# Stage 7 Design — Ergonomics Sweep

- Date: 10 August 2026
- Status: approved by the user in the brainstorming session of 10 August
  2026 (five scoping rulings, then design batches A, B, and C each
  approved as presented).
- Authority: this document is the Stage 7 design authority under the
  roadmap spec `2026-08-10-stage-7-9-roadmap-design.md`. The roadmap's
  stage scope, bounds, sequencing, and uniform exit criteria apply
  unchanged; this spec resolves the stage's open decisions and pins
  behaviour. The implementation plan derives from this document.
- Hard constraint, restated: **no Room schema change and no
  backup-format change anywhere in this stage.** The schema-drift gate
  proves it at exit.

## Session rulings (10 August 2026)

1. **Task duplication does not copy recurrence.** The duplicate is a
   one-off task.
2. **The app is light-theme only.** The roadmap's System/Light/Dark
   preference item is replaced by pinning the light scheme
   unconditionally. No preference UI exists. This supersedes
   DESIGN.md's "dark retained as best-effort" wording, which is amended
   in this stage.
3. **Sort/group choices persist durably device-local** on the
   `AppLockSettings` SharedPreferences pattern. Stage 6's
   session-only board/list mode state is left untouched.
4. **Group-by dimensions are due bucket, project, and priority.**
   Workbench status grouping and tag grouping are excluded.
5. **Insights gets the restyle plus one new trend surface**:
   completions per day across the selected range.

Batch approvals additionally pinned: semantic-status filtering in
saved views (deviation from the roadmap's "workflow status" wording),
due-bucket filtering in saved views (relative buckets, a deviation
from the roadmap's "due-range" wording, presented and approved in
batch A), fixed sort directions with no direction toggle, the saved-view chip
identity fix, `!1` = Urgent numeric priority direction, recurrence
phrases auto-anchoring a due date, the additive `CreateTask` widening,
deletion of the dark color scheme, the duplication field set including
reminder exclusion, and the dot-matrix primitives living in
`:core:designsystem`.

## Prerequisite

The Tasks date-chip fix (live backlog item 3) ships before Stage 7
execution and creates the shared date-bucket classifier in
`core:domain`. Stage 7 requires that classifier to expose the buckets
OVERDUE, TODAY, THIS_WEEK, LATER, and NO_DATE (half-open, zone-aware,
evaluated against an injected clock). The `DueBucket` enum itself
lives in `:core:model` as shared vocabulary — `SearchQuery` and
feature modules must reference it — while only the classifier
function lives in `core:domain`; the chip fix must respect that
placement. Sort/group's due-bucket grouping
and saved-view due-bucket filters consume it; Stage 8's month view
reuses it later. The Actions billing restore and Dependabot queue also
precede stage execution, per the roadmap.

## Section 1 — Sort and group controls

Surfaces: the Tasks list, the project workbench list, and in-column
board ordering. The board keeps its status columns; it receives sort
only.

**Arrangement rule.** A pure rule in `core:domain`:
`TaskArrangement(sort: TaskSortKey, groupBy: TaskGroupKey?)`.

- `TaskSortKey`, each with a fixed direction (no direction toggle):
  - DUE — due date ascending, tasks without a due date last
  - PRIORITY — highest first (URGENT → NONE)
  - TITLE — case-insensitive A–Z
  - UPDATED — most recently updated first, using
    `revision.wallTimeMillis` (the model has no `createdAt`; the review
    queue already uses this proxy)
- `TaskGroupKey`: DUE_BUCKET (shared classifier order: Overdue, Today,
  This week, Later, No date), PROJECT (Tasks list only; tasks without
  a project group under Inbox, which renders first, followed by
  project groups ordered by name), and PRIORITY (Urgent → None).
  Absent `groupBy` renders one flat list.
- One deterministic tiebreak everywhere: title case-insensitive, then
  task id. This rule is the single comparator authority for the three
  Stage 7 surfaces. The existing divergent comparators in Schedule and
  Home are out of scope and untouched; the board's in-column default
  re-points at the shared authority with unchanged visible behaviour
  (priority desc, then title).

**Seam.** `feature/*` modules depend only on `:core:model` and
`:core:designsystem`, so `:app` applies the arrangement and passes
plain arranged data down; feature screens render it and emit choice
events up:

- The sort/group key enums live in `:core:model` as shared vocabulary.
- `:app` (WorkspaceViewModel/OpenTasksApp) computes
  `List<TaskGroup>` (group label + ordered tasks; a flat list is one
  unlabelled group) via the `core:domain` rule and passes it to the
  Tasks and workbench screens. Feature screens gain a compact
  sort/group control in the list header and report changes through
  lambdas, staying stateless.
- The board's pure `boardColumns` projection takes a
  `TaskSortKey` parameter and orders cards inside each column with the
  shared tiebreak. Selectable in-column sorts: PRIORITY (default,
  current behaviour), DUE, TITLE.

**Persistence.** A durable `ViewArrangementStore` on the
`AppLockSettings` pattern (plain SharedPreferences, own prefs file
`view_prefs`, provided in AppModule):

- Tasks-list arrangement: one global choice.
- Workbench arrangement and board in-column sort: keyed per project
  id. Entries for deleted projects are bounded garbage and are not
  cleaned up (deliberate; enum names and project UUIDs only).
- Stored values are enum names and project UUIDs — never task text,
  never vault data, never backup content.
- Defaults when unset: sort DUE, group none; board columns PRIORITY.

**Engine-order parity.** Room serves snapshot tasks `ORDER BY id`; the
in-memory engine currently publishes insertion order. The in-memory
engine changes to publish tasks sorted by id so both engines agree and
ordering tests can pin behaviour. All user-visible ordering comes from
the arrangement layer, not the snapshot.

## Section 2 — Saved-view filters v2

**Model.** `SearchQuery` gains defaulted fields; an empty set means no
constraint:

- `dueBuckets: Set<DueBucket> = emptySet()` — relative buckets from
  the shared classifier (relative stays meaningful over time; absolute
  stored dates would go stale)
- `priorities: Set<Priority> = emptySet()`
- `statuses: Set<SemanticStatus> = emptySet()` — **semantic status**
  (BACKLOG/PLANNED/STARTED/BLOCKED/COMPLETED), deliberately not
  per-project workflow-status ids: workflow ids are project-scoped and
  a cross-project saved view holding them would silently go stale.
  This is the approved deviation from the roadmap's wording.
- `sort: TaskSortKey? = null` — applied to the view's task results.

Filters compose with the existing chain (trash → completion →
projects → tags) as additional conjunctive predicates. Project
results are unaffected by task-only filters.

**Blank-text filter views.** Today a blank needle returns empty
results. That short-circuit is retained only when no v2 filter is
set: a query with blank text and at least one v2 filter returns all
filter matches (task results only — projects have no v2 predicates),
so pure filter views ("urgent this week") work without search text.

**Sort/ranking composition.** When the view's `sort` is null,
Section 3's tier ranking orders everything. When `sort` is set: for
blank-text filter views the sort comparator orders the task results
and the 50-cap applies after sorting; for views with query text, tier
ranking still decides which results survive the cap (relevance picks
the survivors), and the sort comparator then reorders the surviving
task results. Tasks-before-projects is preserved in every case.

**Codec v2.** `SavedViewPayloadCodec` moves to version-first decode:

- Parse the payload as JSON, read `formatVersion` first. A missing or
  non-integer `formatVersion` fails closed (this removes the v1 quirk
  where an omitted version key silently decoded as v1).
- `formatVersion == 1` → strict decode against exactly today's v1
  schema (five fields, unknown keys fatal).
- `formatVersion == 2` → strict decode against the v2 schema (v1
  fields plus the four new ones, unknown keys fatal, unknown enum
  names fatal).
- Any other version fails closed. A failed decode preserves the row
  and keeps it invisible, exactly the existing malformed-row
  behaviour; recovery import of a future v3 payload therefore retains
  the object without surfacing it.
- Encode always writes v2 with `encodeDefaults = true` and
  deterministic ordering: id lists sorted as today; the new sets
  encoded as sorted lists of stable enum names.
- Bounds unchanged: 20 views, 64-char name, 500-char query text,
  2 MiB payload. Existing rows are never rewritten in place — only
  create/update commands re-encode — so journal fingerprints do not
  churn. The content-based `SAVED_VIEW` fingerprint already covers
  richer payloads; no backup change.

**Chip identity fix.** Selecting a saved view keys the active
selection by view id, not by exact query text. Typing then refines the
text while the view's filters stay active; an active-view indicator
stays visible with an explicit clear affordance. Editing text no
longer silently drops filters. Saving, renaming, and deleting keep
their existing flows and undo.

## Section 3 — Search ranking

The two near-identical search implementations in the Room and
in-memory engines hoist into one pure function in `core:domain`
(snapshot + query in, ranked results out); both engines call it. That
function owns filtering (existing chain plus the v2 predicates) and
ranking:

- Tier per result, computed on the normalized title (project name for
  project results): exact match > prefix > word boundary (any word in
  the title starts with the needle) > substring. A result matching
  only in its wider haystack (description, checklist, notes, tags,
  attachment names, project summary) ranks in the substring tier.
- Within a tier: tasks before projects, then title case-insensitive,
  then id.
- Rank first, then apply the 50-result cap (shared constant; the
  in-memory engine's hardcoded `.take(50)` literal disappears into the
  shared rule). This changes which 50 survive versus today's
  concatenation order — intended. A saved view's `sort` composes with
  ranking exactly as pinned in Section 2.

## Section 4 — Quick Add grammar

Scope: the Quick Add sheet only, on every entry path (FAB, tile,
share/PROCESS_TEXT prefill, widget, shortcut). Prefilled text flows
through the same parse, so shared text grows chips too.

**Tokens.**

- `#project` — matches active (unarchived) project names,
  case-insensitive via the existing normalizer, prefix preferred then
  substring; the chip names the resolved project. No project creation
  from the token.
- `@tag` — token runs to the next whitespace. An existing tag resolves
  by name; an unknown name offers "new tag" and dispatches through the
  existing get-or-create dedupe. Multiple `@` tokens allowed, bounded
  by the 50-tags-per-task limit. Multi-word tags are not expressible
  in the grammar (deliberate ceiling; the editor covers them).
- `!priority` — words `!low`, `!med`, `!medium`, `!high`, `!urgent`
  and numerics `!1`–`!4` with 1 = URGENT, 2 = HIGH, 3 = MEDIUM,
  4 = LOW. The chip label always spells the resolved priority, so the
  numeric direction cannot silently surprise.
- Recurrence phrases — `every day|week|month|year`,
  `every N days|weeks|months|years` (N within the existing interval
  bound), `every <weekday>` (single weekday; weekday lists are out of
  scope). Maps onto the existing `RecurrenceRule`. Recurrence requires
  a due date: an explicit parsed date wins; otherwise the chip anchors
  the due by one uniform rule for every phrase family — the earliest
  date matching the phrase whose 17:00 is still ahead of now (so
  "every day" typed at 20:00 anchors tomorrow 17:00, and "every
  monday" typed Monday morning anchors today 17:00). The chip states
  both effects (e.g. "Repeat weekly · due Mon 17:00").
- `~estimate` — `~30m`, `~2h`, `~1h30m`, bare `~45` = minutes. A
  non-positive value or one over 24 hours produces no token (no chip;
  nothing is clamped).
- Natural-language dates remain owned by the existing date parser with
  its grammar unchanged.

**Parser.** The single-span date parse generalizes to a pure
multi-token `parseQuickAdd(text, now, zone, projects, tags)` in
`core:domain`, returning ordered token matches (span, kind, resolved
value). Sigil tokens fire only at a word start with at least one
character after the sigil. All matching uses `Locale.ROOT`
(Turkish-locale regression tests carry over). The span-strip helper
hoists out of the sheet composable into `core:domain` beside the
parser — one implementation for every token type. The sheet takes an
injected clock, retiring the current `Instant.now()`-during-
composition read.

**Confirm-only UX.** One suggestion chip per detected token in a row
under the title field. Each chip is individually confirmable (tap =
apply + strip its span) or dismissible (suppress, revived by editing),
generalizing the existing date-chip pattern. Text is re-parsed after
each confirm. No token ever applies without a tap; tokens never
silently rewrite a title.

Two determinism rules: when several projects match a `#` needle at
the same tier (prefix, then substring), the shortest name wins, then
case-insensitive alphabetical order. For single-valued fields
(project, priority, date, recurrence, estimate) every detected token
still renders a chip, and confirming a chip replaces any previously
applied value for that field; only tags accumulate.

**Command layer.** `CreateTask` widens additively (the same move
Stage 6 made for `due`); it already carries `projectId` and
`priority`:

- `tagNames: List<String> = emptyList()` — applied inside the command
  via the existing name-dedupe (find-by-name or create), bounded by
  the existing tag-name and tags-per-task limits.
- `estimate: Duration? = null`
- `recurrence: RecurrenceRule? = null` — recurrence with a null due is
  rejected with a new rejection reason in both repository companions
  (unreachable from the parser, fail-closed at the command).

Both engines implement the widening behaviourally in sync. The command
stays atomic inside the one existing `execute()` transaction and is
journaled by the existing snapshot diff; undo remains the produced
`DeleteTask` (soft delete covers relations). Rejected alternatives:
ViewModel-composed follow-up commands (non-atomic, fragmented undo)
and a separate composite command (duplicate surface).

## Section 5 — Light theme pin

- `OpenTasksTheme` loses its `darkTheme` parameter; the dark OKLCH
  constants and `DarkColorScheme` are deleted (recoverable from git).
  All four call sites (main app, initializing surface, lock, recovery)
  render the light scheme unconditionally.
- DESIGN.md's theme wording (light required, dark best-effort) is
  amended to light-only in this stage.
- No preference UI, no storage; nothing touches vault or backup
  content. The roadmap's theme-privacy exit criterion is satisfied by
  absence and restates as: under a dark device configuration the app
  still renders the light scheme, pinned by one test.

## Section 6 — Task duplication

New `DuplicateTask(taskId)` command in both engines, atomic and
journaled like any command.

- Copies: title with " (copy)" appended (truncating the source title
  just enough to respect the 240-char cap), description, priority,
  estimate, due and start dates, milestone, tag set, **unticked**
  checklist items with fresh item ids, the source's outgoing
  dependencies, and `parentTaskId` (dormant until Stage 9's subtasks
  UI; a duplicated subtask stays a child of the same parent).
- Lands at the source's workflow status (a duplicated board card stays
  in its column); a completed source duplicates uncompleted at the
  project's default backlog status.
- Excludes: completion state, activity, time entries, attachments,
  recurrence and all series fields (session ruling), and the reminder
  (silently duplicating an armed alarm would surprise; approved
  addition to the roadmap's exclusion list).
- Undo: the produced `DeleteTask` of the copy (soft delete to the
  Bin). Rejections reuse existing reasons (missing/trashed source).
- Entry points: the task detail action area and the board card menu.

## Section 7 — Insights dot-matrix restyle and trend

**Primitives.** Two reusable Compose Canvas primitives land in
`:core:designsystem` (deliberately, so Stage 8's dot-density month
cells reuse them), authored in the ember OKLCH system with density and
shape carrying the signal:

- A **dot-run bar** replacing every `LinearProgressIndicator` metric
  bar: small counts render one dot per unit; durations render
  proportional dot runs; any non-zero value paints at least one dot
  (today's minimum-visible floor, preserved).
- A **dotted-area chart** for time series.

**Trend.** One new surface: completions per day across the selected
7/30/90-day range. The pure `InsightsEngine` computes
`completionTrend` (one entry per day in the range, zone-aware day
boundaries via the engine's existing explicit zone parameter — stays
unit-testable). The chart renders per-day dot columns and scrolls
horizontally inside its own container when the range outgrows the
width. The table presentation gains the matching per-day section. The
chart↔table toggle is retained unchanged.

**Accessibility.** Unchanged model: bars and charts stay decorative
behind merged label + value semantics.

## Section 8 — Cross-cutting

**Dependencies and order.** The chip fix (shared date-bucket
classifier) lands before sort/group and due-bucket filter work. The
Actions billing restore and Dependabot queue precede stage execution.

**No durable change, proven.** The schema-drift gate proves zero Room
change; saved-view v2 lives entirely inside the existing encrypted
payload bytes; no backup family, fixture, or format changes.

**Testing.**

- Arrangement rule and ranking: pure unit tests, including tiebreak
  determinism, rank-then-cap behaviour, blank-text filter views, and
  the saved-view sort composition.
- Grammar: torture tests — sigils inside ordinary titles, Turkish
  locale, span-strip round trips, recurrence anchoring, estimate
  bounds.
- Codec: v1 and v2 round-trip, byte-determinism, the fail-closed
  matrix (missing version, unknown version, unknown keys, unknown enum
  names, oversize, malformed UTF-8), and recovery-import of both
  versions.
- Dual-engine parity: the `CreateTask` widening, `DuplicateTask`, and
  the in-memory id-order alignment.
- Theme: dark device configuration renders the light scheme.
- Insights: trend day-boundary engine tests.
- Compose tests for the new controls and chips on existing conventions
  (`createComposeRule` v2, fixtures, no new test libraries). New UI
  copy goes in `res/values/strings.xml`.

**Deliberate ceilings.** No sort-direction toggle; multi-word tags and
weekday lists not expressible in the grammar; Schedule and Home
comparators untouched; board manual ordering still excluded (waits for
the Stage 9 rank store); per-project preference entries for deleted
projects are not cleaned up.

**Exit criteria.** The roadmap's uniform gates (full CI gate,
six-module connected gate on the disposable AVD, schema-drift /
fixture / workflow-pinning / release-scope / production-logging /
privacy scans, whole-stage independent review, qualification record,
sideload release, handoff update) plus the Stage 7 specifics:

- The schema-drift gate proves zero durable change.
- v1 and v2 saved-view decode and recovery tests green.
- Under a dark device configuration the app renders the light scheme.
- Duplication copies exactly the ruled field set.
