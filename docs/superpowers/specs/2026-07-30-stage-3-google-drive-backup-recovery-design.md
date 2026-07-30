# Stage 3 Google Drive Backup and Recovery Design

**Date:** 30 July 2026
**Status:** Approved in section-by-section brainstorming; written-spec review
pending
**Scope:** One-account Google Drive app-data authorization, encrypted
app-managed structured backup, conditional single-writer publication,
retention, background scheduling, staged recovery, writer takeover, recovery
passphrase change, and backup-management UI

## Decision

Stage 3 will add a dependable app-managed encrypted backup in one explicitly
authorized Google Drive account. It will use only the hidden
`drive.appdata` space and will retain encrypted Room as the sole live
structured-data authority.

The selected concurrency design is one mutable control file per random cloud
lineage plus immutable snapshot and operation-segment objects. Every mutation
of the control file requires a strong conditional provider write against the
exact revision previously read. The control file contains a monotonically
increasing writer epoch and one opaque active-device identity.

A remote backup generation becomes authoritative only when:

1. all required immutable objects have been uploaded;
2. every uploaded object has been downloaded and authenticated;
3. the successor control manifest has been conditionally published; and
4. that published control manifest has been downloaded and authenticated.

Recovery is the only structured cloud-to-Room path. It reconstructs and
validates a separate SQLCipher database, claims the next writer epoch when
retaining a cloud lineage, and atomically activates the staged database.
Ordinary operation never synchronizes, merges, or applies remote records to
the live repository.

The user approved conditional control publication over:

- append-only competing writer claims, which can admit temporary
  split-brain; and
- an external coordination server, which would expand the approved
  serverless scope.

If Google Drive cannot prove the required conditional-write semantics in a
credentialed capability test, Stage 3 stops. It will not substitute
last-writer-wins publication.

## Relationship to approved direction

This design is the focused Stage 3 child of
[Local Authority, Cloud Attachments, and Backup Direction Design](2026-07-28-local-authority-cloud-attachments-backup-design.md).
That parent remains authoritative:

- encrypted Room is the sole live structured-data authority;
- structured records are not synchronized or merged between devices;
- a backed-up vault has one active writer;
- app-managed encrypted backup is the primary recovery path;
- Android Auto Backup is a separate supplementary transport;
- only `RecoveryCoordinator` may reconstruct Room from backup data; and
- cloud attachment bytes use a separate future service and lifecycle.

[Stage 2 Local Backup and Android Auto Backup Design](2026-07-28-stage-2-local-backup-android-auto-backup-design.md)
implemented the local backup journal, verified snapshots and operation
segments, the recovery envelope, and one portable Android package. Stage 3
reuses those formats and policies without weakening their bounds.

The user's scope clarification defers Google-account migration until Stage 4
so account migration and attachment-blob migration can be designed and
delivered as one lifecycle. Stage 3 can authorize, disconnect, and
re-authorize one account, but cannot migrate a live lineage between accounts.

## Goals

- Publish verified encrypted structured backups to one Google Drive account.
- Keep local editing fully useful without an account or network connection.
- Prevent a stale or second writer from overwriting an active backup lineage.
- Recover after reinstall, new-device setup, or loss of a local Keystore
  wrapper.
- Keep Android-restored portable packages inert until explicit verified
  recovery.
- Verify uploads and downloads independently of provider metadata.
- Retain a current and previous usable recovery base.
- Make publication, recovery, takeover, deletion, and passphrase change
  crash-resumable and fail closed.
- Report bounded, truthful backup facts without implying synchronization.
- Preserve the protected current database and every legacy outbox row through
  an additive migration.

## Non-goals

- Structured-data synchronization, polling, or background remote-to-Room
  application.
- Record-level merge, conflict resolution, or collaboration.
- Two simultaneously authorized writers.
- Drive-primary storage.
- A proprietary coordination server.
- Google-account migration.
- Attachment selection, capture, upload, download, cache, or blob retention.
- A combined backup-and-attachment object-store interface.
- Claiming that Android uploaded, retained, or deleted the portable package.
- Persisting a Google email address, profile, OAuth token, refresh token,
  ID token, or server authorization code.
- Provider fallback that weakens conditional publication.

## Existing foundation

The implementation already provides:

- SQLCipher Room v6 as the live authority;
- `VaultRepository` and mutation-bearing `DomainCommand` entry points;
- atomic user mutation and `backup_journal` generation allocation;
- strict structured snapshot and operation-segment codecs;
- `AuthenticatedCloudObjectCodec` with canonical associated data;
- local current/previous snapshot retention and verified local checkpoints;
- a Keystore-wrapped vault-content key;
- a passphrase-wrapped recovery envelope for the same content key;
- one strict, bounded, authenticated portable package; and
- inert Android-restored package intake.

The current application eagerly creates `LocalVaultRuntime` and opens the
fixed `open_tasks.db`. Stage 3 must introduce a runtime gate so an absent or
unreadable vault can enter recovery without first creating or replacing a
database.

## Provider qualification gate

Google Drive documents `appDataFolder`, pre-generated file IDs, resumable
uploads, a monotonically increasing JSON `version`, and file content updates.
Its current public Drive v3 documentation does not state a sufficiently clear
contract for the HTTP conditional-update behavior on which writer safety
depends.

Before feature implementation, a credentialed test account must prove all of
the following through the same HTTP stack intended for production:

1. `files.generateIds` returns IDs usable for files created in
   `appDataFolder`.
2. Creating the pre-generated control ID once succeeds.
3. Attempting to create the same control ID again cannot create a duplicate or
   silently replace the first file.
4. Reading the control returns a strong opaque HTTP revision token.
5. Updating with the current revision token succeeds.
6. Updating with the stale predecessor token fails without changing content.
7. A missing control is distinguishable from an authorization or transient
   failure.
8. Successful create and update content is immediately downloadable and
   byte-identical.
9. Returned content can be authenticated by the application after upload and
   update.

The Drive JSON `version` field may be recorded as an observational diagnostic
but is not the compare-and-swap authority. The strong provider revision is
kept opaque, encrypted at rest, and never logged.

If any required property is absent or inconsistent across repeated trials,
Stage 3 remains unimplemented until a safe provider mechanism is selected in a
new approved design.

## Architecture

```text
DomainCommand
    |
    v
VaultRepository
    |
    +-- atomic Room mutation + backup journal generation
    |
    v
Stage 2 BackupCoordinator
    |
    +-- verified local snapshot / operation segments
    |
    v
RemoteBackupCoordinator
    |
    +-- re-authenticate under CloudLineageId
    +-- upload immutable candidates
    +-- download and verify candidates
    +-- compare-and-swap control manifest
    +-- download and verify published control
    |
    v
Google Drive appDataFolder

Google Drive or portable package
    |
    v
RecoveryCoordinator
    |
    +-- authenticate source
    +-- reconstruct inactive SQLCipher vault
    +-- validate repository invariants
    +-- claim writer epoch when required
    +-- atomically activate vault slot
```

### `VaultRuntimeManager`

Owns the process-level state:

- healthy active vault;
- no local vault;
- unreadable existing vault;
- recovery in progress;
- staged vault ready for activation; and
- active repository.

It replaces unconditional eager Room creation. It never replaces a missing
Keystore key for an existing envelope.

### `DriveAuthorizationManager`

Owns foreground account selection and consent, non-interactive authorization
attempts, token-cache clearing, authorization revocation, and the
account-binding check. It does not persist tokens or profile data.

### `BackupObjectStore`

Remains provider-neutral at the coordinator boundary. Its Drive
implementation owns:

- immutable-object creation and readback;
- control discovery and initial creation;
- control reads with opaque provider revisions;
- conditional control replacement;
- bounded resumable transfer; and
- permanent object deletion.

It does not choose retention, advance checkpoints, or decide writer
ownership.

### `RemoteBackupCoordinator`

Serializes all cloud-backup work for one configured lineage. It consumes
Stage 2 verified local generations, prepares lineage-bound remote frames,
publishes the control manifest last, persists bounded status, and prunes only
after authoritative publication.

### `RecoveryCoordinator`

Owns recovery-source validation, staged reconstruction, repository
verification, writer takeover, repository quiescence, atomic activation, and
pre-activation rollback. It is the only backup-to-Room structured-data path.

### `BackupWorkScheduler`

Maps local pending generations and periodic policy to unique WorkManager
requests. It has no alternate publication logic; manual and automatic backup
use the same `RemoteBackupCoordinator`.

### Product UI

The Backup & recovery UI is stateless and depends on domain contracts. It does
not own Hilt composition, provider clients, tokens, passphrases after
submission, or database activation.

## Identity model

Each configured app-managed backup receives a cryptographically random
`CloudLineageId`. It is separate from the structured payload's existing
`VaultId`.

The separate identity:

- avoids a destructive rewrite of the existing local vault identity;
- gives every independently created backup lineage a unique writer namespace;
- permits a stale device to preserve divergent local work in a new lineage;
  and
- lets the manifest bind a remote lineage to one source payload identity.

`CloudLineageId` is the remote writer authority. `VaultId` remains a strict
payload-consistency field and is never treated as a provider account or
concurrency token.

Each installation participating in a lineage also receives a random opaque
`CloudDeviceId`. Device IDs and lineage IDs are never user-facing account
identifiers and are never logged.

## Drive namespace

Stage 3 uses a flat `appDataFolder` namespace:

- one mutable control file per cloud lineage;
- immutable snapshot objects; and
- immutable operation-segment objects.

File names are constant non-private role names. Bounded app properties may
identify the application format and object role, but contain no task text,
workspace name, account data, or encryption secret. The authenticated object
contents carry the authoritative identity.

Control files are discovered with `spaces=appDataFolder`. A pre-generated
Drive file ID is persisted before initial creation so process death cannot
turn a retry into an accidental second control.

Drive IDs required after initial discovery are persisted only in SQLCipher or
the bounded encrypted recovery registry. They are never placed in WorkManager
names, analytics, exception messages, or logs.

## Control-file format

One control file has two logical sections.

### Public bootstrap

The strict bounded bootstrap contains only recovery necessities:

- control-format family and version;
- opaque `CloudLineageId`;
- creation time;
- recovery-envelope format and KDF parameters;
- passphrase-wrapped vault-content key;
- encrypted-manifest frame length; and
- encrypted-manifest checksum.

The bootstrap is not secret. It exposes neither the passphrase nor the
unwrapped content key. KDF bounds are validated before allocation or key
derivation.

### Authenticated manifest

After the recovery envelope unlocks the content key, the encrypted manifest
authenticates:

- the exact bootstrap-envelope digest;
- cloud lineage and source `VaultId`;
- control-format and object-format versions;
- writer epoch and active opaque device ID;
- control state, including active or deleting;
- publication time and published local generation;
- current and previous base snapshots;
- every segment needed after either retained base;
- each object's logical ID, Drive ID, expected byte length, checksum, family,
  and generation range; and
- recovery-credential generation.

Strict canonical encoding, unknown-version rejection, exact identity
agreement, collection caps, and length-before-allocation rules apply.

### Conditional control API

The storage boundary exposes:

```text
readControl(id)
    -> missing | bytes + opaqueProviderRevision

createInitialControl(preGeneratedId, bytes)
    -> created | alreadyExists | failed

compareAndSwapControl(id, expectedRevision, bytes)
    -> applied(newRevision) | conflict | missing | failed
```

No coordinator operation may express an unconditional control replacement.

## Remote object format

Stage 2 local frames are verified inputs, not bytes copied blindly to Drive.
Their associated data binds the existing local `VaultId`.

For remote publication, the coordinator:

1. decrypts and validates the selected local frame with the vault-content key;
2. verifies source generation, family, identity, and decoded payload;
3. encodes a new remote frame whose `CloudHeaderIdentity.vaultId` is the
   `CloudLineageId`;
4. binds source `VaultId` and generation inside authenticated payload or
   manifest agreement; and
5. clears owned plaintext and temporary key buffers at their boundaries.

This prevents a remote object from being moved between cloud lineages while
still proving that it represents the intended local payload.

The existing bounds remain authoritative:

- snapshot plaintext: at most `64 MiB - 33 bytes` (`67_108_831`);
- operation-segment plaintext: at most `16 MiB - 33 bytes`
  (`16_777_183`);
- recovery envelope: at most 16 KiB; and
- portable package: at most 24 MiB.

Checksums detect transfer corruption. AEAD authentication, not checksums,
provides integrity and identity.

## Initial connection

Initial connection is an explicit foreground flow:

1. Explain hidden app backup, the one-active-device rule, recovery
   passphrase, and non-synchronizing behavior.
2. Create or confirm the existing recovery passphrase and envelope.
3. Request only the Google Drive app-data authorization scope.
4. Let the user explicitly select the account and complete consent.
5. Establish the account-binding digest.
6. Discover existing control files before creating anything.
7. If the live local vault is healthy, offer existing controls only through
   Restore; never attach or import them into the running repository.
8. Generate and durably record a new `CloudLineageId`, `CloudDeviceId`, and
   pre-generated control ID.
9. Request and verify a complete local Stage 2 snapshot.
10. Re-authenticate, upload, download, and verify the initial remote snapshot.
11. Create the epoch-one control with this device active.
12. Download and authenticate the created control.
13. Mark encrypted app backup enabled only after all preceding work succeeds.

Failure before the final step leaves no enabled claim. A created but
unreferenced immutable object is an orphan, not a backup.

## Account authorization and binding

Stage 3 requests only:

`https://www.googleapis.com/auth/drive.appdata`

It requests no profile, email, ID token, server authorization code, or
offline server access.

Google Play services may satisfy an authorization request non-interactively or
return a `PendingIntent` for account selection or consent. Background work
never launches that UI. It records an action-required state for the next
foreground session.

After token acquisition, the application calls:

`about.get(fields=user(permissionId))`

The raw opaque permission ID is reduced immediately to a keyed digest using a
per-install secret. Only that digest is stored in SQLCipher. Every later
authorized Drive session retrieves the current permission ID, recomputes the
digest, and compares it before listing, reading, or mutating backup files.

An account mismatch fails before Drive backup access and requires explicit
foreground account selection. The app does not silently create a second
lineage in an unexpected account. Reconnection of an existing local lineage
must use its originally bound account. Stage 3 offers no `Use this account
instead` action; changing the configured account remains part of the deferred
Stage 4 migration lifecycle.

The digest key is installation-local and Keystore-protected. The digest is not
serialized into structured backups. A recovered installation establishes a
new local digest only after the user explicitly authorizes the account from
which it discovered and authenticated the selected lineage.

Access tokens stay in memory. They are not written to Room, preferences,
files, WorkManager data, saved-instance state, exceptions, or logs. Invalid
tokens are cleared through the authorization client before retry.

## Normal publication

Only one remote run per lineage executes in a process.

1. Obtain a non-interactive authorization result.
2. Verify the account-binding digest.
3. Read and authenticate the current control and retain its provider
   revision.
4. Require active control state, the expected writer epoch, and this device's
   identity.
5. Ask the Stage 2 coordinator for a locally verified generation.
6. Determine the minimal successor inventory under retention policy.
7. Prepare lineage-bound immutable candidates.
8. Upload each candidate using deterministic operation state.
9. Download every candidate into backup-excluded staging.
10. Verify length, checksum, AEAD, logical identity, source identity,
    generation, and decoded payload agreement.
11. Build the successor authenticated control manifest.
12. Compare-and-swap the control against the revision read in step 3.
13. Download and authenticate the published control.
14. Only then persist the remote verified generation and time.
15. Prune objects proven absent from the authoritative successor.

Upload completion alone never advances the remote checkpoint.

### Conflict handling

After a conditional conflict, the coordinator rereads and authenticates the
control:

- a higher epoch or different active device becomes `OwnershipLost`;
- a newer publication by this same device may coalesce the requested
  generation as already completed;
- an expected same-device retry resumes from its durable operation phase; and
- an unexplained same-epoch state fails closed.

The coordinator never resolves a conflict by unconditional update.

### Transfer strategy

Small objects use the simplest Drive upload method that preserves exact bytes.
Objects above the documented small-upload boundary use resumable upload.
Resumable chunks use Drive's required 256 KiB multiple except for the final
chunk.

The resumable-session URI is treated as a credential. It may exist only in
SQLCipher during an active operation and is cleared after completion,
expiration, or bounded abandonment.

Every download goes to private backup-excluded staging before strict parsing.
No unverified remote bytes become a local backup object or recovery source.

## Scheduling and retry

Automatic backup policy is:

- accepted local mutations enqueue unique one-time backup work with a
  15-minute debounce;
- unique periodic work checks for pending generations every 24 hours;
- automatic work requires network connectivity, battery not low, and storage
  not low;
- automatic work does not require unmetered networking in v1;
- `Back up now` calls the same coordinator immediately;
- passphrase change, recovery, takeover, and deletion use their dedicated
  serialized operation paths; and
- retryable failures use bounded exponential backoff with provider guidance
  where available.

WorkManager execution is inexact by design. Product copy never promises an
exact upload time.

Background authorization that needs resolution stops successfully from
WorkManager's perspective after persisting `NeedsReauthorization`; it does not
spin or manufacture a generic network retry.

Work names contain only constant application-owned names or a keyed bounded
digest. They never contain raw lineage, device, account, or Drive identifiers.

Local editing and Stage 2 local backup remain available while remote work is
offline, delayed, quota-blocked, unauthorized, incompatible, or owned by
another device.

## Retention and garbage collection

The authoritative control retains:

- the current verified base snapshot;
- the previous verified base snapshot; and
- all segments needed to reconstruct the latest generation from either base.

Pruning begins only after the successor control was conditionally published
and authenticated by readback.

Before deleting an immutable object, the coordinator:

1. proves the object is absent from the authenticated successor;
2. rereads the control or otherwise verifies the expected current provider
   revision;
3. confirms the same writer epoch and active device; and
4. deletes only an object authenticated as belonging to the lineage.

A successor control never introduces an object absent from its predecessor
unless that object was freshly uploaded and readback-verified. Therefore, a
takeover built from the predecessor cannot begin depending on an object
already proven prunable from the successor.

Interrupted attempts may leave immutable orphans. Orphan cleanup never guesses
from file names. It requires bounded role metadata, authenticated lineage
identity, minimum age, absence from the current authoritative manifest, and
current writer ownership.

## Backup status

The internal status model preserves bounded machine-readable detail. The UI
maps it to:

- Off;
- Preparing;
- Backing up;
- Backed up;
- Waiting to retry;
- Needs re-authorization;
- Wrong Google account;
- This device is no longer active;
- Google Drive storage unavailable;
- Backup damaged or incompatible; and
- Deleting backup history.

`Backed up` time and generation are application facts recorded only after
authenticated control readback. They are not Drive upload claims and do not
describe Android Auto Backup.

Provider response bodies, file names, IDs, account values, and remote
encryption metadata are not persisted as user-visible messages. Errors map to
stable categories with local explanatory copy.

Home has no sync-health indicator. It may show a compact backup-attention
notice only when encrypted app backup is configured and either blocked or
meaningfully overdue. The overdue duration is an injected product policy
selected in the implementation plan, not a wire-format field.

## Disconnect and destructive operations

### Disconnect Google Drive

Disconnect:

1. cancels unique backup work;
2. quiesces the remote coordinator;
3. clears any cached token;
4. revokes the application's authorization grant;
5. marks the encrypted local lineage binding dormant; and
6. sends no Drive object-deletion request.

The dormant record prevents a later reconnect from silently creating a
replacement for a previously known lineage.

The UI states the precise limitation: Open Tasks does not request file
deletion during disconnect, but Google allows users to delete the hidden
app-data folder manually and deletes it when the Drive app is uninstalled from
My Drive. Provider retention is not guaranteed by the app.

### Delete backup history

Deletion is separate from disconnect and requires:

- fresh foreground authorization to the bound account;
- recovery-passphrase confirmation;
- explicit naming of what remains; and
- current writer ownership.

The coordinator:

1. conditionally changes the control state to `DELETING`;
2. authenticates the published deleting control;
3. permanently deletes the lineage's immutable objects;
4. deletes the control file last; and
5. records a local deleted tombstone and disables backup.

Recovery and takeover cannot proceed from `DELETING`. A partially completed
deletion resumes instead of re-enabling publication.

Files in `appDataFolder` cannot be moved to trash, so the action is presented
as permanent. It does not delete or claim to delete Google's separate Android
backup dataset.

A device that has already lost writer ownership must first complete an
explicit takeover before deleting that lineage. It cannot use deletion as an
epoch bypass.

A previously observed missing or replaced control is never recreated
automatically. An explicit delete operation may clean authenticated known
orphans after confirming the bound account, but it still cannot create a new
control.

## Recovery entry and source selection

Recovery entry points are:

- first launch with no local vault;
- new-device setup;
- an existing vault whose Keystore wrapper cannot be opened;
- an inert Android-restored portable package; and
- an explicit restore action.

The runtime does not create an empty Room database before the user chooses
recovery or `Start empty`.

Drive controls are discovered only after explicit authorization. Multiple
lineages are shown with bounded non-private facts such as creation time. User
content is not exposed before the passphrase authenticates a manifest.

For one selected payload identity, the recovery coordinator compares valid
authenticated generations. A current or newer app-managed Drive generation is
preferred. A newer valid portable package may be selected explicitly. The
portable package remains a fallback when Drive is absent, unavailable, or
damaged.

## Staged recovery

Recovery performs:

1. strict bootstrap, version, length, KDF, and collection-bound validation;
2. in-memory passphrase derivation and content-key unwrap;
3. authenticated manifest and inventory validation;
4. authenticated download and decode of the current base and required later
   segments;
5. fallback to the previous retained base and its required segments if the
   current base is damaged;
6. creation of a separate SQLCipher database with a new random database key;
7. transactionally importing the structured snapshot and replaying ordered
   operation segments through a recovery-only importer;
8. validation of counts, IDs, ownership, relations, tombstones, attachment
   references, foreign keys, SQLCipher integrity, and public repository
   projections;
9. reopening the staged database after close;
10. creation of a new Android Keystore wrapper for the recovered
    vault-content key;
11. writer takeover when retaining the cloud lineage; and
12. atomic active-slot replacement.

The recovery importer can write only to a new inactive staging database. It
cannot target the active database, use the normal cloud path, or merge
records.

Remote configuration, account-binding digests, old device IDs, provider
revisions, transfer sessions, remote-operation rows, and the source device's
local backup checkpoint are not structured backup records and are never
imported. Recovery creates fresh installation-local operational state from the
authenticated control and current authorization result.

The staged database adopts the authenticated recovered generation so the next
accepted `DomainCommand` allocates its successor. Its local backup journal
starts without source-device operational rows and requires a fresh complete
Stage 2 baseline after activation. Until that new local baseline is verified,
the already authenticated Drive inventory remains the recovery authority and
no new remote generation is published.

Wrong passphrase, weakened KDF metadata, future format, missing generation,
damaged AEAD, insufficient storage, invariant failure, or failed takeover
leaves the active installation unchanged.

An explicit restore over a healthy vault is replacement, not merge. The
existing repository remains active while staging is built, is quiesced before
final confirmation, and remains as the bounded rollback slot until the
restored vault successfully reopens.

## Writer takeover

Recovery retaining an existing cloud lineage:

1. reads and authenticates the latest control;
2. stages and validates the exact referenced generation;
3. rereads the control before claiming ownership;
4. updates or rebuilds staging if the control advanced;
5. obtains explicit takeover confirmation;
6. prepares the staged local `CloudDeviceId` and Keystore wrappers;
7. conditionally publishes `writerEpoch + 1` with that device active and the
   exact recovered inventory;
8. downloads and authenticates the claimed control; and
9. activates the staged vault.

If compare-and-swap conflicts, the recovery coordinator rereads, validates,
and either refreshes recovery or stops. It never claims against an unverified
revision.

A crash after claim but before activation resumes the already claimed staged
recovery. It does not allocate a second epoch. Required staged state and its
database key are already durably wrapped; no passphrase or raw content key is
persisted.

The former device retains its local Room database. When it next observes the
higher epoch, it blocks cloud publication and deletion but does not block
local editing.

The former device can:

- preserve divergent local work by creating a new `CloudLineageId`; or
- explicitly take ownership back and publish its local state as replacement.

The second choice warns that no merge occurs and work existing only on the
other active device may no longer be represented by future backups.

If a known control disappeared, the prior device treats the lineage as lost
and never recreates it automatically. Restoring a portable package after
permanent cloud deletion creates a new cloud lineage.

## Recovery-passphrase change

Passphrase change rewraps the same vault-content key. It never re-encrypts
backup objects, rotates the SQLCipher database key into content encryption, or
uses the database key as backup key material.

The operation is crash-journaled:

1. verify the current passphrase against the active recovery envelope;
2. generate a pending envelope from the new passphrase;
3. persist only that envelope and bounded operation phase;
4. produce and readback-verify a new portable package;
5. when Drive backup is enabled, conditionally publish the new control
   envelope and recovery-credential generation;
6. download and authenticate the updated control;
7. promote the pending local envelope; and
8. clear the previous local envelope and temporary buffers.

The UI reports completion only after every applicable local and Drive
publication succeeds. A crash resumes from the durable phase. An operation
that already published remotely cannot be rolled back by restoring an older
control unconditionally.

Passphrase change is not retroactive revocation. An older Android dataset,
Drive revision, or externally copied portable package may retain an envelope
that the former passphrase can unwrap. Because the same content key is
rewrapped by policy, possession of such an old package and its former
passphrase can continue to expose content encrypted with that key. The UI
states this limitation and does not imply credential revocation.

## Persistence and migration

Room advances additively from v6 to v7.

### `remote_backup_config`

One row for each cloud lineage known to the local installation contains:

- cloud lineage and control identifiers;
- local opaque device identity;
- account-binding digest;
- observed and active writer epoch;
- encrypted opaque provider revision;
- lifecycle state;
- last remotely verified generation and time;
- pending local generation;
- bounded failure category;
- recovery-credential generation; and
- created and updated times.

At most one row is active for automatic backup. Dormant, ownership-lost, or
deleted rows remain as local safety history so reconnect cannot silently
recreate or overwrite a previously known lineage.

### `remote_backup_object`

Tracks:

- logical object identity and family;
- lineage and generation range;
- Drive file ID;
- expected length and checksum;
- upload/readback verification state;
- authoritative-reference state; and
- bounded orphan or pruning eligibility.

It is an operational cache. The authenticated control manifest remains the
remote recovery inventory authority.

### `remote_backup_operation`

Tracks crash-resumable:

- initial publication;
- ordinary publication;
- resumable upload;
- pruning;
- passphrase change;
- takeover;
- history deletion; and
- disconnect quiescence.

The row contains bounded phases and encrypted provider references, never a
passphrase, access token, raw derived key, or plaintext payload.

### Existing tables

`backup_state`, `backup_journal`, user tables, and legacy outbox rows are not
repurposed. Remote publication never advances the local verified checkpoint.

The v6-to-v7 migration creates new tables and indexes only. It exports a new
Room schema and has no destructive fallback.

### Recovery registry and vault slots

Recovery cannot rely on the active SQLCipher database being readable. A
minimal authenticated and encrypted `RecoveryRegistry` in
`noBackupFilesDir` contains only:

- opaque staging-slot identity;
- recovery operation ID and bounded phase;
- encrypted provider/control references required to resume;
- claimed epoch when applicable; and
- cleanup state.

It contains no token, passphrase, raw content key, or user record. Its key is
separate from the existing vault key. If the registry becomes unreadable, the
inactive staging slot is discarded and the active or unreadable vault remains
unchanged.

An atomic, backup-excluded marker selects the active database slot. Existing
installations initially point to the current `open_tasks.db` without renaming
or rewriting it. Only a verified recovery activation changes the marker.

Activation:

1. quiesces and closes the active repository;
2. checkpoints, closes, and reopens the staged database for verification;
3. durably records both the prior and staged opaque slots in the recovery
   registry;
4. writes and synchronizes a temporary active-slot marker;
5. atomically replaces and directory-synchronizes the live marker;
6. opens and verifies the selected staged slot through normal runtime
   construction; and
7. retires the previous slot and clears recovery state only after that open
   succeeds.

Process death before marker replacement leaves the prior slot active. Process
death after replacement resumes opening the staged slot. If the first
post-switch open fails, the registry permits an atomic rollback to the
unchanged prior slot rather than an in-place repair.

These operational files do not become an alternate authority for task data.
Room remains the only live structured-data authority.

## Module boundaries

- `core:model` owns cloud-backup IDs, status values, and bounded failure
  categories.
- `core:domain` owns authorization-independent coordinator, object-store,
  recovery, scheduling, and status contracts.
- `core:crypto` owns strict frame, control, and envelope cryptography with no
  provider or UI dependency.
- `core:data` owns Room persistence, backup coordination, staged import, and
  Drive object-store semantics over an injected transport.
- `app` owns Google authorization, the HTTP Drive transport, WorkManager
  adapters, process runtime composition, and Compose UI.

The provider transport accepts an access token only for a call's lifetime.
Feature modules remain stateless and Hilt-free. Core crypto and coordination
never depend on Compose.

Ordinary task writes continue through `DomainCommand` and
`VaultRepository`. The recovery importer is narrowly privileged only for a
new inactive database.

## Product surface and terminology

`More -> Backup & recovery` contains two independent Stage 3 cards.

### Encrypted app backup

Shows:

- connection and active-device state;
- last verified application time and generation;
- pending generation or bounded failure;
- `Back up now`;
- restore entry;
- recovery-passphrase change;
- disconnect; and
- delete backup history.

### Android backup package

Keeps the Stage 2 facts:

- Ready or unavailable;
- local production time;
- generation;
- package size; and
- platform-setting guidance.

The UI uses **backup**, **restore**, and **active device**. It does not use
**sync**, **cloud current across devices**, or **Drive-primary**.

## Failure containment

| Failure | Required result |
|---|---|
| Offline | Local editing continues; pending generation remains durable |
| Authorization resolution required | Background work stops; foreground re-authorization is offered |
| Wrong Google account | No Drive list, read, or mutation occurs |
| `401` or expired token | Token cache is cleared; bounded re-authorization state |
| Rate limit or transient `5xx` | Exponential retry without checkpoint advance |
| Quota or storage failure | Pending generation remains; no existing backup is pruned |
| Immutable upload interrupted | Durable bounded resume or later orphan cleanup |
| Candidate readback failure | Candidate is not published |
| Control conditional conflict | Reread; stale writer stops or same-device work coalesces |
| Known control missing or replaced | Ownership loss; never automatic recreation |
| Current base damaged | Recovery tries previous base and required segments |
| Missing required segment | Recovery fails without active-vault change |
| Wrong passphrase or AEAD failure | No plaintext exposure or activation |
| Staging invariant failure | Staging removed; active vault unchanged |
| Process death before activation | Resume bounded staging or discard it |
| Process death after takeover | Resume the already claimed staged vault |
| Partial history deletion | Remain deleting and resume; never republish |
| Conditional-write qualification failure | Stage 3 implementation stops |

## Security and privacy

### Protected

- Task and workspace content is encrypted before Drive receives it.
- Full frame identity and header metadata are bound as AEAD associated data.
- Recovery requires the recovery passphrase and authenticated inventory.
- A stolen Drive token is never persisted by the app.
- A wrong account is rejected before backup access.
- A stale writer cannot publish against a newer control revision.
- All passphrases remain `CharArray` at cryptographic boundaries.
- Owned passphrase, plaintext, derived-key, and temporary key buffers are
  cleared at ownership boundaries.

### Provider-visible metadata

Google can observe:

- that the application uses Drive;
- file counts, sizes, creation/update times, and access patterns;
- constant role names and bounded app properties;
- opaque lineages and object roles required for discovery; and
- public KDF parameters and wrapped recovery-envelope bytes.

Google does not receive task titles, notes, workspace names, plaintext
structured records, the recovery passphrase, or unwrapped content keys.

### Rollback limitation

An installation that has observed a lineage persists its maximum authenticated
epoch and generation and rejects a lower one. Conditional publication prevents
ordinary stale clients from overwriting a successor.

A fresh recovery installation has no independent external witness. If the
provider serves an older but internally authentic complete manifest, the new
installation cannot prove that a newer authentic revision once existed. The
threat model treats Drive as the availability and revision service, not as a
Byzantine transparency log. Solving fresh-client provider rollback would
require a separate witness or server and is outside Stage 3.

## Testing and qualification

### Unit and property tests

- Strict canonical control encoding and independent golden vectors.
- Header, envelope, manifest, object, collection, and KDF bounds.
- Unknown version, duplicate field, identity disagreement, and future format.
- Checksum corruption, AEAD tamper, associated-data swap, and wrong key.
- Initial control creation races and duplicate-create rejection.
- Conditional successor, stale revision, missing control, and same-epoch
  conflict.
- Writer epoch overflow and stale-device rejection.
- Publication idempotency at every durable operation phase.
- Resumable upload expiration, interruption, duplicate completion, and
  readback mismatch.
- Retention and pruning across publication and takeover interleavings.
- Orphan minimum age and authenticated lineage checks.
- Account-binding mismatch before any storage call.
- Bounded provider error mapping with no response-body persistence.
- Passphrase-change crash points and buffer clearing.
- Deleting-state resumption and control-last deletion.

### Room and recovery tests

- v6-to-v7 migration from the committed schema fixture.
- Preservation of every user record, backup row, and legacy outbox row.
- Exported v7 schema and schema-drift gate.
- No destructive fallback or unintended index/table rewrite.
- Complete snapshot import and ordered segment replay into staging.
- Counts, identities, ownership, relations, tombstones, and attachment
  references.
- Foreign-key and SQLCipher integrity.
- Current-base failure with previous-base recovery.
- Active-slot atomicity, reopen verification, and rollback.
- Process death immediately before and after active-marker replacement.
- Recovery excludes source-device account, transfer, operation, and local
  checkpoint rows and requires a fresh local baseline.
- Keystore loss never creates a replacement key for an existing envelope.
- Recovery registry loss cannot replace the active vault.

### WorkManager and UI tests

- Unique one-time debounce and unique periodic scheduling.
- Network, battery, and storage constraints.
- Exponential backoff and provider retry classification.
- Resolution-required work does not launch UI or loop.
- Manual backup and automatic backup share one coordinator.
- Process restoration never saves a passphrase, token, or private identifier.
- Compact, expanded, fold, font-scale, and accessibility behavior.
- Accurate independent encrypted-app-backup and Android-package copy.
- Home attention only for configured blocked or overdue backup.

### Credentialed and device qualification

Using disposable test data, two Google accounts, and two installations:

1. Run and record the provider qualification gate.
2. Connect an explicit account and publish epoch one.
3. Mutate locally and verify an incremental remote publication.
4. Interrupt and resume a large upload.
5. Recover on the second installation.
6. Take ownership and prove the first installation's stale write fails.
7. Preserve divergent old-device work in a new lineage.
8. Disconnect and reconnect without an application delete request.
9. Attempt authorization with the wrong account and prove zero backup access.
10. Change the recovery passphrase and recover with the new envelope.
11. Damage the current base and recover through the previous base.
12. Delete backup history and prove the control is deleted last.
13. Confirm the Android package remains independent and inert.

No protected local workspace is uninstalled, cleared, or destructively
rewritten for qualification.

### Repository gates

The implementation plan must retain:

```bash
git diff --check
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
./gradlew :app:assembleRelease --stacktrace
./gradlew connectedDebugAndroidTest --stacktrace
./scripts/check-schema-drift.sh
```

The plan may split gates at checkpoints, but final Stage 3 acceptance requires
the complete relevant repository suite, deterministic fixtures, privacy/log
checks, credentialed provider qualification, and device recovery/takeover
evidence.

## Implementation sequencing constraints

The implementation plan should sequence work so unsafe assumptions fail
early:

1. credentialed Drive conditional-write capability gate;
2. domain contracts, strict control codec, and deterministic fixtures;
3. additive Room v7 migration and process runtime gate;
4. authorization and account-binding boundary;
5. Drive object-store transport and verified immutable transfer;
6. remote coordinator, conditional publication, scheduling, and retention;
7. staged recovery and atomic vault activation;
8. takeover, disconnect, deletion, and passphrase change;
9. product UI and accessibility;
10. credentialed two-installation acceptance and full repository gates.

No source implementation begins until this written design is approved and a
separate implementation plan is written and approved.

## Acceptance criteria

- One explicitly selected Google account is authorized with only
  `drive.appdata`.
- No OAuth token, account email, profile, raw permission ID, or server
  credential is persisted.
- Existing lineages reconnect only to their bound account; Stage 3 cannot
  switch or migrate them to another account.
- A credentialed test proves duplicate-create prevention and stale conditional
  update rejection before implementation proceeds.
- A random cloud lineage has exactly one authoritative mutable control.
- Only the active epoch and device can publish, prune, or delete.
- Every immutable object and control update is authenticated by download
  before checkpoint advance.
- Current and previous usable recovery bases are retained.
- Local editing remains available through every cloud failure.
- WorkManager coalesces automatic work and never launches authorization UI.
- Disconnect sends no Drive deletion request.
- History deletion is explicit, passphrase-confirmed, permanent, and deletes
  the control last.
- Recovery reconstructs a new SQLCipher database and never imports into the
  live database.
- Recovery initializes fresh installation-local cloud state and verifies a new
  local baseline before publishing another remote generation.
- Recovery takeover conditionally claims the next epoch before activation.
- A stale prior device cannot overwrite the recovered lineage.
- Missing known controls are never recreated automatically.
- Passphrase change rewraps the same content key and truthfully discloses that
  old backup copies are not revoked.
- Room v6 data, Stage 2 backup state, and every legacy outbox row survive the
  additive v7 migration.
- Android Auto Backup remains a separate supplementary package and status.
- No Google-account migration, attachment transport, synchronization, merge,
  or second writer enters Stage 3.

## Official references

- [Store application-specific data in Drive](https://developers.google.com/workspace/drive/api/guides/appdata)
- [Google Play services AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)
- [AuthorizationResult](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationResult)
- [Drive `about.get`](https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get)
- [Drive `files.generateIds`](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/generateIds)
- [Drive upload methods and resumable uploads](https://developers.google.com/workspace/drive/api/guides/manage-uploads)
- [Drive `files.update`](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/update)
- [Drive files resource and observational `version`](https://developers.google.com/workspace/drive/api/reference/rest/v3/files)
- [Drive API error handling](https://developers.google.com/workspace/drive/api/guides/handle-errors)
- [Define WorkManager requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Manage unique WorkManager work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)
