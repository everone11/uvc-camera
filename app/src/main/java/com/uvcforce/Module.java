package com.uvcforce;

import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * LSPosed module: hook Camera2 and legacy Camera API to prefer external cameras.
 * 由 MainModule 统一调度，通过 apply() 而非 IXposedHookLoadPackage 接口触发。
 */
public class Module {
    private static final String TAG = "uvcforce";

    // Empty = global effect. Set to a package name like "com.example.ttjump" to restrict.
    private static final String TARGET_PACKAGE = "";

    /**
     * 在目标包加载时安装 Hook。
     *
     * @param xposed      libxposed API 102 接口实例（由 MainModule 传入）
     * @param packageName 目标应用包名
     * @param classLoader 目标应用的 ClassLoader
     */
    public void apply(XposedInterface xposed, String packageName, ClassLoader classLoader) {
        if (TARGET_PACKAGE != null && TARGET_PACKAGE.length() > 0) {
            if (!TARGET_PACKAGE.equals(packageName)) {
                return;
            }
        }

        xposed.log(Log.DEBUG, TAG, "loaded for " + packageName);

        // Hook Camera2.getCameraIdList
        try {
            Method getCameraIdList = Class.forName(
                    "android.hardware.camera2.CameraManager", false, classLoader)
                    .getDeclaredMethod("getCameraIdList");
            getCameraIdList.setAccessible(true);
            xposed.hook(getCameraIdList).intercept(chain -> {
                String[] ids = (String[]) chain.proceed();
                if (ids == null || ids.length <= 1) return ids;

                CameraManager mgr = (CameraManager) chain.getThisObject();
                List<String> external = new ArrayList<>();
                List<String> others = new ArrayList<>();

                for (String id : ids) {
                    boolean isExternal = false;
                    try {
                        CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                        Integer lens = ch.get(CameraCharacteristics.LENS_FACING);
                        if (lens != null && lens == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                            isExternal = true;
                        }
                    } catch (Throwable t) {
                        // ignore per-camera failures
                    }
                    if (isExternal) external.add(id);
                    else others.add(id);
                }

                if (!external.isEmpty()) {
                    List<String> reordered = new ArrayList<>();
                    reordered.addAll(external);
                    reordered.addAll(others);
                    xposed.log(Log.DEBUG, TAG, "reordered Camera2 list, external first: " + reordered);
                    return reordered.toArray(new String[0]);
                }
                return ids;
            });
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook CameraManager.getCameraIdList: " + t.getMessage());
        }

        // Hook legacy Camera API open() and open(int)
        try {
            Class<?> cameraClass = Camera.class;

            Method openInt = cameraClass.getDeclaredMethod("open", int.class);
            openInt.setAccessible(true);
            xposed.hook(openInt).intercept(chain -> {
                int requested = (Integer) chain.getArg(0);
                int externalId = findExternalCameraOldAPI();
                if (externalId >= 0 && externalId != requested) {
                    xposed.log(Log.DEBUG, TAG, "remapped Camera.open(" + requested + ") -> open(" + externalId + ")");
                    return chain.proceed(new Object[]{externalId});
                }
                return chain.proceed();
            });

            Method openNoArg = cameraClass.getDeclaredMethod("open");
            openNoArg.setAccessible(true);
            xposed.hook(openNoArg).intercept(chain -> {
                int externalId = findExternalCameraOldAPI();
                if (externalId >= 0) {
                    Camera cam = Camera.open(externalId);
                    xposed.log(Log.DEBUG, TAG, "remapped Camera.open() -> open(" + externalId + ")");
                    return cam;
                }
                return chain.proceed();
            });

        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook old Camera API: " + t.getMessage());
        }

        // Hook ByteRTC Camera1Enumerator to clear static cache and force re-enumeration
        try {
            final Class<?> enumClass = Class.forName(
                    "com.ss.bytertc.base.media.camera.Camera1Enumerator", false, classLoader);
            Method getSupportedFormats = enumClass.getDeclaredMethod("getSupportedFormats", int.class);
            getSupportedFormats.setAccessible(true);
            xposed.hook(getSupportedFormats).intercept(chain -> {
                Integer camIndex = (Integer) chain.getArg(0);
                xposed.log(Log.DEBUG, TAG, "Camera1Enumerator.getSupportedFormats called for index: " + camIndex);
                try {
                    java.lang.reflect.Field f = enumClass.getDeclaredField("cachedSupportedFormats");
                    f.setAccessible(true);
                    f.set(null, null);
                    xposed.log(Log.DEBUG, TAG, "cleared cachedSupportedFormats");
                } catch (Throwable t) {
                    xposed.log(Log.DEBUG, TAG, "failed to clear cachedSupportedFormats: " + t);
                }
                @SuppressWarnings("unchecked")
                List<?> res = (List<?>) chain.proceed();
                xposed.log(Log.DEBUG, TAG, "Camera1Enumerator.getSupportedFormats returned size: "
                        + (res == null ? "null" : res.size()));
                return res;
            });
        } catch (ClassNotFoundException e) {
            // not a ByteRTC app; skip silently
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "Camera1Enumerator not found or hook failed: " + t);
        }

        // Hook Camera.Parameters.getSupportedPreviewFpsRange to return dummy data if null/empty
        try {
            Method m = Class.forName("android.hardware.Camera$Parameters", false, classLoader)
                    .getDeclaredMethod("getSupportedPreviewFpsRange");
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                @SuppressWarnings("unchecked")
                List<int[]> result = (List<int[]>) chain.proceed();
                if (result == null || result.isEmpty()) {
                    List<int[]> fake = new ArrayList<>();
                    fake.add(new int[]{15000, 30000});
                    xposed.log(Log.DEBUG, TAG, "faked getSupportedPreviewFpsRange");
                    return fake;
                }
                return result;
            });
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook getSupportedPreviewFpsRange: " + t.getMessage());
        }

        // Hook Camera.Parameters.getSupportedPictureSizes to fallback to preview sizes if null/empty
        try {
            Method m = Class.forName("android.hardware.Camera$Parameters", false, classLoader)
                    .getDeclaredMethod("getSupportedPictureSizes");
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                @SuppressWarnings("unchecked")
                List<?> result = (List<?>) chain.proceed();
                if (result == null || result.isEmpty()) {
                    Camera.Parameters params = (Camera.Parameters) chain.getThisObject();
                    List<Camera.Size> previewSizes = params.getSupportedPreviewSizes();
                    if (previewSizes != null && !previewSizes.isEmpty()) {
                        xposed.log(Log.DEBUG, TAG, "faked getSupportedPictureSizes with preview sizes");
                        return previewSizes;
                    } else {
                        xposed.log(Log.DEBUG, TAG, "getSupportedPictureSizes: no preview sizes available either");
                    }
                }
                return result;
            });
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook getSupportedPictureSizes: " + t.getMessage());
        }

        // Hook Camera.setParameters to swallow exceptions from UVC cameras
        try {
            Method m = Camera.class.getDeclaredMethod("setParameters", Camera.Parameters.class);
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                try {
                    chain.proceed();
                } catch (Throwable t) {
                    xposed.log(Log.DEBUG, TAG, "ignored Camera.setParameters exception: " + t.getMessage());
                }
                return null;
            });
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook Camera.setParameters: " + t.getMessage());
        }

        // Hook CameraManager.openCamera (all overloads) to force external camera ID
        try {
            Class<?> cameraManagerClass = Class.forName("android.hardware.camera2.CameraManager");
            for (Method method : cameraManagerClass.getDeclaredMethods()) {
                if ("openCamera".equals(method.getName())) {
                    method.setAccessible(true);
                    xposed.hook(method).intercept(chain -> {
                        String requestedId = (String) chain.getArg(0);
                        CameraManager mgr = (CameraManager) chain.getThisObject();
                        String externalId = null;
                        try {
                            for (String id : mgr.getCameraIdList()) {
                                CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                                Integer lens = ch.get(CameraCharacteristics.LENS_FACING);
                                if (lens != null && lens == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                                    externalId = id;
                                    break;
                                }
                            }
                        } catch (Throwable t) {
                            xposed.log(Log.DEBUG, TAG, "openCamera hook error: " + t.getMessage());
                        }
                        if (externalId != null && !externalId.equals(requestedId)) {
                            xposed.log(Log.DEBUG, TAG, "remapped openCamera(" + requestedId
                                    + ") -> openCamera(" + externalId + ")");
                            Object[] args = chain.getArgs().toArray(new Object[0]);
                            args[0] = externalId;
                            return chain.proceed(args);
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook CameraManager.openCamera: " + t.getMessage());
        }

        // Hook Camera1 getCameraInfo to spoof external camera as back-facing
        try {
            Method m = Camera.class.getDeclaredMethod("getCameraInfo", int.class, Camera.CameraInfo.class);
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                chain.proceed();
                Camera.CameraInfo info = (Camera.CameraInfo) chain.getArg(1);
                if (info.facing == 2 /* CAMERA_FACING_EXTERNAL */) {
                    info.facing = Camera.CameraInfo.CAMERA_FACING_BACK;
                    xposed.log(Log.DEBUG, TAG, "getCameraInfo spoofed external camera as BACK");
                }
                return null;
            });
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook Camera.getCameraInfo: " + t.getMessage());
        }

        // Hook Camera2 CameraCharacteristics.get to spoof external LENS_FACING as back
        try {
            Method m = Class.forName("android.hardware.camera2.CameraCharacteristics", false, classLoader)
                    .getDeclaredMethod("get", CameraCharacteristics.Key.class);
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                Object result = chain.proceed();
                CameraCharacteristics.Key<?> key = (CameraCharacteristics.Key<?>) chain.getArg(0);
                if (CameraCharacteristics.LENS_FACING.equals(key)
                        && result instanceof Integer
                        && (Integer) result == CameraMetadata.LENS_FACING_EXTERNAL) {
                    xposed.log(Log.DEBUG, TAG,
                            "CameraCharacteristics.get(LENS_FACING) spoofed external as BACK");
                    return CameraMetadata.LENS_FACING_BACK;
                }
                return result;
            });
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook CameraCharacteristics.get: " + t.getMessage());
        }
    }

    private int findExternalCameraOldAPI() {
        try {
            int n = Camera.getNumberOfCameras();
            for (int i = 0; i < n; i++) {
                CameraInfo info = new CameraInfo();
                Camera.getCameraInfo(i, info);
                if (info.facing == 2) { // CameraInfo.CAMERA_FACING_EXTERNAL
                    return i;
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return -1;
    }
}
