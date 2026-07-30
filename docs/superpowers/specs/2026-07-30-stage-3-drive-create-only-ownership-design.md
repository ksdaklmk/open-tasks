# Stage 3 Drive Create-Only Ownership Design

**Date:** 30 July 2026
**Status:** Approved
**Scope:** Replacement concurrency, publication, retention, takeover, recovery,
credential rotation, cleanup, and deletion design for the Stage 3 encrypted
Google Drive backup

## Supersession and retained direction

This design supersedes the mutable-control-file and provider-revision portions
of
[Stage 3 Google Drive Backup and Recovery Design](2026-07-30-stage-3-google-drive-backup-recovery-design.md).
It also supersedes every task in the corresponding Stage 3 implementation plan
that depends on:

- an HTTP `ETag`;
- `If-Match`;
- Drive JSON `version` as concurrency authority;
- in-place control-file update; or
- a compare-and-swap control API.

Where this design and the earlier Stage 3 design disagree, this design
governs. The following approved direction remains unchanged:

- encrypted SQLCipher Room is the sole live structured-data authority;
- structured records are not synchronized or merged between devices;
- one explicitly selected Google account is authorized with only
  `drive.appdata`;
- snapshots and operation segments are immutable, encrypted, bounded, and
  independently authenticated after download;
- recovery reconstructs a separate staging database and is the only
  cloud-to-Room path;
- Android Auto Backup remains an independent supplementary package;
- local editing remains available through cloud failure; and
- Google-account migration and attachment-byte transport remain outside Stage
  3.

The parent
[Local Authority, Cloud Attachments, and Backup Direction Design](2026-07-28-local-authority-cloud-attachments-backup-design.md)
remains authoritative except for its assumption that Drive supplies
conditional mutable-control writes. Its one-active-device requirement is
preserved as one **authoritative** writer per cloud lineage.

## Provider finding that requires this design

The credentialed Drive qualification used the intended Android authorization
and HTTP stack against `appDataFolder`. Drive accepted a pre-generated control
file ID but supplied no strong HTTP revision on either:

1. the successful multipart-create response; or
2. one bounded metadata read of the created file.

The bounded result was
`TRANSPORT_CREATE_CONTROL_CONDITIONAL_UNAVAILABLE`. Therefore the previously
approved `ETag` compare-and-swap design cannot be implemented safely. The app
does not substitute last-writer-wins update or observational JSON `version`.

Drive still documents pre-generated IDs that can be supplied to create
requests in `appDataFolder`. This replacement design uses only immutable
create-by-ID as its candidate coordination primitive. Duplicate-create
rejection remains an unproven provider property until the new qualification
gate in this design passes.

## Decision

Stage 3 uses two separate immutable structures:

1. a short create-only **ownership chain** for setup, takeover, and terminal
   deletion; and
2. append-only authenticated **backup publications** within the current
   ownership epoch.

Every ownership claim reserves exactly one pre-generated Drive file ID for its
only successor. Competing takeover candidates create different authenticated
claims at that same successor ID. The provider's rejection of every losing
create, plus unchanged authenticated readback, selects one winner without
mutating any existing file.

Normal backups do not extend the ownership chain. The active device creates
immutable publications bound to the current ownership claim, epoch, and
device. The unique latest valid publication in that epoch is the authoritative
backup inventory.

A stale device that read ownership immediately before takeover may finish one
old-epoch upload. Such bytes:

- cannot overwrite an existing file;
- cannot become authoritative under the newer ownership tip;
- cannot delete or replace new-epoch objects;
- are ignored during recovery; and
- are eligible for bounded orphan cleanup.

This is one authoritative writer, not multi-device synchronization or
split-brain authority.

## Non-negotiable safety properties

1. Existing provider files are never mutated to publish control state.
2. A provider timestamp, filename, list order, ETag, or JSON version never
   decides ownership or publication authority.
3. Each ownership claim has at most one valid successor file ID.
4. A successor is trusted only after its bytes are downloaded and
   authenticated.
5. The current ownership chain tip is the sole epoch and device authority.
6. A publication is authoritative only under that exact tip.
7. Takeover never depends on payload objects owned by the former epoch.
8. Cleanup cannot delete objects owned by a newer or different device epoch.
9. Recovery never imports into or merges with the live Room database.
10. Any ambiguity in ownership or publication fails closed.

## Identity model

The existing random `CloudLineageId` remains the remote backup namespace. It
is distinct from the structured payload's `VaultId`.

Every installation that owns a lineage has a random opaque `CloudDeviceId`.
Every ownership transition increments a bounded unsigned writer epoch. Epoch
overflow fails closed and requires creation of a new lineage after explicit
user confirmation.

The following authenticated identities are distinct:

- `CloudLineageId`: remote lineage;
- `VaultId`: source structured vault;
- `CloudDeviceId`: owning installation;
- ownership-claim logical ID;
- publication logical ID;
- snapshot or segment logical ID; and
- provider file ID.

No account email, profile, token, task content, workspace name, or encryption
secret is used as an identity or provider-visible query property.

## Drive namespace

Stage 3 uses a flat `appDataFolder` namespace with these roles:

| Role | Mutability | Lifetime |
|---|---|---|
| Ownership root | Immutable | Until explicit lineage deletion |
| Ownership successor claim | Immutable | Until explicit lineage deletion |
| Backup publication | Immutable | Current, previous, or bounded candidate |
| Snapshot | Immutable | Retention and recovery policy |
| Operation segment | Immutable | Retention and recovery policy |
| Terminal ownership tombstone | Immutable | Permanent safety marker |

Constant non-private names and bounded app properties support discovery.
Provider-visible properties may contain only:

- application format family and version;
- opaque lineage identity;
- object role;
- bounded writer epoch;
- opaque owning-device identity where required for namespace cleanup; and
- public logical object identity.

The complete authenticated bytes repeat and bind every authoritative identity.
Provider metadata is an index, never proof.

Discovery and pagination are bounded:

- at most 64 ownership roots are accepted for one account;
- at most 1,024 ownership claims are followed for one lineage;
- at most 128 publication candidates are accepted for one epoch; and
- existing manifest inventory caps remain unchanged.

Exceeding a bound is a corrupt or unsupported remote state, not a reason to
truncate silently.

## Create-only transport boundary

The Drive storage boundary exposes only operations needed by immutable
objects:

```text
generateFileIds(count, role)
    -> generated IDs | failed

createFileIfAbsent(preGeneratedId, metadata, bytes)
    -> created | alreadyExists | ambiguous | failed

readFile(id, maximumBytes)
    -> missing | bounded bytes | failed

listFiles(query, pageToken, pageSize)
    -> bounded page | failed

deleteFile(id)
    -> deleted | missing | failed
```

`createFileIfAbsent` never treats an occupied ID as success. It reports
`alreadyExists`; when the request outcome itself is indeterminate, it reports
`ambiguous`. The caller then reads the exact ID and authenticates its bounded
bytes against the operation being resumed. Different authenticated bytes mean
ownership loss, a losing publication candidate, or corrupt state according to
the caller; they are never replaced.

There is no mutable-control operation, unconditional replacement, conditional
replacement, or provider-revision field.

## Ownership-chain format

### Public header

An ownership file has a strict bounded public header containing only:

- ownership format family and version;
- opaque lineage ID;
- logical claim ID;
- writer epoch;
- public state: `ACTIVE` or `TERMINATED`;
- object role;
- this claim's provider ID;
- the next reserved successor provider ID when the public state is `ACTIVE`;
  and
- encrypted claim-frame length and checksum.

It contains no recovery envelope. Recovery envelopes live in publication
bootstraps so obsolete ownership records do not themselves preserve a
passphrase wrapping.

### Authenticated ownership claim

After the vault-content key is obtained from a valid publication bootstrap,
the claim always authenticates:

- lineage;
- writer epoch;
- state: `ACTIVE` or terminal `TERMINATED`;
- predecessor provider ID, logical ID, and authenticated digest;
- this claim's provider ID and logical ID;
- the predecessor's reserved successor ID;
- format compatibility; and
- claim creation operation identity.

An `ACTIVE` claim additionally authenticates:

- source `VaultId`;
- the active `CloudDeviceId`;
- one newly pre-generated successor ID;
- the epoch baseline publication ID and digest; and
- recovery-credential generation.

A terminal `TERMINATED` claim additionally authenticates only a random
non-secret tombstone identity. It contains no active device, successor ID,
baseline, inventory, recovery credential, or content-key wrapping.

The epoch-one root has no predecessor. An `ACTIVE` claim has exactly one
successor slot. A `TERMINATED` claim has none and is the permanent deletion
tombstone.

Every successor must:

1. occupy the exact provider ID reserved by its predecessor;
2. name and digest that predecessor;
3. increment the epoch by exactly one;
4. name one active device or terminal deletion state; and
5. when `ACTIVE`, bind an already uploaded and authenticated epoch baseline;
   or
6. when `TERMINATED`, contain no successor or recoverable backup reference.

An occupied successor containing invalid, unrelated, or unauthenticated bytes
blocks the lineage. The app never skips to a different successor ID.

## Publication format

Each immutable publication has two logical sections.

### Recovery bootstrap

The strict public bootstrap contains:

- publication format family and version;
- opaque lineage ID and writer epoch;
- recovery-envelope format and bounded KDF parameters;
- passphrase-wrapped vault-content key;
- recovery-credential generation;
- encrypted publication-frame length; and
- encrypted publication-frame checksum.

KDF and length bounds are checked before allocation or derivation.

### Authenticated publication manifest

The encrypted manifest authenticates:

- exact recovery-bootstrap digest;
- lineage and source `VaultId`;
- writer epoch and active device ID;
- publication logical and provider IDs;
- publication sequence;
- predecessor publication ID and digest within this epoch, or epoch-baseline
  status;
- local generation and publication operation identity;
- current and fallback complete bases;
- every segment needed after either base;
- object provider IDs, logical IDs, families, lengths, checksums, and
  generation ranges;
- recovery-credential generation; and
- format compatibility.

An epoch-baseline publication is created before its ownership root or
successor claim. To avoid a circular digest dependency, it authenticates:

- the planned claim provider ID and logical ID;
- the predecessor ownership-claim ID and digest, or initial-lineage status;
- the proposed writer epoch and device ID; and
- publication sequence zero.

It does not claim that the planned ownership file already exists. The baseline
becomes authoritative only when the subsequently created ownership claim
authenticates that exact publication ID and digest.

A non-baseline publication instead authenticates the current ownership-claim
provider ID, logical ID, and digest. Its publication sequence is exactly one
greater than its retained predecessor. Its local generation is greater than
or equal to its predecessor's generation; passphrase rotation may advance the
publication sequence without advancing structured-data generation.

The manifest is self-contained for recovery inventory. Checksums detect
transfer corruption; AEAD authentication establishes identity and integrity.

## Resolving ownership and publication authority

To resolve ownership:

1. discover a bounded root candidate;
2. follow the bounded public-header successor IDs to a candidate public tip;
3. list bounded publication bootstraps for that exact public epoch and claim;
4. obtain a candidate content key from the supplied passphrase;
5. authenticate the root and every exact successor from the beginning;
6. stop on a missing successor; and
7. require the authenticated tip to equal the public candidate tip.

There is no search for a "newer" claim by timestamp or epoch alone. Only the
predecessor's reserved successor can extend the chain. Public successor IDs
are navigation hints whose exact values are authenticated after key unwrap.

To resolve the authoritative publication:

1. list bounded candidates for the exact lineage and tip epoch;
2. reject candidates bound to another ownership claim or device;
3. authenticate every retained candidate;
4. reject every duplicate publication sequence;
5. require one unique highest sequence;
6. require that publication's predecessor to be the retained sequence
   immediately before it, unless it is the epoch baseline;
7. require non-decreasing local generation across the retained pair; and
8. require current and previous retained publications to agree exactly about
   their IDs, digests, sequence, and inventory relationship.

A duplicate sequence, two children of one retained predecessor, missing
retained predecessor, sequence gap, unexplained generation regression, or
competing highest publication is `AMBIGUOUS_REMOTE_STATE` and fails closed.
Older non-retained candidates cannot outrank the retained pair and are
classified only as bounded orphan candidates.

## Initial connection

Initial setup remains an explicit foreground operation:

1. explain hidden encrypted backup, one-authoritative-device behavior,
   recovery passphrase, and non-synchronizing semantics;
2. create or confirm the local recovery envelope;
3. authorize only `drive.appdata`;
4. verify the bound-account digest;
5. discover existing ownership roots before creating anything;
6. generate a new lineage, device ID, root ID, root successor ID, publication
   ID, and two independent base-object IDs;
7. obtain and verify a complete Stage 2 local snapshot;
8. encode two independently identified ciphertext copies of that generation;
9. upload, download, authenticate, decode, and compare both copies;
10. create and read-authenticate the sequence-zero epoch-one baseline
    publication bound to the planned root identity;
11. create the epoch-one ownership root that binds that publication;
12. download and authenticate the root and re-resolve it as the tip; and
13. mark encrypted app backup enabled.

Failure before the final step leaves no enabled backup claim. Uploaded
candidates without a trusted root are bounded orphans.

## Normal publication

Only one remote coordinator for a lineage runs in an application process.
WorkManager and manual backup share that owner.

Normal publication performs:

1. obtain non-interactive authorization;
2. verify the account-binding digest;
3. resolve and authenticate the ownership tip;
4. require `ACTIVE`, this device, and the locally expected epoch;
5. obtain a verified local generation from the Stage 2 coordinator;
6. compute the minimal successor inventory under retention policy;
7. create epoch- and device-owned immutable candidate objects;
8. upload, download, authenticate, decode, and compare every candidate;
9. reread and authenticate the ownership tip;
10. stop if ownership changed;
11. create the immutable next publication at its durably recorded
    pre-generated ID;
12. download and authenticate the publication;
13. reread and authenticate the ownership tip again;
14. advance the local remote checkpoint only if the same claim still owns the
    lineage; and
15. run only namespace-safe bounded cleanup.

Upload or publication creation alone never advances the checkpoint.

If ownership changes after step 9, the old-epoch candidate remains
non-authoritative. If it changes after step 13, the completed old-epoch
publication was valid only before takeover and is ignored by all later
recovery under the new tip.

## Takeover

Takeover is intentionally self-contained and may transfer approximately
128 MiB for two maximum-size complete bases.

It performs:

1. authorize and bind the selected account;
2. resolve and authenticate the current ownership tip;
3. authenticate the selected recovery publication and inventory;
4. reconstruct a separate staged SQLCipher vault;
5. validate repository invariants, close it, and reopen it;
6. generate the new device ID, epoch-owned object IDs, baseline publication
   ID, and next ownership-successor ID;
7. encode two independently identified complete snapshots of the staged
   recovered generation;
8. upload, download, authenticate, decode, and compare both;
9. create and authenticate the sequence-zero new-epoch baseline publication
   bound to the planned successor claim identity and predecessor claim;
10. reread the predecessor ownership tip and stop or restart if its successor
    is occupied;
11. obtain explicit takeover confirmation;
12. create epoch `N+1` at epoch `N`'s exact reserved successor ID;
13. download and authenticate the winning claim;
14. require that it names this device and exact baseline; and
15. atomically activate the staged vault.

A losing claimant authenticates the winner and becomes `OwnershipLost`.
Its pre-claim objects are orphans and never authority.

A crash after claim creation but before activation resumes the already
authenticated staged takeover. It never allocates another epoch. Required
staging state and local database-key wrapping are durable; passphrases, access
tokens, and raw content keys are not.

The former owner keeps its local database and can edit locally. Its next cloud
operation resolves the newer tip, stops remote mutation, and offers:

- preserve divergent local work in a new lineage; or
- explicitly take ownership back through another complete takeover.

No merge occurs.

## Recovery and retention

Fresh recovery:

1. authorizes the selected account;
2. lists bounded ownership roots;
3. follows bounded public ownership headers to each candidate tip;
4. lists publication bootstraps only for that candidate tip;
5. validates bootstrap and KDF bounds;
6. tries the supplied passphrase only against bounded candidate envelopes;
7. obtains a candidate content key from a successful envelope;
8. resolves and authenticates the ownership chain from its root;
9. requires an `ACTIVE` tip matching the public candidate;
10. resolves the unique publication for that exact epoch;
11. reconstructs and validates a separate SQLCipher database;
12. verifies current-base recovery, or fallback-base recovery if current is
    damaged;
13. completes takeover when retaining the lineage; and
14. activates only after the new ownership claim is authenticated.

Every newly activated epoch owns two independent complete bases:

- `currentBase`;
- `fallbackBase`.

At initial setup and takeover they may encode the same logical generation
under distinct logical IDs, nonces, and ciphertext. After a later complete
snapshot, the former current base becomes the fallback.

Incremental publications retain:

- current and fallback complete bases;
- every segment required after either base; and
- current and immediately previous authenticated publication manifests.

The current publication directly names the retained previous publication.
The active epoch's baseline publication is retained while it is also current
or previous; its ID and digest remain authenticated creation evidence in the
ownership claim after its file is pruned. Earlier publication history may be
pruned after the retained pair and their inventories are authenticated.

## Namespace-safe cleanup

Cleanup requires current authenticated ownership immediately before each
bounded deletion batch. A batch contains at most 32 provider deletes. It stops
when the tip changes.

Within the current epoch, cleanup may delete only:

- objects absent from both retained publication inventories;
- abandoned candidates at least seven days old;
- publications older than the retained pair; and
- durable-operation candidates whose operation is terminal or absent.

Every target must authenticate or agree with the trusted publication metadata
for:

- exact lineage;
- exact owner epoch;
- exact owner device;
- expected role; and
- expected logical and provider IDs.

After takeover, the current owner may sweep objects and publications belonging
to superseded epochs because the new epoch's two-base set is self-contained.
Ownership claims are never deleted during automatic cleanup. A late old-epoch
upload is ignored and removed by a later sweep after the same seven-day
minimum age.

No cleanup path deletes an unknown object merely because it is absent from a
list. Unknown, malformed, too-new, or cross-namespace files are retained and
reported as bounded cleanup blockers.

## Recovery-passphrase change

Passphrase change rewraps the same vault-content key and does not rotate the
SQLCipher database key or re-encrypt payload objects.

It performs:

1. verify the current passphrase against the active local envelope;
2. derive and persist only a pending new envelope and bounded operation phase;
3. produce and verify the new portable Android package;
4. create a new immutable Drive publication with:
   - the same authenticated inventory;
   - the same ownership claim and epoch;
   - the next publication sequence with unchanged local generation;
   - an incremented recovery-credential generation; and
   - the new recovery envelope;
5. download and authenticate that publication;
6. reread and authenticate the unchanged ownership tip;
7. promote the pending local envelope; and
8. clear the prior local envelope and owned temporary buffers.

The previous publication remains retained until normal retention can prune it.

Passphrase change is not retroactive revocation. An old retained or exported
publication or portable package plus its old passphrase can still unwrap the
unchanged content key. The UI states this limitation and does not claim that
old copies or credentials are revoked.

## Disconnect and account mismatch

Disconnect:

- cancels remote work;
- clears in-memory authorization state;
- removes local scheduling;
- retains encrypted lineage configuration and backup history; and
- sends no Drive deletion request.

Reconnection of a known lineage requires the originally bound account digest.
An account mismatch stops before listing lineage files. Stage 3 never silently
creates a replacement lineage in another account.

## Delete encrypted backup history

Deletion is explicit, foreground, passphrase-confirmed, and permanent.

It performs:

1. verify the active recovery passphrase and account binding;
2. resolve and authenticate the current ownership tip;
3. create a terminal `TERMINATED` ownership tombstone at the exact reserved
   successor slot;
4. download and authenticate that tombstone;
5. stop every publication and takeover path for the lineage;
6. delete publications and payload objects in bounded resumable batches;
7. rescan and delete aged old-epoch residue;
8. delete the root and non-terminal ownership claims; and
9. retain the terminal ownership tombstone.

The tombstone contains only:

- opaque lineage ID;
- format family and version;
- terminal writer epoch and deletion state;
- its provider and logical IDs;
- predecessor claim ID and digest;
- proof that it occupies the predecessor's reserved successor slot; and
- a random non-secret tombstone identity.

It contains no recovery envelope, KDF parameters, content-key wrapping,
inventory, vault identity, device identity, task content, or successor slot.
It is not a backup and cannot recover anything.

The product reports that recoverable encrypted backup history was deleted and
that a non-secret safety marker remains. Removing all application-data residue
requires deleting the app's Drive application data through the Google account.
Process death resumes cleanup from encrypted local operation state. If the app
is uninstalled after the tombstone is created but before cleanup finishes, the
tombstone still prevents recovery or republishing and the user is directed to
remove residual app data through the Google account.

## Failure model

| Failure | Required behavior |
|---|---|
| Authorization required | Foreground action required; background work stops |
| Account mismatch | Zero lineage access; explicit account selection required |
| Missing known root or claim | Ownership lost or deleted; never recreate |
| Successor already occupied | Authenticate winner; loser never replaces |
| Ambiguous create response | Read exact ID and authenticate before deciding |
| Invalid occupied successor | Lineage blocked; no alternate successor |
| Ownership changes during publication | No checkpoint advance; old bytes ignored |
| Publication fork or gap | `AMBIGUOUS_REMOTE_STATE`; fail closed |
| Candidate readback mismatch | Candidate never published |
| Current base damaged | Recover through independently verified fallback base |
| Both bases damaged | Recovery fails without live-vault change |
| Quota or retryable provider failure | Preserve current backup; bounded retry |
| Cleanup ownership change | Stop deletion immediately |
| Crash after takeover claim | Resume the already claimed staged vault |
| Crash during history deletion | Resume local cleanup behind the terminal tombstone; never republish |

Errors expose only bounded categories. Tokens, IDs, URLs, response bodies,
account data, revisions, manifests, and frame bytes do not enter logs,
exceptions, analytics, WorkManager names, saved state, or UI.

## Provider qualification gate

No product implementation proceeds until the same production-intended Android
authorization and HTTP stack proves create-only slot behavior with disposable
data.

The gate must:

1. generate root, baseline, and successor IDs for `appDataFolder`;
2. create and read-authenticate an epoch-one baseline plus immutable root
   containing its successor;
3. for each race, create two different valid baseline proposals for the same
   planned successor claim ID;
4. race the two different valid authenticated successor claims against that
   exact successor ID;
5. require exactly one candidate to occupy the ID;
6. require every loser to return bounded `AlreadyExists`, never success;
7. retry the losing candidate three times;
8. download and authenticate unchanged winning bytes after every attempt;
9. repeat the two-candidate race for ten fresh predecessor/successor sets;
10. deliberately discard one successful create response and prove exact-ID
    readback resolves
   only the already-created expected bytes;
11. prove missing-file, authorization, quota, retryable, corrupt, and occupied
    outcomes remain distinguishable; and
12. delete all disposable qualification files.

Pass requires all ten races and all three loser retries per race to preserve
one unchanged authenticated winner. Silent replacement, two success results,
changed readback, inconsistent duplicate behavior, or ambiguous resolution to
different bytes stops Stage 3 for another approved design.

The qualification record contains only:

- date;
- app commit;
- Android API level;
- authorization-library version;
- Drive API endpoint family; and
- bounded property pass or failure names.

It contains no account, token, file ID, request URL, response body, or
provider revision.

## Components and boundaries

### `CreateOnlyDriveTransport`

Owns HTTP, authorization headers, generated IDs, create-if-absent, bounded
reads, bounded lists, resumable immutable upload, deletes, retries, and typed
provider failures. It makes no ownership or retention decisions.

### `OwnershipChainStore`

Discovers roots, authenticates and follows exact successor slots, resolves the
tip, creates one successor, and reports occupied or ambiguous state. It does
not publish backup generations.

### `PublicationCatalog`

Creates immutable publication manifests, authenticates bounded candidates,
resolves the unique retained publication chain, and exposes inventories. It
does not mutate Room or claim ownership.

### `RemoteBackupCoordinator`

Serializes local remote work, verifies ownership, obtains Stage 2 objects,
publishes immutable candidates, advances checkpoints after final ownership
recheck, schedules retries, and invokes namespace-safe cleanup.

### `RecoveryCoordinator`

Unlocks bounded publication bootstraps, resolves ownership, downloads and
authenticates recovery data, reconstructs a staging database, uploads the new
epoch's two-base set, claims takeover, and atomically activates.

### Product UI

The UI renders state and sends explicit commands. It does not own provider
clients, identities, tokens, passphrases after submission, object selection,
retention, or database activation.

## Persistence and migration

The additive Room v7 design persists no provider revision or JSON version as
authority.

Encrypted operational state may include:

- lineage and account-binding digest;
- root claim provider ID;
- last authenticated ownership-tip ID and digest;
- writer epoch and device ID;
- current and previous publication IDs and digests;
- current publication sequence;
- remote verified local generation and time;
- generated-but-not-yet-used provider IDs;
- resumable upload session state;
- durable operation type, phase, and candidate identities;
- recovery staging and activation state;
- cleanup cursor and minimum-age facts; and
- bounded failure category.

Access tokens, passphrases, raw account permission IDs, response bodies, file
contents, provider URLs, and raw content keys are never persisted in these
tables.

Every v6 user record, Stage 2 backup row, and legacy outbox row survives the
additive migration. Destructive migration fallback remains forbidden.

## Testing

### Unit and property tests

- strict ownership and publication canonical encoding;
- unknown version, duplicate field, identity disagreement, and bounds;
- exact successor-slot validation and epoch increment;
- occupied successor winner authentication;
- ambiguous create readback;
- ownership-chain truncation, invalid successor, and overflow;
- baseline-to-claim one-way binding without a digest cycle;
- publication sequence, fork, gap, generation regression, and unique-tip
  selection;
- ownership change before and after publication create;
- checkpoint refusal for stale epochs;
- two-base identity independence and fallback recovery;
- takeover races and every crash boundary;
- old-epoch publication after takeover;
- current-epoch and obsolete-epoch cleanup isolation;
- passphrase rotation and old-copy disclosure;
- terminal deletion and non-recoverable tombstone;
- account mismatch before storage access; and
- bounded error mapping with no private leakage.

### Room and recovery tests

- v6-to-v7 migration and schema drift;
- complete staged import and ordered segment replay;
- counts, identities, ownership, relations, tombstones, and foreign keys;
- SQLCipher integrity, close, and reopen;
- current-base failure with fallback-base recovery;
- active-slot atomicity and rollback;
- process death before and after takeover activation;
- no source-device operational-state import; and
- fresh local Stage 2 baseline before the recovered owner publishes.

### WorkManager and UI tests

- one process owner for manual and automatic publication;
- unique debounce and periodic scheduling;
- network, battery, and storage constraints;
- foreground-only authorization resolution;
- accurate pending, verified, ownership-lost, ambiguous, terminated,
  deletion-cleanup, and blocked states;
- no passphrase or provider identity in saved state;
- compact, expanded, fold, font-scale, and accessibility behavior; and
- truthful Android-package independence.

### Credentialed two-installation qualification

After the create-only provider gate passes:

1. create epoch one and publish a verified backup;
2. publish an incremental generation;
3. interrupt and resume an immutable large upload;
4. recover on a second installation;
5. upload and verify the two-base epoch set;
6. race takeover and prove one ownership successor wins;
7. let the former device finish one old-epoch publication;
8. prove that publication is ignored and cannot advance authority;
9. recover through the new epoch's fallback base;
10. change the recovery passphrase;
11. preserve divergent former-device work under a new lineage;
12. disconnect and reconnect the bound account;
13. reject a wrong account before lineage access;
14. delete backup history and retain only the tombstone; and
15. confirm Android Auto Backup remains independent and inert.

No protected local workspace is uninstalled, cleared, migrated, or
destructively rewritten for provider qualification.

## Implementation sequencing constraints

The revised implementation plan must begin from the current uncommitted Task 1
state and sequence work so unsafe assumptions fail first:

1. replace the ETag gate with create-only transport behavior and qualification;
2. commit only after the credentialed duplicate-create gate passes;
3. add strict ownership and publication codecs with deterministic fixtures;
4. add the additive Room v7 schema and runtime gate;
5. retain authorization and account-binding boundaries;
6. implement immutable transfer, ownership resolution, and publication;
7. implement scheduling, retention, and namespace-safe cleanup;
8. implement staged recovery and self-contained takeover;
9. implement passphrase change, disconnect, terminal deletion, and tombstone;
10. implement product UI and accessibility;
11. run two-installation credentialed acceptance; and
12. run full repository, schema, privacy, release, and device gates.

The existing uncommitted Task 1 work may retain:

- Google authorization;
- `drive.appdata` scoping;
- account permission lookup;
- generated-ID handling;
- bounded HTTP failures;
- immutable create, read, list, resumable upload, and delete primitives;
- debug-only qualification packaging; and
- non-private evidence recording.

It must remove or replace test-first:

- ETag requirements;
- `If-Match`;
- compare-and-swap contracts;
- mutable-control update behavior;
- provider-revision persistence; and
- qualification assertions based on stale mutable writes.

No existing Stage 3 implementation code is staged or committed before the
revised first task and provider gate pass.

## Acceptance criteria

- Drive create-only slot behavior passes the credentialed repeated-race gate.
- One immutable ownership chain has one authenticated tip.
- Exactly one authoritative writer exists for each active lineage.
- A stale device cannot overwrite, delete, or become authority over a newer
  epoch.
- Old-epoch race residue is ignored and safely cleanable.
- Normal backup publication uses immutable objects only.
- Every checkpoint requires authenticated readback and unchanged ownership.
- Initial setup and every takeover own two independent complete bases.
- Recovery selects a unique authenticated publication under the ownership tip.
- Recovery reconstructs and validates a separate SQLCipher database.
- Takeover uploads its own recovery bases before claiming ownership.
- Activation occurs only after the winning ownership claim is authenticated.
- Cleanup cannot cross lineage, epoch, device, role, or operation ownership.
- Passphrase change publishes a new immutable recovery bootstrap and discloses
  non-revocation of old copies.
- Deletion creates the terminal ownership tombstone before removing
  recoverable data and retains only that non-recoverable tombstone.
- No ETag, If-Match, Drive version, timestamp, filename, or list order is
  concurrency authority.
- Account, authorization, privacy, local-authority, Android-package, and
  no-merge requirements from the earlier approved Stage 3 design remain true.

## Official references

- [Store application-specific data in Drive](https://developers.google.com/workspace/drive/api/guides/appdata)
- [Drive `files.generateIds`](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/generateIds)
- [Drive `files.create`](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/create)
- [Drive files resource](https://developers.google.com/workspace/drive/api/reference/rest/v3/files)
- [Search for Drive files](https://developers.google.com/workspace/drive/api/guides/search-files)
- [Drive API error handling](https://developers.google.com/workspace/drive/api/guides/handle-errors)
- [Google Play services `AuthorizationClient`](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)
- [Define WorkManager requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Manage unique WorkManager work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)
