# Open Tasks Production Programme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the local-authority, backup, recovery, cloud-attachment,
platform, qualification, and rollout programme without risking the accepted
encrypted workspace.

**Authority:** The approved decision record is
[Local Authority, Cloud Attachments, and Backup Direction Design](../specs/2026-07-28-local-authority-cloud-attachments-backup-design.md).
Encrypted Room is the sole live structured-data authority. Backup, Android
Auto Backup, Drive transport, recovery UI, and attachments are approved future
work and are not operational at this checkpoint.

**Tech Stack:** Kotlin 2.3.21, Android Gradle Plugin 9.3.1, Java 17 on JDK 21,
Compose BOM 2026.06.00, Navigation 3, Material 3 Adaptive, Hilt, coroutines,
Room 2.8.4, SQLCipher 4.15.0, WorkManager 2.11.2, Tink 1.23.0, Bouncy Castle
1.84, Google Drive REST v3, Glance, AndroidX Biometric, Baseline Profiles,
Macrobenchmark, JUnit 4, Compose UI test v2, and GitHub Actions.

## Current checkpoint

- Train 0 and Train 1 Tasks 1.1–1.5 remain completed, reviewed evidence.
- Stage 1 is complete and verified. Its implemented internal foundation
  includes the authenticated provider-independent object codec in `core:data`,
  with canonical framing in `core:sync` and generic AEAD in `core:crypto`.
- No Stage 2 source change has started. Provider transport, backup/blob
  services, recovery, scheduling, and product-visible backup or attachment
  features remain unimplemented.
- Existing Room, outbox, and other local data remain untouched.
- Android Auto Backup remains disabled until Stage 2.
- GitHub dependency maintenance remains paused.

## Global constraints

- Work directly on `main`; do not create a branch, worktree, or pull request.
- Preserve the protected user workspace. Never uninstall, clear data, run
  destructive migration, or let app instrumentation target it.
- Device suites run only on a sole ADB-connected disposable emulator.
- Preserve `minSdk 36`, `compileSdk 37`, `targetSdk 37`, Java 17, JDK 21, and
  AGP built-in Kotlin.
- Keep Home, Tasks, Projects, Schedule, and More as the top-level destinations.
- Feature composables stay stateless and depend only on `core:model` and
  `core:designsystem`; Hilt and platform launchers stay in `app`.
- Route every durable product mutation through a typed `DomainCommand` and
  `VaultRepository.execute`.
- Commit each structured mutation and its backup-journal entry atomically.
  Until Stage 2 migrates that representation, preserve existing outbox rows and
  behaviour.
- Keep `InMemoryVaultRepository` aligned with encrypted Room through the shared
  contract suite.
- Only `RecoveryCoordinator` may reconstruct Room from a backup or portable
  package. Normal cloud flows cannot mutate structured records.
- Keep the SQLCipher database key and vault-content key independent. Use
  `CharArray` for passphrases, zero temporary key arrays, and never save keys,
  passphrases, attachment bytes, or vault payloads in instance state.
- Do not log task content, filenames, account data, Drive IDs, ciphertext,
  recovery metadata, or keys.
- Every Room version bump includes a non-destructive migration, exported
  schema, prior-version fixture, restart verification, and in-place protected
  workspace run.
- Every cloud/crypto format change includes bounded decoder tests, golden
  vectors, tamper and wrong-key tests, old-reader tests, and a documented
  minimum-reader rule.
- Use UK English, day–month dates, 24-hour time, logical start/end layout,
  48 dp actions, keyboard access, and non-colour status cues.
- Update the live product, design, architecture, threat-model, handoff, and
  programme contracts whenever their responsibilities change.
- Run release assembly separately from lint because the repository documents
  an AGP/KSP release-lint race.

## Programme map

| Order | Stage | Exit decision |
|---:|---|---|
| 1 | Direction reset and authenticated object foundation | Active contracts match local authority; the authenticated provider-independent object codec is frozen |
| 2 | Local backup and Android Auto Backup | Local generations produce verified primary snapshots and one strictly whitelisted portable package |
| 3 | App-managed backup and recovery takeover | Drive backup, retention, recovery, writer epochs, and stale-writer rejection are proven |
| 4 | Notes, activity, cloud attachments, and search | Cloud-authoritative blob lifecycle and final structured metadata are complete |
| 5 | Remaining platform features | Import/export, widget, app lock, input, and calendar features use the final local schema |
| 6 | Production qualification and rollout | Backup, attachment, takeover, recovery, accessibility, performance, privacy, and release gates pass |

The programme dependency chain is:

```text
Stage 1 → Stage 2 → Stage 3 → Stage 4 → Stage 5 → Stage 6
```

Do not begin a later stage until the preceding stage's automated gates pass and
its manual or external blockers are satisfied or explicitly recorded as owner
actions.

## Stage contracts

### Stage 1 — Direction reset and authenticated object foundation

- Ratified the approved direction in every active contract and marked
  historical train files immediately.
- Fixed the known Insights lint gate without changing accepted Insights
  behaviour.
- Completed the provider-independent `AuthenticatedCloudObjectCodec` in
  `core:data`.
- Bound complete object identity as associated data, verified checksums before
  decryption, returned typed failures, and froze independent golden vectors.

### Stage 2 — Local backup and Android Auto Backup

- Introduce `BackupJournal` semantics without losing existing outbox data.
- Add `BackupCoordinator` for consistent local snapshots, journal segments,
  verification, generation checkpoints, and retention inputs.
- Add `PortableBackupPublisher` for one atomically replaced package no larger
  than 24 MiB.
- Enable Android Auto Backup only after extraction/include rules prove that
  Room, WAL, SHM, preferences, keys, credentials, cache, and attachment bytes
  remain excluded.

### Stage 3 — App-managed backup and recovery takeover

- Authorise only Google Drive `drive.appdata`.
- Implement the backup object namespace, upload/download verification,
  retention, scheduling, and actionable status.
- Add writer epochs and conditional ownership so one backed-up vault has one
  active writer.
- Add `RecoveryCoordinator` staging for reinstall, new-device, and Keystore
  loss, with explicit takeover and stale-writer rejection before activation.

### Stage 4 — Notes, activity, cloud attachments, and search

- Freeze the final local metadata schema and note/activity commands.
- Add `AttachmentBlobCoordinator` and a separate attachment object namespace.
- Prove bounded 4 MiB chunks, 100 MiB intake, provisional sessions, temporary
  cache, open/share cleanup, delete/Undo, retention, and garbage collection.
- Search notes and attachment display names without making blob bytes local
  authority.

### Stage 5 — Remaining platform features

- Replan and implement encrypted import/export, deliberate plaintext CSV
  warnings, Today widget, Quick Add refinement, app lock/title privacy, input
  refinements, and one-way calendar insertion.
- Use the Stage 4 local schema and backup terminology throughout.

### Stage 6 — Production qualification and rollout

- Qualify backup corruption and fallback, writer takeover, stale-writer
  rejection, Android Auto Backup inclusion, attachment lifecycle, and recovery.
- Complete accessibility, responsive, screenshot, performance, privacy,
  signing, Play testing, and rollout gates.
- Preserve still-valid non-cloud qualification work from the historical Train
  6 contracts while replacing its obsolete cloud matrices.

## Shared ownership boundaries

- `core:model` owns immutable records, identifiers, local generations, backup
  and attachment states, Insights results, and archive summaries.
- `core:domain` owns `DomainCommand`, `VaultRepository`, `BackupJournal`
  contracts, pure rules, object-store abstractions, administration interfaces,
  and repository contract tests.
- `core:sync` owns bounded provider-independent object formats,
  canonical cloud-header identity, legacy internal clocks/merge primitives,
  payload codecs, bounds, retry policy, and format-failure classification. It
  is not a product synchronisation module.
- `core:crypto` owns vault-content key creation, Android Keystore local
  wrapping, passphrase recovery envelopes, and generic AEAD.
- `core:data` owns Room and SQLCipher and composes `core:sync` framing with
  `core:crypto` AEAD in the implemented `AuthenticatedCloudObjectCodec`.
  Later stages add backup-journal persistence, WorkManager, provider
  transports, portable-package files, attachment cache, and recovery staging.
- `app` owns Hilt, ViewModels, `BackupCoordinator`,
  `PortableBackupPublisher`, `AttachmentBlobCoordinator`,
  `RecoveryCoordinator`, activity-result APIs, permissions, authorisation,
  intents, navigation binding, and process-lifecycle locking.

## Shared verification gates

After every implementation task, run its focused tests. At every stage exit,
run:

```bash
git diff --check
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
./gradlew :app:assembleRelease --stacktrace
```

Expected: every command exits `0`; `git diff --check` prints nothing. Run
affected device suites without clearing the protected workspace. Any Room,
backup, or recovery stage includes an in-place upgrade and cold restart.

Before claiming a stage complete:

- [ ] Invoke `superpowers:verification-before-completion`.
- [ ] Review `git status --short` and stage only stage-owned files.
- [ ] Scan changed production source for `TODO`, `FIXME`, `println`, `Log.`,
  raw secrets, and placeholder implementations.
- [ ] Check interface/type names against this master plan and later-stage
  contracts.
- [ ] Record exact commands, device/API, test counts, and manual evidence in
  `HANDOFF.md`.
- [ ] Commit the checkpoint with the exact message named by its stage plan.

## Release strategy

The first Play production publication cannot use a percentage staged rollout.
Contain first-release risk through internal testing, the required closed test,
production-access approval where applicable, and a global open test. Then
publish the approved binary globally using managed publication.

For later production updates:

```text
5% for ≥48 h → 20% for ≥48 h → 50% for ≥48 h → 100%
```

At every stage review Play crash/ANR vitals, pre-launch warnings, reviews,
support reports, and reported backup, attachment, takeover, and recovery
failures. Halt for any new critical/high data-loss, security, recovery, crash,
ANR, or blocking accessibility issue.
