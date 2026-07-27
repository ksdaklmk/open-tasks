# Train 2 — Drive Identity and Core Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Authorise only Google Drive app-data access and deliver resilient,
visible, offline-first manifest/snapshot/operation sync across devices.

**Architecture:** `app` owns Google authorisation UI; an access-token provider
feeds a Drive REST `CloudObjectStore` in `core:data`. Room keeps the local cache,
cursor, per-device HLC, quarantine, and atomic outbox. `RoomSyncCoordinator`
serialises uploads and downloads through the Train 1 encrypted format and
applies remote operations through a no-reoutbox merge transaction. WorkManager
and manual refresh share that coordinator.

**Tech Stack:** Google Identity Services AuthorizationClient
(`com.google.android.gms:play-services-auth:21.4.0`), Drive REST v3 over
`HttpsURLConnection`, WorkManager, Room/SQLCipher, kotlinx.serialization, Tink,
coroutines, JUnit 4, and instrumented fake/credentialed Drive tests.

**Backlog:** P1-D01, P1-D03, and P1-D04.

## Global Constraints

- Follow the master plan constraints.
- Request only `https://www.googleapis.com/auth/drive.appdata`.
- Do not request broad Drive, contacts, profile, email, or storage permissions.
- Persist no access/refresh token and no account email. Use only an opaque
  account key and a user-facing generic “Google Drive connected” state.
- Local commands must continue when offline, unauthorised, over quota, rate
  limited, or when WorkManager is stopped.
- A downloaded operation never creates a duplicate upload operation.

---

### Task 2.1: Expand the streaming cloud-store contract

**Files:**
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/CloudObjectStore.kt`
- Create:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/CloudObjectStoreContract.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/FakeCloudObjectStore.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/FakeCloudObjectStoreTest.kt`

**Interfaces:**

```kotlin
fun interface CloudPayloadSource {
    fun openStream(): InputStream
}

data class CloudUpload(
    val source: CloudPayloadSource,
    val byteCount: Long,
    val sha256: String,
)

data class CloudObject(
    val id: CloudObjectId,
    val name: String,
    val revision: String,
    val byteCount: Long,
    val sha256: String?,
)

data class ChangePage(
    val changes: List<CloudChange>,
    val nextPageToken: String?,
    val newStartPageToken: String?,
)

interface CloudObjectStore {
    suspend fun getStartPageToken(): String
    suspend fun listChanges(pageToken: String): ChangePage
    suspend fun listByPrefix(prefix: String, pageToken: String?): CloudListPage
    suspend fun upload(
        objectName: String,
        upload: CloudUpload,
        expectedRevision: String? = null,
    ): CloudObject
    suspend fun download(id: CloudObjectId, destination: OutputStream): CloudObject
    suspend fun delete(id: CloudObjectId, expectedRevision: String? = null)
}
```

- [ ] Write a reusable contract suite for pagination, stable IDs/revisions,
prefix listing, source reopening on retry, streamed download, conditional
write/delete, duplicate page entries, and injected mid-stream failure.

- [ ] Run:

```bash
./gradlew :core:domain:testDebugUnitTest :core:data:testDebugUnitTest \
  --tests '*FakeCloudObjectStoreTest' --stacktrace
```

Expected: compilation failure for the expanded contract.

- [ ] Implement the fake with deterministic pages and fault injection. Keep
payloads bounded in tests and compare SHA-256 after streaming.

- [ ] Re-run the focused suite.

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain/CloudObjectStore.kt \
  core/domain/src/test core/data/src/test
git commit -m "refactor: define streaming cloud object store"
```

### Task 2.2: Add least-privilege Google authorisation

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create:
  `app/src/main/kotlin/app/opentasks/drive/DriveAuthorizationManager.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/drive/GoogleDriveAuthorizationManager.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/drive/DriveAuthorizationManagerTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**

```kotlin
enum class DriveConnectionState {
    DISCONNECTED,
    AUTHORISING,
    CONNECTED,
    ACTION_REQUIRED,
}

sealed interface DriveTokenResult {
    data class Granted(val token: CharArray) : DriveTokenResult
    data object ActionRequired : DriveTokenResult
    data class Failed(val category: SyncFailureCategory) : DriveTokenResult
}

interface DriveAuthorizationManager {
    val state: StateFlow<DriveConnectionState>
    suspend fun authorise(activity: Activity): DriveTokenResult
    suspend fun token(interactive: Boolean, activity: Activity?): DriveTokenResult
    suspend fun disconnect()
}
```

The token is used immediately, cleared in `finally`, and never enters a
ViewModel, bundle, log, preference, database, or test report.

- [ ] Add failing unit tests around a fake AuthorizationClient for exact scope,
interactive/non-interactive behaviour, cancellation, expired grants, and
disconnect.

- [ ] Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*DriveAuthorizationManagerTest' --stacktrace
```

Expected: compilation failure for the manager.

- [ ] Add Play Services Auth 21.4.0 to the version catalog and app. Configure
the request with exactly:

```kotlin
Scope("https://www.googleapis.com/auth/drive.appdata")
```

Use the Activity result flow from `MainActivity`; retain no email/profile.

- [ ] Re-run tests and inspect the merged manifest for unintended permissions:

```bash
./gradlew :app:testDebugUnitTest :app:processDebugMainManifest --stacktrace
rg -n 'GET_ACCOUNTS|READ_CONTACTS|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE' \
  app/build/intermediates/merged_manifests
```

Expected: tests pass and the permission scan prints nothing.

- [ ] Commit:

```bash
git add gradle/libs.versions.toml app
git commit -m "feat: add least privilege Drive authorisation"
```

### Task 2.3: Implement Drive `appDataFolder` REST transport

**Files:**
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/drive/DriveHttpClient.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/drive/DriveCloudObjectStore.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/drive/DriveFailures.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/drive/DriveCloudObjectStoreTest.kt`

**Interfaces:**

```kotlin
fun interface DriveAccessTokenProvider {
    suspend fun token(): CharArray
}

class DriveCloudObjectStore(
    private val tokenProvider: DriveAccessTokenProvider,
    private val http: DriveHttpClient,
    private val retryPolicy: CloudRetryPolicy,
) : CloudObjectStore
```

Drive fields are restricted to `id,name,version,size,md5Checksum,trashed` and
change tokens. Every create uses parent `appDataFolder`; every list/change
request includes `spaces=appDataFolder`.

- [ ] Write HTTP-fixture tests for start token, list pagination, change
pagination, create/update, conditional revision mismatch, resumable upload,
stream download, delete, duplicate IDs, 401 refresh once, 403 quota/rate-limit
classification, 404, 5xx backoff, cancellation, and mid-stream disconnect.

- [ ] Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*DriveCloudObjectStoreTest' --stacktrace
```

Expected: compilation failure for the Drive transport.

- [ ] Implement Drive REST v3 with `HttpsURLConnection`; set connect/read
timeouts, disable automatic redirects to untrusted hosts, stream in fixed
buffers, and close token arrays/streams in `finally`. Never log URLs or bodies.

- [ ] Use resumable sessions for objects over 5 MiB. On retry, reopen
`CloudPayloadSource` and query the accepted byte range before continuing.

- [ ] Re-run the focused tests.

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/data/src/main/kotlin/app/opentasks/core/data/drive \
  core/data/src/test/kotlin/app/opentasks/core/data/drive
git commit -m "feat: implement Drive app-data object transport"
```

### Task 2.4: Persist sync identity, cursor, quarantine, and outbox segments

**Files:**
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/Entities.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/SyncDao.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/SyncOperationCodec.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/SyncLocalStore.kt`
- Modify:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`
- Create:
  `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/6.json`

**Interfaces:**

```kotlin
data class SyncMetadataEntity(
    val vaultId: String,
    val deviceId: String,
    val changePageToken: String?,
    val lastSnapshotHlc: String?,
    val lastSuccessfulSyncEpochMillis: Long?,
)

data class QuarantinedCloudObjectEntity(
    val objectId: String,
    val category: String,
    val observedEpochMillis: Long,
    val retryable: Boolean,
)

interface SyncLocalStore {
    suspend fun pending(limit: Int): List<SyncOperation>
    suspend fun markUploaded(operationIds: Set<String>, segmentId: String)
    suspend fun cursor(): String?
    suspend fun commitCursor(token: String, at: Instant)
    suspend fun quarantine(failure: QuarantinedObject)
}
```

- [ ] Add failing Room tests for v5→v6 preservation, stable device ID,
outbox survival across restart, uploaded marking, cursor atomicity, quarantine
without payload content, and no destructive fallback.

- [ ] Run:

```bash
./gradlew :core:data:connectedDebugAndroidTest --stacktrace
```

Expected: migration failure because v6 does not exist.

- [ ] Add the non-destructive migration and DAO. Split outbox encoding from
`RoomVaultRepository.kt` into `SyncOperationCodec.kt`; keep the current
versioned payloads readable.

- [ ] Export schema 6 and review that no plaintext index or token exists.

- [ ] Re-run the device suite.

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/data/src/main core/data/src/androidTest core/data/schemas
git commit -m "feat: persist sync cursors and quarantine state"
```

### Task 2.5: Implement deterministic remote merge without re-outbox

**Files:**
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/RemoteMergeApplier.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/RemoteMergeContractTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Modify:
  `core/sync/src/main/kotlin/app/opentasks/core/sync/MergeRules.kt`
- Modify:
  `core/sync/src/test/kotlin/app/opentasks/core/sync/MergeRulesTest.kt`

**Interfaces:**

```kotlin
interface RemoteMergeApplier {
    suspend fun apply(batch: RemoteOperationBatch): RemoteApplyResult
}

data class RemoteApplyResult(
    val applied: Int,
    val duplicates: Int,
    val conflicts: Int,
    val rejected: Int,
)
```

The Room implementation performs applied-operation deduplication, record
merge, conflict persistence, and cursor advancement in one transaction. It
does not call `VaultRepository.execute`.

- [ ] Add a shared failing contract covering duplicate and reordered delivery,
HLC wall-clock rollback, field-level concurrent edits, tombstones, dependency
cycles, workflow/milestone ownership, overlapping timers, unknown commands,
and remote redelivery after restart.

- [ ] Assert that outbox row count is unchanged when applying a purely remote
batch.

- [ ] Run:

```bash
./gradlew :core:sync:testDebugUnitTest :core:data:testDebugUnitTest \
  --tests '*RemoteMergeContractTest' --stacktrace
```

Expected: compilation failure for `RemoteMergeApplier`.

- [ ] Extract the touched repository merge/write helpers into
`RemoteMergeApplier.kt`. Reuse the same validation limits as local commands;
quarantine a whole invalid operation, never partially apply it.

- [ ] Run the contract against in-memory and Room (device) implementations.

Expected: all pass with no new outbox rows from remote apply.

- [ ] Commit:

```bash
git add core/data core/sync
git commit -m "feat: add idempotent no-reoutbox remote merge"
```

### Task 2.6: Orchestrate snapshot/segment sync and retries

**Files:**
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/sync/RoomSyncCoordinator.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/sync/SyncWorker.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/sync/SyncWorkScheduler.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/sync/RoomSyncCoordinatorTest.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`

**Interfaces:**

```kotlin
interface SyncCoordinator {
    val state: StateFlow<SyncState>
    suspend fun requestSync(reason: SyncReason)
}

// Retain STARTUP, EDIT, USER_REFRESH, PERIODIC, and PROVIDER_MIGRATION;
// add CONNECTIVITY and RECOVERY to the existing core:model enum.
```

Sync order is: load/verify manifest; upload immutable local device segment;
download changed objects through all pages; decrypt/quarantine; apply remote
batches; upload a complete snapshot when compaction policy triggers; conditionally
update manifest; commit cursor only after successful apply.

- [ ] Write failing tests for empty sync, offline local edits, two-device
convergence, pagination, duplicate/reordered objects, conditional manifest
race, expired auth, quota, rate limit, 5xx, checksum failure, decryption
failure, unsupported version, retry exhaustion, cancellation, and corrupt
current snapshot with valid previous snapshot.

- [ ] Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*RoomSyncCoordinatorTest' --stacktrace
```

Expected: compilation failure for the coordinator.

- [ ] Implement one per-vault mutex. Segment names are opaque
`v/<vault-id>/o/<device-id>/<first-hlc>-<last-hlc>`; snapshots are immutable
and the manifest points to current and previous verified snapshots.

- [ ] Schedule unique periodic work with network constraint and exponential
backoff. Manual refresh may run offline and return a typed offline state; it
must not block local commands.

- [ ] Re-run focused and Room device tests.

- [ ] Commit:

```bash
git add core/domain core/data app/src/main/kotlin/app/opentasks/di
git commit -m "feat: orchestrate resilient encrypted Drive sync"
```

### Task 2.7: Surface sync health and prove multi-device behaviour

**Files:**
- Create:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/PrivacyRecoveryScreen.kt`
- Create:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/SyncHealthInstrumentedTest.kt`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`
- Modify:
  `feature/home/src/main/kotlin/app/opentasks/feature/home/HomeScreen.kt`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `core/data/src/androidTest/kotlin/app/opentasks/core/data/DriveSyncInstrumentedTest.kt`
- Modify: `docs/architecture.md`
- Modify: `docs/threat-model.md`
- Modify: `HANDOFF.md`

**Interfaces:** `SyncState` exposes phase, pending operation count, last
success, actionable block reason, retryability, and conflicted record count.
It never exposes Drive IDs or account details.

- [ ] Add Compose tests for local-only, connecting, pending, syncing, offline,
auth-required, quota, damaged-object, incompatible-version, retry-exhausted,
conflict, manual refresh, and 200% text states.

- [ ] Add fake multi-device device tests for A→B, offline A/B edits, duplicate
delivery, pagination, process restart, and local edit during active sync.

- [ ] Run the tests and confirm the UI cases fail before wiring.

- [ ] Add a compact Home sync indicator and a full Privacy & recovery status
surface under More. Authentication and retry callbacks are passed from `app`;
feature code imports no Google/Drive class.

- [ ] Add an opt-in credentialed instrumented test guarded by
`OPEN_TASKS_DRIVE_TEST=1`; use a dedicated test vault prefix, delete only that
prefix in teardown, and never print tokens/object bodies.

- [ ] Run all focused tests, then the master train exit gates. Update threat
gates T11–T13 and record exact fake/credentialed evidence.

- [ ] Commit:

```bash
git add app feature/home feature/more core/data docs HANDOFF.md
git commit -m "feat: expose and verify Drive sync health"
```
