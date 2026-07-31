# Galaxy Z Fold 8 and Trifold-Ready Adaptive Layout Design

- Date: 31 July 2026
- Status: Approved design; implementation scheduled after Stage 3 closes
- Owner request: make Open Tasks work best on the Samsung Galaxy Z Fold 8
  family, incorporating the Android trifold/landscape-foldable guidance,
  the Samsung Galaxy Z technical documentation, and the One UI
  large-screen and foldable design guidelines.

## Sources

1. Android Developers — Support trifolds and landscape foldables
   (developer.android.com/develop/adaptive-apps/guides/foldables/
   trifolds-and-landscape-foldables)
2. Samsung Developer — Galaxy Z Tech Docs
   (developer.samsung.com/foldables-and-largescreens)
3. Samsung Developer — One UI Design Guidelines, large screen and
   foldable (developer.samsung.com/one-ui/largescreen-and-foldable)

Key device facts established during design: the Galaxy Z Fold 8 and
Z Fold 8 Ultra are book-style bifolds (5.5-inch 10:16 cover display;
7.6-inch 4:3 main display; the Ultra has an 8-inch main display). The
Samsung trifold is a separate device class with multiple fold lines, no
tabletop posture, and no `HALF_OPENED` state. The trifold guide's
general foldable rules (orientation assumptions, density switches,
configuration-change continuity, no letterboxing) apply to both.

## Scope decisions (user rulings)

1. **Device scope:** optimise for the Z Fold 8 family and make the
   layout contracts trifold-ready (multiple folds, landscape-first
   naturals, no `HALF_OPENED` assumption, density switches). No
   dedicated trifold-optimised layouts until such a device is testable.
2. **Sequencing:** design and plan are committed now; implementation
   runs as a standalone product slice immediately after Stage 3's final
   gates, before Stage 4. Stage 3 Task 13's backup/recovery surfaces
   are re-checked under the new policy inside this slice's acceptance.
3. **Pane ratios:** adopt the One UI ratios as the pane contract
   (42/58 at 600–959 dp, 38/62 at 960 dp and above, fold-aware 50/50
   snapped to the hinge on a separating fold).
4. **Verification:** custom AVD hardware profiles for the Fold 8 family
   plus a Samsung Remote Test Lab session on a real Z Fold 8 for final
   One UI visual QA.
5. **Approach:** widen the pure `WorkspaceLayoutPolicy` (approach A).
   The Material 3 adaptive library was rejected because the repository
   rule is that layout comes only from `WorkspaceLayoutPolicy`, never
   `WindowSizeClass`; a minimal patch was rejected because it cannot
   represent trifold postures.

## Layout contract

### Input: `WindowPosture`

`WorkspaceLayoutPolicy.calculate` takes one immutable value:

```kotlin
data class WindowPosture(
    val widthDp: Int,
    val heightDp: Int,
    val foldLines: List<FoldLine>,
)

data class FoldLine(
    val orientation: FoldOrientation, // VERTICAL or HORIZONTAL
    val isSeparating: Boolean,
    val positionDp: Int,              // from the leading/top edge
    val occludedWidthDp: Int,         // hinge thickness; 0 if none
)
```

- `foldLines` is bounded: at most 4 entries are considered; extras are
  ignored fail-safe.
- The app layer (in `:app`) translates Jetpack `WindowLayoutInfo` into
  `WindowPosture`. Feature modules never see WindowManager types.
- No consumer reads device model, device rotation, or `WindowSizeClass`.
- A Fold 8 unfolded is one vertical separating fold; a trifold is two;
  a tabletop-posture bifold is one horizontal fold. `HALF_OPENED` is
  never assumed to exist; the policy only interprets the fold lines it
  is given.

### Output: `WorkspaceLayout`

Existing fields are preserved. Window-class thresholds are unchanged
(COMPACT < 600, MEDIUM < 840, EXPANDED < 1200, EXTRA_WIDE ≥ 1200) and
already align with the One UI window-class table. New output:

```kotlin
data class PaneSplit(
    val listFraction: Float,          // 0.42, 0.38, or hinge-derived
    val snapToFoldPositionDp: Int?,   // non-null when a fold wins
)

val paneSplit: PaneSplit?             // null when one pane is shown
val hingeExclusionBandDp: IntRange?   // horizontal separating fold
```

Rules:

- MEDIUM and EXPANDED below 960 dp: list/detail split 42/58.
- 960 dp and above: 38/62.
- A vertical separating fold overrides the ratio: the split snaps to
  the fold position (50/50 on the Fold 8), so panes meet at the
  physical hinge. With multiple vertical separating folds (trifold),
  the fold nearest the window centre is the snap candidate.
- The supporting pane remains an EXTRA_WIDE feature (≥ 1200 dp). The
  Fold 8's 4:3 main display (~840–900 dp wide) does not get it; a
  fully unfolded trifold does.
- A horizontal separating fold yields `hingeExclusionBandDp`; editors
  and sheets keep critical controls out of that band in tabletop
  posture. No dedicated Flex-mode layouts beyond this (YAGNI).
- Window class derives from width only. Height feeds only
  tabletop/aspect handling. Nothing assumes rotation 0 = portrait.

## Continuity and density

- Fold/unfold on the Fold 8 switches displays with different densities
  and fires several configuration changes at once. The design keeps the
  proven path: default activity recreation plus the existing
  restoration layers (serialisable Navigation 3 state,
  `SavedStateHandle` selection, bounded Compose saveable state and
  drafts, Room-owned timers).
- A new acceptance test edits a draft on the cover screen, unfolds, and
  requires the draft, scroll position, and selection to survive the
  density switch.
- The manifest `android:configChanges` opt-out is deliberately not
  taken: hand-processing every density-dependent resource is a standing
  risk the recreation path does not have.
- No orientation lock anywhere (including `nosensor`). The application
  remains fully resizeable; on targetSdk 37 the platform ignores
  orientation/aspect restrictions on large screens, so there is no
  letterboxing path.

## Cover screen and multi-window

- The 5.5-inch 10:16 cover display is a narrow compact window.
  Acceptance adds a ~330 dp-wide pass; navigation labels already
  collapse before wrapping at large text scales.
- One UI multitasking: the app must stay correct at split-screen one-
  half and one-third widths and in pop-up view. Policy JVM tests cover
  those widths; one device pass confirms rendering.

## Verification matrix

1. **JVM policy tests** for every posture: folded and unfolded Fold 8,
   synthetic trifold (two vertical separating folds; nearest-to-centre
   wins the snap), tabletop (horizontal fold), landscape-first
   naturals, and split-screen widths.
2. **Compose device tests** asserting pane fractions and hinge snap.
3. **Two new AVD hardware profiles** (Fold 8: 10:16 cover + 4:3 main;
   Fold 8 Ultra: 8-inch main) for visual acceptance at 100% and 200%
   text, light theme.
4. **Samsung Remote Test Lab** session on a real Z Fold 8: One UI
   taskbar, Samsung keyboard, multi-window resize, pop-up view, and
   app-continuity checks, recorded as an acceptance checklist in the
   implementation evidence.

## Rollout

- Implementation is a standalone product slice scheduled immediately
  after Stage 3's exit gates, before any Stage 4 work.
- Stage 3 Task 13 surfaces are re-checked under the new policy as part
  of this slice's acceptance.
- `DESIGN.md` gains the posture/ratio contract; `HANDOFF.md` gains the
  backlog entry; architecture docs are updated only if the app-layer
  posture mapping changes a documented boundary.

## Non-goals

- Camera handling (the application has no camera surface; recorded as
  a consideration for Stage 4 attachments).
- Dedicated Flex-mode split layouts; rear-display and dual-screen
  modes.
- Samsung SDK dependencies of any kind.
- DeX-specific work beyond resizeable-window correctness.
- Trifold-optimised triple-pane compositions (blocked on testable
  hardware or emulator profiles; the contract is ready for them).
