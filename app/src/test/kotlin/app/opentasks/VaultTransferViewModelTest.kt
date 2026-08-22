package app.opentasks

import android.content.Intent
import android.content.ContextWrapper
import android.net.Uri
import app.opentasks.backup.OtVaultExporter
import app.opentasks.backup.OtVaultImporter
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.backup.AttachmentCacheStore
import app.opentasks.core.data.backup.OtVaultCodec
import app.opentasks.core.data.export.ExecutiveDashboardHtmlWriter
import app.opentasks.core.data.export.ProjectMarkdownWriter
import app.opentasks.core.data.export.CsvParseResult
import app.opentasks.core.data.export.WorkspaceCsvWriter
import app.opentasks.core.domain.BackupCaptureSource
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DefaultInsightsEngine
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.ImportedTaskRow
import app.opentasks.core.domain.InsightsEngine
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.WorkspaceId
import app.opentasks.feature.more.CsvExportOutcome
import app.opentasks.feature.more.CsvExportTable
import app.opentasks.feature.more.CsvImportOutcome
import app.opentasks.feature.more.DashboardExportOutcome
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * require [VaultTransferViewModel.onCsvExportDocumentSelected] to be called with a
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

        viewModel.onCsvExportDocumentSelected(uri = null)

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

        viewModel.onCsvExportDocumentSelected(uri = null)

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

    @Test
    fun markdownExportDoesNothingWhileAnotherTransferOwnsTheLock() {
        val viewModel = viewModel()
        viewModel.beginCsvExport(setOf(CsvExportTable.TASKS))

        viewModel.beginMarkdownExport(ProjectId("project-1"))

        assertTrue(!viewModel.markdownExportInProgress.value)
        assertTrue(viewModel.markdownCreateDocumentRequests.tryReceive().getOrNull() == null)
    }

    @Test
    fun cancellingMarkdownDocumentSelectionReleasesTheLockWithoutAnOutcome() {
        val viewModel = viewModel()

        viewModel.beginMarkdownExport(ProjectId("project-1"))
        assertTrue(viewModel.markdownExportInProgress.value)
        assertEquals(
            "open_tasks_studio_refresh.md",
            viewModel.markdownCreateDocumentRequests.tryReceive().getOrNull(),
        )

        viewModel.onMarkdownDocumentSelected(uri = null)

        assertNull(viewModel.markdownExportOutcome.value)
        assertTrue(!viewModel.markdownExportInProgress.value)
        viewModel.beginMarkdownExport(ProjectId("project-1"))
        assertTrue(viewModel.markdownExportInProgress.value)
    }

    @Test
    fun nullCsvImportDocumentReturnsToIdleAndUnlocks() {
        val viewModel = viewModel()

        viewModel.beginCsvImport()
        assertTrue(viewModel.csvImportInProgress.value)
        assertEquals(Unit, viewModel.csvOpenDocumentRequests.tryReceive().getOrNull())

        viewModel.onCsvDocumentSelected(uri = null)

        assertTrue(!viewModel.csvImportInProgress.value)
        viewModel.beginCsvImport()
        assertEquals(Unit, viewModel.csvOpenDocumentRequests.tryReceive().getOrNull())
    }

    @Test
    fun cancelCsvImportClearsPreviewAndUnlocks() {
        val viewModel = viewModel()
        viewModel.beginCsvImport()
        viewModel.handleCsvParseResult(CsvParseResult.Parsed(listOf(importRow())))
        assertEquals(CsvImportOutcome.Preview(1, 0, 0), viewModel.csvImportOutcome.value)

        viewModel.cancelCsvImport()

        assertNull(viewModel.csvImportOutcome.value)
        assertTrue(!viewModel.csvImportInProgress.value)
        viewModel.confirmCsvImport()
        assertNull(viewModel.csvImportCommitRequests.tryReceive().getOrNull())
        viewModel.beginMarkdownExport(ProjectId("project-1"))
        assertTrue(viewModel.markdownExportInProgress.value)
    }

    @Test
    fun confirmCsvImportEmitsOneBoundedRequestWithoutRelocking() {
        val viewModel = viewModel()
        val rows = listOf(importRow())
        viewModel.beginCsvImport()
        viewModel.handleCsvParseResult(CsvParseResult.Parsed(rows))

        viewModel.confirmCsvImport()
        viewModel.confirmCsvImport()

        assertEquals(rows, viewModel.csvImportCommitRequests.tryReceive().getOrNull())
        assertNull(viewModel.csvImportCommitRequests.tryReceive().getOrNull())
        assertNull(viewModel.csvImportOutcome.value)
        assertTrue(viewModel.csvImportInProgress.value)
        viewModel.cancelCsvImport()
        assertTrue(viewModel.csvImportInProgress.value)
        viewModel.beginMarkdownExport(ProjectId("project-1"))
        assertTrue(!viewModel.markdownExportInProgress.value)
    }

    @Test
    fun csvImportCommandSuccessClearsRowsAndUnlocksExactlyOnce() {
        val viewModel = viewModel()
        viewModel.beginCsvImport()
        viewModel.handleCsvParseResult(CsvParseResult.Parsed(listOf(importRow())))
        viewModel.confirmCsvImport()
        viewModel.csvImportCommitRequests.tryReceive()

        viewModel.onCsvImportCommandResult(CommandResult.Success("Imported"))
        viewModel.onCsvImportCommandResult(CommandResult.Success("Duplicate callback"))

        assertEquals(CsvImportOutcome.Completed(1), viewModel.csvImportOutcome.value)
        assertTrue(!viewModel.csvImportInProgress.value)
        viewModel.confirmCsvImport()
        assertNull(viewModel.csvImportCommitRequests.tryReceive().getOrNull())
        viewModel.beginCsvImport()
        assertEquals(Unit, viewModel.csvOpenDocumentRequests.tryReceive().getOrNull())
    }

    @Test
    fun csvImportCommandRejectionClearsRowsAndUnlocksExactlyOnce() {
        val viewModel = viewModel()
        viewModel.beginCsvImport()
        viewModel.handleCsvParseResult(CsvParseResult.Parsed(listOf(importRow())))
        viewModel.confirmCsvImport()
        viewModel.csvImportCommitRequests.tryReceive()

        viewModel.onCsvImportCommandResult(
            CommandResult.Rejected(RejectionReason.IMPORT_STATUS_CONFLICT, "Row changed"),
        )

        assertEquals(CsvImportOutcome.Failed(null, "Row changed"), viewModel.csvImportOutcome.value)
        assertTrue(!viewModel.csvImportInProgress.value)
        viewModel.confirmCsvImport()
        assertNull(viewModel.csvImportCommitRequests.tryReceive().getOrNull())
        viewModel.beginCsvImport()
        assertEquals(Unit, viewModel.csvOpenDocumentRequests.tryReceive().getOrNull())
    }

    @Test
    fun closedCsvImportCommitChannelFailsAndUnlocks() {
        val viewModel = viewModel()
        viewModel.beginCsvImport()
        viewModel.handleCsvParseResult(CsvParseResult.Parsed(listOf(importRow())))
        viewModel.csvImportCommitRequests.close()

        viewModel.confirmCsvImport()

        assertTrue(viewModel.csvImportOutcome.value is CsvImportOutcome.Failed)
        assertTrue(!viewModel.csvImportInProgress.value)
        viewModel.beginMarkdownExport(ProjectId("project-1"))
        assertTrue(viewModel.markdownExportInProgress.value)
    }

    @Test
    fun dashboardDownloadFreezesSnapshotSelectionAndTimeBeforeThePickerReturns() {
        val repository = FakeVaultRepository()
        val engine = RecordingInsightsEngine()
        val timeProvider = FakeInsightsTimeProvider(
            InsightsTimeContext(
                now = Instant.parse("2026-08-21T09:30:00Z"),
                zoneId = ZoneId.of("Asia/Bangkok"),
            ),
        )
        val runtime = dashboardRuntime()
        val viewModel = viewModel(repository, engine, timeProvider, runtime)
        val frozen = repository.observeWorkspace().value
        val selection = InsightsSelection(projectIds = setOf(ProjectId("project-1")))

        viewModel.beginDashboardDownload(selection, includeTaskDetails = true)

        assertEquals(
            "open_tasks_executive_2026-08-21.html",
            viewModel.dashboardCreateDocumentRequests.tryReceive().getOrNull(),
        )
        repository.replace(emptyWorkspace())
        timeProvider.value = InsightsTimeContext(
            now = Instant.parse("2030-01-01T00:00:00Z"),
            zoneId = ZoneId.of("UTC"),
        )
        val output = ByteArrayOutputStream()
        viewModel.onDashboardDestinationSelected(
            DashboardDocumentDestination(open = { output }, delete = {}),
        )

        assertTrue(waitUntil { viewModel.dashboardOutcome.value is DashboardExportOutcome.Completed })
        val captured = engine.requests.single()
        assertSame(frozen, captured.workspace)
        assertEquals(selection, captured.selection)
        assertEquals(Instant.parse("2026-08-21T09:30:00Z"), captured.now)
        assertEquals(ZoneId.of("Asia/Bangkok"), captured.zoneId)
        assertEquals(1, timeProvider.captureCount)
        assertTrue(output.toString(Charsets.UTF_8).startsWith("<!doctype html>"))
    }

    @Test
    fun dashboardPickerCancellationClearsProgressAndReleasesTheSharedLock() {
        val viewModel = viewModel()
        viewModel.beginDashboardDownload(InsightsSelection(), includeTaskDetails = false)
        assertTrue(viewModel.dashboardInProgress.value)

        viewModel.onDashboardDocumentSelected(uri = null)

        assertFalse(viewModel.dashboardInProgress.value)
        assertNull(viewModel.dashboardOutcome.value)
        viewModel.beginMarkdownExport(ProjectId("project-1"))
        assertTrue(viewModel.markdownExportInProgress.value)
    }

    @Test
    fun dashboardDownloadDeletesPartialDocumentWhenWritingFails() {
        val viewModel = viewModel()
        var deleted = 0
        viewModel.beginDashboardDownload(InsightsSelection(), includeTaskDetails = false)
        viewModel.dashboardCreateDocumentRequests.tryReceive()

        viewModel.onDashboardDestinationSelected(
            DashboardDocumentDestination(
                open = {
                    object : OutputStream() {
                        override fun write(value: Int) = throw IOException("disk full")
                    }
                },
                delete = { deleted++ },
            ),
        )

        assertTrue(waitUntil { viewModel.dashboardOutcome.value is DashboardExportOutcome.Failed })
        assertEquals(1, deleted)
        assertFalse(viewModel.dashboardInProgress.value)
    }

    @Test
    fun dashboardWriterFailureDeletesTheChosenDocumentAndSurfacesBoundedCopy() {
        val engine = RecordingInsightsEngine(failure = IOException("private cause"))
        val viewModel = viewModel(insightsEngine = engine)
        var deleted = 0
        viewModel.beginDashboardDownload(InsightsSelection(), includeTaskDetails = false)
        viewModel.dashboardCreateDocumentRequests.tryReceive()

        viewModel.onDashboardDestinationSelected(
            DashboardDocumentDestination(
                open = { ByteArrayOutputStream() },
                delete = { deleted++ },
            ),
        )

        assertTrue(waitUntil { viewModel.dashboardOutcome.value is DashboardExportOutcome.Failed })
        assertEquals(
            DashboardExportOutcome.Failed("The dashboard could not be generated."),
            viewModel.dashboardOutcome.value,
        )
        assertEquals(1, deleted)
    }

    @Test
    fun dashboardUsesTheExistingOperationMutex() {
        val viewModel = viewModel()
        viewModel.beginCsvExport(setOf(CsvExportTable.TASKS))

        viewModel.beginDashboardDownload(InsightsSelection(), includeTaskDetails = false)
        viewModel.shareDashboard(InsightsSelection(), includeTaskDetails = false)

        assertFalse(viewModel.dashboardInProgress.value)
        assertNull(viewModel.dashboardCreateDocumentRequests.tryReceive().getOrNull())
        assertNull(viewModel.dashboardShareRequests.tryReceive().getOrNull())
    }

    @Test
    fun dashboardShareSweepsStaleFilesAndRequestsAReadOnlyHtmlHandoff() {
        val repository = FakeVaultRepository()
        val frozen = repository.observeWorkspace().value
        val engine = RecordingInsightsEngine()
        val runtime = dashboardRuntime()
        runtime.reportsDirectory.mkdirs()
        val stale = File(runtime.reportsDirectory, "stale.html").apply { writeText("old") }
        val expectedIntent = Intent()
        runtime.intent = expectedIntent
        val viewModel = viewModel(
            repository = repository,
            insightsEngine = engine,
            dashboardRuntime = runtime,
        )

        viewModel.shareDashboard(
            selection = InsightsSelection(projectIds = setOf(ProjectId("project-1"))),
            includeTaskDetails = true,
        )
        repository.replace(emptyWorkspace())

        assertTrue(waitUntil { viewModel.dashboardOutcome.value is DashboardExportOutcome.Completed })
        assertFalse(stale.exists())
        val request = runtime.shareRequests.single()
        assertEquals(Intent.ACTION_SEND, request.action)
        assertEquals("text/html", request.mimeType)
        assertTrue(request.grantReadPermission)
        assertTrue(request.includeClipData)
        assertTrue(request.file.isFile)
        assertTrue(request.file.readText().startsWith("<!doctype html>"))
        assertSame(expectedIntent, viewModel.dashboardShareRequests.tryReceive().getOrNull())
        assertSame(frozen, engine.requests.single().workspace)
    }

    @Test
    fun dashboardSharesUseDifferentPathsOnTheSameDay() {
        val runtime = dashboardRuntime()
        val viewModel = viewModel(dashboardRuntime = runtime)

        viewModel.shareDashboard(InsightsSelection(), includeTaskDetails = false)
        assertTrue(waitUntil { viewModel.dashboardOutcome.value is DashboardExportOutcome.Completed })
        val firstPath = runtime.shareRequests.single().file.path

        viewModel.shareDashboard(InsightsSelection(), includeTaskDetails = false)
        assertTrue(waitUntil { runtime.shareRequests.size == 2 })

        assertTrue(firstPath != runtime.shareRequests[1].file.path)
        assertTrue(runtime.shareRequests[1].file.readText().startsWith("<!doctype html>"))
    }

    private fun waitUntil(timeoutMillis: Long = 5_000, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    private fun viewModel(
        repository: FakeVaultRepository = FakeVaultRepository(),
        insightsEngine: RecordingInsightsEngine = RecordingInsightsEngine(),
        insightsTimeProvider: FakeInsightsTimeProvider = FakeInsightsTimeProvider(),
        dashboardRuntime: FakeDashboardTransferRuntime = dashboardRuntime(),
    ): VaultTransferViewModel {
        val crypto: VaultCrypto = TinkVaultCrypto()
        val codec = OtVaultCodec(DefaultAuthenticatedCloudObjectCodec(crypto))
        val exporter = OtVaultExporter(
            vaultId = VaultId("vault-transfer-test"),
            captureSource = BackupCaptureSource { error("Not used by the CSV batch/cancel path") },
            vaultRepository = repository,
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
            vaultRepository = repository,
            csvWriter = WorkspaceCsvWriter(ZoneId.of("UTC")),
            markdownWriter = ProjectMarkdownWriter(),
            dashboardWriter = ExecutiveDashboardHtmlWriter(insightsEngine),
            insightsTimeProvider = insightsTimeProvider,
            dashboardRuntime = dashboardRuntime,
        )
    }

    private fun dashboardRuntime(): FakeDashboardTransferRuntime {
        val reports = Files.createTempDirectory("dashboard-share-test").toFile()
        stagingRoots += reports
        return FakeDashboardTransferRuntime(reports)
    }

    private fun importRow() = ImportedTaskRow(
        sourceRowNumber = 1,
        title = "Imported",
        projectName = null,
        statusName = null,
        priority = app.opentasks.core.model.Priority.NONE,
        start = null,
        due = null,
        completedAt = null,
        estimateMinutes = null,
        tagNames = emptyList(),
        description = "",
    )

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
                projects = listOf(
                    Project(
                        id = ProjectId("project-1"),
                        workspaceId = WorkspaceId("workspace-1"),
                        name = "Studio refresh",
                        summary = "",
                        status = ProjectHealth.ON_TRACK,
                        dueDate = null,
                        completedTasks = 0,
                        totalTasks = 0,
                    ),
                ),
                workflowStatuses = app.opentasks.core.model.WorkflowStatus.defaults(null),
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

        fun replace(value: WorkspaceSnapshot) {
            snapshot.value = value
        }
    }

    private fun emptyWorkspace() = WorkspaceSnapshot(
        home = HomeSnapshot(
            today = LocalDate.of(2030, 1, 1),
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
    )

    private data class CapturedInsightsRequest(
        val workspace: WorkspaceSnapshot,
        val selection: InsightsSelection,
        val now: Instant,
        val zoneId: ZoneId,
    )

    private class RecordingInsightsEngine(
        private val failure: Exception? = null,
    ) : InsightsEngine {
        private val delegate = DefaultInsightsEngine()
        val requests = mutableListOf<CapturedInsightsRequest>()

        override fun calculate(
            workspace: WorkspaceSnapshot,
            selection: InsightsSelection,
            now: Instant,
            zoneId: ZoneId,
        ): InsightsSnapshot {
            requests += CapturedInsightsRequest(workspace, selection, now, zoneId)
            failure?.let { throw it }
            return delegate.calculate(workspace, selection, now, zoneId)
        }
    }

    private class FakeInsightsTimeProvider(
        var value: InsightsTimeContext = InsightsTimeContext(
            now = Instant.parse("2026-08-21T09:30:00Z"),
            zoneId = ZoneId.of("Asia/Bangkok"),
        ),
    ) : InsightsTimeProvider {
        var captureCount = 0

        override fun capture(): InsightsTimeContext {
            captureCount++
            return value
        }

        override suspend fun awaitUntil(instant: Instant) = Unit
    }

    private class FakeDashboardTransferRuntime(
        override val reportsDirectory: File,
    ) : DashboardTransferRuntime {
        val shareRequests = mutableListOf<DashboardShareRequest>()
        var intent: Intent = Intent()

        override fun document(uri: Uri): DashboardDocumentDestination =
            error("Unit tests use the stream destination seam")

        override fun shareIntent(request: DashboardShareRequest): Intent {
            shareRequests += request
            return intent
        }
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
