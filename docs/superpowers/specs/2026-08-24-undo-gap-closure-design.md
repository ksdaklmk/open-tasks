# Undo Gap Closure

Date: 2026-08-24. Status: approved design, pre-implementation.
Authority: this is the first delivery slice in the agreed sequence:
Undo gaps, migration friction, version/trust footer, then Play internal beta.

## Outcome

Undo must restore the complete task relationship for the two remaining
subtask gaps:

1. Moving a child task to another project detaches it; Undo must move it back
   and reattach it to its former parent.
2. Restoring a binned child can safely detach it from an unavailable or moved
   parent; Undo must put it back in the Bin with the former parent link when
   that parent record still exists.

The fix stays in the existing dual-repository command flow. There is no UI,
Room schema, backup-format, dependency, or release-version change.

## Root cause

Both repository engines already make the correct forward safety decisions:

- `MoveTasksToProject` clears `parentTaskId` when the parent does not move
  with the child.
- `RestoreTask` clears `parentTaskId` when restoring it would expose a child
  beneath a missing, binned, or cross-project parent.

Their inverse commands do not carry that relationship state:

- the move inverse is `UpdateTask`, whose repository-produced snapshot omits
  `parentTaskId`;
- the restore inverse is `DeleteTask`, which re-bins the now-detached row.

The smallest root-cause fix is to add optional, repository-produced parent
metadata to those two existing inverse command shapes.

## Command contract

Add `restoreParentTaskId: TaskId? = null` to:

- `DomainCommand.UpdateTask`; and
- `DomainCommand.DeleteTask`.

The field is Undo metadata, like `UpdateTask.restoreStatusId` and
`restorePastReminder`. UI code leaves it null. Null preserves today's normal
command behavior; a non-null value asks the repository to restore that exact
parent link as part of the command's existing atomic write.

No new command, coordinator, table column, serialized record, or abstraction
is introduced.

## Move Undo flow

`MoveTasksToProject` already computes a per-task `detach` decision. When that
decision is true, its `UpdateTask` inverse captures the task's former
`parentTaskId`; otherwise it leaves the new field null.

`UpdateTask` validation resolves the requested parent against the task's
requested project, not the task's current destination project. The existing
one-level `SubtaskRules` remain authoritative: the parent must be live, in the
requested project, not itself a subtask, and the task must not have children.
Room also rechecks the authoritative parent row using its existing live-row
pattern before writing.

On success, `UpdateTask` restores the project, mapped status, milestone,
reminder, and parent in the same repository operation. The Undo batch remains
all-or-nothing because its existing preflight calls the same validation before
the first inverse is applied.

## Restore Undo flow

When `RestoreTask` must detach a child, it captures the former parent ID in its
`DeleteTask` inverse only when the parent record still exists. If the parent
was already purged, Undo cannot recreate that identity and retains today's
safe-detached behavior.

Before a metadata-bearing `DeleteTask` writes, the repository verifies that
the parent record still exists and that restoring the historical link would
not create a self-link or a second subtask level. The parent may remain binned
or may now be in another project because the child is also being returned to
the Bin; a later restore will apply the existing safe-detach rule again.

On success, binning and parent restoration happen in the same write and
journal generation. A failed validation performs no task, activity, or
journal write.

## Engine parity and observability

The in-memory and Room repositories implement the same command semantics and
return the same rejection categories. Existing user-facing messages and
snackbar behavior remain unchanged. Activity entries keep their current
`PROJECT_MOVED`, `RESTORED`, and `BINNED` meanings; this change only makes the
stored relationship match the inverse operation.

The existing command path continues to own revision increments, backup
journaling, and Room transaction boundaries. Parent metadata lives only in
the in-process Undo command, so exports and recovery compatibility are
unchanged.

## Verification

Extend the existing subtask repository tests rather than create a new test
suite. Both engines must prove:

1. moving a child alone detaches it, and executing the returned Undo restores
   its prior project, workflow status, and parent;
2. restoring a child while its parent is binned detaches it, and executing
   that restore's Undo re-bins it with the prior parent link;
3. the same restore/Undo round trip works when the live parent moved while the
   child was binned; and
4. if required parent state disappears or becomes structurally invalid before
   Undo, the command rejects without a partial state change.

Run the repository host tests, lint, debug assembly, and Android-test
compilation. Run connected Room parity only on an explicitly safe disposable
target; the protected emulator remains untouched.

## Acceptance criteria

- Both known Undo gaps round-trip the complete task relationship in both
  repository engines.
- Invalid delayed Undo fails closed and atomically.
- No UI callback or copy changes are required.
- No schema migration, backup fixture, permission, dependency, or version bump
  is introduced.
- The normal host verification gate passes, and Room instrumented tests at
  least compile when no disposable device is available.
