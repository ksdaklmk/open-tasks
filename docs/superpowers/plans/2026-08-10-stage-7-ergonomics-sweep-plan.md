# Stage 7 Ergonomics Sweep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the approved Stage 7 ergonomics sweep: correct relative-date
chips; durable sort/group controls; saved-view filters v2 and deterministic
search ranking; confirm-only Quick Add grammar; a light-only theme; atomic task
duplication; and dot-matrix Insights with a daily completion trend.

**Architecture:** Room remains the sole live durable-data authority at schema
version 9. The only new durable bytes are versioned JSON inside the existing
encrypted `saved_views.encryptedQuery` column and device-local arrangement
preferences in `view_prefs`; neither changes Room or backup formats. Shared
vocabulary stays in `:core:model`, all date, arrangement, search, grammar,
duplication, and Insights rules stay pure in `:core:domain`, both repository
engines execute identical commands, `:app` performs projections and persistence,
and feature modules render plain model values and emit events.

**Tech Stack:** The Kotlin/AGP, Room, SQLCipher, and Java toolchain already on
`main` after the prerequisite dependency queue; kotlinx.serialization JSON,
Android `SharedPreferences`, `java.time`, Jetpack Compose Canvas, JUnit 4, and
Compose UI test v2. No new production dependency or catalogue entry.

## Global Constraints

- Authority spec:
  `docs/superpowers/specs/2026-08-10-stage-7-ergonomics-sweep-design.md`.
  The roadmap sequencing and exit criteria in
  `docs/superpowers/specs/2026-08-10-stage-7-9-roadmap-design.md` also apply.
- Read the live `HANDOFF.md` before execution. Task 1 is Phase 0 and is the
  independently executable date-chip prerequisite. Do not begin Task 2 until
  Task 1 is committed, GitHub Actions billing is restored, and Dependabot PRs
  #14–#19 have been resolved with fresh green checks. Recheck paths and APIs on
  the resulting `main`; the plan intentionally pins no library version that the
  prerequisite queue can change.
- Work directly on `main`; no branch, worktree, or pull request. Immediately
  before Task 1, require `main` already contains this plan and its HANDOFF
  planning checkpoint in a docs-only commit; if it does not, commit exactly
  those two files first. Do **not** record the Stage 7 audit base yet. After
  Task 1 is committed and the Actions/Dependabot gate above is green, record
  `git rev-parse HEAD` in the ignored ledger
  `.superpowers/sdd/2026-08-10-stage-7-ergonomics-sweep-plan/progress.md` as its
  first full 40-character SHA. Task 18 uses that post-Phase-0 SHA for the exact
  Stage 7 base-to-HEAD diff audit.
- Preserve all unrelated user work. In the planning worktree this includes
  the modified historical Stage 3 plan and untracked `.kotlin/` and
  `artifacts/`; never stage or rewrite them.
- Room stays at version 9. Do not change an entity, DAO schema, migration,
  exported schema, backup record family, backup codec version, `.otvault`
  version, fixture bytes, Drive scope, provider role, or key material.
- `SavedViewPayloadCodec` v2 is a payload evolution inside the existing
  encrypted column, not a backup-format evolution. Existing v1 rows remain
  byte-for-byte untouched until a user explicitly updates them. Malformed or
  future rows remain retained but invisible.
- The three Stage 7 arrangement surfaces are Tasks, project workbench, and
  board columns. Home and Schedule comparators remain untouched. The shared
  comparator is the only ordering authority for those three surfaces; snapshot
  order is never presented as UI order.
- Pin the shared due buckets to these half-open boundaries in the injected
  `Clock` zone: OVERDUE is `due < now`; TODAY is `[now, next local midnight)`;
  THIS_WEEK is `[next local midnight, next ISO Monday at 00:00)`; LATER begins
  at that Monday boundary; null is NO_DATE. THIS_WEEK is empty on Sunday.
  Exact `now` is TODAY. The Tasks chips map Today to TODAY, Upcoming to
  THIS_WEEK plus LATER, and Overdue to OVERDUE. All three omit completed and
  deleted tasks; Inbox and All retain their existing meanings.
- Resolve the spec's feature dependency seam as follows: `DueBucket`,
  arrangement keys/data, semantic group values, and `BoardColumn` live in
  `:core:model`; `classifyDueBucket`, `taskComparator`, `arrangeTasks`, and
  `boardColumns` live in `:core:domain`; `OpenTasksApp` computes projections.
  Feature modules never gain a `:core:domain` dependency.
- `TaskGroup` carries a semantic `TaskGroupValue?`, not a user-visible
  `String`. Tasks/project features map Due, Inbox/project, and Priority values
  to their own `stringResource` copy. This satisfies the repository rule that
  new UI copy never originates in domain code.
- Saved-view/search conflict resolution: no-sort text search orders by match
  tier, then result type within a tier, then case-insensitive title and id.
  Thus an exact project may outrank a prefix task. With text plus a saved sort,
  relevance ranks and caps first; surviving tasks are then comparator-sorted
  and placed before surviving projects, whose relevance order is retained.
  A blank query with `sort != null` is a v2 filter view and returns tasks only.
- Search word-boundary matching means index zero or a preceding character that
  is not a Unicode letter or digit. Wider-haystack-only matches are substring
  tier. Normalisation remains `SearchNormalizer` with `Locale.ROOT` behaviour.
- Quick Add remains confirm-only. Parsing never mutates a title. Sigil spans
  claim before recurrence spans, recurrence spans before natural-date spans,
  and no lower-priority token may overlap an already claimed span. Every
  single-valued chip is shown; confirmation replaces that field; tags alone
  accumulate.
- Every write is a `DomainCommand` through `VaultRepository.execute`. Both
  repositories preflight the complete command before their first mutation;
  rejection leaves records, relations, revisions, activity, and backup journal
  unchanged. Room uses its existing outer transaction. Undo is produced only
  by the repository.
- New UI copy lives in module `res/values/strings.xml` in UK English. Compose
  targets stay at least 48 dp, colours remain in the Ember OKLCH system, charts
  are decorative behind merged label/value semantics, and no feature composable
  owns persistence or Hilt state.
- Tests use JUnit 4 `org.junit.Assert.*`, no mocking library,
  `runBlocking` plus `withTimeout(5_000)` where suspending, camelCase behaviour
  names, and `androidx.compose.ui.test.junit4.v2.createComposeRule`. Reuse
  `OpenTasksFixtures`; do not add a test framework.
- No device suite runs before Task 18. Earlier tasks compile instrumented tests
  only. The full connected gate runs on the sole verified disposable ADB target
  started read-only with snapshot load/save disabled; never boot, install to,
  or mutate the protected `Pixel_10_Pro_Fold` AVD.
- Each task ends with the stated focused checks, a scoped staged-name audit, a
  conventional commit, and an independent review before the next task. Fix
  review findings inside the same task boundary.
- The completion gate is
  `./gradlew testDebugUnitTest lintDebug :app:assembleDebug`; build release in a
  separate invocation. Do not claim Stage 7 complete before Task 18 records all
  uniform and stage-specific evidence.

## Scope Check

Keep one integrated plan. The approved spec intentionally couples the shared
date/arrangement/search vocabulary, dual-engine command parity, app-owned
projection/persistence, and feature rendering; splitting those contracts into
separate plans would duplicate gates and create incompatible intermediate APIs.
Phase 0 remains independently shippable, and Tasks 2–18 each retain their own
test/commit/review boundary.

## File Structure

Each path below appears in a task's **Files** block; repeated paths have one
combined responsibility here.

### Core model and domain

- `core/model/src/main/kotlin/app/opentasks/core/model/DueBucket.kt` — shared
  relative-date bucket vocabulary.
- `core/model/src/main/kotlin/app/opentasks/core/model/TaskArrangement.kt` —
  sort/group keys, semantic groups, and board-column data.
- `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt` — additive
  saved-view `SearchQuery` v2 fields.
- `core/model/src/main/kotlin/app/opentasks/core/model/Insights.kt` — daily
  completion-trend model rows.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/DueBucketClassifier.kt`
  — zone-aware half-open due classification.
- `core/domain/src/test/kotlin/app/opentasks/core/domain/DueBucketClassifierTest.kt`
  — classifier boundary and cross-zone tests.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/TaskArrangementRules.kt`
  — the sole task comparator, grouping, and board projection authority.
- `core/domain/src/test/kotlin/app/opentasks/core/domain/TaskArrangementRulesTest.kt`
  — arrangement directions, tiebreaks, group order, and board filtering.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/WorkspaceSearch.kt` —
  shared filtering, ranking, sort composition, and result cap.
- `core/domain/src/test/kotlin/app/opentasks/core/domain/WorkspaceSearchTest.kt`
  — search/filter/rank/cap determinism.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/QuickAddParser.kt` —
  pure multi-token Quick Add parser and span stripping.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/NaturalDateParser.kt` —
  reusable natural-date matches for the multi-token parser.
- `core/domain/src/test/kotlin/app/opentasks/core/domain/QuickAddParserTest.kt` —
  sigil, recurrence, estimate, overlap, locale, and strip torture tests.
- `core/domain/src/test/kotlin/app/opentasks/core/domain/NaturalDateParserTest.kt`
  — retained natural-date grammar regressions.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt` —
  additive CreateTask fields, DuplicateTask command, and rejection vocabulary.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/TaskDuplication.kt` —
  pure duplicate-field projection and fresh checklist ids.
- `core/domain/src/test/kotlin/app/opentasks/core/domain/TaskDuplicationTest.kt`
  — exact copied/excluded field contract and title cap.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/InsightsEngine.kt` —
  zone-aware per-day completion aggregation.
- `core/domain/src/test/kotlin/app/opentasks/core/domain/InsightsEngineTest.kt`
  — range and local-day boundary trend tests.

### Data engines and payloads

- `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
  — normalised task publication, shared search, widened create, and duplicate
  parity.
- `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt` —
  shared search plus atomic widened-create/duplicate Room handlers.
- `core/data/src/main/kotlin/app/opentasks/core/data/SavedViewPayloadCodec.kt` —
  strict version-first v1/v2 decoding and deterministic v2 encoding.
- `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`
  — in-memory publication, create, and duplicate behavioural parity.
- `core/data/src/test/kotlin/app/opentasks/core/data/InMemorySavedViewCommandTest.kt`
  — in-memory saved-view v2 command/undo persistence.
- `core/data/src/test/kotlin/app/opentasks/core/data/SavedViewPayloadCodecTest.kt`
  — codec round trips, exact bytes, bounds, and fail-closed matrix.
- `core/data/src/test/kotlin/app/opentasks/core/data/SearchExtensionTest.kt` —
  in-memory adapter coverage for the shared search rule.
- `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomSavedViewCommandInstrumentedTest.kt`
  — Room saved-view v2 command/undo persistence.
- `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`
  — Room search, widened create, and duplicate parity.
- `core/data/src/androidTest/kotlin/app/opentasks/core/data/backup/BackupRecordImporterInstrumentedTest.kt`
  — v1/v2/future saved-view recovery retention.

### App state, roots, and UI

- `app/src/main/kotlin/app/opentasks/ViewArrangementStore.kt` — validated
  `view_prefs` persistence and its process-local StateFlow.
- `app/src/androidTest/kotlin/app/opentasks/ViewArrangementStoreInstrumentedTest.kt`
  — exact keys, normalisation, round trip, and privacy assertions.
- `app/src/test/kotlin/app/opentasks/ViewArrangementStateTest.kt` — pure
  defaults and non-inserting per-project lookup tests.
- `app/src/main/kotlin/app/opentasks/di/AppModule.kt` — singleton
  `ViewArrangementStore` provider.
- `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt` — exposes
  arrangement state/mutators, v2 search, and atomic CreateTask dispatch.
- `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt` — root clock, app-owned
  projections, arrangement callbacks, Quick Add, and duplicate routing.
- `app/src/main/kotlin/app/opentasks/SearchSurface.kt` — v2 filter controls and
  saved-view-id-stable refinement.
- `app/src/main/kotlin/app/opentasks/QuickAddSheet.kt` — confirm/dismiss chips
  over injected-clock parser output.
- `app/src/test/kotlin/app/opentasks/QuickAddDraftTest.kt` — host-side proof
  that detection is inert, confirmations replace or accumulate correctly, and
  due/recurrence clears remain coupled as specified.
- `app/src/main/res/values/strings.xml` — app-owned search and Quick Add copy.
- `app/src/androidTest/kotlin/app/opentasks/SearchSurfaceSavedViewsInstrumentedTest.kt`
  — filter controls, active id, refinement, save, rename, and delete UI.
- `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt`
  — search/Quick Add restoration without filter identity loss.
- `app/src/androidTest/kotlin/app/opentasks/QuickAddSheetInstrumentedTest.kt` —
  confirm-only chip interactions and submitted values.
- `app/src/androidTest/kotlin/app/opentasks/QuickAddPrefillRootWiringInstrumentedTest.kt`
  — share/process-text prefill grammar wiring.
- `app/src/androidTest/kotlin/app/opentasks/input/ShortcutRootWiringInstrumentedTest.kt`
  — shortcut Quick Add grammar wiring.
- `app/src/androidTest/kotlin/app/opentasks/LightThemeInstrumentedTest.kt` —
  dark-device configuration still renders the light scheme.
- `app/src/main/kotlin/app/opentasks/widget/TodayWidget.kt` — replace widget
  hardcoded light/dark palette selection with the pinned light palette.
- `app/src/main/res/values/themes.xml` — keep the platform launch theme light.
- `app/src/main/res/values-night/themes.xml` — make night-qualified launch
  styling identical to the light launch theme.
- `app/build.gradle.kts` — release 1.1.0 version metadata only.

### Feature surfaces

- `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt` —
  corrected date filters, grouped list controls, and duplicate detail action.
- `feature/tasks/src/main/res/values/strings.xml` — Tasks arrangement and
  duplicate copy.
- `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TasksDateFilterInstrumentedTest.kt`
  — date-chip filtering regression.
- `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TasksArrangementInstrumentedTest.kt`
  — stateless grouped rendering and control callbacks.
- `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskEditorInstrumentedTest.kt`
  — detail duplicate entry-point coverage.
- `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`
  — workbench arrangement controls and app-supplied board columns.
- `feature/projects/src/main/kotlin/app/opentasks/feature/projects/BoardView.kt` —
  model-column rendering, move menu, and duplicate card action.
- `feature/projects/src/main/res/values/strings.xml` — workbench, board sort,
  and duplicate copy.
- `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/ProjectWorkbenchInstrumentedTest.kt`
  — list grouping and board-sort callback coverage.
- `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/BoardViewInstrumentedTest.kt`
  — plain-column move/drag/accessibility and duplicate-menu coverage.
- `feature/projects/src/test/kotlin/app/opentasks/feature/projects/BoardColumnsTest.kt`
  — feature-owned move-target order only.
- `feature/more/src/main/kotlin/app/opentasks/feature/more/InsightsScreen.kt` —
  dot-run metrics plus chart/table daily trend.
- `feature/more/src/main/res/values/strings.xml` — completion-trend copy.
- `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt`
  — dot semantics, trend parity, scrolling, and light-theme regression.

### Design system

- `core/designsystem/build.gradle.kts` — existing unit-test source support for
  dot geometry tests; no production dependency.
- `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/DotMatrix.kt`
  — reusable dot-run and dotted-area Canvas primitives.
- `core/designsystem/src/test/kotlin/app/opentasks/core/designsystem/DotMatrixTest.kt`
  — deterministic dot-count/geometry checks.
- `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/OpenTasksTheme.kt`
  — light-only scheme and removal of dark constants.

### Documentation and qualification

- `docs/architecture.md` — arrangement/search/command ownership.
- `DESIGN.md` — light-only and Stage 7 interaction language.
- `PRODUCT.md` — shipped Stage 7 behaviour and deliberate ceilings.
- `docs/threat-model.md` — confirms device-local enum/id preferences only.
- `CLAUDE.md` — current release/version and repository invariants.
- `HANDOFF.md` — execution/release checkpoint and remaining roadmap state.
- `docs/qualification/stage7-ergonomics-sweep.md` — complete Stage 7 gate
  evidence.
- `docs/qualification/release-1.1.0-sideload.md` — signed sideload release
  evidence.

---

### Task 1 (Phase 0 prerequisite): Correct Tasks date chips and establish `DueBucket`

**Files:**

- Create: `core/model/src/main/kotlin/app/opentasks/core/model/DueBucket.kt`
- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/DueBucketClassifier.kt`
- Create:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/DueBucketClassifierTest.kt`
- Modify:
  `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt:171-260`
  (`TaskFilter` and `TasksScreen` filter derivation)
- Create:
  `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TasksDateFilterInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt:239-253`
  (`OpenTasksApp` signature) and `:945-1136` (`entry<TasksRoute>`)

**Interfaces:**

- Consumes: `Task.due`, `Task.isCompleted`, `Task.deletedAt`, and an injected
  `java.time.Clock`.
- Produces:

```kotlin
enum class DueBucket { OVERDUE, TODAY, THIS_WEEK, LATER, NO_DATE }

fun classifyDueBucket(due: ZonedMoment?, clock: Clock): DueBucket
```

  `OpenTasksApp` also supplies
  `dueBucketsByTaskId: Map<TaskId, DueBucket>` to `TasksScreen`. Tasks 2, 7,
  and 8 consume the enum and classifier unchanged.

- [ ] **Step 1: Write the complete boundary unit test file**

```kotlin
package app.opentasks.core.domain

import app.opentasks.core.model.DueBucket.LATER
import app.opentasks.core.model.DueBucket.NO_DATE
import app.opentasks.core.model.DueBucket.OVERDUE
import app.opentasks.core.model.DueBucket.THIS_WEEK
import app.opentasks.core.model.DueBucket.TODAY
import app.opentasks.core.model.ZonedMoment
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DueBucketClassifierTest {
    private val zone = ZoneId.of("Asia/Bangkok")
    private val now = ZonedDateTime.of(2026, 8, 9, 10, 0, 0, 0, zone).toInstant()
    private val clock = Clock.fixed(now, zone)

    @Test
    fun sundayBucketsAreHalfOpenAndThisWeekIsEmpty() {
        assertEquals(OVERDUE, classifyDueBucket(moment(now.minusNanos(1)), clock))
        assertEquals(TODAY, classifyDueBucket(moment(now), clock))
        assertEquals(TODAY, classifyDueBucket(moment("2026-08-09T23:59:59+07:00"), clock))
        assertEquals(LATER, classifyDueBucket(moment("2026-08-10T00:00:00+07:00"), clock))
        assertEquals(NO_DATE, classifyDueBucket(null, clock))
    }

    @Test
    fun thisWeekEndsAtTheNextIsoMonday() {
        val mondayClock = Clock.fixed(
            ZonedDateTime.of(2026, 8, 10, 10, 0, 0, 0, zone).toInstant(),
            zone,
        )
        assertEquals(
            THIS_WEEK,
            classifyDueBucket(moment("2026-08-16T23:59:59+07:00"), mondayClock),
        )
        assertEquals(
            LATER,
            classifyDueBucket(moment("2026-08-17T00:00:00+07:00"), mondayClock),
        )
    }

    @Test
    fun dueZoneDoesNotOverrideClockZoneBoundaries() {
        val sameInstantDifferentZone = ZonedMoment(
            instant = OffsetDateTime.parse("2026-08-09T23:00:00+07:00").toInstant(),
            zoneId = "Pacific/Kiritimati",
        )
        assertEquals(TODAY, classifyDueBucket(sameInstantDifferentZone, clock))
    }

    private fun moment(instant: Instant): ZonedMoment = ZonedMoment(instant, zone.id)

    private fun moment(value: String): ZonedMoment =
        ZonedMoment(OffsetDateTime.parse(value).toInstant(), zone.id)
}
```

- [ ] **Step 2: Run the RED unit test**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.DueBucketClassifierTest"
```

Expected: compilation fails because `DueBucket` and
`classifyDueBucket` do not exist.

- [ ] **Step 3: Add the shared enum**

```kotlin
package app.opentasks.core.model

enum class DueBucket { OVERDUE, TODAY, THIS_WEEK, LATER, NO_DATE }
```

- [ ] **Step 4: Implement the smallest classifier**

```kotlin
package app.opentasks.core.domain

import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.ZonedMoment
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

fun classifyDueBucket(due: ZonedMoment?, clock: Clock): DueBucket {
    val dueAt = due?.instant ?: return DueBucket.NO_DATE
    val now = clock.instant()
    if (dueAt.isBefore(now)) return DueBucket.OVERDUE
    val today = LocalDate.now(clock)
    val tomorrow = today.plusDays(1).atStartOfDay(clock.zone).toInstant()
    if (dueAt.isBefore(tomorrow)) return DueBucket.TODAY
    val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        .atStartOfDay(clock.zone)
        .toInstant()
    return if (dueAt.isBefore(nextMonday)) DueBucket.THIS_WEEK else DueBucket.LATER
}
```

- [ ] **Step 5: Run the classifier test GREEN**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.DueBucketClassifierTest"
```

Expected: PASS.

- [ ] **Step 6: Write the Compose regression test**

Use fixture copies so every task has a complete valid model; the helper supplies
every non-default `TasksScreen` argument:

```kotlin
@RunWith(AndroidJUnit4::class)
class TasksDateFilterInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun dateChipsUseOnlyTheSuppliedSemanticBuckets() {
        val base = OpenTasksFixtures.tasks.first().copy(
            projectId = OpenTasksFixtures.studioProject.id,
            completedAt = null,
            deletedAt = null,
        )
        val today = base.copy(id = TaskId("date-today"), title = "Today ruled")
        val week = base.copy(id = TaskId("date-week"), title = "Week ruled")
        val later = base.copy(id = TaskId("date-later"), title = "Later ruled")
        val overdue = base.copy(id = TaskId("date-overdue"), title = "Overdue ruled")
        val completed = base.copy(
            id = TaskId("date-completed"),
            title = "Completed overdue",
            semanticStatus = SemanticStatus.COMPLETED,
            completedAt = Instant.parse("2026-08-08T03:00:00Z"),
        )
        val deleted = base.copy(
            id = TaskId("date-deleted"),
            title = "Deleted overdue",
            deletedAt = Instant.parse("2026-08-08T03:00:00Z"),
        )
        val inbox = base.copy(
            id = TaskId("date-inbox"),
            title = "Inbox ruled",
            projectId = null,
        )
        val tasks = listOf(today, week, later, overdue, completed, deleted, inbox)
        val buckets = mapOf(
            today.id to DueBucket.TODAY,
            week.id to DueBucket.THIS_WEEK,
            later.id to DueBucket.LATER,
            overdue.id to DueBucket.OVERDUE,
            completed.id to DueBucket.OVERDUE,
            deleted.id to DueBucket.OVERDUE,
            inbox.id to DueBucket.NO_DATE,
        )

        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = tasks,
                    projectNames = OpenTasksFixtures.snapshot.projects.associate {
                        it.id to it.name
                    },
                    workflowStatuses = OpenTasksFixtures.workflowStatuses,
                    tags = OpenTasksFixtures.tags,
                    selectedTaskId = null,
                    showDetailPane = false,
                    onSelectTask = {},
                    onCloseDetail = {},
                    onCompleteTask = {},
                    onChangeTaskStatus = { _, _ -> },
                    onDeleteTask = {},
                    activeTimerTaskId = null,
                    onToggleTimer = {},
                    onUpdateTask = { _, _ -> },
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                    dueBucketsByTaskId = buckets,
                )
            }
        }

        composeRule.onNodeWithTag("task-filter-today").performClick()
        composeRule.onNodeWithText(today.title).assertIsDisplayed()
        composeRule.onNodeWithText(overdue.title).assertDoesNotExist()

        composeRule.onNodeWithTag("task-filter-upcoming").performClick()
        composeRule.onNodeWithText(week.title).assertIsDisplayed()
        composeRule.onNodeWithText(later.title).assertIsDisplayed()

        composeRule.onNodeWithTag("task-filter-overdue").performClick()
        composeRule.onNodeWithText(overdue.title).assertIsDisplayed()
        composeRule.onNodeWithText(completed.title).assertDoesNotExist()
        composeRule.onNodeWithText(deleted.title).assertDoesNotExist()

        composeRule.onNodeWithTag("task-filter-inbox").performClick()
        composeRule.onNodeWithText(inbox.title).assertIsDisplayed()
        composeRule.onNodeWithTag("task-filter-all").performClick()
        composeRule.onNodeWithText(deleted.title).assertDoesNotExist()
    }
}
```

- [ ] **Step 7: Replace the three bogus predicates**

Add `dueBucketsByTaskId: Map<TaskId, DueBucket> = emptyMap()` to `TasksScreen`
and use this exact filter body; Inbox and All stay byte-for-byte unchanged:

```kotlin
val visibleTasks = when (filter) {
    TaskFilter.INBOX -> tasks.filter { it.projectId == null && it.deletedAt == null }
    TaskFilter.TODAY -> tasks.filter {
        !it.isCompleted && it.deletedAt == null &&
            dueBucketsByTaskId[it.id] == DueBucket.TODAY
    }
    TaskFilter.UPCOMING -> tasks.filter {
        !it.isCompleted && it.deletedAt == null &&
            dueBucketsByTaskId[it.id] in setOf(DueBucket.THIS_WEEK, DueBucket.LATER)
    }
    TaskFilter.OVERDUE -> tasks.filter {
        !it.isCompleted && it.deletedAt == null &&
            dueBucketsByTaskId[it.id] == DueBucket.OVERDUE
    }
    TaskFilter.ALL -> tasks.filter { it.deletedAt == null }
}
```

- [ ] **Step 8: Wire one injected root clock**

Add `clock: Clock = Clock.systemDefaultZone()` to `OpenTasksApp`, derive one
map from the current snapshot, and pass it to the Tasks route:

```kotlin
val dueBucketsByTaskId = remember(snapshot.tasks, clock) {
    snapshot.tasks.associate { task -> task.id to classifyDueBucket(task.due, clock) }
}
```

At the existing `TasksScreen` call inside `entry<TasksRoute>`, add exactly
`dueBucketsByTaskId = dueBucketsByTaskId` after `tasks = snapshot.tasks`.
Leave every existing callback—including the four-field
`DomainCommand.UpdateChecklistItem(taskId, item.id, item.text,
item.completed)` construction—unchanged. Do not alter Home or Schedule.

- [ ] **Step 9: Compile the Compose regression and app wiring**

```bash
./gradlew :feature:tasks:compileDebugAndroidTestKotlin :app:compileDebugKotlin
```

Expected: PASS; no device test runs.

- [ ] **Step 10: Check the patch**

```bash
git diff --check
```

- [ ] **Step 11: Stage and audit exactly the Phase 0 files**

```bash
git add core/model/src/main/kotlin/app/opentasks/core/model/DueBucket.kt \
  core/domain/src/main/kotlin/app/opentasks/core/domain/DueBucketClassifier.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/DueBucketClassifierTest.kt \
  feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt \
  feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TasksDateFilterInstrumentedTest.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt
git diff --cached --name-only
```

Expected: exactly the six paths in this task's **Files** block.

- [ ] **Step 12: Commit Phase 0**

```bash
git commit -m "fix: classify task date filters"
```

- [ ] **Step 13: Clear the Stage 7 gate and record its audit base**

After Actions billing is restored and PRs #14–#19 are resolved with fresh green
checks, fast-forward local `main`, verify a clean scoped state, and print the
base:

```bash
git fetch origin
git merge --ff-only origin/main
for pr in 14 15 16 17 18 19; do gh pr checks "$pr" --required; done
git status --short --branch
git rev-parse HEAD
```

Expected: no billing failure, every resolved PR's required checks are green,
and `HEAD` includes Phase 0 plus the resolved queue. Using `apply_patch`, write
the printed full SHA as the first SHA in
`.superpowers/sdd/2026-08-10-stage-7-ergonomics-sweep-plan/progress.md`. Only
then begin Task 2.

---

### Task 2: Add the single arrangement authority and engine-order parity

**Files:**

- Create:
  `core/model/src/main/kotlin/app/opentasks/core/model/TaskArrangement.kt`
- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/TaskArrangementRules.kt`
- Create:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/TaskArrangementRulesTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt:83-112`
  (constructor publication), `:682-763` (`instantiateProjectTemplate`),
  `:3567-3616` (`publish`), and `:3753-3764`
  (`WorkspaceSnapshot.withResolvedDependencyState`)
- Modify:
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt:34-40`
  (class fixtures) and `:1283-1336`
  (`projectTemplateCaptureInstantiationAndDeleteUndoPreserveReusableStructure`)
- Modify:
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryActivityGenerationTest.kt`
  (`statusChangeRecordsOldAndNewNames` and
  `generated501EntriesKeepNewest500WithOldestEviction`)

**Interfaces:**

- Consumes: Task 1's `DueBucket` and `classifyDueBucket`; task/project/status
  snapshots.
- Produces these stable model contracts:

```kotlin
enum class TaskSortKey { DUE, PRIORITY, TITLE, UPDATED }
enum class TaskGroupKey { DUE_BUCKET, PROJECT, PRIORITY }

data class TaskArrangement(
    val sort: TaskSortKey = TaskSortKey.DUE,
    val groupBy: TaskGroupKey? = null,
)

sealed interface TaskGroupValue {
    data class Due(val bucket: DueBucket) : TaskGroupValue
    data class Project(val projectId: ProjectId?) : TaskGroupValue
    data class PriorityValue(val priority: Priority) : TaskGroupValue
}

data class TaskGroup(
    val value: TaskGroupValue?,
    val tasks: List<Task>,
)

data class BoardColumn(
    val status: WorkflowStatus,
    val tasks: List<Task>,
)
```

  and these pure domain contracts:

```kotlin
fun taskComparator(sort: TaskSortKey): Comparator<Task>

fun arrangeTasks(
    tasks: List<Task>,
    arrangement: TaskArrangement,
    projectNames: Map<ProjectId, String>,
    clock: Clock,
): List<TaskGroup>

fun boardColumns(
    project: Project,
    statuses: List<WorkflowStatus>,
    tasks: List<Task>,
    sort: TaskSortKey = TaskSortKey.PRIORITY,
): List<BoardColumn>
```

  Tasks 4–9 consume these signatures unchanged.

- [ ] **Step 1: Write comparator and grouping RED tests**

Cover every fixed direction, null-due-last, title/id ties, descending revision
wall time, flat grouping, bucket order, Inbox-first project order, project-name
order, missing-project deterministic fallback, and Urgent-to-None priority
order. Use fixture copies so every symbol in the test is defined:

```kotlin
private val clock = Clock.fixed(
    Instant.parse("2026-08-10T03:00:00Z"),
    ZoneId.of("Asia/Bangkok"),
)
private val base = OpenTasksFixtures.tasks.first().copy(
    due = ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), "Asia/Bangkok"),
    priority = Priority.HIGH,
    revision = Revision(DeviceId("arrangement-test"), 100L, 0),
)
private val alphaA = base.copy(id = TaskId("a"), title = "Alpha")
private val alphaB = base.copy(id = TaskId("b"), title = "alpha")
private val betaB = base.copy(id = TaskId("c"), title = "Beta")

@Test
fun everyComparatorFallsBackToTitleThenId() {
    TaskSortKey.entries.forEach { sort ->
        val actual = listOf(betaB, alphaB, alphaA).sortedWith(taskComparator(sort))
        assertEquals(listOf(alphaA.id, alphaB.id, betaB.id), actual.map(Task::id))
    }
}

@Test
fun projectGroupsPutInboxThenKnownNamesThenMissingIds() {
    val alphaId = ProjectId("project-alpha")
    val zuluId = ProjectId("project-zulu")
    val missingA = ProjectId("missing-a")
    val missingZ = ProjectId("missing-z")
    val groups = arrangeTasks(
        listOf(
            base.copy(id = TaskId("zulu"), projectId = zuluId),
            base.copy(id = TaskId("inbox"), projectId = null),
            base.copy(id = TaskId("alpha"), projectId = alphaId),
            base.copy(id = TaskId("missing-z-task"), projectId = missingZ),
            base.copy(id = TaskId("missing-a-task"), projectId = missingA),
        ),
        TaskArrangement(TaskSortKey.TITLE, TaskGroupKey.PROJECT),
        mapOf(zuluId to "Zulu", alphaId to "alpha"),
        clock,
    )
    assertEquals(
        listOf(
            TaskGroupValue.Project(null),
            TaskGroupValue.Project(alphaId),
            TaskGroupValue.Project(zuluId),
            TaskGroupValue.Project(missingA),
            TaskGroupValue.Project(missingZ),
        ),
        groups.map(TaskGroup::value),
    )
}
```

Add the same file's board test with real fixture statuses:

```kotlin
@Test
fun boardColumnsFilterCardsAndUseTheRequestedSharedComparator() {
    val project = OpenTasksFixtures.studioProject
    val statuses = OpenTasksFixtures.workflowStatuses
    val backlog = statuses.single { it.id == OpenTasksFixtures.backlog }
    val alpha = alphaA.copy(projectId = project.id, statusId = backlog.id)
    val beta = betaB.copy(projectId = project.id, statusId = backlog.id)
    val completed = beta.copy(
        id = TaskId("completed"),
        semanticStatus = SemanticStatus.COMPLETED,
        completedAt = clock.instant(),
    )
    val deleted = beta.copy(id = TaskId("deleted"), deletedAt = clock.instant())

    val priorityColumns = boardColumns(project, statuses, listOf(beta, alpha, completed, deleted))
    val titleColumns = boardColumns(
        project,
        statuses,
        listOf(beta, alpha, completed, deleted),
        TaskSortKey.TITLE,
    )

    assertEquals(
        statuses.filter { it.projectId == project.id && it.archivedAt == null }
            .sortedBy(WorkflowStatus::rank).map(WorkflowStatus::id),
        titleColumns.map { it.status.id },
    )
    assertEquals(listOf(alpha.id, beta.id), titleColumns.single { it.status == backlog }.tasks.map(Task::id))
    assertEquals(
        listOf(alpha.id, beta.id),
        priorityColumns.single { it.status == backlog }.tasks.map(Task::id),
    )
}
```

- [ ] **Step 2: Write constructor and mutation publication parity RED tests**

```kotlin
@Test
fun everyTaskPublicationSortsOnlySnapshotTasksById() = runBlocking {
    withTimeout(5_000) {
        val source = OpenTasksFixtures.tasks.first().copy(
            projectId = null,
            completedAt = null,
            deletedAt = null,
        )
        val z = source.copy(id = TaskId("z"), title = "Zulu")
        val a = source.copy(id = TaskId("a"), title = "Alpha")
        val m = source.copy(id = TaskId("m"), title = "Mike")
        val initial = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(z, a, m),
            home = OpenTasksFixtures.snapshot.home.copy(
                focusTasks = listOf(z, m, a),
                upcomingTasks = listOf(m, z),
            ),
        )
        val local = InMemoryVaultRepository(initial = initial)

        assertEquals(listOf("a", "m", "z"), local.currentWorkspace().tasks.map { it.id.value })
        assertEquals(listOf("z", "m", "a"), local.currentWorkspace().home.focusTasks.map { it.id.value })
        assertEquals(listOf("m", "z"), local.currentWorkspace().home.upcomingTasks.map { it.id.value })

        local.execute(DomainCommand.DeleteTask(m.id, Instant.parse("2026-08-10T04:00:00Z")))
        val mutated = local.currentWorkspace()
        assertEquals(listOf("a", "m", "z"), mutated.tasks.map { it.id.value })
        assertEquals(m.id, mutated.tasks.single { it.id == m.id }.id)
    }
}
```

In the existing template test, add this assertion immediately after
`val snapshot = repository.observeWorkspace().value` so the direct template
publication cannot bypass normalisation:

```kotlin
assertEquals(
    snapshot.tasks.map { it.id.value }.sorted(),
    snapshot.tasks.map { it.id.value },
)
```

In `InMemoryActivityGenerationTest`, make both order-sensitive tests select the
started fixture semantically instead of relying on `tasks.first()` after
snapshot id-order normalisation:

```kotlin
val task = repository.currentWorkspace().tasks.first {
    it.semanticStatus == SemanticStatus.STARTED
}
```

Apply that lookup in `statusChangeRecordsOldAndNewNames` and
`generated501EntriesKeepNewest500WithOldestEviction`; leave the other activity
tests unchanged.

- [ ] **Step 3: Run the RED tests**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.TaskArrangementRulesTest"
./gradlew :core:data:testDebugUnitTest \
  --tests "app.opentasks.core.data.InMemoryVaultRepositoryTest"
```

Expected: the arrangement types/functions are unresolved and insertion-order
assertion fails.

- [ ] **Step 4: Implement model vocabulary and pure rules**

Put no display strings in the model or domain. `taskComparator` must append the
same `String.CASE_INSENSITIVE_ORDER` title comparison and `task.id.value`
comparison after the primary key. `arrangeTasks` first sorts once, then groups
in the approved semantic order; a flat arrangement returns exactly one
`TaskGroup(value = null, tasks = sorted)` even when the list is empty.

For project groups, order `Project(null)` first; then known projects by
case-insensitive name and project id; then missing project ids by
`projectId.value`. Missing projects never interleave with known names. For due
groups use enum order exactly as declared in Task 1. `boardColumns` filters
active project statuses by rank and filters cards by project/status,
`deletedAt == null`, and `!isCompleted` before sorting.

- [ ] **Step 5: Route every in-memory task publication through one normaliser**

Keep the existing `withResolvedDependencyState` helper and widen it instead of
adding another abstraction. Resolve once, build or reconcile Home from that
unsorted resolved order, and sort only the public `WorkspaceSnapshot.tasks`:

```kotlin
private fun WorkspaceSnapshot.withResolvedDependencyState(
    rebuildHomeTaskLists: Boolean = false,
): WorkspaceSnapshot {
    val resolvedTasks = resolveDependencyState(tasks)
    val tasksById = resolvedTasks.associateBy(Task::id)
    val resolvedHome = if (rebuildHomeTaskLists) {
        val activeTasks = resolvedTasks.filter { it.deletedAt == null }
        home.copy(
            focusTasks = activeTasks.filterNot(Task::isCompleted).take(3),
            upcomingTasks = activeTasks.filter { it.start != null || it.due != null }.take(3),
        )
    } else {
        home.copy(
            focusTasks = home.focusTasks.mapNotNull { tasksById[it.id] },
            upcomingTasks = home.upcomingTasks.mapNotNull { tasksById[it.id] },
        )
    }
    return copy(
        home = resolvedHome,
        tasks = resolvedTasks.sortedBy { it.id.value },
    )
}
```

The constructor keeps the default `false`. In `publish`, retain its existing
named `current.copy` arguments, set its `tasks` argument to the supplied
`tasks`, then chain
`.withResolvedDependencyState(rebuildHomeTaskLists = true)` before
`.withReconciledTimeState(at = at, entries = timeEntries)`. In
`instantiateProjectTemplate`, remove its local `resolvedTasks`/`activeTasks`
projection, set the copied snapshot's `tasks` field to `current.tasks + tasks`,
then call the same helper with `true`. These are the only
current task-publication boundaries; scratch-import assignment receives an
already normalised snapshot from its own in-memory repository. Do not touch
Room, whose DAO already orders snapshot tasks by id.

- [ ] **Step 6: Run the focused suites GREEN**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.TaskArrangementRulesTest"
./gradlew :core:data:testDebugUnitTest \
  --tests "app.opentasks.core.data.InMemoryVaultRepositoryTest" \
  --tests "app.opentasks.core.data.InMemoryActivityGenerationTest"
```

Expected: PASS, including constructor, mutation, and template publication
parity, plus semantic fixture selection in the two activity-generation tests.

- [ ] **Step 7: Audit task publication call sites and patch validity**

```bash
rg -n 'tasks\s*=|withResolvedDependencyState|private fun publish' \
  core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt
git diff --check
```

Expected: every path that replaces `WorkspaceSnapshot.tasks` either calls the
normaliser or receives a snapshot already normalised by an in-memory
repository; Home is never built from the id-sorted list.

- [ ] **Step 8: Stage and audit exactly the arrangement-authority files**

```bash
git add core/model/src/main/kotlin/app/opentasks/core/model/TaskArrangement.kt \
  core/domain/src/main/kotlin/app/opentasks/core/domain/TaskArrangementRules.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/TaskArrangementRulesTest.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/InMemoryActivityGenerationTest.kt
git diff --cached --name-only
```

Expected: exactly the six paths in this task's **Files** block.

- [ ] **Step 9: Commit**

```bash
git commit -m "feat: centralize task arrangement rules"
```

---

### Task 3: Persist device-local view arrangements

**Files:**

- Create: `app/src/main/kotlin/app/opentasks/ViewArrangementStore.kt`
- Create:
  `app/src/androidTest/kotlin/app/opentasks/ViewArrangementStoreInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt:105-145`
  (`AppModule` process-scoped SharedPreferences providers)
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt:80-177`
  (`WorkspaceViewModel` constructor, exposed state, and mutators)
- Create: `app/src/test/kotlin/app/opentasks/ViewArrangementStateTest.kt`

**Interfaces:**

- Consumes: Task 2's `TaskArrangement`, `TaskSortKey`, and `TaskGroupKey`; plain
  SharedPreferences file `view_prefs`.
- Produces:

```kotlin
data class ViewArrangementState(
    val tasks: TaskArrangement = TaskArrangement(),
    val workbenchByProject: Map<ProjectId, TaskArrangement> = emptyMap(),
    val boardSortByProject: Map<ProjectId, TaskSortKey> = emptyMap(),
) {
    fun workbenchFor(projectId: ProjectId): TaskArrangement
    fun boardSortFor(projectId: ProjectId): TaskSortKey
}

class ViewArrangementStore(private val prefs: SharedPreferences) {
    val state: StateFlow<ViewArrangementState>
    fun load(): ViewArrangementState
    fun saveTasks(arrangement: TaskArrangement)
    fun saveWorkbench(projectId: ProjectId, arrangement: TaskArrangement)
    fun saveBoardSort(projectId: ProjectId, sort: TaskSortKey)
}
```

  `WorkspaceViewModel` exposes `val viewArrangement:
  StateFlow<ViewArrangementState>` plus `setTasksArrangement`,
  `setWorkbenchArrangement`, and `setBoardSort`. Tasks 4–6 consume only these
  ViewModel methods/state; no `SavedStateHandle` arrangement keys are added.

- [ ] **Step 1: Write store and state RED tests**

Use an isolated instrumented SharedPreferences file. Assert exact keys:

```text
tasks_sort
tasks_group
workbench_sort:{projectId.value}
workbench_group:{projectId.value}
board_sort:{projectId.value}
```

Write two projects, reconstruct the store, and assert round-trip. Directly
seed unknown enum strings and assert defaults: Tasks/workbench DUE with no
group, board PRIORITY. Assert workbench PROJECT grouping and board UPDATED are
rejected to their defaults on load. Seed `workbench_sort:` with an empty
project-ID suffix plus a nonmatching key family, and assert neither creates a
project entry. Assert `prefs.all` contains only enum names and project IDs,
never a task title or other vault value.

Pin invalid programmatic selections as well as invalid stored strings. The
immediate StateFlow value and a newly constructed store must agree:

```kotlin
private val context: Context
    get() = ApplicationProvider.getApplicationContext<Context>()

private val prefs: SharedPreferences
    get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

@After
fun tearDown() {
    context.deleteSharedPreferences(PREFS_NAME)
}

private companion object {
    const val PREFS_NAME = "view_arrangement_store_instrumented_test"
}

@Test
fun invalidSelectionsNormaliseBeforeStateAndPreferences() {
    val projectId = ProjectId("project-normalisation")
    val store = ViewArrangementStore(prefs)

    store.saveWorkbench(
        projectId,
        TaskArrangement(TaskSortKey.UPDATED, TaskGroupKey.PROJECT),
    )
    store.saveBoardSort(projectId, TaskSortKey.UPDATED)

    assertEquals(
        TaskArrangement(TaskSortKey.UPDATED, groupBy = null),
        store.state.value.workbenchFor(projectId),
    )
    assertEquals(TaskSortKey.PRIORITY, store.state.value.boardSortFor(projectId))

    val reloaded = ViewArrangementStore(prefs).state.value
    assertEquals(store.state.value, reloaded)
    assertEquals("UPDATED", prefs.getString("workbench_sort:${projectId.value}", null))
    assertFalse(prefs.contains("workbench_group:${projectId.value}"))
    assertEquals("PRIORITY", prefs.getString("board_sort:${projectId.value}", null))
}

@Test
fun blankProjectIdsAreRejectedWithoutMutation() {
    val store = ViewArrangementStore(prefs)
    val initialPreferences = prefs.all
    val initialState = store.state.value
    val blankProjectId = ProjectId("")

    assertThrows(IllegalArgumentException::class.java) {
        store.saveWorkbench(blankProjectId, TaskArrangement(TaskSortKey.TITLE))
    }
    assertThrows(IllegalArgumentException::class.java) {
        store.saveBoardSort(blankProjectId, TaskSortKey.TITLE)
    }

    assertEquals(initialPreferences, prefs.all)
    assertEquals(initialState, store.state.value)
}

@Test
fun loadRefreshesStateAndIgnoresEmptyOrUnrelatedProjectKeys() {
    val store = ViewArrangementStore(prefs)
    prefs.edit()
        .putString("tasks_sort", TaskSortKey.UPDATED.name)
        .putString("tasks_group", TaskGroupKey.PRIORITY.name)
        .putString("workbench_sort:", TaskSortKey.TITLE.name)
        .putString("not_a_view_key:project", TaskSortKey.DUE.name)
        .apply()

    val loaded = store.load()

    assertEquals(
        TaskArrangement(TaskSortKey.UPDATED, TaskGroupKey.PRIORITY),
        loaded.tasks,
    )
    assertTrue(loaded.workbenchByProject.isEmpty())
    assertEquals(loaded, store.state.value)
}
```

In `ViewArrangementStateTest`, use this exact non-mutating lookup assertion:

```kotlin
@Test
fun missingProjectsReturnDefaultsWithoutCreatingEntries() {
    val state = ViewArrangementState()
    val projectId = ProjectId("missing")

    assertEquals(TaskArrangement(), state.workbenchFor(projectId))
    assertEquals(TaskSortKey.PRIORITY, state.boardSortFor(projectId))
    assertTrue(state.workbenchByProject.isEmpty())
    assertTrue(state.boardSortByProject.isEmpty())
}
```

- [ ] **Step 2: Run the RED checks**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "app.opentasks.ViewArrangementStateTest"
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: compilation fails because the store/state do not exist.

- [ ] **Step 3: Implement the plain store**

Use `SharedPreferences.edit` directly; do not introduce an interface, JSON,
repository, cleanup worker, or preference listener. `read()` scans `prefs.all`
once, parses only the five exact key families with `enum.entries.firstOrNull`,
and ignores empty project-ID suffixes and nonmatching key families. Project IDs
remain opaque; do not parse them as UUIDs. Normalise each incoming value exactly
once, then use that same value for both preferences and StateFlow:

```kotlin
class ViewArrangementStore(private val prefs: SharedPreferences) {
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<ViewArrangementState> = mutableState.asStateFlow()

    fun load(): ViewArrangementState =
        read().also { mutableState.value = it }

    fun saveTasks(arrangement: TaskArrangement) {
        val normalised = arrangement
        prefs.edit()
            .putString(TASKS_SORT, normalised.sort.name)
            .applyGroup(TASKS_GROUP, normalised.groupBy)
            .apply()
        mutableState.update { it.copy(tasks = normalised) }
    }

    fun saveWorkbench(projectId: ProjectId, arrangement: TaskArrangement) {
        require(projectId.value.isNotBlank())
        val normalised = arrangement.copy(
            groupBy = arrangement.groupBy?.takeIf {
                it == TaskGroupKey.DUE_BUCKET || it == TaskGroupKey.PRIORITY
            },
        )
        prefs.edit()
            .putString("$WORKBENCH_SORT${projectId.value}", normalised.sort.name)
            .applyGroup("$WORKBENCH_GROUP${projectId.value}", normalised.groupBy)
            .apply()
        mutableState.update {
            it.copy(workbenchByProject = it.workbenchByProject + (projectId to normalised))
        }
    }

    fun saveBoardSort(projectId: ProjectId, sort: TaskSortKey) {
        require(projectId.value.isNotBlank())
        val normalised = sort.takeIf {
            it == TaskSortKey.PRIORITY || it == TaskSortKey.DUE || it == TaskSortKey.TITLE
        } ?: TaskSortKey.PRIORITY
        prefs.edit().putString("$BOARD_SORT${projectId.value}", normalised.name).apply()
        mutableState.update {
            it.copy(boardSortByProject = it.boardSortByProject + (projectId to normalised))
        }
    }

    private fun SharedPreferences.Editor.applyGroup(
        key: String,
        group: TaskGroupKey?,
    ): SharedPreferences.Editor = if (group == null) remove(key) else putString(key, group.name)
}
```

Complete `read()` in the same class by building all three maps from the one
`prefs.all` value. Invalid Tasks/workbench sort becomes DUE, invalid group
becomes null, and invalid board sort becomes PRIORITY. Accept non-empty
project-key suffixes as opaque project IDs. Keep deleted-project keys as
bounded garbage.

- [ ] **Step 4: Provide and expose state**

In `AppModule`, import the store and provide one process singleton:

```kotlin
import app.opentasks.ViewArrangementStore

@Provides
@Singleton
fun provideViewArrangementStore(
    @ApplicationContext context: Context,
): ViewArrangementStore = ViewArrangementStore(
    context.getSharedPreferences("view_prefs", Context.MODE_PRIVATE),
)
```

Inject it into `WorkspaceViewModel`; expose and delegate without a second state
copy or second normalisation pass:

```kotlin
val viewArrangement: StateFlow<ViewArrangementState> = viewArrangementStore.state

fun setTasksArrangement(value: TaskArrangement) =
    viewArrangementStore.saveTasks(value)

fun setWorkbenchArrangement(projectId: ProjectId, value: TaskArrangement) =
    viewArrangementStore.saveWorkbench(projectId, value)

fun setBoardSort(projectId: ProjectId, value: TaskSortKey) =
    viewArrangementStore.saveBoardSort(projectId, value)
```

Do not add arrangement state to `SavedStateHandle`: SharedPreferences is the
approved authority.

- [ ] **Step 5: Run focused verification**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "app.opentasks.ViewArrangementStateTest"
./gradlew :app:compileDebugAndroidTestKotlin :app:compileDebugKotlin
```

Expected: PASS; the instrumented suite is compiled, not run.

- [ ] **Step 6: Check the patch**

```bash
git diff --check
```

- [ ] **Step 7: Stage and audit exactly the store files**

```bash
git add app/src/main/kotlin/app/opentasks/ViewArrangementStore.kt \
  app/src/androidTest/kotlin/app/opentasks/ViewArrangementStoreInstrumentedTest.kt \
  app/src/main/kotlin/app/opentasks/di/AppModule.kt \
  app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt \
  app/src/test/kotlin/app/opentasks/ViewArrangementStateTest.kt
git diff --cached --name-only
```

Expected: exactly the five paths in this task's **Files** block.

- [ ] **Step 8: Commit**

```bash
git commit -m "feat: persist view arrangements"
```

---

### Task 4: Render and persist Tasks sort/group choices

**Files:**

- Modify:
  `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt:186-447`
  (`TasksScreen`) and `:452-570` (`TaskListPane` header/list boundary)
- Modify: `feature/tasks/src/main/res/values/strings.xml:1-40`
- Create:
  `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TasksArrangementInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt:255-278`
  (root collected state) and `:945-1136` (`entry<TasksRoute>`)

**Interfaces:**

- Consumes: Task 2's `TaskGroup`, `TaskSortKey`, `TaskGroupKey`, and
  `arrangeTasks`; Task 3's global Tasks arrangement state/setter; Task 1's
  bucket map.
- Produces these defaulted `TasksScreen` parameters, preserving all existing
  call sites:

```kotlin
taskGroups: List<TaskGroup> = listOf(TaskGroup(value = null, tasks = tasks)),
taskSort: TaskSortKey = TaskSortKey.DUE,
taskGroupBy: TaskGroupKey? = null,
onTaskSortChange: (TaskSortKey) -> Unit = {},
onTaskGroupChange: (TaskGroupKey?) -> Unit = {},
```

  The feature renders order only; `OpenTasksApp` owns projection and durable
  callbacks.

- [ ] **Step 1: Write the Tasks arrangement Compose RED tests**

Render deliberately pre-arranged groups whose order differs from the raw
`tasks` argument. Assert rows follow `taskGroups`, not snapshot order. Click
these exact controls and capture callbacks:

```text
tasks-sort-control
tasks-sort-option-due
tasks-sort-option-priority
tasks-sort-option-title
tasks-sort-option-updated
tasks-group-control
tasks-group-option-none
tasks-group-option-due_bucket
tasks-group-option-project
tasks-group-option-priority
```

Assert a Due header, Inbox header, named-project header, and priority header
are localised resource text; the unlabelled group renders no header. Then
select each existing date chip and prove filtering happens within every group,
empty groups disappear, and surviving group/task order remains unchanged.

Render one PROJECT group for `ProjectId("inbox")` beside the null-project
Inbox group, and give one task an id equal to an unprefixed header key. Assert
all headings and rows render without duplicate-key failure. Project and task
ids are opaque, so header/row key namespaces—not input parsing—must prevent
collisions.

Render non-default `taskSort = UPDATED` and `taskGroupBy = PROJECT`. Assert the
two controls announce the current resource-backed selection; open each menu
and assert the matching option has selected semantics. The controls are
stateless: only their supplied parameters determine these semantics.

- [ ] **Step 2: Run the RED compilation**

```bash
./gradlew :feature:tasks:compileDebugAndroidTestKotlin
```

Expected: compilation fails because the new screen parameters and controls do
not exist.

- [ ] **Step 3: Make grouped rendering replace snapshot rendering**

Keep raw `tasks` for task selection, detail editing, and bulk-selection lookup.
For the list pane, map each supplied group to a copy whose tasks pass the
current `TaskFilter`, discard empty groups, and render a resource-backed
heading followed by keyed rows. Do not flatten and re-sort. The PROJECT value
resolves `null` as Inbox and non-null through the existing `projectNames` map;
the fallback for a missing id is the resource-backed generic Project label.

Add two 48 dp menu controls in the list header. Sort offers DUE, PRIORITY,
TITLE, UPDATED; group offers null, DUE_BUCKET, PROJECT, PRIORITY. Controls emit
callbacks only and never remember their own selected value. Their content
descriptions are `tasks_sort_control` / `tasks_group_control` formatted with
the current option label, and every menu item sets `selected` semantics from
`taskSort` / `taskGroupBy`. Add and use these exact resource-backed strings:

```text
tasks_sort_control = Sort tasks: %1$s
tasks_group_control = Group tasks: %1$s
tasks_sort_due_label = Due
tasks_sort_priority_label = Priority
tasks_sort_title_label = Title
tasks_sort_updated_label = Updated
tasks_group_none_label = None
tasks_group_due_label = Due
tasks_group_project_label = Project
tasks_group_priority_label = Priority
```

Use this transformation before `TaskListPane`; it preserves supplied group and
task order and removes only empty groups:

```kotlin
val visibleTaskGroups = taskGroups.mapNotNull { group ->
    val visible = group.tasks.filter { task ->
        when (filter) {
            TaskFilter.INBOX -> task.projectId == null && task.deletedAt == null
            TaskFilter.TODAY -> !task.isCompleted && task.deletedAt == null &&
                dueBucketsByTaskId[task.id] == DueBucket.TODAY
            TaskFilter.UPCOMING -> !task.isCompleted && task.deletedAt == null &&
                dueBucketsByTaskId[task.id] in setOf(DueBucket.THIS_WEEK, DueBucket.LATER)
            TaskFilter.OVERDUE -> !task.isCompleted && task.deletedAt == null &&
                dueBucketsByTaskId[task.id] == DueBucket.OVERDUE
            TaskFilter.ALL -> task.deletedAt == null
        }
    }
    group.copy(tasks = visible).takeIf { visible.isNotEmpty() }
}
```

Pass `visibleTaskGroups` into `TaskListPane`; in its existing `LazyColumn`, use
one `item(key = "header:${group.value.stableKey()}")` for each non-null
semantic value followed by
`items(group.tasks, key = { "task:${it.id.value}" })`. Add these exhaustive
helpers; add the referenced resource names to this module's `strings.xml`:

```kotlin
private fun TaskGroupValue.stableKey(): String = when (this) {
    is TaskGroupValue.Due -> "due:${bucket.name}"
    is TaskGroupValue.Project -> projectId?.let { "project:id:${it.value}" }
        ?: "project:inbox"
    is TaskGroupValue.PriorityValue -> "priority:${priority.name}"
}

@Composable
private fun taskGroupLabel(
    value: TaskGroupValue,
    projectNames: Map<ProjectId, String>,
): String = when (value) {
    is TaskGroupValue.Due -> stringResource(
        when (value.bucket) {
            DueBucket.OVERDUE -> R.string.tasks_group_due_overdue
            DueBucket.TODAY -> R.string.tasks_group_due_today
            DueBucket.THIS_WEEK -> R.string.tasks_group_due_this_week
            DueBucket.LATER -> R.string.tasks_group_due_later
            DueBucket.NO_DATE -> R.string.tasks_group_due_no_date
        },
    )
    is TaskGroupValue.Project -> value.projectId?.let { projectId ->
        projectNames[projectId] ?: stringResource(R.string.tasks_group_project)
    } ?: stringResource(R.string.tasks_group_inbox)
    is TaskGroupValue.PriorityValue -> stringResource(
        when (value.priority) {
            Priority.URGENT -> R.string.tasks_group_priority_urgent
            Priority.HIGH -> R.string.tasks_group_priority_high
            Priority.MEDIUM -> R.string.tasks_group_priority_medium
            Priority.LOW -> R.string.tasks_group_priority_low
            Priority.NONE -> R.string.tasks_group_priority_none
        },
    )
}
```

- [ ] **Step 4: Project in `OpenTasksApp` and persist callbacks**

Collect `viewModel.viewArrangement`. Replace Task 1's standalone bucket-map
projection with one remembered projection that samples the injected clock
once. This keeps date-chip membership and due-group labels on the same instant
even if the live clock advances between recompositions; reuse the existing
`projectNames` map instead of rebuilding it:

```kotlin
val tasksArrangement = viewArrangement.tasks
val (dueBucketsByTaskId, taskGroups) = remember(
    snapshot.tasks,
    projectNames,
    tasksArrangement,
    clock,
) {
    val projectionClock = Clock.fixed(clock.instant(), clock.zone)
    snapshot.tasks.associate { task ->
        task.id to classifyDueBucket(task.due, projectionClock)
    } to arrangeTasks(
        tasks = snapshot.tasks,
        arrangement = tasksArrangement,
        projectNames = projectNames,
        clock = projectionClock,
    )
}
```

At the existing `TasksScreen` call add `taskGroups`,
`taskSort = tasksArrangement.sort`, and
`taskGroupBy = tasksArrangement.groupBy`. Each callback must copy from the
store's latest value at invocation, not the composition-captured arrangement,
so back-to-back sort/group events cannot overwrite each other:

```kotlin
onTaskSortChange = { sort ->
    viewModel.setTasksArrangement(
        viewModel.viewArrangement.value.tasks.copy(sort = sort),
    )
}
onTaskGroupChange = { groupBy ->
    viewModel.setTasksArrangement(
        viewModel.viewArrangement.value.tasks.copy(groupBy = groupBy),
    )
}
```

Leave every existing argument unchanged. Do not put this choice in
`rememberSaveable` or `SavedStateHandle`.

- [ ] **Step 5: Run focused verification**

```bash
./gradlew :feature:tasks:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin
```

Expected: PASS; no device test runs.

- [ ] **Step 6: Check the patch**

```bash
git diff --check
```

- [ ] **Step 7: Stage and audit exactly the Tasks arrangement files**

```bash
git add feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt \
  feature/tasks/src/main/res/values/strings.xml \
  feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TasksArrangementInstrumentedTest.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt
git diff --cached --name-only
```

Expected: exactly the four paths in this task's **Files** block.

- [ ] **Step 8: Commit**

```bash
git commit -m "feat: arrange the tasks list"
```

---

### Task 5: Render and persist project-workbench arrangements

**Files:**

- Modify:
  `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt:128-275`
  (`ProjectsScreen`) and `:369-884` (`ProjectWorkbench`)
- Modify: `feature/projects/src/main/res/values/strings.xml:1-19`
- Modify:
  `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/ProjectWorkbenchInstrumentedTest.kt:35-406`
  (`ProjectWorkbenchInstrumentedTest`)
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt:255-278`
  (root collected state) and `:1137-1260` (`entry<ProjectsRoute>`)

**Interfaces:**

- Consumes: `TaskGroup`, DUE/PRIORITY/TITLE/UPDATED sorts,
  DUE_BUCKET/PRIORITY groups, and Task 3's per-project workbench preference.
- Produces these defaulted `ProjectsScreen` parameters:

```kotlin
workbenchTaskGroups: List<TaskGroup> = emptyList(),
workbenchSort: TaskSortKey = TaskSortKey.DUE,
workbenchGroupBy: TaskGroupKey? = null,
onWorkbenchSortChange: (TaskSortKey) -> Unit = {},
onWorkbenchGroupChange: (TaskGroupKey?) -> Unit = {},
```

  An empty `workbenchTaskGroups` is a compatibility fallback that wraps the
  selected project's existing non-deleted tasks as one unlabelled group; it is
  not the production path.

- [ ] **Step 1: Add RED coverage to `ProjectWorkbenchInstrumentedTest`**

Supply two semantic groups in an order different from raw tasks and assert the
Tasks section renders that order. Exercise exact tags:

```text
workbench-sort-control
workbench-sort-option-due
workbench-sort-option-priority
workbench-sort-option-title
workbench-sort-option-updated
workbench-group-control
workbench-group-option-none
workbench-group-option-due_bucket
workbench-group-option-priority
```

Assert no Project grouping option exists. Assert the flat group has no heading,
Due/Priority groups use resource-backed headings, and switching to board mode
continues to hide exact `workbench-task-${task.id.value}` nodes while exact
`board-card-${task.id.value}` nodes appear.

Render non-default `workbenchSort = UPDATED` and `workbenchGroupBy = PRIORITY`.
Assert the controls announce those resource-backed current choices and the
matching open-menu options have selected semantics; menu-expanded state is the
only UI state the controls may remember.

Add a fixture where a task id equals a milestone id and another task id equals
an unprefixed group-header key. Also supply both `Project(null)` and
`Project(ProjectId("inbox"))` group values. Assert every milestone, group
heading, and task row renders: opaque ids require disjoint LazyColumn key
namespaces. Assert each non-flat group label has heading semantics.

- [ ] **Step 2: Run the RED compilation**

```bash
./gradlew :feature:projects:compileDebugAndroidTestKotlin
```

Expected: compilation fails on unresolved arrangement parameters/tags.

- [ ] **Step 3: Render the supplied groups without re-sorting**

Thread the new values through both single-pane and two-pane calls to the private
`ProjectWorkbench`. Put the two compact 48 dp controls beside the Tasks section
header, only in list mode. Render group headers and task rows in supplied order.
Keep raw `projectTasks` for counts, progress, milestones, and workflow totals.
Never expose PROJECT grouping in this surface.

The controls format `workbench_sort_control` / `workbench_group_control` with
the supplied current option, and each menu item sets `selected` semantics from
`workbenchSort` / `workbenchGroupBy`. Add and use these exact resources:

```text
workbench_sort_control = Sort project tasks: %1$s
workbench_group_control = Group project tasks: %1$s
workbench_sort_due_label = Due
workbench_sort_priority_label = Priority
workbench_sort_title_label = Title
workbench_sort_updated_label = Updated
workbench_group_none_label = None
workbench_group_due_label = Due
workbench_group_priority_label = Priority
```

Use one compatibility normalisation and feed the result straight to the
existing row loop:

```kotlin
val renderedGroups = workbenchTaskGroups.ifEmpty {
    listOf(TaskGroup(value = null, tasks = projectTasks))
}

renderedGroups.forEach { group ->
    group.value?.let { value ->
        item(key = "workbench:group:${value.stableKey()}") {
            Text(
                workbenchGroupLabel(value),
                modifier = Modifier.semantics { heading() },
            )
        }
    }
    items(group.tasks, key = { "workbench:task:${it.id.value}" }) { task ->
        ProjectTaskRow(task = task, onOpen = { onOpenTask(task.id) })
    }
}
```

Namespace the existing milestone rows as
`items(projectMilestones, key = { "workbench:milestone:${it.id.value}" })` and
tag the `ProjectTaskRow` root as `workbench-task-${task.id.value}`. Do not
validate or rewrite ids.

Add these exhaustive helpers and their referenced strings to
`feature/projects`:

```kotlin
private fun TaskGroupValue.stableKey(): String = when (this) {
    is TaskGroupValue.Due -> "due:${bucket.name}"
    is TaskGroupValue.Project -> projectId?.let { "project:id:${it.value}" }
        ?: "project:inbox"
    is TaskGroupValue.PriorityValue -> "priority:${priority.name}"
}

@Composable
private fun workbenchGroupLabel(value: TaskGroupValue): String = when (value) {
    is TaskGroupValue.Due -> stringResource(
        when (value.bucket) {
            DueBucket.OVERDUE -> R.string.workbench_group_due_overdue
            DueBucket.TODAY -> R.string.workbench_group_due_today
            DueBucket.THIS_WEEK -> R.string.workbench_group_due_this_week
            DueBucket.LATER -> R.string.workbench_group_due_later
            DueBucket.NO_DATE -> R.string.workbench_group_due_no_date
        },
    )
    is TaskGroupValue.Project -> stringResource(R.string.workbench_group_project)
    is TaskGroupValue.PriorityValue -> stringResource(
        when (value.priority) {
            Priority.URGENT -> R.string.workbench_group_priority_urgent
            Priority.HIGH -> R.string.workbench_group_priority_high
            Priority.MEDIUM -> R.string.workbench_group_priority_medium
            Priority.LOW -> R.string.workbench_group_priority_low
            Priority.NONE -> R.string.workbench_group_priority_none
        },
    )
}
```

The Project branch is fail-closed copy only: the menu never emits it and Task 3
normalises it away before state or disk.

- [ ] **Step 4: Wire per-project projection and persistence**

In `OpenTasksApp`, derive one remembered projection. Sample the injected clock
once so one group pass cannot straddle a date boundary, and reuse the existing
root `projectNames` map:

```kotlin
val selectedProject = snapshot.projects.firstOrNull { it.id == selectedProjectId }
val workbenchArrangement = selectedProject?.let {
    viewArrangement.workbenchFor(it.id)
} ?: TaskArrangement()
val workbenchTaskGroups = remember(
    snapshot.tasks,
    selectedProject,
    projectNames,
    workbenchArrangement,
    clock,
) {
    selectedProject?.let { project ->
        val projectionClock = Clock.fixed(clock.instant(), clock.zone)
        arrangeTasks(
            tasks = snapshot.tasks.filter {
                it.projectId == project.id && it.deletedAt == null
            },
            arrangement = workbenchArrangement,
            projectNames = projectNames,
            clock = projectionClock,
        )
    }.orEmpty()
}
```

At the existing `ProjectsScreen` call add `workbenchTaskGroups`,
`workbenchSort = workbenchArrangement.sort`, and
`workbenchGroupBy = workbenchArrangement.groupBy`. Add callbacks that no-op
when `selectedProject` is null and copy the store's latest per-project value at
invocation, so back-to-back sort/group events cannot overwrite each other:

```kotlin
onWorkbenchSortChange = { sort ->
    selectedProject?.let { project ->
        viewModel.setWorkbenchArrangement(
            project.id,
            viewModel.viewArrangement.value.workbenchFor(project.id).copy(sort = sort),
        )
    }
}
onWorkbenchGroupChange = { groupBy ->
    selectedProject?.let { project ->
        viewModel.setWorkbenchArrangement(
            project.id,
            viewModel.viewArrangement.value.workbenchFor(project.id).copy(groupBy = groupBy),
        )
    }
}
```

Leave all existing arguments unchanged.

- [ ] **Step 5: Run focused verification**

```bash
./gradlew :feature:projects:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin
```

Expected: PASS; no device test runs.

- [ ] **Step 6: Check the patch**

```bash
git diff --check
```

- [ ] **Step 7: Stage and audit exactly the workbench files**

```bash
git add feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt \
  feature/projects/src/main/res/values/strings.xml \
  feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/ProjectWorkbenchInstrumentedTest.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt
git diff --cached --name-only
```

Expected: exactly the four paths in this task's **Files** block.

- [ ] **Step 8: Commit**

```bash
git commit -m "feat: arrange project workbench tasks"
```

---

### Task 6: Move board projection to domain and add per-project sorting

**Files:**

- Modify:
  `feature/projects/src/main/kotlin/app/opentasks/feature/projects/BoardView.kt:79-125`
  (`BoardColumn`, `boardColumns`, and `moveTargets`) and `:127-304`
  (`BoardView`)
- Modify:
  `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt:128-275`
  (`ProjectsScreen`) and `:369-702` (`ProjectWorkbench` board header/branch)
- Modify: `feature/projects/src/main/res/values/strings.xml:1-19`
- Modify:
  `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/BoardViewInstrumentedTest.kt:28-154`
  (`BoardViewInstrumentedTest`)
- Modify:
  `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/ProjectWorkbenchInstrumentedTest.kt:35-406`
  (`ProjectWorkbenchInstrumentedTest`)
- Modify:
  `feature/projects/src/test/kotlin/app/opentasks/feature/projects/BoardColumnsTest.kt:19-114`
  (`BoardColumnsTest`)
- Modify:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/TaskArrangementRulesTest.kt:145-190`
  (`boardColumnsFilterCardsAndUseTheRequestedSharedComparator`)
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt:255-278`
  (root collected state) and `:1137-1260` (`entry<ProjectsRoute>`)

**Interfaces:**

- Consumes: Task 2's model `BoardColumn` and domain `boardColumns`; Task 3's
  `boardSortFor`/`setBoardSort`.
- Produces defaulted `ProjectsScreen` parameters:

```kotlin
selectedBoardColumns: List<BoardColumn> = emptyList(),
boardSort: TaskSortKey = TaskSortKey.PRIORITY,
onBoardSortChange: (TaskSortKey) -> Unit = {},
```

  `BoardView` continues to consume only `List<BoardColumn>` and callbacks; it
  has no comparator or repository dependency.

- [ ] **Step 1: Convert feature tests to plain model columns**

Remove calls to the feature-owned `boardColumns` from
`BoardViewInstrumentedTest` and construct `BoardColumn(status, tasks)` fixtures
directly. Preserve the existing tap-to-move, accessibility-action, and
long-press drag tests before production code moves. Use this complete fixture
helper in that test class:

```kotlin
private fun columnsFor(task: Task): List<BoardColumn> =
    OpenTasksFixtures.workflowStatuses
        .filter {
            it.projectId == OpenTasksFixtures.studioProject.id && it.archivedAt == null
        }
        .sortedBy(WorkflowStatus::rank)
        .map { status ->
            BoardColumn(
                status = status,
                tasks = listOf(task).filter { it.statusId == status.id },
            )
        }
```

In `BoardColumnsTest`, delete the projection-order test now owned by the domain
suite; import model `BoardColumn` and retain the `moveTargets` order/exclusion
test. Delete the orphaned project/task fixtures, helpers, and imports left by
that removal. Extend `TaskArrangementRulesTest` with DUE and UPDATED board-column
assertions as well as its existing PRIORITY/TITLE assertions. Choose primary
values whose expected order contradicts title order so an implementation that
ignores the requested sort fails; keep completed/deleted filtering pinned.

- [ ] **Step 2: Write the board-sort UI RED test**

In `ProjectWorkbenchInstrumentedTest`, enter board mode with deliberately
ordered `selectedBoardColumns`. Assert cards follow the supplied order. Open
`board-sort-control`, assert only Priority, Due, and Title choices exist, click
`board-sort-option-priority`, `board-sort-option-due`, and
`board-sort-option-title`, and assert the corresponding callback value.
Assert Updated and group controls are absent in board mode.

Update Task 5's existing list→board assertion to pass explicit model
`BoardColumn` fixtures; after the feature projection is removed, an omitted
`selectedBoardColumns` correctly means an empty board and cannot satisfy that
existing card assertion.

Supply non-default `boardSort = TITLE`; assert the control announces the
resource-backed current choice and the Title option has selected semantics.
Click Due, capture the callback, then reopen and prove the description and
selected option remain Title until the supplied parameter changes. This pins a
stateless control rather than a second arrangement authority.

- [ ] **Step 3: Run the RED compilation**

```bash
./gradlew :feature:projects:compileDebugAndroidTestKotlin
```

Expected: compilation fails because model `BoardColumn` and the new screen
contract have not replaced the feature declarations yet.

- [ ] **Step 4: Remove the feature projection and render supplied columns**

Delete `BoardColumn` and `boardColumns` from `BoardView.kt`; import only the
model type. Thread `selectedBoardColumns` through both workbench call paths and
pass it to `BoardView`. Add one stateless 48 dp board-sort menu beside the
List/Board toggle only when `boardMode` is true. It exposes PRIORITY, DUE, TITLE
only and emits `onBoardSortChange`. Its description formats the current value,
and every item sets `selected` semantics from `boardSort`. Derive option tags
with `candidate.name.lowercase(Locale.ROOT)`. Add the exact control string and
reuse Task 5's existing resource-backed workbench sort labels for the three
shared option names:

```text
board_sort_control = Sort board cards: %1$s
workbench_sort_priority_label = Priority
workbench_sort_due_label = Due
workbench_sort_title_label = Title
```

Replace the current feature projection call with the supplied value:

```kotlin
BoardView(
    columns = selectedBoardColumns,
    columnWidth = boardColumnWidth,
    onMoveTask = onChangeTaskStatus,
    onOpenTask = onOpenTask,
)
```

Build menu items only from
`listOf(TaskSortKey.PRIORITY, TaskSortKey.DUE, TaskSortKey.TITLE)`; each item
sets the menu closed and calls `onBoardSortChange(candidate)`.

- [ ] **Step 5: Project columns in the app**

Use the `selectedProject` already derived in Task 5:

```kotlin
val boardSort = selectedProject?.let { project ->
    viewArrangement.boardSortFor(project.id)
} ?: TaskSortKey.PRIORITY
val selectedBoardColumns = selectedProject?.let { project ->
    boardColumns(
        project = project,
        statuses = snapshot.workflowStatuses,
        tasks = snapshot.tasks,
        sort = boardSort,
    )
}.orEmpty()
```

At the existing `ProjectsScreen` call add `selectedBoardColumns`, `boardSort`,
and an `onBoardSortChange` callback that calls
`viewModel.setBoardSort(project.id, sort)` only when `selectedProject` is
non-null. Leave board/list mode state untouched and do not cache columns in the
feature.

- [ ] **Step 6: Run focused verification**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.TaskArrangementRulesTest"
./gradlew :feature:projects:testDebugUnitTest \
  --tests "app.opentasks.feature.projects.BoardColumnsTest"
./gradlew :feature:projects:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin
```

Expected: PASS; no device test runs and `:feature:projects` still has no
`:core:domain` dependency.

- [ ] **Step 7: Check the patch**

```bash
git diff --check
```

- [ ] **Step 8: Stage and audit exactly the board files**

```bash
git add feature/projects/src/main/kotlin/app/opentasks/feature/projects/BoardView.kt \
  feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt \
  feature/projects/src/main/res/values/strings.xml \
  feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/BoardViewInstrumentedTest.kt \
  feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/ProjectWorkbenchInstrumentedTest.kt \
  feature/projects/src/test/kotlin/app/opentasks/feature/projects/BoardColumnsTest.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/TaskArrangementRulesTest.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt
git diff --cached --name-only
```

Expected: exactly the eight paths in this task's **Files** block.

- [ ] **Step 9: Commit**

```bash
git commit -m "feat: sort board columns consistently"
```

---

### Task 7: Evolve saved-view payloads to strict versioned v2

**Files:**

- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt` —
  `SearchQuery`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/SavedViewPayloadCodec.kt` —
  `SavedViewPayloadCodec` and its payload DTOs
- Modify:
  `core/data/src/test/kotlin/app/opentasks/core/data/SavedViewPayloadCodecTest.kt` —
  `SavedViewPayloadCodecTest`
- Modify:
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemorySavedViewCommandTest.kt` —
  `InMemorySavedViewCommandTest`
- Modify:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomSavedViewCommandInstrumentedTest.kt` —
  `RoomSavedViewCommandInstrumentedTest`
- Modify:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/backup/BackupRecordImporterInstrumentedTest.kt` —
  `BackupRecordImporterInstrumentedTest`

**Interfaces:**

- Consumes: Task 1's `DueBucket`, Task 2's `TaskSortKey`, existing `Priority`
  and `SemanticStatus`, existing encrypted `saved_views` payload bytes.
- Produces the additive model contract:

```kotlin
data class SearchQuery(
    val text: String,
    val projectIds: Set<ProjectId> = emptySet(),
    val tagIds: Set<TagId> = emptySet(),
    val includeCompleted: Boolean = true,
    val includeTrash: Boolean = false,
    val dueBuckets: Set<DueBucket> = emptySet(),
    val priorities: Set<Priority> = emptySet(),
    val statuses: Set<SemanticStatus> = emptySet(),
    val sort: TaskSortKey? = null,
)
```

  and codec behaviour: strict v1/v2 decode, deterministic v2 encode, no row
  rewrite. Tasks 8–9 consume this exact query.

- [ ] **Step 1: Expand the codec RED matrix**

Add these helpers and tests to `SavedViewPayloadCodecTest`; the literals are
the fixtures, so the test does not depend on an encoder to manufacture decode
input:

```kotlin
private val v1 =
    """{"formatVersion":1,"text":"legacy","projectIds":[],"tagIds":[],"includeCompleted":true,"includeTrash":false}"""
private val v2 =
    """{"formatVersion":2,"text":"q","projectIds":["a"],"tagIds":["z"],"includeCompleted":false,"includeTrash":true,"dueBuckets":["LATER","TODAY"],"priorities":["HIGH","URGENT"],"statuses":["BACKLOG","STARTED"],"sort":"TITLE"}"""

private fun assertDecodeFails(json: String) = assertTrue(
    json,
    runCatching { SavedViewPayloadCodec.decode(json.encodeToByteArray()) }.isFailure,
)

private fun withoutField(source: String, key: String): String {
    val fields = Json.parseToJsonElement(source).jsonObject - key
    return JsonObject(fields).toString()
}

@Test fun v1DecodesWithV2DefaultsAndV2BytesAreCanonical() {
    assertEquals(SearchQuery("legacy"), SavedViewPayloadCodec.decode(v1.encodeToByteArray()))
    val query = SearchQuery(
        text = "q",
        projectIds = linkedSetOf(ProjectId("a")),
        tagIds = linkedSetOf(TagId("z")),
        includeCompleted = false,
        includeTrash = true,
        dueBuckets = linkedSetOf(DueBucket.TODAY, DueBucket.LATER),
        priorities = linkedSetOf(Priority.URGENT, Priority.HIGH),
        statuses = linkedSetOf(SemanticStatus.STARTED, SemanticStatus.BACKLOG),
        sort = TaskSortKey.TITLE,
    )
    assertEquals(query, SavedViewPayloadCodec.decode(v2.encodeToByteArray()))
    assertEquals(v2, SavedViewPayloadCodec.encode(query).decodeToString())
    assertEquals(
        """{"formatVersion":2,"text":"","projectIds":[],"tagIds":[],"includeCompleted":true,"includeTrash":false,"dueBuckets":[],"priorities":[],"statuses":[],"sort":null}""",
        SavedViewPayloadCodec.encode(SearchQuery("")).decodeToString(),
    )
}

@Test fun decodeIsVersionFirstAndEverySchemaFieldIsRequired() {
    listOf(
        "{}",
        v1.replace("\"formatVersion\":1,", ""),
        v1.replace("\"formatVersion\":1", "\"formatVersion\":\"1\""),
        v1.replace("\"formatVersion\":1", "\"formatVersion\":1.0"),
        v1.replace("\"formatVersion\":1", "\"formatVersion\":0"),
        v2.replace("\"formatVersion\":2", "\"formatVersion\":3"),
        v1.dropLast(1) + ",\"foreign\":true}",
        v2.dropLast(1) + ",\"foreign\":true}",
        v2.replace("[\"LATER\",\"TODAY\"]", "[\"UNKNOWN\"]"),
        v2.replace("[\"HIGH\",\"URGENT\"]", "[\"UNKNOWN\"]"),
        v2.replace("[\"BACKLOG\",\"STARTED\"]", "[\"UNKNOWN\"]"),
        v2.replace("\"TITLE\"", "\"UNKNOWN\""),
        "{",
    ).forEach(::assertDecodeFails)
    listOf(
        "formatVersion", "text", "projectIds", "tagIds",
        "includeCompleted", "includeTrash",
    ).forEach { assertDecodeFails(withoutField(v1, it)) }
    listOf(
        "formatVersion", "text", "projectIds", "tagIds", "includeCompleted",
        "includeTrash", "dueBuckets", "priorities", "statuses", "sort",
    ).forEach { assertDecodeFails(withoutField(v2, it)) }
}

@Test fun boundsAndUtf8FailClosed() {
    assertTrue(runCatching { SavedViewPayloadCodec.encode(SearchQuery("x".repeat(501))) }.isFailure)
    assertDecodeFails(v2.replace("\"q\"", "\"${"x".repeat(501)}\""))
    assertTrue(
        runCatching {
            SavedViewPayloadCodec.decode(ByteArray(SavedViewPayloadCodec.MAX_PAYLOAD_BYTES + 1))
        }.isFailure,
    )
    assertTrue(runCatching { SavedViewPayloadCodec.decode(byteArrayOf(0x7b, -1, 0x7d)) }.isFailure)
}
```

- [ ] **Step 2: Add repository/recovery RED coverage**

Add this test to `InMemorySavedViewCommandTest`:

```kotlin
@Test fun v2QueryUpdateUndoRestoresEveryField() = runBlocking {
    withTimeout(5_000) {
        val query = SearchQuery(
            text = "", dueBuckets = setOf(DueBucket.TODAY),
            priorities = setOf(Priority.URGENT),
            statuses = setOf(SemanticStatus.STARTED), sort = TaskSortKey.UPDATED,
        )
        val id = SavedViewId("saved-view-v2")
        repository.execute(DomainCommand.CreateSavedView(id, "V2", query))
        val update = repository.execute(
            DomainCommand.UpdateSavedViewQuery(id, SearchQuery("replacement")),
        ) as CommandResult.Success
        repository.execute(checkNotNull(update.undo))
        assertEquals(query, repository.currentWorkspace().savedViews.single { it.id == id }.query)
    }
}
```

Add the restart equivalent to `RoomSavedViewCommandInstrumentedTest`:

```kotlin
@Test fun v2QuerySurvivesUpdateUndoAndEncryptedRestart() = runBlocking {
    withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
        val query = SearchQuery(
            text = "", dueBuckets = setOf(DueBucket.TODAY),
            priorities = setOf(Priority.URGENT),
            statuses = setOf(SemanticStatus.STARTED), sort = TaskSortKey.UPDATED,
        )
        val id = SavedViewId("saved-view-v2")
        repository!!.execute(DomainCommand.CreateSavedView(id, "V2", query))
        val update = repository!!.execute(
            DomainCommand.UpdateSavedViewQuery(id, SearchQuery("replacement")),
        ) as CommandResult.Success
        repository!!.execute(checkNotNull(update.undo))
        repository!!.close()
        database!!.close()
        repository = null
        database = VaultDatabase.create(context, databaseName, databaseKey)
        repository = RoomVaultRepository(
            database = database!!,
            deviceId = DeviceId("saved-view-instrumented-test-device"),
        )
        assertEquals(query, awaitSavedView { it.id == id }.query)
    }
}
```

Add this importer test using its existing `completeVaultSnapshot()`, `request`,
`staging()`, and `TIMEOUT_MILLIS` helpers. It proves recovery visibility and
byte-for-byte v1/v2/v3 row retention without changing the backup codec:

```kotlin
@Test fun recoveryImportsV1V2AndRetainsFutureSavedViewBytes() = runBlocking {
    withTimeout(TIMEOUT_MILLIS) {
        val v1Bytes =
            """{"formatVersion":1,"text":"legacy","projectIds":[],"tagIds":[],"includeCompleted":true,"includeTrash":false}""".encodeToByteArray()
        val v2Json =
            """{"formatVersion":2,"text":"","projectIds":[],"tagIds":[],"includeCompleted":true,"includeTrash":false,"dueBuckets":["TODAY"],"priorities":[],"statuses":[],"sort":null}"""
        val v2Bytes = v2Json.encodeToByteArray()
        val v3Bytes = v2Json.replace("\"formatVersion\":2", "\"formatVersion\":3")
            .encodeToByteArray()
        val records = completeVaultSnapshot().records
            .filterNot { it.family == BackupRecordFamily.SAVED_VIEW } +
            listOf(
                SavedViewEntity("view-v1", WORKSPACE_ID, "V1", v1Bytes).toBackupRecordV1(),
                SavedViewEntity("view-v2", WORKSPACE_ID, "V2", v2Bytes).toBackupRecordV1(),
                SavedViewEntity("view-v3", WORKSPACE_ID, "V3", v3Bytes).toBackupRecordV1(),
            )
        importer.importInto(staging(), request(completeVaultSnapshot().copy(records = records)))
        val room = RoomVaultRepository(staging(), DeviceId("recovery-codec-test"))
        try {
            assertEquals(
                listOf("view-v1", "view-v2"),
                room.currentWorkspace().savedViews.map { it.id.value }.sorted(),
            )
            val recaptured = RoomBackupCaptureSource(staging(), VaultId(VAULT_ID)).capture()
            listOf("view-v1", "view-v2", "view-v3").forEach { id ->
                assertEquals(
                    records.single {
                        it.family == BackupRecordFamily.SAVED_VIEW && it.identity == listOf(id)
                    },
                    recaptured.records.single {
                        it.family == BackupRecordFamily.SAVED_VIEW && it.identity == listOf(id)
                    },
                )
            }
        } finally {
            room.close()
        }
    }
}
```

- [ ] **Step 3: Run the RED checks**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "app.opentasks.core.data.SavedViewPayloadCodecTest" \
  --tests "app.opentasks.core.data.InMemorySavedViewCommandTest"
./gradlew :core:data:compileDebugAndroidTestKotlin
```

Expected: model/codec tests fail because v2 fields and version routing do not
exist.

- [ ] **Step 4: Implement `SearchQuery` and version-first decoding**

Append the four defaulted query fields in the interface order shown above.
Replace the codec's single DTO and decode branch with this exact shape; retain
the existing byte ceiling, strict UTF-8 decoder, and 500-character validator:

```kotlin
private fun decodeText(text: String): SearchQuery {
    val primitive = json.parseToJsonElement(text).jsonObject["formatVersion"]
        as? JsonPrimitive ?: error("Missing formatVersion")
    require(!primitive.isString) { "formatVersion must be an integer" }
    val version = primitive.intOrNull ?: error("formatVersion must be an integer")
    return when (version) {
        1 -> json.decodeFromString<SavedViewPayloadV1>(text).toModel()
        2 -> json.decodeFromString<SavedViewPayloadV2>(text).toModel()
        else -> error("Unsupported saved view format $version")
    }.also(::validate)
}

@Serializable
private data class SavedViewPayloadV1(
    val formatVersion: Int,
    val text: String,
    val projectIds: List<String>,
    val tagIds: List<String>,
    val includeCompleted: Boolean,
    val includeTrash: Boolean,
) {
    fun toModel() = SearchQuery(
        text, projectIds.mapTo(linkedSetOf(), ::ProjectId),
        tagIds.mapTo(linkedSetOf(), ::TagId), includeCompleted, includeTrash,
    )
}

@Serializable
private data class SavedViewPayloadV2(
    val formatVersion: Int,
    val text: String,
    val projectIds: List<String>,
    val tagIds: List<String>,
    val includeCompleted: Boolean,
    val includeTrash: Boolean,
    val dueBuckets: List<DueBucket>,
    val priorities: List<Priority>,
    val statuses: List<SemanticStatus>,
    val sort: TaskSortKey?,
) {
    fun toModel() = SearchQuery(
        text = text,
        projectIds = projectIds.mapTo(linkedSetOf(), ::ProjectId),
        tagIds = tagIds.mapTo(linkedSetOf(), ::TagId),
        includeCompleted = includeCompleted,
        includeTrash = includeTrash,
        dueBuckets = dueBuckets.toSet(),
        priorities = priorities.toSet(),
        statuses = statuses.toSet(),
        sort = sort,
    )

    companion object {
        fun from(query: SearchQuery) = SavedViewPayloadV2(
            formatVersion = 2,
            text = query.text,
            projectIds = query.projectIds.map(ProjectId::value).sorted(),
            tagIds = query.tagIds.map(TagId::value).sorted(),
            includeCompleted = query.includeCompleted,
            includeTrash = query.includeTrash,
            dueBuckets = query.dueBuckets.sortedBy { it.name },
            priorities = query.priorities.sortedBy { it.name },
            statuses = query.statuses.sortedBy { it.name },
            sort = query.sort,
        )
    }
}
```

`decode(payload)` calls `decodeText(text)`. `encode(query)` calls
`json.encodeToString(SavedViewPayloadV2.from(query))`. Keep
`ignoreUnknownKeys = false`, `encodeDefaults = true`, and
`explicitNulls = true`; never rewrite a decoded row.

- [ ] **Step 5: Run focused verification**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "app.opentasks.core.data.SavedViewPayloadCodecTest" \
  --tests "app.opentasks.core.data.InMemorySavedViewCommandTest"
./gradlew :core:data:compileDebugAndroidTestKotlin
scripts/check-schema-drift.sh
git diff --check
```

Expected: JVM tests, Android-test compilation, and schema drift pass.

- [ ] **Step 6: Audit the staged paths**

```bash
git add core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/SavedViewPayloadCodec.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/SavedViewPayloadCodecTest.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/InMemorySavedViewCommandTest.kt \
  core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomSavedViewCommandInstrumentedTest.kt \
  core/data/src/androidTest/kotlin/app/opentasks/core/data/backup/BackupRecordImporterInstrumentedTest.kt
git diff --cached --name-only
```

Expected: exactly the six paths in this task are staged; no schema or fixture
file is staged.

- [ ] **Step 7: Commit**

```bash
git commit -m "feat: version saved view filters"
```

---

### Task 8: Hoist filtering and ranked search into `core:domain`

**Files:**

- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/WorkspaceSearch.kt`
- Create:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/WorkspaceSearchTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt` —
  constructor and `search`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt` —
  `search` and companion `MAX_SEARCH_RESULTS`
- Modify:
  `core/data/src/test/kotlin/app/opentasks/core/data/SearchExtensionTest.kt` —
  `SearchExtensionTest`
- Modify:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt` —
  `RoomVaultRepositoryInstrumentedTest`

**Interfaces:**

- Consumes: Task 7's `SearchQuery`, Task 1's classifier, Task 2's comparator,
  existing `SearchNormalizer`, and complete `WorkspaceSnapshot` relations.
- Produces:

```kotlin
const val MAX_WORKSPACE_SEARCH_RESULTS: Int = 50

fun searchWorkspace(
    snapshot: WorkspaceSnapshot,
    query: SearchQuery,
    clock: Clock,
): List<SearchResult>
```

  Both repositories become thin adapters. Task 9 relies on blank v2 queries
  reaching this function.

- [ ] **Step 1: Write title-tier, type, boundary, and cap RED tests**

Create `WorkspaceSearchTest` with the following fixed fixture helpers and first
test. `task`/`project` are ordinary `copy` calls, so every required model field
comes from `OpenTasksFixtures`:

```kotlin
private val zone = ZoneId.of("Asia/Bangkok")
private val now = Instant.parse("2026-08-10T03:00:00Z")
private val clock = Clock.fixed(now, zone)
private fun task(id: String, title: String, priority: Priority = Priority.NONE) =
    OpenTasksFixtures.tasks.first().copy(
        id = TaskId(id), title = title, description = "", priority = priority,
        checklist = emptyList(), tagIds = emptySet(), deletedAt = null,
        completedAt = null, semanticStatus = SemanticStatus.PLANNED,
    )
private fun project(id: String, name: String, summary: String = "") =
    OpenTasksFixtures.studioProject.copy(
        id = ProjectId(id), name = name, summary = summary, archivedAt = null,
    )
private fun snapshot(tasks: List<Task>, projects: List<Project>) =
    OpenTasksFixtures.snapshot.copy(
        tasks = tasks, projects = projects, tags = emptyList(), notes = emptyList(),
        attachments = emptyList(),
    )
private fun SearchResult.id() = when (this) {
    is SearchResult.TaskResult -> task.id.value
    is SearchResult.ProjectResult -> project.id.value
}

@Test fun titleTierPrecedesTypeAndUnicodeWordBoundaryPrecedesSubstring() {
    val input = snapshot(
        tasks = listOf(
            task("task-prefix", "alpha beta"),
            task("task-word", "go-alpha"),
            task("task-unicode-substring", "βalpha"),
            task("task-substring", "go2alpha"),
            task("task-exact", "ALPHA"),
        ),
        projects = listOf(project("project-exact", "alpha")),
    )
    assertEquals(
        listOf(
            "task-exact", "project-exact", "task-prefix", "task-word",
            "task-substring", "task-unicode-substring",
        ),
        searchWorkspace(input, SearchQuery("alpha"), clock).map { it.id() },
    )
    assertEquals(
        searchWorkspace(input, SearchQuery("alpha"), clock).map { it.id() },
        searchWorkspace(
            input.copy(tasks = input.tasks.reversed(), projects = input.projects.reversed()),
            SearchQuery("alpha"), clock,
        ).map { it.id() },
    )
}

@Test fun rankThenCapKeepsTheExactProject() {
    val tasks = (0..50).map { task("substring-%02d".format(it), "xalpha-$it") }
    val results = searchWorkspace(
        snapshot(tasks, listOf(project("exact-project", "alpha"))),
        SearchQuery("alpha"), clock,
    )
    assertEquals(50, results.size)
    assertEquals("exact-project", results.first().id())
}
```

- [ ] **Step 2: Add wider-haystack, filter, blank-view, and sort RED tests**

Add one deterministic relation fixture and the conflict-resolution assertions:

```kotlin
@Test fun widerHaystackAndEveryTaskFilterComposeWhileProjectsRemainEligible() {
    val due = ZonedMoment(now.plusSeconds(3_600), zone.id)
    val keep = task("keep", "zzz", Priority.URGENT).copy(
        projectId = ProjectId("p"), due = due, tagIds = setOf(TagId("tag")),
        semanticStatus = SemanticStatus.STARTED,
        description = "alpha",
    )
    val input = snapshot(
        tasks = listOf(
            keep,
            keep.copy(id = TaskId("wrong-priority"), priority = Priority.LOW),
            keep.copy(id = TaskId("wrong-project"), projectId = ProjectId("other")),
            keep.copy(id = TaskId("wrong-tag"), tagIds = emptySet()),
            keep.copy(
                id = TaskId("wrong-completed"),
                semanticStatus = SemanticStatus.COMPLETED,
                completedAt = now,
            ),
            keep.copy(id = TaskId("wrong-trash"), deletedAt = now),
            keep.copy(
                id = TaskId("wrong-due"),
                due = ZonedMoment(now.plusSeconds(86_400), zone.id),
            ),
            keep.copy(id = TaskId("wrong-status"), semanticStatus = SemanticStatus.BLOCKED),
        ),
        projects = listOf(project("project", "alpha")),
    ).copy(tags = listOf(Tag(TagId("tag"), OpenTasksFixtures.workspaceId, "Focus")))
    val query = SearchQuery(
        text = "alpha",
        projectIds = setOf(ProjectId("p")),
        tagIds = setOf(TagId("tag")),
        includeCompleted = false,
        dueBuckets = setOf(DueBucket.TODAY),
        priorities = setOf(Priority.URGENT),
        statuses = setOf(SemanticStatus.STARTED, SemanticStatus.COMPLETED),
    )
    assertEquals(listOf("project", "keep"), searchWorkspace(input, query, clock).map { it.id() })
    assertTrue(searchWorkspace(input, SearchQuery(""), clock).isEmpty())
    assertEquals(
        listOf("keep"),
        searchWorkspace(input, query.copy(text = ""), clock).map { it.id() },
    )
}

@Test fun textSortRanksAndCapsBeforeSortingSurvivingTasksAheadOfProjects() {
    val prefix = task("prefix", "alpha beta", Priority.LOW)
    val substringA = task("substring-a", "xalpha", Priority.URGENT)
    val substringB = task("substring-b", "yalpha", Priority.HIGH)
    val input = snapshot(
        listOf(prefix, substringA, substringB),
        listOf(project("exact-project", "alpha")),
    )
    assertEquals(
        listOf("substring-a", "substring-b", "prefix", "exact-project"),
        searchWorkspace(input, SearchQuery("alpha", sort = TaskSortKey.PRIORITY), clock)
            .map { it.id() },
    )
    assertEquals(
        listOf("substring-a", "substring-b", "prefix"),
        searchWorkspace(input, SearchQuery("", sort = TaskSortKey.PRIORITY), clock)
            .map { it.id() },
    )

    val capInput = snapshot(
        tasks = (0 until 50).map { index ->
            task("cap-prefix-%02d".format(index), "alpha cap $index", Priority.LOW)
        } + task("cap-urgent", "xalpha", Priority.URGENT),
        projects = emptyList(),
    )
    val capped = searchWorkspace(
        capInput,
        SearchQuery("alpha", sort = TaskSortKey.PRIORITY),
        clock,
    ).map { it.id() }
    assertEquals(50, capped.size)
    assertFalse(capped.contains("cap-urgent"))
}
```

Also add focused tasks whose only match is in checklist, a task note, a sorted
tag name, a non-deleted attachment display name, and project summary/note. Use
the existing `Note`/`Attachment` constructors from `SearchExtensionTest`; assert
all five land in `SUBSTRING` after any title-tier result, and reversing each
input list leaves ordered ids unchanged.

- [ ] **Step 3: Run the RED unit test**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.WorkspaceSearchTest"
```

Expected: compilation fails because `searchWorkspace` is absent.

- [ ] **Step 4: Implement one deterministic search pipeline**

Implement these exact private types/helpers, then build task/project candidates
from the snapshot and apply them in the shown order:

```kotlin
const val MAX_WORKSPACE_SEARCH_RESULTS: Int = 50
private enum class SearchTier { EXACT, PREFIX, WORD_BOUNDARY, SUBSTRING }
private data class RankedResult(val result: SearchResult, val tier: SearchTier)

private fun titleTier(title: String, needle: String): SearchTier? = when {
    title == needle -> SearchTier.EXACT
    title.startsWith(needle) -> SearchTier.PREFIX
    title.indices.any { index ->
        (index == 0 || !title[index - 1].isLetterOrDigit()) &&
            title.startsWith(needle, index)
    } -> SearchTier.WORD_BOUNDARY
    needle in title -> SearchTier.SUBSTRING
    else -> null
}

private fun SearchQuery.hasV2Criterion(): Boolean =
    dueBuckets.isNotEmpty() || priorities.isNotEmpty() ||
        statuses.isNotEmpty() || sort != null

private fun taskPasses(task: Task, query: SearchQuery, clock: Clock): Boolean =
    (query.includeTrash || task.deletedAt == null) &&
        (query.includeCompleted || !task.isCompleted) &&
        (query.projectIds.isEmpty() || task.projectId in query.projectIds) &&
        (query.tagIds.isEmpty() || task.tagIds.any(query.tagIds::contains)) &&
        (query.dueBuckets.isEmpty() || classifyDueBucket(task.due, clock) in query.dueBuckets) &&
        (query.priorities.isEmpty() || task.priority in query.priorities) &&
        (query.statuses.isEmpty() || task.semanticStatus in query.statuses)
```

Build candidates with this deterministic haystack code before the ranking
block. The sort keys eliminate all snapshot/set insertion-order leakage:

```kotlin
val projectNames = snapshot.projects.associate { it.id to it.name }
val tagNames = snapshot.tags.associate { it.id to it.name }
val notesByTask = snapshot.notes.filter { it.taskId != null }.groupBy { it.taskId }
    .mapValues { (_, values) -> values.sortedWith(compareBy<Note> { it.createdAt }.thenBy { it.id.value }) }
val notesByProject = snapshot.notes.filter { it.projectId != null }.groupBy { it.projectId }
    .mapValues { (_, values) -> values.sortedWith(compareBy<Note> { it.createdAt }.thenBy { it.id.value }) }
val attachmentsByTask = snapshot.attachments.filter { it.deletedAt == null }.groupBy { it.taskId }
    .mapValues { (_, values) ->
        values.sortedWith(
            compareBy<Attachment> { SearchNormalizer.normalize(it.displayName) }
                .thenBy { it.id.value },
        )
    }
fun taskHaystack(task: Task): String = SearchNormalizer.normalize(
    listOf(
        task.title,
        task.description,
        projectNames[task.projectId].orEmpty(),
        task.checklist.sortedWith(compareBy<ChecklistItem> { it.rank }.thenBy { it.id })
            .joinToString(" ", transform = ChecklistItem::text),
        task.tagIds.mapNotNull { id -> tagNames[id]?.let { id to it } }
            .sortedWith(
                compareBy<Pair<TagId, String>> { SearchNormalizer.normalize(it.second) }
                    .thenBy { it.first.value },
            ).joinToString(" ") { it.second },
        notesByTask[task.id].orEmpty().joinToString(" ") { it.body },
        attachmentsByTask[task.id].orEmpty().joinToString(" ") { it.displayName },
    ).joinToString(" "),
)
fun projectHaystack(project: Project): String = SearchNormalizer.normalize(
    listOf(
        project.name,
        project.summary,
        notesByProject[project.id].orEmpty().joinToString(" ") { it.body },
    ).joinToString(" "),
)
val candidates = buildList {
    snapshot.tasks.filter { taskPasses(it, query, clock) }.forEach { task ->
        val title = SearchNormalizer.normalize(task.title)
        val tier = titleTier(title, needle)
            ?: SearchTier.SUBSTRING.takeIf { needle in taskHaystack(task) }
        if (tier != null) add(
            RankedResult(
                SearchResult.TaskResult(task, projectNames[task.projectId] ?: "Inbox"),
                tier,
            ),
        )
    }
    snapshot.projects.filter { it.archivedAt == null }.forEach { project ->
        val title = SearchNormalizer.normalize(project.name)
        val tier = titleTier(title, needle)
            ?: SearchTier.SUBSTRING.takeIf { needle in projectHaystack(project) }
        if (tier != null) add(RankedResult(SearchResult.ProjectResult(project, "Project"), tier))
    }
}
```

```kotlin
val ranked = candidates.sortedWith(
    compareBy<RankedResult> { it.tier.ordinal }
        .thenBy { if (it.result is SearchResult.TaskResult) 0 else 1 }
        .thenBy { SearchNormalizer.normalize(it.result.title) }
        .thenBy {
            when (val result = it.result) {
                is SearchResult.TaskResult -> result.task.id.value
                is SearchResult.ProjectResult -> result.project.id.value
            }
        },
)
val survivors = ranked.take(MAX_WORKSPACE_SEARCH_RESULTS)
if (query.sort == null) return survivors.map(RankedResult::result)
val tasks = survivors.mapNotNull { it.result as? SearchResult.TaskResult }
    .sortedWith { first, second ->
        taskComparator(query.sort).compare(first.task, second.task)
    }
val projects = survivors.mapNotNull { it.result as? SearchResult.ProjectResult }
return tasks + projects
```

For a blank needle, return empty unless `query.hasV2Criterion()`. Otherwise
filter tasks only, sort with `taskComparator(query.sort ?: TaskSortKey.TITLE)`,
map to `TaskResult`, and then take 50. For nonblank text, a title tier wins;
only when it is null may a wider-haystack match assign `SUBSTRING`.

- [ ] **Step 5: Replace both repository implementations**

Append `zoneId` to the in-memory constructor, retain Room's existing provider,
and replace both search bodies with these adapters:

```kotlin
// InMemoryVaultRepository constructor tail
private val zoneId: () -> ZoneId = ZoneId::systemDefault,

override suspend fun search(query: SearchQuery): List<SearchResult> =
    searchWorkspace(mutableWorkspace.value, query, Clock.fixed(now(), zoneId()))

// RoomVaultRepository
override suspend fun search(query: SearchQuery): List<SearchResult> {
    ready.await()
    return searchWorkspace(mutableWorkspace.value, query, Clock.fixed(now(), zoneId()))
}
```

Delete both old algorithms and Room's `MAX_SEARCH_RESULTS`.

- [ ] **Step 6: Pin adapter parity**

Keep every existing haystack assertion and add this in-memory test:

```kotlin
@Test fun blankDueBucketSearchUsesTheInjectedClock() = runBlocking {
    val instant = Instant.parse("2026-08-10T03:00:00Z")
    val zone = ZoneId.of("Asia/Bangkok")
    val due = ZonedMoment(instant.plusSeconds(3_600), zone.id)
    val local = InMemoryVaultRepository(
        now = { instant },
        zoneId = { zone },
    )
    local.execute(DomainCommand.CreateTask("Today adapter", due = due))
    assertEquals(
        listOf("Today adapter"),
        local.search(SearchQuery("", dueBuckets = setOf(DueBucket.TODAY)))
            .map(SearchResult::title),
    )
}
```

Add the Room equivalent and extend its existing helper exactly as follows. Do
not run the instrumented test before Task 18; compile it in Step 7.

```kotlin
@Test fun blankDueBucketSearchUsesTheInjectedClock() = runBlocking {
    withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
        val instant = Instant.parse("2026-08-10T03:00:00Z")
        val zone = ZoneId.of("Asia/Bangkok")
        openRepository(now = { instant }, zoneId = { zone })
        repository!!.execute(
            DomainCommand.CreateTask(
                "Today adapter",
                due = ZonedMoment(instant.plusSeconds(3_600), zone.id),
            ),
        )
        assertEquals(
            listOf("Today adapter"),
            repository!!.search(SearchQuery("", dueBuckets = setOf(DueBucket.TODAY)))
                .map(SearchResult::title),
        )
    }
}

private fun openRepository(
    now: () -> Instant = Instant::now,
    zoneId: () -> ZoneId = ZoneId::systemDefault,
    appendBoundary: BackupJournalAppendBoundary = BackupJournalAppendBoundary { dao, entity ->
        dao.insert(entity)
    },
) {
    database = VaultDatabase.create(context, databaseName, databaseKey)
    repository = RoomVaultRepository(
        database = database!!,
        deviceId = DeviceId("instrumented-test-device"),
        now = now,
        zoneId = zoneId,
        backupJournalAppendBoundary = appendBoundary,
    )
}
```

- [ ] **Step 7: Run focused verification**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.WorkspaceSearchTest"
./gradlew :core:data:testDebugUnitTest \
  --tests "app.opentasks.core.data.SearchExtensionTest"
./gradlew :core:data:compileDebugAndroidTestKotlin
git diff --check
```

Expected: pure/domain and in-memory tests pass and Room tests compile.

- [ ] **Step 8: Audit the staged paths**

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain/WorkspaceSearch.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/WorkspaceSearchTest.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/SearchExtensionTest.kt \
  core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt
git diff --cached --name-only
```

Expected: exactly the six paths in this task are staged and each repository
contains only the thin adapter above.

- [ ] **Step 9: Commit**

```bash
git commit -m "feat: rank workspace search centrally"
```

---

### Task 9: Add saved-view v2 controls and identity-safe refinement

**Files:**

- Modify: `app/src/main/kotlin/app/opentasks/SearchSurface.kt` —
  `SearchSurface`, `SavedViewChip`, and new private `SearchQuerySaver`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt` — `search`
- Modify: `app/src/main/res/values/strings.xml` — saved-search string block
- Modify:
  `app/src/androidTest/kotlin/app/opentasks/SearchSurfaceSavedViewsInstrumentedTest.kt` —
  `SearchSurfaceSavedViewsInstrumentedTest`
- Modify:
  `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt` —
  `ProcessRestorationInstrumentedTest.searchQueryRestoresAndReissuesTheQueryAfterSavedInstanceStateRecreation`

**Interfaces:**

- Consumes: the full Task 7 `SearchQuery`; Task 8's repository behaviour;
  saved-view identity and existing CRUD callbacks.
- Produces a `SearchSurface` whose saveable source of truth is the complete
  query plus `selectedSavedViewId`, with four compact controls for due bucket,
  priority, semantic status, and sort. Its public function signature stays
  unchanged. Test tags are exact and locale-stable:

```kotlin
private const val ACTIVE_VIEW_TAG = "active-saved-view" // suffix with "-<saved-view id>"
private const val CLEAR_ACTIVE_VIEW_TAG = "clear-active-saved-view"
private const val DUE_FILTER_TAG = "search-filter-due"
private const val PRIORITY_FILTER_TAG = "search-filter-priority"
private const val STATUS_FILTER_TAG = "search-filter-status"
private const val SORT_FILTER_TAG = "search-sort"
// Options: search-due-<DueBucket.name.lowercase(Locale.ROOT)>
// search-priority-<Priority.name.lowercase(Locale.ROOT)>
// search-status-<SemanticStatus.name.lowercase(Locale.ROOT)>
// search-sort-relevance or search-sort-<TaskSortKey.name.lowercase(Locale.ROOT)>
```

- [ ] **Step 1: Write identity/refinement RED tests**

Add this identity test; use the existing `focusView.copy` fixture:

```kotlin
@Test fun textRefinementKeepsFiltersUntilExplicitClear() {
    val saved = focusView.copy(
        query = SearchQuery(
            text = "deep", dueBuckets = setOf(DueBucket.TODAY),
            priorities = setOf(Priority.URGENT),
            statuses = setOf(SemanticStatus.STARTED), sort = TaskSortKey.UPDATED,
        ),
    )
    val emitted = AtomicReference<SearchQuery?>()
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
        OpenTasksTheme {
            SearchSurface(emptyList(), emitted::set, {}, {}, {}, listOf(saved))
        }
    }
    composeRule.onNodeWithTag("saved-view-chip-${saved.id.value}").performClick()
    composeRule.onNodeWithTag("workspace-search-query").performTextReplacement("refined")
    composeRule.mainClock.advanceTimeBy(151)
    composeRule.waitForIdle()
    assertEquals(saved.query.copy(text = "refined"), emitted.get())
    composeRule.onNodeWithTag("active-saved-view-${saved.id.value}").assertIsDisplayed()
    composeRule.onNodeWithTag("clear-active-saved-view").performClick()
    composeRule.mainClock.advanceTimeBy(151)
    composeRule.waitForIdle()
    assertEquals(SearchQuery("refined"), emitted.get())
}
```

For rename identity, select a blank-text v2 view so its existing chip menu
remains visible, rename it through the existing menu/dialog tags, and assert
`active-saved-view-<id>` displays the new name; the id must not change.

- [ ] **Step 2: Write filter-control and restoration RED tests**

Use these exact tags in the existing Compose suite:

```text
search-filter-due
search-filter-priority
search-filter-status
search-sort
search-due-<bucket lowercase>
search-priority-<priority lowercase>
search-status-<semantic status lowercase>
search-sort-relevance
search-sort-<sort key lowercase>
```

Assert multi-select toggles due/priority/status sets, sort is single-select with
Relevance mapping to null, and no project/workflow-id values are introduced.
With blank text plus one filter, assert results/no-results UI replaces the
search hint and the Save action is enabled. Save and capture the exact query.
In `ProcessRestorationInstrumentedTest`, restore query text, every selected set,
sort, and active saved-view id from Compose saved state.

```kotlin
val restored = SearchQuery(
    text = "restored", dueBuckets = setOf(DueBucket.LATER),
    priorities = setOf(Priority.HIGH),
    statuses = setOf(SemanticStatus.BLOCKED), sort = TaskSortKey.TITLE,
)
val view = SavedView(
    id = SavedViewId("restored-filter-view"),
    workspaceId = OpenTasksFixtures.workspaceId,
    name = "Restored filters",
    query = restored,
)
// Select saved-view-chip-<id>, call emulateSavedInstanceStateRestore(), then:
composeRule.waitUntil(2_000) { latestQuery.get() == restored }
composeRule.onNodeWithTag("active-saved-view-${view.id.value}").assertIsDisplayed()
```

- [ ] **Step 3: Run the RED compilation**

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: tests fail to compile or find the new controls/identity behaviour.

- [ ] **Step 4: Replace text-only state with a Bundle-safe query saver**

Add this exact Bundle-safe saver; unknown enum names are dropped and unknown
sort names map to relevance (`null`):

```kotlin
private val SearchQuerySaver = listSaver<SearchQuery, Any>(
    save = { query ->
        listOf(
            query.text,
            ArrayList(query.projectIds.map(ProjectId::value).sorted()),
            ArrayList(query.tagIds.map(TagId::value).sorted()),
            query.includeCompleted,
            query.includeTrash,
            ArrayList(query.dueBuckets.map { it.name }.sorted()),
            ArrayList(query.priorities.map { it.name }.sorted()),
            ArrayList(query.statuses.map { it.name }.sorted()),
            query.sort?.name.orEmpty(),
        )
    },
    restore = { values ->
        fun names(index: Int) = (values[index] as? List<*>)
            .orEmpty().mapNotNull { it as? String }
        SearchQuery(
            text = values[0] as String,
            projectIds = names(1).mapTo(linkedSetOf(), ::ProjectId),
            tagIds = names(2).mapTo(linkedSetOf(), ::TagId),
            includeCompleted = values[3] as Boolean,
            includeTrash = values[4] as Boolean,
            dueBuckets = names(5).mapNotNull { name ->
                DueBucket.entries.firstOrNull { it.name == name }
            }.toSet(),
            priorities = names(6).mapNotNull { name ->
                Priority.entries.firstOrNull { it.name == name }
            }.toSet(),
            statuses = names(7).mapNotNull { name ->
                SemanticStatus.entries.firstOrNull { it.name == name }
            }.toSet(),
            sort = (values[8] as? String)?.let { name ->
                TaskSortKey.entries.firstOrNull { it.name == name }
            },
        )
    },
)

var query by rememberSaveable(stateSaver = SearchQuerySaver) {
    mutableStateOf(SearchQuery(""))
}
var selectedSavedViewId by rememberSaveable { mutableStateOf<String?>(null) }
```

Wire state changes exactly:

```kotlin
onTextChange = { value -> query = query.copy(text = value.take(MAX_SEARCH_QUERY_LENGTH)) }
onSavedViewSelect = { view -> query = view.query; selectedSavedViewId = view.id.value }
onClearActiveView = { selectedSavedViewId = null; query = SearchQuery(query.text) }
```

- [ ] **Step 5: Add resource-backed controls and active indicator**

Render the four 48 dp controls below the search field. Each multi-select menu
toggles one enum in an immutable set; the sort menu writes nullable
`TaskSortKey`. Show `Active view: <name>` whenever the selected id still exists,
regardless of typed refinement. Saved-view chips remain available for blank
text. Define a local `hasV2Criterion` with the same four fields as Task 8;
enable Save and results for nonblank text or a v2 criterion, and show the hint
only when both are absent. Keep existing save/rename/delete/Undo flows.

```kotlin
fun <T> Set<T>.toggled(value: T): Set<T> =
    if (value in this) this - value else this + value
val hasV2Criterion = query.dueBuckets.isNotEmpty() || query.priorities.isNotEmpty() ||
    query.statuses.isNotEmpty() || query.sort != null
val activeView = savedViews.firstOrNull { it.id.value == selectedSavedViewId }
// Menu callbacks:
query = query.copy(dueBuckets = query.dueBuckets.toggled(bucket))
query = query.copy(priorities = query.priorities.toggled(priority))
query = query.copy(statuses = query.statuses.toggled(status))
query = query.copy(sort = selectedSort) // null is Relevance
```

Add resource keys `saved_search_filter_due`, `saved_search_filter_priority`,
`saved_search_filter_status`, `saved_search_sort`, `saved_search_relevance`,
`saved_search_active_view`, and `saved_search_clear_active_view`; enum option
labels use existing resource copy where available and new resource entries
otherwise.

- [ ] **Step 6: Let blank v2 queries reach the repository**

Replace `WorkspaceViewModel.search` with:

```kotlin
fun search(query: SearchQuery) {
    viewModelScope.launch { mutableSearchResults.value = repository.search(query) }
}
```

Keep `clearSearch()` unchanged.

- [ ] **Step 7: Run focused verification**

```bash
./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
git diff --check
```

Expected: app host tests pass and instrumented sources compile; refinement
retains filters/id and blank filter-only views dispatch.

- [ ] **Step 8: Audit the staged paths**

```bash
git add app/src/main/kotlin/app/opentasks/SearchSurface.kt \
  app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt \
  app/src/main/res/values/strings.xml \
  app/src/androidTest/kotlin/app/opentasks/SearchSurfaceSavedViewsInstrumentedTest.kt \
  app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt
git diff --cached --name-only
```

Expected: exactly the five paths in this task are staged.

- [ ] **Step 9: Commit**

```bash
git commit -m "feat: refine saved view filters"
```

---

### Task 10: Parse all Quick Add grammar in one pure rule

**Files:**

- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/QuickAddParser.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/NaturalDateParser.kt` —
  `parseNaturalDate` and new internal `parseNaturalDates`
- Create:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/QuickAddParserTest.kt`
- Modify:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/NaturalDateParserTest.kt` —
  `NaturalDateParserTest`

**Interfaces:**

- Consumes: existing `parseNaturalDate` grammar, active projects, tags,
  `RecurrenceRule`, `Priority`, `Duration`, and explicit now/zone values.
- Produces:

```kotlin
enum class QuickAddTokenKind {
    PROJECT, TAG, PRIORITY, DATE, RECURRENCE, ESTIMATE
}

sealed interface QuickAddTokenValue {
    data class ProjectValue(
        val projectId: ProjectId,
        val projectName: String,
    ) : QuickAddTokenValue

    data class TagValue(
        val name: String,
        val existingTagId: TagId?,
    ) : QuickAddTokenValue

    data class PriorityValue(val priority: Priority) : QuickAddTokenValue
    data class DueValue(val due: ZonedMoment) : QuickAddTokenValue
    data class RecurrenceValue(
        val rule: RecurrenceRule,
        val due: ZonedMoment,
    ) : QuickAddTokenValue
    data class EstimateValue(val duration: Duration) : QuickAddTokenValue
}

data class QuickAddTokenMatch(
    val startIndex: Int,
    val endIndex: Int,
    val value: QuickAddTokenValue,
) {
    val kind: QuickAddTokenKind
        get() = when (value) {
            is QuickAddTokenValue.ProjectValue -> QuickAddTokenKind.PROJECT
            is QuickAddTokenValue.TagValue -> QuickAddTokenKind.TAG
            is QuickAddTokenValue.PriorityValue -> QuickAddTokenKind.PRIORITY
            is QuickAddTokenValue.DueValue -> QuickAddTokenKind.DATE
            is QuickAddTokenValue.RecurrenceValue -> QuickAddTokenKind.RECURRENCE
            is QuickAddTokenValue.EstimateValue -> QuickAddTokenKind.ESTIMATE
        }
}

fun parseQuickAdd(
    text: String,
    now: Instant,
    zone: ZoneId,
    projects: List<Project>,
    tags: List<Tag>,
): List<QuickAddTokenMatch>

fun stripQuickAddToken(text: String, match: QuickAddTokenMatch): String
```

  The match derives its kind exhaustively from the sealed value. Existing
  `parseNaturalDate(text, now, zone)` remains source- and behaviour-compatible.
  Tasks 11–12 consume the parser contract unchanged.

- [ ] **Step 1: Write grammar torture RED tests**

Use a fixed Bangkok instant and table-driven host tests:

```kotlin
private val zone = ZoneId.of("Asia/Bangkok")
private val now = ZonedDateTime.of(2026, 8, 10, 10, 0, 0, 0, zone).toInstant()
private val projects = listOf(
    OpenTasksFixtures.studioProject.copy(id = ProjectId("short"), name = "Studio"),
    OpenTasksFixtures.studioProject.copy(id = ProjectId("long"), name = "Studio refresh"),
    OpenTasksFixtures.taxProject.copy(archivedAt = now),
)
private val tags = listOf(Tag(TagId("focus"), OpenTasksFixtures.workspaceId, "Focus"))
private fun tokens(text: String, at: Instant = now) =
    parseQuickAdd(text, at, zone, projects, tags)

@Test fun sigilsRequireAWordStartAndResolveDeterministically() {
    val parsed = tokens("Plan: #stu @FOCUS @new !1 ~1h30m")
    assertEquals(
        listOf(PROJECT, TAG, TAG, PRIORITY, ESTIMATE),
        parsed.map(QuickAddTokenMatch::kind),
    )
    assertEquals("short", (parsed[0].value as ProjectValue).projectId.value)
    assertEquals(TagId("focus"), (parsed[1].value as TagValue).existingTagId)
    assertNull((parsed[2].value as TagValue).existingTagId)
    assertEquals(Priority.URGENT, (parsed[3].value as PriorityValue).priority)
    assertEquals(Duration.ofMinutes(90), (parsed[4].value as EstimateValue).duration)
    assertTrue(tokens("mail@host abc#studio wow!high").isEmpty())
}

@Test fun priorityAndEstimateAliasesRespectBounds() {
    mapOf(
        "!low" to Priority.LOW, "!med" to Priority.MEDIUM,
        "!medium" to Priority.MEDIUM, "!high" to Priority.HIGH,
        "!urgent" to Priority.URGENT, "!1" to Priority.URGENT,
        "!2" to Priority.HIGH, "!3" to Priority.MEDIUM, "!4" to Priority.LOW,
    ).forEach { (text, expected) ->
        assertEquals(expected, (tokens(text).single().value as PriorityValue).priority)
    }
    listOf("!0", "!5", "!unknown", "~0", "~-1m", "~24h1m", "~999999999999h")
        .forEach { assertTrue(it, tokens(it).isEmpty()) }
    assertEquals(Duration.ofHours(24), (tokens("~24h").single().value as EstimateValue).duration)
}

@Test fun recurrenceClaimsBeforeDatesAndUsesRightmostExplicitDate() {
    val parsed = tokens("@friday every monday tomorrow then friday")
    assertEquals(listOf(TAG, RECURRENCE, DATE, DATE), parsed.map(QuickAddTokenMatch::kind))
    val recurrence = parsed.first { it.kind == RECURRENCE }.value as RecurrenceValue
    val rightmost = parsed.last { it.kind == DATE }.value as DueValue
    assertEquals(rightmost.due, recurrence.due)
    assertEquals(setOf(DayOfWeek.MONDAY), recurrence.rule.weekdays)
    assertEquals(parsed.sortedWith(compareBy(QuickAddTokenMatch::startIndex).thenBy { it.endIndex }), parsed)
}

@Test fun recurrenceAnchorsAtTheNextMatchingFivePm() {
    val morning = (tokens("every monday").single().value as RecurrenceValue).due
    val eveningNow = ZonedDateTime.of(2026, 8, 10, 20, 0, 0, 0, zone).toInstant()
    val evening = (tokens("every monday", eveningNow).single().value as RecurrenceValue).due
    assertEquals(LocalDate.of(2026, 8, 10), morning.instant.atZone(zone).toLocalDate())
    assertEquals(LocalDate.of(2026, 8, 17), evening.instant.atZone(zone).toLocalDate())
    assertEquals(LocalTime.of(17, 0), morning.instant.atZone(zone).toLocalTime())
}

@Test fun stripRemovesOnlyTheClaimedHalfOpenSpan() {
    val text = "  Plan   !high   tomorrow  "
    assertEquals("Plan tomorrow", stripQuickAddToken(text, tokens(text).first()))
}
```

Add tables for `~30m`, `~2h`, bare `~45`; every day/week/month/year; every 2
units; every full/short weekday; interval 999/1000; and Turkish default locale.
Each table uses `tokens` above and asserts the exact `Duration` or
`RecurrenceRule`. Keep every existing `NaturalDateParserTest` and add:

```kotlin
@Test fun publicParserStillReturnsTheRightmostDate() {
    val match = parseNaturalDate("today then tomorrow", now, zone)!!
    assertEquals("tomorrow", "today then tomorrow".substring(match.startIndex, match.endIndex))
}
```

- [ ] **Step 2: Run the RED unit tests**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.NaturalDateParserTest" \
  --tests "app.opentasks.core.domain.QuickAddParserTest"
```

Expected: `QuickAddParserTest` fails to compile.

- [ ] **Step 3: Generalise natural-date matching without changing grammar**

Extract the existing loop to:

```kotlin
internal fun parseNaturalDates(
    text: String,
    now: Instant,
    zone: ZoneId,
): List<NaturalDateMatch>
```

Keep the regex and resolver untouched. Implement the public function as
`parseNaturalDates(text, now, zone).lastOrNull()`.

- [ ] **Step 4: Implement token claiming and resolution**

Use only Kotlin `Regex`, `java.time`, and `SearchNormalizer`. Pin precedence and
overlap with these helpers:

```kotlin
private val SIGIL = Regex("""(?<![\p{L}\p{Nd}])([#@!~])(\S+)""")
private val RECURRENCE = Regex(
    """(?i)(?<![\p{L}\p{Nd}])every\s+(?:(\d+)\s+)?""" +
        """(day|days|week|weeks|month|months|year|years|""" +
        """monday|mon|tuesday|tue|wednesday|wed|thursday|thu|""" +
        """friday|fri|saturday|sat|sunday|sun)(?![\p{L}\p{Nd}])""",
)
private fun IntRange.overlaps(start: Int, end: Int): Boolean =
    first < end && start <= last
private fun claimed(start: Int, end: Int, spans: List<IntRange>): Boolean =
    spans.any { it.overlaps(start, end) }

private fun resolveProject(needle: String, projects: List<Project>): Project? {
    val normalisedNeedle = SearchNormalizer.normalize(needle)
    return projects.asSequence()
        .filter { it.archivedAt == null }
        .mapNotNull { project ->
            val name = SearchNormalizer.normalize(project.name)
            val tier = when {
                name.startsWith(normalisedNeedle) -> 0
                normalisedNeedle in name -> 1
                else -> return@mapNotNull null
            }
            Triple(project, tier, name)
        }
        .sortedWith(
            compareBy<Triple<Project, Int, String>> { it.second }
                .thenBy { it.first.name.length }
                .thenBy { it.third }
                .thenBy { it.first.id.value },
        )
        .firstOrNull()?.first
}

private fun resolveTag(needle: String, tags: List<Tag>): QuickAddTokenValue.TagValue {
    val existing = tags.firstOrNull {
        SearchNormalizer.normalize(it.name) == SearchNormalizer.normalize(needle)
    }
    return QuickAddTokenValue.TagValue(existing?.name ?: needle, existing?.id)
}
```

Resolve valid sigils and reserve their half-open spans first. Resolve recurrence
matches not overlapping those spans and reserve them second. Then call
`parseNaturalDates` and retain only dates not overlapping either set. The
rightmost surviving date supplies every recurrence token's explicit due;
otherwise use:

```kotlin
private fun implicitRecurrenceDue(
    weekday: DayOfWeek?,
    now: Instant,
    zone: ZoneId,
): ZonedMoment = try {
    val current = now.atZone(zone)
    var date = if (weekday == null) {
        current.toLocalDate()
    } else {
        current.toLocalDate().with(TemporalAdjusters.nextOrSame(weekday))
    }
    var candidate = date.atTime(17, 0).atZone(zone)
    if (!candidate.toInstant().isAfter(now)) {
        date = if (weekday == null) date.plusDays(1) else date.plusWeeks(1)
        candidate = date.atTime(17, 0).atZone(zone)
    }
    ZonedMoment(candidate.toInstant(), zone.id)
} catch (failure: DateTimeException) {
    throw IllegalArgumentException("Recurrence anchor is out of range", failure)
}
```

Map priority aliases exactly as in the RED table. Parse estimates to total
minutes with `toLongOrNull`, accept `1..1_440`, and use `Duration.ofMinutes`.
Accept recurrence intervals `1..999`; named weekdays always use
`RecurrenceFrequency.WEEKLY`, interval 1, and a singleton weekday set. Catch
arithmetic/date failures per candidate and omit only that token. Return all
matches sorted by `startIndex`, then `endIndex`.

```kotlin
fun stripQuickAddToken(text: String, match: QuickAddTokenMatch): String {
    require(match.startIndex in 0..text.length)
    require(match.endIndex in match.startIndex..text.length)
    return (text.substring(0, match.startIndex) + text.substring(match.endIndex))
        .replace(Regex("""\s+"""), " ")
        .trim()
}
```

- [ ] **Step 5: Run focused verification**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.NaturalDateParserTest" \
  --tests "app.opentasks.core.domain.QuickAddParserTest"
git diff --check
```

Expected: natural-date and Quick Add parser host tests pass.

- [ ] **Step 6: Audit the staged paths**

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain/QuickAddParser.kt \
  core/domain/src/main/kotlin/app/opentasks/core/domain/NaturalDateParser.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/QuickAddParserTest.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/NaturalDateParserTest.kt
git diff --cached --name-only
```

Expected: exactly the four paths in this task are staged.

- [ ] **Step 7: Commit**

```bash
git commit -m "feat: parse quick add grammar"
```

---

### Task 11: Widen `CreateTask` atomically in both engines

**Files:**

- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt` —
  `DomainCommand.CreateTask` and `RejectionReason`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt` —
  `createTask` and companion limits
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt` —
  `createTask` and companion limits
- Modify:
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt` —
  `InMemoryVaultRepositoryTest`
- Modify:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt` —
  `RoomVaultRepositoryInstrumentedTest`

**Interfaces:**

- Consumes: existing create-task command, tag name dedupe, Task 10 values,
  `RecurringTaskPlanner.metadataForUpdate`, and existing transaction/journal
  machinery.
- Produces:

```kotlin
data class CreateTask(
    val title: String,
    val projectId: ProjectId? = null,
    val priority: Priority = Priority.NONE,
    val due: ZonedMoment? = null,
    val tagNames: List<String> = emptyList(),
    val estimate: Duration? = null,
    val recurrence: RecurrenceRule? = null,
) : DomainCommand
```

  plus `RejectionReason.RECURRENCE_REQUIRES_DUE`. Existing positional calls
  retain their first four fields. Success still returns `DeleteTask(newId,
  createdAt)`.

- [ ] **Step 1: Write identical behavioural RED cases in both suites**

Add this exact matrix helper to both test classes. The first two entries pin the
previously missing blank and 241-character title checks:

```kotlin
private fun invalidCreates(due: ZonedMoment): List<Pair<DomainCommand.CreateTask, RejectionReason>> =
    listOf(
        DomainCommand.CreateTask("   ") to RejectionReason.EMPTY_TITLE,
        DomainCommand.CreateTask("x".repeat(241)) to RejectionReason.TITLE_TOO_LONG,
        DomainCommand.CreateTask("Task", tagNames = listOf("   ")) to RejectionReason.EMPTY_TAG_NAME,
        DomainCommand.CreateTask("Task", tagNames = listOf("x".repeat(65))) to
            RejectionReason.TAG_NAME_TOO_LONG,
        DomainCommand.CreateTask("Task", tagNames = List(51) { "tag-$it" }) to
            RejectionReason.TAG_LIMIT_REACHED,
        DomainCommand.CreateTask("Task", estimate = Duration.ZERO) to
            RejectionReason.INVALID_STATE,
        DomainCommand.CreateTask("Task", estimate = Duration.ofMinutes(-1)) to
            RejectionReason.INVALID_STATE,
        DomainCommand.CreateTask(
            "Task", recurrence = RecurrenceRule(RecurrenceFrequency.DAILY),
        ) to RejectionReason.RECURRENCE_REQUIRES_DUE,
        DomainCommand.CreateTask(
            "Task", due = due,
            recurrence = RecurrenceRule(RecurrenceFrequency.DAILY, interval = 1_000),
        ) to RejectionReason.INVALID_STATE,
        DomainCommand.CreateTask(
            "Task", due = due,
            recurrence = RecurrenceRule(
                RecurrenceFrequency.DAILY,
                count = 2,
                endDate = LocalDate.of(2026, 8, 12),
            ),
        ) to RejectionReason.INVALID_STATE,
        DomainCommand.CreateTask(
            "Task", due = due,
            recurrence = RecurrenceRule(
                RecurrenceFrequency.DAILY,
                endDate = LocalDate.of(2026, 8, 9),
            ),
        ) to RejectionReason.INVALID_STATE,
    )
```

Add the in-memory rejection test; it proves every rejection leaves both
workspace and journal unchanged:

```kotlin
@Test fun createTaskRejectsTheWholeCommandBeforeMutation() = runBlocking {
    withTimeout(5_000) {
        val due = ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), "UTC")
        invalidCreates(due).forEach { (command, reason) ->
            val before = repository.currentWorkspace()
            val journalSize = journal.entries.size
            val result = repository.execute(command) as CommandResult.Rejected
            assertEquals(reason, result.reason)
            assertEquals(before, repository.currentWorkspace())
            assertEquals(journalSize, journal.entries.size)
        }
    }
}
```

Add the Room equivalent; unchanged generation proves no backup-journal write:

```kotlin
@Test fun createTaskRejectsTheWholeCommandBeforeRoomMutation() = runBlocking {
    withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
        openRepository(now = { Instant.parse("2026-08-10T09:00:00Z") })
        val due = ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), "UTC")
        invalidCreates(due).forEach { (command, reason) ->
            val before = repository!!.currentWorkspace()
            val generation = database!!.backupStateDao().require("vault-primary").currentGeneration
            val result = repository!!.execute(command) as CommandResult.Rejected
            assertEquals(reason, result.reason)
            assertEquals(before, repository!!.currentWorkspace())
            assertEquals(
                generation,
                database!!.backupStateDao().require("vault-primary").currentGeneration,
            )
        }
    }
}
```

In each suite, declare `fixedNow = Instant.parse("2026-07-26T10:00:00Z")`
(the in-memory suite's repository already supplies that instant; open Room with
`now = { fixedNow }`) and first prove that the limit is applied after
trim/case-fold dedupe:

```kotlin
val fixedNow = Instant.parse("2026-07-26T10:00:00Z")
assertTrue(
    repository.execute(
        DomainCommand.CreateTask(
            "Deduped tags",
            tagNames = List(51) { " deep WORK " },
        ),
    ) is CommandResult.Success,
)
```

Keep the in-memory direct reads shown below. In Room, replace the prerequisite
assertion with a bounded observation before taking `before`, so all independently
fed task/tag/relation/activity state is present:

```kotlin
assertTrue(
    repository!!.execute(
        DomainCommand.CreateTask(
            "Deduped tags",
            tagNames = List(51) { " deep WORK " },
        ),
    ) is CommandResult.Success,
)
val before = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
    repository!!.observeWorkspace().filterNotNull().first { snapshot ->
        val task = snapshot.tasks.singleOrNull { it.title == "Deduped tags" }
            ?: return@first false
        task.tagIds == setOf(TagId("tag-deep-work")) &&
            snapshot.activityEntries.any {
                it.taskId == task.id && it.kind == ActivityKind.RECORD_CREATED
            }
    }
}
```

In the following common block, omit Room's direct `val before =
repository.currentWorkspace()` line and use this observed `before` instead.

Then execute:

```kotlin
// This is 2026-08-09 in America/Los_Angeles but 2026-08-10 in UTC.
val due = ZonedMoment(Instant.parse("2026-08-10T00:30:00Z"), "America/Los_Angeles")
val rule = RecurrenceRule(
    RecurrenceFrequency.WEEKLY,
    endDate = LocalDate.of(2026, 8, 9),
)
val command = DomainCommand.CreateTask(
    title = "Enriched",
    tagNames = listOf("deep WORK", "New tag", "NEW TAG"),
    estimate = Duration.ofMinutes(45),
    due = due,
    recurrence = rule,
)
val before = repository.currentWorkspace()
val beforeActivityIds = before.activityEntries.mapTo(hashSetOf(), ActivityEntry::id)
val success = repository.execute(command) as CommandResult.Success
val after = repository.currentWorkspace()
val created = after.tasks.single { task -> before.tasks.none { it.id == task.id } }
assertEquals(before.tags.size + 1, after.tags.size)
val newTag = after.tags.single { it.id !in before.tags.map { tag -> tag.id }.toSet() }
assertEquals("New tag", newTag.name)
assertEquals(setOf(TagId("tag-deep-work"), newTag.id), created.tagIds)
assertEquals(Duration.ofMinutes(45), created.estimate)
assertEquals(rule, created.recurrence)
assertEquals(created.id, created.recurrenceSeriesId)
assertEquals(due, created.recurrenceAnchor)
assertEquals(0, created.recurrenceOccurrenceIndex)
val newActivities = after.activityEntries.filter { it.id !in beforeActivityIds }
assertEquals(1, newActivities.size)
assertEquals(ActivityKind.RECORD_CREATED, newActivities.single().kind)
assertEquals(created.id, newActivities.single().taskId)
assertEquals(DomainCommand.DeleteTask(created.id, fixedNow), success.undo)
repository.execute(checkNotNull(success.undo))
assertTrue(repository.currentWorkspace().tasks.single { it.id == created.id }.deletedAt != null)
```

Use `repository!!` in the Room class. Replace the common successful execute
line with the following, which captures `generationBefore` immediately before
success and asserts the resulting generation is exactly `generationBefore + 1`.
Decode its rows and assert exactly five upserts:
TASK x1, TAG x1, TASK_TAG x2, and ACTIVITY_ENTRY x1. Assert
`rows.map { it.sequence } == rows.indices.toList()` and that every decoded
mutation has a non-null `record` and no `deletedFamily`; do not merely assert
family containment:

```kotlin
val generationBefore = database!!.backupStateDao().require("vault-primary").currentGeneration
val success = repository!!.execute(command) as CommandResult.Success
val generationAfter = database!!.backupStateDao().require("vault-primary").currentGeneration
val rows = journalRows(generationAfter)
val mutations = rows.map { BackupMutationCodec.decode(it.payload) }
assertEquals(generationBefore + 1, generationAfter)
assertEquals(rows.indices.toList(), rows.map { it.sequence })
assertEquals(5, mutations.size)
assertTrue(mutations.all { it.record != null && it.deletedFamily == null })
assertEquals(1, mutations.count { it.record!!.family == BackupRecordFamily.TASK })
assertEquals(1, mutations.count { it.record!!.family == BackupRecordFamily.TAG })
assertEquals(2, mutations.count { it.record!!.family == BackupRecordFamily.TASK_TAG })
assertEquals(1, mutations.count { it.record!!.family == BackupRecordFamily.ACTIVITY_ENTRY })
```

Room's repository feed is asynchronous: replace the success `after` read with
a bounded observation, and use the same form after reopening and after Undo:

```kotlin
val after = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
    repository!!.observeWorkspace().filterNotNull().first { snapshot ->
        val created = snapshot.tasks.singleOrNull {
            it.title == "Enriched" && it.recurrenceAnchor == due
        } ?: return@first false
        created.tagIds.size == 2 &&
            TagId("tag-deep-work") in created.tagIds &&
            snapshot.tags.size == before.tags.size + 1 &&
            snapshot.tags.any { it.id in created.tagIds && it.name == "New tag" } &&
            snapshot.activityEntries.count {
                it.taskId == created.id && it.kind == ActivityKind.RECORD_CREATED
            } == 1
    }
}
// Close/reopen, then use another bounded observeWorkspace().filterNotNull().first { ... }
// read to reassert the created fields before Undo.
repository!!.execute(checkNotNull(success.undo))
val undone = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
    repository!!.observeWorkspace().filterNotNull().first { snapshot ->
        snapshot.tasks.single { it.id == created.id }.deletedAt == fixedNow
    }
}
assertEquals(fixedNow, undone.tasks.single { it.id == created.id }.deletedAt)
```

Assert the five decoded-record family counts exactly, not a set of contained families.
Add `ActivityKind`, `TagId`, and `ActivityEntry` imports where those assertions
need them.

- [ ] **Step 2: Run the RED tests**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "app.opentasks.core.data.InMemoryVaultRepositoryTest"
./gradlew :core:data:compileDebugAndroidTestKotlin
```

Expected: new constructor fields/rejection reason are unresolved or ignored.

- [ ] **Step 3: Add command fields and complete preflight**

Append the command fields and rejection enum exactly as specified in
Interfaces. In memory, capture `val current = mutableWorkspace.value` before
the project/Backlog preflight and use that same snapshot throughout the method;
do not reread `mutableWorkspace.value`. In both `createTask` functions, run
this validation block after the existing active-project/Backlog lookup and
before `now()`, `TaskId.new()`, or any `UUID.randomUUID()` call:

```kotlin
val title = command.title.trim()
when {
    title.isEmpty() -> return CommandResult.Rejected(
        RejectionReason.EMPTY_TITLE, "A task needs a title.",
    )
    title.length > MAX_TASK_TITLE_LENGTH -> return CommandResult.Rejected(
        RejectionReason.TITLE_TOO_LONG,
        "Keep the task title under $MAX_TASK_TITLE_LENGTH characters.",
    )
}
val uniqueTagNames = linkedMapOf<String, String>()
for (rawName in command.tagNames) {
    val name = rawName.trim()
    validateTagName(name)?.let { return it }
    uniqueTagNames.putIfAbsent(name.lowercase(Locale.ROOT), name)
}
if (uniqueTagNames.size > MAX_TASK_TAGS) return CommandResult.Rejected(
    RejectionReason.TAG_LIMIT_REACHED,
    "A task can contain up to $MAX_TASK_TAGS tags.",
)
val recurrence = command.recurrence
val dueLocalDate = command.due?.let { due ->
    due.instant.atZone(ZoneId.of(due.zoneId)).toLocalDate()
}
when {
    command.estimate?.let { it.isZero || it.isNegative } == true ->
        return CommandResult.Rejected(RejectionReason.INVALID_STATE, "Estimate must be greater than zero.")
    recurrence != null && command.due == null ->
        return CommandResult.Rejected(
            RejectionReason.RECURRENCE_REQUIRES_DUE,
            "Add a due date before repeating this task.",
        )
    recurrence != null && recurrence.interval > MAX_RECURRENCE_INTERVAL ->
        return CommandResult.Rejected(RejectionReason.INVALID_STATE, "Repeat interval is too large.")
    recurrence?.count != null && recurrence.endDate != null ->
        return CommandResult.Rejected(
            RejectionReason.INVALID_STATE, "Choose either an occurrence count or an end date.",
        )
    recurrence?.endDate?.let { endDate ->
        dueLocalDate != null && endDate.isBefore(dueLocalDate)
    } == true ->
        return CommandResult.Rejected(
            RejectionReason.INVALID_STATE, "The repeat end date cannot be before the due date.",
        )
}
```

Resolve case-insensitive existing tags now, but collect missing names as
strings only. In memory, use the already captured workspace:

```kotlin
val tagsByName = current.tags.associateBy { tag -> tag.name.lowercase(Locale.ROOT) }
val existingTags = uniqueTagNames.keys.mapNotNull(tagsByName::get)
val missingTagNames = uniqueTagNames
    .filterKeys { key -> key !in tagsByName }
    .values
    .toList()
```

In Room, finish all DAO reads before allocating an id:

```kotlin
val existingTags = mutableListOf<Tag>()
val missingTagNames = mutableListOf<String>()
for (name in uniqueTagNames.values) {
    val existing = database.workspaceDao()
        .findTagByName(OpenTasksFixtures.workspaceId.value, name)
        ?.toModel()
    if (existing == null) missingTagNames += name else existingTags += existing
}
```

Only after every branch above and every lookup succeeds may code allocate
identifiers:

```kotlin
// No TaskId.new/UUID.randomUUID call is permitted above this line.
val createdAt = now()
val taskId = TaskId.new()
val freshTags = missingTagNames.map { name ->
    Tag(TagId(UUID.randomUUID().toString()), OpenTasksFixtures.workspaceId, name)
}
```

Add `MAX_RECURRENCE_INTERVAL = 999` beside the existing task/tag limits in both
companions. Do not recursively call `execute`, `CreateAndAssignTag`, or
`SetTaskTag`.

- [ ] **Step 4: Apply one atomic mutation per engine**

Capture `createdAt` once as above. Build and finalise the task exactly once:

```kotlin
val base = Task(
    id = taskId,
    workspaceId = OpenTasksFixtures.workspaceId,
    projectId = command.projectId,
    statusId = initialStatus.id,
    semanticStatus = initialStatus.semanticStatus,
    title = title,
    priority = command.priority,
    due = command.due,
    estimate = command.estimate,
    recurrence = command.recurrence,
    tagIds = (existingTags.map(Tag::id) + freshTags.map(Tag::id)).toSet(),
    revision = Revision(deviceId, createdAt.toEpochMilli(), 0),
)
val metadata = RecurringTaskPlanner.metadataForUpdate(base, base.due, base.recurrence)
val task = base.copy(
    recurrenceSeriesId = metadata?.seriesId,
    recurrenceAnchor = metadata?.anchor,
    recurrenceOccurrenceIndex = metadata?.occurrenceIndex,
)
```

Use the in-memory engine's existing `DeviceId("local-device")` revision value
instead of `deviceId` in that file.

Room writes planned tags, task, TaskTag rows, and one Created activity inside
the existing outer `execute` transaction. In-memory builds the Created activity
with `current.activityEntries.appendedActivity(..., at = createdAt)` and
publishes tasks, tags, and that activity in one call:

```kotlin
publish(
    tasks = current.tasks + task,
    tags = current.tags + freshTags,
    activityEntries = current.activityEntries.appendedActivity(
        taskId = task.id,
        projectId = task.projectId,
        kind = ActivityKind.RECORD_CREATED,
        body = "Created",
        at = createdAt,
    ),
    at = createdAt,
)
```

Let existing `reconcileTaskTags` journal relations. No follow-up command is
emitted.

- [ ] **Step 5: Run focused verification**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "app.opentasks.core.data.InMemoryVaultRepositoryTest"
./gradlew :core:data:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin
git diff --check
```

Expected: in-memory tests pass and Room/app affected sources compile.

- [ ] **Step 6: Audit the staged paths**

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt \
  core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt
git diff --cached --name-only
```

Expected: exactly the five paths in this task are staged.

- [ ] **Step 7: Commit**

```bash
git commit -m "feat: create enriched tasks atomically"
```

---

### Task 12: Generalise Quick Add's confirm-only chip UI

**Files:**

- Modify: `app/src/main/kotlin/app/opentasks/QuickAddSheet.kt` —
  `QuickAddSheet`, `QuickAddDraft`, and `QuickAddDraftSaver`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt` —
  the sole `QuickAddSheet` call
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt` —
  remove `addTask`; retain `execute`
- Modify: `app/src/main/res/values/strings.xml` — `quick_add_*`
- Create: `app/src/test/kotlin/app/opentasks/QuickAddDraftTest.kt`
- Modify:
  `app/src/androidTest/kotlin/app/opentasks/QuickAddSheetInstrumentedTest.kt` —
  `QuickAddSheetInstrumentedTest`
- Modify:
  `app/src/androidTest/kotlin/app/opentasks/QuickAddPrefillRootWiringInstrumentedTest.kt` —
  `QuickAddPrefillRootWiringInstrumentedTest`
- Modify:
  `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt` —
  `ProcessRestorationInstrumentedTest`
- Modify:
  `app/src/androidTest/kotlin/app/opentasks/input/ShortcutRootWiringInstrumentedTest.kt` —
  `ShortcutRootWiringInstrumentedTest`

**Interfaces:**

- Consumes: Task 10 parser/strip helper, Task 11 atomic command, root snapshot
  projects/tags, and an injected clock.
- Produces the replacement sheet contract:

```kotlin
@Composable
fun QuickAddSheet(
    onDismiss: () -> Unit,
    onAdd: (DomainCommand.CreateTask) -> Unit,
    initialTitle: String = "",
    projects: List<Project> = emptyList(),
    tags: List<Tag> = emptyList(),
    clock: Clock = Clock.systemDefaultZone(),
)
```

  All entry paths still converge on this single sheet; no activity, tile,
  widget, share, PROCESS_TEXT, prefill, FAB, or shortcut contract changes.

- [ ] **Step 1: Write the pure draft-state RED test**

Create `QuickAddDraftTest.kt` with exact primitive-state expectations:

```kotlin
class QuickAddDraftTest {
    private val zone = ZoneId.of("Asia/Bangkok")
    private val now = Instant.parse("2026-08-10T03:00:00Z")
    private val projects = listOf(OpenTasksFixtures.studioProject)
    private val tags = OpenTasksFixtures.tags

    private fun parsed(text: String) = parseQuickAdd(text, now, zone, projects, tags)

    private fun match(
        text: String,
        kind: QuickAddTokenKind,
        occurrence: Int = 0,
    ) = parsed(text).filter { it.kind == kind }[occurrence]

    private fun QuickAddDraft.confirmCurrent(
        kind: QuickAddTokenKind,
        occurrence: Int = 0,
    ): QuickAddDraft {
        val matches = parsed(title)
        return confirm(matches.filter { it.kind == kind }[occurrence], matches)
    }

    private fun QuickAddDraft.confirmValue(value: QuickAddTokenValue): QuickAddDraft {
        val match = QuickAddTokenMatch(0, 0, value)
        return confirm(match, listOf(match))
    }

    @Test fun rightToLeftConfirmationProducesTheExactGrammarFreeCommand() {
        val original = "Plan #stu @Admin !1 every monday ~45m tomorrow"
        val expectedDue = ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), zone.id)
        var draft = QuickAddDraft(title = original)

        assertEquals(DomainCommand.CreateTask(original), draft.toCommand())
        // Recurrence is confirmed while its explicit date token is still present;
        // every later match is obtained from the current, stripped title.
        draft = draft.confirmCurrent(QuickAddTokenKind.RECURRENCE)
        draft = draft.confirmCurrent(QuickAddTokenKind.DATE)
        draft = draft.confirmCurrent(QuickAddTokenKind.ESTIMATE)
        draft = draft.confirmCurrent(QuickAddTokenKind.PRIORITY)
        draft = draft.confirmCurrent(QuickAddTokenKind.TAG)
        draft = draft.confirmCurrent(QuickAddTokenKind.PROJECT)

        assertEquals(
            DomainCommand.CreateTask(
                title = "Plan",
                projectId = OpenTasksFixtures.studioProject.id,
                priority = Priority.URGENT,
                due = expectedDue,
                tagNames = listOf("Admin"),
                estimate = Duration.ofMinutes(45),
                recurrence = RecurrenceRule(
                    frequency = RecurrenceFrequency.WEEKLY,
                    weekdays = setOf(DayOfWeek.MONDAY),
                ),
            ),
            draft.toCommand(),
        )
        val dateCleared = draft.clear(QuickAddTokenKind.DATE)
        assertNull(dateCleared.toCommand().recurrence)
        assertFalse(dateCleared.dueIsExplicit)
        val recurrenceCleared = draft.clear(QuickAddTokenKind.RECURRENCE)
        assertEquals(expectedDue, recurrenceCleared.toCommand().due)
        assertTrue(recurrenceCleared.dueIsExplicit)
    }

    @Test fun recurrenceConfirmedAfterDateKeepsTheExplicitAppliedDue() {
        var draft = QuickAddDraft("Plan tomorrow every monday")
        draft = draft.confirmCurrent(QuickAddTokenKind.DATE)
        val explicitDue = draft.toCommand().due
        val reparsedRecurrence = match(draft.title, QuickAddTokenKind.RECURRENCE)
        val implicitDue = (reparsedRecurrence.value as QuickAddTokenValue.RecurrenceValue).due
        assertEquals(ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), zone.id), explicitDue)
        assertNotEquals(explicitDue, implicitDue)

        draft = draft.confirm(reparsedRecurrence, parsed(draft.title))

        assertEquals("Plan", draft.toCommand().title)
        assertEquals(explicitDue, draft.toCommand().due)
        assertTrue(draft.dueIsExplicit)
        assertEquals(
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
            draft.toCommand().recurrence,
        )
    }

    @Test fun laterRecurrenceReplacesRuleAndImplicitAnchorInEitherOrder() {
        val original = "Plan every monday every tuesday"
        val mondayDue = ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), zone.id)
        val tuesdayDue = ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), zone.id)

        var mondayThenTuesday = QuickAddDraft(original)
            .confirmCurrent(QuickAddTokenKind.RECURRENCE, occurrence = 0)
        assertEquals(mondayDue, mondayThenTuesday.toCommand().due)
        assertFalse(mondayThenTuesday.dueIsExplicit)
        mondayThenTuesday = mondayThenTuesday
            .confirmCurrent(QuickAddTokenKind.RECURRENCE, occurrence = 0)
        assertEquals("Plan", mondayThenTuesday.toCommand().title)
        assertEquals(tuesdayDue, mondayThenTuesday.toCommand().due)
        assertEquals(
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                weekdays = setOf(DayOfWeek.TUESDAY),
            ),
            mondayThenTuesday.toCommand().recurrence,
        )
        assertFalse(mondayThenTuesday.dueIsExplicit)

        var tuesdayThenMonday = QuickAddDraft(original)
            .confirmCurrent(QuickAddTokenKind.RECURRENCE, occurrence = 1)
        assertEquals(tuesdayDue, tuesdayThenMonday.toCommand().due)
        tuesdayThenMonday = tuesdayThenMonday
            .confirmCurrent(QuickAddTokenKind.RECURRENCE, occurrence = 0)
        assertEquals("Plan", tuesdayThenMonday.toCommand().title)
        assertEquals(mondayDue, tuesdayThenMonday.toCommand().due)
        assertEquals(
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
            tuesdayThenMonday.toCommand().recurrence,
        )
        assertFalse(tuesdayThenMonday.dueIsExplicit)
    }

    @Test fun clearingRecurrenceMakesItsRetainedDueExplicit() {
        var draft = QuickAddDraft("Plan every monday")
            .confirmCurrent(QuickAddTokenKind.RECURRENCE)
            .clear(QuickAddTokenKind.RECURRENCE)
            .editTitle("Plan every tuesday")
        assertTrue(draft.dueIsExplicit)

        draft = draft.confirmCurrent(QuickAddTokenKind.RECURRENCE)

        assertEquals(ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), zone.id), draft.toCommand().due)
        assertEquals(
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                weekdays = setOf(DayOfWeek.TUESDAY),
            ),
            draft.toCommand().recurrence,
        )
        assertTrue(draft.dueIsExplicit)
    }

    @Test fun dismissedTokenSurvivesEarlierConfirmationAndReparse() {
        val original = "Plan #stu @Admin"
        val matches = parsed(original)
        val project = matches.single { it.kind == QuickAddTokenKind.PROJECT }
        val tag = matches.single { it.kind == QuickAddTokenKind.TAG }
        var draft = QuickAddDraft(original).dismiss(tag, matches)
        assertEquals(setOf("tag:@admin:0"), draft.dismissedTokenKeys)

        draft = draft.confirm(project, matches)
        val reparsed = parsed(draft.title)
        val reparsedTag = reparsed.single { it.kind == QuickAddTokenKind.TAG }

        assertEquals("Plan @Admin", draft.title)
        assertTrue(reparsedTag.tokenKey(draft.title, reparsed) in draft.dismissedTokenKeys)
        assertTrue(draft.editTitle(draft.title + " now").dismissedTokenKeys.isEmpty())
    }

    @Test fun identicalTokensRemainIndividuallyDismissible() {
        val title = "@Admin @Admin"
        val matches = parsed(title)
        val draft = QuickAddDraft(title).dismiss(matches.last(), matches)
        val visible = matches.filterNot {
            it.tokenKey(title, matches) in draft.dismissedTokenKeys
        }

        assertEquals(setOf("tag:@admin:1"), draft.dismissedTokenKeys)
        assertEquals(listOf(matches.first()), visible)
    }

    @Test fun identicalDismissalOrdinalRebasesWhenEitherSideIsConfirmed() {
        listOf(
            1 to 0, // dismiss right, confirm left
            0 to 1, // dismiss left, confirm right
        ).forEach { (dismissedIndex, confirmedIndex) ->
            val title = "@Admin @Admin"
            val matches = parsed(title)
            val draft = QuickAddDraft(title)
                .dismiss(matches[dismissedIndex], matches)
                .confirm(matches[confirmedIndex], matches)
            val reparsed = parsed(draft.title)

            assertEquals("@Admin", draft.title)
            assertEquals(setOf("tag:@admin:0"), draft.dismissedTokenKeys)
            assertTrue(
                reparsed.single().tokenKey(draft.title, reparsed) in
                    draft.dismissedTokenKeys,
            )
        }
    }

    @Test fun tagsAccumulateCaseInsensitivelyAndSingleValuesReplace() {
        val first = QuickAddDraft("Task")
            .confirmValue(QuickAddTokenValue.TagValue("Focus", null))
            .confirmValue(QuickAddTokenValue.TagValue("FOCUS", null))
            .confirmValue(QuickAddTokenValue.PriorityValue(Priority.LOW))
            .confirmValue(QuickAddTokenValue.PriorityValue(Priority.URGENT))
        assertEquals(listOf("Focus"), first.toCommand().tagNames)
        assertEquals(Priority.URGENT, first.toCommand().priority)
    }

    @Test fun malformedSaverValuesRestoreFailClosed() {
        val restored = requireNotNull(
            QuickAddDraftSaver.restore(
                arrayListOf<Any?>(
                    "title", "Malformed",
                    "priority", "INVALID",
                    "frequency", "INVALID",
                    "interval", 0,
                    "weekdays", arrayListOf("FUNDAY"),
                    "dueExplicit", true,
                ),
            ),
        )
        assertEquals(Priority.NONE.name, restored.priority)
        assertNull(restored.recurrenceFrequency)
        assertEquals(1, restored.recurrenceInterval)
        assertTrue(restored.recurrenceWeekdays.isEmpty())
        assertFalse(restored.dueIsExplicit)
        assertEquals(DomainCommand.CreateTask("Malformed"), restored.toCommand())
    }
}
```

- [ ] **Step 2: Run the host RED test**

```bash
./gradlew :app:testDebugUnitTest \
  --tests "app.opentasks.QuickAddDraftTest"
```

Expected: compilation fails because `QuickAddDraft` and its Saver seam do not
exist.

- [ ] **Step 3: Write the Compose contract RED cases**

Use these exact test-tag functions in production and tests:

```kotlin
internal fun QuickAddTokenMatch.tokenKey(
    text: String,
    matches: List<QuickAddTokenMatch>,
): String {
    val claimed = SearchNormalizer.normalize(text.substring(startIndex, endIndex))
    val peers = matches.filter { peer ->
        peer.kind == kind &&
            SearchNormalizer.normalize(
                text.substring(peer.startIndex, peer.endIndex),
            ) == claimed
    }
    val ordinal = peers.indexOf(this)
    require(ordinal >= 0) { "Quick Add token is absent from the current parse" }
    return "${kind.name.lowercase(Locale.ROOT)}:$claimed:$ordinal"
}
internal fun suggestionTag(match: QuickAddTokenMatch) =
    "quick-add-suggestion-${match.kind.name.lowercase(Locale.ROOT)}-" +
        "${match.startIndex}-${match.endIndex}"
internal fun dismissTag(match: QuickAddTokenMatch) =
    "quick-add-dismiss-${match.kind.name.lowercase(Locale.ROOT)}-" +
        "${match.startIndex}-${match.endIndex}"
```

Pin applied tags to `quick-add-applied-project`,
`quick-add-applied-priority`, `quick-add-date-chip`,
`quick-add-applied-recurrence`, `quick-add-applied-estimate`, and
`quick-add-applied-tag-{normalisedName}` where `normalisedName` is produced
by `SearchNormalizer.normalize(name).replace(' ', '-')`. Pin clear tags to the
same suffixes under `quick-add-clear-*`, except DATE retains the existing
`quick-add-date-clear` tag.

Add this confirm-only case to `QuickAddSheetInstrumentedTest`:

```kotlin
@Test fun detectedTokensDoNothingUntilConfirmed() {
    val submitted = AtomicReference<DomainCommand.CreateTask?>()
    val clock = Clock.fixed(Instant.parse("2026-08-10T03:00:00Z"), ZoneId.of("Asia/Bangkok"))
    val text = "Plan: #stu @Admin !1 ~45m tomorrow"
    composeRule.setContent {
        OpenTasksTheme {
            QuickAddSheet(
                onDismiss = {},
                onAdd = submitted::set,
                projects = listOf(OpenTasksFixtures.studioProject),
                tags = OpenTasksFixtures.tags,
                clock = clock,
            )
        }
    }
    composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)
    composeRule.onNode(hasText("Add task") and hasClickAction()).performClick()
    assertEquals(DomainCommand.CreateTask(text), submitted.get())
}
```

Add a second case that obtains matches with `parseQuickAdd`, confirms
RECURRENCE first while its explicit DATE token remains, then reparses after
each tap and confirms the remaining current matches from right to left. Submit
and assert the exact command from Step 1: title `Plan`, project ID
`OpenTasksFixtures.studioProject.id` (the fixture name is `Studio refresh`, so
never assert the assumed literal `Studio`), tag `Admin`, `Priority.URGENT`, due
`ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), "Asia/Bangkok")`, estimate
45 minutes, and exactly `RecurrenceRule(RecurrenceFrequency.WEEKLY,
weekdays = setOf(DayOfWeek.MONDAY))`.

Make every UI contract observable with individual cases:

- Confirming a project or priority replaces the previously applied value;
  confirming tags accumulates them case-insensitively.
- Dismiss `@Admin`, assert its positional suggestion tag no longer exists,
  edit the title to append ` now`, then assert the current reparsed suggestion
  tag exists again. Also dismiss `@Admin`, confirm the earlier `#stu`, and
  assert the reparsed `@Admin` suggestion stays absent.
- Enter `@Admin @Admin`, dismiss the second suggestion, and assert exactly one
  suggestion remains. In separate host cases, confirm the first while the
  second is dismissed and confirm the second while the first is dismissed;
  after each reparse the sole remaining occurrence must still be suppressed
  under key `tag:@admin:0`.
- Parse `@Roadmap` with no matching tag and assert the chip text is exactly
  `New tag: Roadmap`; parse fixture tag `@Admin` and assert `Tag: Admin`.
- For `!1`, `!2`, `!3`, and `!4`, assert visible chip labels exactly
  `Priority: Urgent`, `Priority: High`, `Priority: Medium`, and
  `Priority: Low`.
- Clear DATE and assert both due and recurrence disappear on submission;
  clear RECURRENCE and assert the explicit due remains.

Use `StateRestorationTester` in `ProcessRestorationInstrumentedTest` for these
separate restoration witnesses; each submits after restore and compares the
captured `DomainCommand.CreateTask` rather than only checking chip presence:
all three inject `Clock.fixed(Instant.parse("2026-08-10T03:00:00Z"),
ZoneId.of("Asia/Bangkok"))`, `listOf(OpenTasksFixtures.studioProject)`, and
`OpenTasksFixtures.tags`.

1. Confirm `#stu`, `@Admin`, `!2`, `tomorrow`, `~45m`, and `every 2 weeks`
   from `Restore #stu @Admin !2 tomorrow ~45m every 2 weeks`, restore, and
   assert title `Restore`, project `OpenTasksFixtures.studioProject.id`,
   `Priority.HIGH`, due instant `2026-08-11T10:00:00Z` with zone ID
   `Asia/Bangkok`, tags `listOf("Admin")`, estimate 45 minutes, and recurrence
   `RecurrenceRule(RecurrenceFrequency.WEEKLY, interval = 2)`. This witnesses
   Saver keys `title`, `project`, `priority`, `dueEpoch`, `dueZone`, `tags`,
   `estimate`, `frequency`, and the non-default `interval`. Confirm RECURRENCE
   before DATE so DATE leaves the saved `dueExplicit` value `true`; after the
   restore, append and confirm `every monday` and assert the restored explicit
   due remains `2026-08-11T10:00:00Z` instead of changing to the implicit
   Monday anchor `2026-08-10T10:00:00Z`. This behaviorally witnesses the
   `dueExplicit` key.
2. Give the implicit-weekday case a same-file, test-only restoration host in
   `ProcessRestorationInstrumentedTest.kt`; do not add a production observer.
   The host stores `QuickAddDraft("Weekday every monday")` with
   `rememberSaveable(stateSaver = QuickAddDraftSaver)`, publishes the current
   draft to an `AtomicReference<QuickAddDraft>` from `SideEffect`, renders an
   `OutlinedTextField` tagged `restored-weekday-title`, and renders a confirm
   button tagged `restored-weekday-confirm`. The button must compute the full
   unfiltered current list with
   `parseQuickAdd(draft.title, clock.instant(), clock.zone, projects, tags)`,
   select its single RECURRENCE match, and call
   `draft.confirm(recurrence, matches)` with that same list. Confirm Monday,
   assert due `ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"),
   "Asia/Bangkok")`, exact weekly-Monday recurrence, and
   `dueIsExplicit == false`, then emulate saved-state restoration. After the
   restore, replace the field text with `Weekday every tuesday`, click the
   confirm button, and assert the observed draft converts exactly to title
   `Weekday`, due
   `ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), "Asia/Bangkok")`,
   `RecurrenceRule(RecurrenceFrequency.WEEKLY,
   weekdays = setOf(DayOfWeek.TUESDAY))`, and `dueIsExplicit == false`.
   This separately witnesses `weekdays` and fails if Saver restore incorrectly
   promotes every valid implicit due to explicit. Keep item 1 as the separate
   explicit-due restoration witness.
3. Dismiss `@Admin`, restore, and assert its suggestion is still absent. This
   witnesses the `dismissed` key as suppression behavior, not merely a saved
   collection value.

The complete primitive Saver-key inventory is therefore `title`, `project`,
`priority`, `dueEpoch`, `dueZone`, `dueExplicit`, `tags`, `estimate`,
`frequency`, `interval`, `weekdays`, and `dismissed`; do not omit any key from
save, restore, or restoration coverage.

In `QuickAddPrefillRootWiringInstrumentedTest`, extend
`QuickAddPrefillReplica` to accept `onAdd: (DomainCommand.CreateTask) -> Unit`,
`projects`, `tags`, and `clock`. Pass the parser inputs unchanged to
`QuickAddSheet`, but retain the replica's live post-submit behavior exactly:

```kotlin
onAdd = { command ->
    onAdd(command)
    showQuickAdd = false
    quickAddSheetTitle = ""
}
```

Add a prefill `Root #stu @Admin !2 every monday tomorrow ~45m`, using
`Clock.fixed(Instant.parse("2026-08-10T03:00:00Z"),
ZoneId.of("Asia/Bangkok"))`, `listOf(OpenTasksFixtures.studioProject)`, and
`OpenTasksFixtures.tags`. Confirm every current match right-to-left, tap Add,
and assert the captured command has title `Root`, the fixture project ID, tag
`Admin`, `Priority.HIGH`, due
`ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), "Asia/Bangkok")`, estimate
45 minutes, and recurrence exactly
`RecurrenceRule(RecurrenceFrequency.WEEKLY,
weekdays = setOf(DayOfWeek.MONDAY))`. Then assert the sheet is absent; reopen
through a replica FAB button whose handler only sets `showQuickAdd = true`
(it does not change the signal or prefill), and assert `quick-add-title` is
empty. The absence proves `showQuickAdd` was cleared; the FAB remount's null
prefill fallback proves `quickAddSheetTitle` was reset after the callback.

In `ShortcutRootWiringInstrumentedTest`, add the corresponding `Ctrl+N` host
case. The shortcut handler opens the real `QuickAddSheet`; inject the same
fixed clock/projects/tags, enter and confirm
`Shortcut #stu @Admin !2 every monday tomorrow ~45m`, capture its emitted
`DomainCommand.CreateTask`, submit, and assert title `Shortcut` plus every
parser-applied field: project `OpenTasksFixtures.studioProject.id`, tag
`Admin`, `Priority.HIGH`, due
`ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), "Asia/Bangkok")`, estimate
45 minutes, and exactly `RecurrenceRule(RecurrenceFrequency.WEEKLY,
weekdays = setOf(DayOfWeek.MONDAY))`. Do not leave either replica's `onAdd` or
parser inputs as discard lambdas/defaults.

- [ ] **Step 4: Compile the RED instrumentation sources**

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: callback/type mismatch and absent grammar chips.

- [ ] **Step 5: Implement the primitive draft and Saver**

Keep the reducer package-internal in `QuickAddSheet.kt`:

```kotlin
internal data class QuickAddDraft(
    val title: String,
    val projectId: String? = null,
    val priority: String = Priority.NONE.name,
    val dueEpochMillis: Long? = null,
    val dueZoneId: String? = null,
    val dueIsExplicit: Boolean = false,
    val tagNames: List<String> = emptyList(),
    val estimateSeconds: Long? = null,
    val recurrenceFrequency: String? = null,
    val recurrenceInterval: Int = 1,
    val recurrenceWeekdays: List<String> = emptyList(),
    val dismissedTokenKeys: Set<String> = emptySet(),
) {
    fun editTitle(value: String) = copy(title = value, dismissedTokenKeys = emptySet())
    fun dismiss(
        match: QuickAddTokenMatch,
        matches: List<QuickAddTokenMatch>,
    ): QuickAddDraft {
        require(match in matches)
        return copy(
            dismissedTokenKeys = dismissedTokenKeys + match.tokenKey(title, matches),
        )
    }

    fun confirm(
        match: QuickAddTokenMatch,
        matches: List<QuickAddTokenMatch>,
    ): QuickAddDraft {
        require(match in matches)
        val dismissedMatches = matches.filter {
            it.tokenKey(title, matches) in dismissedTokenKeys
        }
        val matchedDismissedKeys = dismissedMatches.mapTo(mutableSetOf()) {
            it.tokenKey(title, matches)
        }
        val remainingMatches = matches.filterNot { it == match }
        val rebasedDismissed = (dismissedTokenKeys - matchedDismissedKeys) +
            dismissedMatches
                .filterNot { it == match }
                .map { it.tokenKey(title, remainingMatches) }
        val base = copy(
            title = stripQuickAddToken(title, match),
            dismissedTokenKeys = rebasedDismissed.toSet(),
        )
        return when (val value = match.value) {
            is QuickAddTokenValue.ProjectValue -> base.copy(projectId = value.projectId.value)
            is QuickAddTokenValue.TagValue -> base.copy(
                tagNames = (tagNames + value.name).distinctBy { it.lowercase(Locale.ROOT) }.take(50),
            )
            is QuickAddTokenValue.PriorityValue -> base.copy(priority = value.priority.name)
            is QuickAddTokenValue.DueValue -> base.copy(
                dueEpochMillis = value.due.instant.toEpochMilli(),
                dueZoneId = value.due.zoneId,
                dueIsExplicit = true,
            )
            is QuickAddTokenValue.RecurrenceValue -> {
                val preserveDue = dueIsExplicit &&
                    dueEpochMillis != null &&
                    dueZoneId?.let { runCatching { ZoneId.of(it) }.isSuccess } == true
                base.copy(
                    dueEpochMillis = if (preserveDue) {
                        dueEpochMillis
                    } else {
                        value.due.instant.toEpochMilli()
                    },
                    dueZoneId = if (preserveDue) dueZoneId else value.due.zoneId,
                    dueIsExplicit = preserveDue,
                    recurrenceFrequency = value.rule.frequency.name,
                    recurrenceInterval = value.rule.interval,
                    recurrenceWeekdays = value.rule.weekdays.map(DayOfWeek::name).sorted(),
                )
            }
            is QuickAddTokenValue.EstimateValue ->
                base.copy(estimateSeconds = value.duration.seconds)
        }
    }

    fun clear(kind: QuickAddTokenKind, tagName: String? = null): QuickAddDraft = when (kind) {
        QuickAddTokenKind.PROJECT -> copy(projectId = null)
        QuickAddTokenKind.TAG -> copy(
            tagNames = tagNames.filterNot { it.equals(tagName, ignoreCase = true) },
        )
        QuickAddTokenKind.PRIORITY -> copy(priority = Priority.NONE.name)
        QuickAddTokenKind.DATE -> copy(
            dueEpochMillis = null, dueZoneId = null, dueIsExplicit = false,
            recurrenceFrequency = null,
            recurrenceInterval = 1, recurrenceWeekdays = emptyList(),
        )
        QuickAddTokenKind.RECURRENCE -> copy(
            recurrenceFrequency = null, recurrenceInterval = 1,
            recurrenceWeekdays = emptyList(),
            dueIsExplicit = dueEpochMillis != null &&
                dueZoneId?.let { runCatching { ZoneId.of(it) }.isSuccess } == true,
        )
        QuickAddTokenKind.ESTIMATE -> copy(estimateSeconds = null)
    }

    fun toCommand() = DomainCommand.CreateTask(
        title = title.trim(),
        projectId = projectId?.let(::ProjectId),
        priority = Priority.valueOf(priority),
        due = dueEpochMillis?.let { epoch ->
            dueZoneId?.let { zone -> ZonedMoment(Instant.ofEpochMilli(epoch), zone) }
        },
        tagNames = tagNames,
        estimate = estimateSeconds?.let(Duration::ofSeconds),
        recurrence = recurrenceFrequency?.let { frequency ->
            RecurrenceRule(
                frequency = RecurrenceFrequency.valueOf(frequency),
                interval = recurrenceInterval,
                weekdays = recurrenceWeekdays.mapTo(linkedSetOf(), DayOfWeek::valueOf),
            )
        },
    )
}
```

Save only those primitive values and restore enums fail-closed:

```kotlin
internal val QuickAddDraftSaver = mapSaver(
    save = { draft ->
        mapOf(
            "title" to draft.title,
            "project" to draft.projectId,
            "priority" to draft.priority,
            "dueEpoch" to draft.dueEpochMillis,
            "dueZone" to draft.dueZoneId,
            "dueExplicit" to draft.dueIsExplicit,
            "tags" to ArrayList(draft.tagNames),
            "estimate" to draft.estimateSeconds,
            "frequency" to draft.recurrenceFrequency,
            "interval" to draft.recurrenceInterval,
            "weekdays" to ArrayList(draft.recurrenceWeekdays),
            "dismissed" to ArrayList(draft.dismissedTokenKeys),
        )
    },
    restore = { saved ->
        val priority = (saved["priority"] as? String)
            ?.let { raw -> Priority.entries.firstOrNull { it.name == raw } }
            ?.name ?: Priority.NONE.name
        val frequency = (saved["frequency"] as? String)
            ?.takeIf { raw -> RecurrenceFrequency.entries.any { it.name == raw } }
        val dueEpoch = saved["dueEpoch"] as? Long
        val dueZone = saved["dueZone"] as? String
        QuickAddDraft(
            title = saved["title"] as? String ?: "",
            projectId = saved["project"] as? String,
            priority = priority,
            dueEpochMillis = dueEpoch,
            dueZoneId = dueZone,
            dueIsExplicit = (saved["dueExplicit"] as? Boolean) == true &&
                dueEpoch != null &&
                dueZone?.let { runCatching { ZoneId.of(it) }.isSuccess } == true,
            tagNames = (saved["tags"] as? ArrayList<*>)
                ?.filterIsInstance<String>().orEmpty(),
            estimateSeconds = saved["estimate"] as? Long,
            recurrenceFrequency = frequency,
            recurrenceInterval = (saved["interval"] as? Int)
                ?.takeIf { it in 1..999 } ?: 1,
            recurrenceWeekdays = (saved["weekdays"] as? ArrayList<*>)
                ?.filterIsInstance<String>()
                ?.filter { raw -> DayOfWeek.entries.any { it.name == raw } }
                .orEmpty(),
            dismissedTokenKeys = (saved["dismissed"] as? ArrayList<*>)
                ?.filterIsInstance<String>().orEmpty().toSet(),
        )
    },
)
```

The package-internal `QuickAddDraftSaver` value is the entire host-test seam;
do not add a wrapper, mapper, or production restore API. Its malformed host
test must pass the `arrayListOf<Any?>(key, value, ...)` representation shown in
Step 1 because `mapSaver` is implemented on top of `listSaver`; passing a Map
does not exercise its restore contract.

The occurrence-aware dismissal identity is exactly kind, normalised claimed
text, and zero-based peer ordinal. `dismiss` and `confirm` always receive the
same complete, unfiltered current parse. `confirm` identifies dismissed old
matches, removes the confirmed match from that peer list, and recomputes keys
for the surviving dismissed occurrences; unmatched restored keys are retained.
No position is persisted, positional indices remain only in Compose test tags,
and no mapping class or other production abstraction is added.

- [ ] **Step 6: Parse once per stable clock and render exact controls**

Capture `val parseNow = remember(clock) { clock.instant() }` once per sheet and
call `parseQuickAdd(draft.title, parseNow, clock.zone, projects, tags)`. Store
the draft with `rememberSaveable(stateSaver = QuickAddDraftSaver)`. Keep that
complete parse in `matches`; derive visible suggestions without changing the
list passed to reducer calls:

```kotlin
val visibleMatches = matches.filterNot {
    it.tokenKey(draft.title, matches) in draft.dismissedTokenKeys
}
// Confirm chip:
draft = draft.confirm(match, matches)
// Separate dismiss IconButton:
draft = draft.dismiss(match, matches)
```

Render every visible match as a 48 dp `AssistChip` plus a separate 48 dp
dismiss `IconButton`, using the exact positional tags in Step 3. Render one
clearable applied chip for every populated field. All labels/descriptions use
these exact new resource keys: `quick_add_project_suggestion`,
`quick_add_existing_tag_suggestion`,
`quick_add_new_tag_suggestion`, `quick_add_priority_suggestion`,
`quick_add_recurrence_suggestion`, `quick_add_estimate_suggestion`,
`quick_add_dismiss_suggestion`, and `quick_add_clear_applied`.

Use these exact values so the RED UI assertions in Step 3 pin user-visible
copy rather than merely finding a node by tag:

```xml
<string name="quick_add_project_suggestion">Project: %1$s</string>
<string name="quick_add_existing_tag_suggestion">Tag: %1$s</string>
<string name="quick_add_new_tag_suggestion">New tag: %1$s</string>
<string name="quick_add_priority_suggestion">Priority: %1$s</string>
<string name="quick_add_recurrence_suggestion">Repeat: %1$s</string>
<string name="quick_add_estimate_suggestion">Estimate: %1$s</string>
<string name="quick_add_dismiss_suggestion">Dismiss %1$s suggestion</string>
<string name="quick_add_clear_applied">Clear %1$s</string>
```

Priority display names are exactly `Urgent`, `High`, `Medium`, and `Low` for
`!1`, `!2`, `!3`, and `!4`. Existing and new tag suggestions select their
resource by whether `TagValue.existingTagId` is non-null.

`submit()` calls `onAdd(draft.toCommand())` only when the trimmed title is in
`1..240`. Detection never calls `confirm`; only a suggestion tap does.

- [ ] **Step 7: Wire the root and remove the redundant wrapper**

Pass active snapshot projects, snapshot tags, and the root clock from
`OpenTasksApp`; execute the emitted command directly through the ViewModel's
existing `execute`:

Use the live root names `showQuickAdd`, `quickAddSheetTitle`, and
`quickAddPrefillText`:

```kotlin
QuickAddSheet(
    onDismiss = {
        showQuickAdd = false
        quickAddSheetTitle = ""
    },
    onAdd = { command ->
        viewModel.execute(command)
        showQuickAdd = false
        quickAddSheetTitle = ""
    },
    initialTitle = quickAddPrefillText ?: quickAddSheetTitle,
    projects = snapshot.projects.filter { it.archivedAt == null },
    tags = snapshot.tags,
    clock = clock,
)
```

Delete the two-field `WorkspaceViewModel.addTask` wrapper after
`rg -n 'addTask\(' app/src` shows only its declaration and the call being
replaced. Keep all intake mechanisms opening this same root sheet.

- [ ] **Step 8: Run focused verification**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.QuickAddParserTest"
./gradlew :app:testDebugUnitTest \
  --tests "app.opentasks.QuickAddDraftTest"
./gradlew :app:compileDebugAndroidTestKotlin :app:compileDebugKotlin
git diff --check
```

Expected: parser and draft host tests pass; app and instrumentation sources
compile. Task 18 runs the connected sheet/restoration cases.

- [ ] **Step 9: Audit the staged paths**

```bash
git add app/src/main/kotlin/app/opentasks/QuickAddSheet.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt \
  app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt \
  app/src/main/res/values/strings.xml \
  app/src/test/kotlin/app/opentasks/QuickAddDraftTest.kt \
  app/src/androidTest/kotlin/app/opentasks/QuickAddSheetInstrumentedTest.kt \
  app/src/androidTest/kotlin/app/opentasks/QuickAddPrefillRootWiringInstrumentedTest.kt \
  app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt \
  app/src/androidTest/kotlin/app/opentasks/input/ShortcutRootWiringInstrumentedTest.kt
git diff --cached --name-only
```

Expected: exactly the nine paths in this task are staged.

- [ ] **Step 10: Commit**

```bash
git commit -m "feat: confirm quick add grammar"
```

---

### Task 13: Pin the app to the light scheme

**Files:**

- Modify: `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/OpenTasksTheme.kt`
  (`OpenTasksColors`, `DarkColorScheme`, and `OpenTasksTheme`)
- Modify: `app/src/main/kotlin/app/opentasks/widget/TodayWidget.kt`
  (`WidgetBackground` through `WidgetAccent`)
- Modify: `app/src/main/res/values/themes.xml` (`Theme.OpenTasks`)
- Modify: `app/src/main/res/values-night/themes.xml` (`Theme.OpenTasks`)
- Create:
  `app/src/androidTest/kotlin/app/opentasks/LightThemeInstrumentedTest.kt`
- Modify:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt`
  (`compactLightTwoHundredPercentStacksLongOverdueLabelAboveMetadata`)

**Interfaces:**

- Consumes: the existing Ember light palette and every existing no-argument
  theme call site.
- Produces:

```kotlin
@Composable
fun OpenTasksTheme(content: @Composable () -> Unit)
```

  It always supplies `LightColorScheme`. There is no theme preference, setting,
  storage key, vault value, or backup value.

- [ ] **Step 1: Write the dark-configuration RED test**

Create the test with the v2 Compose rule already used by app tests. Do not pass
a theme selector:

```kotlin
@RunWith(AndroidJUnit4::class)
class LightThemeInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun darkDeviceConfigurationStillUsesTheLightScheme() {
        val observed = AtomicReference<Pair<Color, Color>>()
        composeRule.setContent {
            val darkConfiguration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    Configuration.UI_MODE_NIGHT_YES
            }
            CompositionLocalProvider(LocalConfiguration provides darkConfiguration) {
                OpenTasksTheme {
                    observed.set(
                        MaterialTheme.colorScheme.background to
                            MaterialTheme.colorScheme.surface,
                    )
                    Text("Light only", Modifier.testTag("light-theme-content"))
                }
            }
        }

        composeRule.onNodeWithTag("light-theme-content").assertIsDisplayed()
        assertEquals(
            OpenTasksColors.LightBackground to OpenTasksColors.LightSurface,
            observed.get(),
        )
    }
}
```

- [ ] **Step 2: Record the RED contract and compile the deferred device test**

```bash
rg -n 'Dark[A-Z]|darkColorScheme|isSystemInDarkTheme|darkTheme|Theme.Material.NoActionBar' \
  core/designsystem/src app/src/main feature/more/src/androidTest
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: `rg` finds the palette, theme branch, Today-widget night providers,
night resource parent, and one explicit test argument. The behavioural test
compiles but, under the global device rule, does not execute until Task 18.

- [ ] **Step 3: Delete the dark branch completely**

Delete `isSystemInDarkTheme`, `darkColorScheme`, every `Dark*` colour, and
`DarkColorScheme`; make the remaining API exactly:

```kotlin
@Composable
fun OpenTasksTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = OpenTasksTypography,
        shapes = OpenTasksShapes,
        content = content,
    )
}
```

Use the same light value for both Glance modes and the light platform parent
in both resource qualifiers:

```kotlin
private val WidgetBackground = ColorProvider(
    day = OpenTasksColors.LightSurface,
    night = OpenTasksColors.LightSurface,
)
private val WidgetInk = ColorProvider(
    day = OpenTasksColors.LightInk,
    night = OpenTasksColors.LightInk,
)
private val WidgetMutedInk = ColorProvider(
    day = OpenTasksColors.LightMutedInk,
    night = OpenTasksColors.LightMutedInk,
)
private val WidgetAccent = ColorProvider(
    day = OpenTasksColors.LightEmber,
    night = OpenTasksColors.LightEmber,
)
```

```xml
<style name="Theme.OpenTasks" parent="android:style/Theme.Material.Light.NoActionBar">
```

Replace the one `OpenTasksTheme(darkTheme = false)` call with
`OpenTasksTheme { ... }`. Add no preference, storage key, or UI.

- [ ] **Step 4: Compile every changed consumer**

```bash
./gradlew :core:designsystem:compileDebugKotlin \
  :app:compileDebugAndroidTestKotlin \
  :feature:more:compileDebugAndroidTestKotlin
```

Expected: all three compilations pass.

- [ ] **Step 5: Prove the dark branch and preference surface are absent**

```bash
if rg -n 'Dark[A-Z]|darkColorScheme|isSystemInDarkTheme|darkTheme|Theme.Material.NoActionBar' \
  core/designsystem/src app/src/main feature/more/src/androidTest; then
  exit 1
fi
if rg -ni 'theme[_-]?(preference|mode)' app/src/main; then
  exit 1
fi
git diff --check
```

Expected: both scans return no matches and the diff check passes.

- [ ] **Step 6: Stage only the six theme paths**

```bash
git add core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/OpenTasksTheme.kt \
  app/src/main/kotlin/app/opentasks/widget/TodayWidget.kt \
  app/src/main/res/values/themes.xml \
  app/src/main/res/values-night/themes.xml \
  app/src/androidTest/kotlin/app/opentasks/LightThemeInstrumentedTest.kt \
  feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt
git diff --cached --name-only
```

Expected: exactly the six listed paths are staged.

- [ ] **Step 7: Commit the light-only contract**

```bash
git commit -m "feat: pin the light theme"
```

Expected: one commit containing only those six paths.

---

### Task 14: Implement atomic task duplication in both engines

**Files:**

- Modify: `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
  (`DomainCommand` beside `CreateTask`)
- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/TaskDuplication.kt`
- Create:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/TaskDuplicationTest.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
  (`dispatch` and a new `duplicateTask` beside `createTask`)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
  (`dispatch` and a new `duplicateTask` beside `createTask`)
- Modify: `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`
  (new `duplicateTask*` tests)
- Modify: `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`
  (new `duplicateTask*` tests)

**Interfaces:**

- Consumes: current task/status/relations, existing id/revision generators,
  transaction/journal infrastructure, and soft-delete Undo.
- Produces:

```kotlin
data class DuplicateTask(val taskId: TaskId) : DomainCommand

fun planTaskDuplicate(
    source: Task,
    targetStatus: WorkflowStatus,
    duplicateId: TaskId,
    checklistItemIds: List<String>,
    revision: Revision,
): Task
```

  Success returns `DeleteTask(duplicateId, createdAt)`. Task 15 consumes only
  the command.

- [ ] **Step 1: Write the pure inclusion/exclusion RED test**

Use the real fixture as the constructor authority and populate every ruled
field. The core assertions are:

```kotlin
@Test
fun copiesOnlyTheApprovedFieldSet() {
    val anchor = ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), "UTC")
    val source = OpenTasksFixtures.tasks.first { it.checklist.isNotEmpty() }.copy(
        id = TaskId("source"),
        parentTaskId = TaskId("parent"),
        title = "x".repeat(240),
        description = "description",
        priority = Priority.URGENT,
        start = anchor,
        due = anchor,
        recurrence = RecurrenceRule(RecurrenceFrequency.WEEKLY),
        recurrenceSeriesId = TaskId("series"),
        recurrenceAnchor = anchor,
        recurrenceOccurrenceIndex = 3,
        estimate = Duration.ofMinutes(90),
        tagIds = setOf(TagId("tag-a"), TagId("tag-b")),
        checklist = listOf(
            ChecklistItem("old-1", "First", completed = true, rank = "a"),
            ChecklistItem("old-2", "Second", completed = false, rank = "b"),
        ),
        dependencyIds = setOf(TaskId("dependency")),
        blockedBy = setOf(TaskId("dependency")),
        completedAt = Instant.parse("2026-08-10T09:00:00Z"),
    )
    val target = OpenTasksFixtures.workflowStatuses.first {
        it.projectId == source.projectId && it.semanticStatus == SemanticStatus.BACKLOG
    }
    val revision = Revision(DeviceId("duplicate-device"), 42L, 0)

    val duplicate = planTaskDuplicate(
        source = source,
        targetStatus = target,
        duplicateId = TaskId("duplicate"),
        checklistItemIds = listOf("new-1", "new-2"),
        revision = revision,
    )

    assertEquals(TaskId("duplicate"), duplicate.id)
    assertEquals("x".repeat(233) + " (copy)", duplicate.title)
    assertEquals(source.workspaceId, duplicate.workspaceId)
    assertEquals(source.projectId, duplicate.projectId)
    assertEquals(source.parentTaskId, duplicate.parentTaskId)
    assertEquals(source.description, duplicate.description)
    assertEquals(source.priority, duplicate.priority)
    assertEquals(source.start, duplicate.start)
    assertEquals(source.due, duplicate.due)
    assertEquals(source.estimate, duplicate.estimate)
    assertEquals(source.milestoneId, duplicate.milestoneId)
    assertEquals(source.tagIds, duplicate.tagIds)
    assertEquals(source.dependencyIds, duplicate.dependencyIds)
    assertEquals(target.id, duplicate.statusId)
    assertEquals(target.semanticStatus, duplicate.semanticStatus)
    assertEquals(listOf("new-1", "new-2"), duplicate.checklist.map { it.id })
    assertEquals(listOf("First", "Second"), duplicate.checklist.map { it.text })
    assertEquals(listOf("a", "b"), duplicate.checklist.map { it.rank })
    assertTrue(duplicate.checklist.none { it.completed })
    assertNull(duplicate.completedAt)
    assertNull(duplicate.deletedAt)
    assertTrue(duplicate.blockedBy.isEmpty())
    assertNull(duplicate.recurrence)
    assertNull(duplicate.recurrenceSeriesId)
    assertNull(duplicate.recurrenceAnchor)
    assertNull(duplicate.recurrenceOccurrenceIndex)
    assertEquals(revision, duplicate.revision)
}

@Test
fun rejectsMismatchedTargetOrChecklistIdentityCounts() {
    val source = OpenTasksFixtures.tasks.first { it.checklist.isNotEmpty() }
    val otherProjectStatus = OpenTasksFixtures.workflowStatuses.first {
        it.projectId != source.projectId
    }
    assertThrows(IllegalArgumentException::class.java) {
        planTaskDuplicate(
            source,
            otherProjectStatus,
            TaskId("copy"),
            source.checklist.map { UUID.randomUUID().toString() },
            source.revision,
        )
    }
    assertThrows(IllegalArgumentException::class.java) {
        planTaskDuplicate(
            source,
            OpenTasksFixtures.workflowStatuses.first { it.id == source.statusId },
            TaskId("copy"),
            emptyList(),
            source.revision,
        )
    }
}
```

- [ ] **Step 2: Write dual-engine command RED tests**

Add the same behavioural matrix to both repository test classes. Use their
existing repository/open-repository fixtures and these exact assertions:

```kotlin
val before = repository.observeWorkspace().value
val source = before.tasks.first { !it.isCompleted && it.deletedAt == null }
val result = repository.execute(DomainCommand.DuplicateTask(source.id))
    as CommandResult.Success
val after = repository.observeWorkspace().value
val duplicate = after.tasks.single { it.id != source.id && it.title == "${source.title} (copy)" }

assertEquals(source.statusId, duplicate.statusId)
assertEquals(source.semanticStatus, duplicate.semanticStatus)
assertFalse(duplicate.isCompleted)
assertEquals(source.tagIds, duplicate.tagIds)
assertEquals(source.dependencyIds, duplicate.dependencyIds)
assertEquals(
    source.dependencyIds.filterTo(linkedSetOf()) { dependencyId ->
        after.tasks.first { it.id == dependencyId }.let { !it.isCompleted && it.deletedAt == null }
    },
    duplicate.blockedBy,
)
assertTrue(duplicate.checklist.none { it.completed })
assertTrue(duplicate.checklist.map { it.id }.intersect(source.checklist.map { it.id }.toSet()).isEmpty())
assertFalse(after.reminders.any { it.taskId == duplicate.id })
assertFalse(after.notes.any { it.taskId == duplicate.id })
assertFalse(after.timeEntries.any { it.taskId == duplicate.id })
assertFalse(after.attachments.any { it.taskId == duplicate.id })
assertNull(duplicate.recurrence)
assertEquals(
    1,
    after.activityEntries.count {
        it.taskId == duplicate.id && it.kind == ActivityKind.RECORD_CREATED
    },
)

repository.execute(checkNotNull(result.undo))
val undone = repository.observeWorkspace().value
assertNotNull(undone.tasks.single { it.id == duplicate.id }.deletedAt)
assertNull(undone.tasks.single { it.id == source.id }.deletedAt)
```

Add named cases `duplicateTaskKeepsAnArchivedOpenStatus`,
`duplicateTaskMovesACompletedSourceToTheFirstActiveBacklog`, and
`duplicateTaskRejectionsAreMutationFree`. The rejection table is exact:

```kotlin
listOf(
    DomainCommand.DuplicateTask(TaskId("missing")) to RejectionReason.NOT_FOUND,
    DomainCommand.DuplicateTask(deletedSource.id) to RejectionReason.INVALID_STATE,
    DomainCommand.DuplicateTask(sourceWithoutRequiredStatus.id) to
        RejectionReason.INVALID_STATE,
).forEach { (command, expectedReason) ->
    val before = repository.observeWorkspace().value
    val result = repository.execute(command) as CommandResult.Rejected
    assertEquals(expectedReason, result.reason)
    assertEquals(before, repository.observeWorkspace().value)
}
```

In the Room success case, close and reopen before reading the duplicate, then
decode the one new journal generation and assert:

```kotlin
val families = latestJournalPayloads().map { payload ->
    checkNotNull(payload.record).family
}
assertEquals(1, families.count { it == BackupRecordFamily.TASK })
assertEquals(1, families.count { it == BackupRecordFamily.ACTIVITY_ENTRY })
assertEquals(source.checklist.size, families.count {
    it == BackupRecordFamily.CHECKLIST_ITEM
})
assertEquals(source.tagIds.size, families.count {
    it == BackupRecordFamily.TASK_TAG
})
assertEquals(source.dependencyIds.size, families.count {
    it == BackupRecordFamily.TASK_DEPENDENCY
})
assertEquals(0, families.count { it == BackupRecordFamily.TAG })
```

Seed incoming dependencies, reminder, notes, prior activity, time entries, and
attachments through the existing fixtures. Assert none point at the duplicate;
the outgoing dependencies above are the only dependency rows copied.

- [ ] **Step 3: Run the RED tests**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.TaskDuplicationTest"
./gradlew :core:data:testDebugUnitTest \
  --tests "app.opentasks.core.data.InMemoryVaultRepositoryTest"
./gradlew :core:data:compileDebugAndroidTestKotlin
```

Expected: duplication function/command are unresolved.

- [ ] **Step 4: Implement the one pure copy function**

Add the command beside `CreateTask`:

```kotlin
data class DuplicateTask(val taskId: TaskId) : DomainCommand
```

Implement one function, with no class or interface:

```kotlin
private const val COPY_SUFFIX = " (copy)"
private const val MAX_DUPLICATE_TITLE_LENGTH = 240

fun planTaskDuplicate(
    source: Task,
    targetStatus: WorkflowStatus,
    duplicateId: TaskId,
    checklistItemIds: List<String>,
    revision: Revision,
): Task {
    require(targetStatus.projectId == source.projectId)
    require(checklistItemIds.size == source.checklist.size)
    return source.copy(
        id = duplicateId,
        statusId = targetStatus.id,
        semanticStatus = targetStatus.semanticStatus,
        title = source.title.take(MAX_DUPLICATE_TITLE_LENGTH - COPY_SUFFIX.length) + COPY_SUFFIX,
        checklist = source.checklist.zip(checklistItemIds) { item, id ->
            item.copy(id = id, completed = false)
        },
        blockedBy = emptySet(),
        completedAt = null,
        deletedAt = null,
        recurrence = null,
        recurrenceSeriesId = null,
        recurrenceAnchor = null,
        recurrenceOccurrenceIndex = null,
        revision = revision,
    )
}
```

- [ ] **Step 5: Preflight and apply in both repositories**

Add `is DomainCommand.DuplicateTask -> duplicateTask(command)` to both
exhaustive dispatches. Room begins with its existing database lookup:

```kotlin
val source = currentTask(command.taskId)
    ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
val statuses = database.workspaceDao()
    .getWorkflowStatuses(source.projectId?.value)
    .map(WorkflowStatusEntity::toModel)
```

In-memory uses its snapshot directly:

```kotlin
val current = mutableWorkspace.value
val source = current.tasks.firstOrNull { it.id == command.taskId }
    ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
val statuses = current.workflowStatuses.filter { it.projectId == source.projectId }
```

Then both apply the same preflight before allocating ids:

```kotlin
if (source.deletedAt != null) {
    return CommandResult.Rejected(RejectionReason.INVALID_STATE, "Restore that task first.")
}
val targetStatus = if (!source.isCompleted) {
    statuses.firstOrNull { it.id == source.statusId }
} else {
    statuses.asSequence()
        .filter {
            it.projectId == source.projectId &&
                it.semanticStatus == SemanticStatus.BACKLOG &&
                it.archivedAt == null
        }
        .minByOrNull { it.rank }
} ?: return CommandResult.Rejected(
    RejectionReason.INVALID_STATE,
    "This task has no available destination status.",
)
```

Capture `createdAt` once, then call the pure function with `TaskId.new()`, one
`UUID.randomUUID().toString()` per source checklist row, and a logical counter
of zero. Mirror the existing create-task activity and Undo:

```kotlin
return CommandResult.Success(
    message = "Task duplicated",
    undo = DomainCommand.DeleteTask(duplicate.id, createdAt),
)
```

Room persists only the copied task and its existing relation kinds:

```kotlin
database.taskDao().upsert(duplicate.toEntity())
duplicate.checklistEntities().forEach { database.workspaceDao().upsertChecklistItem(it) }
duplicate.tagEntities().forEach { database.workspaceDao().upsertTaskTag(it) }
duplicate.dependencyEntities().forEach { database.workspaceDao().upsertDependency(it) }
recordActivity(
    taskId = duplicate.id,
    projectId = duplicate.projectId,
    kind = ActivityKind.RECORD_CREATED,
    body = "Created",
    at = createdAt,
)
```

`tagEntities()` creates `TaskTagEntity` relation rows. Do not upsert, create, or
modify any `TagEntity`; a successful duplicate therefore emits zero `TAG`
journal mutations. In-memory publishes the copied task and records one Created
activity through its existing atomic `execute` boundary. Neither engine reads
or writes reminders, notes, prior activity, time entries, attachments,
recurrence metadata, or incoming dependency rows for the copy.

- [ ] **Step 6: Run focused verification**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.TaskDuplicationTest"
./gradlew :core:data:testDebugUnitTest \
  --tests "app.opentasks.core.data.InMemoryVaultRepositoryTest"
./gradlew :core:data:compileDebugAndroidTestKotlin
git diff --check
```

Expected: pure and in-memory tests pass and Room tests compile.

- [ ] **Step 7: Stage only the seven duplication paths**

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt \
  core/domain/src/main/kotlin/app/opentasks/core/domain/TaskDuplication.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/TaskDuplicationTest.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt \
  core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt
git diff --cached --name-only
```

Expected: exactly the seven listed paths are staged.

- [ ] **Step 8: Commit atomic duplication**

```bash
git commit -m "feat: duplicate tasks atomically"
```

Expected: one commit containing only those seven paths.

---

### Task 15: Add duplication entry points to detail and board

**Files:**

- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
  (`TasksScreen`, both `TaskDetailPane` calls, `TaskDetailPane` action column)
- Modify: `feature/tasks/src/main/res/values/strings.xml` (`task_duplicate`)
- Modify:
  `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskEditorInstrumentedTest.kt`
  (new `duplicateAction*` tests)
- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/BoardView.kt`
  (`BoardView`, `BoardTaskCard`, existing card `DropdownMenu`)
- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`
  (`ProjectsScreen`, both `ProjectWorkbench` calls, `ProjectWorkbench`)
- Modify: `feature/projects/src/main/res/values/strings.xml`
  (`board_duplicate_task`, `board_duplicate_task_description`)
- Modify:
  `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/BoardViewInstrumentedTest.kt`
  (new `duplicateMenuEmitsTaskIdAndKeepsMoveTargets` test)
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
  (`TasksScreen` and `ProjectsScreen` call sites)

**Interfaces:**

- Consumes: Task 14's `DuplicateTask` and selected task/card ids.
- Produces defaulted plain callbacks:

```kotlin
onDuplicateTask: (TaskId) -> Unit = {}
```

  on `TasksScreen`, `ProjectsScreen`, and `BoardView`; existing call sites stay
  source-compatible.

- [ ] **Step 1: Write detail-action RED coverage**

Add tests using the file's existing `TasksScreen` fixture. The action contract
is exact:

```kotlin
val duplicated = AtomicReference<TaskId?>()
composeRule.setContent {
    OpenTasksTheme {
        TasksScreen(
            tasks = listOf(task),
            projectNames = OpenTasksFixtures.snapshot.projects.associate { it.id to it.name },
            workflowStatuses = OpenTasksFixtures.workflowStatuses,
            tags = OpenTasksFixtures.tags,
            selectedTaskId = task.id,
            showDetailPane = false,
            onSelectTask = {},
            onCloseDetail = {},
            onCompleteTask = {},
            onChangeTaskStatus = { _, _ -> },
            onDeleteTask = {},
            activeTimerTaskId = null,
            onToggleTimer = {},
            onUpdateTask = { _, _ -> },
            onAddChecklistItem = { _, _ -> },
            onUpdateChecklistItem = { _, _, _, _ -> },
            onDeleteChecklistItem = { _, _ -> },
            onSetTaskTag = { _, _, _ -> },
            onDuplicateTask = duplicated::set,
        )
    }
}
composeRule.onNodeWithTag("duplicate-task")
    .performScrollTo()
    .assertTextEquals(
        ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.task_duplicate),
    )
    .assertHeightIsAtLeast(48.dp)
    .assertIsEnabled()
    .performClick()
assertEquals(task.id, duplicated.get())
```

In a second test, replace the title, assert `duplicate-task` is disabled while
`dirty == true`, advance the existing 650 ms clock, feed the captured
`TaskEdit` back as the rendered task, and assert it becomes enabled. Replace
the title with blank text and assert it stays disabled. This pins
`enabled = valid && !dirty` and prevents stale-state copies.

- [ ] **Step 2: Write board-menu RED coverage**

Extend the existing board fixture with a capture and assert the menu contract:

```kotlin
val duplicated = AtomicReference<TaskId?>()
val backlog = OpenTasksFixtures.workflowStatuses.single {
    it.id == OpenTasksFixtures.backlog
}
val planned = OpenTasksFixtures.workflowStatuses.single {
    it.id == OpenTasksFixtures.planned
}
BoardView(
    columns = listOf(
        BoardColumn(
            status = backlog,
            tasks = listOf(
                task.copy(
                    projectId = OpenTasksFixtures.studioProject.id,
                    statusId = backlog.id,
                    semanticStatus = backlog.semanticStatus,
                ),
            ),
        ),
        BoardColumn(status = planned, tasks = emptyList()),
    ),
    columnWidth = 272.dp,
    onMoveTask = { taskId, statusId -> moved.set(taskId to statusId) },
    onOpenTask = {},
    onDuplicateTask = duplicated::set,
)

composeRule.onNodeWithTag("board-move-${task.id.value}").performClick()
composeRule.onNodeWithTag("board-duplicate-${task.id.value}").performClick()
assertEquals(task.id, duplicated.get())

composeRule.onNodeWithTag("board-move-${task.id.value}").performClick()
composeRule.onNodeWithTag(
    "board-move-${task.id.value}-to-${OpenTasksFixtures.planned.value}",
).performClick()
assertEquals(task.id to OpenTasksFixtures.planned, moved.get())
```

Keep the existing drag and custom-accessibility-action tests unchanged.

- [ ] **Step 3: Run the RED compilation**

```bash
./gradlew :feature:tasks:compileDebugAndroidTestKotlin \
  :feature:projects:compileDebugAndroidTestKotlin
```

Expected: missing callback/menu/action failures.

- [ ] **Step 4: Add stateless feature actions**

Add one string to each feature module:

```xml
<string name="task_duplicate">Duplicate task</string>
<string name="board_duplicate_task">Duplicate</string>
<string name="board_duplicate_task_description">Duplicate %1$s</string>
```

Thread the defaulted callback through both detail-pane paths and add this
action immediately before Move to Bin:

```kotlin
OutlinedButton(
    onClick = { onDuplicateTask(task.id) },
    enabled = valid && !dirty,
    modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .testTag("duplicate-task"),
) {
    Text(stringResource(R.string.task_duplicate))
}
```

Thread the same callback through both workbench paths and `BoardTaskCard`.
Inside the existing card `DropdownMenu`, add this item without changing the
relative order of `targets.forEach`:

```kotlin
DropdownMenuItem(
    text = { Text(stringResource(R.string.board_duplicate_task)) },
    onClick = {
        menuExpanded = false
        onDuplicateTask(task.id)
    },
    modifier = Modifier
        .heightIn(min = 48.dp)
        .testTag("board-duplicate-${task.id.value}"),
)
```

Use `board_duplicate_task_description` for the item's accessibility label.
Do not touch drag state, move targets, or board ordering.

- [ ] **Step 5: Dispatch the command at the root**

Pass the same root lambda to both feature calls:

```kotlin
onDuplicateTask = { taskId ->
    viewModel.execute(DomainCommand.DuplicateTask(taskId))
},
```

The existing Workspace event/Undo surface owns the result. Add no feature
snackbar or second Undo path.

- [ ] **Step 6: Run focused verification**

```bash
./gradlew :feature:tasks:compileDebugAndroidTestKotlin \
  :feature:projects:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin
git diff --check
```

Expected: both feature test sources and the app compile.

- [ ] **Step 7: Stage only the eight UI paths**

```bash
git add feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt \
  feature/tasks/src/main/res/values/strings.xml \
  feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskEditorInstrumentedTest.kt \
  feature/projects/src/main/kotlin/app/opentasks/feature/projects/BoardView.kt \
  feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt \
  feature/projects/src/main/res/values/strings.xml \
  feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/BoardViewInstrumentedTest.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt
git diff --cached --name-only
```

Expected: exactly the eight listed paths are staged.

- [ ] **Step 8: Commit the two entry points**

```bash
git commit -m "feat: expose task duplication actions"
```

Expected: one commit containing only those eight paths.

---

### Task 16: Add reusable dot-matrix primitives and restyle Insights bars

**Files:**

- Modify: `core/designsystem/build.gradle.kts` (`dependencies`)
- Create:
  `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/DotMatrix.kt`
- Create:
  `core/designsystem/src/test/kotlin/app/opentasks/core/designsystem/DotMatrixTest.kt`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/InsightsScreen.kt`
  (`InsightsChart`, `EstimateChart`, `DurationChartSection`, `TagDurationChart`,
  and `MetricBar`)
- Modify:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt`
  (`chartAndTableExposeTheSameOrderedLabelsAndValues` and
  `positiveSubMinuteDurationsKeepNonZeroLabelsAndBarRatios`)

**Interfaces:**

- Consumes: existing Ember Material colours, Canvas, metric progress/counts,
  and merged Insights semantics.
- Produces:

```kotlin
@Composable
fun DotRunBar(
    progress: Float,
    modifier: Modifier = Modifier,
    unitCount: Long? = null,
    maxDots: Int = 24,
)

@Composable
fun DottedAreaChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    maxRows: Int = 12,
)

internal data class DotRunLayout(
    val total: Int,
    val filled: Int,
)

internal fun dotRunLayout(
    progress: Float,
    unitCount: Long?,
    maxDots: Int,
): DotRunLayout

internal fun dottedColumnHeights(
    values: List<Float>,
    maxRows: Int,
): List<Int>
```

  The composable wrappers expose stable test tags `dot-run-bar` and
  `dotted-area-chart`; their child `Canvas` nodes expose no semantics. Task 17
  consumes `DottedAreaChart`; Stage 8 may reuse both public composables.

- [ ] **Step 1: Write pure dot-layout RED tests**

Add `testImplementation(libs.junit)`—the existing catalogue dependency, not a
new library—and create this test class:

```kotlin
class DotMatrixTest {
    @Test
    fun unitCountsAndProportionsProduceBoundedVisibleDots() {
        assertEquals(DotRunLayout(0, 0), dotRunLayout(0f, 0L, 24))
        assertEquals(DotRunLayout(3, 3), dotRunLayout(0.6f, 3L, 24))
        assertEquals(DotRunLayout(24, 1), dotRunLayout(0.01f, null, 24))
        assertEquals(DotRunLayout(24, 12), dotRunLayout(0.5f, null, 24))
        assertEquals(DotRunLayout(24, 24), dotRunLayout(2f, null, 24))
        assertEquals(DotRunLayout(24, 0), dotRunLayout(Float.NaN, null, 24))
        assertEquals(DotRunLayout(24, 0), dotRunLayout(-1f, null, 24))
    }

    @Test
    fun columnsNormaliseAndKeepPositiveValuesVisible() {
        assertEquals(
            listOf(0, 1, 6, 12),
            dottedColumnHeights(listOf(0f, 1f, 6f, 12f), 12),
        )
        assertEquals(listOf(0, 0), dottedColumnHeights(listOf(0f, 0f), 12))
        assertEquals(
            listOf(0, 0, 0),
            dottedColumnHeights(listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY), 12),
        )
    }

    @Test
    fun nonPositiveLimitsFailFast() {
        assertThrows(IllegalArgumentException::class.java) {
            dotRunLayout(0.5f, null, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            dottedColumnHeights(listOf(1f), 0)
        }
    }
}
```

For column sanitisation, only finite non-negative values participate in the
maximum; negative and non-finite values map to zero.

- [ ] **Step 2: Run the RED JVM test**

```bash
./gradlew :core:designsystem:testDebugUnitTest \
  --tests "app.opentasks.core.designsystem.DotMatrixTest"
```

Expected: test source/functions are absent.

- [ ] **Step 3: Implement the two Canvas primitives**

Use these exact pure rules:

```kotlin
internal data class DotRunLayout(val total: Int, val filled: Int)

internal fun dotRunLayout(
    progress: Float,
    unitCount: Long?,
    maxDots: Int,
): DotRunLayout {
    require(maxDots > 0)
    if (unitCount != null && unitCount in 0..maxDots.toLong()) {
        val count = unitCount.toInt()
        return DotRunLayout(total = count, filled = count)
    }
    val bounded = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val filled = if (bounded == 0f) 0 else {
        ceil(bounded * maxDots).toInt().coerceIn(1, maxDots)
    }
    return DotRunLayout(total = maxDots, filled = filled)
}

internal fun dottedColumnHeights(values: List<Float>, maxRows: Int): List<Int> {
    require(maxRows > 0)
    val finiteMaximum = values.filter(Float::isFinite)
        .filter { it >= 0f }
        .maxOrNull()
        ?.takeIf { it > 0f }
        ?: return values.map { 0 }
    return values.map { value ->
        val bounded = when {
            !value.isFinite() || value < 0f -> 0f
            else -> value.coerceAtMost(finiteMaximum)
        }
        if (bounded == 0f) 0 else {
            ceil((bounded / finiteMaximum) * maxRows).toInt().coerceIn(1, maxRows)
        }
    }
}
```

Build each visual as a tagged `Box` containing a semantics-free `Canvas`:

```kotlin
@Composable
fun DotRunBar(
    progress: Float,
    modifier: Modifier,
    unitCount: Long?,
    maxDots: Int,
) {
    val layout = dotRunLayout(progress, unitCount, maxDots)
    val filledColor = MaterialTheme.colorScheme.secondary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.testTag("dot-run-bar")) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
            if (layout.total == 0) return@Canvas
            val diameter = minOf(
                size.height,
                size.width / (layout.total + (layout.total - 1) * 0.5f),
            )
            val step = diameter * 1.5f
            val contentWidth = diameter + step * (layout.total - 1)
            val firstX = (size.width - contentWidth) / 2f + diameter / 2f
            repeat(layout.total) { index ->
                drawCircle(
                    color = if (index < layout.filled) filledColor else trackColor,
                    radius = diameter / 2f,
                    center = Offset(firstX + index * step, size.height / 2f),
                )
            }
        }
    }
}
```

Keep the public defaults from the Interfaces block. `DottedAreaChart` follows
the same wrapper pattern with tag `dotted-area-chart`, calls
`dottedColumnHeights(values, maxRows)`, and draws each positive height as a
bottom-aligned column of `secondary` circles. It returns an empty Canvas for an
empty/all-zero series and calls neither semantics nor text APIs.

- [ ] **Step 4: Replace every Insights progress bar**

Remove the `LinearProgressIndicator` import/use. Keep existing merged semantics
and replace its body with:

```kotlin
@Composable
private fun MetricBar(
    label: String,
    value: String,
    progress: Float,
    unitCount: Long? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label, $value"
            },
    ) {
        // Keep the existing label/value Row unchanged.
        Spacer(Modifier.height(6.dp))
        DotRunBar(
            progress = progress,
            unitCount = unitCount,
            modifier = Modifier.fillMaxWidth().height(12.dp),
        )
    }
}
```

Pass `snapshot.completed.current` and `.previous` as `unitCount`. Leave all
duration calls on the default `null` value so their existing progress ratios
remain proportional.

- [ ] **Step 5: Extend Compose assertions**

In the existing populated chart test, retain all label/value assertions and
add unmerged-tree checks so merged accessibility remains authoritative:

```kotlin
composeRule.onAllNodesWithTag("dot-run-bar", useUnmergedTree = true)
    .assertCountEquals(8)
composeRule.onAllNodes(
    SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
    useUnmergedTree = true,
).assertCountEquals(0)
composeRule.onNodeWithContentDescription("Alpha, 1 h").assertExists()
composeRule.onNodeWithContentDescription("Focus, 45 min").assertExists()
```

The count is exactly eight: two completion, two estimate, two project-time,
and two tag-time rows in `populatedState()`. Do not add a content description
to either Canvas.

- [ ] **Step 6: Run focused verification**

```bash
./gradlew :core:designsystem:testDebugUnitTest \
  --tests "app.opentasks.core.designsystem.DotMatrixTest"
./gradlew :feature:more:compileDebugAndroidTestKotlin
git diff --check
```

Expected: layout tests pass and the feature device tests compile.

- [ ] **Step 7: Stage only the five restyle paths**

```bash
git add core/designsystem/build.gradle.kts \
  core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/DotMatrix.kt \
  core/designsystem/src/test/kotlin/app/opentasks/core/designsystem/DotMatrixTest.kt \
  feature/more/src/main/kotlin/app/opentasks/feature/more/InsightsScreen.kt \
  feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt
git diff --cached --name-only
```

Expected: exactly the five listed paths are staged.

- [ ] **Step 8: Commit the dot primitives and restyle**

```bash
git commit -m "feat: render insights with dot matrices"
```

Expected: one commit containing only those five paths.

---

### Task 17: Add zone-aware daily completion trends

**Files:**

- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Insights.kt`
  (`CompletionTrendPoint` and `InsightsSnapshot`)
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/InsightsEngine.kt`
  (`DefaultInsightsEngine.calculate`)
- Modify:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/InsightsEngineTest.kt`
  (new `completionTrend*` tests)
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/InsightsScreen.kt`
  (`InsightsChart`, `InsightsTable`, new trend composables, date formatter)
- Modify: `feature/more/src/main/res/values/strings.xml`
  (`insights_completion_trend_*`)
- Modify:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt`
  (new `completionTrend*` and `defaultEmptyCompletionTrend*` tests)

**Interfaces:**

- Consumes: the Insights engine's existing explicit `now`/`zoneId`, selected
  project/tag filters, Task 16's `DottedAreaChart`, and 7/30/90-day range.
- Produces:

```kotlin
data class CompletionTrendPoint(
    val date: LocalDate,
    val completed: Long,
)

data class InsightsSnapshot(
    // existing fields unchanged
    val completionTrend: List<CompletionTrendPoint> = emptyList(),
)
```

  The default is appended last for fixture/source compatibility.

- [ ] **Step 1: Write trend-engine RED tests**

Add one parameter loop for shape and one boundary test. Reuse the file's
existing `workspace(tasks = ...)` helper or add the same one-line
`OpenTasksFixtures.snapshot.copy(tasks = tasks)` helper:

```kotlin
@Test
fun completionTrendContainsEverySelectedLocalDay() {
    val now = Instant.parse("2026-08-10T12:00:00Z")
    InsightsRange.entries.forEach { range ->
        val snapshot = engine.calculate(
            OpenTasksFixtures.snapshot.copy(tasks = emptyList()),
            InsightsSelection(range = range),
            now,
            ZoneId.of("UTC"),
        )
        assertEquals(range.dayCount.toInt(), snapshot.completionTrend.size)
        assertEquals(LocalDate.of(2026, 8, 10), snapshot.completionTrend.last().date)
        assertTrue(snapshot.completionTrend.zipWithNext().all { (a, b) ->
            b.date == a.date.plusDays(1)
        })
        assertTrue(snapshot.completionTrend.all { it.completed == 0L })
    }
}

@Test
fun completionTrendUsesHalfOpenZoneAwareDaysAcrossDst() {
    val zone = ZoneId.of("America/New_York")
    val now = ZonedDateTime.of(2026, 3, 9, 12, 0, 0, 0, zone).toInstant()
    val start = LocalDate.of(2026, 3, 3).atStartOfDay(zone).toInstant()
    val end = LocalDate.of(2026, 3, 10).atStartOfDay(zone).toInstant()
    val moments = listOf(
        start.minusNanos(1),
        start,
        LocalDate.of(2026, 3, 8).atTime(23, 30).atZone(zone).toInstant(),
        end.minusNanos(1),
        end,
    )
    val tasks = moments.mapIndexed { index, instant ->
        OpenTasksFixtures.tasks.first().copy(
            id = TaskId("trend-$index"),
            completedAt = instant,
        )
    }
    val trend = engine.calculate(
        OpenTasksFixtures.snapshot.copy(tasks = tasks),
        InsightsSelection(InsightsRange.SEVEN_DAYS),
        now,
        zone,
    ).completionTrend

    assertEquals(3L, trend.sumOf { it.completed })
    assertEquals(1L, trend.single { it.date == LocalDate.of(2026, 3, 8) }.completed)
    assertEquals(1L, trend.first().completed)
    assertEquals(1L, trend.last().completed)
}
```

Add a third test using one selected project and tag; assert the trend and the
existing `completed.current` both count the same matching tasks. Include a
deleted completed task and pin its current existing inclusion in both values.

- [ ] **Step 2: Run the RED unit test**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.InsightsEngineTest"
```

Expected: `completionTrend` assertions fail to compile.

- [ ] **Step 3: Compute one point per selected local day**

Append the model default last, then compute from the already-filtered task list:

```kotlin
data class CompletionTrendPoint(
    val date: LocalDate,
    val completed: Long,
)

data class InsightsSnapshot(
    // Keep every existing field in its current order.
    val quality: InsightsQuality,
    val completionTrend: List<CompletionTrendPoint> = emptyList(),
)
```

```kotlin
val completionCounts = selectedTasks.asSequence()
    .mapNotNull(Task::completedAt)
    .filter(interval::contains)
    .groupingBy { it.atZone(zoneId).toLocalDate() }
    .eachCount()
val completionTrend = generateSequence(startDate) { date ->
    date.plusDays(1).takeUnless { it.isAfter(currentDate) }
}.map { date ->
    CompletionTrendPoint(date, completionCounts[date]?.toLong() ?: 0L)
}.toList()
```

Pass `completionTrend` into `InsightsSnapshot`. Do not use display strings or
fixed 24-hour durations.

- [ ] **Step 4: Add chart and table presentations**

Add resources with no hardcoded new copy:

```xml
<string name="insights_completion_trend_heading">Completions per day</string>
<plurals name="insights_completion_trend_summary">
    <item quantity="one">%1$d completion from %2$s to %3$s</item>
    <item quantity="other">%1$d completions from %2$s to %3$s</item>
</plurals>
<plurals name="insights_completion_day_count">
    <item quantity="one">%1$d task</item>
    <item quantity="other">%1$d tasks</item>
</plurals>
```

Guard the compatibility default before reading first/last:

```kotlin
if (snapshot.completionTrend.isNotEmpty()) {
    CompletionTrendChart(snapshot.completionTrend)
}
```

The chart implementation is:

```kotlin
@Composable
private fun CompletionTrendChart(points: List<CompletionTrendPoint>) {
    if (points.isEmpty()) return
    val total = points.sumOf(CompletionTrendPoint::completed)
    val firstDate = formatInsightsDate(points.first().date)
    val lastDate = formatInsightsDate(points.last().date)
    val summary = pluralStringResource(
        R.plurals.insights_completion_trend_summary,
        total.toInt(),
        total,
        firstDate,
        lastDate,
    )
    Column(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = summary
        },
    ) {
        Text(stringResource(R.string.insights_completion_trend_heading))
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("insights-completion-trend-scroll"),
        ) {
            DottedAreaChart(
                values = points.map { it.completed.toFloat() },
                modifier = Modifier
                    .width((points.size * 20).dp)
                    .height(160.dp),
            )
        }
    }
}
```

After the Completed section in table mode, use the same non-empty guard and:

```kotlin
TableSectionHeader(stringResource(R.string.insights_completion_trend_heading))
snapshot.completionTrend.forEach { point ->
    DataRow(
        label = formatInsightsDate(point.date),
        value = pluralStringResource(
            R.plurals.insights_completion_day_count,
            point.completed.toInt(),
            point.completed,
        ),
        modifier = Modifier.testTag("insights-completion-day-${point.date}"),
    )
}
```

Add `private fun formatInsightsDate(date: LocalDate): String =
INSIGHTS_DATE_FORMAT.format(date)`. Keep the selector and all existing sections.

- [ ] **Step 5: Add Compose coverage**

Add four exact cases:

```kotlin
@Test
fun defaultEmptyCompletionTrendRendersExistingFixturesWithoutATrendSection() {
    composeRule.setContent { OpenTasksTheme { TestInsightsScreen(populatedState()) } }
    composeRule.onNodeWithTag("insights-completion-trend-scroll").assertDoesNotExist()
}

@Test
fun ninetyDayTrendScrollsAndKeepsDotsDecorative() {
    composeRule.setContent { OpenTasksTheme { TestInsightsScreen(trendState(90)) } }
    composeRule.onNodeWithTag(
        "insights-completion-trend-scroll",
        useUnmergedTree = true,
    ).assert(hasScrollAction())
    composeRule.onNodeWithTag("dotted-area-chart", useUnmergedTree = true).assertExists()
}
```

Also assert the 7-day chart has one summary description and table mode has
exactly `insights-completion-day-2026-08-04` through
`insights-completion-day-2026-08-10`, including a `0 tasks` row.
`trendState(dayCount)` constructs ascending points ending on 10 August 2026 and
otherwise reuses `populatedState()`.

- [ ] **Step 6: Run focused verification**

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests "app.opentasks.core.domain.InsightsEngineTest"
./gradlew :feature:more:compileDebugAndroidTestKotlin
git diff --check
```

Expected: engine tests pass and the feature device tests compile.

- [ ] **Step 7: Stage only the six trend paths**

```bash
git add core/model/src/main/kotlin/app/opentasks/core/model/Insights.kt \
  core/domain/src/main/kotlin/app/opentasks/core/domain/InsightsEngine.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/InsightsEngineTest.kt \
  feature/more/src/main/kotlin/app/opentasks/feature/more/InsightsScreen.kt \
  feature/more/src/main/res/values/strings.xml \
  feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt
git diff --cached --name-only
```

Expected: exactly the six listed paths are staged.

- [ ] **Step 8: Commit the completion trend**

```bash
git commit -m "feat: chart daily completion trends"
```

Expected: one commit containing only those six paths.

---

### Task 18: Qualify Stage 7 and cut signed sideload release 1.1.0

**Files:**

- Modify: `app/build.gradle.kts` (`android.defaultConfig` version fields)
- Modify: `docs/architecture.md` (new Stage 7 architecture section)
- Modify: `DESIGN.md` (`Colour` and new Stage 7 ergonomics section)
- Modify: `PRODUCT.md` (`Current Delivery Boundary`)
- Modify: `docs/threat-model.md` (new Stage 7 addendum and acceptance gates)
- Modify: `CLAUDE.md` (`Architecture rules`, `Data and security`, and bounds)
- Modify: `HANDOFF.md` (top status, live backlog/F6, and Stage 7 checkpoint)
- Create: `docs/qualification/stage7-ergonomics-sweep.md`
- Create: `docs/qualification/release-1.1.0-sideload.md`

**Interfaces:**

- Consumes: Tasks 1–17, the Stage 7 implementation-base SHA recorded after
  the Phase 0 prerequisite and dependency gate,
  the roadmap's uniform exit gates, the approved Stage 7 exit criteria, and
  `RELEASING.md`.
- Produces: `versionCode = 2`, `versionName = "1.1.0"`, a qualification record
  keyed by the real pre-qualification `implementationHeadSha`, a verified
  signed APK, an annotated `v1.1.0` tag, a post-tag HANDOFF-only closure commit,
  and zero Room/backup-format drift.

- [ ] **Step 1: Record the implementation head and pin the release version**

Before editing, run and record the value as `implementationHeadSha` in the
private execution ledger:

```bash
set -euo pipefail
implementation_head_sha="$(git rev-parse HEAD)"
test "${#implementation_head_sha}" -eq 40
git cat-file -e "${implementation_head_sha}^{commit}"
git status --short --branch
```

This is the Tasks 1–17 head, not the future qualification commit's impossible
self-SHA. Change only:

```kotlin
versionCode = 2
versionName = "1.1.0"
```

- [ ] **Step 2: Compile all six connected-test sources**

```bash
./gradlew :app:compileDebugAndroidTestKotlin \
  :core:data:compileDebugAndroidTestKotlin \
  :feature:tasks:compileDebugAndroidTestKotlin \
  :feature:projects:compileDebugAndroidTestKotlin \
  :feature:schedule:compileDebugAndroidTestKotlin \
  :feature:more:compileDebugAndroidTestKotlin
```

Expected: all six modules compile. Fix failures; do not waive or delete tests.

- [ ] **Step 3: Run the full local CI gate once**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --rerun-tasks
```

Expected: the task's only full JVM-unit run passes with lint and debug assembly.

- [ ] **Step 4: Build and verify the signed release separately**

Require the gitignored `keystore.properties` and external release keystore
from `RELEASING.md` without printing either secret. Run:

```bash
./gradlew :app:assembleRelease --rerun-tasks
bash scripts/verify-release-apk.sh
```

Expected: release verifier prints
`verify-release-apk: all checks passed`; APK version is 1.1.0 (2), signed with
the established certificate, R8-minified, and contains no debug qualification
activity. A missing/invalid signing setup stops release qualification and tag
creation without weakening the verifier.

- [ ] **Step 5: Commit the verified version bump**

```bash
git diff --check
git add app/build.gradle.kts
test "$(git diff --cached --name-only)" = app/build.gradle.kts
git commit -m "build: set version 1.1.0"
```

Expected: the verified version bump is its own one-file commit.

- [ ] **Step 6: Run the no-durable-change and deterministic-fixture gates**

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

Expected: Room remains v9, every generator is byte-identical, workflow pinning
passes, and no generated schema/fixture diff exists. Saved-view v2 recovery
tests prove v1/v2 visible and v3 retained/invisible; no new fixture is needed.

- [ ] **Step 7: Audit the exact stage diff for privacy and release scope**

Read the implementation-base SHA from the execution ledger, verify it is a
non-empty ancestor of HEAD, and use that literal SHA in the following read-only
audits. Do not infer the base from dates or commit count.

Audit added Kotlin lines for `Log.`, `println`, and `Timber`—expected zero.
Inspect every `SharedPreferences` addition—expected only enum names and project
ids in `view_prefs`, with no title/query/tag text. Inspect saved-view v2—expected
only encrypted payload-column bytes and no plaintext log/export. Inspect Quick
Add saved state—expected only transient Compose state, no disk preference or
telemetry. Inspect theme changes—expected deletion only, no preference key.
Inspect duplication—expected ordinary existing backup families only.

Run one Bash shell so the checked variable cannot go stale. Audit all 13
manifests, the sole backup-family enum, and endpoint-like additions:

```bash
set -euo pipefail
ledger=.superpowers/sdd/2026-08-10-stage-7-ergonomics-sweep-plan/progress.md
test -f "$ledger"
stage7_base_sha="$(rg -o -m 1 '[0-9a-f]{40}' "$ledger")"
case "$stage7_base_sha" in
  ''|*[!0-9a-f]*) exit 1 ;;
esac
test "${#stage7_base_sha}" -eq 40
git cat-file -e "${stage7_base_sha}^{commit}"
git merge-base --is-ancestor "$stage7_base_sha" HEAD
git diff --name-only "$stage7_base_sha"..HEAD
if git diff --unified=0 "$stage7_base_sha"..HEAD -- '*.kt' | \
  rg '^\+.*(android\.util\.Log|Log\.|println\(|Timber\.)'; then
  exit 1
fi
git diff --exit-code "$stage7_base_sha"..HEAD -- core/data/schemas
git diff --exit-code "$stage7_base_sha"..HEAD -- \
  core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordV1.kt
manifest_count=0
while IFS= read -r manifest; do
  manifest_count=$((manifest_count + 1))
  git diff --exit-code "$stage7_base_sha"..HEAD -- "$manifest"
  rg -n '<uses-permission|android:exported|<intent-filter|<provider|<service|<receiver' \
    "$manifest" || true
done < <(rg --files -g 'AndroidManifest.xml' | sort)
test "$manifest_count" -eq 13
if git diff --unified=0 "$stage7_base_sha"..HEAD -- '*.kt' '*.xml' | \
  rg '^\+.*(https?://|DRIVE_ORIGIN|BASE_URL|baseUrl|endpoint|drive\.appdata)'; then
  exit 1
fi
rg -n "view_prefs|tasks_sort|tasks_group|workbench_sort|workbench_group|board_sort" \
  app/src/main
rg -n 'enum class BackupRecordFamily|SAVED_VIEW|TASK_TAG|TASK_DEPENDENCY' \
  core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordV1.kt
```

Expected: base validation passes; all 13 manifests, schemas, and
`BackupRecordFamily` are unchanged; added logging/endpoint scans have no match;
`view_prefs` contains enum names/project UUIDs only. Record that saved-view v2
uses its encrypted payload column, Quick Add state is transient, theme changes
delete state, and duplication emits only existing families.

- [ ] **Step 8: Dispatch the whole-stage independent review**

Use `superpowers:requesting-code-review` with the approved spec, this plan, and
the exact validated `stage7_base_sha..HEAD` diff. Require the reviewer to check behaviour,
security/privacy, accessibility, dual-engine parity, schema/backup invariants,
and test evidence. Record every finding and disposition.

- [ ] **Step 9: Test, stage, commit, and re-review every blocking fix**

Before editing, add each review fix to the private ledger with literal paths
and its focused test. Run that focused test plus:

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

Stage only those literal paths, audit `git diff --cached --name-only`, commit
with `git commit -m "fix: resolve stage 7 review findings"`, and dispatch a
fresh follow-up review of the fix commit. Repeat to zero Critical/Important,
then run `git rev-parse HEAD`, record that final reviewed value as the refreshed
`implementationHeadSha` in the private ledger, and rerun Steps 4, 6, and 7. No
review fix remains untested, uncommitted, or accepted without independent
re-review.

- [ ] **Step 10: Start and audit the sole disposable AVD**

```bash
set -euo pipefail
stage7_adb=/Users/kk/Library/Android/sdk/platform-tools/adb
stage7_emulator=/Users/kk/Library/Android/sdk/emulator/emulator
test -x "$stage7_adb"
test -x "$stage7_emulator"
test -z "$("$stage7_adb" devices | sed -n '2,$p' | sed '/^$/d')"
if ps -Ao command | rg '[e]mulator.*(Fold8_Acceptance|Pixel_10_Pro_Fold)'; then
  exit 1
fi
"$stage7_emulator" -avd Fold8_Acceptance \
  -read-only -no-snapshot-load -no-snapshot-save -no-window \
  >/tmp/stage7-fold8-emulator.log 2>&1 &
stage7_emulator_pid=$!
test "$stage7_emulator_pid" -gt 0
"$stage7_adb" wait-for-device
stage7_serial="$("$stage7_adb" devices | sed -n '2p' | awk '$2 == "device" { print $1 }')"
test -n "$stage7_serial"
test "$("$stage7_adb" devices | awk '$2 == "device" { count++ } END { print count + 0 }')" -eq 1
test "$("$stage7_adb" -s "$stage7_serial" emu avd name | sed -n '1p')" = Fold8_Acceptance
```

Record the temporary serial/PID only in the private ledger. Abort on any
mismatch; never boot, install to, or kill `Pixel_10_Pro_Fold`. Keep this
read-only overlay alive through Step 14.

- [ ] **Step 11: Run the six-module connected gate**

```bash
./gradlew :app:connectedDebugAndroidTest \
  :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest
```

Expected: all six modules pass, including date buckets/chips, arrangement
controls, arrangement persistence, strict v1/v2 recovery, Room search/create/
duplicate parity, dark-configuration light theme, Quick Add grammar/root
wiring/restoration, duplication entry points, dot bars, trend chart/table, and
all pre-existing suites. Record exact executed/pass/skip counts and the existing
Stage 4/5 expected skips; any new skip or failure blocks release.

- [ ] **Step 12: Execute the Stage 7 manual checklist on the disposable**

On the debug build:

1. Seed tasks at overdue, today, later-this-week, later, and no-date boundaries;
   prove Today/Upcoming/Overdue exactly match the pinned buckets and exclude
   completed/Bin tasks.
2. Set each Tasks sort/group, restart the process, and prove persistence; repeat
   workbench sort/group and board Priority/Due/Title for two projects, proving
   per-project isolation and unchanged board columns/move/drag.
3. Save an urgent-this-week blank-text view, refine its text without losing
   filters/active identity, clear it explicitly, restart, and prove the view
   persists.
4. From FAB, share/PROCESS_TEXT prefill, Quick Settings tile, widget, and
   launcher shortcut, enter grammar covering project, two tags including one
   new, numeric priority, recurrence, estimate, and date. Prove nothing applies
   before tap, dismiss/re-edit revival, and one atomic task with one Undo.
5. Put the device in dark configuration and prove main, initialising, app-lock,
   and recovery surfaces remain the light Ember scheme with readable contrast.
6. Duplicate one open task from its board card menu and one completed task from
   task detail; prove exact status rules, unticked fresh checklist, copied tags/
   outgoing dependencies, excluded reminder/repeat/history/time/attachments,
   and Undo moves only the copy to Bin.
7. In Insights for 7/30/90 days, prove dot-run metrics, horizontally scroll the
   90-day completion trend, switch to table and match daily counts. With TalkBack,
   confirm labels/values are announced once and decorative dots are silent.

Record observed outcomes and screenshots/artefact paths without task text,
account data, device serial, Drive ids, or secrets.

- [ ] **Step 13: Install the signed release on the audited overlay**

```bash
set -euo pipefail
stage7_adb=/Users/kk/Library/Android/sdk/platform-tools/adb
stage7_serial="$("$stage7_adb" devices | sed -n '2p' | awk '$2 == "device" { print $1 }')"
test -n "$stage7_serial"
test "$("$stage7_adb" -s "$stage7_serial" emu avd name | sed -n '1p')" = Fold8_Acceptance
"$stage7_adb" -s "$stage7_serial" uninstall app.opentasks
"$stage7_adb" -s "$stage7_serial" install \
  app/build/outputs/apk/release/app-release.apk
```

Expected: both commands succeed only on the disposable overlay.

- [ ] **Step 14: Run the signed-release smoke checklist**

Execute all seven `RELEASING.md` smoke steps, then one saved filter, grammar
capture, duplication/Undo, and Insights trend toggle. Record pass/fail in
`docs/qualification/release-1.1.0-sideload.md`; any failure blocks tagging.

- [ ] **Step 15: Destroy only the audited disposable overlay**

```bash
set -euo pipefail
stage7_adb=/Users/kk/Library/Android/sdk/platform-tools/adb
stage7_serial="$("$stage7_adb" devices | sed -n '2p' | awk '$2 == "device" { print $1 }')"
test -n "$stage7_serial"
test "$("$stage7_adb" -s "$stage7_serial" emu avd name | sed -n '1p')" = Fold8_Acceptance
"$stage7_adb" -s "$stage7_serial" emu kill
while "$stage7_adb" devices | awk '$2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; do
  sleep 2
done
test -z "$("$stage7_adb" devices | sed -n '2,$p' | sed '/^$/d')"
if ps -Ao command | rg '[e]mulator.*Fold8_Acceptance'; then exit 1; fi
```

Expected: no ADB target or disposable process remains. Physical-device install
remains the owner-controlled final step in `RELEASING.md`.

- [ ] **Step 16: Update contract and qualification documents**

Write `docs/qualification/stage7-ergonomics-sweep.md` with the implementation
base SHA and recorded `implementationHeadSha` (never a self-referential final
SHA), toolchain versions, focused/full/connected counts, schema/fixture/workflow/
privacy/release-scope evidence, saved-view v1/v2/v3 evidence, independent-review
findings/dispositions, manual checklist, and zero Critical/Important verdict.
Write `docs/qualification/release-1.1.0-sideload.md` with verifier and release
smoke evidence, never secrets/private identifiers.

Update:

- `docs/architecture.md`: model/domain/app/feature projection seams,
  `view_prefs`, saved payload v2, shared search, atomic create/duplicate, and
  Insights trend; Room/backup still v9/v1.
- `DESIGN.md`: sort/group controls, saved-filter chips, confirm-only grammar,
  light-only ruling, duplicate actions, dot bars/trend and accessibility.
- `PRODUCT.md`: Stage 7 user-visible boundary and deliberate ceilings.
- `docs/threat-model.md`: bounded untrusted grammar/payload decode,
  device-local non-vault preferences, no reminder duplication, no new external
  surface/permission/network path.
- `CLAUDE.md`: stable saved-view versions/bounds, arrangement preference
  privacy, Quick Add limits, shared comparator/search authority, light-only
  theme, exact duplication exclusions, and no Room/backup change.
- `HANDOFF.md`: locally qualified candidate; remote CI and tag pending; exact
  gates; Stage 8/9 backlog; and F6 observe-only with the exact reason
  **credential-encrypted storage unavailable**. Do not claim `v1.1.0` exists.

- [ ] **Step 17: Commit the pre-tag qualification documents**

The version bump is already committed. Stage exactly these eight docs:

```bash
git add docs/architecture.md DESIGN.md PRODUCT.md \
  docs/threat-model.md CLAUDE.md HANDOFF.md \
  docs/qualification/stage7-ergonomics-sweep.md \
  docs/qualification/release-1.1.0-sideload.md
git diff --cached --name-only
git diff --cached --check
git commit -m "docs: qualify release 1.1.0 candidate"
```

Expected staged-name output is exactly those eight paths; it excludes generated
schemas/fixtures, keystore files, `.kotlin/`, `artifacts/`, the historical Stage
3 plan, and the ignored execution ledger.

- [ ] **Step 18: Push the candidate and wait for its exact GitHub run**

```bash
set -euo pipefail
candidate_sha="$(git rev-parse HEAD)"
git push origin main
run_id="$(gh run list --workflow Android --branch main --event push --limit 20 \
  --json databaseId,headSha --jq \
  "[.[] | select(.headSha == \"$candidate_sha\")][0].databaseId")"
case "$run_id" in ''|null|*[!0-9]*) exit 1 ;; esac
while test "$(gh run view "$run_id" --json status --jq .status)" != completed; do
  sleep 10
done
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

Expected: verify, compact API 36, and release are green. Zero-runner billing or
any required red job blocks tagging. Expanded API 37.0 is observe-only; if red,
record F6 exactly as **credential-encrypted storage unavailable**.

- [ ] **Step 19: Create and push the annotated tag**

```bash
git tag -a v1.1.0 -m "Release 1.1.0"
git push origin v1.1.0
test "$(git rev-list -n 1 v1.1.0)" = "$(git rev-parse HEAD)"
```

Expected: `v1.1.0` points at the remotely qualified candidate commit.

- [ ] **Step 20: Replace candidate wording in HANDOFF**

Record the actual candidate SHA, workflow run id, required green jobs, tag,
remaining Stage 8/9 work, and F6's exact observe-only reason:
**credential-encrypted storage unavailable**.

- [ ] **Step 21: Commit and push the post-tag HANDOFF closure**

```bash
git add HANDOFF.md
test "$(git diff --cached --name-only)" = HANDOFF.md
git diff --cached --check
git commit -m "docs: record release 1.1.0 handoff"
git push origin main
git status --short --branch
```

Expected: the final commit is HANDOFF-only and occurs after the tag; `main`
matches `origin/main`; only the known user-owned Stage 3 plan edit, `.kotlin/`,
and `artifacts/` remain uncommitted.

---

## Spec Coverage Map

| Approved requirement | Tasks |
|---|---:|
| Date-chip prerequisite; shared half-open, zone-aware buckets | 1 |
| Single comparator authority and id-order engine parity | 2 |
| Durable global/per-project arrangement preferences | 3 |
| Tasks sort/group UI | 4 |
| Workbench sort/group UI | 5 |
| Board in-column sort with unchanged columns | 2, 6 |
| SearchQuery v2 and strict v1/v2/future decode/recovery | 7 |
| Shared search filters, ranking, cap, and sort composition | 8 |
| Saved-view identity refinement and filter controls | 9 |
| Multi-token grammar, precedence, anchoring, and strip helper | 10 |
| Atomic enriched CreateTask in both engines | 11 |
| Confirm-only Quick Add on every intake path | 12 |
| Light-only Compose, widget, and platform resources; no preference/storage | 13 |
| Exact atomic duplicate command/Undo and relation-only tag copy in both engines | 14 |
| Detail and board duplication entry points | 15 |
| Reusable dot-run/dotted-area primitives and metric restyle | 16 |
| Zone-aware 7/30/90 completion trend and table parity | 17 |
| No Room/backup change, full gates, fix re-review, candidate CI, tag, post-tag handoff | 18 |

## Deliberate Ceilings Preserved

- No sort-direction toggle, workbench Project grouping, board Updated sort, or
  manual board rank.
- No multi-word tags or weekday lists in Quick Add grammar; no project/tag
  creation from `#project`; only `@tag` may get-or-create.
- No dark theme or theme preference.
- No recurrence/reminder/activity/time/attachment copying.
- No Home/Schedule comparator rewrite, deleted-project preference cleanup,
  Room schema change, backup-format change, new dependency, permission,
  exported component, network path, or telemetry.
