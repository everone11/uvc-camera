package com.everone11.uvccamera.xposed;

import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 虚拟摄像头 Hook
 *
 * 在摄像头列表中注入虚拟摄像头（Camera2 ID "vc0"，伪装为前置摄像头）。
 * 应用打开虚拟摄像头时，模块自动将请求转发到真实的 USB/UVC 摄像头。
 *
 * 数据流：应用 → 虚拟摄像头(vc0 / Camera1 FRONT index) → USB/UVC 摄像头
 *
 * Camera2 hooks:
 *   - CameraManager.getCameraIdList()       注入虚拟 ID "vc0"，隐藏真实 USB ID
 *   - CameraManager.getCameraCharacteristics() 将 "vc0" 重定向到真实 USB 摄像头
 *   - CameraManager.openCamera()            将 "vc0" 重定向到真实 USB 摄像头
 *   - CameraCharacteristics.get(Key)        LENS_FACING_EXTERNAL → LENS_FACING_FRONT
 *
 * Camera1 hooks:
 *   - Camera.getCameraInfo()                USB 外部摄像头伪装为 CAMERA_FACING_FRONT
 *   - Camera.open(int) / Camera.open()      将前置摄像头请求重定向到 USB 摄像头
 */
public class VirtualCameraHook implements IXposedHookLoadPackage {

    private static final String TAG = "VirtualCameraHook";

    /** 虚拟摄像头在 Camera2 API 中对外暴露的 ID */
    private static final String VIRTUAL_CAMERA_ID = "vc0";

    /** Camera1 API 中外部/USB 摄像头的 facing 值（Android 未在公开常量中定义） */
    private static final int CAMERA_FACING_EXTERNAL = 2;

    private static final String MODULE_PACKAGE = "com.everone11.uvccamera.xposed";

    /**
     * 缓存真实 USB 摄像头的 Camera2 ID。
     * 首次通过 getCameraIdList 发现后写入；null 表示尚未发现。
     */
    private static final AtomicReference<String> cachedUvcId = new AtomicReference<>(null);

    /**
     * 缓存真实 USB 摄像头的 Camera1 索引（-1 表示未发现）。
     * 在安装 Hook 之前通过原始 API 发现并缓存，避免 Hook 链递归。
     */
    private static final AtomicInteger cachedUvcIndex = new AtomicInteger(-1);

    /**
     * 缓存 Camera.open(int) 的反射 Method 对象，用于在 Camera.open() 无参 Hook 中
     * 通过 XposedBridge.invokeOriginalMethod 绕过 Hook 链直接调用原始实现。
     */
    private static volatile Method cachedOpenIntMethod = null;

    /**
     * 缓存 CameraCharacteristics.get(Key) 的反射 Method 对象，用于在 USB 摄像头发现阶段
     * 通过 XposedBridge.invokeOriginalMethod 绕过 hookCameraCharacteristicsGet 的
     * LENS_FACING 伪装 Hook，读取真实的镜头朝向值。
     */
    private static volatile Method cachedCcGetMethod = null;

    /** 水平镜像开关：由 handleLoadPackage 从偏好设置读取。 */
    private static volatile boolean mirrorEnabled = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XSharedPreferences prefs = new XSharedPreferences(MODULE_PACKAGE, PrefManager.PREF_NAME);
        prefs.reload();
        String targetPkg = prefs.getString(PrefManager.KEY_TARGET_PACKAGE, "");

        if (targetPkg != null && !targetPkg.isEmpty()) {
            if (!lpparam.packageName.equals(targetPkg)) {
                return;
            }
        }

        XposedBridge.log(TAG + ": loaded for " + lpparam.packageName);

        mirrorEnabled = prefs.getBoolean(PrefManager.KEY_MIRROR_HORIZONTAL, false);
        XposedBridge.log(TAG + ": mirrorEnabled=" + mirrorEnabled);

        // 在安装 Hook 之前发现 Camera1 USB 摄像头索引，避免 Hook 自调用
        discoverCamera1UvcIndex();

        hookCamera2(lpparam);
        hookCamera1();
    }

    // -------------------------------------------------------------------------
    // 初始化：发现 USB 摄像头索引
    // -------------------------------------------------------------------------

    /**
     * 在 Hook 安装前，通过未被 Hook 的原始 Camera1 API 枚举外部摄像头索引并缓存。
     */
    private void discoverCamera1UvcIndex() {
        try {
            int n = Camera.getNumberOfCameras();
            for (int i = 0; i < n; i++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(i, info);
                if (info.facing == CAMERA_FACING_EXTERNAL) {
                    cachedUvcIndex.set(i);
                    XposedBridge.log(TAG + ": Camera1 USB camera discovered at index " + i);
                    return;
                }
            }
            XposedBridge.log(TAG + ": no Camera1 USB camera found at startup");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": discoverCamera1UvcIndex error: " + t.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Camera2 API hooks
    // -------------------------------------------------------------------------

    private void hookCamera2(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookGetCameraIdList(lpparam);
        hookGetCameraCharacteristics(lpparam);
        hookCameraCharacteristicsGet(lpparam);
        hookOpenCamera();
    }

    /**
     * Hook CameraManager.getCameraIdList()：
     * 若存在 USB 摄像头，则在列表首位注入虚拟摄像头 ID "vc0"，
     * 同时将真实 USB 摄像头 ID 从列表中移除（对应用不可见）。
     */
    private void hookGetCameraIdList(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CameraManager",
                lpparam.classLoader,
                "getCameraIdList",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            String[] ids = (String[]) param.getResult();
                            if (ids == null) ids = new String[0];

                            // 若已包含虚拟摄像头则直接返回，避免重复注入
                            boolean alreadyInjected = false;
                            for (String id : ids) {
                                if (VIRTUAL_CAMERA_ID.equals(id)) {
                                    alreadyInjected = true;
                                    break;
                                }
                            }
                            if (alreadyInjected) return;

                            // 懒加载：首次枚举时发现并缓存 USB 摄像头 ID（加锁防止多线程重复发现）
                            if (cachedUvcId.get() == null) {
                                synchronized (cachedUvcId) {
                                    if (cachedUvcId.get() == null) {
                                        // 缓存 CameraCharacteristics.get(Key) 方法，用于绕过
                                        // hookCameraCharacteristicsGet 的 LENS_FACING 伪装，
                                        // 以读取真实的镜头朝向值。
                                        if (cachedCcGetMethod == null) {
                                            try {
                                                cachedCcGetMethod =
                                                        CameraCharacteristics.class.getMethod(
                                                                "get",
                                                                CameraCharacteristics.Key.class);
                                            } catch (Throwable t) {
                                                XposedBridge.log(TAG
                                                        + ": failed to cache CameraCharacteristics"
                                                        + ".get method: " + t.getMessage());
                                            }
                                        }
                                        CameraManager mgr = (CameraManager) param.thisObject;
                                        for (String id : ids) {
                                            try {
                                                CameraCharacteristics ch =
                                                        mgr.getCameraCharacteristics(id);
                                                // 使用原始方法绕过 LENS_FACING 伪装 Hook，
                                                // 否则 hookCameraCharacteristicsGet 会将
                                                // LENS_FACING_EXTERNAL 改为 LENS_FACING_FRONT，
                                                // 导致此处永远无法发现 USB 摄像头。
                                                // 若反射失败则回退到正常调用（值可能已被伪装）。
                                                Integer lens;
                                                if (cachedCcGetMethod != null) {
                                                    lens = (Integer)
                                                            XposedBridge.invokeOriginalMethod(
                                                                    cachedCcGetMethod, ch,
                                                                    new Object[]{
                                                                            CameraCharacteristics
                                                                                    .LENS_FACING});
                                                } else {
                                                    lens = ch.get(
                                                            CameraCharacteristics.LENS_FACING);
                                                }
                                                if (lens != null
                                                        && lens == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                                                    cachedUvcId.set(id);
                                                    XposedBridge.log(TAG
                                                            + ": Camera2 USB camera discovered: "
                                                            + id);
                                                    break;
                                                }
                                            } catch (Throwable t) {
                                                // 单个摄像头查询失败，跳过
                                            }
                                        }
                                    }
                                }
                            }

                            String uvcId = cachedUvcId.get();
                            if (uvcId == null) return; // 无 USB 摄像头，不做处理

                            // 构建新列表：虚拟摄像头在首位，隐藏真实 USB 摄像头 ID
                            List<String> newList = new ArrayList<>();
                            newList.add(VIRTUAL_CAMERA_ID);
                            for (String id : ids) {
                                if (!id.equals(uvcId)) {
                                    newList.add(id);
                                }
                            }
                            param.setResult(newList.toArray(new String[0]));
                            XposedBridge.log(TAG + ": getCameraIdList injected virtual camera "
                                    + VIRTUAL_CAMERA_ID + " (backed by " + uvcId
                                    + "). list=" + newList);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": getCameraIdList hook error: "
                                    + t.getMessage());
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": getCameraIdList hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook getCameraIdList: " + t.getMessage());
        }
    }

    /**
     * Hook CameraManager.getCameraCharacteristics(String id)：
     * 将虚拟摄像头 ID "vc0" 透明地重定向到真实 USB 摄像头，使应用获取真实参数。
     * LENS_FACING 的伪装由 hookCameraCharacteristicsGet() 负责。
     */
    private void hookGetCameraCharacteristics(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CameraManager",
                lpparam.classLoader,
                "getCameraCharacteristics",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String id = (String) param.args[0];
                        String uvcId = cachedUvcId.get();
                        if (VIRTUAL_CAMERA_ID.equals(id) && uvcId != null) {
                            param.args[0] = uvcId;
                            XposedBridge.log(TAG + ": getCameraCharacteristics("
                                    + VIRTUAL_CAMERA_ID + ") -> redirected to USB camera "
                                    + uvcId);
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": getCameraCharacteristics hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook getCameraCharacteristics: "
                    + t.getMessage());
        }
    }

    /**
     * Hook CameraCharacteristics.get(Key)：
     * 将 LENS_FACING_EXTERNAL 伪装为 LENS_FACING_FRONT，
     * 使应用将虚拟摄像头（实为 USB 摄像头）视为普通前置摄像头。
     */
    private void hookCameraCharacteristicsGet(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CameraCharacteristics",
                lpparam.classLoader,
                "get",
                CameraCharacteristics.Key.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        CameraCharacteristics.Key<?> key =
                                (CameraCharacteristics.Key<?>) param.args[0];
                        if (CameraCharacteristics.LENS_FACING.equals(key)) {
                            Object result = param.getResult();
                            if (result instanceof Integer
                                    && (Integer) result == CameraMetadata.LENS_FACING_EXTERNAL) {
                                param.setResult(CameraMetadata.LENS_FACING_FRONT);
                                XposedBridge.log(TAG + ": LENS_FACING_EXTERNAL spoofed"
                                        + " as LENS_FACING_FRONT for virtual camera");
                            }
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": CameraCharacteristics.get hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook CameraCharacteristics.get: "
                    + t.getMessage());
        }
    }

    /**
     * Hook CameraManager.openCamera(String cameraId, ...)：
     * 当应用请求打开虚拟摄像头 "vc0" 时，将 ID 替换为真实 USB 摄像头 ID，
     * 实现虚拟摄像头 → USB 摄像头的透明转发。
     */
    private void hookOpenCamera() {
        try {
            Class<?> cameraManagerClass =
                    Class.forName("android.hardware.camera2.CameraManager");
            for (java.lang.reflect.Method method : cameraManagerClass.getDeclaredMethods()) {
                if ("openCamera".equals(method.getName())) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String requestedId = (String) param.args[0];
                            String uvcId = cachedUvcId.get();
                            if (VIRTUAL_CAMERA_ID.equals(requestedId) && uvcId != null) {
                                param.args[0] = uvcId;
                                XposedBridge.log(TAG + ": openCamera(" + VIRTUAL_CAMERA_ID
                                        + ") -> forwarded to USB camera " + uvcId);
                            }
                        }
                    });
                }
            }
            XposedBridge.log(TAG + ": openCamera hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook openCamera: " + t.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Camera1 (legacy) API hooks
    // -------------------------------------------------------------------------

    private void hookCamera1() {
        hookGetCameraInfo();
        hookCameraOpenInt();
        hookCameraOpenNoArg();
        if (mirrorEnabled) {
            hookCamera1Mirror();
        }
    }

    /**
     * Hook Camera.getCameraInfo(int, CameraInfo)：
     * 将 USB/外部摄像头（facing == 2，CAMERA_FACING_EXTERNAL）报告为前置摄像头，
     * 使旧版 Camera1 API 的应用将其视为虚拟前置摄像头。
     */
    private void hookGetCameraInfo() {
        try {
            XposedHelpers.findAndHookMethod(
                Camera.class,
                "getCameraInfo",
                int.class,
                Camera.CameraInfo.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Camera.CameraInfo info = (Camera.CameraInfo) param.args[1];
                        if (info.facing == CAMERA_FACING_EXTERNAL) {
                            info.facing = Camera.CameraInfo.CAMERA_FACING_FRONT;
                            XposedBridge.log(TAG + ": Camera.getCameraInfo: USB camera"
                                    + " spoofed as CAMERA_FACING_FRONT (virtual camera)");
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": Camera.getCameraInfo hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook Camera.getCameraInfo: " + t.getMessage());
        }
    }

    /**
     * Hook Camera.open(int cameraId)：
     * 当应用打开任意摄像头且 USB 摄像头存在时，将请求透明地转发到 USB 摄像头，
     * 实现虚拟摄像头层到真实 USB 摄像头的转发。
     * 注意：与 Camera2 的 "vc0" 类似，Camera1 中所有摄像头请求均优先转发到 USB 摄像头，
     * 与现有 Module.java 的行为保持一致（始终优选 USB 摄像头）。
     */
    private void hookCameraOpenInt() {
        try {
            XposedHelpers.findAndHookMethod(
                Camera.class,
                "open",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            int requested = (Integer) param.args[0];
                            int uvcIdx = cachedUvcIndex.get();
                            if (uvcIdx >= 0 && uvcIdx != requested) {
                                param.args[0] = uvcIdx;
                                XposedBridge.log(TAG + ": Camera.open(" + requested
                                        + ") -> virtual camera forwarded to USB camera index "
                                        + uvcIdx);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": Camera.open(int) hook error: "
                                    + t.getMessage());
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": Camera.open(int) hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook Camera.open(int): " + t.getMessage());
        }
    }

    /**
     * Hook Camera.open()（无参）：
     * 默认打开第一个摄像头；若 USB 摄像头存在，则通过调用原始 Camera.open(int)
     * 绕过 Hook 链，直接打开 USB 摄像头索引。
     */
    private void hookCameraOpenNoArg() {
        try {
            XposedHelpers.findAndHookMethod(
                Camera.class,
                "open",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            int uvcIdx = cachedUvcIndex.get();
                            if (uvcIdx >= 0) {
                                // 使用原始（未 Hook）Camera.open(int) 方法，避免触发 hookCameraOpenInt
                                if (cachedOpenIntMethod == null) {
                                    cachedOpenIntMethod =
                                            Camera.class.getDeclaredMethod("open", int.class);
                                }
                                Camera cam = (Camera) XposedBridge.invokeOriginalMethod(
                                        cachedOpenIntMethod, null, new Object[]{uvcIdx});
                                param.setResult(cam);
                                XposedBridge.log(TAG + ": Camera.open()"
                                        + " -> virtual camera forwarded to USB camera index "
                                        + uvcIdx);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": Camera.open() hook error: "
                                    + t.getMessage());
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": Camera.open() hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook Camera.open(): " + t.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 水平镜像 hooks（Camera1）
    // -------------------------------------------------------------------------

    /**
     * 当水平镜像开关启用时调用：
     * 通过包装 Camera.PreviewCallback / Camera.setPreviewCallbackWithBuffer 的回调，
     * 在软件层对 NV21 帧数据进行水平翻转。
     * 注意：未使用 Camera.setParameters("mirror","true") HAL 级提示，以避免在支持该参数的
     * OEM 设备上出现 HAL + 软件双重翻转相互抵消的问题。
     */
    private void hookCamera1Mirror() {
        // 软件层 NV21 水平翻转：包装预览回调
        final XC_MethodHook callbackWrapHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Camera.PreviewCallback cb = (Camera.PreviewCallback) param.args[0];
                if (cb == null || cb instanceof MirrorPreviewCallback) return;
                try {
                    Camera cam = (Camera) param.thisObject;
                    Camera.Size size = cam.getParameters().getPreviewSize();
                    param.args[0] = new MirrorPreviewCallback(cb, size.width, size.height);
                    XposedBridge.log(TAG + ": preview callback wrapped for mirror ("
                            + size.width + "x" + size.height + ")");
                } catch (Throwable inner) {
                    XposedBridge.log(TAG + ": failed to wrap preview callback: "
                            + inner.getMessage());
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(Camera.class, "setPreviewCallback",
                    Camera.PreviewCallback.class, callbackWrapHook);
            XposedBridge.log(TAG + ": Camera.setPreviewCallback mirror hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook Camera.setPreviewCallback for mirror: "
                    + t.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(Camera.class, "setPreviewCallbackWithBuffer",
                    Camera.PreviewCallback.class, callbackWrapHook);
            XposedBridge.log(TAG + ": Camera.setPreviewCallbackWithBuffer mirror hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG
                    + ": failed to hook Camera.setPreviewCallbackWithBuffer for mirror: "
                    + t.getMessage());
        }
    }

    /**
     * 对 NV21/NV12 格式帧数据执行原地水平翻转。
     * Y 平面：逐行反转字节顺序。
     * UV 平面（交错 VU 对）：逐行以 2 字节为单位反转，保持色度采样对的完整性。
     */
    private static void flipHorizontalNV21InPlace(byte[] data, int width, int height) {
        // 翻转 Y 平面（每行独立翻转）
        for (int row = 0; row < height; row++) {
            int left  = row * width;
            int right = left + width - 1;
            while (left < right) {
                byte tmp   = data[left];
                data[left]  = data[right];
                data[right] = tmp;
                left++;
                right--;
            }
        }
        // 翻转 UV 平面（以 2 字节 VU 对为单位翻转，保持色度采样对不拆散）
        int uvStart = width * height;
        for (int row = 0; row < height / 2; row++) {
            int left  = uvStart + row * width;
            int right = left + width - 2; // 最后一个完整 VU 对的起始位置
            while (left < right) {
                byte b0 = data[left];
                byte b1 = data[left  + 1];
                data[left]      = data[right];
                data[left  + 1] = data[right + 1];
                data[right]     = b0;
                data[right + 1] = b1;
                left  += 2;
                right -= 2;
            }
        }
    }

    /**
     * Camera1 预览回调代理：在调用原始回调前，对 NV21 帧数据进行水平翻转。
     */
    private static final class MirrorPreviewCallback implements Camera.PreviewCallback {
        private final Camera.PreviewCallback delegate;
        private final int width;
        private final int height;

        MirrorPreviewCallback(Camera.PreviewCallback delegate, int width, int height) {
            this.delegate = delegate;
            this.width    = width;
            this.height   = height;
        }

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            if (data != null && data.length >= (width * height * 3) / 2) {
                flipHorizontalNV21InPlace(data, width, height);
            }
            delegate.onPreviewFrame(data, camera);
        }
    }
}
