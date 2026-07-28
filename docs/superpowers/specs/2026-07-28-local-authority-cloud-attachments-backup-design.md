# Local Authority, Cloud Attachments, and Backup Direction Design

**Date:** 28 July 2026  
**Status:** Interactive design approved; written review pending  
**Scope:** Revised storage, backup, attachment, recovery, and delivery
direction

## Decision

Open Tasks will have one live data authority: the encrypted Room database on
the active device.

All structured workspace data remains local during normal use. This includes
tasks, projects, workflows, milestones, reminders, templates, notes, activity,
time entries, settings that belong to the vault, attachment metadata, and
deletion tombstones. Structured records are not synchronised or merged across
devices.

Cloud use has two logically separate purposes:

1. The backup service stores encrypted structured-data backups for recovery.
2. The attachment blob service stores encrypted attachment bytes.

Both services may use one authorised Google Drive `appDataFolder`, but they
have separate interfaces, object namespaces, retention policies, statuses, and
destructive operations. Outside recovery backups, attachment bytes are the
only product content whose durable authority is in the cloud.

Android Auto Backup is enabled as a supplementary transport for one portable
encrypted structured-data package. It does not replace the app-managed backup
guarantee and never receives the live database, device-bound key envelopes, or
attachment bytes.

A backed-up vault has one active writer device. Recovery on another device is
an explicit takeover, not multi-device synchronisation.

## Goals

- Preserve the complete structured workspace locally and keep it useful
  without an account or network connection.
- Preserve the existing recovery requirement for reinstall, new-device, and
  Android Keystore loss.
- Minimise durable device storage by keeping attachment bytes in the cloud.
- Keep attachment failures isolated from structured workspace editing and
  backup.
- Prevent two divergent local databases from overwriting one backup lineage or
  mutating the same attachment set.
- Reuse the completed key-separation and bounded cloud-frame work.
- Replace misleading sync and Drive-primary concepts in code, product copy,
  tests, and the active roadmap.
- Preserve the existing encrypted Room workspace through every transition.

## Non-goals

- Live or eventual structured-data synchronisation between devices.
- Field-level remote conflict resolution or remote-to-local merge during
  normal use.
- Drive-primary structured storage.
- Durable offline attachment copies or a `keepOffline` option.
- Using Android Auto Backup as the sole recovery guarantee.
- Collaboration, a proprietary server, or a second cloud provider.

## Current foundation and compatibility

The application already treats Room as the active authority. Google Identity,
Drive transport, user-facing recovery, and attachment content are not
implemented. No remote structured state needs to be imported or reconciled.

The following completed work remains valid:

- SQLCipher Room persistence and device-bound database-key wrapping.
- Typed `DomainCommand` writes through `VaultRepository`.
- Atomic record and outbox writes, repository-produced Undo, migrations, and
  tombstones.
- An independently generated vault-content key with separate local Android
  Keystore and recovery-passphrase envelopes.
- Canonical, bounded manifest, snapshot, operation-segment, and
  attachment-chunk frames.
- Strict header parsing, checksum verification, and one-shot ciphertext
  ownership.

The paused authenticated-codec task remains necessary, but it becomes a
provider-independent backup and blob codec. It must not introduce Drive-primary
or remote-merge behaviour.

The hybrid logical clock and merge primitives may remain as unused,
well-tested internal code until a focused cleanup is justified. They are no
longer a product or release dependency.

## Architecture

```text
Compose UI
    │ typed actions
    ▼
app command dispatch
    │ DomainCommand
    ▼
VaultRepository
    ├── SQLCipher Room records ─────────── sole live authority
    ├── atomic backup journal
    └── immutable WorkspaceSnapshot

BackupCoordinator
    ├── reads consistent local snapshots/journal
    ├── encrypts through AuthenticatedCloudObjectCodec
    └── writes BackupObjectStore namespace

AttachmentBlobCoordinator
    ├── streams bounded chunks from/to temporary cache
    ├── encrypts through AuthenticatedCloudObjectCodec
    └── writes AttachmentBlobStore namespace

PortableBackupPublisher
    └── atomically publishes one Auto Backup-eligible encrypted package

RecoveryCoordinator
    ├── reads backup objects or a restored portable package
    ├── builds and verifies a new staged SQLCipher vault
    ├── claims the next writer epoch
    └── atomically activates the staged local vault
```

Normal operation has no cloud-to-Room record path. `RecoveryCoordinator` is
the only component allowed to reconstruct Room from cloud or portable backup
data.

## Local data and backup journal

`VaultRepository` remains the only structured-data mutation boundary. Every
accepted command commits its record changes and backup-journal entry in one
Room transaction. The journal is the narrowed successor to the existing
upload outbox.

The journal is single-writer backup continuity, not a synchronisation log. It
supports:

- identifying the local generation that still needs backup;
- building immutable encrypted segments after a complete snapshot;
- preserving deletions and tombstones after the latest snapshot;
- proving that a successful backup covers a particular local generation.

Downloaded backup data never enters this journal. Recovery constructs a
replacement database in staging rather than replaying remote data through the
active repository.

Existing record revisions may remain for local ordering, history, Undo,
tombstones, and deterministic backup encoding. They no longer imply
multi-device merge semantics.

## Cloud setup

The core application requires neither an account nor a recovery passphrase.
Cloud setup begins only when the user:

- enables the app-managed backup; or
- adds the first attachment.

The focused setup:

1. explains primary backup, supplementary Android backup, cloud-only
   attachments, and single-device ownership;
2. requests only Google Drive `drive.appdata` authorisation;
3. creates or confirms a recovery passphrase;
4. wraps the existing vault-content key in a recovery envelope;
5. establishes writer epoch `1` for the new backup lineage; and
6. produces and verifies the first complete backup before reporting setup
   complete.

An attachment cannot become a durable task attachment until this recovery
configuration exists.

## App-managed structured backup

The app-managed backup remains the dependable recovery path. It contains:

- a bounded manifest with format, recovery-envelope, backup-generation, writer
  epoch, and inventory metadata;
- complete encrypted structured snapshots;
- immutable encrypted backup-journal segments after a retained snapshot; and
- attachment metadata and opaque blob references, but no attachment bytes.

The current format bounds remain:

- 16 KiB canonical header;
- 1 MiB manifest ciphertext;
- 64 MiB snapshot ciphertext;
- 16 MiB and 10,000 operations per journal segment; and
- 100,000 records per snapshot and 10,000 manifest inventory entries.

Backup is requested after local changes with a debounce, periodically under a
network constraint, during risk-sensitive operations, and through **Back up
now**. Exact scheduling intervals are an injected implementation policy rather
than a persisted format contract; the implementation plan must select and test
them.

A checkpoint advances only after the app:

1. uploads every required object;
2. downloads the published bytes;
3. checks length and SHA-256;
4. authenticates and decodes the complete object; and
5. confirms the object identity and covered local generation.

Retention keeps:

- the current verified complete snapshot;
- the immediately previous verified complete snapshot; and
- the journal segments required after either retained recovery base.

A damaged current snapshot falls back to the previous snapshot plus its later
segments. Snapshot pruning never deletes attachment blobs.

Backup failure never blocks local edits. The app records the pending local
generation, last verified backup time, failure category, and retry state.

## Android Auto Backup

The manifest enables Android Auto Backup, but the include rules whitelist only
one atomically replaced portable package. All other application roots remain
excluded from cloud backup and device transfer unless a later reviewed design
explicitly adds another safe file.

The package contains:

- one complete encrypted structured snapshot at a declared local generation;
- the recovery envelope and format metadata;
- attachment metadata and opaque blob references; and
- no raw Room, WAL, SHM, preference-key, Keystore, credential, device-ID, cache,
  or attachment-content file.

Cloud backup requires platform encryption capability through
`disableIfNoEncryptionCapabilities="true"`. The portable package must be at
most 24 MiB, leaving margin under Android's 25 MB per-app limit.

If the package exceeds 24 MiB or platform backup is unavailable:

- the package is withdrawn from the eligible set;
- the UI reports Android backup as unavailable for the current generation; and
- the app-managed backup remains current and unaffected.

The UI may report when and at which generation the package was produced. It
must not claim Android uploaded it or report an invented system-backup time.
Deleting app-managed Drive data does not claim to delete Android's separate
backup dataset; the UI directs the user to system backup settings for that
operation.

## Writer ownership and takeover

The app-managed manifest contains a monotonically increasing writer epoch and
the active writer's opaque device identity. Backup and attachment mutations
require the current epoch and a conditional manifest revision. Reads do not.

Recovery that keeps the original vault identity must:

1. authorise the provider;
2. read the latest manifest revision;
3. decrypt and validate the selected recovery source;
4. ask for explicit takeover confirmation;
5. conditionally publish the next writer epoch; and
6. activate the staged vault only after that claim succeeds.

The prior device retains its local database, but its old epoch cannot upload
backups, publish or delete attachment blobs, or overwrite the manifest. When it
learns that ownership changed, it blocks cloud mutations and offers to preserve
any divergent local work under a new vault identity. It never merges that work
into the recovered original automatically.

An offline prior device cannot learn about takeover immediately. Conditional
provider writes still prevent it from overwriting the cloud lineage when it
reconnects.

If the app-managed cloud lineage has been permanently deleted, an Auto Backup
package may be activated only as a new vault identity after an explicit
warning. This avoids creating a second writer for the deleted lineage.
Attachment metadata is preserved, but missing blob content is reported as
unavailable rather than invented or silently removed.

## Recovery

Recovery entry points remain:

- reinstall with no local vault;
- new-device restore;
- an unreadable local Keystore envelope; and
- an explicit restore action.

The app-managed backup is preferred when it is available and newer than the
restored portable package. A portable package is a fallback source, not a
reason to ignore a newer verified primary backup.

Recovery:

1. validates the manifest, KDF bounds, versions, lengths, and inventory;
2. unlocks the vault-content key in memory using the recovery passphrase;
3. authenticates the selected snapshot and later journal segments;
4. falls back to the previous snapshot if the current snapshot is damaged;
5. constructs a new SQLCipher database under a new random database key;
6. verifies counts, identifiers, ownership, relations, tombstones, and
   attachment references;
7. creates a new local Android Keystore wrapper for the same vault-content key;
8. claims the next writer epoch when retaining the cloud identity; and
9. atomically activates the staged vault.

Wrong passphrase, weakened KDF metadata, damaged or future formats, missing
required segments, insufficient storage, or failed takeover leaves the current
installation unchanged.

## Attachment blob lifecycle

Attachment display name, MIME type, byte count, content hash, task relation,
blob-set identity, chunk inventory, revision, lifecycle, and transfer status
are structured metadata in Room and structured backups.

Attachment bytes are durable only in the attachment blob service.

### Intake

Photo Picker and Storage Access Framework URIs are untrusted. Intake requires
network availability before transfer begins and:

1. obtains the narrowest temporary read permission;
2. assigns an opaque provisional blob-set identity;
3. streams at most 100 MiB while computing the content digest;
4. encrypts each 4 MiB chunk with vault, blob, format, and chunk identity as
   associated data;
5. uploads into a provisional blob session;
6. downloads and verifies every uploaded chunk;
7. publishes the verified blob set; and
8. registers attachment metadata plus its backup-journal entry atomically.

Upload uses a bounded working set rather than a complete staging copy: one
plaintext chunk, one ciphertext chunk, and bounded transport overhead.

An interrupted provisional session does not appear as a completed task
attachment. Sessions older than 24 hours are removed. If the source permission
or source bytes are unavailable on retry, the app asks the user to select the
file again.

### Open and share

Opening requires network access unless the needed authenticated chunks remain
in the temporary cache. The app downloads, checks, authenticates, and decrypts
one chunk at a time.

The encrypted cache is an LRU under `cacheDir` with a ceiling equal to the
smaller of 128 MiB or 5% of currently available app storage. Android or the app
may evict all cached chunks at any time. There is no `keepOffline` state.

Content streams directly when the consumer supports it. A short-lived
decrypted file is created only when an external consumer requires a file. It
is constrained to the private `FileProvider` share path, granted only to the
chosen target, deleted when the operation completes, and removed by startup
cleanup if abandoned.

### Delete and garbage collection

Attachment deletion:

1. tombstones metadata and appends its backup-journal operation atomically;
2. evicts local cache content;
3. retains remote blobs for at least 30 days and while an active or retained
   recovery inventory can legitimately reference them; and
4. restores the exact metadata reference on Undo without re-uploading bytes.

Remote garbage collection occurs only after:

- the deletion tombstone exists in a verified backup;
- the 30-day retention period has elapsed; and
- no active metadata or retained recovery inventory references the blob set.

A permanent task purge remains explicitly confirmed. It does not bypass the
verified-tombstone requirement for safe remote deletion.

## Component boundaries

### `VaultRepository` and `BackupJournal`

Own structured records, typed commands, exact Undo, consistent local
snapshots, and atomic backup-generation changes. They have no provider API
dependency.

### `AuthenticatedCloudObjectCodec`

Owns canonical framing, checksum-before-decrypt, full identity-bound AEAD,
bounded payload decode, typed failure, and plaintext/ciphertext ownership. It
is shared by backup and attachment services and remains provider-independent.

### `BackupCoordinator` and `BackupObjectStore`

Own snapshot/segment production, app-managed upload, verification, retention,
backup checkpoint, and writer-epoch coordination. They cannot mutate product
records.

### `PortableBackupPublisher`

Owns consistent portable-package production, atomic replacement, size
eligibility, and withdrawal. It cannot claim that Android uploaded a package.

### `AttachmentBlobCoordinator` and `AttachmentBlobStore`

Own provisional sessions, chunk transfer, bounded cache, blob verification,
and remote garbage collection. Metadata registration still goes through
`VaultRepository`.

### `RecoveryCoordinator`

Owns staged reconstruction, verification, writer takeover, repository
quiescence, atomic activation, and rollback on pre-activation failure. It is
the only cloud-to-Room structured-data path.

## Product surfaces and terminology

User-facing copy uses **backup**, **cloud attachment**, **restore**, and
**active device**. It does not use **sync**, **Drive-primary**, or **up to date
across devices**.

More gains **Backup & recovery** with four independent sections:

- encrypted app backup: enabled state, last verified time, pending generation,
  failure category, retry, and **Back up now**;
- Android backup package: ready/unavailable state, local production time,
  generation, and size;
- cloud attachments: account connection and temporary-cache usage; and
- active device: writer ownership and takeover explanation.

Home has no sync-health indicator. It may show backup attention only after
backup is configured and is blocked or meaningfully overdue. The overdue
threshold is an injected product policy selected in the implementation plan,
not a cloud-format contract.

Task attachment rows distinguish remote, downloading, unavailable,
tombstoned, and failed transfer state using text and iconography. Offline or
attachment failure never disables task editing.

Disconnecting the account:

- retains all local structured data and attachment metadata;
- stops app-managed backups; and
- makes attachment bytes unavailable until reconnection.

Changing accounts is a verified cloud-store migration. It copies and verifies
backup objects and attachment blobs before changing the binding. It never
silently replaces the provider account.

Deleting app-managed cloud data is a separate destructive action. It requires
recovery-passphrase confirmation and explicit acknowledgement that primary
backups and attachment content will be lost. It never implies deletion of the
Android-managed Auto Backup dataset.

## Failure containment

| Failure | Required result |
|---|---|
| Offline or provider authentication failure | Local structured editing remains available; backup and attachment actions show their own retry state |
| Backup quota or retry exhaustion | Pending local generation remains durable; no attachment state is changed |
| Attachment quota or interrupted upload | Provisional session remains bounded or is cleaned; no completed metadata record is invented |
| Missing or damaged attachment chunk | File is unavailable with retry/recovery guidance; task metadata remains intact |
| Damaged current backup snapshot | Recovery tries the previous verified snapshot and required later segments |
| Wrong passphrase or failed AEAD | No plaintext is exposed and the active installation is unchanged |
| Writer-epoch mismatch | Cloud mutation is rejected; divergent local work is never merged automatically |
| Auto Backup package over 24 MiB | Supplementary package becomes unavailable; primary backup is unaffected |
| Disk pressure | Cache is evicted first; active local database and verified remote blobs are preserved |
| Process death during restore | Staged state is either resumable before activation or removed; active vault remains unchanged |

## Non-destructive transition

The current protected Room workspace remains the migration and acceptance
fixture. No uninstall, data clear, destructive schema rewrite, or cloud-mode
simulation is permitted.

The transition must:

1. record this approved direction and supersede the old future roadmap;
2. complete the authenticated object codec without sync semantics;
3. normalise the product model to local authority;
4. preserve existing outbox rows until a complete baseline backup is verified;
5. establish that baseline as the first backup generation;
6. retire or migrate obsolete sync bookkeeping only after verification; and
7. migrate placeholder attachment metadata without inventing attachment
   content.

Any Room version change requires an exported schema, a non-destructive
migration, restart verification, and an in-place run against the protected
workspace. The current application has no remote vault, so no remote state is
discarded.

The obsolete `keepOffline` attachment preference is removed. Existing
placeholder rows map to metadata-only unavailable state unless a verified blob
reference exists.

## Revised delivery programme

This direction is too broad for one implementation plan. It is decomposed into
six dependency-ordered stages, each with its own focused design or
implementation plan and review checkpoint.

### Stage 1 — Direction reset and authenticated object foundation

- Update the handoff, product, README, architecture, threat model, design
  language, production programme, and affected train contracts.
- Correct the known Insights test lint gate.
- Complete the provider-independent authenticated codec.
- Freeze backup/blob golden vectors and typed failures.

### Stage 2 — Local backup and Android Auto Backup foundation

- Replace Drive-primary and user-facing sync contracts with local-only backup
  contracts.
- Migrate the outbox to explicit backup-journal semantics.
- Produce consistent complete snapshots and the portable encrypted package.
- Enable Auto Backup with strict include rules and backup status.

### Stage 3 — App-managed backup and recovery takeover

- Add Google Identity and separate backup namespace transport.
- Implement backup retention, verification, writer epochs, and status.
- Implement reinstall, new-device, and Keystore-loss recovery.
- Prove takeover atomicity and stale-writer rejection.

### Stage 4 — Notes, activity, cloud attachments, and search

- Finalise metadata schemas and note/activity commands.
- Implement streaming attachment intake, blob transport, cache, open/share,
  deletion, Undo, and garbage collection.
- Extend search to notes and attachment display names.

### Stage 5 — Remaining platform features

- Rebase encrypted import/export, widget, app lock, input refinements, and
  calendar insertion on the final local schema and new backup terminology.

### Stage 6 — Production qualification and rollout

- Replace multi-device sync acceptance with backup, takeover, stale-writer,
  Auto Backup, attachment, and recovery matrices.
- Complete accessibility, responsive, performance, privacy, Play, and rollout
  gates.

## Verification

### Local authority

- Accountless and offline CRUD, reminders, timers, search, and Insights.
- Process restart and every supported Room migration.
- Atomic record plus backup-journal writes.
- Static and dynamic proof that normal provider flows cannot write Room.

### Cryptography and formats

- Independent golden frames and payload fixtures.
- Full header-identity AEAD binding for every object family.
- Wrong key/passphrase, tamper, truncation, checksum, future-reader, and
  allocation-bound cases.
- Key and plaintext zeroisation at ownership boundaries.

### App-managed backup

- Pagination, retry, redelivery, partial transfer, quota, and authentication
  expiry.
- Current and previous snapshot recovery.
- Missing/corrupt segments and incompatible formats.
- Conditional writer-epoch acquisition and old-writer rejection.
- App-managed account migration and destructive cloud deletion.

### Android Auto Backup

- Manifest and extraction-rule audit on every supported API.
- Proof that Room, WAL, SHM, key preferences, credentials, cache, and
  attachment bytes are excluded.
- Package round-trip at and around the 24 MiB eligibility boundary.
- Atomic file replacement, withdrawal, and install/restore acceptance.

### Attachments

- Unknown or lying provider size, filename traversal, MIME spoofing, and the
  100 MiB bound.
- Chunk interruption, process death, source-permission loss, missing chunks,
  wrong chunk identity, and hostile streams.
- Authentication, quota, retry, cache eviction, insufficient space, and
  cleanup.
- Delete/Undo, Bin/permanent purge, backup references, and remote garbage
  collection.

### Recovery and UI

- Reinstall, new device, Keystore loss, Auto Backup fallback, damaged primary
  backup, and failed takeover.
- No activation before complete staging and verification.
- Backup, attachment, and recovery status at 100%, 130%, and 200% text,
  keyboard, TalkBack, Switch Access, compact, foldable, and expanded layouts.
- No user-facing implication of sync or concurrent multi-device use.

## Acceptance criteria

- All structured work remains usable offline without an account.
- Room is the only normal structured-data authority.
- No normal cloud flow downloads or merges structured records.
- A verified app-managed backup restores after reinstall, new-device setup, and
  Keystore loss.
- Android Auto Backup includes only an eligible portable encrypted package and
  is never presented as the primary guarantee.
- Attachment bytes are durable only in the attachment blob service.
- Upload and open use bounded local working storage and no durable offline
  copy.
- Backup failure does not block local work; attachment failure affects only
  the file operation.
- A recovered device cannot create a second cloud writer for the same vault
  identity.
- Existing local data survives the direction transition and every schema
  migration.
- Product copy and release gates contain no Drive-primary or multi-device sync
  promise.

## Documents superseded by this direction

After written approval, the active handoff and implementation plan must treat
the following future-looking portions as superseded:

- Drive-primary authority in `PRODUCT.md`, `docs/architecture.md`, and
  `docs/threat-model.md`;
- the Drive-primary and multi-device sections of the production programme;
- Train 2 core sync design and plan;
- Train 3 authority migration, disconnect, and multi-device design and plan;
- Train 4 keep-offline attachment cache policy; and
- the corresponding Train 6 sync qualification matrix.

Historical completion evidence for the local workspace, key separation, cloud
frame bounds, and reviewed commits remains valid.

## External platform constraints

- [Android Auto Backup](https://developer.android.com/identity/data/autobackup)
- [Android data backup overview](https://developer.android.com/identity/data/backup)
- [Android backup security recommendations](https://developer.android.com/privacy-and-security/risks/backup-best-practices)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Google Drive application-data folder](https://developers.google.com/workspace/drive/api/guides/appdata)
