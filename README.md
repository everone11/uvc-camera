# uvc-camera-xposed

Xposed module template that hooks `com.ss.bytertc.base.media.camera.Camera1Enumerator.getSupportedFormats(int)`:
- **Before** invocation: clears the static field `cachedSupportedFormats` to `null`, forcing re-enumeration on the next call.
- **After** invocation: logs the size of the returned format list via `XposedBridge.log`.

## Build

1. Obtain `xposed-api.jar` (or use the Maven dependency declared in `app/build.gradle`).
2. Run `./gradlew assembleRelease` (or `assembleDebug`).
3. Install the APK: `adb install -r app/build/outputs/apk/release/app-release.apk`.

## Install & Enable

1. Install the built APK on a device running LSPosed / EdXposed.
2. Open the LSPosed / EdXposed manager and enable the module **"uvc-camera-xposed"**.
3. Scope the module to the target application.
4. Reboot (or soft-reboot) the device.
5. Run the target app and check logs: `adb logcat | grep Camera1EnumeratorHook`.

## Configuration

Open `Camera1EnumeratorHook.java` and replace the placeholder `"目标应用包名"` with the actual package name of the target application before building. The placeholder is intentionally left as-is so that you can set it to the correct value for your environment.

## Notes

- The static cache `cachedSupportedFormats` is cleared in `beforeHookedMethod`. Because `getSupportedFormats` is `synchronized`, be aware of potential concurrency considerations in heavily multi-threaded scenarios.
- If the target class is obfuscated or loaded from a custom `ClassLoader`, you may need to adjust the class name or the `classLoader` reference used in the hook.