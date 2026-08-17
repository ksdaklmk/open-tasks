# Stage 9 Board Flow and Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the approved Stage 9 wave: the single Room v9→v10 migration
(automation rules, My Day, per-column WIP limits), soft confirm-over-limit
board WIP, one-level subtasks end-to-end, a curated My Day plan on Home,
and automations-lite with repository-internal single-pass evaluation.

**Architecture:** Room moves to v10 as the sole durable boundary of the
stage: two new tables (`automation_rules`, `my_day_entries`), one nullable
`wipLimit` column on `workflow_statuses`, and a stamped vault row marker.
Two new content-fingerprinted backup families (`AUTOMATION_RULE`,
`MY_DAY`) walk the complete Stage 5 checklist; the WORKFLOW_STATUS record
gains an optional trailing `wipLimit` field under dual-arity validation
inside backup format v1. Every mutation stays a `DomainCommand` through
`VaultRepository.execute` with dual-engine parity and repository-produced
Undo. Rule evaluation runs inside both repositories' `execute`, after a
successful status-transition dispatch, in the same transaction and journal
generation, applying outputs through internal dispatch so re-entry is
structurally impossible. Stale is a pure projection; the My Day sweep is
one idempotent command dispatched at the existing foreground reconcile
point. Pure rules live in `core:domain`, projections in `:app`, stateless
rendering in `feature/*`.

**Tech Stack:** The Kotlin/AGP 9, Room + SQLCipher, kotlinx.serialization,
Hilt (`:app` only), Jetpack Compose, JUnit 4, Compose UI test v2, and Node
fixture generators already on `main`. No new dependency, no catalogue
entry, no new permission, scope, or network path.

**Spec:** `docs/superpowers/specs/2026-08-17-stage-9-board-flow-automation-design.md`
(the Stage 9 authority, beneath
`docs/superpowers/specs/2026-08-10-stage-7-9-roadmap-design.md`).

## Global Constraints

- **Start gate:** do not begin Task 1 until (a) `v1.2.0` is tagged on
  `8841fa1` per `HANDOFF.md`, (b) the Dependabot batch (#14–#19) is merged
  with green checks, and (c) the `stage-9` docs branch (spec + this plan)
  is merged into `main`. Implementation happens directly on `main` (repo
  rule: no branch/PR ceremony). Re-verify file paths and cited line
  anchors on the resulting `main` before editing; anchors in this plan
  name functions first and line numbers second.
- Record `git rev-parse HEAD` after the start gate in the ignored ledger
  `.superpowers/sdd/2026-08-17-stage-9-board-flow-automation-plan/progress.md`
  as the Stage 9 audit base; Task 17 diffs base-to-HEAD.
- Preserve unrelated user work: never stage or rewrite the modified Stage
  3 plan document, the deleted Thai-dashboard spec, or untracked
  `.kotlin/` and `artifacts/`.
- **Implementer subagents must not spawn further subagents** (recorded
  fork-swarm hazard). Each dispatched implementer works alone.
- Room v10 is this stage's only durable change and lands entirely in
  Tasks 1–3. After Task 3, no further entity, migration, exported schema,
  backup family, or record-shape change is permitted.
- **Backup enum append-only rule:** new `BackupRecordFamily` values are
  appended strictly AFTER `TOMBSTONE`. `BackupRecordKey` orders by
  `family.ordinal` and snapshot canonical bytes depend on it; changing any
  existing ordinal breaks byte-canonical decode of every historical
  snapshot. The same applies to `RejectionReason` and `ActivityKind`:
  append only.
- Dual-arity is permanent: the WORKFLOW_STATUS validator accepts the
  9-field and 10-field shapes forever; the encoder emits only 10-field;
  an absent `wipLimit` imports as null. `.otvault` v1 and the Drive/cloud
  fixture sets stay frozen and are never regenerated; only
  `scripts/generate-stage2-backup-v1-fixtures.mjs` output is regenerated,
  and golden digests are updated only by re-running that generator.
- `MIGRATION_9_10` stamps the vault row marker to 10 (`UPDATE vaults SET
  schemaVersion = 10 WHERE schemaVersion < 10`), the 7→8 precedent. The
  recovery gate widens automatically because `RECOVERED_SCHEMA_VERSION`
  aliases `VAULT_DATABASE_VERSION`. VAULT is identity-fingerprinted, so
  the stamp journals nothing; the next baseline snapshot carries it, which
  is correct and expected.
- Bounds (enforced in BOTH repository companions and the record codec):
  20 automation rules; `dueInDays` 0..365; `thresholdDays` 1..365; 200 My
  Day members; `wipLimit` 1..200; subtask depth exactly one. All prior
  stage bounds unchanged.
- Single-authority rules: `arrangeTasks` owns nesting/indentation order,
  `boardColumns` owns column contents and counts, `subtaskRollups`,
  `myDaySuggestions`, `staleTaskIds`, and `evaluateAutomationRules` are
  new `core:domain` authorities. Never re-derive any of them in `:app` or
  a feature module. Feature modules depend only on `:core:model` and
  `:core:designsystem`; all dispatch stays in `:app`.
- Rule evaluation fires ONLY inside `execute()` for external
  `ChangeTaskStatus` / `CompleteTask` / `CompleteTasks` whose dispatch
  actually changed a task's `statusId` (detected from the repository's own
  undo). Outputs apply via internal `dispatch()` — never `execute()`
  (non-reentrant mutex). `UndoBatch`, recurrence spawns, imports,
  recovery, and `MoveTasksToProject` never evaluate rules.
- WIP enforcement lives only in `changeTaskStatus` for non-COMPLETED
  destinations; completion, bulk remaps, recurrence spawns, and rule
  outputs bypass by construction. Confirm, never block.
- The 48 dp tap/menu fallback stays sufficient on its own for every drag
  surface (board move, My Day reorder). Drag adds no command, arithmetic,
  controller, or persistence state.
- New UI copy goes in module `res/values/strings.xml` (UK English) read
  with `stringResource`; do not follow existing hardcoded literals.
  Colors stay OKLCH in `:core:designsystem`; no `Color(0x` in features.
- Tests: JUnit 4 `org.junit.Assert.*`, no mocking library, `runBlocking`
  + `withTimeout(5_000)` for suspend paths, camelCase behaviour names,
  `androidx.compose.ui.test.junit4.v2.createComposeRule`, fixtures from
  `OpenTasksFixtures`. Instrumented tests compile only until Task 17; no
  device suite before Task 17, and then only on the sole disposable
  read-only ADB target — never the protected `Pixel_10_Pro_Fold` AVD.
- Logs and telemetry never contain task text, rule config, or My Day
  content.
- Each task ends with focused checks, a scoped staged-name audit
  (`git status --short` shows only that task's files), a conventional
  commit, and an independent review before the next task. The completion
  gate is `./gradlew testDebugUnitTest lintDebug :app:assembleDebug`;
  `assembleRelease` always runs as its own invocation.

## Scope Check

One integrated plan. The spec deliberately couples everything to the
single v10 boundary (Tasks 1–3), and the features (WIP, subtasks, My Day,
automation) all consume that boundary plus each other (the engine needs
My Day commands; the sweep needs rules). Splitting would re-create the
cross-plan schema coordination the roadmap's "one v10 wave" ruling exists
to prevent.

## File Structure

Durable boundary (Tasks 1–3):

- `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt` —
  `WorkflowStatus.wipLimit`, `AutomationRuleType`, `AutomationRule`,
  `MyDayEntry`.
- `core/model/src/main/kotlin/app/opentasks/core/model/Identifiers.kt` —
  `AutomationRuleId`.
- `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt` —
  `WorkspaceSnapshot.automationRules` / `.myDay`,
  `HomeSnapshot.myDayTasks`.
- `core/data/src/main/kotlin/app/opentasks/core/data/db/Entities.kt` —
  `AutomationRuleEntity`, `MyDayEntryEntity`, `wipLimit` column.
- `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
  — version 10, entity registration, `MIGRATION_9_10`, new DAO methods,
  purge cleanup.
- `core/data/src/main/kotlin/app/opentasks/core/data/db/EntityMappers.kt`
  — new mappers.
- `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/10.json` —
  exported schema (generated).
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/` —
  `BackupRecordV1.kt`, `BackupMutationCodec.kt`,
  `RoomBackupJournalSession.kt`, `BackupRecordImporter.kt`,
  `RecoveryImportDao.kt`, `BackupDaos.kt`, `InMemoryBackupJournal.kt` —
  family wiring and dual-arity.
- `scripts/generate-stage2-backup-v1-fixtures.mjs` + regenerated
  `core/data/src/test/resources/backup-format/v1/*.json`.

Domain rules (pure, `core:domain`):

- `SubtaskRules.kt` (new) — one-level parent guards, rollups, attachable
  filter.
- `TaskArrangementRules.kt` — nesting + `indentedTaskIds`.
- `MyDayRules.kt` (new) — `rankBetween`, `rankForIndex`,
  `myDaySuggestions`.
- `AutomationEngine.kt` (new) — `StatusTransitionTrigger`,
  `evaluateAutomationRules`.
- `StaleRules.kt` (new) — `staleTaskIds`.
- `VaultRepository.kt` — new commands, command fields, rejection reasons.

Engines (identical behaviour, both files, every command):

- `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`

App and features:

- `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`,
  `WorkspaceViewModel.kt`, `MainActivity.kt`,
  `app/src/main/kotlin/app/opentasks/myday/MyDaySweeper.kt` (new).
- `feature/home/src/main/kotlin/app/opentasks/feature/home/HomeScreen.kt`
  + `MyDayPlanSheet.kt` (new).
- `feature/projects/.../BoardView.kt`, `ProjectsScreen.kt`.
- `feature/tasks/.../TasksScreen.kt`.
- `feature/more/.../MoreScreen.kt` + `AutomationsSection.kt` (new).
- `core/designsystem/.../Components.kt` — `TaskRow` stale badge.

---

### Task 1: Room v10 — entities, models, migration, exported schema

**Files:**

- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt`
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Identifiers.kt`
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/Entities.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/EntityMappers.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
  (snapshot assembly only)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
  (snapshot fields only)
- Create (generated): `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/10.json`
- Test: `core/data/src/androidTest/kotlin/app/opentasks/core/data/VaultDatabaseMigrationInstrumentedTest.kt`
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/backup/BackupRecordImporterTest.kt`
  (row-marker bound arms — verify they read `RECOVERED_SCHEMA_VERSION`)

**Interfaces:**

- Consumes: `VAULT_DATABASE_VERSION` (VaultDatabase.kt:585), migration
  chain (`addMigrations`, VaultDatabase.kt:697-706), `MIGRATION_8_9`
  precedent (VaultDatabase.kt:1219-1231), migration test helpers
  (`createV8`/`migrateTo9`/`captureVersion8Bytes` pattern,
  VaultDatabaseMigrationInstrumentedTest.kt:322-400).
- Produces (later tasks rely on these exact shapes):

```kotlin
// Records.kt
data class WorkflowStatus(
    val id: WorkflowStatusId,
    val projectId: ProjectId?,
    val name: String,
    val semanticStatus: SemanticStatus,
    val rank: String,
    val archivedAt: Instant? = null,
    val wipLimit: Int? = null,
)

enum class AutomationRuleType {
    ON_ENTER_ADD_TAG,
    ON_ENTER_ADD_TO_MY_DAY,
    ON_ENTER_SET_DUE,
    MY_DAY_AUTO_REMOVE,
    STALE_BADGE,
}

data class AutomationRule(
    val id: AutomationRuleId,
    val workspaceId: WorkspaceId,
    val type: AutomationRuleType,
    val enabled: Boolean,
    val projectId: ProjectId? = null,
    val statusId: WorkflowStatusId? = null,
    val tagId: TagId? = null,
    val dueInDays: Int? = null,
    val thresholdDays: Int? = null,
)

data class MyDayEntry(val taskId: TaskId, val rank: String)

// Identifiers.kt (exact existing pattern of the other IDs)
@JvmInline
value class AutomationRuleId(val value: String) {
    companion object {
        fun new(): AutomationRuleId = AutomationRuleId(UUID.randomUUID().toString())
    }
}

// Snapshots.kt — appended with defaults so all fixtures keep compiling
// WorkspaceSnapshot: val automationRules: List<AutomationRule> = emptyList(),
//                    val myDay: List<MyDayEntry> = emptyList(),
// HomeSnapshot:      val myDayTasks: List<Task> = emptyList(),
```

Note: check `Identifiers.kt` — if the existing IDs are `data class`
rather than `@JvmInline value class`, match whatever the file actually
uses; the `companion object { fun new() }` shape is confirmed.

- [ ] **Step 1: Write the failing migration test**

In `VaultDatabaseMigrationInstrumentedTest.kt`, following the exact
`migrate8To9PreservesRowsAndAddsEmptyRetiredBlobSets` mold (byte capture
before, migrate, assert equality after):

```kotlin
@Test
fun migrate9To10PreservesRowsAddsEmptyTablesAndStampsMarker() {
    val databaseName = databaseNameV9("preserved")
    lateinit var before: Map<String, List<List<Any?>>>
    createV9(databaseName).use { database ->
        seedVersion9Fixture(database)
        before = database.captureVersion9Bytes()
    }

    val migrated = migrateTo10(databaseName)

    // Existing bytes byte-identical. The capture deliberately excludes
    // `vaults` (the row marker is stamped by design, asserted below)
    // and reads `workflow_statuses` with EXPLICIT v9 columns, because a
    // post-migration `SELECT *` would include the new `wipLimit` column
    // and could never byte-match the pre-migration capture.
    assertEquals(before, migrated.captureVersion9Bytes())
    assertEquals(0L, migrated.longValue("SELECT COUNT(*) FROM automation_rules"))
    assertEquals(0L, migrated.longValue("SELECT COUNT(*) FROM my_day_entries"))
    assertEquals(
        0L,
        migrated.longValue(
            "SELECT COUNT(*) FROM workflow_statuses WHERE wipLimit IS NOT NULL",
        ),
    )
    // MIGRATION_9_10 stamps the marker (the 7→8 precedent): a v9 app can
    // never read v10 data anyway, so the recovery refusal becomes a
    // legible upfront gate instead of a mid-decode field-count error.
    assertEquals(
        10L,
        migrated.longValue("SELECT schemaVersion FROM vaults WHERE id = 'vault-a'"),
    )
    assertTrue(
        migrated.longValue("SELECT schemaVersion FROM vaults WHERE id = 'vault-a'") <=
            RECOVERED_SCHEMA_VERSION,
    )
    migrated.close()
}
```

Add the small helpers beside the v8 set, reusing its shapes verbatim:
`databaseNameV9(suffix) = "vault-v9-v10-$suffix.db"`, `createV9(name) =
migrationTestHelper.createDatabase(name, 9)`, `migrateTo10(name) =
migrationTestHelper.runMigrationsAndValidate(name, 10, true,
VaultDatabase.MIGRATION_9_10)`, `seedVersion9Fixture` = copy of
`seedVersion8Fixture` plus one `retired_blob_sets` row and one
`workflow_statuses` row (9-column insert, no `wipLimit`), and the
capture helper (note `captureVersion8Bytes` uses `SELECT *`, which the
amended table cannot reuse):

```kotlin
private fun SupportSQLiteDatabase.captureVersion9Bytes(): Map<String, List<List<Any?>>> =
    buildMap {
        listOf("tasks", "attachments", "attachment_transfer", "retired_blob_sets")
            .forEach { table ->
                put(table, captureRows("SELECT * FROM $table ORDER BY rowid"))
            }
        // Explicit v9 columns: byte-comparable across the ADD COLUMN.
        put(
            "workflow_statuses",
            captureRows(
                "SELECT id, projectId, name, semanticStatus, rank, " +
                    "archivedAtEpochMillis, revisionWallMillis, " +
                    "revisionLogical, revisionDeviceId " +
                    "FROM workflow_statuses ORDER BY rowid",
            ),
        )
    }
```

- [ ] **Step 2: Verify the test does not compile / fails**

Run: `./gradlew :core:data:compileDebugAndroidTestKotlin`
Expected: FAIL — `MIGRATION_9_10` unresolved.

- [ ] **Step 3: Add models, entities, mappers, migration, DAO methods**

`Records.kt`, `Identifiers.kt`, `Snapshots.kt`: exactly the Interfaces
block above. `Entities.kt`:

```kotlin
@Entity(tableName = "automation_rules", primaryKeys = ["id"])
data class AutomationRuleEntity(
    val id: String,
    val workspaceId: String,
    val type: String,
    val enabled: Boolean,
    val projectId: String?,
    val statusId: String?,
    val tagId: String?,
    val dueInDays: Int?,
    val thresholdDays: Int?,
)

@Entity(tableName = "my_day_entries", primaryKeys = ["taskId"])
data class MyDayEntryEntity(
    val taskId: String,
    val rank: String,
)
```

`WorkflowStatusEntity` gains `val wipLimit: Int?` as the LAST property
(column order matches the migration's `ADD COLUMN`). `EntityMappers.kt`:
extend `WorkflowStatusEntity.toModel` / `WorkflowStatus.toEntity` with
`wipLimit`, and add:

```kotlin
internal fun AutomationRuleEntity.toModel(): AutomationRule = AutomationRule(
    id = AutomationRuleId(id),
    workspaceId = WorkspaceId(workspaceId),
    type = AutomationRuleType.valueOf(type),
    enabled = enabled,
    projectId = projectId?.let(::ProjectId),
    statusId = statusId?.let(::WorkflowStatusId),
    tagId = tagId?.let(::TagId),
    dueInDays = dueInDays,
    thresholdDays = thresholdDays,
)

internal fun AutomationRule.toEntity(): AutomationRuleEntity = AutomationRuleEntity(
    id = id.value,
    workspaceId = workspaceId.value,
    type = type.name,
    enabled = enabled,
    projectId = projectId?.value,
    statusId = statusId?.value,
    tagId = tagId?.value,
    dueInDays = dueInDays,
    thresholdDays = thresholdDays,
)

internal fun MyDayEntryEntity.toModel(): MyDayEntry =
    MyDayEntry(TaskId(taskId), rank)

internal fun MyDayEntry.toEntity(): MyDayEntryEntity =
    MyDayEntryEntity(taskId.value, rank)
```

Malformed persisted enum names (`AutomationRuleType.valueOf` throwing on
recovered foreign data) must not break repository readiness: at the
snapshot assembly site below, map rules with
`runCatching { entity.toModel() }.getOrNull()` — the exact
`savedViews` fail-closed precedent at RoomVaultRepository.kt:4247-4249.

`VaultDatabase.kt`: bump `VAULT_DATABASE_VERSION` to 10; register both
entities in the `@Database` list after `RetiredBlobSetEntity`; add
`MIGRATION_9_10` beside `MIGRATION_8_9` and to `addMigrations`:

```kotlin
internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `automation_rules` (" +
                "`id` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
                "`projectId` TEXT, `statusId` TEXT, `tagId` TEXT, " +
                "`dueInDays` INTEGER, `thresholdDays` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `my_day_entries` (" +
                "`taskId` TEXT NOT NULL, `rank` TEXT NOT NULL, " +
                "PRIMARY KEY(`taskId`))",
        )
        db.execSQL("ALTER TABLE workflow_statuses ADD COLUMN wipLimit INTEGER")
        db.execSQL("UPDATE vaults SET schemaVersion = 10 WHERE schemaVersion < 10")
    }
}
```

Add to `WorkspaceDao` (plain DAO additions, no schema change):

```kotlin
@Query("SELECT * FROM automation_rules ORDER BY id")
suspend fun getAutomationRules(): List<AutomationRuleEntity>

@Query("SELECT * FROM automation_rules ORDER BY id")
fun observeAutomationRules(): Flow<List<AutomationRuleEntity>>

@Query("SELECT * FROM automation_rules WHERE id = :id LIMIT 1")
suspend fun getAutomationRule(id: String): AutomationRuleEntity?

@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun upsertAutomationRule(value: AutomationRuleEntity)

@Query("DELETE FROM automation_rules WHERE id = :id")
suspend fun deleteAutomationRule(id: String): Int

@Query("SELECT * FROM my_day_entries ORDER BY rank, taskId")
suspend fun getMyDayEntries(): List<MyDayEntryEntity>

@Query("SELECT * FROM my_day_entries ORDER BY rank, taskId")
fun observeMyDayEntries(): Flow<List<MyDayEntryEntity>>

@Query("SELECT * FROM my_day_entries WHERE taskId = :taskId LIMIT 1")
suspend fun getMyDayEntry(taskId: String): MyDayEntryEntity?

@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun upsertMyDayEntry(value: MyDayEntryEntity)

@Query("DELETE FROM my_day_entries WHERE taskId = :taskId")
suspend fun deleteMyDayEntry(taskId: String): Int
```

In `VaultDatabase.purgeTask` add `workspaceDao().deleteMyDayEntry(taskId)`
immediately before `taskDao().deleteById(taskId)` so a purged task's My
Day row dies in the same transaction (journaled by the surrounding
snapshot diff once Task 3 wires the family).

Snapshot exposure: in `RoomVaultRepository.observeDatabase`, add the two
observed flows into the existing `combine` cascade (mirror how
`observeTemplates` was folded in at RoomVaultRepository.kt:4089-4099) and
carry them through `RelationRows` (companion data class near
RoomVaultRepository.kt:4350-4364) into `buildSnapshot`, which appends:

```kotlin
automationRules = relations.automationRules.mapNotNull { entity ->
    runCatching { entity.toModel() }.getOrNull()
},
myDay = relations.myDayEntries.map(MyDayEntryEntity::toModel),
```

`HomeSnapshot.myDayTasks` stays `emptyList()` here — the projection is
Task 4. In `InMemoryVaultRepository`, thread the two new
`WorkspaceSnapshot` fields through `publish(...)` untouched (they default
to the current value); no behaviour yet.

- [ ] **Step 4: Export the schema and prove drift-clean**

Run: `./gradlew :core:data:kspDebugKotlin` then
`./scripts/check-schema-drift.sh`
Expected: `10.json` appears; `1.json`–`9.json` byte-identical; drift
check passes.

- [ ] **Step 5: Compile the instrumented test and run the JVM gate**

Run: `./gradlew :core:data:compileDebugAndroidTestKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL — the migration test compiles (device run
waits for Task 17); all existing JVM tests still pass, including
`BackupRecordImporterTest`'s row-marker arms, which now accept markers up
to 10 automatically via `RECOVERED_SCHEMA_VERSION`. Add one explicit JVM
assertion there: a VAULT record with `schemaVersion = 10` normalizes, and
`11` is refused.

- [ ] **Step 6: Commit**

```bash
git add core/model core/data
git commit -m "feat: add room v10 with automation, my day, and wip columns"
```

---

### Task 2: Dual-arity WORKFLOW_STATUS amendment inside format v1

**Files:**

- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordV1.kt`
  (`WorkflowStatusEntity.toBackupRecordV1`, BackupRecordV1.kt:128-140)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupMutationCodec.kt`
  (`RecordSchema`, `validateRecord`, WORKFLOW_STATUS arm)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordImporter.kt`
  (`BackupRecordFields`, `toWorkflowStatusEntity`)
- Modify: `scripts/generate-stage2-backup-v1-fixtures.mjs` (`status()`)
- Regenerate: `core/data/src/test/resources/backup-format/v1/*.json`
- Modify: `core/data/src/test/kotlin/app/opentasks/core/data/backup/BackupPayloadGoldenTest.kt`
  (digests)
- Create: `core/data/src/test/kotlin/app/opentasks/core/data/backup/WorkflowStatusDualArityTest.kt`

**Interfaces:**

- Consumes: `record()`/`nullableIntField()` helpers
  (BackupRecordV1.kt:335-374), `RecordSchema`/`FieldSchema`
  (BackupMutationCodec.kt:499-508), `BackupRecordFields`
  (BackupRecordImporter.kt:491-541).
- Produces: `RecordSchema.optionalTrailing: List<FieldSchema>`;
  `BackupRecordFields.absentOrNullableInt(name: String): Int?`; the
  10-field WORKFLOW_STATUS encoder ending in `wipLimit`.

- [ ] **Step 1: Write the failing dual-arity tests**

`WorkflowStatusDualArityTest.kt`:

```kotlin
package app.opentasks.core.data.backup

import app.opentasks.core.domain.BackupMutationKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkflowStatusDualArityTest {

    private fun field(name: String, type: BackupFieldType, value: String?) =
        BackupFieldV1(name, type, value)

    private fun legacyNineFieldRecord(): BackupRecordV1 = BackupRecordV1(
        family = BackupRecordFamily.WORKFLOW_STATUS,
        identity = listOf("status-legacy"),
        fields = listOf(
            field("id", BackupFieldType.STRING, "status-legacy"),
            field("projectId", BackupFieldType.STRING, "project-1"),
            field("name", BackupFieldType.STRING, "Backlog"),
            field("semanticStatus", BackupFieldType.STRING, "BACKLOG"),
            field("rank", BackupFieldType.STRING, "a0"),
            field("archivedAtEpochMillis", BackupFieldType.NULL, null),
            field("revisionWallMillis", BackupFieldType.LONG, "10"),
            field("revisionLogical", BackupFieldType.INT, "0"),
            field("revisionDeviceId", BackupFieldType.STRING, "device-alpha"),
        ),
    )

    private fun tenFieldRecord(wipLimit: String?): BackupRecordV1 {
        val legacy = legacyNineFieldRecord()
        val tail = if (wipLimit == null) {
            field("wipLimit", BackupFieldType.NULL, null)
        } else {
            field("wipLimit", BackupFieldType.INT, wipLimit)
        }
        return legacy.copy(fields = legacy.fields + tail)
    }

    @Test
    fun legacyNineFieldRecordStaysValidAndByteCanonical() {
        val payload = BackupMutationPayloadV1(
            mutationKind = BackupMutationKind.UPSERT,
            record = legacyNineFieldRecord(),
            deletedFamily = null,
            deletedIdentity = null,
        )
        val encoded = BackupMutationCodec.encode(payload)
        val decoded = BackupMutationCodec.decode(encoded)
        assertEquals(9, requireNotNull(decoded.record).fields.size)
        assertArrayEquals(encoded, BackupMutationCodec.encode(decoded))
    }

    @Test
    fun tenFieldRecordValidatesWithNullAndBoundedValues() {
        BackupMutationCodec.validateRecord(tenFieldRecord(null))
        BackupMutationCodec.validateRecord(tenFieldRecord("3"))
        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.validateRecord(tenFieldRecord("0"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.validateRecord(tenFieldRecord("201"))
        }
    }

    @Test
    fun elevenFieldRecordFailsClosed() {
        val eleven = tenFieldRecord("3").let {
            it.copy(fields = it.fields + field("extra", BackupFieldType.INT, "1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.validateRecord(eleven)
        }
    }

    @Test
    fun legacyRecordImportsAsNoLimitAndTenFieldRoundTrips() {
        assertNull(
            BackupRecordFields.of(legacyNineFieldRecord())
                .toWorkflowStatusEntity().wipLimit,
        )
        assertEquals(
            3,
            BackupRecordFields.of(tenFieldRecord("3"))
                .toWorkflowStatusEntity().wipLimit,
        )
    }

    @Test
    fun encoderEmitsTheTenFieldShape() {
        val entity = app.opentasks.core.data.db.WorkflowStatusEntity(
            id = "status-new",
            projectId = "project-1",
            name = "Started",
            semanticStatus = "STARTED",
            rank = "a2",
            archivedAtEpochMillis = null,
            revisionWallMillis = 10,
            revisionLogical = 0,
            revisionDeviceId = "device-alpha",
            wipLimit = 5,
        )
        val record = entity.toBackupRecordV1()
        assertEquals(10, record.fields.size)
        assertEquals("wipLimit", record.fields.last().name)
        assertEquals("5", record.fields.last().value)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*WorkflowStatusDualArityTest*"`
Expected: FAIL — encoder emits 9 fields; ten-field validation throws
"wrong field count".

- [ ] **Step 3: Implement dual-arity**

`BackupRecordV1.kt` — append to `WorkflowStatusEntity.toBackupRecordV1`
after `revisionDeviceId`:

```kotlin
nullableIntField("wipLimit", wipLimit),
```

`BackupMutationCodec.kt` — extend the schema type and `validateRecord`:

```kotlin
private data class RecordSchema(
    val fields: List<FieldSchema>,
    val identityFieldIndexes: List<Int> = listOf(0),
    val optionalTrailing: List<FieldSchema> = emptyList(),
)
```

In `validateRecord`, replace the size check and zip:

```kotlin
val schema = schemas.getValue(record.family)
val minimum = schema.fields.size
val maximum = minimum + schema.optionalTrailing.size
require(record.fields.size in minimum..maximum) {
    "${record.family} has the wrong field count"
}
val expectedFields = schema.fields +
    schema.optionalTrailing.take(record.fields.size - minimum)
record.fields.zip(expectedFields).forEach { (field, expected) ->
    // unchanged body
}
```

WORKFLOW_STATUS schema entry gains
`optionalTrailing = listOf(int("wipLimit", nullable = true))` (keep the
existing nine `fields` untouched). In `validateFamilyValues`, the
WORKFLOW_STATUS arm gains a trailing-tolerant check — add beside the
local helpers:

```kotlin
fun optionalValue(name: String): String? = fields[name]?.value
```

and in the arm:

```kotlin
optionalValue("wipLimit")?.let { require(it.toInt() in 1..200) { "wipLimit out of range" } }
```

(`fields` is the existing `associateBy` map; `value()` keeps using
`getValue` for guaranteed fields.)

`BackupRecordImporter.kt` — in `BackupRecordFields`:

```kotlin
/** Trailing optional field: absent on legacy records, nullable when present. */
fun absentOrNullableInt(name: String): Int? {
    fields[name] ?: return null
    return nullableInt(name)
}
```

and `toWorkflowStatusEntity` gains
`wipLimit = absentOrNullableInt("wipLimit")` as its last argument.

- [ ] **Step 4: Run the new tests**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*WorkflowStatusDualArityTest*"`
Expected: PASS.

- [ ] **Step 5: Regenerate stage-2 fixtures and update goldens**

In `generate-stage2-backup-v1-fixtures.mjs`, extend `status()`:

```js
function status(id, projectId, name, semanticStatus, rank, logical, wipLimit = null) {
  return record("WORKFLOW_STATUS", [id], [
    stringField("id", id),
    nullableStringField("projectId", projectId),
    stringField("name", name),
    stringField("semanticStatus", semanticStatus),
    stringField("rank", rank),
    nullableLongField("archivedAtEpochMillis", null),
    longField("revisionWallMillis", 10),
    intField("revisionLogical", logical),
    stringField("revisionDeviceId", "device-alpha"),
    nullableIntField("wipLimit", wipLimit),
  ]);
}
```

Give exactly one status a real limit so value validation is exercised:
in the `statuses` construction, pass `wipLimit = 3` for the
`status-project-started` entry (add a conditional argument:
`semantic === "STARTED" ? 3 : null` on the project-scoped call). Run
`node scripts/generate-stage2-backup-v1-fixtures.mjs`, then run
`./gradlew :core:data:testDebugUnitTest --tests "*BackupPayloadGoldenTest*"`,
and copy the two new SHA-256 digests from the failure output into
`BackupPayloadGoldenTest` (counts stay unchanged). Re-run: PASS. The
`.otvault` and Drive fixture generators are NOT touched; run
`./gradlew :core:data:testDebugUnitTest --tests "*OtVault*"` and confirm
the frozen-archive tests still pass — that is the old-shape compat proof
(their embedded WORKFLOW_STATUS records are 9-field and now decode via
dual-arity).

- [ ] **Step 6: Full unit gate and commit**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: PASS (importer round-trips, canonical re-encode, golden bytes).

```bash
git add core/data scripts/generate-stage2-backup-v1-fixtures.mjs
git commit -m "feat: amend workflow status records with dual-arity wip limit"
```

---

### Task 3: AUTOMATION_RULE and MY_DAY backup families end to end

**Files:**

- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordV1.kt`
  (enum + two encoders)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupMutationCodec.kt`
  (schemas + family value validation)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomBackupJournalSession.kt`
  (`BackupMutationDao` queries, `snapshots()`, `requireRecord()`)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupDaos.kt`
  (`BackupCaptureDao` + `allRecords`)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordImporter.kt`
  (write/delete arms + `toEntity` functions)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/backup/RecoveryImportDao.kt`
  (insert/upsert/delete + `structuredRecordCount`)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/backup/InMemoryBackupJournal.kt`
  (`toBackupRecords` arms)
- Modify: `scripts/generate-stage2-backup-v1-fixtures.mjs`
  (builders + snapshot records + familyOrder)
- Modify: `core/data/src/test/kotlin/app/opentasks/core/data/backup/BackupPayloadGoldenTest.kt`
  (counts + digests)
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/backup/BackupMutationCodecTest.kt`
  (new family arms + frozen-ordinal assertion)

**Interfaces:**

- Consumes: Task 1 entities/mappers; the Stage 5 two-commit shape
  (33ea364 + 35d54c3) as the walked precedent; `contentSnapshot()`
  (RoomBackupJournalSession.kt:633-638).
- Produces: `BackupRecordFamily.AUTOMATION_RULE` (identity `[id]`,
  content fingerprint) and `BackupRecordFamily.MY_DAY` (identity
  `[taskId]`, content fingerprint), both APPENDED AFTER `TOMBSTONE`;
  `AutomationRuleEntity.toBackupRecordV1()`,
  `MyDayEntryEntity.toBackupRecordV1()`,
  `BackupRecordFields.toAutomationRuleEntity()`,
  `BackupRecordFields.toMyDayEntryEntity()`.

- [ ] **Step 1: Write the failing codec tests**

In `BackupMutationCodecTest.kt` add:

```kotlin
@Test
fun familyOrdinalsStayFrozenForCanonicalOrdering() {
    // BackupRecordKey orders by ordinal; historical snapshot bytes
    // depend on it. New families append after TOMBSTONE, never before.
    assertEquals(19, BackupRecordFamily.TOMBSTONE.ordinal)
    assertEquals(20, BackupRecordFamily.AUTOMATION_RULE.ordinal)
    assertEquals(21, BackupRecordFamily.MY_DAY.ordinal)
}

@Test
fun automationRuleRecordValidatesPerTypeConfig() {
    fun rule(
        type: String,
        statusId: String?,
        tagId: String?,
        dueInDays: String?,
        thresholdDays: String?,
    ): BackupRecordV1 = BackupRecordV1(
        family = BackupRecordFamily.AUTOMATION_RULE,
        identity = listOf("rule-1"),
        fields = listOf(
            BackupFieldV1("id", BackupFieldType.STRING, "rule-1"),
            BackupFieldV1("workspaceId", BackupFieldType.STRING, "workspace-1"),
            BackupFieldV1("type", BackupFieldType.STRING, type),
            BackupFieldV1("enabled", BackupFieldType.BOOLEAN, "true"),
            BackupFieldV1("projectId", BackupFieldType.NULL, null),
            statusId?.let { BackupFieldV1("statusId", BackupFieldType.STRING, it) }
                ?: BackupFieldV1("statusId", BackupFieldType.NULL, null),
            tagId?.let { BackupFieldV1("tagId", BackupFieldType.STRING, it) }
                ?: BackupFieldV1("tagId", BackupFieldType.NULL, null),
            dueInDays?.let { BackupFieldV1("dueInDays", BackupFieldType.INT, it) }
                ?: BackupFieldV1("dueInDays", BackupFieldType.NULL, null),
            thresholdDays?.let { BackupFieldV1("thresholdDays", BackupFieldType.INT, it) }
                ?: BackupFieldV1("thresholdDays", BackupFieldType.NULL, null),
        ),
    )

    BackupMutationCodec.validateRecord(rule("ON_ENTER_ADD_TAG", "status-1", "tag-1", null, null))
    BackupMutationCodec.validateRecord(rule("ON_ENTER_ADD_TO_MY_DAY", "status-1", null, null, null))
    BackupMutationCodec.validateRecord(rule("ON_ENTER_SET_DUE", "status-1", null, "3", null))
    BackupMutationCodec.validateRecord(rule("MY_DAY_AUTO_REMOVE", null, null, null, null))
    BackupMutationCodec.validateRecord(rule("STALE_BADGE", null, null, null, "14"))

    assertThrows(IllegalArgumentException::class.java) {
        BackupMutationCodec.validateRecord(rule("ON_ENTER_ADD_TAG", "status-1", null, null, null))
    }
    assertThrows(IllegalArgumentException::class.java) {
        BackupMutationCodec.validateRecord(rule("ON_ENTER_SET_DUE", "status-1", null, "366", null))
    }
    assertThrows(IllegalArgumentException::class.java) {
        BackupMutationCodec.validateRecord(rule("STALE_BADGE", null, null, null, "0"))
    }
    assertThrows(IllegalArgumentException::class.java) {
        BackupMutationCodec.validateRecord(rule("MY_DAY_AUTO_REMOVE", "status-1", null, null, null))
    }
    assertThrows(IllegalArgumentException::class.java) {
        BackupMutationCodec.validateRecord(rule("NOT_A_TYPE", null, null, null, null))
    }
}

@Test
fun myDayRecordValidates() {
    val record = BackupRecordV1(
        family = BackupRecordFamily.MY_DAY,
        identity = listOf("task-1"),
        fields = listOf(
            BackupFieldV1("taskId", BackupFieldType.STRING, "task-1"),
            BackupFieldV1("rank", BackupFieldType.STRING, "a0"),
        ),
    )
    BackupMutationCodec.validateRecord(record)
    assertThrows(IllegalArgumentException::class.java) {
        BackupMutationCodec.validateRecord(
            record.copy(
                fields = listOf(
                    record.fields[0],
                    BackupFieldV1("rank", BackupFieldType.STRING, ""),
                ),
            ),
        )
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*BackupMutationCodecTest*"`
Expected: FAIL — `AUTOMATION_RULE` unresolved.

- [ ] **Step 3: Wire both families through every layer**

`BackupRecordV1.kt` — append AFTER `TOMBSTONE` in the enum:

```kotlin
AUTOMATION_RULE,
MY_DAY,
```

and add encoders beside the others:

```kotlin
internal fun AutomationRuleEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.AUTOMATION_RULE,
    identity = listOf(id),
    stringField("id", id),
    stringField("workspaceId", workspaceId),
    stringField("type", type),
    booleanField("enabled", enabled),
    nullableStringField("projectId", projectId),
    nullableStringField("statusId", statusId),
    nullableStringField("tagId", tagId),
    nullableIntField("dueInDays", dueInDays),
    nullableIntField("thresholdDays", thresholdDays),
)

internal fun MyDayEntryEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.MY_DAY,
    identity = listOf(taskId),
    stringField("taskId", taskId),
    stringField("rank", rank),
)
```

`BackupMutationCodec.kt` — schemas (appended at the end of the map):

```kotlin
BackupRecordFamily.AUTOMATION_RULE to RecordSchema(
    listOf(
        string("id"),
        string("workspaceId"),
        string("type"),
        boolean("enabled"),
        string("projectId", nullable = true),
        string("statusId", nullable = true),
        string("tagId", nullable = true),
        int("dueInDays", nullable = true),
        int("thresholdDays", nullable = true),
    ),
),
BackupRecordFamily.MY_DAY to RecordSchema(
    listOf(string("taskId"), string("rank")),
),
```

`validateFamilyValues` arms (import `AutomationRuleType` from
`app.opentasks.core.model`, matching the existing `SemanticStatus`
import):

```kotlin
BackupRecordFamily.AUTOMATION_RULE -> {
    identifier("workspaceId")
    val type = requireNotNull(value("type"))
    require(type in AutomationRuleType.entries.map(AutomationRuleType::name)) {
        "type is not an automation rule type"
    }
    identifier("projectId")
    identifier("statusId")
    identifier("tagId")
    value("dueInDays")?.let { require(it.toInt() in 0..365) { "dueInDays out of range" } }
    value("thresholdDays")?.let { require(it.toInt() in 1..365) { "thresholdDays out of range" } }
    fun requirePresent(name: String) =
        require(value(name) != null) { "$type requires $name" }
    fun requireAbsent(name: String) =
        require(value(name) == null) { "$type forbids $name" }
    when (AutomationRuleType.valueOf(type)) {
        AutomationRuleType.ON_ENTER_ADD_TAG -> {
            requirePresent("statusId"); requirePresent("tagId")
            requireAbsent("dueInDays"); requireAbsent("thresholdDays")
        }
        AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY -> {
            requirePresent("statusId")
            requireAbsent("tagId"); requireAbsent("dueInDays"); requireAbsent("thresholdDays")
        }
        AutomationRuleType.ON_ENTER_SET_DUE -> {
            requirePresent("statusId"); requirePresent("dueInDays")
            requireAbsent("tagId"); requireAbsent("thresholdDays")
        }
        AutomationRuleType.MY_DAY_AUTO_REMOVE -> {
            requireAbsent("projectId"); requireAbsent("statusId")
            requireAbsent("tagId"); requireAbsent("dueInDays"); requireAbsent("thresholdDays")
        }
        AutomationRuleType.STALE_BADGE -> {
            requirePresent("thresholdDays")
            requireAbsent("statusId"); requireAbsent("tagId"); requireAbsent("dueInDays")
        }
    }
}
BackupRecordFamily.MY_DAY -> {
    bounded("rank", 200, allowEmpty = false)
}
```

`RoomBackupJournalSession.kt` — `BackupMutationDao` gains:

```kotlin
@Query("SELECT * FROM automation_rules ORDER BY id")
suspend fun automationRules(): List<AutomationRuleEntity>

@Query("SELECT * FROM automation_rules WHERE id = :id LIMIT 1")
suspend fun automationRule(id: String): AutomationRuleEntity?

@Query("SELECT * FROM my_day_entries ORDER BY taskId")
suspend fun myDayEntries(): List<MyDayEntryEntity>

@Query("SELECT * FROM my_day_entries WHERE taskId = :taskId LIMIT 1")
suspend fun myDayEntry(taskId: String): MyDayEntryEntity?
```

`snapshots()` gains, at the end (content tier — any edit journals):

```kotlin
automationRules().mapTo(this) { it.toBackupRecordV1().contentSnapshot() }
myDayEntries().mapTo(this) { it.toBackupRecordV1().contentSnapshot() }
```

`requireRecord()` gains the two arms (the exhaustive `when` forces this):

```kotlin
BackupRecordFamily.AUTOMATION_RULE ->
    requireNotNull(automationRule(singleId())).toBackupRecordV1()
BackupRecordFamily.MY_DAY ->
    requireNotNull(myDayEntry(singleId())).toBackupRecordV1()
```

`BackupDaos.kt` — `BackupCaptureDao` gains vault-scoped reads (direct
joins, no journal-evidence dance):

```kotlin
@Query(
    """
    SELECT rule.* FROM automation_rules AS rule
    INNER JOIN workspaces AS workspace ON workspace.id = rule.workspaceId
    WHERE workspace.vaultId = :vaultId
    ORDER BY rule.id
    """,
)
suspend fun automationRules(vaultId: String): List<AutomationRuleEntity>

@Query(
    """
    SELECT entry.* FROM my_day_entries AS entry
    INNER JOIN tasks AS task ON task.id = entry.taskId
    INNER JOIN workspaces AS workspace ON workspace.id = task.workspaceId
    WHERE workspace.vaultId = :vaultId
    ORDER BY entry.taskId
    """,
)
suspend fun myDayEntries(vaultId: String): List<MyDayEntryEntity>
```

and `allRecords(vaultId)` appends, after `tombstones(vaultId)`:

```kotlin
automationRules(vaultId).mapTo(this) { it.toBackupRecordV1() }
myDayEntries(vaultId).mapTo(this) { it.toBackupRecordV1() }
```

A My Day row whose task is missing entirely (never legal locally — purge
deletes it in-transaction) would silently drop from capture via the
inner join; keep it legal by adding the guard beside the existing ones
at the top of `allRecords`:

```kotlin
@Query(
    """
    SELECT COUNT(*) FROM my_day_entries AS entry
    WHERE NOT EXISTS (SELECT 1 FROM tasks AS task WHERE task.id = entry.taskId)
    """,
)
suspend fun danglingMyDayEntryCount(): Int
```

```kotlin
require(danglingMyDayEntryCount() == 0) { "My Day entry has no task" }
```

`BackupRecordImporter.kt` — `write()` arms:

```kotlin
BackupRecordFamily.AUTOMATION_RULE -> fields.toAutomationRuleEntity().let { entity ->
    if (replace) importDao.upsertAutomationRule(entity) else importDao.insertAutomationRule(entity)
}
BackupRecordFamily.MY_DAY -> fields.toMyDayEntryEntity().let { entity ->
    if (replace) importDao.upsertMyDayEntry(entity) else importDao.insertMyDayEntry(entity)
}
```

`delete()` arms:

```kotlin
BackupRecordFamily.AUTOMATION_RULE -> importDao.deleteAutomationRule(key.single())
BackupRecordFamily.MY_DAY -> importDao.deleteMyDayEntry(key.single())
```

`toEntity` functions beside `toRetiredBlobSetEntity`:

```kotlin
internal fun BackupRecordFields.toAutomationRuleEntity(): AutomationRuleEntity =
    AutomationRuleEntity(
        id = string("id"),
        workspaceId = string("workspaceId"),
        type = string("type"),
        enabled = boolean("enabled"),
        projectId = nullableString("projectId"),
        statusId = nullableString("statusId"),
        tagId = nullableString("tagId"),
        dueInDays = nullableInt("dueInDays"),
        thresholdDays = nullableInt("thresholdDays"),
    )

internal fun BackupRecordFields.toMyDayEntryEntity(): MyDayEntryEntity =
    MyDayEntryEntity(taskId = string("taskId"), rank = string("rank"))
```

`RecoveryImportDao.kt` — `insertAutomationRule`/`insertMyDayEntry`
(ABORT), `upsertAutomationRule`/`upsertMyDayEntry` (REPLACE),
`deleteAutomationRule(id)`/`deleteMyDayEntry(taskId)` `@Query DELETE`
methods following the retired-blob-set pattern exactly, and widen
`structuredRecordCount` with
`+ (SELECT COUNT(*) FROM automation_rules) + (SELECT COUNT(*) FROM
my_day_entries)` — forgetting this weakens the staging-emptiness proof.

`InMemoryBackupJournal.kt` — `toBackupRecords` gains, before the
tombstone line:

```kotlin
automationRules.mapTo(this) { it.toEntity().toBackupRecordV1() }
myDay.mapTo(this) { it.toEntity().toBackupRecordV1() }
```

- [ ] **Step 4: Run the codec tests**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*BackupMutationCodecTest*"`
Expected: PASS.

- [ ] **Step 5: Extend the fixture generator and goldens**

`generate-stage2-backup-v1-fixtures.mjs` — add builders:

```js
function automationRule(id, type, statusId, tagId, dueInDays, thresholdDays) {
  return record("AUTOMATION_RULE", [id], [
    stringField("id", id),
    stringField("workspaceId", "workspace-1"),
    stringField("type", type),
    booleanField("enabled", true),
    nullableStringField("projectId", null),
    nullableStringField("statusId", statusId),
    nullableStringField("tagId", tagId),
    nullableIntField("dueInDays", dueInDays),
    nullableIntField("thresholdDays", thresholdDays),
  ]);
}

function myDayEntry(taskId, rank) {
  return record("MY_DAY", [taskId], [
    stringField("taskId", taskId),
    stringField("rank", rank),
  ]);
}
```

Append `"AUTOMATION_RULE", "MY_DAY"` at the END of `familyOrder`, and
append to `snapshotRecords` (after the tombstone record):
`automationRule("rule-1", "ON_ENTER_ADD_TAG", "status-project-started",
"tag-1", null, null)` and `myDayEntry("task-1", "a0")`. Regenerate, then
update `BackupPayloadGoldenTest`: `expectedCounts` gains
`BackupRecordFamily.AUTOMATION_RULE to 1` and
`BackupRecordFamily.MY_DAY to 1`, and both digests update from the
failure output. Re-run
`./gradlew :core:data:testDebugUnitTest --tests "*BackupPayloadGoldenTest*"`:
PASS.

- [ ] **Step 6: Full unit gate (recovery round trip included) and commit**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: PASS — recovery-import unit suites replay the regenerated
snapshot fixture through the new arms; `StagedVaultVerifier` needs no
change because `allRecords`/`toBackupRecords` now carry both families on
both sides of its comparison.

```bash
git add core/data scripts/generate-stage2-backup-v1-fixtures.mjs
git commit -m "feat: back up automation rules and my day as record families"
```

---

### Task 4: My Day commands, rank machinery, and Home projection

**Files:**

- Create: `core/domain/src/main/kotlin/app/opentasks/core/domain/MyDayRules.kt`
- Create: `core/domain/src/test/kotlin/app/opentasks/core/domain/MyDayRulesTest.kt`
- Modify: `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`
- Create: `core/data/src/test/kotlin/app/opentasks/core/data/backup/MyDayFamilyTest.kt`
- Test: `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`

**Interfaces:**

- Consumes: Task 1 table/DAO/model, Task 3 journaling.
- Produces:

```kotlin
// MyDayRules.kt
const val MAX_MY_DAY_RANK_LENGTH = 200
/** Midpoint between two ranks; null when no midpoint fits the bound —
 *  the caller then re-ranks the whole list with [myDayRankForIndex]. */
fun myDayRankBetween(previous: String?, next: String?): String?
fun myDayRankForIndex(index: Int): String   // "r000000", "r000001", …
fun myDaySuggestions(
    tasks: List<Task>,
    memberIds: Set<TaskId>,
    today: LocalDate,
    zoneId: ZoneId,
): List<Task>   // open, non-binned, non-member; overdue first, then
                // start-or-due today; each group due-then-title; cap 10

// VaultRepository.kt
data class AddTaskToMyDay(val taskId: TaskId) : DomainCommand
data class RemoveTaskFromMyDay(val taskId: TaskId) : DomainCommand
/** afterTaskId = null moves the entry to the top. */
data class MoveMyDayEntry(val taskId: TaskId, val afterTaskId: TaskId?) : DomainCommand
/** Removes members completed strictly before [before]. Idempotent. */
data class SweepMyDay(val before: Instant) : DomainCommand
/** Repository-produced Undo only; never constructed by UI code. */
data class RestoreMyDayEntries(val entries: List<MyDayEntry>) : DomainCommand

// RejectionReason additions (APPEND at the end of the enum)
MY_DAY_LIMIT_REACHED,

// Both repository companions
const val MAX_MY_DAY_ENTRIES = 200
```

`HomeSnapshot.myDayTasks` becomes real in both engines: members in rank
order resolved to tasks, binned (`deletedAt != null`) filtered out,
completed members retained (the UI dims them).

- [ ] **Step 1: Write the failing pure-rule tests**

`MyDayRulesTest.kt` core assertions:

```kotlin
@Test
fun rankBetweenOrdersStrictlyAndFallsBackToNull() {
    val mid = requireNotNull(myDayRankBetween("a0", "a1"))
    assertTrue("a0" < mid && mid < "a1")
    val head = requireNotNull(myDayRankBetween(null, "a0"))
    assertTrue(head < "a0")
    val tail = requireNotNull(myDayRankBetween("a1", null))
    assertTrue(tail > "a1")
    assertNull(myDayRankBetween("m".repeat(MAX_MY_DAY_RANK_LENGTH), "m".repeat(199) + "n"))
}

@Test
fun rankForIndexIsStrictlyIncreasing() {
    assertTrue(myDayRankForIndex(0) < myDayRankForIndex(1))
    assertTrue(myDayRankForIndex(199) < myDayRankForIndex(200))
}

@Test
fun suggestionsAreOverdueThenTodayOpenNonMembersCappedAtTen() { … }
```

Populate the suggestions test with `OpenTasksFixtures.tasks` copies:
one overdue open task, one due-today, one start-today, one completed
(excluded), one binned (excluded), one member (excluded), twelve
due-today to prove the cap of 10; assert exact order overdue-first.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:domain:testDebugUnitTest --tests "*MyDayRulesTest*"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement MyDayRules**

```kotlin
package app.opentasks.core.domain

import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import java.time.LocalDate
import java.time.ZoneId

const val MAX_MY_DAY_RANK_LENGTH = 200

private const val RANK_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

fun myDayRankForIndex(index: Int): String =
    "r" + index.toString(36).padStart(6, '0')

fun myDayRankBetween(previous: String?, next: String?): String? {
    val candidate: String? = when {
        next == null -> (previous ?: "") + "m"
        previous == null -> midpointBelow(next)
        else -> midpointBetween(previous, next)
    }
    // The final guard makes correctness unconditional: any candidate
    // that is not strictly between its neighbours (or is over the
    // bound) becomes null, and the caller re-ranks the whole list.
    return candidate?.takeIf {
        it.length <= MAX_MY_DAY_RANK_LENGTH &&
            (previous == null || it > previous) &&
            (next == null || it < next)
    }
}

private fun midpointBelow(next: String): String? {
    // Any string strictly below `next`: step its first digit down and
    // re-open the space with a trailing "m". A floor first digit (or a
    // character outside the alphabet, possible in recovered foreign
    // ranks) yields null and the caller re-ranks.
    val first = RANK_ALPHABET.indexOf(next.first())
    return if (first > 0) "${RANK_ALPHABET[first - 1]}m" else null
}

private fun midpointBetween(previous: String, next: String): String? {
    val prefix = previous.commonPrefixWith(next)
    val lowDigit = previous.getOrNull(prefix.length)
        ?.let(RANK_ALPHABET::indexOf) ?: -1
    val highDigit = next.getOrNull(prefix.length)
        ?.let(RANK_ALPHABET::indexOf) ?: return null
    return if (highDigit - lowDigit > 1) {
        prefix + RANK_ALPHABET[(lowDigit + highDigit) / 2]
    } else {
        // Adjacent digits: extend the lower bound instead. The outer
        // guard rejects the candidate when it does not fit below next.
        previous + "m"
    }
}

fun myDaySuggestions(
    tasks: List<Task>,
    memberIds: Set<TaskId>,
    today: LocalDate,
    zoneId: ZoneId,
): List<Task> {
    val open = tasks.filter {
        it.deletedAt == null && !it.isCompleted && it.id !in memberIds
    }
    fun localDate(moment: app.opentasks.core.model.ZonedMoment): LocalDate =
        moment.instant.atZone(moment.zone()).toLocalDate()
    val overdue = open.filter { task ->
        task.due?.let { localDate(it) < today } == true
    }
    val todays = open.filter { task ->
        task !in overdue &&
            (task.due?.let { localDate(it) == today } == true ||
                task.start?.let { localDate(it) == today } == true)
    }
    val byDueThenTitle = compareBy<Task>(
        { it.due?.instant ?: it.start?.instant },
        { it.title.lowercase() },
        { it.id.value },
    )
    return (overdue.sortedWith(byDueThenTitle) + todays.sortedWith(byDueThenTitle))
        .take(10)
}
```

Note the ponytail ceiling: `myDayRankBetween` is a simple base-36
midpoint; if it ever returns null the handler re-ranks the whole list —
correctness never depends on midpoint density. If the loop logic proves
fiddly under test, the acceptable simplification is: return null
whenever a single-character midpoint between the first differing digits
does not exist, and re-rank; the tests above only require strict
ordering, head/tail insertion, and the bounded-null fallback.

Run: `./gradlew :core:domain:testDebugUnitTest --tests "*MyDayRulesTest*"`
Expected: PASS.

- [ ] **Step 4: Write the failing dual-engine command tests**

`InMemoryVaultRepositoryTest.kt` additions (same bodies go to
`RoomVaultRepositoryInstrumentedTest.kt` with the Room harness):

```kotlin
@Test
fun addRemoveAndReorderMyDayEntries() = runBlocking {
    withTimeout(5_000) {
        val repository = InMemoryVaultRepository()
        val tasks = repository.currentWorkspace().tasks.filter { it.deletedAt == null }.take(3)
        tasks.forEach { task ->
            assertTrue(
                repository.execute(DomainCommand.AddTaskToMyDay(task.id))
                    is CommandResult.Success,
            )
        }
        assertEquals(
            tasks.map(Task::id),
            repository.currentWorkspace().home.myDayTasks.map(Task::id),
        )

        // Move the last entry to the top.
        val move = repository.execute(
            DomainCommand.MoveMyDayEntry(tasks[2].id, afterTaskId = null),
        )
        assertTrue(move is CommandResult.Success)
        assertEquals(
            listOf(tasks[2].id, tasks[0].id, tasks[1].id),
            repository.currentWorkspace().home.myDayTasks.map(Task::id),
        )

        // Undo restores the previous rank.
        val undo = (move as CommandResult.Success).undo
        assertTrue(undo is DomainCommand.RestoreMyDayEntries)
        repository.execute(requireNotNull(undo))
        assertEquals(
            tasks.map(Task::id),
            repository.currentWorkspace().home.myDayTasks.map(Task::id),
        )

        // Duplicate add is an idempotent success that changes nothing.
        val duplicate = repository.execute(DomainCommand.AddTaskToMyDay(tasks[0].id))
        assertTrue(duplicate is CommandResult.Success)
        assertEquals(3, repository.currentWorkspace().myDay.size)

        // Remove round-trips through its undo.
        val removed = repository.execute(DomainCommand.RemoveTaskFromMyDay(tasks[1].id))
        assertTrue(removed is CommandResult.Success)
        assertEquals(2, repository.currentWorkspace().myDay.size)
        repository.execute(requireNotNull((removed as CommandResult.Success).undo))
        assertEquals(3, repository.currentWorkspace().myDay.size)
    }
}

@Test
fun myDayBoundBinFilterAndSweep() = runBlocking {
    withTimeout(5_000) {
        val repository = InMemoryVaultRepository()
        val snapshot = repository.currentWorkspace()
        val open = snapshot.tasks.first { it.deletedAt == null && !it.isCompleted }

        repository.execute(DomainCommand.AddTaskToMyDay(open.id))

        // Binned member: row retained, projection hides it.
        repository.execute(DomainCommand.DeleteTask(open.id))
        assertEquals(1, repository.currentWorkspace().myDay.size)
        assertTrue(repository.currentWorkspace().home.myDayTasks.isEmpty())
        repository.execute(DomainCommand.RestoreTask(open.id))

        // Completed member stays visible (dimmed by the UI) until swept.
        repository.execute(DomainCommand.CompleteTask(open.id))
        assertEquals(
            listOf(open.id),
            repository.currentWorkspace().home.myDayTasks.map(Task::id),
        )
        val sweep = repository.execute(
            DomainCommand.SweepMyDay(before = Instant.parse("2100-01-01T00:00:00Z")),
        )
        assertTrue(sweep is CommandResult.Success)
        assertTrue(repository.currentWorkspace().myDay.isEmpty())

        // Sweeping again is a journal-free no-op.
        val again = repository.execute(
            DomainCommand.SweepMyDay(before = Instant.parse("2100-01-01T00:00:00Z")),
        )
        assertTrue(again is CommandResult.Success)
    }
}

@Test
fun addToMyDayRejectsBinnedTasksAndTheBound() { … }
```

The bound test adds 200 synthetic open tasks via
`DomainCommand.CreateTask(title = "seed-$index")`, adds each to My Day,
then asserts the 201st add is
`CommandResult.Rejected(MY_DAY_LIMIT_REACHED, …)` and a binned task's
add is `Rejected(INVALID_STATE, …)`.

`MyDayFamilyTest.kt`, in the `RetiredBlobSetFamilyTest` mold: add →
journal upsert `objectType == "MY_DAY"`; reorder → upsert for moved rows
only; remove → DELETE entry; `PermanentlyDeleteTask` of a member →
DELETE entry in the same generation as the purge.

- [ ] **Step 5: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*MyDay*"`
Expected: FAIL — commands unresolved.

- [ ] **Step 6: Implement commands in BOTH engines**

Domain declarations exactly as the Interfaces block; append
`MY_DAY_LIMIT_REACHED` to `RejectionReason`; add
`MAX_MY_DAY_ENTRIES = 200` to both companions; register the five
commands in both `dispatch` when-trees.

Room handlers (beside the saved-view handlers; all writes inside the
outer execute transaction; My Day writes never touch task revisions):

```kotlin
private suspend fun addTaskToMyDay(command: DomainCommand.AddTaskToMyDay): CommandResult {
    val task = database.taskDao().getById(command.taskId.value)
        ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
    if (task.deletedAtEpochMillis != null) {
        return CommandResult.Rejected(
            RejectionReason.INVALID_STATE,
            "Restore that task before planning it.",
        )
    }
    if (database.workspaceDao().getMyDayEntry(command.taskId.value) != null) {
        return CommandResult.Success("Already on My Day")
    }
    val entries = database.workspaceDao().getMyDayEntries()
    if (entries.size >= MAX_MY_DAY_ENTRIES) {
        return CommandResult.Rejected(
            RejectionReason.MY_DAY_LIMIT_REACHED,
            "My Day holds up to $MAX_MY_DAY_ENTRIES tasks.",
        )
    }
    val last = entries.maxByOrNull(MyDayEntryEntity::rank)?.rank
    database.workspaceDao().upsertMyDayEntry(
        MyDayEntryEntity(command.taskId.value, rankAfter(last)),
    )
    return CommandResult.Success(
        message = "Added to My Day",
        undo = DomainCommand.RemoveTaskFromMyDay(command.taskId),
    )
}

private suspend fun removeTaskFromMyDay(
    command: DomainCommand.RemoveTaskFromMyDay,
): CommandResult {
    val entry = database.workspaceDao().getMyDayEntry(command.taskId.value)
        ?: return CommandResult.Success("Not on My Day")
    database.workspaceDao().deleteMyDayEntry(entry.taskId)
    return CommandResult.Success(
        message = "Removed from My Day",
        undo = DomainCommand.RestoreMyDayEntries(listOf(entry.toModel())),
    )
}

private suspend fun moveMyDayEntry(command: DomainCommand.MoveMyDayEntry): CommandResult {
    val entries = database.workspaceDao().getMyDayEntries()
    val moving = entries.firstOrNull { it.taskId == command.taskId.value }
        ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Not on My Day.")
    val anchored = entries.filterNot { it.taskId == moving.taskId }
    val anchorIndex = command.afterTaskId?.let { after ->
        anchored.indexOfFirst { it.taskId == after.value }
            .takeIf { it >= 0 }
            ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Not on My Day.")
    }
    val previousRank = anchorIndex?.let { anchored[it].rank }
    val nextRank = when (anchorIndex) {
        null -> anchored.firstOrNull()?.rank
        else -> anchored.getOrNull(anchorIndex + 1)?.rank
    }
    if (previousRank == moving.rank || nextRank == moving.rank) {
        return CommandResult.Success("My Day order is unchanged")
    }
    val between = myDayRankBetween(previousRank, nextRank)
    return if (between != null) {
        database.workspaceDao().upsertMyDayEntry(moving.copy(rank = between))
        CommandResult.Success(
            message = "My Day reordered",
            undo = DomainCommand.RestoreMyDayEntries(listOf(moving.toModel())),
        )
    } else {
        // ponytail: midpoint exhausted — deterministic full re-rank in
        // the same transaction; every row journals once, which is rare
        // and bounded at 200 rows.
        val reordered = buildList {
            addAll(anchored.take(anchorIndex?.plus(1) ?: 0))
            add(moving)
            addAll(anchored.drop(anchorIndex?.plus(1) ?: 0))
        }
        reordered.forEachIndexed { index, entry ->
            database.workspaceDao().upsertMyDayEntry(
                entry.copy(rank = myDayRankForIndex(index)),
            )
        }
        CommandResult.Success(
            message = "My Day reordered",
            undo = DomainCommand.RestoreMyDayEntries(entries.map { it.toModel() }),
        )
    }
}

private suspend fun sweepMyDay(command: DomainCommand.SweepMyDay): CommandResult {
    val entries = database.workspaceDao().getMyDayEntries()
    val removed = entries.filter { entry ->
        val task = database.taskDao().getById(entry.taskId)
        task == null || task.completedAtEpochMillis?.let {
            it < command.before.toEpochMilli()
        } == true
    }
    removed.forEach { database.workspaceDao().deleteMyDayEntry(it.taskId) }
    return CommandResult.Success(
        message = if (removed.isEmpty()) "My Day is up to date" else "My Day tidied",
        undo = DomainCommand.RestoreMyDayEntries(removed.map { it.toModel() })
            .takeIf { removed.isNotEmpty() },
    )
}

private suspend fun restoreMyDayEntries(
    command: DomainCommand.RestoreMyDayEntries,
): CommandResult {
    command.entries.forEach { entry ->
        database.workspaceDao().upsertMyDayEntry(entry.toEntity())
    }
    return CommandResult.Success("My Day restored")
}
```

`rankAfter` already exists privately in both engines; keep using it for
appends. InMemory mirrors each handler over `mutableWorkspace.value.myDay`
with `publish(myDay = …)` — extend the engine's `publish` helper with a
`myDay: List<MyDayEntry> = current.myDay` parameter, keep the list
sorted by `(rank, taskId)`, and mirror the purge/permanent-delete paths
(`permanentlyDeleteTask`, `purgeExpiredTrash`) to drop entries for
purged ids. Room purge cleanup already landed in Task 1's `purgeTask`.

Home projection, both engines — in `buildSnapshot`
(RoomVaultRepository.kt, beside `home =`):

```kotlin
val tasksById = tasks.associateBy(Task::id)
val myDayTasks = relations.myDayEntries
    .sortedWith(compareBy(MyDayEntryEntity::rank, MyDayEntryEntity::taskId))
    .mapNotNull { tasksById[TaskId(it.taskId)] }
    .filter { it.deletedAt == null }
```

set `home = HomeSnapshot(…, myDayTasks = myDayTasks, …)` and
`myDay = …` as in Task 1. InMemory computes the same list in `publish`
and re-resolves it in `withResolvedDependencyState` via the existing
`mapNotNull { tasksById[it.id] }` pattern.

- [ ] **Step 7: Run the suites**

Run: `./gradlew :core:data:testDebugUnitTest :core:domain:testDebugUnitTest`
Expected: PASS, including `MyDayFamilyTest` journal assertions.

- [ ] **Step 8: Compile instrumented twins and commit**

Run: `./gradlew :core:data:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add core/domain core/data
git commit -m "feat: add my day store commands with manual rank in both engines"
```

---

### Task 5: Automation rule commands in both engines

**Files:**

- Modify: `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`
- Create: `core/data/src/test/kotlin/app/opentasks/core/data/backup/AutomationRuleFamilyTest.kt`
- Test: `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`

**Interfaces:**

- Consumes: Task 1 model/table, Task 3 family journaling.
- Produces:

```kotlin
data class CreateAutomationRule(val rule: AutomationRule) : DomainCommand
data class UpdateAutomationRule(val rule: AutomationRule) : DomainCommand
data class DeleteAutomationRule(val ruleId: AutomationRuleId) : DomainCommand

// RejectionReason additions (APPEND)
AUTOMATION_RULE_LIMIT_REACHED,
AUTOMATION_RULE_INVALID,

// Both companions
const val MAX_AUTOMATION_RULES = 20
```

Undo shapes later tasks rely on: Create → `DeleteAutomationRule(id)`;
Update → `UpdateAutomationRule(previous)`; Delete →
`CreateAutomationRule(rule)`.

- [ ] **Step 1: Write the failing dual-engine tests**

`InMemoryVaultRepositoryTest.kt` additions (instrumented twins mirror):

```kotlin
private fun addTagRule(
    repository: InMemoryVaultRepository,
    statusId: WorkflowStatusId,
    tagId: TagId,
    enabled: Boolean = true,
): AutomationRule = AutomationRule(
    id = AutomationRuleId.new(),
    workspaceId = OpenTasksFixtures.workspaceId,
    type = AutomationRuleType.ON_ENTER_ADD_TAG,
    enabled = enabled,
    statusId = statusId,
    tagId = tagId,
)

@Test
fun automationRuleCrudRoundTripsWithUndo() = runBlocking {
    withTimeout(5_000) {
        val repository = InMemoryVaultRepository()
        val snapshot = repository.currentWorkspace()
        val rule = addTagRule(
            repository,
            statusId = snapshot.workflowStatuses.first().id,
            tagId = snapshot.tags.first().id,
        )
        val created = repository.execute(DomainCommand.CreateAutomationRule(rule))
        assertTrue(created is CommandResult.Success)
        assertEquals(listOf(rule), repository.currentWorkspace().automationRules)

        val disabled = rule.copy(enabled = false)
        val updated = repository.execute(DomainCommand.UpdateAutomationRule(disabled))
        assertTrue(updated is CommandResult.Success)
        assertEquals(listOf(disabled), repository.currentWorkspace().automationRules)
        repository.execute(requireNotNull((updated as CommandResult.Success).undo))
        assertEquals(listOf(rule), repository.currentWorkspace().automationRules)

        val deleted = repository.execute(DomainCommand.DeleteAutomationRule(rule.id))
        assertTrue(deleted is CommandResult.Success)
        assertTrue(repository.currentWorkspace().automationRules.isEmpty())
        repository.execute(requireNotNull((deleted as CommandResult.Success).undo))
        assertEquals(listOf(rule), repository.currentWorkspace().automationRules)
    }
}

@Test
fun automationRuleValidationRejectsBadConfigMissingRefsAndTheBound() { … }
```

The validation test asserts: wrong per-type config (an
`ON_ENTER_ADD_TAG` without `tagId`) → `AUTOMATION_RULE_INVALID`; a
`tagId`/`statusId`/`projectId` that does not exist → `NOT_FOUND`;
`dueInDays = 366` and `thresholdDays = 0` → `AUTOMATION_RULE_INVALID`;
the 21st rule → `AUTOMATION_RULE_LIMIT_REACHED`.

`AutomationRuleFamilyTest.kt`: create journals `AUTOMATION_RULE` upsert;
update journals upsert (content fingerprint — no revision discipline);
delete journals DELETE; a rejected create journals nothing.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*AutomationRule*"`
Expected: FAIL.

- [ ] **Step 3: Implement in both engines**

Shared validation as a private function in each engine (a pure config
check plus reference checks against that engine's storage):

```kotlin
private fun automationRuleConfigRejection(rule: AutomationRule): CommandResult.Rejected? {
    fun invalid(detail: String) = CommandResult.Rejected(
        RejectionReason.AUTOMATION_RULE_INVALID,
        "This rule is not valid: $detail.",
    )
    rule.dueInDays?.let { if (it !in 0..365) return invalid("days must be 0–365") }
    rule.thresholdDays?.let { if (it !in 1..365) return invalid("days must be 1–365") }
    val requirement = when (rule.type) {
        AutomationRuleType.ON_ENTER_ADD_TAG ->
            rule.statusId != null && rule.tagId != null &&
                rule.dueInDays == null && rule.thresholdDays == null
        AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY ->
            rule.statusId != null && rule.tagId == null &&
                rule.dueInDays == null && rule.thresholdDays == null
        AutomationRuleType.ON_ENTER_SET_DUE ->
            rule.statusId != null && rule.dueInDays != null &&
                rule.tagId == null && rule.thresholdDays == null
        AutomationRuleType.MY_DAY_AUTO_REMOVE ->
            rule.projectId == null && rule.statusId == null && rule.tagId == null &&
                rule.dueInDays == null && rule.thresholdDays == null
        AutomationRuleType.STALE_BADGE ->
            rule.thresholdDays != null && rule.statusId == null &&
                rule.tagId == null && rule.dueInDays == null
    }
    return if (requirement) null else invalid("its settings do not match its type")
}
```

Room reference checks: `rule.statusId` via
`database.workspaceDao().getWorkflowStatus(...)`, `rule.projectId` via
`getProjectById(...)`, `rule.tagId` via a tag read — reuse whatever DAO
read the `setTaskTag` handler uses; if none exists by id, add
`@Query("SELECT * FROM tags WHERE id = :id LIMIT 1") suspend fun
getTagById(id: String): TagEntity?` to `WorkspaceDao`. Missing ref →
`Rejected(NOT_FOUND, "That rule refers to something that no longer exists.")`.
InMemory checks the same against `mutableWorkspace.value` lists.

Create: bound check (`getAutomationRules().size >= MAX_AUTOMATION_RULES`
→ `AUTOMATION_RULE_LIMIT_REACHED`, "Up to 20 automation rules."), id
collision → `INVALID_STATE`; upsert; undo `DeleteAutomationRule`.
Update: must exist (`NOT_FOUND`), same validation; undo carries the
previous model. Delete: absent → idempotent `Success("Rule is already
removed")`; else delete, undo `CreateAutomationRule(previous)`.
Workspace check: `rule.workspaceId` must equal the fixture workspace id
(`OpenTasksFixtures.workspaceId`), else `AUTOMATION_RULE_INVALID`.
Register all three in both dispatch when-trees; InMemory operates on a
`WorkspaceSnapshot.automationRules` copy via `publish(automationRules = …)`
(extend `publish` the same way as `myDay`).

- [ ] **Step 4: Run and commit**

Run: `./gradlew :core:data:testDebugUnitTest && ./gradlew :core:data:compileDebugAndroidTestKotlin`
Expected: PASS / BUILD SUCCESSFUL.

```bash
git add core/domain core/data
git commit -m "feat: add automation rule crud with validation in both engines"
```

---

### Task 6: WIP limits — command and repository enforcement

**Files:**

- Modify: `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
  (`ChangeTaskStatus`, new command, reasons)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
  (`changeTaskStatus` at 1979+, new handler beside the workflow handlers
  at 821-1037, TaskDao query)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
  (one TaskDao query)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`
- Test: `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`

**Interfaces:**

- Consumes: `wipLimit` on model/entity (Task 1), `persistWorkflowStatuses`
  (RoomVaultRepository.kt:3078-3101), the blocked-completion
  acknowledge precedent (RoomVaultRepository.kt:1994-2003).
- Produces:

```kotlin
// ChangeTaskStatus gains one field (positioned after acknowledgeBlocked):
data class ChangeTaskStatus(
    val taskId: TaskId,
    val statusId: WorkflowStatusId,
    val acknowledgeBlocked: Boolean = false,
    val acknowledgeWipLimit: Boolean = false,
    val changedAt: Instant = Instant.now(),
) : DomainCommand

data class SetWorkflowStatusWipLimit(
    val statusId: WorkflowStatusId,
    val wipLimit: Int?,          // null clears the limit
) : DomainCommand

// RejectionReason additions (APPEND)
WIP_LIMIT_CONFIRM_REQUIRED,
WIP_LIMIT_INVALID,
```

Undo of `SetWorkflowStatusWipLimit` is
`RestoreWorkflowStatuses(listOf(original))` — the standard workflow
undo, which round-trips `wipLimit` because the model carries it.

- [ ] **Step 1: Write the failing dual-engine tests**

```kotlin
@Test
fun wipLimitSetClearValidateAndUndo() = runBlocking {
    withTimeout(5_000) {
        val repository = InMemoryVaultRepository()
        val status = repository.currentWorkspace().workflowStatuses.first {
            it.projectId == OpenTasksFixtures.studioProject.id &&
                it.semanticStatus == SemanticStatus.STARTED
        }
        val set = repository.execute(
            DomainCommand.SetWorkflowStatusWipLimit(status.id, 2),
        )
        assertTrue(set is CommandResult.Success)
        assertEquals(
            2,
            repository.currentWorkspace().workflowStatuses
                .first { it.id == status.id }.wipLimit,
        )
        repository.execute(requireNotNull((set as CommandResult.Success).undo))
        assertNull(
            repository.currentWorkspace().workflowStatuses
                .first { it.id == status.id }.wipLimit,
        )
        assertTrue(
            repository.execute(DomainCommand.SetWorkflowStatusWipLimit(status.id, 0))
                is CommandResult.Rejected,
        )
        val done = repository.currentWorkspace().workflowStatuses.first {
            it.projectId == status.projectId &&
                it.semanticStatus == SemanticStatus.COMPLETED
        }
        val onDone = repository.execute(DomainCommand.SetWorkflowStatusWipLimit(done.id, 2))
        assertTrue(onDone is CommandResult.Rejected)
        assertEquals(
            RejectionReason.WIP_LIMIT_INVALID,
            (onDone as CommandResult.Rejected).reason,
        )
    }
}

@Test
fun changeTaskStatusConfirmsOverLimitAndNeverBlocks() = runBlocking {
    withTimeout(5_000) {
        val repository = InMemoryVaultRepository()
        val project = OpenTasksFixtures.studioProject
        val snapshot = repository.currentWorkspace()
        val started = snapshot.workflowStatuses.first {
            it.projectId == project.id && it.semanticStatus == SemanticStatus.STARTED
        }
        repository.execute(DomainCommand.SetWorkflowStatusWipLimit(started.id, 1))

        // Fill the column to its limit with one open task.
        val movable = snapshot.tasks.filter {
            it.projectId == project.id && it.deletedAt == null &&
                !it.isCompleted && it.statusId != started.id
        }
        repository.execute(DomainCommand.ChangeTaskStatus(movable[0].id, started.id))

        val over = repository.execute(
            DomainCommand.ChangeTaskStatus(movable[1].id, started.id),
        )
        assertTrue(over is CommandResult.Rejected)
        assertEquals(
            RejectionReason.WIP_LIMIT_CONFIRM_REQUIRED,
            (over as CommandResult.Rejected).reason,
        )

        // Acknowledged, the same move succeeds — soft limit, never a block.
        val acknowledged = repository.execute(
            DomainCommand.ChangeTaskStatus(
                movable[1].id,
                started.id,
                acknowledgeWipLimit = true,
            ),
        )
        assertTrue(acknowledged is CommandResult.Success)

        // Completion is exempt: moving into a COMPLETED column with a
        // full source column never trips the gate.
        val completion = repository.execute(DomainCommand.CompleteTask(movable[1].id))
        assertTrue(completion is CommandResult.Success)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*InMemoryVaultRepositoryTest*"`
Expected: FAIL — unresolved command / no rejection raised.

- [ ] **Step 3: Implement**

`VaultDatabase.kt` TaskDao gains:

```kotlin
@Query(
    """
    SELECT COUNT(*) FROM tasks
    WHERE statusId = :statusId AND deletedAtEpochMillis IS NULL
        AND semanticStatus != 'COMPLETED'
    """,
)
suspend fun openTaskCountForStatus(statusId: String): Int
```

Room `changeTaskStatus` — insert between the blocked-completion gate
(1994-2003) and the same-status no-op (2004-2006):

```kotlin
val limit = status.wipLimit
if (
    limit != null &&
    status.semanticStatus != SemanticStatus.COMPLETED &&
    task.statusId != status.id &&
    !command.acknowledgeWipLimit &&
    database.taskDao().openTaskCountForStatus(status.id.value) + 1 > limit
) {
    return CommandResult.Rejected(
        RejectionReason.WIP_LIMIT_CONFIRM_REQUIRED,
        "“${status.name}” is at its limit of $limit open tasks.",
    )
}
```

InMemory mirrors with
`current.tasks.count { it.statusId == status.id && it.deletedAt == null && !it.isCompleted }`.
`CompleteTask` keeps delegating with its existing arguments — the
COMPLETED-semantic guard makes the exemption structural; recurrence
spawns, bulk remaps, `RestoreTaskStatus`, and rule outputs never enter
this branch.

`setWorkflowStatusWipLimit` handler (Room; InMemory mirrors over
snapshot lists):

```kotlin
private suspend fun setWorkflowStatusWipLimit(
    command: DomainCommand.SetWorkflowStatusWipLimit,
): CommandResult {
    val entity = database.workspaceDao().getWorkflowStatus(command.statusId.value)
        ?: return CommandResult.Rejected(
            RejectionReason.NOT_FOUND,
            "Workflow status no longer exists.",
        )
    val original = entity.toModel()
    if (original.archivedAt != null) {
        return CommandResult.Rejected(
            RejectionReason.INVALID_STATE,
            "Restore that workflow status before changing its limit.",
        )
    }
    if (original.semanticStatus == SemanticStatus.COMPLETED) {
        return CommandResult.Rejected(
            RejectionReason.WIP_LIMIT_INVALID,
            "Completed columns do not take a limit.",
        )
    }
    command.wipLimit?.let {
        if (it !in 1..200) {
            return CommandResult.Rejected(
                RejectionReason.WIP_LIMIT_INVALID,
                "Limits run from 1 to 200.",
            )
        }
    }
    if (command.wipLimit == original.wipLimit) {
        return CommandResult.Success("Limit is unchanged")
    }
    persistWorkflowStatuses(
        statuses = listOf(original.copy(wipLimit = command.wipLimit)),
        previousEntities = listOf(entity),
    )
    return CommandResult.Success(
        message = if (command.wipLimit == null) "Limit cleared" else "Limit set",
        undo = DomainCommand.RestoreWorkflowStatuses(listOf(original)),
    )
}
```

Writing through `persistWorkflowStatuses` bumps the status revision, so
the revision-tier WORKFLOW_STATUS fingerprint journals the change — a
direct upsert would silently never journal.

- [ ] **Step 4: Run, add a journal assertion, and commit**

Extend `AutomationRuleFamilyTest`'s sibling or add to
`InMemoryVaultRepositoryTest`: a `SetWorkflowStatusWipLimit` journals a
`WORKFLOW_STATUS` upsert.

Run: `./gradlew :core:data:testDebugUnitTest && ./gradlew :core:data:compileDebugAndroidTestKotlin`
Expected: PASS.

```bash
git add core/domain core/data
git commit -m "feat: enforce soft per-column wip limits in both engines"
```

---

### Task 7: WIP limits — editor field, board badge, unified confirm path

**Files:**

- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`
  (`WorkflowEditorSheet` at 1539-1757, `WorkflowStatusEditorRow` at 1759-1844)
- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/BoardView.kt`
  (column header at 163-179)
- Modify: `feature/projects/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
  (beside `changeTaskStatus` at 402-419 and `confirmBlockedCompletion`
  at 488-507)
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
  (board wiring at 1443-1447, editor wiring at 1457-1494, dialogs beside
  1908-1941)
- Modify: `app/src/main/res/values/strings.xml`
- Test: `feature/projects/src/test/…` — follow where the module's
  existing Compose tests live (`BoardViewTest`/`ProjectsScreenTest`
  under `src/androidTest`); add to the same source set.

**Interfaces:**

- Consumes: Task 6 command + rejection; `pendingBlocked` precedent
  (WorkspaceViewModel.kt:402-419, 488-507); `BoardColumn.status.wipLimit`
  (free via Task 1 — `BoardColumn` itself is unchanged).
- Produces:
  - `WorkflowEditorSheet`/`WorkflowStatusEditorRow` gain
    `onSetWipLimit: (WorkflowStatusId, Int?) -> Unit`.
  - `WorkspaceViewModel`: `data class PendingWipMove(val task: Task, val
    statusId: WorkflowStatusId)`, exposed as `pendingWipMove:
    StateFlow<PendingWipMove?>` with `confirmWipMove()` /
    `dismissWipMove()`.
  - The projects-board move path routes through
    `viewModel.changeTaskStatus(task, statusId)` — fixing the latent gap
    where board drops bypassed the blocked-completion confirm.

- [ ] **Step 1: Write the failing Compose tests**

In the projects feature's existing Compose test source set, driving the
stateless screens with `OpenTasksFixtures` data inside
`OpenTasksTheme { }` via
`androidx.compose.ui.test.junit4.v2.createComposeRule`:

```kotlin
@Test
fun boardColumnHeaderShowsCountAgainstLimitAndOverLimitState() {
    val column = BoardColumn(
        status = OpenTasksFixtures.snapshot.workflowStatuses
            .first { it.semanticStatus == SemanticStatus.STARTED &&
                it.projectId == OpenTasksFixtures.studioProject.id }
            .copy(wipLimit = 2),
        tasks = OpenTasksFixtures.tasks.take(3),
    )
    composeRule.setContent {
        OpenTasksTheme {
            BoardView(
                columns = listOf(column),
                columnWidth = 280.dp,
                onMoveTask = { _, _ -> },
                onOpenTask = { },
            )
        }
    }
    composeRule.onNodeWithText("3 / 2").assertIsDisplayed()
}

@Test
fun editorRowHidesLimitFieldForCompletedColumnsAndSavesOthers() {
    var saved: Pair<WorkflowStatusId, Int?>? = null
    composeRule.setContent {
        OpenTasksTheme {
            // Instantiate WorkflowEditorSheet with the fixture project,
            // its statuses, onSetWipLimit = { id, limit -> saved = id to limit },
            // remaining callbacks as no-ops.
        }
    }
    composeRule.onNodeWithTag(
        "workflow-wip-${startedStatusId.value}",
    ).performTextReplacement("3")
    composeRule.onNodeWithTag(
        "save-workflow-wip-${startedStatusId.value}",
    ).performClick()
    assertEquals(startedStatusId to 3, saved)
    composeRule.onNodeWithTag(
        "workflow-wip-${doneStatusId.value}",
    ).assertDoesNotExist()
}
```

(Resolve `startedStatusId`/`doneStatusId` from
`OpenTasksFixtures.started` / `OpenTasksFixtures.done`.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :feature:projects:compileDebugAndroidTestKotlin`
Expected: FAIL — new parameters/tags unresolved.

- [ ] **Step 3: Implement the feature UI**

`WorkflowStatusEditorRow`: below the existing rename row, when
`status.semanticStatus != SemanticStatus.COMPLETED`, an
`OutlinedTextField` (`KeyboardType.Number`, testTag
`"workflow-wip-${status.id.value}"`) initialised from
`status.wipLimit?.toString().orEmpty()`, a save `TextButton` (testTag
`"save-workflow-wip-${status.id.value}"`, ≥48 dp) enabled when the
parsed value differs (blank ⇒ null clears; non-numeric or outside 1..200
shows inline error text and disables save), calling
`onSetWipLimit(status.id, parsed)`. Thread the callback through
`WorkflowEditorSheet` and `ProjectsScreen` to `OpenTasksApp`, which
dispatches `DomainCommand.SetWorkflowStatusWipLimit(statusId, limit)`
via `viewModel.execute` (rejections surface as the ordinary snackbar).

`BoardView` header: replace the plural count text with a limited/
unlimited pair:

```kotlin
val limit = column.status.wipLimit
Text(
    text = if (limit == null) {
        pluralStringResource(
            R.plurals.board_open_task_count,
            column.tasks.size,
            column.tasks.size,
        )
    } else {
        stringResource(R.string.board_wip_count, column.tasks.size, limit)
    },
    style = MaterialTheme.typography.labelMedium,
    color = if (limit != null && column.tasks.size > limit) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    },
)
```

`feature/projects/src/main/res/values/strings.xml` gains:

```xml
<string name="board_wip_count">%1$d / %2$d</string>
<string name="workflow_wip_label">Column limit</string>
<string name="workflow_wip_error">Limits run from 1 to 200</string>
<string name="workflow_wip_save">Save limit</string>
```

(Use `workflow_wip_label`/`workflow_wip_save` for the field label and
button text.)

- [ ] **Step 4: Unify the board path and add the confirm dialog**

`WorkspaceViewModel`: mirror the blocked pattern exactly —

```kotlin
data class PendingWipMove(val task: Task, val statusId: WorkflowStatusId)

private val pendingWip = MutableStateFlow<PendingWipMove?>(null)
val pendingWipMove: StateFlow<PendingWipMove?> = pendingWip.asStateFlow()

fun confirmWipMove() {
    val pending = pendingWip.value ?: return
    pendingWip.value = null
    execute(
        DomainCommand.ChangeTaskStatus(
            taskId = pending.task.id,
            statusId = pending.statusId,
            acknowledgeWipLimit = true,
        ),
    )
}

fun dismissWipMove() {
    pendingWip.value = null
}
```

and in `changeTaskStatus(task, statusId)`'s rejection branch, before the
generic snackbar case:

```kotlin
result.reason == RejectionReason.WIP_LIMIT_CONFIRM_REQUIRED ->
    pendingWip.value = PendingWipMove(task, statusId)
```

`OpenTasksApp`: reroute the projects-board callback (currently a raw
`viewModel.execute` at 1443-1447):

```kotlin
onChangeTaskStatus = { taskId, statusId ->
    snapshot.tasks.firstOrNull { it.id == taskId }?.let { task ->
        viewModel.changeTaskStatus(task, statusId)
    }
},
```

and render the dialog beside the blocked one (collect
`pendingWipMove`):

```kotlin
pendingWipMove?.let { pending ->
    val statusName = snapshot.workflowStatuses
        .firstOrNull { it.id == pending.statusId }?.name.orEmpty()
    AlertDialog(
        onDismissRequest = viewModel::dismissWipMove,
        title = { Text(stringResource(R.string.wip_confirm_title)) },
        text = {
            Text(stringResource(R.string.wip_confirm_body, statusName))
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmWipMove) {
                Text(stringResource(R.string.wip_confirm_move))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissWipMove) {
                Text(stringResource(R.string.wip_confirm_keep))
            }
        },
    )
}
```

`app/src/main/res/values/strings.xml`:

```xml
<string name="wip_confirm_title">Column is at its limit</string>
<string name="wip_confirm_body">“%1$s” already holds its planned number of open tasks. Move this one anyway?</string>
<string name="wip_confirm_move">Move anyway</string>
<string name="wip_confirm_keep">Keep it where it is</string>
```

Because enforcement is repository-side, drag, the 48 dp card menu, and
the accessibility custom actions all reach the same dialog through the
one rerouted callback — and board drops now also surface the
blocked-completion confirm that previously degraded to a snackbar.

- [ ] **Step 5: Run, verify, commit**

Run: `./gradlew :feature:projects:compileDebugAndroidTestKotlin
testDebugUnitTest lintDebug :app:assembleDebug`
Expected: BUILD SUCCESSFUL; unit suites green.

```bash
git add feature/projects app/src/main
git commit -m "feat: surface wip limits on the board with a unified confirm path"
```

---

### Task 8: SetTaskParent and CreateTask(parent:) with one-level guards

**Files:**

- Create: `core/domain/src/main/kotlin/app/opentasks/core/domain/SubtaskRules.kt`
- Create: `core/domain/src/test/kotlin/app/opentasks/core/domain/SubtaskRulesTest.kt`
- Modify: `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
  (one TaskDao query)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`
- Test: `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`

**Interfaces:**

- Consumes: dormant `Task.parentTaskId` (Records.kt:135) — already in
  the TASK backup record (BackupRecordV1.kt:161), so ZERO backup-format
  work here; the `SetTaskDependency` handler shape
  (RoomVaultRepository.kt:2894-2999) and its inline-feedback surfacing.
- Produces:

```kotlin
// SubtaskRules.kt
enum class SubtaskViolation {
    SELF,
    PARENT_MISSING_OR_BINNED,
    CROSS_PROJECT,
    PARENT_IS_A_SUBTASK,
    TASK_HAS_SUBTASKS,
}

object SubtaskRules {
    /** One-level guard: null when [parentTaskId] may become the parent. */
    fun parentViolation(
        tasks: List<Task>,
        taskId: TaskId,
        parentTaskId: TaskId,
    ): SubtaskViolation?

    /** done/total over live (non-binned) children per parent. */
    fun subtaskRollups(tasks: List<Task>): Map<TaskId, SubtaskRollup>

    /** Tasks eligible to become [parent]'s children right now. */
    fun attachableSubtasks(tasks: List<Task>, parent: Task): List<Task>
}

// core:model Records.kt
data class SubtaskRollup(val completed: Int, val total: Int)

// VaultRepository.kt
data class SetTaskParent(
    val taskId: TaskId,
    val parentTaskId: TaskId?,   // null detaches
) : DomainCommand
// CreateTask gains: val parentTaskId: TaskId? = null (last parameter)

// RejectionReason additions (APPEND)
SUBTASK_PARENT_INVALID,
```

- [ ] **Step 1: Write the failing pure-rule tests**

`SubtaskRulesTest.kt`:

```kotlin
@Test
fun parentViolationEnforcesOneLevelSameProjectLiveParents() {
    val base = OpenTasksFixtures.tasks.first { it.deletedAt == null }
    val project = requireNotNull(base.projectId)
    fun task(id: String, parent: String? = null, projectId: ProjectId? = project) =
        base.copy(
            id = TaskId(id),
            parentTaskId = parent?.let(::TaskId),
            projectId = projectId,
            deletedAt = null,
        )
    val parent = task("parent")
    val child = task("child", parent = "parent")
    val loose = task("loose")
    val elsewhere = task("elsewhere", projectId = OpenTasksFixtures.taxProject.id)
    val binned = task("binned").copy(deletedAt = Instant.parse("2026-08-01T00:00:00Z"))
    val tasks = listOf(parent, child, loose, elsewhere, binned)

    assertNull(SubtaskRules.parentViolation(tasks, loose.id, parent.id))
    assertEquals(
        SubtaskViolation.SELF,
        SubtaskRules.parentViolation(tasks, loose.id, loose.id),
    )
    assertEquals(
        SubtaskViolation.PARENT_MISSING_OR_BINNED,
        SubtaskRules.parentViolation(tasks, loose.id, TaskId("missing")),
    )
    assertEquals(
        SubtaskViolation.PARENT_MISSING_OR_BINNED,
        SubtaskRules.parentViolation(tasks, loose.id, binned.id),
    )
    assertEquals(
        SubtaskViolation.CROSS_PROJECT,
        SubtaskRules.parentViolation(tasks, elsewhere.id, parent.id),
    )
    assertEquals(
        SubtaskViolation.PARENT_IS_A_SUBTASK,
        SubtaskRules.parentViolation(tasks, loose.id, child.id),
    )
    assertEquals(
        SubtaskViolation.TASK_HAS_SUBTASKS,
        SubtaskRules.parentViolation(tasks, parent.id, loose.id),
    )
}

@Test
fun rollupsCountLiveChildrenAndCompletion() { … }

@Test
fun attachableSubtasksFilterMirrorsTheGuard() { … }
```

Rollups test: a parent with one completed and one open child →
`SubtaskRollup(1, 2)`; binned children excluded; childless tasks absent
from the map. Attachable test: `attachableSubtasks(tasks, parent)`
returns exactly the tasks for which `parentViolation` is null.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:domain:testDebugUnitTest --tests "*SubtaskRulesTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement SubtaskRules**

```kotlin
package app.opentasks.core.domain

import app.opentasks.core.model.SubtaskRollup
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId

enum class SubtaskViolation {
    SELF,
    PARENT_MISSING_OR_BINNED,
    CROSS_PROJECT,
    PARENT_IS_A_SUBTASK,
    TASK_HAS_SUBTASKS,
}

object SubtaskRules {
    fun parentViolation(
        tasks: List<Task>,
        taskId: TaskId,
        parentTaskId: TaskId,
    ): SubtaskViolation? {
        if (taskId == parentTaskId) return SubtaskViolation.SELF
        val parent = tasks.firstOrNull { it.id == parentTaskId && it.deletedAt == null }
            ?: return SubtaskViolation.PARENT_MISSING_OR_BINNED
        val task = tasks.firstOrNull { it.id == taskId }
        if (task != null && task.projectId != parent.projectId) {
            return SubtaskViolation.CROSS_PROJECT
        }
        if (parent.parentTaskId != null) return SubtaskViolation.PARENT_IS_A_SUBTASK
        if (tasks.any { it.parentTaskId == taskId }) {
            return SubtaskViolation.TASK_HAS_SUBTASKS
        }
        return null
    }

    fun subtaskRollups(tasks: List<Task>): Map<TaskId, SubtaskRollup> =
        tasks.asSequence()
            .filter { it.parentTaskId != null && it.deletedAt == null }
            .groupBy { requireNotNull(it.parentTaskId) }
            .mapValues { (_, children) ->
                SubtaskRollup(
                    completed = children.count(Task::isCompleted),
                    total = children.size,
                )
            }

    fun attachableSubtasks(tasks: List<Task>, parent: Task): List<Task> =
        tasks.filter { candidate ->
            candidate.deletedAt == null &&
                candidate.parentTaskId == null &&
                parentViolation(tasks, candidate.id, parent.id) == null
        }
}
```

Add `data class SubtaskRollup(val completed: Int, val total: Int)` to
`Records.kt`. Run the rule tests: PASS.

- [ ] **Step 4: Write the failing dual-engine command tests**

```kotlin
@Test
fun setTaskParentGuardsAttachDetachAndUndo() = runBlocking {
    withTimeout(5_000) {
        val repository = InMemoryVaultRepository()
        val project = OpenTasksFixtures.studioProject
        val candidates = repository.currentWorkspace().tasks.filter {
            it.projectId == project.id && it.deletedAt == null && it.parentTaskId == null
        }
        val parent = candidates[0]
        val child = candidates[1]
        val other = candidates[2]

        val attached = repository.execute(
            DomainCommand.SetTaskParent(child.id, parent.id),
        )
        assertTrue(attached is CommandResult.Success)
        assertEquals(
            parent.id,
            repository.currentWorkspace().tasks
                .first { it.id == child.id }.parentTaskId,
        )

        // One level: the child cannot become a parent, and the parent
        // cannot become someone's child.
        assertEquals(
            RejectionReason.SUBTASK_PARENT_INVALID,
            (repository.execute(DomainCommand.SetTaskParent(other.id, child.id))
                as CommandResult.Rejected).reason,
        )
        assertEquals(
            RejectionReason.SUBTASK_PARENT_INVALID,
            (repository.execute(DomainCommand.SetTaskParent(parent.id, other.id))
                as CommandResult.Rejected).reason,
        )

        // Undo restores the previous (null) parent.
        repository.execute(requireNotNull((attached as CommandResult.Success).undo))
        assertNull(
            repository.currentWorkspace().tasks
                .first { it.id == child.id }.parentTaskId,
        )
    }
}

@Test
fun createTaskWithParentInheritsProjectAndValidates() = runBlocking {
    withTimeout(5_000) {
        val repository = InMemoryVaultRepository()
        val parent = repository.currentWorkspace().tasks.first {
            it.projectId != null && it.deletedAt == null && it.parentTaskId == null
        }
        val created = repository.execute(
            DomainCommand.CreateTask(title = "Subtask", parentTaskId = parent.id),
        )
        assertTrue(created is CommandResult.Success)
        val child = repository.currentWorkspace().tasks.first { it.title == "Subtask" }
        assertEquals(parent.id, child.parentTaskId)
        assertEquals(parent.projectId, child.projectId)
    }
}
```

- [ ] **Step 5: Run to verify failure, then implement in both engines**

Domain: add the command, the `CreateTask` field, and the appended
rejection reason. Both dispatch when-trees gain
`is DomainCommand.SetTaskParent -> setTaskParent(command)`.

Room handler (beside `setTaskDependency`):

```kotlin
private suspend fun setTaskParent(command: DomainCommand.SetTaskParent): CommandResult {
    val task = currentTask(command.taskId)
        ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
    if (task.parentTaskId == command.parentTaskId) {
        return CommandResult.Success("Subtask link is unchanged")
    }
    val parentId = command.parentTaskId
    if (parentId != null) {
        val violation = SubtaskRules.parentViolation(
            tasks = mutableWorkspace.value.tasks,
            taskId = task.id,
            parentTaskId = parentId,
        )
        if (violation != null) {
            return CommandResult.Rejected(
                RejectionReason.SUBTASK_PARENT_INVALID,
                subtaskViolationMessage(violation),
            )
        }
    }
    persistTask(
        task.copy(parentTaskId = parentId, revision = nextRevision(task)),
        "parent",
    )
    return CommandResult.Success(
        message = if (parentId == null) "Subtask detached" else "Subtask attached",
        undo = DomainCommand.SetTaskParent(task.id, task.parentTaskId),
    )
}

private fun subtaskViolationMessage(violation: SubtaskViolation): String = when (violation) {
    SubtaskViolation.SELF -> "A task cannot be its own subtask."
    SubtaskViolation.PARENT_MISSING_OR_BINNED ->
        "That parent task is no longer available."
    SubtaskViolation.CROSS_PROJECT ->
        "Subtasks live in the same project as their parent."
    SubtaskViolation.PARENT_IS_A_SUBTASK ->
        "Subtasks go one level deep — that task is already a subtask."
    SubtaskViolation.TASK_HAS_SUBTASKS ->
        "That task has subtasks of its own, so it cannot become one."
}
```

Guard-read note: the Room handler validates against
`mutableWorkspace.value.tasks` for the relational checks
(PARENT_IS_A_SUBTASK / TASK_HAS_SUBTASKS / CROSS_PROJECT) exactly as
`setTaskDependency` reads the full edge list; additionally re-verify the
parent row live via `database.taskDao().getById(parentId.value)` (exists
and `deletedAtEpochMillis == null`) so the decision holds inside the
transaction even if the snapshot lags. If either read disagrees, reject
with `PARENT_MISSING_OR_BINNED`'s message. InMemory validates purely
against `mutableWorkspace.value.tasks`.

`createTask` in both engines: when `command.parentTaskId != null`,
resolve the parent first; reject `NOT_FOUND`/`SUBTASK_PARENT_INVALID`
via the same `parentViolation` call (the new task has no id in `tasks`
yet, so only SELF cannot occur); force `projectId = parent.projectId`
(ignoring `command.projectId`); set `parentTaskId` on the built task.
Message and undo stay the handler's existing ones (undo `DeleteTask`
covers the child).

No activity entry is recorded for parent changes — `ActivityKind` has
no fitting value and the append-only rule makes a new one cheap to add
later if wanted; the spec is silent, so we skip it (recorded as a
deliberate omission in the Spec Coverage Map).

- [ ] **Step 6: Run, compile instrumented twins, commit**

Run: `./gradlew :core:domain:testDebugUnitTest :core:data:testDebugUnitTest
&& ./gradlew :core:data:compileDebugAndroidTestKotlin`
Expected: PASS.

```bash
git add core/model core/domain core/data
git commit -m "feat: light up one-level subtask parents in both engines"
```

---

### Task 9: Subtask semantics — completion confirm, subtree bin, moves

**Files:**

- Modify: `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
  (`ChangeTaskStatus`/`CompleteTask`/`CompleteTasks` flags, reason)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
  (`changeTaskStatus`, `completeTask`, `completeTasks`, `deleteTask`,
  `deleteTasks`, `restoreTask`, `moveTasksToProject`)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
  (one TaskDao query)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`
- Test: `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`

**Interfaces:**

- Consumes: Task 8 parent link; blocked-completion and bulk-blocked
  precedents (RoomVaultRepository.kt:1548-1553, 1994-2003).
- Produces:

```kotlin
// Field additions (each positioned after acknowledgeWipLimit /
// acknowledgeBlocked respectively):
// ChangeTaskStatus  gains: val acknowledgeOpenSubtasks: Boolean = false
// CompleteTask      gains: val acknowledgeOpenSubtasks: Boolean = false
// CompleteTasks     gains: val acknowledgeOpenSubtasks: Boolean = false
// CompleteTask/CompleteTasks pass the flag through to changeTaskStatus /
// the bulk preflight.

// RejectionReason additions (APPEND)
OPEN_SUBTASKS_CONFIRM_REQUIRED,
```

Deterministic gate order inside `changeTaskStatus` for a COMPLETED
destination: blocked check first (existing), then open-subtasks check,
then WIP (which COMPLETED destinations never reach).

- [ ] **Step 1: Write the failing dual-engine tests**

```kotlin
@Test
fun completingAParentWithOpenSubtasksNeedsAcknowledgement() = runBlocking {
    withTimeout(5_000) {
        val repository = InMemoryVaultRepository()
        val project = OpenTasksFixtures.studioProject
        val candidates = repository.currentWorkspace().tasks.filter {
            it.projectId == project.id && it.deletedAt == null &&
                !it.isCompleted && it.parentTaskId == null && !it.isBlocked
        }
        val parent = candidates[0]
        val child = candidates[1]
        repository.execute(DomainCommand.SetTaskParent(child.id, parent.id))

        val refused = repository.execute(DomainCommand.CompleteTask(parent.id))
        assertEquals(
            RejectionReason.OPEN_SUBTASKS_CONFIRM_REQUIRED,
            (refused as CommandResult.Rejected).reason,
        )

        val acknowledged = repository.execute(
            DomainCommand.CompleteTask(parent.id, acknowledgeOpenSubtasks = true),
        )
        assertTrue(acknowledged is CommandResult.Success)
        // Children are never touched.
        assertFalse(
            repository.currentWorkspace().tasks.first { it.id == child.id }.isCompleted,
        )

        // Completing the child alone never confirms.
        assertTrue(
            repository.execute(DomainCommand.CompleteTask(child.id))
                is CommandResult.Success,
        )
    }
}

@Test
fun bulkCompletionConfirmsOnlyForChildrenOutsideTheSet() = runBlocking {
    withTimeout(5_000) {
        val repository = InMemoryVaultRepository()
        // parent + child both in the set: no confirm needed.
        // parent alone with an open child outside the set: confirm.
        …
    }
}

@Test
fun binningAParentTakesTheSubtreeAndRestoreDetaches() = runBlocking {
    withTimeout(5_000) {
        val repository = InMemoryVaultRepository()
        …
        val deleted = repository.execute(DomainCommand.DeleteTask(parent.id))
        assertTrue(deleted is CommandResult.Success)
        val afterDelete = repository.currentWorkspace().tasks
        assertNotNull(afterDelete.first { it.id == parent.id }.deletedAt)
        assertNotNull(afterDelete.first { it.id == child.id }.deletedAt)

        // One undo restores the subtree.
        repository.execute(requireNotNull((deleted as CommandResult.Success).undo))
        val restored = repository.currentWorkspace().tasks
        assertNull(restored.first { it.id == parent.id }.deletedAt)
        assertNull(restored.first { it.id == child.id }.deletedAt)
        assertEquals(parent.id, restored.first { it.id == child.id }.parentTaskId)

        // Restoring a child alone from the Bin detaches it.
        repository.execute(DomainCommand.DeleteTask(parent.id))
        repository.execute(DomainCommand.RestoreTask(child.id))
        val detached = repository.currentWorkspace().tasks.first { it.id == child.id }
        assertNull(detached.deletedAt)
        assertNull(detached.parentTaskId)
    }
}

@Test
fun movingAParentCarriesChildrenAndMovingAChildAloneDetaches() { … }
```

The move test: `MoveTasksToProject(listOf(parent.id), otherProject)`
moves parent AND child, both remapped to the destination workflow, link
intact; `MoveTasksToProject(listOf(child.id), otherProject)` moves only
the child with `parentTaskId == null` afterward.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*InMemoryVaultRepositoryTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement in both engines**

TaskDao gains:

```kotlin
@Query(
    """
    SELECT COUNT(*) FROM tasks
    WHERE parentTaskId = :taskId AND deletedAtEpochMillis IS NULL
        AND semanticStatus != 'COMPLETED'
    """,
)
suspend fun openSubtaskCount(taskId: String): Int

@Query(
    """
    SELECT * FROM tasks
    WHERE parentTaskId = :taskId AND deletedAtEpochMillis IS NULL
    ORDER BY id
    """,
)
suspend fun liveChildren(taskId: String): List<TaskEntity>
```

`changeTaskStatus` (both engines), after the blocked gate, for COMPLETED
destinations only:

```kotlin
if (
    status.semanticStatus == SemanticStatus.COMPLETED &&
    !command.acknowledgeOpenSubtasks &&
    database.taskDao().openSubtaskCount(task.id.value) > 0
) {
    return CommandResult.Rejected(
        RejectionReason.OPEN_SUBTASKS_CONFIRM_REQUIRED,
        "This task still has open subtasks.",
    )
}
```

(InMemory counts over `current.tasks`.) `completeTask` threads
`acknowledgeOpenSubtasks` into the delegated `ChangeTaskStatus`.
`completeTasks` preflight, beside its blocked check: for each task,
open children NOT in the completion id set and
`!command.acknowledgeOpenSubtasks` → the same rejection (whole-command,
before any write — matching the existing bulk blocked shape).

`deleteTask`: resolve `children = liveChildren(task.id.value)` first;
bin parent and children in the one transaction (same `deletedAt`, each
with `nextRevision`, each with its own BINNED activity entry, timer stop
check across the whole set). The undo replays in list order, and the
parent must restore FIRST — a child restored while its parent is still
binned would detach itself (the Task 9 restore rule) — so:

```kotlin
// UndoBatch replays in list order: parent first, then children, so no
// child ever restores under a still-binned parent and self-detaches.
val inverses: List<DomainCommand> =
    listOf(DomainCommand.RestoreTask(task.id)) +
        children.map { DomainCommand.RestoreTask(TaskId(it.id)) }
undo = DomainCommand.UndoBatch(inverses)
```
`deleteTasks`: expand the resolved set with each listed parent's live
children (deduplicated); the 200-ID bound applies to the LISTED ids
only (expansion is an effect); inverses parent-first per subtree.

`restoreTask`: after resolving the task, when `task.parentTaskId != null`
and the parent row is missing or still binned, restore with
`parentTaskId = null` (one write, same command).

`moveTasksToProject`: locate the handler (search
`private suspend fun moveTasksToProject` in both engines) and extend its
resolved set the same way as `deleteTasks` (listed parents pull their
live children in); after the remap loop, any moved task whose parent was
NOT moved and whose parent's project now differs gets
`parentTaskId = null` in the same write. Keep the handler's existing
status-remap and undo shape, widened to the expanded set.

- [ ] **Step 4: Run the suites and commit**

Run: `./gradlew :core:data:testDebugUnitTest
&& ./gradlew :core:data:compileDebugAndroidTestKotlin`
Expected: PASS.

```bash
git add core/domain core/data
git commit -m "feat: give subtasks completion confirm and subtree semantics"
```

---

### Task 10: Nesting authority, list indentation, board rollups

**Files:**

- Modify: `core/domain/src/main/kotlin/app/opentasks/core/domain/TaskArrangementRules.kt`
- Test: `core/domain/src/test/kotlin/app/opentasks/core/domain/TaskArrangementRulesTest.kt`
  (or the module's existing arrangement test file — extend in place)
- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
  (`TaskListPane` items at ~609-640)
- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`
  (workbench rows at ~864)
- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/BoardView.kt`
  (`BoardTaskContent` metadata row at 387-410)
- Modify: `feature/projects/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
  (projections at 552-598, screen wiring)
- Test: feature Compose test source sets (tasks + projects)

**Interfaces:**

- Consumes: `arrangeTasks` (TaskArrangementRules.kt:31-59),
  `SubtaskRules.subtaskRollups` (Task 8), `boardColumns` unchanged.
- Produces:
  - `arrangeTasks` return shape UNCHANGED (`List<TaskGroup>`) but each
    group's task order now nests children directly under their parent.
  - `fun indentedTaskIds(groups: List<TaskGroup>): Set<TaskId>` in
    `TaskArrangementRules.kt`.
  - `TasksScreen` gains `indentedTaskIds: Set<TaskId>`; `ProjectsScreen`
    gains `workbenchIndentedTaskIds: Set<TaskId>` and
    `subtaskRollups: Map<TaskId, SubtaskRollup>`; `BoardView` gains
    `subtaskRollups: Map<TaskId, SubtaskRollup> = emptyMap()`.

- [ ] **Step 1: Write the failing domain tests**

```kotlin
@Test
fun arrangeTasksNestsChildrenDirectlyUnderTheirParent() {
    val base = OpenTasksFixtures.tasks.first { it.deletedAt == null }
    fun task(id: String, title: String, parent: String? = null) = base.copy(
        id = TaskId(id),
        title = title,
        parentTaskId = parent?.let(::TaskId),
        priority = Priority.NONE,
        due = null,
    )
    val tasks = listOf(
        task("b-parent", "Beta parent"),
        task("a-parent", "Alpha parent"),
        task("z-child", "Zulu child", parent = "a-parent"),
        task("m-child", "Mike child", parent = "a-parent"),
        task("orphan-child", "Orphan child", parent = "filtered-out"),
    )
    val groups = arrangeTasks(
        tasks = tasks,
        arrangement = TaskArrangement(sort = TaskSortKey.TITLE, groupBy = null),
        projectNames = emptyMap(),
        clock = Clock.systemUTC(),
    )
    assertEquals(
        listOf("a-parent", "m-child", "z-child", "b-parent", "orphan-child"),
        groups.single().tasks.map { it.id.value },
    )
    assertEquals(
        setOf(TaskId("m-child"), TaskId("z-child")),
        indentedTaskIds(groups),
    )
}

@Test
fun childWhoseParentLandsInAnotherGroupRendersFlat() { … }

@Test
fun recoveredDeeperTreesClampToASingleIndentLevel() {
    // grandparent <- parent <- child (legal in recovered foreign data):
    // order is depth-first under the top ancestor; only tasks whose
    // parent is present in the group are indented — one visual level.
    …
}
```

The other-group test: group by PRIORITY with parent HIGH and child LOW →
the child renders flat (not indented) inside the LOW group. The
flat-order check inside "Alpha parent" proves siblings keep comparator
order (`m` before `z` under TITLE sort).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:domain:testDebugUnitTest --tests "*TaskArrangement*"`
Expected: FAIL — flat order, `indentedTaskIds` unresolved.

- [ ] **Step 3: Implement nesting inside arrangeTasks**

In `TaskArrangementRules.kt`:

```kotlin
private fun nestWithinGroup(sorted: List<Task>): List<Task> {
    val presentIds = sorted.mapTo(hashSetOf(), Task::id)
    val childrenByParent = sorted
        .filter { it.parentTaskId != null && it.parentTaskId in presentIds }
        .groupBy { requireNotNull(it.parentTaskId) }
    val nestedIds = childrenByParent.values.flatten().mapTo(hashSetOf(), Task::id)
    return buildList {
        fun addWithDescendants(task: Task) {
            add(task)
            childrenByParent[task.id].orEmpty().forEach(::addWithDescendants)
        }
        sorted.forEach { task ->
            if (task.id !in nestedIds) addWithDescendants(task)
        }
    }
}

fun indentedTaskIds(groups: List<TaskGroup>): Set<TaskId> =
    groups.flatMapTo(hashSetOf()) { group ->
        val present = group.tasks.mapTo(hashSetOf(), Task::id)
        group.tasks
            .filter { it.parentTaskId != null && it.parentTaskId in present }
            .map(Task::id)
    }
```

Apply `nestWithinGroup` to every group `arrangeTasks` builds — wrap each
constructed `TaskGroup(value, tasks)` as
`TaskGroup(value, nestWithinGroup(tasks))` in all four branches (the
acyclicity of recovered data guarantees termination; the single
`indentedTaskIds` level clamps rendering depth by construction).

Run the domain tests: PASS.

- [ ] **Step 4: Thread rendering through app and features**

`OpenTasksApp`: extend the existing `remember` blocks —
`indentedTaskIds(taskGroups)` beside `taskGroups` (552-566),
`indentedTaskIds(workbenchTaskGroups)` beside the workbench arrangement
(570-587), and `SubtaskRules.subtaskRollups(snapshot.tasks)` remembered
on `snapshot.tasks`; pass to `TasksScreen`, `ProjectsScreen`, and
through to `BoardView`.

`TasksScreen.TaskListPane`: rows whose id is in `indentedTaskIds` get
`modifier = Modifier.padding(start = 24.dp)` on the `TaskRow` call (the
4 dp scale: 24). `ProjectsScreen` workbench `ProjectTaskRow` calls: same
start padding.

`BoardView`/`BoardTaskContent`: add `subtaskRollup: SubtaskRollup?`
parameter to `BoardTaskContent`, rendered in the metadata `Row` after
the due date:

```kotlin
subtaskRollup?.let { rollup ->
    Text(
        text = stringResource(R.string.board_subtask_rollup, rollup.completed, rollup.total),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
```

Pass `subtaskRollups[task.id]` from both `BoardTaskCard` and the drag
preview (BoardView.kt:244-254) so the chip renders in both.
`feature/projects/src/main/res/values/strings.xml` gains:

```xml
<string name="board_subtask_rollup">%1$d/%2$d subtasks</string>
```

- [ ] **Step 5: Compose tests, gate, commit**

Feature tests: a board card with `SubtaskRollup(1, 3)` shows
"1/3 subtasks" on card and drag preview; a Tasks list with one indented
child lays the child row out with the start inset (assert via
`onNodeWithText("…child…").assertLeftPositionInRootIsAtLeast(…)` or a
testTag on the padded wrapper — keep whichever the module's existing
tests use for geometry; a `testTag("task-indent-${id}")` wrapper Box is
the simplest and is what the plan prescribes).

Run: `./gradlew :core:domain:testDebugUnitTest
:feature:tasks:compileDebugAndroidTestKotlin
:feature:projects:compileDebugAndroidTestKotlin
testDebugUnitTest lintDebug :app:assembleDebug`
Expected: PASS.

```bash
git add core/domain core/model feature/tasks feature/projects app/src/main
git commit -m "feat: nest subtasks in lists and roll them up on board cards"
```

---

### Task 11: Task-detail subtasks section and confirm dialogs

**Files:**

- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
  (`TaskDetailPane` at 961+, new `SubtasksSection` composable beside the
  checklist/dependency sections)
- Modify: `feature/tasks/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: feature/tasks Compose test source set

**Interfaces:**

- Consumes: Task 8/9 commands and reasons; `SubtaskRules.attachableSubtasks`;
  the `DependencyFeedback` inline pattern (WorkspaceViewModel.kt:373-400)
  and `pendingBlocked`/`pendingBlockedBulk` dialog precedents.
- Produces `TaskDetailPane` additions:

```kotlin
subtasks: List<Task>,                       // live children, arrangeTasks order
attachableSubtasks: List<Task>,
parentOfTask: Task?,                        // non-null when this task is a subtask
subtaskError: String?,
onAddSubtask: (String) -> Unit,             // quick-add title
onAttachSubtask: (TaskId) -> Unit,
onDetachSubtask: (TaskId) -> Unit,
onOpenSubtask: (TaskId) -> Unit,
onCompleteSubtask: (Task) -> Unit,
onClearSubtaskError: () -> Unit,
```

and `WorkspaceViewModel` additions:

```kotlin
data class SubtaskFeedback(val taskId: TaskId, val message: String)
data class PendingSubtaskCompletion(
    val task: Task,
    val requestedStatusId: WorkflowStatusId?,   // null = CompleteTask
    val acknowledgeBlocked: Boolean,
)
fun setTaskParent(taskId: TaskId, parentTaskId: TaskId?)
fun confirmSubtaskCompletion() / dismissSubtaskCompletion()
fun confirmSubtaskBulkCompletion() / dismissSubtaskBulkCompletion()
```

- [ ] **Step 1: Write the failing Compose tests**

```kotlin
@Test
fun detailShowsSubtasksWithQuickAddAttachAndDetach() {
    var added: String? = null
    var attached: TaskId? = null
    var detached: TaskId? = null
    // Instantiate TaskDetailPane with a fixture parent, two children in
    // `subtasks`, one candidate in `attachableSubtasks`, callbacks
    // recording into the vars, all other params as fixture defaults.
    composeRule.onNodeWithTag("subtask-quick-add").performTextInput("New step")
    composeRule.onNodeWithTag("subtask-quick-add-confirm").performClick()
    assertEquals("New step", added)
    composeRule.onNodeWithTag("subtask-attach").performClick()
    composeRule.onNodeWithTag("subtask-attach-${candidate.id.value}").performClick()
    assertEquals(candidate.id, attached)
    composeRule.onNodeWithTag("subtask-detach-${child.id.value}").performClick()
    assertEquals(child.id, detached)
}

@Test
fun subtaskErrorRendersInlineAndClearsOnInput() {
    var cleared = false
    // Same TaskDetailPane instantiation as above, with
    // subtaskError = "Subtasks go one level deep — that task is already a subtask."
    // and onClearSubtaskError = { cleared = true }.
    composeRule
        .onNodeWithText("Subtasks go one level deep — that task is already a subtask.")
        .assertIsDisplayed()
    composeRule.onNodeWithTag("subtask-quick-add").performTextInput("x")
    assertTrue(cleared)
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :feature:tasks:compileDebugAndroidTestKotlin`
Expected: FAIL.

- [ ] **Step 3: Implement the section**

`SubtasksSection` beside the checklist section inside `TaskDetailPane`:
a `SectionHeader` ("Subtasks" via `R.string.subtasks_heading`, with a
"n done of m" supporting text from the list itself), each child rendered
as a compact row — complete toggle `IconButton` (48 dp) calling
`onCompleteSubtask`, title opening via `onOpenSubtask`, and a detach
`IconButton` (testTag `subtask-detach-{id}`) calling `onDetachSubtask` —
plus a quick-add `OutlinedTextField` (testTag `subtask-quick-add`) with
a confirm `TextButton` (testTag `subtask-quick-add-confirm`) that trims
and forwards to `onAddSubtask`, and an attach affordance (testTag
`subtask-attach`) opening a `DropdownMenu` of `attachableSubtasks`
titles (item testTag `subtask-attach-{id}`). Show `subtaskError` as
inline `supportingText` in error colour with `onClearSubtaskError` on
value change. The section renders only when the task itself is not a
subtask (`task.parentTaskId == null`) — a subtask's detail instead shows
a "Subtask of “X”" breadcrumb `TextButton` navigating via
`onOpenSubtask(parentId)`; pass the parent task in as
`parentOfTask: Task?`. Strings in `feature/tasks` strings.xml:
`subtasks_heading`, `subtask_add_hint`, `subtask_add_confirm`,
`subtask_attach_existing`, `subtask_detach_description`,
`subtask_parent_breadcrumb` ("Subtask of “%1$s”").

- [ ] **Step 4: Wire the app layer and the confirm dialogs**

`WorkspaceViewModel`:

```kotlin
fun setTaskParent(taskId: TaskId, parentTaskId: TaskId?) {
    viewModelScope.launch {
        when (val result = repository.execute(
            DomainCommand.SetTaskParent(taskId, parentTaskId),
        )) {
            is CommandResult.Success -> {
                mutableSubtaskFeedback.value = null
                send(result)
            }
            is CommandResult.Rejected ->
                mutableSubtaskFeedback.value = SubtaskFeedback(taskId, result.message)
        }
    }
}
```

Extend the rejection branches of `completeTask` and
`changeTaskStatus(task, statusId)`:

```kotlin
result.reason == RejectionReason.OPEN_SUBTASKS_CONFIRM_REQUIRED ->
    pendingSubtask.value = PendingSubtaskCompletion(
        task = task,
        requestedStatusId = statusId,   // null in completeTask's branch
        acknowledgeBlocked = false,
    )
```

`confirmBlockedCompletion` may itself surface the subtask rejection on
re-dispatch; route its `execute` result through the same interception by
re-dispatching via `changeTaskStatus`/`completeTask`-style handling with
`acknowledgeBlocked = true` recorded, and `confirmSubtaskCompletion`
re-dispatches with BOTH `acknowledgeOpenSubtasks = true` and the stored
`acknowledgeBlocked`. `completeBulkSelection` gains the
`OPEN_SUBTASKS_CONFIRM_REQUIRED → pendingSubtaskBulk.value = true`
branch, with `confirmSubtaskBulkCompletion()` re-dispatching
`CompleteTasks(ids, acknowledgeBlocked = true, acknowledgeOpenSubtasks
= true)` — blocked-ack included because the bulk preflight runs blocked
first and the user has by then confirmed both dialogs in order.

`OpenTasksApp`: compute per-selected-task `subtasks` (children of the
selected task in `arrangeTasks` comparator order), `attachableSubtasks`
via `SubtaskRules.attachableSubtasks(snapshot.tasks, task)`,
`parentOfTask`; wire callbacks (`onAddSubtask = { title ->
viewModel.execute(DomainCommand.CreateTask(title = title, parentTaskId
= task.id)) }`, attach/detach via `viewModel::setTaskParent`); render
two `AlertDialog`s beside the blocked ones for `pendingSubtask` and
`pendingSubtaskBulk` with app strings `subtask_confirm_title`
("Complete with open subtasks?"), `subtask_confirm_body`
("“%1$s” still has open subtasks. They stay open if you complete it."),
`subtask_confirm_complete` ("Complete anyway"), `subtask_confirm_keep`
("Keep open"), and bulk variants `subtask_confirm_bulk_title`/`_body`.

- [ ] **Step 5: Gate and commit**

Run: `./gradlew :feature:tasks:compileDebugAndroidTestKotlin
testDebugUnitTest lintDebug :app:assembleDebug`
Expected: PASS.

```bash
git add feature/tasks app/src/main
git commit -m "feat: add detail subtask section with completion confirms"
```

---

### Task 12: My Day replaces Today focus on Home

**Files:**

- Modify: `feature/home/src/main/kotlin/app/opentasks/feature/home/HomeScreen.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
  (`entry<HomeRoute>` at 1141-1163)
- Test: feature/home Compose test source set

**Interfaces:**

- Consumes: `HomeSnapshot.myDayTasks` (Task 4), `MoveMyDayEntry` /
  `RemoveTaskFromMyDay` commands, `PlanningDrag` root primitives
  (`RootDragState`, `dragTargetAt`, `rootLongPressDragSource`,
  `RootDragPreview` — PlanningDrag.kt:27-107).
- Produces `HomeScreen` signature changes:

```kotlin
// Removed usage: the "Today focus" SectionHeader + focusTasks items
// (HomeScreen.kt:78-103). HomeSnapshot.focusTasks stays in the model —
// the widget path is untouched — Home simply stops rendering it.
// Added parameters:
onRemoveFromMyDay: (TaskId) -> Unit,
onMoveMyDayEntry: (taskId: TaskId, afterTaskId: TaskId?) -> Unit,
// onPlanToday keeps its name; Task 13 rewires its target.
```

- [ ] **Step 1: Write the failing Compose tests**

```kotlin
@Test
fun myDaySectionRendersRankOrderDimsCompletedAndFallsBackToMenu() {
    var moved: Pair<TaskId, TaskId?>? = null
    var removed: TaskId? = null
    val open = OpenTasksFixtures.tasks.first { !it.isCompleted && it.deletedAt == null }
    val done = OpenTasksFixtures.tasks.first { it.isCompleted }
    val snapshot = OpenTasksFixtures.snapshot.home.copy(
        myDayTasks = listOf(open, done),
    )
    composeRule.setContent {
        OpenTasksTheme {
            HomeScreen(
                snapshot = snapshot,
                projectNames = emptyMap(),
                onOpenSearch = {}, onPlanToday = {},
                onOpenTask = {}, onCompleteTask = {},
                onOpenProject = {},
                insightsSummary = OpenTasksFixtures.insightsSummary,
                onOpenInsights = {}, onToggleTimer = {},
                onRemoveFromMyDay = { removed = it },
                onMoveMyDayEntry = { id, after -> moved = id to after },
            )
        }
    }
    composeRule.onNodeWithText("My Day").assertIsDisplayed()
    composeRule.onNodeWithTag("my-day-row-${done.id.value}").assertIsDisplayed()

    composeRule.onNodeWithTag("my-day-menu-${done.id.value}").performClick()
    composeRule.onNodeWithTag("my-day-move-up-${done.id.value}").performClick()
    assertEquals(done.id to null, moved)

    composeRule.onNodeWithTag("my-day-menu-${open.id.value}").performClick()
    composeRule.onNodeWithTag("my-day-remove-${open.id.value}").performClick()
    assertEquals(open.id, removed)
}

@Test
fun emptyMyDayShowsThePlanPrompt() {
    composeRule.setContent {
        OpenTasksTheme {
            HomeScreen(
                snapshot = OpenTasksFixtures.snapshot.home.copy(
                    myDayTasks = emptyList(),
                ),
                projectNames = emptyMap(),
                onOpenSearch = {}, onPlanToday = {},
                onOpenTask = {}, onCompleteTask = {},
                onOpenProject = {},
                insightsSummary = OpenTasksFixtures.insightsSummary,
                onOpenInsights = {}, onToggleTimer = {},
                onRemoveFromMyDay = {},
                onMoveMyDayEntry = { _, _ -> },
            )
        }
    }
    composeRule.onNodeWithTag("my-day-empty").assertIsDisplayed()
}
```

(If `OpenTasksFixtures` lacks an `insightsSummary` member, reuse
whatever the existing Home Compose tests construct for
`insightsSummary` — copy their fixture expression verbatim.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :feature:home:compileDebugAndroidTestKotlin`
Expected: FAIL.

- [ ] **Step 3: Implement the section**

Replace the Today-focus block (HomeScreen.kt:78-103) with:

```kotlin
item {
    Spacer(Modifier.height(16.dp))
    SectionHeader(
        title = stringResource(R.string.my_day_heading),
        supportingText = snapshot.overdueCount
            .takeIf { it > 0 }
            ?.let { count ->
                pluralStringResource(R.plurals.my_day_overdue, count, count)
            },
        action = {
            TextButton(onClick = onPlanToday) {
                Text(stringResource(R.string.my_day_plan))
            }
        },
    )
}
if (snapshot.myDayTasks.isEmpty()) {
    item {
        Text(
            text = stringResource(R.string.my_day_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("my-day-empty"),
        )
    }
}
items(snapshot.myDayTasks, key = { "my-day-${it.id.value}" }) { task ->
    MyDayRow(
        task = task,
        order = snapshot.myDayTasks,
        projectName = projectNames[task.projectId] ?: stringResource(R.string.my_day_inbox),
        onOpenTask = onOpenTask,
        onCompleteTask = onCompleteTask,
        onRemoveFromMyDay = onRemoveFromMyDay,
        onMoveMyDayEntry = onMoveMyDayEntry,
    )
}
```

`MyDayRow`: a `Row` (testTag `my-day-row-{id}`) wrapping the shared
`TaskRow` (dimmed via `Modifier.graphicsLayer { alpha = if
(task.isCompleted) 0.5f else 1f }`) plus a trailing 48 dp overflow
`IconButton` (testTag `my-day-menu-{id}`) opening a `DropdownMenu` with
Move up / Move down / Remove items (testTags `my-day-move-up-{id}`,
`my-day-move-down-{id}`, `my-day-remove-{id}`, each `heightIn(min =
48.dp)`). Move up maps to `onMoveMyDayEntry(task.id, order.getOrNull(
index - 2)?.id)` (null lands on top); move down to
`onMoveMyDayEntry(task.id, order.getOrNull(index + 1)?.id)`; items
disabled at the ends. Long-press drag reorder layers over this complete
fallback with the board's exact pattern: each row registers bounds in a
`mutableStateMapOf<TaskId, Rect>`, applies `rootLongPressDragSource`,
a `RootDragPreview` floats the row, `dragTargetAt` resolves the hovered
row, and the drop dispatches `onMoveMyDayEntry(dragged, afterTaskId =
the member preceding the hovered row — null when hovering the first)`.
No new command, arithmetic, controller, or persistence state.

`feature/home/src/main/res/values/strings.xml` gains `my_day_heading`
("My Day"), `my_day_plan` ("Plan"), `my_day_empty` ("Nothing planned
yet. Use Plan to shape today."), `my_day_inbox` ("Inbox"), and plural
`my_day_overdue` ("%d overdue item"/"%d overdue items").

`OpenTasksApp` `entry<HomeRoute>` wires:

```kotlin
onRemoveFromMyDay = { taskId ->
    viewModel.execute(DomainCommand.RemoveTaskFromMyDay(taskId))
},
onMoveMyDayEntry = { taskId, afterTaskId ->
    viewModel.execute(DomainCommand.MoveMyDayEntry(taskId, afterTaskId))
},
```

- [ ] **Step 4: Gate and commit**

Run: `./gradlew :feature:home:compileDebugAndroidTestKotlin
testDebugUnitTest lintDebug :app:assembleDebug`
Expected: PASS.

```bash
git add feature/home app/src/main
git commit -m "feat: put the my day plan on home with drag and menu reorder"
```

---

### Task 13: My Day curation sheet, suggestions, and entry points

**Files:**

- Create: `feature/home/src/main/kotlin/app/opentasks/feature/home/MyDayPlanSheet.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`
- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/BoardView.kt`
  (card menu at 326-355)
- Modify: `feature/projects/src/main/res/values/strings.xml`
- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
  (`TaskDetailPane` action row)
- Modify: `feature/tasks/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Test: feature/home Compose test source set

**Interfaces:**

- Consumes: `myDaySuggestions` (Task 4), `AddTaskToMyDay`, Home section
  (Task 12).
- Produces:

```kotlin
@Composable
fun MyDayPlanSheet(
    members: List<Task>,               // rank order
    suggestions: List<Task>,
    projectNames: Map<ProjectId, String>,
    onDismiss: () -> Unit,
    onAddToMyDay: (TaskId) -> Unit,
    onRemoveFromMyDay: (TaskId) -> Unit,
    onMoveMyDayEntry: (TaskId, TaskId?) -> Unit,
)
```

`BoardView`/`BoardTaskCard` gain `onAddTaskToMyDay: (TaskId) -> Unit =
{}` and a `myDayMemberIds: Set<TaskId> = emptySet()` to hide the item
for members; `TaskDetailPane` gains `isOnMyDay: Boolean`,
`onToggleMyDay: () -> Unit`.

- [ ] **Step 1: Write the failing Compose tests**

```kotlin
@Test
fun planSheetListsMembersAndAddsSuggestionsWithOneTap() {
    var added: TaskId? = null
    // MyDayPlanSheet with two members and two suggestions;
    // onAddToMyDay = { added = it }.
    composeRule.onNodeWithTag("my-day-suggestion-add-${suggestion.id.value}")
        .performClick()
    assertEquals(suggestion.id, added)
}

@Test
fun emptyStateOffersSuggestionsInline() { … }  // Home empty state gains
                                               // the same one-tap adds
```

Also extend the board test: a card menu shows "Add to My Day" for a
non-member and hides it for a member.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :feature:home:compileDebugAndroidTestKotlin :feature:projects:compileDebugAndroidTestKotlin`
Expected: FAIL.

- [ ] **Step 3: Implement**

`MyDayPlanSheet`: `ModalBottomSheet` (the `WorkflowEditorSheet` mold —
`rememberModalBottomSheetState(skipPartiallyExpanded = true)`, scrolling
`Column`) with a heading (`my_day_plan_title` "Plan My Day"), the member
list re-using `MyDayRow` (Task 12 — extract it to file scope so both
call it), a divider, a suggestions `SectionHeader`
(`my_day_suggestions` "Suggested for today") and per-suggestion rows:
title + project, trailing 48 dp add `IconButton` (testTag
`my-day-suggestion-add-{id}`) calling `onAddToMyDay`. Empty suggestion
list hides the section. Home's empty state (Task 12's `my-day-empty`
item) is replaced by the same suggestion rows inline when suggestions
exist, else the existing copy.

App: `var showMyDayPlan by rememberSaveable { mutableStateOf(false) }`;
`onPlanToday = { showMyDayPlan = true }` (replacing
`navigate(TasksRoute)`); render the sheet when true with
`members = snapshot.home.myDayTasks`, `suggestions = remember(snapshot,
projectionClock) { myDaySuggestions(snapshot.tasks,
snapshot.myDay.mapTo(hashSetOf()) { it.taskId }, today,
currentDeviceClock(clock, zoneProvider).zone) }`, and the three command
dispatches. Suggestions also feed the Home empty state via a new
`suggestions: List<Task>` parameter on `HomeScreen`.

Board: `DropdownMenuItem` "Add to My Day" (string
`board_add_to_my_day`, testTag `board-my-day-{id}`) after Duplicate,
shown when `task.id !in myDayMemberIds`, calling
`onAddTaskToMyDay(task.id)`; app passes
`myDayMemberIds = snapshot.myDay.mapTo(hashSetOf()) { it.taskId }` and
the dispatch. Detail: a toggle `TextButton` in the action row —
`task_add_to_my_day` / `task_remove_from_my_day` — calling
`onToggleMyDay` (app dispatches Add/Remove based on membership).

Recorded deviation (also in the Spec Coverage Map): the spec's
"task-row overflow menus" entry point lands as the board-card menu plus
the detail toggle — the shared `TaskRow` has no overflow menu and its
long-press is already claimed by bulk selection; adding a third
affordance to every list row would fight that. The curation sheet plus
detail covers the Tasks-list path.

- [ ] **Step 4: Gate and commit**

Run: `./gradlew :feature:home:compileDebugAndroidTestKotlin
:feature:projects:compileDebugAndroidTestKotlin
:feature:tasks:compileDebugAndroidTestKotlin
testDebugUnitTest lintDebug :app:assembleDebug`
Expected: PASS.

```bash
git add feature/home feature/projects feature/tasks app/src/main
git commit -m "feat: add my day curation with suggestions and entry points"
```

---

### Task 14: The automation engine and the My Day sweep

**Files:**

- Create: `core/domain/src/main/kotlin/app/opentasks/core/domain/AutomationEngine.kt`
- Create: `core/domain/src/test/kotlin/app/opentasks/core/domain/AutomationEngineTest.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
  (`execute` at 178-202)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
  (`execute` at 132-157)
- Create: `app/src/main/kotlin/app/opentasks/myday/MyDaySweeper.kt`
- Modify: `app/src/main/kotlin/app/opentasks/MainActivity.kt` (`onStart`
  at 324-339)
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/AutomationEvaluationTest.kt`
  (new, InMemory-driven)
- Test: `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`

**Interfaces:**

- Consumes: rules (Task 5), My Day commands (Task 4), the non-reentrant
  `writeMutex` constraint — outputs go through internal `dispatch()`,
  never `execute()`.
- Produces:

```kotlin
// AutomationEngine.kt
data class StatusTransitionTrigger(
    val task: Task,                     // post-transition state
    val enteredStatusId: WorkflowStatusId,
    val reminder: Reminder?,            // preserved by SET_DUE outputs
    val myDayMemberIds: Set<TaskId>,
    val myDayCount: Int,
    val today: LocalDate,
    val zoneId: String,
)

/** Deterministic: matching rules apply in ascending rule-id order.
 *  Idempotent verbs skip silently; outputs never re-enter evaluation
 *  because callers apply them via internal dispatch. */
fun evaluateAutomationRules(
    rules: List<AutomationRule>,
    trigger: StatusTransitionTrigger,
): List<DomainCommand>

const val MAX_MY_DAY_MEMBERS_FOR_AUTOMATION = 200  // mirror of the bound
```

- [ ] **Step 1: Write the failing engine unit tests**

`AutomationEngineTest.kt` (fixtures-based):

```kotlin
@Test
fun matchingRulesEmitVerbsInRuleIdOrderWithIdempotentSkips() {
    val task = OpenTasksFixtures.tasks.first { it.deletedAt == null }
        .copy(tagIds = setOf(TagId("tag-existing")))
    val entered = task.statusId
    fun rule(id: String, type: AutomationRuleType, tagId: TagId? = null, dueInDays: Int? = null) =
        AutomationRule(
            id = AutomationRuleId(id),
            workspaceId = OpenTasksFixtures.workspaceId,
            type = type,
            enabled = true,
            statusId = entered,
            tagId = tagId,
            dueInDays = dueInDays,
        )
    val trigger = StatusTransitionTrigger(
        task = task,
        enteredStatusId = entered,
        reminder = null,
        myDayMemberIds = emptySet(),
        myDayCount = 0,
        today = LocalDate.parse("2026-08-17"),
        zoneId = "Asia/Bangkok",
    )
    val outputs = evaluateAutomationRules(
        listOf(
            rule("b-tag", AutomationRuleType.ON_ENTER_ADD_TAG, tagId = TagId("tag-new")),
            rule("a-my-day", AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY),
            rule("c-skip", AutomationRuleType.ON_ENTER_ADD_TAG, tagId = TagId("tag-existing")),
            rule("d-due", AutomationRuleType.ON_ENTER_SET_DUE, dueInDays = 3),
        ),
        trigger,
    )
    assertEquals(3, outputs.size)
    assertTrue(outputs[0] is DomainCommand.AddTaskToMyDay)          // "a-my-day"
    val tag = outputs[1] as DomainCommand.SetTaskTag                // "b-tag"
    assertEquals(TagId("tag-new"), tag.tagId)
    assertTrue(tag.present)
    val schedule = outputs[2] as DomainCommand.SetTaskSchedule      // "d-due"
    assertEquals(task.start, schedule.start)
    assertEquals(
        ZonedDateTime.of(2026, 8, 20, 17, 0, 0, 0, ZoneId.of("Asia/Bangkok")).toInstant(),
        requireNotNull(schedule.due).instant,
    )
    assertEquals("Asia/Bangkok", requireNotNull(schedule.due).zoneId)
}

@Test
fun nonMatchingDisabledWrongStatusAndCapRulesEmitNothing() { … }
```

The second test covers: disabled rule; different `statusId`; member
already on My Day; My Day at 200; sweep/stale types ignored.

- [ ] **Step 2: Run to verify failure, implement the engine**

```kotlin
fun evaluateAutomationRules(
    rules: List<AutomationRule>,
    trigger: StatusTransitionTrigger,
): List<DomainCommand> = rules
    .asSequence()
    .filter { it.enabled && it.statusId == trigger.enteredStatusId }
    .sortedBy { it.id.value }
    .mapNotNull { rule ->
        when (rule.type) {
            AutomationRuleType.ON_ENTER_ADD_TAG -> rule.tagId
                ?.takeIf { it !in trigger.task.tagIds && trigger.task.tagIds.size < 50 }
                ?.let { DomainCommand.SetTaskTag(trigger.task.id, it, present = true) }
            AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY ->
                DomainCommand.AddTaskToMyDay(trigger.task.id)
                    .takeIf {
                        trigger.task.id !in trigger.myDayMemberIds &&
                            trigger.myDayCount < MAX_MY_DAY_MEMBERS_FOR_AUTOMATION
                    }
            AutomationRuleType.ON_ENTER_SET_DUE -> rule.dueInDays?.let { days ->
                val zone = ZoneId.of(trigger.zoneId)
                DomainCommand.SetTaskSchedule(
                    taskId = trigger.task.id,
                    start = trigger.task.start,
                    due = ZonedMoment(
                        instant = trigger.today.plusDays(days.toLong())
                            .atTime(17, 0).atZone(zone).toInstant(),
                        zoneId = trigger.zoneId,
                    ),
                    reminder = trigger.reminder,
                )
            }
            AutomationRuleType.MY_DAY_AUTO_REMOVE,
            AutomationRuleType.STALE_BADGE,
            -> null
        }
    }
    .toList()
```

Run: engine tests PASS.

- [ ] **Step 3: Write the failing integration tests**

`AutomationEvaluationTest.kt` on `InMemoryVaultRepository` with an
injected `InMemoryBackupJournal`:

```kotlin
@Test
fun ruleFiresInTheTriggersGenerationAndOneUndoRevertsEverything() = runBlocking {
    withTimeout(5_000) {
        val journal = InMemoryBackupJournal()
        val repository = InMemoryVaultRepository(backupJournal = journal)
        val snapshot = repository.currentWorkspace()
        val task = snapshot.tasks.first {
            it.deletedAt == null && !it.isCompleted && !it.isBlocked
        }
        val destination = snapshot.workflowStatuses.first {
            it.projectId == task.projectId && it.id != task.statusId &&
                it.archivedAt == null && it.semanticStatus != SemanticStatus.COMPLETED
        }
        val tag = snapshot.tags.first { it.id !in task.tagIds }
        repository.execute(
            DomainCommand.CreateAutomationRule(
                AutomationRule(
                    id = AutomationRuleId("rule-tag"),
                    workspaceId = OpenTasksFixtures.workspaceId,
                    type = AutomationRuleType.ON_ENTER_ADD_TAG,
                    enabled = true,
                    statusId = destination.id,
                    tagId = tag.id,
                ),
            ),
        )
        val generationBefore = journal.currentGeneration

        val moved = repository.execute(
            DomainCommand.ChangeTaskStatus(task.id, destination.id),
        )

        assertTrue(moved is CommandResult.Success)
        val after = repository.currentWorkspace().tasks.first { it.id == task.id }
        assertTrue(tag.id in after.tagIds)
        // Trigger and rule output share ONE journal generation.
        assertEquals(generationBefore + 1, journal.currentGeneration)

        // One undo reverts the move AND the rule's tag.
        val undo = requireNotNull((moved as CommandResult.Success).undo)
        assertTrue(undo is DomainCommand.UndoBatch)
        repository.execute(undo)
        val reverted = repository.currentWorkspace().tasks.first { it.id == task.id }
        assertEquals(task.statusId, reverted.statusId)
        assertFalse(tag.id in reverted.tagIds)
        // And the undo replay did NOT re-fire the rule.
        assertFalse(
            tag.id in repository.currentWorkspace().tasks
                .first { it.id == task.id }.tagIds,
        )
    }
}

@Test
fun completionAndBulkCompletionFireRulesOnTheCompletedColumn() { … }

@Test
fun projectMoveRemapAndRecurrenceSpawnNeverFireRules() { … }

@Test
fun sweepMyDayCommandIsIdempotentAndRuleGated() { … }
```

The exclusion test: an `ON_ENTER_ADD_TAG` rule on a backlog column, a
recurring task completed (its spawn lands in Planned/Backlog — no tag
appears on the spawn), and a `MoveTasksToProject` whose remap lands
tasks in the destination backlog — no tag. The sweep test drives
`SweepMyDay` directly (the app-side gate is Step 5's sweeper, unit-
tested by inspection here: with no `MY_DAY_AUTO_REMOVE` rule the
sweeper never dispatches — assert via a repository whose journal gains
no entries when `MyDaySweeper.sweep()` runs; `MyDaySweeper` takes a
`Provider<VaultRepository>`, so construct it with `Provider {
repository }`).

- [ ] **Step 4: Implement the repository hook in both engines**

Room — in `execute` (RoomVaultRepository.kt:178-202), replace
`val result = dispatch(command)` with:

```kotlin
val result = dispatch(command)
val finalResult = if (result is CommandResult.Success) {
    applyAutomationRules(command, result)
} else {
    result
}
```

append `finalResult` as the transaction's return value (the journal
`appendChanges` call stays where it is, after evaluation, so trigger
and outputs share the session's single lazily-allocated generation).

```kotlin
private suspend fun applyAutomationRules(
    command: DomainCommand,
    result: CommandResult.Success,
): CommandResult.Success {
    val transitioned: List<TaskId> = when (command) {
        is DomainCommand.ChangeTaskStatus,
        is DomainCommand.CompleteTask,
        ->
            (result.undo as? DomainCommand.RestoreTaskStatus)
                ?.let { listOf(it.taskId) }
                .orEmpty()
        is DomainCommand.CompleteTasks ->
            (result.undo as? DomainCommand.UndoBatch)
                ?.commands
                ?.filterIsInstance<DomainCommand.RestoreTaskStatus>()
                ?.map(DomainCommand.RestoreTaskStatus::taskId)
                ?.asReversed()   // stored reversed; recover application order
                .orEmpty()
        else -> emptyList()
    }
    if (transitioned.isEmpty()) return result
    val rules = database.workspaceDao().getAutomationRules()
        .mapNotNull { entity -> runCatching { entity.toModel() }.getOrNull() }
        .filter { it.enabled }
    if (rules.none { it.statusId != null }) return result

    val outputUndos = mutableListOf<DomainCommand>()
    for (taskId in transitioned) {
        val task = currentTask(taskId) ?: continue
        val myDay = database.workspaceDao().getMyDayEntries()
        val trigger = StatusTransitionTrigger(
            task = task,
            enteredStatusId = task.statusId,
            reminder = database.workspaceDao()
                .getReminderForTask(taskId.value)?.toModel(),
            myDayMemberIds = myDay.mapTo(hashSetOf()) { TaskId(it.taskId) },
            myDayCount = myDay.size,
            today = LocalDate.ofInstant(now(), zoneId()),
            zoneId = zoneId().id,
        )
        evaluateAutomationRules(rules, trigger).forEach { output ->
            // Internal dispatch: never execute() (non-reentrant mutex),
            // and internal dispatch never re-evaluates — single pass by
            // construction. A rejected output is skipped silently.
            val outcome = dispatch(output)
            if (outcome is CommandResult.Success) {
                outcome.undo?.let(outputUndos::add)
            }
        }
    }
    if (outputUndos.isEmpty()) return result
    // FLATTEN the trigger's undo: `rejectUndoCommand` preflights only a
    // fixed set of undo shapes and fails closed on anything else — a
    // nested UndoBatch would make the whole composed undo unreplayable.
    // CompleteTasks stores its inverses in reverse application order and
    // UndoBatch replays in list order, so splicing them after the output
    // undos preserves exactly the replay the repository intended.
    val triggerInverses: List<DomainCommand> = when (val undo = result.undo) {
        null -> emptyList()
        is DomainCommand.UndoBatch -> undo.commands
        else -> listOf(undo)
    }
    return result.copy(
        undo = DomainCommand.UndoBatch(outputUndos.asReversed() + triggerInverses),
    )
}
```

Additionally, extend `rejectUndoCommand` in BOTH engines
(RoomVaultRepository.kt:1895-1957; the InMemory mirror at
InMemoryVaultRepository.kt:1807) — the engine's output undos introduce
one new shape that can now appear inside a stored batch:

```kotlin
is DomainCommand.RemoveTaskFromMyDay -> null  // idempotent; cannot fail
```

and update the function's KDoc list of stored shapes accordingly. The
other output undos already preflight: `SetTaskTag` and
`SetTaskSchedule` are in the existing `when`, and the flattened trigger
inverses are `RestoreTaskStatus` rows.

Caveat baked into the tests: `currentTask` reads DAO state, not the
lagging `mutableWorkspace` StateFlow — Room's `currentTask`
(RoomVaultRepository.kt:2990-3037) already reads through the DAOs, so
the in-transaction view is authoritative. InMemory mirrors the same
function verbatim against `mutableWorkspace.value` (which its dispatch
has already updated synchronously), calling its own internal
`dispatch(output)`; its evaluation runs between `dispatch` and
`reconcileTaskTags` inside `execute`
(InMemoryVaultRepository.kt:132-157) so the tag reconciliation and the
journal diff both observe the rule outputs.

- [ ] **Step 5: The sweeper**

`app/src/main/kotlin/app/opentasks/myday/MyDaySweeper.kt`:

```kotlin
package app.opentasks.myday

import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.AutomationRuleType
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Dispatches the idempotent My Day rollover sweep when the
 * MY_DAY_AUTO_REMOVE rule is enabled. Silent by design: the message and
 * undo are dropped, exactly as FocusCoordinator's dispatches are.
 */
@Singleton
class MyDaySweeper @Inject constructor(
    private val repository: Provider<VaultRepository>,
) {
    suspend fun sweep() {
        val vault = runCatching { repository.get() }.getOrNull() ?: return
        val enabled = vault.currentWorkspace().automationRules.any {
            it.enabled && it.type == AutomationRuleType.MY_DAY_AUTO_REMOVE
        }
        if (!enabled) return
        val zone = ZoneId.systemDefault()
        val before = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        vault.execute(DomainCommand.SweepMyDay(before))
    }
}
```

`MainActivity`: inject `@Inject lateinit var myDaySweeper: MyDaySweeper`
and add a third independent block inside the `onStart` launch:

```kotlin
runCatching { myDaySweeper.sweep() }
```

(after the digest reconcile; each failure stays isolated, and a missing
vault returns without dispatching).

- [ ] **Step 6: Run everything and commit**

Run: `./gradlew :core:domain:testDebugUnitTest :core:data:testDebugUnitTest
&& ./gradlew :core:data:compileDebugAndroidTestKotlin
testDebugUnitTest lintDebug :app:assembleDebug`
Expected: PASS.

```bash
git add core/domain core/data app/src/main
git commit -m "feat: evaluate automation rules in-transaction with a rollover sweep"
```

---

### Task 15: Stale projection and row badges

**Files:**

- Create: `core/domain/src/main/kotlin/app/opentasks/core/domain/StaleRules.kt`
- Create: `core/domain/src/test/kotlin/app/opentasks/core/domain/StaleRulesTest.kt`
- Modify: `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/Components.kt`
  (`TaskRow` at 88-190)
- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/BoardView.kt`
  (`BoardTaskContent`)
- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`
  (`ProjectTaskRow`)
- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Test: `core/domain` unit + feature Compose tests

**Interfaces:**

- Consumes: `AutomationRule` STALE_BADGE rows; the ReviewQueue
  `lastTouched` definition (max of revision wall time and latest
  activity `createdAt`).
- Produces:

```kotlin
// StaleRules.kt
fun staleTaskIds(
    tasks: List<Task>,
    activityEntries: List<ActivityEntry>,
    rules: List<AutomationRule>,
    now: Instant,
): Set<TaskId>
```

`TaskRow` gains `stale: Boolean = false`; `BoardTaskContent` and
`ProjectTaskRow` gain the same; `TasksScreen`/`ProjectsScreen` gain
`staleTaskIds: Set<TaskId> = emptySet()`.

- [ ] **Step 1: Write the failing domain tests**

```kotlin
@Test
fun staleUsesLastTouchedThresholdsAndScopeOverride() {
    val now = Instant.parse("2026-08-17T12:00:00Z")
    val base = OpenTasksFixtures.tasks.first { it.deletedAt == null && !it.isCompleted }
    fun task(id: String, touchedDaysAgo: Long, projectId: ProjectId? = base.projectId) =
        base.copy(
            id = TaskId(id),
            projectId = projectId,
            revision = base.revision.copy(
                wallTimeMillis = now.minus(Duration.ofDays(touchedDaysAgo)).toEpochMilli(),
            ),
        )
    fun rule(id: String, days: Int, projectId: ProjectId? = null) = AutomationRule(
        id = AutomationRuleId(id),
        workspaceId = OpenTasksFixtures.workspaceId,
        type = AutomationRuleType.STALE_BADGE,
        enabled = true,
        projectId = projectId,
        thresholdDays = days,
    )
    val global = rule("global", days = 14)
    val scoped = rule("scoped", days = 3, projectId = base.projectId)

    // Global only: 15 days is stale, 13 is not.
    assertEquals(
        setOf(TaskId("old")),
        staleTaskIds(
            listOf(task("old", 15), task("fresh", 13)),
            emptyList(), listOf(global), now,
        ),
    )
    // Project rule overrides global inside its project.
    assertEquals(
        setOf(TaskId("old"), TaskId("fresh")),
        staleTaskIds(
            listOf(task("old", 15), task("fresh", 13)),
            emptyList(), listOf(global, scoped), now,
        ),
    )
    // Recent activity un-stales an old revision.
    val activity = ActivityEntry(
        id = "a-1", taskId = TaskId("old"), projectId = base.projectId,
        kind = ActivityKind.STATUS_CHANGED, body = "",
        createdAt = now.minus(Duration.ofDays(1)),
    )
    assertTrue(
        staleTaskIds(listOf(task("old", 15)), listOf(activity), listOf(global), now)
            .isEmpty(),
    )
    // Completed and binned tasks are never stale; a disabled rule is inert.
    …
}
```

- [ ] **Step 2: Implement**

```kotlin
fun staleTaskIds(
    tasks: List<Task>,
    activityEntries: List<ActivityEntry>,
    rules: List<AutomationRule>,
    now: Instant,
): Set<TaskId> {
    val staleRules = rules.filter {
        it.enabled && it.type == AutomationRuleType.STALE_BADGE && it.thresholdDays != null
    }
    if (staleRules.isEmpty()) return emptySet()
    val global = staleRules.filter { it.projectId == null }
        .minByOrNull { it.id.value }
    val byProject = staleRules.filter { it.projectId != null }
        .groupBy { requireNotNull(it.projectId) }
        .mapValues { (_, matching) -> matching.minBy { it.id.value } }
    val latestActivity = activityEntries
        .filter { it.taskId != null }
        .groupBy { requireNotNull(it.taskId) }
        .mapValues { (_, entries) -> entries.maxOf(ActivityEntry::createdAt) }
    return tasks.asSequence()
        .filter { it.deletedAt == null && !it.isCompleted }
        .mapNotNull { task ->
            val rule = task.projectId?.let(byProject::get) ?: global ?: return@mapNotNull null
            val threshold = Duration.ofDays(requireNotNull(rule.thresholdDays).toLong())
            val touched = maxOf(
                Instant.ofEpochMilli(task.revision.wallTimeMillis),
                latestActivity[task.id] ?: Instant.EPOCH,
            )
            task.id.takeIf { Duration.between(touched, now) > threshold }
        }
        .toSet()
}
```

(Duplicate rules at the same scope resolve deterministically by lowest
rule id.)

- [ ] **Step 3: Badges and wiring**

`TaskRow`: after the blocked icon block (Components.kt:172-180), when
`stale`:

```kotlin
if (stale) {
    Spacer(Modifier.width(8.dp))
    Icon(
        Icons.Rounded.Schedule,
        contentDescription = stringResource(R.string.task_stale_description),
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.secondary,
    )
}
```

with `task_stale_description` ("Untouched for a while") in the
designsystem module's `res/values/strings.xml` (create the file if the
module has none — check first; if the module carries no res directory,
put the string in each consuming feature and pass plain
`contentDescription` text down instead — one-line decision recorded in
the commit message). `BoardTaskContent` and `ProjectTaskRow` render the
same 18 dp icon in their metadata rows when their new `stale` flag is
set. App computes once:

```kotlin
val staleIds = remember(snapshot.tasks, snapshot.activityEntries,
    snapshot.automationRules, projectionClock) {
    staleTaskIds(
        tasks = snapshot.tasks,
        activityEntries = snapshot.activityEntries,
        rules = snapshot.automationRules,
        now = Instant.now(projectionClock),
    )
}
```

and passes `staleTaskIds = staleIds` into `TasksScreen` and
`ProjectsScreen` (rows check membership). Home is deliberately
badge-free (spec scope).

- [ ] **Step 4: Compose tests, gate, commit**

Feature tests: a row/card with `stale = true` exposes the badge's
content description; `stale = false` does not.

Run: `./gradlew :core:domain:testDebugUnitTest
:feature:tasks:compileDebugAndroidTestKotlin
:feature:projects:compileDebugAndroidTestKotlin
testDebugUnitTest lintDebug :app:assembleDebug`
Expected: PASS.

```bash
git add core/domain core/designsystem feature/tasks feature/projects app/src/main
git commit -m "feat: badge stale tasks from a pure projection"
```

---

### Task 16: Automations editor on More

**Files:**

- Create: `feature/more/src/main/kotlin/app/opentasks/feature/more/AutomationsSection.kt`
- Modify: `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`
- Modify: `feature/more/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
  (`MoreScreen` wiring at 1585+)
- Test: feature/more Compose test source set

**Interfaces:**

- Consumes: rule CRUD commands (Task 5), `AutomationRule` model,
  `AutomationRuleId.new()`.
- Produces:

```kotlin
@Composable
fun AutomationsSection(
    rules: List<AutomationRule>,
    projects: List<Project>,
    workflowStatuses: List<WorkflowStatus>,
    tags: List<Tag>,
    onCreateRule: (AutomationRule) -> Unit,
    onUpdateRule: (AutomationRule) -> Unit,
    onDeleteRule: (AutomationRuleId) -> Unit,
    modifier: Modifier = Modifier,
)
```

`MoreScreen` gains those parameters and renders the section between its
existing content blocks (beside the templates/backup sections — match
the screen's existing section pattern).

- [ ] **Step 1: Write the failing Compose tests**

```kotlin
@Test
fun automationsListRulesToggleEnabledAndDelete() {
    var updated: AutomationRule? = null
    var deleted: AutomationRuleId? = null
    val rule = AutomationRule(
        id = AutomationRuleId("rule-1"),
        workspaceId = OpenTasksFixtures.workspaceId,
        type = AutomationRuleType.STALE_BADGE,
        enabled = true,
        thresholdDays = 14,
    )
    // AutomationsSection with the fixture reference lists.
    composeRule.onNodeWithTag("automation-enabled-rule-1").performClick()
    assertEquals(false, requireNotNull(updated).enabled)
    composeRule.onNodeWithTag("automation-delete-rule-1").performClick()
    composeRule.onNodeWithTag("automation-delete-confirm").performClick()
    assertEquals(rule.id, deleted)
}

@Test
fun addFlowGatesPerTypeConfigAndEmitsAValidRule() {
    var created: AutomationRule? = null
    // Open the add sheet, pick ON_ENTER_ADD_TAG, verify the confirm is
    // disabled until a status and tag are chosen, choose both, confirm.
    …
    val rule = requireNotNull(created)
    assertEquals(AutomationRuleType.ON_ENTER_ADD_TAG, rule.type)
    assertNotNull(rule.statusId)
    assertNotNull(rule.tagId)
}

@Test
fun brokenReferenceRuleRendersItsErrorState() {
    // A rule whose statusId matches no workflow status shows
    // automation_broken text and keeps its delete affordance.
    …
}
```

- [ ] **Step 2: Run to verify failure, then implement**

`AutomationsSection`: a `SectionHeader`
(`automations_heading` "Automations", supporting text
`automations_count` plural "n of 20 rules") with an add `TextButton`
(testTag `automation-add`). Each rule renders a row: a two-line summary
— type label (`automation_type_add_tag` "Add a tag on entering",
`automation_type_add_my_day` "Add to My Day on entering",
`automation_type_set_due` "Set due date on entering",
`automation_type_sweep` "Remove completed from My Day daily",
`automation_type_stale` "Badge stale tasks") plus a config line
resolved from the reference lists ("Studio · In progress · #focus",
"After 14 days", …) — a Material `Switch` (testTag
`automation-enabled-{id}`) calling `onUpdateRule(rule.copy(enabled =
!rule.enabled))`, and a delete `IconButton` (testTag
`automation-delete-{id}`) guarded by an `AlertDialog`
(`automation_delete_title`/`_body`/`_confirm` with testTag
`automation-delete-confirm`). A rule whose `statusId`/`tagId`/
`projectId` resolves to nothing renders `automation_broken` ("This rule
points at something that no longer exists") in the error colour; the
engine already skips it, so the row is inert but deletable.

The add flow is a `ModalBottomSheet` (WorkflowEditorSheet mold): five
`FilterChip`s for the type, then per-type config — status picker (a
dropdown of active statuses labelled "project · status name", Inbox
statuses labelled "Inbox · name"), tag dropdown, an
`OutlinedTextField(KeyboardType.Number)` for days (0–365 for due,
1–365 for stale) — and a confirm `TextButton` (testTag
`automation-create-confirm`, ≥48 dp) enabled only when the type's
required config is present and in range, emitting:

```kotlin
onCreateRule(
    AutomationRule(
        id = AutomationRuleId.new(),
        workspaceId = OpenTasksFixtures.workspaceId,
        type = selectedType,
        enabled = true,
        projectId = selectedProjectId,   // stale scope only, optional
        statusId = selectedStatusId,
        tagId = selectedTagId,
        dueInDays = dueDays,
        thresholdDays = staleDays,
    ),
)
```

(`OpenTasksFixtures.workspaceId` is the single-workspace product
constant the repositories themselves seed with.) `MoreScreen` threads
the parameters; `OpenTasksApp` passes `snapshot.automationRules`,
`snapshot.projects`, `snapshot.workflowStatuses`, `snapshot.tags` and
dispatches the three commands via `viewModel.execute` (rejections —
bound, invalid config, missing refs — surface as the ordinary
snackbar).

- [ ] **Step 3: Gate and commit**

Run: `./gradlew :feature:more:compileDebugAndroidTestKotlin
testDebugUnitTest lintDebug :app:assembleDebug`
Expected: PASS.

```bash
git add feature/more app/src/main
git commit -m "feat: add the automations editor to more"
```

---

### Task 17: Qualify Stage 9 and cut sideload release 1.3.0

**Files:**

- Create: `docs/qualification/stage9-board-flow-automation.md`
- Create: `docs/qualification/release-1.3.0-sideload.md`
- Modify: `HANDOFF.md`
- Modify: `app/build.gradle.kts` (versionName 1.3.0, versionCode 4 —
  per `RELEASING.md`)

**Interfaces:** Consumes everything; produces the tagged release and the
updated handoff.

- [ ] **Step 1: Forced-fresh host gates**

Run, as separate invocations:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --rerun-tasks
./gradlew :app:assembleRelease --rerun-tasks
```

Expected: both BUILD SUCCESSFUL; record task and test counts in the
qualification record. Never combine `lintDebug` with `assembleRelease`.

- [ ] **Step 2: Determinism and scope scans**

```bash
./scripts/check-schema-drift.sh
node scripts/generate-stage2-backup-v1-fixtures.mjs && git diff --exit-code core/data/src/test/resources
./scripts/verify-actions-workflow.sh
git diff --stat "$(cat .superpowers/sdd/2026-08-17-stage-9-board-flow-automation-plan/progress.md | head -1)"..HEAD
```

Expected: schemas 1–9 untouched plus a stable `10.json`; fixture
regeneration byte-identical; workflow pins intact; the diff contains
only files this plan names. Grep gates: no `Color(0x` outside
designsystem, no logging of task text/rule config
(`grep -rn "Log\." app core feature --include=*.kt` reviewed), Stage 5
`.otvault` and Drive fixtures untouched
(`git diff --exit-code` over their resource directories).

- [ ] **Step 3: Six-module connected gate**

On the sole disposable ADB target started `-read-only
-no-snapshot-load -no-snapshot-save` (NEVER the protected
`Pixel_10_Pro_Fold`):

```bash
./gradlew :app:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest :feature:more:connectedDebugAndroidTest
```

Expected: 0 failures, only the two established skips (credentialed
Drive, cross-display fold). The migration test
`migrate9To10PreservesRowsAddsEmptyTablesAndStampsMarker` and the new
Room repository twins run here for the first time — budget a fix round.

- [ ] **Step 4: Whole-stage independent review**

Use superpowers:requesting-code-review over the recorded audit base to
HEAD. Close with zero Critical and zero Important findings, fixing
inside this task.

- [ ] **Step 5: Manual acceptance matrix (sideload per RELEASING.md)**

Record in `docs/qualification/stage9-board-flow-automation.md`:

1. v9→v10 upgrade in place preserves the workspace (sideload over the
   1.2.0 build; boards, tasks, saved views, backups intact).
2. WIP: set a limit of 1, drag a second card in → confirm dialog; menu
   move and accessibility action show the same dialog; confirm moves,
   dismiss leaves; header shows `n / limit` with over-limit tint;
   completion into Done never confirms.
3. Subtasks: attach, one-level guard messages, quick-add from detail,
   indented list and workbench rows, board rollup chip on card and drag
   preview, parent-completion confirm (single and bulk), subtree bin +
   single undo, child restore detaches.
4. My Day: add from board menu/detail/suggestions, drag and menu
   reorder, completed member dims, rollover sweep removes yesterday's
   completions exactly once with the rule on and not at all with it
   off, 200-bound message.
5. Automations: create each of the five rule types, on-enter rules fire
   from board drag AND a reminder-notification completion, one snackbar
   undo reverts trigger plus effects, broken-reference rule shows its
   error and stays skipped.
6. Stale: badge appears past threshold, clears on touch, project scope
   overrides global.
7. Digest and Today widget behave exactly as on 1.2.0 (untouched
   contracts).
8. Recovery: a backup produced on 1.3.0 recovers on 1.3.0 (rules, My
   Day, limits intact); a 1.2.0 backup recovers on 1.3.0 (dual-arity);
   a 1.3.0 backup on a 1.2.0 build refuses legibly at the row-marker
   gate.

- [ ] **Step 6: Release and handoff**

Follow `RELEASING.md` for the signed sideload build, verification
script, smoke rows, tag `v1.3.0`, and `docs/qualification/
release-1.3.0-sideload.md`. Update `HANDOFF.md` with the Stage 9
completion checkpoint (implementation SHAs, gate evidence, release
record) and commit:

```bash
git add docs HANDOFF.md app/build.gradle.kts
git commit -m "docs: qualify stage 9 and record release 1.3.0"
```

---

## Spec Coverage Map

Sequencing note: the spec's "v10 wave, alone" is Tasks 1–5 — the
durable boundary (1–3) plus the store commands whose dual-engine parity
the wave pins (4–5). No feature surface lands before Task 6, matching
the spec order wave → WIP → subtasks → My Day → automation.

- Room v10 wave, stamped marker, no parent index → Task 1.
- Dual-arity WORKFLOW_STATUS within v1, frozen fixture sets → Task 2.
- AUTOMATION_RULE + MY_DAY families, content fingerprints, recovery
  import, fixture regeneration, verifier → Task 3.
- My Day commands, rankBetween + re-rank fallback, purge cleanup,
  200-bound, projection with dim/filter → Task 4.
- Rule CRUD, per-type validation, 20-bound, fail-closed refs → Task 5.
- WIP command + soft enforcement basis and exemptions → Task 6.
- WIP editor field, board badge/tint, unified board confirm path
  (fixing the latent blocked-dialog gap) → Task 7.
- SetTaskParent / CreateTask(parent:) one-level guards, clamped
  rendering of recovered deeper trees → Tasks 8, 10.
- Completion confirm (single + bulk), subtree bin/restore/detach,
  project-move cascade/detach → Task 9; dialogs → Task 11.
- arrangeTasks nesting authority, indentation, rollup chips → Task 10.
- Detail subtask section, attach picker, breadcrumb → Task 11.
- My Day on Home, drag + 48 dp fallback, dim, empty state → Task 12.
- Curation sheet, suggestions, entry points, Plan rewire → Task 13.
- Engine placement, firing conditions, UndoBatch composition, sweep at
  the foreground reconcile, silence → Task 14.
- Stale projection + badges → Task 15.
- Automations editor on More → Task 16.
- Exit criteria, qualification, release → Task 17.

Recorded deviations and deliberate omissions (spec-sanctioned or
flagged for the reviewer):

- "Add to My Day in task-row overflow menus" is delivered as the
  board-card menu + detail toggle + curation sheet; the shared
  `TaskRow` has no overflow menu and long-press is claimed by bulk
  selection (Task 13 notes this in-code).
- No activity entries for SetTaskParent or My Day mutations:
  `ActivityKind` gains no value this stage; append-only makes that
  cheap later if wanted.
- `ReopenTask` is not a rule trigger — the spec's trigger list is
  exactly `ChangeTaskStatus`/`CompleteTask`/`CompleteTasks`.
- Widget and digest read `computeTodayProjection` unchanged; Home
  alone consumes `myDayTasks`.

## Deliberate Ceilings Preserved

- Board in-column manual ordering stays unscheduled; My Day holds the
  roadmap's only manual rank.
- Automation rules act only through ordinary `DomainCommand`s; no rule
  may move a task between statuses, so rule-engine WIP interaction is
  structurally impossible.
- `my_day_entries` and `automation_rules` carry no revision columns by
  design — content fingerprints journal every edit; adding revisions
  later is a schema change requiring v11.
- The one-level subtask guard lives at the command boundary; backup
  validators keep accepting any acyclic parent graph, and rendering
  clamps depth.
