package com.uvcforce;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.ArrayList;
import java.util.List;

/**
 * LSPosed module entry: hook Camera2 and legacy Camera API to prefer external cameras.
 */
public class Module implements IXposedHookLoadPackage {
    // Empty = global effect. Set to a package name like "com.example.ttjump" to restrict.
    private static final String TARGET_PACKAGE = "";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (TARGET_PACKAGE != null && TARGET_PACKAGE.length() > 0) {
            if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
                return;
            }
        }

        XposedBridge.log("uvcforce: loaded for " + lpparam.packageName);

        // Hook Camera2.getCameraIdList
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
                            if (ids == null || ids.length <= 1) return;

                            Object thisObj = param.thisObject;
                            CameraManager mgr = null;
                            if (thisObj instanceof CameraManager) {
                                mgr = (CameraManager) thisObj;
                            }

                            List<String> external = new ArrayList<>();
                            List<String> others = new ArrayList<>();

                            for (String id : ids) {
                                boolean isExternal = false;
                                if (mgr != null) {
                                    try {
                                        CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                                        Integer lens = ch.get(CameraCharacteristics.LENS_FACING);
                                        if (lens != null && lens == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                                            isExternal = true;
                                        }
                                    } catch (Throwable t) {
                                        // ignore per-camera failures
                                    }
                                }
                                if (isExternal) external.add(id);
                                else others.add(id);
                            }

                            if (!external.isEmpty()) {
                                List<String> reordered = new ArrayList<>();
                                reordered.addAll(external);
                                reordered.addAll(others);
                                param.setResult(reordered.toArray(new String[0]));
                                XposedBridge.log("uvcforce: reordered Camera2 list, external first: " + reordered.toString());
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("uvcforce: Camera2 hook error: " + t.getMessage());
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("uvcforce: failed to hook CameraManager.getCameraIdList: " + t.getMessage());
        }

        // Hook legacy Camera API open() and open(int)
        try {
            Class<?> cameraClass = Camera.class;

            XposedHelpers.findAndHookMethod(
                cameraClass,
                "open",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            int requested = (Integer) param.args[0];
                            int externalId = findExternalCameraOldAPI();
                            if (externalId >= 0 && externalId != requested) {
                                param.args[0] = externalId;
                                XposedBridge.log("uvcforce: remapped Camera.open(" + requested + ") -> open(" + externalId + ")");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("uvcforce: open(int) hook error: " + t.getMessage());
                        }
                    }
                }
            );

            XposedHelpers.findAndHookMethod(
                cameraClass,
                "open",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            int externalId = findExternalCameraOldAPI();
                            if (externalId >= 0) {
                                Camera cam = Camera.open(externalId);
                                param.setResult(cam);
                                XposedBridge.log("uvcforce: remapped Camera.open() -> open(" + externalId + ")");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("uvcforce: open() hook error: " + t.getMessage());
                        }
                    }
                }
            );

        } catch (Throwable t) {
            XposedBridge.log("uvcforce: failed to hook old Camera API: " + t.getMessage());
        }

        // Hook ByteRTC Camera1Enumerator to clear static cache and force re-enumeration
        try {
            final Class<?> enumClass = XposedHelpers.findClass(
                "com.ss.bytertc.base.media.camera.Camera1Enumerator",
                lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(
                enumClass,
                "getSupportedFormats",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Integer camIndex = (Integer) param.args[0];
                        if (camIndex == null) return;
                        XposedBridge.log("uvcforce: Camera1Enumerator.getSupportedFormats called for index: " + camIndex);
                        try {
                            XposedHelpers.setStaticObjectField(enumClass, "cachedSupportedFormats", null);
                            XposedBridge.log("uvcforce: cleared cachedSupportedFormats");
                        } catch (Throwable t) {
                            XposedBridge.log("uvcforce: failed to clear cachedSupportedFormats: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        @SuppressWarnings("unchecked")
                        java.util.List<?> res = (java.util.List<?>) param.getResult();
                        XposedBridge.log("uvcforce: Camera1Enumerator.getSupportedFormats returned size: " + (res == null ? "null" : res.size()));
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("uvcforce: Camera1Enumerator not found or hook failed: " + t);
        }

        // Hook Camera.Parameters.getSupportedPreviewFpsRange to return dummy data if null/empty
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.Camera$Parameters",
                lpparam.classLoader,
                "getSupportedPreviewFpsRange",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        @SuppressWarnings("unchecked")
                        List<int[]> result = (List<int[]>) param.getResult();
                        if (result == null || result.isEmpty()) {
                            List<int[]> fake = new ArrayList<>();
                            fake.add(new int[]{15000, 30000});
                            param.setResult(fake);
                            XposedBridge.log("uvcforce: faked getSupportedPreviewFpsRange");
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("uvcforce: failed to hook getSupportedPreviewFpsRange: " + t.getMessage());
        }

        // Hook Camera.Parameters.getSupportedPictureSizes to fallback to preview sizes if null/empty
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.Camera$Parameters",
                lpparam.classLoader,
                "getSupportedPictureSizes",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        @SuppressWarnings("unchecked")
                        List<?> result = (List<?>) param.getResult();
                        if (result == null || result.isEmpty()) {
                            Camera.Parameters params = (Camera.Parameters) param.thisObject;
                            List<Camera.Size> previewSizes = params.getSupportedPreviewSizes();
                            if (previewSizes != null && !previewSizes.isEmpty()) {
                                param.setResult(previewSizes);
                                XposedBridge.log("uvcforce: faked getSupportedPictureSizes with preview sizes");
                            } else {
                                XposedBridge.log("uvcforce: getSupportedPictureSizes: no preview sizes available either");
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("uvcforce: failed to hook getSupportedPictureSizes: " + t.getMessage());
        }

        // Hook Camera.setParameters to swallow exceptions from UVC cameras
        try {
            XposedHelpers.findAndHookMethod(
                Camera.class,
                "setParameters",
                Camera.Parameters.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                        } catch (Throwable t) {
                            XposedBridge.log("uvcforce: ignored Camera.setParameters exception: " + t.getMessage());
                        }
                        return null;
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("uvcforce: failed to hook Camera.setParameters: " + t.getMessage());
        }

        // Hook CameraManager.openCamera (all overloads) to force external camera ID
        try {
            Class<?> cameraManagerClass = Class.forName("android.hardware.camera2.CameraManager");
            for (java.lang.reflect.Method method : cameraManagerClass.getDeclaredMethods()) {
                if ("openCamera".equals(method.getName())) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                String requestedId = (String) param.args[0];
                                CameraManager mgr = (CameraManager) param.thisObject;
                                String externalId = null;
                                for (String id : mgr.getCameraIdList()) {
                                    CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                                    Integer lens = ch.get(CameraCharacteristics.LENS_FACING);
                                    if (lens != null && lens == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                                        externalId = id;
                                        break;
                                    }
                                }
                                if (externalId != null && !externalId.equals(requestedId)) {
                                    param.args[0] = externalId;
                                    XposedBridge.log("uvcforce: remapped openCamera(" + requestedId + ") -> openCamera(" + externalId + ")");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("uvcforce: openCamera hook error: " + t.getMessage());
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("uvcforce: failed to hook CameraManager.openCamera: " + t.getMessage());
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
            XposedBridge.log("uvcforce: findExternalCameraOldAPI error: " + t.getMessage());
        }
        return -1;
    }
}
