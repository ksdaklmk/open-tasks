package app.opentasks

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentasks.backup.OT_VAULT_EXPORT_FAILED_REASON
import app.opentasks.backup.OtVaultExportResult
import app.opentasks.backup.OtVaultExporter
import app.opentasks.feature.more.VaultExportOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Owns the whole-vault export flow.
 *
 * A confirmed passphrase asks the caller to launch the SAF create-document
 * picker; once a destination is chosen, the export streams straight into
 * that document's own [android.content.ContentResolver] output stream — no
 * plaintext archive is ever staged anywhere on this device. Any result other
 * than success deletes that (empty or partial) document immediately, so a
 * cancelled or failed export leaves nothing behind.
 */
@HiltViewModel
class VaultTransferViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val exporter: OtVaultExporter,
) : ViewModel() {
    private val context: Context = context
    private val operation = Mutex()
    private var pendingPassphrase: CharArray? = null

    private val mutableExportInProgress = MutableStateFlow(false)
    private val mutableExportOutcome = MutableStateFlow<VaultExportOutcome?>(null)

    val exportInProgress: StateFlow<Boolean> = mutableExportInProgress.asStateFlow()
    val exportOutcome: StateFlow<VaultExportOutcome?> = mutableExportOutcome.asStateFlow()

    /** A confirmed passphrase is ready; the caller launches the SAF create-document picker. */
    val createDocumentRequests = Channel<Unit>(Channel.BUFFERED)

    /** Holds a validated passphrase and asks the caller to pick a destination for it. */
    fun beginExport(passphrase: String) {
        if (!operation.tryLock()) return
        pendingPassphrase = passphrase.toCharArray()
        if (!createDocumentRequests.trySend(Unit).isSuccess) {
            releasePendingPassphrase()
            operation.unlock()
        }
    }

    /** The SAF picker returned [uri], or null if the person cancelled it. */
    fun onExportDocumentSelected(uri: Uri?) {
        val passphrase = pendingPassphrase
        pendingPassphrase = null
        if (uri == null || passphrase == null) {
            passphrase?.fill('\u0000')
            if (operation.isLocked) operation.unlock()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            mutableExportInProgress.value = true
            var result: OtVaultExportResult? = null
            try {
                result = try {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
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
                        deletePartialDocument(uri)
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

    fun dismissOutcome() {
        mutableExportOutcome.value = null
    }

    /** A passphrase awaiting the SAF picker's result must not outlive this ViewModel. */
    override fun onCleared() {
        releasePendingPassphrase()
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
