package com.everone11.uvccamera.xposed;

import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;

import io.github.libxposed.api.XposedInterface;
import android.content.SharedPreferences;
import android.util.Log;

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
public class VirtualCameraHook {

    private static final String TAG = "VirtualCameraHook";

    /** 虚拟摄像头在 Camera2 API 中对外暴露的 ID */
    private static final String VIRTUAL_CAMERA_ID = "vc0";

    /** Camera1 API 中外部/USB 摄像头的 facing 值（Android 未在公开常量中定义） */
    private static final int CAMERA_FACING_EXTERNAL = 2;


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
     * 通过 xposed.getInvoker(method).setType(ORIGIN) 绕过 Hook 链直接调用原始实现。
     */
    private static volatile Method cachedOpenIntMethod = null;

    /**
     * 缓存 CameraCharacteristics.get(Key) 的反射 Method 对象，用于在 USB 摄像头发现阶段
     * 通过 xposed.getInvoker(method).setType(ORIGIN) 绕过 hookCameraCharacteristicsGet 的
     * LENS_FACING 伪装 Hook，读取真实的镜头朝向值。
     */
    private static volatile Method cachedCcGetMethod = null;

    /** 水平镜像开关：由 apply() 从偏好设置读取。 */
    private static volatile boolean mirrorEnabled = false;

    /** 顺时针旋转90度开关：由 apply() 从偏好设置读取。 */
    private static volatile boolean rotateCW90Enabled = false;

    /**
     * 在目标包加载时安装 Hook。
     *
     * @param xposed       libxposed API 102 接口实例（由 MainModule 传入）
     * @param packageName  目标应用包名
     * @param classLoader  目标应用的 ClassLoader
     * @param prefs        模块 SharedPreferences（由 MainModule 通过 getRemotePreferences() 提供）
     */
    public void apply(XposedInterface xposed, String packageName, ClassLoader classLoader, SharedPreferences prefs) {
        String targetPkg = prefs.getString(PrefManager.KEY_TARGET_PACKAGE, "");

        if (targetPkg != null && !targetPkg.isEmpty()) {
            if (!packageName.equals(targetPkg)) {
                return;
            }
        }

        xposed.log(Log.DEBUG, TAG, "loaded for " + packageName);

        mirrorEnabled = prefs.getBoolean(PrefManager.KEY_MIRROR_HORIZONTAL, false);
        xposed.log(Log.DEBUG, TAG, "mirrorEnabled=" + mirrorEnabled);

        rotateCW90Enabled = prefs.getBoolean(PrefManager.KEY_ROTATE_CW90, false);
        xposed.log(Log.DEBUG, TAG, "rotateCW90Enabled=" + rotateCW90Enabled);

        // 在安装 Hook 之前发现 Camera1 USB 摄像头索引，避免 Hook 自调用
        discoverCamera1UvcIndex(xposed);

        hookCamera2(xposed, classLoader);
        hookCamera1(xposed);
    }

    // -------------------------------------------------------------------------
    // 初始化：发现 USB 摄像头索引
    // -------------------------------------------------------------------------

    /**
     * 在 Hook 安装前，通过未被 Hook 的原始 Camera1 API 枚举外部摄像头索引并缓存。
     */
    private void discoverCamera1UvcIndex(XposedInterface xposed) {
        try {
            int n = Camera.getNumberOfCameras();
            for (int i = 0; i < n; i++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(i, info);
                if (info.facing == CAMERA_FACING_EXTERNAL) {
                    cachedUvcIndex.set(i);
                    xposed.log(Log.DEBUG, TAG, "Camera1 USB camera discovered at index " + i);
                    return;
                }
            }
            xposed.log(Log.DEBUG, TAG, "no Camera1 USB camera found at startup");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "discoverCamera1UvcIndex error: " + t.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Camera2 API hooks
    // -------------------------------------------------------------------------

    private void hookCamera2(XposedInterface xposed, ClassLoader classLoader) {
        hookGetCameraIdList(xposed, classLoader);
        hookGetCameraCharacteristics(xposed, classLoader);
        hookCameraCharacteristicsGet(xposed, classLoader);
        hookOpenCamera(xposed);
    }

    /**
     * Hook CameraManager.getCameraIdList()：
     * 若存在 USB 摄像头，则在列表首位注入虚拟摄像头 ID "vc0"，
     * 同时将真实 USB 摄像头 ID 从列表中移除（对应用不可见）。
     */
    private void hookGetCameraIdList(XposedInterface xposed, ClassLoader classLoader) {
        try {
            Method m = Class.forName("android.hardware.camera2.CameraManager", false, classLoader)
                    .getDeclaredMethod("getCameraIdList");
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                String[] ids = (String[]) chain.proceed();
                if (ids == null) ids = new String[0];

                // 若已包含虚拟摄像头则直接返回，避免重复注入
                for (String id : ids) {
                    if (VIRTUAL_CAMERA_ID.equals(id)) return ids;
                }

                // 懒加载：首次枚举时发现并缓存 USB 摄像头 ID（加锁防止多线程重复发现）
                if (cachedUvcId.get() == null) {
                    synchronized (cachedUvcId) {
                        if (cachedUvcId.get() == null) {
                            // 缓存 CameraCharacteristics.get(Key) 方法，用于绕过
                            // hookCameraCharacteristicsGet 的 LENS_FACING 伪装，
                            // 以读取真实的镜头朝向值。
                            if (cachedCcGetMethod == null) {
                                try {
                                    cachedCcGetMethod = CameraCharacteristics.class
                                            .getMethod("get", CameraCharacteristics.Key.class);
                                } catch (Throwable t) {
                                    xposed.log(Log.DEBUG, TAG,
                                            "failed to cache CameraCharacteristics.get method: "
                                                    + t.getMessage());
                                }
                            }
                            CameraManager mgr = (CameraManager) chain.getThisObject();
                            for (String id : ids) {
                                try {
                                    CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                                    // 使用原始方法绕过 LENS_FACING 伪装 Hook，
                                    // 否则 hookCameraCharacteristicsGet 会将
                                    // LENS_FACING_EXTERNAL 改为 LENS_FACING_FRONT，
                                    // 导致此处永远无法发现 USB 摄像头。
                                    // 若反射失败则回退到正常调用（值可能已被伪装）。
                                    Integer lens;
                                    if (cachedCcGetMethod != null) {
                                        lens = (Integer) xposed
                                                .getInvoker(cachedCcGetMethod)
                                                .setType(XposedInterface.Invoker.Type.ORIGIN)
                                                .invoke(ch, CameraCharacteristics.LENS_FACING);
                                    } else {
                                        lens = ch.get(CameraCharacteristics.LENS_FACING);
                                    }
                                    if (lens != null
                                            && lens == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                                        cachedUvcId.set(id);
                                        xposed.log(Log.DEBUG, TAG,
                                                "Camera2 USB camera discovered: " + id);
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
                if (uvcId == null) return ids; // 无 USB 摄像头，不做处理

                // 构建新列表：虚拟摄像头在首位，隐藏真实 USB 摄像头 ID
                List<String> newList = new ArrayList<>();
                newList.add(VIRTUAL_CAMERA_ID);
                for (String id : ids) {
                    if (!id.equals(uvcId)) newList.add(id);
                }
                String[] result = newList.toArray(new String[0]);
                xposed.log(Log.DEBUG, TAG, "getCameraIdList injected virtual camera "
                        + VIRTUAL_CAMERA_ID + " (backed by " + uvcId
                        + "). list=" + newList);
                return result;
            });
            xposed.log(Log.DEBUG, TAG, "getCameraIdList hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook getCameraIdList: " + t.getMessage());
        }
    }

    /**
     * Hook CameraManager.getCameraCharacteristics(String id)：
     * 将虚拟摄像头 ID "vc0" 透明地重定向到真实 USB 摄像头，使应用获取真实参数。
     * LENS_FACING 的伪装由 hookCameraCharacteristicsGet() 负责。
     */
    private void hookGetCameraCharacteristics(XposedInterface xposed, ClassLoader classLoader) {
        try {
            Method m = Class.forName("android.hardware.camera2.CameraManager", false, classLoader)
                    .getDeclaredMethod("getCameraCharacteristics", String.class);
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                String id = (String) chain.getArg(0);
                String uvcId = cachedUvcId.get();
                if (VIRTUAL_CAMERA_ID.equals(id) && uvcId != null) {
                    xposed.log(Log.DEBUG, TAG, "getCameraCharacteristics("
                            + VIRTUAL_CAMERA_ID + ") -> redirected to USB camera " + uvcId);
                    return chain.proceed(new Object[]{uvcId});
                }
                return chain.proceed();
            });
            xposed.log(Log.DEBUG, TAG, "getCameraCharacteristics hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook getCameraCharacteristics: "
                    + t.getMessage());
        }
    }

    /**
     * Hook CameraCharacteristics.get(Key)：
     * 将 LENS_FACING_EXTERNAL 伪装为 LENS_FACING_FRONT，
     * 使应用将虚拟摄像头（实为 USB 摄像头）视为普通前置摄像头。
     */
    private void hookCameraCharacteristicsGet(XposedInterface xposed, ClassLoader classLoader) {
        try {
            Method m = Class.forName("android.hardware.camera2.CameraCharacteristics",
                    false, classLoader)
                    .getDeclaredMethod("get", CameraCharacteristics.Key.class);
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                Object result = chain.proceed();
                CameraCharacteristics.Key<?> key =
                        (CameraCharacteristics.Key<?>) chain.getArg(0);
                if (CameraCharacteristics.LENS_FACING.equals(key)
                        && result instanceof Integer
                        && (Integer) result == CameraMetadata.LENS_FACING_EXTERNAL) {
                    xposed.log(Log.DEBUG, TAG, "LENS_FACING_EXTERNAL spoofed"
                            + " as LENS_FACING_FRONT for virtual camera");
                    return CameraMetadata.LENS_FACING_FRONT;
                }
                return result;
            });
            xposed.log(Log.DEBUG, TAG, "CameraCharacteristics.get hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook CameraCharacteristics.get: "
                    + t.getMessage());
        }
    }

    /**
     * Hook CameraManager.openCamera(String cameraId, ...)：
     * 当应用请求打开虚拟摄像头 "vc0" 时，将 ID 替换为真实 USB 摄像头 ID，
     * 实现虚拟摄像头 → USB 摄像头的透明转发。
     */
    private void hookOpenCamera(XposedInterface xposed) {
        try {
            Class<?> cameraManagerClass =
                    Class.forName("android.hardware.camera2.CameraManager");
            for (Method method : cameraManagerClass.getDeclaredMethods()) {
                if ("openCamera".equals(method.getName())) {
                    method.setAccessible(true);
                    xposed.hook(method).intercept(chain -> {
                        String requestedId = (String) chain.getArg(0);
                        String uvcId = cachedUvcId.get();
                        if (VIRTUAL_CAMERA_ID.equals(requestedId) && uvcId != null) {
                            xposed.log(Log.DEBUG, TAG, "openCamera(" + VIRTUAL_CAMERA_ID
                                    + ") -> forwarded to USB camera " + uvcId);
                            Object[] args = chain.getArgs().toArray(new Object[0]);
                            args[0] = uvcId;
                            return chain.proceed(args);
                        }
                        return chain.proceed();
                    });
                }
            }
            xposed.log(Log.DEBUG, TAG, "openCamera hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook openCamera: " + t.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Camera1 (legacy) API hooks
    // -------------------------------------------------------------------------

    private void hookCamera1(XposedInterface xposed) {
        hookGetCameraInfo(xposed);
        hookCameraOpenInt(xposed);
        hookCameraOpenNoArg(xposed);
        if (mirrorEnabled) {
            hookCamera1Mirror(xposed);
        }
        if (rotateCW90Enabled) {
            hookCamera1RotateCW90(xposed);
        }
    }

    /**
     * Hook Camera.getCameraInfo(int, CameraInfo)：
     * 将 USB/外部摄像头（facing == 2，CAMERA_FACING_EXTERNAL）报告为前置摄像头，
     * 使旧版 Camera1 API 的应用将其视为虚拟前置摄像头。
     */
    private void hookGetCameraInfo(XposedInterface xposed) {
        try {
            Method m = Camera.class.getDeclaredMethod("getCameraInfo",
                    int.class, Camera.CameraInfo.class);
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                chain.proceed();
                Camera.CameraInfo info = (Camera.CameraInfo) chain.getArg(1);
                if (info.facing == CAMERA_FACING_EXTERNAL) {
                    info.facing = Camera.CameraInfo.CAMERA_FACING_FRONT;
                    xposed.log(Log.DEBUG, TAG, "Camera.getCameraInfo: USB camera"
                            + " spoofed as CAMERA_FACING_FRONT (virtual camera)");
                }
                return null;
            });
            xposed.log(Log.DEBUG, TAG, "Camera.getCameraInfo hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook Camera.getCameraInfo: " + t.getMessage());
        }
    }

    /**
     * Hook Camera.open(int cameraId)：
     * 当应用打开任意摄像头且 USB 摄像头存在时，将请求透明地转发到 USB 摄像头，
     * 实现虚拟摄像头层到真实 USB 摄像头的转发。
     */
    private void hookCameraOpenInt(XposedInterface xposed) {
        try {
            Method m = Camera.class.getDeclaredMethod("open", int.class);
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                try {
                    int requested = (Integer) chain.getArg(0);
                    int uvcIdx = cachedUvcIndex.get();
                    if (uvcIdx >= 0 && uvcIdx != requested) {
                        xposed.log(Log.DEBUG, TAG, "Camera.open(" + requested
                                + ") -> virtual camera forwarded to USB camera index " + uvcIdx);
                        return chain.proceed(new Object[]{uvcIdx});
                    }
                } catch (Throwable t) {
                    xposed.log(Log.DEBUG, TAG, "Camera.open(int) hook error: " + t.getMessage());
                }
                return chain.proceed();
            });
            xposed.log(Log.DEBUG, TAG, "Camera.open(int) hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook Camera.open(int): " + t.getMessage());
        }
    }

    /**
     * Hook Camera.open()（无参）：
     * 默认打开第一个摄像头；若 USB 摄像头存在，则通过调用原始 Camera.open(int)
     * 绕过 Hook 链，直接打开 USB 摄像头索引。
     */
    private void hookCameraOpenNoArg(XposedInterface xposed) {
        try {
            Method m = Camera.class.getDeclaredMethod("open");
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                try {
                    int uvcIdx = cachedUvcIndex.get();
                    if (uvcIdx >= 0) {
                        // 使用原始（未 Hook）Camera.open(int) 方法，避免触发 hookCameraOpenInt
                        if (cachedOpenIntMethod == null) {
                            cachedOpenIntMethod =
                                    Camera.class.getDeclaredMethod("open", int.class);
                            cachedOpenIntMethod.setAccessible(true);
                        }
                        Camera cam = (Camera) xposed.getInvoker(cachedOpenIntMethod)
                                .setType(XposedInterface.Invoker.Type.ORIGIN)
                                .invoke(null, uvcIdx);
                        xposed.log(Log.DEBUG, TAG, "Camera.open()"
                                + " -> virtual camera forwarded to USB camera index " + uvcIdx);
                        return cam;
                    }
                } catch (Throwable t) {
                    xposed.log(Log.DEBUG, TAG, "Camera.open() hook error: " + t.getMessage());
                }
                return chain.proceed();
            });
            xposed.log(Log.DEBUG, TAG, "Camera.open() hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook Camera.open(): " + t.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 水平镜像 hooks（Camera1）
    // -------------------------------------------------------------------------

    /**
     * 当水平镜像开关启用时调用：
     * 通过包装 Camera.PreviewCallback / Camera.setPreviewCallbackWithBuffer 的回调，
     * 在软件层对 NV21 帧数据进行水平翻转。
     */
    private void hookCamera1Mirror(XposedInterface xposed) {
        // 软件层 NV21 水平翻转：包装预览回调
        XposedInterface.Hooker callbackWrapHook = chain -> {
            Camera.PreviewCallback cb = (Camera.PreviewCallback) chain.getArg(0);
            if (cb == null || cb instanceof MirrorPreviewCallback) return chain.proceed();
            try {
                Camera cam = (Camera) chain.getThisObject();
                Camera.Size size = cam.getParameters().getPreviewSize();
                Object[] args = chain.getArgs().toArray(new Object[0]);
                args[0] = new MirrorPreviewCallback(cb, size.width, size.height);
                xposed.log(Log.DEBUG, TAG, "preview callback wrapped for mirror ("
                        + size.width + "x" + size.height + ")");
                return chain.proceed(args);
            } catch (Throwable inner) {
                xposed.log(Log.DEBUG, TAG, "failed to wrap preview callback: "
                        + inner.getMessage());
                return chain.proceed();
            }
        };

        try {
            Method m = Camera.class.getDeclaredMethod("setPreviewCallback",
                    Camera.PreviewCallback.class);
            m.setAccessible(true);
            xposed.hook(m).intercept(callbackWrapHook);
            xposed.log(Log.DEBUG, TAG, "Camera.setPreviewCallback mirror hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG,
                    "failed to hook Camera.setPreviewCallback for mirror: " + t.getMessage());
        }

        try {
            Method m = Camera.class.getDeclaredMethod("setPreviewCallbackWithBuffer",
                    Camera.PreviewCallback.class);
            m.setAccessible(true);
            xposed.hook(m).intercept(callbackWrapHook);
            xposed.log(Log.DEBUG, TAG,
                    "Camera.setPreviewCallbackWithBuffer mirror hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG,
                    "failed to hook Camera.setPreviewCallbackWithBuffer for mirror: "
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

    // -------------------------------------------------------------------------
    // 顺时针旋转90度 hooks（Camera1）
    // -------------------------------------------------------------------------

    /**
     * 当顺时针旋转90度开关启用时调用：
     * 通过包装 Camera.PreviewCallback / Camera.setPreviewCallbackWithBuffer 的回调，
     * 在软件层对 NV21 帧数据进行顺时针旋转90度。
     */
    private void hookCamera1RotateCW90(XposedInterface xposed) {
        XposedInterface.Hooker callbackWrapHook = chain -> {
            Camera.PreviewCallback cb = (Camera.PreviewCallback) chain.getArg(0);
            if (cb == null || cb instanceof RotateCW90PreviewCallback) return chain.proceed();
            try {
                Camera cam = (Camera) chain.getThisObject();
                Camera.Size size = cam.getParameters().getPreviewSize();
                Object[] args = chain.getArgs().toArray(new Object[0]);
                args[0] = new RotateCW90PreviewCallback(cb, size.width, size.height);
                xposed.log(Log.DEBUG, TAG, "preview callback wrapped for rotateCW90 ("
                        + size.width + "x" + size.height + ")");
                return chain.proceed(args);
            } catch (Throwable inner) {
                xposed.log(Log.DEBUG, TAG,
                        "failed to wrap preview callback for rotateCW90: " + inner.getMessage());
                return chain.proceed();
            }
        };

        try {
            Method m = Camera.class.getDeclaredMethod("setPreviewCallback",
                    Camera.PreviewCallback.class);
            m.setAccessible(true);
            xposed.hook(m).intercept(callbackWrapHook);
            xposed.log(Log.DEBUG, TAG, "Camera.setPreviewCallback rotateCW90 hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG,
                    "failed to hook Camera.setPreviewCallback for rotateCW90: " + t.getMessage());
        }

        try {
            Method m = Camera.class.getDeclaredMethod("setPreviewCallbackWithBuffer",
                    Camera.PreviewCallback.class);
            m.setAccessible(true);
            xposed.hook(m).intercept(callbackWrapHook);
            xposed.log(Log.DEBUG, TAG,
                    "Camera.setPreviewCallbackWithBuffer rotateCW90 hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG,
                    "failed to hook Camera.setPreviewCallbackWithBuffer for rotateCW90: "
                            + t.getMessage());
        }
    }

    /**
     * 对 NV21 格式帧数据执行顺时针旋转90度。
     * 输入帧尺寸为 width×height，输出帧尺寸为 height×width。
     * 返回旋转后的新字节数组（输出宽=原高，输出高=原宽）。
     */
    private static byte[] rotateNV21CW90(byte[] data, int width, int height) {
        int frameSize = width * height;
        byte[] output = new byte[data.length];

        // 旋转 Y 平面：顺时针90度，输出尺寸 height×width
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                // 顺时针90度映射：输出[col][height-1-row] = 输入[row][col]
                output[col * height + (height - 1 - row)] = data[row * width + col];
            }
        }

        // 旋转 UV 平面（NV21 交错 VU 对），输入UV尺寸 width×(height/2)，输出 height×(width/2)
        int uvOffset = frameSize;
        int uvWidth  = width;
        int uvHeight = height / 2;
        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col += 2) {
                byte vByte = data[uvOffset + row * uvWidth + col];
                byte uByte = data[uvOffset + row * uvWidth + col + 1];
                // 对UV坐标做同样的顺时针旋转，保持VU对完整
                int outCol = col / 2;            // 输出UV列（UV水平降采样）
                int outRow = uvHeight - 1 - row; // 输出UV行
                int outUVOffset = frameSize + outCol * height * 2 + outRow * 2;
                if (outUVOffset + 1 < output.length) {
                    output[outUVOffset]     = vByte;
                    output[outUVOffset + 1] = uByte;
                }
            }
        }

        return output;
    }

    /**
     * Camera1 预览回调代理：在调用原始回调前，对 NV21 帧数据进行顺时针旋转90度。
     * 注意：旋转后宽高互换，回调中传入的 Camera 对象仍为原始对象。
     */
    private static final class RotateCW90PreviewCallback implements Camera.PreviewCallback {
        private final Camera.PreviewCallback delegate;
        private final int width;
        private final int height;

        RotateCW90PreviewCallback(Camera.PreviewCallback delegate, int width, int height) {
            this.delegate = delegate;
            this.width    = width;
            this.height   = height;
        }

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            if (data != null && data.length >= (width * height * 3) / 2) {
                data = rotateNV21CW90(data, width, height);
            }
            delegate.onPreviewFrame(data, camera);
        }
    }
}
