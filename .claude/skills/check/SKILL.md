---
name: check
description: Run the CI-equivalent gate for Open Tasks - unit tests, Android lint, and a debug assembly. Use before claiming a change is done, or when the user asks to verify, check, or validate the build.
---

Run exactly what CI runs:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
```

Then:

- If everything passes, say so in one line with the task names that ran.
- If anything fails, report the failing module and test/lint id with the relevant output excerpt. Lint results are in `<module>/build/reports/lint-results-debug.html`; unit test reports are in `<module>/build/reports/tests/testDebugUnitTest/`.
- Do not report success unless the command exited 0. Do not summarize passing output at length.

Instrumented tests are not part of this gate — they need a device. Use `/connected-tests` for those.
