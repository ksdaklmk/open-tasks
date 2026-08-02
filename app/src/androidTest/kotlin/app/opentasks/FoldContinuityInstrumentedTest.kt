package app.opentasks

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.AtomicFile
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
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
import app.opentasks.core.data.AtomicFileVaultRegistryOperations
import app.opentasks.core.data.AndroidVaultKeyManager
import app.opentasks.core.data.LocalVaultRuntimeFactory
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.data.VaultSlotRegistry
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.VaultId
import org.junit.Assert.assertEquals
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
    private var ownedVault: OwnedVault? = null

    private val vaultFixtureRule = object : ExternalResource() {
        override fun before() {
            check(ownedVault == null) { "A continuity fixture is already owned" }
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val application = context as OpenTasksApplication
            runBlocking { application.vaultRuntimeManager.initialize() }
            check(application.vaultRuntimeManager.state.value is VaultRuntimeState.NoVault) {
                "Fold continuity requires a clean NoVault baseline"
            }
            check(!context.getDatabasePath("open_tasks.db").exists()) {
                "Fold continuity refuses to replace an existing legacy database"
            }
            check(!AndroidVaultKeyManager(context).hasDatabaseKey(VaultSlot.LEGACY)) {
                "Fold continuity refuses to replace an existing legacy database key"
            }
            check(!activeSlotMarker(context).exists()) {
                "Fold continuity refuses to replace an existing active-slot marker"
            }
            check(
                context.getSharedPreferences("vault_content_keys_v1", 0).all.isEmpty(),
            ) {
                "Fold continuity refuses to replace existing legacy content keys"
            }
        }

        override fun after() {
            val owned = ownedVault ?: return
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val application = context as OpenTasksApplication
            val active = application.vaultRuntimeManager.state.value as? VaultRuntimeState.Active
                ?: error("The owned continuity vault is no longer active")
            check(active.runtime.slot == owned.slot && active.runtime.vaultId == owned.vaultId) {
                "The active vault is not the exact continuity fixture"
            }
            val registered = VaultSlotRegistry(
                directory = File(context.filesDir, "vault_runtime"),
                fileOperations = AtomicFileVaultRegistryOperations(),
            ).read()
            check(registered == owned.slot) {
                "The active-slot marker is not the exact continuity fixture"
            }
            check(owned.slot == VaultSlot.LEGACY) {
                "The production new-vault flow created an unexpected slot"
            }
            application.activeVaultServices.quiesce()
            application.vaultRuntimeManager.close()
            val databaseName = LocalVaultRuntimeFactory.databaseName(owned.slot)
            check(context.deleteDatabase(databaseName) || !context.getDatabasePath(databaseName).exists()) {
                "Unable to delete the owned continuity database"
            }
            AndroidVaultKeyManager(context).deleteDatabaseKey(owned.slot)
            AndroidVaultContentKeyStorage.deleteLegacyStorage(context)
            AtomicFile(activeSlotMarker(context)).delete()
            runBlocking { application.vaultRuntimeManager.initialize() }
            check(application.vaultRuntimeManager.state.value is VaultRuntimeState.NoVault) {
                "The continuity fixture did not return the process to NoVault"
            }
        }
    }
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(vaultFixtureRule).around(composeRule)

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
            composeRule.onAllNodesWithTag("recovery-shell").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Start without restoring").performClick()
        ownedVault = awaitCreatedVault()
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
        val selectedTitle = "Fold continuity filler 0"
        val selectedTaskId = activeWorkspaceTaskId(selectedTitle)
        composeRule.onNodeWithText(selectedTitle).performClick()
        composeRule.onNode(
            selectedTaskMatcher(selectedTitle),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("task-list").performScrollToIndex(5)
        val beforeScroll = listScrollPosition()
        assertTrue("Expected a meaningful pre-fold list scroll, was $beforeScroll", beforeScroll > 0f)

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("task-title-field")
            .performTextReplacement("Fold continuity draft")
        composeRule.onNode(editingTaskMatcher(selectedTitle)).assertIsDisplayed()
        assertEquals(selectedTitle, repositoryTitle(selectedTaskId))

        try {
            shell("cmd device_state state ${checkNotNull(closed)}")
            awaitDeviceState(checkNotNull(closed))
            awaitSystemIdle()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()

            shell("cmd device_state state ${checkNotNull(opened)}")
            awaitDeviceState(checkNotNull(opened))
            shell("wm dismiss-keyguard")
            awaitActivityState(Lifecycle.State.RESUMED)
            awaitSystemIdle()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("task-title-field")
                .assertTextContains("Fold continuity draft", substring = true)
            composeRule.onNode(editingTaskMatcher(selectedTitle)).assertIsDisplayed()
            assertEquals(selectedTitle, repositoryTitle(selectedTaskId))
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

    private fun selectedTaskMatcher(title: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
            .and(hasAnyDescendant(hasText(title)))

    private fun editingTaskMatcher(title: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Editing $title")

    private fun activeWorkspaceTaskId(title: String): TaskId =
        activeRuntime().repository.observeWorkspace().value.tasks.single { it.title == title }.id

    private fun repositoryTitle(taskId: TaskId): String =
        activeRuntime().repository.observeWorkspace().value.tasks.single { it.id == taskId }.title

    private fun activeRuntime() =
        (application().vaultRuntimeManager.state.value as VaultRuntimeState.Active).runtime

    private fun awaitCreatedVault(): OwnedVault {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (
            application().vaultRuntimeManager.state.value !is VaultRuntimeState.Active &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(50)
        }
        val runtime = activeRuntime()
        check(runtime.slot == VaultSlot.LEGACY) {
            "The production new-vault flow created an unexpected slot"
        }
        return OwnedVault(runtime.slot, runtime.vaultId)
    }

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

    private fun awaitDeviceState(expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var actual: String
        do {
            actual = shell("cmd device_state print-state").trim()
            if (actual == expected) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertEquals("Expected device state $expected", expected, actual)
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

    private fun application(): OpenTasksApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as OpenTasksApplication

    private fun activeSlotMarker(context: android.content.Context): File =
        File(context.filesDir, "vault_runtime/active_slot.json")

    private data class OwnedVault(
        val slot: VaultSlot,
        val vaultId: VaultId,
    )
}
