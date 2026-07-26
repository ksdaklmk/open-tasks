---
name: connected-tests
description: Run the Open Tasks instrumented test suites on a connected device or emulator.
disable-model-invocation: true
---

Instrumented tests are not run by CI and require a connected device or emulator.

**Never uninstall the app or wipe emulator data.** The emulator's workspace has already been
migrated v1 to v2, and clearing it destroys the migration state these tests depend on.

Check for a device first (`adb devices`). If none is attached, stop and tell the user.

Full suite:

```bash
./gradlew :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest
```

A single class, when `$ARGUMENTS` names one:

```bash
./gradlew :feature:tasks:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.opentasks.feature.tasks.TaskEditorInstrumentedTest
```

If `$ARGUMENTS` is empty, run the full suite. Report failures with the module, test name, and the
relevant output; reports land in `<module>/build/reports/androidTests/connected/`.
