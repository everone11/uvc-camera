package com.everone11.uvccamera.xposed;

import android.content.SharedPreferences;

import com.uvcforce.Module;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102+ 模块入口。
 *
 * 取代各 Hook 类分散的 IXposedHookLoadPackage 实现，统一在此读取模块 SharedPreferences
 * 并分发至各 Hook 子模块。在 API 102 中，XposedModule 不再继承 Application，改用
 * getRemotePreferences() 从目标进程读取模块偏好设置（LSPosed 框架通过 PROP_CAP_REMOTE
 * 将模块数据目录下的文件桥接给 hook 进程，无需依赖已被 SELinux 限制的跨进程文件读取）。
 */
public class MainModule extends XposedModule {

    private final VirtualCameraHook virtualCameraHook = new VirtualCameraHook();
    private final Camera1EnumeratorHook camera1EnumeratorHook = new Camera1EnumeratorHook();
    private final ByteRtcCameraHook byteRtcCameraHook = new ByteRtcCameraHook();
    private final Module legacyModule = new Module();

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        // 通过框架的 Remote Preferences 机制读取模块偏好设置，
        // LSPosed 的 PROP_CAP_REMOTE 保证在目标进程中可访问模块数据目录下的文件。
        SharedPreferences prefs = getRemotePreferences(PrefManager.PREF_NAME);

        String packageName = param.getPackageName();
        ClassLoader classLoader = param.getClassLoader();

        virtualCameraHook.apply(this, packageName, classLoader, prefs);
        camera1EnumeratorHook.apply(this, packageName, classLoader, prefs);
        byteRtcCameraHook.apply(this, packageName, classLoader, prefs);
        // Module uses a hard-coded TARGET_PACKAGE constant rather than reading user prefs;
        // it applies global Camera2 reordering and legacy Camera redirection for all packages
        // (or a compile-time-fixed target) and does not need per-user preference isolation.
        legacyModule.apply(this, packageName, classLoader);
    }
}
