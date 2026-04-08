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

import java.util.ArrayList;
import java.util.List;

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
    static final String VIRTUAL_CAMERA_ID = "vc0";

    private static final String MODULE_PACKAGE = "com.everone11.uvccamera.xposed";

    /**
     * 缓存真实 USB 摄像头的 Camera2 ID。
     * 首次通过 getCameraIdList 发现后写入；后续直接读取，避免重复枚举。
     */
    private static volatile String cachedUvcId = null;

    /**
     * 缓存真实 USB 摄像头的 Camera1 索引（-1 表示未发现）。
     * 在安装 Hook 之前通过原始 API 发现并缓存，避免 Hook 链递归。
     */
    private static volatile int cachedUvcIndex = -1;

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
                if (info.facing == 2 /* CAMERA_FACING_EXTERNAL */) {
                    cachedUvcIndex = i;
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
                            for (String id : ids) {
                                if (VIRTUAL_CAMERA_ID.equals(id)) return;
                            }

                            // 懒加载：首次枚举时发现并缓存 USB 摄像头 ID
                            if (cachedUvcId == null) {
                                CameraManager mgr = (CameraManager) param.thisObject;
                                for (String id : ids) {
                                    try {
                                        CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                                        Integer lens = ch.get(CameraCharacteristics.LENS_FACING);
                                        if (lens != null
                                                && lens == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                                            cachedUvcId = id;
                                            XposedBridge.log(TAG + ": Camera2 USB camera discovered: "
                                                    + id);
                                            break;
                                        }
                                    } catch (Throwable t) {
                                        // 单个摄像头查询失败，跳过
                                    }
                                }
                            }

                            if (cachedUvcId == null) return; // 无 USB 摄像头，不做处理

                            // 构建新列表：虚拟摄像头在首位，隐藏真实 USB 摄像头 ID
                            List<String> newList = new ArrayList<>();
                            newList.add(VIRTUAL_CAMERA_ID);
                            for (String id : ids) {
                                if (!id.equals(cachedUvcId)) {
                                    newList.add(id);
                                }
                            }
                            param.setResult(newList.toArray(new String[0]));
                            XposedBridge.log(TAG + ": getCameraIdList injected virtual camera "
                                    + VIRTUAL_CAMERA_ID + " (backed by " + cachedUvcId
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
                        if (VIRTUAL_CAMERA_ID.equals(id) && cachedUvcId != null) {
                            param.args[0] = cachedUvcId;
                            XposedBridge.log(TAG + ": getCameraCharacteristics("
                                    + VIRTUAL_CAMERA_ID + ") -> redirected to USB camera "
                                    + cachedUvcId);
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
                            if (VIRTUAL_CAMERA_ID.equals(requestedId) && cachedUvcId != null) {
                                param.args[0] = cachedUvcId;
                                XposedBridge.log(TAG + ": openCamera(" + VIRTUAL_CAMERA_ID
                                        + ") -> forwarded to USB camera " + cachedUvcId);
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
                        if (info.facing == 2 /* CAMERA_FACING_EXTERNAL */) {
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
                            if (cachedUvcIndex >= 0 && cachedUvcIndex != requested) {
                                param.args[0] = cachedUvcIndex;
                                XposedBridge.log(TAG + ": Camera.open(" + requested
                                        + ") -> virtual camera forwarded to USB camera index "
                                        + cachedUvcIndex);
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
     * 默认打开第一个摄像头；若 USB 摄像头存在，则转发到 USB 摄像头索引。
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
                            if (cachedUvcIndex >= 0) {
                                // 直接打开 USB 摄像头并设置为结果，跳过默认的 open()
                                Camera cam = Camera.open(cachedUvcIndex);
                                param.setResult(cam);
                                XposedBridge.log(TAG + ": Camera.open()"
                                        + " -> virtual camera forwarded to USB camera index "
                                        + cachedUvcIndex);
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
}
