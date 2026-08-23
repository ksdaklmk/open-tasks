# Design System

## Intent

Open Tasks feels like a well-kept charcoal desk in changing light: task-dense
and precise on the inside, with a controlled ember signal only where time,
progress, selection, or data needs attention. The colour strategy is restrained.
Orange never becomes decorative background colour and charcoal remains the
filled-action colour.

The primary usage scene is a solo professional moving between a foldable cover
screen in daylight, the unfolded workbench at a desk, and a tablet in lower
evening light. Light mode is the required and release-gated colour scheme.
The app renders that light scheme unconditionally, including when Android uses
a dark device configuration; there is no theme preference.

## Colour

Colour tokens are authored as OKLCH values and converted to sRGB in Compose.
This preserves the design contract without scattering raw hexadecimal colours.

### Light

- Background: `oklch(0.975 0.000 0)` — true neutral off-white.
- Surface: `oklch(1.000 0.000 0)` — pure white.
- Surface dim: `oklch(0.945 0.004 29)`.
- Ink / filled action: `oklch(0.220 0.008 29)`.
- Muted ink: `oklch(0.470 0.010 29)`.
- Outline: `oklch(0.720 0.008 29)`.
- Ember: `oklch(0.620 0.170 32)` — time, progress, selection, data emphasis.
- Pale ember: `oklch(0.930 0.035 32)`.
- Error: `oklch(0.520 0.190 25)`.
- Success: `oklch(0.480 0.100 150)`.

Text on a saturated ember fill is white. Status also uses icon, label, and
container shape; colour never carries meaning alone. Dynamic Colour is disabled.

## Typography

Roboto/system sans carries the complete Material type scale. Use Material roles
only; feature code does not select ad-hoc sizes.

- Display: rare, only for high-level empty or onboarding moments.
- Headline: screen identity and high-attention sections.
- Title: sections, task names, and pane headers.
- Body: descriptions, task content, and explanatory copy.
- Label: controls, navigation, metadata, tags, and compact data.

Body copy uses at least `bodyLarge` for prose. Numeric time and report values
use tabular figures where the platform font supports them. The hierarchy is
created with role, weight, and space rather than excessive size.

Static taglines and sentences that merely describe a screen or section are
omitted. Keep headings concise, then show only changing state, useful metadata,
validation, warnings, or instructions needed to complete an action.

## Spacing and Layout

Use a 4 dp base scale: 4, 8, 12, 16, 24, 32, 48, and 64 dp. Related metadata
stays at 4–8 dp; rows and controls use 8–12 dp internal gaps; sections separate
by 24–32 dp. Screen content uses 16 dp compact padding, 24 dp medium padding,
and 32 dp expanded padding.

- Compact: navigation bar and one content pane.
- Medium: navigation rail with a 42/58 list/detail split.
- Expanded: rail with a 42/58 split below 960 dp and 38/62 at or above 960 dp.
- Extra wide (at least 1,200 dp): a supporting pane may appear.

Every touch target is at least 48 dp. Window size and folding posture determine
structure; hardware model names never do.

`WorkspaceLayoutPolicy` receives a `WindowPosture` (width, height, and fold
lines). List/detail panes use One UI 42/58 fractions below 960 dp and 38/62 at
or above 960 dp. A separating vertical hinge snaps the split to the valid fold
nearest the window centre, breaking a centre tie toward the leading fold; at
most four fold lines are considered. A separating horizontal fold reserves its
occlusion band. Posture handling must not assume `HALF_OPENED` state.

## Shape and Elevation

Material 3 Expressive shapes are restrained:

- Controls and tags: 10–14 dp corners.
- Content containers: 16–20 dp corners.
- FAB and high-emphasis containers: Material expressive large shape.
- Selected list rows use a full tonal container, leading icon, and label.

Do not use coloured side-stripes. Prefer spacing and section rules to wrapping
everything in cards. Tonal elevation communicates layers; shadows are reserved
for floating controls and temporary surfaces.

## Components

- Adaptive navigation: five destinations, bar on compact and rail otherwise.
- Context FAB: one Quick Add action; label may expand where room permits.
- Task row: completion control, title, project, due context, priority, and
  blocking state with accessible custom actions.
- Backup attention: Home has no standing cloud-status decoration. After backup
  is configured, Home may show a text-and-icon attention state only when
  backup is blocked or meaningfully overdue; colour never carries the state
  alone.
- Timer: charcoal focus surface with ember numerals and explicit pause/stop.
- Time tracking: task detail shows completed logged duration and a plain
  Review/Add action. A single scrollable Material sheet lists entries with UK
  dates, 24-hour ranges, duration, optional note and 48 dp edit/delete actions.
  Its inline editor uses date, start and duration rather than ambiguous
  start/end text. Overlaps use warning iconography, explicit double-counting
  copy and repository-derived conflict state; recorded work is never silently
  trimmed. Running entries remain labelled and read-only until stopped.
- Filters: Material chips with selected state and accessible names.
- Reminder editor: due-relative preset chips followed by Flexible/Precise
  delivery choices. Ask for notification or precise-alarm access only beside
  the choice that needs it, preserve the saved reminder when access is denied,
  and explain the active fallback inline.
- Reminder notification: show task context only in the private notification
  version; use generic copy on the lock screen. Content opens the task, Snooze
  is always available, and Complete appears only when the task is not blocked.
- Workbench: list and persistent detail panes with visible selection.
- Notes and activity: one merged timeline in task and project detail. Notes
  are editable drafts with explicit save/cancel state; activity is immutable,
  dated metadata and never becomes an editable note substitute.
- Attachment rows: show a sanitised name, type/size metadata, text-and-icon
  state, and 48 dp actions. Remote, downloading, unavailable, tombstoned, and
  failed remain distinct; unavailable is neutral rather than an error claim.
  Intake and sharing use temporary files only, and task editing remains
  available while attachment work is offline or failing.
- Workflow editor: a project-scoped modal sheet with editable status names,
  explicit up/down actions as an accessible alternative to drag gestures,
  immutable reporting-category labels, assigned-task counts and explained
  archive confirmation. New-status categories wrap at narrow widths and large
  text sizes; every action is at least 48 dp.
- Milestone editor: a project-scoped modal sheet for name, optional due date,
  completion/reopen and deletion. Show assigned-task impact before deletion,
  distinguish open and complete milestones with text and iconography, and keep
  all controls at least 48 dp.
- Task milestone selector: appears beside project organisation, lists only
  open milestones from the selected project plus an already-assigned completed
  milestone, and clears its draft selection when the project changes.
- Project template capture: an anchored modal sheet summarises the exact open
  workflow, milestone and task counts before saving. It explicitly explains
  that completed/Bin content is excluded and dates shift from the earliest
  saved date.
- Template library: a plain More destination lists reusable structures with
  task, milestone and stage counts. Use opens one focused sheet for the new
  project name and anchor date; delete remains an immediate reversible command.
- Dependency editor: a searchable task-scoped modal sheet with checked
  selections, project and open/complete/Bin context, a 100-link count and
  inline live-region feedback when a link would create a cycle. The main task
  editor summarises unfinished versus total prerequisites.
- Blocked completion: every in-app completion control and Completed-status
  selection opens the same confirmation dialog, naming up to three unfinished
  prerequisites. A blocked workflow state receives distinct copy. Reminder
  notifications omit Complete while the task is blocked.
- Schedule: compact windows show one selected-day agenda with previous, Today,
  next and horizontally scrollable weekday controls. Medium-and-wider windows
  show the ISO Monday–Sunday week as date-grouped timeline columns beside an
  open-only unscheduled tray. Start time takes precedence over due time for placement;
  due-only work is labelled explicitly, and completion, blocking and reminders
  never rely on colour alone.
- Month calendar: Schedule offers Week and Month at every window size. Month
  is a Monday-first six-row by seven-column grid; adjacent-month dates stay
  selectable but render muted. Previous and Next move one month, Today opens
  the month containing today and selects it, and choosing a day only changes
  the agenda — it never jumps straight into a task. Each cell carries the
  local date, up to six density dots with a `6+` overflow label, and a
  labelled warning marker when open work is overdue. Dots and warning colour
  are decorative; the merged cell semantics state the full date and the exact
  total, completed and overdue counts. Compact stacks the grid above the
  selected-day agenda, expanded places the agenda and the unscheduled tray in
  a supporting column.
- Rescheduling: every movable row has a 48 dp Reschedule action and, where it
  is valid, Remove schedule. That tap and menu path is complete on its own.
  Long-press pointer drag layers over it: expanded Week drags between its
  seven day columns and the tray, Month drags agenda and tray rows onto
  visible cells, and compact Week stays tap and menu only by design. Only
  open, non-binned tasks are drag sources, and a recurring task can never
  reach the tray. The preview renders unclipped above scroll containers and
  is RTL-safe; same-source, outside-target and rejected drops leave the task
  where it was and state why. Dropping an undated task on a day makes it due
  at 18:00 in the current device zone. Removing a schedule from a task that
  has a reminder always asks first, through the same confirmation from either
  path.
- Task scheduling editor: the editor gains native start date and time
  controls, and the due row gains an explicit time control. A new start
  defaults to 09:00 and a new due date to 17:00, both in the current device
  zone; existing moments keep their stored zone and local time. Due before
  start is refused with inline validation, while an older invalid record
  stays readable behind a warning until it is corrected.
- Project Timeline: a read-only third project-workbench presentation beside
  List and Board, chosen from one 48 dp segmented control. It shows a
  Monday-aligned 12-week window; Previous and Next move four weeks and Today
  returns to the current week's Monday. Rows carry dot-run spans, labelled
  start and due markers, 48 dp milestone diamonds and merged non-colour
  semantics. Nothing on it is draggable; every edit routes through the
  existing task and milestone editors.
- Daily digest setting: an inline More switch, off until it is switched on,
  with an enabled-only 48 dp row that shows the chosen time as `HH:mm` and
  opens the platform's native 24-hour picker. Notification access never gates
  the switch: a refused or revoked permission leaves the digest on and its
  time visible, explains only that delivery is unavailable, and offers a
  48 dp action to turn notifications on.
- Daily digest notification: counts only, never titles. The private version
  reads Today with `3 open today · 1 overdue`; the lock-screen version shows
  the generic Daily digest title and no workspace content. It uses its own
  Daily digest channel so it can be silenced independently of reminders,
  posts nothing when both counts are zero, and opens Home when tapped.
- Snackbar: confirms immediate edits and offers Undo where reversible.
- Search: modal command surface opened from UI or `/` / `Ctrl+K`.
- Today widget: the minimum 2×1 Glance layout keeps both counts and Quick Add
  usable in one compact row. Resizing it taller adds up to three focus task
  titles and their actions; title privacy or app lock replaces those expanded
  details with a generic label. Widget state never appears in a backup.
- App-lock overlay: an unlock screen replaces all content, with no
  workspace data composed behind it, using one platform biometric prompt
  with device-credential fallback. It takes precedence over the recovery
  shell: a locked device always shows the overlay first, even mid-recovery.
- Shortcut help dialog: a plain dialog opened by `?` lists every keyboard
  and mouse shortcut with its action, read from `stringResource`.
- Calendar preview dialog: shows the exact title, start/end times in the
  moment's stored zone, and description that Insert will hand to the
  device calendar app, with explicit Insert/Cancel actions.

All interactive components cover default, pressed, focused, selected, disabled,
loading, error, and empty states where relevant.

## Backup & recovery

More includes two independent **Backup & recovery** cards. The Android backup
package card supports Not prepared, Preparing, Ready, Update pending,
Unavailable, and Restored package detected. Setup explains that Android backup
is supplementary, that the recovery passphrase cannot be recovered, and that
local package readiness is not an upload claim. Ready shows local production
time, generation, current generation, and bytes.

The Encrypted app backup card exposes explicit connection, Back up now,
passphrase change, disconnect, permanent history deletion, and ownership-loss
recovery only when the current lifecycle supports each action. Backing up uses
the runner's retained execution sequence and the exact local generation
captured under the publication gate, so a newer edit remains pending. No active
backup service is created for NoVault, Unreadable, Activating, or Recovering.

The recovery shell keeps Drive and Android-package recovery independent.
NoVault also offers Start without restoring; active replacement never does.
Failures use text plus Error semantics, keep both recovery actions available,
and do not overwrite an unreadable vault. Passphrase and confirmation fields
are masked, non-saveable, and cleared after submission. Task 13 is complete:
transient provider failures receive truthful temporary-unavailability retry
copy rather than Sign in guidance, and genuine `MainActivity` production-route
recreation coverage proves private passphrase input is not restored.

Whole-vault export and import add two more independent rows to More.
Export asks for a passphrase with confirmation, then hands the person a
Storage Access Framework picker; the archive streams straight to the
chosen document, and any result other than success deletes the partial
file. Import asks for the archive's passphrase, stages it, and shows a
confirmable preview of exact record and attachment counts before
replacing the device's vault; declining the preview discards the staged
archive.

The programme retains four independent concerns:

- **Encrypted app backup** — enabled state, last verified time, pending
  generation, failure category, retry, and **Back up now**.
- **Android backup package** — ready or unavailable state, local production
  time, generation, and size. It never claims that Android uploaded the
  package or invents a platform backup time.
- **Cloud attachments** — account connection and combined encrypted-frame
  cache plus plaintext staging usage, with clear remote, downloading,
  unavailable, tombstoned, and failed states. Content deletion requires the
  passphrase and preserves metadata/history.
- **Active device** — the current writer and explicit takeover explanation.

Backup, attachment, and active-device states use text and iconography and
remain distinct. Local task editing stays available when backup or attachment
transport is offline or failing. Android Auto Backup and device transfer now
use the verified exact-file allow-list; the interface still cannot infer
platform upload or restore success.

No backup indicator is present on Home. App-managed backup may show attention
only after it is configured and blocked or meaningfully overdue. The threshold
remains injected product policy, not a format claim.

## Language and locale

The application language is UK English (`en-GB`). User-facing copy uses UK
spelling, the deletion surface is called Bin, dates use day–month order and
times use the 24-hour clock. Android's per-app locale declaration and startup
configuration keep system date pickers and formatted notifications aligned.
Stable internal command/type names may retain historical terminology and are
not user-facing. A future additional locale requires extracting remaining
Compose literals into resources before it can be advertised as supported.

## Continuity

Rotation, fold changes and process recreation preserve the current
destination, selected record, filters, meaningful scroll position and open
drafts. A restored unsaved editor draft takes precedence over the first
repository emission so the interface never appears to recover text and then
replace it. Running timers resume from their persisted start instant rather
than restarting or freezing at the last rendered elapsed value.

Transient recovery must remain unobtrusive: do not add success banners for
ordinary restoration. Existing validation and save-status copy should describe
the restored draft accurately, and search should re-run its restored query.

## Motion

State transitions last 150–250 ms using Material easing. Animate navigation
selection, list/detail state, completion, and snackbar feedback only when motion
helps explain a change. If system animator duration is zero, use an instant
change or a short crossfade. Do not choreograph page-load entrances.

## Data visualisation

Dot matrix is the house visual language for every chart, meter, progress
indicator and density cue — in the app and in any design material produced
about it. `DotRunBar` and `DottedAreaChart` in `:core:designsystem` are the
established primitives: Insights progress, the Insights completion trend,
Month day density and Timeline spans all read as counted dots rather than
solid fills. Any new visualisation follows the same language. Do not
introduce solid progress bars, filled area charts or pie charts.

Marks stay decorative beneath merged label-and-value semantics, so density,
shape, position and text carry the signal rather than colour. A visual cap —
six dots in a Month cell, 84 in a Timeline row — is a drawing limit only;
the exact number always remains available as text and in the accessible
description.

## Stage 6 daily flow

Search presents saved views as named, editable chips above results. Applying a
view restores its text, project, tag, status, and date filters together; Save,
Rename, and Delete use compact dialogs and preserve repository Undo.

Tasks enters multi-select from a long press, checkbox, keyboard, or accessible
action. A persistent selection bar states the exact count and offers Complete,
Reschedule, Move, Tag, Delete, and Clear with 48 dp targets. Blocked completion
requires one explicit confirmation; successful batch actions leave through a
single snackbar Undo.

The project workbench offers List and Board as a two-option control. Board
columns use the project's workflow order, state a visible open count, and keep
cards legible at high font scale. Every card supports long-press drag with
drop-target highlight and edge auto-scroll, plus a complete three-dot
tap-to-move menu and equivalent TalkBack custom actions. Invalid drops leave the
task in its original column.

Weekly review is a linear, resumable sheet over Overdue, Stale, Unscheduled,
and Project health. Each step states section progress, shows one task or project
with enough context to decide, and offers Review or Skip; empty sections advance
without ceremony. Back is consumed while a review write is pending.

The active focus banner names the 25/5 or 50/10 cycle, current Focus or Break
phase, remaining time, and Stop. Task-detail Stop for the focus-owned task ends
the complete cycle, so the banner must remain gone after background/foreground
reconciliation. Boundary notifications use generic text and expose no private
task content.

The Today widget retains canonical open and complete actions with count-first
locked/title-private states. Quick Add can also arrive from browser sharing,
selected text, the Quick Settings tile, and the existing launcher/widget
routes; every route converges on the same lock-gated sheet.

The installed adaptive launcher icon uses the Ember background with the
approved white card, coral status bars, and dark cursor foreground. Normal and
round launcher resources share that adaptive artwork; the monochrome layer
keeps the card, bars, and cursor distinct for themed or minimal icon treatment.
The artwork stays centred with safe inset under Android launcher's round mask.

## Stage 7 ergonomics (implemented)

Tasks and project-workbench lists expose compact sort and group controls. Sort
directions are fixed: due soonest, priority highest, title A–Z, or most recently
updated. Grouping is limited to due bucket, project where applicable, and
priority. Board columns retain workflow grouping and expose only in-column due,
priority, or title sorting.

Saved filters appear as identity-stable chips. Typing may refine an active
filter without discarding its due-bucket, priority, semantic-status, or sort
choices; the active chip remains visible with an explicit clear action.

Quick Add presents each detected project, tag, priority, recurrence, estimate,
or date phrase as a suggestion chip. A token applies only after its own chip is
confirmed, may be dismissed independently, and never silently rewrites the
title. Task detail exposes Duplicate and board-card menus do likewise;
successful duplication offers the ordinary snackbar Undo.

Insights replaces solid progress bars with ember dot runs and adds a dotted
completion-per-day trend for the selected range. Chart and table presentations
remain equivalent. Dots and charts are decorative beneath merged label-and-value
semantics, so density, shape, text, and accessibility names carry the signal
rather than colour alone.

## Stage 8 planning surfaces (implemented)

Schedule offers Week and Month at every window size. Both are stateless views
over a live device-zone clock. Placement stays one task, one day: start when
it is present, otherwise due, converted to a local date in that moment's own
stored zone. Every non-binned dated task appears, completed work included
with completed icon and text treatment, and undated open work stays in the
unscheduled tray. The chosen mode, the selected day and the Month anchor are
saveable interface state, never vault or backup content.

Rescheduling is exact and single-task. The tap and menu path is the complete
route: a 48 dp Reschedule action on every movable row, Remove schedule where
it is valid, and one confirmation before a reminder is cleared along with its
schedule. Pointer drag is layered over that path and introduces no separate
rule — the same targets, the same confirmation, the same snackbar Undo, and
the same repository message when a move is refused. A move preserves each
moment's local time and stored zone; a task holding both start and due shifts
by a single calendar-day delta and keeps its span. Dropping an undated task
on a day makes it due at 18:00 in the current device zone, deliberately
different from the editor's 17:00 default for a newly typed due date. A
reminder follows its due moment by the same lead, and a move that would put
that reminder in the past is refused with actionable feedback rather than
silently dropping the alarm. Recurring work moves only its current
occurrence and can never reach the tray.

The task editor now owns the whole schedule: native start date and time
controls beside the due date and its new explicit time control. A new start
defaults to 09:00 and a new due date to 17:00 in the current device zone;
existing moments keep what they already had. Due before start is refused
inline, and a legacy or imported invalid record stays readable behind a
warning until it is corrected.

### Project Timeline

Timeline is the third project-workbench presentation: read-only, and bounded
to a Monday-aligned 12-week window of 84 days. The bound is the design — no
project history can grow an unbounded canvas. Previous and Next move four
weeks, Today returns to the current week's Monday, and the day header, the
milestone row and every task row's grid area scroll horizontally together.

Every non-binned project task keeps a row, whatever its dates:

- start and due render an inclusive dot-run span between their stored-zone
  local dates;
- start only or due only render a labelled marker with its own icon;
- a span crossing a window edge clips there and adds a continuation chevron;
- a task lying wholly before or after the window states Before window or
  After window rather than a false in-window point;
- due before start states the invalid range instead of drawing a backwards
  span; and
- undated tasks collect in a compact Unscheduled list below the grid.

Status cues never stack on one another. At each clipped edge the continuation
chevron and the completed or blocked icon occupy side-by-side slots, so a
long completed or blocked task running past the window stays legible and both
cues remain independently named in the row's description.

Milestones dated inside the window are 48 dp diamonds on their own row, with
completed icon and text treatment when they are done. Milestones outside it
contribute exact before and after counts in one summary line rather than
inventing an in-window position, and undated milestones stay in the existing
milestone list, which remains present in all three presentations.

Selecting a row highlights its complete transitive dependency context.
Highlighting carries both a container tint and a distinct leading icon for
selected, prerequisite, dependant and both, so the relationship never depends
on colour. The chain summary counts unique out-of-project tasks, not
dependency edges. There is no arrow routing and nothing is draggable; a
separate 48 dp Open action, a sibling outside the row's merge boundary, opens
the editor, so selecting a row and opening it can never be confused.

Every row is one merged semantics node naming the title, the complete start
and due dates, the total duration in days, any clipping or outside state,
completion, blocked state and dependency role. Spans, markers and diamonds
are cleared from the semantics tree beneath it. Shape, icon, label and that
description carry duration, completion, blocked, milestone and dependency
meaning independently of colour.

Per-project presentation, the Timeline anchor — always a Monday — and the
selected row are saveable interface state scoped to the project, so two
projects never share a view.

### Daily digest

The daily digest is opt-in and stays off until it is switched on. Its inline
More setting is a switch plus, only while it is on, a 48 dp row showing the
chosen time as `HH:mm` and opening the platform's native 24-hour picker.
There is no new destination and no separate settings screen. Notification
access is never a precondition of the switch: a refused or revoked permission
leaves the digest on and its time visible, states plainly that delivery is
unavailable, and offers a 48 dp action to turn notifications on. First use
defaults to 08:00 local.

Delivery is counts only. The private notification reads Today with
`3 open today · 1 overdue`; the public lock-screen version shows the generic
Daily digest title and no counts, no titles and no workspace content. When
both counts are zero nothing is posted at all. The digest owns its own Daily
digest channel with private lock-screen visibility, so it can be silenced
without touching reminders, and tapping it opens Home — behind the app-lock
overlay, which always takes precedence over any workspace composition.
Exactly one digest is posted per local day, and switching the setting off and
on again cannot repeat that day's digest.

The setting is device-local to this installation, excluded from Android
backup, and never appears in vault content or a backup.

These surfaces are implemented and their whole-stage review is closed with
zero Critical findings. Device confirmation of the drag, time-picker and
digest legs belongs to the stage qualification record rather than to this
document; see `docs/qualification/stage8-planning-surfaces.md`.

## First-run and executive dashboard design (implemented)

This design is implemented. The visual and behavioural contract remains
`docs/superpowers/specs/2026-08-21-offline-onboarding-executive-dashboard-nfr-design.md`.

### Welcome

A missing vault renders Welcome, not recovery discovery. Compact is one calm,
vertically balanced column; expanded may use a restrained identity/action
split. Both reuse the light-only charcoal/ember system, system typography,
dot motif, existing spacing, and 48 dp controls. No new illustration or design
dependency is required.

The screen contains `Welcome to Open Tasks`, one concise private-local
workspace sentence, `Continue with Google` with the Google mark, the immediate
disclosure `Optional — Google Drive is used only for encrypted backup and
recovery.`, `Continue offline`, and `Restore from this device`. The rejected
`Works offline` and `Encrypted locally` bubbles do not appear. Google and
portable discovery begin only from their respective actions.

The layout must preserve complete labels, logical TalkBack/focus order,
visible keyboard focus, 48 dp targets, and action reachability at 200% font
across compact, folding, and expanded windows.

### Executive HTML

Insights gains one `Generate executive dashboard` section with an
off-by-default `Include task details` switch, `Download HTML`, `Share HTML`,
progress/error state, and a permanent plaintext disclosure. It is not a new
destination and has no embedded browser preview.

The output is one responsive, semantic, self-contained page. It reuses the
charcoal/ember palette, tabular numerals, thin rules, and dot runs; it avoids
pie charts, decorative gradients, card-grid SaaS composition, solid area
walls, and oversized hero metrics. Its executive story flows from summary
through portfolio and milestone health, ageing, completion, estimate/actual,
time allocation, blockers, and data-quality caveats. Project/status/risk
filters and secondary disclosure work offline; the first useful reading does
not depend on JavaScript. Budget, cost, team capacity, resource allocation,
and benefits are labelled as not tracked; unavailable metrics are never
rendered as invented zeros.

Print styles, reduced motion, keyboard operation, visible focus, text
alternatives, non-colour states, and 200% browser zoom are release gates.
Aggregate data is the default. Optional task detail never includes
descriptions, notes, activity bodies, attachment names, provider IDs, or
credentials.

Automated Compose and HTML tests cover the implemented layout, semantics,
focus, hostile content, CSP, print, and reduced-motion contracts. The manual
two-browser, print-preview, keyboard, zoom, and screen-reader release exercise
is still pending in
`docs/qualification/onboarding-dashboard-nfr-acceptance.md`.
