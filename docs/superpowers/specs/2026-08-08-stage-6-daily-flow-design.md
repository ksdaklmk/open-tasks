# Stage 6 — Daily-Flow Features Design

- Date: 8 August 2026
- Status: approved by the user in the brainstorming session of 8 August
  2026 (all four candidate directions selected; one stage; per-feature
  rulings recorded inline below); amended on 9 August 2026 with the
  separately approved Ember launcher-icon design.
- Authority: this document is the Stage 6 design authority. Its plan
  will live at `docs/superpowers/plans/` and derives from this spec.

## Goal

Cut friction on daily use of the existing workspace. Stage 6 adds
faster capture (share-sheet, text selection, Quick Settings tile,
natural-language dates), action from glance surfaces (interactive
widget, focus cycles), in-app power (saved searches, bulk
multi-select, Kanban board), a guided weekly review, and plaintext
interop (Markdown export, own-schema CSV import). No new cloud
surface, no sync, and — after the planning-time discovery below — no
durable schema change at all. The approved Ember launcher artwork closes the
stage before qualification.

## Recorded scope rulings

- One stage, all four directions, fourteen ordered tasks
  (Stage 5-style single spec and plan).
- CSV import accepts exactly the task-table schema the Stage 5
  exporter writes (own-schema round-trip). No third-party formats.
- Kanban moves cards by drag-and-drop plus a complete accessible
  tap-to-move fallback; the fallback is built first and is the
  shippable interaction on its own.
- The weekly review walks overdue, stale, unscheduled, and project
  health.
- Focus mode offers preset cycles only: 25/5 and 50/10.
- The launcher icon uses the approved adaptive Ember artwork from
  `docs/superpowers/specs/2026-08-09-ember-launcher-icon-design.md`; it is a
  standalone implementation/review boundary before qualification.
- Standing rulings hold: offline-first, encrypted Room as the sole
  live structured-data authority, no bidirectional sync, create-only
  Drive with `drive.appdata` as the sole scope, sideload-only
  distribution.

## Execution order

Foundation first, biggest UI risk last, qualification closes:

1. Saved-search commands over the dormant `saved_views` table
2. Natural-language dates in Quick Add
3. Share-sheet and text-selection intake
4. Quick Settings tile
5. Interactive Today widget
6. Focus cycles
7. Saved searches UI
8. Bulk multi-select
9. Weekly review
10. Markdown project export
11. CSV import
12. Kanban board
13. Ember launcher icon
14. Qualification and exit gates

## Foundations

### Saved-search commands (dormant `saved_views` table)

Saved searches are workspace content (query text is user content), so
they live in encrypted Room, not SharedPreferences.

Planning-time discovery (8 August 2026, amending the approved draft's
Room v10 plan): Stage 1 already shipped the storage end-to-end,
dormant — `SavedView(id, workspaceId, name, query: SearchQuery)` in
`core/model`, the `saved_views` table (`SavedViewEntity` with an
`encryptedQuery` payload column), and the `SAVED_VIEW` backup family
with mutation-codec validation, journal identity, capture
attribution, and recovery import. Nothing in product code reads or
writes it. Task 1 therefore lights up the existing table instead of
adding a new one:

- No Room version bump, no migration, no exported schema change, no
  backup-family enum change, and no fixture regeneration.
- A `SavedViewPayloadCodec` (the `TemplatePayloadCodec` shape:
  bounded JSON, format version 1) encodes `SearchQuery` into
  `encryptedQuery`; at-rest encryption remains SQLCipher's.
- Granular `DomainCommand`s (create, rename, update query, delete)
  executed through `VaultRepository.execute`, each returning exact
  repository-produced Undo, with `InMemoryVaultRepository` parity.
  Bounds, fail-closed: 20 saved views per workspace, 500-character
  query text.
- `WorkspaceSnapshot` gains `savedViews`; both repositories map it;
  the in-memory journal's snapshot-to-records mapping gains the
  collection so journal parity holds.
- The `SAVED_VIEW` journal fingerprint moves from identity-only to
  the content-fingerprint style used by the other unrevisioned
  families (checklist, tag, reminder, time entry) — otherwise a
  rename or query update would never journal. A test pins this.

## Capture

### Natural-language dates in Quick Add (task 2)

- `parseNaturalDate(text, now, zone)` in `:core:domain`: pure,
  clock-injected, deterministic. Bounded UK-English grammar only:
  "today", "tomorrow", weekday names, "next <weekday>",
  "in N days/weeks", and times as "16:00", "4pm", "at 4pm". Returns
  the matched span and resolved moment, or null. No library.
- Quick Add shows the resolved moment as a confirmable chip; the
  parser suggests, the user taps to apply. Applying sets the due
  moment and strips the matched text from the title. No silent
  date-grabbing.

### Share-sheet and text-selection intake (task 3)

- `ACTION_SEND` (text/plain) and `ACTION_PROCESS_TEXT` intent filters
  on the already-exported `MainActivity`, routed through the existing
  `handleIntent` path: app-lock gate first, then the shared Quick Add
  sheet.
- Prefill is title-only in this stage: first line of the shared text,
  truncated to the title bound; the user edits before save. Shared
  content is never logged.

### Quick Settings tile (task 4)

- A `TileService` exported only under the system
  `BIND_QUICK_SETTINGS_TILE` permission, static generic label from
  `strings.xml`, `onClick` firing the same boolean-extra Quick Add
  intent as the launcher shortcut. No content in the tile.

## Glance and focus

### Interactive Today widget (task 5)

- Each focus row gets a Glance action callback dispatching the
  existing complete command through `VaultRepository.execute`, then
  republishing through the existing publisher.
- Single tap completes; no confirm step. Mis-taps are recoverable by
  reopening the task in the app. This trade-off is deliberate: an
  arm/confirm dance defeats a glance surface.
- Locked or title-privacy engaged (`titlesPermitted = false`): counts
  only, no action buttons rendered, any tap opens the app to unlock.
  Concealment continues to flow through the existing
  `StopGatedWriter` gate.
- Widget action failures self-heal: the next republish reflects
  repository truth.

### Focus cycles (task 6)

- A pure, clock-injected `FocusSessionController` state machine:
  focus/break phases, preset cycles 25/5 and 50/10 only. Focus phase
  runs the existing task timer; break pauses it. Starts use the existing
  task-specific command; stops use an additive owner-checked command so the
  ownership check and stop are atomic while existing `StopTimer` callers stay
  unchanged.
- Boundary alerts reuse the reminder scheduling infrastructure and
  the generic-content notification path (no task text when privacy
  is engaged).
- Session state (preset, phase, phase end, task id) persists in
  SharedPreferences — the `AppLockSettings` precedent — so a session
  survives process death. Deliberately not workspace data: it is
  device-local, transient, and carries no user content.

## Organize

### Saved searches UI (task 7)

- In the search surface: save the current query under a name (the
  surface carries no separate filter UI today; the stored
  `SearchQuery` payload already accommodates its filter fields for
  when it does); saved searches render as chips; apply, rename, and
  delete use the task 1 commands. Bounds enforced fail-closed with
  clear copy when full.

### Bulk multi-select (task 8)

- Long-press enters selection mode on task lists; the selection set
  lives in `SavedStateHandle`. Actions: complete, reschedule, move to
  project, add/remove tag, bin.
- Each action is a composite command over an id set, bounded at 200
  ids, executed in one transaction with ordered backup-journal
  entries for every affected record and one repository-produced Undo
  restoring the whole batch. Both repositories implement each
  composite; shared tests enforce parity, including journal
  atomicity.

### Kanban board (task 12)

- A list/board toggle in the project workbench (toggle state in
  `SavedStateHandle`); columns are the project's ordered workflow
  statuses; cards are open tasks in each status.
- A card move is exactly the existing status-assignment command; the
  board is pure presentation over existing data and commands.
- Interaction: build the accessible tap-to-move first — a per-card
  "Move to <status>" menu, exposed as custom accessibility actions —
  as the complete, shippable interaction. Layer pointer drag on top:
  drop-target highlight and edge auto-scroll. If drag slips, the
  fallback ships alone and drag becomes follow-up work.
- Column sizing and pane behaviour come from
  `WorkspaceLayoutPolicy`, never window size classes.

## Weekly review (task 9)

- Stateless: a pure `buildReviewQueue(snapshot, now)` orders overdue
  (open, due moment passed), stale (open, no activity in 14 days),
  unscheduled (open, no due or start date), then project health
  (each active project with milestone summary).
- Task cards offer Complete, Reschedule (date picker — the NL parser
  lives in `:core:domain`, which feature modules cannot depend on, and
  a picker keeps the review surface small), Keep, and Bin. Keep writes
  a "Reviewed" activity entry — implemented as a new `MarkReviewed`
  command, since activity entries have no user-facing command today —
  and that is what resets staleness; no new persistence. It
  consumes activity-bound budget (500 entries per task), which is
  acceptable and disclosed here.
- Project cards offer Keep (activity entry on the project) and
  Archive (existing command).
- Entry point: a More row. The flow is a new Navigation 3
  `@Serializable` route driven by `NavDisplay`.

## Interop

### Markdown project export (task 10)

- A pure per-project writer: project header, milestones, tasks
  grouped by workflow status with checkboxes and dates. SAF
  `CreateDocument("text/markdown")`, streamed, partial-output
  deletion on failure or cancellation (`NonCancellable` finally — the
  Stage 5 export shape).

### CSV import (task 11)

- Consumes exactly the task-table CSV the Stage 5 exporter writes,
  pinned by a round-trip fixture test: export, import, compare
  logical content.
- Import always creates, never merges. Projects and tags resolve by
  exact trimmed name, else are created. A case-only collision with the
  existing case-insensitive uniqueness invariant fails closed before any
  write. Formula neutralisation uses a reversible odd-apostrophe-run
  encoding, so both formula-looking text and genuine leading apostrophes
  survive an own-schema round trip. Within the existing `tags` column,
  backslash escapes both backslash and the semicolon list separator so every
  currently valid tag name also round-trips without changing the CSV header.
- All-or-nothing: the first malformed row aborts with a row-numbered
  error before any write. A preview (task, new-project, and tag
  counts) precedes the commit. The whole import is one composite
  command with one exact Undo.
- CSV import is deliberately lossy — the CSV carries no checklists,
  notes, attachments, dependencies, or time entries. `.otvault`
  remains the real transfer format; named zone ids normalise to the
  exported offset, and the import UI copy says so.

## Identity

### Ember launcher icon (task 13)

The approved design authority is
`docs/superpowers/specs/2026-08-09-ember-launcher-icon-design.md`. Replace only
the existing foreground vector, monochrome vector, and launcher background
colour with the supplied Ember versions. The existing adaptive definitions
and manifest references stay unchanged. Legacy PNG fallbacks are unnecessary
at minSdk 36, and the Play Store image remains deferred with the sideload-only
distribution ruling.

## Constraints carried forward

- Every write is a `DomainCommand` through `VaultRepository.execute`;
  Undo is repository-produced; no Room access outside
  `RoomVaultRepository`.
- Feature modules depend only on `:core:model` and
  `:core:designsystem`, stay stateless and Hilt-free; dispatch lives
  in `:app`.
- New copy in `res/values/strings.xml` via `stringResource`; OKLCH
  colours only; 4 dp spacing scale; Material typography roles; UK
  English throughout.
- No new runtime permissions. New exported surface is exactly the
  bind-permission-guarded `TileService` and the two intent filters on
  `MainActivity`. Text extras are never logged; saved-search text is
  encrypted workspace content; logs and telemetry carry no task
  text, account details, Drive ids, attachment names, or encryption
  metadata.
- Nothing touches Drive; `drive.appdata` stays the sole scope;
  Stage 4/5 bounds are unchanged.

## Testing and qualification

- TDD per task. JUnit 4, no mocking library, `runBlocking` +
  `withTimeout(5_000)`, real flow collection.
- Pure JVM cores with exhaustive unit tests: date parser, review
  queue builder, focus controller, board move targets, CSV import
  validator, Markdown writer.
- Composite and saved-search commands run against both repositories
  via shared tests, including journal atomicity and Undo exactness.
- Compose tests use `createComposeRule` (`.v2`) with
  `OpenTasksFixtures`; instrumented tests are compile-verified per
  task and execute at the task 14 connected gate on the sole
  disposable read-only AVD.
- Task 14 gates: forced-fresh CI gate and release assembly (separate
  invocations), schema-drift check, all fixture generators
  byte-identical, privacy scans over the stage range, release-scope
  check (`drive.appdata` sole scope, no new exported components
  beyond those named above), and the device checklist: share intake
  from a real app, `PROCESS_TEXT`, QS tile, widget tap-complete and
  locked-state concealment, a focus boundary notification,
  saved-search persistence across restart and through an `.otvault`
  round-trip, bulk undo, the full review flow, Markdown and CSV
  export/import via SAF, Kanban drag plus TalkBack tap-to-move, and the Ember
  launcher artwork under normal, round, and Material You themed treatments.
- Qualification record: `docs/qualification/stage6-daily-flow.md`.

## External and deferred work

- Wear OS, location reminders, any sync, third-party import formats,
  scheduled extra local snapshots: out of scope, unchanged rulings.
- Drag polish beyond drop-target highlight and edge auto-scroll
  (e.g. haptics, spring animations) is follow-up work, not gating.
- F6 (API 37.0 canary lane) remains observe-only; the Ctrl+K CI
  blind spot remains parked.
- The supplied 512 px Play Store listing icon remains with the parked Play
  Console work; Task 13 changes installed-app resources only.
