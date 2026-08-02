# Stage 4 Notes, Activity, Cloud Attachments, and Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finalise the local structured metadata schema (Room v8) and deliver
first-class notes, immutable activity history, the create-only cloud
attachment blob lifecycle, and search over note bodies and attachment display
names.

**Architecture:** Room stays the sole live structured-data authority. Notes
and activity are ordinary repository records with atomic backup-journal
writes. Attachment bytes live only in a new create-only `AttachmentBlobStore`
Drive namespace: immutable chunk objects plus one blob-set manifest,
authenticated to the Stage 3 lineage; ownership is the existing chain tip, and
garbage collection reuses the bounded namespace-safe cleanup pattern. There is
no conditional provider write, update path, ETag, or provider-revision concept
anywhere.

**Tech Stack:** Kotlin 2.3.21, AGP 9.3.1, Java 17 on JDK 21, Compose BOM
2026.06.01, Room 2.8.4, SQLCipher 4.15.0, Tink 1.23.0, kotlinx.serialization
1.11.0, WorkManager 2.11.2, `HttpURLConnection`, AndroidX Activity Photo
Picker (`PickVisualMedia`) and `OpenDocument`, androidx.core `FileProvider`,
JUnit 4, Compose UI test v2, Node.js `crypto` for independent fixtures. No new
version-catalog entries.

## Global Constraints

- Authority spec:
  `docs/superpowers/specs/2026-08-02-stage-4-notes-activity-cloud-attachments-search-design.md`.
- Work directly on `main`; no branch, worktree, or pull request.
- The user-owned untracked `artifacts/` and `.kotlin/` stay untouched.
- Never start, install to, instrument, or mutate the protected
  `Pixel_10_Pro_Fold` AVD outside the recorded credentialed-gate procedure.
  Connected suites run only on a sole disposable ADB target started with
  `-read-only -no-snapshot-load -no-snapshot-save`.
- Bounds are exact and verbatim from the spec: note body 10,000 chars, 500
  notes per owner; 500 activity entries per owner with oldest-first pruning;
  attachment display name and MIME type 255 chars; attachment 100 MiB, 4 MiB
  chunks, 25 chunks max, 100 attachments per task; GC 32 deletes per pass;
  provisional sessions expire after 24 hours; cache ceiling
  min(128 MiB, 5% available storage); blob retention ≥30 days after a
  tombstone covered by every retained verified base.
- No conditional provider writes, no update/PATCH, no ETag/If-Match, no
  provider-revision concept. Only `drive.appdata`. Immutable create-by-ID
  objects; unknown or ambiguous remote objects fail closed.
- Do not modify the frozen `RemoteObjectRoleV1` enum or any Stage 3 codec,
  cleanup disposition, or ownership format. Attachments get their own store,
  role tags, and cleanup.
- Logs and telemetry never contain task text, note text, attachment display
  names, account details, Drive IDs, or encryption metadata.
- Every write is a `DomainCommand` through `VaultRepository.execute`; records
  and ordered backup-journal entries commit in one transaction; Undo is
  repository-produced. `InMemoryVaultRepository` stays behaviourally in sync
  with `RoomVaultRepository`, including journal atomicity.
- Room v8 requires exported `core/data/schemas/.../8.json` and a
  non-destructive `Migration(7, 8)` with a preservation fixture test.
- Feature composables stay stateless (data in, lambdas out), no Hilt in
  feature/core modules, new UI copy in `res/values/strings.xml` in UK English,
  OKLCH-only colours, 4 dp spacing scale, Material typography roles.
- Tests: JUnit 4 `org.junit.Assert.*`, no mocking library, `runBlocking` +
  `withTimeout(5_000)`, camelCase behaviour names,
  `androidx.compose.ui.test.junit4.v2.createComposeRule`.
- The CI gate before any completion claim:
  `./gradlew testDebugUnitTest lintDebug :app:assembleDebug`. Release
  assembly runs separately, never combined with `lintDebug`.
- Commit after every task with a conventional-prefix message ending in
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Execution notes

- Each task is an independent review boundary: dispatch an independent
  review after the task commit and fix findings before the next task.
- Tasks 1–8 need no device. Instrumented tests written in Tasks 2, 4, 5, 7,
  9–13 are compiled locally (`compileDebugAndroidTestKotlin` of the owning
  module) and executed on the sole disposable AVD at the Task 14 connected
  gate, plus earlier focused runs where a step says so.
- The one deliberate format change: the Stage 2 `ATTACHMENT` backup-record
  schema is finalised in place (Task 3). No production code path has ever
  written an attachment record or journal payload, so no encoded instance of
  the old shape can exist; the reviewer must re-verify that fact before
  approving Task 3.

---

### Task 1: Note record, commands, and in-memory behaviour

**Files:**

- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Identifiers.kt`
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt`
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt`
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Fixtures.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryNoteCommandTest.kt`

**Interfaces:**

- Consumes: `Revision`, `TaskId`, `ProjectId`, `CommandResult`,
  `RejectionReason`, the `publish`/`nextRevision`/`validate*` helper pattern.
- Produces:

```kotlin
@JvmInline
value class NoteId(val value: String) {
    companion object {
        fun new(): NoteId = NoteId(UUID.randomUUID().toString())
    }
}

data class Note(
    val id: NoteId,
    val taskId: TaskId?,
    val projectId: ProjectId?,
    val body: String,
    val createdAt: Instant,
    val editedAt: Instant?,
    val revision: Revision,
)
```

New `DomainCommand` members (nested in the existing sealed interface, after
the time-entry block):

```kotlin
    data class AddNote(
        val taskId: TaskId?,
        val projectId: ProjectId?,
        val body: String,
        val createdAt: Instant = Instant.now(),
    ) : DomainCommand

    data class UpdateNote(
        val noteId: NoteId,
        val body: String,
        val editedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class DeleteNote(val noteId: NoteId) : DomainCommand

    data class RestoreNote(val note: Note) : DomainCommand
```

New `RejectionReason` entries: `EMPTY_NOTE`, `NOTE_TOO_LONG`,
`NOTE_LIMIT_REACHED`. `WorkspaceSnapshot` gains
`val notes: List<Note> = emptyList()`. Bounds constants in both repository
companions: `MAX_NOTE_BODY_LENGTH = 10_000`, `MAX_NOTES_PER_OWNER = 500`.
Undo contract: `AddNote → DeleteNote(id)`, `UpdateNote → RestoreNote(prior)`,
`DeleteNote → RestoreNote(note)`.

- [ ] **Step 1: Write the failing in-memory tests**

Create `InMemoryNoteCommandTest.kt`:

```kotlin
class InMemoryNoteCommandTest {

    private val repository = InMemoryVaultRepository()

    @Test
    fun addNoteToTaskAppearsInSnapshotWithExactUndo() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()

            val result = repository.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = null, body = "  Call the venue  "),
            ) as CommandResult.Success

            val note = repository.currentWorkspace().notes.single { it.taskId == task.id }
            assertEquals("Call the venue", note.body)
            assertNull(note.editedAt)
            val undo = result.undo as DomainCommand.DeleteNote
            assertEquals(note.id, undo.noteId)
        }
    }

    @Test
    fun addNoteRequiresExactlyOneOwner() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            val project = repository.currentWorkspace().projects.first()

            val both = repository.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = project.id, body = "x"),
            )
            val neither = repository.execute(
                DomainCommand.AddNote(taskId = null, projectId = null, body = "x"),
            )

            assertEquals(RejectionReason.INVALID_STATE, (both as CommandResult.Rejected).reason)
            assertEquals(RejectionReason.INVALID_STATE, (neither as CommandResult.Rejected).reason)
        }
    }

    @Test
    fun noteBodyBoundsAreEnforced() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()

            val empty = repository.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = null, body = "   "),
            )
            val oversize = repository.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = null, body = "a".repeat(10_001)),
            )

            assertEquals(RejectionReason.EMPTY_NOTE, (empty as CommandResult.Rejected).reason)
            assertEquals(RejectionReason.NOTE_TOO_LONG, (oversize as CommandResult.Rejected).reason)
        }
    }

    @Test
    fun updateAndDeleteProduceExactRestoreUndo() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            repository.execute(DomainCommand.AddNote(taskId = task.id, projectId = null, body = "v1"))
            val original = repository.currentWorkspace().notes.single { it.taskId == task.id }

            val updated = repository.execute(
                DomainCommand.UpdateNote(original.id, "v2"),
            ) as CommandResult.Success
            val afterUpdate = repository.currentWorkspace().notes.single { it.id == original.id }
            assertEquals("v2", afterUpdate.body)
            assertNotNull(afterUpdate.editedAt)
            assertEquals(original, (updated.undo as DomainCommand.RestoreNote).note)

            val deleted = repository.execute(
                DomainCommand.DeleteNote(original.id),
            ) as CommandResult.Success
            assertTrue(repository.currentWorkspace().notes.none { it.id == original.id })
            assertEquals(afterUpdate, (deleted.undo as DomainCommand.RestoreNote).note)

            repository.execute(deleted.undo as DomainCommand)
            assertEquals(afterUpdate, repository.currentWorkspace().notes.single { it.id == original.id })
        }
    }

    @Test
    fun ownerNoteCapIsEnforcedAt500() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            repeat(500) {
                val result = repository.execute(
                    DomainCommand.AddNote(taskId = task.id, projectId = null, body = "note $it"),
                )
                assertTrue(result is CommandResult.Success)
            }

            val overCap = repository.execute(
                DomainCommand.AddNote(taskId = task.id, projectId = null, body = "one more"),
            )

            assertEquals(
                RejectionReason.NOTE_LIMIT_REACHED,
                (overCap as CommandResult.Rejected).reason,
            )
        }
    }
}
```

- [ ] **Step 2: Run for RED**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*InMemoryNoteCommandTest*"`
Expected: compilation failure (`NoteId`, `AddNote` unresolved).

- [ ] **Step 3: Implement model, commands, and in-memory handlers**

Add `NoteId`, `Note`, snapshot field, `RejectionReason` entries, and the four
commands exactly as in Interfaces. In `InMemoryVaultRepository` add dispatch
arms and handlers following the checklist-item pattern; the core of `addNote`:

```kotlin
    private fun addNote(command: DomainCommand.AddNote): CommandResult {
        val current = mutableWorkspace.value
        if ((command.taskId == null) == (command.projectId == null)) {
            return CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "A note belongs to exactly one task or project.",
            )
        }
        command.taskId?.let { taskId ->
            current.tasks.firstOrNull { it.id == taskId && it.deletedAt == null }
                ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Task no longer exists.")
        }
        command.projectId?.let { projectId ->
            current.projects.firstOrNull { it.id == projectId }
                ?: return CommandResult.Rejected(RejectionReason.NOT_FOUND, "Project no longer exists.")
        }
        val body = command.body.trim()
        validateNoteBody(body)?.let { return it }
        val owned = current.notes.count {
            it.taskId == command.taskId && it.projectId == command.projectId
        }
        if (owned >= MAX_NOTES_PER_OWNER) {
            return CommandResult.Rejected(
                RejectionReason.NOTE_LIMIT_REACHED,
                "A task or project can hold up to $MAX_NOTES_PER_OWNER notes.",
            )
        }
        val note = Note(
            id = NoteId.new(),
            taskId = command.taskId,
            projectId = command.projectId,
            body = body,
            createdAt = command.createdAt,
            editedAt = null,
            revision = Revision(sourceDeviceId, command.createdAt.toEpochMilli(), 0),
        )
        mutableWorkspace.value = current.copy(notes = current.notes + note)
        return CommandResult.Success("Note added", undo = DomainCommand.DeleteNote(note.id))
    }
```

`validateNoteBody` mirrors `validateChecklistText` (`EMPTY_NOTE`,
`NOTE_TOO_LONG`). `updateNote` trims, validates, bumps `revision` via the
note-adapted `nextRevision` form
`Revision(deviceId, maxOf(prior.wallTimeMillis + 1, now), prior.logicalCounter + 1)`,
sets `editedAt`, returns `RestoreNote(prior)`. `deleteNote` removes the row
and returns `RestoreNote(note)`. `restoreNote` re-inserts the exact value
(after the same owner-existence check), message "Note restored". Add two
fixture notes (one task-owned on `task-proposal`, one project-owned on
`studioProject`) to `OpenTasksFixtures.snapshot`.

- [ ] **Step 4: Run for GREEN**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*InMemoryNoteCommandTest*"`
Expected: PASS. Then the full unit sweep for regressions:
`./gradlew :core:data:testDebugUnitTest :core:domain:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS (fixture-dependent suites may need the new snapshot fields
tolerated; fix any strict fixture equality in place).

- [ ] **Step 5: Commit**

```bash
git add -A core feature app
git commit -m "feat: add first-class note commands"
```

---

### Task 2: Room v8 — note entity, finalised attachment, transfer state, migration

**Files:**

- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Identifiers.kt`
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt`
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/Entities.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/EntityMappers.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
- Create: `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/8.json`
  (generated)
- Test:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/VaultDatabaseMigrationInstrumentedTest.kt`
  (extend)

**Interfaces:**

- Consumes: `Revision`, entity/mapper conventions
  (`bodyCiphertext = body.toByteArray(Charsets.UTF_8)`, revision triple
  columns `revisionWallMillis`/`revisionLogical`/`revisionDeviceId`).
- Produces:

```kotlin
@JvmInline
value class BlobSetId(val value: String) {
    companion object {
        fun new(): BlobSetId = BlobSetId(UUID.randomUUID().toString())
    }
}
// AttachmentId gains: fun new(): AttachmentId = AttachmentId(UUID.randomUUID().toString())

data class Attachment(
    val id: AttachmentId,
    val taskId: TaskId,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val contentHash: String,
    val blobSetId: BlobSetId?,
    val chunkCount: Int,
    val deletedAt: Instant?,
    val revision: Revision,
)
```

(`keepOffline` is removed from the model; `blobSetId == null` means
metadata-only unavailable, the mapping for pre-Stage 4 placeholder rows.)
`WorkspaceSnapshot` gains `val attachments: List<Attachment> = emptyList()`
and `val activityEntries: List<ActivityEntry> = emptyList()`.

```kotlin
@Entity(tableName = "notes", primaryKeys = ["id"], indices = [Index("taskId"), Index("projectId")])
data class NoteEntity(
    val id: String,
    val taskId: String?,
    val projectId: String?,
    val bodyCiphertext: ByteArray,
    val createdAtEpochMillis: Long,
    val editedAtEpochMillis: Long?,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)

@Entity(tableName = "attachments", primaryKeys = ["id"], indices = [Index("taskId")])
data class AttachmentEntity(
    val id: String,
    val taskId: String,
    val displayNameCiphertext: ByteArray,
    val mimeType: String,
    val byteCount: Long,
    val contentHash: String,
    val blobSetId: String?,
    val chunkCount: Int,
    val deletedAtEpochMillis: Long?,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)

@Entity(tableName = "attachment_transfer", primaryKeys = ["blobSetId"])
data class AttachmentTransferEntity(
    val blobSetId: String,
    val attachmentId: String,
    val taskId: String,
    val phase: String,
    val displayNameCiphertext: ByteArray,
    val mimeType: String,
    val declaredByteCount: Long,
    val contentHash: String?,
    val chunkCount: Int?,
    val chunkStateEncoded: String,
    val manifestProviderFileId: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
```

Mappers: `NoteEntity.toModel(): Note`, `Note.toEntity(): NoteEntity`,
`AttachmentEntity.toModel(): Attachment`, `Attachment.toEntity()`.

- [ ] **Step 1: Extend the migration test first**

Add to `VaultDatabaseMigrationInstrumentedTest` following the 6→7 pattern
(`databaseNameV7`, `createV7`, `migrateTo8` helpers using
`VaultDatabase.MIGRATION_7_8`):

```kotlin
    @Test
    fun migrate7To8PreservesRowsAndRebuildsAttachments() {
        val databaseName = databaseNameV7("preserved")
        lateinit var before: Map<String, List<List<Any?>>>
        createV7(databaseName).use { database ->
            seedVersion7Fixture(database)
            database.execSQL(
                "INSERT INTO attachments (id, taskId, displayNameCiphertext, mimeType," +
                    " byteCount, contentHash, keepOffline) VALUES ('att-1', 'task-a'," +
                    " x'6e616d65', 'image/png', 42, 'hash-a', 1)",
            )
            before = database.captureVersion7Bytes()
        }

        val migrated = migrateTo8(databaseName)

        assertEquals(before, migrated.capturePreservedBytes())
        assertEquals(0, migrated.longValue("SELECT COUNT(*) FROM notes"))
        assertEquals(0, migrated.longValue("SELECT COUNT(*) FROM attachment_transfer"))
        assertEquals(
            listOf<Any?>("att-1", "task-a", "image/png", 42L, "hash-a", null, 0L, null),
            migrated.rowValues(
                "SELECT id, taskId, mimeType, byteCount, contentHash, blobSetId," +
                    " chunkCount, deletedAtEpochMillis FROM attachments",
            ),
        )
        assertEquals(
            8L,
            migrated.longValue("SELECT schemaVersion FROM vaults WHERE id = 'vault-a'"),
        )
        migrated.close()
    }
```

`captureVersion7Bytes`/`capturePreservedBytes` compare every table except
`attachments` (whose rebuild is asserted column-by-column above) and the two
new tables.

- [ ] **Step 2: Compile the test for RED**

Run: `./gradlew :core:data:compileDebugAndroidTestKotlin`
Expected: FAIL — `MIGRATION_7_8` unresolved.

- [ ] **Step 3: Implement entities, model change, and the additive migration**

Bump `version = 8`. `MIGRATION_7_8` body (exact SQL):

```kotlin
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notes (
                        id TEXT NOT NULL,
                        taskId TEXT,
                        projectId TEXT,
                        bodyCiphertext BLOB NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        editedAtEpochMillis INTEGER,
                        revisionWallMillis INTEGER NOT NULL,
                        revisionLogical INTEGER NOT NULL,
                        revisionDeviceId TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_taskId ON notes(taskId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_projectId ON notes(projectId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attachments_v8 (
                        id TEXT NOT NULL,
                        taskId TEXT NOT NULL,
                        displayNameCiphertext BLOB NOT NULL,
                        mimeType TEXT NOT NULL,
                        byteCount INTEGER NOT NULL,
                        contentHash TEXT NOT NULL,
                        blobSetId TEXT,
                        chunkCount INTEGER NOT NULL,
                        deletedAtEpochMillis INTEGER,
                        revisionWallMillis INTEGER NOT NULL,
                        revisionLogical INTEGER NOT NULL,
                        revisionDeviceId TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO attachments_v8 (id, taskId, displayNameCiphertext, mimeType,
                        byteCount, contentHash, blobSetId, chunkCount, deletedAtEpochMillis,
                        revisionWallMillis, revisionLogical, revisionDeviceId)
                    SELECT id, taskId, displayNameCiphertext, mimeType, byteCount,
                        contentHash, NULL, 0, NULL, 0, 0, ''
                    FROM attachments
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE attachments")
                db.execSQL("ALTER TABLE attachments_v8 RENAME TO attachments")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attachments_taskId ON attachments(taskId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attachment_transfer (
                        blobSetId TEXT NOT NULL,
                        attachmentId TEXT NOT NULL,
                        taskId TEXT NOT NULL,
                        phase TEXT NOT NULL,
                        displayNameCiphertext BLOB NOT NULL,
                        mimeType TEXT NOT NULL,
                        declaredByteCount INTEGER NOT NULL,
                        contentHash TEXT,
                        chunkCount INTEGER,
                        chunkStateEncoded TEXT NOT NULL,
                        manifestProviderFileId TEXT,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(blobSetId)
                    )
                    """.trimIndent(),
                )
                db.execSQL("UPDATE vaults SET schemaVersion = 8 WHERE schemaVersion < 8")
            }
        }
```

Register the three entities and `.addMigrations(..., MIGRATION_7_8)`. Update
the `Attachment` model, `BlobSetId`, mappers, and every compile site that
referenced `keepOffline` (the Task 3 backup files are updated in Task 3; if
they fail compilation now, make the minimal mechanical field-rename edit here
and leave semantic work for Task 3).

- [ ] **Step 4: Regenerate and verify the exported schema**

Run: `./gradlew :core:data:kspDebugKotlin` then
`scripts/check-schema-drift.sh`
Expected: `8.json` exists; drift script passes.

- [ ] **Step 5: Compile everything**

Run: `./gradlew :core:data:compileDebugAndroidTestKotlin testDebugUnitTest`
Expected: PASS (migration test executes at the connected gate).

- [ ] **Step 6: Commit**

```bash
git add -A core
git commit -m "feat: add room v8 notes and finalised attachments"
```

---

### Task 3: Backup record families — NOTE plus finalised ATTACHMENT

**Files:**

- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordV1.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupMutationCodec.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomBackupJournalSession.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupPayloadCodec.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RecoveryImportDao.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordImporter.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupDaos.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/StagedVaultVerifier.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
  (in-memory journal family support)
- Modify: `scripts/generate-stage2-backup-v1-fixtures.mjs`
- Modify: `core/data/src/test/resources/backup-format/v1/*.json` (regenerated)
- Test: extend `BackupMutationCodecTest.kt`, `BackupSnapshotCodecTest.kt`,
  `BackupPayloadGoldenTest.kt` in
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/`

**Interfaces:**

- Consumes: `NoteEntity`, finalised `AttachmentEntity`, the nine-touchpoint
  family checklist below.
- Produces: `BackupRecordFamily.NOTE` (appended after `SAVED_VIEW`, before
  `TOMBSTONE`), plus:

```kotlin
internal fun NoteEntity.toBackupRecordV1(): BackupRecordV1 = record(
    family = BackupRecordFamily.NOTE,
    identity = listOf(id),
    stringField("id", id),
    nullableStringField("taskId", taskId),
    nullableStringField("projectId", projectId),
    bytesField("bodyCiphertext", bodyCiphertext),
    longField("createdAtEpochMillis", createdAtEpochMillis),
    nullableLongField("editedAtEpochMillis", editedAtEpochMillis),
    longField("revisionWallMillis", revisionWallMillis),
    intField("revisionLogical", revisionLogical),
    stringField("revisionDeviceId", revisionDeviceId),
)
```

and the finalised `AttachmentEntity.toBackupRecordV1()` (drop
`booleanField("keepOffline", ...)`; add `nullableStringField("blobSetId")`,
`intField("chunkCount")`, `nullableLongField("deletedAtEpochMillis")`, and
the revision triple).

The complete touchpoint list this task must cover — every item test-first
where a unit test can reach it:

1. `BackupRecordFamily` enum + both `toBackupRecordV1()` functions.
2. `BackupMutationCodec` per-family semantic validation `when` (NOTE:
   `identifier("id")`, nullable owner identifiers with an
   exactly-one-owner check, `bounded("bodyCiphertext", 40_000)` bytes,
   `nonNegativeLong` timestamps, `revision()`) and the `schemas` map.
3. `BackupMutationDao`: `noteRevisions(): List<RevisionedIdRow>` +
   `note(id)`, and replace `attachmentIds()` with
   `attachmentRevisions(): List<RevisionedIdRow>` + keep `attachment(id)`
   lookups; extend `snapshots()` and `requireRecord()` (the exhaustive
   `when` forces this at compile time).
4. `BackupPayloadCodec` referential validation: a NOTE's `taskId` must be a
   snapshot task or its `projectId` a snapshot project; ATTACHMENT `taskId`
   must exist; both families join `recordsByFamily.getValue(...)`.
5. `RecoveryImportDao`: `insertNote`/`upsertNote`/`deleteNote(id)` triple.
6. `BackupRecordImporter`: NOTE upsert arm with the
   `entity.bodyCiphertext.fill(0)` finally pattern, delete arm, and
   `fields.toNoteEntity()`; update `fields.toAttachmentEntity()` to the
   final columns (also `displayNameCiphertext.fill(0)` in finally).
7. `BackupCaptureDao.notes(vaultId)` query; `StagedVaultVerifier`
   `stagedRecords` adds notes, and `retentionPurgeRemovals` treats a
   task-owned NOTE like `CHECKLIST_ITEM` and a project-owned NOTE as
   never task-purged (mirror the nullable-taskId `ACTIVITY_ENTRY` arm).
8. `InMemoryVaultRepository`/`InMemoryBackupJournal`: notes and finalised
   attachments participate in the before/after diff with the same
   family/identity encoding.
9. `generate-stage2-backup-v1-fixtures.mjs`: add one task NOTE, one project
   NOTE, and the finalised ATTACHMENT record to the snapshot fixture and a
   NOTE upsert to the segment fixture; regenerate; goldens assert the new
   canonical bytes and digests.

- [ ] **Step 1: Write failing codec tests**

Extend `BackupMutationCodecTest` using the file's existing payload-builder
helpers (build a `BackupMutationPayloadV1` around
`NoteEntity(...).toBackupRecordV1()` exactly as the existing
activity-entry cases do):

- `noteRecordRoundTripsCanonically` — encode, `decodeOwned`, assert the
  decoded payload equals the input and re-encoding is byte-identical;
- `noteWithBothOwnersIsRejected` — a NOTE record whose `taskId` and
  `projectId` fields are both non-null fails semantic validation; assert
  the same for both-null;
- `noteBodyCiphertextOverBoundIsRejected` — a `bodyCiphertext` above the
  40,000-byte field bound fails;
- `attachmentRecordCarriesBlobIdentityAndNoKeepOffline` — the finalised
  ATTACHMENT record round-trips with `blobSetId`, `chunkCount`,
  `deletedAtEpochMillis`, and the revision triple, and a record carrying a
  `keepOffline` field is rejected as an unknown field.

Extend `BackupSnapshotCodecTest` with a snapshot containing both note kinds
and a finalised attachment; assert referential rejection when a task NOTE's
owning task is absent, when a project NOTE's owning project is absent, and
when an ATTACHMENT's owning task is absent.

- [ ] **Step 2: Run for RED**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*BackupMutationCodecTest*" --tests "*BackupSnapshotCodecTest*"`
Expected: FAIL (missing family/fields).

- [ ] **Step 3: Implement all nine touchpoints**

Work through the list above; the exhaustive `when`s in `requireRecord`,
`BackupRecordImporter`, and the schemas map surface every omission as a
compile error.

- [ ] **Step 4: Regenerate fixtures and run the full backup suite**

Run: `node scripts/generate-stage2-backup-v1-fixtures.mjs` then
`./gradlew :core:data:testDebugUnitTest`
Expected: PASS including goldens; `git diff` shows only intended fixture
changes.

- [ ] **Step 5: Commit**

```bash
git add -A core scripts
git commit -m "feat: add note and finalised attachment backup families"
```

---

### Task 4: Room note commands and repository parity

**Files:**

- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
  (`WorkspaceDao` note queries)
- Test:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomNoteCommandInstrumentedTest.kt`

**Interfaces:**

- Consumes: Task 1 commands, Task 2 `NoteEntity`, Task 3 journal families.
- Produces: `WorkspaceDao` methods
  `observeNotes(workspaceScope: ...): Flow<List<NoteEntity>>` (follow the
  existing observe pattern used to build `WorkspaceSnapshot`),
  `upsertNote(value: NoteEntity)`, `deleteNote(id: String): Int`,
  `deleteNotesForTask(taskId: String)`, `deleteNotesForProject(projectId: String)`;
  Room dispatch arms `addNote`/`updateNote`/`deleteNote`/`restoreNote`
  identical in behaviour to Task 1's in-memory handlers, wrapped in
  `database.withTransaction { }` so the surrounding `execute` journal diff
  covers them; `purgeTask` gains `deleteNotesForTask`.

- [ ] **Step 1: Write the failing instrumented tests**

`RoomNoteCommandInstrumentedTest` (follow the existing Room repository
instrumented-test setup in `core/data/src/androidTest`): add/update/delete/
restore round trip, exactly-one-owner rejection, journal evidence — after
`AddNote`, the newest `backup_journal` row has `objectType = "NOTE"` and the
same transaction's generation; after `PermanentlyDeleteTask`, the task's
notes are gone and NOTE delete operations are journalled.

- [ ] **Step 2: Compile for RED**

Run: `./gradlew :core:data:compileDebugAndroidTestKotlin`
Expected: FAIL (missing DAO methods/arms).

- [ ] **Step 3: Implement DAO methods and Room handlers**

Mirror Task 1's handlers with Room reads (`currentWorkspace()` snapshot for
validation, `upsertNote(note.toEntity())` for writes). Wire notes into the
snapshot combine so `WorkspaceSnapshot.notes` is populated. Extend
`purgeTask`.

- [ ] **Step 4: Compile and run unit gate**

Run: `./gradlew :core:data:compileDebugAndroidTestKotlin :core:data:testDebugUnitTest`
Expected: PASS. Device execution happens at the Task 14 connected gate (or a
focused disposable run if the reviewer requests one).

- [ ] **Step 5: Commit**

```bash
git add -A core
git commit -m "feat: persist notes through room commands"
```

---

### Task 5: Activity history generation

**Files:**

- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt`
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/EntityMappers.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
  (activity DAO queries)
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryActivityGenerationTest.kt`
- Test:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomActivityGenerationInstrumentedTest.kt`

**Interfaces:**

- Consumes: existing command handlers; `ActivityEntryEntity` (unchanged
  schema — `kind` stays a TEXT column).
- Produces:

```kotlin
enum class ActivityKind {
    RECORD_CREATED,
    STATUS_CHANGED,
    COMPLETED,
    REOPENED,
    PROJECT_MOVED,
    MILESTONE_CHANGED,
    DEPENDENCY_ADDED,
    DEPENDENCY_REMOVED,
    BINNED,
    RESTORED,
    ATTACHMENT_ADDED,
    ATTACHMENT_REMOVED,
}

data class ActivityEntry(
    val id: String,
    val taskId: TaskId?,
    val projectId: ProjectId?,
    val kind: ActivityKind,
    val body: String,
    val createdAt: Instant,
)
```

(the unused `immutable` flag is deleted; mappers parse unknown stored kinds
into a skipped row rather than crashing). Emission map — inside the same
handler, same transaction, in both repositories, via a shared private helper
`recordActivity(taskId, projectId, kind, body, at)`:

| Command | Kind | Body example |
|---|---|---|
| `CreateTask` / `CreateProject` | `RECORD_CREATED` | `Created` |
| `ChangeTaskStatus` / `RestoreTaskStatus` | `STATUS_CHANGED` | `Backlog → Started` |
| `CompleteTask` | `COMPLETED` | `Completed` |
| `ReopenTask` | `REOPENED` | `Reopened` |
| `UpdateTask` with project change | `PROJECT_MOVED` | `Inbox → Client research` |
| `UpdateTask` with milestone change | `MILESTONE_CHANGED` | `Milestone: Public launch` |
| `SetTaskDependency` present/absent | `DEPENDENCY_ADDED` / `DEPENDENCY_REMOVED` | `Depends on: Finish launch proposal` |
| `DeleteTask` | `BINNED` | `Moved to Bin` |
| `RestoreTask` | `RESTORED` | `Restored from Bin` |
| `RegisterAttachment` / `DeleteAttachment` (Task 7) | `ATTACHMENT_ADDED` / `ATTACHMENT_REMOVED` | the sanitised display name |

Bounds: `MAX_ACTIVITY_ENTRIES_PER_OWNER = 500`,
`MAX_ACTIVITY_BODY_LENGTH = 500` (truncate, never reject). After insert,
prune oldest-first beyond 500 for that owner in the same transaction (the
journal diff records the deletions). Note commands and activity itself never
emit activity. `WorkspaceSnapshot.activityEntries` is populated by both
repositories.

- [ ] **Step 1: Write failing in-memory tests**

`InMemoryActivityGenerationTest`: completing a task appends exactly one
`COMPLETED` entry for that task; a status change records old → new names;
501 generated entries keep only the newest 500 with deterministic oldest
eviction; `AddNote` emits nothing; entries are immutable (no command can
touch them).

- [ ] **Step 2: Run for RED**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*InMemoryActivityGenerationTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement in both repositories**

Add the helper + call sites per the emission map; Room writes
`ActivityEntryEntity` via a new `upsertActivityEntry` DAO method and prunes
with `DELETE FROM activity_entries WHERE id IN (SELECT id ... ORDER BY
createdAtEpochMillis ASC LIMIT :excess)` scoped to the owner.

- [ ] **Step 4: Write the Room instrumented test and compile**

`RoomActivityGenerationInstrumentedTest`: the entity row and its
`ACTIVITY_ENTRY` journal row share the transaction generation; pruning
deletions are journalled. Run:
`./gradlew :core:data:testDebugUnitTest :core:data:compileDebugAndroidTestKotlin`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A core
git commit -m "feat: generate immutable activity history"
```

---

### Task 6: Search over notes and attachment names

**Files:**

- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/SearchExtensionTest.kt`

**Interfaces:**

- Consumes: `SearchNormalizer`, `SearchQuery`, `SearchResult`
  (`TaskResult`/`ProjectResult` — unchanged), snapshot `notes` and
  `attachments`.
- Produces: both `search` implementations extend the task text bundle with
  that task's note bodies and non-tombstoned attachment display names, and
  the project filter with that project's note bodies. Activity bodies are
  never searched. `MAX_SEARCH_RESULTS = 50` unchanged.

- [ ] **Step 1: Write failing tests**

`SearchExtensionTest` against `InMemoryVaultRepository`: a task note body
match returns the owning `TaskResult`; a project note match returns the
owning `ProjectResult`; an attachment display-name match returns the owning
task; a tombstoned attachment's name does not match; an activity body string
present nowhere else returns nothing; diacritic normalisation applies to
note text.

- [ ] **Step 2: Run for RED**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*SearchExtensionTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement in both repositories**

In each `search`, before the task loop build
`val notesByTask = snapshot.notes.filter { it.taskId != null }.groupBy { it.taskId }`,
`val notesByProject = snapshot.notes.filter { it.projectId != null }.groupBy { it.projectId }`,
`val attachmentNamesByTask = snapshot.attachments.filter { it.deletedAt == null }.groupBy { it.taskId }`,
and append `notesByTask[task.id].orEmpty().joinToString(" ") { it.body }` and
the joined attachment names to the task `listOfNotNull(...)` bundle, and the
project note bodies to the project match string.

- [ ] **Step 4: Run for GREEN**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*SearchExtensionTest*"`
then the module suite. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A core
git commit -m "feat: search note bodies and attachment names"
```

---

### Task 7: Attachment metadata commands

**Files:**

- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryAttachmentCommandTest.kt`

**Interfaces:**

- Consumes: finalised `Attachment`, `ActivityKind.ATTACHMENT_ADDED/REMOVED`.
- Produces:

```kotlin
    data class RegisterAttachment(val attachment: Attachment) : DomainCommand

    data class DeleteAttachment(
        val attachmentId: AttachmentId,
        val deletedAt: Instant = Instant.now(),
    ) : DomainCommand

    data class RestoreAttachment(val attachment: Attachment) : DomainCommand
```

New `RejectionReason` entries: `EMPTY_ATTACHMENT_NAME`,
`ATTACHMENT_NAME_TOO_LONG`, `ATTACHMENT_LIMIT_REACHED`,
`INVALID_ATTACHMENT_METADATA`. Bounds constants:
`MAX_ATTACHMENT_NAME_LENGTH = 255`, `MAX_ATTACHMENT_MIME_LENGTH = 255`,
`MAX_ATTACHMENTS_PER_TASK = 100`, `MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024`,
`MAX_ATTACHMENT_CHUNK_COUNT = 25`. Semantics: `RegisterAttachment` validates
owner task exists and is not in the Bin, name non-blank/bounded after
sanitisation, MIME bounded, `byteCount in 1..MAX_ATTACHMENT_BYTES`,
`chunkCount in 1..25` consistent with
`ceil(byteCount / 4 MiB)`, `contentHash` 64 lowercase hex, cap 100 active
attachments per task; emits `ATTACHMENT_ADDED` activity; no Undo (bytes are
already uploaded; the inverse of registration is `DeleteAttachment`, offered
in UI, not as snackbar Undo). `DeleteAttachment` sets `deletedAt`
(tombstone; row retained), emits `ATTACHMENT_REMOVED`, Undo =
`RestoreAttachment(prior)` which clears `deletedAt` and re-emits nothing.
Bin leaves attachments untouched; `purgeTask` already cascades the rows and
the journal diff records their deletion.

- [ ] **Step 1: Write failing in-memory tests**

Register happy path + snapshot visibility; each validation rejection (owner
missing, blank name, over-cap, hash malformed, chunk-count mismatch);
delete tombstones with exact `RestoreAttachment` undo and restore round
trip; `ATTACHMENT_ADDED`/`ATTACHMENT_REMOVED` activity entries appear;
tombstoned attachments stay in the snapshot (UI renders the state).

- [ ] **Step 2: Run for RED**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*InMemoryAttachmentCommandTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement in both repositories**

Follow the note-handler shape; Room upserts `attachment.toEntity()` and
wires `snapshot.attachments` into the combine.

- [ ] **Step 4: Run for GREEN, then unit sweep**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A core
git commit -m "feat: add attachment metadata commands"
```

---

### Task 8: AttachmentBlobStore contract and Drive implementation

**Files:**

- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/AttachmentBlobContracts.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/AttachmentBlobSetManifestCodec.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/CreateOnlyDriveAttachmentBlobStore.kt`
- Create: `scripts/generate-stage4-attachment-v1-fixtures.mjs`
- Create:
  `core/data/src/test/resources/backup-format/attachment-v1/blob-set-manifest.json`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/AttachmentBlobSetManifestCodecTest.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/CreateOnlyDriveAttachmentBlobStoreTest.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/AttachmentGoldenTest.kt`

**Interfaces:**

- Consumes: `CreateOnlyDriveTransport` (unchanged),
  `AuthenticatedCloudObjectCodec`, `CloudObjectFamily.ATTACHMENT_CHUNK` and
  `MANIFEST`, `CloudBounds.MAX_ATTACHMENT_CHUNK_PLAINTEXT_BYTES/MAX_ATTACHMENT_CHUNKS`,
  `CloudLineageId`, `ProviderObjectId`, `Sha256Digest`, `BlobSetId`,
  `RemoteBackupFailureCategory`.
- Produces (core:domain):

```kotlin
data class AttachmentChunkRef(
    val index: Int,
    val providerObjectId: ProviderObjectId,
    val ciphertextSha256: Sha256Digest,
    val plaintextByteCount: Int,
)

data class AttachmentBlobSetManifest(
    val blobSetId: BlobSetId,
    val contentSha256: Sha256Digest,
    val totalByteCount: Long,
    val chunks: List<AttachmentChunkRef>,
)

sealed interface AttachmentObjectResult {
    data object Created : AttachmentObjectResult
    data object AlreadyExists : AttachmentObjectResult
    data object Ambiguous : AttachmentObjectResult
    data class Failed(val reason: RemoteBackupFailureCategory) : AttachmentObjectResult
}

sealed interface AttachmentReadResult {
    data class Found(val bytes: ByteArray) : AttachmentReadResult
    data object Missing : AttachmentReadResult
    data class Failed(val reason: RemoteBackupFailureCategory) : AttachmentReadResult
}

sealed interface AttachmentManifestLookup {
    data class Found(val providerObjectId: ProviderObjectId) : AttachmentManifestLookup
    data object Missing : AttachmentManifestLookup
    data object Ambiguous : AttachmentManifestLookup
    data class Failed(val reason: RemoteBackupFailureCategory) : AttachmentManifestLookup
}

data class AttachmentListedObject(
    val providerObjectId: ProviderObjectId,
    val role: String?,
    val blobSetId: String?,
    val createdAtEpochMillis: Long?,
)

interface AttachmentBlobStore {
    suspend fun generateObjectIds(count: Int): List<ProviderObjectId>
    suspend fun createChunk(
        providerObjectId: ProviderObjectId,
        blobSetId: BlobSetId,
        chunkIndex: Int,
        chunkCount: Int,
        frameBytes: ByteArray,
    ): AttachmentObjectResult
    suspend fun readObject(providerObjectId: ProviderObjectId, maximumBytes: Long): AttachmentReadResult
    suspend fun createManifest(
        providerObjectId: ProviderObjectId,
        blobSetId: BlobSetId,
        frameBytes: ByteArray,
    ): AttachmentObjectResult
    suspend fun findManifest(blobSetId: BlobSetId): AttachmentManifestLookup
    suspend fun listNamespace(pageToken: String?): Pair<List<AttachmentListedObject>, String?>
    suspend fun delete(providerObjectId: ProviderObjectId): Boolean
}
```

(core:data) `AttachmentBlobSetManifestCodec` with
`encode(manifest, lineageId, contentKey): ByteArray` /
`decode(bytes, lineageId, blobSetId, contentKey): AttachmentBlobSetManifest`
— AEAD identity `family = MANIFEST, vaultId = lineageId.value,
objectId = "attachment-manifest:${blobSetId.value}"`, canonical
kotlinx.serialization JSON payload, strict bounds (≤25 chunks, indexes
exactly `0 until chunkCount`, positive plaintext counts summing to
`totalByteCount`, ≤100 MiB). Chunk frames use
`family = ATTACHMENT_CHUNK, vaultId = lineageId.value,
objectId = blobSetId.value, chunkIndex/chunkCount` — the codec-enforced
tuple. `CreateOnlyDriveAttachmentBlobStore(transport, lineageId)` tags Drive
`appProperties` with `format=v1`, `role=attachment-chunk|attachment-manifest`,
`lineageId`, `blobSetId`, `chunkIndex` (chunks only); constant names
`"attachment-chunk"` / `"attachment-manifest"`; per-object ceilings
`HEADER_AND_PREFIX_BYTES + MAX_ATTACHMENT_CHUNK_CIPHERTEXT_BYTES_V1` and the
manifest family ceiling; `findManifest` lists by
`appProperties has { key='blobSetId' and value='...' } and
{ key='role' and value='attachment-manifest' }` and returns `Ambiguous` for
more than one match (fail closed).

- [ ] **Step 1: Write failing codec and store tests**

Codec: round trip; wrong lineage/blob-set identity fails AEAD; chunk-count
and index-bound rejections; oversize rejection. Store (fake
`CreateOnlyDriveTransport` in the test file, following
`CreateOnlyDriveObjectStoreTest` style): create-if-absent occupied and
ambiguous mapping, ceiling enforcement before any transport call, manifest
lookup single/none/duplicate, list pagination with foreign objects passed
through untouched.

- [ ] **Step 2: Run for RED**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*AttachmentBlob*" --tests "*AttachmentGolden*"`
Expected: FAIL.

- [ ] **Step 3: Implement codec and store**

- [ ] **Step 4: Generate the independent manifest fixture**

`generate-stage4-attachment-v1-fixtures.mjs` re-implements the canonical
manifest JSON + frame bytes with Node `crypto`; `AttachmentGoldenTest`
asserts byte-identical encode and digest. Run:
`node scripts/generate-stage4-attachment-v1-fixtures.mjs` then
`./gradlew :core:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A core scripts
git commit -m "feat: add create-only attachment blob store"
```

---

### Task 9: Intake coordinator with hostile-input matrix

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/AttachmentBlobCoordinator.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
  (`attachment_transfer` DAO)
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/AttachmentIntakeTest.kt`

**Interfaces:**

- Consumes: `AttachmentBlobStore`, `AttachmentBlobSetManifestCodec`,
  `AuthenticatedCloudObjectCodec`, `VaultRepository.execute`
  (`RegisterAttachment`), `AttachmentTransferEntity`, ownership tip
  authentication.
- Produces:

```kotlin
interface AttachmentSource {
    val declaredByteCount: Long
    fun open(): InputStream
}

sealed interface AttachmentIntakeResult {
    data class Registered(val attachmentId: AttachmentId) : AttachmentIntakeResult
    data object SourceUnavailable : AttachmentIntakeResult
    data object TooLarge : AttachmentIntakeResult
    data object OwnershipUnavailable : AttachmentIntakeResult
    data class Failed(val reason: RemoteBackupFailureCategory) : AttachmentIntakeResult
}

class AttachmentBlobCoordinator(
    private val repository: VaultRepository,
    private val transferDao: AttachmentTransferDao,
    private val codec: AuthenticatedCloudObjectCodec,
    private val manifestCodec: AttachmentBlobSetManifestCodec,
    private val lineageId: CloudLineageId,
    private val contentKey: () -> VaultKey,
    private val holdsOwnership: suspend () -> Boolean,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun intake(
        store: AttachmentBlobStore,
        taskId: TaskId,
        displayName: String,
        mimeType: String,
        source: AttachmentSource,
    ): AttachmentIntakeResult

    suspend fun resume(store: AttachmentBlobStore): Int   // resumed sessions
    suspend fun expireStaleSessions(store: AttachmentBlobStore): Int
}

fun sanitizeAttachmentDisplayName(raw: String): String
```

Durable phases in `attachment_transfer.phase`: `PLANNED` (IDs generated and
persisted with the chunk provider IDs in `chunkStateEncoded`, before any
network mutation) → `UPLOADING` → `CHUNKS_VERIFIED` → `MANIFEST_CREATED` →
`REGISTERED`; any failure keeps the row for `resume`, and rows older than 24
hours are expired via bounded exact-ID deletes of that session's own objects
only. Streaming rules: refuse when `declaredByteCount > 100 MiB`; read
through `DigestInputStream` computing SHA-256; buffer exactly one 4 MiB
plaintext chunk and one ciphertext frame; a stream that ends early or runs
past its declared length fails the session (`TooLarge` past the cap, else
`Failed(CORRUPT_OR_INCOMPATIBLE)`); every uploaded chunk is verified by
`readObject` + AEAD decode + ciphertext-digest comparison before
`CHUNKS_VERIFIED`; the manifest is created last; `RegisterAttachment`
executes only after manifest readback; `holdsOwnership()` is checked before
the first create and again before the manifest create — losing ownership
mid-session abandons it. `sanitizeAttachmentDisplayName` strips ISO control
characters, `/`, `\\`, and leading dots, collapses whitespace, and truncates
to 255; an empty result becomes `"attachment"`.

- [ ] **Step 1: Write the failing intake matrix**

`AttachmentIntakeTest` with in-memory fakes for store and DAO:

- happy path: 9 MiB source → 3 chunks + manifest, all verified, metadata
  registered with correct hash/byteCount/chunkCount;
- lying declared size (larger stream) fails with no metadata and a
  resumable-then-expirable session;
- 100 MiB + 1 refused before any store call;
- readback mismatch on chunk 2 fails closed, no manifest, no register;
- process-death simulation: rerun `resume` from a persisted `UPLOADING` row
  completes without duplicating chunk creates (occupied slots adopted by
  digest match);
- ownership lost before manifest → `OwnershipUnavailable`, session
  abandoned;
- `expireStaleSessions` deletes only that session's exact provider IDs and
  never touches foreign objects;
- traversal name `"../../etc/passwd"` sanitises to `"etc_passwd"`-style
  safe output (assert exact `sanitizeAttachmentDisplayName` results).

- [ ] **Step 2: Run for RED**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*AttachmentIntakeTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement the coordinator**

- [ ] **Step 4: Run for GREEN plus module sweep**

Run: `./gradlew :core:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A core
git commit -m "feat: add bounded attachment intake coordinator"
```

---

### Task 10: Open, share, and the encrypted LRU cache

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/AttachmentCacheStore.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/AttachmentOpenCoordinator.kt`
- Modify: `app/src/main/res/xml/file_paths.xml` (or the existing FileProvider
  paths resource — locate by the `FileProvider` manifest entry)
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/AttachmentCacheStoreTest.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/AttachmentOpenCoordinatorTest.kt`

**Interfaces:**

- Consumes: `AttachmentBlobStore`, `AttachmentBlobSetManifestCodec`,
  `AuthenticatedCloudObjectCodec`.
- Produces:

```kotlin
class AttachmentCacheStore(
    private val cacheRoot: File,          // <cacheDir>/attachments/v1
    private val availableBytes: () -> Long,
) {
    fun read(blobSetId: BlobSetId, chunkIndex: Int): ByteArray?   // verified frame bytes
    fun write(blobSetId: BlobSetId, chunkIndex: Int, frameBytes: ByteArray)
    fun evict(blobSetId: BlobSetId)
    fun sweep()                            // enforce ceiling, oldest-access first
    fun usageBytes(): Long
}

sealed interface AttachmentOpenResult {
    data class Opened(val byteCount: Long) : AttachmentOpenResult
    data object Unavailable : AttachmentOpenResult
    data class Failed(val reason: RemoteBackupFailureCategory) : AttachmentOpenResult
}

class AttachmentOpenCoordinator(
    private val cache: AttachmentCacheStore,
    private val manifestCodec: AttachmentBlobSetManifestCodec,
    private val codec: AuthenticatedCloudObjectCodec,
    private val lineageId: CloudLineageId,
    private val contentKey: () -> VaultKey,
) {
    suspend fun open(
        store: AttachmentBlobStore,
        attachment: Attachment,
        destination: OutputStream,
    ): AttachmentOpenResult
}
```

Cache ceiling: `min(128L * 1024 * 1024, availableBytes() / 20)`; stored
files are the verified ciphertext frames (encrypted at rest by their own
AEAD); `sweep()` deletes least-recently-accessed files until under the
ceiling and is also the startup cleanup hook. `open` resolves the manifest
(cache-first via a durable local `manifestProviderFileId` when present,
otherwise `findManifest`), then per chunk: cache hit → AEAD decode; miss →
`readObject`, verify against the manifest's `ciphertextSha256`, cache, AEAD
decode; plaintext streams to `destination` one chunk at a time and each
chunk's plaintext buffer is cleared in `finally`; total plaintext must equal
`attachment.byteCount` and its digest must equal `contentHash` or the
result is `Failed(CORRUPT_OR_INCOMPATIBLE)`. A missing manifest or chunk
(including `blobSetId == null`) is `Unavailable` — never invented bytes.
The share path (wired in Task 13) writes through `open` into a file under
the FileProvider share directory `share/attachments/`, granted only to the
chosen target, deleted on completion, and swept at startup.

- [ ] **Step 1: Write failing cache and open tests**

Cache: write/read round trip, LRU eviction order under a small injected
ceiling, `sweep` idempotence, `usageBytes`. Open: happy path digest match;
chunk digest mismatch fails closed and evicts the bad chunk; missing
manifest → `Unavailable`; total-length mismatch → failed; cache hit avoids
store reads (count store calls in the fake).

- [ ] **Step 2: Run for RED**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*AttachmentCache*" --tests "*AttachmentOpen*"`
Expected: FAIL.

- [ ] **Step 3: Implement, then GREEN**

Run the same filter, then `./gradlew :core:data:testDebugUnitTest`.
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A core app
git commit -m "feat: add attachment open path and encrypted cache"
```

---

### Task 11: Delete, garbage collection, destructive and terminal deletion

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/AttachmentGarbageCollector.kt`
- Modify:
  `app/src/main/kotlin/app/opentasks/backup/DefaultRemoteBackupLifecycleCoordinator.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/AttachmentGarbageCollectorTest.kt`
- Test: extend
  `app/src/test/kotlin/app/opentasks/backup/` lifecycle coordinator tests
  (same directory as the existing Task 12 suites)

**Interfaces:**

- Consumes: `AttachmentBlobStore`, `OwnershipChainStore` tip
  re-authentication (the `DefaultNamespaceSafeRemoteCleanup.holdsExpectedTip`
  pattern), snapshot attachments, remote state
  (`lastVerifiedGeneration`, `previousPublicationGeneration`), the journal
  generation of an attachment tombstone (new `BackupJournalDao` query
  `latestGenerationFor(objectType: String, objectId: String): Long?`).
- Produces:

```kotlin
data class AttachmentGcResult(
    val deletedObjects: Int,
    val stoppedForOwnershipChange: Boolean,
    val blockers: Int,
)

class AttachmentGarbageCollector(
    private val chainStore: OwnershipChainStore,
    private val rootClaimProviderId: ProviderObjectId,
    private val contentKey: () -> VaultKey,
    private val minimumRetention: Duration = Duration.ofDays(30),
    private val maximumDeletesPerBatch: Int = 32,
) {
    suspend fun runBatch(
        store: AttachmentBlobStore,
        candidates: List<GcCandidate>,
        now: Instant,
    ): AttachmentGcResult
}

data class GcCandidate(
    val blobSetId: BlobSetId,
    val deletedAt: Instant,
    val tombstoneGeneration: Long,
    val coveredByCurrentBase: Boolean,
    val coveredByPreviousBase: Boolean,
    val activelyReferenced: Boolean,
)
```

A blob set is collectable only when: `!activelyReferenced`,
`now - deletedAt >= 30 days`, and both `coveredByCurrentBase` and
`coveredByPreviousBase` are true (every retained recoverable base already
contains the tombstone — the concrete encoding of "verified backup + no
retained recovery inventory reference"). The batch re-authenticates the
ownership tip before every delete batch; unknown, foreign-lineage, or
role-less listed objects are blockers, never deleted; the 32-delete budget
is shared across the pass; chunks delete before the manifest so an
interrupted pass never yields a manifest-less orphan set that looks
published. The caller (app runtime) builds `GcCandidate`s from Room + remote
state. **Delete cloud attachment content** joins
`DefaultRemoteBackupLifecycleCoordinator` under the existing
`publicationGate`: passphrase verification first (reuse the
`deleteHistory` verification path), then tip authentication, then bounded
enumerate-and-delete of the entire attachment namespace (chunks before
manifests, ≤32 per invocation with durable progress in
`attachment_transfer`-style rows or the existing operation table pattern),
leaving structured metadata and backup history untouched. Terminal lineage
deletion extends its role exhaustion to the two attachment roles before
claims, inside the same budget/cursor machinery, with the terminal tombstone
still retained last.

- [ ] **Step 1: Write failing GC tests**

Eligibility truth table (each precondition individually blocking); ownership
change stops the batch with nothing deleted; foreign object counted as
blocker; budget exhaustion resumes next pass; chunk-before-manifest order;
the destructive action deletes everything attachment-scoped and nothing
else; terminal deletion covers attachment roles before claims.

- [ ] **Step 2: Run for RED**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*AttachmentGarbageCollector*" :app:testDebugUnitTest --tests "*Lifecycle*"`
Expected: FAIL.

- [ ] **Step 3: Implement collector and lifecycle extensions, then GREEN**

Run the same filters, then module sweeps. Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A core app
git commit -m "feat: add attachment gc and destructive deletion"
```

---

### Task 12: Runtime wiring, recovery, disconnect, and end-to-end proof

**Files:**

- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`
- Modify: `app/src/main/kotlin/app/opentasks/backup/RemoteBackupRuntime.kt`
  (attachment work joins the runtime: GC pass after successful publication,
  session expiry on start)
- Test: extend the Stage 3 deterministic production-protocol end-to-end test
  in `core/data/src/androidTest` (locate the Task 14 E2E suite) with the
  attachment lifecycle scenario
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/AttachmentOwnershipBoundaryTest.kt`

**Interfaces:**

- Consumes: everything from Tasks 8–11, `DefaultRemoteBackupRunner`,
  recovery import (Task 3 families make recovered vaults carry
  notes/attachments automatically).
- Produces: app wiring — the attachment store, coordinators, cache, and
  collector are created per open active vault slot next to the existing
  `publicationGate` collaborators in `AppModule`, and torn down with the
  slot. Behavioural requirements proven by tests:
  - a `DORMANT`/`OWNERSHIP_LOST`/`TERMINATED` lineage never performs any
    attachment store call (mirror the runner's lifecycle guard);
  - after recovery on a second installation, `open` succeeds from manifest
    discovery alone (no local provider IDs), and a stale first installation
    refuses intake/GC after tip change;
  - divergent-work preservation under a separate lineage keeps attachment
    metadata with `blobSetId` intact but its opens report `Unavailable`
    against the new lineage namespace (foreign lineage tag), and copies
    nothing;
  - disconnect keeps metadata and cache-evicts bytes on demand only.

- [ ] **Step 1: Write the failing ownership-boundary unit tests**

`AttachmentOwnershipBoundaryTest`: fake store records every call; a stopped
or non-ACTIVE runtime performs zero attachment calls; tip mismatch refuses
intake and GC.

- [ ] **Step 2: Extend the deterministic E2E for RED (compile)**

Add the scenario: intake 9 MiB on device A → publish backup → recover on
device B → open and verify bytes on B → A's further intake/GC refused →
delete attachment on B → advance both retained bases past the tombstone →
GC collects → terminal deletion leaves only the tombstone. Run:
`./gradlew :core:data:compileDebugAndroidTestKotlin`
Expected: FAIL until wiring exists.

- [ ] **Step 3: Implement wiring, then compile and unit GREEN**

Run: `./gradlew :core:data:compileDebugAndroidTestKotlin :core:data:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A core app
git commit -m "feat: wire attachment runtime and recovery boundaries"
```

---

### Task 13: Product surfaces

**Files:**

- Create:
  `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/Timeline.kt`
- Create:
  `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TaskAttachmentsSection.kt`
- Create:
  `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/NotesActivitySection.kt`
- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
- Modify: `feature/tasks/src/main/res/values/strings.xml`
- Modify:
  `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`
- Create: `feature/projects/src/main/res/values/strings.xml`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/BackupRecoveryScreen.kt`
- Modify: `feature/more/src/main/res/values/strings.xml`
- Create: `app/src/main/kotlin/app/opentasks/AttachmentIntakeViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test:
  `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/NotesActivitySectionInstrumentedTest.kt`
- Test:
  `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskAttachmentsSectionInstrumentedTest.kt`
- Test:
  `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/ProjectNotesInstrumentedTest.kt`
- Test: extend
  `feature/more/src/androidTest/.../BackupRecoveryScreenInstrumentedTest.kt`

**Interfaces:**

- Consumes: `Note`, `ActivityEntry`, `Attachment`, `SectionHeader`,
  `OpenTasksTheme`, `OpenTasksFixtures`, the `execute`/snackbar-undo
  pipeline, `PickVisualMedia`/`OpenDocument` contracts,
  `AttachmentBlobCoordinator`, `AttachmentOpenCoordinator`,
  `AttachmentCacheStore.usageBytes`.
- Produces (all stateless, UK English copy via `stringResource`):

```kotlin
// core:designsystem — model-free timeline primitives
data class TimelineItem(
    val key: String,
    val timestampLabel: String,
    val body: String,
    val editable: Boolean,
    val iconKind: TimelineIconKind,
)
enum class TimelineIconKind { NOTE, EVENT }

@Composable
fun TimelineList(
    items: List<TimelineItem>,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
)

// feature:tasks
enum class AttachmentRowState { REMOTE, DOWNLOADING, UNAVAILABLE, TOMBSTONED, FAILED }

@Composable
fun TaskAttachmentsSection(
    attachments: List<Attachment>,
    states: Map<AttachmentId, AttachmentRowState>,
    onAddFromPhotos: () -> Unit,
    onAddFromFiles: () -> Unit,
    onOpen: (AttachmentId) -> Unit,
    onShare: (AttachmentId) -> Unit,
    onDelete: (AttachmentId) -> Unit,
    onRetry: (AttachmentId) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
fun NotesActivitySection(
    notes: List<Note>,
    activity: List<ActivityEntry>,
    onAddNote: (String) -> Unit,
    onUpdateNote: (NoteId, String) -> Unit,
    onDeleteNote: (NoteId) -> Unit,
    modifier: Modifier = Modifier,
)
```

Placement: `TaskAttachmentsSection` after the "Context" section;
`NotesActivitySection` after "Checklist" (read-mostly timeline, newest
first, notes editable inline, events read-only with icon + text state — no
colour-only cues). `TasksScreen`/`TaskDetailPane` gain the corresponding
parameters with safe defaults; `ProjectWorkbench` gains
`notes`/`activity`/note callbacks and renders the same
`NotesActivitySection` mapped through the shared `TimelineList`.
`BackupRecoveryScreen` gains a fourth heading block **Cloud attachments**:
connection state line, temporary-cache usage line
(`Formatter.formatShortFileSize` equivalent already used by the package
card), and a `BackupAction(R.string.attachments_delete_content,
"attachments-delete-content", onDeleteAttachmentContent)` guarded by the
existing passphrase sheet. `AttachmentIntakeViewModel` (app) owns the
picker launchers' results: `PickVisualMedia` and `OpenDocument("*/*")`
launch from the section callbacks, resolve
name/MIME/size via `ContentResolver` (untrusted; sanitised in the
coordinator), take only the transient read grant, and drive
intake/open/share with per-attachment `AttachmentRowState` exposed as
`StateFlow<Map<AttachmentId, AttachmentRowState>>`. When no active remote
backup configuration exists, the add-attachment callbacks do not open a
picker: the section shows the spec's setup-required explanation and a
single action that navigates to the existing Backup & recovery screen —
attachments never fork a second setup flow. Note commands dispatch
through `viewModel.execute(...)` for the standard snackbar Undo. New string
resources use the established naming (`task_notes_heading`,
`task_attachments_heading`, `attachment_state_unavailable`,
`attachments_cache_usage`, `attachments_delete_content`, …). Attachment
failure states never disable any editor field; 48 dp targets; TalkBack
content descriptions on every row state.

- [ ] **Step 1: Write failing feature UI tests**

Follow the `TaskEditorInstrumentedTest` pattern (fixtures, `AtomicReference`
capture, `mainClock`): timeline interleaves notes and events newest-first;
adding a note dispatches `onAddNote` with trimmed text; attachment row
renders each `AttachmentRowState` as text (assert on state strings, not
colour); tombstoned row offers no Open; project notes render and dispatch;
More shows the cloud attachments heading, cache usage, and delete action.

- [ ] **Step 2: Compile for RED**

Run: `./gradlew :feature:tasks:compileDebugAndroidTestKotlin :feature:projects:compileDebugAndroidTestKotlin :feature:more:compileDebugAndroidTestKotlin`
Expected: FAIL.

- [ ] **Step 3: Implement designsystem, feature sections, and app wiring**

- [ ] **Step 4: Compile tests, run the repository gate**

Run: `./gradlew :feature:tasks:compileDebugAndroidTestKotlin :feature:projects:compileDebugAndroidTestKotlin :feature:more:compileDebugAndroidTestKotlin`
then `./gradlew testDebugUnitTest lintDebug :app:assembleDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A core feature app
git commit -m "feat: add notes, activity, and attachment surfaces"
```

---

### Task 14: Qualification and exit gates

**Files:**

- Modify: `app/src/debug/kotlin/app/opentasks/backup/drive/` (extend the
  qualification harness with attachment properties)
- Modify: `docs/architecture.md`, `docs/threat-model.md`, `PRODUCT.md`,
  `DESIGN.md`, `CLAUDE.md`, `HANDOFF.md`
- Create: `docs/qualification/stage4-notes-activity-attachments-search.md`

**Steps:**

- [ ] **Step 1: Extend and run the credentialed live gate**

Add attachment properties to the debug-only qualification path (same
source-set gating, same `driveQualification=run` argument): pre-generated
exact-ID chunk create + occupied rejection, chunk readback byte-identity,
manifest create/readback/single-lookup, and exact-ID cleanup of every
qualification object. Run it once on the sole audited disposable API 37
target with the signed-in account, exactly as the Stage 3 procedure records
(serials exported, `adb devices` audited, disposable shut down after, final
ADB/qemu audit empty). A failure stops Stage 4 here.

- [ ] **Step 2: Run the full connected gate on the sole disposable**

```bash
./gradlew :app:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest :feature:more:connectedDebugAndroidTest
```

Expected: PASS including the v7→v8 migration test, Room note/activity
parity, the extended E2E, and all new feature UI suites. Zero unexpected
skips.

- [ ] **Step 3: Repository, release, schema, and hygiene gates**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --rerun-tasks
./gradlew :app:assembleRelease --rerun-tasks
scripts/check-schema-drift.sh
node scripts/generate-stage2-backup-v1-fixtures.mjs && git diff --exit-code core/data/src/test/resources
node scripts/generate-stage4-attachment-v1-fixtures.mjs && git diff --exit-code core/data/src/test/resources
git diff --check
```

Expected: all pass; release inspection shows only `drive.appdata`, no debug
activity, no attachment-name or Drive-ID logging (grep the app/core source
for `Log\.` additions — there must be none carrying private fields).

- [ ] **Step 4: Update the contract documents**

- `docs/architecture.md`: attachment coordinator/store implemented; notes,
  activity, search sections; schema-freeze statement.
- `docs/threat-model.md`: T12 attachment transport implemented; T17–T19
  move from "not operational" to their implemented controls; assets table
  rows for note text, activity, attachment session state, cache.
- `PRODUCT.md` delivery boundary; `DESIGN.md` timeline/attachment-row
  conventions; `CLAUDE.md` Stage 4 rules (attachment namespace constraints,
  new bounds); `HANDOFF.md` Stage 4 closure checkpoint.
- Write `docs/qualification/stage4-notes-activity-attachments-search.md`
  recording every gate result with exact counts, no private identifiers.

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "docs: verify stage 4 notes activity attachments search"
```

---

## Spec coverage map

| Spec section | Tasks |
|---|---|
| Note record/commands/bounds | 1, 4 |
| Room v8, migration, saved_views dormant, schema freeze | 2, 14 |
| Backup payloads + fixtures + recovery import | 3 |
| Activity kinds, atomicity, pruning, merged timeline | 5, 13 |
| Search extension | 6 |
| Attachment metadata, Bin/purge, delete/Undo | 7 |
| Blob-set objects, ceilings, namespace | 8 |
| Intake, provisional sessions, hostile matrix | 9 |
| Open/share, LRU cache, FileProvider cleanup | 10 |
| GC preconditions, destructive action, terminal deletion | 11 |
| Ownership gating, recovery, disconnect, separate lineage | 12 |
| Product surfaces, accessibility, UK copy | 13 |
| Credentialed gate, connected/release/privacy gates, docs | 14 |
