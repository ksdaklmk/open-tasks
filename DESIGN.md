# Design System

## Intent

Open Tasks feels like a well-kept charcoal desk in changing light: task-dense
and precise on the inside, with a controlled ember signal only where time,
progress, selection, or data needs attention. The color strategy is restrained.
Orange never becomes decorative background color and charcoal remains the
filled-action color.

The primary usage scene is a solo professional moving between a foldable cover
screen in daylight, the unfolded workbench at a desk, and a tablet in lower
evening light. Both light and dark schemes are first-class.

## Color

Color tokens are authored as OKLCH values and converted to sRGB in Compose.
This preserves the design contract without scattering raw hexadecimal colors.

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

### Dark

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
container shape; color never carries meaning alone. Dynamic Color is disabled.

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

Do not use colored side-stripes. Prefer spacing and section rules to wrapping
everything in cards. Tonal elevation communicates layers; shadows are reserved
for floating controls and temporary surfaces.

## Components

- Adaptive navigation: five destinations, bar on compact and rail otherwise.
- Context FAB: one Quick Add action; label may expand where room permits.
- Task row: completion control, title, project, due context, priority, and
  blocking state with accessible custom actions.
- Sync health: text and icon with a tonal attention state; never a decorative
  dot or color-only signal.
- Timer: charcoal focus surface with ember numerals and explicit pause/stop.
- Filters: Material chips with selected state and accessible names.
- Workbench: list and persistent detail panes with visible selection.
- Snackbar: confirms immediate edits and offers Undo where reversible.
- Search: modal command surface opened from UI or `/` / `Ctrl+K`.

All interactive components cover default, pressed, focused, selected, disabled,
loading, error, and empty states where relevant.

## Motion

State transitions last 150–250 ms using Material easing. Animate navigation
selection, list/detail state, completion, and snackbar feedback only when motion
helps explain a change. If system animator duration is zero, use an instant
change or a short crossfade. Do not choreograph page-load entrances.
