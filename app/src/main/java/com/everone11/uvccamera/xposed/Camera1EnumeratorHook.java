package com.everone11.uvccamera.xposed;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Hook Camera1Enumerator.getSupportedFormats，读取格式列表并清空静态缓存。
 * 由 MainModule 统一调度，通过 apply() 而非 IXposedHookLoadPackage 接口触发。
 */
public class Camera1EnumeratorHook {

    private static final String TAG = "Camera1EnumeratorHook";

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

        // Allow overriding the class name in case the target app obfuscates ByteRTC classes.
        String enumeratorClass = prefs.getString(
                PrefManager.KEY_ENUMERATOR_CLASS, PrefManager.DEFAULT_ENUMERATOR_CLASS);

        xposed.log(Log.DEBUG, TAG, "loaded in: " + packageName);

        Class<?> enumClass;
        try {
            enumClass = Class.forName(enumeratorClass, false, classLoader);
        } catch (ClassNotFoundException e) {
            // Expected when this app does not include the ByteRTC SDK; fail silently.
            return;
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "Camera1EnumeratorHook error finding class: " + t);
            return;
        }

        final Class<?> finalEnumClass = enumClass;
        try {
            Method getSupportedFormats = enumClass.getDeclaredMethod("getSupportedFormats", int.class);
            getSupportedFormats.setAccessible(true);
            xposed.hook(getSupportedFormats).intercept(chain -> {
                int camIndex = (Integer) chain.getArg(0);
                xposed.log(Log.DEBUG, TAG, "Camera1Enumerator.getSupportedFormats called for index: " + camIndex);
                try {
                    // 清空静态缓存，强制重新枚举
                    Field field = finalEnumClass.getDeclaredField("cachedSupportedFormats");
                    field.setAccessible(true);
                    field.set(null, null);
                    xposed.log(Log.DEBUG, TAG, "cleared cachedSupportedFormats");
                } catch (Throwable t) {
                    xposed.log(Log.DEBUG, TAG, "failed to clear cachedSupportedFormats: " + t);
                }

                List<?> res = (List<?>) chain.proceed();
                xposed.log(Log.DEBUG, TAG, "Camera1Enumerator.getSupportedFormats returned size: "
                        + (res == null ? "null" : res.size()));
                return res;
            });
        } catch (Throwable t) {
            xposed.log(Log.DEBUG, TAG, "Camera1EnumeratorHook error: " + t);
        }
    }
}

