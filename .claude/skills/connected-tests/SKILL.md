---
name: connected-tests
description: Run the Open Tasks instrumented test suites on a connected device or emulator.
disable-model-invocation: true
---

CI runs the instrumented suites on API 36 and 37. Local runs require a
connected device or emulator and are still required for device-specific
diagnosis and visual acceptance.

**Never uninstall the app or wipe emulator data.** The `Pixel_10_Pro_Fold`
AVD holds a protected workspace migrated v1 to v6 plus the signed-in Google
account the Stage 3 credentialed gate needs; clearing either destroys state
these tests depend on.

**`:app:connectedDebugAndroidTest` uninstalls `app.opentasks`.** Run connected
suites only against a sole disposable ADB target started with `-read-only
-no-snapshot-load -no-snapshot-save`, never against the normal emulator.

Check for a device first (`adb devices`). If none is attached, stop and tell the user.

Full suite (matches CI):

```bash
./gradlew :app:connectedDebugAndroidTest \
  :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest
```

A single class, when `$ARGUMENTS` names one:

```bash
./gradlew :feature:tasks:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.opentasks.feature.tasks.TaskEditorInstrumentedTest
```

If `$ARGUMENTS` is empty, run the full suite. Report failures with the module, test name, and the
relevant output; reports land in `<module>/build/reports/androidTests/connected/`.
