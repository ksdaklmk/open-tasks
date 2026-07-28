# Train 4 — Notes, Attachments, and Search Implementation Plan

> **Superseded — 28 July 2026:** Do not execute this train. The approved
> local-authority, backup, recovery-takeover, and cloud-attachment direction is
> defined in the 28 July design and the live production master plan.

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add revisioned notes, meaningful immutable activity, encrypted local
and cloud attachments, bounded offline cache behaviour, safe sharing, and
workspace search for notes and attachment names.

**Architecture:** Finalise one attachment record and encrypted chunk contract
for local and Drive use. App URI coordinators stream untrusted input into a
`core:data` staging store; repository registration remains the durable
metadata/outbox boundary. Generated activity is emitted transactionally for
meaningful command outcomes. Search scans the already-decrypted snapshot and
adds no plaintext index.

**Tech Stack:** Kotlin, Room/SQLCipher schema 7, Tink streaming chunks, Drive
object store, WorkManager, Photo Picker, Storage Access Framework, drag/drop,
FileProvider, JUnit 4, Compose UI test.

**Backlog:** P2-F02, P1-D07, and P1-L05.

## Global Constraints

- Follow the master plan constraints.
- Final local schema version for v1 is 7. Train 5 archive work consumes it and
  must not add product tables.
- Limits: 500 characters/note, 10,000 activity entries/task,
  100 attachments/task, 100 MiB plaintext/attachment, 4 MiB plaintext/chunk.
- MIME type, display name, provider size, and URI are untrusted.
- No broad storage permission and no decrypted attachment-content search.
- Partial/unverified files never become visible attachment records.

---

### Task 4.1: Finalise activity, note, and attachment domain records

**Files:**
- Modify:
  `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt`
- Modify:
  `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/ActivityRules.kt`
- Create:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/ActivityRulesTest.kt`

**Interfaces:**

```kotlin
enum class ActivityKind {
    LEGACY,
    NOTE,
    TASK_CREATED,
    TASK_COMPLETED,
    TASK_REOPENED,
    STATUS_CHANGED,
    PROJECT_MOVED,
    MILESTONE_CHANGED,
    ATTACHMENT_ADDED,
    ATTACHMENT_DELETED,
    RECOVERED,
}

enum class AttachmentTransferState {
    LOCAL_ONLY,
    PENDING_UPLOAD,
    AVAILABLE,
    DOWNLOADING,
    FAILED,
}

data class ActivityEntry(
    val id: String,
    val taskId: TaskId,
    val projectId: ProjectId?,
    val kind: ActivityKind,
    val noteBody: String?,
    val immutable: Boolean,
    val occurredAt: Instant,
    val revision: Revision,
    val deletedAt: Instant?,
)

data class Attachment(
    val id: AttachmentId,
    val taskId: TaskId,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val contentHash: String,
    val chunkCount: Int,
    val remoteObjectPrefix: String?,
    val localAvailable: Boolean,
    val transferState: AttachmentTransferState,
    val keepOffline: Boolean,
    val revision: Revision,
    val deletedAt: Instant?,
)
```

Add commands `AddNote`, `UpdateNote`, `DeleteNote`, `RestoreNote`,
`RegisterAttachment`, `SetAttachmentKeepOffline`, `DeleteAttachment`, and
`RestoreAttachment`.

- [ ] Write failing rule tests for trimming, 500-character boundary,
10,000-entry limit, immutable generated-entry rejection, note revision order,
100-attachment limit, 100 MiB boundary, chunk-count calculation, task
ownership, tombstones, and exact Undo.

- [ ] Run:

```bash
./gradlew :core:domain:testDebugUnitTest --tests '*ActivityRulesTest' --stacktrace
```

Expected: compilation failure for the new enums/commands.

- [ ] Implement typed `RejectionReason` values for every limit/ownership
failure. Add `activityEntries` and `attachments` to `WorkspaceSnapshot`.

- [ ] Re-run focused tests.

- [ ] Commit:

```bash
git add core/model core/domain
git commit -m "feat: finalise notes and attachment domain contracts"
```

### Task 4.2: Migrate and implement the final Room schema

**Files:**
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/Entities.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/EntityMappers.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/ActivityEventFactory.kt`
- Modify:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`
- Create:
  `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/7.json`

**Interfaces:** Implements Task 4.1 commands. `ActivityEventFactory` maps a
successful semantic command to zero or one generated event; note/attachment
commands create their explicit event in the same transaction.

- [ ] Add shared repository contract tests against in-memory and Room for all
note/attachment commands, Undo, restart, outbox payloads, and generated
activity. Assert autosave text changes do not create an activity row.

- [ ] Add device migration fixtures from every schema 1–6. Assert existing
provisional attachment/activity rows survive; unknown old kind maps to
`LEGACY`; deterministic migration revisions sort before new changes.

- [ ] Run Data tests and observe migration/command failures.

- [ ] Implement v6→v7 with added columns/defaults and indexes only on encrypted
Room fields. Extend `WorkspaceDao`/snapshot assembly. Extract touched activity
and attachment command handlers from `RoomVaultRepository.kt` into focused
private collaborators without altering unrelated commands.

- [ ] Export and review schema 7; verify no destructive migration and no
plaintext FTS/token table.

- [ ] Re-run:

```bash
./gradlew :core:data:testDebugUnitTest \
  :core:data:connectedDebugAndroidTest --stacktrace
```

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/data
git commit -m "feat: persist final activity and attachment schema"
```

### Task 4.3: Stream, encrypt, verify, and publish local attachments

**Files:**
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/attachments/AttachmentFileStore.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/attachments/EncryptedAttachmentFileStore.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/attachments/AttachmentIntake.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/attachments/EncryptedAttachmentFileStoreTest.kt`
- Create:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/AttachmentFileStoreInstrumentedTest.kt`

**Interfaces:**

```kotlin
data class AttachmentIntakeMetadata(
    val taskId: TaskId,
    val boundedDisplayName: String,
    val boundedMimeType: String,
)

data class StagedAttachment(
    val id: AttachmentId,
    val byteCount: Long,
    val contentHash: String,
    val chunkCount: Int,
    val stagedPath: Path,
)

interface AttachmentFileStore {
    suspend fun stage(
        metadata: AttachmentIntakeMetadata,
        source: InputStream,
    ): StagedAttachment
    suspend fun verify(staged: StagedAttachment)
    suspend fun publish(staged: StagedAttachment): PublishedAttachment
    suspend fun decrypt(id: AttachmentId, destination: OutputStream)
    suspend fun deleteLocal(id: AttachmentId)
    suspend fun cleanAbandoned(now: Instant): CleanupResult
}
```

- [ ] Write failing tests for 0 bytes, exactly/over 100 MiB, provider size lie,
short reads, traversal names, huge names/MIME, 4 MiB boundaries, random
ciphertext, swapped chunks, missing/reordered/duplicated chunks, tamper, wrong
key, disk full, cancellation, publish rename failure, and abandoned staging.

- [ ] Run focused tests and confirm missing implementation.

- [ ] Implement a fixed 4 MiB buffer. Each chunk AEAD context binds vault ID,
attachment ID, format version, chunk index, and count. Hash plaintext while
streaming, write framed ciphertext under a generated ID, read back every frame,
then atomically rename within the fixed attachment root.

- [ ] Do not use display names in paths. Bound names to 255 code points and
MIME to 127 ASCII characters for display metadata; use safe fallback values.

- [ ] Re-run JVM/device tests and inspect that failure leaves no published
file.

- [ ] Commit:

```bash
git add core/data/src/main/kotlin/app/opentasks/core/data/attachments \
  core/data/src/test/kotlin/app/opentasks/core/data/attachments \
  core/data/src/androidTest/kotlin/app/opentasks/core/data/AttachmentFileStoreInstrumentedTest.kt
git commit -m "feat: add verified encrypted attachment storage"
```

### Task 4.4: Coordinate URI intake, FileProvider sharing, and cleanup

**Files:**
- Create:
  `app/src/main/kotlin/app/opentasks/attachments/AttachmentCoordinator.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/attachments/AttachmentLaunchers.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/attachments/AttachmentShareStore.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/attachments/AttachmentCleanupWorker.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/attachments/AttachmentCoordinatorTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/file_paths.xml`

**Interfaces:**

```kotlin
interface AttachmentCoordinator {
    suspend fun import(taskId: TaskId, uri: Uri): AttachmentActionResult
    suspend fun open(attachmentId: AttachmentId): AttachmentActionResult
    suspend fun share(attachmentId: AttachmentId): AttachmentActionResult
    suspend fun removeLocalCopy(attachmentId: AttachmentId): AttachmentActionResult
}
```

- [ ] Add failing fake-resolver tests for missing/lying metadata, unavailable
stream, permission loss, cancellation, successful publish plus
`RegisterAttachment`, repository rejection cleanup, decrypted-share expiry,
and URI grant flags.

- [ ] Implement Photo Picker for images/video, `ACTION_OPEN_DOCUMENT` for
documents, receive shares, and Compose drag/drop through app-owned launchers.
Take persistable permission only when offered and only until intake completes.

- [ ] Restrict FileProvider to `cache/shared/` in `file_paths.xml`. Decrypt to
a generated short-lived filename, grant `FLAG_GRANT_READ_URI_PERMISSION` only,
and delete on expiry/startup. Do not export any provider path beyond this root.

- [ ] Run:

```bash
./gradlew :app:testDebugUnitTest :app:processDebugMainManifest --stacktrace
```

Expected: tests pass; merged manifest contains no broad storage permission.

- [ ] Commit:

```bash
git add app/src/main/kotlin/app/opentasks/attachments \
  app/src/test/kotlin/app/opentasks/attachments \
  app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml
git commit -m "feat: add safe attachment intake and sharing"
```

### Task 4.5: Add resumable cloud chunks and bounded offline cache

**Files:**
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/attachments/AttachmentTransferCoordinator.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/attachments/AttachmentCachePolicy.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/attachments/AttachmentTransferCoordinatorTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/attachments/AttachmentCachePolicyTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/sync/RoomSyncCoordinator.kt`
- Modify: `core/sync/src/main/kotlin/app/opentasks/core/sync/CloudPayloads.kt`

**Interfaces:**

```kotlin
enum class AttachmentCacheLimit(val bytes: Long) {
    MIB_256(256L * 1024 * 1024),
    GIB_1(1024L * 1024 * 1024),
    GIB_4(4L * 1024 * 1024 * 1024),
}

interface AttachmentTransferCoordinator {
    suspend fun upload(id: AttachmentId): AttachmentTransferResult
    suspend fun download(id: AttachmentId): AttachmentTransferResult
    suspend fun retry(id: AttachmentId): AttachmentTransferResult
}
```

- [ ] Add failing tests for chunk-boundary resume, all chunks before metadata
visibility, missing chunk, corrupt chunk, quota, auth expiry, network loss,
duplicate upload, deletion during transfer, local-only use, and verified
download before state change.

- [ ] Add cache-policy tests for 256 MiB/1 GiB/4 GiB, true LRU ordering,
`keepOffline`, open/uploading/downloading/sharing/unverified exclusions, clear
downloaded files, and metadata preservation.

- [ ] Implement remote names using vault prefix, attachment ID, and numeric
chunk index only. Upload the metadata operation after all chunks verify.
Download into staging and publish only after the complete inventory verifies.

- [ ] Store cache preference as non-sensitive app configuration; default to
1 GiB. Eviction removes encrypted local content and updates availability but
does not tombstone metadata or delete Drive content.

- [ ] Run focused sync/attachment tests.

- [ ] Commit:

```bash
git add core/data/src/main/kotlin/app/opentasks/core/data/attachments \
  core/data/src/test/kotlin/app/opentasks/core/data/attachments \
  core/data/src/main/kotlin/app/opentasks/core/data/sync \
  core/sync/src/main/kotlin/app/opentasks/core/sync/CloudPayloads.kt
git commit -m "feat: sync and cache encrypted attachments"
```

### Task 4.6: Add Activity & files UI

**Files:**
- Create:
  `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TaskActivitySection.kt`
- Create:
  `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TaskAttachmentsSection.kt`
- Create:
  `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskActivityFilesInstrumentedTest.kt`
- Modify:
  `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
- Create:
  `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectActivitySection.kt`
- Modify:
  `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:** Feature sections receive immutable `ActivityFilesUiState` and
callbacks for note and attachment actions. They import no URI, repository,
Hilt, Drive, or crypto class.

- [ ] Add Compose tests for add/edit/delete/Undo note, immutable generated
event, add/open/share/delete/Undo attachment, keep offline, retry/download,
transfer states, limit errors, keyboard, TalkBack, and 200% text.

- [ ] Run Tasks/Projects device tests and confirm compile failures.

- [ ] Extract only Activity & files and existing time history from the large
`TasksScreen.kt` into focused files. Notes appear before chronological
generated activity. Attachment rows expose type, size, local/remote state, and
named actions.

- [ ] Project activity aggregates its tasks plus project events by snapshot
projection; do not create duplicate activity records.

- [ ] Wire platform actions in `app` via `AttachmentCoordinator`.

- [ ] Re-run Tasks, Projects, and App device suites.

- [ ] Commit:

```bash
git add feature/tasks feature/projects app/src/main
git commit -m "feat: add task activity and attachment surfaces"
```

### Task 4.7: Extend safe workspace search

**Files:**
- Modify:
  `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt`
- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/WorkspaceSearchEngine.kt`
- Create:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/WorkspaceSearchEngineTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/SearchSurface.kt`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Modify: `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt`

**Interfaces:**

```kotlin
enum class SearchMatchKind {
    TASK_TITLE,
    TASK_DESCRIPTION,
    CHECKLIST,
    TAG,
    PROJECT,
    NOTE,
    ATTACHMENT_NAME,
}

interface WorkspaceSearchEngine {
    fun search(
        snapshot: WorkspaceSnapshot,
        query: SearchQuery,
    ): List<SearchResult>
}
```

Extend the existing sealed `SearchResult` with `NoteResult` and
`AttachmentResult`, and add a `SearchMatchKind` property to all variants.

- [ ] Add failing tests for note bodies, attachment names, existing fields,
case/whitespace normalisation with `Locale.ROOT`, safe bounded snippets,
project/tag/completed/Bin filters, deleted notes/attachments, duplicate result
coalescing, result ordering, blank/oversized queries, and absence of decrypted
file-content search.

- [ ] Implement a single snapshot scan. Bound the normalised query at 200 code
points, result count at 200, and snippet at 160 code points. Never retain a
plaintext index or query history.

- [ ] Update Search UI labels/context and process restoration. Saved state may
retain the bounded query but no result content.

- [ ] Run:

```bash
./gradlew :core:domain:testDebugUnitTest :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest --stacktrace
```

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/domain app
git commit -m "feat: search notes and attachment names"
```

### Task 4.8: Qualify attachment security and final schema

**Files:**
- Create:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/AttachmentMatrixInstrumentedTest.kt`
- Modify: `docs/architecture.md`
- Modify: `docs/threat-model.md`
- Modify: `DESIGN.md`
- Modify: `PRODUCT.md`
- Modify: `README.md`
- Modify: `HANDOFF.md`

**Interfaces:** No new production API.

- [ ] Add matrix fixtures for malicious provider size/name/MIME, path
traversal, 100 MiB boundaries, disk full, quota, auth expiry, corrupted and
missing chunks, interrupted resume, cache eviction, share cleanup, task purge,
remote delete/Undo, and two-device concurrent notes/attachments.

- [ ] Run all core, app, Tasks, Projects, and More tests; perform an in-place
v6→v7 migration and cold restart without clearing data.

- [ ] Inspect merged manifest/FileProvider paths and scan production source for
broad storage permissions, plaintext filenames in paths/logs, and unbounded
`readBytes()`.

- [ ] Run train exit gates. Update privacy flows, cache/deletion semantics,
schema 7, limits, and threat gates. Record exact test counts/evidence.

- [ ] Commit:

```bash
git add core/data/src/androidTest docs DESIGN.md PRODUCT.md README.md HANDOFF.md
git commit -m "test: qualify notes and encrypted attachments"
```
