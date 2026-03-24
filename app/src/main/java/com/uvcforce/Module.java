package com.uvcforce;

import android.content.pm.ApplicationInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
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
        // Skip all built-in system apps (FLAG_SYSTEM) to avoid NullPointerException
        // in CameraManager internal Map during early initialization
        if (lpparam.appInfo != null
                && (lpparam.appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return;
        }

        // Skip the Android core system process (appInfo may be null for it)
        if ("android".equals(lpparam.packageName)) {
            return;
        }

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
