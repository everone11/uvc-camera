package com.everone11.uvccamera.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.util.List;

/**
 * Xposed Hook：读取用户在 MainActivity 中选择的目标包名，
 * 仅对该应用 Hook Camera1Enumerator.getSupportedFormats。
 * 若未选择特定应用，则对所有加载的包生效。
 */
public class Camera1EnumeratorHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 从模块 SharedPreferences 读取用户选择的目标包名
        XSharedPreferences prefs = new XSharedPreferences(
                "com.everone11.uvccamera.xposed", PrefManager.PREF_NAME);
        prefs.reload();
        String targetPkg = prefs.getString(PrefManager.KEY_TARGET_PACKAGE, "");

        // 若已选择特定应用，则跳过其他包
        if (targetPkg != null && !targetPkg.isEmpty()) {
            if (!lpparam.packageName.equals(targetPkg)) {
                return;
            }
        }

        // Allow overriding the class name in case the target app obfuscates ByteRTC classes.
        String enumeratorClass = prefs.getString(
                PrefManager.KEY_ENUMERATOR_CLASS, PrefManager.DEFAULT_ENUMERATOR_CLASS);

        XposedBridge.log("Camera1EnumeratorHook loaded in: " + lpparam.packageName);
        try {
            final Class<?> enumClass = XposedHelpers.findClass(
                enumeratorClass,
                lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(
                enumClass,
                "getSupportedFormats",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int camIndex = (Integer) param.args[0];
                        XposedBridge.log("Camera1Enumerator.getSupportedFormats called for index: " + camIndex);
                        try {
                            // 清空静态缓存，强制重新枚举
                            XposedHelpers.setStaticObjectField(enumClass, "cachedSupportedFormats", null);
                            XposedBridge.log("cleared cachedSupportedFormats");
                        } catch (Throwable t) {
                            XposedBridge.log("failed to clear cachedSupportedFormats: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        @SuppressWarnings("unchecked")
                        List<?> res = (List<?>) param.getResult();
                        XposedBridge.log("Camera1Enumerator.getSupportedFormats returned size: "
                                + (res == null ? "null" : res.size()));
                    }
                }
            );
        } catch (XposedHelpers.ClassNotFoundError e) {
            // Expected when this app does not include the ByteRTC SDK; fail silently.
        } catch (Throwable t) {
            XposedBridge.log("Camera1EnumeratorHook error: " + t);
        }
    }
}
