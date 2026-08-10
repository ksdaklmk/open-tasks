package app.opentasks

import android.content.Context
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
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.SavedView
import app.opentasks.core.model.SavedViewId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TaskSortKey
import app.opentasks.core.model.ZonedMoment
import app.opentasks.lock.AppLockSettings
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
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
                QuickAddSheet(onDismiss = {}, onAdd = { _, _ -> })
            }
        }

        composeRule.onNodeWithTag("quick-add-title")
            .performTextReplacement("Restored quick-add draft")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("quick-add-title")
            .assertTextContains("Restored quick-add draft", substring = true)
    }

    @Test
    fun quickAddAppliedDueRestoresAfterSavedInstanceStateRecreation() {
        val zone = ZoneId.systemDefault()
        val expectedDue = ZonedMoment(
            instant = LocalDate.now(zone).plusDays(1).atTime(16, 0).atZone(zone).toInstant(),
            zoneId = zone.id,
        )
        val submitted = AtomicReference<Pair<String, ZonedMoment?>>()
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            OpenTasksTheme {
                QuickAddSheet(
                    onDismiss = {},
                    onAdd = { title, due -> submitted.set(title to due) },
                )
            }
        }

        composeRule.onNodeWithTag("quick-add-title")
            .performTextReplacement("Restored due tomorrow 4pm")
        composeRule.onNodeWithTag("quick-add-date-chip").performClick()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Add task").performClick()

        assertEquals("Restored due", submitted.get().first)
        assertEquals(expectedDue, submitted.get().second)
    }

    @Test
    fun searchQueryRestoresAndReissuesTheQueryAfterSavedInstanceStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        val latestQuery = AtomicReference(SearchQuery(""))
        val restored = SearchQuery(
            text = "restored",
            dueBuckets = setOf(DueBucket.LATER),
            priorities = setOf(Priority.HIGH),
            statuses = setOf(SemanticStatus.BLOCKED),
            sort = TaskSortKey.TITLE,
        )
        val view = SavedView(
            id = SavedViewId("restored-filter-view"),
            workspaceId = OpenTasksFixtures.workspaceId,
            name = "Restored filters",
            query = restored,
        )
        restorationTester.setContent {
            OpenTasksTheme {
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = latestQuery::set,
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                    savedViews = listOf(view),
                )
            }
        }

        composeRule.onNodeWithTag("saved-view-chip-${view.id.value}").performClick()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitUntil(timeoutMillis = 2_000) {
            latestQuery.get() == restored
        }

        composeRule.onNodeWithTag("workspace-search-query")
            .assertTextContains("restored", substring = true)
        composeRule.onNodeWithTag("active-saved-view-${view.id.value}").assertIsDisplayed()
    }
}

@RunWith(AndroidJUnit4::class)
class MainActivityRecoveryRestorationInstrumentedTest {
    private val recoveryInbox by lazy {
        AndroidBackupFiles(InstrumentationRegistry.getInstrumentation().targetContext).recoveryInbox
    }
    private val recoveryFixtureRule = object : ExternalResource() {
        override fun before() {
            // MainActivity checks `locked` ahead of `activeRecovery` (Task 10),
            // and AppLockController's cold-start value is a Hilt @Singleton
            // read once from this real preferences file -- whichever test
            // first triggers its construction in this instrumentation
            // session latches it in. A stale `lock_enabled=true` left by an
            // earlier session would show the lock overlay instead of the
            // recovery shell this test depends on, so this establishes the
            // clean baseline this test needs before `composeRule` (the inner
            // rule) launches the production activity.
            AppLockSettings(
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getSharedPreferences("app_lock", Context.MODE_PRIVATE),
            ).lockEnabled = false
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
