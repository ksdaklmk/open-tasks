# Stage 2 Local Backup and Android Auto Backup Design

**Date:** 28 July 2026
**Status:** Approved in conversation; written-spec review pending
**Scope:** Local backup generations, backup-journal migration, verified local
backup objects, one portable encrypted package, Android Auto Backup allow-list,
and minimal package status

## Decision

Stage 2 will add the local backup foundation without adding a cloud provider or
a normal cloud-to-Room path.

Every accepted mutation-bearing `DomainCommand` will continue to mutate
encrypted Room through `VaultRepository`. The same Room transaction will
allocate one local backup generation and append one or more ordered
`BackupJournal` entries. A local `BackupCoordinator` will produce deterministic
complete snapshots and operation segments, encrypt them through the
implemented `AuthenticatedCloudObjectCodec`, decode-verify the resulting
objects, and only then advance local checkpoints.

Android Auto Backup and device-to-device transfer will receive the same single
self-encrypted portable package. Both transfer modes will use an exact-file
allow-list. Room, WAL/SHM, preferences, Android Keystore material, credentials,
device-local state, cache, local backup staging, and attachment bytes will
remain excluded.

The portable package requires a recovery-passphrase envelope for the existing
vault-content key. Stage 2 will therefore add one narrowly scoped **Prepare
Android backup** action. It will not add Google authorisation, Drive transport,
app-managed cloud backup, restore activation, writer takeover, attachment
transport, or a user-visible claim that Android uploaded anything.

## Relationship to the approved direction

This design is the focused Stage 2 child of
[Local Authority, Cloud Attachments, and Backup Direction Design](2026-07-28-local-authority-cloud-attachments-backup-design.md).
That parent decision remains authoritative:

- encrypted Room is the sole live structured-data authority;
- structured records are not synchronised or merged across devices;
- app-managed encrypted backup remains the dependable future recovery path;
- Android Auto Backup is supplementary;
- attachment bytes remain outside the portable package;
- backup and attachment object stores remain separate future services; and
- only a future `RecoveryCoordinator` may reconstruct Room.

Stage 1 already implemented the provider-independent authenticated object
codec, strict bounded frames, typed failures, and independent golden vectors.
Stage 2 composes those foundations but does not change their wire format.

## Goals

- Preserve every existing outbox row and the protected v5 Room workspace.
- Replace new product-facing sync/outbox semantics with explicit local backup
  generations and journal entries.
- Commit each accepted mutation and its journal representation atomically.
- Produce deterministic complete snapshots and bounded operation segments from
  consistent Room state.
- Verify every local backup object by decoding and validating it before
  checkpoint advancement.
- Wrap the existing vault-content key for recovery without replacing it.
- Publish one atomically replaced portable package no larger than 24 MiB.
- Enable Android cloud backup and device transfer with an exact-file
  allow-list and deny-by-default behaviour for every other application file.
- Show only locally provable package status and a focused setup/retry action.
- Preserve a package restored by Android as inert recovery input.
- Keep local editing available through every backup or package failure.

## Non-goals

- Google Identity, Drive REST, `drive.appdata`, or any other provider.
- `BackupObjectStore` or `AttachmentBlobStore` transport implementations.
- WorkManager or periodic/network-constrained cloud scheduling.
- App-managed upload, download, retention, or remote verification.
- Recovery-passphrase entry for restore, SQLCipher staging, activation, writer
  epochs, takeover, or stale-writer rejection.
- Attachment intake, chunks, cache, open/share, or garbage collection.
- Normal cloud downloads, remote merge, conflict resolution, or a second live
  writer.
- Product copy claiming that Android performed an upload or reporting an
  invented system-backup timestamp.
- Cross-platform transfer rules.

## Architecture

```text
DomainCommand
    │
    ▼
VaultRepository / Room transaction
    ├── product-record mutations
    ├── one local generation allocation
    └── ordered BackupJournal entries

BackupCoordinator
    ├── captures immutable Room rows at one generation
    ├── encodes deterministic snapshot or segment payloads
    ├── encrypts through AuthenticatedCloudObjectCodec
    ├── writes local no-backup staging
    ├── decodes and validates the produced object
    └── advances the local verified checkpoint

PortableBackupPublisher
    ├── captures a complete snapshot at one generation
    ├── combines it with the verified recovery envelope
    ├── writes an AtomicFile temporary package
    ├── parses, authenticates, and validates the complete package
    └── replaces or withdraws the one Auto Backup-eligible file

Android Auto Backup / device transfer
    └── may copy only the exact final portable-package path

Future RecoveryCoordinator
    └── may consume an inert restored package through staged recovery
```

Normal operation still has no cloud-to-Room record path.

## Module ownership

### `core:model`

Own:

- `BackupGeneration`;
- read-only local backup and portable-package status models; and
- typed, user-presentable package eligibility categories.

Do not expose filesystem paths, recovery-envelope bytes, journal payloads, or
provider concepts to feature modules.

### `core:domain`

Own:

- `BackupJournal` and consistent backup-capture contracts;
- pure generation, segmentation, snapshot-threshold, retention, and package
  eligibility rules;
- domain-level local backup status; and
- contract tests shared by Room and test implementations.

The obsolete product `StorageMode`, `SyncState`, `SyncReason`, and
`SyncCoordinator` contracts are removed. The historically named `core:sync`
module remains a bounded object-format module, not a product synchronisation
service.

### `core:data`

Own:

- the Room v5→v6 migration and exported schema;
- journal, backup-state, and recovery-envelope entities and DAOs;
- transaction-level generation allocation;
- immutable consistent Room capture;
- versioned deterministic snapshot and operation-segment payload codecs;
- local backup object verification; and
- the existing `core:sync` framing plus `core:crypto` AEAD composition.

### `core:crypto`

Retain the existing:

- `VaultContentKeyStore`;
- `VaultCrypto.wrapForRecovery`;
- `VaultCrypto.unlock`;
- Argon2id parameters; and
- Tink AES-256-GCM content-key format.

Stage 2 adds no algorithm, KDF setting, nonce policy, or alternate key.

### `app`

Own:

- Hilt wiring;
- in-process `BackupCoordinator`;
- `PortableBackupPublisher`;
- Android file locations and `AtomicFile`;
- recovery-passphrase setup orchestration;
- restored-package detection and quarantine;
- navigation to system backup settings; and
- binding status/actions into the stateless More feature.

### `feature:more`

Own only stateless rendering of:

- local package readiness;
- generation, byte count, and local production time;
- bounded failure guidance;
- **Prepare Android backup**;
- retry; and
- restored-package presence.

The feature receives plain state and lambdas and remains free of Hilt, Android
backup APIs, filesystem access, and cryptography.

## Room v6 data model

Stage 2 uses an additive, non-destructive migration.

### `backup_journal`

Each row is immutable and contains:

- stable operation ID;
- vault ID;
- local generation;
- zero-based sequence within that generation;
- payload format version and mutation kind;
- object ID and object type;
- versioned payload bytes;
- revision wall time and logical counter; and
- the existing opaque source-device ID where present in historical records.

The payload is protected by SQLCipher locally. The enclosing snapshot or
operation-segment object supplies cloud/portable AEAD; journal payloads do not
claim independent at-rest encryption.

The unique key is the operation ID. A unique `(vaultId, generation, sequence)`
index prevents ambiguous ordering.

### `backup_state`

One row per vault contains:

- current committed generation;
- last locally verified complete-snapshot generation;
- current and previous verified local recovery-base identities;
- latest verified operation-segment generation;
- portable-package generation, bytes, and production time;
- package state and typed local failure category;
- recovery-envelope readiness;
- legacy-outbox coverage generation; and
- snapshot creation time used by the seven-day threshold.

Persistent status stores enums and bounded public metadata, never exception
text or private record values.

### `vault_recovery_envelope`

One row per vault contains the existing `VaultKeyEnvelope` fields:

- envelope format version;
- KDF algorithm and bounded Argon2id parameters;
- salt;
- AES-GCM nonce; and
- wrapped vault-content-key ciphertext.

The passphrase and plaintext content key are never stored. SQLCipher protects
the local row, and the same envelope is intentionally embedded in the portable
package so a future recovery installation can derive the content key.

### Legacy tables

`sync_operations` remains physically present and unchanged in v6. It becomes
read-only after migration. Stage 2 does not drop it.

The legacy `vaults.storageMode` column remains physically present in v6 and is
normalised to `LOCAL` during migration. No runtime choice reads it. Retaining
the column keeps the v6 migration additive; removing it requires a later
reviewed migration. The product `StorageMode` enum and other unused sync-state
contracts are removed in Stage 2.

## v5→v6 migration

The migration runs entirely inside Room's migration transaction:

1. Create the v6 journal, state, and recovery-envelope tables and indices.
2. Read every `sync_operations` row, including rows historically marked
   uploaded.
3. Order rows by revision wall time, logical counter, device ID, then
   operation ID.
4. Copy every row into `backup_journal`, preserving its operation ID, object
   identity, payload bytes, revisions, and source-device ID exactly. Mark its
   payload format as the immutable legacy-outbox format.
5. Assign one deterministic generation per legacy row because the old schema
   contains no transaction identifier. Set its sequence to zero.
6. Initialise `backup_state.currentGeneration` to the largest assigned
   generation, or zero when the legacy table is empty.
7. Leave `legacyOutboxCoveredAtGeneration` unset.
8. Normalise every persisted authority value to local.
9. Advance the vault schema version to 6.

No legacy row is deleted or mutated.

After first startup, `BackupCoordinator` captures and verifies a complete
baseline at the current generation. Only that successful verification sets
`legacyOutboxCoveredAtGeneration`. The old table still remains available for
forensic and migration comparison.

Copied legacy entries never become recovery deltas without that baseline. The
first coordinator action is the complete baseline; once it is verified, those
entries are covered history and only later local-command entries are eligible
for post-base segments.

Removing `sync_operations` requires a later reviewed schema migration after
Stage 3 proves the first app-managed backup.

## New transaction and generation semantics

An accepted `DomainCommand` receives exactly one local generation inside the
same Room transaction as its record changes.

- Rejected commands allocate no generation.
- A rolled-back transaction consumes no committed generation.
- Commands emitting several operations assign stable sequence values
  `0..n-1` under the same generation.
- An idempotent success that changes no persisted product row and emits no
  journal entry does not allocate a generation.
- A generation is local backup continuity, not a remote revision or merge
  clock.
- Existing record revisions remain available for deterministic encoding,
  local history, tombstones, and exact Undo.
- Downloaded or restored data will never enter the active journal.

The repository implementation centralises generation allocation and journal
append operations so individual command branches cannot accidentally update a
record without a journal entry. The in-memory repository uses a deterministic
in-memory journal implementation for shared contract tests.

New local-command payloads use a strict canonical v1 discriminated union. Each
entry is either a complete after-image for one changed logical record/relation
or a deletion marker with its complete stable identity. A command that changes
several rows emits every affected after-image or deletion under the command's
one generation. Payloads never depend on the old sync parser, and a future
recovery reader can apply them after a verified base without consulting live
Room state.

## Consistent backup capture

Room rows and `backup_state.currentGeneration` are read inside one short
transaction into immutable capture DTOs. Encoding, encryption, file I/O, and
verification occur after the transaction.

The capture includes every structured record required to reconstruct the
vault:

- vault, workspace, and member metadata;
- projects, workflows, milestones, tasks, checklist items, dependencies, tags,
  and task-tag membership;
- reminders;
- time entries;
- templates and saved views;
- activity rows;
- attachment metadata and opaque blob references;
- tombstones; and
- format/version metadata.

It excludes:

- `sync_operations`, `backup_journal`, and `backup_state`;
- local database and content-key preference envelopes;
- Android Keystore aliases or key material;
- credentials or provider metadata;
- cache and temporary files; and
- attachment bytes.

Device-specific fields already embedded in encrypted structured records may
remain for historical interpretation. No device identity appears in the
portable package's public bootstrap metadata.

## Snapshot payload v1

Snapshot plaintext uses a dedicated versioned wire model rather than serialised
Room entities or `WorkspaceSnapshot`.

Properties have fixed declaration order. Collections are sorted by stable
identifiers and, where identity is composite, by every identity component.
Times are epoch milliseconds, dates are ISO-8601 local dates, optional values
are explicit `null`, enums use fixed uppercase wire names, and byte fields use
unpadded Base64.

The strict decoder rejects:

- unknown, missing, duplicated, reordered, or non-canonical fields;
- invalid UTF-8;
- unsupported snapshot or minimum-reader versions;
- duplicate identifiers;
- broken foreign keys;
- invalid workflow ownership or semantic coverage;
- invalid task, milestone, checklist, tag, reminder, dependency, template,
  time-entry, attachment, or tombstone bounds;
- cyclic parent/dependency graphs;
- impossible dates, instants, zones, counts, or sizes; and
- any payload exceeding the existing 100,000-record or 64 MiB snapshot bounds.

The snapshot payload carries its covered local generation. The authenticated
outer frame uses family `SNAPSHOT` and object identity
`snapshot:<generation>`.

Independent golden payloads freeze canonical ordering and cross-implementation
bytes.

## Operation-segment payload v1

Operation segments contain:

- payload version and minimum reader;
- vault ID;
- inclusive first and last local generation;
- ordered journal entries; and
- entry count.

Entries sort by generation then sequence and preserve operation IDs and payload
bytes exactly.

One segment contains at most 10,000 operations and at most 16 MiB of plaintext
before authenticated framing. The authenticated outer identity is
`segment:<firstGeneration>:<lastGeneration>`.

Local checkpoint advancement occurs only after the complete frame is written,
decoded, authenticated, strictly parsed, and compared with the source
generation range and operation IDs.

## Local backup object lifecycle

Local app-managed backup staging lives under:

```text
noBackupFilesDir/backup/v1/
    current/
    previous/
    segments/
```

Android backup APIs cannot include `noBackupFilesDir`.

The first verified baseline becomes `current`. A new complete snapshot is
required after either:

- 5,000 operations since the current base; or
- seven days since that base was produced.

Earlier snapshots are allowed for bounded recovery or explicit verification.
After a replacement base is fully verified:

1. current becomes previous;
2. the replacement becomes current;
3. only segments required after current or previous are retained; and
4. pruning occurs after checkpoint commit and never touches the portable
   package or future attachment blobs.

In-process work is requested by journal changes and coalesced with an injected
debounce policy. Startup observes the persistent journal/checkpoint and resumes
pending work. WorkManager and network scheduling remain Stage 3 concerns.

## Portable package v1

The one portable file is:

```text
filesDir/android_backup/open_tasks_portable_v1.otb
```

It is a bounded binary container:

```text
4-byte big-endian bootstrap-header length
canonical UTF-8 bootstrap header
authenticated MANIFEST frame
authenticated SNAPSHOT frame
```

### Public bootstrap header

The canonical header has fixed property order and a 16 KiB bound. It contains:

- magic `OPEN_TASKS_PORTABLE`;
- package version `1`;
- minimum reader version `1`;
- opaque vault ID;
- covered local generation;
- local production epoch milliseconds;
- the recovery envelope and bounded KDF metadata;
- manifest frame length and SHA-256;
- snapshot frame length and SHA-256; and
- exact total package length.

It contains no task content, attachment display name, provider/account data,
local database key, Android Keystore alias, local device ID, or upload claim.

### Authenticated manifest

The manifest plaintext repeats and cryptographically binds:

- package and minimum-reader versions;
- vault ID;
- generation and production time;
- SHA-256 of the canonical recovery-envelope representation;
- snapshot object identity, frame length, and frame SHA-256; and
- bounded record-family counts.

Its authenticated outer family is `MANIFEST`, with object identity
`portable-manifest:<generation>`.

### Authenticated snapshot

The snapshot frame contains the complete Snapshot Payload v1 for the same
generation. Bootstrap metadata, decrypted manifest metadata, and decoded
snapshot metadata must agree exactly.

### Size and ownership

The complete final file must be at most 24 MiB, leaving margin below Android's
25 MB per-app Auto Backup quota. Header and frame lengths are checked before
allocation or read.

Plaintext, associated-data, ciphertext, derived-key, and passphrase buffers are
cleared at their ownership boundaries. Stage 2 remains on the existing
bounded byte-array AEAD boundary; it does not introduce a second streaming
cryptographic format.

## Recovery-passphrase setup

The minimal More flow is **Prepare Android backup**.

The screen explains:

- Android backup is supplementary;
- app-managed cloud backup is not yet available;
- the package is not proof that Android uploaded anything;
- the recovery passphrase cannot be recovered; and
- forgetting it makes the portable package unusable.

The passphrase:

- must be 12–128 Unicode code points;
- is not trimmed or normalised;
- must match its confirmation exactly;
- is held in non-saveable secure text state;
- is converted to `CharArray` only at the cryptographic boundary; and
- is removed from UI state immediately after submission, while every mutable
  character, UTF-8, and derived-key buffer is cleared at its ownership
  boundary.

Compose and the Android input stack may create immutable internal text copies
that cannot be zeroed directly. The flow minimises their lifetime, never saves
them, never converts them to logs or exception messages, and does not claim
stronger zeroisation than the runtime can provide.

The app:

1. opens the existing locally wrapped vault-content key;
2. calls the existing `wrapForRecovery`;
3. immediately unlocks the new envelope and proves it represents the same
   content key;
4. persists only the verified encrypted envelope in SQLCipher;
5. produces and verifies the portable package; and
6. reports `Ready` only after the final file passes complete validation.

The setup path never calls `createKey` for an existing vault. A failure leaves
the existing content key, database, prior verified envelope, and prior package
unchanged.

Package refresh does not require the passphrase again. The locally wrapped
content key encrypts new snapshot bytes, and the stored verified recovery
envelope is copied into the package.

Passphrase change and recovery entry are Stage 3 product flows.

## Package publication

`PortableBackupPublisher` uses `AtomicFile` for the exact eligible path.

1. Capture one complete snapshot and target generation.
2. Encode and authenticate the manifest and snapshot frames.
3. Write a temporary atomic file.
4. Flush it.
5. Parse the complete temporary package.
6. Verify lengths and checksums before AEAD.
7. Authenticate both frames.
8. Strictly decode the manifest and snapshot.
9. Confirm envelope digest, vault identity, generation, counts, relations, and
   total package bytes.
10. Commit the atomic file.
11. Commit package-ready status for the same generation.

`AtomicFile` temporary or `.bak` paths are not included by the Android rules.

If local state advances during production, the verified package may commit for
its captured generation and immediately becomes `UpdatePending`. A later
coalesced run targets the newer generation.

## Android backup and transfer rules

After package production and restored-package handling are implemented, the
manifest explicitly sets:

```xml
android:allowBackup="true"
```

No custom `BackupAgent` and no `backupInForeground` override are added.

For API 36 and 37, `data_extraction_rules.xml` has one exact include per
transfer mode:

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

An `<include>` disables default inclusion for that mode. No database,
shared-preference, root, device-protected, external, directory-wide, or
cross-platform include exists.

The legacy `backup_rules.xml` remains deny-all. The app's `minSdk` is 36, so
that file is not the active configuration on supported devices; retaining its
deny-all posture prevents accidental exposure if platform support changes.

The package is self-encrypted. Cloud transfer additionally requires platform
encryption capability through
`disableIfNoEncryptionCapabilities="true"`. Device-to-device transfer uses the
same package but does not rely on a server-side dataset.

## Restored-package handling

Android restores eligible files before first application launch.

At startup, package discovery runs before publication:

- When Room `backup_state` and the package agree, it is the current
  installation's self-produced file.
- When backup state is absent, vault identity differs, or generation cannot be
  linked to the local state, the file is treated as restored/unknown.

An unknown file is never opened as Room and never overwritten.

The app first performs bounded bootstrap parsing. It then atomically moves the
file, without decrypting it, to:

```text
noBackupFilesDir/recovery/incoming_android_v1.otb
```

If the move fails, package publication remains blocked and the eligible file
is left untouched. Malformed unknown files are preserved with a typed
incompatible/corrupt status for future recovery diagnosis.

Stage 2 may report **Restored package detected**, but supplies no restore,
discard, or activation action. Stage 3 will own passphrase entry, compatibility
checks, staged SQLCipher construction, cloud-source comparison, takeover, and
activation.

## User-visible status

More gains a minimal **Backup & recovery** section.

### `NotPrepared`

No recovery envelope exists. Show the supplementary-backup explanation and
**Prepare Android backup**.

### `Preparing`

Envelope or package work is in progress. Do not expose private operation names
or content.

### `Ready`

Show:

- package generation;
- current local generation;
- byte count; and
- local production time.

Copy says **Package ready**, never **Backed up**.

### `UpdatePending`

The eligible package is valid but older than current local state. Keep the
previous verified package while retrying.

### `Unavailable`

Show a bounded local reason:

- package over 24 MiB;
- recovery envelope unavailable;
- encoding/encryption/verification failure; or
- local file I/O failure.

Android exposes no dependable upload time or encryption-capability state to
the app. The section links to system backup settings for platform state and
does not infer upload success.

### `RestoredPackageDetected`

An inert package is preserved in the recovery inbox. Explain that recovery
activation is not available in the current stage.

No backup indicator is added to Home in Stage 2.

## Failure policy

| Failure | Required result |
|---|---|
| Rejected product command | No record change and no generation |
| Room transaction failure | Record, generation, and journal changes all roll back |
| Migration failure | Room does not open v6; v5 file remains the recovery fixture |
| Snapshot/segment encode or crypto failure | Checkpoint does not advance; local editing continues |
| Local object verification failure | Reject object, retain prior verified base/checkpoint |
| Transient package encode, crypto, or file failure | Retain prior verified package and report it stale |
| Package exceeds 24 MiB | Withdraw eligible final file for the current generation |
| Corrupt self-produced package | Withdraw and regenerate |
| Unknown/restored package | Preserve in recovery inbox; never overwrite or activate |
| Recovery-envelope setup failure | Keep prior envelope/package/content key unchanged |
| Process death during atomic write | Previous verified file or recoverable `AtomicFile` state remains |
| Platform backup disabled or encryption unavailable | OS skips cloud backup; app makes no upload claim |

Persistent failure codes contain no private content, paths outside fixed public
labels, ciphertext, checksums, key data, account state, or exception text.

## Concurrency and lifecycle

- Room is the generation authority.
- Capture observes one committed generation and immutable row set.
- Coordinator work is single-flight per vault.
- Newer requests coalesce while one capture is running.
- A successful older-generation package may commit safely and become
  `UpdatePending`.
- Coordinator cancellation clears owned sensitive buffers and leaves
  checkpoints unchanged.
- Application startup resumes from persistent state.
- Auto Backup may stop the process; Android only sees the atomically committed
  final file.
- Repository/database shutdown ordering remains unchanged.

## Security and privacy invariants

- Room remains the sole live structured-data authority.
- No normal provider or package flow mutates Room records.
- The existing content key is wrapped; it is never silently replaced.
- Recovery passphrases are never persisted or placed in saved state.
- Public package metadata contains no product content or local device ID.
- Snapshot and manifest frames bind complete object identity through AEAD.
- Length and checksum validation precede AEAD and payload allocation remains
  bounded.
- Bootstrap, manifest, and snapshot identities must agree.
- Temporary plaintext and key material are cleared at ownership boundaries.
- The package contains no attachment bytes.
- Android can copy only the exact final portable file.
- Local backup staging uses `noBackupFilesDir`.
- Logs and persistent status contain no task text, attachment names, account
  data, ciphertext, checksums, recovery metadata, paths, or keys.

## Verification strategy

### JVM

- Generation allocation and rollback.
- Multi-entry command sequencing.
- In-memory and Room contract alignment.
- Deterministic v5 legacy-row ordering/copy semantics.
- Snapshot canonical bytes and independent golden payload.
- Strict snapshot bounds, duplicates, ownership, relations, cycles, dates,
  zones, and version failures.
- Operation-segment generation ranges, count/byte limits, and golden payload.
- Local checkpoint advancement only after full decode verification.
- Complete and previous-base retention rules.
- Portable bootstrap, manifest, and snapshot canonical bytes.
- Wrong key/passphrase, tamper, swapped frame, truncation, future version,
  length/checksum, envelope digest, generation, and count mismatches.
- 24 MiB boundary below, at, and above the limit.
- Atomic replacement, previous-package retention, withdrawal, and restored
  package classification.
- Passphrase length/confirmation and exact-key-envelope verification.
- Passphrase, key, plaintext, associated-data, and ciphertext cleanup.
- Status copy never claims upload.

### Instrumented Room

- v5→v6 migration from an exported v5 fixture.
- Every legacy row, payload, revision, and ID preserved.
- v6 exported schema validation.
- Accepted commands commit records, generation, and ordered journal rows
  atomically.
- Rejected and failed commands leave all three unchanged.
- Complete baseline verification and legacy coverage marker.
- Encrypted close/reopen and cold process restart.
- In-place upgrade of the protected workspace only through the recorded
  snapshot/audit procedure; never uninstall or clear data.

### Disposable-device acceptance

- Run with exactly one verified disposable ADB target.
- Audit AVD, API, posture, font scale, and process flags before instrumentation.
- Verify the packaged manifest and `data_extraction_rules` contain exactly the
  approved file includes.
- Prove database, WAL/SHM, preferences, keys, credentials, cache, local staging,
  and attachment paths are absent from the eligible set.
- Exercise setup, confirmation mismatch, retry, ready, pending, stale,
  oversized, and restored-package states.
- Rotation, process death, and restart never restore passphrase fields.
- Package replacement and withdrawal leave no eligible temp/backup file.
- More UI passes compact, foldable, expanded, keyboard, TalkBack semantics,
  and 100%, 130%, and 200% text.
- `bmgr` commands run only on disposable state.

Actual Google transport upload/restore requires a configured account and
transport and remains an external qualification gate. Stage 2 does not infer
that result from local package readiness.

### Repository gates

After production changes:

```bash
git diff --check
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
./gradlew :app:assembleRelease --stacktrace
```

Release assembly remains separate because the repository records an AGP/KSP
release-lint race.

## Acceptance criteria

- Existing v5 data and every legacy outbox row survive migration.
- New accepted mutations and their journal entries commit atomically under one
  local generation.
- The first complete baseline is verified before legacy rows are marked
  covered.
- Verified local snapshots and bounded operation segments represent every
  structured record and tombstone without attachment bytes.
- One recovery passphrase wraps the existing content key and is never stored.
- The portable package authenticates its manifest, snapshot, envelope digest,
  identity, generation, and counts.
- The package is at most 24 MiB or is withdrawn.
- Android cloud backup and device transfer can include only the exact final
  portable-package path.
- Local staging, Room, keys, preferences, credentials, cache, and attachments
  are excluded.
- An Android-restored package remains inert, preserved, and unactivated.
- The UI reports only local package facts and never claims upload.
- Backup/package failure never blocks local editing.
- No provider, WorkManager, restore activation, writer takeover, attachment
  transfer, remote merge, or second live writer enters Stage 2.

## Implementation-plan boundaries

The implementation plan must decompose Stage 2 into reviewable tasks for:

1. domain/model contract replacement;
2. Room v6 migration and journal atomicity;
3. snapshot and segment payload formats with independent vectors;
4. verified local object lifecycle;
5. recovery-envelope persistence and setup orchestration;
6. portable-package codec and atomic publisher;
7. Android manifest/extraction rules and restored-file intake;
8. minimal More status/setup UI;
9. migration, disposable-device, debug, release, and handoff gates.

Every task uses strict test-driven development and commits independently.
Source work may begin only after the written spec and its implementation plan
are reviewed and approved.

## Platform references

- [Android Auto Backup](https://developer.android.com/identity/data/autobackup)
- [Android data backup overview](https://developer.android.com/identity/data/backup)
- [Test backup and restore](https://developer.android.com/identity/data/testingbackup)
- [Backup security recommendations](https://developer.android.com/privacy-and-security/risks/backup-best-practices)
- [BackupManager API](https://developer.android.com/reference/android/app/backup/BackupManager)
