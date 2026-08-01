package app.opentasks

import app.opentasks.backup.RecoveryPresentation
import app.opentasks.feature.more.RecoveryShellMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPresentationTest {
    @Test
    fun recoveringRuntimeDoesNotHideTakeoverConfirmation() {
        assertEquals(
            RecoveryShellMode.TakeoverConfirmation,
            recoveryShellMode(
                runtimeRecovering = true,
                presentation = RecoveryPresentation.TakeoverConfirmation("operation", 7),
                activeReplacement = false,
            ),
        )
    }

    @Test
    fun noVaultAndActiveReplacementUseDistinctRecoveryRoutes() {
        assertEquals(
            RecoveryShellMode.NoVault,
            recoveryShellMode(false, RecoveryPresentation.NoVault, activeReplacement = false),
        )
        assertEquals(
            RecoveryShellMode.ActiveReplacement,
            recoveryShellMode(false, RecoveryPresentation.NoVault, activeReplacement = true),
        )
    }

    @Test
    fun navigationLabelsRemainVisibleAtSupportedStandardScales() {
        assertTrue(shouldShowNavigationLabels(fontScale = 1f))
        assertTrue(shouldShowNavigationLabels(fontScale = 1.3f))
    }

    @Test
    fun navigationLabelsCollapseBeforeTheyWrapAtLargeTextScales() {
        assertFalse(shouldShowNavigationLabels(fontScale = 1.5f))
        assertFalse(shouldShowNavigationLabels(fontScale = 2f))
    }
}
