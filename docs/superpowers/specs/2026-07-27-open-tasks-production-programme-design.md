# Open Tasks Production Programme Design

**Date:** 28 July 2026
**Status:** Approved programme contract
**Target:** Local-authority, recoverable production release with cloud-only
attachment bytes and staged updates

## Purpose

Deliver a private Android workspace whose structured data remains locally
authoritative, whose encrypted backups support verified recovery, and whose
attachment bytes can remain durably cloud-authoritative. This programme
implements the approved
[Local Authority, Cloud Attachments, and Backup Direction](2026-07-28-local-authority-cloud-attachments-backup-design.md).

The programme starts from the completed local workspace and Train 1 Tasks
1.1–1.5 recorded in `HANDOFF.md`. Those shipped facts remain evidence. Backup,
Android Auto Backup, Google authorisation, Drive transport, recovery UI, and
attachments are approved future work and are not operational at this
checkpoint.

## Product contract

Open Tasks remains:

- Android-only and adaptive across compact phones, foldables, and tablets.
- A solo-professional workspace without collaboration or a proprietary server.
- Free, without advertising, purchases, analytics, or crash-reporting SDKs.
- Fully useful offline, with encrypted Room as the sole live authority for
  structured workspace data.
- Recoverable through app-managed encrypted backup, supplemented by one
  strictly whitelisted Android Auto Backup package after Stage 2.
- Explicit that attachment metadata is local structured data while attachment
  bytes are durable only in the cloud attachment service.
- Limited to one active writer per backed-up vault; recovery elsewhere is an
  explicit takeover.
- Globally available with UK English (`en-GB`) as the only v1 locale.

The release does not add collaboration, a web client, iOS support, background
two-way calendar behaviour, plaintext cloud data, or an alternate analytics
store. It does not merge structured records from two active devices.

## Programme-wide engineering constraints

- Preserve `minSdk 36`, `compileSdk 37`, `targetSdk 37`, Java 17, the JDK 21
  daemon toolchain, and AGP built-in Kotlin.
- Keep Navigation 3, `WorkspaceLayoutPolicy`, stateless feature composables,
  and app-layer command dispatch.
- Route every durable structured mutation through `DomainCommand` and
  `VaultRepository.execute`.
- Commit every accepted mutation and its backup-journal entry atomically.
- Preserve existing outbox rows and local data until Stage 2 verifies their
  migration.
- Use repository-produced Undo for reversible commands.
- Keep `InMemoryVaultRepository` aligned with `RoomVaultRepository`.
- Allow only `RecoveryCoordinator` to reconstruct Room from backup input.
- Keep the SQLCipher database and vault-content keys independent.
- Zero temporary key arrays and retain passphrases as `CharArray`.
- Never put keys, passphrases, attachment content, or vault payloads in saved
  instance state.
- Never log private content, filenames, account details, Drive identifiers,
  ciphertext, keys, or encryption metadata.
- Require a non-destructive migration, exported Room schema, prior-version
  fixture, restart verification, and protected-workspace run for every database
  version.
- Require old-format fixtures and independent golden-vector review for every
  crypto-format change.
- Put new copy in resources and retain UK spelling, day–month dates, 24-hour
  times, localisation readiness, RTL-safe layout, 48 dp actions, and
  non-colour status cues.
- Update the active architecture, design, threat model, README, handoff, and
  programme contracts together whenever responsibilities change.

## System architecture

```text
Compose feature UI
    │ typed callbacks
    ▼
app ViewModels / coordinators
    │ DomainCommand
    ▼
VaultRepository
    ├── SQLCipher Room records ─────────── sole live authority
    ├── atomic BackupJournal entry
    └── immutable WorkspaceSnapshot

BackupCoordinator
    ├── reads consistent local snapshots and journal generations
    ├── encrypts through AuthenticatedCloudObjectCodec
    └── writes BackupObjectStore

PortableBackupPublisher
    └── atomically publishes one eligible encrypted package

AttachmentBlobCoordinator
    ├── streams bounded chunks through temporary cache
    ├── encrypts through AuthenticatedCloudObjectCodec
    └── writes AttachmentBlobStore

RecoveryCoordinator
    ├── stages and verifies a replacement SQLCipher vault
    ├── claims the next writer epoch
    └── atomically activates the staged vault
```

Normal provider flows have no structured cloud-to-Room path.
`RecoveryCoordinator` is the only boundary allowed to reconstruct Room from a
backup or restored portable package.

## Data and service boundaries

### Room and `BackupJournal`

Room owns all live structured data: tasks, projects, workflows, milestones,
reminders, templates, notes, activity, time entries, settings, attachment
metadata, and tombstones. `VaultRepository` writes a record mutation and its
backup-journal entry in one transaction. The journal proves local-generation
coverage; it is not a remote merge log.

### `BackupCoordinator`

`BackupCoordinator` creates bounded complete snapshots and later journal
segments, uploads them through a provider-independent object store, downloads
and authenticates published bytes, advances checkpoints only after full
verification, and preserves current/previous recovery bases. Backup failure
does not block local editing.

### `PortableBackupPublisher`

`PortableBackupPublisher` atomically replaces one encrypted package containing
a complete structured snapshot, recovery envelope, format metadata,
attachment metadata, and opaque blob references. It excludes raw Room,
WAL/SHM, preferences, keys, credentials, cache, and attachment bytes and
withdraws packages above 24 MiB.

### `AttachmentBlobCoordinator`

`AttachmentBlobCoordinator` owns provisional upload sessions, bounded chunk
transfer, verification, temporary cache, open/share cleanup, retention, and
remote garbage collection. `VaultRepository` remains the attachment-metadata
mutation boundary. Attachment failure cannot disable task editing.

### `RecoveryCoordinator`

`RecoveryCoordinator` authenticates a selected backup or portable package,
constructs a new staged SQLCipher vault under a fresh database key, verifies
relations and references, creates a new local wrapper for the existing
vault-content key, claims the next writer epoch where applicable, and activates
only after every gate passes. Failure leaves the current installation
unchanged.

## Product surfaces

The five top-level destinations remain unchanged:

- Home retains daily focus and compact Insights. It may show backup attention
  only after backup is configured and is blocked or meaningfully overdue.
- Tasks owns canonical task editing, activity, notes, cloud attachments, and
  time history.
- Projects owns project context and filtered Insights.
- Schedule remains a read-only Room snapshot projection and offers explicit
  one-way calendar insertion.
- More owns Insights, Templates, Archive, Bin, Settings, and **Backup &
  recovery**.

**Backup & recovery** contains independent encrypted app backup, Android backup
package, cloud attachments, and active-device sections. User-facing
terminology is backup, cloud attachment, restore, and active device. Backup
history and attachment blobs have separate destructive actions. Neither
claims to delete Android's separately managed backup dataset.

All charts have equivalent ordered tables or text summaries. Meaning never
depends on colour. Restored routes, selections, filters, scroll positions,
drafts, and open sheets retain the existing layered restoration contract.

## Failure, ownership, and privacy model

- Offline, authentication, quota, or provider failure leaves local structured
  editing available.
- A backup checkpoint advances only after complete upload, download, checksum,
  authentication, decode, identity, and generation checks.
- Current and previous verified snapshot bases are retained with the journal
  segments each needs.
- Backup and attachment object namespaces, retention, status, and destructive
  operations remain separate.
- A monotonically increasing writer epoch and conditional control-manifest
  update enforce one active writer.
- A previous writer cannot publish backup or attachment mutations after
  takeover. Divergent local work is never merged automatically.
- Attachment intake is capped at 100 MiB and uses bounded 4 MiB chunks.
- Recovery passphrases require at least 12 characters, confirmation, and
  strength guidance; they may be generated locally as a multi-word phrase.
- Encrypted import validates in an isolated staging vault and activates only
  after confirmation. Plaintext CSV is export-only and warns on every export.
- App lock uses Android biometric or device credential as an access gate, not
  a replacement encryption scheme.

## Six-stage programme

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

Stage 1 preserves accepted Insights and frame work while completing the
authenticated provider-independent codec. Stage 2 establishes local backup and
the strictly whitelisted portable package before any provider recovery claim.
Stage 3 adds app-managed Drive backup and takeover. Stage 4 freezes metadata
and cloud attachment lifecycle. Stage 5 rebases the remaining features on that
schema. Stage 6 qualifies the complete product.

## Acceptance matrix

| Area | Required evidence |
|---|---|
| Local authority | Accountless/offline CRUD, reminders, timers, search, Insights, migrations, atomic record/journal writes, and proof that normal provider paths cannot write Room |
| Authenticated objects | Independent golden vectors, full identity-bound AEAD, checksum-before-decrypt, bounded decoders, wrong-key/passphrase, tamper, truncation, future-reader, and zeroisation cases |
| App backup | Scheduling policy, pagination, retry, quota, current/previous snapshot recovery, corrupt/missing segment handling, retention, and independent cloud deletion |
| Android backup | Manifest/rule audit on supported APIs, 24 MiB boundary, atomic replacement/withdrawal, restore round-trip, and proof that databases, keys, credentials, cache, and blobs are excluded |
| Active device | Conditional epoch acquisition, stale-writer rejection, offline prior-device reconnect, missing control record, explicit takeover, and divergent-work preservation |
| Attachments | Hostile input, 100 MiB limit, chunk interruption, cache eviction, open/share cleanup, unavailable state, delete/Undo, retained-backup references, and garbage collection |
| Recovery | Reinstall, new device, Keystore loss, portable fallback, damaged primary, failed takeover, staging rollback, and no activation before full verification |
| Product quality | 100/130/200% text, keyboard, TalkBack, Switch Access, compact/foldable/expanded layouts, screenshots, performance, privacy, signing, Play testing, and rollout |

## Production delivery

The Play path is internal testing, required closed testing,
production-access approval where applicable, globally available open testing,
and global production publication. The first production release is published
globally after those gates because percentage staging is unavailable for a new
app. Later updates progress through 5%, 20%, 50%, and 100% with explicit
observation and halt gates.

Drive requests only `drive.appdata`. OAuth production branding, signing
fingerprints, privacy policy, Data Safety, app-content declarations, Play App
Signing, upload-key custody, and verified developer details remain release
gates.

## Definition of production complete

- Structured data remains locally authoritative and fully usable offline.
- Verified app-managed backup restores after reinstall, new-device setup, and
  Keystore loss.
- Android Auto Backup contains only the eligible portable encrypted package.
- Cloud attachment bytes use bounded working storage and do not become durable
  local authority.
- Backup history and attachment blobs can be deleted independently.
- Recovery takeover cannot create a second writer for the same vault identity.
- Existing local data survives every transition and migration.
- Accessibility, responsive, performance, privacy, security, and store gates
  pass with no critical/high open issue.
- Internal, closed, and open testing complete before the initial global
  production release; later staged-update halt procedures are rehearsed.
