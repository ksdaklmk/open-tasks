package app.opentasks.core.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.crypto.AndroidVaultContentKeyStore
import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.AndroidVaultKeyManager
import app.opentasks.core.data.DefaultVaultRuntimeManager
import app.opentasks.core.data.LocalRecoveryStagingFactory
import app.opentasks.core.data.LocalVaultRepositoryFactory
import app.opentasks.core.data.LocalVaultRuntimeFactory
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.data.VerifiedStagedVault
import app.opentasks.core.data.db.ActivityEntryEntity
import app.opentasks.core.data.db.MemberEntity
import app.opentasks.core.data.db.ProjectEntity
import app.opentasks.core.data.db.TaskEntity
import app.opentasks.core.data.db.TombstoneEntity
import app.opentasks.core.data.db.VaultEntity
import app.opentasks.core.data.db.WorkflowStatusEntity
import app.opentasks.core.data.db.WorkspaceEntity
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.VaultId
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Activation of a recovered slot on a real device.
 *
 * Process death is modelled by abandoning every in-memory object and rebuilding
 * the runtime manager from what is actually on disk, which is exactly what a
 * restarted process sees. An instrumentation run cannot survive killing its own
 * process, so the observable equivalent is what is asserted: after each
 * interruption point the manager opens either the unchanged prior slot or the
 * fully verified recovered slot, and never anything in between.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryActivationInstrumentedTest {
    private lateinit var context: Context
    private val crypto: VaultCrypto = TinkVaultCrypto()
    private val managers = mutableListOf<DefaultVaultRuntimeManager>()
    private val stagedSlots = mutableListOf<VaultSlot>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearVaultState()
    }

    @After
    fun tearDown() {
        managers.forEach { runCatching { it.close() } }
        managers.clear()
        clearVaultState()
    }

    @Test
    fun aVerifiedStagedVaultBecomesTheLiveVault() = runBlocking {
        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }
        assertEquals(VaultRuntimeState.NoVault, manager.state.value)

        val staged = reconstruct(manager)
        val factory = stagingFactory(manager)
        withTimeout(TIMEOUT_MILLIS) { factory.activate(staged.session, staged.verified) }

        val state = manager.state.value
        assertTrue(state is VaultRuntimeState.Active)
        val runtime = (state as VaultRuntimeState.Active).runtime
        assertEquals(VaultId(VAULT_ID), runtime.vaultId)
        assertEquals(staged.verified.slot, runtime.slot)
        assertEquals(
            RECOVERED_GENERATION,
            checkNotNull(runtime.backupStateStore.get(VaultId(VAULT_ID))).currentGeneration,
        )
    }

    /**
     * The recovered vault has to be able to produce a fresh Stage 2 complete
     * base before it may publish anything, and its journal is empty by design:
     * capture must therefore attribute its tombstones and relationless activity
     * entries without journal evidence.
     */
    @Test
    fun aRecoveredVaultCapturesAFreshCompleteBaseForItsNextPublication() = runBlocking {
        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }
        val staged = reconstruct(manager)
        withTimeout(TIMEOUT_MILLIS) {
            stagingFactory(manager).activate(staged.session, staged.verified)
        }

        val runtime = (manager.state.value as VaultRuntimeState.Active).runtime
        val capture = withTimeout(TIMEOUT_MILLIS) { runtime.backupCaptureSource.capture() }

        assertEquals(VaultId(VAULT_ID), capture.vaultId)
        val identities = capture.records.groupBy { it.family }
            .mapValues { entry -> entry.value.map { it.identity }.toSet() }
        assertTrue(
            identities.getValue(BackupRecordFamily.TOMBSTONE)
                .contains(listOf(TOMBSTONE_OBJECT_ID, TOMBSTONE_OBJECT_TYPE)),
        )
        assertTrue(
            identities.getValue(BackupRecordFamily.ACTIVITY_ENTRY)
                .contains(listOf(UNLINKED_ACTIVITY_ID)),
        )
        BackupSnapshotCodec.encode(BackupSnapshotCodec.fromCapture(capture)).fill(0)
    }

    @Test
    fun deathBeforeActivationLeavesThePriorSlotUnchanged() = runBlocking {
        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }
        withTimeout(TIMEOUT_MILLIS) { manager.createNewVault() }
        val priorSlot = (manager.state.value as VaultRuntimeState.Active).runtime.slot
        val staged = reconstruct(manager)
        staged.session.close()

        // Everything in memory is abandoned; the next process reads only disk.
        manager.close()
        val restarted = manager()
        withTimeout(TIMEOUT_MILLIS) { restarted.initialize() }

        val state = restarted.state.value
        assertTrue(state is VaultRuntimeState.Active)
        assertEquals(priorSlot, (state as VaultRuntimeState.Active).runtime.slot)
        assertNotEquals(staged.verified.slot, state.runtime.slot)
        // The staged slot is retained rather than published: an interrupted
        // recovery may still be confirmed after a restart.
        assertTrue(
            LocalVaultRuntimeFactory(context, crypto)
                .listStagedSlots()
                .contains(staged.verified.slot),
        )
    }

    @Test
    fun deathAfterTheMarkerReplacementOpensTheFullyVerifiedRecoveredSlot() = runBlocking {
        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }
        val staged = reconstruct(manager)
        withTimeout(TIMEOUT_MILLIS) {
            stagingFactory(manager).activate(staged.session, staged.verified)
        }

        manager.close()
        val restarted = manager()
        withTimeout(TIMEOUT_MILLIS) { restarted.initialize() }

        val state = restarted.state.value
        assertTrue(state is VaultRuntimeState.Active)
        assertEquals(staged.verified.slot, (state as VaultRuntimeState.Active).runtime.slot)
        assertEquals(VaultId(VAULT_ID), state.runtime.vaultId)
    }

    @Test
    fun anAbandonedRecoveryDiscardsOnlyTheStagedSlot() = runBlocking {
        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }
        val staged = reconstruct(manager)

        withTimeout(TIMEOUT_MILLIS) { stagingFactory(manager).abandon(staged.session) }

        assertEquals(VaultRuntimeState.NoVault, manager.state.value)
        assertFalse(
            LocalVaultRuntimeFactory(context, crypto)
                .listStagedSlots()
                .contains(staged.verified.slot),
        )
        val namespace = checkNotNull(
            LocalVaultRepositoryFactory.storageNamespace(staged.verified.slot),
        )
        assertNull(
            runCatching {
                AndroidVaultContentKeyStore(context, crypto, namespace)
                    .openExisting(VaultId(VAULT_ID))
                    .close()
            }.getOrNull(),
        )
    }

    // ------------------------------------------------------------- Fixtures

    private fun manager(): DefaultVaultRuntimeManager =
        DefaultVaultRuntimeManager(context, crypto).also { managers += it }

    private fun stagingFactory(manager: DefaultVaultRuntimeManager) =
        LocalRecoveryStagingFactory(
            context = context,
            crypto = crypto,
            runtimeManager = manager,
            keyManager = AndroidVaultKeyManager(context),
        )

    private suspend fun reconstruct(manager: DefaultVaultRuntimeManager): StagedRecovery {
        val session = stagingFactory(manager).begin(OPERATION_ID)
        stagedSlots += session.slot
        val contentKey = crypto.createKey()
        val verified = try {
            withTimeout(RECONSTRUCT_TIMEOUT_MILLIS) {
                session.reconstruct(
                    request = RecoveryImportRequest(
                        snapshot = recoveredSnapshot(),
                        segments = emptyList(),
                        recoveryEnvelope = recoveryEnvelope(),
                        expectedGeneration = BackupGeneration(RECOVERED_GENERATION),
                    ),
                    contentKey = contentKey,
                )
            }
        } finally {
            contentKey.close()
        }
        return StagedRecovery(session, verified)
    }

    private class StagedRecovery(
        val session: RecoveryStagingSession,
        val verified: VerifiedStagedVault,
    )

    private fun recoveryEnvelope(): VaultKeyEnvelope = VaultKeyEnvelope(
        formatVersion = 1,
        kdf = Argon2Metadata(
            salt = ByteArray(16) { (it + 1).toByte() },
            memoryKiB = 65_536,
            iterations = 3,
            parallelism = 1,
        ),
        nonce = ByteArray(12) { (it + 21).toByte() },
        wrappedKeyset = ByteArray(48) { (it + 41).toByte() },
    )

    /**
     * A complete vault a repository will accept, holding exactly the two record
     * families a recovered vault can no longer attribute through its journal.
     */
    private fun recoveredSnapshot(): BackupSnapshotPayloadV1 {
        val records = mutableListOf<BackupRecordV1>()
        records += VaultEntity(
            id = VAULT_ID,
            storageMode = "LOCAL",
            createdAtEpochMillis = 1_700_000_000_000,
            schemaVersion = 6,
            cryptoVersion = 1,
            minimumReaderVersion = 1,
        ).toBackupRecordV1()
        records += MemberEntity(MEMBER_ID, "You").toBackupRecordV1()
        records += WorkspaceEntity(
            id = WORKSPACE_ID,
            vaultId = VAULT_ID,
            ownerId = MEMBER_ID,
            name = "Open Tasks",
        ).toBackupRecordV1()
        records += ProjectEntity(
            id = PROJECT_ID,
            workspaceId = WORKSPACE_ID,
            name = "Recovered project",
            summary = "",
            health = "ON_TRACK",
            dueDate = null,
            completedTasks = 0,
            totalTasks = 1,
            archivedAtEpochMillis = null,
            revisionWallMillis = 1,
            revisionLogical = 0,
            revisionDeviceId = SOURCE_DEVICE_ID,
        ).toBackupRecordV1()
        SemanticStatus.entries.forEachIndexed { index, semantic ->
            records += WorkflowStatusEntity(
                id = "status-project-${semantic.name.lowercase()}",
                projectId = PROJECT_ID,
                name = semantic.name,
                semanticStatus = semantic.name,
                rank = "a$index",
                archivedAtEpochMillis = null,
                revisionWallMillis = 1,
                revisionLogical = 0,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1()
            records += WorkflowStatusEntity(
                id = "status-inbox-${semantic.name.lowercase()}",
                projectId = null,
                name = "Inbox ${semantic.name}",
                semanticStatus = semantic.name,
                rank = "b$index",
                archivedAtEpochMillis = null,
                revisionWallMillis = 1,
                revisionLogical = 0,
                revisionDeviceId = SOURCE_DEVICE_ID,
            ).toBackupRecordV1()
        }
        records += TaskEntity(
            id = TASK_ID,
            workspaceId = WORKSPACE_ID,
            projectId = PROJECT_ID,
            parentTaskId = null,
            statusId = "status-project-${SemanticStatus.STARTED.name.lowercase()}",
            semanticStatus = SemanticStatus.STARTED.name,
            title = "Recovered task",
            descriptionCiphertext = byteArrayOf(1, 2, 3),
            priority = "MEDIUM",
            startEpochMillis = null,
            startZoneId = null,
            dueEpochMillis = null,
            dueZoneId = null,
            recurrenceFrequency = null,
            recurrenceInterval = null,
            recurrenceWeekdays = null,
            recurrenceCount = null,
            recurrenceEndDate = null,
            recurrenceSeriesId = null,
            recurrenceAnchorEpochMillis = null,
            recurrenceAnchorZoneId = null,
            recurrenceOccurrenceIndex = null,
            estimateSeconds = null,
            milestoneId = null,
            completedAtEpochMillis = null,
            deletedAtEpochMillis = null,
            revisionWallMillis = 1,
            revisionLogical = 0,
            revisionDeviceId = SOURCE_DEVICE_ID,
        ).toBackupRecordV1()
        records += ActivityEntryEntity(
            id = UNLINKED_ACTIVITY_ID,
            taskId = null,
            projectId = null,
            kind = "UPDATED",
            bodyCiphertext = byteArrayOf(21, 22),
            createdAtEpochMillis = 1_700_000_008_000,
        ).toBackupRecordV1()
        records += TombstoneEntity(
            objectId = TOMBSTONE_OBJECT_ID,
            objectType = TOMBSTONE_OBJECT_TYPE,
            deletedAtEpochMillis = 1_700_000_012_000,
            purgeAfterEpochMillis = Long.MAX_VALUE,
            revisionWallMillis = 1_700_000_014_000,
            revisionLogical = 10,
            revisionDeviceId = SOURCE_DEVICE_ID,
        ).toBackupRecordV1()
        return BackupSnapshotPayloadV1(
            vaultId = VAULT_ID,
            coveredGeneration = RECOVERED_GENERATION,
            records = records,
        )
    }

    private fun clearVaultState() {
        managers.forEach { runCatching { it.close() } }
        val runtimeFactory = LocalVaultRuntimeFactory(context, crypto)
        (runtimeFactory.listStagedSlots() + stagedSlots + VaultSlot.LEGACY).distinct()
            .forEach { slot -> runCatching { runtimeFactory.discard(slot) } }
        stagedSlots.clear()
        File(context.filesDir, "vault_runtime").deleteRecursively()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val RECONSTRUCT_TIMEOUT_MILLIS = 30_000L
        const val OPERATION_ID = "recovery:activation-instrumented"
        const val VAULT_ID = "vault-primary"
        const val WORKSPACE_ID = "workspace-primary"
        const val MEMBER_ID = "member-owner"
        const val PROJECT_ID = "project-recovered"
        const val TASK_ID = "task-recovered"
        const val UNLINKED_ACTIVITY_ID = "activity-unlinked"
        const val TOMBSTONE_OBJECT_ID = "purged-task"
        const val TOMBSTONE_OBJECT_TYPE = "task"
        const val SOURCE_DEVICE_ID = "device-source"
        const val RECOVERED_GENERATION = 12L
    }
}
