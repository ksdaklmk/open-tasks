package app.opentasks

import android.content.ContextWrapper
import app.opentasks.backup.OtVaultExporter
import app.opentasks.backup.OtVaultImporter
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.backup.AttachmentCacheStore
import app.opentasks.core.data.backup.OtVaultCodec
import app.opentasks.core.data.export.WorkspaceCsvWriter
import app.opentasks.core.domain.BackupCaptureSource
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.feature.more.CsvExportOutcome
import app.opentasks.feature.more.CsvExportTable
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [VaultTransferViewModel]'s CSV batch/cancel state machine as far as
 * this module's constraints allow.
 *
 * Of the four terminal [CsvExportOutcome] states, three are reachable here:
 * "cancelled before anything completed" (the picker returns a null
 * [android.net.Uri] for the very first requested table), and `Failed` via
 * [VaultTransferViewModel.requestNextCsvDocument]'s synchronous
 * closed-channel guard -- closing [VaultTransferViewModel.csvCreateDocumentRequests]
 * before [VaultTransferViewModel.beginCsvExport] makes its first `trySend`
 * fail, which aborts the batch with `Failed` before any coroutine, `Uri`, or
 * [android.content.ContentResolver] is ever involved. The remaining two --
 * full success and partial-success-then-cancelled -- are not reachable: both
 * require [VaultTransferViewModel.onCsvDocumentSelected] to be called with a
 * *non-null* `Uri` that a write actually succeeds against, and this module
 * has no way to produce a non-null `Uri` at all: `Uri.parse` and every other
 * public factory throw `RuntimeException: ... not mocked` under the stub
 * `android.jar` (verified directly against this project's test runtime, not
 * assumed), `Uri.EMPTY` resolves to `null` there rather than a real
 * instance, and `Uri`'s own no-arg constructor is package-private to
 * `android.net` so it cannot be subclassed from this module either. No
 * mocking library and no Robolectric are available to work around that (repo
 * rules), and the product is not refactored for testability here.
 */
class VaultTransferViewModelTest {
    private val stagingRoots = mutableListOf<File>()

    @After
    fun tearDown() {
        stagingRoots.forEach { it.deleteRecursively() }
    }

    @Test
    fun cancellingBeforeAnyTableCompletesLeavesTheOutcomeNullAndReleasesTheLock() {
        val viewModel = viewModel()

        viewModel.beginCsvExport(setOf(CsvExportTable.TASKS))
        assertTrue(viewModel.csvExportInProgress.value)

        viewModel.onCsvDocumentSelected(uri = null)

        assertNull(viewModel.csvExportOutcome.value)
        assertTrue(waitUntil { !viewModel.csvExportInProgress.value })
        // The operation lock was released synchronously by the cancel path
        // (there is no coroutine to await here), so a fresh batch can start
        // immediately.
        viewModel.beginCsvExport(setOf(CsvExportTable.PROJECTS))
        assertTrue(viewModel.csvExportInProgress.value)
    }

    @Test
    fun cancellingWithMultipleTablesPendingStopsTheWholeBatchAtOnce() {
        val viewModel = viewModel()

        viewModel.beginCsvExport(setOf(CsvExportTable.TASKS, CsvExportTable.PROJECTS))
        assertTrue(viewModel.csvExportInProgress.value)

        viewModel.onCsvDocumentSelected(uri = null)

        assertNull(viewModel.csvExportOutcome.value)
        assertTrue(waitUntil { !viewModel.csvExportInProgress.value })
    }

    @Test
    fun aClosedCsvDocumentRequestChannelFailsTheBatchSynchronously() {
        val viewModel = viewModel()
        viewModel.csvCreateDocumentRequests.close()

        viewModel.beginCsvExport(setOf(CsvExportTable.TASKS))

        // requestNextCsvDocument's trySend fails against a closed channel,
        // which aborts the batch synchronously -- no coroutine, no Uri, no
        // ContentResolver ever enters the picture.
        assertEquals(
            CsvExportOutcome.Failed("The CSV export could not be completed."),
            viewModel.csvExportOutcome.value,
        )
        assertTrue(!viewModel.csvExportInProgress.value)
    }

    @Test
    fun anEmptyTableSelectionNeverStartsABatch() {
        val viewModel = viewModel()

        viewModel.beginCsvExport(emptySet())

        assertNull(viewModel.csvExportOutcome.value)
        assertTrue(!viewModel.csvExportInProgress.value)
        // The operation lock was never taken.
        viewModel.beginCsvExport(setOf(CsvExportTable.TASKS))
        assertTrue(viewModel.csvExportInProgress.value)
    }

    private fun waitUntil(timeoutMillis: Long = 5_000, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    private fun viewModel(): VaultTransferViewModel {
        val crypto: VaultCrypto = TinkVaultCrypto()
        val codec = OtVaultCodec(DefaultAuthenticatedCloudObjectCodec(crypto))
        val vaultRepository = FakeVaultRepository()
        val exporter = OtVaultExporter(
            vaultId = VaultId("vault-transfer-test"),
            captureSource = BackupCaptureSource { error("Not used by the CSV batch/cancel path") },
            vaultRepository = vaultRepository,
            contentKeyStore = ErrorContentKeyStore,
            codec = codec,
            prepareEnvelope = { error("Not used by the CSV batch/cancel path") },
            readChunksForExport = { _, _ -> error("Not used by the CSV batch/cancel path") },
        )
        val stagingRoot = Files.createTempDirectory("vault-transfer-test-staging").toFile()
        val cacheRoot = Files.createTempDirectory("vault-transfer-test-cache").toFile()
        stagingRoots += stagingRoot
        stagingRoots += cacheRoot
        val importer = OtVaultImporter(
            codec = codec,
            authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
            crypto = crypto,
            cache = AttachmentCacheStore(cacheRoot) { 0L },
            stagingRoot = stagingRoot,
            activateImportedVault = { _, _, _ -> error("Not used by the CSV batch/cancel path") },
        )
        return VaultTransferViewModel(
            context = ContextWrapper(null),
            exporter = exporter,
            importer = importer,
            vaultRepository = vaultRepository,
            csvWriter = WorkspaceCsvWriter(ZoneId.of("UTC")),
        )
    }

    /** A minimal [VaultRepository] exposing only an empty, valid snapshot. */
    private class FakeVaultRepository : VaultRepository {
        private val snapshot = MutableStateFlow(
            WorkspaceSnapshot(
                home = HomeSnapshot(
                    today = LocalDate.of(2026, 8, 6),
                    focusTasks = emptyList(),
                    upcomingTasks = emptyList(),
                    projects = emptyList(),
                    activeTimer = null,
                    overdueCount = 0,
                ),
                tasks = emptyList(),
                projects = emptyList(),
                workflowStatuses = emptyList(),
                milestones = emptyList(),
                tags = emptyList(),
            ),
        )

        override fun observeHome(): Flow<HomeSnapshot> = error("Not used by the CSV batch/cancel path")

        override fun observeWorkspace(): StateFlow<WorkspaceSnapshot> = snapshot

        override fun observeTask(id: TaskId): Flow<Task?> =
            error("Not used by the CSV batch/cancel path")

        override suspend fun currentWorkspace(): WorkspaceSnapshot = snapshot.value

        override suspend fun execute(command: DomainCommand): CommandResult =
            error("Not used by the CSV batch/cancel path")

        override suspend fun search(query: SearchQuery): List<SearchResult> =
            error("Not used by the CSV batch/cancel path")
    }

    private object ErrorContentKeyStore : VaultContentKeyStore {
        override fun getOrCreate(vaultId: VaultId): VaultKey =
            error("Not used by the CSV batch/cancel path")

        override fun openExisting(vaultId: VaultId): VaultKey =
            error("Not used by the CSV batch/cancel path")

        override fun replace(vaultId: VaultId, key: VaultKey) =
            error("Not used by the CSV batch/cancel path")

        override fun delete(vaultId: VaultId) = error("Not used by the CSV batch/cancel path")
    }
}
