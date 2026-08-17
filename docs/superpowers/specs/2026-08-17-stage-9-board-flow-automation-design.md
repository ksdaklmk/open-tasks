# Stage 9 — Board Flow and Automation (the Room v10 wave)

Date: 2026-08-17. Status: approved design, pre-implementation.
Authority chain: this spec details Stage 9 of the approved roadmap
(`docs/superpowers/specs/2026-08-10-stage-7-9-roadmap-design.md`) and is
the Stage 9 authority beneath it. Implementation is gated on the
`v1.2.0` tag per `HANDOFF.md`; until the tag exists this stage may
advance only as documentation on the `stage-9` branch.

## Scope

One Room v9→v10 migration carrying every durable need of the stage,
then four features on that boundary:

- **Automations-lite**: a fixed rule menu, evaluated single-pass,
  acting only by dispatching ordinary `DomainCommand`s.
- **Board WIP limits**: soft per-column limits — confirm over limit,
  never block.
- **My Day**: a curated, manually ordered plan on Home (~200 task
  IDs), the roadmap's only manual rank.
- **Subtasks**: the dormant `parentTaskId` lit up end-to-end, one
  level deep.

Non-goals (unchanged from the roadmap): no new permission, Drive
scope, network path, or exported surface; no bidirectional sync; no
in-column board manual ordering; custom fields stay parked.

## Rulings made in this brainstorm

1. **Rule menu trimmed to three**: auto-remove completed from My Day,
   on-enter-status side effects, stale marking. Auto-archive is
   dropped — no task-level archive exists, completed tasks are
   already invisible on Board and Home, and a true archive would
   amend the heavily journaled TASK family for thin payoff.
2. **On-enter side-effect verbs**: add a tag, add to My Day, set/shift
   due date. Set-priority is dropped (its only backing is the
   whole-field `UpdateTask`, which races an open editor).
3. **Stale is a projection, not a write**: computed at render time
   from last-touched vs the rule's threshold. No command, no journal
   churn, no self-refresh trap (a marking write would advance the
   revision that defines staleness), no reserved-tag collision with
   the 50-tag budget or Quick Add grammar.
4. **My Day day semantics**: the list persists day to day; completing
   a member keeps it on today's list, dimmed; the auto-remove rule
   (when enabled) drops members completed before today at the first
   reconcile of a new day.
5. **Home integration**: My Day replaces the derived "Today focus"
   section as Home's primary list. The Plan button opens My Day
   curation instead of navigating to Tasks. The empty state offers
   the derived today picks as one-tap adds.
6. **Subtask completion**: completing a parent with open children
   requires a confirm (acknowledge flag on the command,
   repository-enforced), mirroring the blocked-completion pattern.
   Children are never auto-completed.
7. **Subtask deletion**: binning a parent bins the subtree in one
   transaction. Each row is individually restorable; restoring a
   child while its parent is still binned detaches it.
8. **Subtask depth**: exactly one level. A parent cannot itself have
   a parent; a task with children cannot be given a parent. The
   cycle guard collapses to those two checks plus self-reference.
9. **Rule engine placement**: inside the repository — post-dispatch,
   same transaction, same journal generation, outputs applied via
   internal dispatch (single-pass by construction). An app-level
   coordinator was rejected: status changes dispatch from many sites
   (board, Tasks screen, widget, reminder actions, bulk) and a
   coordinator would have to wrap them all, plus it inherits the
   eventual-consistency await hazard and can lose a firing to a
   crash between trigger and effect.
10. **WORKFLOW_STATUS compatibility**: dual-arity within backup
    format v1. The per-family schema accepts the legacy 9-field and
    the new 10-field shapes forever; the encoder emits only
    10-field; an absent `wipLimit` imports as no-limit. No
    formatVersion/minimumReaderVersion machinery.

Standing defaults recorded with the rulings: the Today widget and
daily digest stay on `computeTodayProjection` unchanged (their
privacy contracts are pinned); the Tasks CSV schema stays frozen and
parent-blind; project templates do not capture WIP limits (template
format v1 untouched); `DuplicateTask` keeps copying `parentTaskId`
(duplicating a child yields a sibling; duplicating a parent does not
deep-copy children); bulk commands never auto-expand to children;
recurrence occurrences keep their parent; no activity actor/origin
field is added — rule-driven activity keeps standard bodies.

## Room v10 (the stage's first task, landing alone)

- `VAULT_DATABASE_VERSION` 9 → 10. One additive `MIGRATION_9_10`:
  - `CREATE TABLE automation_rules` (below).
  - `CREATE TABLE my_day_entries` (below).
  - `ALTER TABLE workflow_statuses ADD COLUMN wipLimit INTEGER`
    (nullable, no default rewrite).
- No new index on `parentTaskId`: subtask reads happen on in-memory
  snapshots; the only SQL touching the column (purge detach,
  import-undo child guard) is rare.
- Exported `10.json`; `1.json`–`9.json` stay byte-identical
  (`scripts/check-schema-drift.sh`).
- The migration **stamps the vault row marker to 10** (the 7→8
  precedent). Rationale: the first post-upgrade baseline re-encodes
  every record with the 10-field workflow shape and the new
  families, so a v9 app can never recover v10 data regardless;
  stamping makes the refusal legible upfront through the
  `normalizeForRecovery` gate instead of a mid-decode field-count
  error. `RECOVERED_SCHEMA_VERSION` widens automatically.
- Instrumented migration test in the `migrate8To9…` byte-capture
  mold: all prior bytes preserved, new tables empty, `wipLimit`
  null, row marker asserted 10; JVM gate tests for the recovery
  bound.

### `automation_rules`

Flat discrete columns, no payload blob (so the backup record stays a
strict flat field list like WORKFLOW_STATUS, with no versioned codec
of its own):

- `id` TEXT PK; `workspaceId` TEXT (direct backup-capture
  attribution — no journal-evidence dance); `type` TEXT (enum by
  name); `enabled` INTEGER; `projectId` TEXT NULL; `statusId` TEXT
  NULL; `tagId` TEXT NULL; `dueInDays` INTEGER NULL;
  `thresholdDays` INTEGER NULL.

Five type values realize the three menu items:

| Type                    | Required config       | Effect |
|-------------------------|-----------------------|--------|
| `ON_ENTER_ADD_TAG`      | `statusId`, `tagId`   | entering the column adds the tag |
| `ON_ENTER_ADD_TO_MY_DAY`| `statusId`            | entering the column adds to My Day |
| `ON_ENTER_SET_DUE`      | `statusId`, `dueInDays` | entering the column sets due to today+N at 17:00 |
| `MY_DAY_AUTO_REMOVE`    | none                  | enables the day-rollover sweep |
| `STALE_BADGE`           | `thresholdDays`, optional `projectId` | stale projection threshold |

Validation (both engines and the record codec): per-type
required/forbidden config exactly as above; `dueInDays` in 0..365;
`thresholdDays` in 1..365; at most **20 rules** total. A rule whose
referenced tag, status, or project no longer exists fails closed:
it is skipped at evaluation and rendered as broken in the editor,
never deleted automatically.

Commands: `CreateAutomationRule`, `UpdateAutomationRule`,
`DeleteAutomationRule`, each with repository-produced undo and full
dual-engine parity. Editor UI: an "Automations" screen reachable
from More listing all rules with add/edit/enable/delete.

### `my_day_entries`

- `taskId` TEXT PK; `rank` TEXT. No revision columns.
- Bound: **200 members**, validated on the add path in both engines;
  an automation add beyond the cap is silently skipped (fail
  closed), a manual add is rejected with a message.
- Purging a task deletes its row in the same transaction, journaled
  as a family DELETE, in both engines (the `retired_blob_sets`
  cleanup pattern). Binned members keep their row and are filtered
  from projection; completed members render dimmed until swept.

## Backup families and the WORKFLOW_STATUS amendment

Two new families, `AUTOMATION_RULE` (identity = rule id) and
`MY_DAY` (identity = taskId), both **content-fingerprinted** (the
SAVED_VIEW tier): any edit journals an upsert and there is no
revision-bump discipline to forget. Each walks the complete Stage 5
add-a-family checklist: `BackupRecordFamily` enum value,
`toBackupRecordV1` encoder, strict positional `RecordSchema` plus
semantic bounds validation, `snapshots()`/`requireRecord()` and
`BackupMutationDao` wiring, `BackupCaptureDao` capture (rules via
`workspaceId`, My Day via task join) and `allRecords`, importer
write/delete arms and `toEntity`, `RecoveryImportDao`
insert/upsert/delete plus `structuredRecordCount` widening,
`WorkspaceSnapshot` fields, `InMemoryBackupJournal.toBackupRecords`
arms, `StagedVaultVerifier` coverage, and
`generate-stage2-backup-v1-fixtures.mjs` builders with regenerated
goldens (`BackupPayloadGoldenTest` digests and counts).

WORKFLOW_STATUS amendment, dual-arity within format v1:

- Encoder emits the 10-field shape (`wipLimit` appended, nullable).
- `RecordSchema` gains optional-trailing-field support; the
  validator accepts exactly the 9-field and 10-field arities for
  this family, forever. Canonical re-encode equality holds because
  encoding serializes the decoded record's own field list verbatim.
- Importer treats the absent field as null (`BackupRecordFields`
  gains an absent-tolerant nullable read for trailing optionals).
- Explicit tests: a legacy 9-field record decodes and imports; a
  10-field record round-trips; an 11-field record fails closed.

Frozen fixture sets stay frozen: `.otvault` v1 and the Drive/cloud
fixtures are not regenerated; a compat test proves an old archive
containing 9-field WORKFLOW_STATUS records still imports.

## The rule engine

A pure `core:domain` function:

    evaluateAutomationRules(rules, trigger): List<DomainCommand>

shared verbatim by both repositories. Invocation contract:

- **Fires only for an external `execute()` call whose command is a
  status transition** — `ChangeTaskStatus`, `CompleteTask`,
  `CompleteTasks` — and only for tasks whose `statusId` actually
  changed in that dispatch. It runs after the triggering dispatch
  succeeds, inside the same database transaction and the same
  journal generation.
- Outputs are applied via **internal dispatch** (never `execute()`,
  whose write mutex is non-reentrant). Internal dispatch never
  evaluates rules, so undo replay (`UndoBatch`), rule outputs,
  recurrence spawns, imports, recovery, template instantiation, and
  `MoveTasksToProject` remaps can never fire rules. Single-pass is
  structural, not policed.
- Deterministic order: matching rules evaluate in ascending rule-id
  order; for bulk completion, per task in the command's ID order.
- Idempotent verbs: add-tag skips if the tag is already present;
  add-to-My-Day skips if already a member or at the 200 cap;
  set-due sets due to today+N at 17:00 in the current device zone
  through `SetTaskSchedule` semantics (overwriting any prior due).
  Skips are silent; the triggering command still succeeds.
- Undo composition: the triggering command's `CommandResult` undo
  becomes an `UndoBatch` of the trigger's inverse plus each rule
  output's inverse, so the user's single snackbar Undo reverts the
  move and everything the rules did.

### The My Day sweep

`SweepMyDay` is an ordinary `DomainCommand`: remove every member
whose `completedAt` is before the start of today in the repository
zone (the projection-clock override affects display only). It is
idempotent by construction, so it needs no mark-handled ledger. The
app dispatches it from the existing `MainActivity.onStart` reconcile
block (beside the digest reconcile, in its own `runCatching`,
tolerant of no active vault) only when a `MY_DAY_AUTO_REMOVE` rule
is enabled. The dispatch is silent — message and undo are dropped,
as FocusCoordinator's dispatches already are; journaling and
activity behavior hold unchanged. A no-op sweep allocates no journal
generation (existing lazy-generation behavior).

### The stale projection

Never enters the engine. A pure `core:domain` predicate: a task is
stale when open, not binned, and
`max(revision wallTime, latest activity createdAt)` is older than
the matching `STALE_BADGE` rule's threshold (project-scoped rule
beats global for tasks in that project). Rendered as a badge/tint on
task rows in the Tasks list, workbench, and board cards. Touching
the task clears it by definition.

## Board WIP limits

- `wipLimit: Int?` on `WorkflowStatus` end-to-end (model, entity,
  mappers, amended backup record). Meaningful bound: 1..200.
- Editing: a limit field on each `WorkflowStatusEditorRow` in the
  existing WorkflowEditorSheet, hidden for COMPLETED-semantic
  columns (their board count is always zero). New command
  `SetWorkflowStatusWipLimit(statusId, limit?)` writes through
  `persistWorkflowStatuses` so the revision bump journals the
  change; undo is the standard `RestoreWorkflowStatuses`.
  `CreateWorkflowStatus` does not take an initial limit.
- Enforcement, both engines, inside `changeTaskStatus`: count the
  destination column on the `boardColumns` basis (same project,
  open, non-deleted, non-completed), in-transaction. If the move
  would exceed the limit and `acknowledgeWipLimit` is false, reject
  with a new confirm-required `RejectionReason`. Only an explicit
  `ChangeTaskStatus` into a non-COMPLETED-semantic destination
  enforces; completion, bulk remaps, recurrence spawns, and rule
  outputs (whose verbs never change status) bypass — that is the
  "soft, never block" ruling made concrete.
- UI: `ChangeTaskStatus` gains `acknowledgeWipLimit: Boolean =
  false` beside `acknowledgeBlocked`. The ViewModel stashes the
  rejection as a pending confirm; the dialog's confirm re-dispatches
  acknowledged — the exact blocked-completion pattern. **Board
  moves are rerouted through the gated ViewModel path** (today they
  bypass it via generic `execute`, so even the blocked-completion
  confirm never appears for board drops — a latent gap this fixes).
  Menu moves and accessibility custom actions get the same dialog
  for free because enforcement is repository-side.
- Board column headers render "n / limit" and an over-limit tint.
  `BoardColumn` carries the limit and open count from
  `boardColumns` — the single authority; no counting in feature
  code.

## My Day

- Home's Today-focus section is replaced by **My Day**: members in
  manual rank order via the shared `TaskRow`, completed members
  dimmed, binned members filtered. Reorder by drag using the shared
  `PlanningDrag` root primitives, layered over a complete 48 dp
  overflow fallback (move up / move down); drag adds no command
  arithmetic or persistence state of its own.
- The Plan button opens a curation sheet: current members
  reorderable and removable, plus suggestions (tasks due or
  starting today, and overdue tasks, from the existing snapshot)
  as one-tap adds. The same suggestions fill the empty state.
  "Add to My Day" also appears in task-row overflow menus and the
  task detail screen.
- Commands (full dual-engine parity, repository-produced undo):
  - `AddTaskToMyDay(taskId)` — appends via `rankAfter`; rejects
    over the 200 bound, on binned tasks, and on duplicates.
  - `RemoveTaskFromMyDay(taskId)`.
  - `MoveMyDayEntry(taskId, afterTaskId?)` — manual reorder. Needs
    the one new rank primitive: `rankBetween(a, b)`, a
    lexicographic-midpoint generator in `core:domain` beside
    `rankAfter`. Only moved rows journal. If a midpoint would
    exceed the rank-string bound, the handler re-ranks the whole
    list in the same transaction.
  - `SweepMyDay` (above).
- Projection: repository-resolved into the snapshot like
  `HomeSnapshot` today (ordered, pruned of dangling/binned IDs,
  completed flagged for dimming), mirrored in both engines. The
  widget and digest keep consuming `computeTodayProjection`
  unchanged.

## Subtasks

- `SetTaskParent(taskId, parentTaskId?)` with one-level guards, each
  a distinct rejection surfaced inline (the dependency-feedback
  pattern): parent exists and is not binned; same project; parent
  has no parent; the task has no children; no self-reference.
  `parentTaskId = null` detaches. Undo restores the prior parent.
- `CreateTask` gains optional `parentTaskId` under the same guards;
  the child inherits the parent's project.
- Task detail gains a **Subtasks section**: children with complete
  toggles, a quick-add creating `CreateTask(parent:)`, and an
  attach-existing picker filtered to eligible tasks.
- Ordering/indentation: `arrangeTasks` stays the single authority,
  extended to nest each child directly under its parent within the
  parent's group (children in comparator order); a child whose
  parent is not in the filtered result renders flat. The shared
  `TaskRow` gains an indent parameter; the Tasks list and workbench
  consume the same output. `searchWorkspace` stays flat. Recovered
  foreign data may legally hold deeper acyclic trees (the backup
  validator accepts them); commands still reject depth > 1, and
  rendering clamps to a single indent level.
- Board: children are ordinary cards (they own a status). The
  parent's card gains a "done/total" subtask rollup chip in the
  metadata row of `BoardTaskContent`, so it renders in both the
  card and the drag preview. Cross-project pairs cannot arise
  (same-project guard); moving a parent to another project
  cascades the move to its children; moving a child alone to
  another project detaches it.
- Completion: completing a parent with open children rejects with a
  new confirm-required reason plus an acknowledge flag on the
  command — same dialog machinery as WIP and blocked completion,
  and the unified board path shows it everywhere. `CompleteTasks`
  applies the same confirm when a listed parent has open children
  outside the completion set, mirroring its existing blocked-task
  handling (the plan verifies that bulk mechanism and keeps
  parity with it).
- Deletion: `DeleteTask`/`DeleteTasks` on a parent bins the subtree
  in one transaction; the undo restores the subtree. Restoring a
  child from the Bin while its parent is still binned detaches it;
  restoring the parent alone restores only the parent. Purge-time
  detach behavior is unchanged and stays covered by
  `StagedVaultVerifier`.

## Bounds (enforced in both repository companions)

- 20 automation rules; `dueInDays` 0..365; `thresholdDays` 1..365.
- 200 My Day members; rank strings within the existing 200-char
  record bound.
- WIP limit 1..200 per column; confirm over limit, never block.
- Subtask depth exactly 1; all Stage 4–7 bounds unchanged.

## Testing

- Migration: instrumented byte-capture test for 9→10; JVM
  recovery-gate tests for the stamped marker.
- Codec: dual-arity WORKFLOW_STATUS decode tests (9 accepts, 10
  accepts, 11 fails); new-family schema and bound validation arms;
  golden digests regenerated only via the fixture generator.
- Families: `AUTOMATION_RULE` and `MY_DAY` behavior tests in the
  `RetiredBlobSetFamilyTest` mold (upsert on edit, delete on
  remove/purge, journal object types); recovery-import round trips;
  frozen-archive compat test.
- Engine: single-pass proof (a rule output that would match a rule
  never fires one), firing-condition exclusions (undo replay,
  recurrence spawn, project-move remap, import), idempotent-verb
  skips, cap-skip silence, `UndoBatch` composition reverting
  trigger plus effects, deterministic rule order.
- Sweep: idempotence, zone-boundary behavior, disabled-rule no-op,
  no-vault tolerance at the reconcile site.
- WIP: confirm-over-limit round trip via board drag, menu, and
  a11y action; bypass paths (completion, bulk, recurrence) proven
  unblocked; count basis matches `boardColumns`.
- Subtasks: every guard rejection in both engines; subtree
  bin/restore/detach; parent-confirm flows including bulk;
  `arrangeTasks` nesting including filtered-parent flattening;
  rollup chip and indentation Compose tests with
  `OpenTasksFixtures`.
- All new UI copy in `res/values/strings.xml`; Compose tests via
  `createComposeRule` (v2), stateless screens, no ViewModel/Hilt.

## Sequencing within the stage

1. The v10 wave, alone: migration, tables, column, families,
   dual-arity amendment, fixtures, recovery import, verifier,
   dual-engine parity. Nothing else lands with it.
2. WIP limits (includes the board-path unification).
3. Subtasks.
4. My Day (store, commands, Home surface, curation).
5. Automation engine + Automations editor + sweep + stale
   projection (depends on My Day commands).
6. Qualification, sideload release per `RELEASING.md`, exit
   criteria per the roadmap (uniform set plus the Stage 9
   additions).

## Deferred to the implementation plan

- Exact user-facing strings and dialog copy (all via
  `stringResource`).
- Automations screen layout details within the dot-matrix /
  designsystem language.
- Verification of `CompleteTasks`' existing blocked-task bulk
  mechanism before mirroring it for parent confirms.
- Curation-sheet suggestion composition details (ordering, caps).
