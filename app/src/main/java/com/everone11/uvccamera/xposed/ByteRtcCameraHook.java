package com.everone11.uvccamera.xposed;

import android.content.SharedPreferences;
import android.hardware.Camera;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
 *
 * 由 MainModule 统一调度，通过 apply() 而非 IXposedHookLoadPackage 接口触发。
 */
public class ByteRtcCameraHook {

    private static final String TAG = "ByteRtcCameraHook";

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

        hookGetNumberOfCameras(xposed);
        hookCamera1Enumerator(xposed, classLoader, prefs);
        hookCamera1Session(xposed, classLoader, prefs);
        hookCamera2Session(xposed, classLoader, prefs);
    }

    /**
     * Hook Camera.getNumberOfCameras() to return at least 1 on TV devices
     * that report 0 cameras, so Camera1Enumerator can enumerate UVC cameras.
     */
    private void hookGetNumberOfCameras(XposedInterface xposed) {
        try {
            Method m = Camera.class.getDeclaredMethod("getNumberOfCameras");
            m.setAccessible(true);
            xposed.hook(m).intercept(chain -> {
                int count = (int) chain.proceed();
                if (count == 0) {
                    xposed.log(Log.DEBUG, TAG, "Camera.getNumberOfCameras() was 0,"
                            + " overriding to 1 for UVC camera support");
                    return 1;
                }
                return count;
            });
            xposed.log(Log.DEBUG, TAG, "Camera.getNumberOfCameras hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook Camera.getNumberOfCameras: " + t.getMessage());
        }
    }

    /**
     * Hook Camera1Enumerator methods:
     * - getDeviceNames(): return at least ["0"] to ensure the UVC camera is discovered.
     * - getSupportedFormats(int): return UVC-compatible fallback formats when the result is empty.
     */
    private void hookCamera1Enumerator(XposedInterface xposed, ClassLoader classLoader, SharedPreferences prefs) {
        String className = prefs.getString(
                PrefManager.KEY_ENUMERATOR_CLASS, PrefManager.DEFAULT_ENUMERATOR_CLASS);
        Class<?> enumClass;
        try {
            enumClass = Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            // Expected when this app does not include the ByteRTC SDK; fail silently.
            return;
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to find Camera1Enumerator class: " + t.getMessage());
            return;
        }

        // Hook getDeviceNames()
        try {
            Method getDeviceNames = enumClass.getDeclaredMethod("getDeviceNames");
            getDeviceNames.setAccessible(true);
            xposed.hook(getDeviceNames).intercept(chain -> {
                String[] names = (String[]) chain.proceed();
                if (names == null || names.length == 0) {
                    // Return ["0"] so Camera1Enumerator can open camera at index 0
                    xposed.log(Log.DEBUG, TAG, "Camera1Enumerator.getDeviceNames() was empty,"
                            + " overriding to [\"0\"] for UVC camera");
                    return new String[]{"0"};
                } else {
                    xposed.log(Log.DEBUG, TAG, "Camera1Enumerator.getDeviceNames() returned: "
                            + Arrays.toString(names));
                    return names;
                }
            });
            xposed.log(Log.DEBUG, TAG, "Camera1Enumerator.getDeviceNames hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook Camera1Enumerator.getDeviceNames: "
                    + t.getMessage());
        }

        // Hook getSupportedFormats(int)
        try {
            Method getSupportedFormats = enumClass.getDeclaredMethod("getSupportedFormats", int.class);
            getSupportedFormats.setAccessible(true);
            xposed.hook(getSupportedFormats).intercept(chain -> {
                @SuppressWarnings("unchecked")
                List<?> res = (List<?>) chain.proceed();
                if (res == null || res.isEmpty()) {
                    List<Object> fallback = buildUvcCaptureFormats(classLoader);
                    if (!fallback.isEmpty()) {
                        xposed.log(Log.DEBUG, TAG, "Camera1Enumerator.getSupportedFormats("
                                + chain.getArg(0) + ") was empty, overriding with "
                                + fallback.size() + " UVC formats");
                        return fallback;
                    } else {
                        xposed.log(Log.DEBUG, TAG, "Camera1Enumerator.getSupportedFormats("
                                + chain.getArg(0) + ") empty, no CaptureFormat class found");
                    }
                } else {
                    xposed.log(Log.DEBUG, TAG, "Camera1Enumerator.getSupportedFormats("
                            + chain.getArg(0) + ") returned " + res.size() + " formats");
                }
                return res;
            });
            xposed.log(Log.DEBUG, TAG, "Camera1Enumerator.getSupportedFormats hook installed");
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to hook Camera1Enumerator.getSupportedFormats: "
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
                Class<?> captureFormatClass = Class.forName(className, false, classLoader);
                for (int[] cfg : UVC_CONFIGS) {
                    Object fmt = tryCreateCaptureFormat(captureFormatClass, cfg);
                    if (fmt != null) {
                        formats.add(fmt);
                    }
                }
                if (!formats.isEmpty()) {
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
            Constructor<?> ctor = captureFormatClass.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(cfg[0], cfg[1], cfg[2] * 1000, cfg[2] * 1000);
        } catch (Throwable ignored) {
            // Fall through to alternate constructor
        }
        // Try: CaptureFormat(int width, int height, int framerate)
        try {
            Constructor<?> ctor = captureFormatClass.getDeclaredConstructor(
                    int.class, int.class, int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(cfg[0], cfg[1], cfg[2]);
        } catch (Throwable ignored) {
            // Constructor signature differs; skip this config
        }
        return null;
    }

    /**
     * Hook Camera1Session to intercept create() and startCapturing().
     * Logs all invocations so issues on Android 14 TV can be diagnosed via logcat.
     */
    private void hookCamera1Session(XposedInterface xposed, ClassLoader classLoader, SharedPreferences prefs) {
        String className = prefs.getString(
                PrefManager.KEY_SESSION1_CLASS, PrefManager.DEFAULT_SESSION1_CLASS);
        Class<?> sessionClass;
        try {
            sessionClass = Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            // Expected when this app does not include the ByteRTC SDK; fail silently.
            return;
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to find Camera1Session class: " + t.getMessage());
            return;
        }

        int hookedCount = 0;
        // getDeclaredMethods() is used intentionally: the exact parameter signatures of
        // create() and startCapturing() vary across ByteRTC SDK versions, so we hook
        // all overloads by name rather than a single fixed signature.
        for (Method m : sessionClass.getDeclaredMethods()) {
            final String methodName = m.getName();
            if ("create".equals(methodName) || "startCapturing".equals(methodName)) {
                m.setAccessible(true);
                xposed.hook(m).intercept(chain -> {
                    xposed.log(Log.DEBUG, TAG, "Camera1Session." + methodName + "() called"
                            + (chain.getArgs().size() > 0
                                    ? " args=" + chain.getArgs() : ""));
                    try {
                        Object result = chain.proceed();
                        xposed.log(Log.DEBUG, TAG, "Camera1Session." + methodName
                                + "() returned: " + result);
                        return result;
                    } catch (Throwable t) {
                        xposed.log(Log.DEBUG, TAG, "Camera1Session." + methodName
                                + "() threw: " + t.getMessage());
                        throw t;
                    }
                });
                hookedCount++;
            }
        }
        xposed.log(Log.DEBUG, TAG, "Camera1Session: " + hookedCount + " method(s) hooked");
    }

    /**
     * Hook Camera2Session to intercept create().
     * Logs session creation so failures on Android 14 TV can be diagnosed.
     */
    private void hookCamera2Session(XposedInterface xposed, ClassLoader classLoader, SharedPreferences prefs) {
        String className = prefs.getString(
                PrefManager.KEY_SESSION2_CLASS, PrefManager.DEFAULT_SESSION2_CLASS);
        Class<?> sessionClass;
        try {
            sessionClass = Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            // Expected when this app does not include the ByteRTC SDK; fail silently.
            return;
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "failed to find Camera2Session class: " + t.getMessage());
            return;
        }

        int hookedCount = 0;
        // getDeclaredMethods() is used intentionally: create() overloads vary by SDK version.
        for (Method m : sessionClass.getDeclaredMethods()) {
            if ("create".equals(m.getName())) {
                m.setAccessible(true);
                xposed.hook(m).intercept(chain -> {
                    xposed.log(Log.DEBUG, TAG, "Camera2Session.create() called"
                            + (chain.getArgs().size() > 0
                                    ? " args=" + chain.getArgs() : ""));
                    try {
                        Object result = chain.proceed();
                        xposed.log(Log.DEBUG, TAG, "Camera2Session.create() returned: " + result);
                        return result;
                    } catch (Throwable t) {
                        xposed.log(Log.DEBUG, TAG, "Camera2Session.create() threw: "
                                + t.getMessage());
                        throw t;
                    }
                });
                hookedCount++;
            }
        }
        xposed.log(Log.DEBUG, TAG, "Camera2Session: " + hookedCount + " method(s) hooked");
    }
}
