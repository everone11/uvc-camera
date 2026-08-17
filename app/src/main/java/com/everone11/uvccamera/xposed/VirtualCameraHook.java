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
    private static final String VIRTUAL_CAMERA_ID = "vc0";
    private static final int CAMERA_FACING_EXTERNAL = 2;
    private static final AtomicReference<String> cachedUvcId = new AtomicReference<>(null);
    private static final AtomicInteger cachedUvcIndex = new AtomicInteger(-1);
    private static volatile Method cachedOpenIntMethod = null;
    private static volatile Method cachedCcGetMethod = null;
    private static volatile boolean mirrorEnabled = false;
    private static volatile boolean rotateCW90Enabled = false;

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

        discoverCamera1UvcIndex(xposed);
        hookCamera2(xposed, classLoader);
        hookCamera1(xposed);
    }

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

    private void hookCamera2(XposedInterface xposed, ClassLoader classLoader) {
        hookGetCameraIdList(xposed, classLoader);
        hookGetCameraCharacteristics(xposed, classLoader);
        hookCameraCharacteristicsGet(xposed, classLoader);
        hookOpenCamera(xposed);
    }

    private void hookGetCameraIdList(XposedInterface xposed, ClassLoader classLoader) {
        try {
            Method m = Class.forName("android.hardware.camera2.CameraManager", false, classLoader)
                    .getDeclaredMethod("getCameraIdList");
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                String[] ids = (String[]) chain.proceed();
                if (ids == null) ids = new String[0];

                for (String id : ids) {
                    if (VIRTUAL_CAMERA_ID.equals(id)) return ids;
                }

                if (cachedUvcId.get() == null) {
                    synchronized (cachedUvcId) {
                        if (cachedUvcId.get() == null) {
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
                                }
                            }
                        }
                    }
                }

                String uvcId = cachedUvcId.get();
                if (uvcId == null) return ids;

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

    private void hookCameraOpenNoArg(XposedInterface xposed) {
        try {
            Method m = Camera.class.getDeclaredMethod("open");
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                try {
                    int uvcIdx = cachedUvcIndex.get();
                    if (uvcIdx >= 0) {
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

    private void hookCamera1Mirror(XposedInterface xposed) {
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

    private static void flipHorizontalNV21InPlace(byte[] data, int width, int height) {
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
        int uvStart = width * height;
        for (int row = 0; row < height / 2; row++) {
            int left  = uvStart + row * width;
            int right = left + width - 2;
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

    private static byte[] rotateNV21CW90(byte[] data, int width, int height) {
        int frameSize = width * height;
        byte[] output = new byte[data.length];

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                output[col * height + (height - 1 - row)] = data[row * width + col];
            }
        }

        int uvOffset = frameSize;
        int uvWidth  = width;
        int uvHeight = height / 2;
        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col += 2) {
                byte vByte = data[uvOffset + row * uvWidth + col];
                byte uByte = data[uvOffset + row * uvWidth + col + 1];
                int outCol = col / 2;
                int outRow = uvHeight - 1 - row;
                int outUVOffset = frameSize + outCol * height * 2 + outRow * 2;
                if (outUVOffset + 1 < output.length) {
                    output[outUVOffset]     = vByte;
                    output[outUVOffset + 1] = uByte;
                }
            }
        }

        return output;
    }

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
