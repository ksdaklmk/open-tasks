# Architecture

Open Tasks is a single-activity Compose app with unidirectional state flow.
Feature UI emits typed actions; ViewModels reduce those actions and call
`VaultRepository`. Repositories are the only domain-data source.

## Data authority

Exactly one vault is active.

- `LOCAL`: the SQLCipher Room database and encrypted app-private attachments
  are authoritative.
- `DRIVE_PRIMARY`: the same local database is the immediate offline cache and
  atomic outbox, while encrypted Drive app-data objects are durable authority.

Every write is represented by a `DomainCommand`. The Room implementation
updates records and appends an outbox operation in one transaction. The current
foundation seeds the sample workspace into SQLCipher once, then treats Room as
the authority for task, timer, Trash, search, and outbox state.

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
`WorkspaceSnapshot`. A status-change command accepts only a status ID; the
repository resolves the semantic category and rejects missing or archived
statuses. Entering a completed category records the completion instant,
leaving it clears that instant, and Undo restores the exact prior status and
completion time. Completing blocked work still requires explicit
acknowledgement.

Recurring tasks persist both the user-authored rule and internal series
metadata: a stable series ID, original wall-clock anchor, and occurrence
index. Completion derives the next deterministic occurrence from the original
anchor so month-end schedules do not drift and local times survive DST. The
current completion and generated occurrence are written with separate outbox
operations in one Room transaction. Completion Undo reopens the current task
and tombstones only the generated occurrence. Room schema v2 adds this series
metadata through a non-destructive v1→v2 migration.

Task deletion is a reversible timestamped write. Moving a running task to
Trash stops its timer in the same transaction, and restore clears only the
deletion timestamp. Expired Trash is purged on repository startup. An explicit
permanent delete removes task relations and appends a tombstone/outbox
operation atomically; the UI requires a confirmation because this command has
no Undo.

Project Workbench edits use complete, validated project updates. Room reads the
latest project revision, writes the project row, and appends its sync operation
atomically. The previous project value becomes an exact Undo command. Selected
project identity lives in `SavedStateHandle`, while compact and expanded
windows render the same repository state as one-pane or list/detail workbenches.

Project creation assigns the ID before execution so successful creation can
open the new workbench without a name-based lookup. Active project names are
case-insensitively unique. Archive and Restore update only the project's
timestamped lifecycle field and append an outbox operation atomically; assigned
tasks, milestones, and history never move or disappear. Home, Projects, and
project search exclude archived projects. Task context still resolves archived
project names, but new task assignment is restricted to active projects until
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
new-device recovery and multi-device merge proofs runnable as unit tests.

## Security invariants

- Database and cloud keys are independent random values.
- The local database key is wrapped by an AES-GCM Android Keystore key that is
  restricted to use after device unlock.
- Recovery uses Argon2id (64 MiB, 3 iterations, parallelism 1, 16-byte salt).
- Record encryption binds vault, object, and format identifiers as associated
  data.
- Recovery passphrases are never persisted.
- Android Auto Backup excludes databases, keys, vault files, and attachments.
- Logs and telemetry must never contain task text, account details, Drive IDs,
  attachment names, or encryption metadata.

The asset inventory, trust boundaries, adversaries, residual risks and release
gates are maintained in [Threat Model](threat-model.md).

## Sync format

Cloud objects expose `schemaVersion`, `cryptoVersion`, and
`minimumReaderVersion`. Operations use hybrid logical clocks plus device ID
tie-breaking. Scalar fields merge independently; sets use timestamped
add/remove operations; immutable notes/time entries merge additively; newer
deletes dominate until an explicitly newer restore.

Drive transport is intentionally not wired to credentials in this foundation.
`CloudObjectStore` and `SyncCoordinator` remain the production seams for a
later OAuth-configured slice.
