# Architecture

Open Tasks is a single-activity Compose app with unidirectional state flow.
Feature UI emits typed actions; ViewModels reduce those actions and call
`VaultRepository`. Repositories are the only domain-data source.

## Data authority

Exactly one vault is active, and encrypted SQLCipher Room is its sole live
structured-data authority. Normal operation has no runtime authority mode and
no cloud-to-Room record path.

Every write is represented by a `DomainCommand`. The Room implementation
updates records and appends an outbox operation in one transaction. The current
foundation seeds the sample workspace into SQLCipher once, then treats Room as
the authority for task, timer/time-entry, Bin, search, and outbox state.
Existing outbox rows remain untouched during Stage 1.

Stage 2 will narrow that outbox responsibility into `BackupJournal`: an atomic
record of local generations that need backup, not a remote merge log. The
migration must preserve every existing row until a verified complete baseline
backup covers it.

The approved future service boundaries are:

```text
VaultRepository
    ├── SQLCipher Room records ─────────── sole live authority
    ├── atomic BackupJournal
    └── immutable WorkspaceSnapshot

BackupCoordinator
    ├── reads consistent snapshots and journal generations
    ├── encrypts through AuthenticatedCloudObjectCodec
    └── writes BackupObjectStore

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

These services are approved, not implemented. `RecoveryCoordinator` will be
the only component allowed to reconstruct Room from backup data or a restored
portable package. `BackupCoordinator`, `PortableBackupPublisher`, and
`AttachmentBlobCoordinator` cannot mutate structured product records.

`RoomVaultRepository` owns its observation scope. Closing the repository
cancels and joins that scope before its database owner closes SQLCipher. This
ordering is part of the lifecycle contract: no Room flow collector may outlive
the connection pool during process, test, vault-switch or recovery teardown.

The task editor emits a complete validated core-field update after a short
debounce. Room reads the latest committed record before assigning the next
revision, so rapid edits cannot derive revisions or undo state from a stale UI
snapshot. Each accepted update returns the prior values as an undo command.

Checklist and tag edits use granular commands rather than replacing a complete
relation set. Room reads current checklist and tag rows inside the serialized
write path, advances the owning task revision, mutates the relation, and
appends its outbox operation in one transaction. This preserves rapid
independent taps and produces exact add/remove/edit Undo commands. Creating a
tag writes the reusable tag record and its initial task membership atomically.

Workflow statuses are first-class Room records exposed in
`WorkspaceSnapshot`. Every project owns an independent ordered workflow;
Inbox uses the same model with a `null` project scope. New projects receive
Backlog, Planned, Started, Blocked and Completed categories in the same
transaction as the project and their separate outbox operations. Status names
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
current completion and generated occurrence are written with separate outbox
operations in one Room transaction. If the current task has a reminder, the
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
a non-destructive v3→v4 migration. Task outbox payload v5 includes start and due
moments plus milestone identity, so a future backup segment cannot silently
lose scheduling or membership.

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
their independent outbox operations in one transaction.

A workspace is capped at 100 templates and each template at 500 tasks and a
2 MiB versioned payload. The strict decoder validates identifier and text
lengths, semantic workflow coverage, collection bounds, zone IDs, date ranges,
unique ranks/keys and acyclic parent/dependency graphs before instantiation.
Local payload bytes are protected by SQLCipher at rest; the upsert outbox
payload is self-contained so a future encrypted backup segment can preserve
the template without relying on a local database row. Schema v5 adds template
revision fields through a non-destructive v4→v5 migration. Capture and delete
provide repository-produced Undo; creating a project from a template follows
the existing non-Undoable project-creation contract.

Task dependencies are directed task-to-task relations. `SetTaskDependency`
adds or removes one relation through the repository, caps each task at 100
links and rejects self-links or any edge which would make the graph cyclic.
The relation write, revised task record and task outbox operation are one Room
transaction; repository-produced Undo applies the exact inverse relation.
Task outbox payload v5 carries the complete sorted dependency-ID set as well as
start, due and milestone identity.

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
Room writes both records and their independent outbox operations in one
transaction, while repository-produced Undo restores both prior values.
`SetTaskReminder` is the granular path used by notification actions such as
Snooze. Permanent task purge removes the reminder and queues its deletion in
the same transaction as the task tombstone.

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
task ID from the same snapshot. Selecting an item deep-links to the existing
task editor, so Schedule introduces no alternate mutation path.

Time entries are first-class records in `WorkspaceSnapshot`. Starting a timer
inserts an open entry; stopping it closes that same entry, and elapsed time is
derived from its original instant rather than UI state. Manual add, edit,
delete and exact restore are typed repository commands. They require an
existing non-Bin task, a strictly positive interval, a note of at most 500
characters and at most 10,000 entries per task. Each accepted Room mutation
and its time-entry outbox operation commit atomically; add, edit and delete
return repository-produced Undo.

Overlaps are preserved rather than silently truncating recorded work. The
repository deterministically sorts the complete stream, performs a linear
interval sweep and exposes conflict pairs with their overlap duration in the
snapshot. The task editor shows both an inline warning and a complete
time-entry review sheet; editing or deleting either completed entry resolves
the warning. Running entries remain read-only until their timer is stopped.
This also keeps overlapping local records visible without inventing or
discarding duration. Time-entry outbox payload v2 carries the entry ID,
task ID, source device, start/end instants and a delimiter-safe encoded note;
future backup publication must encrypt the containing operation before upload.

Moving a task to the Bin stops its timer in the same transaction, and restore
clears only the deletion timestamp. Expired Bin content is purged on repository
startup. An explicit
permanent delete removes task relations, appends relation deletion operations
where required, and appends a task tombstone/outbox operation atomically; the
UI requires a confirmation because this command has no Undo.

Project Workbench edits use complete, validated project updates. Room reads the
latest project revision, writes the project row, and appends its outbox operation
atomically. The previous project value becomes an exact Undo command. Selected
project identity lives in `SavedStateHandle`, while compact and expanded
windows render the same repository state as one-pane or list/detail workbenches.

## UI process restoration

Restoration has three explicit authorities rather than a duplicate domain
store:

- Navigation 3's serializable back stack restores the current top-level
  destination.
- `SavedStateHandle` restores selected task and project IDs across ViewModel
  recreation.
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

Project creation assigns the ID before execution so successful creation can
open the new workbench without a name-based lookup. Its project row, five
default workflow rows and six independent outbox operations are one
transaction. Active project names are case-insensitively unique. Archive and
Restore update only the project's timestamped lifecycle field and append an
outbox operation atomically; assigned tasks, workflow, milestones, and history
never move or disappear. Home, Projects, and project search exclude archived
projects. Task context still resolves archived project names, but new task
assignment and workflow editing are restricted to active projects until the
project is restored.

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
- Android backup is currently disabled. Stage 2 may enable Android Auto Backup
  only for one strictly whitelisted portable encrypted package; databases,
  WAL/SHM, preferences, keys, credentials, cache, and attachment bytes remain
  excluded.
- Logs and telemetry must never contain task text, account details, Drive IDs,
  attachment names, or encryption metadata.

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
separate full-size verified ciphertext array and transfers it at most once.

The SHA-256 field is a corruption check, not an authentication claim. Stage 1
Task 2 must bind the complete `CloudHeaderIdentity` as AEAD associated data,
verify the checksum before decryption, and translate rejection to typed
failures. Until that provider-independent codec exists, canonical frames must
not be treated as authenticated backup or blob objects.

Hybrid logical clocks and merge primitives remain implemented internal
utilities but do not define product behaviour. Future journal segments carry
local backup continuity for one active writer. Normal operation never
downloads or merges structured records.

Google authorisation and Drive transport are not wired to credentials.
Approved future transports implement separate `BackupObjectStore` and
`AttachmentBlobStore` namespaces. Their shared codec remains
provider-independent, and only `RecoveryCoordinator` may use decoded backup
data to construct a staged replacement Room vault.
