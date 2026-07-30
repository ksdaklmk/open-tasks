# Stage 3 Google Drive Backup and Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add verified encrypted Google Drive app-data backup, conditional
single-writer publication, staged recovery and takeover, and truthful
backup-management UI without adding synchronization or attachment transport.

**Architecture:** Stage 2 remains the local source of verified snapshots and
operation segments. A session-bound Drive adapter uploads immutable
lineage-bound frames and publishes one authenticated control file with an
HTTP conditional write; `RecoveryCoordinator` is the sole reverse path and
activates only a separately verified SQLCipher vault slot. Google
authorization, WorkManager, and Compose remain app-layer adapters around
provider-independent domain and data contracts.

**Tech Stack:** Kotlin 2.3.21, Android Gradle Plugin 9.3.1, Java 17 on JDK 21,
Compose BOM 2026.06.01, Room 2.8.4, SQLCipher 4.15.0, Tink 1.23.0,
Bouncy Castle 1.84, kotlinx.serialization 1.11.0, Google Play services Auth
21.6.0, WorkManager 2.11.2, Android `AtomicFile`, `HttpURLConnection`,
JUnit 4, Compose UI test v2, and Node.js built-in `crypto` for independent
format fixtures.

## Global Constraints

- Work directly on `main`; do not create a branch, worktree, or pull request.
- Preserve the protected Room workspace. Never uninstall the application,
  clear its data, attach instrumentation to its normal emulator, reset the
  repository, or overwrite its named replacement snapshot.
- Use exactly one audited disposable ADB target for connected and credentialed
  qualification. Record its serial before any install or test command.
- Before the first `connectedDebugAndroidTest`, export both
  `STAGE3_ADB_SERIAL` and `ANDROID_SERIAL` to the exact audited serial, run
  `adb -s "$STAGE3_ADB_SERIAL" shell getprop ro.build.version.sdk`, and stop
  if `adb devices` shows any other eligible target. Every later connected
  command in this plan inherits those values.
- Room remains the sole live structured-data authority.
- Normal cloud operation has no remote-to-Room record path. Only
  `RecoveryCoordinator` may populate a new inactive staging database.
- Keep every v6 row and every `sync_operations` row. The v6-to-v7 migration is
  additive and `sync_operations` remains read-only.
- Do not import remote configuration, account binding, provider revisions,
  transfer sessions, old device IDs, or old local backup checkpoints into a
  recovered vault.
- Keep Stage 2 local and remote checkpoints separate. Remote verification
  never advances `backup_state`.
- Request only
  `https://www.googleapis.com/auth/drive.appdata`.
- Persist no OAuth access token, refresh token, ID token, server
  authorization code, Google email, profile, raw Drive permission ID, or raw
  account name.
- `about.get(fields=user(permissionId))` is the only account-binding input.
  Persist only a per-install HMAC-SHA-256 digest in SQLCipher.
- Initial account selection is explicit. Existing lineages reconnect only to
  their bound account. Account switching and migration remain Stage 4.
- Access-token strings returned by Google Play services are immutable runtime
  objects. Minimize their lifetime, drop references promptly, clear the Google
  token cache on invalidation, and do not claim direct zeroisation.
- Use one random `CloudLineageId` per independently created backup and one
  random `CloudDeviceId` per participating installation.
- Use one mutable Drive control file per lineage and immutable snapshot and
  operation-segment files.
- A remote generation is verified only after immutable-object readback,
  successful control compare-and-swap, and authenticated control readback.
- Treat provider revisions and Drive IDs as encrypted operational secrets.
  Never log them or place them in WorkManager names or data.
- Drive JSON `version` is observational only. HTTP conditional revision is the
  writer-safety authority.
- The credentialed provider gate must prove pre-generated control IDs,
  duplicate-create rejection, current conditional update success, stale
  conditional update rejection, missing-file classification, and immediate
  readback. Stop Stage 3 if any property fails.
- There is no last-writer-wins fallback and no unconditional control update.
- Preserve strict canonical UTF-8 JSON, exact field order, explicit nulls,
  fixed enum names, unpadded Base64, deterministic collection order, and
  epoch-millisecond instants.
- Preserve Stage 1/2 bounds: 16 KiB cloud header, 1 MiB manifest ciphertext,
  64 MiB snapshot ciphertext, 16 MiB operation-segment ciphertext, 100,000
  snapshot records, and 10,000 segment operations.
- AES-GCM crypto-v1 adds 33 bytes. Plaintext maxima remain `1 MiB - 33`,
  `64 MiB - 33`, and `16 MiB - 33`.
- Recovery envelopes remain at most 16 KiB. Portable packages remain at most
  24 MiB (`25_165_824` bytes).
- Retain the current and previous verified bases plus every segment needed
  after either base.
- Prune only after authoritative successor readback, current ownership
  confirmation, and proof that the object is absent from the successor.
- A successor may not reintroduce an absent object unless it uploaded and
  readback-verified fresh immutable bytes.
- A client that previously observed a missing or replaced control treats the
  lineage as lost and never recreates it automatically.
- Automatic backup uses a 15-minute one-time debounce and a 24-hour periodic
  check with connected, battery-not-low, and storage-not-low constraints.
- Automatic v1 backup does not require an unmetered network.
- Background work never launches authorization UI. Resolution-required work
  persists an action-required state and completes without a retry loop.
- Local structured editing and Stage 2 package production remain available
  through every remote failure.
- Keep passphrases as `CharArray` at cryptographic boundaries. Never persist
  them. Clear owned mutable characters, UTF-8, plaintext, derived keys,
  associated data, and temporary key arrays at ownership boundaries.
- Recovery-passphrase change rewraps the same vault-content key. It does not
  rotate content encryption or claim to revoke older backup copies.
- Never create a replacement Keystore key for an existing unreadable local
  envelope.
- Disconnect sends no Drive object-delete request. History deletion is a
  separate passphrase-confirmed operation and deletes the control last.
- Files in `appDataFolder` are permanently deleted; do not present trash or
  undo semantics.
- Keep the Android portable package independent and inert until explicit
  recovery.
- Keep Google-account migration, attachment transport, synchronization,
  remote merge, collaboration, and a second active writer out of Stage 3.
- Keep `core:sync` and `core:crypto` free of Compose, Google APIs,
  WorkManager, provider credentials, and product UI.
- Feature composables remain stateless and Hilt-free.
- New copy belongs in resources and uses UK English, Bin terminology,
  day-month dates, and the 24-hour clock.
- Keep `minSdk 36`, `compileSdk 37`, `targetSdk 37`, Java 17, JDK 21, and AGP
  built-in Kotlin. Do not apply `org.jetbrains.kotlin.android`.
- Use JUnit 4 assertions and camelCase test names. Add no mocking library,
  Turbine, Robolectric, or coroutine-test dependency.
- Run release assembly separately from lint because the repository records an
  AGP/KSP release-lint race.
- GitHub dependency-PR checks and resolution remain paused.

---

## Scope and File Map

The approved design is
`docs/superpowers/specs/2026-07-30-stage-3-google-drive-backup-recovery-design.md`.
The provider, publication, recovery, and lifecycle work is sequential and
shares one control format and writer state, so this is one ordered plan rather
than independent plans with competing interface definitions.

Focused responsibilities:

- `gradle/libs.versions.toml` — Play services Auth 21.6.0 and existing
  WorkManager 2.11.2 aliases.
- `core/model/.../RemoteBackupModels.kt` — feature-safe IDs, facts, statuses,
  and bounded failure categories.
- `core/domain/.../RemoteBackupContracts.kt` — object-store, publication,
  scheduling, lifecycle, and recovery contracts.
- `core/data/.../backup/drive/DriveTransport.kt` — session-bound low-level
  Drive REST contract with no Android dependency.
- `app/.../backup/drive/HttpDriveTransport.kt` — access-token-scoped Drive
  REST implementation and direct HTTP revision handling.
- `app/src/debug/.../DriveQualificationActivity.kt` — debug-only credentialed
  conditional-write gate.
- `core/data/.../backup/RemoteControlCodec.kt` — strict public bootstrap and
  authenticated control manifest v1.
- `scripts/generate-stage3-drive-v1-fixtures.mjs` — independent canonical
  Stage 3 fixtures.
- `core/data/.../backup/RemoteBackupEntities.kt` — Room v7 configuration,
  immutable-object, and operation rows.
- `core/data/.../backup/RemoteBackupDaos.kt` — bounded state transitions and
  one-active-lineage enforcement.
- `core/data/.../backup/RoomRemoteBackupStore.kt` — domain mapping and
  transaction boundary for remote state.
- `app/.../backup/drive/GoogleDriveAuthorizationManager.kt` — explicit
  selection, non-interactive authorization, HMAC account binding, token
  clearing, and revocation.
- `core/data/.../backup/DriveBackupObjectStore.kt` — control and immutable
  object semantics over an injected `DriveTransport`.
- `core/data/.../backup/RemoteObjectCodec.kt` — local-frame verification and
  cloud-lineage re-authentication.
- `core/data/.../backup/DefaultRemoteBackupConfigurator.kt` — discovery and
  epoch-one publication.
- `core/data/.../backup/DefaultRemoteBackupCoordinator.kt` — incremental
  publication, conflict handling, verified checkpoints, and retention.
- `core/data/.../VaultSlotRegistry.kt` — atomic active-vault marker with
  legacy database compatibility.
- `core/data/.../RecoveryRegistry.kt` — bounded encrypted crash journal
  outside the active vault.
- `core/data/.../VaultRuntimeManager.kt` — no-vault, unreadable, staging, and
  active runtime states.
- `app/.../backup/RemoteBackupWorker.kt` — one WorkManager execution adapter.
- `app/.../backup/WorkManagerRemoteBackupScheduler.kt` — unique debounce and
  periodic work behind the domain scheduling contract.
- `app/.../backup/RemoteBackupRuntime.kt` — authorization, scheduler, and
  coordinator composition for an active vault.
- `core/data/.../backup/BackupRecordImporter.kt` — exhaustive v1 record and
  mutation mapping into an empty staging database.
- `core/data/.../backup/DefaultRecoveryCoordinator.kt` — Drive/portable source
  validation, fallback, staging, takeover, and activation.
- `app/.../backup/RecoveryPassphraseChanger.kt` — crash-resumable envelope,
  portable-package, and control publication.
- `app/.../backup/DefaultRemoteBackupLifecycleCoordinator.kt` — disconnect,
  deleting-state cleanup, authorization cleanup, and divergent-lineage
  preservation behind the domain lifecycle contract.
- `app/.../backup/EncryptedBackupViewModel.kt` — foreground cloud-backup
  actions and ephemeral authorization effects.
- `app/.../backup/RecoveryViewModel.kt` — recovery-shell source selection,
  passphrase, progress, and takeover confirmation.
- `feature/more/.../BackupRecoveryScreen.kt` — independent encrypted-app and
  Android-package cards.
- `feature/more/.../RecoveryShellScreen.kt` — stateless no-vault and
  unreadable-vault recovery UI.

### Task 1: Prove Drive Conditional-Write Semantics

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/drive/DriveTransport.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/backup/drive/HttpDriveTransport.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/drive/HttpDriveTransportTest.kt`
- Create:
  `app/src/debug/kotlin/app/opentasks/backup/drive/DriveQualificationActivity.kt`
- Create:
  `app/src/debug/kotlin/app/opentasks/backup/drive/DriveConditionalWriteQualification.kt`
- Create: `app/src/debug/AndroidManifest.xml`
- Create:
  `app/src/androidTest/kotlin/app/opentasks/DriveQualificationPackagingInstrumentedTest.kt`
- Create: `docs/qualification/stage3-drive-conditional-write.md`

**Interfaces:**

- Consumes: Google Play services `AuthorizationClient`,
  `AuthorizationRequest.Prompt.SELECT_ACCOUNT`, Drive REST v3, one disposable
  Google account, and a Google Cloud Android OAuth client registered for
  package `app.opentasks` plus the debug signing SHA-1. No client secret or
  downloaded credential file enters the repository.
- Produces:

```kotlin
data class DriveFileMetadata(
    val providerFileId: String,
    val name: String,
    val role: String,
)

data class DriveCreateRequest(
    val metadata: DriveFileMetadata,
    val content: ByteArray,
)

data class DriveDownloadReceipt(
    val byteCount: Long,
    val providerRevision: String,
)

sealed interface DriveCreateResult {
    data class Created(val providerRevision: String) : DriveCreateResult
    data object AlreadyExists : DriveCreateResult
}

sealed interface DriveCompareAndSwapResult {
    data class Applied(val providerRevision: String) :
        DriveCompareAndSwapResult
    data object Conflict : DriveCompareAndSwapResult
    data object Missing : DriveCompareAndSwapResult
}

data class DriveListedFile(
    val providerFileId: String,
    val name: String,
    val role: String?,
)

data class DriveResumableSession(
    val sessionUri: String,
)

sealed interface DriveChunkResult {
    data class ResumeAt(val nextByte: Long) : DriveChunkResult
    data class Complete(val providerRevision: String) : DriveChunkResult
    data object Expired : DriveChunkResult
}

enum class DriveTransportFailureCategory {
    AUTHORIZATION,
    MISSING,
    STORAGE_QUOTA,
    RETRYABLE,
    CORRUPT_RESPONSE,
    CONDITIONAL_UNAVAILABLE,
}

class DriveTransportException(
    val category: DriveTransportFailureCategory,
) : IOException()

interface DriveTransport : AutoCloseable {
    suspend fun readCurrentUserPermissionId(): String
    suspend fun generateAppDataFileIds(count: Int): List<String>
    suspend fun listAppDataFiles(role: String): List<DriveListedFile>
    suspend fun createFile(request: DriveCreateRequest): DriveCreateResult
    suspend fun downloadFile(
        providerFileId: String,
        destination: File,
        maximumBytes: Long,
    ): DriveDownloadReceipt
    suspend fun compareAndSwapFile(
        providerFileId: String,
        expectedRevision: String,
        content: ByteArray,
    ): DriveCompareAndSwapResult
    suspend fun startResumableCreate(
        metadata: DriveFileMetadata,
        totalBytes: Long,
    ): DriveResumableSession
    suspend fun queryResumableUpload(
        sessionUri: String,
        totalBytes: Long,
    ): DriveChunkResult
    suspend fun uploadChunk(
        sessionUri: String,
        firstByte: Long,
        totalBytes: Long,
        content: ByteArray,
    ): DriveChunkResult
    suspend fun deleteFile(providerFileId: String): Boolean
}
```

`HttpDriveTransport` is constructed with one access-token string and drops
that reference on `close()`. Its exception types contain only bounded HTTP
status categories and never request URLs, response bodies, tokens, IDs, or
headers.

- [ ] **Step 1: Add the exact authorization dependency**

Add:

```toml
play-services-auth = "21.6.0"
google-play-services-auth = {
    module = "com.google.android.gms:play-services-auth",
    version.ref = "play-services-auth"
}
```

Add `implementation(libs.google.play.services.auth)` to `app`.

- [ ] **Step 2: Write failing low-level HTTP tests**

Use an injected `DriveConnectionFactory` and fake
`HttpURLConnection`. Cover:

```kotlin
@Test
fun staleIfMatchMapsToConflictWithoutLeakingResponseBody() = runBlocking {
    val transport = transportReturning(
        status = 412,
        responseBody = "private provider diagnostic",
    )

    assertEquals(
        DriveCompareAndSwapResult.Conflict,
        transport.compareAndSwapFile("file-a", "\"old\"", byteArrayOf(1)),
    )
    assertFalse(transport.recordedFailureText.contains("private"))
}
```

Also assert `If-Match`, `Authorization: Bearer`, `spaces=appDataFolder`,
`about.get(fields=user(permissionId))`, pre-generated IDs, multipart metadata,
`ETag` capture, exact media bytes, `404`, `409`, `412`, `401`, `403`, `429`,
and `5xx` classification. The exact transport categories are:

```text
401 or an authorization-related 403 -> AUTHORIZATION
404 outside an explicit missing result -> MISSING
403 storageQuotaExceeded             -> STORAGE_QUOTA
403 rate-limit reason, 429, or 5xx    -> RETRYABLE
missing/weak conditional revision     -> CONDITIONAL_UNAVAILABLE
invalid bounded JSON or media length  -> CORRUPT_RESPONSE
```

No exception message contains provider text or a request identifier.

- [ ] **Step 3: Run the focused tests and confirm the missing types fail**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.drive.HttpDriveTransportTest' --stacktrace
```

Expected: compilation failure for `DriveTransport` and
`HttpDriveTransport`.

- [ ] **Step 4: Implement the session-bound Drive REST transport**

Use `Dispatchers.IO`, `HttpURLConnection`, strict JSON through the existing
kotlinx serialization dependency, bounded response reads, and these endpoints:

```text
GET  /drive/v3/files/generateIds?count=N&space=appDataFolder&type=files
GET  /drive/v3/about?fields=user(permissionId)
GET  /drive/v3/files?spaces=appDataFolder&q=appProperties...
POST /upload/drive/v3/files?uploadType=multipart
GET  /drive/v3/files/{id}?alt=media
PATCH /upload/drive/v3/files/{id}?uploadType=media
POST /upload/drive/v3/files?uploadType=resumable
PUT  {resumable-session-uri}
DELETE /drive/v3/files/{id}
```

Set `If-Match` only for `compareAndSwapFile`. Stream media into the exact
caller-owned private destination, stop before writing byte
`maximumBytes + 1`, sync before returning, and delete a partial destination
on failure. Accept an applied update only when status is `200` or `201`, an
`ETag` exists, and later readback can verify content.

- [ ] **Step 5: Run the HTTP tests**

Run the command from Step 3.

Expected: PASS with no response body, token, or provider ID in assertion
messages.

- [ ] **Step 6: Add the debug-only credentialed qualification activity**

The activity must:

1. request only `drive.appdata` with `SELECT_ACCOUNT`;
2. launch the returned `PendingIntent` when required;
3. obtain one token in memory;
4. call `about.get`, HMAC its permission ID with an ephemeral key, and retain
   neither raw identifier nor digest after the run;
5. generate a control ID;
6. create a Stage 1 authenticated manifest-family frame with an ephemeral
   content key;
7. prove duplicate create is rejected;
8. download to a private backup-excluded file, capture its revision, and
   authenticate the downloaded frame;
9. update with the current revision using a second authenticated frame;
10. prove the predecessor revision returns conflict;
11. read and authenticate the updated frame;
12. delete the disposable qualification file;
13. clear every owned key and frame buffer; and
14. render only `PASS` or a bounded failed property name.

The debug manifest marks the activity `exported="false"`. The release
manifest contains no qualification activity.

- [ ] **Step 7: Prove packaging excludes the qualification activity**

Run:

```bash
: "${STAGE3_ADB_SERIAL:?Set STAGE3_ADB_SERIAL to the audited disposable target}"
export ANDROID_SERIAL="$STAGE3_ADB_SERIAL"
adb -s "$STAGE3_ADB_SERIAL" shell getprop ro.build.version.sdk
./gradlew :app:assembleDebug :app:assembleRelease \
  :app:connectedDebugAndroidTest --stacktrace
```

Expected:

- debug packaging test finds the internal qualification activity;
- release manifest inspection does not find it; and
- existing Android backup allow-list tests still pass.

- [ ] **Step 8: Run the credentialed gate on one disposable device**

Set an audited serial and launch the debug activity:

```bash
: "${STAGE3_ADB_SERIAL:?Set STAGE3_ADB_SERIAL to the audited disposable target}"
export ANDROID_SERIAL="$STAGE3_ADB_SERIAL"
adb -s "$STAGE3_ADB_SERIAL" shell am start \
  -n app.opentasks/.backup.drive.DriveQualificationActivity
```

Complete Google's account UI and require `PASS`. Repeat the stale update three
times against the same predecessor revision. If any stale update succeeds or
duplicate create produces a second control, stop the plan and write the
observed property failure into the qualification document.

- [ ] **Step 9: Record non-private evidence**

Record date, app commit, Android API level, Play services Auth version, Drive
API endpoint family, and pass/fail for the nine provider properties. Record no
account, token, file ID, revision, request URL, or response body.

- [ ] **Step 10: Commit the qualified transport**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
  core/data/src/main/kotlin/app/opentasks/core/data/backup/drive \
  app/src/main/kotlin/app/opentasks/backup/drive \
  app/src/debug app/src/androidTest/kotlin/app/opentasks \
  docs/qualification/stage3-drive-conditional-write.md
git commit -m "feat: qualify Drive conditional backup writes"
```

### Task 2: Freeze Cloud-Lineage and Control Formats

**Files:**

- Create:
  `core/model/src/main/kotlin/app/opentasks/core/model/RemoteBackupModels.kt`
- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RemoteControlCodec.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/RemoteControlCodecTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/RemoteControlGoldenTest.kt`
- Create: `scripts/generate-stage3-drive-v1-fixtures.mjs`
- Create:
  `core/data/src/test/resources/backup-format/drive-v1/control-bootstrap.json`
- Create:
  `core/data/src/test/resources/backup-format/drive-v1/control-manifest.json`
- Create:
  `core/data/src/test/resources/backup-format/drive-v1/control-complete.otc`

**Interfaces:**

- Consumes: `VaultId`, `BackupGeneration`, `CloudObjectFamily`,
  `AuthenticatedCloudObjectCodec`, `RecoveryEnvelopeCodec`, and the Stage 1
  frame bounds.
- Produces:

```kotlin
class CloudLineageId private constructor(val value: String) {
    companion object {
        fun new(): CloudLineageId =
            CloudLineageId(UUID.randomUUID().toString())
        fun parse(value: String): CloudLineageId =
            CloudLineageId(requireCanonicalUuid(value))
    }

    override fun equals(other: Any?) =
        other is CloudLineageId && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = "CloudLineageId([redacted])"
}

class CloudDeviceId private constructor(val value: String) {
    companion object {
        fun new(): CloudDeviceId =
            CloudDeviceId(UUID.randomUUID().toString())
        fun parse(value: String): CloudDeviceId =
            CloudDeviceId(requireCanonicalUuid(value))
    }

    override fun equals(other: Any?) =
        other is CloudDeviceId && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = "CloudDeviceId([redacted])"
}

class ProviderObjectId private constructor(val value: String) {
    companion object {
        fun of(value: String) =
            ProviderObjectId(requireBoundedOpaque(value, maximum = 1_024))
    }

    override fun equals(other: Any?) =
        other is ProviderObjectId && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = "ProviderObjectId([redacted])"
}

class ProviderRevision private constructor(val value: String) {
    companion object {
        fun of(value: String) =
            ProviderRevision(requireBoundedOpaque(value, maximum = 1_024))
    }

    override fun equals(other: Any?) =
        other is ProviderRevision && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = "ProviderRevision([redacted])"
}

private fun requireCanonicalUuid(value: String): String {
    require(value.length == 36)
    require(UUID.fromString(value).toString() == value)
    return value
}

private fun requireBoundedOpaque(value: String, maximum: Int): String {
    require(value.isNotEmpty() && value.length <= maximum)
    require(value.none { it.isISOControl() })
    return value
}

enum class RemoteBackupFailureCategory {
    AUTHORIZATION_REQUIRED,
    ACCOUNT_MISMATCH,
    OWNERSHIP_LOST,
    PROVIDER_STORAGE,
    RETRYABLE_PROVIDER,
    CORRUPT_OR_INCOMPATIBLE,
    LOCAL_STORAGE,
    CONDITIONAL_WRITE_UNAVAILABLE,
}

enum class RecoveryFailureCategory {
    AUTHORIZATION_REQUIRED,
    ACCOUNT_MISMATCH,
    WRONG_PASSPHRASE,
    UNSAFE_KDF,
    CORRUPT_OR_INCOMPATIBLE,
    MISSING_REQUIRED_OBJECT,
    INSUFFICIENT_STORAGE,
    STAGING_INVARIANT,
    OWNERSHIP_CHANGED,
    LOCAL_KEY_UNAVAILABLE,
}

enum class RecoverySource {
    GOOGLE_DRIVE,
    ANDROID_BACKUP_PACKAGE,
}

enum class PassphraseChangeFailureCategory {
    CURRENT_PASSPHRASE_INVALID,
    PORTABLE_PACKAGE,
    REMOTE_BACKUP,
    LOCAL_STORAGE,
}

data class RemoteBackupVerifiedInfo(
    val generation: BackupGeneration,
    val verifiedAt: Instant,
)

sealed interface RemoteBackupStatus {
    data object Disabled : RemoteBackupStatus
    data object Preparing : RemoteBackupStatus
    data class BackingUp(val generation: BackupGeneration) :
        RemoteBackupStatus
    data class Verified(val info: RemoteBackupVerifiedInfo) :
        RemoteBackupStatus
    data class RetryScheduled(
        val generation: BackupGeneration,
        val reason: RemoteBackupFailureCategory,
    ) : RemoteBackupStatus
    data class ActionRequired(val reason: RemoteBackupFailureCategory) :
        RemoteBackupStatus
    data object Deleting : RemoteBackupStatus
}
```

The domain object-store contract uses `OwnedRemoteBytes : AutoCloseable` and
the exact result families:

```kotlin
interface BackupObjectStore {
    suspend fun discoverControlIds(): List<ProviderObjectId>
    suspend fun readControl(id: ProviderObjectId): ControlReadResult
    suspend fun createInitialControl(
        id: ProviderObjectId,
        bytes: OwnedRemoteBytes,
    ): InitialControlResult
    suspend fun compareAndSwapControl(
        id: ProviderObjectId,
        expected: ProviderRevision,
        bytes: OwnedRemoteBytes,
    ): ControlSwapResult
    suspend fun uploadImmutable(request: ImmutableUploadRequest):
        ImmutableUploadResult
    suspend fun downloadImmutable(id: ProviderObjectId): ImmutableDownloadResult
    suspend fun deleteImmutable(id: ProviderObjectId): DeleteObjectResult
    suspend fun deleteControl(id: ProviderObjectId): DeleteObjectResult
}
```

The referenced ownership and result types are closed, bounded contracts:

```kotlin
interface OwnedRemoteBytes : AutoCloseable {
    val size: Int
    fun take(): ByteArray
}

interface OwnedRemoteFile : AutoCloseable {
    val file: File
    val length: Long
}

sealed interface ControlReadResult {
    data class Found(
        val bytes: OwnedRemoteBytes,
        val revision: ProviderRevision,
    ) : ControlReadResult
    data object Missing : ControlReadResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        ControlReadResult
}

sealed interface InitialControlResult {
    data class Created(val revision: ProviderRevision) :
        InitialControlResult
    data object AlreadyExists : InitialControlResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        InitialControlResult
}

sealed interface ControlSwapResult {
    data class Applied(val revision: ProviderRevision) : ControlSwapResult
    data object Conflict : ControlSwapResult
    data object Missing : ControlSwapResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        ControlSwapResult
}

data class ImmutableUploadRequest(
    val objectId: String,
    val providerObjectId: ProviderObjectId,
    val family: CloudObjectFamily,
    val firstGeneration: BackupGeneration,
    val lastGeneration: BackupGeneration,
    val frameLength: Long,
    val frameSha256: String,
    val frame: OwnedRemoteFile,
)

sealed interface ImmutableUploadResult {
    data object UploadedAndVerified : ImmutableUploadResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        ImmutableUploadResult
}

sealed interface ImmutableDownloadResult {
    data class Downloaded(val frame: OwnedRemoteFile) :
        ImmutableDownloadResult
    data object Missing : ImmutableDownloadResult
    data object Corrupt : ImmutableDownloadResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        ImmutableDownloadResult
}

sealed interface DeleteObjectResult {
    data object Deleted : DeleteObjectResult
    data object Missing : DeleteObjectResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        DeleteObjectResult
}
```

`OwnedRemoteBytes.take()` transfers exactly once. Closing it clears retained
bytes. Closing `OwnedRemoteFile` closes open streams, removes its private
staging file, and makes future access fail.

The strict format is:

```kotlin
@Serializable
data class RemoteControlBootstrapV1(
    val magic: String = "OPEN_TASKS_CONTROL",
    val controlVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val lineageId: String,
    val createdAtEpochMillis: Long,
    val recoveryEnvelope: RecoveryEnvelopePayloadV1,
    val manifestFrameLength: Long,
    val manifestFrameSha256: String,
    val totalControlLength: Long,
)

@Serializable
data class RemoteInventoryItemV1(
    val objectId: String,
    val providerFileId: String,
    val family: CloudObjectFamily,
    val firstGeneration: Long,
    val lastGeneration: Long,
    val frameLength: Long,
    val frameSha256: String,
)

@Serializable
enum class RemoteControlStateV1 {
    ACTIVE,
    DELETING,
}

@Serializable
data class RemoteControlManifestV1(
    val controlVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val lineageId: String,
    val sourceVaultId: String,
    val writerEpoch: Long,
    val activeDeviceId: String,
    val controlState: RemoteControlStateV1,
    val publishedGeneration: Long,
    val publishedAtEpochMillis: Long,
    val currentBaseObjectId: String,
    val previousBaseObjectId: String?,
    val inventory: List<RemoteInventoryItemV1>,
    val recoveryEnvelopeSha256: String,
    val recoveryCredentialGeneration: Long,
)
```

- [ ] **Step 1: Write failing model and strict-codec tests**

Cover random canonical IDs, malformed and over-bound ID rejection, redacted
identifier `toString()`, non-negative epochs/generations, current/previous
base membership, complete segment coverage, sorted unique inventory, 16 KiB
bootstrap, `1 MiB - 33` manifest plaintext, canonical bytes, envelope digest,
lineage/source identity agreement, unknown fields, future versions, length,
checksum, AEAD, associated-data swapping, and owned-buffer clearing.

Example:

```kotlin
@Test
fun manifestFromAnotherLineageFailsAuthentication() {
    val encoded = codec.encode(ENVELOPE, manifest(lineage = LINEAGE_A), KEY)

    assertThrows(IllegalArgumentException::class.java) {
        codec.verifyComplete(
            ByteArrayInputStream(encoded),
            encoded.size.toLong(),
            LINEAGE_B,
            KEY,
        )
    }
}
```

- [ ] **Step 2: Run tests and confirm missing declarations fail**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests 'app.opentasks.core.data.backup.RemoteControlCodecTest' --stacktrace
```

Expected: compilation failure for the new IDs and codec.

- [ ] **Step 3: Implement exact domain ownership contracts**

Implement the exact ownership contracts above. `ProviderObjectId` and
`ProviderRevision` return only redacted text from `toString()` and are never
interpolated into exception messages. Result failures carry only bounded
categories; provider diagnostics remain inside the transport boundary.

- [ ] **Step 4: Implement `RemoteControlCodec`**

Use a four-byte bootstrap length followed by canonical bootstrap JSON and one
authenticated manifest frame. Bind the manifest frame with:

```kotlin
CloudHeaderIdentity(
    family = CloudObjectFamily.MANIFEST,
    schemaVersion = 1,
    cryptoVersion = 1,
    minimumReaderVersion = 1,
    vaultId = lineageId.value,
    objectId = "control:manifest",
)
```

Validate all declared lengths before allocation. Decode the recovery envelope
before key unlock, then require the authenticated manifest's envelope digest
to match the exact canonical bootstrap envelope.

- [ ] **Step 5: Run the focused codec tests**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 6: Generate independent fixtures**

The Node script must construct canonical JSON independently, derive the fixed
test key with the documented fixture inputs, emit the three fixtures, parse
them again, and verify lengths and SHA-256 values. It must not call Gradle or
Kotlin.

```bash
node scripts/generate-stage3-drive-v1-fixtures.mjs
git diff --exit-code -- \
  core/data/src/test/resources/backup-format/drive-v1
```

Expected: the second generation is byte-identical.

- [ ] **Step 7: Run golden and existing Stage 1/2 format suites**

```bash
./gradlew :core:data:testDebugUnitTest --stacktrace
```

Expected: all existing portable, snapshot, segment, and cloud-frame tests plus
the new control tests pass.

- [ ] **Step 8: Commit the format contract**

```bash
git add core/model/src/main/kotlin/app/opentasks/core/model \
  core/domain/src/main/kotlin/app/opentasks/core/domain \
  core/data/src/main/kotlin/app/opentasks/core/data/backup \
  core/data/src/test core/data/src/test/resources/backup-format/drive-v1 \
  scripts/generate-stage3-drive-v1-fixtures.mjs
git commit -m "feat: freeze remote backup control format"
```

### Task 3: Add Additive Room v7 Remote State

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RemoteBackupEntities.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RemoteBackupDaos.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomRemoteBackupStore.kt`
- Create:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RemoteBackupDaoInstrumentedTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
- Modify:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/VaultDatabaseMigrationInstrumentedTest.kt`
- Create: `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/7.json`
- Create: `scripts/check-schema-drift.sh`

**Interfaces:**

- Consumes: Task 2 IDs and status categories plus existing `VaultDatabase`.
- Produces three SQLCipher tables. At most one config row may have
  `lifecycle = 'ACTIVE'`.

```kotlin
@Entity(
    tableName = "remote_backup_config",
    indices = [Index("vaultId"), Index("lifecycle")],
)
data class RemoteBackupConfigEntity(
    @PrimaryKey val lineageId: String,
    val vaultId: String,
    val controlFileId: String,
    val deviceId: String,
    val accountBindingDigest: ByteArray,
    val writerEpoch: Long,
    val providerRevision: String?,
    val lifecycle: String,
    val lastVerifiedGeneration: Long?,
    val lastVerifiedAtEpochMillis: Long?,
    val pendingGeneration: Long?,
    val failureCategory: String?,
    val recoveryCredentialGeneration: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "remote_backup_object",
    primaryKeys = ["lineageId", "objectId"],
    indices = [Index("providerFileId", unique = true)],
)
data class RemoteBackupObjectEntity(
    val lineageId: String,
    val objectId: String,
    val providerFileId: String,
    val family: String,
    val firstGeneration: Long,
    val lastGeneration: Long,
    val frameLength: Long,
    val frameSha256: String,
    val lifecycle: String,
    val resumableSessionUri: String?,
    val uploadedBytes: Long,
    val verifiedAtEpochMillis: Long?,
)

@Entity(
    tableName = "remote_backup_operation",
    indices = [Index("lineageId")],
)
data class RemoteBackupOperationEntity(
    @PrimaryKey val operationId: String,
    val lineageId: String,
    val kind: String,
    val phase: String,
    val expectedProviderRevision: String?,
    val targetGeneration: Long?,
    val pendingRecoveryEnvelope: ByteArray?,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
```

- [ ] **Step 1: Extend migration tests first**

Add a v6 fixture with user records, backup state, recovery envelope, and legacy
outbox rows. Assert v6-to-v7 creates exactly the three new tables, preserves
all bytes, changes no existing column, and updates only
`vaults.schemaVersion` from 6 to 7.

- [ ] **Step 2: Run the migration test and verify failure**

```bash
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.core.data.VaultDatabaseMigrationInstrumentedTest --stacktrace
```

Expected: failure because schema 7 and `MIGRATION_6_7` do not exist.

- [ ] **Step 3: Implement entities and exact migration**

Add the entities to `@Database`, set `version = 7`, register
`MIGRATION_6_7`, and execute:

```sql
CREATE TABLE IF NOT EXISTS remote_backup_config (
    lineageId TEXT NOT NULL,
    vaultId TEXT NOT NULL,
    controlFileId TEXT NOT NULL,
    deviceId TEXT NOT NULL,
    accountBindingDigest BLOB NOT NULL,
    writerEpoch INTEGER NOT NULL,
    providerRevision TEXT,
    lifecycle TEXT NOT NULL,
    lastVerifiedGeneration INTEGER,
    lastVerifiedAtEpochMillis INTEGER,
    pendingGeneration INTEGER,
    failureCategory TEXT,
    recoveryCredentialGeneration INTEGER NOT NULL,
    createdAtEpochMillis INTEGER NOT NULL,
    updatedAtEpochMillis INTEGER NOT NULL,
    PRIMARY KEY(lineageId)
);
CREATE INDEX IF NOT EXISTS index_remote_backup_config_vaultId
    ON remote_backup_config(vaultId);
CREATE INDEX IF NOT EXISTS index_remote_backup_config_lifecycle
    ON remote_backup_config(lifecycle);
CREATE TABLE IF NOT EXISTS remote_backup_object (
    lineageId TEXT NOT NULL,
    objectId TEXT NOT NULL,
    providerFileId TEXT NOT NULL,
    family TEXT NOT NULL,
    firstGeneration INTEGER NOT NULL,
    lastGeneration INTEGER NOT NULL,
    frameLength INTEGER NOT NULL,
    frameSha256 TEXT NOT NULL,
    lifecycle TEXT NOT NULL,
    resumableSessionUri TEXT,
    uploadedBytes INTEGER NOT NULL,
    verifiedAtEpochMillis INTEGER,
    PRIMARY KEY(lineageId, objectId)
);
CREATE UNIQUE INDEX IF NOT EXISTS
    index_remote_backup_object_providerFileId
    ON remote_backup_object(providerFileId);
CREATE TABLE IF NOT EXISTS remote_backup_operation (
    operationId TEXT NOT NULL,
    lineageId TEXT NOT NULL,
    kind TEXT NOT NULL,
    phase TEXT NOT NULL,
    expectedProviderRevision TEXT,
    targetGeneration INTEGER,
    pendingRecoveryEnvelope BLOB,
    startedAtEpochMillis INTEGER NOT NULL,
    updatedAtEpochMillis INTEGER NOT NULL,
    PRIMARY KEY(operationId)
);
CREATE INDEX IF NOT EXISTS index_remote_backup_operation_lineageId
    ON remote_backup_operation(lineageId);
UPDATE vaults SET schemaVersion = 7 WHERE schemaVersion < 7;
```

- [ ] **Step 4: Write DAO transition tests**

Cover:

- insertion rejects a second active lineage;
- dormant and deleted lineages coexist;
- compare-and-update requires expected epoch and provider revision;
- verified checkpoint clears matching pending generation only;
- operation phase changes are compare-and-set;
- resumable URI is cleared on verified or abandoned object;
- byte-array getters return defensive copies; and
- no state writer accepts negative generation, epoch, length, or upload
  offset.

- [ ] **Step 5: Implement `RoomRemoteBackupStore`**

Expose:

```kotlin
interface RemoteBackupStateStore {
    suspend fun active(vaultId: VaultId): RemoteBackupConfiguration?
    suspend fun known(lineageId: CloudLineageId): RemoteBackupConfiguration?
    fun observeActive(vaultId: VaultId): Flow<RemoteBackupConfiguration?>
    suspend fun insertInitial(configuration: RemoteBackupConfiguration)
    suspend fun transition(
        lineageId: CloudLineageId,
        expected: RemoteBackupStateVersion,
        mutation: RemoteBackupStateMutation,
    ): Boolean
}
```

Use one Room transaction to check the active count and insert or activate a
lineage. Never use `REPLACE` for configuration or operation state.

- [ ] **Step 6: Run DAO and migration tests**

```bash
./gradlew :core:data:connectedDebugAndroidTest --stacktrace
```

Expected: v6 rows and legacy outbox bytes are unchanged; DAO tests pass.

- [ ] **Step 7: Export and verify schema 7**

Create `scripts/check-schema-drift.sh` to copy committed schemas to a private
temporary directory, run `:core:data:kspDebugKotlin`, diff every versioned
schema byte-for-byte, and remove the temporary snapshot. The script exits
non-zero for a missing, added, or changed schema and never auto-restores a
working-tree difference.

```bash
./gradlew :core:data:kspDebugKotlin
./scripts/check-schema-drift.sh
git diff --check
```

Expected: committed schema 7 matches Room output and prior schema files are
unchanged.

- [ ] **Step 8: Commit Room v7**

```bash
git add core/data/src/main core/data/src/androidTest core/data/schemas \
  scripts/check-schema-drift.sh
git commit -m "feat: persist remote backup state"
```

### Task 4: Gate Startup Behind Crash-Safe Vault Slots

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/VaultSlotRegistry.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/RecoveryRegistry.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/VaultRuntimeManager.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/VaultSlotRegistryTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/RecoveryRegistryTest.kt`
- Create:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/VaultRuntimeManagerInstrumentedTest.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/ActiveVaultServices.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/ActiveVaultServicesTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/AndroidVaultKeyManager.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/LocalVaultRepositoryFactory.kt`
- Modify:
  `core/crypto/src/main/kotlin/app/opentasks/core/crypto/AndroidVaultContentKeyStore.kt`
- Modify:
  `core/crypto/src/androidTest/kotlin/app/opentasks/core/crypto/AndroidVaultContentKeyStoreInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/MainActivity.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApplication.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`

**Interfaces:**

- Consumes: existing `open_tasks.db`, existing legacy database-key preference
  and alias, `LocalVaultRuntime`, and Android `AtomicFile`.
- Produces:

```kotlin
class VaultSlot private constructor(val value: String) {
    companion object {
        val LEGACY = VaultSlot("legacy")
        fun new(): VaultSlot = VaultSlot(UUID.randomUUID().toString())
        fun parse(value: String): VaultSlot =
            if (value == LEGACY.value) LEGACY
            else VaultSlot(value).also {
                require(value.length == 36)
                require(UUID.fromString(value).toString() == value)
            }
    }

    override fun equals(other: Any?) =
        other is VaultSlot && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = "VaultSlot([redacted])"
}

data class VerifiedStagedVault(
    val slot: VaultSlot,
    val vaultId: VaultId,
    val recoveredGeneration: BackupGeneration,
)

sealed interface VaultRuntimeState {
    data object Initializing : VaultRuntimeState
    data object NoVault : VaultRuntimeState
    data class Unreadable(val preservedSlot: VaultSlot) : VaultRuntimeState
    data class Recovering(val operationId: String) : VaultRuntimeState
    data class Active(val runtime: LocalVaultRuntime) : VaultRuntimeState
}

interface VaultRuntimeManager {
    val state: StateFlow<VaultRuntimeState>
    suspend fun initialize()
    suspend fun createNewVault()
    suspend fun beginRecovery(operationId: String): VaultSlot
    suspend fun activate(staged: VerifiedStagedVault)
    fun requireActive(): LocalVaultRuntime
}
```

The active marker contains only format version and opaque slot. The encrypted
recovery registry contains operation ID, phase, previous slot, staged slot,
encrypted provider/control references, claimed epoch, and cleanup state.
`LocalVaultRuntime` owns both its Room database and its slot-scoped
`VaultContentKeyStore`.

- [ ] **Step 1: Write failing legacy and crash-matrix tests**

Cover:

- no marker plus existing `open_tasks.db` selects the legacy slot without
  rename;
- no marker and no database yields `NoVault` without key creation;
- `createNewVault` is the only no-vault path that creates a database, database
  key, content key, and random logical `VaultId`;
- reopening any slot reads its sole `VaultId` from Room instead of assuming
  `vault-primary`;
- existing key envelope plus missing Keystore alias yields `Unreadable`;
- unreadable state preserves database, WAL, SHM, preferences, and aliases;
- staged slots use random database names and independent database keys;
- slot parsing rejects non-canonical values and `toString()` is redacted;
- the same logical `VaultId` in two slots uses different content-key
  preference keys and Keystore aliases;
- the legacy slot uses the exact existing content-key preference keys,
  aliases, and associated data;
- replacing a staged slot's content key cannot change or delete the prior
  slot's wrapper;
- death before marker replacement keeps the prior slot;
- death after marker replacement opens the staged slot;
- failed first staged open atomically rolls back the unchanged prior slot;
- recovery-registry key loss discards only inactive staging; and
- active runtime closes Room before replacement;
- no repository, backup coordinator, content-key store, or active ViewModel
  dependency is constructed in `NoVault` or `Unreadable`; and
- active app services start once for `Active` and close on slot replacement.

- [ ] **Step 2: Run unit and instrumented tests and verify failure**

```bash
./gradlew :core:data:testDebugUnitTest \
  :core:crypto:connectedDebugAndroidTest \
  :core:data:connectedDebugAndroidTest \
  :app:testDebugUnitTest --stacktrace
```

Expected: missing registry, runtime manager, slot-namespaced key-store, and
active-services declarations fail compilation.

- [ ] **Step 3: Refactor database-key management without changing legacy keys**

Add:

```kotlin
fun openExistingDatabaseKey(slot: VaultSlot): ByteArray
fun createDatabaseKey(slot: VaultSlot): ByteArray
fun deleteDatabaseKey(slot: VaultSlot)
```

The `VaultSlot.LEGACY` path reads the exact existing preference keys and alias.
New slots use a SHA-256 slot digest in preference-key and alias suffixes.
`openExistingDatabaseKey` never creates an alias.

- [ ] **Step 4: Namespace content-key wrappers by vault slot**

Add a public `storageNamespace: String?` constructor parameter to
`AndroidVaultContentKeyStore`. `null` preserves the exact legacy preference
keys, aliases, and associated data. New slots pass only the SHA-256 digest of
the opaque slot; that digest namespaces the preference keys, Keystore aliases,
and wrapping associated data.

`LocalVaultRepositoryFactory.createRuntime(context, slot)` constructs and
retains the matching store in `LocalVaultRuntime`. Expose
`contentKeyStoreFor(context, slot)` only for inactive staging work. Remove the
process-global content-key-store binding from `AppModule`. Preserve
`vault-primary` only when opening the existing legacy database; new empty
vaults receive a random `VaultId`, and recovered slots retain the
authenticated source `VaultId`.

- [ ] **Step 5: Implement atomic slot and encrypted recovery registries**

Use `AtomicFile`, strict canonical JSON, a 64 KiB total bound, a distinct
Keystore AES-GCM alias, file-descriptor sync, and directory sync through an
injectable `VaultSlotFileOperations`. Registry errors contain no slot,
provider ID, or ciphertext.

- [ ] **Step 6: Implement activation ordering**

Activation must:

1. quiesce and close the active runtime;
2. checkpoint, close, reopen, and verify staging;
3. persist prior and staged slots in recovery registry;
4. write and sync a temporary marker;
5. atomically replace and directory-sync the active marker;
6. construct and verify the staged normal runtime;
7. publish `Active`; and
8. remove the prior slot and clear recovery state only after success.

- [ ] **Step 7: Gate composition without instantiating active view models**

`MainActivity` observes `VaultRuntimeState`. It calls `OpenTasksApp` only for
`Active`; other states render a temporary minimal gate owned by Task 13.
Replace eager active-vault Hilt bindings with `ActiveVaultServicesFactory`.
`OpenTasksApplication` initializes the manager and constructs existing
Android backup services only after an active runtime exists; it closes those
services before slot replacement. Inert portable-package file discovery
remains available without active-vault services.

- [ ] **Step 8: Run the protected-data compatibility tests**

```bash
./gradlew :core:crypto:connectedDebugAndroidTest \
  :core:data:connectedDebugAndroidTest \
  :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest --stacktrace
```

Expected: legacy database identity and records survive; no-vault and
unreadable tests create no replacement keys; equal logical vault IDs in two
slots keep independent content-key wrappers.

- [ ] **Step 9: Commit runtime gating**

```bash
git add core/crypto/src/main core/crypto/src/androidTest \
  core/data/src/main core/data/src/test core/data/src/androidTest \
  app/src/main/kotlin/app/opentasks app/src/test/kotlin/app/opentasks
git commit -m "feat: gate startup behind vault runtime state"
```

### Task 5: Add Explicit Authorization and Account Binding

**Files:**

- Create:
  `app/src/main/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManager.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/backup/drive/DriveAccountBinding.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManagerTest.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/drive/DriveAccountBindingTest.kt`
- Modify:
  `app/src/main/kotlin/app/opentasks/backup/drive/HttpDriveTransport.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`

**Interfaces:**

- Consumes: Task 1 `DriveTransport`, Google
  `AuthorizationClient`, and Task 3 stored account digest.
- Produces:

```kotlin
enum class DriveAuthorizationMode {
    EXPLICIT_ACCOUNT,
    NON_INTERACTIVE,
}

sealed interface DriveAuthorizationResult {
    data class Authorized(
        val session: AuthorizedDriveSession,
    ) : DriveAuthorizationResult
    data class ResolutionRequired(
        val pendingIntent: PendingIntent,
    ) : DriveAuthorizationResult
    data object AccountMismatch : DriveAuthorizationResult
    data object Unavailable : DriveAuthorizationResult
}

class AuthorizedDriveSession internal constructor(
    val transport: DriveTransport,
    accountBindingDigest: ByteArray,
) : AutoCloseable {
    fun copyAccountBindingDigest(): ByteArray
    override fun close()
}

interface GoogleDriveAuthorizationManager {
    suspend fun authorize(
        mode: DriveAuthorizationMode,
        expectedAccountDigest: ByteArray?,
    ): DriveAuthorizationResult
    suspend fun acceptResolution(
        data: Intent,
        expectedAccountDigest: ByteArray?,
    ): DriveAuthorizationResult
    suspend fun clearToken(session: AuthorizedDriveSession)
    suspend fun revokeAccess(session: AuthorizedDriveSession)
}
```

- [ ] **Step 1: Write failing authorization tests**

Cover:

```kotlin
@Test
fun nonInteractiveWrongAccountClosesTransportBeforeAnyBackupCall() = runBlocking {
    val manager = manager(
        permissionId = "account-b",
        expectedDigest = digestOf("account-a"),
    )

    assertEquals(
        DriveAuthorizationResult.AccountMismatch,
        manager.authorize(
            DriveAuthorizationMode.NON_INTERACTIVE,
            digestOf("account-a"),
        ),
    )
    assertEquals(1, manager.closedTransports)
    assertEquals(0, manager.backupApiCalls)
}
```

Also assert explicit mode sets `SELECT_ACCOUNT`, requested scopes contain only
`drive.appdata`, resolution is never persisted, `about.get` is the first
Drive call, raw permission IDs are not stored, token cache clears on `401`,
and revoke uses `RevokeAccessRequest` built from the session's in-memory
`Account` plus exactly the granted `drive.appdata` scope.

- [ ] **Step 2: Run focused tests and confirm failure**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.drive.*Authorization*Test' \
  --tests 'app.opentasks.backup.drive.DriveAccountBindingTest' --stacktrace
```

Expected: compilation failure for the manager and binding key.

- [ ] **Step 3: Implement the HMAC binding**

Create a non-exportable Android Keystore HMAC-SHA-256 key using alias:

```text
open_tasks_drive_account_binding_hmac_v1
```

`DriveAccountBinding.digest(permissionId)` encodes strict UTF-8, computes HMAC,
returns a new 32-byte array, and clears the UTF-8 buffer. It never exposes the
raw ID after return.

- [ ] **Step 4: Implement authorization without token persistence**

Bridge Google `Task` to suspension with `suspendCancellableCoroutine`; do not
add `kotlinx-coroutines-play-services`. Request no offline access. Convert a
successful token result immediately into a session-bound
`HttpDriveTransport`, call `about.get(fields=user(permissionId))`, compare
digests with `MessageDigest.isEqual`, and close the transport on mismatch.
The session privately owns the immutable access-token and `Account`
references needed by `ClearTokenRequest` and `RevokeAccessRequest`; neither
reference is exposed, persisted, or logged, and both are dropped on close.

- [ ] **Step 5: Run unit and release-shrinker checks**

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease --stacktrace
```

Expected: authorization tests and R8 pass; no keep rule exposes debug
qualification classes.

- [ ] **Step 6: Commit authorization**

```bash
git add app/src/main/kotlin/app/opentasks/backup/drive \
  app/src/test/kotlin/app/opentasks/backup/drive \
  app/src/main/kotlin/app/opentasks/di/AppModule.kt
git commit -m "feat: bind Drive backup authorization"
```

### Task 6: Implement Verified Drive Object Storage

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/DriveBackupObjectStore.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/DriveBackupObjectStoreTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RemoteBackupDaos.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomRemoteBackupStore.kt`
- Modify:
  `app/src/main/kotlin/app/opentasks/backup/AndroidBackupFiles.kt`

**Interfaces:**

- Consumes: Task 1 `DriveTransport`, Task 2 `BackupObjectStore`, and Task 3
  durable object rows.
- Produces:

```kotlin
class DriveBackupObjectStore(
    private val transport: DriveTransport,
    private val transferStore: RemoteBackupTransferStore,
    private val stagingRoot: File,
) : BackupObjectStore
```

Drive app properties are exactly:

```text
openTasksFormat=backup-v1
openTasksRole=control|snapshot|segment
```

File names are `open-tasks-control-v1`, `open-tasks-snapshot-v1`, and
`open-tasks-segment-v1`. Names and properties contain no lineage, generation,
vault, device, account, or content value.

- [ ] **Step 1: Write failing object-store tests**

Cover discovery pagination, pre-generated IDs, duplicate create, missing
control, current/stale conditional update mapping, multipart upload at or
below 5 MiB, resumable upload above 5 MiB, 256 KiB non-final chunks,
session-query resume, expired-session restart with the same pre-generated
file ID, private staging, exact download length, and permanent deletion.

Example:

```kotlin
@Test
fun resumableRetryContinuesAtProviderConfirmedOffset() = runBlocking {
    val store = storeWithInterruptedUpload(
        confirmedOffset = 2L * 256 * 1024,
    )

    val result = store.uploadImmutable(request(frameFile = LARGE_FRAME_FILE))

    assertTrue(result is ImmutableUploadResult.Uploaded)
    assertEquals(2L * 256 * 1024, store.firstRetriedChunkOffset)
    assertNull(store.persistedSessionUri)
}
```

- [ ] **Step 2: Run the focused tests and confirm failure**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests 'app.opentasks.core.data.backup.DriveBackupObjectStoreTest' \
  --stacktrace
```

Expected: compilation failure for `DriveBackupObjectStore`.

- [ ] **Step 3: Add exact private staging paths**

Extend `AndroidBackupFiles`:

```kotlin
val remoteTransferRoot =
    File(context.noBackupFilesDir, "backup/remote-transfer/v1")
val recoveryRoot =
    File(context.noBackupFilesDir, "recovery/staging/v1")
```

Assert both remain excluded by packaged Android backup rules.

- [ ] **Step 4: Implement control operations**

Map transport results one-for-one to domain results. `readControl` copies
the bounded private control file into `OwnedRemoteBytes`, deletes the staging
file, and returns the opaque revision. No control ID is invented after
`Missing`.

- [ ] **Step 5: Implement immutable upload and download**

Before upload, persist provider ID, expected frame length/checksum, and
candidate state. After create or resume, use `downloadFile` to stream into a
fresh private file, sync it, verify exact byte count and SHA-256, then return
an owned file-backed stream to the coordinator. Clear the resumable URI and
offset only after verification. `close()` on that owned download closes the
stream and removes its private staging file.

On duplicate create, download the existing file and accept it only if exact
length, checksum, and later AEAD verification match the requested object.

- [ ] **Step 6: Run store, packaged-rule, and Room tests**

```bash
./gradlew :core:data:testDebugUnitTest \
  :app:connectedDebugAndroidTest --stacktrace
```

Expected: store tests, migration tests, and exact Android backup exclusions
pass.

- [ ] **Step 7: Commit object storage**

```bash
git add core/data/src/main/kotlin/app/opentasks/core/data/backup \
  core/data/src/test/kotlin/app/opentasks/core/data/backup \
  app/src/main/kotlin/app/opentasks/backup/AndroidBackupFiles.kt \
  app/src/androidTest
git commit -m "feat: verify Drive backup objects"
```

### Task 7: Publish the Initial Epoch-One Backup

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RemoteObjectCodec.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/DefaultRemoteBackupConfigurator.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/RemoteObjectCodecTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/DefaultRemoteBackupConfiguratorTest.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/LocalVaultRepositoryFactory.kt`

**Interfaces:**

- Consumes: active `LocalVaultRuntime`, Stage 2 `BackupCoordinator`,
  `LocalBackupObjectStore`, recovery-envelope store, content-key store, Task 2
  control codec, Task 3 state store, and Task 6 object store.
- Produces:

```kotlin
sealed interface RemoteBackupConnectResult {
    data class ExistingBackupsFound(val count: Int) :
        RemoteBackupConnectResult
    data class Connected(
        val lineageId: CloudLineageId,
        val generation: BackupGeneration,
    ) : RemoteBackupConnectResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        RemoteBackupConnectResult
}

interface RemoteBackupConfigurator {
    suspend fun connect(
        objectStore: BackupObjectStore,
        accountBindingDigest: ByteArray,
        allowSeparateLineage: Boolean,
    ): RemoteBackupConnectResult
}
```

`RemoteObjectCodec` exposes:

```kotlin
fun reauthenticateLocalObject(
    localObjectId: String,
    localStore: LocalBackupObjectStore,
    sourceVaultId: VaultId,
    lineageId: CloudLineageId,
    key: VaultKey,
    stagingRoot: File,
): OwnedRemoteFile
```

- [ ] **Step 1: Write failing re-authentication tests**

Assert local frame AEAD and decoded payload are checked before a remote frame
is emitted; the remote header uses `lineageId` as `vaultId`; source payload
still contains the original `VaultId`; family/object/generation disagreement
fails; all owned plaintext buffers clear; and closing the returned owner
removes its private frame file.

- [ ] **Step 2: Write failing initial-connection state-machine tests**

Cover:

- discovery occurs before any ID generation;
- existing controls return `ExistingBackupsFound`;
- `allowSeparateLineage=false` performs no create;
- Stage 2 complete snapshot is requested and verified;
- lineage, device, control ID, and snapshot provider ID persist before create;
- immutable snapshot upload and readback precede control creation;
- epoch is exactly 1;
- control readback precedes enabled status;
- crash at each phase resumes without a second control;
- account digest is defensively copied; and
- failure never marks backup enabled.

- [ ] **Step 3: Run focused tests and verify failure**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests 'app.opentasks.core.data.backup.RemoteObjectCodecTest' \
  --tests 'app.opentasks.core.data.backup.DefaultRemoteBackupConfiguratorTest' \
  --stacktrace
```

Expected: compilation failure for both production classes.

- [ ] **Step 4: Expose local backup dependencies without starting work**

Add the already constructed `LocalBackupObjectStore` to
`LocalVaultRuntime`, and make `LocalVaultRuntime` `AutoCloseable` by retaining
its `VaultDatabase`. Preserve current Stage 2 startup ordering and existing
factory overloads.

- [ ] **Step 5: Implement remote frame re-authentication**

Read the local frame through `AuthenticatedCloudObjectCodec`, require the
expected local header, strictly decode the snapshot or segment, re-encode
canonical plaintext, reject it before encryption if its family bound would be
exceeded, and encrypt with:

```kotlin
CloudHeaderIdentity(
    family = family,
    schemaVersion = 1,
    cryptoVersion = 1,
    minimumReaderVersion = 1,
    vaultId = lineageId.value,
    objectId = localObjectId,
)
```

Write the bounded returned frame to a fresh private file, sync it, clear the
owned frame and plaintext arrays, and remove a partial destination on any
failure.

- [ ] **Step 6: Implement crash-resumable initial publication**

Use one `remote_backup_operation` row with phases:

```text
DISCOVERED
IDENTITIES_PERSISTED
LOCAL_BASE_VERIFIED
IMMUTABLE_UPLOADED
IMMUTABLE_VERIFIED
CONTROL_CREATED
CONTROL_VERIFIED
COMPLETED
```

Only `CONTROL_VERIFIED -> COMPLETED` activates the configuration. Any retry
loads the persisted IDs and continues from the last verified phase.

- [ ] **Step 7: Run focused and Stage 2 coordinator tests**

```bash
./gradlew :core:data:testDebugUnitTest --stacktrace
```

Expected: initial connection and all prior local backup tests pass.

- [ ] **Step 8: Commit epoch-one publication**

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain \
  core/data/src/main/kotlin/app/opentasks/core/data \
  core/data/src/test/kotlin/app/opentasks/core/data/backup
git commit -m "feat: publish initial Drive backup"
```

### Task 8: Publish Incremental Backups and Safe Retention

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/DefaultRemoteBackupCoordinator.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/DefaultRemoteBackupCoordinatorTest.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RemoteBackupDaos.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomRemoteBackupStore.kt`

**Interfaces:**

- Consumes: active remote configuration and all Task 7 publication inputs.
- Produces:

```kotlin
sealed interface RemoteBackupRunResult {
    data class Verified(val generation: BackupGeneration) :
        RemoteBackupRunResult
    data object NoChanges : RemoteBackupRunResult
    data object AuthorizationRequired : RemoteBackupRunResult
    data object AccountMismatch : RemoteBackupRunResult
    data object OwnershipLost : RemoteBackupRunResult
    data class Retryable(val reason: RemoteBackupFailureCategory) :
        RemoteBackupRunResult
    data class Blocked(val reason: RemoteBackupFailureCategory) :
        RemoteBackupRunResult
}

interface RemoteBackupCoordinator {
    suspend fun run(objectStore: BackupObjectStore): RemoteBackupRunResult
}
```

- [ ] **Step 1: Write the publication matrix as failing tests**

Cover:

- single-flight coalescing;
- local Stage 2 request before remote selection;
- unchanged generation returns `NoChanges`;
- current control authentication before upload;
- expected lineage, source vault, epoch, and device;
- snapshot and segment successor inventories;
- immutable readback before control compare-and-swap;
- current control readback before remote checkpoint;
- higher epoch or different device returns `OwnershipLost`;
- same-device newer generation coalesces;
- unexplained same-epoch conflict fails closed;
- `401` and authorization-related `403` return `AuthorizationRequired`;
- storage-quota `403` returns `Blocked(PROVIDER_STORAGE)`;
- known-control `404` returns `OwnershipLost` and is never recreated;
- `412` rereads and authenticates the winner before conflict classification;
- rate-limit `403`, `429`, and `5xx` return
  `Retryable(RETRYABLE_PROVIDER)`;
- absent or weak conditional revisions return
  `Blocked(CONDITIONAL_WRITE_UNAVAILABLE)`;
- no checkpoint on cancellation or failure; and
- app failure strings contain no provider response or ID.

- [ ] **Step 2: Write retention interleaving tests**

Model predecessor `P`, successor `S`, takeover `T`, and prunable object `O`.
Assert:

```kotlin
@Test
fun takeoverCannotReferenceObjectRemovedByPublishedSuccessor() {
    val result = model.publishSuccessorThenTakeoverThenPrune()

    assertFalse(result.takeover.inventory.any { it.objectId == "O" })
    assertTrue(result.pruneAllowed)
}
```

Also assert current and previous bases plus required segments remain; deletion
requires current epoch/device and expected revision; newly referenced objects
were freshly uploaded and verified; and orphan cleanup requires authenticated
lineage, absence, current ownership, and minimum age. Once an object is absent
from a published successor, a later manifest may reference that object ID only
after fresh upload and readback verification in that later operation.

- [ ] **Step 3: Run focused tests and verify failure**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests 'app.opentasks.core.data.backup.DefaultRemoteBackupCoordinatorTest' \
  --stacktrace
```

Expected: compilation failure for the coordinator.

- [ ] **Step 4: Implement one process-scoped coordinator**

Use the same owner/joiner pattern as `DefaultBackupCoordinator`: joined callers
observe owner cancellation or failure, pending requests coalesce, and
ownership release plus pending check is one mutex transition.

- [ ] **Step 5: Implement publish-control-last**

Durable phases are:

```text
CONTROL_READ
LOCAL_VERIFIED
CANDIDATES_PERSISTED
IMMUTABLES_UPLOADED
IMMUTABLES_VERIFIED
CONTROL_APPLIED
CONTROL_VERIFIED
CHECKPOINTED
PRUNED
```

After `Conflict`, reread and authenticate. Never reuse candidate bytes from
another lineage or epoch.

- [ ] **Step 6: Implement retention and orphan cleanup**

Build the retained set from the authenticated successor, not only Room cache.
Before every delete batch, reread the control and require the expected
revision, epoch, and device. Stop the batch on the first mismatch.

- [ ] **Step 7: Run all core data tests**

```bash
./gradlew :core:data:testDebugUnitTest --stacktrace
```

Expected: publication, retention, Stage 1/2 format, and local coordinator tests
pass.

- [ ] **Step 8: Commit incremental publication**

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain \
  core/data/src/main/kotlin/app/opentasks/core/data/backup \
  core/data/src/test/kotlin/app/opentasks/core/data/backup
git commit -m "feat: publish verified remote backup generations"
```

### Task 9: Schedule Unique Background Backup Work

**Files:**

- Modify: `app/build.gradle.kts`
- Create:
  `app/src/main/kotlin/app/opentasks/backup/RemoteBackupWorker.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/backup/RemoteBackupWorkerFactory.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/backup/WorkManagerRemoteBackupScheduler.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/backup/RemoteBackupRuntime.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/WorkManagerRemoteBackupSchedulerTest.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/RemoteBackupWorkerTest.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/RemoteBackupRuntimeTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApplication.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt`

**Interfaces:**

- Consumes: Task 5 authorization, Task 8 coordinator, Task 4 active runtime,
  and `backup_state.currentGeneration`.
- Produces constant unique names:

```text
open-tasks-remote-backup-once-v1
open-tasks-remote-backup-periodic-v1
```

and:

```kotlin
interface BackupWorkScheduler {
    fun onPendingGeneration()
    fun ensurePeriodic()
    fun cancelAll()
}

interface RemoteBackupRunner {
    suspend fun run(): RemoteBackupRunResult
}
```

- [ ] **Step 1: Add WorkManager to the app**

Add `implementation(libs.work.runtime)`; do not add Hilt WorkManager or a
second dependency-injection library.

- [ ] **Step 2: Write failing request-construction tests**

Assert:

```kotlin
assertEquals(Duration.ofMinutes(15), oneTime.initialDelay)
assertEquals(Duration.ofHours(24), periodic.repeatInterval)
assertEquals(Duration.ofHours(6), periodic.flexInterval)
assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
assertTrue(constraints.requiresBatteryNotLow())
assertTrue(constraints.requiresStorageNotLow())
```

Assert `ExistingWorkPolicy.REPLACE` for debounce, `KEEP` for periodic, 30
seconds exponential backoff, no input data, and constant work names.

- [ ] **Step 3: Write failing worker result tests**

Map `Retryable` to `Result.retry()`. Map verified, no changes,
authorization-required, account mismatch, ownership loss, and blocked to
`Result.success()` after their bounded status is persisted. No worker starts a
`PendingIntent`.

- [ ] **Step 4: Run focused tests and verify failure**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.RemoteBackup*Test' --stacktrace
```

Expected: missing scheduler, runner, worker, and factory declarations.

- [ ] **Step 5: Implement an injected custom `WorkerFactory`**

`OpenTasksApplication` implements `Configuration.Provider` and supplies
`RemoteBackupWorkerFactory`. The factory accepts only
`RemoteBackupRunner`; the worker receives no lineage, Drive ID, account
digest, token, provider revision, or passphrase in `WorkerParameters`.
`WorkManagerRemoteBackupScheduler` implements the provider-independent
`BackupWorkScheduler`; no WorkManager type enters `core:domain`.

- [ ] **Step 6: Implement runtime authorization behavior**

The runner loads the active configuration, requests non-interactive
authorization with its expected digest, constructs a session-bound
`DriveBackupObjectStore`, calls the coordinator, and closes the session in
`finally`. `AccountMismatch` persists `ACCOUNT_MISMATCH` without constructing
an object store. `ResolutionRequired` persists
`AUTHORIZATION_REQUIRED` and returns without retry.

If a provider call returns `AUTHORIZATION`, clear the session token through
`GoogleDriveAuthorizationManager` before closing the session, persist
`AUTHORIZATION_REQUIRED`, and complete without launching UI.

- [ ] **Step 7: Observe local generations only while active**

`RemoteBackupRuntime` starts after `VaultRuntimeState.Active`, observes
`backup_state`, enqueues one-time work only when remote backup is active and
the current generation exceeds the remote verified generation, and ensures
periodic work once. It cancels remote work on runtime deactivation.

- [ ] **Step 8: Run unit, manifest, and startup tests**

```bash
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest --stacktrace
```

Expected: exact WorkManager behavior and existing Android backup startup
ordering pass.

- [ ] **Step 9: Commit scheduling**

```bash
git add app/build.gradle.kts app/src/main/kotlin/app/opentasks \
  app/src/test app/src/androidTest \
  core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt
git commit -m "feat: schedule remote backup work"
```

### Task 10: Reconstruct a Verified Staging Database

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RecoveryImportDao.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordImporter.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/StagedVaultVerifier.kt`
- Create:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/backup/BackupRecordImporterInstrumentedTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/LocalVaultRepositoryFactory.kt`

**Interfaces:**

- Consumes: strict `BackupSnapshotPayloadV1`, ordered
  `BackupOperationSegmentPayloadV1`, recovery envelope, Task 4 staged slot,
  and a newly generated SQLCipher key.
- Produces a populated instance of the Task 4 staging descriptor:

```kotlin
data class RecoveryImportRequest(
    val snapshot: BackupSnapshotPayloadV1,
    val segments: List<BackupOperationSegmentPayloadV1>,
    val recoveryEnvelope: VaultKeyEnvelope,
    val expectedGeneration: BackupGeneration,
)

interface BackupRecordImporter {
    suspend fun importInto(
        database: VaultDatabase,
        request: RecoveryImportRequest,
    )
}

class RoomBackupRecordImporter(
    private val importDao: RecoveryImportDao,
) : BackupRecordImporter
```

- [ ] **Step 1: Write an exhaustive failing family test**

Build one valid record of each `BackupRecordFamily`, import into an empty
database, and query exact entity bytes. Include composite relation identities,
nullable fields, ciphertext bytes, revisions, tombstones, and attachment
metadata.

Use:

```kotlin
for (family in BackupRecordFamily.entries) {
    assertEquals(
        "missing import assertion for $family",
        1,
        importedCount(database, family),
    )
}
```

- [ ] **Step 2: Write failing replay and rejection tests**

Cover UPSERT after-images, DELETE for every family, ordered multi-entry
generation, duplicate identities, gaps or overlaps in supplied segment
inventory, reversed ranges, source vault mismatch, relation failure,
foreign-key failure, invalid tombstone, future schema, and over-bound payload.

Assert no `remote_backup_*`, `sync_operations`, source `backup_journal`, or
source `backup_state` operation rows are imported.

- [ ] **Step 3: Run the importer suite and verify failure**

```bash
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.core.data.backup.BackupRecordImporterInstrumentedTest \
  --stacktrace
```

Expected: missing importer and DAO declarations fail compilation.

- [ ] **Step 4: Implement typed field access**

Create `BackupRecordFields` that first calls
`BackupMutationCodec.validateRecord` and then exposes:

```kotlin
fun string(name: String): String
fun nullableString(name: String): String?
fun long(name: String): Long
fun nullableLong(name: String): Long?
fun int(name: String): Int
fun nullableInt(name: String): Int?
fun boolean(name: String): Boolean
fun bytes(name: String): ByteArray
```

Every `bytes` caller owns and clears the returned array after Room copies it.

- [ ] **Step 5: Implement all record mappings explicitly**

Use one exhaustive `when (record.family)` and named conversions:

```text
toVaultEntity
toWorkspaceEntity
toMemberEntity
toProjectEntity
toWorkflowStatusEntity
toMilestoneEntity
toTaskEntity
toChecklistItemEntity
toTaskDependencyEntity
toTagEntity
toTaskTagEntity
toReminderEntity
toAttachmentEntity
toActivityEntryEntity
toTimeEntryEntity
toTemplateEntity
toSavedViewEntity
toTombstoneEntity
```

The DAO uses `ABORT` for snapshot insertion. Segment UPSERT uses explicit
family-specific upsert. Segment DELETE uses exact primary or composite
identity and requires either one deleted row or a proven idempotent absence.

- [ ] **Step 6: Initialize fresh local operational state**

After replay:

- set `backup_state.currentGeneration` to the recovered generation;
- leave local verified base and segment IDs null;
- set package state to `NOT_PREPARED`;
- store the recovery envelope;
- leave `backup_journal`, `sync_operations`, and all remote tables empty; and
- mark a fresh complete Stage 2 baseline required before future remote
  publication.

- [ ] **Step 7: Verify and reopen staging**

Run `PRAGMA foreign_key_check`, SQLCipher integrity check, family counts,
identity and relation checks, then close and reopen with the new database key.
Construct a normal repository, read `currentWorkspace()`, and compare its
canonical capture to the recovered payload.

- [ ] **Step 8: Run importer, migration, and repository suites**

```bash
./gradlew :core:data:connectedDebugAndroidTest --stacktrace
```

Expected: exhaustive family, replay, integrity, migration, and existing
repository tests pass.

- [ ] **Step 9: Commit staged import**

```bash
git add core/data/src/main core/data/src/androidTest
git commit -m "feat: reconstruct verified staging vaults"
```

### Task 11: Recover from Drive or Portable Backup and Take Ownership

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/DefaultRecoveryCoordinator.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/DefaultRecoveryCoordinatorTest.kt`
- Create:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/backup/RecoveryActivationInstrumentedTest.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/PortableBackupCodec.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/VaultRuntimeManager.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/LocalVaultRepositoryFactory.kt`

**Interfaces:**

- Consumes: Drive control candidates, inert portable package, `VaultCrypto`,
  Task 10 importer, Task 4 runtime manager, and current account digest.
- Produces:

```kotlin
data class RecoveryCandidate(
    val source: RecoverySource,
    val lineageId: CloudLineageId?,
    val sourceVaultId: VaultId?,
    val createdAt: Instant,
)

sealed interface RecoveryResult {
    data class TakeoverConfirmationRequired(
        val candidate: RecoveryCandidate,
        val generation: BackupGeneration,
        val currentWriterEpoch: Long,
    ) : RecoveryResult
    data class Activated(
        val generation: BackupGeneration,
        val lineageId: CloudLineageId?,
    ) : RecoveryResult
    data class Failed(val reason: RecoveryFailureCategory) : RecoveryResult
}

interface RecoveryCoordinator {
    suspend fun discover(
        objectStore: BackupObjectStore?,
        portablePackage: File?,
    ): List<RecoveryCandidate>
    suspend fun prepare(
        candidate: RecoveryCandidate,
        passphrase: CharArray,
        objectStore: BackupObjectStore?,
        accountBindingDigest: ByteArray?,
    ): RecoveryResult
    suspend fun confirmTakeover(operationId: String): RecoveryResult
}
```

- [ ] **Step 1: Extend portable decode tests first**

Add a `decodeComplete` path that returns the strict snapshot and envelope to
the recovery caller after AEAD verification. Assert existing
`verifyComplete` behavior and Android package bounds remain byte-identical.

- [ ] **Step 2: Write failing recovery-source tests**

Cover bootstrap discovery without plaintext, wrong passphrase, KDF bound
failure before derivation, current Drive base, previous-base fallback,
required segment download, missing segment, Drive/portable generation choice,
future format, AEAD failure, identity disagreement, and insufficient storage.
Require the exact mappings `WRONG_PASSPHRASE`, `UNSAFE_KDF`,
`MISSING_REQUIRED_OBJECT`, `CORRUPT_OR_INCOMPATIBLE`, and
`INSUFFICIENT_STORAGE`; no exception or provider diagnostic reaches UI state.

- [ ] **Step 3: Write failing takeover race tests**

Cover:

- control reread after staging;
- changed control causes staged refresh before confirmation;
- explicit confirmation required;
- compare-and-swap publishes exactly `epoch + 1`;
- exact recovered inventory remains referenced;
- conflict never activates;
- successful claim requires authenticated readback;
- crash after claim resumes the same operation and epoch;
- old device's next publication returns `OwnershipLost`; and
- missing known control is never recreated.

- [ ] **Step 4: Run focused tests and verify failure**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests 'app.opentasks.core.data.backup.DefaultRecoveryCoordinatorTest' \
  --stacktrace
```

Expected: missing coordinator declarations fail compilation.

- [ ] **Step 5: Implement bounded source discovery and authentication**

For Drive, list only control-role files, read bootstrap, and expose only
creation time plus opaque candidate handle. For portable, read the inert
package bootstrap. Unlock the content key only inside
`prepare`, clear the supplied `CharArray` in the caller, and close the key in
`finally`.

- [ ] **Step 6: Implement current/previous recovery selection**

Authenticate the control, validate inventory coverage, try current base plus
later segments, and on integrity failure try previous base plus every segment
after it. Do not fall back after account mismatch, future format, weakened
KDF, or writer-control identity failure.

- [ ] **Step 7: Implement takeover and activation**

After Task 10 verifies staging, reread the control. On an unchanged revision,
install the recovered content key through the Task 4 slot-scoped staging
`VaultContentKeyStore.replace`, persist a fresh staged `CloudDeviceId` plus
fresh local account/config and resumable-operation state, close the raw key,
then publish `writerEpoch + 1`. Authenticate readback and call
`VaultRuntimeManager.activate`.
Never call `replace` on the prior active runtime's content-key store, even
when both databases contain the same logical `VaultId`.

The staged database key and staged content-key wrapper must both be durable
before the conditional claim. Therefore a crash after claim resumes without
the passphrase or a persisted raw content key and reuses the already claimed
epoch instead of incrementing again.

If the cloud lineage was deleted and recovery uses portable input, activate
with no remote config and require a future explicit new-lineage connection.

- [ ] **Step 8: Verify process-death activation on a disposable device**

Instrument suspension points immediately before control claim, after claim,
before marker replacement, and after marker replacement. Kill only the test
process on the disposable target, relaunch, and assert the active vault is
either the unchanged prior slot or the fully verified recovered slot.

- [ ] **Step 9: Run recovery and all connected data tests**

```bash
./gradlew :core:data:testDebugUnitTest \
  :core:data:connectedDebugAndroidTest --stacktrace
```

Expected: source, fallback, takeover, activation, and existing database tests
pass.

- [ ] **Step 10: Commit recovery**

```bash
git add core/domain/src/main core/data/src/main core/data/src/test \
  core/data/src/androidTest
git commit -m "feat: recover and take over backup lineages"
```

### Task 12: Add Passphrase Change, Disconnect, and History Deletion

**Files:**

- Create:
  `app/src/main/kotlin/app/opentasks/backup/RecoveryPassphraseChanger.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/RecoveryPassphraseChangerTest.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/backup/DefaultRemoteBackupLifecycleCoordinator.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/DefaultRemoteBackupLifecycleCoordinatorTest.kt`
- Modify:
  `app/src/main/kotlin/app/opentasks/backup/PortableBackupPublisher.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RemoteControlCodec.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RemoteBackupDaos.kt`
- Modify:
  `app/src/main/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManager.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt`

**Interfaces:**

- Consumes: active content key, local recovery envelope, portable publisher,
  current writer control, remote operation journal, scheduler, and
  authorization manager.
- Produces:

```kotlin
interface RecoveryPassphraseChanger {
    suspend fun change(
        currentPassphrase: CharArray,
        newPassphrase: CharArray,
        objectStore: BackupObjectStore?,
    ): PassphraseChangeResult
}

sealed interface PassphraseChangeResult {
    data class Changed(
        val olderCopiesRemainUsable: Boolean = true,
    ) : PassphraseChangeResult
    data class Failed(
        val reason: PassphraseChangeFailureCategory,
    ) : PassphraseChangeResult
}

interface RemoteBackupLifecycleCoordinator {
    suspend fun disconnect(): LifecycleResult
    suspend fun deleteHistory(
        passphrase: CharArray,
        objectStore: BackupObjectStore,
    ): LifecycleResult
    suspend fun preserveDivergentWorkAsNewLineage(
        objectStore: BackupObjectStore,
        accountBindingDigest: ByteArray,
    ): RemoteBackupConnectResult
}

sealed interface LifecycleResult {
    data class Disconnected(
        val authorizationRevoked: Boolean,
    ) : LifecycleResult
    data object HistoryDeleted : LifecycleResult
    data object OwnershipRequired : LifecycleResult
    data class Failed(
        val reason: RemoteBackupFailureCategory,
    ) : LifecycleResult
}
```

- [ ] **Step 1: Write passphrase-change crash tests**

Cover current-passphrase verification, same content key, new envelope,
portable readback before remote publication, remote compare-and-swap/readback
before local promotion, no Drive path when remote is disabled, and death at
every phase. Assert old local envelope remains active before final promotion,
pending envelope bytes live only in SQLCipher, and all mutable passphrases and
keys clear.

- [ ] **Step 2: Write disconnect and deletion state tests**

Assert disconnect journals intent, cancels work, prevents a new runner from
starting, requests non-interactive authorization only to clear/revoke the
bound account when available, closes the session, marks config dormant even
when revocation is unavailable, and makes zero Drive file list/read/write or
delete calls. Kill at every phase and assert restart completes locally
dormant without re-enabling work.

Assert delete:

1. requires current writer ownership and passphrase proof;
2. conditionally publishes `DELETING`;
3. authenticates readback;
4. deletes immutable objects;
5. deletes control last;
6. persists a local deleted tombstone; and
7. resumes partial deletion without republishing.

Assert an ownership-lost device must take over before delete.

- [ ] **Step 3: Run focused tests and verify failure**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.RecoveryPassphraseChangerTest' \
  --tests 'app.opentasks.backup.DefaultRemoteBackupLifecycleCoordinatorTest' \
  --stacktrace
```

Expected: missing lifecycle classes fail compilation.

- [ ] **Step 4: Add explicit-envelope portable publication**

Refactor `PortableBackupPublisher` so its existing path delegates to:

```kotlin
suspend fun publishWithEnvelope(
    envelope: VaultKeyEnvelope,
): AndroidBackupStatus
```

The method verifies the package but does not promote the Room recovery
envelope. Existing Stage 2 setup behavior remains unchanged.

- [ ] **Step 5: Implement passphrase-change phases**

Use:

```text
PENDING_ENVELOPE_STORED
PORTABLE_VERIFIED
REMOTE_CONTROL_APPLIED
REMOTE_CONTROL_VERIFIED
LOCAL_ENVELOPE_PROMOTED
COMPLETED
```

Increment `recoveryCredentialGeneration`, not writer epoch. The UI result must
carry a fixed disclosure that older Android, Drive-revision, or copied
packages are not revoked.

- [ ] **Step 6: Implement disconnect and deleting-state cleanup**

Disconnect sends no Drive request after authorization revocation begins.
`DefaultRemoteBackupLifecycleCoordinator` implements the domain interface and
composes the scheduler and authorization manager with provider-independent
Room state/object-store contracts; no app type is imported by `core:data`. A
failed or unavailable token-clear/revoke attempt is recorded as a bounded
local disclosure and cannot reactivate backup.

Deletion permanently removes only authenticated provider IDs from the
deleting manifest. A missing object is idempotent; a missing control after all
known objects are removed completes the deleted tombstone.

- [ ] **Step 7: Implement divergent-lineage preservation**

Only a locally known ownership-lost configuration may invoke this action.
Create a fresh lineage through Task 7 using the same bound account. Do not
change source `VaultId`, merge any remote record, or reactivate the lost
lineage.

- [ ] **Step 8: Run lifecycle and existing Stage 2 tests**

```bash
./gradlew :app:testDebugUnitTest :core:data:testDebugUnitTest --stacktrace
```

Expected: lifecycle tests and all portable-package tests pass.

- [ ] **Step 9: Commit lifecycle operations**

```bash
git add app/src/main/kotlin/app/opentasks/backup \
  app/src/test/kotlin/app/opentasks/backup \
  core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/backup \
  core/data/src/test/kotlin/app/opentasks/core/data/backup \
  app/src/main/kotlin/app/opentasks/di/AppModule.kt
git commit -m "feat: manage remote backup lifecycle"
```

### Task 13: Build Backup and Recovery Product Surfaces

**Files:**

- Create:
  `app/src/main/kotlin/app/opentasks/backup/EncryptedBackupViewModel.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/backup/RecoveryViewModel.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/EncryptedBackupViewModelTest.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/backup/RecoveryViewModelTest.kt`
- Create:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/RecoveryShellScreen.kt`
- Create:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/RecoveryShellScreenInstrumentedTest.kt`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/BackupRecoveryScreen.kt`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`
- Modify: `feature/more/src/main/res/values/strings.xml`
- Modify:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/BackupRecoveryScreenInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/MainActivity.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`
- Modify:
  `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt`

**Interfaces:**

- Consumes: independent `RemoteBackupStatus` and `AndroidBackupStatus`,
  Task 5 ephemeral authorization resolution, Task 4 runtime states, Task 11
  recovery, and Task 12 lifecycle actions.
- Produces stateless feature contracts:

```kotlin
data class EncryptedBackupPresentation(
    val status: RemoteBackupStatus,
    val canBackUpNow: Boolean,
    val canRestore: Boolean,
    val canDisconnect: Boolean,
    val canDeleteHistory: Boolean,
    val passphraseChangeDisclosureVisible: Boolean,
)

sealed interface RecoveryPresentation {
    data object NoVault : RecoveryPresentation
    data object UnreadableVault : RecoveryPresentation
    data object Discovering : RecoveryPresentation
    data class Candidates(val values: List<RecoveryCandidateSummary>) :
        RecoveryPresentation
    data object Authenticating : RecoveryPresentation
    data class TakeoverConfirmation(val generation: Long) :
        RecoveryPresentation
    data object Activating : RecoveryPresentation
    data class Failed(val reason: RecoveryFailureCategory) :
        RecoveryPresentation
}
```

- [ ] **Step 1: Write failing ViewModel tests**

Cover:

- Connect requests explicit account selection.
- Resolution `PendingIntent` is emitted as a one-shot effect and is not in
  saved state.
- Existing backups offer Restore or explicit separate-lineage creation.
- `Back up now` uses the same runner and blocks duplicate taps.
- Background authorization-required state opens foreground authorization.
- Wrong account offers account selection but no switch/migration action.
- Ownership loss offers new-lineage preservation or explicit takeover.
- Disconnect and delete are distinct.
- Delete and passphrase change require fresh passphrase arrays and clear them.
- Recovery candidates reveal no content before authentication.
- Process restoration contains no passphrase, token, Drive ID, revision,
  lineage ID, device ID, or account digest.

- [ ] **Step 2: Run ViewModel tests and verify failure**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.EncryptedBackupViewModelTest' \
  --tests 'app.opentasks.backup.RecoveryViewModelTest' --stacktrace
```

Expected: missing ViewModels fail compilation.

- [ ] **Step 3: Implement the two app-layer ViewModels**

Use `viewModelScope` and one operation mutex per ViewModel. Keep
`PendingIntent` in a non-replay one-shot channel. Convert UI strings to
`CharArray` only at the service boundary and clear them in `finally`.

- [ ] **Step 4: Write failing Compose tests for the two-card screen**

Required headings and tags:

```text
encrypted-backup-heading
encrypted-backup-connect
encrypted-backup-now
encrypted-backup-reauthorize
encrypted-backup-preserve
encrypted-backup-disconnect
encrypted-backup-delete
android-backup-heading
recovery-shell
recovery-drive
recovery-portable
recovery-passphrase
recovery-takeover-confirm
```

Test compact, expanded, separating fold, 100%, 130%, and 200% text. Assert
48 dp actions, headings, focus order, scroll reachability, masked passphrases,
IME next/done, error semantics, and no use of the word `sync`. A verified
state must display its generation and locally formatted verification time;
retry and action-required states must never present an unverified time as
successful.

- [ ] **Step 5: Implement exact user-facing status copy**

The encrypted card maps to:

```text
Off
Preparing
Backing up
Backed up
Waiting to retry
Needs re-authorisation
Wrong Google account
This device is no longer active
Google Drive storage unavailable
Backup damaged or incompatible
Deleting backup history
```

Use **active device**, **backup**, and **restore**. Keep the Android card's
existing **Package ready** wording and system-settings guidance.

- [ ] **Step 6: Implement recovery shell routing**

`MainActivity` renders `RecoveryShellScreen` for `NoVault`,
`Unreadable`, and `Recovering`. It constructs `OpenTasksApp` and its active
view models only for `VaultRuntimeState.Active`.

`RecoveryViewModel` discovers the inert portable package directly through
`AndroidBackupFiles`; it does not require an active repository,
`AndroidBackupRuntime`, or content-key store merely to show that the package
is present.

The shell offers:

- Restore from Google Drive;
- Restore Android backup package when one is present;
- Start without restoring for `NoVault`; and
- retry/export guidance without overwriting for `Unreadable`.

- [ ] **Step 7: Add truthful destructive and passphrase copy**

Disconnect copy states that Open Tasks sends no delete request but Google may
remove hidden app data through account/Drive controls. Delete copy states that
app-managed Drive history is permanent and Android's separate package is not
deleted. Passphrase copy states that older backup copies remain usable with
the old passphrase.

- [ ] **Step 8: Run UI, process-restoration, and accessibility suites**

```bash
./gradlew :app:testDebugUnitTest \
  :feature:more:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest --stacktrace
```

Expected: ViewModel, two-card, recovery-shell, process-restoration, fold, and
text-scale tests pass.

- [ ] **Step 9: Commit product surfaces**

```bash
git add app/src/main app/src/test app/src/androidTest \
  feature/more/src/main feature/more/src/androidTest
git commit -m "feat: add encrypted backup and recovery UI"
```

### Task 14: Qualify Two-Installation Recovery and Close Stage 3

**Files:**

- Create:
  `app/src/androidTest/kotlin/app/opentasks/RemoteBackupBoundaryInstrumentedTest.kt`
- Create:
  `app/src/androidTest/kotlin/app/opentasks/RecoveryTakeoverInstrumentedTest.kt`
- Create:
  `docs/qualification/stage3-google-drive-backup-recovery.md`
- Modify:
  `docs/superpowers/specs/2026-07-30-stage-3-google-drive-backup-recovery-design.md`
- Modify: `HANDOFF.md`
- Modify: `CLAUDE.md`

**Interfaces:**

- Consumes: all Stage 3 tasks, two disposable installations or emulator data
  profiles, two Google accounts for mismatch testing, and the protected
  existing workspace for non-destructive migration proof.
- Produces: reproducible non-private qualification evidence and an
  authoritative next-stage handoff.

- [ ] **Step 1: Add packaged boundary tests**

Assert release packaging contains:

- only `drive.appdata` authorization scope usage;
- no debug qualification activity;
- no broad Drive scope string;
- no server-client secret;
- no backup of Room, preferences, keys, recovery registry, remote transfer
  staging, or provider state;
- exactly the existing Android portable include; and
- WorkManager names and data with no identifiers.

- [ ] **Step 2: Add fake-provider end-to-end instrumentation**

With deterministic fake authorization and Drive transport, execute:

1. initial epoch-one backup;
2. mutation and incremental publication;
3. process death during resumable upload;
4. staged recovery;
5. takeover;
6. stale prior-device rejection;
7. disconnect and reconnect;
8. wrong account;
9. passphrase change;
10. current-base corruption and previous-base fallback;
11. deleting-state process death; and
12. control-last deletion.

Assert canonical workspace capture equality before backup and after recovery.

- [ ] **Step 3: Run fast repository gates**

```bash
git diff --check
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
```

Expected: PASS. Record task counts and test counts.

- [ ] **Step 4: Run release separately**

```bash
./gradlew :app:assembleRelease --stacktrace
```

Expected: PASS with R8 and resource shrinking.

- [ ] **Step 5: Run schema and deterministic-fixture gates**

```bash
./scripts/check-schema-drift.sh
node scripts/generate-stage3-drive-v1-fixtures.mjs
git diff --exit-code -- \
  core/data/src/test/resources/backup-format/drive-v1 \
  core/data/schemas
```

Expected: no fixture or schema diff.

- [ ] **Step 6: Audit and run all connected tests on one disposable target**

```bash
: "${STAGE3_ADB_SERIAL:?Set STAGE3_ADB_SERIAL to the audited disposable target}"
export ANDROID_SERIAL="$STAGE3_ADB_SERIAL"
adb -s "$STAGE3_ADB_SERIAL" shell getprop ro.build.version.sdk
./gradlew connectedDebugAndroidTest --stacktrace
```

Before running Gradle, ensure it resolves exactly the audited serial. Stop if
another target is eligible.

- [ ] **Step 7: Run credentialed two-installation acceptance**

Using disposable workspace data:

1. explicitly select account A and publish epoch one;
2. mutate and verify an incremental generation;
3. interrupt and resume a frame larger than 5 MiB;
4. restore the latest generation on installation B;
5. take ownership and verify installation A receives ownership loss;
6. preserve divergent A data under a new lineage in account A;
7. disconnect B and reconnect to account A with no application delete;
8. select account B and verify zero backup list/read/write calls;
9. change passphrase and recover a new test installation with it;
10. corrupt current base and verify previous-base recovery;
11. delete history and prove control deletion is the final request; and
12. verify the Android package remains inert and independent.

Record only generations, byte counts, times, bounded state names, request
families, and pass/fail. Record no account, token, file ID, provider revision,
lineage/device ID, encryption metadata, or private content.

- [ ] **Step 8: Re-verify the protected workspace non-destructively**

Upgrade the existing protected v6 database in place. Before and after a cold
restart, compare package identity, database inode identities, visible record
counts and names, projects, active timer, backup generation, portable package
status, and legacy outbox count. Do not uninstall, clear, instrument, or run
backup-manager restore against the protected target.

- [ ] **Step 9: Run privacy and logging audit**

Search source, tests, WorkManager data, exceptions, debug/release manifests,
and captured logs for:

```text
Authorization: Bearer
drive.google.com
permissionId
providerRevision
sessionUri
CloudLineageId(
CloudDeviceId(
```

Every occurrence must be a declaration, test fixture, or redacted boundary;
no runtime value may appear in logs or user copy.

- [ ] **Step 10: Update authoritative documentation**

Set the Stage 3 design status to `Implemented and verified` only after all
gates pass. Add exact commits, test counts, provider qualification facts,
residual fresh-client rollback limitation, and the next approved action to
`HANDOFF.md`. Update `CLAUDE.md` with the durable v7, conditional-write,
runtime-slot, and recovery invariants.

- [ ] **Step 11: Commit Stage 3 qualification**

```bash
git add app/src/androidTest docs/qualification \
  docs/superpowers/specs/2026-07-30-stage-3-google-drive-backup-recovery-design.md \
  HANDOFF.md CLAUDE.md
git commit -m "docs: verify Stage 3 backup and recovery"
```

- [ ] **Step 12: Inspect final repository state**

```bash
git status --short
git log --oneline -15
```

Expected: only known user-owned untracked files remain. Do not begin Stage 4
brainstorming or implementation in this plan.
