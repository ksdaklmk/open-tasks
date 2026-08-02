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

    @Test
    fun contentFractionSubtractsTrailingInsetBeforeSnapping() {
        val fraction = WorkspaceLayoutPolicy.contentListFraction(
            split = PaneSplit(listFraction = 0.5f, snapToFoldPositionDp = 420),
            windowWidthDp = 840,
            contentStartDp = 96,
            contentEndDp = 24,
        )

        assertEquals(0.45f, fraction, 0.001f)
    }

    @Test
    fun contentFractionWithoutSnapKeepsRatio() {
        val fraction = WorkspaceLayoutPolicy.contentListFraction(
            split = PaneSplit(listFraction = 0.42f, snapToFoldPositionDp = null),
            windowWidthDp = 840,
            contentStartDp = 96,
            contentEndDp = 0,
        )

        assertEquals(0.42f, fraction, 0.0001f)
    }

    @Test
    fun contentFractionIsClampedToUsableRange() {
        val fraction = WorkspaceLayoutPolicy.contentListFraction(
            split = PaneSplit(listFraction = 0.5f, snapToFoldPositionDp = 60),
            windowWidthDp = 840,
            contentStartDp = 96,
            contentEndDp = 0,
        )

        assertEquals(0.2f, fraction, 0.0001f)
    }
}
