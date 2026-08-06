package app.opentasks.core.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.DefaultVaultRuntimeManager
import app.opentasks.core.data.LocalVaultRepositoryFactory
import app.opentasks.core.data.LocalVaultRuntime
import app.opentasks.core.data.LocalVaultRuntimeFactory
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.model.VaultId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole `.otvault` round trip on a real device: a seeded vault is exported
 * to a frozen v1 archive, that archive is authenticated back, and the result is
 * published as this device's vault through the recovery activation path.
 *
 * The archive is written with [OtVaultCodec] rather than `OtVaultExporter`
 * (which lives in `:app`, and cannot be depended on from here): the codec is
 * what the exporter writes through, so the bytes crossing this test are the
 * bytes a real export produces for a vault with no attachments.
 */
@RunWith(AndroidJUnit4::class)
class OtVaultImportInstrumentedTest {
    private lateinit var context: Context
    private val crypto: VaultCrypto = TinkVaultCrypto()
    private val codec = OtVaultCodec(DefaultAuthenticatedCloudObjectCodec(crypto))
    private val managers = mutableListOf<DefaultVaultRuntimeManager>()

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
    fun anImportedArchiveBecomesTheLiveVaultAndReleasesTheRollbackSlot() = runBlocking {
        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }
        withTimeout(TIMEOUT_MILLIS) { manager.createNewVault() }
        val priorRuntime = manager.requireActive()
        val priorSlot = priorRuntime.slot
        val archive = exportArchive(priorRuntime)
        val runtimeFactory = LocalVaultRuntimeFactory(context, crypto)
        // The vault being replaced is the rollback for as long as the import
        // runs; it is released only once the imported slot has proved it opens.
        assertTrue(runtimeFactory.hasVault(priorSlot))

        val imported = importArchive(manager, archive)

        val state = manager.state.value
        assertTrue(state is VaultRuntimeState.Active)
        val runtime = (state as VaultRuntimeState.Active).runtime
        assertNotEquals(priorSlot, runtime.slot)
        assertEquals(VaultId(imported.vaultId), runtime.vaultId)
        assertFalse(runtimeFactory.hasVault(priorSlot))
        assertTrue(runtimeFactory.listStagedSlots().contains(runtime.slot))

        // The imported vault reproduces the archive's records exactly: the same
        // canonical Stage 2 snapshot bytes the export was taken from.
        val capture = withTimeout(TIMEOUT_MILLIS) { runtime.backupCaptureSource.capture() }
        assertArrayEquals(
            BackupSnapshotCodec.encode(imported),
            BackupSnapshotCodec.encode(BackupSnapshotCodec.fromCapture(capture)),
        )
    }

    @Test
    fun anImportedVaultStartsWithNoRemoteBindingAtAll() = runBlocking {
        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }
        withTimeout(TIMEOUT_MILLIS) { manager.createNewVault() }
        val archive = exportArchive(manager.requireActive())

        importArchive(manager, archive)

        val runtime = manager.requireActive()
        assertTrue(runtime.remoteBackupStore.configurations(runtime.vaultId).isEmpty())
        val backupState = checkNotNull(runtime.backupStateStore.get(runtime.vaultId))
        assertEquals(NOT_PREPARED_PACKAGE_STATE, backupState.packageState)
        assertEquals(null, backupState.currentBaseObjectId)
        assertEquals(null, backupState.lastVerifiedSnapshotGeneration)
    }

    @Test
    fun aRefusedImportLeavesTheActiveVaultAndItsSlotUnchanged() = runBlocking {
        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }
        withTimeout(TIMEOUT_MILLIS) { manager.createNewVault() }
        val priorSlot = manager.requireActive().slot
        val archive = exportArchive(manager.requireActive())
        val snapshot = readArchive(archive) { header, key ->
            // A vault with no workspace is a state no import may publish, so
            // the staged slot is refused before anything is replaced.
            readSnapshot(archive, header, key).let { payload ->
                payload.copy(
                    records = payload.records.filter {
                        it.family != BackupRecordFamily.WORKSPACE
                    },
                )
            }
        }

        val refused = runCatching { activate(manager, archive, snapshot) }

        assertTrue(refused.isFailure)
        val state = manager.state.value
        assertTrue(state is VaultRuntimeState.Active)
        assertEquals(priorSlot, (state as VaultRuntimeState.Active).runtime.slot)
        assertTrue(LocalVaultRuntimeFactory(context, crypto).hasVault(priorSlot))
        assertTrue(LocalVaultRuntimeFactory(context, crypto).listStagedSlots().isEmpty())
    }

    // ------------------------------------------------------------- Fixtures

    private fun manager(): DefaultVaultRuntimeManager =
        DefaultVaultRuntimeManager(context, crypto).also { managers += it }

    /**
     * Writes the active vault as a `.otvault` v1 archive, exactly as an export
     * of a vault holding no attachments does.
     */
    private suspend fun exportArchive(runtime: LocalVaultRuntime): ByteArray {
        // Reading the workspace first forces the shipped fixture to be seeded,
        // so the capture below describes a vault a person would recognise.
        withTimeout(TIMEOUT_MILLIS) { runtime.repository.currentWorkspace() }
        val capture = withTimeout(TIMEOUT_MILLIS) { runtime.backupCaptureSource.capture() }
        val key = runtime.contentKeyStore.getOrCreate(runtime.vaultId)
        return try {
            val header = OtVaultHeaderV1(
                formatVersion = OtVaultCodec.FORMAT_VERSION,
                vaultId = runtime.vaultId,
                createdAtEpochMillis = ARCHIVE_CREATED_AT,
                envelope = crypto.wrapForRecovery(key, PASSPHRASE.toCharArray()),
                recordCount = capture.records.size,
                attachmentCount = 0,
            )
            val destination = ByteArrayOutputStream()
            codec.writeHeader(destination, header)
            val entry = codec.writeSnapshot(
                destination,
                key,
                header,
                BackupSnapshotCodec.fromCapture(capture),
            )
            codec.writeInventory(destination, key, header, listOf(entry))
            destination.toByteArray()
        } finally {
            key.close()
        }
    }

    /** Authenticates [archive] and publishes it, returning the snapshot it held. */
    private suspend fun importArchive(
        manager: DefaultVaultRuntimeManager,
        archive: ByteArray,
    ): BackupSnapshotPayloadV1 {
        val snapshot = readArchive(archive) { header, key -> readSnapshot(archive, header, key) }
        activate(manager, archive, snapshot)
        return snapshot
    }

    private suspend fun activate(
        manager: DefaultVaultRuntimeManager,
        archive: ByteArray,
        snapshot: BackupSnapshotPayloadV1,
    ) {
        val source = ByteArrayInputStream(archive)
        val header = codec.readHeader(source)
        val contentKey = crypto.unlock(PASSPHRASE.toCharArray(), header.envelope)
        try {
            withTimeout(ACTIVATE_TIMEOUT_MILLIS) {
                LocalVaultRepositoryFactory.activateArchivedVault(
                    context = context,
                    crypto = crypto,
                    runtimeManager = manager,
                    operationId = OPERATION_ID,
                    snapshot = snapshot,
                    recoveryEnvelope = header.envelope,
                    contentKey = contentKey,
                )
            }
        } finally {
            contentKey.close()
        }
    }

    private fun <T> readArchive(
        archive: ByteArray,
        block: (OtVaultHeaderV1, VaultKey) -> T,
    ): T {
        val header = codec.readHeader(ByteArrayInputStream(archive))
        val key = crypto.unlock(PASSPHRASE.toCharArray(), header.envelope)
        return try {
            block(header, key)
        } finally {
            key.close()
        }
    }

    private fun readSnapshot(
        archive: ByteArray,
        header: OtVaultHeaderV1,
        key: VaultKey,
    ): BackupSnapshotPayloadV1 {
        val source = ByteArrayInputStream(archive)
        codec.readHeader(source)
        var payload: BackupSnapshotPayloadV1? = null
        codec.readAll(source, key, header) { event ->
            if (event is OtVaultReadEvent.Snapshot) payload = event.payload
        }
        return checkNotNull(payload) { "The archive holds no snapshot" }
    }

    private fun clearVaultState() {
        managers.forEach { runCatching { it.close() } }
        val runtimeFactory = LocalVaultRuntimeFactory(context, crypto)
        (runtimeFactory.listStagedSlots() + VaultSlot.LEGACY).distinct().forEach { slot ->
            runCatching { runtimeFactory.discard(slot) }
        }
        File(context.filesDir, "vault_runtime").deleteRecursively()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val ACTIVATE_TIMEOUT_MILLIS = 30_000L
        const val OPERATION_ID = "otvault-import:instrumented"
        const val PASSPHRASE = "correct horse battery staple"
        const val ARCHIVE_CREATED_AT = 1_700_000_000_000L
    }
}
