package app.opentasks

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentasks.backup.OT_VAULT_EXPORT_FAILED_REASON
import app.opentasks.backup.OT_VAULT_IMPORT_ACTIVATION_FAILED_REASON
import app.opentasks.backup.OT_VAULT_IMPORT_FAILED_REASON
import app.opentasks.backup.OtVaultExportResult
import app.opentasks.backup.OtVaultExporter
import app.opentasks.backup.OtVaultImportPreview
import app.opentasks.backup.OtVaultImporter
import app.opentasks.core.data.export.CsvTable
import app.opentasks.core.data.export.CsvImportPreviewResult
import app.opentasks.core.data.export.CsvParseResult
import app.opentasks.core.data.export.ExecutiveDashboardHtmlWriter
import app.opentasks.core.data.export.ExecutiveDashboardRequest
import app.opentasks.core.data.export.MAX_TASKS_CSV_BYTES
import app.opentasks.core.data.export.ProjectMarkdownWriter
import app.opentasks.core.data.export.WorkspaceCsvWriter
import app.opentasks.core.data.export.parseTasksCsv
import app.opentasks.core.data.export.previewTasksImport
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.ImportedTaskRow
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.feature.more.CsvExportOutcome
import app.opentasks.feature.more.CsvExportTable
import app.opentasks.feature.more.CsvImportOutcome
import app.opentasks.feature.more.DashboardExportOutcome
import app.opentasks.feature.more.MarkdownExportOutcome
import app.opentasks.feature.more.VaultExportOutcome
import app.opentasks.feature.more.VaultImportOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Owns the whole-vault export/import flows and the plaintext CSV transfer flows.
 *
 * A confirmed passphrase asks the caller to launch the matching Storage Access
 * Framework picker. An export streams straight into the chosen document's own
 * [android.content.ContentResolver] output stream — no plaintext archive is
 * ever staged anywhere on this device — and any result other than success
 * deletes that (empty or partial) document immediately. An import reads the
 * chosen document straight through the importer, which touches no vault slot
 * until the person confirms the preview it produces.
 *
 * CSV export shares [operation] with the whole-vault flows above — one
 * transfer of any kind at a time — and writes one document per chosen table,
 * one [android.provider.DocumentsContract.createDocument] picker at a time,
 * straight to that document's output stream with no retained plaintext copy.
 * A write failure or a mid-batch cancellation deletes that document's partial
 * bytes and ends the whole batch there; tables already written stand.
 */
@HiltViewModel
class VaultTransferViewModel internal constructor(
    context: Context,
    private val exporter: OtVaultExporter,
    private val importer: OtVaultImporter,
    private val vaultRepository: VaultRepository,
    private val csvWriter: WorkspaceCsvWriter,
    private val markdownWriter: ProjectMarkdownWriter,
    private val dashboardWriter: ExecutiveDashboardHtmlWriter,
    private val insightsTimeProvider: InsightsTimeProvider,
    private val dashboardRuntime: DashboardTransferRuntime,
) : ViewModel() {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        exporter: OtVaultExporter,
        importer: OtVaultImporter,
        vaultRepository: VaultRepository,
        csvWriter: WorkspaceCsvWriter,
        markdownWriter: ProjectMarkdownWriter,
        dashboardWriter: ExecutiveDashboardHtmlWriter,
        insightsTimeProvider: InsightsTimeProvider,
    ) : this(
        context = context,
        exporter = exporter,
        importer = importer,
        vaultRepository = vaultRepository,
        csvWriter = csvWriter,
        markdownWriter = markdownWriter,
        dashboardWriter = dashboardWriter,
        insightsTimeProvider = insightsTimeProvider,
        dashboardRuntime = AndroidDashboardTransferRuntime(context),
    )

    private val context: Context = context
    private val operation = Mutex()

    /** Outlives [viewModelScope] so a staged import is always wiped. */
    private val release = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingPassphrase: CharArray? = null

    private val mutableExportInProgress = MutableStateFlow(false)
    private val mutableExportOutcome = MutableStateFlow<VaultExportOutcome?>(null)
    private val mutableImportInProgress = MutableStateFlow(false)
    private val mutableImportOutcome = MutableStateFlow<VaultImportOutcome?>(null)
    private val mutableCsvExportInProgress = MutableStateFlow(false)
    private val mutableCsvExportOutcome = MutableStateFlow<CsvExportOutcome?>(null)
    private val mutableCsvImportInProgress = MutableStateFlow(false)
    private val mutableCsvImportOutcome = MutableStateFlow<CsvImportOutcome?>(null)
    private val mutableMarkdownExportInProgress = MutableStateFlow(false)
    private val mutableMarkdownExportOutcome = MutableStateFlow<MarkdownExportOutcome?>(null)
    private val mutableDashboardInProgress = MutableStateFlow(false)
    private val mutableDashboardOutcome = MutableStateFlow<DashboardExportOutcome?>(null)

    val exportInProgress: StateFlow<Boolean> = mutableExportInProgress.asStateFlow()
    val exportOutcome: StateFlow<VaultExportOutcome?> = mutableExportOutcome.asStateFlow()
    val importInProgress: StateFlow<Boolean> = mutableImportInProgress.asStateFlow()
    val importOutcome: StateFlow<VaultImportOutcome?> = mutableImportOutcome.asStateFlow()
    val csvExportInProgress: StateFlow<Boolean> = mutableCsvExportInProgress.asStateFlow()
    val csvExportOutcome: StateFlow<CsvExportOutcome?> = mutableCsvExportOutcome.asStateFlow()
    val csvImportInProgress: StateFlow<Boolean> = mutableCsvImportInProgress.asStateFlow()
    val csvImportOutcome: StateFlow<CsvImportOutcome?> = mutableCsvImportOutcome.asStateFlow()
    val markdownExportInProgress: StateFlow<Boolean> = mutableMarkdownExportInProgress.asStateFlow()
    val markdownExportOutcome: StateFlow<MarkdownExportOutcome?> =
        mutableMarkdownExportOutcome.asStateFlow()
    val dashboardInProgress: StateFlow<Boolean> = mutableDashboardInProgress.asStateFlow()
    val dashboardOutcome: StateFlow<DashboardExportOutcome?> = mutableDashboardOutcome.asStateFlow()

    /** A confirmed passphrase is ready; the caller launches the SAF create-document picker. */
    val createDocumentRequests = Channel<Unit>(Channel.BUFFERED)

    /** A confirmed passphrase is ready; the caller launches the SAF open-document picker. */
    val openDocumentRequests = Channel<Unit>(Channel.BUFFERED)

    /** The next table to export; the caller launches a `text/csv` create-document picker for it. */
    val csvCreateDocumentRequests = Channel<CsvTable>(Channel.BUFFERED)

    /** Requests a dedicated picker for an Open Tasks Tasks CSV. */
    val csvOpenDocumentRequests = Channel<Unit>(Channel.BUFFERED)

    /** Parsed rows awaiting normal WorkspaceViewModel command execution. */
    val csvImportCommitRequests = Channel<List<ImportedTaskRow>>(capacity = 1)

    /** The suggested filename for the selected project's Markdown document. */
    val markdownCreateDocumentRequests = Channel<String>(Channel.BUFFERED)

    /** The stable local-date filename for a dashboard SAF destination. */
    val dashboardCreateDocumentRequests = Channel<String>(Channel.BUFFERED)

    /** A completed FileProvider handoff ready for the Android share sheet. */
    val dashboardShareRequests = Channel<Intent>(Channel.BUFFERED)

    /** Tables still to be asked for after the one currently in flight. */
    private val pendingCsvTables = ArrayDeque<CsvTable>()
    private var csvCompletedCount = 0

    /**
     * Captured once when a batch starts, so every table it writes reflects
     * the same moment rather than whatever the live vault holds by the time
     * each document's picker happens to return.
     */
    private var csvSnapshot: WorkspaceSnapshot? = null
    private var csvImportRows: List<ImportedTaskRow>? = null
    private var csvImportState = CsvImportState.IDLE
    private var csvImportOwnsOperation = false
    private var markdownSnapshot: WorkspaceSnapshot? = null
    private var markdownProjectId: ProjectId? = null
    private var pendingDashboardRequest: ExecutiveDashboardRequest? = null
    private var dashboardState = DashboardState.IDLE
    private var dashboardOwnsOperation = false

    /** Holds a validated passphrase and asks the caller to pick a destination for it. */
    fun beginExport(passphrase: String) = beginTransfer(passphrase, createDocumentRequests)

    /** Holds the archive passphrase and asks the caller to pick the archive to read. */
    fun beginImport(passphrase: String) = beginTransfer(passphrase, openDocumentRequests)

    private fun beginTransfer(passphrase: String, requests: Channel<Unit>) {
        if (!operation.tryLock()) return
        pendingPassphrase = passphrase.toCharArray()
        if (!requests.trySend(Unit).isSuccess) {
            releasePendingPassphrase()
            operation.unlock()
        }
    }

    /** The SAF picker returned [uri], or null if the person cancelled it. */
    fun onExportDocumentSelected(uri: Uri?) {
        val (document, passphrase) = takePendingTransfer(uri) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            mutableExportInProgress.value = true
            var result: OtVaultExportResult? = null
            try {
                result = try {
                    context.contentResolver.openOutputStream(document)?.use { stream ->
                        exporter.export(stream, passphrase)
                    } ?: OtVaultExportResult.Failed(OT_VAULT_EXPORT_FAILED_REASON)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    OtVaultExportResult.Failed(OT_VAULT_EXPORT_FAILED_REASON)
                } finally {
                    // `export` wipes its own copy, but a document that yields no
                    // stream at all — revoked or deleted between the pick and
                    // here — never reaches it. Wiping twice is harmless.
                    passphrase.fill('\u0000')
                }
            } finally {
                // Cancellation (this ViewModel cleared mid-export) must not
                // orphan the document the person just chose: unlocking,
                // clearing the progress flag, and deleting anything short of
                // a verified success all run to completion even though the
                // job that started them is itself cancelled.
                withContext(NonCancellable) {
                    operation.unlock()
                    mutableExportInProgress.value = false
                    if (result !is OtVaultExportResult.Completed) {
                        deletePartialDocument(document)
                    }
                }
            }
            mutableExportOutcome.value = when (val outcome = checkNotNull(result)) {
                is OtVaultExportResult.Completed -> VaultExportOutcome.Completed(
                    byteCount = outcome.byteCount,
                    attachmentCount = outcome.attachmentCount,
                )

                is OtVaultExportResult.MissingAttachmentBytes ->
                    VaultExportOutcome.MissingAttachmentBytes(outcome.displayNames)

                is OtVaultExportResult.Failed -> VaultExportOutcome.Failed(outcome.reason)
            }
        }
    }

    /**
     * Stages the archive the person chose. Nothing is replaced here: the
     * preview this publishes is what they confirm or dismiss.
     */
    fun onImportDocumentSelected(uri: Uri?) {
        val (document, passphrase) = takePendingTransfer(uri) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            mutableImportInProgress.value = true
            val preview = try {
                try {
                    context.contentResolver.openInputStream(document)?.use { stream ->
                        importer.stage(stream, passphrase)
                    } ?: OtVaultImportPreview.Rejected(OT_VAULT_IMPORT_FAILED_REASON)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    OtVaultImportPreview.Rejected(OT_VAULT_IMPORT_FAILED_REASON)
                } finally {
                    // `stage` wipes its own copy, but a document that yields no
                    // stream at all — revoked or deleted between the pick and
                    // here — never reaches it. Wiping twice is harmless.
                    passphrase.fill('\u0000')
                }
            } finally {
                // A cleared ViewModel must still release the lock and drop the
                // progress flag; the staged archive itself is released below.
                withContext(NonCancellable) {
                    operation.unlock()
                    mutableImportInProgress.value = false
                }
            }
            mutableImportOutcome.value = when (preview) {
                is OtVaultImportPreview.Ready -> VaultImportOutcome.Ready(
                    recordCount = preview.recordCount,
                    attachmentCount = preview.attachmentCount,
                    attachmentsBeyondCache = preview.attachmentsBeyondCache,
                )

                is OtVaultImportPreview.Rejected -> VaultImportOutcome.Failed(preview.reason)
            }
        }
    }

    /** Replaces this device's vault with the staged archive. */
    fun confirmImport() {
        if (mutableImportOutcome.value !is VaultImportOutcome.Ready) return
        if (!operation.tryLock()) return
        viewModelScope.launch(Dispatchers.IO) {
            mutableImportInProgress.value = true
            val replaced = try {
                importer.activate()
            } finally {
                withContext(NonCancellable) {
                    operation.unlock()
                    mutableImportInProgress.value = false
                }
            }
            mutableImportOutcome.value = if (replaced) {
                VaultImportOutcome.Completed
            } else {
                VaultImportOutcome.Failed(OT_VAULT_IMPORT_ACTIVATION_FAILED_REASON)
            }
        }
    }

    /**
     * Clears the import surface, discarding a staged archive the person chose
     * not to confirm. An outcome that is no longer awaiting confirmation has
     * nothing staged behind it, so dismissing it only clears the message; a
     * transfer already in flight owns the staging and resolves it itself.
     */
    fun dismissImport() {
        val awaitingConfirmation = mutableImportOutcome.value is VaultImportOutcome.Ready
        mutableImportOutcome.value = null
        if (awaitingConfirmation) releaseStagedImport()
    }

    fun dismissOutcome() {
        mutableExportOutcome.value = null
    }

    fun beginCsvImport() {
        if (csvImportState != CsvImportState.IDLE || !operation.tryLock()) return
        csvImportOwnsOperation = true
        csvImportState = CsvImportState.AWAITING_DOCUMENT
        mutableCsvImportOutcome.value = null
        mutableCsvImportInProgress.value = true
        if (!csvOpenDocumentRequests.trySend(Unit).isSuccess) {
            mutableCsvImportOutcome.value = CsvImportOutcome.Failed(
                rowNumber = null,
                reason = CSV_IMPORT_FAILED_REASON,
            )
            finishCsvImport()
        }
    }

    /** The dedicated Tasks CSV picker returned [uri], or null on cancellation. */
    fun onCsvDocumentSelected(uri: Uri?) {
        if (csvImportState != CsvImportState.AWAITING_DOCUMENT) return
        if (uri == null) {
            finishCsvImport()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var bytes: ByteArray? = null
            try {
                val result = try {
                    bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readNBytes(MAX_TASKS_CSV_BYTES + 1)
                    }
                    bytes?.let(::parseTasksCsv)
                        ?: CsvParseResult.Malformed(0, CSV_IMPORT_FAILED_REASON)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    CsvParseResult.Malformed(0, CSV_IMPORT_FAILED_REASON)
                }
                handleCsvParseResult(result)
            } finally {
                bytes?.fill(0)
            }
        }
    }

    internal fun handleCsvParseResult(result: CsvParseResult) {
        if (csvImportState != CsvImportState.AWAITING_DOCUMENT) return
        when (result) {
            is CsvParseResult.Malformed -> {
                mutableCsvImportOutcome.value = CsvImportOutcome.Failed(
                    rowNumber = result.rowNumber.takeIf { it > 0 },
                    reason = result.reason,
                )
                finishCsvImport()
            }
            is CsvParseResult.Parsed -> when (
                val preview = previewTasksImport(
                    result.rows,
                    vaultRepository.observeWorkspace().value,
                )
            ) {
                is CsvImportPreviewResult.Invalid -> {
                    mutableCsvImportOutcome.value = CsvImportOutcome.Failed(
                        rowNumber = preview.rowNumber,
                        reason = preview.message,
                    )
                    finishCsvImport()
                }
                is CsvImportPreviewResult.Ready -> {
                    csvImportRows = result.rows
                    csvImportState = CsvImportState.PREVIEW
                    mutableCsvImportOutcome.value = CsvImportOutcome.Preview(
                        taskCount = preview.summary.taskCount,
                        newProjectCount = preview.summary.newProjectCount,
                        newTagCount = preview.summary.newTagCount,
                    )
                }
            }
        }
    }

    fun confirmCsvImport() {
        if (csvImportState != CsvImportState.PREVIEW) return
        val rows = csvImportRows ?: return
        csvImportState = CsvImportState.COMMITTING
        if (!csvImportCommitRequests.trySend(rows).isSuccess) {
            mutableCsvImportOutcome.value = CsvImportOutcome.Failed(
                rowNumber = null,
                reason = CSV_IMPORT_FAILED_REASON,
            )
            finishCsvImport()
        } else {
            mutableCsvImportOutcome.value = null
        }
    }

    fun onCsvImportCommandResult(result: CommandResult) {
        if (csvImportState != CsvImportState.COMMITTING) return
        val taskCount = csvImportRows?.size ?: 0
        mutableCsvImportOutcome.value = when (result) {
            is CommandResult.Success -> CsvImportOutcome.Completed(taskCount)
            is CommandResult.Rejected -> CsvImportOutcome.Failed(null, result.message)
        }
        finishCsvImport()
    }

    fun cancelCsvImport() {
        if (csvImportState == CsvImportState.COMMITTING) return
        mutableCsvImportOutcome.value = null
        finishCsvImport()
    }

    fun dismissCsvImportOutcome() {
        if (csvImportState == CsvImportState.COMMITTING) return
        mutableCsvImportOutcome.value = null
        finishCsvImport()
    }

    private fun finishCsvImport() {
        csvImportRows = null
        csvImportState = CsvImportState.IDLE
        mutableCsvImportInProgress.value = false
        if (csvImportOwnsOperation) {
            csvImportOwnsOperation = false
            operation.unlock()
        }
    }

    /** Starts a plaintext CSV export of [tables], one document per table. */
    fun beginCsvExport(tables: Set<CsvExportTable>) {
        if (tables.isEmpty() || !operation.tryLock()) return
        pendingCsvTables.clear()
        pendingCsvTables.addAll(tables.map(::toCsvTable))
        // A plain StateFlow read, not the suspending currentWorkspace(): capturing
        // the snapshot here keeps beginCsvExport itself synchronous, so there is
        // no async gap in which a cleared ViewModel could strand the lock this
        // function just took.
        csvSnapshot = vaultRepository.observeWorkspace().value
        csvCompletedCount = 0
        mutableCsvExportInProgress.value = true
        requestNextCsvDocument()
    }

    /**
     * The SAF picker returned [uri] for the table currently at the front of
     * [pendingCsvTables], or null if the person cancelled it. A cancellation
     * ends the whole batch where it stands: tables already written keep
     * their documents, and nothing further is requested.
     */
    fun onCsvExportDocumentSelected(uri: Uri?) {
        val table = pendingCsvTables.removeFirstOrNull() ?: return
        if (uri == null) {
            val completed = csvCompletedCount
            abortCsvExport(if (completed > 0) CsvExportOutcome.Completed(completed) else null)
            return
        }
        val snapshot = checkNotNull(csvSnapshot) {
            "beginCsvExport must capture a snapshot before requesting any document"
        }
        viewModelScope.launch(Dispatchers.IO) {
            var success = false
            var cancelled = false
            try {
                val stream = context.contentResolver.openOutputStream(uri)
                if (stream != null) {
                    // Closing the writer closes the stream it wraps, so this
                    // is the one and only close — nesting a second `use` on
                    // `stream` itself would close it twice.
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        csvWriter.write(table, snapshot, writer)
                    }
                    success = true
                }
            } catch (cancellation: CancellationException) {
                cancelled = true
                throw cancellation
            } catch (_: Exception) {
                success = false
            } finally {
                // Cancellation (this ViewModel cleared mid-write) must not
                // orphan the document the person just chose, nor strand the
                // shared operation lock: this cleanup runs to completion even
                // though the job that started it is itself cancelled, exactly
                // as the whole-vault export above. A cancellation releases
                // the lock here even with tables still pending — no further
                // document will ever be requested — where an ordinary
                // mid-batch success or failure defers release to whichever
                // finally call finds the queue empty.
                withContext(NonCancellable) {
                    if (!success) {
                        deletePartialDocument(uri)
                        pendingCsvTables.clear()
                    }
                    if (cancelled || pendingCsvTables.isEmpty()) {
                        operation.unlock()
                        mutableCsvExportInProgress.value = false
                    }
                }
            }
            if (success) csvCompletedCount++
            if (pendingCsvTables.isEmpty()) {
                val completed = csvCompletedCount
                csvCompletedCount = 0
                csvSnapshot = null
                mutableCsvExportOutcome.value = if (success) {
                    CsvExportOutcome.Completed(completed)
                } else {
                    CsvExportOutcome.Failed(CSV_EXPORT_FAILED_REASON)
                }
            } else {
                requestNextCsvDocument()
            }
        }
    }

    fun dismissCsvExportOutcome() {
        mutableCsvExportOutcome.value = null
    }

    /** Captures one project and asks SAF for its Markdown document. */
    fun beginMarkdownExport(projectId: ProjectId) {
        if (!operation.tryLock()) return
        val snapshot = vaultRepository.observeWorkspace().value
        val project = snapshot.projects.firstOrNull { it.id == projectId }
        if (project == null) {
            operation.unlock()
            return
        }
        markdownSnapshot = snapshot
        markdownProjectId = projectId
        mutableMarkdownExportInProgress.value = true
        if (!markdownCreateDocumentRequests.trySend(markdownFileName(project.name)).isSuccess) {
            markdownSnapshot = null
            markdownProjectId = null
            operation.unlock()
            mutableMarkdownExportInProgress.value = false
            mutableMarkdownExportOutcome.value = MarkdownExportOutcome.Failed(MARKDOWN_EXPORT_FAILED_REASON)
        }
    }

    /** The SAF picker returned [uri], or null when the person cancelled it. */
    fun onMarkdownDocumentSelected(uri: Uri?) {
        val snapshot = markdownSnapshot ?: return
        val projectId = markdownProjectId ?: return
        markdownSnapshot = null
        markdownProjectId = null
        if (uri == null) {
            operation.unlock()
            mutableMarkdownExportInProgress.value = false
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var success = false
            try {
                val stream = context.contentResolver.openOutputStream(uri)
                if (stream != null) {
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        markdownWriter.write(projectId, snapshot, writer)
                    }
                    success = true
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                success = false
            } finally {
                withContext(NonCancellable) {
                    if (!success) deletePartialDocument(uri)
                    operation.unlock()
                    mutableMarkdownExportInProgress.value = false
                }
            }
            mutableMarkdownExportOutcome.value = if (success) {
                MarkdownExportOutcome.Completed
            } else {
                MarkdownExportOutcome.Failed(MARKDOWN_EXPORT_FAILED_REASON)
            }
        }
    }

    fun dismissMarkdownExportOutcome() {
        mutableMarkdownExportOutcome.value = null
    }

    /** Freezes one dashboard request before asking SAF for its destination. */
    fun beginDashboardDownload(
        selection: InsightsSelection,
        includeTaskDetails: Boolean,
    ) {
        if (!operation.tryLock()) return
        dashboardOwnsOperation = true
        val request = captureDashboardRequest(selection, includeTaskDetails)
        pendingDashboardRequest = request
        dashboardState = DashboardState.AWAITING_DOCUMENT
        mutableDashboardOutcome.value = null
        mutableDashboardInProgress.value = true
        if (!dashboardCreateDocumentRequests.trySend(dashboardFileName(request)).isSuccess) {
            mutableDashboardOutcome.value = dashboardFailure()
            finishDashboard()
        }
    }

    /** The dashboard create-document picker returned [uri], or null on cancellation. */
    fun onDashboardDocumentSelected(uri: Uri?) {
        if (dashboardState != DashboardState.AWAITING_DOCUMENT) return
        if (uri == null) {
            finishDashboard()
            return
        }
        val destination = try {
            dashboardRuntime.document(uri)
        } catch (_: Exception) {
            deletePartialDocument(uri)
            mutableDashboardOutcome.value = dashboardFailure()
            finishDashboard()
            return
        }
        onDashboardDestinationSelected(destination)
    }

    /** Stream seam used by the SAF callback above and host-side state-machine tests. */
    internal fun onDashboardDestinationSelected(destination: DashboardDocumentDestination?) {
        if (dashboardState != DashboardState.AWAITING_DOCUMENT) return
        if (destination == null) {
            finishDashboard()
            return
        }
        val request = pendingDashboardRequest ?: return
        pendingDashboardRequest = null
        dashboardState = DashboardState.WRITING
        viewModelScope.launch(Dispatchers.IO) {
            var outcome: DashboardExportOutcome = dashboardFailure()
            var success = false
            try {
                val byteCount = destination.open()?.use { output ->
                    dashboardWriter.write(request, output)
                } ?: throw IOException("Dashboard destination is unavailable")
                outcome = DashboardExportOutcome.Completed(byteCount)
                success = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                outcome = dashboardFailure()
            } finally {
                withContext(NonCancellable) {
                    if (!success) {
                        try {
                            destination.delete()
                        } catch (_: Exception) {
                            // The provider may already have removed the partial document.
                        }
                    }
                    finishDashboard()
                }
            }
            mutableDashboardOutcome.value = outcome
        }
    }

    /** Generates a frozen dashboard in the one FileProvider-visible report directory. */
    fun shareDashboard(
        selection: InsightsSelection,
        includeTaskDetails: Boolean,
    ) {
        if (!operation.tryLock()) return
        dashboardOwnsOperation = true
        val request = captureDashboardRequest(selection, includeTaskDetails)
        dashboardState = DashboardState.WRITING
        mutableDashboardOutcome.value = null
        mutableDashboardInProgress.value = true
        viewModelScope.launch(Dispatchers.IO) {
            var staged: File? = null
            var outcome: DashboardExportOutcome = dashboardFailure()
            var success = false
            try {
                val directory = dashboardRuntime.reportsDirectory
                sweepStagedPlaintext(directory)
                check(directory.mkdirs() || directory.isDirectory) {
                    "Dashboard report directory is unavailable"
                }
                staged = File(directory, dashboardFileName(request))
                val byteCount = FileOutputStream(staged).use { output ->
                    dashboardWriter.write(request, output)
                }
                val shareRequest = DashboardShareRequest(file = staged)
                val intent = dashboardRuntime.shareIntent(shareRequest)
                check(dashboardShareRequests.trySend(intent).isSuccess) {
                    "Dashboard share delivery is unavailable"
                }
                outcome = DashboardExportOutcome.Completed(byteCount)
                success = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                outcome = dashboardFailure()
            } finally {
                withContext(NonCancellable) {
                    if (!success) staged?.delete()
                    finishDashboard()
                }
            }
            mutableDashboardOutcome.value = outcome
        }
    }

    fun dismissDashboardOutcome() {
        mutableDashboardOutcome.value = null
    }

    private fun captureDashboardRequest(
        selection: InsightsSelection,
        includeTaskDetails: Boolean,
    ): ExecutiveDashboardRequest {
        val time = insightsTimeProvider.capture()
        return ExecutiveDashboardRequest(
            workspace = vaultRepository.observeWorkspace().value,
            selection = selection.copy(
                projectIds = selection.projectIds.toSet(),
                tagIds = selection.tagIds.toSet(),
            ),
            now = time.now,
            zoneId = time.zoneId,
            includeTaskDetails = includeTaskDetails,
        )
    }

    private fun dashboardFileName(request: ExecutiveDashboardRequest): String =
        "open_tasks_executive_${request.now.atZone(request.zoneId).toLocalDate()}.html"

    private fun finishDashboard() {
        pendingDashboardRequest = null
        dashboardState = DashboardState.IDLE
        mutableDashboardInProgress.value = false
        if (dashboardOwnsOperation) {
            dashboardOwnsOperation = false
            operation.unlock()
        }
    }

    private fun dashboardFailure(): DashboardExportOutcome =
        DashboardExportOutcome.Failed(DASHBOARD_EXPORT_FAILED_REASON)

    /**
     * Asks the caller for the next pending table's document, or aborts the
     * batch if the request itself cannot be sent (a closed channel — never
     * expected with [Channel.BUFFERED], but left fail-safe rather than
     * fail-silent).
     */
    private fun requestNextCsvDocument() {
        val next = pendingCsvTables.firstOrNull() ?: return
        if (!csvCreateDocumentRequests.trySend(next).isSuccess) {
            abortCsvExport(CsvExportOutcome.Failed(CSV_EXPORT_FAILED_REASON))
        }
    }

    /**
     * Synchronously ends the batch: only ever called outside the write
     * coroutine above (an immediate cancellation, or a request that could
     * not even be sent), so there is no cancellation-safety concern here.
     */
    private fun abortCsvExport(outcome: CsvExportOutcome?) {
        pendingCsvTables.clear()
        csvCompletedCount = 0
        csvSnapshot = null
        operation.unlock()
        mutableCsvExportInProgress.value = false
        if (outcome != null) mutableCsvExportOutcome.value = outcome
    }

    private fun toCsvTable(table: CsvExportTable): CsvTable = when (table) {
        CsvExportTable.TASKS -> CsvTable.TASKS
        CsvExportTable.PROJECTS -> CsvTable.PROJECTS
        CsvExportTable.TIME_ENTRIES -> CsvTable.TIME_ENTRIES
        CsvExportTable.NOTES -> CsvTable.NOTES
    }

    private fun markdownFileName(projectName: String): String {
        val projectPart = projectName
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "project" }
        return ("open_tasks_" + projectPart).take(80) + ".md"
    }

    /**
     * Neither a passphrase awaiting the SAF picker's result nor the unlocked
     * key of a staged archive may outlive this ViewModel.
     */
    override fun onCleared() {
        if (dashboardState == DashboardState.AWAITING_DOCUMENT) finishDashboard()
        finishCsvImport()
        releasePendingPassphrase()
        if (mutableImportOutcome.value is VaultImportOutcome.Ready) releaseStagedImport()
    }

    /**
     * Releases a staged archive on [release], which outlives [viewModelScope]:
     * a staged import must be wiped even when what discarded it was this
     * ViewModel being cleared.
     */
    private fun releaseStagedImport() {
        if (!operation.tryLock()) return
        release.launch {
            try {
                importer.abandon()
            } finally {
                operation.unlock()
            }
        }
    }

    /**
     * Pairs the held passphrase with the document the picker returned,
     * releasing the operation when the person cancelled it instead. Returns
     * null when there is nothing left to do.
     */
    private fun takePendingTransfer(uri: Uri?): Pair<Uri, CharArray>? {
        val passphrase = pendingPassphrase
        pendingPassphrase = null
        if (uri != null && passphrase != null) return uri to passphrase
        passphrase?.fill('\u0000')
        // operation is always held here: beginTransfer locks it before sending
        // the picker request, and this callback is the only code that observes
        // that request's result, so the lock can never belong to anyone else.
        if (operation.isLocked) operation.unlock()
        return null
    }

    private fun releasePendingPassphrase() {
        pendingPassphrase?.fill('\u0000')
        pendingPassphrase = null
    }

    private fun deletePartialDocument(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            // A stray empty or partial document carries no vault content on its own.
        }
    }
}

/**
 * The one generic UK failure copy a CSV table write failure surfaces. Like
 * [OT_VAULT_EXPORT_FAILED_REASON], it never distinguishes causes, so it never
 * risks leaking one.
 */
private const val CSV_EXPORT_FAILED_REASON = "The CSV export could not be completed."
private const val CSV_IMPORT_FAILED_REASON = "The CSV import could not be completed."
private const val MARKDOWN_EXPORT_FAILED_REASON = "The Markdown export could not be completed."
private const val DASHBOARD_EXPORT_FAILED_REASON = "The dashboard could not be generated."
private const val DASHBOARD_MIME_TYPE = "text/html"
private const val DASHBOARD_SHARE_DIRECTORY = "share/reports"

private enum class CsvImportState { IDLE, AWAITING_DOCUMENT, PREVIEW, COMMITTING }
private enum class DashboardState { IDLE, AWAITING_DOCUMENT, WRITING }

internal class DashboardDocumentDestination(
    val open: () -> OutputStream?,
    val delete: () -> Unit,
)

internal data class DashboardShareRequest(
    val file: File,
    val action: String = Intent.ACTION_SEND,
    val mimeType: String = DASHBOARD_MIME_TYPE,
    val grantReadPermission: Boolean = true,
    val includeClipData: Boolean = true,
)

internal interface DashboardTransferRuntime {
    val reportsDirectory: File

    fun document(uri: Uri): DashboardDocumentDestination

    fun shareIntent(request: DashboardShareRequest): Intent
}

private class AndroidDashboardTransferRuntime(
    private val context: Context,
) : DashboardTransferRuntime {
    override val reportsDirectory = File(context.cacheDir, DASHBOARD_SHARE_DIRECTORY)

    override fun document(uri: Uri) = DashboardDocumentDestination(
        open = { context.contentResolver.openOutputStream(uri) },
        delete = {
            try {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (_: Exception) {
                // The provider may already have removed the partial document.
            }
        },
    )

    override fun shareIntent(request: DashboardShareRequest): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            request.file,
        )
        val intent = Intent(request.action)
            .setType(request.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
        if (request.includeClipData) intent.clipData = ClipData.newRawUri("", uri)
        if (request.grantReadPermission) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, null)
    }
}
