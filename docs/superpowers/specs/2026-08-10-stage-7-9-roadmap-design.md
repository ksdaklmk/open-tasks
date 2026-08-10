# Stage 7–9 Roadmap Design — Ergonomics, Planning Surfaces, Automation

- Date: 10 August 2026
- Status: approved by the user in the brainstorming session of 10 August
  2026 (stage contents and standing assumptions approved at the first
  sitting; the backlog pool, parked items, sequencing, risks, and exit
  criteria approved at the resumed sitting the same day).
- Authority: this document is the roadmap authority for Stages 7, 8, and
  9. It pins stage scope, ordering, bounds, and exit criteria. Each stage
  still receives its own detailed design spec in `docs/superpowers/specs/`
  and plan in `docs/superpowers/plans/` before execution; the open
  design-time decisions listed at the end resolve in those stage specs.

## Context and verdict

A competitive feature analysis of 10 August 2026 (Asana, Jira,
monday.com, ClickUp, Todoist, Notion) produced a published reference
report
(https://claude.ai/code/artifact/e7363086-75c8-45bd-8b91-bc3809ff1a9e)
and a code-level feature inventory of this app. Verdict: solo-use parity
is largely won; the credible gaps are list ergonomics, capture grammar,
and view surfaces. The audit also surfaced the Tasks date-chip defect
(the Overdue chip filters by priority, Today matches any task with a due
date, Upcoming any task with a start date), recorded as live backlog
item 3 in `HANDOFF.md`; its fix ships immediately and outside these
stages.

## User profile and direction rulings

- The user works mainly in project boards and Schedule/planning and
  feels near-term pull for Gantt/timeline and automation rules.
- Roadmap shape B — cheap wins first — was chosen over the pain-first
  and canvas-first alternatives: Stage 7 is a no-schema ergonomics
  sweep, Stage 8 planning surfaces, Stage 9 the single Room v10 wave.
- Custom fields stays parked with a written reopen condition (below).
- All new or restyled visualisations use the dot-matrix / dotted-area
  language: unit dots and dot-run bars in the ember system, with density
  and shape carrying the signal (user directive, 10 August 2026).

## Standing assumptions

- The chip fix ships immediately, outside the stages, and creates the
  shared date-bucket rule in `core:domain` that Stage 7 sort/group and
  Stage 8's month view then reuse.
- Every durable-schema need batches into the one Room v10 migration in
  Stage 9. Stages 7 and 8 must prove schema-drift-clean at their gates.
- Each stage ends with qualification and a sideload release per
  `RELEASING.md`, bumping `versionCode` by exactly 1.
- The Actions billing restore and the Dependabot queue (#14–#19)
  precede Stage 7 execution, so every stage runs with live CI and
  current dependencies.
- Standing architecture rulings hold throughout: offline-first,
  encrypted Room as the sole live structured-data authority, no
  bidirectional sync, create-only Drive with `drive.appdata` as the sole
  scope, sideload-only distribution.

## Stage 7 — ergonomics sweep

No schema or backup-format change anywhere in this stage.

- **Sort and group controls** for the Tasks list, workbench list, and
  in-column board ordering. Attribute sorts only; manual ordering is
  excluded (see the manual-ordering clarification below).
- **Saved-view filters v2**: due-range, priority, workflow status, and
  chosen sort join the persisted query. Payload codec format v2 with
  strict fail-closed decode of v1 and v2; bounds 20 views / 64-char
  name / 500-char query unchanged. The content-based `SAVED_VIEW`
  journal fingerprint already journals richer payloads, so no backup
  change follows.
- **Quick Add grammar**: `#project`, `@tag`, `!priority`, recurrence
  phrases mapping onto the existing `RecurrenceRule`, and `~estimate`.
  `Locale.ROOT` for all token matching per the Stage 6 lesson, and the
  same suggestion-chip plus span-strip confirm pattern as
  natural-language dates, so tokens never silently rewrite a title.
- **Search ranking**: exact > prefix > word boundary > substring, inside
  the existing 50-result cap.
- **Theme preference**: System/Light/Dark. Device-local state only —
  never vault data and never backup content. Whether forcing dark is
  promoted beyond best-effort is a stage-design decision.
- **Task duplication**: copies title, description, priority, estimate,
  tags, unticked checklist, and dependencies; excludes completion state,
  activity, time entries, and attachments. Whether recurrence copies is
  a stage-design decision.
- **Insights dot-matrix restyle**: unit dots and dotted-area trends in
  Compose Canvas, Insights scope only, chart-to-table toggle retained.

## Stage 8 — planning surfaces

No durable schema change anywhere in this stage.

- **Month calendar view**: a read-only `WorkspaceSnapshot` projection
  with start-else-due placement, dot-density day cells, and tap-through
  to the task. No parallel store.
- **Drag-to-reschedule**: reuses the Stage 6 board-drag infrastructure
  across week columns, month cells, and the unscheduled tray.
  Tap-to-edit remains the complete fallback from day one. Default drop
  time and due-only semantics are stage-design decisions.
- **Gantt-lite per-project timeline**, read-only in v1: start–due bars
  as dot runs, milestone diamonds, blocked markers, and tap-to-highlight
  of a dependency chain. No arrow routing and no bar drag — edits go
  through the existing editors.
- **Daily digest notification**: opt-in, reusing `computeTodayProjection`,
  generic lock-screen copy only, boot and time-change re-arm on the
  reminder-scheduler precedent.

## Stage 9 — board flow and automation (the Room v10 wave)

The stage's first task is Room v10, landing alone: an
`automation_rules` table with a new backup record family, the My Day
ordered store, and WIP limits amending the workflow-status payload
inside its existing family — one exported schema, one non-destructive
migration, one deterministic fixture regeneration, one recovery-import
extension. Everything else in the stage builds on that boundary.

- **Automations-lite**: a fixed rule menu, trimmed at stage design to
  roughly four rules from these candidates: auto-archive completed after
  N days, stale marking, on-enter-status side effects, and auto-remove
  completed from My Day. Bounded at roughly 20 rules. Rules fire at
  deterministic reconcile points and act only by dispatching ordinary
  `DomainCommand`s, so journaling, undo, and activity attribution hold
  unchanged. Rule evaluation is single-pass: a rule's output never
  re-enters rule evaluation in the same pass. This single-pass rule is
  pinned here, not open.
- **Board WIP limits**: soft per-column limits — confirm when moving
  over the limit, never block.
- **My Day**: a curated ordered plan on Home, bounded at roughly 200
  task IDs. Manual rank exists only there.
- **Subtasks UI**: lights up the dormant `parentTaskId` end-to-end —
  `SetTaskParent` with cycle and depth guards mirroring
  `DependencyRules`, `CreateTask(parent:)`, a detail sub-task section,
  list indentation, and board-card rollups. No migration (the column
  exists); the full dual-engine command tax applies.

### Manual-ordering clarification

Stage 7 ships attribute sorts only. The only manual rank in this
roadmap is My Day's ordered store in Stage 9. In-column board manual
ordering is not scheduled anywhere; if it is wanted later, it builds on
that store as a separate, future decision.

## Backlog pool (deliberately unscheduled)

Two audit recommendations stay out of Stages 7–9 and remain alive in
the `HANDOFF.md` backlog:

- **Recurrence skip/pause** — "skip next occurrence" and "pause series"
  controls on recurring tasks. Real command-layer work (both engines,
  undo, journal) for a pull not yet felt. If pull appears, its natural
  ride is the Stage 9 command wave; otherwise it waits.
- **SAF-folder second backup target** — a second create-only backup
  destination (local or NAS folder via SAF) beside Drive. A whole
  transport surface plus recovery-source selection for redundancy no
  current failure has demanded. The `.otvault` export already covers
  the "my data outside Google" need.

## Parked: custom fields

Parked with this written reopen condition: a concrete field the user
needs repeatedly that priority, tags, estimate, and milestones cannot
express. Until a real field name shows up more than once, no schema, no
editor UI, no filter plumbing.

## Sequencing

1. The chip fix ships now, outside the stages, seeding the shared
   date-bucket rule in `core:domain`.
2. Actions billing restore, then the Dependabot six (#14–#19) — both
   before Stage 7 execution.
3. Stage 7, then Stage 8, then Stage 9 — each its own brainstorm →
   spec → plan → execution, ending with qualification and a sideload
   release per `RELEASING.md`. Three releases, not one big bang.
4. Every durable-schema need waits for the single Room v10 wave in
   Stage 9.

## Risks

- **Stage 7 — saved-view payload v2** is the one format-adjacent move.
  Mitigation: strict fail-closed decode of v1 and v2 forever, bounds
  unchanged, recovery-import tests for both versions; the content-based
  fingerprint already journals richer payloads.
- **Stage 7 — Quick Add grammar false positives**: tokens firing inside
  ordinary titles. Mitigation: the suggestion-chip plus span-strip
  confirm pattern, `Locale.ROOT`, and grammar torture tests.
- **Stage 8 — drag-to-reschedule**: Stage 6's board drag took three
  review rounds (clipping, stale callbacks, RTL). Reusing it across
  week, month, and tray surfaces will surface the same class of bugs;
  budget fix rounds and keep tap-to-edit as the complete fallback.
- **Stage 9 — the v10 wave is the stage**: migration, exported schema,
  new backup families, the WIP payload amendment, fixture regeneration,
  and recovery import concentrate in one boundary. Mitigation: it goes
  first in the Stage 9 plan and lands alone.
- **Stage 9 — automation loops**: rules dispatching commands that
  could trigger rules. Mitigation is structural: fixed menu, the ~20
  rule bound, deterministic reconcile-point firing, and single-pass
  evaluation.
- **Stage 9 — subtasks blast radius**: `parentTaskId` touches list,
  board, detail, bulk operations, completion semantics, and both
  engines. Cycle and depth guards mirror `DependencyRules`; the
  dual-engine command tax is priced in.
- **Cross-cutting**: CI billing is an external dependency (hence the
  sequencing gate); the F6 canary lane stays observe-only red; every
  new command pays dual-engine parity.

## Per-stage exit criteria

Uniform for all three stages (the Stage 5/6 discipline, unchanged):

- Full CI gate green (`testDebugUnitTest lintDebug :app:assembleDebug`).
- Six-module connected gate on the sole disposable read-only AVD.
- Schema-drift, fixture, workflow-pinning, release-scope,
  production-logging, and privacy scans clean.
- Whole-stage independent review closed with zero Critical or Important
  findings.
- Qualification record in `docs/qualification/`.
- Sideload release tagged per `RELEASING.md`.
- `HANDOFF.md` updated.

Stage-specific additions:

- **Stage 7**: the schema-drift gate proves zero durable change; v1 and
  v2 saved-view decode and recovery tests green; the theme preference
  provably absent from vault and backup content (privacy scan);
  duplication copies exactly the ruled field set.
- **Stage 8**: still zero durable change; the digest strictly opt-in
  with generic lock-screen copy; no new permission beyond the
  notification and alarm precedents; month and Gantt proven read-only
  by projection unit tests; `drive.appdata` untouched.
- **Stage 9**: exported `10.json` plus a non-destructive migration and
  preservation test; deterministic fixtures regenerate byte-identically;
  recovery import covers the new families; rule, My Day, and WIP bounds
  in both repository companions; subtask cycle and depth rejections
  tested in both engines; WIP confirm-over-limit never blocks.

## Open design-time decisions (resolve in stage specs)

- Stage 7: whether forcing dark is promoted beyond best-effort; whether
  task duplication copies recurrence.
- Stage 8: drag-to-reschedule default drop time; due-only semantics.
- Stage 9: the exact automation rule menu (trim to roughly four from
  the listed candidates).
