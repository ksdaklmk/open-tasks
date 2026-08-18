# Stage 9 board-flow-automation qualification (Task 17, Steps 1-3)

## Status and source identity

**Qualified — Steps 1-3 gates are green at HEAD.** Steps 0-2 (version
bump, forced-fresh host gates, determinism/scope scans) were clean
from the first pass. Step 3a's seven-module connected gate on
`Fold8_Acceptance` originally surfaced 9 failures in
`RoomVaultRepositoryInstrumentedTest` (including both named
Room-automation twins) plus 2 related failures in
`BackupRecordImporterInstrumentedTest` and 1 unrelated failure in
`BoardViewInstrumentedTest`. All three findings are now resolved:
`BoardViewInstrumentedTest` was diagnosed and fixed in Step 3a itself
(test-only, `clearAndSetSemantics` pre-dating this stage); the
`:core:data` cluster was diagnosed as a test-pattern defect and fixed
in fix round 1 (Ruling U, controller-verified against the test
source) — every failing test asserted on a naked
`repository!!.currentWorkspace()` immediately after `execute()`
instead of awaiting the documented decoupled-collector propagation
(progress.md line 102). Fix round 1 converted all nine tests to the
file's own established await pattern and fixed the
`BackupRecordImporterInstrumentedTest` argument-order bug, preserving
every assertion. The full `:core:data:connectedDebugAndroidTest`
module re-ran clean: 212/212, 0 failures.

This document covers Steps 1-3 only (qualification gates and
evidence). Step 4 (whole-stage review) is the controller's job. Steps
5-6 (manual acceptance matrix, signed sideload, tag) belong to the
owner session and are left as placeholders below.

- Date: 19 August 2026
- Audit base (`Audit-base` in the plan's ledger):
  `8d70c9638c9168c7466afd52c65035e8ae95139c`
- Pre-existing implementation head (start of this task):
  `73b6655bbebcc1eb3422f1af9d937800ac3afa1b`
- Step 0 version-bump commit: `21e3926bcff9b7a4e6df4d3998f6a6403370696a`
  (`chore: bump to 1.3.0 for the stage 9 sideload release`)
- Step 1, Step 2, and the first Step 3a attempt (all modules) ran at
  `21e3926`.
- Mid-Step-3a test-only fix commit:
  `7e7dfb6e1418579ad15d1774bb30d86855522b27`
  (`test: fix drag-preview rollup-chip count for cleared semantics`,
  `feature/projects/src/androidTest/.../BoardViewInstrumentedTest.kt`
  only)
- Step 3b (all four module-matched invocations) ran at `7e7dfb6`.
- Fix round 1 commit:
  `d01c95055210a0b00e01ea04ced9bba95cf7a352`
  (`test: await workspace propagation in stage 9 room twins`,
  `RoomVaultRepositoryInstrumentedTest.kt` and
  `BackupRecordImporterInstrumentedTest.kt` only)
- Current HEAD (the fix round 1 verification ran here):
  `d01c95055210a0b00e01ea04ced9bba95cf7a352`
- `git diff --stat 21e3926..HEAD` touches exactly three files
  (`BoardViewInstrumentedTest.kt`, `RoomVaultRepositoryInstrumentedTest.kt`,
  `BackupRecordImporterInstrumentedTest.kt`, all `androidTest` sources
  only), so every other module's Step 3a/3b evidence recorded at
  `21e3926`/`7e7dfb6` remains an accurate description of the current
  HEAD — none of those modules' production or test sources changed.
- Candidate version: versionName 1.3.0, versionCode 4

This record contains no account identifier, device serial, process
id, credential, private task text, record UUID, Drive object id, or
other private identifier. The protected `Pixel_10_Pro_Fold` AVD was
never booted, installed to, or mutated during this qualification.

## Step 1 — forced-fresh host gates

Both invocations ran separately, foreground, at `21e3926`.

```
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --rerun-tasks
```

BUILD SUCCESSFUL in 1m 29s. 553 actionable tasks, 553 executed (all
forced). 1,355 unit tests, 0 failures, 0 errors, 0 skipped. 0 lint
issues across all 12 modules (`lint-results-debug.xml`, `<issue`
count).

```
./gradlew :app:assembleRelease --rerun-tasks
```

BUILD SUCCESSFUL in 1m 4s. 442 actionable tasks, 442 executed (all
forced). `keystore.properties` is present, so this is a real signed,
minified release build: `app/build/outputs/apk/release/app-release.apk`,
16,595,235 bytes. The debug APK from the first command also exists
(`app/build/outputs/apk/debug/app-debug.apk`, 96,625,996 bytes).

## Step 2 — determinism and scope scans

- `./scripts/check-schema-drift.sh` — ran to completion in the
  foreground (regenerated `10.json` via a forced
  `:core:data:kspDebugKotlin`, 6s, 38/38 tasks). Output: "No schema
  drift: ... matches the current Room entities." Immediately after,
  `git status --porcelain core/data/schemas` was empty — nothing was
  interrupted, no restore was needed.
- `node scripts/generate-stage2-backup-v1-fixtures.mjs && git diff
  --exit-code core/data/src/test/resources` — exit 0, no diff.
  Regeneration is byte-identical against the checked-in fixtures.
- `bash scripts/verify-actions-workflow.sh` — exit 0, no output
  (pins and CI matrix shape intact). File mode confirmed unchanged
  at 644; not `chmod`-ed.
- Schemas 1-9 are untouched over `8d70c96..HEAD`
  (`git diff --stat` on `core/data/schemas/` shows only `10.json`,
  2,190 insertions, 0 deletions — a new file, not a modification of
  any prior version).

**Scope scan** (`git diff --stat 8d70c96..HEAD`, evaluated after Step
0's commit, before the Step 3a fix commit): 79 files changed, 11,482
insertions, 207 deletions. Disposition:

- 75 files map directly onto either the plan's global File Structure
  section, a per-task `**Files:**` list (including the deliberately
  generic "Test: feature/&lt;module&gt; Compose test source set"
  entries the plan uses for Tasks 10-13, 15, 16, which this scan
  accepted for any new androidTest class in the named module/feature
  area), or one of the brief's pre-approved exceptions
  (`WorkflowWipLimit.kt`, `feature/home/build.gradle.kts`,
  `HideWindowsRule.kt`'s sixth copy, `HANDOFF.md`, the three pre-17
  repair test files, `app/build.gradle.kts`, and this record itself).
- 4 files are **not** literally named in the plan's File Structure or
  any per-task Files list, but match Task 3's ledger-recorded,
  reviewed rulings exactly (verified by reading each diff against the
  ledger text, not merely by name):
  `core/data/.../backup/BackupPayloadCodec.kt` (progress.md line 57/60
  — AUTOMATION_RULE joined `validateWorkspaceOwnedRecords`, MY_DAY
  task-existence check),
  `core/data/.../backup/StagedVaultVerifier.kt` (line 54 — verifier
  extended for the two new families' capture/retention accounting),
  `core/data/src/test/.../backup/BackupSnapshotCodecTest.kt` (line 60
  — contains `automationRuleRecordRequiresItsWorkspaceToExistInTheSnapshot`
  exactly as named), and
  `core/data/src/androidTest/.../backup/BackupRecordImporterInstrumentedTest.kt`
  (line 54 — "add both families to ... per-family fixture helper").
  This was reported as a scope-scan finding per the brief's "report
  it, do not wave it through," not silently accepted. **Ruling V
  (controller): all four are legitimate** — each matches its cited
  Task 3 ledger ruling (progress.md lines 54/57/60) exactly, verified
  by content, not merely by name; no further action needed.
- 0 files are unexplained.

**Grep gates.** No added `Color(0x` outside `core/designsystem`
anywhere in `8d70c96..HEAD`. `grep -rn "Log\." app core feature
--include="*.kt"` finds 18 lines across 6 copies of
`HideWindowsRule.kt` (one per instrumented-test module); `git diff
8d70c96..HEAD | grep "^+.*Log\."` shows only 3 of those lines were
*added* in this range (one `Log.i`, two `Log.w`), all in
`feature/home`'s new sixth copy — restating, not re-flagging, Stage
8's narrowing of this rule to production sources.

**Frozen fixture directories** — all 5 confirmed untouched via
`git diff --exit-code 8d70c96..HEAD` on each: `backup-format/otvault-v1`,
`backup-format/drive-create-only-v1`, `backup-format/attachment-v1`,
`cloud-format/v1-authenticated` (core/data), `cloud-format` (core/sync).
`backup-format/v1/operation-segment.json` is also untouched; only
`portable-package.json` and `snapshot.json` changed in-range, matching
the brief's expectation exactly.

## Step 3 — connected gates

Device rules followed throughout: exactly one emulator at a time,
`Pixel_10_Pro_Fold` never booted, each emulator killed with `adb emu
kill` before booting the next and before finishing. `adb devices -l`
confirmed a sole target after each boot; `adb emu avd name` confirmed
identity after each boot. The three animation scales were set to 0
after each boot. Per-module counts below are XML-authoritative
(`<module>/build/outputs/androidTest-results/connected/debug/`).

### Step 3a — seven-module full gate on Fold8_Acceptance

Booted `-read-only -no-snapshot-load -no-snapshot-save -no-window
-gpu host` per the mandatory Stage 8 procedure. Identity confirmed
`Fold8_Acceptance` via `adb emu avd name`; sole ADB target; `wm size`
2160x1856, `wm density` 420 (matches the expanded/unfolded profile).
Display-0 canary: `am start ... --display 0` launched Settings, which
became `topResumedActivity` with no launch error, before animation
scales were disabled.

```
./gradlew :app:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest :feature:more:connectedDebugAndroidTest \
  :feature:home:connectedDebugAndroidTest
```

First attempt, at `21e3926`: BUILD FAILED in 7m 51s (488 actionable
tasks). XML-authoritative per-module totals:

| Module | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| `:app` | 92 | 0 | 0 | 2 |
| `:core:data` | 212 | 11 (0 after fix round 1, see below) | 0 | 0 |
| `:feature:tasks` | 52 | 0 | 0 | 0 |
| `:feature:projects` | 37 | 1 (0 after the same-day fix below) | 0 | 0 |
| `:feature:schedule` | 23 | 0 | 0 | 0 |
| `:feature:more` | 71 | 0 | 0 | 0 |
| `:feature:home` | 6 | 0 | 0 | 0 |
| **Total** | **493** | **12** | **0** | **2** |

`:core:data`'s 11 failures were resolved by fix round 1
(`d01c950`): the full module re-ran 212/212, 0 failures — see
"Fix round 1" below.

The two `:app` skips are exactly the established pair (verified by
name from the XML):
`DriveCreateOnlyQualificationPackagingInstrumentedTest#explicitCredentialedArgumentLaunchesInternalQualificationAndRequiresBoundedPass`
and `FoldContinuityInstrumentedTest#draftAndSelectionSurviveFoldTransition`.
`:feature:home` (new this task) is clean on its first-ever execution:
6/6, covering `HomeScreenInstrumentedTest` and
`MyDayPlanSheetInstrumentedTest`. `:feature:schedule` reproduced the
qualified 23/23 baseline the brief cites. The migration test,
`VaultDatabaseMigrationInstrumentedTest`, is clean: 10/10, 0 failures
— see the named rows below.

**Finding 1 — `:core:data`, 11 failures, diagnosed, fixed in fix
round 1, re-verified green (test-pattern defect, Ruling U).** All 11
were in two classes:

`RoomVaultRepositoryInstrumentedTest` (73 tests, 9 failures) —
`myDayBoundBinFilterAndSweep`,
`automationRuleCreateRejectsIdCollisionAndDeleteIsIdempotent`,
`addToMyDayRejectsBinnedTasksAndTheBound`, `wipLimitSetClearValidateAndUndo`,
`addRemoveAndReorderMyDayEntries`,
`automationMyDayOutputUndoReplaysAndExclusionsNeverFire` (named test 2),
`automationRuleCrudRoundTripsWithUndo`,
`automationRulesFireInsideTheTriggerGenerationAndComposeOneUndo`
(named test 1), `automationRuleValidationRejectsBadConfigMissingRefsAndTheBound`.

`BackupRecordImporterInstrumentedTest` (37 tests, 2 failures) —
`importedNullableColumnsStayNull`, `importedRowsHoldTheExactAuthenticatedValues`.

Per the brief's Ruling M-derived instruction, the whole
`RoomVaultRepositoryInstrumentedTest`+`BackupRecordImporterInstrumentedTest`
pair was re-run once, class-scoped, at the same SHA (`21e3926`), to
classify deterministic-vs-flake:

```
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.opentasks.core.data.RoomVaultRepositoryInstrumentedTest,app.opentasks.core.data.backup.BackupRecordImporterInstrumentedTest
```

BUILD FAILED in 1m 54s. 110/110 tests ran; 10 failures. 8 of the 9
original `RoomVaultRepositoryInstrumentedTest` failures reproduced
identically (same assertion, same expected/actual values) — this
**includes both named risk tests**, both deterministic:
`automationRulesFireInsideTheTriggerGenerationAndComposeOneUndo`
(1.097s then 1.124s, identical `assertTrue` failure at
`RoomVaultRepositoryInstrumentedTest.kt:4140`) and
`automationMyDayOutputUndoReplaysAndExclusionsNeverFire` (1.082s then
1.096s, identical `expected:<[TaskId(value=task-domain)]> but
was:<[]>`). One test,
`automationRuleCreateRejectsIdCollisionAndDeleteIsIdempotent`,
**passed on re-run — flaky, not deterministic**. Both
`BackupRecordImporterInstrumentedTest` failures reproduced
identically (byte-for-byte matching failure text both times).

Every reproduced `RoomVaultRepositoryInstrumentedTest` failure follows
the same shape: a command is `execute()`-d (an automation rule create,
a My Day add/reorder, a WIP-limit set, or a status change that fires
an automation rule inside the trigger transaction), and the very next
statement calls `repository.currentWorkspace()` to assert the written
state — which comes back missing the write, or with one fewer item
than just added. `RoomVaultRepository.currentWorkspace()` returns
`mutableWorkspace.value`, a `StateFlow` fed by a *separate*
`repositoryScope.launch { observeDatabase().collect { ... } }`
coroutine that is architecturally decoupled from `execute()`'s
`writeMutex`/`database.withTransaction` (RoomVaultRepository.kt
lines 175-228; the same lag was already named, for a different
context, by the Task 6 review — progress.md line 102). `automation_rules`,
`my_day_entries`, and `workflow_statuses.wipLimit` are correctly wired
into that same observed `combine()` chain and the DAOs use plain
`SELECT *` (VaultDatabase.kt lines 157-158, 525-526, 537-541; schema
and migration both verified present and correct, including the
`ALTER TABLE workflow_statuses ADD COLUMN wipLimit` in `MIGRATION_9_10`
at VaultDatabase.kt:1309, and confirmed by the clean 10/10 migration
test above) — so this reads as a **read-after-write timing gap
specific to these three Stage 9 write paths**, not a missing
migration, a missing column, or a missing DAO registration. Whether
that gap is a genuine regression in how these paths publish state, or
an existing architectural characteristic that Stage 9 is the first
work to expose through this exact write-then-immediately-read test
pattern, was the open question left for the controller's fix round.
No test or production code in this cluster was modified at this
point in Step 3a — per the brief, this thread stopped here pending a
ruling. **Ruling U (fix round 1, below) settled it: test-pattern, not
production** — every *passing* pre-existing test in this same file
already awaits `observeWorkspace().filterNotNull().first { ... }`
for the expected post-write state before asserting, and only the
nine newly-added Stage 9 tests skipped that step, reading a naked
`currentWorkspace()` immediately after `execute()` instead. The
19-of-20 rules visible in the bound-check failure and the flaky pass
on re-run were both symptoms of the same race, not of missing writes.

The two `BackupRecordImporterInstrumentedTest` failures were a
**separate, test-only defect** (fixed in the same fix round 1
commit since both live in the same module): `assertRow()`'s
column-list check
(`BackupRecordImporterInstrumentedTest.kt:2198-2202`) calls
`assertEquals(message, cursor.columnNames.toSortedSet(),
expected.keys.toSortedSet())` — the real database columns are passed
as JUnit's "expected" parameter and the test's own hand-written map is
passed as "actual", so the failure message prints backwards. Read
correctly, the *real* `workflow_statuses` table **does** carry
`wipLimit` (proving the schema/migration are fine here too); the two
calling tests' own hand-written `expected` maps for `workflow_statuses`
rows were simply never updated to include the `wipLimit` key when Task
1 added the column. Reading both fixture builders
(`oneRecordPerFamilySnapshot()` and `sparseSnapshot()`) confirmed
neither constructs its `WorkflowStatusEntity` with a `wipLimit`
argument, so both default to `null` (`Entities.kt:61`) — the fix adds
`"wipLimit" to null` to both maps rather than a guessed non-null
value.

**Fix round 1 (`d01c950`, authorized by the controller's Ruling U,
two files: `RoomVaultRepositoryInstrumentedTest.kt` and
`BackupRecordImporterInstrumentedTest.kt`).** Converted every
read-after-write assertion in the nine failing tests to the file's
own house await pattern (`withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
repository!!.observeWorkspace().filterNotNull().first { snapshot ->
<predicate> } }`, matching the pre-existing mold at lines 139, 190,
278, 290), capturing the awaited snapshot once and running every
detailed assertion against it — no assertion was weakened, dropped,
or replaced with a looser check. Direct-DAO reads
(`database!!.backupStateDao()...currentGeneration`) were left exactly
as they were, since they read inside the same transaction and are not
subject to the collector lag. Rejection/no-op assertions
(e.g. `automationRuleWorkspaceMismatchIsRejectedOnCreateAndUpdate`'s
existing pattern, and the six-rejection block inside
`automationRuleValidationRejectsBadConfigMissingRefsAndTheBound`) were
left as plain `currentWorkspace()` reads, matching the file's own
existing convention for state that a rejected command never wrote.
The 200-iteration My Day bound loop
(`addToMyDayRejectsBinnedTasksAndTheBound`) reads each newly created
task's id from `(created as CommandResult.Success).undo as
DomainCommand.DeleteTask).taskId` instead of adding 200 per-iteration
awaits — that id is available synchronously from the same command
result and carries no propagation risk of its own, which also keeps
the loop well inside its 30s `DEVICE_TEST_TIMEOUT_MILLIS` budget.
Fixed the `BackupRecordImporterInstrumentedTest` argument-order bug
described above and added the missing `wipLimit` key to both
`workflow_statuses` expected maps.

RED (pre-fix, `21e3926`): 11 failures across the two classes, detailed
above. GREEN (post-fix, `d01c950`):

```
./gradlew :core:data:connectedDebugAndroidTest
```

Booted `Fold8_Acceptance` fresh with the same mandatory flags,
identity/geometry/canary re-verified. Full module (not class-scoped,
per the controller's instruction that the qualification record needs
the whole-module anchor): BUILD SUCCESSFUL in 3m 35s, 125 actionable
tasks. XML-authoritative: **212/212 tests, 0 failures, 0 errors, 0
skipped** — every test that was failing, plus the 201 that were
already passing, all green in the same run. Host gate re-run
(`./gradlew testDebugUnitTest lintDebug :app:assembleDebug`, no
`--rerun-tasks` needed): BUILD SUCCESSFUL in 8s, 553 actionable tasks
(16 executed against the two changed `androidTest` files, 537
up-to-date); 1,355 unit tests, 0 failures (unchanged, no unit-test or
production source touched); 0 lint issues across all 12 modules,
including fresh `core:data` lint analysis of the two edited files.
`:app:assembleRelease` was correctly **not** re-run — `androidTest`
sources cannot enter a release artifact, so Step 1's original
`21e3926` release-gate evidence still stands (Ruling M scoping).
Step 3b needed no re-run: the fix touches only `:core:data`
`androidTest` sources, and none of the ten Step 3b classes live in
that module.

**Finding 2 — `:feature:projects`, 1 failure, diagnosed, fixed,
re-verified green (test-only).**
`BoardViewInstrumentedTest#cardAndDragPreviewShowSubtaskRollupChip`
asserted `onAllNodesWithText(rollupText).assertCountEquals(2)` (one
copy on the source card, one on the drag-preview overlay) but found
only 1. Re-run once, class-scoped, at `21e3926`: BUILD FAILED in 7s,
1/9, identical failure — deterministic, not flaky. `git log -L` on
the drag-preview's modifier chain shows `.clearAndSetSemantics { }`
has wrapped that overlay's subtree since commit `c5c5e11` ("fix:
clear stage 6 device qualification gate"), well before Stage 9 —
it deliberately makes the overlay's own content (including its copy
of the rollup chip) invisible to semantics queries, while its own
`testTag` survives because it sits later in the same modifier chain.
Production code (`BoardView.kt:268-270`) does pass
`subtaskRollup = subtaskRollups[drag.payload.taskId]` into the
preview's `BoardTaskContent`, matching the source card's own binding
at line 222 — this is a test-only assertion incompatible with a
pre-existing, deliberately-established architecture, not a production
gap.

**Fix (one commit, per the process contract):**
`7e7dfb6e1418579ad15d1774bb30d86855522b27` changes the expected count
from 2 to 1 with an explanatory comment, in
`BoardViewInstrumentedTest.kt` only. RED confirmed above (7s, 1/9,
same assertion, twice). GREEN, re-run on both profiles as required:

```
./gradlew :feature:projects:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.opentasks.feature.projects.BoardViewInstrumentedTest
```

Fold8_Acceptance, at `7e7dfb6`: BUILD SUCCESSFUL in 7s, 9/9, 0
failures. Pixel6_Scratch, at `7e7dfb6`, as part of the Step 3b
`:feature:projects` class group below: 25/25, 0 failures (includes
this class).

### Step 3b — ten-class compact gate on Pixel6_Scratch

Booted `-read-only -no-snapshot-load -no-snapshot-save -no-boot-anim
-no-audio`. Identity confirmed `Pixel6_Scratch` via `adb emu avd
name`; sole ADB target; `wm size` 1080x2400, `wm density` 420. All
four module-matched invocations ran at HEAD `7e7dfb6` (after the
Step 3a test-only fix). `:app`'s whole suite was never run on this
AVD, per the brief.

| Module | Classes | Tests | Failures | Errors | Skips |
|---|---|---:|---:|---:|---:|
| `:feature:home` | HomeScreenInstrumentedTest, MyDayPlanSheetInstrumentedTest | 6 | 0 | 0 | 0 |
| `:feature:more` | AutomationsSectionInstrumentedTest | 3 | 0 | 0 | 0 |
| `:feature:projects` | BoardStaleBadgeInstrumentedTest, ProjectWorkbenchStaleBadgeInstrumentedTest, BoardViewInstrumentedTest, ProjectWorkbenchInstrumentedTest | 25 | 0 | 0 | 0 |
| `:feature:tasks` | TaskDetailSubtasksInstrumentedTest, TaskStaleBadgeInstrumentedTest, TasksArrangementInstrumentedTest | 7 | 0 | 0 | 0 |
| **Total** | 10 classes | **41** | **0** | **0** | **0** |

All ten classes ran clean, including `BoardViewInstrumentedTest`
after the fix above. Compact-profile skip counts are the OBSERVED
value (0), recorded as this profile's own result, not asserted
against the expanded profile's established pair.

## Named rows (Ruling O)

Rows 1-2 below record BOTH attempts for full transparency: the
original `21e3926` FAILED result (which triggered Ruling U and fix
round 1) and the `d01c950` PASS result the fix produced. The PASS row
is the one that discharges Task 14 Important 1.

1. `automationRulesFireInsideTheTriggerGenerationAndComposeOneUndo` —
   FAILED at SHA `21e3926`, device `Fold8_Acceptance`, 1.097s (first
   attempt) and 1.124s (re-run), deterministic across both attempts
   (see Finding 1 above). **PASS** at SHA `d01c950`, device
   `Fold8_Acceptance`, runtime 1.115s (full-module re-run).
2. `automationMyDayOutputUndoReplaysAndExclusionsNeverFire` — FAILED
   at SHA `21e3926`, device `Fold8_Acceptance`, 1.082s (first
   attempt) and 1.096s (re-run), deterministic across both attempts
   (see Finding 1 above). **PASS** at SHA `d01c950`, device
   `Fold8_Acceptance`, runtime 0.826s (full-module re-run).
3. `migrate9To10PreservesRowsAddsEmptyTablesAndStampsMarker` — PASS,
   0.027s, SHA `21e3926`, device `Fold8_Acceptance`. Unaffected by
   fix round 1 (unrelated file); re-confirmed PASS at 0.035s in the
   same `d01c950` full-module run.
4. Task 14 Important 1 ("Room evaluation path had no executed
   coverage", progress.md line 197) **is hereby discharged by rows
   1-2**, at SHA `d01c950`. The ruling's own cost analysis is exactly
   what played out: the first device execution (SHA `21e3926`)
   surfaced "a Room-only defect... ships undetected until Task 17 —
   which is exactly when the plan chose to detect it" — except the
   defect was in the *test's* read-after-write pattern rather than in
   Room itself (Ruling U), so the gate did its job by catching a real
   bug, just not the one the deferral's cost analysis had guessed at.
   The `:core:data` module total for the SHA `d01c950` run is the
   required 0-failure anchor: 212 tests, 0 failures, 0 errors. The
   migration path (row 3) was independently clean throughout.

## Carry-forward

Add `:feature:home:connectedDebugAndroidTest` to the CI connected
matrix (`.github/workflows/android.yml`). This module has androidTest
coverage for the first time as of this stage and no existing CI lane
runs it; wiring CI is out of this task's scope.

## Step 5 — manual acceptance matrix

**Placeholder — owner session.** Not run. Blocked behind Step 4
(whole-stage review); the `:core:data` fix round above is complete.

## Step 6 — release record

**Placeholder — owner session.** Not run. No tag, no signed sideload
distribution beyond the Step 1 `:app:assembleRelease` gate evidence
recorded above. `RELEASING.md`'s release steps were not touched by
this task.
