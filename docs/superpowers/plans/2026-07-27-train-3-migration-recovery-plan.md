# Train 3 — Migration and Recovery Implementation Plan

> **Superseded — 28 July 2026:** Do not execute this train. The approved
> local-authority, backup, recovery-takeover, and cloud-attachment direction is
> defined in the 28 July design and the live production master plan.

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Safely migrate authority between local and Drive-primary modes,
retain a seven-day rollback, recover after reinstall/new device/Keystore loss,
and make cloud deletion an explicit separate operation.

**Architecture:** A `VaultAdministration` boundary coordinates repository
quiescence, encrypted file sets, the Train 2 sync coordinator, content-key
recovery envelopes, and atomic activation. Every destructive-looking workflow
builds and verifies a staged replacement first. Storage authority changes only
after a cloud upload/download/decrypt comparison or a complete local
materialisation passes.

**Tech Stack:** Kotlin coroutines, Room/SQLCipher, encrypted app-private files,
Tink/Argon2 recovery envelopes, Android Keystore, Drive appDataFolder,
WorkManager, JUnit 4, instrumented migration/recovery fixtures, Compose UI test.

**Backlog:** P1-D05, P1-D06, P0-R03, and P0-R09.

## Global Constraints

- Follow the master plan constraints.
- Never switch authority before a complete verified round-trip.
- Never delete the active database or its attachments before an atomic staged
  replacement is open and validated.
- Disconnect never deletes cloud data. Cloud deletion is a separate,
  passphrase-confirmed action.
- Keep one rollback copy for seven days; replace it only after a later
  successful authority transition.
- Passphrases are `CharArray`, at least 12 characters, never logged/saved, and
  cleared after use.

---

### Task 3.1: Add recovery passphrase policy and local generation

**Files:**
- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/RecoveryPassphrasePolicy.kt`
- Create:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/RecoveryPassphrasePolicyTest.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/recovery/LocalPassphraseGenerator.kt`
- Create:
  `app/src/test/kotlin/app/opentasks/recovery/LocalPassphraseGeneratorTest.kt`
- Create: `app/src/main/res/raw/recovery_words_en_gb.txt`

**Interfaces:**

```kotlin
enum class PassphraseStrength { TOO_SHORT, WEAK, REASONABLE, STRONG }

data class PassphraseAssessment(
    val accepted: Boolean,
    val strength: PassphraseStrength,
    val messageKey: String,
)

object RecoveryPassphrasePolicy {
    const val MINIMUM_CHARACTERS = 12
    fun assess(passphrase: CharArray): PassphraseAssessment
}

interface PassphraseGenerator {
    fun generate(): CharArray
}
```

- [ ] Write failing tests for 0/11/12 characters, whitespace-only input,
Unicode, repeated values, long input bounds, confirmation mismatch in the app
validator, and generated six-word phrases with at least 80 bits of selection
entropy.

- [ ] Run:

```bash
./gradlew :core:domain:testDebugUnitTest :app:testDebugUnitTest \
  --tests '*Passphrase*Test' --stacktrace
```

Expected: compilation failure for the new policy/generator.

- [ ] Implement no composition rule. Strength guidance may consider length and
distinct code points but acceptance is length plus non-blank. Generate six
words using `SecureRandom` from a bundled, reviewed UK-English list of at least
2,048 unique words. Return `CharArray`; do not retain generator output.

- [ ] Re-run the tests.

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/domain app/src/main/kotlin/app/opentasks/recovery \
  app/src/test/kotlin/app/opentasks/recovery app/src/main/res/raw
git commit -m "feat: add recovery passphrase policy"
```

### Task 3.2: Define atomic vault administration and staged file sets

**Files:**
- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultAdministration.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/admin/VaultFileSet.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/admin/VaultActivationJournal.kt`
- Create:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/VaultFileSetInstrumentedTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/LocalVaultRepositoryFactory.kt`

**Interfaces:**

```kotlin
enum class AdministrationPhase {
    IDLE,
    PREPARING,
    UPLOADING,
    VERIFYING,
    ACTIVATING,
    ROLLING_BACK,
    RECOVERING,
    DISCONNECTING,
    DELETING_CLOUD,
}

data class VaultAdministrationState(
    val phase: AdministrationPhase,
    val progress: Int?,
    val recoverableFailure: AdministrationFailure?,
)

interface VaultAdministration {
    val state: StateFlow<VaultAdministrationState>
    suspend fun migrateToDrive(passphrase: CharArray): AdministrationResult
    suspend fun rollbackToLocal(): AdministrationResult
    suspend fun disconnectToLocal(): AdministrationResult
    suspend fun recoverFromDrive(passphrase: CharArray): AdministrationResult
    suspend fun deleteCloudCopy(passphrase: CharArray): AdministrationResult
}
```

`VaultFileSet` names fixed private directories: `active`, `staging`, and
`rollback/<transition-id>`. A journal contains no user data and records
`PREPARED`, `VERIFIED`, or `ACTIVATED`.

- [ ] Add failing device tests for prepare/copy/fsync/open/activate, process
death at each journal phase, insufficient disk, corrupt staged DB, absent WAL,
and cleanup that never crosses the fixed vault root.

- [ ] Run:

```bash
./gradlew :core:data:connectedDebugAndroidTest --stacktrace
```

Expected: compilation failure for `VaultFileSet`.

- [ ] Implement canonical path checks, copy through a unique staging
directory, fsync files/directories, validate SQLCipher open plus snapshot
invariants, rename atomically on the same filesystem, and retain the previous
active set as rollback.

- [ ] On startup, `LocalVaultRepositoryFactory` reads the journal and either
finishes a verified activation or discards an unverified stage. It never
guesses from file timestamps.

- [ ] Re-run the device tests.

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain/VaultAdministration.kt \
  core/data
git commit -m "feat: add atomic vault activation boundary"
```

### Task 3.3: Implement local-to-Drive migration and seven-day rollback

**Files:**
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/admin/RoomVaultAdministration.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/admin/RoomVaultAdministrationTest.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/admin/RollbackRetentionWorker.kt`
- Modify:
  `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`

**Interfaces:** `Vault` persists `storageMode`, `authorityChangedAt`, and a
non-secret `cloudVaultId`. Migration owns a `MigrationCheckpoint` containing
local snapshot digest, manifest revision, and transition ID.

- [ ] Write failing tests for:
  happy migration; passphrase confirmation; empty and large outbox; offline;
  auth cancellation/expiry; quota before/during upload; manifest race; checksum
  mismatch; decrypt mismatch; process death during each phase; edit attempt
  during the short activation lock; and rollback on days 0, 6, 7, and 8.

- [ ] Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*RoomVaultAdministrationTest' --stacktrace
```

Expected: compilation failure for the administrator.

- [ ] Implement this exact order:

```text
quiesce writes → checkpoint Room/WAL/files → wrap content key for recovery
→ create immutable snapshot/manifest → upload all objects
→ download through CloudObjectStore → checksum → decrypt
→ compare canonical workspace digest → activate DRIVE_PRIMARY
→ retain pre-transition file set for seven days → resume writes/sync
```

Never use object IDs or upload success alone as verification.

- [ ] Schedule rollback retention cleanup with WorkManager. Cleanup checks
`authorityChangedAt + 7 days`, active mode, and journal state before deleting
only the named rollback directory.

- [ ] Re-run unit and device tests, including an in-place Room restart.

- [ ] Commit:

```bash
git add core/model core/data app/src/main/kotlin/app/opentasks/di
git commit -m "feat: migrate vault authority with verified rollback"
```

### Task 3.4: Implement reinstall, new-device, and Keystore-loss recovery

**Files:**
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/admin/DriveRecoveryPlanner.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/admin/DriveRecoveryPlannerTest.kt`
- Create:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/DriveRecoveryInstrumentedTest.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/admin/RoomVaultAdministration.kt`

**Interfaces:**

```kotlin
data class RecoveryPreview(
    val vaultCreatedAt: Instant,
    val snapshotCreatedAt: Instant,
    val taskCount: Int,
    val projectCount: Int,
    val attachmentCount: Int,
)

sealed interface RecoveryPlanResult {
    data class Ready(val preview: RecoveryPreview) : RecoveryPlanResult
    data class Rejected(val failure: AdministrationFailure) : RecoveryPlanResult
}
```

- [ ] Add failing tests for correct/wrong passphrase, missing envelope,
invalidated Keystore alias, clean install, new device, deleted app-data folder,
corrupt current snapshot with previous fallback, missing segment, incompatible
reader, replay, disk full, cancellation, and active-vault preservation on every
failure.

- [ ] Run focused tests and confirm failure.

- [ ] Implement:

```text
authorise → list bounded manifests → select opaque vault
→ unlock recovery envelope → download current/previous snapshot
→ replay later verified segments → validate full WorkspaceSnapshot
→ build staged SQLCipher vault → locally wrap recovered content key
→ present RecoveryPreview → activate only after confirmation
```

No record is written to the active vault until the staged vault validates.

- [ ] Add an instrumented fixture that deletes only the local content-key
alias, proves normal open fails closed, then recovers with the envelope. Add a
second fixture with no envelope that remains blocked with explanatory state.

- [ ] Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  :core:data:connectedDebugAndroidTest --stacktrace
```

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/data
git commit -m "feat: recover Drive vaults after key loss"
```

### Task 3.5: Implement Drive-to-local disconnect and guarded cloud deletion

**Files:**
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/admin/RoomVaultAdministration.kt`
- Modify:
  `core/data/src/test/kotlin/app/opentasks/core/data/admin/RoomVaultAdministrationTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/admin/CloudDeletionTest.kt`

**Interfaces:** Uses `VaultAdministration.disconnectToLocal` and
`deleteCloudCopy`; no new public method.

- [ ] Add failing tests proving disconnect:
  flushes/merges remote state; verifies all attachment inventory seams; stages
  a complete local file set; changes to `LOCAL`; retains cloud objects; and
  keeps local authority when the network fails.

- [ ] Add failing deletion tests for wrong passphrase, list pagination,
concurrent manifest change, partial deletion, retry, cancellation, and
completion. Assert no cloud delete is called during disconnect.

- [ ] Implement disconnect order:

```text
sync to fixed manifest revision → download/verify complete inventory
→ build/open staged local authority → atomic LOCAL activation
→ preserve cloud objects and recovery envelope
```

- [ ] Implement cloud deletion only from a complete `LOCAL` vault. Unlock the
manifest recovery envelope with the supplied passphrase and compare the
content-key fingerprint before listing/deleting the exact vault prefix through
all pages. If partial deletion occurs, retain a resumable deletion journal and
show the user that the local vault remains safe.

- [ ] Re-run focused tests.

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/data
git commit -m "feat: disconnect locally and guard cloud deletion"
```

### Task 3.6: Build migration and recovery UI

**Files:**
- Create:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/RecoveryScreen.kt`
- Create:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/StorageSettingsScreen.kt`
- Create:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/RecoveryInstrumentedTest.kt`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/PrivacyRecoveryScreen.kt`
- Create:
  `app/src/main/kotlin/app/opentasks/recovery/VaultAdministrationCoordinator.kt`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:** Feature screens consume `VaultAdministrationUiState` and
callbacks only. Passphrase text stays inside a non-saveable app-owned dialog
state and is cleared on dismiss/result.

- [ ] Add Compose tests for migration education, generated/manual passphrase,
confirmation mismatch, progress, retryable failure, success, rollback
countdown, disconnect wording, separate delete wording, Keystore-loss recovery,
wrong passphrase, and 200% text/TalkBack semantics.

- [ ] Run:

```bash
./gradlew :feature:more:connectedDebugAndroidTest --stacktrace
```

Expected: compile failure for the new screens.

- [ ] Split Privacy/Recovery and Storage Settings from `MoreScreen.kt`. Require
explicit confirmation before migration/activation/deletion; never put a
passphrase in `SavedStateHandle` or Navigation arguments.

- [ ] Disable only authority-changing controls during activation; normal local
editing remains available during upload/download. Explain that Drive app data
is hidden but may be deleted by the user.

- [ ] Re-run More and App process-restoration suites.

- [ ] Commit:

```bash
git add feature/more app/src/main
git commit -m "feat: add explicit migration and recovery UI"
```

### Task 3.7: Complete the core recovery matrix and threat gates

**Files:**
- Create:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/MultiDeviceRecoveryMatrixInstrumentedTest.kt`
- Modify: `docs/architecture.md`
- Modify: `docs/threat-model.md`
- Modify: `DESIGN.md`
- Modify: `PRODUCT.md`
- Modify: `README.md`
- Modify: `HANDOFF.md`

**Interfaces:** No production API change; this is qualification and
documentation.

- [ ] Build table-driven fake tests for two/three devices, concurrent record
edits, HLC rollback, duplicates/reordering, auth expiry, quota, pagination,
corrupt snapshot/segment, previous-snapshot recovery, reinstall, new device,
Keystore loss, interrupted migration/rollback/disconnect/recovery, and
user-deleted app data.

- [ ] Run the matrix twice with alternate delivery order and assert canonical
snapshot equality plus zero missing record IDs.

- [ ] Run the Train 3 exit gates, all Data/App/More device tests, and an
opt-in credentialed migration/recovery smoke test using a test-only vault
prefix.

- [ ] Update authority diagrams, key-loss residual risk, cloud-delete
semantics, recovery wording, and T02–T06/T11–T13 evidence. Record exact
commands and device details in `HANDOFF.md`.

- [ ] Commit:

```bash
git add core/data/src/androidTest docs DESIGN.md PRODUCT.md README.md HANDOFF.md
git commit -m "test: qualify vault migration and recovery"
```
