# UVC Camera Xposed Module

Xposed 模块：Hook `com.ss.bytertc.base.media.camera.Camera1Enumerator.getSupportedFormats(int)` 以及 Camera2/Camera1 外置摄像头优先逻辑。模块自带 **图形界面**，可在手机上直接选择 Hook 目标应用，无需修改源码。

## 模块用途

该模块在目标应用加载时，会 hook：

- `Camera1Enumerator.getSupportedFormats(int)`：清空静态缓存并记录结果。
- Camera2 `getCameraIdList` / `openCamera`：将外置摄像头排在列表最前并优先打开。
- Camera1 `Camera.open()` / `Camera.open(int)`：重定向到外置摄像头。
- `Camera.Parameters.getSupportedPreviewFpsRange` / `getSupportedPictureSizes`：为 UVC 摄像头提供兜底数据。
- `Camera.setParameters`：忽略 UVC 摄像头可能抛出的异常。

## UI 界面：选择 Hook 目标应用

安装 APK 后，桌面上会出现 **"UVC Camera Hook 目标选择"** 图标，点击即可打开应用选择界面：

- **查看当前目标**：顶部区域显示当前已选择的 Hook 目标。
- **搜索**：支持按应用名称或包名搜索。
- **选择应用**：点击列表中的任意应用，即将其设为 Hook 目标；已选应用会以蓝色标签高亮显示。
- **Hook 所有应用**：点击"清除选择"按钮，模块将对所有应用生效（不限定目标）。

> 修改选择后，**重启目标应用**（或重载 Xposed 框架）使新设置生效。

## 构建步骤

1. 将 Xposed/LSPosed API jar 放入 `app/libs/xposed-api.jar`，或使用 Maven 依赖 `de.robv.android.xposed:api:82`（需 jitpack 仓库）。
2. 运行 `./gradlew assembleRelease` 构建 APK。
3. 安装 APK：`adb install -r app/build/outputs/apk/release/app-release.apk`。

## 安装与启用

1. 安装 APK 后，在 Xposed / EdXposed / LSPosed 管理器中启用该模块。
2. 重启设备或重载框架。
3. 打开桌面上的"UVC Camera Hook 目标选择"图标，在 UI 中选择目标应用并保存。
4. 重启目标应用，通过 `adb logcat | grep Camera1EnumeratorHook` 查看 hook 日志。

## 注意事项

- Hook 目标通过 UI 动态选择，无需修改源码。若未选择特定应用，模块将对所有应用生效。
- 目标应用若使用混淆或自定义 ClassLoader，请先确认运行时类名是否匹配。
- `cachedSupportedFormats` 为静态缓存字段；若字段名被混淆或不存在，清空操作会静默失败（已做异常捕获）。
- 多线程场景下清空静态缓存需注意并发安全，`getSupportedFormats` 本身是 `synchronized` 方法。