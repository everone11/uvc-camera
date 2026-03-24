package com.everone11.uvccamera.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.List;

/**
 * Xposed hook: 清空 Camera1Enumerator.cachedSupportedFormats 并记录调用。
 *
 * 注意：将 "目标应用包名" 替换为你要 hook 的应用包名，或者删除包名检查以 hook 所有包（不推荐）。
 */
public class Camera1EnumeratorHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 将下面替换为目标 app 的包名
        final String targetPkg = "目标应用包名";
        if (!lpparam.packageName.equals(targetPkg)) {
            return;
        }

        XposedBridge.log("Camera1EnumeratorHook loaded in: " + lpparam.packageName);
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
                        int camIndex = (Integer) param.args[0];
                        XposedBridge.log("Camera1Enumerator.getSupportedFormats called for index: " + camIndex);
                        try {
                            // 清空静态缓存，强制重新枚举（与同步保持一致性时需小心）
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
                        XposedBridge.log("Camera1Enumerator.getSupportedFormats returned size: " + (res == null ? "null" : res.size()));
                        // 可以在这里篡改返回值，例如添加/替换元素：
                        // if (res != null) { res.clear(); /* 构造自定义列表后 param.setResult(res) */ }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("Camera1EnumeratorHook error: " + t);
        }
    }
}
