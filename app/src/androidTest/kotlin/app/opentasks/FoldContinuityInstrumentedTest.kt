package app.opentasks

import android.app.Activity
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.core.crypto.AndroidVaultContentKeyStore
import app.opentasks.core.data.AtomicFileVaultRegistryOperations
import app.opentasks.core.data.AndroidVaultKeyManager
import app.opentasks.core.data.LocalVaultRuntimeFactory
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.data.VaultSlotRegistry
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.VaultId
import app.opentasks.digest.DailyDigestIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class FoldContinuityInstrumentedTest {
    private var ownedVault: OwnedVault? = null
    private var baselineClean = false

    private val vaultFixtureRule = object : ExternalResource() {
        override fun before() {
            baselineClean = false
            check(ownedVault == null) { "A continuity fixture is already owned" }
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val application = context as OpenTasksApplication
            requireCleanLegacyStorageBaseline(context)
            runBlocking { application.vaultRuntimeManager.initialize() }
            requireCleanLegacyBaseline(context, application)
            baselineClean = true
        }

        override fun after() {
            val owned = ownedVault ?: return
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val application = context as OpenTasksApplication
            val databaseName = LocalVaultRuntimeFactory.databaseName(owned.slot)
            try {
                check(baselineClean) { "The continuity fixture did not start from a clean baseline" }
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
                requireExpectedLegacyAliases(owned.vaultId)
            } finally {
                // Every step below is best-effort and unconditional: a
                // verification failure above must still fail this test, but it
                // must never leave `open_tasks.db` (or its Keystore aliases)
                // behind for the *next* test run to inherit -- that is exactly
                // the state `requireCleanLegacyStorageBaseline` exists to
                // refuse. An aborted teardown here would otherwise poison
                // every later run of this fixture, not just this one.
                runCatching { application.activeVaultServices.quiesce() }
                runCatching { AndroidVaultContentKeyStore(context).delete(owned.vaultId) }
                runCatching { application.vaultRuntimeManager.close() }
                runCatching { context.deleteDatabase(databaseName) }
                runCatching { AndroidVaultKeyManager(context).deleteDatabaseKey(owned.slot) }
                runCatching { AtomicFileVaultRegistryOperations().delete(activeSlotMarker(context)) }
            }
            requireAbsentOwnedLegacyResources(context, databaseName)
            runBlocking { application.vaultRuntimeManager.initialize() }
            check(application.vaultRuntimeManager.state.value is VaultRuntimeState.NoVault) {
                "The continuity fixture did not return the process to NoVault"
            }
        }
    }
    private val composeRule = createAndroidComposeRule<MainActivity>()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(vaultFixtureRule)
        .around(composeRule)
        .around(HideWindowsRule())

    @Test
    fun legacyBaselineRejectsOrphanKeystoreAliasesWithoutDeletingThem() {
        val context = application()
        val keyManager = AndroidVaultKeyManager(context)
        val contentKeyStore = AndroidVaultContentKeyStore(context)
        keyManager.createDatabaseKey(VaultSlot.LEGACY).fill(0)
        contentKeyStore.getOrCreate(LEGACY_VAULT_ID).close()
        check(
            context.getSharedPreferences(VAULT_KEY_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(LEGACY_DATABASE_NONCE)
                .remove(LEGACY_DATABASE_CIPHERTEXT)
                .commit(),
        )
        check(
            context.getSharedPreferences(CONTENT_KEY_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(LEGACY_CONTENT_NONCE)
                .remove(LEGACY_CONTENT_CIPHERTEXT)
                .commit(),
        )

        try {
            val failure = runCatching {
                requireCleanLegacyBaseline(context, application())
            }.exceptionOrNull()

            assertTrue("Orphan Keystore aliases must fail the clean baseline", failure != null)
            assertTrue(androidKeyStore().containsAlias(LEGACY_DATABASE_ALIAS))
            assertTrue(androidKeyStore().containsAlias(LEGACY_CONTENT_ALIAS))
        } finally {
            contentKeyStore.delete(LEGACY_VAULT_ID)
            keyManager.deleteDatabaseKey(VaultSlot.LEGACY)
        }
        assertTrue(!androidKeyStore().containsAlias(LEGACY_DATABASE_ALIAS))
        assertTrue(!androidKeyStore().containsAlias(LEGACY_CONTENT_ALIAS))
    }

    @Test
    fun legacyBaselineRejectsOrphanStorageSidecarsWithoutDeletingThem() {
        val context = application()
        val database = context.getDatabasePath(LEGACY_DATABASE_NAME)
        val sidecars = listOf(
            File("${database.path}-wal"),
            File("${database.path}-shm"),
            File("${database.path}-journal"),
            File("${database.path}-wipecheck"),
            File(checkNotNull(database.parentFile), "${database.name}-mj-continuity-test"),
        ) + activeSlotFiles(context).drop(1)

        sidecars.forEach { sidecar ->
            check(sidecar.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            check(sidecar.createNewFile())
            try {
                val failure = runCatching {
                    requireCleanLegacyBaseline(context, application())
                }.exceptionOrNull()

                assertTrue("Orphan ${sidecar.name} must fail the clean baseline", failure != null)
                assertTrue("The refused sidecar must not be deleted", sidecar.exists())
            } finally {
                check(sidecar.delete() || !sidecar.exists())
            }
        }
    }

    @Test
    fun consumedDigestIntentDoesNotReplayHomeAfterRecreation() {
        composeRule.activityRule.scenario.close()
        val launchIntent = DailyDigestIntents.homeIntent(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("recovery-shell")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Start without restoring").performClick()
            ownedVault = awaitCreatedVault()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("More", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("More", useUnmergedTree = true).performClick()
            composeRule.onNodeWithTag("more-overview").assertIsDisplayed()
            scenario.onActivity { activity ->
                // ActivityScenario filters lifecycle events against this
                // retained launch token; synchronize the token without
                // changing the production Activity intent under test.
                launchIntent.action = activity.intent.action
            }
            scenario.recreate()

            composeRule.onNodeWithTag("more-overview").assertIsDisplayed()
        }
    }

    @Test
    fun draftAndSelectionSurviveFoldTransition() {
        val avdName = shell("getprop ro.boot.qemu.avd_name").trim()
        assumeTrue(
            "Pixel_10_Pro_Fold cross-display transitions are unsupported by the " +
                "ActivityScenario/Compose harness",
            avdName != "Pixel_10_Pro_Fold",
        )
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

        try {
            shell("cmd device_state state ${checkNotNull(closed)}")
            awaitDeviceState(checkNotNull(closed))
            shell("wm dismiss-keyguard")
            awaitActivityState(Lifecycle.State.RESUMED)
            // The fold state change recreates the activity, so RESUMED can be
            // reached before the new composition attaches. Querying then throws
            // rather than reporting an empty tree, which would abort the wait
            // instead of retrying it.
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onAllNodesWithTag("recovery-shell")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }.getOrDefault(false)
            }
            composeRule.onNodeWithText("Start without restoring").performClick()
            ownedVault = awaitCreatedVault()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Quick add", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            repeat(10) { index ->
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
            assertTrue(
                "Expected a meaningful pre-fold list scroll, was $beforeScroll",
                beforeScroll > 0f,
            )

            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithTag("task-title-field")
                .performTextReplacement("Fold continuity draft")
            composeRule.onNode(editingTaskMatcher("Fold continuity draft")).assertIsDisplayed()
            assertEquals(selectedTitle, repositoryTitle(selectedTaskId))
            val beforeActivity = currentActivity()
            val beforeWindow = windowSnapshot(beforeActivity)

            shell("cmd device_state state ${checkNotNull(opened)}")
            awaitDeviceState(checkNotNull(opened))
            shell("wm dismiss-keyguard")
            awaitActivityState(Lifecycle.State.RESUMED)
            awaitSystemIdle()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()

            val afterActivity = currentActivity()
            val afterWindow = windowSnapshot(afterActivity)
            assertNotSame("Fold transition must recreate MainActivity", beforeActivity, afterActivity)
            assertNotEquals("Fold transition must change window configuration", beforeWindow, afterWindow)

            composeRule.onNodeWithTag("task-title-field")
                .assertTextContains("Fold continuity draft", substring = true)
            composeRule.onNode(editingTaskMatcher("Fold continuity draft")).assertIsDisplayed()
            assertEquals(selectedTitle, repositoryTitle(selectedTaskId))
            val afterScroll = listScrollPosition()
            assertEquals(
                "The meaningful list position must survive the fold transition",
                beforeScroll,
                afterScroll,
                1f,
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

    private fun currentActivity(): MainActivity {
        var current: MainActivity? = null
        composeRule.activityRule.scenario.onActivity { current = it }
        return checkNotNull(current)
    }

    private fun windowSnapshot(activity: Activity): WindowSnapshot =
        activity.resources.configuration.let { configuration ->
            WindowSnapshot(
                widthDp = configuration.screenWidthDp,
                heightDp = configuration.screenHeightDp,
                densityDpi = configuration.densityDpi,
            )
        }

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
        check(runtime.vaultId == LEGACY_VAULT_ID) {
            "The production new-vault flow created an unexpected vault identity"
        }
        while (
            !expectedLegacyAliasesExist() &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(50)
        }
        requireExpectedLegacyAliases(runtime.vaultId)
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

    private fun requireCleanLegacyBaseline(
        context: android.content.Context,
        application: OpenTasksApplication,
    ) {
        check(application.vaultRuntimeManager.state.value is VaultRuntimeState.NoVault) {
            "Fold continuity requires a clean NoVault baseline"
        }
        requireCleanLegacyStorageBaseline(context)
        check(!AndroidVaultKeyManager(context).hasDatabaseKey(VaultSlot.LEGACY)) {
            "Fold continuity refuses to replace an existing legacy database key"
        }
        check(
            context.getSharedPreferences("vault_content_keys_v1", 0).all.isEmpty(),
        ) {
            "Fold continuity refuses to replace existing legacy content keys"
        }
        check(!androidKeyStore().containsAlias(LEGACY_DATABASE_ALIAS)) {
            "Fold continuity refuses to reuse an existing legacy database alias"
        }
        check(!androidKeyStore().containsAlias(LEGACY_CONTENT_ALIAS)) {
            "Fold continuity refuses to reuse an existing legacy content-key alias"
        }
    }

    private fun requireCleanLegacyStorageBaseline(context: Context) {
        check(legacyDatabaseFiles(context).none(File::exists)) {
            "Fold continuity refuses to replace existing legacy database files"
        }
        check(activeSlotFiles(context).none(File::exists)) {
            "Fold continuity refuses to replace existing active-slot files"
        }
    }

    private fun activeSlotMarker(context: android.content.Context): File =
        File(context.filesDir, "vault_runtime/active_slot.json")

    private fun activeSlotFiles(context: Context): List<File> {
        val marker = activeSlotMarker(context)
        return listOf(marker, File("${marker.path}.new"), File("${marker.path}.bak"))
    }

    private fun legacyDatabaseFiles(
        context: Context,
        databaseName: String = LEGACY_DATABASE_NAME,
    ): List<File> {
        val database = context.getDatabasePath(databaseName)
        val masterJournals = database.parentFile
            ?.listFiles { file -> file.name.startsWith("${database.name}-mj") }
            ?.toList()
            .orEmpty()
        return listOf(
            database,
            File("${database.path}-wal"),
            File("${database.path}-shm"),
            File("${database.path}-journal"),
            File("${database.path}-wipecheck"),
        ) + masterJournals
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun expectedLegacyAliasesExist(): Boolean =
        androidKeyStore().let { keyStore ->
            keyStore.containsAlias(LEGACY_DATABASE_ALIAS) &&
                keyStore.containsAlias(LEGACY_CONTENT_ALIAS)
        }

    private fun requireExpectedLegacyAliases(vaultId: VaultId) {
        check(vaultId == LEGACY_VAULT_ID) {
            "The continuity fixture does not own the expected legacy vault identity"
        }
        check(expectedLegacyAliasesExist()) {
            "The continuity fixture does not own both expected legacy aliases"
        }
    }

    private fun requireAbsentOwnedLegacyResources(
        context: Context,
        databaseName: String,
    ) {
        check(legacyDatabaseFiles(context, databaseName).none(File::exists)) {
            "Owned continuity database files remain after cleanup"
        }
        check(!AndroidVaultKeyManager(context).hasDatabaseKey(VaultSlot.LEGACY)) {
            "The owned continuity database-key envelope remains after cleanup"
        }
        check(
            context.getSharedPreferences(CONTENT_KEY_PREFERENCES, Context.MODE_PRIVATE)
                .all
                .isEmpty(),
        ) {
            "The owned continuity content-key envelope remains after cleanup"
        }
        check(activeSlotFiles(context).none(File::exists)) {
            "Owned continuity active-slot files remain after cleanup"
        }
        check(!androidKeyStore().containsAlias(LEGACY_DATABASE_ALIAS)) {
            "The owned continuity database alias remains after cleanup"
        }
        check(!androidKeyStore().containsAlias(LEGACY_CONTENT_ALIAS)) {
            "The owned continuity content-key alias remains after cleanup"
        }
    }

    private data class OwnedVault(
        val slot: VaultSlot,
        val vaultId: VaultId,
    )

    private data class WindowSnapshot(
        val widthDp: Int,
        val heightDp: Int,
        val densityDpi: Int,
    )

    private companion object {
        val LEGACY_VAULT_ID = VaultId("vault-primary")
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val VAULT_KEY_PREFERENCES = "vault_keys"
        const val CONTENT_KEY_PREFERENCES = "vault_content_keys_v1"
        const val LEGACY_DATABASE_NAME = "open_tasks.db"
        const val LEGACY_DATABASE_NONCE = "database_key_nonce_v1"
        const val LEGACY_DATABASE_CIPHERTEXT = "database_key_ciphertext_v1"
        const val LEGACY_CONTENT_NONCE =
            "nonce_v1_e7fb92d32ce629d2d0db1f93337fb4df409b33e878ae731e6032b3922a1164e0"
        const val LEGACY_CONTENT_CIPHERTEXT =
            "ciphertext_v1_e7fb92d32ce629d2d0db1f93337fb4df409b33e878ae731e6032b3922a1164e0"
        const val LEGACY_DATABASE_ALIAS = "open_tasks_local_vault_wrapper_v1"
        const val LEGACY_CONTENT_ALIAS =
            "open_tasks_vault_content_wrapper_v1_" +
                "e7fb92d32ce629d2d0db1f93337fb4df409b33e878ae731e6032b3922a1164e0"
    }
}
