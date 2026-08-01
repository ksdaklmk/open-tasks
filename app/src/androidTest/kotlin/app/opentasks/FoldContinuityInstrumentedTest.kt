package app.opentasks

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.AtomicFile
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.core.crypto.AndroidVaultContentKeyStorage
import app.opentasks.core.data.AndroidVaultKeyManager
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.data.VaultSlot
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class FoldContinuityInstrumentedTest {
    private val vaultCleanupRule = object : ExternalResource() {
        override fun after() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val application = context as OpenTasksApplication
            application.activeVaultServices.quiesce()
            application.vaultRuntimeManager.close()
            context.deleteDatabase("open_tasks.db")
            AndroidVaultKeyManager(context).deleteDatabaseKey(VaultSlot.LEGACY)
            AndroidVaultContentKeyStorage.deleteLegacyStorage(context)
            AtomicFile(File(context.filesDir, "vault_runtime/active_slot.json")).delete()
            runBlocking { application.vaultRuntimeManager.initialize() }
            check(application.vaultRuntimeManager.state.value is VaultRuntimeState.NoVault) {
                "The continuity fixture did not return the process to NoVault"
            }
        }
    }
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(vaultCleanupRule).around(composeRule)

    @Test
    fun draftAndSelectionSurviveFoldTransition() {
        val statesOutput = shell("cmd device_state print-states")
        assertTrue(
            "device_state did not return a supported-state list: $statesOutput",
            statesOutput.contains("Supported states"),
        )
        val states = Regex("identifier=(\\d+), name='([A-Z_]+)'")
            .findAll(statesOutput)
            .associate { it.groupValues[2] to it.groupValues[1] }
        val closed = states["CLOSED"]
        val opened = states["OPENED"]
        assumeTrue(
            "Target exposes no closed/opened fold states: $statesOutput",
            closed != null && opened != null,
        )

        shell("wm dismiss-keyguard")
        awaitActivityState(Lifecycle.State.RESUMED)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("recovery-shell").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Quick add", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag("recovery-shell").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Start without restoring").performClick()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Quick add", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        repeat(6) { index ->
            composeRule.onNodeWithText("Quick add", useUnmergedTree = true).performClick()
            composeRule.onNodeWithTag("quick-add-title")
                .performTextReplacement("Fold continuity filler $index")
            composeRule.onNodeWithTag("quick-add-title").performImeAction()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("quick-add-title").fetchSemanticsNodes().isEmpty()
            }
        }

        composeRule.onNodeWithText("Tasks").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("task-list").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("Fold continuity filler 0").performClick()
        composeRule.onNodeWithTag("task-list").performScrollToIndex(5)
        val beforeScroll = listScrollPosition()
        assertTrue("Expected a meaningful pre-fold list scroll, was $beforeScroll", beforeScroll > 0f)

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("task-title-field")
            .performTextReplacement("Fold continuity draft")

        try {
            shell("cmd device_state state ${checkNotNull(closed)}")
            awaitActivityState(Lifecycle.State.CREATED)
            awaitSystemIdle()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()

            shell("cmd device_state state ${checkNotNull(opened)}")
            shell("wm dismiss-keyguard")
            awaitActivityState(Lifecycle.State.RESUMED)
            awaitSystemIdle()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("task-title-field")
                .assertTextContains("Fold continuity draft", substring = true)
            val afterScroll = listScrollPosition()
            assertTrue(
                "Expected the list scroll to survive the fold transition, was $afterScroll",
                afterScroll > 0f,
            )
        } finally {
            shell("cmd device_state state reset")
            shell("wm dismiss-keyguard")
            awaitSystemIdle()
        }
    }

    private fun listScrollPosition(): Float =
        composeRule.onNodeWithTag("task-list")
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private fun awaitSystemIdle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 10_000)
        instrumentation.waitForIdleSync()
    }

    private fun awaitActivityState(expected: Lifecycle.State) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (
            composeRule.activityRule.scenario.state != expected &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(50)
        }
        assertTrue(
            "Expected MainActivity state $expected, was ${composeRule.activityRule.scenario.state}",
            composeRule.activityRule.scenario.state == expected,
        )
    }
}
