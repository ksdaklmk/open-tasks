package app.opentasks

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.backup.AndroidBackupFiles
import app.opentasks.core.designsystem.OpenTasksTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProcessRestorationInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

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

@RunWith(AndroidJUnit4::class)
class MainActivityRecoveryRestorationInstrumentedTest {
    private val recoveryInbox by lazy {
        AndroidBackupFiles(InstrumentationRegistry.getInstrumentation().targetContext).recoveryInbox
    }
    private val recoveryFixtureRule = object : ExternalResource() {
        override fun before() {
            check(!recoveryInbox.exists()) { "The disposable recovery inbox is not empty" }
            check(
                recoveryInbox.parentFile?.mkdirs() != false ||
                    recoveryInbox.parentFile?.isDirectory == true,
            )
            recoveryInbox.writeBytes(byteArrayOf(0))
        }

        override fun after() {
            check(!recoveryInbox.exists() || recoveryInbox.delete()) {
                "The disposable recovery fixture was not deleted"
            }
        }
    }
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(recoveryFixtureRule).around(composeRule)

    @Test
    fun productionRecoveryRouteClearsPassphraseAfterActivityRecreation() {
        composeRule.onNodeWithTag("recovery-shell").assertIsDisplayed()
        composeRule.onNodeWithTag("recovery-portable").performClick()
        val passphrase = composeRule.onNodeWithTag("recovery-passphrase")
            .assertIsDisplayed()
        passphrase.performTextReplacement("process private passphrase")
        passphrase.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.InputText,
                AnnotatedString("process private passphrase"),
            ),
        )

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("recovery-passphrase").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.InputText,
                AnnotatedString(""),
            ),
        )
    }
}
