# Stage 5 Remaining Platform Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the two Stage 4 carry-forwards (retired blob-set GC, silent
intake auto-resume) and deliver encrypted `.otvault` export/import, warned
CSV export, the Today widget, app lock and title privacy with unified Quick
Add, keyboard/mouse/accessible input, and one-way calendar insertion on the
final local schema.

**Architecture:** Room stays the sole live structured-data authority; the
one schema bump is v9's `retired_blob_sets` table, which also becomes a new
backup record family so GC authority survives recovery. `.otvault` reuses
the frozen Stage 1 frame families and the existing recovery-envelope
machinery: the outer header wraps the vault content key under an export
passphrase, and every frame is authenticated at archive-scoped identities.
The widget, app lock, input, and calendar features are additive product
surfaces with no new transport, no provider changes, and no merge path.

**Tech Stack:** Kotlin 2.3.21, AGP 9 built-in Kotlin, Java 17 on JDK 21,
Room 2.8.4, SQLCipher 4.15.0, Tink 1.23.0, BouncyCastle Argon2id,
WorkManager 2.11.2, Storage Access Framework
(`CreateDocument`/`OpenDocument`), platform
`android.hardware.biometrics.BiometricPrompt` (minSdk 36 — no
androidx.biometric), `androidx.glance:glance-appwidget` (the stage's only
new catalogue entry), Node.js `crypto` fixtures, JUnit 4, Compose UI test
v2.

## Global Constraints

- Authority spec:
  `docs/superpowers/specs/2026-08-03-stage-5-platform-features-design.md`.
- Work directly on `main`; no branch, worktree, or pull request.
- The user-owned untracked `artifacts/` and `.kotlin/` stay untouched, as
  does the uncommitted historical Stage 3 plan amendment.
- Never start, install to, instrument, or mutate the protected
  `Pixel_10_Pro_Fold` AVD. Connected suites run only on a sole disposable
  ADB target started with `-read-only -no-snapshot-load -no-snapshot-save`.
- Bounds verbatim from the spec: retired-set GC eligibility 30 days with
  current/previous-generation base coverage and the shared 32-delete
  budget; intake sessions expire after 24 h (unchanged); attachment bounds
  unchanged (100 MiB, 4 MiB chunks, 25 chunks, 100 per task); cache bound
  `min(128 MiB, 5% available storage)` (`AttachmentCacheStore.MAX_CACHE_BYTES`
  with the `availableBytes() / 20` term); `.otvault` KDF is Argon2id
  64 MiB / 3 iterations / parallelism 1 / 16-byte salt; `.otvault` has no
  aggregate cap and streams; CSV is export-only with exactly four tables.
- Frozen things that must not change: Stage 3 ownership/publication codecs
  and roles, `RemoteObjectRoleV1`, the Stage 4 attachment manifest codec,
  `drive.appdata`-only scope, create-only immutable objects, no
  update/PATCH/ETag/revision concepts. Unknown or ambiguous remote objects
  fail closed.
- Every write is a `DomainCommand` through `VaultRepository.execute`;
  records and ordered backup-journal entries commit in one transaction;
  Undo is repository-produced; `InMemoryVaultRepository` stays
  behaviourally in sync with `RoomVaultRepository`.
- Room v9 requires exported `core/data/schemas/.../9.json` and a
  non-destructive `MIGRATION_8_9` with a preservation fixture test.
- Logs and telemetry never contain task text, note text, attachment
  display names, account details, Drive IDs, or encryption metadata.
- Passphrases stay `CharArray`; derived key arrays are zeroed in `finally`.
- Feature composables stay stateless, no Hilt in feature/core modules, new
  UI copy in `res/values/strings.xml` in UK English, OKLCH-only colours,
  4 dp spacing scale, Material typography roles.
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
- No task before Task 13 runs a device suite. Instrumented tests written in
  Tasks 1, 2, 7, 9, 10, 11 are compile-verified with
  `:<module>:compileDebugAndroidTestKotlin` and execute at the Task 13
  connected gate.
- `BackupRecordFamily` gains `RETIRED_BLOB_SET` immediately after `NOTE`,
  keeping `TOMBSTONE` last. `entries`/`ordinal` are used only for in-memory
  canonical sorting (`BackupPayloadCodec.kt:265,289`,
  `PortableBackupCodec.kt:628,797`), never persisted; newly produced
  payload bytes change canonically, so Stage 2 fixtures regenerate in
  Task 2. The reviewer must re-verify no ordinal is persisted.
- The `.otvault` export passphrase becomes the imported vault's recovery
  passphrase (the header envelope IS a recovery envelope). UI copy must say
  so; this is deliberate reuse, not an accident.

---

### Task 1: Retired blob-set record, Room v9, and purge-path writes

**Files:**

- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt`
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/Entities.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Create: `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/9.json`
  (KSP-exported, not hand-written)
- Modify:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/VaultDatabaseMigrationInstrumentedTest.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/RetiredBlobSetTest.kt`

**Interfaces:**

- Consumes: `BlobSetId`, `Revision`, `Attachment.blobSetId`,
  `VaultDatabase.purgeTask(taskId, tombstone, revisionWallMillis,
  revisionDeviceId)` (`VaultDatabase.kt:540`),
  `DomainCommand.PermanentlyDeleteTask` (`VaultRepository.kt:248`), the
  existing trash-purge path (`purgeExpiredTrash`).
- Produces:

```kotlin
// Records.kt
data class RetiredBlobSet(
    val blobSetId: BlobSetId,
    val chunkCount: Int,
    val retiredAt: Instant,
    val revision: Revision,
)

// Snapshots.kt — WorkspaceSnapshot gains
val retiredBlobSets: List<RetiredBlobSet> = emptyList(),

// Entities.kt
@Entity(tableName = "retired_blob_sets", primaryKeys = ["blobSetId"])
data class RetiredBlobSetEntity(
    val blobSetId: String,
    val chunkCount: Int,
    val retiredAtEpochMillis: Long,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val revisionDeviceId: String,
)
```

New `WorkspaceDao` members (beside the note helpers at
`VaultDatabase.kt:332-390`): `observeRetiredBlobSets(): Flow<List<RetiredBlobSetEntity>>`,
`getRetiredBlobSet(blobSetId: String): RetiredBlobSetEntity?`,
`upsertRetiredBlobSet(value: RetiredBlobSetEntity)`,
`deleteRetiredBlobSet(blobSetId: String): Int`.

- [ ] **Step 1: Write the failing in-memory test**

Create `RetiredBlobSetTest.kt`:

```kotlin
class RetiredBlobSetTest {

    private val repository = InMemoryVaultRepository()

    @Test
    fun permanentTaskDeletionRetiresReferencedBlobSets() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            val blobSetId = BlobSetId.new()
            val attachment = Attachment(
                id = AttachmentId.new(), taskId = task.id,
                displayName = "site-plan.pdf", mimeType = "application/pdf",
                byteCount = 9_000_000L,
                contentHash = "ab".repeat(32),
                blobSetId = blobSetId, chunkCount = 3, deletedAt = null,
                revision = task.revision,
            )
            repository.execute(DomainCommand.RegisterAttachment(attachment))

            repository.execute(DomainCommand.PermanentlyDeleteTask(task.id))

            val retired = repository.currentWorkspace().retiredBlobSets.single()
            assertEquals(blobSetId, retired.blobSetId)
            assertEquals(3, retired.chunkCount)
            assertTrue(repository.currentWorkspace().attachments.none { it.taskId == task.id })
        }
    }

    @Test
    fun tombstoneDeleteAndBloblessPurgeWriteNoRetiredRow() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            val blobless = Attachment(
                id = AttachmentId.new(), taskId = task.id,
                displayName = "pending.bin", mimeType = "application/octet-stream",
                byteCount = 10L, contentHash = "cd".repeat(32),
                blobSetId = null, chunkCount = 0, deletedAt = null,
                revision = task.revision,
            )
            repository.execute(DomainCommand.RegisterAttachment(blobless))

            repository.execute(DomainCommand.DeleteAttachment(blobless.id))
            repository.execute(DomainCommand.PermanentlyDeleteTask(task.id))

            assertTrue(repository.currentWorkspace().retiredBlobSets.isEmpty())
        }
    }
}
```

Match the exact `PermanentlyDeleteTask` constructor arguments at
`VaultRepository.kt:248` before compiling.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*RetiredBlobSetTest*"`
Expected: FAIL — `retiredBlobSets` unresolved.

- [ ] **Step 3: Implement**

Add the record, snapshot field, entity, DAO members; bump
`@Database(version = 9)` adding `RetiredBlobSetEntity` to the entity list;
add the migration to the companion beside `MIGRATION_7_8`
(`VaultDatabase.kt:1022`) and to `.addMigrations(...)`:

```kotlin
internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `retired_blob_sets` (" +
                "`blobSetId` TEXT NOT NULL, `chunkCount` INTEGER NOT NULL, " +
                "`retiredAtEpochMillis` INTEGER NOT NULL, " +
                "`revisionWallMillis` INTEGER NOT NULL, " +
                "`revisionLogical` INTEGER NOT NULL, " +
                "`revisionDeviceId` TEXT NOT NULL, " +
                "PRIMARY KEY(`blobSetId`))",
        )
    }
}
```

In `VaultDatabase.purgeTask`, before deleting the task's attachment rows,
select rows with `blobSetId IS NOT NULL` and insert one retired row each
(retirement revision from the purge arguments; `retiredAtEpochMillis` from
`revisionWallMillis`). Apply the same inside the Room trash-purge path if
it does not route through `purgeTask`, and mirror both in
`InMemoryVaultRepository.permanentlyDeleteTask` and `purgeExpiredTrash`.
Snapshot mapping surfaces the table as `WorkspaceSnapshot.retiredBlobSets`
ordered by `retiredAtEpochMillis` then `blobSetId` in both repositories.

- [ ] **Step 4: Run to verify pass, export schema, extend migration test**

Run: `./gradlew :core:data:testDebugUnitTest` — all green.
Run: `./gradlew :core:data:kspDebugKotlin` — `9.json` appears; commit it.
Extend `VaultDatabaseMigrationInstrumentedTest` with an 8→9 case seeding a
v8 fixture (tasks, attachments with and without `blobSetId`,
`attachment_transfer` rows) and asserting byte-preserved rows plus an
empty `retired_blob_sets` table after migration. Then run
`./gradlew :core:data:compileDebugAndroidTestKotlin` (compile-verified;
executes at Task 13). Run `scripts/check-schema-drift.sh`.

- [ ] **Step 5: Commit**

```bash
git add core/model core/data
git commit -m "feat: add room v9 retired blob-set index"
```

---

### Task 2: RETIRED_BLOB_SET backup family and collection command

**Files:**

- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordV1.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomBackupJournalSession.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/InMemoryBackupJournal.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupMutationCodec.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordImporter.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RecoveryImportDao.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/StagedVaultVerifier.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
  and `InMemoryVaultRepository.kt` (dispatch arms)
- Modify: `scripts/generate-stage2-backup-v1-fixtures.mjs` and regenerate
  `core/data/src/test/resources/backup-format/v1/*`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/RetiredBlobSetFamilyTest.kt`

**Interfaces:**

- Consumes: Task 1's entity/DAO, `BackupRecordFamily` (`BackupRecordV1.kt:61`),
  the `NoteEntity.toBackupRecordV1()` pattern (`BackupRecordV1.kt:296`),
  the snapshot-builder family map (`RoomBackupJournalSession.kt:505-529`)
  and `identitySnapshot` fan-out (`:533-580`), `RecoveryImportRequest`
  (`BackupRecordImporter.kt:38`).
- Produces: `BackupRecordFamily.RETIRED_BLOB_SET` (after `NOTE`, before
  `TOMBSTONE`); `RetiredBlobSetEntity.toBackupRecordV1()`; importer arm
  `toRetiredBlobSetEntity()`; and the new command executed by GC:

```kotlin
data class MarkRetiredBlobSetCollected(
    val blobSetId: BlobSetId,
    val collectedAt: Instant = Instant.now(),
) : DomainCommand
```

Semantics mirror `MarkAttachmentContentCollected`
(`VaultRepository.kt:326`): deleting an absent row is an idempotent
`Success` with no journal write and no Undo; deleting a present row
removes it and journals the family delete in the same transaction.

- [ ] **Step 1: Write the failing family tests**

```kotlin
class RetiredBlobSetFamilyTest {

    private val repository = InMemoryVaultRepository()

    @Test
    fun purgeJournalsRetiredRowAndCollectionJournalsDelete() = runBlocking {
        withTimeout(5_000) {
            val task = repository.currentWorkspace().tasks.first()
            val blobSetId = BlobSetId.new()
            repository.execute(
                DomainCommand.RegisterAttachment(
                    OpenTasksFixtures.attachment(task, blobSetId = blobSetId, chunkCount = 2),
                ),
            )
            repository.execute(DomainCommand.PermanentlyDeleteTask(task.id))

            val upserts = repository.journalEntries()
                .filter { it.objectType == "RETIRED_BLOB_SET" }
            assertEquals(blobSetId.value, upserts.last().objectId)

            val collect = repository.execute(
                DomainCommand.MarkRetiredBlobSetCollected(blobSetId),
            )
            assertTrue(collect is CommandResult.Success)
            assertTrue(repository.currentWorkspace().retiredBlobSets.isEmpty())
            val again = repository.execute(
                DomainCommand.MarkRetiredBlobSetCollected(blobSetId),
            )
            assertTrue(again is CommandResult.Success)
        }
    }
}
```

Add an `OpenTasksFixtures.attachment(task, blobSetId, chunkCount)` helper
in `Fixtures.kt` if one does not already exist; reuse the journal-entry
observation hook the Stage 4 note tests use (see
`InMemoryNoteCommandTest`/`InMemoryAttachmentCommandTest` for the exact
accessor name — do not invent a new one).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*RetiredBlobSetFamilyTest*"`
Expected: FAIL — `RETIRED_BLOB_SET`/command unresolved.

- [ ] **Step 3: Implement the family end-to-end**

Enum entry; `toBackupRecordV1()` with fields `blobSetId` (identity),
`chunkCount`, `retiredAtEpochMillis`, revision triple; snapshot-builder and
`identitySnapshot` arms in `RoomBackupJournalSession`; in-memory journal
parity; `BackupMutationCodec` strict validation (non-blank identity,
`chunkCount in 0..25`, non-negative timestamp); `BackupRecordImporter`
plan + `toRetiredBlobSetEntity()` + `RecoveryImportDao` insert;
`StagedVaultVerifier` scoped counts include the family; both repository
dispatch arms for `MarkRetiredBlobSetCollected` inside
`database.withTransaction { }` / the in-memory equivalent.

- [ ] **Step 4: Regenerate fixtures and run the golden suite**

Extend `generate-stage2-backup-v1-fixtures.mjs` with one retired row in
the snapshot fixture, run it, and confirm only expected resource diffs:

```bash
node scripts/generate-stage2-backup-v1-fixtures.mjs
git diff --stat core/data/src/test/resources
./gradlew :core:data:testDebugUnitTest
```

Expected: `BackupPayloadGoldenTest`, `PortableBackupGoldenTest`, and the
new family test all pass.

- [ ] **Step 5: Commit**

```bash
git add core/domain core/data scripts
git commit -m "feat: back up retired blob sets as a record family"
```

---

### Task 3: GC closure over retired sets

**Files:**

- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/AttachmentRuntime.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/RetiredBlobSetGcTest.kt`

**Interfaces:**

- Consumes: `GcCandidate` and `isCollectable`
  (`AttachmentGarbageCollector.kt:25-53` — unchanged),
  `AttachmentRuntime.collectable(configuration)` and
  `tombstoneGeneration(...)` (`AttachmentRuntime.kt:379-421`),
  `AttachmentGcResult.collectedBlobSets`,
  `DomainCommand.MarkRetiredBlobSetCollected` (Task 2).
- Produces: retired rows joining the GC candidate stream. For each
  `retired_blob_sets` row: `deletedAt = retiredAt`, `tombstoneGeneration =
  journalDao.latestGenerationFor(objectType = "RETIRED_BLOB_SET", objectId
  = blobSetId)`, base coverage from the existing coverage helpers,
  `activelyReferenced = true` only if a live attachment row shares the
  `blobSetId` (replacement edge — excluded from deletion). After
  `runBatch`, every retired `blobSetId` in `collectedBlobSets` is released
  via `repository.execute(MarkRetiredBlobSetCollected(blobSetId))`.

- [ ] **Step 1: Write the failing GC tests**

In `RetiredBlobSetGcTest.kt`, drive `AttachmentGarbageCollector.runBatch`
with a fake `AttachmentBlobStore` (copy the fake in
`AttachmentGarbageCollectorTest.kt`) over candidates built the way the
runtime will build them, and assert:

```kotlin
@Test
fun retiredCandidateOldEnoughAndCoveredIsDeletedChunksBeforeManifest() { /* deletions ordered, collectedBlobSets contains id */ }

@Test
fun youngOrUncoveredRetiredCandidateIsRetained() { /* no store deletes */ }

@Test
fun retiredIdStillReferencedByLiveAttachmentIsNeverACandidate() { /* activelyReferenced excluded */ }
```

Write them as full tests with the fake store's recorded call order (the
existing test file shows the recording pattern); the third asserts the
candidate builder output, not the collector.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*RetiredBlobSetGcTest*"`
Expected: FAIL — candidate builder does not yet include retired rows.

- [ ] **Step 3: Implement in `AttachmentRuntime`**

Extend `collectable(configuration)` to append retired-row candidates and
extend `collectRetiredBytes()` to execute `MarkRetiredBlobSetCollected`
for each collected retired id after `runBatch` (same place
`recordAllContentCollected` handles attachment rows). No change to
`AttachmentGarbageCollector` itself.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:data:testDebugUnitTest` — green, including the
existing GC and ownership-boundary suites.

- [ ] **Step 5: Commit**

```bash
git add core/data
git commit -m "feat: collect retired blob sets in attachment gc"
```

---

### Task 4: Silent attachment intake auto-resume

**Files:**

- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/AttachmentRuntime.kt`
- Modify: `app/src/main/kotlin/app/opentasks/backup/RemoteBackupRuntime.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/AttachmentResumeRuntimeTest.kt`

**Interfaces:**

- Consumes: `AttachmentBlobCoordinator.resume(store): Int`
  (`AttachmentBlobCoordinator.kt:90`, unchanged, tests retained),
  `AttachmentRuntime.expireStaleSessions()` (`AttachmentRuntime.kt:163`),
  `DefaultRemoteBackupRuntime` constructor
  (`RemoteBackupRuntime.kt:323-330`) and its `start()` expiry launch
  (`:368-378`), AppModule wiring (`AppModule.kt:560`).
- Produces:

```kotlin
// AttachmentRuntime
suspend fun resumeInterruptedSessions(): Int
```

Behaviour: open a provider session via the existing `openSession`; a
non-`Opened` result or stopped runtime returns 0 with zero store calls;
otherwise `expireStaleSessions` has already run and the method returns
`coordinator.resume(store)`. `DefaultRemoteBackupRuntime` gains
`resumeAttachmentSessions: suspend () -> Unit = {}`, invoked in `start()`
immediately after the expiry launch completes (same coroutine, ordered:
expire, then resume) and again in the existing completion-re-arm branch
after a publication run finishes, so a long-lived process retries.
`AppModule` wires `resumeAttachmentSessions =
{ attachmentRuntime.resumeInterruptedSessions() }` beside the existing
`expireAttachmentSessions` lambda (`AppModule.kt:560`).

- [ ] **Step 1: Write the failing runtime tests**

`AttachmentResumeRuntimeTest.kt` constructs `AttachmentRuntime` the way
`AttachmentIntakeTest` does (fake store, in-memory repository, real
transfer DAO fake) and asserts:

```kotlin
@Test
fun interruptedSessionResumesAndRegistersWithoutNewIntake() { /* seed a PHASE_UPLOADING session, resumeInterruptedSessions() == 1, record registered */ }

@Test
fun nonActiveLineageMakesZeroStoreCalls() { /* openSession returns Unavailable; store records no calls */ }

@Test
fun stoppedRuntimeRefusesResume() { /* stop() first; returns 0, zero store calls */ }
```

Write them fully using the seeding helpers in `AttachmentIntakeTest`; the
session row seeds through the same transfer DAO the coordinator persists.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*AttachmentResumeRuntimeTest*"`
Expected: FAIL — `resumeInterruptedSessions` unresolved.

- [ ] **Step 3: Implement runtime method and app wiring**

As specified in Interfaces. In `DefaultRemoteBackupRuntime.start()`,
replace the expiry launch body with `expireAttachmentSessions();
resumeAttachmentSessions()` so ordering is guaranteed; add the
completion-re-arm call beside the existing re-arm logic. Update the
existing `RemoteBackupRuntime` unit tests' constructor calls.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:data:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS, including the untouched `AttachmentBlobCoordinator`
resume/expiry suites.

- [ ] **Step 5: Commit**

```bash
git add core/data app
git commit -m "feat: auto-resume interrupted attachment intakes"
```

---

### Task 5: `.otvault` v1 format, codec, and fixtures

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/OtVaultCodec.kt`
- Create: `scripts/generate-stage5-otvault-v1-fixtures.mjs`
- Create: `core/data/src/test/resources/backup-format/otvault-v1/`
  (generator output: `archive.bin`, `archive.json` descriptor, corrupt/
  truncated/oversized/newer-version variants)
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/OtVaultCodecTest.kt`,
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/OtVaultGoldenTest.kt`

**Interfaces:**

- Consumes: `AuthenticatedCloudObjectCodec`, `CloudObjectFormat` families
  (SNAPSHOT, OPERATION_SEGMENT, ATTACHMENT_CHUNK, MANIFEST),
  `RecoveryEnvelopeCodec` (canonical envelope bytes, Argon2id constants),
  `VaultCrypto`/`VaultKeyEnvelope`/`VaultKey`,
  `BackupSnapshotPayloadV1`/`BackupOperationSegmentPayloadV1`,
  `AttachmentBlobSetManifestCodec`, `StructuredBackupCapture`.
- Produces:

```kotlin
data class OtVaultHeaderV1(
    val formatVersion: Int,          // 1
    val vaultId: VaultId,
    val createdAtEpochMillis: Long,
    val envelope: VaultKeyEnvelope,  // content key wrapped under export passphrase
    val recordCount: Int,
    val attachmentCount: Int,
)

data class OtVaultInventoryEntryV1(
    val objectId: String,
    val family: String,
    val sha256: String,
    val byteCount: Long,
)

sealed interface OtVaultReadEvent {
    data class Snapshot(val payload: BackupSnapshotPayloadV1) : OtVaultReadEvent
    data class Segment(val payload: BackupOperationSegmentPayloadV1) : OtVaultReadEvent
    data class AttachmentManifest(val manifest: AttachmentBlobSetManifest) : OtVaultReadEvent
    data class AttachmentChunk(
        val blobSetId: BlobSetId, val chunkIndex: Int, val plaintext: ByteArray,
    ) : OtVaultReadEvent
}

class OtVaultCodec(private val codec: AuthenticatedCloudObjectCodec) {
    fun writeHeader(destination: OutputStream, header: OtVaultHeaderV1)
    fun readHeader(source: InputStream): OtVaultHeaderV1   // bounded before anything else
    // frame writers: each encrypts under the content key at a fresh
    // archive-scoped object id and appends the inventory entry
    fun writeSnapshot(destination, key: VaultKey, header, payload): OtVaultInventoryEntryV1
    fun writeSegment(destination, key, header, payload): OtVaultInventoryEntryV1
    fun writeAttachmentManifest(destination, key, header, manifest): OtVaultInventoryEntryV1
    fun writeAttachmentChunk(destination, key, header, blobSetId, chunkIndex, plaintext): OtVaultInventoryEntryV1
    fun writeInventory(destination, key, header, entries: List<OtVaultInventoryEntryV1>)
    fun readAll(source, key, header, onEvent: (OtVaultReadEvent) -> Unit)
    // readAll authenticates every frame, verifies the inventory last
    // (count + per-object sha256), and throws OtVaultFormatException on
    // any mismatch, truncation, unknown family, or bound violation.

    companion object {
        const val MAGIC = "OPEN_TASKS_VAULT"
        const val FORMAT_VERSION = 1
        const val MAX_HEADER_BYTES = 16 * 1024
    }
}

class OtVaultFormatException(message: String) : Exception(message)
```

Header encoding: `MAGIC` bytes, u32 version, u32 length-prefixed canonical
JSON (fixed key order, matching `RecoveryEnvelopeCodec` conventions) with
the envelope embedded via its existing canonical encoding. Frames after
the header are standard `CloudObjectFormat` frames; chunk plaintext is
cleared after write/read delivery (`fill(0)` in `finally`).

- [ ] **Step 1: Write the failing codec tests**

`OtVaultCodecTest.kt`: round-trip write→read of a small archive (one
snapshot payload, one segment, one two-chunk attachment with manifest,
inventory) asserting event order, byte-identical plaintext, and cleared
chunk buffers; rejection tests for a flipped ciphertext byte, truncated
final frame, oversized header, wrong magic, and `formatVersion = 2`
(old-reader refusal) — each throwing `OtVaultFormatException` before any
event for the corrupted object is delivered.

- [ ] **Step 2: Run to verify failure, then implement**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*OtVaultCodecTest*"`
Expected: FAIL. Implement `OtVaultCodec` until green.

- [ ] **Step 3: Independent Node fixtures and golden test**

`generate-stage5-otvault-v1-fixtures.mjs` builds the same small archive
independently with Node `crypto` (Argon2id via the same parameters the
Stage 2/3 generators already implement, AES-GCM frames, fixed inputs) and
writes the resource set. `OtVaultGoldenTest` decodes the committed archive
byte-for-byte and asserts digest identity, mirroring
`AttachmentGoldenTest.kt:24-26` naming. Verify deterministic regeneration:

```bash
node scripts/generate-stage5-otvault-v1-fixtures.mjs
git diff --exit-code core/data/src/test/resources
./gradlew :core:data:testDebugUnitTest
```

- [ ] **Step 4: Commit**

```bash
git add core/data scripts
git commit -m "feat: freeze otvault v1 archive format"
```

---

### Task 6: Encrypted vault export

**Files:**

- Create: `app/src/main/kotlin/app/opentasks/backup/OtVaultExporter.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/AttachmentOpenCoordinator.kt`
  (chunk-callback variant), `AttachmentRuntime.kt` (expose it)
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`,
  `app/src/main/kotlin/app/opentasks/AttachmentIntakeViewModel.kt` or a new
  `app/src/main/kotlin/app/opentasks/VaultTransferViewModel.kt` (SAF
  launchers and state)
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/BackupRecoveryScreen.kt`,
  `feature/more/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/app/opentasks/backup/OtVaultExporterTest.kt`

**Interfaces:**

- Consumes: `OtVaultCodec` (Task 5), `RoomBackupCaptureSource.capture()`,
  `VaultCrypto` envelope creation (the same call
  `PortableBackupPublisher.prepare(passphrase)` uses at
  `PortableBackupPublisher.kt:47`), `AttachmentCacheStore.read`,
  `AttachmentBlobStore.readObject` via the runtime session.
- Produces:

```kotlin
sealed interface OtVaultExportResult {
    data class Completed(val byteCount: Long, val attachmentCount: Int) : OtVaultExportResult
    data class MissingAttachmentBytes(val displayNames: List<String>) : OtVaultExportResult
    data class Failed(val reason: String) : OtVaultExportResult   // reason is generic UK copy, never private data
}

class OtVaultExporter(...) {
    suspend fun export(destination: OutputStream, passphrase: CharArray): OtVaultExportResult
}

// AttachmentRuntime
suspend fun readChunksForExport(
    attachment: Attachment,
    onChunk: suspend (chunkIndex: Int, plaintext: ByteArray) -> Unit,
): Boolean   // false = bytes unfetchable (cache miss and no/failed session)
```

Export order: capture records in one transaction → wrap content key under
the export passphrase into the header envelope → **pre-flight every active
attachment** (cache probe, then session probe) and return
`MissingAttachmentBytes` with display names before writing anything if any
is unfetchable → stream snapshot, segments, manifests, chunks, inventory →
`Completed`. Partial output on failure is the caller's SAF document; the
ViewModel deletes it via `DocumentsContract.deleteDocument` and sweeps any
app-private temp state. No plaintext archive is ever staged.

- [ ] **Step 1: Write the failing exporter tests**

`OtVaultExporterTest.kt` with `InMemoryVaultRepository`-backed capture and
a fake chunk reader: a full export round-trips through
`OtVaultCodec.readAll` with matching counts; an unfetchable attachment
yields `MissingAttachmentBytes` listing its display name and writes zero
bytes; passphrase array is zeroed after export.

- [ ] **Step 2: Run to verify failure, implement, re-run**

Run: `./gradlew :app:testDebugUnitTest --tests "*OtVaultExporterTest*"`
FAIL, implement, PASS. The `readChunksForExport` internals reuse
`AttachmentOpenCoordinator`'s verified chunk streaming with a per-chunk
callback instead of an `OutputStream` sink.

- [ ] **Step 3: Product surface**

`BackupRecoveryScreen` gains an "Export encrypted vault" row (testTag
`"vault-export"`) under a new "Vault transfer" heading; stateless — data
in, lambdas out. The ViewModel owns
`rememberLauncherForActivityResult(CreateDocument("application/octet-stream"))`
with suggested name `open_tasks_vault.otvault`, the passphrase sheet
(reuse the `RecoveryPassphraseSheet` composable pattern at
`BackupRecoveryScreen.kt:764` — new entry, fresh passphrase with
confirmation field), progress/result states, and the UK copy note that
the export passphrase restores this archive. Strings in
`feature/more/src/main/res/values/strings.xml`.

- [ ] **Step 4: Gate and commit**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
git add app feature/more core/data
git commit -m "feat: export encrypted otvault archives"
```

---

### Task 7: Encrypted vault import and activation

**Files:**

- Create: `app/src/main/kotlin/app/opentasks/backup/OtVaultImporter.kt`
- Modify: `app/src/main/kotlin/app/opentasks/VaultTransferViewModel.kt`
  (or the Task 6 ViewModel), `OpenTasksApp.kt` (import route/dialog),
  `feature/more/.../BackupRecoveryScreen.kt`, feature/more strings
- Test: `app/src/test/kotlin/app/opentasks/backup/OtVaultImporterTest.kt`
- Test (instrumented, compile-verified):
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/backup/OtVaultImportInstrumentedTest.kt`

**Interfaces:**

- Consumes: `OtVaultCodec.readHeader`/`readAll`, `BackupRecordImporter`
  (`RoomBackupRecordImporter.importInto(database, request)` with
  `RecoveryImportRequest(snapshot, segments, recoveryEnvelope,
  expectedGeneration)` — `BackupRecordImporter.kt:38-50`),
  `DefaultStagedVaultVerifier.verify(slot, expectedVaultId,
  expectedGeneration, expectedCapture)` (`StagedVaultVerifier.kt:26-40`),
  the recovery activation slot-replacement path proven by
  `RecoveryActivationInstrumentedTest.kt`, `AttachmentCacheStore.write`.
- Produces:

```kotlin
sealed interface OtVaultImportPreview {
    data class Ready(
        val recordCount: Int,
        val attachmentCount: Int,
        val attachmentsBeyondCache: List<String>,   // display names, may be empty
    ) : OtVaultImportPreview
    data class Rejected(val reason: String) : OtVaultImportPreview
}

class OtVaultImporter(...) {
    suspend fun stage(source: InputStream, passphrase: CharArray): OtVaultImportPreview
    suspend fun activate(): Boolean   // only after a Ready preview; false restores rollback
    suspend fun abandon()             // deletes staging, active vault untouched
}
```

Rules from the spec, all mandatory: staging is isolated (fresh staging
directory + staged SQLCipher database); the archive envelope becomes the
imported vault's stored recovery envelope (export passphrase = recovery
passphrase, said in the preview copy); remote state tables stay empty
(fresh-only local operational state → disconnected-dormant; the active
runtime stops via the existing slot replacement); chunks are written to
the cache until the `min(128 MiB, 5%)` bound, remaining attachments are
listed in `attachmentsBeyondCache`; the previous slot is retained as
rollback until the imported vault completes its first successful unlock,
and any activation failure restores it; corrupt archives reject at
`stage` with the active vault untouched.

- [ ] **Step 1: Write the failing importer unit tests**

`OtVaultImporterTest.kt` against the Task 5 fixture archives: `stage`
returns `Ready` with exact counts; a corrupt archive returns `Rejected`
and leaves no staging residue; a wrong passphrase returns `Rejected`
without decrypting any frame; `attachmentsBeyondCache` lists names when
the fake cache reports a tiny bound.

- [ ] **Step 2: Run to verify failure, implement staging, re-run**

Run: `./gradlew :app:testDebugUnitTest --tests "*OtVaultImporterTest*"`
FAIL → implement `stage`/`abandon` → PASS.

- [ ] **Step 3: Implement activation and the instrumented proof**

`activate()` maps staged content into `RecoveryImportRequest`, runs
`RoomBackupRecordImporter.importInto` + `DefaultStagedVaultVerifier` on
the staged slot, then swaps slots exactly the way recovery activation
does. `OtVaultImportInstrumentedTest` (compile-verified now, runs at
Task 13) proves: export from a seeded vault → import into a fresh context
→ canonical capture bytes match → active runtime stopped → remote config
tables empty → rollback slot present until first unlock, gone after.

- [ ] **Step 4: Product surface**

"Import encrypted vault" row (testTag `"vault-import"`) beside export;
`OpenDocument` launcher accepting `application/octet-stream`; preview
dialog showing counts, replacement consequences, the recovery-passphrase
note, and `attachmentsBeyondCache` names when non-empty; explicit
Replace/Cancel. Compile the androidTest source set:
`./gradlew :core:data:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin`.

- [ ] **Step 5: Gate and commit**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
git add app feature/more core/data
git commit -m "feat: import otvault archives with staged activation"
```

---

### Task 8: CSV export with disclosure

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/export/WorkspaceCsvWriter.kt`
- Modify: `app/src/main/kotlin/app/opentasks/VaultTransferViewModel.kt`,
  `feature/more/.../BackupRecoveryScreen.kt` (or MoreScreen — beside the
  vault-transfer rows), feature/more strings
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/export/WorkspaceCsvWriterTest.kt`

**Interfaces:**

- Consumes: `WorkspaceSnapshot` (tasks, projects, timeEntries, notes with
  the exact record shapes in `Records.kt`).
- Produces:

```kotlin
enum class CsvTable { TASKS, PROJECTS, TIME_ENTRIES, NOTES }

class WorkspaceCsvWriter(private val zone: ZoneId) {
    fun write(table: CsvTable, snapshot: WorkspaceSnapshot, out: Appendable)
}
```

Fixed column orders (header row exactly these names):

- `TASKS`: `id,title,project,status,priority,start_display,start_iso,due_display,due_iso,completed_display,completed_iso,estimate_minutes,tags,description`
- `PROJECTS`: `id,name,summary,health,due_display,due_iso,completed_tasks,total_tasks`
- `TIME_ENTRIES`: `id,task_id,task_title,started_display,started_iso,stopped_display,stopped_iso,duration_minutes,note`
- `NOTES`: `id,owner_type,owner_id,owner_title,created_display,created_iso,edited_iso,body`

Rules: RFC 4180 (CRLF rows, quote fields containing `,`/`"`/CR/LF, double
embedded quotes); UTF-8; `*_display` uses UK format `d MMMM yyyy HH:mm`
(date-only fields `d MMMM yyyy`) in the moment's stored zone
(`ZonedMoment.zone()`) or the writer zone for `Instant` fields; `*_iso`
uses `DateTimeFormatter.ISO_OFFSET_DATE_TIME` / `ISO_INSTANT`; values
starting with `=`, `+`, `-`, or `@` are prefixed with `'`;
Bin rows (`deletedAt != null`) are excluded; tags are semicolon-joined
names; `owner_type` is `task` or `project`.

- [ ] **Step 1: Write the failing writer tests**

Full tests: header exactness per table; a title
`=HYPERLINK("x"),"b"` round-trips neutralised and quoted; CRLF endings; a
multi-line note body stays one quoted field; Bin task excluded; UK and
ISO columns agree on the same moment; empty optional fields are empty
strings.

- [ ] **Step 2: Run FAIL, implement, run PASS**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*WorkspaceCsvWriterTest*"`

- [ ] **Step 3: Product surface**

"Export CSV" row (testTag `"csv-export"`): table multi-select, then the
fresh disclosure dialog (exact consequence copy from the spec, no
"do not ask again"), then one `CreateDocument("text/csv")` per selected
table (`open_tasks_tasks.csv` etc.), streamed via the ViewModel with no
retained plaintext copy and partial-output deletion on failure.

- [ ] **Step 4: Gate and commit**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
git add core/data app feature/more
git commit -m "feat: add disclosed formula-safe csv export"
```

---

### Task 9: Today widget

**Files:**

- Modify: `gradle/libs.versions.toml` (add `glance = "1.1.1"` and
  `glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glance" }`;
  use the current stable if newer), `app/build.gradle.kts`
  (`implementation(libs.glance.appwidget)`)
- Create: `app/src/main/kotlin/app/opentasks/widget/TodayWidget.kt`
  (GlanceAppWidget + receiver + projection publisher),
  `app/src/main/kotlin/app/opentasks/widget/TodayWidgetProjection.kt`
- Create: `app/src/main/res/xml/today_widget_info.xml`; Modify:
  `app/src/main/AndroidManifest.xml` (receiver with
  `APPWIDGET_UPDATE` intent filter), `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt` (publisher
  lifecycle beside the slot services)
- Test: `app/src/test/kotlin/app/opentasks/widget/TodayWidgetProjectionTest.kt`

**Interfaces:**

- Consumes: `VaultRepository.observeWorkspace()`, `WorkspaceSnapshot`,
  Task 10's `AppLockSettings` does not exist yet — this task publishes
  titles unconditionally gated by a `titlesPermitted: Boolean` input that
  Task 10 wires to lock/privacy state (until then the publisher passes
  `true`).
- Produces:

```kotlin
data class TodayWidgetProjection(
    val openTodayCount: Int,
    val overdueCount: Int,
    val focusTitles: List<String>,   // max 3; empty when titles not permitted
)

fun computeTodayProjection(
    snapshot: WorkspaceSnapshot,
    today: LocalDate,
    zone: ZoneId,
    titlesPermitted: Boolean,
): TodayWidgetProjection
```

Semantics: a task counts for today when open (`!isCompleted`,
`deletedAt == null`) and its `start` or fallback `due` local date (in the
moment's stored zone) equals `today`; overdue when open and `due` is
before now; focus titles are today's open tasks ordered by due then
priority, first three. `TodayWidgetPublisher` collects
`observeWorkspace()` in the app scope, recomputes, writes Glance state
(`updateAppWidgetState`) and calls `TodayWidget().updateAll(context)`.
Glance state lives under `filesDir`, which the Auto Backup allow-list
already excludes (only `android_backup/open_tasks_portable_v1.otb` is
included). Widget tap → `MainActivity`; Quick Add button → `MainActivity`
with extra `"open_quick_add" = true` (record-free intent, handled by the
existing `handleIntent` at `MainActivity.kt:177`).

- [ ] **Step 1: Write the failing projection tests**

Full tests over `OpenTasksFixtures` data: today-by-start, fallback-to-due,
Bin and completed excluded, overdue boundary at `now`, three-title cap and
ordering, `titlesPermitted = false` → empty titles with counts intact,
zone-respecting date bucketing (a 23:30 UTC start that is tomorrow in the
stored zone).

- [ ] **Step 2: Run FAIL, implement `computeTodayProjection`, run PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*TodayWidgetProjectionTest*"`

- [ ] **Step 3: Glance surface and publisher**

Add the catalogue entry and dependency; implement the widget (counts row,
up to three title lines or the generic "Tasks are hidden" string, Open and
Quick Add actions via `actionStartActivity<MainActivity>`), the receiver,
provider info XML (resizable horizontal/vertical, min 2×1), and the
publisher wired into the active-slot lifecycle (stop collection with the
slot; on stop, clear titles from Glance state). Verify:
`./gradlew :app:assembleDebug` and
`./gradlew :app:compileDebugAndroidTestKotlin`.

- [ ] **Step 4: Gate and commit**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
git add gradle app
git commit -m "feat: add today glance widget"
```

---

### Task 10: App lock, title privacy, and unified Quick Add

**Files:**

- Create: `app/src/main/kotlin/app/opentasks/lock/AppLockSettings.kt`,
  `app/src/main/kotlin/app/opentasks/lock/AppLockController.kt`,
  `app/src/main/kotlin/app/opentasks/lock/AppLockScreen.kt` (thin overlay
  composable in app, not feature)
- Modify: `app/src/main/kotlin/app/opentasks/MainActivity.kt`,
  `OpenTasksApp.kt`, `app/src/main/kotlin/app/opentasks/reminders/ReminderSystem.kt`,
  `app/src/main/kotlin/app/opentasks/widget/TodayWidget.kt` (wire
  `titlesPermitted`), `app/src/main/res/values/strings.xml`,
  `app/src/main/res/xml/shortcuts.xml` (create — static Quick Add
  shortcut), `AndroidManifest.xml` (shortcut meta-data)
- Modify: `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`
  + strings (Privacy & lock settings section)
- Test: `app/src/test/kotlin/app/opentasks/lock/AppLockControllerTest.kt`

**Interfaces:**

- Consumes: platform `android.hardware.biometrics.BiometricPrompt` +
  `BiometricManager` (`setAllowedAuthenticators(BIOMETRIC_STRONG or
  BIOMETRIC_WEAK or DEVICE_CREDENTIAL)`), `Activity.setRecentsScreenshotEnabled(false)`,
  `WindowManager.LayoutParams.FLAG_SECURE`, `ReminderNotifier`
  (`ReminderSystem.kt:175-207`), `QuickAddSheet` (`QuickAddSheet.kt:42`),
  Task 9's publisher `titlesPermitted` input.
- Produces:

```kotlin
enum class LockDelay(val duration: Duration) {
    IMMEDIATE(Duration.ZERO),
    ONE_MINUTE(Duration.ofMinutes(1)),
    FIVE_MINUTES(Duration.ofMinutes(5)),
    FIFTEEN_MINUTES(Duration.ofMinutes(15)),
}

class AppLockSettings(prefs: SharedPreferences) {
    var lockEnabled: Boolean
    var lockDelay: LockDelay
    var titlePrivacy: Boolean
    var screenshotBlocking: Boolean
    fun observe(): Flow<Unit>   // change notifications
}

class AppLockController(
    private val settings: AppLockSettings,
    private val now: () -> Instant = Instant::now,
) {
    fun onAppBackgrounded()
    fun onAppForegrounded(): Boolean   // true = must lock now
    fun onUnlocked()
    val locked: StateFlow<Boolean>     // true on cold start when enabled
}
```

Behaviour, pinned: cold start with `lockEnabled` starts locked;
foreground after a background span `>= lockDelay.duration` locks; unlock
uses one platform `BiometricPrompt` with device-credential fallback and
changes no key material; the lock overlay replaces all content (no
composition of workspace data behind it); `titlePrivacy || locked` drives
`titlesPermitted = false` into the widget publisher (with an immediate
republish on engage), the generic notification content path in
`ReminderNotifier.show`, and generic external Quick Add labels;
`setRecentsScreenshotEnabled(false)` whenever `lockEnabled || titlePrivacy`;
`FLAG_SECURE` only when `screenshotBlocking`; recovery/permission dialogs
render outside the concealed content and stay readable. Quick Add: static
launcher shortcut and widget extra both route through
`MainActivity.handleIntent` → after unlock → the one shared
`QuickAddSheet`; exported intents carry only the boolean extra.

- [ ] **Step 1: Write the failing controller tests**

Full unit tests: cold start locked when enabled; not locked when
disabled; background 4 min with `FIVE_MINUTES` → no lock, 6 min → lock;
`IMMEDIATE` locks on any foreground; `onUnlocked` clears; settings change
to disabled unlocks.

- [ ] **Step 2: Run FAIL, implement controller + settings, run PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*AppLockControllerTest*"`

- [ ] **Step 3: Surfaces**

Lock overlay (icon, "Unlock Open Tasks" button re-triggering the prompt);
MainActivity lifecycle wiring (`onStart`/`onStop` timestamps — the single
activity makes process-level observers unnecessary); More → "Privacy &
lock" section (enable, delay choice, title privacy, screenshot blocking —
Material list rows, strings.xml); notification/widget/shortcut wiring per
Interfaces. Compile instrumented:
`./gradlew :app:compileDebugAndroidTestKotlin` (an
`AppLockOverlayInstrumentedTest` asserting the overlay hides workspace
content and survives recreation runs at Task 13).

- [ ] **Step 4: Gate and commit**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
git add app feature/more
git commit -m "feat: add app lock and title privacy"
```

---

### Task 11: Keyboard, mouse, and accessible actions

**Files:**

- Create: `app/src/main/kotlin/app/opentasks/input/ShortcutDispatcher.kt`,
  `app/src/main/kotlin/app/opentasks/input/ShortcutHelpDialog.kt`
- Modify: `OpenTasksApp.kt` (root key handling + helper dialog state),
  `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/app/opentasks/input/ShortcutDispatcherTest.kt`

**Interfaces:**

- Produces:

```kotlin
enum class ShortcutAction {
    OPEN_SEARCH, QUICK_ADD, NEW_PROJECT, SHOW_HELP, DISMISS_TOP,
}

fun shortcutActionFor(
    key: Key, isCtrlPressed: Boolean, isShiftPressed: Boolean,
    inProjectsRoute: Boolean, editableFocused: Boolean,
): ShortcutAction?
```

Mapping, pinned: `Ctrl+K` and `/` → `OPEN_SEARCH`; `Ctrl+N` →
`QUICK_ADD`; `Ctrl+Shift+N` → `NEW_PROJECT` only when `inProjectsRoute`;
`?` → `SHOW_HELP`; `Esc` → `DISMISS_TOP`. Single-key shortcuts (`/`,
`?`) return null when `editableFocused`. Wiring: Ctrl combos on
`Modifier.onPreviewKeyEvent` at the root; single keys on bubbling
`Modifier.onKeyEvent` so focused text fields consume typing first, plus
an `editableFocused` guard tracked from the root `FocusManager`/
`onFocusEvent`. `DISMISS_TOP` dismisses in order: open dialog, open
sheet, expanded search — falling through to nothing (never `finish()`).
Every control keeps Enter/Space activation and visible focus/hover from
Material defaults; the task audits the five feature screens for any
drag-only action (there must be none — up/down and explicit actions are
the accessible authority) and records the audit in the task report.

- [ ] **Step 1: Write the failing dispatcher tests**

Full tests over `shortcutActionFor`: every mapping above, the
`editableFocused` suppression for `/` and `?` but not `Ctrl+K`, the
Projects-route guard, and null for unmapped keys.

- [ ] **Step 2: Run FAIL, implement, run PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*ShortcutDispatcherTest*"`

- [ ] **Step 3: Root wiring and help dialog**

Wire at the `OpenTasksApp` root; helper dialog lists every shortcut with
`stringResource` copy; instrumented compose test (compile now, run at
Task 13) sends `Ctrl+K` and asserts search focus, sends `Esc` and asserts
sheet dismissal. `./gradlew :app:compileDebugAndroidTestKotlin`.

- [ ] **Step 4: Gate and commit**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
git add app
git commit -m "feat: add keyboard shortcuts and helper"
```

---

### Task 12: Calendar insertion

**Files:**

- Create: `app/src/main/kotlin/app/opentasks/calendar/CalendarInsertion.kt`
- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/`
  (task editor action row), `feature/schedule/src/main/kotlin/...`
  (selected-task action), app wiring in `OpenTasksApp.kt`, strings in
  `feature/tasks/src/main/res/values/strings.xml` and
  `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/app/opentasks/calendar/CalendarInsertionTest.kt`

**Interfaces:**

- Produces:

```kotlin
data class CalendarEventDraft(
    val title: String,
    val beginEpochMillis: Long,
    val endEpochMillis: Long?,     // null = due-only context, no end extra
    val description: String,       // "Project: <name>" or ""
)

fun calendarEventDraft(task: Task, projectName: String?): CalendarEventDraft?
```

Pinned: null for undated tasks (`start == null && due == null`); `start`
present → begin at `start.instant`, end at `due?.instant` when after it;
due-only → begin at `due.instant`, `endEpochMillis = null`. The Android
wrapper builds `Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)`
with `EXTRA_EVENT_BEGIN_TIME`, conditional `EXTRA_EVENT_END_TIME`,
`Events.TITLE`, `Events.DESCRIPTION` — nothing else; no permission, no
stored event id, no result handling beyond returning to the app.

- [ ] **Step 1: Write the failing draft tests**

Full tests: undated → null; start+due → begin/end; due-only → null end;
due before start → null end; description from project name; Inbox task →
empty description.

- [ ] **Step 2: Run FAIL, implement, run PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*CalendarInsertionTest*"`

- [ ] **Step 3: Surfaces**

"Add to calendar" appears in the task editor and the Schedule selected
task only when `calendarEventDraft(...) != null`; tapping shows the
preview dialog (title, formatted times in the moment's stored zone,
description) with Insert/Cancel; Insert launches the provider intent.
Stateless feature composables receive `onAddToCalendar: (() -> Unit)?`
(null hides the action). `./gradlew :app:compileDebugAndroidTestKotlin`.

- [ ] **Step 4: Gate and commit**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
git add app feature/tasks feature/schedule
git commit -m "feat: add one-way calendar insertion"
```

---

### Task 13: Qualification and exit gates

**Files:**

- Modify: `docs/architecture.md`, `docs/threat-model.md`, `DESIGN.md`,
  `PRODUCT.md`, `CLAUDE.md`, `HANDOFF.md`
- Create: `docs/qualification/stage5-platform-features.md`

**Steps:**

- [ ] **Step 1: Full connected gate on the sole disposable**

Verify AVD identity, API level, and font scale 1.0 before
instrumentation, then:

```bash
./gradlew :app:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest :feature:more:connectedDebugAndroidTest
```

Expected: PASS including the 8→9 migration preservation case, the
`.otvault` import round trip, the app-lock overlay, shortcut, and widget
projection suites. Record exact counts. The known `Pixel_10_Pro_Fold`
cross-display harness skip remains the only expected skip. Shut the
disposable down and confirm empty ADB/emulator audits.

- [ ] **Step 2: Repository, release, schema, fixture, hygiene gates**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --rerun-tasks
./gradlew :app:assembleRelease --rerun-tasks
scripts/check-schema-drift.sh
node scripts/generate-stage2-backup-v1-fixtures.mjs && git diff --exit-code core/data/src/test/resources
node scripts/generate-stage4-attachment-v1-fixtures.mjs && git diff --exit-code core/data/src/test/resources
node scripts/generate-stage5-otvault-v1-fixtures.mjs && git diff --exit-code core/data/src/test/resources
git diff --check
```

Expected: all pass at schema v9. Release inspection: only
`drive.appdata`; no debug activity; the widget receiver is the only new
exported component; no new `Log.` call carries private fields (grep the
diff range for `Log\.`).

- [ ] **Step 3: Privacy scans**

Grep the Stage 5 diff range for task text, display names, Drive IDs, and
passphrase handling outside `CharArray`; verify Glance state files carry
no titles after lock engages (instrumented assertion from Task 10's
suite); verify CSV and `.otvault` temp paths are swept on failure (unit
suites from Tasks 6–8); verify exported shortcut/widget intents carry no
record text.

- [ ] **Step 4: Contract documents**

- `docs/architecture.md`: retired-set family, `.otvault`, CSV writer,
  widget projection, app lock — implemented state and boundaries.
- `docs/threat-model.md`: the widget plaintext-titles-at-rest addendum
  (cleared on lock/privacy, excluded from backup), export-passphrase =
  recovery-passphrase note, CSV plaintext disclosure control.
- `DESIGN.md` (widget, lock overlay, transfer rows, shortcut helper);
  `PRODUCT.md` (Stage 5 delivery boundary); `CLAUDE.md` (Room v9 frozen,
  glance dependency, new bounds); `HANDOFF.md` (Stage 5 closure
  checkpoint, carry-forwards discharged).
- Write `docs/qualification/stage5-platform-features.md` with every gate
  result and exact counts, no private identifiers.

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "docs: verify stage 5 platform features"
```

---

## Spec coverage map

| Spec section | Tasks |
|---|---|
| Recorded scope rulings | Global constraints, 4, 13 |
| Retired blob-set index (Room v9) | 1 |
| Retired-set backup family | 2 |
| GC closure, bounded deletion, fail closed | 3 |
| Silent intake auto-resume | 4 |
| `.otvault` format, fixtures, rejection vectors | 5 |
| Export: capture, fail-closed chunks, SAF, cleanup | 6 |
| Import: staging, validation, preview, activation, lineage stop, cache staging, rollback | 7 |
| CSV tables, disclosure, RFC 4180, neutralisation | 8 |
| Today widget, projection, privacy-gated titles | 9, 10 |
| App lock, title privacy surfaces, Quick Add unification | 10 |
| Keyboard/mouse/accessible actions | 11 |
| Calendar insertion | 12 |
| Testing, qualification, contract docs | every task's gates, 13 |
| External and deferred work | 13 (recorded, unchanged) |
