package app.opentasks.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanningDragTest {
    @Test
    fun movedStateAccumulatesRootPositionWithoutLayoutDirectionMath() {
        val sourceBounds = Rect(10f, 20f, 110f, 70f)

        val moved = RootDragState(
            payload = "task",
            sourceBounds = sourceBounds,
            startInRoot = Offset(40f, 30f),
        ).movedBy(Offset(5f, -2f)).movedBy(Offset(-1f, 8f))

        assertEquals(sourceBounds, moved.sourceBounds)
        assertEquals(Offset(4f, 6f), moved.accumulatedOffset)
        assertEquals(Offset(44f, 36f), moved.positionInRoot)
    }

    @Test
    fun targetHitTestUsesIterationOrderEligibilityAndExactBounds() {
        val targets = listOf("ineligible", "first", "second")
        val bounds = mapOf(
            "ineligible" to Rect(0f, 0f, 10f, 10f),
            "first" to Rect(5f, 5f, 15f, 15f),
            "second" to Rect(5f, 5f, 15f, 15f),
        )

        assertEquals(
            "first",
            dragTargetAt(
                positionInRoot = Offset(8f, 8f),
                targets = targets,
                bounds = bounds,
                eligible = { it != "ineligible" },
            ),
        )
        assertNull(
            dragTargetAt(
                positionInRoot = Offset(16f, 16f),
                targets = targets,
                bounds = bounds,
            ),
        )
    }
}
