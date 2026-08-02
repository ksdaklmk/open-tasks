# Galaxy Fold 8 Adaptive Acceptance

- Date: 2 August 2026
- Emulator result: **PASS — 38/38 required visual rows**
- Samsung Remote Test Lab result: **External-blocked**
- Product source under test: `9cc6057` (`fix: stack project status at large text`)
- Slice implementation range: `7276f90..9cc6057`
- Ignored evidence root:
  `.superpowers/sdd/2026-07-31-galaxy-fold8-trifold-adaptive-plan/task-5-evidence/`

This qualification closes the emulator acceptance portion of the approved
Galaxy Z Fold 8 trifold-ready adaptive slice. Samsung Remote Test Lab remains
an explicitly recorded external gap because the user's Samsung developer
account approval is pending. No Samsung sign-in page was opened and no
credential was requested or handled.

## Acceptance environment

| Profile | API | Physical display | Resolution | Density | Approximate dp | Theme | Text scales |
|---|---:|---|---:|---:|---:|---|---|
| `Fold8_Acceptance` | 37 | Main | 2160×1856 | 420 dpi | 823×707 | Light | 100%, 200% |
| `Fold8_Acceptance` | 37 | Cover | 1080×1728 | 420 dpi | 411×658 | Light | 100%, 200% |
| `Fold8_Ultra_Acceptance` | 37 | Main | 2268×1968 | 420 dpi | 864×750 | Light | 100%, 200% |

The physical SurfaceFlinger display IDs were `4619827259835644672` for the
main display and `4619827551948147201` for the cover display. Each AVD ran
sequentially as the sole target with `-read-only -no-snapshot-save
-no-snapshot-load -no-window -no-boot-anim`. Before each matrix the controller
verified the exact AVD name, API, physical display, density, light theme and
font scale. UIAutomator confirmed the intended route and selected fixture;
every accepted PNG was then inspected at original resolution.

The protected `Pixel_10_Pro_Fold` AVD was not started or mutated. The final
font scale was restored to `1.0`, each disposable emulator was shut down, and
the final ADB and Fold 8 emulator-process audits were empty.

## Visual acceptance matrix

Every row below passed without clipping, overlap, inaccessible controls or
unintended navigation substitution. At 200% text, wrapping and scrolling are
accepted when all content and controls remain reachable. The Projects rows use
the selected `Client research` workbench, not the visually easier empty state.

### Fold 8 cover — 14/14 PASS

| Scale | Surface | Result | Evidence file |
|---:|---|---|---|
| 100% | Home | PASS | `fold8-cover-100/home.png` |
| 100% | Tasks list | PASS | `fold8-cover-100/tasks-list.png` |
| 100% | Task editor | PASS | `fold8-cover-100/task-editor.png` |
| 100% | Projects workbench | PASS | `fold8-cover-100/projects-workbench-final.png` |
| 100% | Schedule | PASS | `fold8-cover-100/schedule.png` |
| 100% | More | PASS | `fold8-cover-100/more.png` |
| 100% | Quick Add | PASS | `fold8-cover-100/quick-add.png` |
| 200% | Home | PASS | `fold8-cover-200/home.png` |
| 200% | Tasks list | PASS | `fold8-cover-200/tasks-list.png` |
| 200% | Task editor | PASS | `fold8-cover-200/task-editor.png` |
| 200% | Projects workbench | PASS | `fold8-cover-200/projects-workbench-green.png` |
| 200% | Schedule | PASS | `fold8-cover-200/schedule.png` |
| 200% | More | PASS | `fold8-cover-200/more.png` |
| 200% | Quick Add | PASS | `fold8-cover-200/quick-add.png` |

### Fold 8 main — 12/12 PASS

| Scale | Surface | Result | Evidence file |
|---:|---|---|---|
| 100% | Home | PASS | `fold8-main-100/home.png` |
| 100% | Tasks list/detail | PASS | `fold8-main-100/tasks-list-detail.png` |
| 100% | Task editor | PASS | `fold8-main-100/task-editor.png` |
| 100% | Projects workbench | PASS | `fold8-main-100/projects-workbench-final.png` |
| 100% | Schedule week view | PASS | `fold8-main-100/schedule-green.png` |
| 100% | More | PASS | `fold8-main-100/more.png` |
| 200% | Home | PASS | `fold8-main-200/home.png` |
| 200% | Tasks list/detail | PASS | `fold8-main-200/tasks-list-detail.png` |
| 200% | Task editor | PASS | `fold8-main-200/task-editor.png` |
| 200% | Projects workbench | PASS | `fold8-main-200/projects-workbench-green.png` |
| 200% | Schedule week view | PASS | `fold8-main-200/schedule-green.png` |
| 200% | More | PASS | `fold8-main-200/more.png` |

### Fold 8 Ultra main — 12/12 PASS

| Scale | Surface | Result | Evidence file |
|---:|---|---|---|
| 100% | Home | PASS | `fold8-ultra-100/home.png` |
| 100% | Tasks list/detail | PASS | `fold8-ultra-100/tasks-list-detail.png` |
| 100% | Task editor | PASS | `fold8-ultra-100/task-editor.png` |
| 100% | Projects workbench | PASS | `fold8-ultra-100/projects-workbench.png` |
| 100% | Schedule week view | PASS | `fold8-ultra-100/schedule.png` |
| 100% | More | PASS | `fold8-ultra-100/more.png` |
| 200% | Home | PASS | `fold8-ultra-200/home.png` |
| 200% | Tasks list/detail | PASS | `fold8-ultra-200/tasks-list-detail.png` |
| 200% | Task editor | PASS | `fold8-ultra-200/task-editor.png` |
| 200% | Projects workbench | PASS | `fold8-ultra-200/projects-workbench.png` |
| 200% | Schedule week view | PASS | `fold8-ultra-200/schedule.png` |
| 200% | More | PASS | `fold8-ultra-200/more.png` |

Matching XML state captures accompany the final selected-project workbench,
all Ultra rows, both Fold main Schedule corrections and the Fold main 200%
rows where exact route/state confirmation was material.

## Findings fixed during acceptance

The first matrix was stopped at each real visual failure, the product was
fixed, and the affected row was recaptured before acceptance continued:

- `38a84f8` (`fix: show week schedule on medium windows`) corrected the main
  display selecting the compact day agenda rather than the required week view.
  The original red capture is `fold8-main-100/schedule.png`; the accepted
  captures are the corresponding `schedule-green.png` files.
- `da75a9e` (`fix: keep project progress readable at large text`) made the
  selected-project progress header stack at large text instead of squeezing
  progress copy and counts into overlapping columns. The red capture is
  `fold8-main-200/projects.png`.
- `9cc6057` (`fix: stack project status at large text`) moved the local-save
  status into the title column so the selected workbench remains readable on
  the 200% cover display. The red capture is
  `fold8-cover-200/projects-workbench.png`.

## Fold and hinge evidence boundary

The physical main and cover display captures validate the size-class,
navigation, one-pane and list/detail surfaces used by the two AVD profiles. A
single bounded emulator fold/unfold probe was accepted by the emulator, but
`cmd device_state` exposed only state identifier `0` (`DEFAULT`) and AndroidX
reported no `FoldingFeature`, `WindowLayoutInfo` hinge or separating fold.
Therefore this qualification makes **no native emulator hinge claim**.

Task 3 instrumentation independently proves the product contract for a
synthetic vertical separating fold: the two panes use a 50/50 split snapped to
the supplied hinge. Task 4 instrumentation independently proves horizontal
hinge exclusion and fold-continuity restoration. Together these tests cover
the posture policy without misrepresenting the AVD's native fold telemetry.

## Samsung Remote Test Lab

| Required real-device check | Status |
|---|---|
| Cover-to-main continuity with an in-progress draft | External-blocked |
| One UI taskbar versus compact bottom navigation | External-blocked |
| Samsung keyboard in the editor on cover and main displays | External-blocked |
| Split-screen at one-half and one-third widths | External-blocked |
| Pop-up view | External-blocked |
| Pane alignment at the physical hinge | External-blocked |

Reason: Samsung developer account approval is pending. The approved plan
allows the slice to close with emulator evidence plus this explicit gap. These
rows must be executed on a real Galaxy Z Fold 8 before making real-device or
One UI integration claims.

## Repository gates

The final documentation commit was prepared only after running the required
gates from the product source above:

```text
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
BUILD SUCCESSFUL in 515ms
547 actionable tasks: 12 executed, 535 up-to-date

./gradlew :app:assembleRelease --stacktrace
BUILD SUCCESSFUL in 32s
441 actionable tasks: 55 executed, 1 from cache, 385 up-to-date
Included minifyReleaseWithR8, resource shrinking/optimisation and packaging.

git diff --check
PASS (no output)
```

No credential, account identifier, private task content or protected workspace
data appears in this record or its evidence. The pre-existing user-owned plan
amendment, `.kotlin/` directory and `artifacts/` directory were preserved and
remain outside this task's commit.

## Exit decision

The Fold 8 emulator adaptive acceptance slice is complete. Samsung RTL remains
External-blocked and visible in the handoff. Pause here; Stage 4 may start only
after a new explicit user request.
