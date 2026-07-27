# Train 4 — Notes, Attachments, and Search Design

## Goal

Implement P2-F02, P1-D07, and P1-L05 as one coherent local/cloud attachment
contract with revisioned notes, immutable activity, encrypted files, resumable
sync, bounded cache behaviour, and workspace search.

## Domain records

`ActivityEntry.kind` becomes a stable enum. Generated entries are immutable and
record task/project lifecycle events without private values that duplicate full
record history. User notes are revisioned mutable entries with typed add,
update, delete, and restore commands.

`Attachment` gains:

- Revision and lifecycle/tombstone state.
- Opaque remote-object identity.
- Local availability and transfer state.
- Display name, MIME type, byte count, and content hash inside encrypted
  metadata.
- `keepOffline` retention preference.

Attachments remain task-scoped. Project activity aggregates its tasks and
project-scoped generated events without creating duplicate attachment rows.

Limits are:

- 500 characters per user note.
- 10,000 activity/note entries per task.
- 100 attachments per task.
- 100 MiB plaintext per attachment.
- 4 MiB authenticated encryption chunks.

## Command and file coordination

Notes use repository commands directly and receive exact Undo.

Attachment import is coordinated in the app layer:

1. Resolve a platform URI with the narrowest available temporary permission.
2. Stream into a bounded app-private staging file.
3. Reject content exceeding 100 MiB even when the provider omits or lies about
   size.
4. Compute the content digest while streaming.
5. Encrypt 4 MiB chunks with vault/object/chunk associated data.
6. Read back and verify the encrypted file.
7. Atomically rename it into the vault attachment directory.
8. Execute `RegisterAttachment`, which stores metadata and appends the outbox
   operation atomically.
9. Remove the published file if repository registration is rejected.

Delete writes the metadata tombstone and outbox operation before local
eviction. Undo restores metadata while retained encrypted content or Drive
content remains available. Permanent task purge deletes attachment metadata,
content, and remote tombstones through the existing irreversible path.

## Platform intake and sharing

- Photo Picker handles photos and video.
- Storage Access Framework handles documents.
- Sharesheet and drag/drop accept incoming read-only URIs.
- MIME type and display name are treated as untrusted metadata and bounded.
- A private `FileProvider` exposes only a short-lived decrypted copy under
  `cache/shared`.
- Sharing requires an explicit attachment action and grants read permission
  only to the selected target.
- Startup and scheduled cleanup remove abandoned intake and share files.

No broad storage permission is requested.

## Cloud attachments

Remote object names contain the vault prefix, attachment ID, and numeric chunk
index only. Filename, MIME type, digest, task ID, and size remain encrypted.

Uploads and downloads are resumable at chunk boundaries. The attachment
metadata operation becomes visible remotely only after all referenced chunks
verify. A remote metadata record with missing chunks is incomplete and cannot
replace a valid local attachment.

`keepOffline=true` content is never evicted automatically. Other verified
Drive-backed content participates in an LRU cache with:

- A user-configurable 256 MiB, 1 GiB, or 4 GiB ceiling.
- A default 1 GiB ceiling.
- No eviction of open, uploading, downloading, sharing, or unverified files.
- Explicit Clear downloaded files and per-attachment Download/Remove actions.

Cache eviction removes local encrypted content, not metadata or Drive content.
Opening uncached content downloads, verifies, decrypts, and streams through a
bounded viewer/share path.

## Activity and task UI

Task detail gains an Activity & files section with:

- User notes first, including add/edit/delete and Undo.
- Chronological generated activity with text/icon kinds.
- Attachment rows with name, type, size, availability, and transfer state.
- Add, open, share, keep-offline, retry, and delete actions.

Running work and time history stay in their existing section. Activity does not
turn every autosave keystroke into a noisy event; it records meaningful command
outcomes such as completion, status change, project move, milestone change,
file addition, and recovery.

All actions are at least 48 dp, keyboard reachable, named for TalkBack, and
usable at 200% font scaling.

## Search

Search extends the existing decrypted workspace projection to:

- User note bodies.
- Attachment display names.
- Existing task titles/descriptions/checklists/tags.
- Project names/summaries.

No plaintext FTS table or token index is added to SQLCipher. Search normalises
case and whitespace with `Locale.ROOT`, returns the matching object with safe
context, respects project/tag/completed/Bin filters, and never searches
decrypted attachment content in v1.

## Schema and migration

This train performs the final planned local-schema bump before import/export.
The migration adds revision/lifecycle/transfer fields without deleting the
existing placeholder attachment/activity rows. Existing activity kinds map to
a safe `LEGACY` enum value. Missing revisions receive a deterministic migration
revision that sorts before new edits.

The exported schema and encrypted migration fixture prove preservation from
every prior Room version.

## Exit criteria

- Local attachment intake, open, share, delete/Undo, keep-offline, and cleanup
  work without Drive.
- Drive upload/download resumes after interruption and never exposes partial
  content.
- Notes and metadata converge across devices with exact Undo locally.
- Search finds note bodies and filenames without a plaintext index.
- Limits, malicious providers, filename traversal, MIME spoofing, disk-full,
  quota, and missing-chunk cases fail safely.
- Attachment privacy and data flows are recorded in the threat model.
