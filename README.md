# UVC Camera Xposed Module

Xposed 模块模板：hook `com.ss.bytertc.base.media.camera.Camera1Enumerator.getSupportedFormats(int)`。

## 模块用途

该模块在目标应用包名加载时，会 hook 类 `com.ss.bytertc.base.media.camera.Camera1Enumerator` 的 `getSupportedFormats(int)` 方法：

- 在调用前清空其静态缓存 `cachedSupportedFormats`（如果存在），强制重新枚举支持的格式。
- 在调用后记录返回结果大小，供进一步分析和修改。

## 构建步骤

1. 将 Xposed/LSPosed API jar 放入 `app/libs/xposed-api.jar`（可从 LSPosed/EdXposed 模块模板或你的 LSPosed 发行版获取），或直接使用 Maven 依赖 `de.robv.android.xposed:api:82`（需 jitpack 仓库）。
2. 在 `app/src/main/java/com/everone11/uvccamera/xposed/Camera1EnumeratorHook.java` 中，将 `"目标应用包名"` 替换为你要 hook 的应用包名（或删除包名检查以 hook 所有包，但不推荐）。
3. 运行 `./gradlew assembleRelease` 构建 APK。
4. 安装 APK：`adb install -r app/build/outputs/apk/release/app-release.apk`。

## 安装与启用

1. 安装 APK 后，在 Xposed / EdXposed / LSPosed 管理器中启用该模块。
2. 重启设备或重载框架。
3. 运行目标应用，通过 `adb logcat | grep Camera1EnumeratorHook` 查看 hook 日志。

## 注意事项

- 将 `Camera1EnumeratorHook.java` 中的 `"目标应用包名"` 替换为实际包名，否则模块不会对任何应用生效。
- 目标应用若使用混淆或自定义 ClassLoader，请先确认运行时类名是否匹配，或在正确的 classLoader 上查找类。
- `cachedSupportedFormats` 为静态缓存字段；若字段名被混淆或不存在，清空操作会静默失败（已做异常捕获）。
- 多线程场景下清空静态缓存需注意并发安全，`getSupportedFormats` 本身是 `synchronized` 方法。