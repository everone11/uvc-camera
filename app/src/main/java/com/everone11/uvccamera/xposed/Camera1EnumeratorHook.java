package com.everone11.uvccamera.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.List;

/**
 * Xposed hook for Camera1Enumerator.getSupportedFormats(int).
 *
 * Before invocation: clears the static field cachedSupportedFormats to null.
 * After invocation: logs the size of the returned format list.
 *
 * Replace "目标应用包名" with the actual package name of the target application.
 */
public class Camera1EnumeratorHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        final String targetPkg = "目标应用包名";
        if (!lpparam.packageName.equals(targetPkg)) {
            return;
        }

        XposedBridge.log("Camera1EnumeratorHook: loaded in " + lpparam.packageName);

        try {
            final Class<?> enumClass = XposedHelpers.findClass(
                    "com.ss.bytertc.base.media.camera.Camera1Enumerator",
                    lpparam.classLoader);

            XposedHelpers.findAndHookMethod(enumClass, "getSupportedFormats", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                XposedHelpers.setStaticObjectField(enumClass, "cachedSupportedFormats", null);
                                XposedBridge.log("Camera1EnumeratorHook: cleared cachedSupportedFormats");
                            } catch (Throwable t) {
                                XposedBridge.log("Camera1EnumeratorHook: failed to clear cachedSupportedFormats: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                @SuppressWarnings("unchecked")
                                List<?> result = (List<?>) param.getResult();
                                XposedBridge.log("Camera1EnumeratorHook: getSupportedFormats returned size: "
                                        + (result == null ? "null" : result.size()));
                            } catch (Throwable t) {
                                XposedBridge.log("Camera1EnumeratorHook: error in afterHookedMethod: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("Camera1EnumeratorHook: error hooking getSupportedFormats: " + t);
        }
    }
}
