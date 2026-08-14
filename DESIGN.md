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

Stage 8 is an in-development, unqualified checkpoint. The landed Schedule
work adds a Monday-first 42-cell Month view, start/due editor controls, a
complete non-drag rescheduling fallback, and long-press pointer drag layered
over that fallback: expanded Week drags between day columns and the tray,
Month drags agenda and tray rows onto visible cells, compact Week stays
tap/menu only by design, only open non-binned tasks drag, and recurring tasks
cannot target the tray. The drag preview renders unclipped above scroll
containers and is RTL-safe; the tap/menu fallback remains complete. Undated
day placement is due at 18:00 in the current device zone, while a new editor
due date defaults to 17:00. Week/Month remain stateless and use the live
device zone. Timeline UI and saved state, daily digest, device coverage, and
release qualification remain pending.
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
the runner's in-flight state and local generation. No active backup service is
created for NoVault, Unreadable, Activating, or Recovering.

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
