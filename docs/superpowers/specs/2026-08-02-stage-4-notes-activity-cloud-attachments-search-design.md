# Stage 4 — Notes, Activity, Cloud Attachments, and Search Design

**Date:** 2 August 2026
**Status:** Approved
**Scope:** Final structured metadata schema, first-class notes, activity
history, the create-only cloud attachment blob lifecycle, and search
extension

This is the combined Stage 4 design for the production programme. It was
approved section by section in the 2 August 2026 brainstorming session.

## Decision

Stage 4 finalises the local structured metadata schema and delivers four
user-visible capabilities on it:

1. **Notes** become first-class timestamped records on tasks and projects.
2. **Activity** becomes a repository-generated immutable event history,
   presented with notes as one merged timeline.
3. **Cloud attachments** implement the approved blob lifecycle on the
   qualified Stage 3 create-only Drive foundation. Attachment bytes are
   durable only in the cloud; metadata is structured local data.
4. **Search** extends to note bodies and attachment display names.

After Stage 4 lands, the Room schema and canonical backup record families
are declared final for Stage 5.

## Prior authorities and one supersession

This design implements the attachment lifecycle fixed by the approved
[local-authority direction](2026-07-28-local-authority-cloud-attachments-backup-design.md):
Photo Picker/SAF intake, the 100 MiB intake cap, 4 MiB encrypted chunks,
bounded working storage, the temporary LRU cache, open/share cleanup,
delete/Undo, 30-day retention, and garbage-collection preconditions all
stand unchanged.

**Superseded wording:** that design gates attachment mutations behind "a
conditional control-manifest revision". Stage 3's credentialed
qualification returned `TRANSPORT_CREATE_CONTROL_CONDITIONAL_UNAVAILABLE`
and the mutable-control design was formally replaced. Attachment ownership
therefore extends the Stage 3 create-only lineage instead: immutable
create-by-ID objects, authentication to the exact lineage and epoch, and
bounded namespace-safe cleanup. No conditional provider write, ETag,
If-Match, or provider-revision concept exists anywhere in Stage 4.

All Stage 3 boundaries remain binding: encrypted Room is the sole live
structured-data authority, there is no bidirectional sync path, objects are
encrypted locally through the provider-neutral authenticated codec, and
`BackupObjectStore` and `AttachmentBlobStore` remain separate boundaries.

## Non-goals

- Free-standing workspace notes detached from tasks and projects.
- Attachments owned by projects, notes, or anything other than tasks.
- Rich text, markdown rendering, or note formatting.
- Searching activity bodies.
- Durable offline attachment copies or any keep-offline state.
- Full-text-search indexes; search remains the bounded in-memory scan.
- Live structured-data synchronisation or remote merge, as always.

## Final metadata schema (Room v8)

### Note

A new first-class record:

- `id: NoteId`;
- exactly one of `taskId` or `projectId` non-null (the owning record);
- `body`: at most 10,000 characters, stored as ciphertext like other
  private text;
- `createdAt: Instant` and nullable `editedAt: Instant`;
- `revision: Revision`.

At most 500 notes per owning record. `Task.description` remains the short
summary field and is unchanged.

### ActivityEntry

The dormant model is finalised as repository-generated immutable system
events. `kind` becomes a closed typed set encoded as strings:

- task/project created;
- workflow status changed;
- completed and reopened;
- moved between projects;
- milestone membership changed;
- dependency added and removed;
- moved to Bin and restored; and
- attachment added and removed.

`body` is a bounded rendered detail string (for example old → new status
names), stored as ciphertext. Entries are written inside the same Room
transaction as the mutation they describe, with their own ordered
backup-journal entries. There is no user edit or delete path. History is
bounded at 500 entries per owning record with deterministic oldest-first
pruning in the same transaction; pruning deletions are journalled. Notes
are not mirrored into activity; the timeline UI interleaves the two tables
by timestamp.

### Attachment

The placeholder model is finalised to the approved field list:

- `id: AttachmentId` and `taskId: TaskId` (tasks are the only owners);
- `displayName`: at most 255 characters after sanitisation, which strips
  control characters and path separators from the untrusted provider name;
- `mimeType`: at most 255 characters;
- `byteCount`: at most 100 MiB;
- `contentHash`: SHA-256 of the plaintext stream;
- `blobSetId`: opaque remote blob-set identity;
- `chunkCount`: at most 25 (100 MiB at 4 MiB per chunk);
- lifecycle: active, or tombstoned with `deletedAt`; and
- `revision: Revision`.

The obsolete `keepOffline` column is removed. At most 100 attachments per
task. Metadata registration and tombstoning are repository commands;
delete-Undo restores the exact metadata without re-uploading bytes.

### Bin and purge semantics

Moving a task to the Bin leaves its notes, activity, and attachment
metadata untouched; restore is intact. Permanent purge deletes all three
atomically with journalled tombstones in the same transaction as the task
tombstone. Blob bytes then follow the garbage-collection rules below.
Project archive and restore never touch project notes or activity; no
permanent project deletion path exists today, and this design adds none.

### Migration and backup payloads

- Room v8: additive `notes` table; `attachments` table rebuild that drops
  `keepOffline` and adds the final columns while preserving rows; exported
  schema JSON and a non-destructive migration with a fixture test.
- The dormant `saved_views` table remains as-is and is explicitly recorded
  as dormant; dropping it would breach the non-destructive convention for
  no benefit.
- The canonical backup payload gains note records and the finalised
  attachment record with strict bounded decoders, new golden fixtures, and
  byte-identical regeneration. Activity records remain covered.
- `InMemoryVaultRepository` mirrors every new command and bound.

### Schema freeze

After this stage's exit gates pass, the local structured schema and backup
record families are final inputs for Stage 5. Later changes require a new
reviewed design.

## Commands and repository behaviour

- `AddNote`, `EditNote`, `DeleteNote`: granular commands with atomic
  record plus backup-journal writes and exact repository-produced Undo.
- Activity is never commanded directly; the repository emits entries as a
  side effect of existing accepted commands, in the same transaction.
- `RegisterAttachment` is callable only after the blob service publishes a
  verified blob set; `DeleteAttachment` tombstones metadata with Undo.
- All bounds (note body and count, activity count, attachment fields and
  count) are enforced in the repository before commit, matching existing
  bound-violation behaviour.

## Attachment blob service

### Boundary and objects

`AttachmentBlobStore` is a new boundary in `core:data` with its own remote
namespace, sharing the qualified create-only Drive HTTP transport and the
provider-neutral authenticated codec. A blob set consists of:

- up to 25 immutable chunk objects using the Stage 1 attachment-chunk
  frame family, each AEAD-bound to the vault/lineage identity, blob-set
  identity, and chunk tuple; and
- one blob-set manifest object created last, using the bounded Stage 1
  manifest family limits, listing the verified chunk inventory and the
  content hash.

A blob set is published only when every chunk has passed
download-and-verify readback and the manifest object exists. Uploads and
deletions require the active vault slot and authenticate the current
ownership tip. A stale writer's leftover objects are non-authoritative
residue, cleaned by the bounded rules below.

### Intake

Unchanged from the approved design: network availability is required
before transfer; the narrowest temporary read permission is obtained;
a provisional blob-set identity is assigned; at most 100 MiB streams
while the digest is computed; the bounded working set is one plaintext
chunk, one ciphertext chunk, and transport overhead; chunk creates use the
provider-confirmed resumable transfer with the existing restart and stall
guards; every chunk is verified by readback; the manifest is created; and
only then is metadata registered through the repository atomically.

An interrupted provisional session never produces metadata. Sessions older
than 24 hours are removed by bounded namespace-safe cleanup that
authenticates session identity and fails closed on unknown or ambiguous
objects. Adding the first attachment requires the Stage 3 backup
configuration to exist; the UI routes to backup setup rather than forking
a second setup flow.

### Open and share

Opening downloads, checksums, authenticates, and decrypts one chunk at a
time. The encrypted LRU cache lives under `cacheDir` with a ceiling of the
smaller of 128 MiB or 5% of currently available app storage and may be
evicted entirely at any time. Content streams directly when the consumer
supports it; otherwise a short-lived decrypted file is created on the
private `FileProvider` share path, granted only to the chosen target,
deleted when the operation completes, and swept at startup if abandoned.

### Delete and garbage collection

Deletion tombstones metadata (journalled, Undo-able) and evicts cached
chunks. Remote deletion of a blob set occurs only after all three hold:

1. the deletion tombstone exists in a verified backup;
2. at least 30 days have elapsed; and
3. no active metadata and no retained recovery inventory references the
   blob set.

Collection then uses the Stage 3 bounded cleanup pattern: at most 32
deletes per pass, ownership-tip re-authentication before every batch,
fail-closed handling of unknown objects, and crash-resumable progress.

**Delete cloud attachment content** remains a distinct destructive action
with recovery-passphrase confirmation; it removes remote blob sets after
explicit acknowledgement that existing metadata becomes unavailable, and
keeps local structured data and backup history. Terminal lineage deletion
extends to the attachment namespace with the same authentication rules,
and the terminal tombstone remains the final retained object.

### Recovery, takeover, and disconnect

Recovered vaults keep attachment metadata. Blob sets referenced by any
retained recovery inventory are protected from collection. Missing bytes
render as unavailable — never invented, never blocking task editing.
Disconnect keeps metadata and stops byte availability until reconnection.
Divergent-work preservation under a separate lineage does not copy or
claim the lost lineage's blobs; those attachments become unavailable
metadata in the preserved vault.

## Search

`VaultRepository.search` extends to note bodies and attachment display
names using the existing `SearchNormalizer` in-memory scan over the
snapshot; no index is added because all text is encrypted at rest. Note
hits deep-link to the owning task editor or project workbench; attachment
hits deep-link to the owning task. Activity bodies are not searchable.
Existing result bounds are retained.

## Product surfaces

- The task editor gains an **Attachments** section (rows distinguishing
  remote, downloading, unavailable, tombstoned, and failed-transfer states
  in text and iconography; add via Photo Picker or SAF; failure never
  disables editing) and a merged **Notes & activity** timeline (notes
  editable and deletable with Undo, system events read-only, newest
  first).
- The project workbench gains the same timeline for project notes and
  project-level events.
- More's Backup & recovery area gains the **cloud attachments** section:
  account connection state, temporary-cache usage, and the delete action
  above, naming exactly what remains.
- All new copy lives in `res/values/strings.xml` in UK English. Feature
  composables stay stateless with dispatch in `:app`. The accessibility
  baseline applies: 48 dp targets, TalkBack names and actions, keyboard
  and switch access, and 200% text.

## Failure containment

| Failure | Required result |
|---|---|
| Offline or provider failure during intake | No metadata record exists; the provisional session stays bounded or is cleaned |
| Interrupted upload or process death | Resumable within guard bounds or cleaned; never a half-published blob set |
| Source permission or bytes lost on retry | The user is asked to select the file again |
| Missing or damaged chunk on open | File unavailable with retry guidance; metadata intact; editing unaffected |
| Cache eviction or disk pressure | Cache evicts first; remote blobs and local structured data preserved |
| Ownership lost mid-operation | Cloud mutation refuses; local metadata unchanged |
| GC precondition unmet or object ambiguous | Collection fails closed; nothing is deleted |
| Note/activity/attachment bound exceeded | Command rejected with the existing bound-violation behaviour |

## Bounds summary

| Bound | Value |
|---|---|
| Note body | 10,000 characters |
| Notes per task or project | 500 |
| Activity entries per task or project | 500, oldest-first pruning |
| Attachment display name / MIME type | 255 characters each |
| Attachment size / chunk size / chunks | 100 MiB / 4 MiB / 25 |
| Attachments per task | 100 |
| GC deletes per pass | 32 |
| Provisional session lifetime | 24 hours |
| Attachment cache ceiling | min(128 MiB, 5% available storage) |
| Blob retention after tombstone | at least 30 days |

## Verification

### Unit and deterministic

- Command, Undo, bound, and pruning coverage for notes, activity, and
  attachment metadata; Room/in-memory repository parity.
- v7→v8 migration fixture preserving existing rows byte-safely.
- Backup payload golden fixtures regenerated byte-identically with the
  new record families; strict decoder bound cases.
- Search extension cases including normalisation and deep-link targets.
- The full hostile-intake matrix against deterministic provider fakes:
  lying provider size, filename traversal, MIME spoofing, the 100 MiB
  bound, chunk interruption, process death, source-permission loss,
  missing chunks, wrong chunk identity, hostile streams, cache eviction,
  insufficient space, cleanup, delete/Undo, and GC preconditions.

### Device and credentialed

- Connected suites on the sole disposable API 37 emulator with the
  established read-only, snapshot-disabled flags.
- A credentialed live gate on the Stage 3 pattern proving real Drive
  chunk create, readback, manifest publication, and cleanup for a small
  blob set. Size bounds are proven deterministically; no 100 MiB live
  upload is required.
- The protected `Pixel_10_Pro_Fold` workspace is never started or
  mutated by Stage 4 work.

### Exit gates

- The repository CI gate, separate release/R8 assembly, schema-drift
  script, and `git diff --check`.
- Privacy scans proving logs and telemetry contain no attachment names,
  Drive IDs, task text, or encryption metadata.
- Threat model rows T17–T19 updated from "not operational" to their
  implemented controls; CLAUDE.md, `docs/architecture.md`, `DESIGN.md`,
  and `PRODUCT.md` updated in the same change as the contracts they
  describe.

## Acceptance criteria

- Notes and activity work fully offline without an account.
- Activity entries are immutable, bounded, and generated atomically with
  the mutations they describe.
- Attachment bytes are durable only in the attachment blob service; no
  durable offline copy exists.
- A blob set is either completely published and verified or invisible.
- No attachment failure blocks structured editing; no missing blob is
  invented or silently removed.
- Garbage collection never deletes a blob set that a verified backup,
  active metadata, or retained recovery inventory can reference.
- Search finds note bodies and attachment display names and deep-links
  correctly; activity is not searchable.
- The v8 migration preserves every existing row; the schema and backup
  record families are final for Stage 5.
- No conditional provider write, update path, or provider-revision
  concept exists in the implementation.
