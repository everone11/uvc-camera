package com.everone11.uvccamera.xposed;

import android.content.SharedPreferences;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.List;

/**
 * Hook Camera1Enumerator.getSupportedFormats，读取格式列表并清空静态缓存。
 * 由 MainModule 统一调度，通过 apply() 而非 IXposedHookLoadPackage 接口触发。
 */
public class Camera1EnumeratorHook {

    /**
     * 在目标包加载时安装 Hook。
     *
     * @param packageName  目标应用包名
     * @param classLoader  目标应用的 ClassLoader
     * @param prefs        模块 SharedPreferences（由 MainModule 通过 getSharedPreferences() 提供）
     */
    public void apply(String packageName, ClassLoader classLoader, SharedPreferences prefs) {
        String targetPkg = prefs.getString(PrefManager.KEY_TARGET_PACKAGE, "");

        if (targetPkg != null && !targetPkg.isEmpty()) {
            if (!packageName.equals(targetPkg)) {
                return;
            }
        }

        // Allow overriding the class name in case the target app obfuscates ByteRTC classes.
        String enumeratorClass = prefs.getString(
                PrefManager.KEY_ENUMERATOR_CLASS, PrefManager.DEFAULT_ENUMERATOR_CLASS);

        XposedBridge.log("Camera1EnumeratorHook loaded in: " + packageName);
        try {
            final Class<?> enumClass = XposedHelpers.findClass(
                enumeratorClass,
                classLoader
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

