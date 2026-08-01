package app.opentasks

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.backup.RecoveryCandidateSummary
import app.opentasks.backup.RecoveryPresentation
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.domain.RecoverySource
import app.opentasks.feature.more.RecoveryShellCandidate
import app.opentasks.feature.more.RecoveryShellScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProcessRestorationInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recoveryRecreationKeepsNoPrivateStateAndReturnsToTruthfulNoVaultRoute() {
        val restorationTester = StateRestorationTester(composeRule)
        val presentation = AtomicReference<RecoveryPresentation>(
            RecoveryPresentation.Candidates(
                listOf(
                    RecoveryCandidateSummary("process-private", RecoverySource.GOOGLE_DRIVE),
                ),
            ),
        )
        restorationTester.setContent {
            val current = presentation.get()
            val candidates = (current as? RecoveryPresentation.Candidates)
                ?.values
                ?.map { RecoveryShellCandidate(it.handle, drive = true) }
                .orEmpty()
            OpenTasksTheme {
                RecoveryShellScreen(
                    mode = recoveryShellMode(
                        runtimeRecovering = false,
                        presentation = current,
                        activeReplacement = false,
                    ),
                    candidates = candidates,
                )
            }
        }

        composeRule.onNodeWithTag("recovery-passphrase")
            .performTextReplacement("process private passphrase")

        // Activity recreation keeps the live recovery route, but the secret is not saveable.
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("recovery-passphrase").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString(""),
            ),
        )

        // Process restoration rebuilds from the truthful runtime source, not private candidates.
        presentation.set(RecoveryPresentation.NoVault)
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("recovery-passphrase").assertDoesNotExist()
        composeRule.onNodeWithText("Restore Google Drive backup").assertDoesNotExist()
        composeRule.onNodeWithTag("recovery-drive").assertIsDisplayed()
        composeRule.onNodeWithTag("recovery-portable").assertIsDisplayed()
        composeRule.onNodeWithText("Start without restoring").assertIsDisplayed()
    }

    @Test
    fun workspaceRouteRestoresAfterSavedInstanceStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            val backStack = rememberWorkspaceBackStack()
            OpenTasksTheme {
                Column {
                    Text(
                        when (backStack.lastOrNull()) {
                            TasksRoute -> "Current route: Tasks"
                            else -> "Current route: Home"
                        },
                    )
                    Button(
                        onClick = {
                            backStack.clear()
                            backStack.add(TasksRoute)
                        },
                    ) {
                        Text("Open Tasks")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Open Tasks").performClick()
        composeRule.onNodeWithText("Current route: Tasks").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Current route: Tasks").assertIsDisplayed()
    }

    @Test
    fun quickAddDraftRestoresAfterSavedInstanceStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            OpenTasksTheme {
                QuickAddSheet(onDismiss = {}, onAdd = {})
            }
        }

        composeRule.onNodeWithTag("quick-add-title")
            .performTextReplacement("Restored quick-add draft")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("quick-add-title")
            .assertTextContains("Restored quick-add draft", substring = true)
    }

    @Test
    fun searchQueryRestoresAndReissuesTheQueryAfterSavedInstanceStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        val latestQuery = AtomicReference("")
        restorationTester.setContent {
            OpenTasksTheme {
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = latestQuery::set,
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                )
            }
        }

        composeRule.onNodeWithTag("workspace-search-query")
            .performTextReplacement("restored search")

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitUntil(timeoutMillis = 2_000) {
            latestQuery.get() == "restored search"
        }

        composeRule.onNodeWithTag("workspace-search-query")
            .assertTextContains("restored search", substring = true)
    }
}
