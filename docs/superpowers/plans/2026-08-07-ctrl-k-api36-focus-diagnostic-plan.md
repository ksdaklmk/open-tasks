# API 36 Ctrl+K Focus Diagnostic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce one API 36 CI artifact that explains why the Ctrl+K search
dialog produces no focused-EditText accessibility event.

**Architecture:** Change only the existing instrumented test. It keeps the
real Activity key dispatch and the real `SearchSurface` dialog, then enriches
the existing timeout with direct dialog-view state and the accessibility events
seen while waiting. A subsequent correction is intentionally not planned until
that artifact distinguishes missing window focus from an event-filter issue.

**Tech Stack:** Kotlin, AndroidX Test, `UiAutomation`, JUnit 4, GitHub Actions.

## Global Constraints

- Modify test code only; do not change product code, workflow logic, formats,
  or the API 37.0 F6 lane.
- Preserve the real Ctrl+K-opens-Search claim. Do not fake focus; retain the
  query-focus assertion whenever the Dialog has real window focus, and use the
  documented capability exception only when it does not.
- Use one fresh CI bench for this diagnostic. A second bench is available only
  for a single correction proven by the diagnostic artifact.
- Follow repository JUnit 4 and Kotlin style; use no new dependencies.
- Before any authorised push, run the local JVM/lint/debug and Android-test
  assembly gates, the workflow verifier, and `git diff --check`.

---

### Task 1: Capture the failed focus transition precisely

**Files:**

- Modify:
  `app/src/androidTest/kotlin/app/opentasks/input/ShortcutRootWiringInstrumentedTest.kt:134-150`
- Test:
  `app/src/androidTest/kotlin/app/opentasks/input/ShortcutRootWiringInstrumentedTest.kt`

**Consumes:** The existing `dialogRoot`, the direct
`ComponentActivity.dispatchKeyEvent` Ctrl+K path, and the existing
`UiAutomation.executeAndWaitForEvent` timeout.

**Produces:** A failing CI assertion that reports dialog attachment/window
focus, the currently focused native view, and the observed accessibility event
types/classes/packages from the attempted focus transition.

- [x] **Step 1: Preserve the existing red reproduction**

  Treat run `31177454111` as the red result: the test reaches line 134 and
  times out after no matching `TYPE_VIEW_FOCUSED` event. Do not alter
  `OpenTasksApp.kt` or `SearchSurface.kt`.

- [x] **Step 2: Add bounded event and native-focus diagnostics to the existing test**

  Import `java.util.concurrent.TimeoutException`. Immediately before the
  existing wait, create `val observedEvents = mutableListOf<String>()`. In the
  event filter, append this bounded description before testing the existing
  focused-EditText predicate:

  ```kotlin
  if (observedEvents.size < 16) {
      observedEvents += "type=${event.eventType}, " +
          "class=${event.className}, package=${event.packageName}"
  }
  ```

  Catch `TimeoutException` and replace it with an `AssertionError` whose
  message contains exactly these direct states:

  ```kotlin
  "dialogAttached=${dialogRoot.isAttachedToWindow}, " +
      "dialogWindowFocused=${dialogRoot.hasWindowFocus()}, " +
      "dialogFocused=${dialogRoot.isFocused}, " +
      "focusedView=${dialogRoot.findFocus()?.javaClass?.name}, " +
      "events=$observedEvents"
  ```

  Keep the original event predicate unchanged. The diagnostic must fail only
  when the existing focus proof fails, so a received matching event still
  passes the test.

- [x] **Step 3: Compile the instrumented test source**

  Run:

  ```bash
  ./gradlew :app:compileDebugAndroidTestKotlin
  ```

  Expected: exit 0 and no Kotlin errors in
  `ShortcutRootWiringInstrumentedTest.kt`.

- [x] **Step 4: Run the required local pre-bench gate**

  Run:

  ```bash
  ./gradlew testDebugUnitTest lintDebug :app:assembleDebug \
    :app:assembleDebugAndroidTest :core:data:assembleDebugAndroidTest
  bash scripts/verify-actions-workflow.sh
  git diff --check
  ```

  Expected: all commands exit 0. This machine has no approved disposable
  device, so the focused Android test remains a CI-bench observation.

- [x] **Step 5: Review the exact diff and request publication authority**

  Confirm only the test and required evidence records changed; preserve the
  pre-existing Stage 3 plan edit, `.kotlin/`, and `artifacts/`. Do not commit,
  push, or start the bench until the user explicitly authorises those GitHub
  actions.

## Plan Self-Review

- Scope coverage: Task 1 implements the design's sole deliverable: a
  test-only snapshot that distinguishes missing focus from a missing event;
  the original predicate remains decisive whenever its real window-focus
  precondition exists.
- No placeholders: every code and verification step names the exact test,
  state fields, command, and expected outcome.
- Type consistency: `AccessibilityEvent` already supplies `eventType`,
  `className`, and `packageName`; `TimeoutException` is the documented
  `executeAndWaitForEvent` timeout type; every native-state property belongs
  to the existing `View` root.

## Result

Bench 1 (`31186660700`) proved the diagnostic's missing-window-focus outcome:
the dialog was attached but `dialogWindowFocused=false`, `dialogFocused=false`,
and no accessibility events arrived. The native focused view was only the
Compose root.

The controller selected a narrow capability-based exception. The local test
now proves Ctrl+K created the Dialog, then uses
`assumeTrue(dialogRoot.hasWindowFocus())` before the original focused-EditText
accessibility assertion. Consequently, only a runner without real Dialog
window focus skips the impossible focus proof; focus-capable runners retain
the original proof and diagnostic. The required local gate, workflow verifier,
and diff check passed. This source and the supporting documents are paused
uncommitted; bench 2 remains unused.
