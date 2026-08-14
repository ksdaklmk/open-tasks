# Stage 8 Planning Surfaces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the approved Stage 8 planning surfaces: a six-week Month view,
exact single-task rescheduling with complete non-drag access, a bounded project
Timeline, and an opt-in private daily digest; then qualify signed-sideload
release 1.2.0 (`versionCode = 3`).

**Architecture:** Room v9 and backup v1 remain the only durable workspace
authorities. Month and Timeline are pure `WorkspaceSnapshot` projections in
`:core:domain` with semantic values in `:core:model`; `:app` computes them and
feature modules render plain values and emit callbacks. One exact
`SetTaskSchedule` command mutates task schedule plus reminder atomically in both
repositories, while the existing full-editor `UpdateTask` is widened with
`start`. Only Board-proven root-coordinate drag mechanics move to
`:core:designsystem`. Daily digest state is one device-local SharedPreferences
file and one inexact one-shot alarm; it stores no workspace content.

**Tech Stack:** Existing Kotlin/AGP, Java `java.time`, Room, SQLCipher, Jetpack
Compose Material 3, Hilt, Android `SharedPreferences`, `AlarmManager`, AndroidX
notifications, JUnit 4, and Compose UI test v2. No new dependency, permission,
worker, service, route, schema, backup record, or Drive scope.

## Global Constraints

- Authority:
  `docs/superpowers/specs/2026-08-14-stage-8-planning-surfaces-design.md` and
  the Stage 8 scope/uniform gates in
  `docs/superpowers/specs/2026-08-10-stage-7-9-roadmap-design.md`. Read the
  current `HANDOFF.md` before every resumed execution session.
- Work directly on `main`; do not create a branch, worktree, or pull request.
  Before Task 1, require this plan, its approved spec status, and its HANDOFF
  checkpoint to exist in a committed docs-only change. Record that commit's
  full 40-character SHA as `stage8BaseSha` in the ignored ledger
  `.superpowers/sdd/2026-08-14-stage-8-planning-surfaces-plan/progress.md`.
- Preserve unrelated user work. At planning time that is the modified
  historical Stage 3 plan, deleted user-owned pinfo spec, and untracked
  `.kotlin/` and `artifacts/`. Never stage, restore, delete, or reformat them.
- Room stays at version 9. Do not edit an entity/DAO schema, migration,
  exported schema, backup record family/codec/version, fixture bytes,
  `.otvault` format, key material, backup XML, dependency catalogue, Drive
  scope, or any existing permission declaration.
- The one permitted manifest delta is a non-exported, no-filter
  `DailyDigestReceiver`. All 12 other manifests, the app permission set, and
  every existing activity/provider/service/receiver intent filter must remain
  base-byte-identical.
- Feature modules retain their present dependency boundary: model and design
  system only. They never import `:core:domain`, repository, Hilt, Android
  persistence, or alarm APIs. `OpenTasksApp` owns projection and command
  mapping.
- Keep `DomainCommand.RescheduleTasks` and its Tasks/Review callers unchanged.
  It remains due-only bulk behaviour. `SetTaskSchedule` is only for explicit
  single-task rescheduling. Editor autosave emits one widened `UpdateTask`; it
  never splits schedule and other fields across commands.
- `SetTaskSchedule` preflights every field before mutation; Room writes task
  and reminder inside one transaction, in-memory publishes once, revision
  advances exactly once, and the existing snapshot diff emits one journal
  generation with the exact ordered TASK then REMINDER entries. Undo is the
  same command carrying the previous exact schedule/reminder and is produced
  only by repositories.
- Schedule date placement is `start ?: due` in that moment's stored zone.
  Day-drop rules are exact: undated becomes due 18:00 in the current device
  zone; single moments use `ZonedDateTime.ofLocal` with the old offset as the
  preferred overlap offset; start+due uses equal calendar-day deltas and each
  stored-zone `plusDays`. Java gap-forward/overlap resolution is the authority.
- `Clock` supplies testable instants, not a permanently captured device zone.
  Root planning actions and time-version projection refreshes resolve the
  current zone through an injectable provider defaulting to
  `ZoneId::systemDefault`.
- Month is Monday-first and exactly 42 cells. It includes all non-binned dated
  tasks, including completed; only open tasks drag. Cell semantics expose full
  dates and exact total/completed/open-overdue counts. Dots are decorative,
  capped at six, and `6+` marks overflow.
- Timeline is read-only and exactly 84 days starting Monday. It includes all
  non-binned tasks for the selected project, preserves each schedule moment's
  stored-zone date, uses inclusive spans, explicit clipping/outside/invalid/
  unscheduled states, and traverses the complete non-binned dependency graph
  with visited sets bounded by active task count. `Task.isBlocked` is the
  marker authority; cross-project summary counts unique task ids, not edges.
- Digest preferences are exactly file `daily_digest` and keys `enabled`,
  `minute_of_day`, and optional `last_handled_epoch_day`. They contain only
  Boolean/Int/Long. Invalid state fails closed; disabled state retains handled
  day. Scheduling uses one stable immutable explicit broadcast PendingIntent
  and `setAndAllowWhileIdle`, never exact alarm APIs.
- Digest delivery marks today handled and re-arms before vault lookup or
  notification. Missing vault, permission/channel denial, `SecurityException`,
  and zero counts do not retry that day. Projection always passes
  `titlesPermitted = false`; private content has counts only and the public
  version is generic. No catch-up delivery exists.
- New copy lives in the owning feature/app resource file, uses UK English,
  day-month dates, Monday-first weeks, and 24-hour time. Interactive targets
  are at least 48 dp. Colour is never the only meaning; decorative dots are
  absent from semantics; drag has a complete tap/menu path.
- Use existing fixtures locally; do not expand `OpenTasksFixtures` merely for
  convenience. Tests use JUnit 4 `org.junit.Assert.*`, no mocking library,
  `runBlocking` plus `withTimeout(5_000)` where suspending, camelCase behaviour
  names, and `androidx.compose.ui.test.junit4.v2.createComposeRule`.
- No device test runs before Task 15. Earlier tasks run JVM tests and compile
  instrumented sources only. Task 15 alone owns the audited, sole,
  read-only/no-snapshot `Fold8_Acceptance` overlay and must never boot, install
  to, mutate, or terminate `Pixel_10_Pro_Fold`.
- End every implementation task with focused checks, `git diff --check`, exact
  staged-name audit, a conventional commit, and an independent review. Fix and
  re-review findings inside that task boundary before continuing.
- Stage 7's app-lock, secure-cleanup, and candidate-CI waivers do not carry
  forward. Stage 8 requires all three unless the user gives a fresh explicit
  waiver. GitHub Free only: if included runners are unavailable, stop and ask;
  never add payment, metered spend, larger runners, Codespaces, or another
  paid GitHub service.

## Scope Check

Keep one integrated plan. Month, rescheduling, Timeline, and digest are
separate review boundaries, but they share one release, root composition,
accessibility/privacy audit, connected gate, and signed artefact. Splitting
them into separate plans would duplicate those gates and permit a partially
qualified Stage 8. Tasks 1–14 remain independently committed and reviewed;
Task 15 is the single controller-owned whole-stage gate.

## File Structure

### Core model, domain, and data

- `core/model/src/main/kotlin/app/opentasks/core/model/Planning.kt` — Month
  semantic projection values.
- `core/model/src/main/kotlin/app/opentasks/core/model/ProjectTimeline.kt` —
  project presentation, bounded Timeline rows/markers, and dependency roles.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/SchedulePlanning.kt` —
  pure Month and exact schedule-move planning rules.
- `core/domain/src/test/kotlin/app/opentasks/core/domain/ScheduleMonthProjectionTest.kt`
  — 42-cell placement/count boundaries.
- `core/domain/src/test/kotlin/app/opentasks/core/domain/TaskScheduleRulesTest.kt`
  — all date/tray/reminder/DST move shapes.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt` —
  widened `UpdateTask` and new `SetTaskSchedule` command.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/RecurringTaskPlanner.kt`
  — start-aware recurrence metadata preservation.
- `core/domain/src/test/kotlin/app/opentasks/core/domain/RecurringTaskPlannerTest.kt`
  — start/due metadata regressions.
- `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
  and `RoomVaultRepository.kt` — identical schedule validation, mutation, and
  Undo behaviour.
- `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`,
  `InMemoryBulkCommandTest.kt`, and `RetiredBlobSetTest.kt` — in-memory parity
  plus all forced `UpdateTask` call-site updates.
- `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`
  — Room transaction, restart, generation ordering, rejection, and Undo parity.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/ProjectTimelineProjection.kt`
  and matching `ProjectTimelineProjectionTest.kt` — bounded span/milestone and
  full-graph dependency projection.

### Design system and features

- `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/PlanningDrag.kt`
  and matching `PlanningDragTest.kt` — only root-coordinate state, hit test,
  source gesture, and absolute preview placement.
- `feature/projects/src/main/kotlin/app/opentasks/feature/projects/BoardView.kt`
  and `BoardViewInstrumentedTest.kt` — migrated consumer and regression proof;
  Board targets, cards, menus, accessibility, and edge scrolling stay local.
- `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleScreen.kt`
  — stateless Week/Month shell and existing agenda/week rendering.
- `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/MonthCalendar.kt`
  — compact/expanded 42-cell Month rendering.
- `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleReschedule.kt`
  — date picker, remove action, and reminder-removal confirmation.
- `feature/schedule/src/androidTest/kotlin/app/opentasks/feature/schedule/ScheduleScreenInstrumentedTest.kt`
  — mode, Month, fallback, drag, callback, RTL, and semantics coverage.
- `feature/schedule/src/main/res/values/strings.xml` — Schedule/Month copy.
- `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt` —
  widened `TaskEdit`, saveable start state, native date/time controls, and
  due-before-start validation.
- `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskEditorInstrumentedTest.kt`
  and `feature/tasks/src/main/res/values/strings.xml` — editor coverage/copy.
- `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectTimelineView.kt`
  and matching `ProjectTimelineViewInstrumentedTest.kt` — bounded Gantt-lite UI.
- `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`,
  `ProjectWorkbenchInstrumentedTest.kt`, and module strings — third presentation
  and existing milestone/task-editor routing.
- `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`,
  `DailyDigestSettingsInstrumentedTest.kt`, and module strings — inline opt-in,
  native 24-hour picker, and notification guidance.

### App, system integration, and release records

- `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt` and
  `app/src/test/kotlin/app/opentasks/WorkspaceProjectViewStateTest.kt` —
  SavedState-backed per-project presentation/Timeline anchor/selection.
- `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt` — saveable Schedule state,
  app-owned projections, move-to-command mapping, Timeline callbacks, digest
  state, and Home/task routing.
- `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt`
  — Schedule and project planning-state restoration.
- `app/src/main/kotlin/app/opentasks/digest/DailyDigestSystem.kt` — exact
  preferences, timing/notification planning, alarm, coordinator, notifier, and
  delivery receiver.
- `app/src/test/kotlin/app/opentasks/digest/DailyDigestSystemTest.kt` and
  `app/src/androidTest/kotlin/app/opentasks/digest/DailyDigestSystemInstrumentedTest.kt`
  — pure sequencing plus Android preference/channel/manifest/notification proof.
- `app/src/main/kotlin/app/opentasks/di/AppModule.kt` — one singleton preference
  store provider.
- `app/src/main/kotlin/app/opentasks/OpenTasksApplication.kt` — channel creation
  and process-start reconciliation.
- `app/src/main/kotlin/app/opentasks/MainActivity.kt` — foreground reconcile and
  generic Home-open signal behind app lock.
- `app/src/main/kotlin/app/opentasks/reminders/ReminderSystem.kt` — reuse the
  existing system-event receiver for digest reconcile before vault lookup.
- `app/src/main/AndroidManifest.xml` — the only new component declaration.
- `app/src/main/res/values/strings.xml` — root schedule-rejection and private
  digest channel/notification copy.
- `app/build.gradle.kts` — final 1.2.0 (3) version bump only.
- `docs/qualification/stage8-planning-surfaces.md` and
  `docs/qualification/release-1.2.0-sideload.md` — stage/release evidence.
- `docs/architecture.md`, `DESIGN.md`, `PRODUCT.md`, `docs/threat-model.md`,
  `CLAUDE.md`, and `HANDOFF.md` — post-implementation contract and handoff.

---

### Task 1: Add the pure 42-cell Month projection

**Files:**

- Create: `core/model/src/main/kotlin/app/opentasks/core/model/Planning.kt`
- Create: `core/domain/src/main/kotlin/app/opentasks/core/domain/SchedulePlanning.kt`
- Create: `core/domain/src/test/kotlin/app/opentasks/core/domain/ScheduleMonthProjectionTest.kt`

**Interfaces:**

```kotlin
data class ScheduleMonthProjection(
    val month: YearMonth,
    val days: List<ScheduleMonthDay>,
)

data class ScheduleMonthDay(
    val date: LocalDate,
    val inSelectedMonth: Boolean,
    val tasks: List<Task>,
    val totalCount: Int,
    val completedCount: Int,
    val overdueCount: Int,
    val densityDotCount: Int,
    val hasDensityOverflow: Boolean,
)

fun computeScheduleMonthProjection(
    snapshot: WorkspaceSnapshot,
    selectedMonth: YearMonth,
    clock: Clock,
    displayZone: ZoneId,
): ScheduleMonthProjection
```

- [ ] **Step 1: Write the failing projection tests**

Use fixture copies with fixed revisions and clocks. Implement these exact test
methods; each asserts complete values, not merely collection size:

```kotlin
@Test fun monthIsMondayFirstAndAlwaysContainsFortyTwoDays()
@Test fun startPrecedesDueAndPlacementUsesItsStoredZone()
@Test fun dueFallbackUsesStoredZoneAcrossDisplayBoundary()
@Test fun completedRemainsAndBinnedIsExcluded()
@Test fun countsCompletedAndExactOpenOverdueWhileDensityCapsAtSix()
@Test fun tasksInEachCellSortByPlacementLocalTimeThenTitle()
```

The first test uses February 2026 and asserts `days.first().date ==
2026-01-26`, `days.last().date == 2026-03-08`, seven columns per
`days.chunked(7)`, and Monday/Sunday edges. The zone tests put equal instants on
different stored-zone dates. The density test supplies seven tasks, one
completed and one open-overdue, then asserts total 7, completed 1, overdue 1,
dot count 6, and overflow true.

- [ ] **Step 2: Run the RED test**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.ScheduleMonthProjectionTest"
```

Expected: compilation fails because the model and projection do not exist.

- [ ] **Step 3: Add the semantic values and smallest projection**

Use one placement helper and the existing due classifier:

```kotlin
private const val MONTH_CELL_DOT_CAP = 6

fun computeScheduleMonthProjection(
    snapshot: WorkspaceSnapshot,
    selectedMonth: YearMonth,
    clock: Clock,
    displayZone: ZoneId,
): ScheduleMonthProjection {
    val firstDate = selectedMonth.atDay(1)
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val projectionClock = Clock.fixed(clock.instant(), displayZone)
    val tasksByDate = snapshot.tasks
        .asSequence()
        .filter { it.deletedAt == null }
        .mapNotNull { task -> task.scheduleDate()?.let { it to task } }
        .groupBy({ it.first }, { it.second })

    return ScheduleMonthProjection(
        month = selectedMonth,
        days = List(42) { offset ->
            val date = firstDate.plusDays(offset.toLong())
            val tasks = tasksByDate[date].orEmpty().sortedWith(
                compareBy<Task>(
                    { task ->
                        (task.start ?: task.due)?.let { moment ->
                            moment.instant.atZone(moment.zone()).toLocalTime()
                        } ?: LocalTime.MAX
                    },
                    { it.title.lowercase(Locale.UK) },
                    { it.id.value },
                ),
            )
            ScheduleMonthDay(
                date = date,
                inSelectedMonth = YearMonth.from(date) == selectedMonth,
                tasks = tasks,
                totalCount = tasks.size,
                completedCount = tasks.count(Task::isCompleted),
                overdueCount = tasks.count {
                    !it.isCompleted && classifyDueBucket(it.due, projectionClock) == DueBucket.OVERDUE
                },
                densityDotCount = minOf(tasks.size, MONTH_CELL_DOT_CAP),
                hasDensityOverflow = tasks.size > MONTH_CELL_DOT_CAP,
            )
        },
    )
}

private fun Task.scheduleDate(): LocalDate? = (start ?: due)?.let { moment ->
    moment.instant.atZone(moment.zone()).toLocalDate()
}
```

Do not add a Month repository, cache, paging layer, or feature-domain
dependency.

- [ ] **Step 4: Run the projection test GREEN**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.ScheduleMonthProjectionTest"
```

Expected: PASS.

- [ ] **Step 5: Check, stage, commit, and review Task 1**

```bash
git diff --check
git add core/model/src/main/kotlin/app/opentasks/core/model/Planning.kt \
  core/domain/src/main/kotlin/app/opentasks/core/domain/SchedulePlanning.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/ScheduleMonthProjectionTest.kt
git diff --cached --name-only
git commit -m "feat: project schedule months"
```

Expected staged names: exactly the three files above. Dispatch an independent
review against Section 2 of the spec; fix and re-review before Task 2.

---

### Task 2: Plan every single-task schedule move in one pure rule

**Files:**

- Modify: `core/domain/src/main/kotlin/app/opentasks/core/domain/SchedulePlanning.kt`
- Create: `core/domain/src/test/kotlin/app/opentasks/core/domain/TaskScheduleRulesTest.kt`

**Interfaces:**

```kotlin
sealed interface ScheduleMoveTarget {
    data class Day(val date: LocalDate) : ScheduleMoveTarget
    data class Unscheduled(val reminderRemovalConfirmed: Boolean) : ScheduleMoveTarget
}

enum class ScheduleMoveFailure {
    TASK_NOT_MOVABLE,
    REMINDER_IDENTITY_MISMATCH,
    DUE_BEFORE_START,
    RECURRENCE_REQUIRES_SCHEDULE,
    RECURRENCE_COUNT_AND_END_DATE,
    RECURRENCE_END_BEFORE_SCHEDULE,
    REMINDER_REQUIRES_DUE,
    REMINDER_IN_PAST,
}

sealed interface ScheduleMovePlan {
    data object NoChange : ScheduleMovePlan
    data object ReminderRemovalConfirmationRequired : ScheduleMovePlan
    data class Ready(
        val start: ZonedMoment?,
        val due: ZonedMoment?,
        val reminder: Reminder?,
    ) : ScheduleMovePlan
    data class Rejected(val failure: ScheduleMoveFailure) : ScheduleMovePlan
}

fun planTaskScheduleMove(
    task: Task,
    reminder: Reminder?,
    target: ScheduleMoveTarget,
    now: Instant,
    displayZone: ZoneId,
): ScheduleMovePlan

fun validateTaskScheduleState(
    taskId: TaskId,
    start: ZonedMoment?,
    due: ZonedMoment?,
    recurrence: RecurrenceRule?,
    reminder: Reminder?,
    now: Instant,
    allowPastReminder: Boolean = false,
): ScheduleMoveFailure?

fun ScheduleMoveFailure.toCommandRejection(): CommandResult.Rejected
```

- [ ] **Step 1: Write the complete failing move matrix**

Create these exact tests:

```kotlin
@Test fun undatedMoveUsesEighteenHundredInDisplayZone()
@Test fun dueOnlyPreservesLocalTimeZoneAndPreferredOverlapOffset()
@Test fun startOnlyPreservesLocalTimeZoneAndGapMovesForward()
@Test fun startAndDueShiftTheSameCalendarDeltaAcrossDifferentZones()
@Test fun startAndDuePlusDaysPinsDstGapAndOverlapBehaviour()
@Test fun sameSourceMoveReturnsNoChange()
@Test fun sameSourceWithAnUnchangedPastReminderReturnsNoChange()
@Test fun reminderLeadIdentityAndPrecisionMoveWithDue()
@Test fun moveRejectsReminderAtOrBeforeNow()
@Test fun moveRejectsDueBeforeStart()
@Test fun validationRejectsMismatchedReminderIdentity()
@Test fun validationRejectsRecurrenceWithoutSchedule()
@Test fun validationRejectsRecurrenceCountAndEndDateTogether()
@Test fun validationRejectsRecurrenceEndBeforeSchedule()
@Test fun validationRejectsReminderWithoutDue()
@Test fun scheduleFailuresMapToStableCommandRejections()
@Test fun trayRequiresReminderConfirmationThenClearsTogether()
@Test fun recurringTaskCannotMoveToTray()
@Test fun completedAndBinnedTasksCannotMove()
```

For the DST assertions, use `America/New_York`: 02:30 on the 2026 spring gap
resolves to 03:30; an overlap retains the prior offset when valid. Assert exact
instants and zone ids. Assert reminder lead with `Duration.between(oldTrigger,
oldDue)` and that the new trigger uses the new due zone. Every rejection test
asserts its exact `ScheduleMoveFailure`, not only that a rejection occurred.

- [ ] **Step 2: Run the RED move test**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.TaskScheduleRulesTest"
```

Expected: compilation fails on the missing move API.

- [ ] **Step 3: Implement the date resolver and four source shapes**

```kotlin
private fun ZonedMoment.onDate(targetDate: LocalDate): ZonedMoment {
    val current = instant.atZone(zone())
    val moved = ZonedDateTime.ofLocal(
        targetDate.atTime(current.toLocalTime()),
        current.zone,
        current.offset,
    )
    return ZonedMoment(moved.toInstant(), zoneId)
}

private fun ZonedMoment.plusCalendarDays(days: Long): ZonedMoment {
    val moved = instant.atZone(zone()).plusDays(days)
    return ZonedMoment(moved.toInstant(), zoneId)
}
```

Implement `validateTaskScheduleState` once for reminder identity/due/future,
recurrence-needs-schedule/end-date, and start-before-due invariants. Then
implement `planTaskScheduleMove` with this order:

```kotlin
fun validateTaskScheduleState(
    taskId: TaskId,
    start: ZonedMoment?,
    due: ZonedMoment?,
    recurrence: RecurrenceRule?,
    reminder: Reminder?,
    now: Instant,
    allowPastReminder: Boolean = false,
): ScheduleMoveFailure? {
    val anchor = due ?: start
    val endBeforeAnchor = recurrence?.endDate?.let { endDate ->
        anchor?.let { moment ->
            endDate.isBefore(moment.instant.atZone(moment.zone()).toLocalDate())
        }
    } == true
    return when {
        reminder != null && (
            reminder.taskId != taskId || reminder.id != Reminder.primaryId(taskId)
        ) -> ScheduleMoveFailure.REMINDER_IDENTITY_MISMATCH
        start != null && due != null && due.instant.isBefore(start.instant) ->
            ScheduleMoveFailure.DUE_BEFORE_START
        recurrence != null && anchor == null ->
            ScheduleMoveFailure.RECURRENCE_REQUIRES_SCHEDULE
        recurrence?.count != null && recurrence.endDate != null ->
            ScheduleMoveFailure.RECURRENCE_COUNT_AND_END_DATE
        endBeforeAnchor -> ScheduleMoveFailure.RECURRENCE_END_BEFORE_SCHEDULE
        reminder != null && due == null -> ScheduleMoveFailure.REMINDER_REQUIRES_DUE
        reminder != null && !allowPastReminder &&
            !reminder.triggerAt.instant.isAfter(now) ->
            ScheduleMoveFailure.REMINDER_IN_PAST
        else -> null
    }
}
```

Add one public `ScheduleMoveFailure.toCommandRejection()` mapping beside the
validator so both repository engines return the same exact rejection. All
failures use `RejectionReason.INVALID_STATE` except `REMINDER_IN_PAST`. Map the
eight enum values in declaration order to these messages:

1. `Only open tasks can be rescheduled.`
2. `That reminder does not belong to this task.`
3. `Due time cannot be before start time.`
4. `A repeating task needs a start or due time.`
5. `Choose either an occurrence count or an end date.`
6. `The repeat end date cannot be before the schedule.`
7. `Add a due date before setting a reminder.`
8. `Choose a reminder time in the future.`

Then implement the planner in this order:

1. reject completed or binned tasks;
2. for `Unscheduled`, reject recurrence, require confirmation when a reminder
   exists, then target three nulls;
3. for `Day`, apply exactly the four approved source-shape rules;
4. when due moves, retain reminder id/task id/precision and apply its old lead;
5. compare the proposed start/due/reminder with the current exact fields and
   return `NoChange` before validation when all three are unchanged;
6. call the shared validator and return its exact failure as `Rejected`, or
   return `Ready`.

The movable-task check and target-shape rules precede equality; only unchanged
target-state validation is skipped. This lets a same-source drop snap back
without rejecting an unchanged past reminder or legacy-invalid schedule.

The start+due delta is `ChronoUnit.DAYS.between(sourceStartDate,
target.date)`; do not compute an instant duration. The undated case is:

```kotlin
val dueAt = target.date.atTime(18, 0).atZone(displayZone)
val due = ZonedMoment(dueAt.toInstant(), displayZone.id)
```

- [ ] **Step 4: Run both Schedule rule suites GREEN**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.ScheduleMonthProjectionTest" \
  --tests "app.opentasks.core.domain.TaskScheduleRulesTest"
```

Expected: PASS.

- [ ] **Step 5: Check, stage, commit, and review Task 2**

```bash
git diff --check
git add core/domain/src/main/kotlin/app/opentasks/core/domain/SchedulePlanning.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/TaskScheduleRulesTest.kt
git diff --cached --name-only
git commit -m "feat: plan exact task schedule moves"
```

Review specifically for all callers having one rule, exact DST resolver,
past-reminder behaviour, and no repository/UI coupling.

---

### Task 3: Compute the bounded project Timeline and dependency context

**Files:**

- Create: `core/model/src/main/kotlin/app/opentasks/core/model/ProjectTimeline.kt`
- Create: `core/domain/src/main/kotlin/app/opentasks/core/domain/ProjectTimelineProjection.kt`
- Create: `core/domain/src/test/kotlin/app/opentasks/core/domain/ProjectTimelineProjectionTest.kt`

**Interfaces:**

```kotlin
enum class ProjectPresentation { LIST, BOARD, TIMELINE }
const val PROJECT_TIMELINE_DAY_COUNT = 84

data class ProjectTimelineWindow(val firstDate: LocalDate) {
    init { require(firstDate.dayOfWeek == DayOfWeek.MONDAY) }
    val lastDate: LocalDate = firstDate.plusDays(PROJECT_TIMELINE_DAY_COUNT - 1L)
}

enum class ProjectTimelineWindowSide { BEFORE, AFTER }
enum class ProjectTimelineMarkerKind { START, DUE }
enum class ProjectTimelineDependencyRole {
    NONE, SELECTED, PREREQUISITE, DEPENDANT, PREREQUISITE_AND_DEPENDANT,
}

sealed interface ProjectTimelineTaskPlacement {
    data class Span(
        val firstVisibleDayIndex: Int,
        val lastVisibleDayIndex: Int,
        val totalDayCount: Long,
        val continuesBefore: Boolean,
        val continuesAfter: Boolean,
    ) : ProjectTimelineTaskPlacement
    data class Marker(val dayIndex: Int, val kind: ProjectTimelineMarkerKind) :
        ProjectTimelineTaskPlacement
    data class Outside(val side: ProjectTimelineWindowSide) :
        ProjectTimelineTaskPlacement
    data object InvalidRange : ProjectTimelineTaskPlacement
    data object Unscheduled : ProjectTimelineTaskPlacement
}

data class ProjectTimelineTaskRow(
    val task: Task,
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
    val placement: ProjectTimelineTaskPlacement,
    val dependencyRole: ProjectTimelineDependencyRole,
)

data class ProjectTimelineMilestoneMarker(val milestone: Milestone, val dayIndex: Int)

data class ProjectTimelineProjection(
    val projectId: ProjectId,
    val window: ProjectTimelineWindow,
    val taskRows: List<ProjectTimelineTaskRow>,
    val milestoneMarkers: List<ProjectTimelineMilestoneMarker>,
    val milestonesBeforeWindow: Int,
    val milestonesAfterWindow: Int,
    val selectedTaskId: TaskId?,
    val outOfProjectDependencyTaskCount: Int,
)

fun computeProjectTimelineProjection(
    snapshot: WorkspaceSnapshot,
    projectId: ProjectId,
    window: ProjectTimelineWindow,
    selectedTaskId: TaskId? = null,
): ProjectTimelineProjection
```

- [ ] **Step 1: Write the full RED projection suite**

```kotlin
@Test fun windowIsMondayAlignedAndAlwaysContainsExactlyTwelveWeeks()
@Test fun projectFilteringKeepsCompletedAndExcludesBinAndOtherProjects()
@Test fun storedZoneDatesDriveInclusiveSpansAndStartDueMarkers()
@Test fun spansClipAtEitherOrBothEdgesAndOutsideDatesStayOutside()
@Test fun instantOrLocalDateReversalProducesInvalidRange()
@Test fun milestonesUseInclusiveEdgesAndExactDatedBeforeAfterCounts()
@Test fun dependencyContextTraversesBothDirectionsAcrossProjectsUniquely()
@Test fun dependencyContextTerminatesOnDefensiveCycleAtSnapshotTaskBound()
@Test fun missingOrOutOfProjectSelectionProducesNoDependencyContext()
```

Use local fixture copies. The span test asserts inclusive indices and total
day count. The milestone test asserts undated milestones are absent from
markers/counts but remain untouched in the input snapshot. The graph tests
build a diamond plus cross-project node and a cycle, then assert exact role for
each in-project id and one unique external count.

- [ ] **Step 2: Run RED**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.ProjectTimelineProjectionTest"
```

Expected: model/projection missing.

- [ ] **Step 3: Implement bounded placement with stored-zone dates**

Filter `task.projectId == projectId && task.deletedAt == null`; retain
completed. Convert start and due independently with `moment.zone()`. Rules:

- both moments and either due instant or stored-zone local date before the
  corresponding start value => InvalidRange;
- valid both => inclusive span, clamp visible indices to 0..83, retain total
  day count and both continuation flags;
- one moment => in-window labelled marker or explicit Before/After;
- neither => Unscheduled;
- valid span wholly before/after => explicit Outside.

Milestone window edges are inclusive. Count only dated project milestones
strictly before/after; map in-window dates to 0..83.

- [ ] **Step 4: Traverse prerequisites and dependants once**

Build `activeTasksById` from all non-binned snapshot tasks and a reverse
dependant index from `dependencyIds`. Use two `ArrayDeque<TaskId>` traversals,
two visited sets, and stop after at most `activeTasksById.size` unique ids per
direction. Never traverse `blockedBy`; it is only `Task.isBlocked`'s immediate
unfinished state. Derive each project row's selected/prerequisite/dependant/
both role and count the union of related ids whose project differs, excluding
the selected task itself. A selected id is valid only when it names one of the
projection's non-binned project rows; otherwise normalise it to null, leave all
roles `NONE`, and report zero external tasks.

- [ ] **Step 5: Run GREEN and check the fixed bound**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.ProjectTimelineProjectionTest"
rg -n "PROJECT_TIMELINE_DAY_COUNT|ArrayDeque|blockedBy" \
  core/model/src/main/kotlin/app/opentasks/core/model/ProjectTimeline.kt \
  core/domain/src/main/kotlin/app/opentasks/core/domain/ProjectTimelineProjection.kt
```

Expected: PASS; bound is 84; traversal does not use `blockedBy`.

- [ ] **Step 6: Stage, commit, and review Task 3**

Stage exactly the three files, run `git diff --check`, then:

```bash
git commit -m "feat: project bounded timelines"
```

Review stored-zone conversion, every placement state, inclusive bounds,
completed/Bin handling, graph direction/uniqueness/cycle termination, and no
write path.

---

### Task 4: Add the atomic schedule command and widen the editor save boundary

**Files:**

- Modify: `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- Modify: `core/domain/src/main/kotlin/app/opentasks/core/domain/RecurringTaskPlanner.kt`
- Modify: `core/domain/src/test/kotlin/app/opentasks/core/domain/RecurringTaskPlannerTest.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify: `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`
- Modify: `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryBulkCommandTest.kt`
- Modify: `core/data/src/test/kotlin/app/opentasks/core/data/RetiredBlobSetTest.kt`
- Modify: `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`
- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`

**Interfaces:**

```kotlin
data class UpdateTask(
    val taskId: TaskId,
    val title: String,
    val description: String,
    val projectId: ProjectId?,
    val priority: Priority,
    val start: ZonedMoment?,
    val due: ZonedMoment?,
    val recurrence: RecurrenceRule?,
    val estimate: Duration?,
    val milestoneId: MilestoneId? = null,
    val recurrenceMetadata: RecurrenceSeriesMetadata? = null,
    val restoreStatusId: WorkflowStatusId? = null,
    val reminder: Reminder? = null,
    val restorePastReminder: Boolean = false,
) : DomainCommand

data class SetTaskSchedule(
    val taskId: TaskId,
    val start: ZonedMoment?,
    val due: ZonedMoment?,
    val reminder: Reminder?,
    val restorePastReminder: Boolean = false,
) : DomainCommand
```

- [ ] **Step 1: Write RED in-memory parity tests**

Extend the existing repository test file with:

```kotlin
@Test fun setTaskScheduleUpdatesExactFieldsOnceAndUndoRestoresExactValues()
@Test fun setTaskScheduleReplacesAndRemovesReminderInOneGeneration()
@Test fun setTaskSchedulePreservesRecurrenceMetadata()
@Test fun setTaskScheduleRejectsMismatchedReminderIdentityWithoutMutation()
@Test fun setTaskScheduleRejectsReminderWithoutDue()
@Test fun setTaskScheduleRejectsRecurringTrayTarget()
@Test fun setTaskScheduleRejectsCountAndEndDateWithoutMutation()
@Test fun setTaskScheduleRejectsEndBeforeSchedule()
@Test fun setTaskScheduleRejectsDueBeforeStart()
@Test fun setTaskScheduleRejectsPastReminderWithoutPartialWrite()
@Test fun setTaskScheduleRejectsCompletedAndBinnedWithoutMutation()
@Test fun setTaskScheduleUndoRestoresPastReminderMetadata()
@Test fun setTaskSchedulePastReminderNoOpDoesNotValidateAdvanceOrJournal()
```

Capture the full snapshot, task revision, journal generation count, and
activity list before every rejection/no-op. Assert all remain exact. For Undo,
execute only the returned command, then assert prior start/due/reminder fields
and a past reminder's metadata are restored exactly.

- [ ] **Step 2: Write the Room parity declarations before production code**

Add matching instrumented methods to
`RoomVaultRepositoryInstrumentedTest.kt`. The success test must close/reopen
the database, then assert one generation whose changed families are exactly
`[TASK, REMINDER]` in canonical order. Compile-only until Task 15.

- [ ] **Step 3: Run RED and capture the exhaustive-dispatch failures**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "app.opentasks.core.data.InMemoryVaultRepositoryTest" \
  :core:data:compileDebugAndroidTestKotlin
```

Expected: command/API compilation fails and both sealed dispatches become
non-exhaustive.

- [ ] **Step 4: Widen `UpdateTask` without a default and audit every caller**

Add required `start` immediately before `due`; no default is allowed because a
silent null would clear starts at old call sites. Update all constructors found
by:

```bash
rg -n "DomainCommand\.UpdateTask\(" --glob '*.kt'
```

Add `start` to both validation paths, equality checks, task copies, inverse
builders, `TaskEdit`, `Task.toTaskEdit`, and `TaskEdit.toCommand`. Until Task 5
adds controls, `TaskEdit`'s editor value carries the live `task.start` unchanged
so this commit cannot clear it.

Widen recurrence metadata to make start part of change detection:

```kotlin
fun metadataForUpdate(
    task: Task,
    start: ZonedMoment?,
    due: ZonedMoment?,
    rule: RecurrenceRule?,
): RecurrenceSeriesMetadata? {
    if (rule == null) return null
    val anchor = due ?: start ?: return null
    if (task.recurrence == rule && task.start == start && task.due == due &&
        task.recurrenceSeriesId != null && task.recurrenceAnchor != null &&
        task.recurrenceOccurrenceIndex != null
    ) {
        return RecurrenceSeriesMetadata(
            task.recurrenceSeriesId,
            task.recurrenceAnchor,
            task.recurrenceOccurrenceIndex,
        )
    }
    return RecurrenceSeriesMetadata(task.id, anchor, initialOccurrenceIndex(anchor, rule))
}
```

- [ ] **Step 5: Implement identical command preflight in both engines**

Before the first write, resolve task/existing reminder and reject completed or
binned explicit-reschedule targets with `TASK_NOT_MOVABLE`. Next compare the
command's exact start/due/reminder fields with storage and return the existing
no-op success when unchanged. The parity test uses an unchanged past reminder
and proves no validation rejection, revision, write, journal entry, or Undo.

Only a changed target calls `validateTaskScheduleState` for:

- exact reminder id and task id;
- reminder requires due;
- recurring task requires start or due;
- recurrence count and end date are mutually exclusive;
- recurrence end is not before the stored-zone local date of `due ?: start`;
- due instant is not before start instant;
- changed reminder is after `now`, except repository-produced Undo.

Normal UI code must never set `restorePastReminder`; add a KDoc stating that.
`SetTaskSchedule` never normalises a mismatched reminder identity. Existing
`UpdateTask` may retain its current editor-facing normalisation, then calls the
same schedule-state validator so both engines and the pure move rule cannot
drift on schedule invariants.
Convert each failure with the one public `toCommandRejection()` mapping before
returning; neither engine duplicates rejection reasons or messages.
Preserve the editor's existing unchanged-past-reminder behaviour:
`UpdateTask` passes `allowPastReminder = command.restorePastReminder ||
requestedReminder == existingReminder`; `SetTaskSchedule` passes only its
repository-produced `restorePastReminder` flag. That flag bypasses solely the
future check.

- [ ] **Step 6: Mutate once and return same-command Undo**

The in-memory success shape is:

```kotlin
val updated = task.copy(
    start = command.start,
    due = command.due,
    revision = nextRevision(task),
)
publish(
    tasks = current.tasks.map { if (it.id == task.id) updated else it },
    reminders = current.reminders.filterNot { it.taskId == task.id } +
        listOfNotNull(command.reminder),
)
return CommandResult.Success(
    message = "Schedule updated",
    undo = DomainCommand.SetTaskSchedule(
        taskId = task.id,
        start = task.start,
        due = task.due,
        reminder = existingReminder,
        restorePastReminder = true,
    ),
)
```

Room performs task upsert and reminder delete/upsert inside one existing
`database.withTransaction`. Do not record activity. An exact no-op returns
success with no Undo, revision, write, or journal generation through Step 5's
pre-validation short-circuit. Add the command to direct dispatch and
`UndoBatch` preflight/execution in both engines.

- [ ] **Step 7: Pin unchanged bulk behaviour and start-aware full updates**

Extend the existing bulk test with a start-bearing task and assert
`RescheduleTasks` changes only due. Extend the existing full-update/Undo tests
in both engines to change and restore start atomically. Add recurrence tests
that preserve metadata only when start, due, and rule are all unchanged.

- [ ] **Step 8: Run the focused GREEN checks**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.RecurringTaskPlannerTest" \
  :core:data:testDebugUnitTest \
  --tests "app.opentasks.core.data.InMemoryVaultRepositoryTest" \
  --tests "app.opentasks.core.data.InMemoryBulkCommandTest" \
  :core:data:compileDebugAndroidTestKotlin \
  :feature:tasks:compileDebugKotlin \
  :app:compileDebugKotlin
```

Expected: all JVM tests and Room/app/feature source compilation pass.

- [ ] **Step 9: Prove no old constructor or split editor save remains**

```bash
rg -n "DomainCommand\.UpdateTask\(" --glob '*.kt'
rg -n "SetTaskSchedule|TaskEdit\(" core app feature
git diff --check
```

Inspect every hit: each `UpdateTask` passes `start`; the only
`SetTaskSchedule` constructions are tests/repository Undo until Task 8; the
editor still emits one command.

- [ ] **Step 10: Stage, commit, and review Task 4**

Stage exactly the eleven files in this task's **Files** block, audit staged
names, then:

```bash
git commit -m "feat: update task schedules atomically"
```

Require independent review of both engines, all exhaustive dispatch paths,
single revision/generation, exact Undo, recurrence metadata, and unchanged
bulk behaviour before Task 5.

---

### Task 5: Complete start and due editing inside the existing autosave

**Files:**

- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
- Modify: `feature/tasks/src/main/res/values/strings.xml`
- Modify: `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskEditorInstrumentedTest.kt`

**Interfaces:**

- `TaskEdit.start` remains part of the one existing debounced value.
- Start and due each expose a date action and 24-hour time action.
- No new callback, command, ViewModel, store, or editor save coroutine is added.

- [ ] **Step 1: Write the failing editor behaviours**

Add these exact methods using the existing editor helper and captured
`AtomicReference<TaskEdit>`:

```kotlin
@Test fun startDateAndTimeDefaultToNineAndAutoSaveTogether()
@Test fun existingStartAndDueControlsPreserveStoredZones()
@Test fun newDueDateStillDefaultsToSeventeenAndTimeCanBeChanged()
@Test fun dueBeforeStartShowsWarningAndSuppressesAutoSave()
@Test fun legacyInvalidScheduleRemainsVisibleUntilCorrected()
```

Assert the date/time controls are each at least 48 dp. For the atomic test,
change start plus title inside one debounce window and assert exactly one
`TaskEdit` contains both values. For the legacy case, pass a task whose due
instant precedes start, assert both values and warning render, then correct due
and assert one valid save.

- [ ] **Step 2: Compile RED**

```bash
./gradlew :feature:tasks:compileDebugAndroidTestKotlin
```

Expected: new tags/copy and start/time controls do not exist.

- [ ] **Step 3: Add saveable start state and exact validation**

Mirror due state without creating a second editor object:

```kotlin
var startEpochMillis by rememberSaveable(task.id.value) {
    mutableStateOf(task.start?.instant?.toEpochMilli())
}
var startZoneId by rememberSaveable(task.id.value) {
    mutableStateOf(task.start?.zoneId)
}
val editorStart = startEpochMillis?.let {
    ZonedMoment(Instant.ofEpochMilli(it), startZoneId ?: ZoneId.systemDefault().id)
}
```

Add `start = editorStart` to `editorValue`, restore both fields in the existing
repository-sync effect, and include:

```kotlin
val scheduleError = if (
    editorStart != null && editorDue != null &&
    editorDue.instant.isBefore(editorStart.instant)
) {
    stringResource(R.string.task_schedule_due_before_start)
} else null

val valid = titleError == null && !descriptionError &&
    recurrenceError == null && scheduleError == null
```

An invalid legacy task remains readable because validation only blocks save;
do not coerce, clear, or reorder its values.
Update recurrence validation to require `editorDue ?: editorStart`, and compare
an end date to that anchor's stored-zone local date. Reminder continues to
require due. This matches the shared repository validator without coupling the
feature to domain code.

- [ ] **Step 4: Add native date/time actions without changing zones**

Use the existing Material date picker convention. For time construct the
platform `android.app.TimePickerDialog` with `is24HourView = true` and emit the
selected `LocalTime`. Centralise only the
moment-component replacement already needed twice:

```kotlin
private fun ZonedMoment.withLocal(
    date: LocalDate = instant.atZone(zone()).toLocalDate(),
    time: LocalTime = instant.atZone(zone()).toLocalTime(),
): ZonedMoment {
    val current = instant.atZone(zone())
    val changed = ZonedDateTime.ofLocal(date.atTime(time), current.zone, current.offset)
    return ZonedMoment(changed.toInstant(), zoneId)
}
```

New start date uses 09:00 in `ZoneId.systemDefault()`. New due date keeps 17:00
in that zone. Existing date changes preserve time/zone; existing time changes
preserve date/zone. Clearing due always clears its reminder and clears
recurrence only when start is also null. Clearing start clears recurrence only
when due is also null. Put all new visible copy in resources.

- [ ] **Step 5: Compile the UI and run all host checks affected by the model**

```bash
./gradlew :feature:tasks:compileDebugAndroidTestKotlin \
  :feature:tasks:compileDebugKotlin \
  :app:compileDebugKotlin \
  :core:data:testDebugUnitTest \
  --tests "app.opentasks.core.data.InMemoryVaultRepositoryTest"
```

Expected: PASS/compile; no device run.

- [ ] **Step 6: Stage, commit, and review Task 5**

```bash
git diff --check
git add feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt \
  feature/tasks/src/main/res/values/strings.xml \
  feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskEditorInstrumentedTest.kt
git diff --cached --name-only
git commit -m "feat: edit task start and due times"
```

Review the single-save boundary, state restoration, zones/DST, recurrence and
reminder coupling, 48 dp targets, and invalid legacy visibility.

---

### Task 6: Extract only Board-proven root drag mechanics

**Files:**

- Create: `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/PlanningDrag.kt`
- Create: `core/designsystem/src/test/kotlin/app/opentasks/core/designsystem/PlanningDragTest.kt`
- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/BoardView.kt`
- Modify: `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/BoardViewInstrumentedTest.kt`

**Interfaces:**

```kotlin
data class RootDragState<T>(
    val payload: T,
    val sourceBounds: Rect,
    val startInRoot: Offset,
    val accumulatedOffset: Offset = Offset.Zero,
) {
    val positionInRoot: Offset
    fun movedBy(delta: Offset): RootDragState<T>
}

fun <T> dragTargetAt(
    positionInRoot: Offset,
    targets: Iterable<T>,
    bounds: Map<T, Rect>,
    eligible: (T) -> Boolean = { true },
): T?

@Composable
fun Modifier.rootLongPressDragSource(
    key: Any?,
    enabled: Boolean = true,
    onStart: (Offset, Rect) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
): Modifier

@Composable
fun BoxScope.RootDragPreview(
    state: RootDragState<*>,
    containerBounds: Rect,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)
```

- [ ] **Step 1: Write the small pure RED check**

`PlanningDragTest` must contain:

```kotlin
@Test fun movedStateAccumulatesRootPositionWithoutLayoutDirectionMath()
@Test fun targetHitTestUsesIterationOrderEligibilityAndExactBounds()
```

The first asserts two deltas and source-root start. The second asserts an
ineligible overlapping target is skipped and outside returns null.

- [ ] **Step 2: Run RED**

```bash
./gradlew :core:designsystem:testDebugUnitTest \
  --tests "app.opentasks.core.designsystem.PlanningDragTest"
```

Expected: missing drag API.

- [ ] **Step 3: Move the minimum mechanics, not a framework**

Move from `BoardView.kt` only source bounds, accumulated offset, fresh
callbacks, hit testing, and preview root-to-container translation. Implement
the preview with `absoluteOffset`, because pointer/root coordinates are already
physical and must not be mirrored a second time in RTL. Keep Board-local:

- status targets and task payload shape;
- edge scrolling and scroll state;
- cards, columns, menus, accessibility actions, and eligibility;
- move callback and sibling preview content.

Do not add drag registries, controllers, composition locals, animation state,
or a generic target composable.

- [ ] **Step 4: Migrate Board as the regression consumer**

Use a local payload such as `BoardDragPayload(task, sourceStatusId)`. Preserve
the existing `rememberUpdatedState(onMoveTask)` and invoke the fresh callback
only when the eligible target differs from the source. Add Compose regressions:

```kotlin
@Test fun outsideTargetSnapsBackWithoutCallback()
@Test fun rtlPreviewUsesAbsoluteRootCoordinatesAndIsNotClipped()
```

Retain the existing menu, custom-action, drag, callback-replacement, and
preview tests unchanged in meaning.

- [ ] **Step 5: Run GREEN/compile checks**

```bash
./gradlew :core:designsystem:testDebugUnitTest \
  --tests "app.opentasks.core.designsystem.PlanningDragTest" \
  :feature:projects:compileDebugAndroidTestKotlin \
  :feature:projects:compileDebugKotlin
```

Expected: PASS/compile; no connected test.

- [ ] **Step 6: Stage, commit, and review Task 6**

Stage exactly the four files, run `git diff --check`, then:

```bash
git commit -m "refactor: share root drag mechanics"
```

Review for API minimality, callback freshness, no RTL double mirror, no Board
behaviour loss, and no feature-specific type leaking into designsystem.

---

### Task 7: Render stateless Week and Month presentations

**Files:**

- Modify: `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleScreen.kt`
- Create: `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/MonthCalendar.kt`
- Modify: `feature/schedule/src/main/res/values/strings.xml`
- Modify: `feature/schedule/src/androidTest/kotlin/app/opentasks/feature/schedule/ScheduleScreenInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt`

**Interfaces:**

```kotlin
enum class SchedulePresentation { WEEK, MONTH }

@Composable
fun ScheduleScreen(
    tasks: List<Task>,
    projectNames: Map<ProjectId, String>,
    expanded: Boolean,
    presentation: SchedulePresentation,
    selectedDate: LocalDate,
    month: ScheduleMonthProjection,
    onPresentationChange: (SchedulePresentation) -> Unit,
    onSelectedDateChange: (LocalDate) -> Unit,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
    reminders: List<Reminder> = emptyList(),
    today: LocalDate = LocalDate.now(),
    calendarEligibleTaskIds: Set<TaskId> = emptySet(),
    onAddToCalendar: (TaskId) -> Unit = {},
)
```

- [ ] **Step 1: Add failing stateless Month tests**

Add exact methods:

```kotlin
@Test fun weekAndMonthControlsMeetFortyEightDpAndEmitPresentation()
@Test fun monthShowsFortyTwoSelectableCellsIncludingMutedAdjacentDates()
@Test fun monthCellSemanticsExposeExactCountsAndLabelledOverdue()
@Test fun selectingMonthCellUpdatesAgendaWithoutOpeningTask()
@Test fun selectedAgendaTaskOpensExistingTask()
@Test fun expandedMonthShowsAgendaAndOpenUnscheduledTray()
```

Pass a literal `ScheduleMonthProjection`; the feature test must not compute it.
Assert the merged semantics sentence exactly, e.g. `Monday, 17 August 2026. 7
tasks, 1 completed, 1 overdue.` Decorative dot nodes must have no independent
content descriptions.

Add `schedulePresentationMonthAndSelectedDateRestore` and
`replacementZoneProviderChangesProjectionZoneWithoutChangingNow` to the
existing root restoration test. The first enters Month, chooses an
adjacent-month cell, recreates, and asserts presentation, displayed month, and
selected day restore independently. The second switches a mutable zone
provider behind a fixed-instant clock and asserts the refreshed root date/zone.

- [ ] **Step 2: Compile RED**

```bash
./gradlew :feature:schedule:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestKotlin
```

Expected: missing Month API/tags.

- [ ] **Step 3: Hoist all projection inputs to `OpenTasksApp`**

Use `rememberSaveable` strings for presentation name, selected ISO date, and
displayed `YearMonth`; parse with safe fallbacks. Capture one projection clock
per time-version refresh. Add
`zoneProvider: () -> ZoneId = ZoneId::systemDefault` to `OpenTasksApp`; tests
pass a mutable provider. Derive clocks with the existing clock's instant source
and the provider's live zone:

```kotlin
internal fun currentDeviceClock(
    clock: Clock,
    zoneProvider: () -> ZoneId,
): Clock = clock.withZone(zoneProvider())

val scheduleMonth = remember(
    snapshot, scheduleMonthValue, clock, zoneProvider, timeVersion,
) {
    val currentClock = currentDeviceClock(clock, zoneProvider)
    computeScheduleMonthProjection(
        snapshot = snapshot,
        selectedMonth = scheduleMonthValue,
        clock = Clock.fixed(currentClock.instant(), currentClock.zone),
        displayZone = currentClock.zone,
    )
}
```

Use the same refreshed current-device clock for existing root due/arrangement
projections and values passed into root-owned date consumers so new Month does
not disagree with Home/Tasks after a live zone change. The replacement-provider
regression switches zones behind one fixed-instant clock and proves the instant
is unchanged while the projected local date/zone changes.

Expanded Week Previous/Next move selected date by one week; compact Week keeps
the existing previous/next-day behaviour. `OpenTasksApp` chooses that delta
from the same `expanded` layout value passed to the feature. Month
Previous/Next move displayed month by one and select the same clamped
day-of-month. Today resets both selected date and displayed month. Selecting an
adjacent cell changes selected date but does not change the displayed month
until navigation or mode sync. Switching into Month syncs displayed month to
selected date.

- [ ] **Step 4: Render the exact compact and expanded Month shapes**

`MonthCalendar.kt` renders weekday headers plus `month.days.chunked(7)`. Cells
are selectable 48 dp minimum, adjacent dates muted, completed/overdue warning
non-colour-labelled, dots decorative, and overflow textual. Compact stacks
grid then selected-day agenda. Expanded puts grid beside selected agenda and
the existing open-only unscheduled tray. Only agenda rows open tasks; cells
select dates.

Keep current compact Week (day agenda) and expanded Week semantics. Remove the
feature's internal selected-date `rememberSaveable`; it now consumes values and
callbacks only.
Reuse `DotRunBar(progress = 1f, unitCount =
day.densityDotCount.toLong(), maxDots = 6)` for the decorative density run and
clear its semantics; do not add another dot primitive.

- [ ] **Step 5: Compile all changed roots GREEN**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.ScheduleMonthProjectionTest" \
  :feature:schedule:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin
```

Expected: PASS/compile.

- [ ] **Step 6: Stage, commit, and review Task 7**

Stage exactly the six files, run `git diff --check`, then:

```bash
git commit -m "feat: add month schedule view"
```

Review projection ownership, independent adjacent-date selection, fixed 42
cells, completed/open-tray rules, exact semantics, and process restoration.

---

### Task 8: Add the complete 48 dp rescheduling fallback first

**Files:**

- Create: `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleReschedule.kt`
- Modify: `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleScreen.kt`
- Modify: `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/MonthCalendar.kt`
- Modify: `feature/schedule/src/main/res/values/strings.xml`
- Modify: `feature/schedule/src/androidTest/kotlin/app/opentasks/feature/schedule/ScheduleScreenInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**

```kotlin
onRescheduleTask: (TaskId, LocalDate) -> Unit = { _, _ -> }
onRemoveTaskSchedule: (TaskId) -> Unit = {}
```

- [ ] **Step 1: Write fallback RED tests before pointer drag**

```kotlin
@Test fun compactWeekReschedulePickerEmitsTaskAndDate()
@Test fun monthAgendaUsesTheSameReschedulePicker()
@Test fun removeWithoutReminderEmitsImmediately()
@Test fun removeWithReminderRequiresConfirmationAndCancelDoesNothing()
@Test fun recurringTaskHasNoRemoveAction()
@Test fun completedAndBinnedTasksExposeNoRescheduleAction()
@Test fun ordinaryTapStillOpensTask()
```

Assert every Reschedule/Remove/confirm target is at least 48 dp. Use the
platform date picker through the existing Android test interaction precedent;
assert callbacks, not repository state.

- [ ] **Step 2: Compile RED**

```bash
./gradlew :feature:schedule:compileDebugAndroidTestKotlin
```

Expected: missing fallback UI/callbacks.

- [ ] **Step 3: Implement one reusable feature-owned action menu**

Every open non-binned schedule/tray row gets Reschedule. A scheduled,
non-recurring open task also gets Remove schedule. Use the native date picker
and preserve ordinary row click. If a reminder exists, Remove opens an
`AlertDialog`; only its confirm callback emits removal. The feature inspects
plain task/reminder values only and never computes moments.

- [ ] **Step 4: Map the fallback through the one pure rule and command**

In `OpenTasksApp`, resolve the current task and its reminder, then:

```kotlin
fun executeScheduleMove(taskId: TaskId, target: ScheduleMoveTarget) {
    val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: return
    val reminder = snapshot.reminders.firstOrNull { it.taskId == taskId }
    val currentClock = currentDeviceClock(clock, zoneProvider)
    when (val plan = planTaskScheduleMove(
        task, reminder, target, currentClock.instant(), currentClock.zone,
    )) {
        is ScheduleMovePlan.Ready -> viewModel.execute(
            DomainCommand.SetTaskSchedule(
                taskId = taskId,
                start = plan.start,
                due = plan.due,
                reminder = plan.reminder,
            ),
        )
        is ScheduleMovePlan.Rejected -> coroutineScope.launch {
            val message = when (plan.failure) {
                ScheduleMoveFailure.TASK_NOT_MOVABLE ->
                    R.string.schedule_move_task_not_movable
                ScheduleMoveFailure.REMINDER_IDENTITY_MISMATCH ->
                    R.string.schedule_move_reminder_changed
                ScheduleMoveFailure.DUE_BEFORE_START ->
                    R.string.schedule_move_due_before_start
                ScheduleMoveFailure.RECURRENCE_REQUIRES_SCHEDULE ->
                    R.string.schedule_move_recurrence_requires_schedule
                ScheduleMoveFailure.RECURRENCE_COUNT_AND_END_DATE ->
                    R.string.schedule_move_recurrence_limit_conflict
                ScheduleMoveFailure.RECURRENCE_END_BEFORE_SCHEDULE ->
                    R.string.schedule_move_recurrence_end_before_schedule
                ScheduleMoveFailure.REMINDER_REQUIRES_DUE ->
                    R.string.schedule_move_reminder_requires_due
                ScheduleMoveFailure.REMINDER_IN_PAST ->
                    R.string.schedule_move_reminder_in_past
            }
            snackbarHostState.showSnackbar(activity.getString(message))
        }
        ScheduleMovePlan.NoChange,
        ScheduleMovePlan.ReminderRemovalConfirmationRequired,
        -> Unit
    }
}
```

Resolve `currentDeviceClock` inside the callback on every invocation, not when
the composable first mounts; an undated move after a live zone change must use
18:00 in the replacement zone.

The removal callback passes `Unscheduled(reminderRemovalConfirmed = true)`;
the feature has already confirmed. Repository rejection still flows through
the existing snackbar/Undo event path. Add the root-owned messages to
`app/src/main/res/values/strings.xml`:

```xml
<string name="schedule_move_task_not_movable">Only open tasks can be rescheduled.</string>
<string name="schedule_move_reminder_changed">Reopen the task before moving it because its reminder changed.</string>
<string name="schedule_move_due_before_start">Set the due time on or after the start time.</string>
<string name="schedule_move_recurrence_requires_schedule">Add a start or due time before repeating this task.</string>
<string name="schedule_move_recurrence_limit_conflict">Choose either an occurrence count or an end date before moving this task.</string>
<string name="schedule_move_recurrence_end_before_schedule">Set the repeat end date on or after the schedule.</string>
<string name="schedule_move_reminder_requires_due">Add a due time before moving this reminder.</string>
<string name="schedule_move_reminder_in_past">Choose a future reminder time before moving this task.</string>
```

- [ ] **Step 5: Run focused pure checks and compile GREEN**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.TaskScheduleRulesTest" \
  :feature:schedule:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin
```

Expected: PASS/compile.

- [ ] **Step 6: Stage, commit, and review Task 8**

Stage exactly the seven files, run `git diff --check`, then:

```bash
git add \
  feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleReschedule.kt \
  feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleScreen.kt \
  feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/MonthCalendar.kt \
  feature/schedule/src/main/res/values/strings.xml \
  feature/schedule/src/androidTest/kotlin/app/opentasks/feature/schedule/ScheduleScreenInstrumentedTest.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt \
  app/src/main/res/values/strings.xml
git diff --cached --name-only
git commit -m "feat: reschedule tasks without drag"
```

Review that every future drag action already has a complete accessible path,
confirmation precedes reminder loss, root arithmetic has one authority, and
snackbar/Undo remain unchanged.

---

### Task 9: Layer pointer drag over the proven fallback

**Files:**

- Modify: `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleScreen.kt`
- Modify: `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/MonthCalendar.kt`
- Modify: `feature/schedule/src/androidTest/kotlin/app/opentasks/feature/schedule/ScheduleScreenInstrumentedTest.kt`

**Interfaces:**

- Reuse Task 6's `RootDragState`, source modifier, target hit test, and preview.
- Reuse Task 8's `onRescheduleTask` / `onRemoveTaskSchedule` callbacks.
- Add no new command, arithmetic, drag controller, or persistence state.

- [ ] **Step 1: Add the drag RED matrix**

```kotlin
@Test fun expandedWeekDragMovesDatedTaskBetweenDays()
@Test fun expandedWeekTrayDragUsesDayAndRemoveCallbacks()
@Test fun monthAgendaDragTargetsVisibleCell()
@Test fun monthTrayDragTargetsVisibleCell()
@Test fun completedTaskIsNotADragSource()
@Test fun sameSourceAndOutsideDropSnapBackWithoutCallback()
@Test fun dragUsesReplacementCallback()
@Test fun previewIsUnclippedAndRtlSafe()
```

Keep compact Week out of pointer tests: it deliberately has no honest target
grid. Compact Month may drag agenda rows to cells; Remove remains its
tray-equivalent fallback.

- [ ] **Step 2: Add feature-local targets and preview**

Expanded Week registers seven day-column bounds plus tray bounds. Month
registers the 42 visible cell bounds and sources only individual agenda/tray
rows. Eligibility is open/non-binned; recurring tasks cannot target tray.
Target collections, scroll handling, task cards, source-date equality, and
tray confirmation remain feature-local.
The drag payload carries the source target already known by the rendering
context (its day column/selected agenda date, or tray); the feature does not
recompute a task date or zone from moments.

On drop:

- day target calls `onRescheduleTask(task.id, date)`;
- tray target calls `onRemoveTaskSchedule(task.id)` only when no reminder;
- tray plus reminder opens the same confirmation from Task 8;
- same-source/outside target clears local drag state only.

Use `rememberUpdatedState` for both callbacks and render `RootDragPreview` as a
sibling overlay outside clipped scroll containers.

- [ ] **Step 3: Compile GREEN without running a device**

```bash
./gradlew :core:designsystem:testDebugUnitTest \
  --tests "app.opentasks.core.designsystem.PlanningDragTest" \
  :feature:schedule:compileDebugAndroidTestKotlin \
  :feature:schedule:compileDebugKotlin \
  :app:compileDebugKotlin
```

Expected: PASS/compile.

- [ ] **Step 4: Stage, commit, and review Task 9**

```bash
git diff --check
git add feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleScreen.kt \
  feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/MonthCalendar.kt \
  feature/schedule/src/androidTest/kotlin/app/opentasks/feature/schedule/ScheduleScreenInstrumentedTest.kt
git diff --cached --name-only
git commit -m "feat: drag tasks across schedule days"
```

Review expanded/compact scope, eligibility, confirmation, outside/no-op,
callback freshness, preview clipping, RTL, and exact reuse of the fallback.

---

### Task 10: Replace saved Board booleans with per-project planning state

**Files:**

- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Delete: `app/src/test/kotlin/app/opentasks/WorkspaceBoardViewStateTest.kt`
- Create: `app/src/test/kotlin/app/opentasks/WorkspaceProjectViewStateTest.kt`

**Interfaces:**

```kotlin
data class ProjectWorkbenchViewState(
    val presentationByProject: Map<ProjectId, ProjectPresentation> = emptyMap(),
    val timelineFirstDateByProject: Map<ProjectId, LocalDate> = emptyMap(),
    val selectedTimelineTaskByProject: Map<ProjectId, TaskId> = emptyMap(),
)

internal class WorkspaceProjectViewState(private val savedStateHandle: SavedStateHandle) {
    val state: StateFlow<ProjectWorkbenchViewState>
    fun setProjectPresentation(projectId: ProjectId, value: ProjectPresentation)
    fun setProjectTimelineFirstDate(projectId: ProjectId, value: LocalDate)
    fun setProjectTimelineSelection(projectId: ProjectId, taskId: TaskId?)
}
```

- [ ] **Step 1: Replace the old state test with RED coverage**

```kotlin
@Test fun presentationAnchorAndDependencySelectionRestorePerProject()
@Test fun legacyBoardIdsRestoreAsBoardPresentation()
@Test fun malformedSavedStateFallsBackWithoutThrowing()
```

Use two projects and assert mutations never insert defaults for the other.
Recreate the state object over the same `SavedStateHandle` and assert exact
maps. Seed the old `projectBoardModeIds` key and assert BOARD restoration.

- [ ] **Step 2: Run RED**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "app.opentasks.WorkspaceProjectViewStateTest"
```

Expected: replacement types/methods missing.

- [ ] **Step 3: Persist only supported scalar/list values**

Retain `projectBoardModeIds` as the Board-id list for backwards restoration and
add `projectTimelineModeIds`. Store alternating `ArrayList<String>` values
under `projectTimelineAnchors` (`projectId`, then epoch-day) and
`projectTimelineSelections` (`projectId`, then task id); do not use a delimiter
that an id could contain and do not use a custom Parcelable. Ignore odd,
blank, invalid-date, non-Monday, or unpaired entries without throwing; the
first valid pair wins over later duplicates. If corrupt saved state lists the
same project as Board and Timeline,
Timeline wins. Setters keep the two mode lists mutually exclusive and require
Monday anchors.

Expose `projectWorkbenchViewState` from `WorkspaceViewModel`, replace
`boardModeProjectIds`, and replace `setBoardMode` with the three exact setters.
Do not persist LIST defaults or a null selection.

- [ ] **Step 4: Run GREEN and compile app callers RED-to-GREEN minimally**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "app.opentasks.WorkspaceProjectViewStateTest" \
  :app:compileDebugKotlin
```

Update `OpenTasksApp` only enough to collect the new flow and derive the same
LIST/BOARD value; Timeline projection/wiring lands in Task 11. Expected: PASS.

- [ ] **Step 5: Stage, commit, and review Task 10**

```bash
git diff --check
git add app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt \
  app/src/test/kotlin/app/opentasks/WorkspaceBoardViewStateTest.kt \
  app/src/test/kotlin/app/opentasks/WorkspaceProjectViewStateTest.kt
git diff --cached --name-only
git commit -m "feat: save project planning views"
```

Review legacy Board restoration, safe decoding, per-project isolation, and no
SharedPreferences/vault persistence.

---

### Task 11: Render and wire the read-only project Timeline

**Files:**

- Create: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectTimelineView.kt`
- Create: `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/ProjectTimelineViewInstrumentedTest.kt`
- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`
- Modify: `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/ProjectWorkbenchInstrumentedTest.kt`
- Modify: `feature/projects/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt`

**Interfaces:**

Replace `boardMode` / `onBoardModeChange` on `ProjectsScreen` with:

```kotlin
presentation: ProjectPresentation = ProjectPresentation.LIST,
timelineProjection: ProjectTimelineProjection? = null,
onPresentationChange: (ProjectPresentation) -> Unit = {},
onTimelinePrevious: () -> Unit = {},
onTimelineToday: () -> Unit = {},
onTimelineNext: () -> Unit = {},
onTimelineTaskSelectionChange: (TaskId?) -> Unit = {},
```

`ProjectTimelineView` additionally receives separate `onOpenTask` and
`onOpenMilestone` callbacks.

- [ ] **Step 1: Add failing stateless Timeline Compose tests**

```kotlin
@Test fun thirdPresentationAndBoundedNavigationEmitStatelessCallbacks()
@Test fun spansMarkersClippingAndInvalidRangesExposeMergedNonColourSemantics()
@Test fun rowSelectionHighlightsChainWhileSeparateOpenActionOpensTask()
@Test fun milestoneDiamondsCountsAndActivationUseExistingEditor()
@Test fun completedBlockedAndUnscheduledRowsRemainVisible()
```

Assert Previous/Today/Next and every Open action are at least 48 dp. Assert
row click changes selection but does not call Open. Assert exact content
descriptions name complete start/due dates, total duration, clipping/outside/
invalid state, completion, blocked state, and dependency role. Decorative dots
and tracks must not announce separately.

Extend root restoration coverage: select Timeline for project A, move four
weeks, select a chain row, switch to B, then recreate and assert A/B isolation.
Extend Task 7's replacement-zone-provider root regression so Timeline Today
uses the replacement zone's current Monday without changing the injected
instant.

- [ ] **Step 2: Compile RED**

```bash
./gradlew :feature:projects:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestKotlin
```

Expected: missing presentation/API/view.

- [ ] **Step 3: Render the 84-day fixed grid with existing dots**

Use a horizontally scrollable 84-cell row with a fixed small day width.
Render spans with unchanged `DotRunBar(progress = 1f, unitCount =
visibleCount.toLong(), maxDots = 84)` positioned from the projection indices.
Render start/due markers
with distinct labelled icons; invalid ranges with warning shape/label; outside
and unscheduled with explicit text; continuation at clipped edges. RTL mirrors
the date-cell row naturally—do not mirror dates or pointer coordinates by hand.

Completed uses muted completed icon/text; blocked uses `task.isBlocked` already
carried by the task. Milestones inside the window are 48 dp diamond actions;
before/after counts are exact; undated and outside milestones remain in the
existing milestone list. Bars/diamonds have no drag modifiers.

- [ ] **Step 4: Change the workbench branch from Boolean to enum**

Render three 48 dp segmented actions. Keep the existing milestone list in all
presentations. Branch exactly:

- LIST: existing grouped task rows and arrangement controls;
- BOARD: existing Board and Board sort control;
- TIMELINE: `ProjectTimelineView`.

Timeline milestone callback sets the existing `milestoneEditorKey`; Timeline
task Open uses the existing `onOpenTask`. Row selection only emits selection.

- [ ] **Step 5: Compute the projection and navigation in `OpenTasksApp`**

For selected project, derive presentation, first date (default current Monday),
and selected task from `ProjectWorkbenchViewState`. Compute:

```kotlin
val timelineProjection = selectedProject?.takeIf {
    presentation == ProjectPresentation.TIMELINE
}?.let { project ->
    computeProjectTimelineProjection(
        snapshot = snapshot,
        projectId = project.id,
        window = ProjectTimelineWindow(firstDate),
        selectedTaskId = selectedTimelineTaskId,
    )
}
```

Previous/Next change first date by exactly four weeks. The default anchor and
Today callback resolve `currentDeviceClock(clock, zoneProvider)` at the
refresh/action point and use that zone's current Monday; never read the
captured `clock.zone`. Presentation/anchor/selection setters go through Task 10
state. Keep List/Board projections unchanged when selected.

- [ ] **Step 6: Compile all Timeline paths GREEN**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.ProjectTimelineProjectionTest" \
  :app:testDebugUnitTest \
  --tests "app.opentasks.WorkspaceProjectViewStateTest" \
  :feature:projects:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin
```

Expected: PASS/compile.

- [ ] **Step 7: Stage, commit, and review Task 11**

Stage exactly the seven files, run `git diff --check`, then:

```bash
git commit -m "feat: add project timeline view"
```

Review presentation parity, fixed navigation, all row placement semantics,
unique graph highlighting, milestone/editor routes, independent Open action,
RTL, completed/blocked/unscheduled visibility, and absence of writes/drag.

---

### Task 12: Add bounded digest preferences, timing, and title-free planning

**Files:**

- Create: `app/src/main/kotlin/app/opentasks/digest/DailyDigestSystem.kt`
- Create: `app/src/test/kotlin/app/opentasks/digest/DailyDigestSystemTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`

**Interfaces:**

```kotlin
data class DailyDigestSettings(
    val enabled: Boolean = false,
    val minuteOfDay: Int = 8 * 60,
    val lastHandledEpochDay: Long? = null,
)

class DailyDigestSettingsStore(private val prefs: SharedPreferences) {
    val state: StateFlow<DailyDigestSettings>
    fun load(): DailyDigestSettings
    fun setEnabled(enabled: Boolean): DailyDigestSettings
    fun setMinuteOfDay(minuteOfDay: Int): DailyDigestSettings
    fun markHandled(epochDay: Long): DailyDigestSettings
}

internal fun nextDailyDigestOccurrence(
    minuteOfDay: Int,
    now: Instant,
    zone: ZoneId,
    lastHandledEpochDay: Long? = null,
): Instant

data class DailyDigestNotificationPlan(
    val openTodayCount: Int,
    val overdueCount: Int,
)

internal fun dailyDigestNotificationPlan(
    snapshot: WorkspaceSnapshot,
    now: Instant,
    zone: ZoneId,
): DailyDigestNotificationPlan?
```

- [ ] **Step 1: Write RED settings/timing/planning tests**

Use the existing test-source `FakeSharedPreferences` rather than a new mocking
dependency. Add:

```kotlin
@Test fun defaultsOffAndUses0800WhenFirstEnabled()
@Test fun outOfRangeOrWrongTypeTimeFailsClosed()
@Test fun unknownPreferenceKeyIsRemovedAndFailsClosed()
@Test fun disablingRetainsLastHandledDay()
@Test fun nextOccurrenceBeforeConfiguredTimeUsesToday()
@Test fun atOrAfterConfiguredTimeUsesTomorrow()
@Test fun springGapMovesForwardByTheGap()
@Test fun autumnOverlapUsesTheEarlierOffset()
@Test fun lastHandledDayBoundsARewoundClock()
@Test fun zeroCountsProduceNoNotificationPlan()
@Test fun notificationPlanContainsCountsOnlyAndSuppressesTitles()
```

Inspect `prefs.all` in each store test and assert its keys are a subset of the
three canonical keys and values only Boolean/Int/Long. Inject wrong types by
writing through the fake editor's other typed method. Use Europe/London gap
and overlap dates; assert exact instants.

- [ ] **Step 2: Run RED**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "app.opentasks.digest.DailyDigestSystemTest"
```

Expected: digest APIs missing.

- [ ] **Step 3: Implement the exact preference boundary**

Use `prefs.all` to validate raw types so wrong types cannot silently become
defaults. Valid state is Boolean `enabled`, Int `minute_of_day in 0..1439`, and
optional Long epoch day from `LocalDate.MIN` through the day before
`LocalDate.MAX` (a following occurrence must remain representable). Missing
keys use off/08:00/null. Any unknown key, wrong type, or out-of-range value is
cleared and rewritten to disabled/08:00 while retaining only a valid handled
day; it never throws. `setMinuteOfDay` uses `require(minuteOfDay in 0..1439)`.
Disabling writes only enabled and therefore retains handled day.

Provide exactly:

```kotlin
DailyDigestSettingsStore(
    context.getSharedPreferences("daily_digest", Context.MODE_PRIVATE),
)
```

as a singleton in `AppModule`. Add no per-vault scope or repository reference.

- [ ] **Step 4: Implement next one-shot occurrence with wall time**

```kotlin
internal fun nextDailyDigestOccurrence(
    minuteOfDay: Int,
    now: Instant,
    zone: ZoneId,
    lastHandledEpochDay: Long?,
): Instant {
    require(minuteOfDay in 0..1439)
    val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    var date = LocalDate.ofInstant(now, zone)
    if (lastHandledEpochDay != null && date.toEpochDay() <= lastHandledEpochDay) {
        date = LocalDate.ofEpochDay(Math.addExact(lastHandledEpochDay, 1L))
    }
    var candidate = date.atTime(time).atZone(zone).toInstant()
    if (!candidate.isAfter(now)) {
        date = date.plusDays(1)
        candidate = date.atTime(time).atZone(zone).toInstant()
    }
    return candidate
}
```

Do not use start-of-day plus minutes; that produces the wrong wall time across
DST. Bound/validate the stored epoch day before this function.

- [ ] **Step 5: Reuse the existing Today projection with titles disabled**

`dailyDigestNotificationPlan` calls `computeTodayProjection` exactly once with
`today = LocalDate.ofInstant(now, zone)` and `titlesPermitted = false`. Return
null only when both counts are zero; otherwise copy just the two counts. Do not
add title/task/project fields to the plan.

- [ ] **Step 6: Run GREEN and inspect stored vocabulary**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "app.opentasks.digest.DailyDigestSystemTest"
rg -n "daily_digest|enabled|minute_of_day|last_handled_epoch_day" \
  app/src/main/kotlin/app/opentasks/digest/DailyDigestSystem.kt \
  app/src/main/kotlin/app/opentasks/di/AppModule.kt
```

Expected: PASS and only the canonical file/keys.

- [ ] **Step 7: Stage, commit, and review Task 12**

```bash
git diff --check
git add app/src/main/kotlin/app/opentasks/digest/DailyDigestSystem.kt \
  app/src/test/kotlin/app/opentasks/digest/DailyDigestSystemTest.kt \
  app/src/main/kotlin/app/opentasks/di/AppModule.kt
git diff --cached --name-only
git commit -m "feat: plan private daily digests"
```

Review fail-closed typing, handled-day retention, exact DST behaviour,
backward-clock bound, zero silence, no title-bearing type, and no backup/vault
state.

---

### Task 13: Deliver one private digest and always re-arm first

**Files:**

- Modify: `app/src/main/kotlin/app/opentasks/digest/DailyDigestSystem.kt`
- Modify: `app/src/test/kotlin/app/opentasks/digest/DailyDigestSystemTest.kt`
- Create: `app/src/androidTest/kotlin/app/opentasks/digest/DailyDigestSystemInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApplication.kt`
- Modify: `app/src/main/kotlin/app/opentasks/reminders/ReminderSystem.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**

```kotlin
@Singleton
class DailyDigestScheduler @Inject constructor(@ApplicationContext context: Context) {
    fun reconcile(
        settings: DailyDigestSettings,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    )
    fun cancel()
}

@Singleton
class DailyDigestCoordinator @Inject constructor(
    store: DailyDigestSettingsStore,
    scheduler: DailyDigestScheduler,
    notifier: DailyDigestNotifier,
    repository: Provider<VaultRepository>,
) {
    val settings: StateFlow<DailyDigestSettings>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setMinuteOfDay(minuteOfDay: Int)
    suspend fun reconcile()
    suspend fun handleDelivery()
}

@AndroidEntryPoint
class DailyDigestReceiver : BroadcastReceiver()
```

- [ ] **Step 1: Extend RED tests around sequencing and duplicate prevention**

Add host tests using internal fixed-now/zone and callback overloads—not public
interfaces or a mocking framework:

```kotlin
@Test fun sameOrEarlierEpochDaySkipsDeliveryAndOnlyReconciles()
@Test fun deliveryMarksAndRearmsBeforeVaultLookup()
@Test fun missingActiveVaultLeavesTodayHandledAndTomorrowArmed()
@Test fun deliveryResolvesTheCurrentVaultForEachHandledDay()
@Test fun disablingSerialisesWithDeliveryAndLeavesTheAlarmCancelled()
```

Record callback order in a mutable list and assert exactly `mark`, `rearm`,
`vault`, `post` for a nonzero delivery. Make vault lookup throw and assert the
first two remain, with no post. Assert same/backward day performs no mark,
vault, or post. Use a provider that returns two repositories on consecutive
local days and assert both are resolved; the singleton coordinator must never
cache a replaced vault runtime.

Add Android declarations/tests:

```kotlin
@Test fun preferenceStoreUsesOnlyTheThreeCanonicalKeys()
@Test fun dailyDigestReceiverIsNotExportedAndHasNoIntentFilter()
@Test fun dailyDigestChannelIsIndependentAndPrivate()
@Test fun privateNotificationContainsCountsAndPublicVersionIsGeneric()
@Test fun digestContentIntentRequestsHomeWithoutWorkspacePayload()
```

- [ ] **Step 2: Run/compile RED**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "app.opentasks.digest.DailyDigestSystemTest" \
  :app:compileDebugAndroidTestKotlin
```

Expected: runtime/receiver/channel APIs missing.

- [ ] **Step 3: Schedule exactly one stable inexact broadcast**

`DailyDigestIntents` owns one action and stable `opentasks://digest/deliver`
data. `DailyDigestScheduler` creates one explicit immutable
`PendingIntent.getBroadcast(context, 0, deliveryIntent,
PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)`.
Enabled/valid calls `AlarmManager.setAndAllowWhileIdle(
AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pendingIntent)`; cancel uses the
same PendingIntent identity. Disabled/invalid catches state and cancels. It
never calls `canScheduleExactAlarms`, `setExact*`, WorkManager, a service, or
repeats.

- [ ] **Step 4: Build a separate private channel and generic public version**

Use channel id `daily_digest`, default importance, and
`Notification.VISIBILITY_PRIVATE`. Private content is exactly resource-backed
counts such as `3 open today • 1 overdue`; no titles. Public title/text are
generic and contain no counts. The private builder uses
`VISIBILITY_PRIVATE`, `setPublicVersion`, auto-cancel, and a content intent to
`MainActivity` action `OPEN_DAILY_DIGEST_HOME`; no task/vault/count extras.
`show` returns without posting when permission/app/channel is unavailable and
catches `SecurityException` at the coordinator boundary.
Reuse `today_widget_label` and `today_widget_counts` for the private title/body
and the existing generic `reminder_public_text`; add only digest channel name,
channel description, and public-title strings.

- [ ] **Step 5: Implement delivery order under one mutex**

`DailyDigestReceiver` rejects any action or data other than the one canonical
delivery intent, uses `goAsync`, calls the coordinator on an IO supervisor,
and always calls `pendingResult.finish()` from `finally`.

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != DailyDigestIntents.ACTION_DELIVER ||
        intent.data != DailyDigestIntents.deliveryData()
    ) return
    val pendingResult = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            coordinator.handleDelivery()
        } finally {
            pendingResult.finish()
        }
    }
}
```

Inside the coordinator mutex:

1. reload settings; disabled/invalid => cancel and return;
2. capture one `now` and current zone;
3. if today's epoch day `<= lastHandled`, reconcile and return;
4. `markHandled(today)`;
5. reconcile the returned settings (tomorrow is now bounded by handled day);
6. only then resolve `Provider<VaultRepository>` and current snapshot;
7. compute the title-free plan; post only when non-null/available.

Catch missing active-vault `IllegalStateException` and notification
`SecurityException` after Step 5. Never roll back handled day or schedule a
same-day retry.
Use `Provider`, not `Lazy`: the existing unscoped Hilt binding deliberately
resolves the currently active runtime, while a singleton-held `Lazy` would
cache the first vault across an in-process slot replacement.
The suspend `setEnabled`, `setMinuteOfDay`, and `reconcile` methods use that
same mutex. Each setter writes through the store and immediately reconciles
the returned settings, so disable cannot race a delivery re-arm and every
enabled time change replaces the one-shot alarm.

- [ ] **Step 6: Reconcile startup and system events without vault coupling**

Create the digest channel beside the reminder channel in
`OpenTasksApplication`; do not resolve a repository there. Inject the
coordinator into the existing `ReminderSystemEventReceiver` and call digest
reconcile before its lazy vault lookup, alongside device-local focus re-arm.
Task 14's single-activity `onStart` hook covers initial launch and every app
foreground. Reuse existing boot/package/time/time-zone filters; do not add a
new system-event receiver or permission.

Add only:

```xml
<receiver
    android:name=".digest.DailyDigestReceiver"
    android:exported="false" />
```

- [ ] **Step 7: Run host GREEN and compile Android coverage**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "app.opentasks.digest.DailyDigestSystemTest" \
  :app:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin
```

Expected: PASS/compile; no device execution.

- [ ] **Step 8: Audit the manifest delta now**

```bash
git diff -- app/src/main/AndroidManifest.xml
rg -n "setExact|setRepeating|WorkManager|OPEN_DAILY_DIGEST_HOME|DailyDigestReceiver" \
  app/src/main/kotlin/app/opentasks/digest/DailyDigestSystem.kt \
  app/src/main/AndroidManifest.xml
git diff --check
```

Expected manifest diff: exactly one non-exported no-filter receiver; forbidden
alarm/worker APIs absent.

- [ ] **Step 9: Stage, commit, and review Task 13**

Stage exactly the seven files, audit names, then:

```bash
git commit -m "feat: deliver private daily digests"
```

Review explicit intent identity, action/data validation, delivery ordering,
duplicate suppression, missing-vault/permission handling, private/public
content, system-event ordering, and exact manifest/permission scope.

---

### Task 14: Add the inline More setting and foreground/Home wiring

**Files:**

- Modify: `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`
- Modify: `feature/more/src/main/res/values/strings.xml`
- Create: `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/DailyDigestSettingsInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/MainActivity.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`

**Interfaces:**

Add primitive values/callbacks to `MoreScreen`:

```kotlin
dailyDigestEnabled: Boolean = false,
dailyDigestMinuteOfDay: Int = 8 * 60,
dailyDigestNotificationsEnabled: Boolean = true,
onDailyDigestEnabledChange: (Boolean) -> Unit = {},
onDailyDigestMinuteOfDayChange: (Int) -> Unit = {},
onEnableNotifications: () -> Unit = {},
```

- [ ] **Step 1: Write the failing More UI matrix**

```kotlin
@Test fun switchInvokesOptInAndShows0800()
@Test fun timeButtonUses24HourPickerAndReturnsMinuteOfDay()
@Test fun notificationGuidanceAppearsOnlyWhenEnabledAndUnavailable()
@Test fun disabledDigestHidesTimeAndGuidance()
```

Assert switch, time, and permission/settings actions are at least 48 dp. The
feature test receives primitives/lambdas only and asserts exact minute-of-day
callback; it never instantiates the store/coordinator.

- [ ] **Step 2: Compile RED**

```bash
./gradlew :feature:more:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin
```

Expected: new arguments/UI missing.

- [ ] **Step 3: Render one inline setting in More overview**

Place Daily digest in the existing overview/settings section, not a new route.
Use the existing toggle-row pattern. Only while enabled, show formatted `HH:mm`
and a 48 dp time button. Launch
`android.app.TimePickerDialog(context, hour, minute, true)` and emit
`hour * 60 + minute`. If enabled but notifications are unavailable, show the
existing contextual enable/settings action; preserve enabled state regardless.
All new copy is resource-backed.

- [ ] **Step 4: Reconcile every foreground and route generic taps Home**

Inject `DailyDigestCoordinator` into `MainActivity`. Add
`runCatching { coordinator.reconcile() }` inside the existing `onStart`
`lifecycleScope.launch` after `super.onStart()`; keep vault initialisation
independent so either failure cannot suppress the other.
In `handleIntent`, recognise
only `OPEN_DAILY_DIGEST_HOME`, increment an `openHomeSignal`, and pass the
coordinator/signal to `OpenTasksApp`. Do not bypass the existing runtime and
app-lock branches; the signal is consumed only after workspace composition.

In `OpenTasksApp`:

- collect `coordinator.settings` with lifecycle-aware state;
- compute `DailyDigestNotifications.areEnabled(activity)` from the existing
  `permissionStateVersion` refresh;
- use `LaunchedEffect(openHomeSignal) { if (openHomeSignal > 0) navigate(HomeRoute) }`;
- pass primitives to More and wrap each coordinator setter in the existing
  composition coroutine scope;
- reuse the existing notification permission/settings launcher.

- [ ] **Step 5: Compile every final product surface**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "app.opentasks.digest.DailyDigestSystemTest" \
  :feature:more:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin
```

Expected: PASS/compile.

- [ ] **Step 6: Run the pre-qualification local integration gate**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
```

Expected: all host tests, lint, and debug assembly pass. This is not the final
forced-fresh gate and runs no device.

- [ ] **Step 7: Stage, commit, and review Task 14**

```bash
git diff --check
git add feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt \
  feature/more/src/main/res/values/strings.xml \
  feature/more/src/androidTest/kotlin/app/opentasks/feature/more/DailyDigestSettingsInstrumentedTest.kt \
  app/src/main/kotlin/app/opentasks/MainActivity.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt
git diff --cached --name-only
git commit -m "feat: configure daily digest in More"
```

Review opt-in/default time, native 24-hour picker, denied-notification
preservation/guidance, foreground re-arm, lock-authoritative Home navigation,
feature boundary, and no extra route/permission.

---

### Task 15: Qualify Stage 8 and cut signed sideload release 1.2.0

**Files:**

- Modify: `app/build.gradle.kts` (only `versionCode` / `versionName`)
- Create: `docs/qualification/stage8-planning-surfaces.md`
- Create: `docs/qualification/release-1.2.0-sideload.md`
- Modify: `docs/architecture.md`
- Modify: `DESIGN.md`
- Modify: `PRODUCT.md`
- Modify: `docs/threat-model.md`
- Modify: `CLAUDE.md`
- Modify: `HANDOFF.md`

**Interfaces:**

- Consumes Tasks 1–14, the approved spec, the recorded `stage8BaseSha`, the
  roadmap's uniform exit gates, and `RELEASING.md`.
- Produces reviewed `implementationHeadSha`, version 1.2.0 (3), zero
  Critical/Important findings, complete local/connected/privacy/release
  evidence, a verified signed APK, exact-candidate GitHub Free CI evidence, an
  annotated `v1.2.0` tag, and a separate post-tag HANDOFF closure.

- [ ] **Step 1: Record implementation head and bump exactly one version**

Record the Tasks 1–14 head in the ignored ledger before editing:

```bash
set -euo pipefail
implementation_head_sha="$(git rev-parse HEAD)"
test "${#implementation_head_sha}" -eq 40
git cat-file -e "${implementation_head_sha}^{commit}"
git status --short --branch
```

Change only:

```kotlin
versionCode = 3
versionName = "1.2.0"
```

Do not record a future qualification commit as a self-referential
implementation SHA.

- [ ] **Step 2: Compile all six connected-test source sets**

```bash
./gradlew :app:compileDebugAndroidTestKotlin \
  :core:data:compileDebugAndroidTestKotlin \
  :feature:tasks:compileDebugAndroidTestKotlin \
  :feature:projects:compileDebugAndroidTestKotlin \
  :feature:schedule:compileDebugAndroidTestKotlin \
  :feature:more:compileDebugAndroidTestKotlin
```

Expected: all compile. Fix source/tests; do not delete, skip, or waive them.

- [ ] **Step 3: Run one forced-fresh local CI gate**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --rerun-tasks
```

Expected: PASS. Record exact task/test counts available from reports.

- [ ] **Step 4: Build and verify the signed release separately**

Require the existing gitignored `keystore.properties` and external keystore
without printing file contents, passwords, aliases, or certificate identifiers:

```bash
./gradlew :app:assembleRelease --rerun-tasks
bash scripts/verify-release-apk.sh
```

Expected: `verify-release-apk: all checks passed`; APK is 1.2.0 (3), signed by
the established key, minified, and free of debug qualification components. A
missing signing prerequisite blocks release; never weaken the verifier.

- [ ] **Step 5: Commit the verified one-file version bump**

```bash
git diff --check
git add app/build.gradle.kts
test "$(git diff --cached --name-only)" = app/build.gradle.kts
git commit -m "build: set version 1.2.0"
```

- [ ] **Step 6: Run all no-durable-change and deterministic-fixture gates**

```bash
scripts/check-schema-drift.sh
node scripts/generate-authenticated-cloud-v1-fixtures.mjs
git diff --exit-code core/data/src/test/resources
node scripts/generate-stage2-backup-v1-fixtures.mjs
git diff --exit-code core/data/src/test/resources
node scripts/generate-stage3-drive-create-only-v1-fixtures.mjs
git diff --exit-code core/data/src/test/resources
node scripts/generate-stage4-attachment-v1-fixtures.mjs
git diff --exit-code core/data/src/test/resources
node scripts/generate-stage5-otvault-v1-fixtures.mjs
git diff --exit-code core/data/src/test/resources
bash scripts/verify-actions-workflow.sh
git diff --check
```

Expected: Room v9 and every fixture/workflow byte remain unchanged.

- [ ] **Step 7: Audit the exact base-to-head privacy and release scope**

Run this as one foreground shell so its validated base cannot go stale:

```bash
set -euo pipefail
ledger=.superpowers/sdd/2026-08-14-stage-8-planning-surfaces-plan/progress.md
test -f "$ledger"
stage8_base_sha="$(rg -o -m 1 '[0-9a-f]{40}' "$ledger")"
case "$stage8_base_sha" in ''|*[!0-9a-f]*) exit 1 ;; esac
test "${#stage8_base_sha}" -eq 40
git cat-file -e "${stage8_base_sha}^{commit}"
git merge-base --is-ancestor "$stage8_base_sha" HEAD
git diff --name-only "$stage8_base_sha"..HEAD

if git diff --unified=0 "$stage8_base_sha"..HEAD -- '*.kt' | \
  rg '^\+.*(android\.util\.Log|Log\.|println\(|Timber\.)'; then
  exit 1
fi
git diff --exit-code "$stage8_base_sha"..HEAD -- core/data/schemas
git diff --exit-code "$stage8_base_sha"..HEAD -- \
  core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordV1.kt
git diff --exit-code "$stage8_base_sha"..HEAD -- \
  app/src/main/res/xml/backup_rules.xml \
  app/src/main/res/xml/data_extraction_rules.xml \
  gradle/libs.versions.toml

while IFS= read -r build_file; do
  if test "$build_file" != app/build.gradle.kts; then
    git diff --exit-code "$stage8_base_sha"..HEAD -- "$build_file"
  fi
done < <(rg --files -g '*.gradle.kts' | sort)
if git diff --unified=0 "$stage8_base_sha"..HEAD -- app/build.gradle.kts | \
  rg '^[+-][^+-]' | rg -v 'version(Code|Name)'; then
  exit 1
fi

manifest_count=0
while IFS= read -r manifest; do
  manifest_count=$((manifest_count + 1))
  if test "$manifest" != app/src/main/AndroidManifest.xml; then
    git diff --exit-code "$stage8_base_sha"..HEAD -- "$manifest"
  fi
done < <(rg --files -g 'AndroidManifest.xml' | sort)
test "$manifest_count" -eq 13

diff \
  <(git show "$stage8_base_sha":app/src/main/AndroidManifest.xml | rg '<uses-permission') \
  <(rg '<uses-permission' app/src/main/AndroidManifest.xml)
app_manifest_delta="$(git diff --unified=0 "$stage8_base_sha"..HEAD -- \
  app/src/main/AndroidManifest.xml | rg '^[+-][^+-]')"
test "$(printf '%s\n' "$app_manifest_delta" | rg -c '^\+')" -eq 3
test "$(printf '%s\n' "$app_manifest_delta" | rg -c '^-')" -eq 0
test "$(printf '%s\n' "$app_manifest_delta" | rg -c '^\+\s*<receiver')" -eq 1
test "$(printf '%s\n' "$app_manifest_delta" | rg -c 'DailyDigestReceiver')" -eq 1
test "$(printf '%s\n' "$app_manifest_delta" | \
  rg -c 'android:exported="false"\s*/>')" -eq 1

if git diff --unified=0 "$stage8_base_sha"..HEAD -- '*.kt' '*.xml' | \
  rg '^\+.*(https?://|DRIVE_ORIGIN|BASE_URL|baseUrl|endpoint|drive\.appdata)'; then
  exit 1
fi
rg -n 'daily_digest|enabled|minute_of_day|last_handled_epoch_day' \
  app/src/main/kotlin/app/opentasks/digest/DailyDigestSystem.kt \
  app/src/main/kotlin/app/opentasks/di/AppModule.kt
rg -n 'getSharedPreferences|put(Boolean|Int|Long|String|StringSet)' \
  app/src/main/kotlin/app/opentasks/digest/DailyDigestSystem.kt \
  app/src/main/kotlin/app/opentasks/di/AppModule.kt
if rg -n 'put(String|StringSet|Float)' \
  app/src/main/kotlin/app/opentasks/digest/DailyDigestSystem.kt \
  app/src/main/kotlin/app/opentasks/di/AppModule.kt; then
  exit 1
fi
```

Manually inspect the app-manifest diff and digest preference hits. Expected:
exactly the non-exported no-filter digest receiver; existing permissions and
filters unchanged; only Boolean/Int/optional Long under the three keys; no
task/title/project/vault/count/zone/payload/scheduled-instant persistence; no
new endpoint/Drive/dependency/backup surface.

- [ ] **Step 8: Dispatch the whole-stage independent review**

Use `superpowers:requesting-code-review` with the approved spec, this plan, the
literal validated `stage8BaseSha..HEAD` diff, and test evidence. Require checks
for behaviour, dual-engine atomicity/journal order/Undo, DST, reminder safety,
accessibility/RTL, saved-state decoding, bounded graph traversal, digest
privacy/lifecycle/duplicate suppression, schema/backup/permission/Drive scope,
and regression risk.

- [ ] **Step 9: Fix, test, commit, and re-review every blocking finding**

Record each finding, disposition, literal paths, and focused check in the
ignored ledger before editing. For every fix wave run its focused check plus:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
./gradlew :app:compileDebugAndroidTestKotlin \
  :core:data:compileDebugAndroidTestKotlin \
  :feature:tasks:compileDebugAndroidTestKotlin \
  :feature:projects:compileDebugAndroidTestKotlin \
  :feature:schedule:compileDebugAndroidTestKotlin \
  :feature:more:compileDebugAndroidTestKotlin
git diff --check
git status --short
```

Stage only literal fix paths, audit staged names, commit with
`fix: resolve stage 8 review findings`, and obtain fresh independent re-review.
Repeat to zero Critical/Important. Record the final reviewed full SHA as the
refreshed `implementationHeadSha`, then rerun Step 2 when test sources changed
and always rerun forced-fresh Steps 3, 4, 6, and 7 against that final head.

**Qualification invalidation invariant:** from this point until the tag is
created, any production, build, or test-source edit invalidates the recorded
implementation SHA, signed APK, review, forced-fresh/scope evidence, and every
connected/manual/signed/candidate-CI result collected after it. Securely clean
up and stop the overlay first if it is running; diagnose and commit the focused
fix; return through Steps 8–9 to zero Critical/Important; refresh
`implementationHeadSha`; rerun Steps 2, 3, 4, 6, and 7; then start a fresh
audited overlay at Step 10 and repeat all remaining connected, manual, signed,
documentation, candidate-CI, and tag steps. A retry with no repository edit
may repeat only the affected check after its environmental cause is recorded.
Never carry an earlier APK or evidence across a code/test/build change.

- [ ] **Step 10: Start and audit the sole disposable overlay**

If any Stage 7 residual ADB target/emulator is present, stop here and resolve
its secure cleanup before Stage 8. Then:

```bash
set -euo pipefail
stage8_adb=/Users/kk/Library/Android/sdk/platform-tools/adb
stage8_emulator=/Users/kk/Library/Android/sdk/emulator/emulator
test -x "$stage8_adb"
test -x "$stage8_emulator"
test -z "$("$stage8_adb" devices | sed -n '2,$p' | sed '/^$/d')"
if ps -Ao command | rg '[e]mulator.*(Fold8_Acceptance|Pixel_10_Pro_Fold)'; then
  exit 1
fi
stage8_temp_dir="$(mktemp -d /tmp/opentasks-stage8.XXXXXX)"
case "$stage8_temp_dir" in /tmp/opentasks-stage8.*) ;; *) exit 1 ;; esac
test -d "$stage8_temp_dir"
test ! -L "$stage8_temp_dir"
stage8_log="$stage8_temp_dir/emulator.log"
test ! -e "$stage8_log"
stage8_emulator_pid=""
stage8_serial=""

stage8_startup_cleanup() {
  original_status=$?
  trap - EXIT
  if test -n "$stage8_serial"; then
    avd_name="$("$stage8_adb" -s "$stage8_serial" emu avd name \
      2>/dev/null | sed -n '1p' || true)"
    if test "$avd_name" = Fold8_Acceptance; then
      "$stage8_adb" -s "$stage8_serial" emu kill >/dev/null 2>&1 || true
    fi
  fi
  if test -n "$stage8_emulator_pid" && \
      kill -0 "$stage8_emulator_pid" 2>/dev/null; then
    process_command="$(ps -p "$stage8_emulator_pid" -o command= || true)"
    if test -n "$process_command"; then
      case "$process_command" in
        *"/Library/Android/sdk/emulator/"*"-avd Fold8_Acceptance"*) ;;
        *) exit 1 ;;
      esac
      kill "$stage8_emulator_pid" 2>/dev/null || true
      stopped=false
      for attempt in {1..30}; do
        if ! kill -0 "$stage8_emulator_pid" 2>/dev/null; then
          stopped=true
          break
        fi
        sleep 1
      done
      test "$stopped" = true
    fi
  fi
  if test -e "$stage8_log"; then
    test -f "$stage8_log"
    test ! -L "$stage8_log"
    rm "$stage8_log"
  fi
  test -d "$stage8_temp_dir"
  test ! -L "$stage8_temp_dir"
  rmdir "$stage8_temp_dir"
  exit "$original_status"
}
trap stage8_startup_cleanup EXIT

"$stage8_emulator" -avd Fold8_Acceptance \
  -read-only -no-snapshot-load -no-snapshot-save -no-window \
  >"$stage8_log" 2>&1 &
stage8_emulator_pid=$!
test "$stage8_emulator_pid" -gt 0
boot_ready=false
for attempt in {1..180}; do
  if ! kill -0 "$stage8_emulator_pid" 2>/dev/null; then exit 1; fi
  target_count="$("$stage8_adb" devices | \
    awk 'NR > 1 && NF { count++ } END { print count + 0 }')"
  test "$target_count" -le 1
  device_count="$("$stage8_adb" devices | \
    awk '$2 == "device" { count++ } END { print count + 0 }')"
  test "$device_count" -le 1
  stage8_serial="$("$stage8_adb" devices | \
    awk '$2 == "device" { print $1; exit }')"
  if test -n "$stage8_serial"; then
    boot_state="$("$stage8_adb" -s "$stage8_serial" shell \
      getprop sys.boot_completed 2>/dev/null | sed 's/\r$//' || true)"
    if test "$boot_state" = 1; then
      boot_ready=true
      break
    fi
  fi
  sleep 2
done
test "$boot_ready" = true
test -n "$stage8_serial"
test "$("$stage8_adb" devices | \
  awk 'NR > 1 && NF { count++ } END { print count + 0 }')" -eq 1
test "$("$stage8_adb" devices | \
  awk '$2 == "device" { count++ } END { print count + 0 }')" -eq 1
test "$("$stage8_adb" -s "$stage8_serial" emu avd name | sed -n '1p')" = \
  Fold8_Acceptance
printf 'stage8EmulatorPid: %s\nstage8Serial: %s\nstage8TempDir: %s\nstage8Log: %s\n' \
  "$stage8_emulator_pid" "$stage8_serial" "$stage8_temp_dir" "$stage8_log"
trap - EXIT
```

The four labelled output lines are the ownership handoff after every exact
audit passes; record them immediately and verbatim in the ignored ledger, then
the final trap disarm leaves normal cleanup responsible for that exact overlay.
Never commit them. On any earlier shell
failure the EXIT trap stops only the verified captured Fold8 process/target and
removes only its validated fresh temp files. Keep the successfully handed-off
overlay through signed smoke.

- [ ] **Step 11: Run the six-module connected gate**

```bash
./gradlew :app:connectedDebugAndroidTest \
  :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest
```

Expected: all six pass. Record exact total/pass/failure/error/skip counts and
module totals. Exactly the established credential-only Drive row and
cross-display fold harness may remain skipped; any new skip/failure/error
blocks qualification and any resulting edit follows the invalidation invariant
above. Room schedule parity must prove restart, one revision, one ordered
TASK/REMINDER generation, rejection atomicity, and exact Undo.

- [ ] **Step 12: Execute the complete Stage 8 debug manual checklist**

On the same audited overlay, record outcomes without private task text,
screens containing secrets, device id, vault/Drive id, or credentials:

1. Compact and expanded Schedule: Week/Month switching, 42 cells, adjacent
   selection, Previous/Today/Next, selected agenda, completed visibility,
   open-only tray, six-dot/`6+` density, overdue marker, TalkBack exact cell
   counts, 200% text, and process restoration.
2. Rescheduling: all four source shapes including 18:00 undated; stored-zone
   time; change the device zone in-process and prove the next undated move uses
   18:00 in that replacement zone; start+due span; DST gap/overlap;
   due-relative reminder; past-reminder rejection; recurring day move/current
   metadata; recurring tray rejection; reminder removal confirm/cancel; Undo;
   same/outside snapback; compact fallback; expanded Week and Month drag;
   callback freshness; RTL and unclipped preview.
3. Task editor: add/edit/clear start, explicit start/due times, 09:00/17:00
   defaults, zone preservation, due-before-start warning, one autosave, and
   readable legacy-invalid state.
4. Projects: List/Board/Timeline restoration per two projects; 12-week Monday
   window and four-week navigation; Timeline Today follows the live replacement
   device zone; every span/marker/clipping/outside/invalid/unscheduled state;
   completed/blocked markers; milestone diamonds and exact outside counts;
   separate row selection/Open; unique transitive
   prerequisite/dependant highlights and external count; Board regression;
   TalkBack/non-colour semantics; no bar/diamond drag.
5. Digest: off by default at 08:00; enable and choose a near-future 24-hour
   time; exactly one post; zero-count silence; disable cancels; re-enable does
   not duplicate the same day; denied permission/channel retains setting and
   guidance; reboot and wall-clock/time-zone changes retain local wall time.
6. Digest privacy: private shade contains counts only, public lock-screen copy
   is generic with no counts, no title appears, and tapping reaches Home only
   after the app-lock overlay when locked.
7. Fold/continuity: rotate/fold posture and process recreation preserve Month,
   selected date, per-project Timeline anchor/chain, and digest preference;
   every interactive target remains reachable at 200% text.

Any mismatch blocks release; any resulting edit follows the invalidation
invariant above.

- [ ] **Step 13: Install the verified signed APK on only that overlay**

```bash
set -euo pipefail
stage8_adb=/Users/kk/Library/Android/sdk/platform-tools/adb
stage8_serial="$("$stage8_adb" devices | sed -n '2p' | \
  awk '$2 == "device" { print $1 }')"
test -n "$stage8_serial"
test "$("$stage8_adb" -s "$stage8_serial" emu avd name | sed -n '1p')" = \
  Fold8_Acceptance
"$stage8_adb" -s "$stage8_serial" uninstall app.opentasks
"$stage8_adb" -s "$stage8_serial" install \
  app/build/outputs/apk/release/app-release.apk
```

Expected: both succeed on the disposable overlay only.

- [ ] **Step 14: Run all signed-sideload smoke rows plus Stage 8 extras**

Execute every `RELEASING.md` row without inherited waiver:

1. fresh launch and Start without restoring;
2. project/task/checklist/tag creation;
3. force-stop/relaunch persistence;
4. `.otvault` export/import with matching counts;
5. enable immediate app lock, background past delay, and unlock;
6. Today widget counts;
7. Quick Add launcher shortcut.

Then repeat one representative Month move/Undo, one Timeline chain/Open, and a
near-future digest whose shade/public/tap behaviour matches Step 12. Any
failure blocks tag creation; any resulting edit follows the invalidation
invariant above.

- [ ] **Step 15: Perform secure cleanup and destroy only the overlay**

Delete the temporary exported `.otvault` through its owning picker/app. Remove
the temporary screen credential through the device UI without recording or
echoing it. Remove any controller-created credential helper file only after
resolving and inspecting its exact private-ledger path; stop if it is missing,
linked, locked, or outside the intended temporary directory. Then kill only the
audited AVD:

```bash
set -euo pipefail
stage8_adb=/Users/kk/Library/Android/sdk/platform-tools/adb
ledger=.superpowers/sdd/2026-08-14-stage-8-planning-surfaces-plan/progress.md
test -f "$ledger"
stage8_recorded_pid="$(sed -n 's/^stage8EmulatorPid: //p' "$ledger" | tail -n 1)"
stage8_recorded_serial="$(sed -n 's/^stage8Serial: //p' "$ledger" | tail -n 1)"
stage8_temp_dir="$(sed -n 's/^stage8TempDir: //p' "$ledger" | tail -n 1)"
stage8_log="$(sed -n 's/^stage8Log: //p' "$ledger" | tail -n 1)"
case "$stage8_recorded_pid" in ''|*[!0-9]*) exit 1 ;; esac
case "$stage8_temp_dir" in /tmp/opentasks-stage8.*) ;; *) exit 1 ;; esac
test "$stage8_log" = "$stage8_temp_dir/emulator.log"
test -d "$stage8_temp_dir"
test ! -L "$stage8_temp_dir"
test -f "$stage8_log"
test ! -L "$stage8_log"
stage8_serial="$("$stage8_adb" devices | sed -n '2p' | \
  awk '$2 == "device" { print $1 }')"
test -n "$stage8_serial"
test "$stage8_serial" = "$stage8_recorded_serial"
test "$("$stage8_adb" -s "$stage8_serial" emu avd name | sed -n '1p')" = \
  Fold8_Acceptance
test -n "$(ps -p "$stage8_recorded_pid" -o command= | \
  rg '/Library/Android/sdk/emulator/.*-avd Fold8_Acceptance')"
"$stage8_adb" -s "$stage8_serial" emu kill
detached=false
for attempt in {1..60}; do
  target_count="$("$stage8_adb" devices | \
    awk 'NR > 1 && NF { count++ } END { print count + 0 }')"
  process_running=false
  if ps -Ao command | rg '[e]mulator.*Fold8_Acceptance'; then
    process_running=true
  fi
  if test "$target_count" -eq 0 && test "$process_running" = false; then
    detached=true
    break
  fi
  sleep 2
done
test "$detached" = true
test -z "$("$stage8_adb" devices | sed -n '2,$p' | sed '/^$/d')"
if ps -Ao command | rg '[e]mulator.*Fold8_Acceptance'; then exit 1; fi
rm "$stage8_log"
rmdir "$stage8_temp_dir"
test ! -e "$stage8_log"
test ! -e "$stage8_temp_dir"
```

Verify no ADB target, disposable process, exported package, helper credential
file, or temporary device credential remains. Physical-device installation is
owner-controlled and outside this task.

- [ ] **Step 16: Write stage, release, architecture, and security records**

`docs/qualification/stage8-planning-surfaces.md` records base and final reviewed
implementation SHAs, toolchain, focused/full/connected counts, dual-engine and
journal evidence, schema/fixture/workflow/privacy/manifest/preference/Drive
audits, review dispositions, manual matrix, cleanup, and zero
Critical/Important. It labels remote CI/tag as pending so it never claims
evidence from a future self-referential commit.
`docs/qualification/release-1.2.0-sideload.md` records APK
version/size/hash/verifier and every signed smoke outcome. Never record secrets,
private identifiers, or task text.

Update:

- `docs/architecture.md`: Month/Timeline projections, exact schedule command,
  widened editor, drag primitive boundary, saved project view state, digest
  preference/alarm/lifecycle; Room v9/backup v1 unchanged.
- `DESIGN.md`: Week/Month, drag/fallback, editor times, Timeline visual and
  semantic language, digest opt-in/private public copy/accessibility.
- `PRODUCT.md`: Stage 8 visible boundary and deliberate ceilings.
- `docs/threat-model.md`: atomic schedule/reminder safety, bounded graph,
  fail-closed local prefs, explicit non-exported receiver, mark/rearm-before-
  vault privacy, no title/network/permission/backup expansion.
- `CLAUDE.md`: stable planning boundaries and test/release invariants.
- `HANDOFF.md`: locally qualified candidate, remote CI/tag pending, exact
  evidence, no Stage 7 waiver inheritance, Stage 9 and Unicode hardening next.

- [ ] **Step 17: Commit the pre-tag qualification candidate docs**

```bash
git add docs/architecture.md DESIGN.md PRODUCT.md \
  docs/threat-model.md CLAUDE.md HANDOFF.md \
  docs/qualification/stage8-planning-surfaces.md \
  docs/qualification/release-1.2.0-sideload.md
git diff --cached --name-only
git diff --cached --check
git commit -m "docs: qualify release 1.2.0 candidate"
```

Expected staged names: exactly those eight docs; never the ignored ledger,
keystore, user dirty files, schemas, fixtures, `.kotlin/`, or `artifacts/`.

- [ ] **Step 18: Push and wait for the exact candidate's GitHub Free run**

```bash
set -euo pipefail
candidate_sha="$(git rev-parse HEAD)"
test "${#candidate_sha}" -eq 40
git push origin main
run_id=""
for attempt in {1..12}; do
  run_id="$(gh run list --workflow Android --branch main --event push --limit 20 \
    --json databaseId,headSha --jq \
    "[.[] | select(.headSha == \"$candidate_sha\")][0].databaseId")"
  case "$run_id" in ''|null) sleep 5 ;; *) break ;; esac
done
case "$run_id" in ''|null|*[!0-9]*) exit 1 ;; esac
completed=false
# Workflow jobs have a 45-minute maximum; allow another 15 minutes for queueing.
for attempt in {1..360}; do
  if test "$(gh run view "$run_id" --json status --jq .status)" = completed; then
    completed=true
    break
  fi
  sleep 10
done
test "$completed" = true
gh run view "$run_id" --json jobs --jq '.jobs[] | [.name, .conclusion] | @tsv'
required_count="$(gh run view "$run_id" --json jobs --jq \
  '[.jobs[] | select(.name == "verify" or .name == "release" or (.name | contains("API 36")))] | length')"
required_green="$(gh run view "$run_id" --json jobs --jq \
  '[.jobs[] | select(.name == "verify" or .name == "release" or (.name | contains("API 36"))) | select(.conclusion == "success")] | length')"
test "$required_count" -eq 3
test "$required_green" -eq 3
test "$(gh run view "$run_id" --json jobs --jq \
  '[.jobs[] | select(.name | contains("API 37.0"))] | length')" -eq 1
```

Expected: verify, compact API 36, and release are green on the literal
candidate. A missing/zero-runner/billing-blocked or red required job stops here
and requires user direction; a run still queued/in progress at the bounded
60-minute wait does the same. Do not spend money or tag. API 37.0 may remain
observe-only only if its evidence is the unchanged historical pre-test failure
**credential-encrypted storage unavailable**; any other failure blocks.

- [ ] **Step 19: Create and push the annotated release tag**

```bash
git tag -a v1.2.0 -m "Release 1.2.0"
git push origin v1.2.0
test "$(git rev-list -n 1 v1.2.0)" = "$(git rev-parse HEAD)"
```

Expected: tag points at the exact remotely qualified candidate.

- [ ] **Step 20: Close HANDOFF after the tag and push**

Replace candidate wording with actual candidate SHA, run id and required job
results, tag, signed artefact evidence, cleanup result, and remaining ordered
work: Stage 9 then repository-wide Unicode tag-identity hardening. Commit only
HANDOFF after the tag:

```bash
git add HANDOFF.md
test "$(git diff --cached --name-only)" = HANDOFF.md
git diff --cached --check
git commit -m "docs: record release 1.2.0 handoff"
git push origin main
git status --short --branch
```

Expected: `main` matches origin; only the preserved unrelated user dirty state
remains.

---

## Spec Coverage Map

| Approved requirement | Tasks |
|---|---:|
| Pure Monday-first 42-cell Month, stored-zone placement, completed/Bin/count/density rules | 1, 7 |
| All four day moves, 18:00 default, DST/zone/span/reminder/tray/no-op rules | 2, 8, 9 |
| Exact atomic dual-engine command, journal generation, Undo, unchanged bulk command | 4 |
| One widened editor autosave with start and explicit start/due time controls | 4, 5 |
| Minimal Board-proven root drag extraction and Board regression | 6 |
| Compact/expanded Week/Month, saveable selection, fallback, drag, RTL/accessibility | 7–9 |
| Pure bounded 12-week Timeline, all placements/milestones/dependency context | 3 |
| Per-project List/Board/Timeline, anchor and chain SavedState restoration | 10, 11 |
| Gantt-lite rendering, separate selection/Open, completed/blocked/milestone semantics | 11 |
| Device-local off/08:00 digest settings, bounds, DST and duplicate prevention | 12–14 |
| Inexact stable alarm, mark/rearm-before-vault, counts-only/private/public privacy | 13 |
| Inline More opt-in/time/guidance, foreground/system re-arm, lock-safe Home tap | 13, 14 |
| No schema/backup/dependency/permission/route/Drive expansion | 1–15, audited in 15 |
| Full review, connected/manual/privacy/release gates and signed 1.2.0 (3) | 15 |

## Deliberate Non-Goals

- No Planner route, parallel planning store, schema/backup migration, task
  history activity, exact digest alarm, worker/service, title-bearing digest,
  catch-up delivery, or new notification permission.
- No general drag framework, compact-Week pointer fiction, Month-cell drag
  source, Timeline arrow routing/zoom/bar editing, unbounded canvas, or
  dependency-edge renderer.
- No editor split-save protocol. Add one only if the product later abandons its
  existing debounced full-edit command.
- No additional Month density detail beyond six dots plus exact semantics; add
  richer aggregation only when a demonstrated planning need exceeds this cap.

## Execution Handoff

After this plan is approved, choose exactly one:

1. **Subagent-Driven (recommended):** use
   `superpowers:subagent-driven-development`; a fresh worker implements each
   task, then spec and code-quality reviewers gate it before the controller
   advances.
2. **Inline Execution:** use `superpowers:executing-plans`; execute this file in
   order with its review checkpoints in the current agent context.

Task 15 remains controller-owned in either mode because it operates the sole
device, signing boundary, external CI, tag, cleanup, and final records.
