package com.everone11.uvccamera.xposed;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 偏好设置管理器：用于保存/读取用户选择的 Hook 目标应用包名。
 */
public class PrefManager {
    public static final String PREF_NAME = "uvc_hook_prefs";
    public static final String KEY_TARGET_PACKAGE = "target_package";

    /** 保存目标包名（空字符串表示 Hook 所有应用）。 */
    public static void setTargetPackage(Context context, String packageName) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_TARGET_PACKAGE, packageName == null ? "" : packageName).apply();
    }

    /** 读取目标包名（空字符串表示 Hook 所有应用）。 */
    public static String getTargetPackage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_TARGET_PACKAGE, "");
    }
}
