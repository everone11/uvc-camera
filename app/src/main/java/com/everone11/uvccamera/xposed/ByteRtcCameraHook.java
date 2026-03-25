package com.everone11.uvccamera.xposed;

import android.hardware.Camera;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ByteRTC Camera Deep Hook
 *
 * Hooks ByteRTC SDK camera enumeration and session classes to ensure
 * UVC (USB) cameras are recognized on Android 14 Google TV devices,
 * even when Camera.getNumberOfCameras() returns 0.
 *
 * Hooked classes:
 * - android.hardware.Camera: getNumberOfCameras()
 * - com.ss.bytertc.base.media.camera.Camera1Enumerator: getDeviceNames(), getSupportedFormats(int)
 * - com.ss.bytertc.base.media.camera.Camera1Session: create(), startCapturing()
 * - com.ss.bytertc.base.media.camera.Camera2Session: create()
 */
public class ByteRtcCameraHook implements IXposedHookLoadPackage {

    private static final String TAG = "ByteRtcCameraHook";

    // Module package name used to read shared preferences via XSharedPreferences.
    private static final String MODULE_PACKAGE = "com.everone11.uvccamera.xposed";

    private static final String BYTERTC_CAMERA1_ENUMERATOR =
            "com.ss.bytertc.base.media.camera.Camera1Enumerator";
    private static final String BYTERTC_CAMERA1_SESSION =
            "com.ss.bytertc.base.media.camera.Camera1Session";
    private static final String BYTERTC_CAMERA2_SESSION =
            "com.ss.bytertc.base.media.camera.Camera2Session";

    // Candidate class names for the SDK's CaptureFormat used to build fallback format lists.
    private static final String[] CAPTURE_FORMAT_CLASSES = {
        "org.webrtc.CameraEnumerationAndroid$CaptureFormat",
        "com.ss.bytertc.base.media.camera.CameraEnumerationAndroid$CaptureFormat",
        "com.ss.bytertc.engine.video.CaptureFormat",
    };

    // UVC-compatible resolutions and frame rates offered as fallback when the SDK returns none.
    private static final int[][] UVC_CONFIGS = {
        {1280, 720,  30},
        {1920, 1080, 30},
        {640,  480,  30},
        {640,  480,  15},
    };

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XSharedPreferences prefs = new XSharedPreferences(
                MODULE_PACKAGE, PrefManager.PREF_NAME);
        prefs.reload();
        String targetPkg = prefs.getString(PrefManager.KEY_TARGET_PACKAGE, "");

        if (targetPkg != null && !targetPkg.isEmpty()) {
            if (!lpparam.packageName.equals(targetPkg)) {
                return;
            }
        }

        XposedBridge.log(TAG + ": loaded for " + lpparam.packageName);

        hookGetNumberOfCameras();
        hookCamera1Enumerator(lpparam, prefs);
        hookCamera1Session(lpparam, prefs);
        hookCamera2Session(lpparam, prefs);
    }

    /**
     * Hook Camera.getNumberOfCameras() to return at least 1 on TV devices
     * that report 0 cameras, so Camera1Enumerator can enumerate UVC cameras.
     */
    private void hookGetNumberOfCameras() {
        try {
            XposedHelpers.findAndHookMethod(
                Camera.class,
                "getNumberOfCameras",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        int count = (int) param.getResult();
                        if (count == 0) {
                            param.setResult(1);
                            XposedBridge.log(TAG + ": Camera.getNumberOfCameras() was 0,"
                                    + " overriding to 1 for UVC camera support");
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": Camera.getNumberOfCameras hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook Camera.getNumberOfCameras: " + t.getMessage());
        }
    }

    /**
     * Hook Camera1Enumerator methods:
     * - getDeviceNames(): return at least ["0"] to ensure the UVC camera is discovered.
     * - getSupportedFormats(int): return UVC-compatible fallback formats when the result is empty.
     */
    private void hookCamera1Enumerator(final XC_LoadPackage.LoadPackageParam lpparam,
            XSharedPreferences prefs) {
        String className = prefs.getString(
                PrefManager.KEY_ENUMERATOR_CLASS, PrefManager.DEFAULT_ENUMERATOR_CLASS);
        Class<?> enumClass;
        try {
            enumClass = XposedHelpers.findClass(className, lpparam.classLoader);
        } catch (XposedHelpers.ClassNotFoundError e) {
            // Expected when this app does not include the ByteRTC SDK; fail silently.
            return;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to find Camera1Enumerator class: " + t.getMessage());
            return;
        }

        // Hook getDeviceNames()
        try {
            XposedHelpers.findAndHookMethod(
                enumClass,
                "getDeviceNames",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String[] names = (String[]) param.getResult();
                        if (names == null || names.length == 0) {
                            // Return ["0"] so Camera1Enumerator can open camera at index 0
                            param.setResult(new String[]{"0"});
                            XposedBridge.log(TAG + ": Camera1Enumerator.getDeviceNames() was empty,"
                                    + " overriding to [\"0\"] for UVC camera");
                        } else {
                            XposedBridge.log(TAG + ": Camera1Enumerator.getDeviceNames() returned: "
                                    + Arrays.toString(names));
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": Camera1Enumerator.getDeviceNames hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook Camera1Enumerator.getDeviceNames: "
                    + t.getMessage());
        }

        // Hook getSupportedFormats(int)
        final Class<?> finalEnumClass = enumClass;
        try {
            XposedHelpers.findAndHookMethod(
                finalEnumClass,
                "getSupportedFormats",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        @SuppressWarnings("unchecked")
                        List<?> res = (List<?>) param.getResult();
                        if (res == null || res.isEmpty()) {
                            List<Object> fallback =
                                    buildUvcCaptureFormats(param.method.getDeclaringClass()
                                            .getClassLoader());
                            if (!fallback.isEmpty()) {
                                param.setResult(fallback);
                                XposedBridge.log(TAG + ": Camera1Enumerator.getSupportedFormats("
                                        + param.args[0] + ") was empty, overriding with "
                                        + fallback.size() + " UVC formats");
                            } else {
                                XposedBridge.log(TAG + ": Camera1Enumerator.getSupportedFormats("
                                        + param.args[0] + ") empty, no CaptureFormat class found");
                            }
                        } else {
                            XposedBridge.log(TAG + ": Camera1Enumerator.getSupportedFormats("
                                    + param.args[0] + ") returned " + res.size() + " formats");
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": Camera1Enumerator.getSupportedFormats hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook Camera1Enumerator.getSupportedFormats: "
                    + t.getMessage());
        }
    }

    /**
     * Build a list of UVC-compatible CaptureFormat objects via reflection.
     * Tries multiple candidate class names used across ByteRTC / WebRTC SDK versions.
     * Returns an empty list if no suitable class is found.
     */
    private List<Object> buildUvcCaptureFormats(ClassLoader classLoader) {
        List<Object> formats = new ArrayList<>();
        for (String className : CAPTURE_FORMAT_CLASSES) {
            try {
                Class<?> captureFormatClass = XposedHelpers.findClass(className, classLoader);
                for (int[] cfg : UVC_CONFIGS) {
                    Object fmt = tryCreateCaptureFormat(captureFormatClass, cfg);
                    if (fmt != null) {
                        formats.add(fmt);
                    }
                }
                if (!formats.isEmpty()) {
                    XposedBridge.log(TAG + ": built " + formats.size()
                            + " UVC CaptureFormats using " + className);
                    break;
                }
            } catch (Throwable ignored) {
                // Class not present in this SDK version; try the next candidate
            }
        }
        return formats;
    }

    /**
     * Try to instantiate a CaptureFormat with the given {width, height, fps} config.
     * Attempts the 4-arg constructor (width, height, minFps*1000, maxFps*1000) first,
     * then falls back to the 3-arg constructor (width, height, fps).
     * Returns null if neither constructor matches.
     */
    private Object tryCreateCaptureFormat(Class<?> captureFormatClass, int[] cfg) {
        // Try: CaptureFormat(int width, int height, int minFramerate, int maxFramerate)
        try {
            return XposedHelpers.newInstance(
                    captureFormatClass, cfg[0], cfg[1], cfg[2] * 1000, cfg[2] * 1000);
        } catch (Throwable ignored) {
            // Fall through to alternate constructor
        }
        // Try: CaptureFormat(int width, int height, int framerate)
        try {
            return XposedHelpers.newInstance(captureFormatClass, cfg[0], cfg[1], cfg[2]);
        } catch (Throwable ignored) {
            // Constructor signature differs; skip this config
        }
        return null;
    }

    /**
     * Hook Camera1Session to intercept create() and startCapturing().
     * Logs all invocations so issues on Android 14 TV can be diagnosed via logcat.
     * This allows lower-level UVC hooks to substitute the real camera device.
     */
    private void hookCamera1Session(final XC_LoadPackage.LoadPackageParam lpparam,
            XSharedPreferences prefs) {
        String className = prefs.getString(
                PrefManager.KEY_SESSION1_CLASS, PrefManager.DEFAULT_SESSION1_CLASS);
        Class<?> sessionClass;
        try {
            sessionClass = XposedHelpers.findClass(className, lpparam.classLoader);
        } catch (XposedHelpers.ClassNotFoundError e) {
            // Expected when this app does not include the ByteRTC SDK; fail silently.
            return;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to find Camera1Session class: " + t.getMessage());
            return;
        }

        int hookedCount = 0;
        // getDeclaredMethods() is used intentionally: the exact parameter signatures of
        // create() and startCapturing() vary across ByteRTC SDK versions, so we hook
        // all overloads by name rather than a single fixed signature.
        for (java.lang.reflect.Method m : sessionClass.getDeclaredMethods()) {
            final String methodName = m.getName();
            if ("create".equals(methodName) || "startCapturing".equals(methodName)) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + ": Camera1Session." + methodName + "() called"
                                + (param.args.length > 0
                                        ? " args=" + Arrays.toString(param.args) : ""));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.getThrowable() != null) {
                            XposedBridge.log(TAG + ": Camera1Session." + methodName
                                    + "() threw: " + param.getThrowable().getMessage());
                        } else {
                            XposedBridge.log(TAG + ": Camera1Session." + methodName
                                    + "() returned: " + param.getResult());
                        }
                    }
                });
                hookedCount++;
            }
        }
        XposedBridge.log(TAG + ": Camera1Session: " + hookedCount + " method(s) hooked");
    }

    /**
     * Hook Camera2Session to intercept create().
     * Logs session creation so failures on Android 14 TV can be diagnosed.
     */
    private void hookCamera2Session(final XC_LoadPackage.LoadPackageParam lpparam,
            XSharedPreferences prefs) {
        String className = prefs.getString(
                PrefManager.KEY_SESSION2_CLASS, PrefManager.DEFAULT_SESSION2_CLASS);
        Class<?> sessionClass;
        try {
            sessionClass = XposedHelpers.findClass(className, lpparam.classLoader);
        } catch (XposedHelpers.ClassNotFoundError e) {
            // Expected when this app does not include the ByteRTC SDK; fail silently.
            return;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to find Camera2Session class: " + t.getMessage());
            return;
        }

        int hookedCount = 0;
        // getDeclaredMethods() is used intentionally: create() overloads vary by SDK version.
        for (java.lang.reflect.Method m : sessionClass.getDeclaredMethods()) {
            if ("create".equals(m.getName())) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + ": Camera2Session.create() called"
                                + (param.args.length > 0
                                        ? " args=" + Arrays.toString(param.args) : ""));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.getThrowable() != null) {
                            XposedBridge.log(TAG + ": Camera2Session.create() threw: "
                                    + param.getThrowable().getMessage());
                        } else {
                            XposedBridge.log(TAG + ": Camera2Session.create() returned: "
                                    + param.getResult());
                        }
                    }
                });
                hookedCount++;
            }
        }
        XposedBridge.log(TAG + ": Camera2Session: " + hookedCount + " method(s) hooked");
    }
}
