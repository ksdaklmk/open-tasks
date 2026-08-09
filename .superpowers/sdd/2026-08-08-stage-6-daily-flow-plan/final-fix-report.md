# Stage 6 final-fix report

## Outcome

All six Important findings from `final-review-report.md` are closed in one
bounded wave. The final scoped checks, Android-test compile checks, full
non-device gate, and whitespace check are green. Room was not changed, the
in-memory forward import now has Room-equivalent one-publish visibility, and
the Task 6 focus/manual-stop behavior was not touched.

## Baseline and scope controls

Before any edit, `git status --short` was:

```text
 M docs/superpowers/plans/2026-07-30-stage-3-google-drive-backup-recovery-plan.md
?? .kotlin/
?? artifacts/
```

Those pre-existing paths were left untouched and are excluded from this
wave's commit. I read `CLAUDE.md`, `docs/architecture.md`, `DESIGN.md`, the
complete final review report, and the relevant production and test sources
before editing. No dependency, schema, backup format, manifest, focus-session,
or unrelated cleanup change was made. No connected, device, ADB, or emulator
command ran.

The implementation followed the repository's existing patterns: the existing
activity-list trimming helper, the parser's existing grammar, Material radio
rows and `IconButton`, Bundle-safe saveable primitives, and the widget's
existing publisher/gate structure. No general-purpose abstraction or new
dependency was introduced.

## Finding 1 — forward in-memory import published partial states

### Root cause

`InMemoryVaultRepository` published projects/statuses first, tasks/tags next,
and then one state per project/task activity. Writer serialization did not
make those separate `StateFlow` publications atomic to observers, unlike
Room's enclosing database transaction.

### RED

The regression test was added first. It observes after the initial state,
imports a new project, its default statuses, one tag, and two tasks, then
requires one mutually consistent emission.

```text
$ ./gradlew :core:data:testDebugUnitTest \
    --tests "*InMemoryImportTasksTest.forwardImportPublishesOneCompleteSnapshot"
InMemoryImportTasksTest > forwardImportPublishesOneCompleteSnapshot FAILED
Expected: 1
Actual:   5
BUILD FAILED
```

### Fix

The already-preflighted import plan is now folded into final project, status,
task, tag, and bounded activity collections locally. A single `publish(...)`
writes that complete snapshot. Receipts reuse the activity IDs already
allocated by the plan, so no follow-up `recordActivity` publication is needed.
The existing `appendedActivity` path preserves per-owner activity trimming.

### GREEN

```text
$ ./gradlew :core:data:testDebugUnitTest --tests "*InMemoryImportTasks*"
BUILD SUCCESSFUL in 287ms
57 actionable tasks: 1 from cache, 56 up-to-date
```

The new test also asserts that the sole emission contains the created project,
its statuses, both tasks, the tag assignments, and all receipt activity IDs.

## Finding 2 — natural-date locale and invalid 12-hour input

### Root cause

English grammar-token normalization did not state its locale contract
explicitly, and AM/PM conversion attempted adjustment without first enforcing
the grammar's source-hour range of `1..12`. That admitted `0am`, `0pm`,
`13am`, and `13pm` whenever the adjusted value happened to form a `LocalTime`.

### RED

The Turkish-locale, invalid-hour, and pinned grammar regressions were added
before the parser change.

```text
$ ./gradlew :core:domain:testDebugUnitTest --tests "*NaturalDateParser*"
9 tests completed, 1 failed
NaturalDateParserTest > twelveHourTimesRejectHoursOutsideOneThroughTwelve FAILED
0am: expected null but was NaturalDateMatch(...)
BUILD FAILED
```

The Turkish-locale assertion was already green on this Kotlin runtime because
its no-argument lowercase implementation is invariant. The production code
still now names `Locale.ROOT` explicitly, which pins the required contract and
avoids relying on that implicit library behavior.

### Fix

Both matched tokens use `lowercase(Locale.ROOT)`, and AM/PM parsing returns
null unless the source hour is in `1..12`. Compact tests also pin last-valid-
match wins, future time-only today, literal `today` at 17:00 after 17:00, and
`in N weeks`.

### GREEN

```text
$ ./gradlew :core:domain:testDebugUnitTest --tests "*NaturalDateParser*"
BUILD SUCCESSFUL in 305ms
35 actionable tasks: 1 from cache, 34 up-to-date
```

## Finding 3 — stale widget action authorization

### Root cause

Action dispatch used the publisher's asynchronously updated render cache as
authority. A callback could also capture the active publisher before `stop()`
and execute later because the write gate guarded Glance state, not repository
commands. During the first fix pass, self-review found a narrower interleaving:
the generation was read before live authorization, so stop could invalidate
while a live-authority read was in flight and the old callback could still
dispatch.

### RED

The deterministic JVM test file was added first against the missing action
gate:

```text
$ ./gradlew :app:testDebugUnitTest --tests "*WidgetActionGateTest"
e: unresolved reference: WidgetActionGate
Execution failed for task ':app:compileDebugUnitTestKotlin'.
BUILD FAILED
```

After the initial implementation, an additional deterministic interleaving
test held live authorization open, invalidated the captured generation, then
released authorization. It proved the self-review race before the final gate
ordering fix:

```text
$ ./gradlew :app:testDebugUnitTest \
    --tests "*WidgetActionGateTest.invalidationDuringLiveAuthorizationPreventsDispatch"
1 test completed, 1 failed
WidgetActionGateTest > invalidationDuringLiveAuthorizationPreventsDispatch FAILED
java.lang.AssertionError at WidgetActionGateTest.kt:90
BUILD FAILED in 3s
```

### Fix

`TodayWidgetPublisher` now receives a live predicate backed directly by
`AppLockSettings.titlePrivacy` and `AppLockController.locked.value`. Capturing
or dispatching a task completion checks that predicate; the render cache is no
longer action authority.

An atomic action generation is captured with the callback and invalidated by
privacy concealment and synchronously inside the active-publisher stop lock.
The live predicate is evaluated before the generation's final atomic read, so
an invalidation that happens while authorization is evaluated is observed.
Only a current generation, live authority, and a freshly recomputed
completable Today row can execute `CompleteTask`. `StopGatedWriter` remains
unchanged and continues to solve only Glance-write ordering.

Four deterministic JVM tests now cover live revocation before validation,
publisher-stop generation invalidation, invalidation during authorization, and
one exactly-once authorized completion using a real Today projection.

### GREEN

```text
$ ./gradlew :app:testDebugUnitTest --tests "*Widget*"
BUILD SUCCESSFUL in 652ms
199 actionable tasks: 1 executed, 198 up-to-date
```

## Finding 4 — Markdown project selection lacked selected semantics

### Root cause

The picker rendered every project as an identical `TextButton`. Selection only
enabled the distant Export button; there was no radio indicator, selected
presentation, `selected` semantics, or radio role.

### RED

The Compose regression was written before the production change. On the old
UI it expects the first and then second row to expose `Role.RadioButton`, move
selected/unselected semantics, and export the second `ProjectId`.

Per the wave's explicit compile-only rule for instrumented tests, that runtime
RED was not executed on a device. The old-source failure is direct: neither
`selectable`, `Role.RadioButton`, `RadioButton`, nor selected state was present.
The pre-fix test compile completed successfully, confirming it was a real
runnable Compose test rather than a compile-failure surrogate:

```text
$ ./gradlew :feature:more:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL
```

### Fix

Each row now reuses the repository's existing full-width, minimum-48-dp
`selectable(selected, role = Role.RadioButton)` pattern with a `RadioButton`
indicator and body text. No new component was introduced.

### GREEN

```text
$ ./gradlew :feature:more:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL in 264ms
30 actionable tasks: 30 up-to-date
```

## Finding 5 — Quick Add clear target and no-op suggestion clear

### Root cause

The clear action was a bare clickable icon nested inside the AssistChip, so it
had no Material 48-dp target. While a parsed suggestion was unapplied, its
handler only cleared the already-null applied due; `suggestion?.due` kept the
chip visible and the action was a no-op.

### RED

The Compose regressions were written first. They require a 48-by-48-dp clear
target, an unapplied clear that removes the chip without changing the title,
and apply-then-clear submission with a null due date.

The explicit instrumented-test compile-only constraint prohibited running the
old UI on a device. The expected runtime RED is caused by the old icon-sized
click target and unchanged `suggestion?.due`. The pre-fix test compile was
green:

```text
$ ./gradlew :app:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL
```

### Fix

The chip and clear action are now siblings. Clear uses Material `IconButton`.
For an unapplied parse, one saveable title key suppresses the suggestion while
that title is unchanged; editing the title permits parsing again. For an
applied suggestion, clear removes the saved epoch and zone. The title is never
silently altered by clear.

### GREEN

```text
$ ./gradlew :app:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL in 306ms
188 actionable tasks: 188 up-to-date
```

## Finding 6 — Quick Add lost an applied due on recreation

### Root cause

The title survived recreation through `rememberSaveable`, but `appliedDue`
used plain `remember`. Applying a suggestion stripped the source phrase, so
recreation lost both the moment and the text needed to reconstruct it.

### RED

The restoration regression was written before production changes. It enters
`tomorrow 4pm`, applies it, emulates saved-instance-state restoration, submits,
and requires the stripped title plus the exact expected instant and zone.

As required, instrumented tests were compile-only in this wave. The old runtime
RED was therefore not executed; it follows directly from the old plain
`remember` value resetting to null while the saveable stripped title remains.
The pre-fix `:app:compileDebugAndroidTestKotlin` command was green.

### Fix

The applied moment is stored as two Bundle-safe `rememberSaveable` primitives:
epoch milliseconds and zone ID. `ZonedMoment` is reconstructed locally; the
domain model was not made Parcelable and no Saver abstraction was added.

### GREEN

The same final app Android-test compile shown for finding 5 is green and
includes `ProcessRestorationInstrumentedTest`.

## Final focused verification

The exact scoped commands required by the final review report were rerun after
the last production edit:

```text
./gradlew :core:domain:testDebugUnitTest --tests "*NaturalDateParser*"
  BUILD SUCCESSFUL in 305ms

./gradlew :core:data:testDebugUnitTest --tests "*InMemoryImportTasks*"
  BUILD SUCCESSFUL in 287ms

./gradlew :app:testDebugUnitTest --tests "*Widget*"
  BUILD SUCCESSFUL in 652ms

./gradlew :app:compileDebugAndroidTestKotlin
  BUILD SUCCESSFUL in 306ms

./gradlew :feature:more:compileDebugAndroidTestKotlin
  BUILD SUCCESSFUL in 264ms
```

No connected/device task was invoked; the two instrumented suites were
compile-only as required.

## Full non-device gate

The full gate was rerun after the final widget interleaving fix:

```text
$ ./gradlew testDebugUnitTest lintDebug :app:assembleDebug
BUILD SUCCESSFUL in 33s
550 actionable tasks: 23 executed, 2 from cache, 525 up-to-date
Configuration cache entry reused.
```

Final whitespace verification:

```text
$ git diff --check
<no output>
exit 0
```

## Scoped self-review

I reread the complete bounded diff after the final GREEN runs and checked each
finding against the review's root-cause and acceptance language:

1. Forward in-memory import has exactly one publish and retains bounded
   activity trimming and preallocated receipt IDs. Room remains unchanged.
2. Parser normalization is explicitly root-locale and source AM/PM hours are
   restricted before conversion; all requested pinned cases are covered.
3. Widget actions use live authority. Privacy and stop invalidate captured
   generations synchronously, including invalidation during authority
   evaluation; stale callbacks cannot reach the command recorder in the JVM
   race tests.
4. Markdown rows have a visible radio indicator, a radio role, and moving
   selected semantics; Export dispatch is pinned to the selected project.
5. Quick Add clear is a separate Material target and is effective in both
   unapplied and applied states without deleting title text.
6. Applied due instant and zone survive saved-state recreation as Bundle-safe
   primitives.

No new Critical or Important issue was found. The wave does not change Task 6
focus ownership, reconcile, generic timer Stop, or banner Stop behavior. It
does not change dependencies, Room schema/version, backup/import wire formats,
or any deferred review item.

## Files changed by this wave

- `.superpowers/sdd/2026-08-08-stage-6-daily-flow-plan/final-fix-report.md`
- `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt`
- `app/src/androidTest/kotlin/app/opentasks/QuickAddSheetInstrumentedTest.kt`
- `app/src/main/kotlin/app/opentasks/QuickAddSheet.kt`
- `app/src/main/kotlin/app/opentasks/di/AppModule.kt`
- `app/src/main/kotlin/app/opentasks/widget/TodayWidget.kt`
- `app/src/test/kotlin/app/opentasks/widget/WidgetActionGateTest.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryImportTasksTest.kt`
- `core/domain/src/main/kotlin/app/opentasks/core/domain/NaturalDateParser.kt`
- `core/domain/src/test/kotlin/app/opentasks/core/domain/NaturalDateParserTest.kt`
- `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/BackupRecoveryScreenInstrumentedTest.kt`
- `feature/more/src/main/kotlin/app/opentasks/feature/more/BackupRecoveryScreen.kt`

## Concerns and remaining evidence

There is no unresolved code concern within this fix wave. The Markdown and
Quick Add regressions were compiled but not run because device/instrumented
execution was explicitly prohibited here. Their runtime proof, along with the
real locked-widget flow and the unchanged Task 6 manual-stop product behavior,
remains part of Task 14's separately authorized device evidence.
