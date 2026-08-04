package app.opentasks

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentasks.backup.OT_VAULT_EXPORT_FAILED_REASON
import app.opentasks.backup.OT_VAULT_IMPORT_ACTIVATION_FAILED_REASON
import app.opentasks.backup.OT_VAULT_IMPORT_FAILED_REASON
import app.opentasks.backup.OtVaultExportResult
import app.opentasks.backup.OtVaultExporter
import app.opentasks.backup.OtVaultImportPreview
import app.opentasks.backup.OtVaultImporter
import app.opentasks.feature.more.VaultExportOutcome
import app.opentasks.feature.more.VaultImportOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Owns the whole-vault export and import flows.
 *
 * A confirmed passphrase asks the caller to launch the matching Storage Access
 * Framework picker. An export streams straight into the chosen document's own
 * [android.content.ContentResolver] output stream — no plaintext archive is
 * ever staged anywhere on this device — and any result other than success
 * deletes that (empty or partial) document immediately. An import reads the
 * chosen document straight through the importer, which touches no vault slot
 * until the person confirms the preview it produces.
 */
@HiltViewModel
class VaultTransferViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val exporter: OtVaultExporter,
    private val importer: OtVaultImporter,
) : ViewModel() {
    private val context: Context = context
    private val operation = Mutex()

    /** Outlives [viewModelScope] so a staged import is always wiped. */
    private val release = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingPassphrase: CharArray? = null

    private val mutableExportInProgress = MutableStateFlow(false)
    private val mutableExportOutcome = MutableStateFlow<VaultExportOutcome?>(null)
    private val mutableImportInProgress = MutableStateFlow(false)
    private val mutableImportOutcome = MutableStateFlow<VaultImportOutcome?>(null)

    val exportInProgress: StateFlow<Boolean> = mutableExportInProgress.asStateFlow()
    val exportOutcome: StateFlow<VaultExportOutcome?> = mutableExportOutcome.asStateFlow()
    val importInProgress: StateFlow<Boolean> = mutableImportInProgress.asStateFlow()
    val importOutcome: StateFlow<VaultImportOutcome?> = mutableImportOutcome.asStateFlow()

    /** A confirmed passphrase is ready; the caller launches the SAF create-document picker. */
    val createDocumentRequests = Channel<Unit>(Channel.BUFFERED)

    /** A confirmed passphrase is ready; the caller launches the SAF open-document picker. */
    val openDocumentRequests = Channel<Unit>(Channel.BUFFERED)

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
                    passphrase.fill('\u0000')
                    OtVaultImportPreview.Rejected(OT_VAULT_IMPORT_FAILED_REASON)
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

    /**
     * Neither a passphrase awaiting the SAF picker's result nor the unlocked
     * key of a staged archive may outlive this ViewModel.
     */
    override fun onCleared() {
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
