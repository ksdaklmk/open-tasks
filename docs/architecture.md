# Architecture

Open Tasks is a single-activity Compose app with unidirectional state flow.
Feature UI emits typed actions; ViewModels reduce those actions and call
`VaultRepository`. Repositories are the only domain-data source.

## Data authority

Exactly one vault is active, and encrypted SQLCipher Room is its sole live
structured-data authority. Normal operation has no runtime authority mode and
no cloud-to-Room record path.

Every write is represented by a `DomainCommand`. The Room implementation
updates records and appends ordered backup-journal entries in one transaction.
The current foundation seeds the sample workspace into SQLCipher once, then
treats Room as the authority for task, timer/time-entry, Bin, search, and
backup-journal state.
Room v9 assigns one local generation to each mutation-bearing accepted
command and appends its ordered `BackupJournal` rows in the same transaction.
The additive v7→v8 migration adds the first-class `notes` table, the
finalised attachment metadata shape (dropping the obsolete keep-offline
column while preserving rows), and durable attachment-transfer session
state. Note commands, Room persistence, backup records, and
repository-generated activity history are implemented with in-memory parity.
Activity is immutable, excludes note edits, and retains the newest 500 entries
per task or project by deterministic creation-time-and-ID eviction. Search
includes note bodies and active attachment display names through the existing
bounded in-memory scan; activity bodies remain excluded and the 50-result cap
is unchanged. Attachment metadata commands validate, register, tombstone, and
restore rows in both repositories with activity and atomic journal parity.
The provider-neutral attachment blob contract, strict authenticated manifest
codec, and create-only Drive adapter are implemented.
The durable attachment coordinator now performs bounded streaming intake,
authenticated exact-ID readback, crash-safe resume, manifest-last publication,
command-only metadata registration, and exact-ID provisional expiry. The
attachment open path authenticates manifest and chunk identity while streaming
one plaintext chunk at a time; its bounded ciphertext-only LRU cache rejects
noncanonical paths and never follows symlinks. Attachment garbage collection,
attachment-only destructive deletion, and terminal attachment cleanup are
bounded, ownership-authenticated, crash-resumable, and chunk-before-manifest.
Runtime construction, share staging, and product flows are implemented;
attachment-byte failure leaves structured Room work available. The additive
v8→v9 migration (Stage 5) adds the durable `retired_blob_sets` index: every
path that permanently deletes a blob-bearing attachment record — permanent
task deletion, expired-Bin purge, and the undo of a completion that
generated a recurring occurrence — retires the blob set in the same
transaction. Retired rows are a backed-up `RETIRED_BLOB_SET` record family
that survives recovery; `MarkRetiredBlobSetCollected` releases a row
idempotently after remote cleanup; staged-vault verification accepts
exactly a settle purge's own retired rows as drift and fails closed on any
other. Any further durable schema change requires a later migration with an
exported schema.
The journal is a local backup record, not a remote merge log. The additive
v5→v6 migration preserves every existing outbox row, copies deterministic
legacy format-0 journal entries, and leaves `sync_operations` read-only until a
verified complete baseline records coverage.

The approved service boundaries are:

```text
VaultRepository
    ├── SQLCipher Room records ─────────── sole live authority
    ├── atomic BackupJournal
    └── immutable WorkspaceSnapshot

BackupCoordinator
    ├── reads consistent snapshots and journal generations
    ├── encrypts through AuthenticatedCloudObjectCodec
    └── writes verified LocalBackupObjectStore current/previous/segments

PortableBackupPublisher
    └── atomically publishes one Auto Backup-eligible encrypted package

AttachmentBlobCoordinator
    ├── streams bounded chunks through temporary cache
    ├── encrypts through AuthenticatedCloudObjectCodec
    └── writes AttachmentBlobStore

RecoveryCoordinator
    ├── stages and verifies a replacement SQLCipher vault
    ├── claims the next writer epoch
    └── atomically activates the staged vault
```

`BackupCoordinator`, strict snapshot/segment codecs, consistent Room capture,
and `LocalBackupObjectStore` are implemented in `core:data`. The application
runtime starts one process-scoped coordinator, coalesces journal changes, and
resumes pending work. `PortableBackupPublisher`, verified recovery-envelope
setup, exact Android backup eligibility, and restored-package quarantine are
implemented in `app`. The create-only Drive backup store, explicit
authorization boundary, runtime scheduler, lifecycle coordinator, and staged
recovery path are implemented. `AttachmentBlobCoordinator` persists an exact
ID session before creates, publishes verified chunks before a manifest, and
registers metadata only through `VaultRepository`; its open, cache, GC,
destructive-deletion, and share-staging paths stay behind the separate
`AttachmentBlobStore` boundary. The internal
`AuthenticatedCloudObjectCodec` shown at their encryption
boundary is implemented in `core:data`.
`RecoveryCoordinator` is the only component allowed to reconstruct Room
from backup data or a restored portable package. `BackupCoordinator`,
`PortableBackupPublisher`, and `AttachmentBlobCoordinator` cannot mutate
structured product records.

The `.otvault` v1 archive format (Stage 5) is a separate, frozen
whole-vault export/import path with no online lineage of its own.
`OtVaultExporter` captures one point-in-time `WorkspaceSnapshot`
baseline, wraps the archive's content key in a real recovery envelope
(export passphrase = recovery passphrase), and streams the frozen Stage
1 authenticated frame family at archive-scoped object identifiers to a
person-chosen document; exports are snapshot-only, with no
operation-segment frames. `OtVaultImporter` stages an archive into an
isolated vault slot, verifies it in full before any live state changes,
and activates through the same proven recovery slot-replacement path as
`RecoveryCoordinator`, retaining the previous slot as rollback until
first unlock of the newly activated vault. The format version is frozen:
an independent fixture generator must regenerate it byte-identically,
and any future change requires a new version rather than an in-place
edit.

`WorkspaceCsvWriter` is a pure, Android-free formatter over the same
`WorkspaceSnapshot` for the four fixed CSV export tables (tasks,
projects, time entries, notes). It applies RFC 4180 quoting and
neutralises any cell beginning `=`, `+`, `-`, or `@` against formula
injection in the opening spreadsheet application. Unlike every other
export path above, CSV output is disclosed plaintext by design and
carries no encryption; the product surface requires an explicit
disclosure before any write.

`RoomVaultRepository` owns its observation scope. Closing the repository
cancels and joins that scope before its database owner closes SQLCipher. This
ordering is part of the lifecycle contract: no Room flow collector may outlive
the connection pool during process, test, vault-switch or recovery teardown.

The task editor emits a complete validated core-field update after a short
debounce. Room reads the latest committed record before assigning the next
revision, so rapid edits cannot derive revisions or undo state from a stale UI
snapshot. Each accepted update returns the prior values as an undo command.

Since Stage 8 the editor's validated core fields include `start` as well as
`due`. `TaskEdit` and the full `UpdateTask` command both carry start, and the
app mapper, validation, equality, both engine handlers, and the
repository-produced inverse builders move start and due together in that same
debounced atomic save.

Checklist and tag edits use granular commands rather than replacing a complete
relation set. Room reads current checklist and tag rows inside the serialized
write path, advances the owning task revision, mutates the relation, and
appends its backup-journal entry in one transaction. This preserves rapid
independent taps and produces exact add/remove/edit Undo commands. Creating a
tag writes the reusable tag record and its initial task membership atomically.

Workflow statuses are first-class Room records exposed in
`WorkspaceSnapshot`. Every project owns an independent ordered workflow;
Inbox uses the same model with a `null` project scope. New projects receive
Backlog, Planned, Started, Blocked and Completed categories in the same
transaction as the project and their separate backup-journal entries. Status names
and order are custom, while the semantic reporting category is immutable.
Create, rename, move, archive and restore are typed repository commands with
exact repository-produced Undo. A project may have at most 20 active statuses
and must retain at least one active status in every semantic category.

A status-change command accepts only a status ID; the repository verifies that
the active status belongs to the task's current project or Inbox workflow.
Moving a task between projects maps it to the first active destination status
with the same semantic category, while Undo restores the exact prior status ID.
Archiving never rewrites assigned tasks: their status and reporting category
remain visible until the user moves them. Entering a completed category records
the completion instant, leaving it clears that instant, and Undo restores the
exact prior status and completion time. Completing blocked work still requires
explicit acknowledgement.

Recurring tasks persist both the user-authored rule and internal series
metadata: a stable series ID, original wall-clock anchor, and occurrence
index. Completion derives the next deterministic occurrence from the original
anchor so month-end schedules do not drift and local times survive DST. The
current completion and generated occurrence are written with separate
backup-journal entries in one Room transaction. If the current task has a reminder, the
next occurrence inherits its due-relative lead time and delivery precision in
that transaction. Completion Undo reopens the current task, tombstones only
the generated occurrence and emits the generated reminder deletion. Room
schema v2 adds the series metadata through a non-destructive v1→v2 migration.
Schema v3 clones the former workspace-wide workflow into each project and
Inbox, remaps every task status, and adds workflow revision fields through a
non-destructive v2→v3 migration.

Milestones are revisioned, project-owned records. Create, rename, due-date
edit, complete, reopen and delete are typed repository commands. Names are
trimmed, capped at 120 characters and case-insensitively unique within a
project; each project is capped at 100 milestones. Task membership is a
nullable milestone ID validated against the task's project. Moving a task to
another project or Inbox clears membership unless an exact Undo restores the
prior project, workflow status and milestone together. Recurring occurrences
inherit the current milestone membership.

Deleting a milestone clears every assigned task membership and appends the
milestone deletion plus each revised task operation in one Room transaction.
The repository-produced Undo restores the milestone and only the captured
project-matching memberships. Schema v4 adds milestone revision fields through
a non-destructive v3→v4 migration. The canonical task backup payload includes
start and due moments plus milestone identity, so a backup segment cannot
silently lose scheduling or membership.

Project templates are immutable, revisioned snapshots captured from an active
project. A capture includes project summary and due date; every active workflow
stage; open milestones; and open tasks with descriptions, start/due moments,
recurrence, estimate, parent structure, checklist text, tag names, milestone
membership and in-template dependencies. Completed tasks, completed milestones,
Bin items, reminders and historical activity are deliberately excluded.
Checklist completion and project health reset when a template is used.

All saved dates are represented as non-negative day offsets from the earliest
captured date, capped at 36,525 days. Zoned task moments also retain local
second-of-day and zone ID, so choosing a new anchor date preserves wall-clock
intent across DST. Instantiation derives every child identifier
deterministically from the new project ID and source key, remaps relations, and
writes the project, workflow, milestones, any new tags, tasks, relations and
their independent backup-journal entries in one transaction.

A workspace is capped at 100 templates and each template at 500 tasks and a
2 MiB versioned payload. The strict decoder validates identifier and text
lengths, semantic workflow coverage, collection bounds, zone IDs, date ranges,
unique ranks/keys and acyclic parent/dependency graphs before instantiation.
Local payload bytes are protected by SQLCipher at rest; the canonical upsert
backup payload is self-contained so an encrypted backup segment can preserve
the template without relying on a local database row. Schema v5 adds template
revision fields through a non-destructive v4→v5 migration. Capture and delete
provide repository-produced Undo; creating a project from a template follows
the existing non-Undoable project-creation contract.

Task dependencies are directed task-to-task relations. `SetTaskDependency`
adds or removes one relation through the repository, caps each task at 100
links and rejects self-links or any edge which would make the graph cyclic.
The relation write, revised task record and task backup-journal entry are one
Room transaction; repository-produced Undo applies the exact inverse relation.
The canonical task backup payload carries the complete sorted dependency-ID
set as well as start, due and milestone identity.

`dependencyIds` preserves every active link, including completed
prerequisites. `blockedBy` is a derived view containing only linked tasks which
are not complete, so completing or reopening a prerequisite automatically
resolves or restores the dependent task's blocking state without rewriting
the relation. A task may also be blocked by its workflow category. Moving to a
Completed category from either condition requires explicit repository
acknowledgement. All in-app completion controls and status changes route
through the same ViewModel gate; blocked reminder notifications omit the
Complete action, and a stale notification action is still rejected by the
repository.

Each task has at most one durable reminder, addressed by the deterministic
`reminder:<task-id>` identifier and exposed through `WorkspaceSnapshot`.
Task-editor saves treat the task and reminder as one validated replacement:
Room writes both records and their independent backup-journal entries in one
transaction, while repository-produced Undo restores both prior values.
`SetTaskReminder` is the granular path used by notification actions such as
Snooze, and `SetTaskSchedule` is the exact rescheduling path that rewrites a
task's schedule and its reminder together. Permanent task purge removes the
reminder and queues its deletion in the same transaction as the task
tombstone.

`ReminderScheduler` derives Android alarms from future reminders belonging to
active tasks outside the Bin. Flexible reminders use idle-safe inexact alarms.
Precise reminders use exact alarms only while Android's special access is
granted and fall back to the same inexact path if access is missing or revoked
during scheduling. Alarm intents contain opaque record IDs rather than task
text. The scheduler reconciles after repository changes, boot, package
replacement, wall-clock/time-zone changes and exact-alarm access changes.

Notification permission is requested only when the user first chooses a
reminder. The task-reminder channel is private on the lock screen and supplies
a generic public version; disabled app or channel notifications are surfaced
inline in the editor. Notification taps deep-link to the task, while Snooze
and Complete execute typed repository commands through private immutable
pending intents. Blocked tasks omit the Complete action.

Schedule is a read-only projection of `WorkspaceSnapshot`; it owns no parallel
calendar store. Active dated tasks are grouped by the local calendar date of
`start`, falling back to `due`, using the zone carried by that `ZonedMoment`.
Within a day they sort by local time and then title. Compact windows project
one selected-day agenda, while expanded windows project the containing ISO
Monday–Sunday week and an unscheduled tray containing only active,
non-completed tasks without either date. Reminder indicators are joined by
task ID from the same snapshot. Schedule also offers a Month mode at every
window size over the same projection rules. Selecting an item deep-links to
the existing task editor, and rescheduling goes through one repository
command, so Schedule still introduces no alternate mutation path.

The Home-screen Today widget is another read-only projection of
`WorkspaceSnapshot`, computed by the pure `computeTodayProjection`
function (today/overdue counts and up to three focus task titles). A
publisher is bound to the active vault-slot lifecycle: it starts and
republishes only while a vault is active, and stops when that slot is
replaced or the runtime tears down. Every title write passes through the
mutex-gated `StopGatedWriter`, which exposes one `titlesPermitted` seam
consulted at write time, so no write can land after a stop-time title
clear. Responsive Glance layouts keep both counts and Quick Add in the compact
2×1 surface; only a taller layout reads the optional title rows and exposes
their open/complete actions.

Time entries are first-class records in `WorkspaceSnapshot`. Starting a timer
inserts an open entry; stopping it closes that same entry, and elapsed time is
derived from its original instant rather than UI state. Manual add, edit,
delete and exact restore are typed repository commands. They require an
existing non-Bin task, a strictly positive interval, a note of at most 500
characters and at most 10,000 entries per task. Each accepted Room mutation
and its time-entry backup-journal entry commit atomically; add, edit and delete
return repository-produced Undo.

Overlaps are preserved rather than silently truncating recorded work. The
repository deterministically sorts the complete stream, performs a linear
interval sweep and exposes conflict pairs with their overlap duration in the
snapshot. The task editor shows both an inline warning and a complete
time-entry review sheet; editing or deleting either completed entry resolves
the warning. Running entries remain read-only until their timer is stopped.
This also keeps overlapping local records visible without inventing or
discarding duration. The canonical time-entry backup payload carries the entry
ID, task ID, source device, start/end instants and a delimiter-safe encoded
note; future backup publication must encrypt the containing operation before
upload.

Moving a task to the Bin stops its timer in the same transaction, and restore
clears only the deletion timestamp. Expired Bin content is purged on repository
startup. An explicit
permanent delete removes task relations, appends relation deletion operations
where required, and appends a task tombstone backup-journal entry atomically; the
UI requires a confirmation because this command has no Undo.

Project Workbench edits use complete, validated project updates. Room reads the
latest project revision, writes the project row, and appends its
backup-journal entry atomically. The previous project value becomes an exact
Undo command. Selected
project identity lives in `SavedStateHandle`, while compact and expanded
windows render the same repository state as one-pane or list/detail workbenches.

## UI process restoration

Restoration has three explicit authorities rather than a duplicate domain
store:

- Navigation 3's serializable back stack restores the current top-level
  destination.
- `SavedStateHandle` restores selected task and project IDs across ViewModel
  recreation, and the per-project planning view state: LIST, BOARD or
  TIMELINE presentation, the Monday-only Timeline anchor, and the Timeline
  task selection.
- Compose saveable state restores transient UI context: filters, list/detail
  scroll positions, open sheets, search and quick-add text, and unsaved task
  or project editor fields.

An editor treats restored saveable fields as newer than the repository value
present on its first composition. Its initial repository observation therefore
must not overwrite the restored draft. Later committed repository updates
continue to reconcile normally. Durable timer continuity and completed time
history do not use UI state: `TimeEntry` rows remain in SQLCipher, and the
reconstructed snapshot derives an active entry's elapsed time from the current
clock.

Saveable state is for bounded text and UI identifiers only. It must never hold
recovery passphrases, cryptographic keys, attachment bytes or imported vault
payloads. Android owns this transient bundle and protects device at-rest state
with the platform credential boundary, but it is not a second
vault-encryption format.

App lock is a clock-injected, pure `AppLockController`: cold start is
locked whenever the lock is enabled, and an unlocked session locks again
only after a background span reaches the chosen delay. `MainActivity`
checks the locked state ahead of every other top-level state, including
an open recovery shell, so the lock overlay always takes precedence
inside the Active state and replaces all content with no workspace data
composed behind it.

Project creation assigns the ID before execution so successful creation can
open the new workbench without a name-based lookup. Its project row, five
default workflow rows and six independent backup-journal entries are one
transaction. Active project names are case-insensitively unique. Archive and
Restore update only the project's timestamped lifecycle field and append a
backup-journal entry atomically; assigned tasks, workflow, milestones, and
history never move or disappear. Home, Projects, and project search exclude
archived projects. Task context still resolves archived project names, but new
task assignment and workflow editing are restricted to active projects until
the project is restored.

## Module boundaries

```text
app
├── feature/*
├── core:data ─── core:domain ─── core:model
├── core:crypto ───────────────── core:model
├── core:sync ─────────────────── core:model
└── core:designsystem
```

`core:sync` and `core:crypto` are independent of Compose. This keeps
authenticated object-format and recovery proofs runnable as unit tests.
`core:sync` is a historical module name: its product responsibility is bounded
provider-independent object formats. Hybrid logical clocks and deterministic
merge primitives may remain as unused, well-tested internal code, but are not
a product or release dependency.

`core:designsystem` owns the shared low-level drag mechanics — root-coordinate
source bounds, accumulated pointer offset, fresh start and drop callbacks,
target hit testing, and absolute preview placement — extracted from the proven
Board implementation. It is not a general drag framework: drop targets, drag
eligibility, scrolling, cards, and every scheduling rule stay in the consuming
feature. Pure planning projections stay in `core:domain` with their small
result types in `core:model`, and `app` computes them, so feature modules
still depend only on `core:model` and `core:designsystem`.

## Security invariants

- The SQLCipher database key and Tink AES-256-GCM vault-content key are
  independently generated random 256-bit values.
- The local database key is wrapped by an AES-GCM Android Keystore key that is
  restricted to use after device unlock.
- The vault-content key has two independent envelopes: an Argon2id-derived
  recovery envelope and a local AES-GCM envelope under a deterministic
  per-vault Android Keystore alias. Changing the recovery passphrase re-wraps
  the same content key.
- A stored local content-key envelope requires its existing alias. Missing,
  replaced or invalidated aliases fail closed; persistence and alias failures
  restore the prior preference envelope where possible and never silently
  create a replacement content key.
- Recovery uses Argon2id (64 MiB, 3 iterations, parallelism 1, 16-byte salt).
- Record encryption binds vault, object, and format identifiers as associated
  data.
- Recovery passphrases are never persisted.
- Android Auto Backup and device transfer include only the exact portable
  encrypted package in the `file` domain at application-relative path
  `android_backup/open_tasks_portable_v1.otb`. Databases, WAL/SHM, preferences,
  keys, credentials, cache, local recovery staging, and attachment bytes
  remain excluded.
- Logs and telemetry must never contain task text, account details, Drive IDs,
  attachment names, or encryption metadata.
- The device-local `daily_digest` preference file stores exactly an enabled
  flag, a minute of day, and an optional last-handled epoch day. It never
  stores task text, titles, project or vault identity, counts, zone IDs,
  notification payloads, or a scheduled instant, and invalid state fails
  closed by disabling the feature and cancelling its alarm.
- Biometric app-lock unlock changes no key material: it gates UI content
  composition only and never re-wraps, re-derives, or otherwise touches
  the vault-content key or the SQLCipher database key.

The asset inventory, trust boundaries, adversaries, residual risks and release
gates are maintained in [Threat Model](threat-model.md).

## Authenticated object foundation

The implemented v1 cloud frame is a four-byte big-endian header length,
canonical UTF-8 JSON header, then raw ciphertext. The header has fixed property
ordering and exposes the object family, `schemaVersion`, `cryptoVersion`,
`minimumReaderVersion`, exact vault/object identity, ciphertext length and
SHA-256, plus an attachment-only chunk tuple. Unknown, missing, duplicated,
reordered, non-canonical or invalid UTF-8 fields are rejected.

Decoding reads and validates the bounded header before ciphertext allocation.
It enforces an exact total frame length, a 16 KiB header limit and ciphertext
limits of 1 MiB for manifests, 64 MiB for snapshots, 16 MiB for operation
segments and 4 MiB plaintext plus crypto-v1 overhead for each of at most 26
attachment chunks. Model limits are 10,000 manifest entries, 100,000 snapshot
records and 10,000 operations per segment. For ciphertext reads, the input
source receives only an 8 KiB-bounded scratch buffer; the decoder retains one
separate full-size ciphertext array. Scratch storage is cleared in `finally`;
partial and complete locally owned ciphertext is cleared on every failure
before the complete, checksum-verified array transfers at most once.

The SHA-256 field is a corruption check, not an authentication claim. The
implemented provider-independent codec in `core:data` binds the complete
`CloudHeaderIdentity` as AEAD associated data, verifies length and checksum
before decryption, and translates untrusted frame and authentication rejection
to typed failures. Strict snapshot/operation payload codecs and the verified
local recovery-object coordinator now consume it. Portable-package
publication, runtime scheduling, and local package status are implemented.
Create-only provider transport, staged restore activation, and the
product-visible app-managed backup/recovery surfaces are implemented in source.
Task 13 is complete and review-clean: transient authorization transport
failure has a distinct bounded retry presentation with no false sign-in
guidance, and a genuine `MainActivity` production recovery-route recreation
test proves private passphrase input is not restored. Stage 4's final gates
are qualified, including the one-shot credentialed attachment properties; that
does not broaden live-provider or two-installation recovery claims. Attachment
flows are implemented behind their separate store boundary.

Hybrid logical clocks and merge primitives remain implemented internal
utilities but do not define product behaviour. Future journal segments carry
local backup continuity for one active writer. Normal operation never
downloads or merges structured records.

## Stage 6 daily-flow command boundary

Stage 6 activates the existing Room v9 `saved_views` table without a schema or
backup-format change. `WorkspaceSnapshot.savedViews` is now live workspace
state. Search creates, renames, updates, deletes, and restores saved views only
through `DomainCommand`; the query payload has a strict codec and remains
encrypted workspace content. The existing `SAVED_VIEW` backup family keeps ID
as its record identity, but backup-journal comparison uses the complete encoded
record as its fingerprint so a rename or query edit journals an upsert.

Bulk mutations and task CSV import are repository-owned composite commands.
Bulk commands accept distinct task IDs, validate the complete batch before
writing, commit at most 200 task changes atomically, and return one
repository-produced `UndoBatch`. Room applies the batch in one transaction;
the in-memory engine uses a scratch snapshot and publishes once. `ImportTasks`
accepts only validated rows from the app's own Tasks CSV schema, creates new
records without matching or merging existing ones, and returns a receipt whose
`RemoveImportedRecords` undo removes exactly those created records. Parser and
repository limits independently cap an import at 5,000 task rows.

Weekly review is derived UI state over overdue tasks, tasks not reviewed for
14 days, unscheduled tasks, and project health. Completing a review row sends
`MarkReviewed` for exactly one task or project and appends an immutable
`ActivityKind.REVIEWED` entry; reviewed progress itself is not a parallel
mutable store.

Focus-cycle state is app-managed alarm/session state, while time entries remain
Room authority. The 25/5 and 50/10 presets serialize start, boundary,
reconciliation, banner Stop, and task-detail timer Stop through one coordinator
gate. A manual Stop for the focus-owned task clears the session and alarm before
`StopTimerIfOwned`; a Stop for another task retains ordinary `StopTimer`
behaviour. The repository owner check and stop occur in the same transaction,
so a stale boundary cannot stop a newly started task.

Markdown export and Tasks CSV export/import are explicit Storage Access
Framework boundaries. Markdown and CSV output are plaintext outside the vault;
partial documents are deleted on failure. CSV parsing is bounded before
repository dispatch, and parse buffers are cleared when the preview is no
longer needed. These interop paths do not change the encrypted Room, create-only
Drive, or snapshot-only `.otvault` authority boundaries.

Google authorisation is explicit and the Drive backup transport requests only
`drive.appdata`; it lists/reads encrypted lineage objects and creates immutable
objects without a PATCH/update path. Backup and attachment namespaces remain
separate `BackupObjectStore` and `AttachmentBlobStore` boundaries. Their shared
codec is provider-independent, and only `RecoveryCoordinator` may use decoded
backup data to construct a staged replacement Room vault. Live structured data
is never synchronized or merged from Drive during normal operation.

## Stage 7 ergonomics boundary (implemented)

Stage 7 keeps the existing module direction. `core:model` owns the shared
due-bucket, sort, group, and saved-filter vocabulary. `core:domain` is the one
authority for task arrangement, search filtering and ranking, Quick Add token
parsing, task-duplication planning, and the zone-aware Insights completion
trend. `app` applies those rules, owns command dispatch, and persists only
arrangement enum names and project IDs in the device-local `view_prefs` file.
Stateless Tasks, Projects, and More features render the resulting plain data
and emit choices through callbacks.

Saved-view payloads use strict version-first decoding: existing v1 rows remain
readable, new or updated rows encode deterministic v2 payloads, and malformed or
future versions remain stored but invisible. The v2 due-bucket, priority,
semantic-status, and sort fields stay inside the existing encrypted saved-view
record and its content-based journal fingerprint.

Quick Add widens the existing `CreateTask` command so confirmed tags, estimate,
and recurrence are validated and written atomically with the task and one Undo.
`DuplicateTask` is likewise one repository command in both engines: it writes
the ruled task fields, fresh unticked checklist rows, activity, and journal state
atomically, while excluding reminder, recurrence, time-entry, note, attachment,
and prior-activity state. Room remains v9 and the authenticated backup object
format remains v1; Stage 7 adds no schema, backup family, or external data path.


## Stage 8 planning surfaces (implemented)

Stage 8 adds planning surfaces to the existing destinations rather than a new
one: Week and Month inside Schedule, List/Board/Timeline inside the project
workbench, and an inline daily-digest setting in More. Room remains v9 and the
authenticated backup object format remains v1; Stage 8 adds no schema, backup
family, worker, service, external dependency, permission, Drive scope, or
top-level route. The application version is 1.2.0, `versionCode` 3.

Month and Timeline are pure projections of `WorkspaceSnapshot` computed in
`core:domain`, with their small semantic result types in `core:model` beside
the existing projection vocabulary. `OpenTasksApp` computes both and passes
plain values and callbacks to stateless feature composables. Neither owns a
parallel planning store, and neither writes.

The month projection takes a selected month, a `Clock`, and a display zone and
produces a Monday-first, fixed six-row by seven-column 42-cell grid. Leading
and trailing adjacent-month dates remain selectable with muted treatment.
Placement is the one-task-one-day rule Schedule already uses: `start` when
present, otherwise `due`, converted to a `LocalDate` in that moment's own
stored zone. Every non-binned dated task is placed and completed work stays
visible with completed icon and text treatment, but only open tasks are drag
sources. Each cell carries the local date, the total placed count, the
completed count, and the overdue count of open tasks classified
`DueBucket.OVERDUE`, plus up to six density dots and a compact `6+` overflow
label above that cap. Dots and warning colour are decorative and hidden from
the semantics tree: merged cell semantics state the full date and the exact
counts, and overdue state also carries a labelled warning marker. Previous and
Next move one month, Today opens the month containing today and selects it,
and selecting a day only updates the selected-day agenda rather than
short-circuiting into a task.

The project timeline projection is a read-only Gantt-lite over a bounded
84-day, twelve-week, Monday-aligned window; the fixed window is the bound, so
no amount of project history can create an unbounded canvas. Every non-binned
project task remains one row. Start and due render an inclusive dot-run span
through the existing `DotRunBar`; start-only and due-only render labelled
markers; a due instant before its start renders a labelled warning instead of
a fabricated backwards span; and undated tasks fall into a compact unscheduled
list below the timeline. A span crossing a window edge clips to that edge and
shows a labelled continuation marker in its own slot beside the
completed/blocked status icon, while wholly earlier or later dates render
labelled before-window and after-window states rather than a false in-window
point. `Task.isBlocked` remains the blocked-marker authority for both
unfinished dependencies and semantic blocked state. Milestones dated inside
the window render as 48 dp diamonds, and out-of-window or undated milestones
contribute exact before and after counts while remaining available in the
existing milestone list. Selecting a row highlights its complete transitive
dependency context — prerequisites and dependants — over the full non-binned
snapshot graph, traversed across project boundaries and bounded by a visited
set and the snapshot's task count; the chain summary reports unique
out-of-project tasks rather than dependency edges, and there is no arrow
routing. Shape, icon, label, and merged semantics carry duration, completion,
blocked, milestone, and dependency meaning independently of colour. Bars and
diamonds are not draggable: a separate 48 dp Open action opens the existing
task editor, and milestone activation opens the existing milestone editor.

The bulk due-only `RescheduleTasks` command and its Tasks and Review callers
are unchanged. `SetTaskSchedule` is the one exact single-task path,
implemented in both engines: it carries the task ID, the target `start`, `due`
and `reminder`, and a `restorePastReminder` flag. The repository validates the
complete target state — task and reminder identity, reminder-with-due,
recurrence-with-schedule, recurrence end date, start-before-due, and the
future-reminder rule — then writes the task and its reminder in one
transaction under one journal generation with ordered task-then-reminder
entries, advances the task revision once, journals through the existing
snapshot diff, and returns the same command carrying the previous exact values
as repository-produced Undo. Only that repository-produced Undo sets
`restorePastReminder`, which bypasses solely the future-reminder check,
restores exact reminder metadata, and never arms an already-past alarm.
Rescheduling adds no activity kind, preserving the existing due-edit
behaviour.

A pure `core:domain` move rule converts the current task and reminder plus a
date or tray target into those exact command fields, so no UI code
reimplements date, zone, recurrence, or reminder arithmetic. An undated task
dropped on a day becomes due at 18:00 in the device's current zone. A due-only
or start-only task keeps its local time and stored zone through
`ZonedDateTime.ofLocal` with its existing offset preferred; a start-and-due
task shifts both moments by the same calendar-day delta with `plusDays` in
each moment's own stored zone, preserving the day span. Java's gap and overlap
resolution is the DST authority and the only deviation from literal wall-time
preservation. A reminder stays due-relative: its lead duration and delivery
precision reapply to the new due moment, and a move that would place it at or
before now is rejected with an actionable message rather than silently
deleting or restoring a past alarm. Moving a recurring task changes only the
current occurrence's dates and leaves its series ID, anchor, occurrence index
and rule untouched; a recurring task cannot move to the unscheduled tray,
because recurrence requires a schedule. Dropping an open non-recurring task
into the tray clears start and due, and when it has a reminder one hoisted
confirmation explains that both are removed, clears them atomically, and
restores them together on Undo.

The task editor completes the schedule surface with native start date and time
controls and an explicit due time control. New start moments default to 09:00
and new due moments keep the editor's pre-Stage-8 17:00 convention, both in
the device's current zone, while existing moments preserve their stored zone
and local time; 18:00 belongs only to an undated day drop. The editor rejects
due-before-start, and legacy or imported invalid records remain readable
behind a warning until corrected. Editor saves stay on the debounced
`UpdateTask` path while `SetTaskSchedule` stays reserved for explicit
rescheduling actions, so the editor never races two schedule commands.

Pointer drag is a layer over a complete non-drag path, not a replacement for
it. Expanded Week registers its seven day columns and the unscheduled tray;
Month registers its 42 visible cells as targets only, because a cell can
represent several tasks, and sources individual selected-day and tray rows;
compact Week has no honest pointer target grid and stays tap and menu only.
The drag payload carries the rendering context's own date rather than
recomputing one. Completed and binned tasks are never sources, a same-source
or outside-target drop snaps back without issuing a command, and repository
rejection leaves the task in place and surfaces its exact message. Every
draggable task keeps a 48 dp Reschedule action, a Remove schedule action where
valid, and the same hoisted confirmation the drag path opens. Drag adds no
command, arithmetic, controller, or persistence state.

The project workbench's per-project Board Boolean is gone. `SavedStateHandle`
holds one `WorkspaceProjectViewState` per project: a LIST, BOARD or TIMELINE
presentation, a Monday-only Timeline anchor, and the Timeline task selection.
This stays session and process-restorable UI state — not a SharedPreferences
or vault preference — and holds no task or query text. Decoding is
fail-closed: a legacy board-mode entry restores as BOARD, a corrupt row
claiming two modes resolves to TIMELINE, and LIST defaults and null selections
are never persisted. Timeline navigation moves the anchor by exactly four
weeks and resolves Today and default anchors through the injectable zone
provider at action time.

The daily digest is opt-in, device-local, off by default, and adds no
permission. Its `daily_digest` preference file is a second device-local file
beside `view_prefs` and stores exactly `enabled`, `minute_of_day` in `0..1439`
and an optional `last_handled_epoch_day`, as Boolean, Int and Long. Raw types
are validated on read; anything else fails closed by rewriting a disabled
08:00 state that retains only a valid handled day, and disabling writes only
`enabled` so an off/on cycle cannot duplicate a digest on the same local day.
The existing SharedPreferences exclusion keeps the file out of Android Auto
Backup.

Scheduling is one inexact `AlarmManager.setAndAllowWhileIdle` alarm behind a
single stable, immutable, non-exported broadcast `PendingIntent`; a daily
digest does not justify exact-alarm access. The next occurrence is the
configured local wall time in the device's current zone, resolved by
`java.time` across DST gaps and overlaps and bounded by the stored handled
day, so a backward clock or date change cannot post twice. The non-exported,
no-filter `DailyDigestReceiver` is the stage's only manifest delta; it
validates its action, re-reads settings, and cancels rather than re-arms when
the setting is disabled or invalid. Delivery is serialized under one mutex in
a fixed order: mark today handled, re-arm the next one-shot alarm, then read
active vault state, then post. Reconciliation also runs on app foreground and
startup from `MainActivity.onStart` and on boot, package replacement,
wall-clock change and time-zone change through the existing system-event
receiver. Enabling after today's configured time schedules tomorrow rather
than posting a catch-up. A missing active vault, permission denial, a disabled
channel, or a notification `SecurityException` leaves the day handled and is
not retried, and the next alarm stays armed.

Digest content is counts only. Delivery captures one `now` and zone and calls
the existing `computeTodayProjection` with `titlesPermitted = false`, so no
title can reach the digest; when both counts are zero nothing is posted and
tomorrow stays armed. The digest uses its own private-visibility
`daily_digest` channel with a generic public lock-screen version, so Android
can disable it independently of task reminders. More carries the inline switch
inside the existing overview list and an enabled-only `HH:mm` row whose 48 dp
button opens the native 24-hour `TimePickerDialog`; notification guidance is
contextual and never gates the switch, matching reminder preservation.
Tapping the digest sends `OPEN_DAILY_DIGEST_HOME`, which `MainActivity` turns
into a navigation signal consumed exactly once inside the unlocked Active
workspace, so a stale signal cannot misroute a later reminder tap. The
app-lock overlay remains authoritative before any workspace composition.

Stage 8 implementation is complete through its fix wave and its whole-stage
review, which returned no Critical findings. Device qualification, signing,
and release records live in `docs/qualification/stage8-planning-surfaces.md`
and `docs/qualification/release-1.2.0-sideload.md`; remote CI and the release
tag are recorded there as pending.

## Implemented onboarding, dashboard, and NFR boundary

The implementation follows
`docs/superpowers/plans/2026-08-21-open-tasks-onboarding-dashboard-nfr-plan.md`.

Vault existence remains the onboarding authority. `VaultRuntimeState.NoVault`
renders Welcome and performs no discovery. Offline creation continues through
`VaultRuntimeManager.createNewVault()`, but Room’s production default seed is
only the vault/member/primary-workspace structure plus
`WorkflowStatus.defaults(null)`. A production `PRIMARY_WORKSPACE_ID` replaces
runtime imports of demonstration fixtures; tests may still inject
`OpenTasksFixtures.snapshot` explicitly. No “welcome seen” preference, account
table, or identity layer is added.

Drive and portable recovery remain in `RecoveryViewModel` and the existing
staged activation path. Their work begins only after explicit Welcome actions.
Google authorization remains a capability for encrypted `drive.appdata`
backup/recovery and conveys no authority over local records. Unreadable and
active-replacement states keep the protected recovery shell and never
overwrite a vault implicitly.

`ExecutiveDashboardHtmlWriter` lives in `core:data` and receives the existing
`InsightsEngine`. A request freezes `WorkspaceSnapshot`,
`InsightsSelection`, `Instant`, `ZoneId`, and the detail flag once. One bounded
typed DTO is serialized into one offline HTML document; no reporting store,
second metric engine, server, WebView, or web framework exists.
`VaultTransferViewModel` extends its existing transfer mutex to stream a
download through SAF or stage one uniquely named share file under the existing
FileProvider. Partial plaintext output is deleted on every non-success path.

The repository no longer owns a one-second clock flow. Persisted time-entry
changes update the snapshot, while Home locally derives running elapsed text.
Search and Insights use one latest-wins job each and perform CPU work on
`Dispatchers.Default`. Room FTS and startup deferral remain conditional on
failed physical measurements.

Release packaging supports arm64-v8a, x86_64, and a universal 64-bit fallback.
Only proven-unused Bouncy Castle resources are excluded; crypto libraries,
parameters, and the Tink keep rule remain. A separate `com.android.test`
Macrobenchmark module and benchmark-only signature-protected fixture receiver
measure empty/500/5,000-record datasets. Emulator execution is functional
smoke; fixed API 36 arm64 evidence owns percentile decisions.

Trust-boundary limits are 512 MiB for `.otvault`, 500 new projects and 1,000
new tags per bounded Tasks CSV import, and 10 MiB for HTML. Reminder and widget
actions carry a revocable predicate to the repository write mutex and recheck
it immediately before mutation. Biometric success is bound to the current
foreground prompt generation, and passive reminder/widget content is
concealed immediately on background. The owner-reported process-death device
gate passed. CodeQL, high-severity dependency review, SHA-256 Gradle dependency
verification, a checksum-pinned versioned wrapper cache, release SBOM, and
per-ABI size checks join the release evidence. The three-APK verifier now
requires one independent owner certificate input and matches each filename to
its exact `aapt2 native-code` set: arm64 only, x86_64 only, or both for the
universal APK. The real owner-only proof remains pending. Room stays v9 and
authenticated backup format stays v1.

`DefaultRemoteBackupRunner` captures the current local generation under the
existing serialized publication gate and publishes an execution sequence with
that immutable start generation. `RemoteBackupRuntime` consumes completion by
sequence and schedules any later generation, so a flow-observation delay
cannot mark an edit made during an active run as already attempted.

Local implementation evidence and the still-pending physical API 36,
credentialled provider, browser/accessibility, benchmark-threshold, signer,
applicable PR dependency-review, and API 37 canary gates are separated
explicitly in
`docs/qualification/onboarding-dashboard-nfr-acceptance.md`. The original and
closing findings are remediated; post-fix scans reported zero findings.
Security runs `32615516366`, `32617307907`, and exact-head `32617911327`
passed. Android run `32615516358` proved that the `6cd690e` `--no-parallel`
experiment did not
serialize UTP work: at least four connected tasks began within seven seconds
before the API 37 canary lost its activity/package services and ran zero tests.
`afb1d93` removes the ineffective flag; this is rollback, not serialization
proof, and no second workflow workaround is claimed. `316bd86` separately
removes the API 36 timer-test observation race, and `c649801` closes the
release-verifier ABI-label gap. Task 12 is paused at external release
qualification and documentation closure.
