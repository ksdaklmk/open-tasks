package app.opentasks

import app.opentasks.backup.RecoveryPresentation
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.data.VaultSlot
import app.opentasks.feature.more.RecoveryShellMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPresentationTest {
    @Test
    fun onlyAnIdleOrdinaryNoVaultSurfaceCreatesTheInitialVault() {
        assertTrue(
            shouldCreateInitialVault(
                VaultRuntimeState.NoVault,
                RecoveryPresentation.NoVault,
                activeReplacement = false,
            ),
        )
        assertFalse(
            shouldCreateInitialVault(
                VaultRuntimeState.NoVault,
                RecoveryPresentation.Discovering,
                activeReplacement = false,
            ),
        )
        assertFalse(
            shouldCreateInitialVault(
                VaultRuntimeState.NoVault,
                RecoveryPresentation.NoVault,
                activeReplacement = true,
            ),
        )
        assertFalse(
            shouldCreateInitialVault(
                VaultRuntimeState.Unreadable(VaultSlot.LEGACY),
                RecoveryPresentation.NoVault,
                activeReplacement = false,
            ),
        )
        assertFalse(
            shouldCreateInitialVault(
                VaultRuntimeState.Recovering("operation"),
                RecoveryPresentation.NoVault,
                activeReplacement = false,
            ),
        )
    }

    @Test
    fun onlyOrdinaryNoVaultRecoveryWithholdsFullyDrawn() {
        assertFalse(shouldReportRecoveryFullyDrawn(VaultRuntimeState.NoVault))
        assertTrue(
            shouldReportRecoveryFullyDrawn(VaultRuntimeState.Unreadable(VaultSlot.LEGACY)),
        )
        assertTrue(
            shouldReportRecoveryFullyDrawn(VaultRuntimeState.Recovering("operation")),
        )
    }

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
    fun activeReplacementRetainsTheRecoveryShell() {
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
