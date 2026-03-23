# uvcforce

LSPosed module to prefer external UVC cameras (Camera2 + legacy Camera API hooks).

Build locally:
1. Put the appropriate xposed-api.jar into `app/libs/xposed-api.jar`. You can obtain xposed-api.jar from LSPosed/EdXposed module templates or your LSPosed distribution.
2. Run `./gradlew assembleRelease`.
3. Install the APK: `adb install -r app/build/outputs/apk/release/app-release.apk`.

CI / GitHub Actions:
The repository includes a workflow `.github/workflows/android-build.yml` that will attempt to build on push and on manual dispatch. CI attempts to download xposed-api.jar; if that fails, add the jar to `app/libs/` in the repo or adjust the workflow.

Usage:
1. Install the APK and enable the module in LSPosed.
2. Optionally edit `Module.java` and set `TARGET_PACKAGE` to the target app's package name to restrict the hook.
3. Reboot the device and run the target app.
4. Check logs: `adb logcat | grep uvcforce` to see hooking logs and confirmation.

Notes:
- The module hooks Java-layer Camera2 and legacy Camera APIs. Apps that use native HAL paths may not be affected.
- CI produces an unsigned APK. Sign/zipalign locally if needed.