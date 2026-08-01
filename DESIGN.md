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
Existing dark support is retained as best-effort but is not an acceptance gate.

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

### Dark (best-effort)

- Background: `oklch(0.150 0.006 29)`.
- Surface: `oklch(0.190 0.007 29)`.
- Surface bright: `oklch(0.250 0.009 29)`.
- Ink: `oklch(0.940 0.004 29)`.
- Muted ink: `oklch(0.720 0.008 29)`.
- Outline: `oklch(0.420 0.010 29)`.
- Ember: `oklch(0.720 0.150 32)`.
- Deep ember container: `oklch(0.300 0.065 32)`.
- Error: `oklch(0.720 0.150 25)`.
- Success: `oklch(0.720 0.100 150)`.

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
- Medium: navigation rail with list/detail when both panes meet their minimums.
- Expanded: rail with a 360–420 dp list pane and a flexible detail pane.
- Extra wide (at least 1,200 dp): a supporting pane may appear.

Every touch target is at least 48 dp. Window size and folding posture determine
structure; hardware model names never do.

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
  next and horizontally scrollable weekday controls. Expanded windows show the
  ISO Monday–Sunday week as date-grouped timeline columns beside an open-only
  unscheduled tray. Start time takes precedence over due time for placement;
  due-only work is labelled explicitly, and completion, blocking and reminders
  never rely on colour alone.
- Snackbar: confirms immediate edits and offers Undo where reversible.
- Search: modal command surface opened from UI or `/` / `Ctrl+K`.

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
are masked, non-saveable, and cleared after submission. Task 13 remains
review-incomplete at the current checkpoint: transient provider failures must
receive truthful retry copy rather than Sign in guidance, and recovery must be
proved across genuine Activity/production-route recreation before this surface
is treated as complete.

The programme retains four independent concerns:

- **Encrypted app backup** — enabled state, last verified time, pending
  generation, failure category, retry, and **Back up now**.
- **Android backup package** — ready or unavailable state, local production
  time, generation, and size. It never claims that Android uploaded the
  package or invents a platform backup time.
- **Cloud attachments** — account connection and temporary-cache usage, with
  clear remote, downloading, unavailable, tombstoned, and failed states.
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
