# Stage 8 Design — Planning Surfaces

- Date: 14 August 2026
- Status: design content approved by the user in the brainstorming session of
  14 August 2026 (architecture, month/rescheduling, Gantt-lite, daily digest,
  and verification sections approved as presented); committed written-spec
  review and approval pending.
- Authority: this document is the Stage 8 design authority under
  `2026-08-10-stage-7-9-roadmap-design.md`. The roadmap's stage scope,
  ordering, bounds, and uniform exit criteria apply unchanged. The
  implementation plan derives from this document.
- Hard constraint: **no Room schema change and no backup-format change
  anywhere in this stage**. No new permission, external dependency, top-level
  navigation destination, or Drive scope is added.

## Session rulings

1. Stage 8 integrates into the existing product surfaces: Week/Month in
   Schedule, List/Board/Timeline in Projects, and an inline daily-digest
   setting in More. There is no new Planner destination.
2. Dropping an undated task onto a day makes it due at **18:00 in the
   device's current local zone**.
3. A due-only task remains due-only when moved. Its local time and stored zone
   are preserved.
4. The complete design sections below were approved without amendment.
5. Stage 8 ends with signed sideload release **1.2.0**, `versionCode = 3`.

## Existing foundations

- `WorkspaceSnapshot` already contains every task, project, milestone,
  reminder, workflow, and dependency fact required by the new projections.
- Schedule already uses start-else-due placement in the moment's stored zone,
  exposes compact day and expanded week layouts, and owns the open-only
  unscheduled tray.
- `DueBucket` and the zone-aware classifier already provide the shared
  `OVERDUE`, `TODAY`, `THIS_WEEK`, `LATER`, and `NO_DATE` vocabulary.
- `DotRunBar` and `DottedAreaChart` already establish the dot-matrix visual
  language in `:core:designsystem`.
- Stage 6's Board drag already proves root-coordinate hit testing, current
  callback capture, edge scrolling, an unclipped sibling preview, absolute
  RTL-safe positioning, menu fallback, and custom accessibility actions.
- Reminder alarms already prove stable `PendingIntent` identity, inexact
  idle-safe scheduling, boot/package/time/time-zone re-arm, lazy active-vault
  lookup, private notifications, and generic public versions.
- `computeTodayProjection` already computes open-today and overdue counts while
  suppressing titles when `titlesPermitted = false`.

## Section 1 — Architecture and ownership

Stage 8 adds no route, parallel planning store, Room entity, backup record,
worker, service, dependency, or permission.

Two pure projections live in `:core:domain`:

- a month projection from `WorkspaceSnapshot`, selected month, `Clock`, and
  display zone; and
- a project-timeline projection from `WorkspaceSnapshot`, project id, and a
  bounded date window.

Their small semantic result types live in `:core:model`, matching existing
projection vocabulary such as `HomeSnapshot`, `InsightsSnapshot`, and
`TaskGroup`. Feature modules continue to depend only on `:core:model` and
`:core:designsystem`; they do not gain a dependency on `:core:domain`.
`OpenTasksApp` computes both projections and passes plain values and callbacks
to feature surfaces; UI-only selection remains saveable state inside the
existing presentation boundary.

Schedule's Week/Month selection and selected date use saveable UI state.
Projects replaces its per-project Board Boolean with a per-project
`LIST`/`BOARD`/`TIMELINE` presentation enum in the existing SavedState-backed
view state. This remains session/process-restorable UI state, not a
SharedPreferences or vault preference.

Only the proven low-level Board drag mechanics move into
`:core:designsystem`: root-coordinate source bounds, accumulated pointer
offset, fresh start/drop callbacks, target hit testing, and absolute preview
placement. Board remains a consumer and therefore a regression proof. Week,
month, and tray keep their own targets, scrolling, cards, and scheduling
rules; there is no general drag framework.

## Section 2 — Month calendar

Schedule exposes Week and Month modes at every window size.

The month projection produces a Monday-first, fixed six-row by seven-column
grid. Leading and trailing dates from adjacent months remain selectable but
render with muted styling. Previous and Next move one month; Today opens the
month containing today and selects today. Selecting a day always updates the
selected-day agenda; it never short-circuits directly into a task. Selecting a
task in that agenda opens the existing task surface.

Placement remains one task, one day:

1. use `task.start` when present;
2. otherwise use `task.due`; and
3. convert that moment to `LocalDate` in its own stored zone.

The month includes every non-binned dated task, preserving existing Schedule
semantics. Completed work remains visible with completed icon/text treatment,
but only open tasks are drag sources. Undated open tasks remain in the
unscheduled tray.

Each date cell exposes:

- the local date;
- total placed-task count;
- completed count;
- overdue count of open tasks whose due class is `DueBucket.OVERDUE`; and
- up to six density dots, followed by a compact `6+` overflow label when the
  count exceeds the visual cap.

Dots and warning colour are decorative. The merged cell semantics state the
full date and exact task/completed/overdue counts. Overdue state also uses a
labelled warning marker, so colour never carries the meaning alone.

Compact Month stacks the grid and selected-day agenda. Expanded Month places
the grid beside a supporting column containing the selected-day agenda and
the unscheduled tray. Month cells are drop targets only: a cell cannot be a
drag source because it can represent several tasks. Individual selected-day
and tray rows are unambiguous sources.

Expanded Week supports pointer drag among its seven day columns and tray.
Month supports pointer drag from its visible agenda/tray rows to cells.
Compact Week has no honest pointer target grid, so it uses the complete
tap/menu path below; compact Month can drag between its stacked agenda and
visible cells, with Remove schedule providing the tray-equivalent action.

## Section 3 — Rescheduling and editor completion

### Command boundary

The existing bulk `RescheduleTasks` command and its Tasks/Review callers stay
unchanged. It is due-only and cannot move a task whose visible Schedule anchor
is `start`.

Stage 8 adds one exact single-task command in both repository engines:

```kotlin
data class SetTaskSchedule(
    val taskId: TaskId,
    val start: ZonedMoment?,
    val due: ZonedMoment?,
    val reminder: Reminder?,
    val restorePastReminder: Boolean = false,
) : DomainCommand
```

The repository validates the complete target state, including task/reminder
identity, reminder-with-due, recurrence-with-schedule, recurrence end date,
start-before-due, and future-reminder rules. It updates task and reminder
atomically, advances the revision once, journals through the existing snapshot
diff, and returns the same command carrying the previous exact values as Undo.
Only repository-produced Undo sets `restorePastReminder = true`; that flag
bypasses solely the future-reminder check, restores exact reminder metadata,
and never schedules an alarm whose trigger is already past. Schedule changes
do not add a new activity kind; this preserves the existing due-edit behaviour.

A pure `:core:domain` move rule converts the current task/reminder plus a date
or tray target into the exact command fields. UI code does not independently
reimplement date, zone, recurrence, or reminder arithmetic.

### Day-drop rules

| Existing shape | Drop on a day |
| --- | --- |
| No start or due | Set due to 18:00 in the device's current zone; keep start null |
| Due only | Move due to the target local date, preserving its local time and stored zone |
| Start only | Move start to the target local date, preserving its local time and stored zone |
| Start and due | Shift both by the same calendar-day delta, preserving each local time, stored zone, and day span |

For a start-and-due task, the source anchor is the start date in the start
moment's stored zone. Each target moment uses `ZonedDateTime.plusDays(delta)`
in its own stored zone. Java therefore preserves the local time when it exists,
shifts a nonexistent gap time forward by the gap, and retains the previous
offset during an overlap when possible. This is the sole exception to literal
wall-time preservation and keeps the rule deterministic across DST and
differing stored zones. The command rejects a target whose due instant is
before its start instant.

A reminder remains due-relative: when due moves, its existing lead duration is
applied to the new due moment, preserving precise/inexact preference. A move
that would place the reminder at or before `now` is rejected with actionable
feedback rather than silently deleting or restoring a past alarm.

Moving a recurring task changes only the current occurrence's dates and keeps
its series id, anchor, occurrence index, and rule unchanged. A recurring task
cannot move to the unscheduled tray because recurrence requires a schedule.

### Tray-drop rules

Dropping an open non-recurring task into the tray clears both start and due.
When it has a reminder, the drop opens a confirmation explaining that both the
schedule and reminder will be removed; confirmation clears them atomically and
Undo restores them together. Cancellation changes nothing.

Completed and binned tasks are never drag sources. A same-source or
outside-target drop snaps back without issuing a command. Repository rejection
keeps the task in place and surfaces its exact message. Success uses the
existing snackbar/Undo path.

### Complete non-drag path

Every draggable task has a 48 dp Reschedule action that opens the same target
date selection and a Remove schedule action when valid. Ordinary task tap
continues to open the editor.

The existing task editor gains native start date/time controls, and the due
row gains an explicit time control. `TaskEdit` and the existing full
`DomainCommand.UpdateTask` both gain `start`; the app mapper, validation,
equality, both engine handlers, and repository-produced inverse builders carry
it in the same debounced atomic save. `SetTaskSchedule` remains reserved for
explicit rescheduling actions, so the editor never races two commands.

New start moments default to 09:00 in the device's current zone; existing
moments preserve their stored zone and local time. New due moments created by
the existing editor keep its pre-Stage-8 date-only convention of 17:00 local;
**18:00 is specific to an undated drag/drop**. The editor rejects
due-before-start, while legacy/imported invalid records remain readable and
render a warning until corrected.

## Section 4 — Project Gantt-lite

Projects adds Timeline as the third project-workbench presentation beside
List and Board. It is a read-only `WorkspaceSnapshot` projection; it never
owns or writes task, milestone, or dependency state.

The view shows a Monday-aligned 12-week window. Today makes the first visible
day Monday of the current week; Previous and Next move that first day four
weeks. The anchor and selected dependency chain are saveable UI state scoped
to the selected project. The fixed window is the bound: no project history can
create an unbounded canvas.

All non-binned project tasks appear:

- start and due: an inclusive dot-run bar between their stored-zone local
  dates;
- start only: a labelled start marker;
- due only: a labelled due marker;
- neither: a compact Unscheduled list below the timeline; and
- due before start: a labelled warning marker instead of a fabricated
  backwards span.

Every task remains a row. A span crossing a window edge clips to that edge and
shows a labelled continuation marker; a wholly earlier or later date shows a
labelled Before window or After window state rather than a false in-window
point. Merged semantics retain the task's complete dates and clipping state.

Completed tasks remain visible with muted completed icon/text treatment.
`Task.isBlocked` is the marker authority, covering both unfinished
dependencies and semantic BLOCKED state. Milestones dated inside the window
render as diamonds; completed milestones add completed icon/text treatment.
Out-of-window milestones contribute exact Before/After counts and, with
undated milestones, remain available in the existing milestone list rather
than inventing an in-window position.

Selecting a task row highlights its complete transitive dependency context:
prerequisites and dependants. Traversal uses the full non-binned snapshot graph
across project boundaries, a visited set, and the snapshot's task-count bound
even though command validation already prevents cycles. Unique in-project
tasks highlight as rows; the chain summary reports the number of unique
out-of-project tasks, not dependency edges. There is no arrow routing.

Row selection owns highlighting. A separate 48 dp Open action opens the task
editor. Milestone activation opens the existing milestone editor. Bars and
diamonds are not draggable in v1; all edits route through existing editors.
Shape, icon, label, and merged semantics carry duration, completion, blocked,
milestone, and dependency meanings independently of colour.

## Section 5 — Daily digest

More gains an inline Daily digest switch and native 24-hour time picker. The
setting is device-local to the app installation, excluded from Android backup
by the existing SharedPreferences exclusion, off by default, and defaults to
08:00 local when first enabled.

The preference file stores only:

- `enabled: Boolean`;
- `minute_of_day: Int` in `0..1439`; and
- optional `last_handled_epoch_day: Long`.

It stores no task, title, project, vault, count, zone id, notification payload,
or scheduled instant. Invalid enabled/time state fails closed by disabling the
feature and cancelling its alarm. Disabling retains the last-handled date so
off/on cannot duplicate a digest on the same local day.

One stable, non-exported broadcast `PendingIntent` is scheduled with
`AlarmManager.setAndAllowWhileIdle`; a daily digest does not justify exact
alarm access. The next occurrence is computed from the configured local wall
time and the current device zone. Standard `java.time` zone resolution handles
DST gaps and overlaps. If today's epoch day is not greater than the stored
last-handled day, delivery is skipped and the next alarm is reconciled. This
prevents a backward clock or date change from posting twice.

The receiver validates its action and re-reads settings. A disabled or invalid
setting cancels instead of re-arming. For an enabled, not-yet-handled local day,
it records today as handled and re-arms the next one-shot alarm before reading
vault state or posting. It also reconciles on app foreground/startup, boot,
package replacement, wall-clock change, and time-zone change through the
existing system-event receiver precedent. Enabling after today's configured
time schedules tomorrow rather than posting a catch-up.

Missing active vault state, permission denial, a disabled channel, or a
notification `SecurityException` remains handled for that day and is not
retried; the next alarm remains armed.

Delivery captures one `now` and current zone, reads the active
`WorkspaceSnapshot`, and calls:

```kotlin
computeTodayProjection(
    snapshot = snapshot,
    today = LocalDate.ofInstant(now, zone),
    zone = zone,
    now = now,
    titlesPermitted = false,
)
```

Private notification content contains counts only, for example
`3 open today • 1 overdue`. The public lock-screen version is always generic
and contains no counts or workspace content. When both counts are zero, the
delivery posts nothing and leaves tomorrow armed.
Tapping opens Home; the app-lock overlay remains authoritative before any
workspace composition.

Daily digest uses its own private-visibility notification channel and a
private builder with a generic public version, so Android can disable it
independently. It adds no permission. Enabling remains recorded if notification
permission or the channel is denied, matching reminder preservation; More
shows the existing contextual permission/settings action until delivery is
available.

## Section 6 — Accessibility, errors, and continuity

- Every drag action has a complete tap/menu path and a minimum 48 dp target.
- Root-coordinate previews remain unclipped, callback-fresh, and absolute so
  RTL mirrors targets without mirroring pointer coordinates.
- Day cells expose exact text semantics; decorative dots are hidden from the
  semantics tree.
- Timeline state uses icon, shape, label, and semantics as well as colour.
- Invalid drops never partially mutate task/reminder state. Confirmation is
  required before reminder removal; command rejection and notification
  failures preserve structured data.
- Month, project presentation, selected day, timeline anchor, and dependency
  highlight are saveable UI state. They are not vault or backup content.
- New user-facing copy lives in feature resources and follows UK English,
  day-month dates, Monday-first weeks, and 24-hour time.

## Section 7 — Verification

### Pure JVM tests

- Month: start precedence, due fallback, stored-zone date boundaries, adjacent
  month cells, completed/Bin filtering, density overflow, and exact
  `DueBucket` overdue counts under an injected clock.
- Schedule move: all four source shapes, 18:00 undated default, stored-zone
  preservation, DST transitions, start/due span preservation, no-op, tray,
  recurrence, reminder movement, past-reminder rejection, and invalid ranges.
- Timeline: project filtering, 12-week clipping, partial/full spans,
  milestones, completed/blocked markers, undated rows, invalid ranges,
  transitive prerequisite/dependant chains, cross-project counts, and a
  defensive cyclic graph.
- Digest: default-off and preference bounds, next occurrence before/at/after
  the configured time, DST gap/overlap, backward-clock duplicate prevention,
  zero-count silence, and title-free notification planning.

### Repository parity

Both in-memory and Room paths prove exact schedule fields, one revision
advance, reminder replacement/removal, recurrence metadata preservation,
rejections with no partial write, one journal generation containing the exact
ordered task and reminder entries, and exact Undo.
Existing bulk `RescheduleTasks` behaviour remains pinned unchanged.

### Compose and device coverage

- Schedule: Week/Month restoration, month navigation and selection, density
  semantics, task tap-through, day/tray moves, confirmation, outside-target
  snapback, callback replacement, preview clipping, compact fallback, and RTL.
- Projects: third presentation, bounded navigation, span/marker semantics,
  chain highlighting, cross-project summary, and task/milestone actions.
- More: opt-in callback, 24-hour time control, denied-notification guidance,
  and disabled state.
- Board: its existing menu, accessibility, drag, preview, callback-freshness,
  and RTL tests remain green after the small drag extraction.
- Disposable-device acceptance: one near-future digest posts once; disable
  cancels; private shade shows counts; lock screen is generic; reboot/time-zone
  change retain local time; Schedule drag and tap fallbacks work at compact and
  expanded sizes.

### Stage exit

The roadmap's uniform exit gates remain mandatory:

- forced-fresh `testDebugUnitTest lintDebug :app:assembleDebug`;
- six-module connected gate on the sole disposable read-only AVD;
- schema-drift, deterministic fixtures, workflow pinning, release scope,
  production logging, privacy, and diff-hygiene scans;
- whole-stage independent review closed with zero Critical or Important
  findings;
- Stage 8 qualification record;
- signed sideload release 1.2.0 / versionCode 3 per `RELEASING.md`; and
- `HANDOFF.md` closure.

The permission set and `drive.appdata` scope must be byte-for-byte unchanged.

## Implementation sequence constraints

1. Land semantic projection vocabulary and pure month/timeline/move rules.
2. Land the exact dual-engine schedule command and editor completion.
3. Extract only the proven drag mechanics, retaining Board regression tests.
4. Add Week/Month and rescheduling surfaces.
5. Add the read-only project Timeline.
6. Add daily-digest settings, alarm, receiver, notifier, and More wiring.
7. Run the whole-stage review/fix wave, qualification, version bump, signed
   smoke, release record, tag, and handoff closure.

## Deliberate ceilings and exclusions

- Month density is capped visually at six dots; exact counts remain textual.
- Gantt-lite is a fixed 12-week day-granularity window with no zoom, arrow
  routing, bar drag, resource allocation, critical-path calculation, or
  parallel store.
- Digest has no missed-delivery catch-up, task titles, task actions, per-vault
  setting, exact alarm, or background worker.
- Stage 9's Room v10 automation/My Day/WIP/subtask wave is untouched.
- Repository-wide Unicode tag-identity hardening remains its separate ordered
  backlog item; Stage 8 does not patch an isolated caller.
