# Stage 3 Drive Create-Only Backup and Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add verified encrypted Google Drive app-data backup, immutable
create-only ownership, staged recovery and takeover, and truthful backup
management without synchronization, provider revisions, or mutable remote
control.

**Architecture:** Stage 2 remains the only source of verified snapshots and
operation segments, while Room remains the sole live structured-data
authority. A short immutable ownership chain selects one authoritative writer;
append-only authenticated publications select recoverable inventory inside the
current epoch. `RecoveryCoordinator` is the only cloud-to-Room path and
activates a separately verified SQLCipher vault slot only after a winning
takeover claim is authenticated.

**Tech Stack:** Kotlin 2.3.21, Android Gradle Plugin 9.3.1, Java 17 on JDK 21,
Compose BOM 2026.06.01, Room 2.8.4, SQLCipher 4.15.0, Tink 1.23.0,
Bouncy Castle 1.84, kotlinx.serialization 1.11.0, Google Play services Auth
21.6.0, WorkManager 2.11.2, Android `AtomicFile`, `HttpURLConnection`,
JUnit 4, Compose UI test v2, and Node.js built-in `crypto` for independent
format fixtures.

## Global Constraints

- Work directly on `main`; do not create a branch, worktree, or pull request.
- The user-owned untracked `artifacts/` directory is out of scope. Never read,
  modify, stage, or commit it.
- Preserve the protected Room workspace. Never uninstall the application,
  clear its data, attach instrumentation to its normal emulator, reset the
  repository, or overwrite its named replacement snapshot.
- Use exactly one audited disposable ADB target for connected and credentialed
  qualification. Record its serial before any install or test command.
- Before the first `connectedDebugAndroidTest`, export both
  `STAGE3_ADB_SERIAL` and `ANDROID_SERIAL` to the exact audited serial, run
  `adb -s "$STAGE3_ADB_SERIAL" shell getprop ro.build.version.sdk`, and stop if
  `adb devices` shows any other eligible target.
- Start Task 1 from the current uncommitted qualification work, but stage no
  Stage 3 source until the create-only credentialed gate passes.
- Remove every ETag, `If-Match`, compare-and-swap, mutable-control, and
  provider-revision assumption test-first.
- Drive JSON `version`, HTTP ETag, timestamps, names, and list order are never
  ownership or publication authority.
- Existing provider files are never mutated to publish control state.
- One immutable ownership chain has one authenticated tip and one exact
  successor slot per active claim.
- Normal backups create immutable publications inside the current ownership
  epoch and do not extend the ownership chain.
- Every initial setup and takeover uploads, downloads, authenticates, decodes,
  and compares two independently identified complete bases before claiming
  ownership.
- A stale device may finish old-epoch bytes, but those bytes cannot overwrite,
  delete, or become authoritative under the new tip.
- Any ownership or publication ambiguity fails closed.
- Room remains the sole live structured-data authority.
- Normal cloud operation has no remote-to-Room record path. Only
  `RecoveryCoordinator` may populate a new inactive staging database.
- Keep every v6 user record, every Stage 2 backup row, and every
  `sync_operations` row. The v6-to-v7 migration is additive and
  `sync_operations` remains read-only.
- Do not import remote configuration, account binding, transfer sessions,
  former device IDs, or former local backup checkpoints into a recovered
  vault.
- Keep Stage 2 local and remote checkpoints separate. Remote verification
  never advances `backup_state`.
- Request only
  `https://www.googleapis.com/auth/drive.appdata`.
- Persist no OAuth access token, refresh token, ID token, server authorization
  code, Google email, profile, raw Drive permission ID, or raw account name.
- `about.get(fields=user(permissionId))` is the only account-binding input.
  Persist only a per-install HMAC-SHA-256 digest in SQLCipher.
- Initial account selection is explicit. A known lineage reconnects only to
  its bound account. Account switching and migration remain outside Stage 3.
- Access-token strings returned by Google Play services are immutable runtime
  objects. Minimize their lifetime, drop references promptly, clear the Google
  token cache on invalidation, and do not claim direct zeroisation.
- Use one random `CloudLineageId` per independently created backup and one
  random `CloudDeviceId` per owning installation.
- Provider-visible metadata contains only format family/version, opaque
  lineage, role, bounded writer epoch, opaque owner device where cleanup needs
  it, and public logical object identity.
- Provider metadata is an index only; authenticated bytes repeat and bind all
  authoritative identity.
- Accept at most 64 ownership roots per account, 1,024 ownership claims per
  lineage, and 128 publication candidates per epoch.
- Preserve strict canonical UTF-8 JSON, exact field order, explicit nulls,
  fixed enum names, unpadded Base64, deterministic collection order, and
  epoch-millisecond instants.
- Preserve Stage 1/2 bounds: 16 KiB public header/bootstrap, 1 MiB manifest
  ciphertext, 64 MiB snapshot ciphertext, 16 MiB operation-segment ciphertext,
  100,000 snapshot records, and 10,000 segment operations.
- AES-GCM crypto-v1 adds 33 bytes. Plaintext maxima remain `1 MiB - 33`,
  `64 MiB - 33`, and `16 MiB - 33`.
- Recovery envelopes remain at most 16 KiB. Portable packages remain at most
  24 MiB (`25_165_824` bytes).
- A publication sequence increases by exactly one. Local generation is
  non-decreasing and may remain equal only for recovery-passphrase rotation.
- Retain the current and immediately previous authenticated publications,
  current and fallback complete bases, and every segment required after either
  base.
- Cleanup authenticates current ownership immediately before every batch,
  deletes at most 32 objects, and stops when the tip changes.
- Automatic cleanup never deletes unknown, malformed, too-new, cross-lineage,
  cross-epoch, or cross-device objects.
- Abandoned candidates and late old-epoch residue must be at least seven days
  old before cleanup.
- Ownership claims are never automatically deleted.
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
- Recovery-passphrase change rewraps the same vault-content key, increments
  publication sequence and recovery-credential generation, and does not
  advance local generation or claim to revoke older backup copies.
- Never create a replacement Keystore key for an existing unreadable local
  envelope.
- Disconnect sends no Drive file request and retains encrypted lineage
  configuration and history.
- History deletion first creates and authenticates a permanent non-recoverable
  `TERMINATED` tombstone at the exact successor slot, then removes recoverable
  objects in resumable batches.
- Keep the Android portable package independent and inert until explicit
  recovery.
- Keep Google-account migration, attachment transport, synchronization,
  remote merge, collaboration, and a second authoritative writer out of Stage
  3.
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

The authoritative design is
`docs/superpowers/specs/2026-07-30-stage-3-drive-create-only-ownership-design.md`.
It supersedes the mutable-control and provider-revision portions of the older
Stage 3 design and plan. This replacement plan is ordered because formats,
Room state, ownership, publication, recovery, and lifecycle operations share
one safety protocol.

Focused responsibilities:

- `gradle/libs.versions.toml`, `app/build.gradle.kts`, and the app manifests —
  Play services Auth, WorkManager, Internet permission, and debug-only
  qualification packaging.
- `core/data/.../backup/drive/CreateOnlyDriveTransport.kt` — session-bound
  generated-ID, create-if-absent, bounded read/list, resumable-create, and
  delete contract with no provider revision.
- `app/.../backup/drive/HttpCreateOnlyDriveTransport.kt` — bounded Drive REST v3
  implementation and provider failure mapping.
- `app/src/debug/.../DriveCreateOnlyQualification.kt` — ten authenticated
  duplicate-create races and discarded-success-response resolution.
- `core/model/.../RemoteBackupModels.kt` — redacted opaque identities, status
  facts, and bounded failure categories.
- `core/domain/.../RemoteBackupContracts.kt` — provider-independent storage,
  ownership, publication, scheduling, recovery, and lifecycle contracts.
- `core/data/.../backup/OwnershipClaimCodec.kt` — strict public ownership
  header plus authenticated root, successor, and terminal claims.
- `core/data/.../backup/PublicationCodec.kt` — strict recovery bootstrap plus
  authenticated baseline and normal publication manifests.
- `scripts/generate-stage3-drive-create-only-v1-fixtures.mjs` — independent
  canonical ownership and publication fixtures.
- `core/data/.../backup/RemoteBackupEntities.kt`,
  `RemoteBackupDaos.kt`, and `RoomRemoteBackupStore.kt` — additive Room v7
  configuration, object, operation, checkpoint, and cleanup state.
- `core/data/.../VaultSlotRegistry.kt`, `RecoveryRegistry.kt`, and
  `VaultRuntimeManager.kt` — crash-safe staging and atomic active-vault
  activation.
- `app/.../backup/drive/GoogleDriveAuthorizationManager.kt` — explicit
  account selection, non-interactive authorization, HMAC account binding,
  token clearing, and revocation.
- `core/data/.../backup/CreateOnlyDriveObjectStore.kt` — immutable create,
  resumable transfer, bounded read/list, authenticated readback staging, and
  delete semantics over `CreateOnlyDriveTransport`.
- `core/data/.../backup/OwnershipChainStore.kt` — bounded exact-successor
  traversal, authentication, winner resolution, and terminal-state handling.
- `core/data/.../backup/PublicationCatalog.kt` — bounded publication
  authentication, unique-tip resolution, and fork/gap rejection.
- `core/data/.../backup/RemoteObjectCodec.kt` — Stage 2 object verification
  and lineage/epoch/device re-authentication.
- `core/data/.../backup/DefaultRemoteBackupConfigurator.kt` — initial
  two-base, baseline-first, ownership-root connection.
- `core/data/.../backup/DefaultRemoteBackupCoordinator.kt` — single-owner
  publication, final-tip checks, checkpointing, retention, and cleanup.
- `app/.../backup/RemoteBackupWorker.kt`,
  `WorkManagerRemoteBackupScheduler.kt`, and `RemoteBackupRuntime.kt` —
  unique background work and active-vault composition.
- `core/data/.../backup/BackupRecordImporter.kt` and
  `StagedVaultVerifier.kt` — exhaustive import into a separate SQLCipher
  database, integrity validation, close, and reopen.
- `core/data/.../backup/DefaultRecoveryCoordinator.kt` — bounded discovery,
  recovery, two-base takeover, winner authentication, and activation.
- `app/.../backup/RecoveryPassphraseChanger.kt` and
  `DefaultRemoteBackupLifecycleCoordinator.kt` — immutable passphrase
  publication, disconnect, divergent preservation, terminal deletion, and
  resumable cleanup.
- `app/.../backup/EncryptedBackupViewModel.kt`,
  `RecoveryViewModel.kt`, `feature/more/.../BackupRecoveryScreen.kt`, and
  `RecoveryShellScreen.kt` — stateless product surfaces and ephemeral actions.
- `docs/qualification/` and `HANDOFF.md` — non-private provider evidence,
  final acceptance, and authoritative continuation state.

### Task 1: Qualify Create-Only Drive Successor Slots

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Rename:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/drive/DriveTransport.kt`
  to
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/drive/CreateOnlyDriveTransport.kt`
- Rename:
  `app/src/main/kotlin/app/opentasks/backup/drive/HttpDriveTransport.kt`
  to
  `app/src/main/kotlin/app/opentasks/backup/drive/HttpCreateOnlyDriveTransport.kt`
- Rename:
  `app/src/test/kotlin/app/opentasks/backup/drive/HttpDriveTransportTest.kt`
  to
  `app/src/test/kotlin/app/opentasks/backup/drive/HttpCreateOnlyDriveTransportTest.kt`
- Rename:
  `app/src/debug/kotlin/app/opentasks/backup/drive/DriveConditionalWriteQualification.kt`
  to
  `app/src/debug/kotlin/app/opentasks/backup/drive/DriveCreateOnlyQualification.kt`
- Rename:
  `app/src/debug/kotlin/app/opentasks/backup/drive/DriveQualificationActivity.kt`
  to
  `app/src/debug/kotlin/app/opentasks/backup/drive/DriveCreateOnlyQualificationActivity.kt`
- Rename:
  `app/src/testDebug/kotlin/app/opentasks/backup/drive/DriveConditionalWriteQualificationTest.kt`
  to
  `app/src/testDebug/kotlin/app/opentasks/backup/drive/DriveCreateOnlyQualificationTest.kt`
- Modify: `app/src/debug/AndroidManifest.xml`
- Rename:
  `app/src/androidTest/kotlin/app/opentasks/DriveQualificationPackagingInstrumentedTest.kt`
  to
  `app/src/androidTest/kotlin/app/opentasks/DriveCreateOnlyQualificationPackagingInstrumentedTest.kt`
- Create: `docs/qualification/stage3-drive-create-only.md`
- Delete after replacement:
  `docs/qualification/stage3-drive-conditional-write.md`

**Interfaces:**

- Consumes: the current uncommitted authorization, generated-ID, bounded HTTP,
  app-data metadata, debug activity, direct instrumentation, and packaging
  work. The uncommitted source remains unqualified until this task passes.
- Produces:

```kotlin
data class DriveFileMetadata(
    val providerFileId: String,
    val name: String,
    val role: String,
    val appProperties: Map<String, String>,
)

data class DriveCreateRequest(
    val metadata: DriveFileMetadata,
    val content: ByteArray,
)

data class DriveDownloadReceipt(val byteCount: Long)

sealed interface DriveCreateResult {
    data object Created : DriveCreateResult
    data object AlreadyExists : DriveCreateResult
    data object Ambiguous : DriveCreateResult
}

data class DriveListedFile(
    val providerFileId: String,
    val name: String,
    val role: String?,
    val appProperties: Map<String, String>,
)

data class DriveListPage(
    val files: List<DriveListedFile>,
    val nextPageToken: String?,
)

data class DriveResumableSession(val sessionUri: String)

sealed interface DriveChunkResult {
    data class ResumeAt(val nextByte: Long) : DriveChunkResult
    data object Complete : DriveChunkResult
    data object Expired : DriveChunkResult
    data object Ambiguous : DriveChunkResult
}

enum class DriveTransportFailureCategory {
    AUTHORIZATION,
    MISSING,
    STORAGE_QUOTA,
    RETRYABLE,
    CORRUPT_RESPONSE,
    PROVIDER_REJECTED,
}

class DriveTransportException(
    val category: DriveTransportFailureCategory,
) : IOException()

interface CreateOnlyDriveTransport : AutoCloseable {
    suspend fun readCurrentUserPermissionId(): String
    suspend fun generateAppDataFileIds(count: Int): List<String>
    suspend fun listAppDataFiles(
        query: String,
        pageToken: String?,
        pageSize: Int,
    ): DriveListPage
    suspend fun createFileIfAbsent(
        request: DriveCreateRequest,
    ): DriveCreateResult
    suspend fun downloadFile(
        providerFileId: String,
        destination: File,
        maximumBytes: Long,
    ): DriveDownloadReceipt
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

`HttpCreateOnlyDriveTransport` owns one access-token reference and drops it on
`close()`. An indeterminate create after request transmission returns
`Ambiguous`; callers resolve it only by reading the exact generated ID.
Exception text contains no URL, response body, token, provider ID, session
URI, header, or request identifier.

- [ ] **Step 1: Preserve the qualified setup dependencies**

Keep exactly:

```toml
play-services-auth = "21.6.0"
google-play-services-auth = {
    module = "com.google.android.gms:play-services-auth",
    version.ref = "play-services-auth"
}
```

and:

```kotlin
implementation(libs.google.play.services.auth)
```

Keep only this main-manifest addition:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 2: Replace revision tests with failing create-only tests**

Delete every test assertion for `ETag`, `If-Match`, provider revision, PATCH,
conditional conflict, or revision-bearing receipts. Add:

```kotlin
@Test
fun occupiedGeneratedIdReturnsAlreadyExistsWithoutReplacement() = runBlocking {
    val transport = transportReturning(
        status = 409,
        responseBody = """{"error":{"message":"private"}}""",
    )

    assertEquals(
        DriveCreateResult.AlreadyExists,
        transport.createFileIfAbsent(createRequest("generated-a", byteArrayOf(1))),
    )
    assertFalse(transport.recordedFailureText.contains("private"))
    assertEquals(0, transport.patchRequests)
}

@Test
fun lostCreateResponseReturnsAmbiguousForExactIdResolution() = runBlocking {
    val transport = transportThatFailsAfterRequestBytesWereSent()

    assertEquals(
        DriveCreateResult.Ambiguous,
        transport.createFileIfAbsent(createRequest("generated-a", byteArrayOf(1))),
    )
}
```

Also assert exact `parents=["appDataFolder"]`, supplied generated ID, bounded
app properties, `spaces=appDataFolder`, strict page tokens, media-length
bounds, multipart and resumable creates, 256 KiB non-final chunks, `404`,
`409`, `401`, authorization-related `403`, quota `403`, rate-limit `403`,
`429`, and `5xx` mapping.

- [ ] **Step 3: Run the focused transport tests for RED**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.drive.HttpCreateOnlyDriveTransportTest' --stacktrace
```

Expected: compilation failures for the new result shapes while the old
revision and compare-and-swap declarations still exist.

- [ ] **Step 4: Implement the create-only transport**

Use only:

```text
GET    /drive/v3/files/generateIds?count=N&space=appDataFolder&type=files
GET    /drive/v3/about?fields=user(permissionId)
GET    /drive/v3/files?spaces=appDataFolder&q=...&pageToken=...&pageSize=...
POST   /upload/drive/v3/files?uploadType=multipart
GET    /drive/v3/files/{id}?alt=media
POST   /upload/drive/v3/files?uploadType=resumable
PUT    {resumable-session-uri}
DELETE /drive/v3/files/{id}
```

Remove `compareAndSwapFile`, PATCH, ETag parsing, metadata read for a revision,
and all provider-revision fields. Accept create success only for `200` or
`201`; map `409` to `AlreadyExists`; return `Ambiguous` only when request bytes
may have reached Drive but a trustworthy result did not return. A definite
pre-request failure uses a bounded exception category.

- [ ] **Step 5: Run the transport and packaging tests for GREEN**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.drive.HttpCreateOnlyDriveTransportTest' \
  --tests 'app.opentasks.backup.drive.DriveCreateOnlyQualificationTest' \
  :app:assembleDebug :app:assembleRelease --stacktrace
```

Expected: transport tests pass; debug contains the internal qualification
activity; release excludes it; no conditional-write symbol remains.

- [ ] **Step 6: Implement the authenticated race harness**

Define a debug-only HMAC frame independent of Task 2:

```kotlin
@Serializable
data class QualificationClaim(
    val format: String = "open-tasks-create-only-qualification-v1",
    val predecessorId: String,
    val successorId: String,
    val claimId: String,
    val candidateId: String,
    val baselineId: String,
    val nextSuccessorId: String,
    val epoch: Long,
)

data class QualificationResult(
    val property: String,
    val passed: Boolean,
)

class QualificationCreateFacade(
    private val transport: CreateOnlyDriveTransport,
) {
    suspend fun createAndDeliberatelyDiscardCreatedResult(
        request: DriveCreateRequest,
    ): DriveCreateResult
}
```

Encode canonical JSON plus HMAC-SHA-256 with one ephemeral 32-byte key. For
each of ten races:

1. generate fresh predecessor, successor, root-baseline, two candidate
   baseline, and two next-successor IDs;
2. create and read-authenticate the root baseline and epoch-one predecessor;
3. create and read-authenticate both different baseline proposals;
4. launch two different valid epoch-two claims against the same successor ID;
5. require exactly one `Created` and one `AlreadyExists`;
6. retry the losing bytes three times and require `AlreadyExists`;
7. after each retry, download the exact successor and authenticate byte-for-byte
   unchanged winner identity; and
8. delete every disposable object in `finally`.

In one separate run, deliberately convert a successful create result to
`Ambiguous` in `QualificationCreateFacade` after the production transport has
completed the provider call, discard the original result, read the exact ID,
and accept only the exact expected authenticated bytes. The facade is
debug-only; production resolution handles a real `Ambiguous` result through
the same exact-ID authentication path.

- [ ] **Step 7: Run the debug qualification unit tests**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.drive.DriveCreateOnlyQualificationTest' \
  --stacktrace
```

Expected: ten deterministic fake-provider races, thirty loser retries, an
ambiguous-response resolution, cleanup-on-failure, and bounded result-name
tests pass.

- [ ] **Step 8: Audit and run the credentialed hard gate**

```bash
: "${STAGE3_ADB_SERIAL:?Set STAGE3_ADB_SERIAL to the audited disposable target}"
export ANDROID_SERIAL="$STAGE3_ADB_SERIAL"
adb devices
adb -s "$STAGE3_ADB_SERIAL" shell getprop ro.build.version.sdk
STAGE3_ADB_SERIAL="$STAGE3_ADB_SERIAL" \
ANDROID_SERIAL="$STAGE3_ADB_SERIAL" \
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.driveQualification=run \
  --stacktrace --console=plain
```

Expected: the direct same-process instrumentation launcher allows up to ten
minutes for explicit account consent and passes only when all ten live
provider races, all thirty loser retries, unchanged authenticated readbacks,
discarded-response resolution, and disposable cleanup pass. The same
production transport's deterministic HTTP tests supply the missing,
authorization, quota, retryable, corrupt, occupied, and indeterminate outcome
mapping evidence; the live gate does not attempt to manufacture quota or
provider outages.
Any second success, silent replacement, changed winner, inconsistent occupied
result, or different-byte ambiguous resolution stops Stage 3.

- [ ] **Step 9: Record bounded evidence and prove forbidden concepts are gone**

The record contains date, app commit, Android API, Play services Auth 21.6.0,
Drive REST v3 endpoint family, and bounded property names only. Then run:

```bash
rg -n 'ETag|If-Match|compareAndSwap|providerRevision|CONDITIONAL_' \
  core/data/src/main/kotlin/app/opentasks/core/data/backup/drive \
  app/src/main/kotlin/app/opentasks/backup/drive \
  app/src/debug app/src/test app/src/testDebug docs/qualification
```

Expected: no match. The qualification record contains no account, token,
permission ID, file ID, request URL, response body, session URI, or provider
revision.

- [ ] **Step 10: Commit only after the provider gate passes**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
  app/src/main/AndroidManifest.xml \
  core/data/src/main/kotlin/app/opentasks/core/data/backup/drive \
  app/src/main/kotlin/app/opentasks/backup/drive \
  app/src/debug app/src/test/kotlin/app/opentasks/backup/drive \
  app/src/testDebug app/src/androidTest/kotlin/app/opentasks \
  docs/qualification/stage3-drive-create-only.md
git commit -m "feat: qualify Drive create-only ownership slots"
```

If the credentialed gate does not pass, do not commit source and do not begin
Task 2.

### Task 2: Freeze Ownership and Publication Formats

**Files:**

- Create:
  `core/model/src/main/kotlin/app/opentasks/core/model/RemoteBackupModels.kt`
- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/OwnershipClaimCodec.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/PublicationCodec.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/OwnershipClaimCodecTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/PublicationCodecTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/DriveCreateOnlyGoldenTest.kt`
- Create: `scripts/generate-stage3-drive-create-only-v1-fixtures.mjs`
- Create:
  `core/data/src/test/resources/backup-format/drive-create-only-v1/ownership-root.json`
- Create:
  `core/data/src/test/resources/backup-format/drive-create-only-v1/ownership-successor.json`
- Create:
  `core/data/src/test/resources/backup-format/drive-create-only-v1/ownership-terminated.json`
- Create:
  `core/data/src/test/resources/backup-format/drive-create-only-v1/publication-baseline.json`
- Create:
  `core/data/src/test/resources/backup-format/drive-create-only-v1/publication-successor.json`

**Interfaces:**

- Consumes: `VaultId`, `BackupGeneration`, `CloudObjectFamily`,
  `AuthenticatedCloudObjectCodec`, `RecoveryEnvelopeCodec`, and Stage 1/2
  bounds.
- Produces opaque IDs whose `toString()` is redacted:

```kotlin
class CloudLineageId private constructor(val value: String)
class CloudDeviceId private constructor(val value: String)
class OwnershipClaimId private constructor(val value: String)
class PublicationId private constructor(val value: String)
class RemoteLogicalObjectId private constructor(val value: String)
class ProviderObjectId private constructor(val value: String)
class Sha256Digest private constructor(val value: String)

@JvmInline
value class WriterEpoch(val value: Long)

@JvmInline
value class PublicationSequence(val value: Long)
```

Each ID exposes `new()` where generated locally and `parse(String)` or
`of(String)` with canonical UUID, opaque-length, or 64-character lowercase
hex validation. `WriterEpoch` is in `1..Long.MAX_VALUE`;
`PublicationSequence` is non-negative.

The bounded model results are:

```kotlin
enum class RemoteBackupFailureCategory {
    AUTHORIZATION_REQUIRED,
    ACCOUNT_MISMATCH,
    OWNERSHIP_LOST,
    TERMINATED,
    AMBIGUOUS_REMOTE_STATE,
    PROVIDER_STORAGE,
    RETRYABLE_PROVIDER,
    CORRUPT_OR_INCOMPATIBLE,
    LOCAL_STORAGE,
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
    OWNERSHIP_LOST,
    TERMINATED,
    AMBIGUOUS_REMOTE_STATE,
    LOCAL_KEY_UNAVAILABLE,
}

enum class OwnershipStateV1 { ACTIVE, TERMINATED }
enum class RemoteObjectRoleV1 {
    OWNERSHIP_ROOT,
    OWNERSHIP_CLAIM,
    OWNERSHIP_TOMBSTONE,
    PUBLICATION,
    SNAPSHOT,
    SEGMENT,
}
```

The provider-independent persisted/presented models are:

```kotlin
data class OwnershipClaimRef(
    val providerId: ProviderObjectId,
    val logicalId: OwnershipClaimId,
    val sha256: Sha256Digest,
    val writerEpoch: WriterEpoch,
)

data class PublicationRef(
    val providerId: ProviderObjectId,
    val logicalId: PublicationId,
    val sha256: Sha256Digest,
    val sequence: PublicationSequence,
    val generation: BackupGeneration,
)

enum class RemoteBackupLifecycle {
    CONNECTING,
    ACTIVE,
    DORMANT,
    OWNERSHIP_LOST,
    DELETING,
    TERMINATED,
    BLOCKED,
}

@JvmInline
value class RemoteBackupStateVersion(val value: Long)

data class RemoteBackupConfiguration(
    val lineageId: CloudLineageId,
    val vaultId: VaultId,
    val rootClaimProviderId: ProviderObjectId,
    val accountBindingDigest: ByteArray,
    val lifecycle: RemoteBackupLifecycle,
    val activeDeviceId: CloudDeviceId?,
    val writerEpoch: WriterEpoch?,
    val ownershipClaim: OwnershipClaimRef?,
    val nextSuccessorProviderId: ProviderObjectId?,
    val currentPublication: PublicationRef?,
    val previousPublication: PublicationRef?,
    val lastVerifiedGeneration: BackupGeneration?,
    val lastVerifiedAt: Instant?,
    val recoveryCredentialGeneration: Long,
    val failureCategory: RemoteBackupFailureCategory?,
    val stateVersion: RemoteBackupStateVersion,
)

data class RemoteBackupOperation(
    val operationId: String,
    val lineageId: CloudLineageId,
    val kind: String,
    val phase: String,
    val targetEpoch: WriterEpoch?,
    val targetGeneration: BackupGeneration?,
    val candidateClaimProviderId: ProviderObjectId?,
    val candidatePublicationProviderId: ProviderObjectId?,
    val stateBytes: ByteArray,
    val startedAt: Instant,
    val updatedAt: Instant,
)

enum class RemoteObjectLifecycle {
    PLANNED,
    UPLOADING,
    VERIFIED,
    ABANDONED,
    DELETED,
}

data class RemoteBackupObject(
    val lineageId: CloudLineageId,
    val logicalObjectId: RemoteLogicalObjectId,
    val providerObjectId: ProviderObjectId,
    val role: RemoteObjectRoleV1,
    val writerEpoch: WriterEpoch,
    val ownerDeviceId: CloudDeviceId,
    val operationId: String,
    val firstGeneration: BackupGeneration,
    val lastGeneration: BackupGeneration,
    val frameLength: Long,
    val frameSha256: Sha256Digest,
    val lifecycle: RemoteObjectLifecycle,
    val resumableSessionUri: String?,
    val uploadedBytes: Long,
    val createdAt: Instant,
    val verifiedAt: Instant?,
)

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
    data object OwnershipLost : RemoteBackupStatus
    data object AmbiguousRemoteState : RemoteBackupStatus
    data object Deleting : RemoteBackupStatus
    data object Terminated : RemoteBackupStatus
}
```

All byte-array constructors and getters defensively copy. Identifier wrappers
compare by value but never reveal that value through `toString()`.

The strict ownership format is:

```kotlin
@Serializable
data class OwnershipPublicHeaderV1(
    val magic: String = "OPEN_TASKS_OWNERSHIP",
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val lineageId: String,
    val claimId: String,
    val writerEpoch: Long,
    val state: OwnershipStateV1,
    val role: RemoteObjectRoleV1,
    val providerFileId: String,
    val nextSuccessorProviderFileId: String?,
    val encryptedFrameLength: Long,
    val encryptedFrameSha256: String,
)

@Serializable
data class OwnershipClaimV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val lineageId: String,
    val writerEpoch: Long,
    val state: OwnershipStateV1,
    val predecessorProviderFileId: String?,
    val predecessorClaimId: String?,
    val predecessorClaimSha256: String?,
    val providerFileId: String,
    val claimId: String,
    val predecessorReservedSuccessorProviderFileId: String?,
    val sourceVaultId: String?,
    val activeDeviceId: String?,
    val nextSuccessorProviderFileId: String?,
    val baselinePublicationProviderFileId: String?,
    val baselinePublicationId: String?,
    val baselinePublicationSha256: String?,
    val recoveryCredentialGeneration: Long?,
    val creationOperationId: String,
    val tombstoneId: String?,
)
```

The strict publication format is:

```kotlin
@Serializable
data class PublicationBootstrapV1(
    val magic: String = "OPEN_TASKS_PUBLICATION",
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val lineageId: String,
    val writerEpoch: Long,
    val plannedClaimProviderFileId: String?,
    val recoveryEnvelope: RecoveryEnvelopePayloadV1,
    val recoveryCredentialGeneration: Long,
    val encryptedFrameLength: Long,
    val encryptedFrameSha256: String,
)

@Serializable
data class RemoteInventoryItemV1(
    val logicalObjectId: String,
    val providerFileId: String,
    val role: RemoteObjectRoleV1,
    val firstGeneration: Long,
    val lastGeneration: Long,
    val frameLength: Long,
    val frameSha256: String,
)

@Serializable
data class PublicationManifestV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val bootstrapSha256: String,
    val lineageId: String,
    val sourceVaultId: String,
    val writerEpoch: Long,
    val activeDeviceId: String,
    val publicationProviderFileId: String,
    val publicationId: String,
    val publicationSequence: Long,
    val predecessorPublicationProviderFileId: String?,
    val predecessorPublicationId: String?,
    val predecessorPublicationSha256: String?,
    val baseline: Boolean,
    val plannedClaimProviderFileId: String?,
    val plannedClaimId: String?,
    val predecessorClaimProviderFileId: String?,
    val predecessorClaimId: String?,
    val predecessorClaimSha256: String?,
    val ownershipClaimProviderFileId: String?,
    val ownershipClaimId: String?,
    val ownershipClaimSha256: String?,
    val localGeneration: Long,
    val publicationOperationId: String,
    val currentBaseObjectId: String,
    val fallbackBaseObjectId: String,
    val inventory: List<RemoteInventoryItemV1>,
    val recoveryCredentialGeneration: Long,
)
```

A terminal claim requires every nullable active field to be null and a
non-null random `tombstoneId`. An active claim requires all active fields and
no tombstone. A baseline has sequence zero, binds the planned claim and
predecessor claim, and has no actual ownership-claim digest. A normal
publication has the actual claim triplet and no planned-claim fields.

- [ ] **Step 1: Write failing ID and ownership-codec tests**

```kotlin
@Test
fun terminalClaimCannotCarryRecoveryOrSuccessorState() {
    val invalid = activeClaim().copy(
        state = OwnershipStateV1.TERMINATED,
        tombstoneId = UUID.randomUUID().toString(),
    )

    assertThrows(IllegalArgumentException::class.java) {
        ownershipCodec.encode(invalid, contentKey)
    }
}

@Test
fun successorMustOccupyPredecessorsExactReservedId() {
    assertThrows(IllegalArgumentException::class.java) {
        ownershipCodec.verifySuccessor(
            predecessor = rootClaim(),
            candidate = successorClaim(providerFileId = "different-id"),
            contentKey = contentKey,
        )
    }
}
```

Cover canonical IDs, redacted `toString()`, 16 KiB public header,
`1 MiB - 33` claim plaintext, exact epoch increment, predecessor digest,
exact reserved slot, root rules, active-field completeness, terminal-field
absence, overflow, unknown fields, duplicate fields, future versions, length,
checksum, AEAD, and identity swapping.

- [ ] **Step 2: Write failing publication-codec tests**

```kotlin
@Test
fun baselineBindsPlannedClaimWithoutDigestCycle() {
    val encoded = publicationCodec.encode(
        baselineManifest(plannedClaimProviderFileId = "claim-provider-a"),
        envelope,
        contentKey,
    )
    val verified = publicationCodec.verify(encoded, contentKey)

    assertEquals(0L, verified.manifest.publicationSequence)
    assertEquals("claim-provider-a", verified.manifest.plannedClaimProviderFileId)
    assertNull(verified.manifest.ownershipClaimSha256)
}

@Test
fun passphraseRotationMayAdvanceSequenceAtSameGeneration() {
    publicationCodec.requireSuccessor(
        previous = manifest(sequence = 7, generation = 42),
        current = manifest(sequence = 8, generation = 42),
    )
}
```

Cover bootstrap KDF bounds before allocation, exact bootstrap digest,
sequence increment, predecessor digest, baseline/normal exclusive fields,
unique sorted inventory, distinct current/fallback bases, complete segment
coverage after either base, non-decreasing generation, duplicate sequence,
fork, gap, future version, unknown field, associated-data swap, owned-buffer
clearing, and all Stage 1/2 byte bounds.

- [ ] **Step 3: Run the focused codec tests for RED**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests 'app.opentasks.core.data.backup.OwnershipClaimCodecTest' \
  --tests 'app.opentasks.core.data.backup.PublicationCodecTest' --stacktrace
```

Expected: compilation failure for the new IDs and codecs.

- [ ] **Step 4: Implement exact canonical codecs**

Both complete files use:

```text
4-byte unsigned big-endian public JSON length
canonical public JSON bytes
authenticated cloud frame bytes
```

Validate declared lengths before allocation. Bind each encrypted frame through
`AuthenticatedCloudObjectCodec` with `CloudObjectFamily.MANIFEST`,
schema/crypto/minimum-reader version 1, `vaultId = lineageId.value`, and
`objectId = claimId.value` or `publicationId.value`. The authenticated body
repeats the provider ID and every public authoritative identity.

- [ ] **Step 5: Implement publication-pair authority validation**

Expose:

```kotlin
data class VerifiedOwnershipClaim(
    val header: OwnershipPublicHeaderV1,
    val claim: OwnershipClaimV1,
    val completeSha256: Sha256Digest,
)

data class VerifiedPublication(
    val bootstrap: PublicationBootstrapV1,
    val manifest: PublicationManifestV1,
    val completeSha256: Sha256Digest,
)

fun PublicationCodec.requireRetainedPair(
    current: VerifiedPublication,
    previous: VerifiedPublication?,
    ownership: VerifiedOwnershipClaim,
)
```

Require one unique highest sequence, exact predecessor agreement, no duplicate
sequence or sibling child, no sequence gap in the retained pair, non-decreasing
generation, and exact claim/epoch/device binding.

- [ ] **Step 6: Generate independent fixtures**

```bash
node scripts/generate-stage3-drive-create-only-v1-fixtures.mjs
git diff --exit-code -- \
  core/data/src/test/resources/backup-format/drive-create-only-v1
```

The Node script constructs canonical JSON and AES-GCM fixtures independently,
parses every output, verifies lengths/digests, and produces byte-identical
second-generation output.

- [ ] **Step 7: Run golden and existing format suites**

```bash
./gradlew :core:data:testDebugUnitTest --stacktrace
```

Expected: ownership, publication, portable-package, snapshot, segment, and
authenticated cloud-frame tests pass.

- [ ] **Step 8: Commit the immutable format contract**

```bash
git add core/model/src/main/kotlin/app/opentasks/core/model \
  core/domain/src/main/kotlin/app/opentasks/core/domain \
  core/data/src/main/kotlin/app/opentasks/core/data/backup \
  core/data/src/test core/data/src/test/resources/backup-format/drive-create-only-v1 \
  scripts/generate-stage3-drive-create-only-v1-fixtures.mjs
git commit -m "feat: freeze create-only backup formats"
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

- Consumes: Task 2 IDs, digests, status categories, and existing
  `VaultDatabase`.
- Produces three SQLCipher tables and one transaction-backed state store:

```kotlin
@Entity(
    tableName = "remote_backup_config",
    indices = [Index("vaultId"), Index("lifecycle")],
)
data class RemoteBackupConfigEntity(
    @PrimaryKey val lineageId: String,
    val vaultId: String,
    val rootClaimProviderFileId: String,
    val accountBindingDigest: ByteArray,
    val lifecycle: String,
    val activeDeviceId: String?,
    val writerEpoch: Long?,
    val ownershipClaimProviderFileId: String?,
    val ownershipClaimId: String?,
    val ownershipClaimSha256: String?,
    val nextSuccessorProviderFileId: String?,
    val currentPublicationProviderFileId: String?,
    val currentPublicationId: String?,
    val currentPublicationSha256: String?,
    val previousPublicationProviderFileId: String?,
    val previousPublicationId: String?,
    val previousPublicationSha256: String?,
    val previousPublicationGeneration: Long?,
    val publicationSequence: Long?,
    val lastVerifiedGeneration: Long?,
    val lastVerifiedAtEpochMillis: Long?,
    val recoveryCredentialGeneration: Long,
    val failureCategory: String?,
    val stateVersion: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "remote_backup_object",
    primaryKeys = ["lineageId", "logicalObjectId"],
    indices = [Index("providerFileId", unique = true), Index("operationId")],
)
data class RemoteBackupObjectEntity(
    val lineageId: String,
    val logicalObjectId: String,
    val providerFileId: String,
    val role: String,
    val writerEpoch: Long,
    val ownerDeviceId: String,
    val operationId: String,
    val firstGeneration: Long,
    val lastGeneration: Long,
    val frameLength: Long,
    val frameSha256: String,
    val lifecycle: String,
    val resumableSessionUri: String?,
    val uploadedBytes: Long,
    val createdAtEpochMillis: Long,
    val verifiedAtEpochMillis: Long?,
)

@Entity(
    tableName = "remote_backup_operation",
    indices = [Index("lineageId"), Index("kind"), Index("phase")],
)
data class RemoteBackupOperationEntity(
    @PrimaryKey val operationId: String,
    val lineageId: String,
    val kind: String,
    val phase: String,
    val targetEpoch: Long?,
    val targetGeneration: Long?,
    val candidateClaimProviderFileId: String?,
    val candidatePublicationProviderFileId: String?,
    val stateBytes: ByteArray,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
```

`stateBytes` is strict canonical operation state stored inside SQLCipher. It
may contain planned generated IDs, digests, pending recovery-envelope bytes,
staging slot, cleanup cursor, and seven-day eligibility facts. It never
contains a passphrase, token, raw permission ID, provider response, file
contents, raw content key, or unbounded URL.

The store contract is:

```kotlin
interface RemoteBackupStateStore {
    suspend fun active(vaultId: VaultId): RemoteBackupConfiguration?
    suspend fun known(lineageId: CloudLineageId): RemoteBackupConfiguration?
    fun observeActive(vaultId: VaultId): Flow<RemoteBackupConfiguration?>
    suspend fun insertConnecting(configuration: RemoteBackupConfiguration)
    suspend fun compareAndSet(
        lineageId: CloudLineageId,
        expected: RemoteBackupStateVersion,
        next: RemoteBackupConfiguration,
    ): Boolean
    suspend fun putOperation(operation: RemoteBackupOperation)
    suspend fun transitionOperation(
        operationId: String,
        expectedPhase: String,
        next: RemoteBackupOperation,
    ): Boolean
}

interface RemoteBackupTransferStore {
    suspend fun objectState(
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
    ): RemoteBackupObject?
    suspend fun insertObject(value: RemoteBackupObject)
    suspend fun compareAndSetObject(
        expected: RemoteBackupObject,
        next: RemoteBackupObject,
    ): Boolean
    suspend fun objectsForLineage(
        lineageId: CloudLineageId,
    ): List<RemoteBackupObject>
    suspend fun removeObjectState(
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
    ): Boolean
}
```

- [ ] **Step 1: Extend the v6 migration test first**

Create a v6 fixture with user records, every Stage 2 backup table, recovery
envelope, and legacy `sync_operations`. Assert v6-to-v7 creates exactly the
three remote tables, changes no existing column or byte, and updates only
`vaults.schemaVersion` from 6 to 7.

```kotlin
@Test
fun migrate6To7PreservesStage2AndLegacyBytes() {
    val before = captureVersion6Bytes()
    val migrated = migrateAndOpen(6, 7)

    assertEquals(before, capturePreservedBytes(migrated))
    assertEquals(0, count(migrated, "remote_backup_config"))
    assertEquals(0, count(migrated, "remote_backup_object"))
    assertEquals(0, count(migrated, "remote_backup_operation"))
}
```

- [ ] **Step 2: Run migration RED**

```bash
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.core.data.VaultDatabaseMigrationInstrumentedTest --stacktrace
```

Expected: failure because schema 7 and `MIGRATION_6_7` do not exist.

- [ ] **Step 3: Implement entities and exact additive migration**

Add all three entities to `@Database`, set `version = 7`, register
`MIGRATION_6_7`, and create columns and indexes matching the entity
declarations byte-for-byte. Use no destructive migration fallback and no
`REPLACE`.

```sql
CREATE TABLE IF NOT EXISTS remote_backup_config (
    lineageId TEXT NOT NULL,
    vaultId TEXT NOT NULL,
    rootClaimProviderFileId TEXT NOT NULL,
    accountBindingDigest BLOB NOT NULL,
    lifecycle TEXT NOT NULL,
    activeDeviceId TEXT,
    writerEpoch INTEGER,
    ownershipClaimProviderFileId TEXT,
    ownershipClaimId TEXT,
    ownershipClaimSha256 TEXT,
    nextSuccessorProviderFileId TEXT,
    currentPublicationProviderFileId TEXT,
    currentPublicationId TEXT,
    currentPublicationSha256 TEXT,
    previousPublicationProviderFileId TEXT,
    previousPublicationId TEXT,
    previousPublicationSha256 TEXT,
    previousPublicationGeneration INTEGER,
    publicationSequence INTEGER,
    lastVerifiedGeneration INTEGER,
    lastVerifiedAtEpochMillis INTEGER,
    recoveryCredentialGeneration INTEGER NOT NULL,
    failureCategory TEXT,
    stateVersion INTEGER NOT NULL,
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
    logicalObjectId TEXT NOT NULL,
    providerFileId TEXT NOT NULL,
    role TEXT NOT NULL,
    writerEpoch INTEGER NOT NULL,
    ownerDeviceId TEXT NOT NULL,
    operationId TEXT NOT NULL,
    firstGeneration INTEGER NOT NULL,
    lastGeneration INTEGER NOT NULL,
    frameLength INTEGER NOT NULL,
    frameSha256 TEXT NOT NULL,
    lifecycle TEXT NOT NULL,
    resumableSessionUri TEXT,
    uploadedBytes INTEGER NOT NULL,
    createdAtEpochMillis INTEGER NOT NULL,
    verifiedAtEpochMillis INTEGER,
    PRIMARY KEY(lineageId, logicalObjectId)
);
CREATE UNIQUE INDEX IF NOT EXISTS index_remote_backup_object_providerFileId
    ON remote_backup_object(providerFileId);
CREATE INDEX IF NOT EXISTS index_remote_backup_object_operationId
    ON remote_backup_object(operationId);

CREATE TABLE IF NOT EXISTS remote_backup_operation (
    operationId TEXT NOT NULL,
    lineageId TEXT NOT NULL,
    kind TEXT NOT NULL,
    phase TEXT NOT NULL,
    targetEpoch INTEGER,
    targetGeneration INTEGER,
    candidateClaimProviderFileId TEXT,
    candidatePublicationProviderFileId TEXT,
    stateBytes BLOB NOT NULL,
    startedAtEpochMillis INTEGER NOT NULL,
    updatedAtEpochMillis INTEGER NOT NULL,
    PRIMARY KEY(operationId)
);
CREATE INDEX IF NOT EXISTS index_remote_backup_operation_lineageId
    ON remote_backup_operation(lineageId);
CREATE INDEX IF NOT EXISTS index_remote_backup_operation_kind
    ON remote_backup_operation(kind);
CREATE INDEX IF NOT EXISTS index_remote_backup_operation_phase
    ON remote_backup_operation(phase);

UPDATE vaults SET schemaVersion = 7 WHERE schemaVersion < 7;
```

Execute that statement only after all three tables and indexes exist.

- [ ] **Step 4: Write failing DAO transition tests**

```kotlin
@Test
fun staleLocalStateVersionCannotAdvanceRemoteCheckpoint() = runBlocking {
    store.insertConnecting(configuration(epoch = 3, claimDigest = DIGEST_A))

    assertFalse(
        store.compareAndSet(
            LINEAGE,
            expected = RemoteBackupStateVersion(2),
            next = checkpointedConfiguration(generation = 42),
        ),
    )
}
```

Cover one active config per vault, dormant/ownership-lost/terminated
coexistence, exact epoch/claim/publication compare-and-set, publication
sequence independent from generation, operation phase compare-and-set,
defensive byte copies, resumable-state clearing only after verification,
cleanup cursor durability, terminal-state irreversibility, and rejection of
negative epochs, sequences, generations, lengths, offsets, or times.

- [ ] **Step 5: Implement transactional state mapping**

`RoomRemoteBackupStore` implements both `RemoteBackupStateStore` and
`RemoteBackupTransferStore`, validates every database string through the Task
2 opaque types, checks at most one `ACTIVE` row for a vault inside the same
Room transaction, and returns defensive copies for `accountBindingDigest` and
`stateBytes`. `TERMINATED` rejects every transition except cleanup progress.

- [ ] **Step 6: Run DAO and migration GREEN**

```bash
./gradlew :core:data:connectedDebugAndroidTest --stacktrace
```

Expected: all v6 rows and legacy bytes remain unchanged; state-transition,
terminal, and operation tests pass.

- [ ] **Step 7: Export and verify schema 7**

`scripts/check-schema-drift.sh` copies committed schemas to a private
temporary directory, runs `:core:data:kspDebugKotlin`, compares all versioned
schemas byte-for-byte, and exits non-zero on a missing, added, or changed
schema without restoring a working-tree difference.

```bash
./gradlew :core:data:kspDebugKotlin
./scripts/check-schema-drift.sh
git diff --check
```

Expected: schema 7 matches Room output and schemas 1 through 6 are unchanged.

- [ ] **Step 8: Commit Room v7**

```bash
git add core/data/src/main core/data/src/androidTest core/data/schemas \
  scripts/check-schema-drift.sh
git commit -m "feat: persist create-only remote backup state"
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
- Create: `app/src/main/kotlin/app/opentasks/ActiveVaultServices.kt`
- Create: `app/src/test/kotlin/app/opentasks/ActiveVaultServicesTest.kt`
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
        fun new(): VaultSlot
        fun parse(value: String): VaultSlot
    }
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

The active marker stores only format version and opaque slot. The encrypted
recovery registry stores operation ID, phase, prior and staged slots, bounded
provider/claim/publication references, claimed epoch, activation state, and
cleanup state.

- [ ] **Step 1: Write the failing compatibility and crash matrix**

```kotlin
@Test
fun unreadableLegacyVaultIsPreservedWithoutReplacementKeys() = runBlocking {
    val harness = harnessWithEnvelopeAndMissingAlias()

    harness.manager.initialize()

    assertTrue(harness.manager.state.value is VaultRuntimeState.Unreadable)
    assertEquals(harness.beforeFiles, harness.currentFiles())
    assertEquals(0, harness.createdAliases)
}
```

Cover legacy selection without rename, no-vault without key creation, explicit
new-vault creation, Room-derived `VaultId`, unreadable preservation, random
staged names and keys, slot redaction, slot-scoped content-key wrappers,
legacy key compatibility, no prior-wrapper replacement, death before/after
marker replacement, failed staged open rollback, registry-key loss discarding
only inactive staging, Room close-before-replacement, and no active services
outside `Active`.

- [ ] **Step 2: Run the missing-type RED**

```bash
./gradlew :core:data:testDebugUnitTest \
  :core:crypto:connectedDebugAndroidTest \
  :core:data:connectedDebugAndroidTest \
  :app:testDebugUnitTest --stacktrace
```

Expected: compilation failure for registry, runtime manager, slot-namespaced
key storage, and active-service declarations.

- [ ] **Step 3: Refactor key management without changing legacy keys**

Expose:

```kotlin
fun openExistingDatabaseKey(slot: VaultSlot): ByteArray
fun createDatabaseKey(slot: VaultSlot): ByteArray
fun deleteDatabaseKey(slot: VaultSlot)
```

`VaultSlot.LEGACY` uses the exact current preference keys, Keystore aliases,
and associated data. New slots use a SHA-256 slot digest suffix.
`openExistingDatabaseKey` never creates an alias.

- [ ] **Step 4: Namespace content-key wrappers by slot**

Add `storageNamespace: String?` to `AndroidVaultContentKeyStore`. `null`
preserves the exact legacy storage names and associated data; a new slot
passes only its SHA-256 digest. `LocalVaultRuntime` retains the matching store
and implements `AutoCloseable`.

- [ ] **Step 5: Implement strict atomic registries**

Use `AtomicFile`, canonical JSON, a 64 KiB total bound, a distinct Keystore
AES-GCM alias, descriptor sync, and directory sync through an injectable file
operations boundary. Registry errors expose no slot, provider ID, ciphertext,
or operation state.

- [ ] **Step 6: Implement activation ordering**

```text
quiesce active services
close active Room
checkpoint, close, reopen, and verify staging
persist prior and staged slots
sync temporary active marker
atomically replace and directory-sync marker
construct and verify staged normal runtime
publish VaultRuntimeState.Active
remove prior slot and clear registry after success
```

The first failed normal open after marker replacement restores the unchanged
prior marker and leaves the prior database and wrappers intact.

- [ ] **Step 7: Gate app composition**

`MainActivity` constructs `OpenTasksApp` only for `Active`.
`OpenTasksApplication` starts existing Android backup services only after an
active runtime exists and closes them before slot replacement. Inert portable
package discovery remains available without active-vault services.

- [ ] **Step 8: Run protected compatibility GREEN**

```bash
./gradlew :core:crypto:connectedDebugAndroidTest \
  :core:data:connectedDebugAndroidTest \
  :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest --stacktrace
```

Expected: legacy identity and records survive, no-vault/unreadable creates no
replacement keys, and equal logical vault IDs in different slots keep
independent wrappers.

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
- Create:
  `app/src/androidTest/kotlin/app/opentasks/backup/drive/DriveAccountBindingInstrumentedTest.kt`
- Modify:
  `app/src/main/kotlin/app/opentasks/backup/drive/HttpCreateOnlyDriveTransport.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`

**Interfaces:**

- Consumes: Task 1 `CreateOnlyDriveTransport`, Google
  `AuthorizationClient`, and Task 3 stored account digest.
- Produces:

```kotlin
enum class DriveAuthorizationMode {
    EXPLICIT_ACCOUNT,
    NON_INTERACTIVE,
}

enum class DriveAuthorizationUnavailableReason {
    AUTHORIZATION_REQUIRED,
    RETRYABLE,
    REJECTED,
}

sealed interface DriveAuthorizationResult {
    data class Authorized(
        val session: AuthorizedDriveSession,
    ) : DriveAuthorizationResult
    data class ResolutionRequired(
        val pendingIntent: PendingIntent,
    ) : DriveAuthorizationResult
    data object AccountMismatch : DriveAuthorizationResult
    data class Unavailable(
        val reason: DriveAuthorizationUnavailableReason,
    ) : DriveAuthorizationResult
}

class AuthorizedDriveSession internal constructor(
    val transport: CreateOnlyDriveTransport,
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

```kotlin
@Test
fun nonInteractiveWrongAccountClosesBeforeLineageAccess() = runBlocking {
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
    assertEquals(0, manager.lineageCalls)
}
```

Assert explicit mode sets `SELECT_ACCOUNT`, requested scopes contain exactly
`drive.appdata`, non-interactive mode never starts resolution, `about.get` is
the only Drive call before digest verification, raw permission IDs are not
stored, tokens/accounts/pending intents are not persisted or logged, `401`
clears the token, and revoke uses the session's in-memory account plus exactly
the granted scope.

- [ ] **Step 2: Run focused authorization RED**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.drive.*Authorization*Test' \
  --tests 'app.opentasks.backup.drive.DriveAccountBindingTest' --stacktrace
```

Expected: compilation failure for the manager, session, and binding key.

- [ ] **Step 3: Implement the non-exportable account-binding key**

Use alias:

```text
open_tasks_drive_account_binding_hmac_v1
```

`DriveAccountBinding.digest(permissionId)` encodes strict UTF-8, computes
HMAC-SHA-256, returns a defensive 32-byte array, and clears the owned UTF-8
buffer. The Keystore key is non-exportable.

- [ ] **Step 4: Implement authorization without token persistence**

Bridge Google `Task` with `suspendCancellableCoroutine`; add no
`kotlinx-coroutines-play-services`. Request no offline access. Convert a
successful token result directly into a session-bound
`HttpCreateOnlyDriveTransport`,
call `about.get(fields=user(permissionId))`, compare digests with
`MessageDigest.isEqual`, and close on mismatch. The session privately owns the
immutable token and `Account` references for clear/revoke and drops both on
close.

- [ ] **Step 5: Run JVM, Keystore, and release checks**

```bash
./gradlew :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.backup.drive.DriveAccountBindingInstrumentedTest \
  --stacktrace
./gradlew :app:assembleRelease --stacktrace
```

Expected: authorization and Keystore tests pass; R8 succeeds; release exposes
no debug qualification class.

- [ ] **Step 6: Commit authorization**

```bash
git add app/src/main/kotlin/app/opentasks/backup/drive \
  app/src/test/kotlin/app/opentasks/backup/drive \
  app/src/androidTest/kotlin/app/opentasks/backup/drive \
  app/src/main/kotlin/app/opentasks/di/AppModule.kt
git commit -m "feat: bind Drive backup authorization"
```

### Task 6: Implement Create-Only Drive Object Storage

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/CreateOnlyDriveObjectStore.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/CreateOnlyDriveObjectStoreTest.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RemoteBackupDaos.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomRemoteBackupStore.kt`
- Modify:
  `app/src/main/kotlin/app/opentasks/backup/AndroidBackupFiles.kt`

**Interfaces:**

- Consumes: Task 1 `CreateOnlyDriveTransport`, Task 2 IDs/roles/digests, and
  Task 3 durable object/operation rows.
- Produces:

```kotlin
interface OwnedRemoteBytes : AutoCloseable {
    val size: Int
    fun take(): ByteArray
}

interface OwnedRemoteFile : AutoCloseable {
    val file: File
    val length: Long
}

data class RemoteListRequest(
    val lineageId: CloudLineageId,
    val role: RemoteObjectRoleV1,
    val writerEpoch: WriterEpoch?,
    val ownerDeviceId: CloudDeviceId?,
    val pageToken: String?,
    val pageSize: Int,
)

data class RemoteListedObject(
    val providerObjectId: ProviderObjectId,
    val logicalObjectId: String?,
    val role: RemoteObjectRoleV1?,
    val writerEpoch: WriterEpoch?,
    val ownerDeviceId: CloudDeviceId?,
)

data class RemoteListPage(
    val objects: List<RemoteListedObject>,
    val nextPageToken: String?,
)

sealed interface CreateSmallResult {
    data object Created : CreateSmallResult
    data object AlreadyExists : CreateSmallResult
    data object Ambiguous : CreateSmallResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        CreateSmallResult
}

sealed interface ReadSmallResult {
    data class Found(val bytes: OwnedRemoteBytes) : ReadSmallResult
    data object Missing : ReadSmallResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        ReadSmallResult
}

data class ImmutableUploadRequest(
    val lineageId: CloudLineageId,
    val writerEpoch: WriterEpoch,
    val ownerDeviceId: CloudDeviceId,
    val operationId: String,
    val logicalObjectId: RemoteLogicalObjectId,
    val providerObjectId: ProviderObjectId,
    val role: RemoteObjectRoleV1,
    val firstGeneration: BackupGeneration,
    val lastGeneration: BackupGeneration,
    val frameLength: Long,
    val frameSha256: Sha256Digest,
    val frame: OwnedRemoteFile,
)

sealed interface ImmutableUploadResult {
    data object UploadedAndVerified : ImmutableUploadResult
    data object OccupiedByExpectedBytes : ImmutableUploadResult
    data object OccupiedByDifferentBytes : ImmutableUploadResult
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

interface CreateOnlyBackupObjectStore {
    suspend fun generateProviderIds(
        count: Int,
        role: RemoteObjectRoleV1,
    ): List<ProviderObjectId>
    suspend fun createSmallIfAbsent(
        providerObjectId: ProviderObjectId,
        metadata: RemoteListedObject,
        bytes: OwnedRemoteBytes,
    ): CreateSmallResult
    suspend fun readSmall(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
    ): ReadSmallResult
    suspend fun list(request: RemoteListRequest): RemoteListPage
    suspend fun uploadImmutable(
        request: ImmutableUploadRequest,
    ): ImmutableUploadResult
    suspend fun downloadImmutable(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
        expectedSha256: Sha256Digest,
    ): ImmutableDownloadResult
    suspend fun delete(
        providerObjectId: ProviderObjectId,
    ): DeleteObjectResult
}

class CreateOnlyDriveObjectStore(
    private val transport: CreateOnlyDriveTransport,
    private val transferStore: RemoteBackupTransferStore,
    private val stagingRoot: File,
) : CreateOnlyBackupObjectStore
```

Names are constant per role. App properties contain bounded format, role,
opaque lineage, public logical ID, epoch, and cleanup owner device only.
Neither names nor properties contain `VaultId`, account data, generation,
content, credential material, or human copy.

- [ ] **Step 1: Write failing object-store tests**

```kotlin
@Test
fun ambiguousCreateResolvesOnlyThroughExactIdBytes() = runBlocking {
    val store = storeReturningAmbiguousThenBytes(EXPECTED_BYTES)

    val result = store.uploadImmutable(uploadRequest(EXPECTED_BYTES))

    assertEquals(
        ImmutableUploadResult.OccupiedByExpectedBytes,
        result,
    )
    assertEquals(listOf(EXACT_PROVIDER_ID), store.readIds)
}
```

Cover bounded pagination, generated IDs, multipart at or below 5 MiB,
resumable above 5 MiB, 256 KiB non-final chunks, provider-confirmed resume,
expired session restart with the same generated ID, private staging, exact
length/SHA-256 readback, occupied expected/different bytes, ambiguous exact-ID
resolution, cancellation, missing, and permanent delete.

- [ ] **Step 2: Run object-store RED**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests 'app.opentasks.core.data.backup.CreateOnlyDriveObjectStoreTest' \
  --stacktrace
```

Expected: compilation failure for the object store and domain result types.

- [ ] **Step 3: Add private staging paths**

```kotlin
val remoteTransferRoot =
    File(context.noBackupFilesDir, "backup/remote-transfer/v1")
val recoveryRoot =
    File(context.noBackupFilesDir, "recovery/staging/v1")
```

Extend packaged Android backup tests to prove both paths remain excluded.

- [ ] **Step 4: Implement small immutable operations**

Build bounded Drive queries from constants, reject a page token longer than
1,024 characters, cap page size at 100, and map transport results one-for-one.
`OwnedRemoteBytes.take()` transfers once; `close()` clears retained bytes.
Occupied and ambiguous create results never become success without an exact-ID
read and later caller authentication.

- [ ] **Step 5: Implement resumable immutable upload**

Persist generated ID, expected length/digest, operation owner, session URI,
and confirmed offset before network mutation. After multipart or resumable
completion, download to a fresh private file, sync, verify exact length and
SHA-256, and return `UploadedAndVerified`. Clear session/offset only after
verification. A different existing digest returns
`OccupiedByDifferentBytes`; it is never overwritten.

- [ ] **Step 6: Run storage, Room, and packaging GREEN**

```bash
./gradlew :core:data:testDebugUnitTest \
  :core:data:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest --stacktrace
```

Expected: store, operation-resume, migration, DAO, and exact backup-exclusion
tests pass.

- [ ] **Step 7: Commit create-only storage**

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain \
  core/data/src/main/kotlin/app/opentasks/core/data/backup \
  core/data/src/test/kotlin/app/opentasks/core/data/backup \
  app/src/main/kotlin/app/opentasks/backup/AndroidBackupFiles.kt \
  app/src/androidTest
git commit -m "feat: verify immutable Drive backup objects"
```

### Task 7: Resolve Ownership and Publish Epoch One

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RemoteObjectCodec.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/OwnershipChainStore.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/PublicationCatalog.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/DefaultRemoteBackupConfigurator.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/RemoteObjectCodecTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/OwnershipChainStoreTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/PublicationCatalogTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/DefaultRemoteBackupConfiguratorTest.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/LocalVaultRepositoryFactory.kt`

**Interfaces:**

- Consumes: active `LocalVaultRuntime`, Stage 2 `BackupCoordinator` and local
  object store, recovery envelope/content key, Tasks 2/3/6, and a private
  staging root.
- Produces:

```kotlin
sealed interface OwnershipResolution {
    data class Active(
        val root: VerifiedOwnershipClaim,
        val tip: VerifiedOwnershipClaim,
    ) : OwnershipResolution
    data class Terminated(
        val tombstone: VerifiedOwnershipClaim,
    ) : OwnershipResolution
    data class Blocked(
        val reason: RemoteBackupFailureCategory,
    ) : OwnershipResolution
}

interface OwnershipChainStore {
    suspend fun discoverPublicRoots(): List<OwnershipPublicHeaderV1>
    suspend fun resolve(
        rootProviderId: ProviderObjectId,
        contentKey: VaultKey,
    ): OwnershipResolution
    suspend fun createClaim(
        expectedPredecessor: VerifiedOwnershipClaim?,
        encodedClaim: OwnedRemoteBytes,
    ): OwnershipClaimCreateResult
}

class DefaultOwnershipChainStore(
    private val objectStore: CreateOnlyBackupObjectStore,
    private val codec: OwnershipClaimCodec,
) : OwnershipChainStore

sealed interface OwnershipClaimCreateResult {
    data class Won(val claim: VerifiedOwnershipClaim) :
        OwnershipClaimCreateResult
    data class Lost(val winner: VerifiedOwnershipClaim) :
        OwnershipClaimCreateResult
    data object AmbiguousRemoteState : OwnershipClaimCreateResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        OwnershipClaimCreateResult
}

interface PublicationCatalog {
    suspend fun discoverBootstraps(
        lineageId: CloudLineageId,
        epoch: WriterEpoch,
        plannedOrActualClaimProviderId: ProviderObjectId,
    ): List<PublicationBootstrapV1>
    suspend fun resolve(
        ownership: VerifiedOwnershipClaim,
        candidates: List<PublicationBootstrapV1>,
        contentKey: VaultKey,
    ): PublicationResolution
    suspend fun create(
        providerObjectId: ProviderObjectId,
        encodedPublication: OwnedRemoteBytes,
        contentKey: VaultKey,
    ): PublicationCreateResult
}

class DefaultPublicationCatalog(
    private val objectStore: CreateOnlyBackupObjectStore,
    private val codec: PublicationCodec,
) : PublicationCatalog

sealed interface PublicationResolution {
    data class Resolved(
        val current: VerifiedPublication,
        val previous: VerifiedPublication?,
    ) : PublicationResolution
    data class Failed(val reason: RemoteBackupFailureCategory) :
        PublicationResolution
}

sealed interface PublicationCreateResult {
    data class Created(val publication: VerifiedPublication) :
        PublicationCreateResult
    data class OccupiedByExpected(val publication: VerifiedPublication) :
        PublicationCreateResult
    data object OccupiedByDifferent : PublicationCreateResult
    data class Failed(val reason: RemoteBackupFailureCategory) :
        PublicationCreateResult
}

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
        objectStore: CreateOnlyBackupObjectStore,
        accountBindingDigest: ByteArray,
        allowSeparateLineage: Boolean,
    ): RemoteBackupConnectResult
}
```

`RemoteObjectCodec.reauthenticateLocalObject` accepts an explicit
`RemoteLogicalObjectId`; therefore the two base copies never share identity
even when both encode the same generation.

- [ ] **Step 1: Write failing chain and catalog tests**

```kotlin
@Test
fun invalidOccupiedSuccessorBlocksWithoutAlternateSlot() = runBlocking {
    val store = chainStoreWithOccupiedSuccessor(INVALID_BYTES)

    val result = store.resolve(ROOT_ID, CONTENT_KEY)

    assertEquals(
        OwnershipResolution.Blocked(
            RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
        ),
        result,
    )
    assertEquals(0, store.generatedAlternateIds)
}
```

Cover 64-root, 1,024-claim, and 128-publication bounds; exact header
navigation; authentication from root; missing successor as tip; invalid
occupied successor; epoch overflow; terminal tip; duplicate publication
sequence; two children; gap; missing retained predecessor; generation
regression; competing highest publication; claim/device mismatch; and retained
pair agreement.

- [ ] **Step 2: Write failing remote-object and setup tests**

```kotlin
@Test
fun initialSetupCreatesTwoIndependentBasesBeforeRoot() = runBlocking {
    val result = configurator.connect(store, ACCOUNT_DIGEST, false)

    assertTrue(result is RemoteBackupConnectResult.Connected)
    assertNotEquals(store.baseRequests[0].logicalObjectId, store.baseRequests[1].logicalObjectId)
    assertEquals(
        listOf("BASE_A", "BASE_B", "BASELINE", "ROOT"),
        store.authorityEvents,
    )
}
```

Assert local AEAD/payload verification before remote encoding, explicit remote
logical IDs, independent nonces/ciphertext, discovery before ID generation,
existing-root handling, Stage 2 complete capture, two verified bases,
sequence-zero baseline bound to planned root, root bound back to baseline,
readback/authentication before enablement, and crash resume without a second
root.

- [ ] **Step 3: Run focused RED**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests 'app.opentasks.core.data.backup.RemoteObjectCodecTest' \
  --tests 'app.opentasks.core.data.backup.OwnershipChainStoreTest' \
  --tests 'app.opentasks.core.data.backup.PublicationCatalogTest' \
  --tests 'app.opentasks.core.data.backup.DefaultRemoteBackupConfiguratorTest' \
  --stacktrace
```

Expected: compilation failures for the four production components.

- [ ] **Step 4: Implement bounded ownership and publication resolution**

Follow only exact successor IDs from public headers, then authenticate the
root and every successor after content-key unlock. Stop at a missing
successor; require authenticated and public tips to match. Never search by
timestamp or highest epoch. Publication resolution authenticates all bounded
candidates and fails closed for every duplicate, fork, gap, or competing tip.

- [ ] **Step 5: Implement remote frame re-authentication**

Verify the local Stage 2 frame and strict decoded payload, re-encode canonical
plaintext, apply the existing family bound, then encrypt with lineage plus the
explicit new remote logical ID. Write to a fresh private file, sync, clear
owned plaintext/frame arrays, and delete partial output on failure.

- [ ] **Step 6: Implement crash-resumable epoch-one setup**

Use exact phases:

```text
DISCOVERY_COMPLETED
IDENTITIES_STORED
LOCAL_BASE_CAPTURED
BASE_A_VERIFIED
BASE_B_VERIFIED
BASELINE_CREATED
BASELINE_VERIFIED
ROOT_CREATED
ROOT_VERIFIED
COMPLETED
```

Generate lineage, device, root, root successor, publication, and two base IDs
once and persist them. Encode two independent copies of the same complete
capture. Create the baseline before the root to avoid a digest cycle. Mark
remote backup active only after root readback and full chain re-resolution.

- [ ] **Step 7: Run core data GREEN**

```bash
./gradlew :core:data:testDebugUnitTest --stacktrace
```

Expected: initial setup, ownership, publication, remote object, and every
Stage 1/2 test pass.

- [ ] **Step 8: Commit epoch-one publication**

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain \
  core/data/src/main/kotlin/app/opentasks/core/data \
  core/data/src/test/kotlin/app/opentasks/core/data/backup
git commit -m "feat: publish initial create-only Drive backup"
```

### Task 8: Publish Immutable Successors and Clean Safely

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/DefaultRemoteBackupCoordinator.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/NamespaceSafeRemoteCleanup.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/DefaultRemoteBackupCoordinatorTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/backup/NamespaceSafeRemoteCleanupTest.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RemoteBackupDaos.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomRemoteBackupStore.kt`

**Interfaces:**

- Consumes: active Task 3 state, Task 7 ownership/catalog/object components,
  Stage 2 local backup inputs, and a process-scoped mutex.
- Produces:

```kotlin
sealed interface RemoteBackupRunResult {
    data class Verified(val generation: BackupGeneration) :
        RemoteBackupRunResult
    data object NoChanges : RemoteBackupRunResult
    data object AuthorizationRequired : RemoteBackupRunResult
    data object AccountMismatch : RemoteBackupRunResult
    data object OwnershipLost : RemoteBackupRunResult
    data object Terminated : RemoteBackupRunResult
    data object AmbiguousRemoteState : RemoteBackupRunResult
    data class Retryable(val reason: RemoteBackupFailureCategory) :
        RemoteBackupRunResult
    data class Blocked(val reason: RemoteBackupFailureCategory) :
        RemoteBackupRunResult
}

interface RemoteBackupCoordinator {
    suspend fun run(
        objectStore: CreateOnlyBackupObjectStore,
    ): RemoteBackupRunResult
}

data class CleanupBatchResult(
    val deletedCount: Int,
    val stoppedForOwnershipChange: Boolean,
    val blockers: Int,
)

interface NamespaceSafeRemoteCleanup {
    suspend fun runBatch(
        ownership: VerifiedOwnershipClaim,
        current: VerifiedPublication,
        previous: VerifiedPublication?,
        now: Instant,
    ): CleanupBatchResult
}
```

- [ ] **Step 1: Write the failing publication matrix**

```kotlin
@Test
fun ownershipChangeAfterPublicationPreventsCheckpoint() = runBlocking {
    val coordinator = coordinator(
        firstTip = claim(epoch = 3, device = THIS_DEVICE),
        finalTip = claim(epoch = 4, device = OTHER_DEVICE),
    )

    assertEquals(RemoteBackupRunResult.OwnershipLost, coordinator.run(store))
    assertNull(coordinator.remoteCheckpoint)
}
```

Cover single-flight join/coalesce, local Stage 2 request, no-change, exact
claim/epoch/device checks before upload, verified immutable candidates,
ownership recheck before publication create, pre-generated publication ID,
publication readback, second ownership recheck, checkpoint refusal on every
failure/cancellation/tip change, old-epoch residue, retryable/storage/auth
mapping, publication sequence independent from generation, and passphrase
rotation compatibility.

- [ ] **Step 2: Write failing retention and cleanup tests**

```kotlin
@Test
fun cleanupStopsAtThirtyTwoAndRechecksTip() = runBlocking {
    val result = cleanupWithEligibleTargets(40).runBatch(
        OWNERSHIP,
        CURRENT_PUBLICATION,
        PREVIOUS_PUBLICATION,
        NOW,
    )

    assertEquals(32, result.deletedCount)
    assertEquals(1, cleanup.tipChecks)
}
```

Assert retained current/previous publications, two bases, required segments,
seven-day local first-observed minimum, exact lineage/epoch/device/role/logical
ID/provider ID authentication, superseded-epoch cleanup only under a
self-contained current epoch, unknown/malformed/cross-namespace retention,
ownership claims retained, one authenticated tip check immediately before each
batch, and zero deletes when the next batch observes a changed tip. The epoch
baseline file remains while it is current or previous; after pruning, the
ownership claim's authenticated baseline ID/digest remains immutable creation
evidence.

- [ ] **Step 3: Run focused RED**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests 'app.opentasks.core.data.backup.DefaultRemoteBackupCoordinatorTest' \
  --tests 'app.opentasks.core.data.backup.NamespaceSafeRemoteCleanupTest' \
  --stacktrace
```

Expected: compilation failure for the coordinator and cleanup classes.

- [ ] **Step 4: Implement immutable publication ordering**

Use durable phases:

```text
OWNERSHIP_RESOLVED
LOCAL_GENERATION_VERIFIED
CANDIDATE_IDS_STORED
CANDIDATES_VERIFIED
OWNERSHIP_RECHECKED
PUBLICATION_CREATED
PUBLICATION_VERIFIED
FINAL_OWNERSHIP_RECHECKED
CHECKPOINTED
CLEANUP_STARTED
COMPLETED
```

The new publication sequence is predecessor sequence plus one. Build inventory
from the authenticated predecessor plus newly verified objects. Creation alone
never advances the remote checkpoint.

- [ ] **Step 5: Implement bounded namespace-safe cleanup**

Use locally persisted first-observed or operation-created time, never provider
time. Immediately before each batch, resolve/authenticate the current tip and
require the expected claim. Delete no more than 32 per call. Unknown or
unprovable candidates increment blockers and remain. Automatic cleanup never
deletes ownership claims.

- [ ] **Step 6: Run core data GREEN**

```bash
./gradlew :core:data:testDebugUnitTest --stacktrace
```

Expected: publication, retention, cleanup, ownership, and all Stage 1/2 tests
pass.

- [ ] **Step 7: Commit immutable publication**

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain \
  core/data/src/main/kotlin/app/opentasks/core/data/backup \
  core/data/src/test/kotlin/app/opentasks/core/data/backup
git commit -m "feat: publish immutable remote backup generations"
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
- Produces:

```kotlin
interface BackupWorkScheduler {
    fun onPendingGeneration()
    fun ensurePeriodic()
    fun cancelAll()
}

interface RemoteBackupRunner {
    suspend fun run(): RemoteBackupRunResult
}

interface RemoteBackupRuntime {
    fun start()
    fun requestNow()
    fun stop()
}
```

with constant unique names:

```text
open-tasks-remote-backup-once-v1
open-tasks-remote-backup-periodic-v1
```

- [ ] **Step 1: Add the existing WorkManager dependency**

```kotlin
implementation(libs.work.runtime)
```

Add no Hilt WorkManager or second dependency-injection library.

- [ ] **Step 2: Write failing scheduler and worker tests**

```kotlin
@Test
fun requestsUseExactPolicyAndNoPrivateInput() {
    val once = scheduler.buildOneTimeRequest()
    val periodic = scheduler.buildPeriodicRequest()

    assertEquals(Duration.ofMinutes(15), once.initialDelay)
    assertEquals(Duration.ofHours(24), periodic.repeatInterval)
    assertEquals(Duration.ofHours(6), periodic.flexInterval)
    assertEquals(0, once.inputData.size())
    assertEquals(NetworkType.CONNECTED, once.constraints.requiredNetworkType)
    assertTrue(once.constraints.requiresBatteryNotLow())
    assertTrue(once.constraints.requiresStorageNotLow())
}
```

Assert `ExistingWorkPolicy.REPLACE`, `ExistingPeriodicWorkPolicy.KEEP`,
30-second exponential backoff, no unmetered requirement, and only `Retryable`
mapping to `Result.retry()`. All success, no-change, auth, mismatch,
ownership-lost, ambiguous, terminated, and blocked results persist bounded
state then map to `Result.success()`.

- [ ] **Step 3: Run WorkManager RED**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.WorkManagerRemoteBackupSchedulerTest' \
  --tests 'app.opentasks.backup.RemoteBackupWorkerTest' \
  --tests 'app.opentasks.backup.RemoteBackupRuntimeTest' --stacktrace
```

Expected: missing scheduler, runner, worker, factory, and runtime declarations.

- [ ] **Step 4: Implement the custom worker factory**

`OpenTasksApplication` implements `Configuration.Provider` and supplies
`RemoteBackupWorkerFactory`. Worker parameters and work names contain no
lineage, Drive ID, account digest, token, claim, publication, session URI, or
passphrase.

- [ ] **Step 5: Implement non-interactive runner behavior**

Load active configuration, authorize non-interactively against its digest,
construct a session-scoped `CreateOnlyDriveObjectStore`, call the coordinator,
and close the session in `finally`. Account mismatch performs zero lineage
calls. Resolution-required persists `AUTHORIZATION_REQUIRED` without starting
UI. Provider authorization failure clears the token before close.

- [ ] **Step 6: Start work only for an active vault and lineage**

Use one mutex-protected runner for manual and automatic execution.
`RemoteBackupRuntime` observes generations only while both vault and remote
lineage are active, enqueues when local generation exceeds remote verified
generation, and cancels ordinary work on deactivation, dormancy,
ownership-loss, or termination. A durable terminal-deletion cleanup operation
remains resumable through Task 12 rather than ordinary publication work.

- [ ] **Step 7: Run app GREEN**

```bash
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest --stacktrace
```

Expected: request policy, worker mapping, startup ordering, runtime
activation/deactivation, and existing Android backup tests pass.

- [ ] **Step 8: Commit scheduling**

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
  and a new SQLCipher database key.
- Produces:

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
    private val database: VaultDatabase,
    private val importDao: RecoveryImportDao,
) : BackupRecordImporter

interface StagedVaultVerifier {
    suspend fun verify(
        slot: VaultSlot,
        expectedVaultId: VaultId,
        expectedGeneration: BackupGeneration,
        expectedCapture: StructuredBackupCapture,
    ): VerifiedStagedVault
}
```

- [ ] **Step 1: Write the exhaustive failing family test**

```kotlin
@Test
fun importsEveryBackupRecordFamilyExactly() = runBlocking {
    val request = requestWithOneRecordPerFamily()

    importer.importInto(database, request)

    for (family in BackupRecordFamily.entries) {
        assertEquals(
            "missing import assertion for $family",
            1,
            importedCount(database, family),
        )
    }
}
```

Use exact assertions for composite identities, nullable fields, ciphertext
bytes, revisions, tombstones, attachments, templates, saved views, time
entries, and relations.

- [ ] **Step 2: Write replay and rejection tests**

Cover UPSERT after-images, DELETE for every family, ordered multi-entry
generation, duplicate identities, segment gaps/overlaps, reversed ranges,
source-vault mismatch, relation/foreign-key failure, invalid tombstone, future
schema, and every payload bound. Assert no source `remote_backup_*`,
`sync_operations`, `backup_journal`, `backup_state`, device operational state,
or remote checkpoint is imported.

- [ ] **Step 3: Run importer RED**

```bash
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.core.data.backup.BackupRecordImporterInstrumentedTest \
  --stacktrace
```

Expected: compilation failure for the importer, DAO, and verifier.

- [ ] **Step 4: Implement strict typed record access**

After `BackupMutationCodec.validateRecord`, expose:

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

Every byte-array caller clears its owned result after Room copies it.

- [ ] **Step 5: Implement every record mapping explicitly**

Use one exhaustive `when (record.family)` and these named conversions:

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

Snapshot insert uses `ABORT`. Segment UPSERT uses family-specific upsert.
Segment DELETE targets the exact primary/composite identity and requires one
deleted row or a proven idempotent absence.

- [ ] **Step 6: Initialize only fresh local operational state**

Set local `backup_state.currentGeneration` to the recovered generation, leave
local verified base/segment IDs null, set package state `NOT_PREPARED`, store
the recovery envelope, and require a fresh complete Stage 2 baseline. Leave
the journal, legacy outbox, and all remote tables empty. Normalize the
recovered vault's logical schema marker to 7 even when the authenticated
portable payload originated from v6.

- [ ] **Step 7: Verify, close, and reopen staging**

Run `PRAGMA foreign_key_check`, SQLCipher integrity, record counts, identities,
and relations. Close Room, reopen with the new key, construct a normal
repository without seeding an empty vault, read the workspace, and compare a
normalized canonical capture to the authenticated recovered payload.

- [ ] **Step 8: Run importer and database GREEN**

```bash
./gradlew :core:data:connectedDebugAndroidTest --stacktrace
```

Expected: exhaustive import, replay, integrity, migration, close/reopen, and
existing repository tests pass.

- [ ] **Step 9: Commit staged import**

```bash
git add core/data/src/main core/data/src/androidTest
git commit -m "feat: reconstruct verified staging vaults"
```

### Task 11: Recover, Take Ownership, and Activate

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

- Consumes: bounded root/tombstone/publication discovery, inert portable
  package, `VaultCrypto`, Tasks 4/7/10, and the current account digest.
- Produces:

```kotlin
enum class RecoverySource {
    GOOGLE_DRIVE,
    ANDROID_BACKUP_PACKAGE,
}

data class RecoveryCandidate(
    val handle: String,
    val source: RecoverySource,
)

sealed interface RecoveryResult {
    data class TakeoverConfirmationRequired(
        val operationId: String,
        val generation: BackupGeneration,
        val nextWriterEpoch: WriterEpoch,
    ) : RecoveryResult
    data class Activated(
        val generation: BackupGeneration,
        val lineageId: CloudLineageId?,
    ) : RecoveryResult
    data class Failed(val reason: RecoveryFailureCategory) : RecoveryResult
}

interface RecoveryCoordinator {
    suspend fun discover(
        objectStore: CreateOnlyBackupObjectStore?,
        portablePackage: File?,
    ): List<RecoveryCandidate>
    suspend fun prepare(
        candidate: RecoveryCandidate,
        passphrase: CharArray,
        objectStore: CreateOnlyBackupObjectStore?,
        accountBindingDigest: ByteArray?,
    ): RecoveryResult
    suspend fun confirmTakeover(
        operationId: String,
        objectStore: CreateOnlyBackupObjectStore,
    ): RecoveryResult
}
```

Candidate handles are random, process-local, bounded, and reveal no lineage,
provider ID, timestamp, content, generation, or account identity. Drive
discovery lists both roots and terminal tombstones; an authenticated terminal
lineage cannot be recovered or silently recreated.

- [ ] **Step 1: Extend portable decode tests**

Add:

```kotlin
data class DecodedPortableBackup(
    val snapshot: BackupSnapshotPayloadV1,
    val recoveryEnvelope: VaultKeyEnvelope,
    val generation: BackupGeneration,
) : AutoCloseable

fun PortableBackupCodec.decodeComplete(
    source: File,
    passphrase: CharArray,
): DecodedPortableBackup
```

Assert existing `verifyComplete` bytes and Android package bounds remain
unchanged and all transferred sensitive buffers have one owner.

- [ ] **Step 2: Write failing source, fallback, and ambiguity tests**

```kotlin
@Test
fun damagedCurrentBaseRecoversThroughIndependentFallback() = runBlocking {
    val result = coordinator.prepare(
        DRIVE_CANDIDATE,
        PASSPHRASE.copyOf(),
        storeWithDamagedCurrentAndValidFallback(),
        ACCOUNT_DIGEST,
    )

    assertTrue(result is RecoveryResult.TakeoverConfirmationRequired)
    assertEquals(FALLBACK_BASE_ID, coordinator.baseUsed)
}
```

Cover wrong passphrase, KDF rejection before derivation, exact authenticated
ownership tip, duplicate/fork/gap publication failure, current base, fallback
base, required segments, both bases damaged, missing object, future format,
AEAD/identity disagreement, insufficient storage, terminal tombstone, and
wrong account before lineage access.

- [ ] **Step 3: Write failing takeover race and crash tests**

Assert two fresh complete bases and baseline are verified before confirmation;
predecessor tip is reread; claim uses the predecessor's exact successor ID and
epoch plus one; occupied slot authenticates the winner; loser never activates;
winner must name this device and exact baseline; old-epoch late publication is
ignored; crash after claim resumes the same operation/epoch/staged slot; and
no alternate successor is allocated.

- [ ] **Step 4: Run recovery RED**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests 'app.opentasks.core.data.backup.DefaultRecoveryCoordinatorTest' \
  --stacktrace
```

Expected: missing coordinator and decoded portable result declarations.

- [ ] **Step 5: Implement bounded authentication and reconstruction**

For Drive, validate public bootstrap/KDF bounds before allocation or
derivation, unlock only bounded candidate envelopes, authenticate the entire
ownership chain from root, require an `ACTIVE` public/authenticated tip match,
resolve one unique publication pair, download exact inventory, and reconstruct
staging. Never choose by provider time, name, list order, or highest
observational epoch.

- [ ] **Step 6: Implement the self-contained takeover**

Use phases:

```text
SOURCE_AUTHENTICATED
STAGING_RECONSTRUCTED
STAGING_VERIFIED
TAKEOVER_IDENTITIES_STORED
BASE_A_VERIFIED
BASE_B_VERIFIED
BASELINE_CREATED
BASELINE_VERIFIED
PREDECESSOR_RECHECKED
CONFIRMATION_REQUIRED
CLAIM_CREATED
CLAIM_VERIFIED
ACTIVATED
COMPLETED
```

Persist the staged database key wrapper, slot-scoped content-key wrapper,
generated IDs, operation, planned epoch, and activation facts before claim.
Do not persist passphrase, access token, or raw content key.

- [ ] **Step 7: Create and authenticate the exact successor**

After explicit confirmation, create at the predecessor's reserved ID. On
`AlreadyExists` or `Ambiguous`, download and authenticate exact bytes.
`Won` requires this device and exact baseline; `Lost` closes staging without
activation and reports ownership loss. A crash after `CLAIM_CREATED` reuses
the already claimed operation and never increments again.

- [ ] **Step 8: Activate only the winning staged vault**

Call `VaultRuntimeManager.activate` only after `CLAIM_VERIFIED`. Portable
recovery without a retained Drive lineage activates with no remote
configuration and requires later explicit new-lineage connection. Recovered
local state requires a fresh Stage 2 complete base before any new remote
publication.

- [ ] **Step 9: Verify process-death activation on the disposable target**

Instrument suspension points before claim, after claim, before marker replace,
and after marker replace. Kill only the test process and assert restart opens
either the unchanged prior slot or the fully verified winning recovered slot.

```bash
./gradlew :core:data:testDebugUnitTest \
  :core:data:connectedDebugAndroidTest --stacktrace
```

Expected: source, fallback, race, process-death, slot activation, and existing
database tests pass.

- [ ] **Step 10: Commit recovery and takeover**

```bash
git add core/domain/src/main core/data/src/main core/data/src/test \
  core/data/src/androidTest
git commit -m "feat: recover and take over backup lineages"
```

### Task 12: Rotate Passphrases and Manage Remote Lifecycle

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
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/PublicationCodec.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/PublicationCatalog.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RemoteBackupDaos.kt`
- Modify:
  `app/src/main/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManager.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt`

**Interfaces:**

- Consumes: active content key/envelope, portable publisher, current
  ownership/publication, remote operation journal, scheduler, authorization,
  Task 7 configurator, and Task 8 cleanup.
- Produces:

```kotlin
enum class PassphraseChangeFailureCategory {
    CURRENT_PASSPHRASE_INVALID,
    PORTABLE_PACKAGE,
    REMOTE_BACKUP,
    LOCAL_STORAGE,
}

interface RecoveryPassphraseChanger {
    suspend fun change(
        currentPassphrase: CharArray,
        newPassphrase: CharArray,
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
    suspend fun deleteHistory(passphrase: CharArray): LifecycleResult
    suspend fun preserveDivergentWorkAsNewLineage():
        RemoteBackupConnectResult
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

- [ ] **Step 1: Write passphrase-rotation crash tests**

```kotlin
@Test
fun rotationAdvancesSequenceWithoutAdvancingGenerationOrEpoch() = runBlocking {
    val result = changer.change(OLD.copyOf(), NEW.copyOf())

    assertTrue(result is PassphraseChangeResult.Changed)
    assertEquals(8L, published.manifest.publicationSequence)
    assertEquals(42L, published.manifest.localGeneration)
    assertEquals(3L, published.manifest.writerEpoch)
    assertEquals(2L, published.manifest.recoveryCredentialGeneration)
}
```

Cover current-passphrase verification, unchanged content key/inventory,
portable readback, immutable publication readback, unchanged-tip recheck,
local promotion last, death at every phase, old envelope active before
promotion, pending envelope only in SQLCipher, and buffer clearing.

- [ ] **Step 2: Write disconnect and terminal-deletion tests**

Disconnect must cancel scheduling, prevent a new runner, clear/revoke when
available, persist dormant even when revocation fails, and make zero Drive
file list/read/create/delete calls.

Deletion must require passphrase and ownership, persist intent/generated
tombstone ID, create `TERMINATED` at the exact successor slot, authenticate
it, stop publication/takeover, delete at most 32 recoverable objects per
batch, retain residue younger than seven days, remove root and non-terminal
claims after recoverable bytes, retain the terminal tombstone permanently,
resume after death, and never republish.

- [ ] **Step 3: Run lifecycle RED**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.RecoveryPassphraseChangerTest' \
  --tests 'app.opentasks.backup.DefaultRemoteBackupLifecycleCoordinatorTest' \
  --stacktrace
```

Expected: missing lifecycle classes and explicit-envelope portable method.

- [ ] **Step 4: Add explicit-envelope portable publication**

```kotlin
suspend fun PortableBackupPublisher.publishWithEnvelope(
    envelope: VaultKeyEnvelope,
): AndroidBackupStatus
```

The existing Stage 2 path delegates to it. This method verifies the package
without promoting the Room recovery envelope.

- [ ] **Step 5: Implement passphrase operation phases**

```text
PENDING_ENVELOPE_STORED
PORTABLE_VERIFIED
REMOTE_PUBLICATION_CREATED
REMOTE_PUBLICATION_VERIFIED
OWNERSHIP_RECHECKED
LOCAL_ENVELOPE_PROMOTED
COMPLETED
```

Increment publication sequence and recovery-credential generation only. Keep
writer epoch, local generation, claim, device, and inventory unchanged.

- [ ] **Step 6: Implement disconnect with zero storage calls**

Persist dormant intent and cancel work before token clear/revoke. Authorization
cleanup may fail with a bounded local disclosure but cannot reactivate backup.
Retain encrypted lineage configuration and backup history.

- [ ] **Step 7: Implement terminal deletion phases**

```text
DELETE_INTENT_STORED
TOMBSTONE_ID_STORED
TOMBSTONE_CREATED
TOMBSTONE_VERIFIED
PAYLOAD_CLEANUP
CLAIM_CLEANUP
COMPLETED
```

The terminal claim contains no recovery envelope, KDF data, vault identity,
device identity, inventory, content-key wrapping, or successor. An occupied
successor is authenticated; an active competing winner returns ownership
loss, while the exact expected terminal winner resumes cleanup. Never allocate
an alternate slot or delete the terminal marker.

- [ ] **Step 8: Implement divergent-lineage preservation**

Only locally known ownership-lost state may call Task 7 with an explicitly new
lineage under the bound account. Preserve local `VaultId`; import or merge no
remote record; reactivate no lost lineage.

- [ ] **Step 9: Run lifecycle GREEN**

```bash
./gradlew :app:testDebugUnitTest :core:data:testDebugUnitTest --stacktrace
```

Expected: lifecycle, portable-package, publication, terminal format, cleanup,
and all Stage 1/2 tests pass.

- [ ] **Step 10: Commit lifecycle operations**

```bash
git add app/src/main/kotlin/app/opentasks/backup \
  app/src/test/kotlin/app/opentasks/backup \
  core/domain/src/main/kotlin/app/opentasks/core/domain/RemoteBackupContracts.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/backup \
  core/data/src/test/kotlin/app/opentasks/core/data/backup \
  app/src/main/kotlin/app/opentasks/di/AppModule.kt
git commit -m "feat: manage create-only backup lifecycle"
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

- Consumes: independent remote/Android status, Task 5 ephemeral authorization
  resolution, Task 4 runtime state, Task 11 recovery, and Task 12 lifecycle.
- Produces:

```kotlin
data class EncryptedBackupPresentation(
    val status: RemoteBackupStatus,
    val canBackUpNow: Boolean,
    val canRestore: Boolean,
    val canReauthorise: Boolean,
    val canTakeOver: Boolean,
    val canPreserveAsNewLineage: Boolean,
    val canChangePassphrase: Boolean,
    val canDisconnect: Boolean,
    val canDeleteHistory: Boolean,
    val passphraseChangeDisclosureVisible: Boolean,
)

data class RecoveryCandidateSummary(
    val handle: String,
    val source: RecoverySource,
)

sealed interface RecoveryPresentation {
    data object NoVault : RecoveryPresentation
    data object UnreadableVault : RecoveryPresentation
    data object Discovering : RecoveryPresentation
    data class Candidates(
        val values: List<RecoveryCandidateSummary>,
    ) : RecoveryPresentation
    data object Authenticating : RecoveryPresentation
    data class TakeoverConfirmation(
        val operationId: String,
        val generation: Long,
    ) : RecoveryPresentation
    data object Activating : RecoveryPresentation
    data class Failed(
        val reason: RecoveryFailureCategory,
    ) : RecoveryPresentation
}
```

- [ ] **Step 1: Write failing ViewModel tests**

```kotlin
@Test
fun processStateContainsNoCredentialOrRemoteIdentity() {
    viewModel.connect()
    val saved = viewModel.savedStateForTest()

    assertFalse(saved.contains("PendingIntent"))
    assertFalse(saved.contains("ProviderObjectId"))
    assertFalse(saved.contains("CloudLineageId"))
    assertFalse(saved.contains(TEST_PASSPHRASE))
}
```

Cover explicit account selection, one-shot non-replayed resolution,
existing-backup restore/separate-lineage choice, shared runner for backup now,
foreground re-authorization, wrong account, ownership loss takeover/new
lineage, distinct disconnect/delete, fresh cleared passphrase arrays, opaque
candidates before authentication, terminal/ambiguous states, and no private
saved state.

- [ ] **Step 2: Run ViewModel RED**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.EncryptedBackupViewModelTest' \
  --tests 'app.opentasks.backup.RecoveryViewModelTest' --stacktrace
```

Expected: missing ViewModels and presentation types.

- [ ] **Step 3: Implement app-layer ViewModels**

Use `viewModelScope`, one operation mutex each, and a non-replay one-shot
channel for `PendingIntent`. Convert UI strings to `CharArray` only at the
service boundary and clear them in `finally`. UI-facing methods never accept
an object store or provider client.

- [ ] **Step 4: Write failing two-card and recovery-shell Compose tests**

Required tags:

```text
encrypted-backup-heading
encrypted-backup-connect
encrypted-backup-now
encrypted-backup-reauthorize
encrypted-backup-takeover
encrypted-backup-preserve
encrypted-backup-change-passphrase
encrypted-backup-disconnect
encrypted-backup-delete
android-backup-heading
recovery-shell
recovery-drive
recovery-portable
recovery-passphrase
recovery-takeover-confirm
```

Test compact, expanded, separating fold, 100%, 130%, and 200% text; 48 dp
actions; headings; focus order; scroll reachability; masked non-saveable
passphrases; IME next/done; error semantics; truthful verified
generation/time; and no successful time for pending/failed state.

- [ ] **Step 5: Implement exact status copy**

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
Backup state is ambiguous
Backup blocked
Deleting backup history
Backup history deleted
```

Use **active device**, **backup**, and **restore**. Do not use **sync**. Keep
the existing Android card's package facts and system-settings guidance.

- [ ] **Step 6: Implement runtime-gated recovery shell routing**

`MainActivity` renders `RecoveryShellScreen` for `NoVault`, `Unreadable`, and
`Recovering`. It creates `OpenTasksApp`, `WorkspaceViewModel`, and active
backup ViewModels only for `Active`. Inert portable-package discovery does not
construct an active repository, Android backup runtime, or content-key store.

- [ ] **Step 7: Add truthful lifecycle disclosures**

Disconnect says Open Tasks sends no Drive file request. Deletion says the
recoverable app-managed history is permanent, a non-secret safety marker
remains, and Android's separate package is not deleted. Passphrase change says
older Drive publications, Android packages, and copied exports may remain
usable with the old passphrase.

- [ ] **Step 8: Run UI and restoration GREEN**

```bash
./gradlew :app:testDebugUnitTest \
  :feature:more:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest --stacktrace
```

Expected: ViewModel, two-card, shell, process-restoration, fold, text-scale,
accessibility, and existing Stage 2 UI tests pass.

- [ ] **Step 9: Commit product surfaces**

```bash
git add app/src/main app/src/test app/src/androidTest \
  feature/more/src/main feature/more/src/androidTest
git commit -m "feat: add create-only backup and recovery UI"
```

### Task 14: Qualify Two-Installation Recovery and Close Stage 3

**Files:**

- Create:
  `app/src/androidTest/kotlin/app/opentasks/RemoteBackupBoundaryInstrumentedTest.kt`
- Create:
  `app/src/androidTest/kotlin/app/opentasks/RecoveryTakeoverInstrumentedTest.kt`
- Create:
  `docs/qualification/stage3-google-drive-create-only-backup-recovery.md`
- Modify:
  `docs/superpowers/specs/2026-07-30-stage-3-drive-create-only-ownership-design.md`
- Modify:
  `docs/superpowers/specs/2026-07-30-stage-3-google-drive-backup-recovery-design.md`
- Modify: `HANDOFF.md`
- Modify: `CLAUDE.md`

**Interfaces:**

- Consumes: Tasks 1 through 13, two disposable installations or isolated
  emulator profiles, a bound account, a different account for mismatch
  testing, and protected-workspace metadata for read-only comparison.
- Produces: reproducible non-private evidence, implemented design status, and
  the authoritative next-stage handoff.

- [ ] **Step 1: Add release boundary tests**

Assert release packaging contains only `drive.appdata`, no broad Drive scope,
no server-client secret, no debug qualification activity, no Room/preferences/
keys/recovery registry/staging/provider state in Android backup, exactly the
existing portable include, and no identifier-bearing WorkManager name or data.

```kotlin
@Test
fun releaseContainsNoMutableDriveAuthorityContract() {
    val strings = packagedReleaseStrings()
    assertFalse(strings.contains("If-Match"))
    assertFalse(strings.contains("providerRevision"))
    assertFalse(strings.contains("compareAndSwap"))
}
```

- [ ] **Step 2: Add deterministic fake-provider end-to-end tests**

Execute:

```text
epoch-one setup with two independent bases
incremental immutable publication
resumable upload process death
staged recovery
two-base takeover
two contenders racing one exact successor
old owner finishing ignored old-epoch publication
fallback recovery after current-base corruption
passphrase rotation at equal generation and next sequence
divergent old-owner preservation into a new lineage
disconnect and reconnect
wrong account before lineage access
terminal tombstone, process death, bounded cleanup
final remote recoverability state containing only the tombstone
independent inert Android package
```

Compare the canonical workspace capture before backup and after recovery.

- [ ] **Step 3: Run fast repository gates**

```bash
git diff --check
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
```

Expected: PASS. Record exact executed task and JVM test counts.

- [ ] **Step 4: Run release separately**

```bash
./gradlew :app:assembleRelease --stacktrace
```

Expected: PASS with R8 and resource shrinking.

- [ ] **Step 5: Run schema and deterministic-fixture gates**

```bash
./scripts/check-schema-drift.sh
node scripts/generate-stage3-drive-create-only-v1-fixtures.mjs
git diff --exit-code -- \
  core/data/src/test/resources/backup-format/drive-create-only-v1 \
  core/data/schemas
```

Expected: no fixture or schema difference.

- [ ] **Step 6: Audit and run all connected tests on the sole disposable target**

```bash
: "${STAGE3_ADB_SERIAL:?Set STAGE3_ADB_SERIAL to the audited disposable target}"
export ANDROID_SERIAL="$STAGE3_ADB_SERIAL"
adb devices
adb -s "$STAGE3_ADB_SERIAL" shell getprop ro.build.version.sdk
./gradlew connectedDebugAndroidTest --stacktrace
```

Stop before Gradle if another target is eligible. Expected: all connected
tests pass on the audited target.

- [ ] **Step 7: Run credentialed two-installation acceptance**

Using disposable data:

1. select the bound account and create epoch one;
2. publish an incremental generation;
3. interrupt and resume a frame larger than 5 MiB;
4. recover on installation B;
5. upload and verify B's two independent bases;
6. race takeover contenders and prove one exact successor winner;
7. allow A to finish an old-epoch publication and prove it is ignored;
8. recover B through its fallback base;
9. rotate the passphrase and recover a fresh disposable installation;
10. preserve divergent A work under a new lineage;
11. disconnect/reconnect B without Drive file calls during disconnect;
12. select the wrong account and prove zero lineage access;
13. create the terminal tombstone, interrupt cleanup, resume, and leave only
    the tombstone; and
14. prove Android Auto Backup remains independent and inert.

Record bounded state names, generations, byte counts, request families, times,
and pass/fail only.

- [ ] **Step 8: Re-verify the protected workspace non-destructively**

Install/relaunch the v7 app without instrumentation, uninstall, clear, restore,
or backup-manager commands against the protected target. Compare before/after
package identity, database/WAL/SHM inode identities, visible record counts and
names, projects, active timer, backup generation, portable package state, and
legacy outbox count.

- [ ] **Step 9: Run privacy and forbidden-authority audits**

```bash
rg -n 'Authorization: Bearer|drive\\.google\\.com|permissionId|sessionUri|CloudLineageId\\(|CloudDeviceId\\(' \
  app core feature docs/qualification
rg -n 'ETag|If-Match|providerRevision|compareAndSwap|Drive JSON version' \
  app core feature docs/qualification
```

Every first-scan match must be a declaration, test fixture, or redacted
boundary; no runtime value may appear in logs or copy. The second scan must
contain only explicit negative tests or historical provider evidence, never a
production authority path.

- [ ] **Step 10: Update authoritative documentation**

Set the create-only design status to `Implemented and verified` only after all
gates pass. Mark the older mutable-control design implemented only where still
retained and point to the create-only replacement for concurrency. Record
exact commits, tests, provider gate facts, residual limitations, and next
approved action in `HANDOFF.md`. Add durable v7, immutable ownership,
publication-sequence, vault-slot, cleanup, and terminal-tombstone invariants to
`CLAUDE.md`.

- [ ] **Step 11: Commit Stage 3 qualification**

```bash
git add app/src/androidTest docs/qualification \
  docs/superpowers/specs/2026-07-30-stage-3-drive-create-only-ownership-design.md \
  docs/superpowers/specs/2026-07-30-stage-3-google-drive-backup-recovery-design.md \
  HANDOFF.md CLAUDE.md
git commit -m "docs: verify create-only Stage 3 backup"
```

- [ ] **Step 12: Inspect final repository state**

```bash
git status --short
git log --oneline -15
```

Expected: only known user-owned untracked files remain. Do not begin Stage 4
brainstorming or implementation in this plan.
