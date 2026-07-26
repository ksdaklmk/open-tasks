package app.opentasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLayoutPolicyTest {
    @Test
    fun compactWindowUsesBottomNavigationAndOnePane() {
        val layout = WorkspaceLayoutPolicy.calculate(412, hasSeparatingFold = false)

        assertEquals(WorkspaceWindowClass.COMPACT, layout.windowClass)
        assertFalse(layout.showNavigationRail)
        assertFalse(layout.showDetailPane)
        assertFalse(layout.useExtendedQuickAdd)
    }

    @Test
    fun mediumWindowUsesRailAndListDetail() {
        val layout = WorkspaceLayoutPolicy.calculate(700, hasSeparatingFold = false)

        assertEquals(WorkspaceWindowClass.MEDIUM, layout.windowClass)
        assertTrue(layout.showNavigationRail)
        assertTrue(layout.showDetailPane)
    }

    @Test
    fun separatingFoldPreservesListDetailContinuity() {
        val layout = WorkspaceLayoutPolicy.calculate(599, hasSeparatingFold = true)

        assertTrue(layout.showDetailPane)
    }

    @Test
    fun extraWideWindowPermitsSupportingPane() {
        val layout = WorkspaceLayoutPolicy.calculate(1_200, hasSeparatingFold = false)

        assertEquals(WorkspaceWindowClass.EXTRA_WIDE, layout.windowClass)
        assertTrue(layout.showSupportingPane)
        assertTrue(layout.useExtendedQuickAdd)
    }
}
