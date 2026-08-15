package app.opentasks.lock

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.HideWindowsRule
import app.opentasks.core.designsystem.OpenTasksTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Proves the mechanism `MainActivity` relies on for the app lock overlay:
 * [AppLockScreen] is composed *in place of* workspace content, never
 * alongside it, and that holds across a recreation.
 *
 * This exercises the same boolean-gate shape `MainActivity` itself uses
 * (`if (locked) AppLockScreen(...) else <workspace>`), isolated the same
 * way `ProcessRestorationInstrumentedTest` isolates other pieces of that
 * activity's composition, rather than standing up a live `MainActivity`
 * and vault runtime: the guarantee under test -- no workspace data is ever
 * composed behind the overlay, before or after recreation -- does not
 * depend on a real vault slot existing.
 *
 * `locked` is a plain captured `var`, not Compose-saved state: production
 * `AppLockController.locked` is a `@Singleton`-scoped `StateFlow` that
 * outlives `MainActivity` recreation on its own, never through Compose's
 * saved-instance-state machinery, so this stand-in survives the same way.
 *
 * Compile-verified only in this task; runs at Task 13.
 */
@RunWith(AndroidJUnit4::class)
class AppLockOverlayInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun overlayReplacesWorkspaceContentAndSurvivesRecreation() {
        var locked = true
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            OpenTasksTheme {
                if (locked) {
                    AppLockScreen(onUnlockClick = { locked = false })
                } else {
                    Text("Workspace content", modifier = Modifier.testTag("workspace-content"))
                }
            }
        }

        composeRule.onNodeWithTag("app-lock-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace-content").assertDoesNotExist()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("app-lock-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace-content").assertDoesNotExist()
    }
}
