# Stage 2 Local Backup and Android Auto Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add atomic local backup generations, verified encrypted recovery
objects, and one strictly allow-listed self-encrypted Android backup package
without adding a cloud provider or a restore-to-Room path.

**Architecture:** Encrypted Room remains the sole live structured-data
authority. Each mutation-bearing `DomainCommand` commits its records and
ordered backup-journal entries under one local generation; a local coordinator
then captures, encrypts, decodes, and verifies immutable snapshots and
operation segments. The application publishes one separately verified
portable package for Android cloud backup and device transfer, while restored
packages remain inert until Stage 3.

**Tech Stack:** Kotlin 2.3.21, Android Gradle Plugin 9.3.1, Java 17 on JDK 21,
Compose BOM 2026.06.00, Room 2.8.4, SQLCipher 4.15.0, Tink 1.23.0,
Bouncy Castle 1.84, kotlinx.serialization 1.9.0, Android `AtomicFile`,
JUnit 4, Compose UI test v2, and Node.js built-in `crypto` for independent
format fixtures.

## Global Constraints

- Work directly on `main`; do not create a branch, worktree, or pull request.
- Preserve the protected Room workspace. Never uninstall the application,
  clear its data, attach instrumentation to its normal emulator, reset the
  repository, or overwrite its named replacement snapshot.
- Run every connected suite and every `bmgr` command with exactly one audited
  disposable ADB target.
- Room is the sole live structured-data authority. No normal cloud, portable
  package, or coordinator path may mutate active Room product records.
- Keep every v5 row and every `sync_operations` row. The v5→v6 migration is
  additive, `sync_operations` becomes read-only, and
  `vaults.storageMode` remains physically present but is normalised to
  `LOCAL`.
- Every mutation-bearing accepted `DomainCommand` receives one committed
  generation. Its complete ordered journal entries and product rows commit in
  the same Room transaction. Rejected, failed, and idempotent no-op commands
  consume no generation.
- Use strict canonical UTF-8 JSON payload version `1`, minimum reader version
  `1`, fixed field order, explicit nulls, fixed uppercase enum wire names,
  unpadded Base64, deterministic collection order, and epoch-millisecond
  instants.
- Preserve the Stage 1 authenticated frame bounds: 16 KiB header; 1 MiB
  manifest ciphertext; 64 MiB snapshot ciphertext; 16 MiB operation-segment
  ciphertext; 100,000 records per snapshot; and 10,000 operations per
  segment.
- AES-GCM crypto-v1 adds 33 bytes. Therefore v1 plaintext maxima are
  `1 MiB - 33` for manifests, `64 MiB - 33` for snapshots, and
  `16 MiB - 33` for operation segments.
- A local checkpoint advances only after the emitted frame is read back,
  length/checksum checked, authenticated, strictly decoded, and compared with
  its source identity and generation.
- Complete snapshots are required after 5,000 journal operations or seven
  days. Local recovery bases retain current and previous only, plus the
  segments required after either retained base.
- Local backup objects live only under `noBackupFilesDir/backup/v1/`.
- The sole Android-eligible file is
  `filesDir/android_backup/open_tasks_portable_v1.otb`.
- The complete portable package is at most 24 MiB (`25_165_824` bytes).
- Android cloud backup and device transfer use the same package and each has
  exactly one file-domain include. Database, WAL/SHM, preferences, keys,
  credentials, cache, no-backup staging, attachment bytes, temporary files,
  and `AtomicFile` backup files remain excluded.
- Android Auto Backup is supplementary. Product copy may say **Package
  ready**, but never **Backed up**, and may not invent an upload time or
  platform-encryption state.
- A recovery passphrase is 12–128 Unicode code points, is not trimmed or
  normalised, and must match confirmation exactly.
- Never persist a passphrase. Clear every mutable character, UTF-8, derived
  key, plaintext, associated-data, and owned ciphertext buffer at its
  ownership boundary; do not claim direct zeroisation of immutable Android or
  Compose text copies.
- Initialise the production vault-content key through
  `VaultContentKeyStore.getOrCreate` before exposing setup. Once a local
  envelope exists, alias loss or envelope damage fails closed and never
  creates a replacement.
- Setup wraps and verifies the established vault-content key. It never calls
  `VaultCrypto.createKey`, never replaces the SQLCipher key, and never
  activates a restored package.
- Keep `core:sync` and `core:crypto` free of Compose, Android UI, providers,
  credentials, and backup scheduling.
- Add no Google Identity, Drive REST, `BackupObjectStore`,
  `AttachmentBlobStore`, WorkManager job, `BackupAgent`, cloud scheduler,
  recovery activation, writer epoch, attachment transport, or remote merge.
- Feature composables remain stateless and Hilt-free. New user copy belongs in
  resources and uses UK English, Bin terminology, day–month dates, and the
  24-hour clock.
- Keep `minSdk 36`, `compileSdk 37`, `targetSdk 37`, Java 17, JDK 21, and AGP
  built-in Kotlin. Do not apply `org.jetbrains.kotlin.android`.
- Use JUnit 4 assertions and camelCase test names. Add no mocking library,
  Turbine, Robolectric, or coroutine-test dependency.
- Run release assembly separately from lint because the repository records an
  AGP/KSP release-lint race.
- GitHub dependency-PR checks and resolution remain paused.

---

## Stage Boundary and File Map

This plan implements only the approved
`docs/superpowers/specs/2026-07-28-stage-2-local-backup-android-auto-backup-design.md`.
Stage 3 separately owns app-managed provider backup, recovery activation,
writer takeover, remote retention, and passphrase change.

The focused file responsibilities are:

- `core/model/src/main/kotlin/app/opentasks/core/model/BackupModels.kt`
  — feature-safe generation, package information, status, and failure models.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/BackupContracts.kt`
  — immutable journal contracts, passphrase policy, snapshot/segment policy,
  and coordinator/status-source boundaries.
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupEntities.kt`
  — v6 journal, state, and recovery-envelope Room entities.
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupDaos.kt`
  — journal/state/envelope persistence and exact consistent-capture queries.
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordV1.kt`
  — canonical scalar record envelope and exact v1 field schemas.
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupMutationCodec.kt`
  — canonical local after-image/delete payloads.
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupPayloadCodec.kt`
  — strict complete-snapshot and operation-segment codecs.
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomBackupCaptureSource.kt`
  — one-transaction immutable capture at one committed generation.
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomBackupJournalSession.kt`
  — lazy one-generation allocation and ordered append for one command.
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/LocalBackupObjectStore.kt`
  — verified current/previous/segment storage under an injected no-backup root.
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/DefaultBackupCoordinator.kt`
  — single-flight capture, encryption, verification, checkpoint, and pruning.
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/RecoveryEnvelopeCodec.kt`
  — canonical bounded recovery-envelope representation.
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/PortableBackupCodec.kt`
  — portable bootstrap, authenticated manifest, complete package, and strict
  verification.
- `app/src/main/kotlin/app/opentasks/backup/AndroidBackupFiles.kt`
  — exact application-private eligible, staging, and recovery-inbox paths.
- `app/src/main/kotlin/app/opentasks/backup/RecoveryEnvelopePreparer.kt`
  — passphrase-bound wrapping and same-content-key proof.
- `app/src/main/kotlin/app/opentasks/backup/PortableBackupPublisher.kt`
  — `AtomicFile` publication, size withdrawal, and status reconciliation.
- `app/src/main/kotlin/app/opentasks/backup/RestoredPackageIntake.kt`
  — startup classification and inert no-backup quarantine.
- `app/src/main/kotlin/app/opentasks/backup/AndroidBackupRuntime.kt`
  — startup ordering, content-key bootstrap, journal observation, coalescing,
  restored-file intake, and publisher requests.
- `app/src/main/kotlin/app/opentasks/backup/BackupViewModel.kt`
  — app-layer setup/retry/system-settings actions and status binding.
- `feature/more/src/main/kotlin/app/opentasks/feature/more/BackupRecoveryScreen.kt`
  — stateless package status and non-saveable setup presentation.
- `scripts/generate-stage2-backup-v1-fixtures.mjs`
  — independent canonical payload and portable-package fixture generator.
- `core/data/src/test/resources/backup-format/v1/*.json`
  — immutable independently generated v1 vectors.

### Task 1: Replace Product Sync Contracts with Local Backup Contracts

**Files:**

- Create:
  `core/model/src/main/kotlin/app/opentasks/core/model/BackupModels.kt`
- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/BackupContracts.kt`
- Create:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/BackupPolicyTest.kt`
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt`
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt`
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Fixtures.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- Delete:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/CloudObjectStore.kt`
- Modify:
  `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/Components.kt`
- Modify:
  `feature/home/src/main/kotlin/app/opentasks/feature/home/HomeScreen.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify: `core/data/build.gradle.kts`
- Modify: `CLAUDE.md`

**Interfaces:**

- Consumes: `VaultId`, `Instant`, existing `DomainCommand`, and the fixed
  Stage 1 bounds in `CloudBounds`.
- Produces:

```kotlin
@JvmInline
value class BackupGeneration(val value: Long)

data class BackupPackageInfo(
    val packageGeneration: BackupGeneration,
    val currentGeneration: BackupGeneration,
    val byteCount: Long,
    val producedAt: Instant,
)

enum class BackupUnavailableReason {
    PACKAGE_TOO_LARGE,
    RECOVERY_ENVELOPE_UNAVAILABLE,
    ENCODING_OR_CRYPTO,
    VERIFICATION_FAILED,
    FILE_IO,
}

enum class RestoredPackageCondition {
    PRESERVED,
    INCOMPATIBLE_OR_CORRUPT,
}

sealed interface AndroidBackupStatus {
    data object NotPrepared : AndroidBackupStatus
    data object Preparing : AndroidBackupStatus
    data class Ready(val packageInfo: BackupPackageInfo) : AndroidBackupStatus
    data class UpdatePending(val packageInfo: BackupPackageInfo) : AndroidBackupStatus
    data class Unavailable(
        val reason: BackupUnavailableReason,
    ) : AndroidBackupStatus
    data class RestoredPackageDetected(
        val condition: RestoredPackageCondition,
    ) : AndroidBackupStatus
}

sealed interface RecoveryPassphraseValidation {
    data object Valid : RecoveryPassphraseValidation
    data object TooShort : RecoveryPassphraseValidation
    data object TooLong : RecoveryPassphraseValidation
    data object ConfirmationMismatch : RecoveryPassphraseValidation
}
```

`RecoveryPassphraseValidation` lives in `BackupModels.kt` so
`feature:more` can render a validation result without depending on
`core:domain`.

- Produces `BackupPolicy`, `RecoveryPassphrasePolicy`,
  `BackupJournalEntry`, `BackupJournalReader`, `BackupCoordinator`, and
  `AndroidBackupStatusSource` from `BackupContracts.kt`.

```kotlin
enum class BackupMutationKind {
    LEGACY,
    UPSERT,
    DELETE,
}

data class BackupJournalEntry(
    val operationId: String,
    val vaultId: VaultId,
    val generation: BackupGeneration,
    val sequence: Int,
    val payloadFormatVersion: Int,
    val mutationKind: BackupMutationKind,
    val objectId: String,
    val objectType: String,
    val payload: ByteArray,
    val revision: Revision,
)

interface BackupJournalReader {
    suspend fun currentGeneration(vaultId: VaultId): BackupGeneration
    suspend fun entriesAfter(
        vaultId: VaultId,
        generation: BackupGeneration,
        limit: Int,
    ): List<BackupJournalEntry>
}

interface BackupCoordinator {
    suspend fun request()
}

fun interface BackupCaptureSource<T> {
    suspend fun capture(): T
}

interface AndroidBackupStatusSource {
    val status: StateFlow<AndroidBackupStatus>
}
```

- [ ] **Step 1: Write failing policy and model tests**

Create tests that assert:

```kotlin
@Test
fun snapshotBecomesDueAtFiveThousandOperations() {
    assertTrue(
        BackupPolicy.requiresSnapshot(
            operationsSinceBase = 5_000,
            baseProducedAt = Instant.parse("2026-07-20T00:00:00Z"),
            now = Instant.parse("2026-07-20T01:00:00Z"),
        ),
    )
}

@Test
fun snapshotBecomesDueAtSevenDays() {
    assertTrue(
        BackupPolicy.requiresSnapshot(
            operationsSinceBase = 1,
            baseProducedAt = Instant.parse("2026-07-20T00:00:00Z"),
            now = Instant.parse("2026-07-27T00:00:00Z"),
        ),
    )
}

@Test
fun passphraseUsesCodePointsWithoutTrimmingOrNormalising() {
    val value = "1234567890😀x"
    assertEquals(
        RecoveryPassphraseValidation.Valid,
        RecoveryPassphrasePolicy.validate(value, value),
    )
    assertEquals(
        RecoveryPassphraseValidation.TooShort,
        RecoveryPassphrasePolicy.validate(" 123456789 ", " 123456789 "),
    )
    assertEquals(
        RecoveryPassphraseValidation.ConfirmationMismatch,
        RecoveryPassphrasePolicy.validate("12345678901é", "12345678901e\u0301"),
    )
}
```

Also assert non-negative `BackupGeneration`, the exact 24 MiB constant, the
`16 MiB - 33` segment plaintext constant, deterministic segment splitting by
generation/sequence, and retention of only current/previous recovery bases.

- [ ] **Step 2: Run the focused test to verify RED**

Run:

```bash
./gradlew :core:domain:testDebugUnitTest \
  --tests '*BackupPolicyTest' --stacktrace
```

Expected: compilation fails because the backup contracts and policies do not
exist.

- [ ] **Step 3: Add the feature-safe status models and pure policies**

Implement the interfaces listed above. Use:

```kotlin
object BackupPolicy {
    const val SNAPSHOT_OPERATION_INTERVAL = 5_000
    val SNAPSHOT_TIME_INTERVAL: Duration = Duration.ofDays(7)
    const val MAX_PORTABLE_PACKAGE_BYTES = 24L * 1024 * 1024
    const val MAX_SEGMENT_PLAINTEXT_BYTES =
        16 * 1024 * 1024 - 33

    fun requiresSnapshot(
        operationsSinceBase: Int,
        baseProducedAt: Instant,
        now: Instant,
    ): Boolean =
        operationsSinceBase >= SNAPSHOT_OPERATION_INTERVAL ||
            !now.isBefore(baseProducedAt.plus(SNAPSHOT_TIME_INTERVAL))
}
```

`RecoveryPassphrasePolicy.validate` must count
`value.codePointCount(0, value.length)`, compare confirmation with exact
`String` equality, and return one of `Valid`, `TooShort`, `TooLong`, or
`ConfirmationMismatch`. It must not call `trim`, `lowercase`, or Unicode
normalisation.

- [ ] **Step 4: Remove unused product sync surfaces**

Make these exact removals:

- remove `StorageMode`, `SyncPhase`, `SyncBlockReason`, `SyncState`,
  `SyncReason`, and model `SyncOperation`;
- remove `Vault.storageMode`;
- remove `HomeSnapshot.syncState`;
- remove `SyncCoordinator`;
- delete the unused generic `CloudObjectStore`, `EncryptedSource`,
  `CloudObject`, and `ChangePage`;
- delete `SyncHealthChip` from `core:designsystem`;
- remove the **On this device** chip from Home; do not replace it with a
  backup indicator;
- keep `core:sync` framing, `HybridLogicalClock`, and `MergeRules` as
  provider-independent internal foundations;
- remove the unused `androidx.work` dependency from `core:data`; and
- update `CLAUDE.md` so new mutations require backup-journal atomicity and the
  old outbox table is explicitly migration-only.

Keep the physical `SyncOperationEntity` and table for Task 2.

- [ ] **Step 5: Run affected model/domain/UI compilation and tests**

Run:

```bash
./gradlew :core:model:testDebugUnitTest \
  :core:domain:testDebugUnitTest \
  :core:designsystem:testDebugUnitTest \
  :feature:home:testDebugUnitTest \
  :core:data:testDebugUnitTest \
  --stacktrace
```

Expected: PASS, including `BackupPolicyTest`, with no remaining Kotlin
reference to the removed product sync types.

- [ ] **Step 6: Verify the legacy-type scan**

Run:

```bash
rg -n \
  'StorageMode|SyncState|SyncReason|SyncCoordinator|SyncHealthChip|CloudObjectStore' \
  core app feature --glob '*.kt'
```

Expected: no matches. `SyncOperationEntity` remains intentionally outside
this expression.

- [ ] **Step 7: Commit Task 1**

```bash
git add CLAUDE.md core/model core/domain core/designsystem feature/home \
  core/data/build.gradle.kts \
  core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt
git commit -m "refactor: replace product sync contracts with backup state"
```

### Task 2: Add the Additive Room v6 Backup Schema and Legacy Migration

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupEntities.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupDaos.kt`
- Create:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/VaultDatabaseMigrationInstrumentedTest.kt`
- Create:
  `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/6.json`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/Entities.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
- Modify: `core/data/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`

**Interfaces:**

- Consumes: the unchanged v5 `sync_operations` table and
  `BackupGeneration`.
- Produces:

```kotlin
@Entity(
    tableName = "backup_journal",
    indices = [
        Index(
            value = ["vaultId", "generation", "sequence"],
            unique = true,
        ),
    ],
)
data class BackupJournalEntity(
    @PrimaryKey val operationId: String,
    val vaultId: String,
    val generation: Long,
    val sequence: Int,
    val payloadFormatVersion: Int,
    val mutationKind: String,
    val objectId: String,
    val objectType: String,
    val payload: ByteArray,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val sourceDeviceId: String,
)

@Entity(tableName = "backup_state")
data class BackupStateEntity(
    @PrimaryKey val vaultId: String,
    val currentGeneration: Long,
    val lastVerifiedSnapshotGeneration: Long?,
    val currentBaseObjectId: String?,
    val previousBaseObjectId: String?,
    val latestVerifiedSegmentGeneration: Long?,
    val portablePackageGeneration: Long?,
    val portablePackageBytes: Long?,
    val portablePackageProducedAtEpochMillis: Long?,
    val packageState: String,
    val failureCategory: String?,
    val recoveryEnvelopeReady: Boolean,
    val legacyOutboxCoveredAtGeneration: Long?,
    val snapshotCreatedAtEpochMillis: Long?,
)

@Entity(tableName = "vault_recovery_envelope")
data class VaultRecoveryEnvelopeEntity(
    @PrimaryKey val vaultId: String,
    val formatVersion: Int,
    val kdfAlgorithm: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val salt: ByteArray,
    val nonce: ByteArray,
    val wrappedKeyset: ByteArray,
)
```

- Produces read-only `LegacySyncOperationDao`, mutable `BackupJournalDao`,
  `BackupStateDao`, `VaultRecoveryEnvelopeDao`, and read-only
  `BackupCaptureDao`.

- [ ] **Step 1: Add Room migration-test support**

Add:

```toml
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
```

and:

```kotlin
androidTestImplementation(libs.room.testing)
```

- [ ] **Step 2: Write the failing v5→v6 migration tests**

Use `MigrationTestHelper` with the exported v5 schema. Insert:

- one vault with `storageMode = "DRIVE_PRIMARY"`;
- three legacy rows deliberately out of insertion order;
- equal wall/logical revisions separated by device ID and operation ID;
- one row with `uploadedAtEpochMillis` set;
- distinct payload blobs including zero bytes.

Assert after migration:

```kotlin
assertEquals(
    listOf("op-a", "op-b", "op-c"),
    journalRows.map { it.operationId },
)
assertEquals(listOf(1L, 2L, 3L), journalRows.map { it.generation })
assertTrue(journalRows.all { it.sequence == 0 })
assertTrue(journalRows.all { it.payloadFormatVersion == 0 })
assertTrue(journalRows.all { it.mutationKind == "LEGACY" })
assertEquals(3L, state.currentGeneration)
assertNull(state.legacyOutboxCoveredAtGeneration)
assertEquals("LOCAL", migratedStorageMode)
assertEquals(3, legacyTableRowCount)
```

Compare operation IDs, object IDs/types, payload bytes, revision values, and
source-device IDs with the pre-migration fixture. Also test:

- no legacy rows produces generation `0`;
- more than one vault plus legacy rows fails closed because v5 operations have
  no vault ID;
- more than one vault with no legacy rows creates independent generation-zero
  state rows; and
- all three new tables match Room's exported v6 schema.

- [ ] **Step 3: Run the migration test to verify RED**

After auditing that exactly one disposable device is attached, run:

```bash
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.core.data.VaultDatabaseMigrationInstrumentedTest \
  --stacktrace
```

Expected: compilation fails because v6 entities and `MIGRATION_5_6` do not
exist.

- [ ] **Step 4: Add v6 entities, DAOs, and read-only legacy access**

Keep `SyncOperationEntity` mapped to `sync_operations`, but expose only:

```kotlin
@Dao
interface LegacySyncOperationDao {
    @Query(
        """
        SELECT * FROM sync_operations
        ORDER BY revisionWallMillis, revisionLogical, deviceId, id
        """,
    )
    suspend fun allForAudit(): List<SyncOperationEntity>

    @Query("SELECT COUNT(*) FROM sync_operations")
    suspend fun count(): Int
}
```

`BackupJournalDao` must provide ordered reads after a generation and count
reads. `BackupStateDao` must provide `observe(vaultId): Flow<BackupStateEntity>`
and compare-and-update methods used by later tasks. No DAO may update or
delete `backup_journal`.

Use these exact DAO names:

```kotlin
interface BackupJournalDao {
    suspend fun insert(entity: BackupJournalEntity)
    suspend fun after(
        vaultId: String,
        generation: Long,
        limit: Int,
    ): List<BackupJournalEntity>
    suspend fun between(
        vaultId: String,
        firstGeneration: Long,
        lastGeneration: Long,
    ): List<BackupJournalEntity>
    suspend fun countAfter(vaultId: String, generation: Long): Int
}

interface BackupStateDao {
    suspend fun get(vaultId: String): BackupStateEntity?
    suspend fun require(vaultId: String): BackupStateEntity
    fun observe(vaultId: String): Flow<BackupStateEntity>
    suspend fun insert(entity: BackupStateEntity)
    suspend fun update(entity: BackupStateEntity)
}

interface VaultRecoveryEnvelopeDao {
    suspend fun get(vaultId: String): VaultRecoveryEnvelopeEntity?
    suspend fun upsert(entity: VaultRecoveryEnvelopeEntity)
    suspend fun delete(vaultId: String): Int
}

interface BackupStateStore {
    fun observe(vaultId: VaultId): Flow<BackupStateEntity>
    suspend fun get(vaultId: VaultId): BackupStateEntity?
    suspend fun update(entity: BackupStateEntity)
}

interface RecoveryEnvelopeStore {
    suspend fun get(vaultId: VaultId): VaultRecoveryEnvelopeEntity?
    suspend fun upsert(entity: VaultRecoveryEnvelopeEntity)
    suspend fun delete(vaultId: VaultId)
}
```

Implement `RoomBackupStateStore` and `RoomRecoveryEnvelopeStore` as thin
adapters over these DAOs in `BackupDaos.kt`; later app code consumes only the
store interfaces.

- [ ] **Step 5: Implement `MIGRATION_5_6`**

Perform these operations in Room's migration transaction:

```kotlin
val vaultIds = mutableListOf<String>()
db.query("SELECT id FROM vaults ORDER BY id").use { cursor ->
    while (cursor.moveToNext()) vaultIds += cursor.getString(0)
}
val operationCount = db.query(
    "SELECT COUNT(*) FROM sync_operations",
).use { cursor ->
    check(cursor.moveToFirst())
    cursor.getLong(0)
}
check(operationCount == 0L || vaultIds.size == 1) {
    "Legacy backup operations cannot be assigned to multiple vaults"
}
```

Create the three tables and unique index with the exact Room column
nullability. Read old rows using:

```sql
SELECT id, deviceId, objectId, objectType, encryptedPayload,
       revisionWallMillis, revisionLogical
FROM sync_operations
ORDER BY revisionWallMillis, revisionLogical, deviceId, id
```

Insert them with generation `1..n`, sequence `0`, payload format `0`, and
mutation kind `LEGACY`. Insert one `backup_state` row per vault, normalise
`vaults.storageMode` to `LOCAL`, and set `schemaVersion = 6`. Do not update or
delete any legacy operation.

- [ ] **Step 6: Register v6 and generate the exported schema**

Set `VaultDatabase.version = 6`, add all three entities/DAOs, and register
`MIGRATION_5_6` after `MIGRATION_4_5`. Run:

```bash
./gradlew :core:data:compileDebugKotlin \
  :core:data:compileDebugAndroidTestKotlin --stacktrace
```

Expected: PASS and
`core/data/schemas/app.opentasks.core.data.db.VaultDatabase/6.json` exists.

- [ ] **Step 7: Rerun migration and encrypted restart coverage**

Run the focused migration class from Step 3, then:

```bash
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.core.data.RoomVaultRepositoryInstrumentedTest \
  --stacktrace
```

Expected: both commands PASS on the sole disposable device. Existing
encrypted restart, template, recurrence, reminder, and time-entry tests remain
green.

- [ ] **Step 8: Commit Task 2**

```bash
git add gradle/libs.versions.toml core/data/build.gradle.kts \
  core/data/src/main/kotlin/app/opentasks/core/data/db \
  core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupEntities.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupDaos.kt \
  core/data/src/androidTest/kotlin/app/opentasks/core/data/VaultDatabaseMigrationInstrumentedTest.kt \
  core/data/schemas/app.opentasks.core.data.db.VaultDatabase/6.json
git commit -m "feat: add room v6 backup journal migration"
```

### Task 3: Make Every New Mutation and Journal Entry Atomic

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordV1.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupMutationCodec.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomBackupJournalSession.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/InMemoryBackupJournal.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/BackupMutationCodecTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/InMemoryBackupJournalTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
- Modify:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`
- Modify:
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`

**Interfaces:**

- Consumes: v6 DAOs, `BackupJournalEntry`, and every existing
  `DomainCommand`.
- Produces:

```kotlin
@Serializable
data class BackupFieldV1(
    val name: String,
    val type: BackupFieldType,
    val value: String?,
)

@Serializable
data class BackupRecordV1(
    val family: BackupRecordFamily,
    val identity: List<String>,
    val fields: List<BackupFieldV1>,
)

@Serializable
data class BackupMutationPayloadV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val mutationKind: BackupMutationKind,
    val record: BackupRecordV1?,
    val deletedFamily: BackupRecordFamily?,
    val deletedIdentity: List<String>?,
)

enum class BackupFieldType {
    STRING,
    LONG,
    INT,
    BOOLEAN,
    BYTES,
    NULL,
}

enum class BackupRecordFamily {
    VAULT,
    WORKSPACE,
    MEMBER,
    PROJECT,
    WORKFLOW_STATUS,
    MILESTONE,
    TASK,
    CHECKLIST_ITEM,
    TASK_DEPENDENCY,
    TAG,
    TASK_TAG,
    REMINDER,
    ATTACHMENT,
    ACTIVITY_ENTRY,
    TIME_ENTRY,
    TEMPLATE,
    SAVED_VIEW,
    TOMBSTONE,
}

fun interface BackupJournalAppendBoundary {
    suspend fun insert(
        dao: BackupJournalDao,
        entity: BackupJournalEntity,
    )
}
```

Exactly one of `record` or `(deletedFamily, deletedIdentity)` is populated.
Non-null scalar values use canonical decimal/boolean/string text; `BYTES`
uses unpadded Base64; `NULL` has a null `value`.

- [ ] **Step 1: Write strict mutation-codec RED tests**

Cover:

- canonical encode/decode for an upsert and deletion;
- wrong field order, duplicate JSON key, duplicate record field, unknown key,
  invalid UTF-8, padded/non-canonical Base64, future format, weakened minimum
  reader, wrong identity, and oversized payload rejection;
- caller input ownership and decoder-owned buffer clearing; and
- every mutable record family currently written by a `DomainCommand`.

Use canonical-byte equality after decoding to reject reordered or duplicate
input:

```kotlin
val decoded = json.decodeFromString<BackupMutationPayloadV1>(text)
validate(decoded)
require(source.contentEquals(encode(decoded))) {
    "Backup mutation payload is not canonical"
}
```

- [ ] **Step 2: Run mutation-codec tests to verify RED**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*BackupMutationCodecTest' --stacktrace
```

Expected: compilation fails because the v1 mutation codec does not exist.

- [ ] **Step 3: Implement the record schema and mutation codec**

Define exact ordered field schemas for these Room-backed families:

| Family | Ordered fields |
|---|---|
| `VAULT` | `id`, `createdAtEpochMillis`, `schemaVersion`, `cryptoVersion`, `minimumReaderVersion` |
| `WORKSPACE` | `id`, `vaultId`, `ownerId`, `name` |
| `MEMBER` | `id`, `displayName` |
| `PROJECT` | `id`, `workspaceId`, `name`, `summary`, `health`, `dueDate`, `completedTasks`, `totalTasks`, `archivedAtEpochMillis`, `revisionWallMillis`, `revisionLogical`, `revisionDeviceId` |
| `WORKFLOW_STATUS` | `id`, `projectId`, `name`, `semanticStatus`, `rank`, `archivedAtEpochMillis`, `revisionWallMillis`, `revisionLogical`, `revisionDeviceId` |
| `MILESTONE` | `id`, `projectId`, `name`, `dueDate`, `completedAtEpochMillis`, `revisionWallMillis`, `revisionLogical`, `revisionDeviceId` |
| `TASK` | every `TaskEntity` column in declaration order |
| `CHECKLIST_ITEM` | `id`, `taskId`, `text`, `completed`, `rank` |
| `TASK_DEPENDENCY` | `taskId`, `dependsOnTaskId`, `revisionWallMillis`, `revisionLogical`, `revisionDeviceId` |
| `TAG` | `id`, `workspaceId`, `name` |
| `TASK_TAG` | `taskId`, `tagId`, `present`, `revisionWallMillis`, `revisionLogical`, `revisionDeviceId` |
| `REMINDER` | `id`, `taskId`, `triggerAtEpochMillis`, `zoneId`, `precise` |
| `ATTACHMENT` | `id`, `taskId`, `displayNameCiphertext`, `mimeType`, `byteCount`, `contentHash`, `keepOffline` |
| `ACTIVITY_ENTRY` | `id`, `taskId`, `projectId`, `kind`, `bodyCiphertext`, `createdAtEpochMillis` |
| `TIME_ENTRY` | `id`, `taskId`, `deviceId`, `startedAtEpochMillis`, `stoppedAtEpochMillis`, `noteCiphertext` |
| `TEMPLATE` | `id`, `workspaceId`, `name`, `encryptedPayload`, `revisionWallMillis`, `revisionLogical`, `revisionDeviceId` |
| `SAVED_VIEW` | `id`, `workspaceId`, `name`, `encryptedQuery` |
| `TOMBSTONE` | `objectId`, `objectType`, `deletedAtEpochMillis`, `purgeAfterEpochMillis`, `revisionWallMillis`, `revisionLogical`, `revisionDeviceId` |

Composite identities are:

- dependency: `[taskId, dependsOnTaskId]`;
- task tag: `[taskId, tagId]`;
- tombstone: `[objectId, objectType]`; and
- every other family: its single primary-key ID.

Validate field count, field names/types, identity agreement, identifier and
string bounds, enum values, date/zone syntax, non-negative counts/sizes, and
revision values before returning decoded content.

- [ ] **Step 4: Write atomic generation RED tests**

Add instrumented tests that prove:

```kotlin
val before = database.backupStateDao().require("vault-primary")
val result = repository.execute(command)
val after = database.backupStateDao().require("vault-primary")
val rows = database.backupJournalDao()
    .between(after.currentGeneration, after.currentGeneration)

assertTrue(result is CommandResult.Success)
assertEquals(before.currentGeneration + 1, after.currentGeneration)
assertEquals(rows.indices.toList(), rows.map { it.sequence })
```

Cover:

- one-row task edit;
- project creation with five workflow rows;
- workflow reorder with two rows;
- template instantiation with project/workflow/milestone/tag/task rows;
- task purge with relation deletions and tombstone upsert;
- multi-task expiry purge under one generation;
- idempotent template delete and unchanged workflow move;
- representative rejected command; and
- injected append failure rolling product rows, generation, and journal back.

Add matching in-memory tests for one-row, multi-row, rejected, idempotent, and
rollback semantics.

- [ ] **Step 5: Run atomicity tests to verify RED**

Run the in-memory class:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*InMemoryBackupJournalTest' --stacktrace
```

Then run the focused Room methods on the sole disposable device:

```bash
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.core.data.RoomVaultRepositoryInstrumentedTest \
  --stacktrace
```

Expected: new assertions fail because commands still write the legacy outbox
and do not allocate v6 generations.

- [ ] **Step 6: Centralise one lazy journal session per command**

Wrap Room command dispatch as:

```kotlin
override suspend fun execute(command: DomainCommand): CommandResult {
    ready.await()
    return writeMutex.withLock {
        database.withTransaction {
            val session = RoomBackupJournalSession(
                vaultId = VAULT_ID,
                stateDao = database.backupStateDao(),
                journalDao = database.backupJournalDao(),
                mutationCodec = BackupMutationCodec,
                operationId = { UUID.randomUUID().toString() },
            )
            dispatch(command, session)
        }
    }
}
```

`session.append` allocates `currentGeneration + 1` only on its first call and
then assigns sequence `0..n-1`. Nested `withTransaction` calls may remain
during the focused refactor because Room nests them in the outer transaction.
The production append boundary calls `dao.insert(entity)`; tests inject a
boundary that throws before insert to prove the enclosing Room transaction
rolls back. No command writes `sync_operations`.

Replace each old operation builder with complete v1 after-images or deletion
markers. Every physically deleted checklist, dependency, task-tag, reminder,
attachment, activity, time-entry, template, task, milestone, and workflow row
gets a deletion marker unless a tombstone's documented cascade is the only
recovery event. Project/template creation emits every inserted row. Workflow
reorder emits both changed statuses.

- [ ] **Step 7: Align the in-memory journal**

Give `InMemoryVaultRepository` an internal
`InMemoryBackupJournal` dependency. It must:

- diff the pre-command and post-command logical records;
- allocate only when the diff is non-empty;
- encode the same v1 record families as Room;
- emit stable identity ordering and sequence values; and
- restore the pre-command snapshot, generation, and rows if an injected
  journal failure occurs.

Keep this test seam internal to `core:data`; do not expose journal internals to
feature modules.

- [ ] **Step 8: Prove the legacy table is read-only**

Run:

```bash
rg -n \
  'syncOperationDao|INSERT INTO sync_operations|UPDATE sync_operations|DELETE FROM sync_operations' \
  core/data/src/main --glob '*.kt'
```

Expected: no runtime write path. The only permitted legacy references are its
entity, read-only audit DAO, and `MIGRATION_5_6` source query.

- [ ] **Step 9: Run complete Data tests**

Run:

```bash
./gradlew :core:data:testDebugUnitTest --stacktrace
```

Expected: PASS for mutation codec, in-memory journal, existing repository
behaviour, authenticated objects, templates, and mappers.

- [ ] **Step 10: Commit Task 3**

```bash
git add core/data/src/main core/data/src/test \
  core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt
git commit -m "feat: journal local mutations by generation"
```

### Task 4: Freeze Complete Snapshot and Operation-Segment Payload v1

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupPayloadCodec.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomBackupCaptureSource.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/BackupSnapshotCodecTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/BackupOperationSegmentCodecTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/BackupPayloadGoldenTest.kt`
- Create: `scripts/generate-stage2-backup-v1-fixtures.mjs`
- Create:
  `core/data/src/test/resources/backup-format/v1/snapshot.json`
- Create:
  `core/data/src/test/resources/backup-format/v1/operation-segment.json`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupDaos.kt`

**Interfaces:**

- Consumes: `BackupRecordV1`, v6 journal rows, and exact Room capture queries.
- Produces:

```kotlin
@Serializable
data class BackupSnapshotPayloadV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val vaultId: String,
    val coveredGeneration: Long,
    val records: List<BackupRecordV1>,
)

@Serializable
data class BackupSegmentEntryV1(
    val operationId: String,
    val generation: Long,
    val sequence: Int,
    val objectId: String,
    val objectType: String,
    val revisionWallMillis: Long,
    val revisionLogical: Int,
    val sourceDeviceId: String,
    val payloadBase64: String,
)

@Serializable
data class BackupOperationSegmentPayloadV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val vaultId: String,
    val firstGeneration: Long,
    val lastGeneration: Long,
    val entries: List<BackupSegmentEntryV1>,
    val entryCount: Int,
)

data class StructuredBackupCapture(
    val vaultId: VaultId,
    val generation: BackupGeneration,
    val records: List<BackupRecordV1>,
)
```

- Produces
  `RoomBackupCaptureSource : BackupCaptureSource<StructuredBackupCapture>`,
  where immutable entity copies and `currentGeneration` are read in one short
  Room transaction and encoding happens after it returns.

- [ ] **Step 1: Write complete snapshot RED tests**

Build a fixture containing at least one record from all 18 backup families.
Assert:

- canonical bytes and deterministic family/identity order;
- unpadded Base64 for every byte field;
- generation and vault identity;
- exactly 18 family counts;
- 100,000-record acceptance and 100,001 rejection;
- `64 MiB - 33` plaintext acceptance and one-byte-over rejection;
- unknown/missing/duplicate/reordered fields;
- duplicate identities;
- invalid foreign keys and ownership;
- invalid workflow semantics/ranks;
- task parent and dependency cycles;
- invalid recurrence/date/instant/zone/count/size values;
- invalid attachment metadata without reading attachment bytes; and
- invalid UTF-8 and future versions.

- [ ] **Step 2: Write operation-segment RED tests**

Assert generation/sequence order, inclusive range agreement, unique operation
IDs, payload canonicality, 10,000-entry acceptance, 10,001 rejection,
`16 MiB - 33` plaintext acceptance, one-byte-over rejection, and object
identity:

```kotlin
assertEquals(
    "segment:41:53",
    BackupPayloadIdentities.segmentObjectId(
        BackupGeneration(41),
        BackupGeneration(53),
    ),
)
```

Reject a segment containing `payloadFormatVersion = 0`; legacy entries are
covered by the initial baseline and never become post-base deltas.

- [ ] **Step 3: Run both codec tests to verify RED**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*BackupSnapshotCodecTest' \
  --tests '*BackupOperationSegmentCodecTest' \
  --stacktrace
```

Expected: compilation fails because snapshot/segment codecs and capture DTOs
do not exist.

- [ ] **Step 4: Implement exact consistent Room capture**

Add ordered `SELECT *` queries for every backup family. Use primary-key order;
for composite identities use every key column. `RoomBackupCaptureSource`
must execute:

```kotlin
database.withTransaction {
    val state = database.backupStateDao().require(vaultId.value)
    StructuredBackupCapture(
        vaultId = vaultId,
        generation = BackupGeneration(state.currentGeneration),
        records = database.backupCaptureDao().allRecords(vaultId.value),
    )
}
```

`allRecords` here is a Kotlin DAO composition method that maps the 18 exact
ordered query results to immutable `BackupRecordV1` values. Do not use
`WorkspaceSnapshot`, flows, encoded Room entities, journal rows, state rows,
key preferences, credentials, cache, or attachment files.

- [ ] **Step 5: Implement strict canonical payload codecs**

Use a shared strict UTF-8 decoder configured with
`CodingErrorAction.REPORT`. Configure `Json` with defaults, explicit nulls,
unknown-key rejection, no leniency, and no trailing comma. Decode, validate,
re-encode, and require exact source-byte equality.

Snapshot validation must build identity maps and validate every relation
without mutating input. Segment validation must decode each embedded local
mutation payload and compare its object identity to the enclosing entry.

- [ ] **Step 6: Generate independent v1 fixtures**

The Node script must use only built-in `crypto`, `fs`, and `path`. It writes
canonical snapshot and segment plaintext independently of Kotlin, including
expected UTF-8 hex and SHA-256.

Run:

```bash
node scripts/generate-stage2-backup-v1-fixtures.mjs
```

Then make `BackupPayloadGoldenTest` load both resources and assert exact bytes,
identity, counts, and digests.

- [ ] **Step 7: Run payload and independent-vector tests**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*BackupSnapshotCodecTest' \
  --tests '*BackupOperationSegmentCodecTest' \
  --tests '*BackupPayloadGoldenTest' \
  --stacktrace
```

Expected: PASS.

Regenerate and prove no diff:

```bash
node scripts/generate-stage2-backup-v1-fixtures.mjs
git diff --exit-code -- core/data/src/test/resources/backup-format/v1
```

Expected: both commands exit `0`.

- [ ] **Step 8: Commit Task 4**

```bash
git add scripts/generate-stage2-backup-v1-fixtures.mjs \
  core/data/src/main/kotlin/app/opentasks/core/data/backup \
  core/data/src/test/kotlin/app/opentasks/core/data/backup \
  core/data/src/test/resources/backup-format/v1
git commit -m "feat: freeze local backup payload format"
```

### Task 5: Produce and Retain Verified Local Recovery Objects

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/LocalBackupObjectStore.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/DefaultBackupCoordinator.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/LocalBackupObjectStoreTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/DefaultBackupCoordinatorTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupDaos.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/LocalVaultRepositoryFactory.kt`

**Interfaces:**

- Consumes: `BackupCaptureSource<StructuredBackupCapture>`,
  `BackupSnapshotCodec`,
  `BackupOperationSegmentCodec`, `AuthenticatedCloudObjectCodec`,
  `VaultContentKeyStore`, and v6 state/journal DAOs.
- Produces:

```kotlin
data class LocalBackupCandidate(
    val objectId: String,
    val file: File,
    val byteCount: Long,
)

interface LocalBackupObjectStore {
    fun writeCandidate(
        objectId: String,
        frame: ByteArray,
    ): LocalBackupCandidate

    fun commitSnapshot(
        candidate: LocalBackupCandidate,
        previousObjectId: String?,
    )

    fun commitSegment(candidate: LocalBackupCandidate)

    fun open(objectId: String): InputStream
    fun length(objectId: String): Long
    fun prune(retainedObjectIds: Set<String>)
}
```

- Produces `DefaultBackupCoordinator : BackupCoordinator`.

The production store receives only an injected
`noBackupFilesDir/backup/v1` root. It never derives Android paths itself.

- [ ] **Step 1: Write local-object lifecycle RED tests**

Use a fresh temporary directory per test. Assert:

- candidates are not visible as current/previous/segments;
- commit uses a same-directory temporary file and atomic replacement;
- a verified current base moves to previous only after replacement commit;
- failed write/flush/move leaves prior current and previous bytes unchanged;
- segment names are exactly `segment-<first>-<last>.otf`;
- snapshot names are exactly `snapshot-<generation>.otf`;
- pruning retains current, previous, and segments needed after either base;
- temporary and interrupted files are ignored on reopen; and
- no path can escape the injected root.

Expected layout:

```text
backup/v1/
    current/snapshot-<generation>.otf
    previous/snapshot-<generation>.otf
    segments/segment-<first>-<last>.otf
    staging/
```

- [ ] **Step 2: Write coordinator RED tests**

Use real `TinkVaultCrypto`, `DefaultAuthenticatedCloudObjectCodec`, strict
payload codecs, an in-memory capture/journal/state source, and a temporary
object store. Assert:

- first request produces and verifies a complete baseline;
- `legacyOutboxCoveredAtGeneration` advances only after baseline
  verification;
- legacy format-0 rows are never emitted as segments;
- later local rows form contiguous ordered segments;
- checkpoint does not advance on encode, encrypt, write, checksum,
  authentication, strict-decode, identity, or source-comparison failure;
- prior verified objects survive each failure;
- 5,000 operations or seven days rotates a new complete base;
- newer requests coalesce while work is running;
- state advancing during capture commits the captured generation and schedules
  another pass;
- cancellation clears owned arrays and leaves state unchanged; and
- local edit/journal operations remain available after backup failure.

- [ ] **Step 3: Run lifecycle/coordinator tests to verify RED**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*LocalBackupObjectStoreTest' \
  --tests '*DefaultBackupCoordinatorTest' \
  --stacktrace
```

Expected: compilation fails because the local store and coordinator do not
exist.

- [ ] **Step 4: Implement atomic local-object storage**

Validate every object ID against:

```kotlin
private val SNAPSHOT_ID = Regex("snapshot:[0-9]+")
private val SEGMENT_ID = Regex("segment:[0-9]+:[0-9]+")
```

Write to `staging`, flush the `FileOutputStream` descriptor, close it, and
move within the same filesystem using `ATOMIC_MOVE` and `REPLACE_EXISTING`.
If atomic move is unavailable, fail with typed local file I/O; do not silently
fall back to a non-atomic replacement.

- [ ] **Step 5: Implement encrypt-readback-verify-checkpoint ordering**

For snapshots:

```kotlin
val capture = captureSource.capture()
val plaintext = snapshotCodec.encode(capture)
val frame = try {
    authenticatedCodec.encrypt(
        identity = CloudHeaderIdentity(
            family = CloudObjectFamily.SNAPSHOT,
            schemaVersion = 1,
            cryptoVersion = 1,
            minimumReaderVersion = 1,
            vaultId = capture.vaultId.value,
            objectId = "snapshot:${capture.generation.value}",
        ),
        plaintext = plaintext,
        key = key,
    )
} finally {
    plaintext.fill(0)
}
```

Write the candidate, reopen it, call authenticated decode, strictly decode the
owned plaintext, and compare vault, object ID, generation, canonical records,
and source counts. Only then commit the file and state checkpoint.

Segments follow the same sequence and compare every operation ID,
generation/sequence tuple, and payload byte before checkpoint advancement.

- [ ] **Step 6: Add single-flight coalescing**

Use one `Mutex` and a pending flag:

```kotlin
override suspend fun request() {
    mutex.withLock {
        if (running) {
            pending = true
            return
        }
        running = true
    }
    try {
        do {
            mutex.withLock { pending = false }
            produceRequiredObjects()
        } while (mutex.withLock { pending })
    } finally {
        mutex.withLock { running = false }
    }
}
```

The application supplies the coroutine scope and debounce in Task 8. This
coordinator has no WorkManager or network dependency.

- [ ] **Step 7: Run complete local-backup tests**

Run:

```bash
./gradlew :core:data:testDebugUnitTest --stacktrace
```

Expected: PASS, including Stage 1 authenticated-object vectors and all new
snapshot, segment, store, and coordinator coverage.

- [ ] **Step 8: Commit Task 5**

```bash
git add core/data/src/main/kotlin/app/opentasks/core/data/backup \
  core/data/src/test/kotlin/app/opentasks/core/data/backup \
  core/data/src/main/kotlin/app/opentasks/core/data/LocalVaultRepositoryFactory.kt
git commit -m "feat: verify and retain local recovery objects"
```

### Task 6: Prepare a Verified Recovery Envelope

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RecoveryEnvelopeCodec.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/RecoveryEnvelopeCodecTest.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/backup/RecoveryEnvelopePreparer.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/RecoveryEnvelopePreparerTest.kt`
- Modify:
  `core/crypto/src/main/kotlin/app/opentasks/core/crypto/VaultContentKeyStore.kt`
- Modify:
  `core/crypto/src/main/kotlin/app/opentasks/core/crypto/AndroidVaultContentKeyStore.kt`
- Modify:
  `core/crypto/src/androidTest/kotlin/app/opentasks/core/crypto/AndroidVaultContentKeyStoreInstrumentedTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupDaos.kt`

**Interfaces:**

- Consumes: `VaultCrypto.wrapForRecovery`, `VaultCrypto.unlock`, v6
  `VaultRecoveryEnvelopeDao`, and the local content-key store.
- Produces:

```kotlin
interface VaultContentKeyStore {
    fun getOrCreate(vaultId: VaultId): VaultKey
    fun openExisting(vaultId: VaultId): VaultKey
    fun replace(vaultId: VaultId, key: VaultKey)
    fun delete(vaultId: VaultId)
}

@Serializable
data class RecoveryEnvelopePayloadV1(
    val formatVersion: Int,
    val kdfAlgorithm: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val saltBase64: String,
    val nonceBase64: String,
    val wrappedKeysetBase64: String,
)

class PreparedRecoveryEnvelope internal constructor(
    val envelope: VaultKeyEnvelope,
    val canonicalBytes: ByteArray,
) : AutoCloseable
```

- Produces
  `RecoveryEnvelopePreparer.prepare(passphrase: CharArray):
  PreparedRecoveryEnvelope`. Preparation verifies but does not persist; Task 7
  commits it only with a verified portable package.

- [ ] **Step 1: Write `openExisting` fail-closed RED tests**

Add instrumented tests that prove:

- absent preference data makes `openExisting` fail without creating a
  preference or alias;
- complete preference data reopens the same content key;
- missing alias, incomplete preference, malformed Base64, wrong nonce, and
  authentication failure never call key generation;
- `getOrCreate` still performs the one allowed initial bootstrap; and
- concurrent opens do not replace the established key.

- [ ] **Step 2: Write envelope-codec and preparer RED tests**

Assert:

- exact canonical recovery-envelope bytes;
- algorithm exactly `ARGON2ID`;
- 16-byte salt, 12-byte nonce, 64 MiB memory, three iterations, parallelism
  one, fixed format `1`, and unpadded Base64;
- unknown/reordered/duplicate fields and weakened/future metadata rejection;
- passphrase array cleanup by the caller;
- `prepare` calls `openExisting`, never `getOrCreate` or `createKey`;
- wrong-passphrase and tampered-envelope verification fail;
- the unlocked candidate decrypts a random challenge encrypted by the
  established content key;
- candidate failure leaves DAO and package seams untouched; and
- all challenge, associated-data, plaintext, key, and candidate canonical
  buffers are cleared.

- [ ] **Step 3: Run focused tests to verify RED**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*RecoveryEnvelopeCodecTest' --stacktrace
./gradlew :app:testDebugUnitTest \
  --tests '*RecoveryEnvelopePreparerTest' --stacktrace
```

Expected: compilation fails because `openExisting`, the envelope codec, and
the preparer do not exist.

- [ ] **Step 4: Implement non-creating key open**

Refactor `AndroidVaultContentKeyStore` so:

```kotlin
override fun openExisting(vaultId: VaultId): VaultKey =
    synchronized(PROCESS_LOCK) {
        when (val stored = readPreferenceState(vaultId).toStoredEnvelope()) {
            StoredEnvelope.Absent ->
                error("The local vault-content key has not been initialised")
            is StoredEnvelope.Complete -> unwrap(vaultId, stored)
        }
    }
```

`unwrap` already requests an existing alias. Keep the current rollback and
alias-cleanup guarantees for `getOrCreate`, `replace`, and `delete`.

- [ ] **Step 5: Implement canonical envelope mapping and same-key proof**

`RecoveryEnvelopeCodec` maps entity, `VaultKeyEnvelope`, and canonical payload
without exposing key bytes. `RecoveryEnvelopePreparer`:

1. opens the established content key;
2. wraps it with the supplied passphrase;
3. unlocks the candidate with that passphrase;
4. encrypts a 32-byte random challenge with the established key;
5. decrypts it with the unlocked candidate and compares in constant time;
6. returns the verified candidate; and
7. closes both keys and clears every owned mutable buffer in `finally`.

Do not persist inside `prepare`.

- [ ] **Step 6: Run crypto, envelope, and preparer coverage**

Run:

```bash
./gradlew :core:crypto:testDebugUnitTest \
  :core:data:testDebugUnitTest \
  :app:testDebugUnitTest \
  --stacktrace
```

Then run only the key-store class on the sole disposable device:

```bash
./gradlew :core:crypto:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.core.crypto.AndroidVaultContentKeyStoreInstrumentedTest \
  --stacktrace
```

Expected: both commands PASS.

- [ ] **Step 7: Commit Task 6**

```bash
git add core/crypto core/data/src/main/kotlin/app/opentasks/core/data/backup \
  core/data/src/test/kotlin/app/opentasks/core/data/backup \
  app/src/main/kotlin/app/opentasks/backup/RecoveryEnvelopePreparer.kt \
  app/src/test/kotlin/app/opentasks/backup/RecoveryEnvelopePreparerTest.kt
git commit -m "feat: prepare verified vault recovery envelopes"
```

### Task 7: Encode, Verify, and Atomically Publish the Portable Package

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/PortableBackupCodec.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/PortableBackupCodecTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/PortableBackupGoldenTest.kt`
- Create:
  `core/data/src/test/resources/backup-format/v1/portable-package.json`
- Create:
  `app/src/main/kotlin/app/opentasks/backup/AndroidBackupFiles.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/backup/PortableBackupPublisher.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/PortableBackupPublisherTest.kt`
- Modify: `scripts/generate-stage2-backup-v1-fixtures.mjs`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupDaos.kt`

**Interfaces:**

- Consumes: canonical snapshot/envelope codecs, Stage 1 authenticated frames,
  strict v6 state, `RecoveryEnvelopePreparer`, and Android `AtomicFile`.
- Produces:

```kotlin
@Serializable
data class PortableBootstrapHeaderV1(
    val magic: String = "OPEN_TASKS_PORTABLE",
    val packageVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val vaultId: String,
    val generation: Long,
    val producedAtEpochMillis: Long,
    val recoveryEnvelope: RecoveryEnvelopePayloadV1,
    val manifestFrameLength: Long,
    val manifestFrameSha256: String,
    val snapshotFrameLength: Long,
    val snapshotFrameSha256: String,
    val totalPackageLength: Long,
)

@Serializable
data class PortableManifestPayloadV1(
    val packageVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val vaultId: String,
    val generation: Long,
    val producedAtEpochMillis: Long,
    val recoveryEnvelopeSha256: String,
    val snapshotObjectId: String,
    val snapshotFrameLength: Long,
    val snapshotFrameSha256: String,
    val recordCounts: List<BackupRecordFamilyCountV1>,
)

@Serializable
data class BackupRecordFamilyCountV1(
    val family: BackupRecordFamily,
    val count: Int,
)
```

- Produces `PortableBackupCodec.encode`, bounded `readBootstrap`, and
  `verifyComplete`.
- Produces `PortableBackupPublisher.prepare(passphrase: CharArray)` and
  `refresh()`.

- [ ] **Step 1: Write portable-codec RED tests**

Cover:

- canonical 4-byte bootstrap length, header, manifest frame, and snapshot
  frame;
- exact header/manifest/snapshot agreement;
- envelope digest and family counts;
- MANIFEST identity `portable-manifest:<generation>`;
- SNAPSHOT identity `snapshot:<generation>`;
- 16 KiB header bound;
- 24 MiB package acceptance and one-byte-over rejection;
- negative/overflowed/inconsistent frame and total lengths;
- checksum failure before AEAD;
- wrong key/passphrase, header/manifest substitution, frame swap, truncation,
  future version, unknown/reordered/duplicate field, invalid UTF-8, invalid
  recovery metadata, generation mismatch, and count mismatch;
- bounded bootstrap-only parsing without allocating declared frame sizes; and
- clearing manifest/snapshot plaintext and owned frame buffers on every exit.

- [ ] **Step 2: Run portable-codec tests to verify RED**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*PortableBackupCodecTest' --stacktrace
```

Expected: compilation fails because the portable codec does not exist.

- [ ] **Step 3: Implement portable package encode and full verification**

Encoding order is exact:

```text
4-byte big-endian bootstrap-header length
canonical UTF-8 bootstrap header
authenticated MANIFEST frame
authenticated SNAPSHOT frame
```

Before allocating, use checked `Long` arithmetic and enforce the declared
header, frame, family-ciphertext, and total package bounds. During verification:

1. parse the bounded bootstrap;
2. check exact total and frame lengths;
3. check both frame SHA-256 values;
4. authenticate and strictly decode manifest;
5. authenticate and strictly decode snapshot;
6. compare vault, generation, production time, envelope digest, object IDs,
   record counts, and package bytes; and
7. return only public verified metadata.

- [ ] **Step 4: Extend the independent fixture generator**

Use the fixed AES-256-GCM key, Tink prefix, and deterministic nonces already
used by the Stage 1 fixture pattern. Use fixed bounded recovery-envelope bytes;
the independent fixture verifies representation and package authentication,
not Argon2id derivation.

Write `portable-package.json` with canonical bootstrap/manifest/snapshot text,
frame hex, package hex, and SHA-256 values. `PortableBackupGoldenTest` must
verify exact Kotlin bytes and complete decode.

- [ ] **Step 5: Write publisher RED tests with an `AtomicFile` boundary**

Define:

```kotlin
interface AtomicPackageFile {
    fun startWrite(): OutputStream
    fun finishWrite(stream: OutputStream)
    fun failWrite(stream: OutputStream)
    fun openRead(): InputStream
    fun length(): Long
    fun delete(): Boolean
}
```

Test:

- initial preparation persists the envelope and `Ready` state only after
  complete temporary-package verification and file commit;
- refresh reuses the stored verified envelope without requesting passphrase;
- a captured older generation commits as `UpdatePending`;
- encode/crypto/verification/write/flush/finish failures retain the prior
  verified file and report it stale;
- over-24-MiB output withdraws the eligible final file and records
  `PACKAGE_TOO_LARGE`;
- corrupt self-produced output is withdrawn and scheduled for regeneration;
- database persistence failure after initial file commit deletes the new file;
- process-death states reconcile from valid final or prior `AtomicFile` bytes;
  and
- no exception text, path, checksum, ciphertext, envelope metadata, or
  private record enters persistent status.

- [ ] **Step 6: Implement exact Android paths and publisher ordering**

`AndroidBackupFiles` exposes only:

```kotlin
val eligiblePackage =
    File(context.filesDir, "android_backup/open_tasks_portable_v1.otb")
val localBackupRoot =
    File(context.noBackupFilesDir, "backup/v1")
val recoveryInbox =
    File(context.noBackupFilesDir, "recovery/incoming_android_v1.otb")
```

For initial preparation, require no active recovery envelope. Prepare the
candidate envelope, capture and build the complete package, write/flush/verify
the `AtomicFile` temporary bytes, commit the file, then persist envelope and
ready status. If persistence fails, delete the just-committed final file and
leave setup unprepared.

For refresh, use the stored envelope and established local content key. A
final file whose vault, envelope digest, and generation can be linked to local
state is self-produced and may reconcile after process death.

- [ ] **Step 7: Run codec, golden, publisher, and generator checks**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*PortableBackupCodecTest' \
  --tests '*PortableBackupGoldenTest' --stacktrace
./gradlew :app:testDebugUnitTest \
  --tests '*PortableBackupPublisherTest' --stacktrace
```

Regenerate and prove no diff:

```bash
node scripts/generate-stage2-backup-v1-fixtures.mjs
git diff --exit-code -- core/data/src/test/resources/backup-format/v1
```

Expected: all commands exit `0`.

- [ ] **Step 8: Commit Task 7**

```bash
git add scripts/generate-stage2-backup-v1-fixtures.mjs \
  core/data/src/main/kotlin/app/opentasks/core/data/backup/PortableBackupCodec.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/backup \
  core/data/src/test/resources/backup-format/v1 \
  app/src/main/kotlin/app/opentasks/backup \
  app/src/test/kotlin/app/opentasks/backup/PortableBackupPublisherTest.kt
git commit -m "feat: publish verified portable backup package"
```

### Task 8: Enable the Exact Android Allow-List and Preserve Restored Input

**Files:**

- Create:
  `app/src/main/kotlin/app/opentasks/backup/RestoredPackageIntake.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/backup/AndroidBackupRuntime.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/RestoredPackageIntakeTest.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/AndroidBackupRuntimeTest.kt`
- Create:
  `app/src/androidTest/kotlin/app/opentasks/BackupConfigurationInstrumentedTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/LocalVaultRepositoryFactory.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`
- Modify:
  `app/src/main/kotlin/app/opentasks/OpenTasksApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `app/src/main/res/xml/backup_rules.xml`

**Interfaces:**

- Consumes: the publisher, package bootstrap/full verifier, v6 state/envelope,
  content-key store, repository lifetime, and exact Android private paths.
- Produces:

```kotlin
sealed interface RestoredPackageIntakeResult {
    data object NoPackage : RestoredPackageIntakeResult
    data object CurrentSelfProduced : RestoredPackageIntakeResult
    data object ReconciledSelfProduced : RestoredPackageIntakeResult
    data class Preserved(
        val condition: RestoredPackageCondition,
    ) : RestoredPackageIntakeResult
    data object PreservationBlocked : RestoredPackageIntakeResult
}

interface AndroidBackupRuntime {
    fun start()
    fun retry()
}
```

- Produces one Hilt singleton runtime graph and preserves one shared
  `VaultDatabase`/`RoomVaultRepository` instance.

- [ ] **Step 1: Write restored-package classification RED tests**

Test:

- no eligible file;
- current package matching local status;
- crash-after-publish package with same vault/envelope digest and generation
  not above current state, followed by complete verification and status
  reconciliation;
- corrupt self-produced package withdrawal/regeneration;
- missing local state, different vault, different envelope, future
  generation, incompatible bootstrap, and malformed unknown input;
- unknown bytes moved without decryption to the exact no-backup inbox;
- an existing inbox file or failed atomic move leaves eligible input untouched
  and blocks publication; and
- no unknown/restored input is overwritten, deleted, activated, or opened as
  Room.

- [ ] **Step 2: Write runtime-order RED tests**

Use fakes to assert this exact startup order:

```text
restored-package intake
→ established content-key getOrCreate bootstrap
→ local coordinator startup request
→ journal-generation observation
→ portable publisher refresh when envelope-ready
```

Also assert:

- one content key is created on first Stage 2 startup only;
- an existing local envelope failure stops backup work without replacing the
  key or blocking repository editing;
- rapid generation changes debounce and coalesce;
- restart resumes incomplete local/package state; and
- no WorkManager or network call occurs.

- [ ] **Step 3: Run intake/runtime tests to verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*RestoredPackageIntakeTest' \
  --tests '*AndroidBackupRuntimeTest' \
  --stacktrace
```

Expected: compilation fails because intake and runtime do not exist.

- [ ] **Step 4: Preserve one shared local runtime in Hilt**

Refactor `LocalVaultRepositoryFactory` to return one process-lifetime
`LocalVaultRuntime` containing the repository and internal backup data
facades. Provide it once:

```kotlin
data class LocalVaultRuntime(
    val vaultId: VaultId,
    val repository: VaultRepository,
    val backupJournalReader: BackupJournalReader,
    val backupCaptureSource: RoomBackupCaptureSource,
    val backupStateStore: BackupStateStore,
    val recoveryEnvelopeStore: RecoveryEnvelopeStore,
)

@Provides
@Singleton
fun provideLocalVaultRuntime(
    @ApplicationContext context: Context,
): LocalVaultRuntime =
    LocalVaultRepositoryFactory.createRuntime(context)

@Provides
fun provideVaultRepository(
    runtime: LocalVaultRuntime,
): VaultRepository = runtime.repository
```

Provide singleton `VaultCrypto`, `VaultContentKeyStore`,
`AuthenticatedCloudObjectCodec`, coordinator, publisher, intake, and
`AndroidBackupRuntime` from the same runtime/database. Do not open a second
Room instance.

Inject the runtime into `OpenTasksApplication` and call `start()` once from
`onCreate`.

- [ ] **Step 5: Implement inert restored-package intake**

Perform bounded bootstrap parsing first. A file is self-produced only when
local state exists and its vault, recovery-envelope digest, and generation can
be linked. Full verification is required before reconciliation.

For unknown input, move within app-private storage to:

```text
noBackupFilesDir/recovery/incoming_android_v1.otb
```

Use no-overwrite semantics. If the move cannot complete atomically, preserve
the eligible source, record `RestoredPackageDetected`, and prevent publisher
replacement.

- [ ] **Step 6: Install the exact Android backup configuration**

Set:

```xml
android:allowBackup="true"
```

Use exactly:

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup disableIfNoEncryptionCapabilities="true">
        <include
            domain="file"
            path="android_backup/open_tasks_portable_v1.otb" />
    </cloud-backup>
    <device-transfer>
        <include
            domain="file"
            path="android_backup/open_tasks_portable_v1.otb" />
    </device-transfer>
</data-extraction-rules>
```

Keep `backup_rules.xml` deny-all for `root`, `file`, `database`,
`sharedpref`, `external`, `device_root`, `device_file`, `device_database`,
and `device_sharedpref`. Add no `BackupAgent` and no
`backupInForeground`.

- [ ] **Step 7: Add packaged-configuration instrumentation**

`BackupConfigurationInstrumentedTest` must:

- assert `ApplicationInfo.FLAG_ALLOW_BACKUP`;
- parse packaged `data_extraction_rules` and find two includes, both with the
  exact file/path pair;
- assert the cloud encryption-capability flag;
- assert no other include or directory-wide path;
- parse legacy rules and assert each domain is excluded; and
- assert the eligible directory contains no `.new`, `.tmp`, or `.bak` file
  after publisher success/failure.

- [ ] **Step 8: Run app unit and packaged configuration tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --stacktrace
```

Then, on the sole disposable target:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.BackupConfigurationInstrumentedTest \
  --stacktrace
```

Expected: both commands PASS.

- [ ] **Step 9: Commit Task 8**

```bash
git add app/src/main app/src/test/kotlin/app/opentasks/backup \
  app/src/androidTest/kotlin/app/opentasks/BackupConfigurationInstrumentedTest.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/LocalVaultRepositoryFactory.kt
git commit -m "feat: enable exact android backup package allowlist"
```

### Task 9: Add Minimal Backup and Recovery Status to More

**Files:**

- Create:
  `app/src/main/kotlin/app/opentasks/backup/BackupViewModel.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/BackupViewModelTest.kt`
- Create:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/BackupRecoveryScreen.kt`
- Create:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/BackupRecoveryScreenInstrumentedTest.kt`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`
- Modify: `feature/more/src/main/res/values/strings.xml`
- Modify: `feature/more/build.gradle.kts`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`

**Interfaces:**

- Consumes: `AndroidBackupStatusSource`, publisher preparation/retry, runtime
  retry, and Android system settings.
- Produces:

```kotlin
@HiltViewModel
class BackupViewModel @Inject constructor(
    statusSource: AndroidBackupStatusSource,
    private val publisher: PortableBackupPublisher,
    private val runtime: AndroidBackupRuntime,
) : ViewModel() {
    val status: StateFlow<AndroidBackupStatus> = statusSource.status
    fun prepare(passphrase: String)
    fun retry()
}
```

- Produces stateless:

```kotlin
@Composable
fun BackupRecoveryScreen(
    status: AndroidBackupStatus,
    validatePassphrase: (
        passphrase: String,
        confirmation: String,
    ) -> RecoveryPassphraseValidation,
    onPrepare: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 1: Write ViewModel RED tests**

Assert:

- status is passed through unchanged;
- `prepare` checks code-point length without trim/normalisation;
- valid input is copied to a mutable `CharArray` only immediately before the
  publisher call;
- the mutable array is cleared in `finally` on success and failure;
- the input `String` is not saved or logged;
- concurrent setup is ignored while `Preparing`;
- retry calls runtime retry without claiming upload; and
- ViewModel errors map only to bounded `BackupUnavailableReason`.

- [ ] **Step 2: Write stateless More UI RED tests**

Cover every state and exact copy:

- `NotPrepared`: supplementary explanation and **Prepare Android backup**;
- `Preparing`: progress semantics and no editable passphrase;
- `Ready`: **Package ready**, generation, byte count, local production time;
- `UpdatePending`: prior package facts and update-in-progress wording;
- `Unavailable`: one bounded reason and retry where allowed;
- `RestoredPackageDetected`: inert-preservation explanation with no restore or
  discard action.

The passphrase sheet must:

- use non-saveable `remember`, never `rememberSaveable`;
- mask passphrase and confirmation;
- show exact mismatch/length guidance;
- clear both UI values before invoking `onPrepare`;
- omit autofill/save semantics where supported;
- expose 48 dp minimum targets and keyboard traversal; and
- restore neither field after `StateRestorationTester` recreation.

- [ ] **Step 3: Run ViewModel test to verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*BackupViewModelTest' --stacktrace
```

Expected: compilation fails because `BackupViewModel` does not exist.

- [ ] **Step 4: Implement app-layer setup and system settings action**

In `OpenTasksApp`, collect `BackupViewModel.status` with lifecycle and pass
plain values/lambdas to More. Open platform state with:

```kotlin
fun openSystemSettings() {
    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
}
```

Android 37 has no public backup-specific settings intent; do not hard-code an
undocumented action. The UI copy must describe opening system settings, not
promise a direct platform backup page.

Supply `RecoveryPassphrasePolicy::validate` from the app-layer call so
`feature:more` does not depend on `core:domain`. The ViewModel repeats the
same validation immediately before creating the mutable `CharArray`.

Do not inspect or infer platform upload/encryption state.

- [ ] **Step 5: Implement the stateless screen and More destination**

Add `BACKUP_RECOVERY` to `MoreDestination`. Replace the inactive
**Privacy & recovery** row with **Backup & recovery**, an exact local status
summary, and navigation into `BackupRecoveryScreen`.

Put every new string in
`feature/more/src/main/res/values/strings.xml`. Format time using UK English,
day–month date, and 24-hour time. Format bytes deterministically without
provider language.

- [ ] **Step 6: Run feature instrumentation across text scales**

After verifying a sole disposable target, run:

```bash
./gradlew :feature:more:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.feature.more.BackupRecoveryScreenInstrumentedTest \
  --stacktrace
```

The test class must cover default density plus injected 130% and 200% font
scales, compact and expanded widths, keyboard activation/traversal, headings,
content descriptions, state restoration, and all status variants.

Expected: PASS with no clipping or inaccessible action.

- [ ] **Step 7: Run all More tests and app unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  :feature:more:connectedDebugAndroidTest \
  --stacktrace
```

Expected: PASS on the sole disposable device, including existing Insights,
templates, Archive, and Bin coverage.

- [ ] **Step 8: Commit Task 9**

```bash
git add app/src/main/kotlin/app/opentasks/OpenTasksApp.kt \
  app/src/main/kotlin/app/opentasks/backup/BackupViewModel.kt \
  app/src/test/kotlin/app/opentasks/backup/BackupViewModelTest.kt \
  feature/more
git commit -m "feat: show local android backup package status"
```

### Task 10: Run Stage 2 Exit Gates and Record the Checkpoint

**Files:**

- Modify: `README.md`
- Modify: `PRODUCT.md`
- Modify: `DESIGN.md`
- Modify: `docs/architecture.md`
- Modify: `docs/threat-model.md`
- Modify:
  `docs/superpowers/plans/2026-07-27-open-tasks-production-master-plan.md`
- Modify:
  `docs/superpowers/specs/2026-07-28-stage-2-local-backup-android-auto-backup-design.md`
- Modify: `HANDOFF.md`

**Interfaces:**

- Consumes: Tasks 1–9 and every acceptance criterion in the approved Stage 2
  design.
- Produces: one reviewed Stage 2 checkpoint with reproducible host,
  migration, disposable-device, Android backup, UI, protected-workspace, and
  release evidence. The sole next action becomes focused Stage 3
  brainstorming/design.

- [ ] **Step 1: Run deterministic source and format scans**

Run:

```bash
rg -n \
  'TB[D]|TO[D]O|FIXM[E]|implement[[:space:]]+later|similar[[:space:]]+to|DRIVE_PRIMARY|SyncCoordinator|SyncState|CloudObjectStore' \
  core app feature scripts \
  --glob '*.kt' --glob '*.kts' --glob '*.mjs' --glob '*.xml'
```

Expected: no placeholder or obsolete product-sync match.

Run:

```bash
rg -n \
  'Backed up|last upload|Google Drive|drive\\.appdata|BackupAgent|WorkManager|CoroutineWorker' \
  app feature core \
  --glob '*.kt' --glob '*.xml'
```

Expected: no product upload claim, provider, custom backup agent, or worker.

Run:

```bash
git diff --check
node scripts/generate-stage2-backup-v1-fixtures.mjs
git diff --exit-code -- core/data/src/test/resources/backup-format/v1
```

Expected: all commands exit `0`.

- [ ] **Step 2: Run the complete fresh host gate**

Run:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug \
  --stacktrace --rerun-tasks
```

Expected: exit `0`; all JVM tests, Android lint, and debug assembly pass.
Record executed task and test counts.

- [ ] **Step 3: Audit the disposable device before connected work**

Run:

```bash
/Users/kk/Library/Android/sdk/platform-tools/adb devices -l
ps -Ao pid,command
```

Confirm exactly one ADB target and record its AVD name, API, writable
disposable status, posture, density, font scale, and emulator process flags.
Stop if the target is the protected normal emulator.

- [ ] **Step 4: Run complete connected suites on the disposable target**

Run:

```bash
./gradlew :core:crypto:connectedDebugAndroidTest \
  :core:data:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest \
  --stacktrace --rerun-tasks
```

Expected: exit `0`. Record test counts per module. Verify the v5→v6 migration,
encrypted close/reopen, content-key failure modes, packaged allow-list,
restored input, process recreation, and More UI all pass.

- [ ] **Step 5: Exercise Android backup with `bmgr` on disposable state**

Prepare a valid package through the debug UI, then run:

```bash
/Users/kk/Library/Android/sdk/platform-tools/adb shell bmgr enabled
/Users/kk/Library/Android/sdk/platform-tools/adb shell bmgr list transports
/Users/kk/Library/Android/sdk/platform-tools/adb shell bmgr backupnow app.opentasks
/Users/kk/Library/Android/sdk/platform-tools/adb shell dumpsys backup
/Users/kk/Library/Android/sdk/platform-tools/adb shell bmgr list sets
```

Use only the configured disposable transport. Inspect the resulting dataset or
transport logs and prove that the sole eligible application-relative path is:

```text
android_backup/open_tasks_portable_v1.otb
```

On the disposable target only, capture the hexadecimal dataset token reported
by `bmgr list sets`, then run:

```bash
read -r OPEN_TASKS_RESTORE_TOKEN
test -n "$OPEN_TASKS_RESTORE_TOKEN"
/Users/kk/Library/Android/sdk/platform-tools/adb uninstall app.opentasks
/Users/kk/Library/Android/sdk/platform-tools/adb install \
  app/build/outputs/apk/debug/app-debug.apk
/Users/kk/Library/Android/sdk/platform-tools/adb shell bmgr restore \
  "$OPEN_TASKS_RESTORE_TOKEN" app.opentasks
/Users/kk/Library/Android/sdk/platform-tools/adb shell am start \
  -n app.opentasks/.MainActivity
```

Verify first launch moves the file to the inert no-backup inbox, reports
**Restored package detected**, and exposes no restore/activation action. The
uninstall is authorised only for the audited disposable target in this step.

If the emulator transport cannot provide encrypted cloud semantics, record
that as the approved external Google transport qualification gate; do not
infer upload from local readiness.

- [ ] **Step 6: Verify UI acceptance at 100%, 130%, and 200%**

On disposable state, set each font scale, force-stop/relaunch, and inspect:

- More overview status summary;
- setup explanation;
- hidden passphrase and confirmation;
- mismatch and length errors;
- Preparing, Ready, Update pending, Unavailable, and Restored package states;
- keyboard traversal/activation; and
- compact, unfolded/expanded, and separating-fold layouts.

Restore the disposable font scale after evidence capture. Do not change the
protected emulator's recorded 200% state.

- [ ] **Step 7: Run the separate fresh release gate**

Run:

```bash
./gradlew :app:assembleRelease --stacktrace --rerun-tasks
```

Expected: exit `0`, including R8, resource shrinking, release packaging, and
`:app:assembleRelease`.

- [ ] **Step 8: Perform the protected workspace in-place audit**

Only after every disposable and host gate passes:

1. confirm the named authorised replacement snapshot exists;
2. record package UID, first-install time, database/WAL/SHM inode identities,
   database size, and visible workspace anchors;
3. install the debug APK in place without uninstall or data clear;
4. launch normally without instrumentation;
5. verify v5→v6 completed, all existing projects/tasks/templates/time entries
   remain visible, legacy outbox count is unchanged, and local generation
   baseline is verified;
6. force-stop/relaunch and repeat the visible/data identity audit; and
7. restore the authorised snapshot immediately if any invariant fails.

Do not run `connectedDebugAndroidTest`, `bmgr`, uninstall, or clear-data
against the protected workspace.

- [ ] **Step 9: Reconcile active documentation**

Update current-state language to record:

- Room v6 and backup-journal atomicity;
- verified local snapshots/segments;
- prepared recovery envelope and package;
- exact Android Auto Backup/device-transfer allow-list;
- Android backup as supplementary and upload state unknowable;
- restored packages inert and Stage 3 recovery activation absent;
- attachment bytes still absent;
- no provider, WorkManager, writer takeover, or remote merge;
- every Task 1–9 commit and fresh gate result;
- external Google transport qualification, if unavailable locally; and
- Stage 3 as the only next design/planning action.

Set the Stage 2 design status to **Implemented and verified** only if every
required non-external gate passes. Keep external account/transport evidence
explicitly external.

- [ ] **Step 10: Run final documentation and working-tree checks**

Run:

```bash
git diff --check
git status --short
```

Expected: only the intentional Task 10 documentation paths are modified.

- [ ] **Step 11: Commit Task 10**

```bash
git add README.md PRODUCT.md DESIGN.md docs/architecture.md \
  docs/threat-model.md \
  docs/superpowers/plans/2026-07-27-open-tasks-production-master-plan.md \
  docs/superpowers/specs/2026-07-28-stage-2-local-backup-android-auto-backup-design.md \
  HANDOFF.md
git commit -m "docs: record stage 2 backup verification"
```

- [ ] **Step 12: Request final code review before Stage 3**

Use `superpowers:requesting-code-review` against the complete Stage 2 commit
range. Resolve every correctness, security, migration, backup-eligibility, and
scope finding through the required review workflow, rerun affected tests, and
record the final-review correction wave in `HANDOFF.md`.

Do not begin Stage 3 source or provider work from this plan.
