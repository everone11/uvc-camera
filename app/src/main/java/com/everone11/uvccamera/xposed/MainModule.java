package com.everone11.uvccamera.xposed;

import android.app.Application;
import android.content.SharedPreferences;

import com.uvcforce.Module;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 100+ 模块入口。
 *
 * 取代各 Hook 类分散的 IXposedHookLoadPackage 实现，统一在此读取模块 SharedPreferences
 * 并分发至各 Hook 子模块。在 LSPosed 框架下，XposedModule 被注入为目标进程的 Application，
 * 因此 getSharedPreferences() 可直接访问模块自身数据目录，无需依赖已被 SELinux 限制的
 * XSharedPreferences 跨进程文件读取方式。
 */
public class MainModule extends XposedModule {

    private final VirtualCameraHook virtualCameraHook = new VirtualCameraHook();
    private final Camera1EnumeratorHook camera1EnumeratorHook = new Camera1EnumeratorHook();
    private final ByteRtcCameraHook byteRtcCameraHook = new ByteRtcCameraHook();
    private final Module legacyModule = new Module();

    public MainModule(Application host, XposedModuleInterface.ModuleLoadedParam param) {
        super(host, param);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        // 通过模块自身 Application 上下文读取 SharedPreferences，
        // LSPosed 保证此调用在 hook 进程中读取的是模块数据目录下的文件。
        SharedPreferences prefs = getSharedPreferences(PrefManager.PREF_NAME, MODE_PRIVATE);

        String packageName = param.getPackageName();
        ClassLoader classLoader = param.getClassLoader();

        virtualCameraHook.apply(packageName, classLoader, prefs);
        camera1EnumeratorHook.apply(packageName, classLoader, prefs);
        byteRtcCameraHook.apply(packageName, classLoader, prefs);
        legacyModule.apply(packageName, classLoader);
    }
}
