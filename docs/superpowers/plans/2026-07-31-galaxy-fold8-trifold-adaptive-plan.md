# Galaxy Z Fold 8 Trifold-Ready Adaptive Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Open Tasks adaptive-correct and One UI-conformant on the
Galaxy Z Fold 8 family while making the layout contracts trifold-ready.

**Architecture:** Widen the pure `WorkspaceLayoutPolicy` to consume an
immutable `WindowPosture` (width, height, bounded fold lines) and emit
explicit pane fractions with hinge snapping and a horizontal-fold
exclusion band. The `:app` layer keeps sole responsibility for
translating Jetpack `WindowLayoutInfo` into neutral posture data;
feature composables stay stateless and receive plain fractions.

**Tech Stack:** Kotlin, Compose, androidx.window 1.5.1 (already a
dependency), JUnit 4, Compose UI test `junit4.v2`.

**Approved design:**
`docs/superpowers/specs/2026-07-31-galaxy-fold8-trifold-adaptive-design.md`

## Global Constraints

- Implementation starts only after Stage 3's exit gates (user ruling).
- Layout decisions come only from `WorkspaceLayoutPolicy`. Never read
  `WindowSizeClass`, device model, or device rotation. Nothing assumes
  rotation 0 = portrait or that `HALF_OPENED` exists.
- One UI pane ratios are exact: list fraction `0.42f` below 960 dp,
  `0.38f` at 960 dp and above; a vertical separating fold overrides the
  ratio and snaps the split to the fold position. With several vertical
  separating folds, the one nearest the window centre wins; on a tie
  the leading (smaller `positionDp`) fold wins.
- Window-class thresholds stay 600/840/1200. The supporting pane stays
  EXTRA_WIDE-only.
- At most 4 fold lines are considered; extras and folds with
  `positionDp <= 0` or `positionDp >= widthDp` (vertical) /
  `positionDp >= heightDp` (horizontal) are ignored fail-safe.
- No new dependencies. No Material 3 adaptive library. No Samsung SDKs.
- No manifest `android:configChanges` opt-out; no orientation lock
  (including `nosensor`). Continuity uses the existing recreation +
  saved-state layers.
- Feature composables stay stateless and Hilt-free: plain data in,
  lambdas out. Feature modules depend only on `:core:model` and
  `:core:designsystem`.
- JUnit 4 assertions, camelCase test names, no mocking library, no
  Robolectric. Compose tests use
  `androidx.compose.ui.test.junit4.v2.createComposeRule`.
- Spacing stays on the 4 dp scale; colours stay OKLCH in
  `:core:designsystem`; any new UI copy goes to `res/values/strings.xml`
  in UK English.
- The repository gate is
  `./gradlew testDebugUnitTest lintDebug :app:assembleDebug`; release
  assembly runs separately. Device suites run only on the sole audited
  read-only disposable emulator per the standing procedure (audit
  attached devices by qemu flags; never target the protected instance).
- Do not stage `docs/superpowers/plans/`
  `2026-07-30-stage-3-google-drive-backup-recovery-plan.md` (user-owned
  uncommitted amendment), `.kotlin/`, or `artifacts/`.

---

### Task 1: Posture Model and Widened Policy

**Files:**

- Create: `app/src/main/kotlin/app/opentasks/WindowPosture.kt`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceLayoutPolicy.kt`
- Modify: `app/src/test/kotlin/app/opentasks/WorkspaceLayoutPolicyTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt:365-368`
- Modify: `DESIGN.md` (layout contract section)

**Interfaces:**

- Consumes: nothing new.
- Produces (later tasks rely on these exact shapes):

```kotlin
enum class FoldOrientation { VERTICAL, HORIZONTAL }

data class FoldLine(
    val orientation: FoldOrientation,
    val isSeparating: Boolean,
    val positionDp: Int,
    val occludedWidthDp: Int,
)

data class WindowPosture(
    val widthDp: Int,
    val heightDp: Int,
    val foldLines: List<FoldLine>,
)

data class PaneSplit(
    val listFraction: Float,
    val snapToFoldPositionDp: Int?,
)

data class WorkspaceLayout(
    val windowClass: WorkspaceWindowClass,
    val showNavigationRail: Boolean,
    val showDetailPane: Boolean,
    val showSupportingPane: Boolean,
    val useExtendedQuickAdd: Boolean,
    val paneSplit: PaneSplit?,
    val hingeExclusionBandDp: IntRange?,
)

object WorkspaceLayoutPolicy {
    fun calculate(posture: WindowPosture): WorkspaceLayout
}
```

- [ ] **Step 1: Rewrite the policy test file with failing posture tests**

Replace `WorkspaceLayoutPolicyTest.kt` with tests calling the new
signature. Keep the four existing behaviours (rewritten) and add the
posture matrix:

```kotlin
package app.opentasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLayoutPolicyTest {
    private fun posture(
        widthDp: Int,
        heightDp: Int = 900,
        folds: List<FoldLine> = emptyList(),
    ) = WindowPosture(widthDp, heightDp, folds)

    private fun verticalFold(
        positionDp: Int,
        occludedWidthDp: Int = 24,
        isSeparating: Boolean = true,
    ) = FoldLine(FoldOrientation.VERTICAL, isSeparating, positionDp, occludedWidthDp)

    @Test
    fun compactWindowUsesBottomNavigationAndOnePane() {
        val layout = WorkspaceLayoutPolicy.calculate(posture(412))

        assertEquals(WorkspaceWindowClass.COMPACT, layout.windowClass)
        assertFalse(layout.showNavigationRail)
        assertFalse(layout.showDetailPane)
        assertNull(layout.paneSplit)
    }

    @Test
    fun narrowCoverWidthStaysCompactAndSinglePane() {
        val layout = WorkspaceLayoutPolicy.calculate(posture(330, heightDp = 700))

        assertEquals(WorkspaceWindowClass.COMPACT, layout.windowClass)
        assertNull(layout.paneSplit)
    }

    @Test
    fun mediumWindowUsesOneUiListFraction() {
        val layout = WorkspaceLayoutPolicy.calculate(posture(700))

        assertEquals(WorkspaceWindowClass.MEDIUM, layout.windowClass)
        assertTrue(layout.showNavigationRail)
        assertTrue(layout.showDetailPane)
        assertEquals(0.42f, layout.paneSplit?.listFraction)
        assertNull(layout.paneSplit?.snapToFoldPositionDp)
    }

    @Test
    fun expandedBelowNineSixtyKeepsMediumFraction() {
        val layout = WorkspaceLayoutPolicy.calculate(posture(900))

        assertEquals(WorkspaceWindowClass.EXPANDED, layout.windowClass)
        assertEquals(0.42f, layout.paneSplit?.listFraction)
    }

    @Test
    fun nineSixtyAndAboveUsesWideFraction() {
        val layout = WorkspaceLayoutPolicy.calculate(posture(960))

        assertEquals(0.38f, layout.paneSplit?.listFraction)
    }

    @Test
    fun extraWideWindowPermitsSupportingPane() {
        val layout = WorkspaceLayoutPolicy.calculate(posture(1_200))

        assertEquals(WorkspaceWindowClass.EXTRA_WIDE, layout.windowClass)
        assertTrue(layout.showSupportingPane)
        assertTrue(layout.useExtendedQuickAdd)
    }

    @Test
    fun verticalSeparatingFoldSnapsSplitToHinge() {
        val layout = WorkspaceLayoutPolicy.calculate(
            posture(840, folds = listOf(verticalFold(positionDp = 420))),
        )

        assertEquals(420, layout.paneSplit?.snapToFoldPositionDp)
        assertEquals(0.5f, layout.paneSplit?.listFraction)
    }

    @Test
    fun compactWindowWithSeparatingFoldStillShowsDetail() {
        val layout = WorkspaceLayoutPolicy.calculate(
            posture(599, folds = listOf(verticalFold(positionDp = 300))),
        )

        assertTrue(layout.showDetailPane)
        assertEquals(300, layout.paneSplit?.snapToFoldPositionDp)
    }

    @Test
    fun trifoldSnapsToTheFoldNearestTheCentre() {
        val layout = WorkspaceLayoutPolicy.calculate(
            posture(
                1_200,
                folds = listOf(
                    verticalFold(positionDp = 400),
                    verticalFold(positionDp = 810),
                ),
            ),
        )

        assertEquals(400, layout.paneSplit?.snapToFoldPositionDp)
    }

    @Test
    fun trifoldCentreTieSnapsToTheLeadingFold() {
        val layout = WorkspaceLayoutPolicy.calculate(
            posture(
                1_200,
                folds = listOf(
                    verticalFold(positionDp = 500),
                    verticalFold(positionDp = 700),
                ),
            ),
        )

        assertEquals(500, layout.paneSplit?.snapToFoldPositionDp)
    }

    @Test
    fun nonSeparatingVerticalFoldDoesNotSnap() {
        val layout = WorkspaceLayoutPolicy.calculate(
            posture(
                840,
                folds = listOf(verticalFold(positionDp = 420, isSeparating = false)),
            ),
        )

        assertNull(layout.paneSplit?.snapToFoldPositionDp)
        assertEquals(0.42f, layout.paneSplit?.listFraction)
    }

    @Test
    fun horizontalSeparatingFoldYieldsExclusionBand() {
        val layout = WorkspaceLayoutPolicy.calculate(
            posture(
                900,
                heightDp = 840,
                folds = listOf(
                    FoldLine(
                        orientation = FoldOrientation.HORIZONTAL,
                        isSeparating = true,
                        positionDp = 420,
                        occludedWidthDp = 20,
                    ),
                ),
            ),
        )

        assertEquals(420..440, layout.hingeExclusionBandDp)
    }

    @Test
    fun foldLinesBeyondFourAreIgnored() {
        val folds = (1..6).map { verticalFold(positionDp = it * 100) }
        val layout = WorkspaceLayoutPolicy.calculate(posture(1_200, folds = folds))

        assertEquals(400, layout.paneSplit?.snapToFoldPositionDp)
    }

    @Test
    fun outOfRangeFoldPositionsAreIgnored() {
        val layout = WorkspaceLayoutPolicy.calculate(
            posture(
                840,
                folds = listOf(
                    verticalFold(positionDp = 0),
                    verticalFold(positionDp = 840),
                ),
            ),
        )

        assertNull(layout.paneSplit?.snapToFoldPositionDp)
    }

    @Test
    fun landscapeFirstNaturalDoesNotChangeWidthClassing() {
        val layout = WorkspaceLayoutPolicy.calculate(posture(900, heightDp = 420))

        assertEquals(WorkspaceWindowClass.EXPANDED, layout.windowClass)
    }

    @Test
    fun splitScreenHalfWidthBehavesAsMedium() {
        val layout = WorkspaceLayoutPolicy.calculate(posture(640, heightDp = 800))

        assertEquals(WorkspaceWindowClass.MEDIUM, layout.windowClass)
        assertEquals(0.42f, layout.paneSplit?.listFraction)
    }

    @Test
    fun splitScreenThirdWidthBehavesAsCompact() {
        val layout = WorkspaceLayoutPolicy.calculate(posture(426, heightDp = 800))

        assertEquals(WorkspaceWindowClass.COMPACT, layout.windowClass)
        assertNull(layout.paneSplit)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.WorkspaceLayoutPolicyTest' --stacktrace
```

Expected: compilation failure — `WindowPosture`, `FoldLine`,
`FoldOrientation`, `PaneSplit`, and the one-argument `calculate` do not
exist.

- [ ] **Step 3: Create the posture model**

`app/src/main/kotlin/app/opentasks/WindowPosture.kt`:

```kotlin
package app.opentasks

enum class FoldOrientation {
    VERTICAL,
    HORIZONTAL,
}

data class FoldLine(
    val orientation: FoldOrientation,
    val isSeparating: Boolean,
    val positionDp: Int,
    val occludedWidthDp: Int,
) {
    init {
        require(occludedWidthDp >= 0) { "Occluded width must not be negative" }
    }
}

data class WindowPosture(
    val widthDp: Int,
    val heightDp: Int,
    val foldLines: List<FoldLine>,
) {
    init {
        require(widthDp > 0) { "Window width must be positive" }
        require(heightDp > 0) { "Window height must be positive" }
    }
}
```

- [ ] **Step 4: Widen the policy**

Replace the body of `WorkspaceLayoutPolicy.kt` (keep
`WorkspaceWindowClass` unchanged):

```kotlin
package app.opentasks

data class PaneSplit(
    val listFraction: Float,
    val snapToFoldPositionDp: Int?,
)

data class WorkspaceLayout(
    val windowClass: WorkspaceWindowClass,
    val showNavigationRail: Boolean,
    val showDetailPane: Boolean,
    val showSupportingPane: Boolean,
    val useExtendedQuickAdd: Boolean,
    val paneSplit: PaneSplit?,
    val hingeExclusionBandDp: IntRange?,
)

object WorkspaceLayoutPolicy {
    private const val MAX_FOLD_LINES = 4
    private const val WIDE_FRACTION_MIN_WIDTH_DP = 960
    private const val MEDIUM_LIST_FRACTION = 0.42f
    private const val WIDE_LIST_FRACTION = 0.38f

    fun calculate(posture: WindowPosture): WorkspaceLayout {
        val widthDp = posture.widthDp
        val windowClass = when {
            widthDp < 600 -> WorkspaceWindowClass.COMPACT
            widthDp < 840 -> WorkspaceWindowClass.MEDIUM
            widthDp < 1_200 -> WorkspaceWindowClass.EXPANDED
            else -> WorkspaceWindowClass.EXTRA_WIDE
        }
        val considered = posture.foldLines.take(MAX_FOLD_LINES)
        val verticalSeparating = considered.filter {
            it.orientation == FoldOrientation.VERTICAL &&
                it.isSeparating &&
                it.positionDp in 1 until widthDp
        }
        val horizontalSeparating = considered.firstOrNull {
            it.orientation == FoldOrientation.HORIZONTAL &&
                it.isSeparating &&
                it.positionDp in 1 until posture.heightDp
        }
        val snapFold = verticalSeparating.minWithOrNull(
            compareBy(
                { kotlin.math.abs(it.positionDp - widthDp / 2) },
                { it.positionDp },
            ),
        )
        val showDetailPane =
            windowClass != WorkspaceWindowClass.COMPACT || snapFold != null
        val paneSplit = if (showDetailPane && (snapFold != null ||
                windowClass != WorkspaceWindowClass.COMPACT)
        ) {
            if (snapFold != null) {
                PaneSplit(
                    listFraction = snapFold.positionDp.toFloat() / widthDp,
                    snapToFoldPositionDp = snapFold.positionDp,
                )
            } else {
                PaneSplit(
                    listFraction = if (widthDp >= WIDE_FRACTION_MIN_WIDTH_DP) {
                        WIDE_LIST_FRACTION
                    } else {
                        MEDIUM_LIST_FRACTION
                    },
                    snapToFoldPositionDp = null,
                )
            }
        } else {
            null
        }
        return WorkspaceLayout(
            windowClass = windowClass,
            showNavigationRail = windowClass != WorkspaceWindowClass.COMPACT,
            showDetailPane = showDetailPane,
            showSupportingPane = windowClass == WorkspaceWindowClass.EXTRA_WIDE,
            useExtendedQuickAdd =
                windowClass == WorkspaceWindowClass.EXPANDED ||
                    windowClass == WorkspaceWindowClass.EXTRA_WIDE,
            paneSplit = paneSplit,
            hingeExclusionBandDp = horizontalSeparating?.let {
                it.positionDp..(it.positionDp + it.occludedWidthDp)
            },
        )
    }
}
```

- [ ] **Step 5: Bridge the existing call site**

In `OpenTasksApp.kt` replace lines 365–368 with a behaviour-preserving
bridge (real fold mapping arrives in Task 2):

```kotlin
val layout = WorkspaceLayoutPolicy.calculate(
    WindowPosture(
        widthDp = maxWidth.value.toInt().coerceAtLeast(1),
        heightDp = maxHeight.value.toInt().coerceAtLeast(1),
        foldLines = if (hasSeparatingFold) {
            listOf(
                FoldLine(
                    orientation = FoldOrientation.VERTICAL,
                    isSeparating = true,
                    positionDp = (maxWidth.value.toInt() / 2).coerceAtLeast(1),
                    occludedWidthDp = 0,
                ),
            )
        } else {
            emptyList()
        },
    ),
)
```

- [ ] **Step 6: Run the policy tests to verify they pass**

Run the Step 2 command. Expected: all tests pass.

- [ ] **Step 7: Update DESIGN.md and run the repository gate**

Add the posture/ratio contract to `DESIGN.md`'s layout section: the
`WindowPosture` input, the 42/58 and 38/62 One UI fractions, hinge
snap with nearest-to-centre/leading tie-break, the four-fold cap, the
horizontal-fold exclusion band, and the no-`HALF_OPENED`-assumption
rule. Then run:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/app/opentasks/WindowPosture.kt \
  app/src/main/kotlin/app/opentasks/WorkspaceLayoutPolicy.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt \
  app/src/test/kotlin/app/opentasks/WorkspaceLayoutPolicyTest.kt \
  DESIGN.md
git commit -m "feat: widen layout policy to window postures"
```

---

### Task 2: Map WindowLayoutInfo to WindowPosture

**Files:**

- Create: `app/src/main/kotlin/app/opentasks/WindowPostureMapper.kt`
- Create: `app/src/test/kotlin/app/opentasks/WindowPostureMapperTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
  (fold-state collection at lines 193 and 292-300; bridge from Task 1
  Step 5)

**Interfaces:**

- Consumes: `WindowPosture`, `FoldLine`, `FoldOrientation` from Task 1.
- Produces:

```kotlin
data class RawFold(
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val isSeparating: Boolean,
)

object WindowPostureMapper {
    fun map(
        widthDp: Int,
        heightDp: Int,
        density: Float,
        folds: List<RawFold>,
    ): WindowPosture
}
```

`RawFold` is deliberately free of Android types so the mapper is pure
JVM. The one-expression extraction from `FoldingFeature` lives in
`OpenTasksApp` and is covered by the Task 4 device test.

- [ ] **Step 1: Write the failing mapper tests**

`app/src/test/kotlin/app/opentasks/WindowPostureMapperTest.kt`:

```kotlin
package app.opentasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowPostureMapperTest {
    @Test
    fun tallFoldBecomesVerticalFoldLineInDp() {
        val posture = WindowPostureMapper.map(
            widthDp = 840,
            heightDp = 900,
            density = 2.0f,
            folds = listOf(
                RawFold(
                    leftPx = 840,
                    topPx = 0,
                    widthPx = 48,
                    heightPx = 1_800,
                    isSeparating = true,
                ),
            ),
        )

        val fold = posture.foldLines.single()
        assertEquals(FoldOrientation.VERTICAL, fold.orientation)
        assertEquals(420, fold.positionDp)
        assertEquals(24, fold.occludedWidthDp)
        assertTrue(fold.isSeparating)
    }

    @Test
    fun wideFoldBecomesHorizontalFoldLineInDp() {
        val posture = WindowPostureMapper.map(
            widthDp = 900,
            heightDp = 840,
            density = 2.0f,
            folds = listOf(
                RawFold(
                    leftPx = 0,
                    topPx = 840,
                    widthPx = 1_800,
                    heightPx = 40,
                    isSeparating = true,
                ),
            ),
        )

        val fold = posture.foldLines.single()
        assertEquals(FoldOrientation.HORIZONTAL, fold.orientation)
        assertEquals(420, fold.positionDp)
        assertEquals(20, fold.occludedWidthDp)
    }

    @Test
    fun zeroWidthHingeIsPreservedAsZeroOcclusion() {
        val posture = WindowPostureMapper.map(
            widthDp = 840,
            heightDp = 900,
            density = 2.0f,
            folds = listOf(
                RawFold(
                    leftPx = 840,
                    topPx = 0,
                    widthPx = 0,
                    heightPx = 1_800,
                    isSeparating = true,
                ),
            ),
        )

        assertEquals(0, posture.foldLines.single().occludedWidthDp)
        assertEquals(420, posture.foldLines.single().positionDp)
    }

    @Test
    fun windowSizeIsCarriedThrough() {
        val posture = WindowPostureMapper.map(
            widthDp = 330,
            heightDp = 700,
            density = 3.0f,
            folds = emptyList(),
        )

        assertEquals(330, posture.widthDp)
        assertEquals(700, posture.heightDp)
        assertTrue(posture.foldLines.isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify compilation failure**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.WindowPostureMapperTest' --stacktrace
```

Expected: compilation failure — `WindowPostureMapper` and `RawFold` do
not exist.

- [ ] **Step 3: Implement the mapper**

`app/src/main/kotlin/app/opentasks/WindowPostureMapper.kt`:

```kotlin
package app.opentasks

import kotlin.math.roundToInt

data class RawFold(
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val isSeparating: Boolean,
)

object WindowPostureMapper {
    fun map(
        widthDp: Int,
        heightDp: Int,
        density: Float,
        folds: List<RawFold>,
    ): WindowPosture {
        require(density > 0f) { "Density must be positive" }
        val foldLines = folds.map { fold ->
            val vertical = fold.heightPx >= fold.widthPx
            if (vertical) {
                FoldLine(
                    orientation = FoldOrientation.VERTICAL,
                    isSeparating = fold.isSeparating,
                    positionDp = (fold.leftPx / density).roundToInt(),
                    occludedWidthDp = (fold.widthPx / density).roundToInt(),
                )
            } else {
                FoldLine(
                    orientation = FoldOrientation.HORIZONTAL,
                    isSeparating = fold.isSeparating,
                    positionDp = (fold.topPx / density).roundToInt(),
                    occludedWidthDp = (fold.heightPx / density).roundToInt(),
                )
            }
        }
        return WindowPosture(
            widthDp = widthDp,
            heightDp = heightDp,
            foldLines = foldLines,
        )
    }
}
```

- [ ] **Step 4: Run the mapper tests to verify they pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Wire real fold data through OpenTasksApp**

In `OpenTasksApp.kt`:

1. Replace the boolean state at line 193 with:

```kotlin
var rawFolds by remember { mutableStateOf(emptyList<RawFold>()) }
```

2. Replace the collector body at lines 292–300 with:

```kotlin
LaunchedEffect(activity) {
    WindowInfoTracker.getOrCreate(activity)
        .windowLayoutInfo(activity)
        .collect { layout ->
            rawFolds = layout.displayFeatures
                .filterIsInstance<FoldingFeature>()
                .map { feature ->
                    RawFold(
                        leftPx = feature.bounds.left,
                        topPx = feature.bounds.top,
                        widthPx = feature.bounds.width(),
                        heightPx = feature.bounds.height(),
                        isSeparating = feature.isSeparating,
                    )
                }
        }
}
```

3. Replace the Task 1 bridge with the mapper (inside
`BoxWithConstraints`, where `LocalDensity` is available):

```kotlin
val density = LocalDensity.current.density
val layout = WorkspaceLayoutPolicy.calculate(
    WindowPostureMapper.map(
        widthDp = maxWidth.value.toInt().coerceAtLeast(1),
        heightDp = maxHeight.value.toInt().coerceAtLeast(1),
        density = density,
        folds = rawFolds,
    ),
)
```

Add the `androidx.compose.ui.platform.LocalDensity` import if absent.

- [ ] **Step 6: Run the app unit suite and repository gate**

```bash
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
```

Expected: BUILD SUCCESSFUL, no test failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/app/opentasks/WindowPostureMapper.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt \
  app/src/test/kotlin/app/opentasks/WindowPostureMapperTest.kt
git commit -m "feat: map window layout folds to postures"
```

---

### Task 3: Apply Pane Fractions and Hinge Snap to Workbenches

**Files:**

- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
  (signature at line 162; list-pane modifier near line 278)
- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`
  (signature at line 116; list-pane modifier at line 182)
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
  (pass fractions; compute content-relative snap)
- Modify: `app/src/test/kotlin/app/opentasks/WorkspaceLayoutPolicyTest.kt`
  (content-fraction helper tests)
- Test: `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskPaneFractionInstrumentedTest.kt`
  (create)
- Test: `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/ProjectPaneFractionInstrumentedTest.kt`
  (create)

**Interfaces:**

- Consumes: `WorkspaceLayout.paneSplit` from Task 1.
- Produces: both screens gain one parameter, default preserving the
  One UI medium ratio so existing fixture tests keep compiling:

```kotlin
listPaneFraction: Float = 0.42f,
```

The hinge position is window-relative; the list/detail row starts after
the navigation rail and content padding, so `:app` converts the snap to
a content-relative fraction with a pure helper added to
`WorkspaceLayoutPolicy`:

```kotlin
fun contentListFraction(
    split: PaneSplit,
    contentStartDp: Int,
    contentWidthDp: Int,
): Float
```

- [ ] **Step 1: Write the failing content-fraction helper tests**

Append to `WorkspaceLayoutPolicyTest.kt`:

```kotlin
    @Test
    fun contentFractionConvertsWindowSnapToContentCoordinates() {
        val fraction = WorkspaceLayoutPolicy.contentListFraction(
            split = PaneSplit(listFraction = 0.5f, snapToFoldPositionDp = 420),
            contentStartDp = 96,
            contentWidthDp = 744,
        )

        assertEquals(0.4355f, fraction, 0.001f)
    }

    @Test
    fun contentFractionWithoutSnapKeepsRatio() {
        val fraction = WorkspaceLayoutPolicy.contentListFraction(
            split = PaneSplit(listFraction = 0.42f, snapToFoldPositionDp = null),
            contentStartDp = 96,
            contentWidthDp = 744,
        )

        assertEquals(0.42f, fraction, 0.0001f)
    }

    @Test
    fun contentFractionIsClampedToUsableRange() {
        val fraction = WorkspaceLayoutPolicy.contentListFraction(
            split = PaneSplit(listFraction = 0.5f, snapToFoldPositionDp = 60),
            contentStartDp = 96,
            contentWidthDp = 744,
        )

        assertEquals(0.2f, fraction, 0.0001f)
    }
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.WorkspaceLayoutPolicyTest' --stacktrace
```

Expected: compilation failure — `contentListFraction` does not exist.

- [ ] **Step 3: Implement the helper**

Add to `WorkspaceLayoutPolicy`:

```kotlin
    fun contentListFraction(
        split: PaneSplit,
        contentStartDp: Int,
        contentWidthDp: Int,
    ): Float {
        require(contentWidthDp > 0) { "Content width must be positive" }
        val snap = split.snapToFoldPositionDp ?: return split.listFraction
        val relative = (snap - contentStartDp).toFloat() / contentWidthDp
        return relative.coerceIn(0.2f, 0.8f)
    }
```

Run the Step 2 command. Expected: PASS.

- [ ] **Step 4: Thread the fraction through the feature screens**

In `TasksScreen.kt` add `listPaneFraction: Float = 0.42f` after
`showDetailPane: Boolean` (line 169). Where the two panes are laid out
(list-pane modifier near line 278 and the `.weight(1f)` detail pane at
line 296), replace the fixed list sizing with weights:

```kotlin
// list pane, inside the two-pane Row:
modifier = Modifier.weight(listPaneFraction)
// detail pane:
modifier = Modifier.weight(1f - listPaneFraction)
```

In `ProjectsScreen.kt` add the same parameter after
`showDetailPane: Boolean` (line 122) and replace
`Modifier.width(390.dp)` (line 182) and the `.weight(1f)` detail
(line 196) the same way. Preserve every other modifier already chained
on those nodes. Add `testTag("listPane")` on the list-pane node and
`testTag("detailPane")` on the detail-pane node in both screens (import
`androidx.compose.ui.platform.testTag`).

- [ ] **Step 5: Pass the computed fraction from the app layer**

In `OpenTasksApp.kt`, inside the content `Row` where the rail width is
known, compute the fraction once and pass it to both screens
(`TasksScreen` call at line 501, `ProjectsScreen` call at line 593):

```kotlin
val listPaneFraction = layout.paneSplit?.let { split ->
    WorkspaceLayoutPolicy.contentListFraction(
        split = split,
        contentStartDp = contentStartDp,
        contentWidthDp = contentWidthDp,
    )
} ?: 0.42f
```

where `contentStartDp` is the navigation rail width (80 when
`layout.showNavigationRail`, else 0) plus the start content padding in
dp, and `contentWidthDp` is the `BoxWithConstraints` width minus
`contentStartDp`, both computed as `Int` dp values at the call site.
Pass `listPaneFraction = listPaneFraction` to both screens.

- [ ] **Step 6: Write the failing feature device tests**

`TaskPaneFractionInstrumentedTest.kt` (Projects variant mirrors it with
`ProjectsScreen` and `OpenTasksFixtures` project data):

```kotlin
package app.opentasks.feature.tasks

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import org.junit.Rule
import org.junit.Test

class TaskPaneFractionInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listPaneOccupiesProvidedFraction() {
        composeRule.setContent {
            OpenTasksTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier
                        .androidx.compose.foundation.layout.width(1_000.dp)
                        .testTag("paneHost"),
                ) {
                    TasksScreen(
                        tasks = OpenTasksFixtures.tasks,
                        reminders = emptyList(),
                        projectNames = emptyMap(),
                        activeProjectIds = emptySet(),
                        workflowStatuses = OpenTasksFixtures.workflowStatuses,
                        tags = emptyList(),
                        milestones = emptyList(),
                        selectedTaskId = OpenTasksFixtures.tasks.first().id,
                        showDetailPane = true,
                        listPaneFraction = 0.38f,
                        onSelectTask = {},
                        onCloseDetail = {},
                        onCompleteTask = {},
                        onChangeTaskStatus = { _, _ -> },
                        onDeleteTask = {},
                        activeTimerTaskId = null,
                        onToggleTimer = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("listPane", useUnmergedTree = true)
            .assertWidthIsAtLeast(370.dp)
    }
}
```

Adjust the `TasksScreen` parameter list to the real signature at
implementation time — the fixture call must compile against the actual
screen; the assertion contract is: host width 1000 dp, fraction 0.38f,
list pane asserted at 370 dp minimum and detail pane present. Add a
second test asserting fraction 0.5f yields a list pane of at least
490 dp (hinge-snap parity).

- [ ] **Step 7: Run the feature device suites on the sole disposable**

Follow the standing device procedure (audit attached devices by qemu
flags; start `Pixel_10_Pro_Fold` with
`-read-only -no-snapshot-save -no-snapshot-load -no-window`; pin
`STAGE3_ADB_SERIAL` and `ANDROID_SERIAL`; verify SDK 37 and font scale
1.0), then:

```bash
./gradlew :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest --stacktrace
```

Expected: new fraction tests fail before Step 4/5 are complete and pass
after; all existing feature tests stay green. Kill the disposable and
verify ADB is empty afterwards.

- [ ] **Step 8: Run the repository gate and commit**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
git add feature/tasks/src feature/projects/src \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt \
  app/src/main/kotlin/app/opentasks/WorkspaceLayoutPolicy.kt \
  app/src/test/kotlin/app/opentasks/WorkspaceLayoutPolicyTest.kt
git commit -m "feat: align panes with fold-aware fractions"
```

---

### Task 4: Hinge Exclusion Band and Fold Continuity Acceptance

**Files:**

- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
  (editor sheet host: new `hingeExclusionBandDp: IntRange? = null`
  parameter; sheet content top padding clears the band)
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
  (pass `layout.hingeExclusionBandDp` to `TasksScreen`)
- Test: `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskEditorHingeInstrumentedTest.kt`
  (create)
- Create: `app/src/androidTest/kotlin/app/opentasks/FoldContinuityInstrumentedTest.kt`

**Interfaces:**

- Consumes: `WorkspaceLayout.hingeExclusionBandDp` from Task 1.
- Produces: `TasksScreen(hingeExclusionBandDp: IntRange? = null)`.

- [ ] **Step 1: Write the failing hinge test**

`TaskEditorHingeInstrumentedTest.kt`: compose `TasksScreen` inside
`OpenTasksTheme` at 900×840 dp with `showDetailPane = true`, a selected
fixture task, and `hingeExclusionBandDp = 400..440`. Open the editor
sheet via the existing edit affordance, then assert the sheet's first
interactive control node (`onNodeWithTag("editorSheetContent")`, add the
tag in Step 2) has a top position of at least 440 dp
(`getUnclippedBoundsInRoot().top >= 440.dp`). Second test: with
`hingeExclusionBandDp = null` the same node's top is unconstrained
(strictly less than 440 dp at this size). Use
`mainClock.autoAdvance = false` and advance past the sheet animation as
the existing feature tests do.

- [ ] **Step 2: Implement the band padding**

In `TasksScreen.kt` add the parameter after `listPaneFraction`. In the
editor sheet host apply:

```kotlin
val sheetTopPaddingDp = hingeExclusionBandDp?.last ?: 0
```

and pad the sheet content column with
`Modifier.padding(top = sheetTopPaddingDp.dp).testTag("editorSheetContent")`
when the band is non-null, preserving the existing padding chain.
In `OpenTasksApp.kt` pass
`hingeExclusionBandDp = layout.hingeExclusionBandDp` at the
`TasksScreen` call site.

- [ ] **Step 3: Write the fold continuity test**

`FoldContinuityInstrumentedTest.kt` in `:app` follows the
`ProcessRestorationInstrumentedTest` pattern: launch the activity, type
a bounded draft into the task editor, record the list scroll position,
then switch the emulator device state and assert survival:

```kotlin
@Test
fun draftAndSelectionSurviveFoldTransition() {
    // 1. open a fixture task, type "Fold continuity draft" into the title
    // 2. read the device-state list once via
    //    uiAutomation.executeShellCommand("cmd device_state print-states")
    //    and pick the closed and opened state ids from its output
    // 3. uiAutomation.executeShellCommand("cmd device_state state <closed>")
    // 4. await idle; 5. switch to <opened>; 6. await idle
    // 7. assert the editor still shows "Fold continuity draft", the
    //    selected task is unchanged, and the list scroll survives
    // 8. finally: "cmd device_state state reset"
}
```

The state ids must be parsed from `print-states` output, never
hard-coded — the AVD's ids are emulator-image specific. If
`cmd device_state` reports no fold states (non-foldable target), the
test must `assumeTrue(false)` (skip), not fail.

- [ ] **Step 4: Run both device suites on the sole disposable**

Standing device procedure, then:

```bash
./gradlew :feature:tasks:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest --stacktrace
```

Note: `:app:connectedDebugAndroidTest` uninstalls `app.opentasks` —
sole read-only disposable only, per the standing procedure. Expected:
hinge tests and continuity test pass; existing suites stay green.

- [ ] **Step 5: Run the repository gate and commit**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
git add feature/tasks/src app/src
git commit -m "feat: keep editors clear of tabletop hinges"
```

---

### Task 5: Fold 8 Acceptance Environment, Evidence, and Handoff

**Files:**

- Create: `docs/qualification/fold8-adaptive-acceptance.md`
- Modify: `HANDOFF.md` (backlog + slice completion entry)

**Interfaces:**

- Consumes: everything shipped in Tasks 1–4.
- Produces: recorded acceptance evidence; no code.

- [ ] **Step 1: Create the two AVD hardware profiles**

Record in the qualification doc and execute locally. Fold 8 main
display (7.6", 4:3, ~2160×1856 px, 420 dpi → ~823×707 dp), cover
display (5.5", 10:16, ~1080×1728 px, 420 dpi), Ultra main (8", 4:3).
Create two AVDs from the API 37 image (device profiles added via
Android Studio's Device Manager clone-and-edit, or
`~/.android/devices.xml`), named `Fold8_Acceptance` and
`Fold8_Ultra_Acceptance`. Start each only as a disposable
(`-read-only -no-snapshot-save -no-snapshot-load`). Document the exact
resolutions/densities used in the doc.

- [ ] **Step 2: Run the visual acceptance matrix**

On each profile, light theme, at 100% and 200% font scale: Home, Tasks
list/detail (panes meet the hinge on the main display — 50/50), task
editor, Projects workbench, Schedule week view, More. On the cover
profile: compact bottom navigation, single pane, quick add, editor
usable at ~330 dp width. Record pass/fail per screen in the
qualification doc with the emulator profile, density, and font scale
for each row. Any failure is fixed before this task completes and noted
with its commit.

- [ ] **Step 3: Samsung Remote Test Lab session (External)**

External step — requires the user's Samsung developer account. On a
real Galaxy Z Fold 8 in RTL: install the debug APK, then check and
record in the qualification doc: cover↔main app continuity with a
draft, One UI taskbar overlap with the bottom bar, Samsung keyboard in
the editor at both displays, split-screen ½ and ⅓ widths, pop-up view,
and pane alignment at the hinge. If no Samsung account is available,
record the step as External-blocked in the doc and `HANDOFF.md`; the
slice may close with emulator evidence plus this recorded gap.

- [ ] **Step 4: Run the full gates**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
./gradlew :app:assembleRelease --stacktrace
git diff --check
```

Expected: all green (release separately, per the standing rule).

- [ ] **Step 5: Update HANDOFF.md and commit**

Record the slice, its commits, the acceptance evidence location, and
any External-blocked RTL step in `HANDOFF.md`'s backlog/status.

```bash
git add docs/qualification/fold8-adaptive-acceptance.md HANDOFF.md
git commit -m "docs: record fold 8 adaptive acceptance"
```

---

## Self-Review Notes

- Spec coverage: contract (Task 1), posture mapping (Task 2), One UI
  fractions + hinge snap (Task 3), exclusion band + continuity
  (Task 4), cover/multi-window widths (Tasks 1 JVM + 5 visual),
  verification matrix and RTL (Task 5), DESIGN/HANDOFF updates
  (Tasks 1 and 5). Non-goals honoured: no camera, no Flex layouts, no
  Samsung SDK, no configChanges opt-out, no orientation lock.
- The Task 3 fixture-call parameter list is explicitly marked "adjust
  to the real signature"; assertion contracts are pinned instead.
- Type names used across tasks were cross-checked: `WindowPosture`,
  `FoldLine`, `FoldOrientation`, `PaneSplit`, `RawFold`,
  `contentListFraction`, `listPaneFraction`, `hingeExclusionBandDp`.
