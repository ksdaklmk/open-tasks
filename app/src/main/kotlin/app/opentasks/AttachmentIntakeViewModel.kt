package app.opentasks

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentasks.core.data.backup.AttachmentIntakeResult
import app.opentasks.core.data.backup.AttachmentOpenResult
import app.opentasks.core.data.backup.AttachmentRuntime
import app.opentasks.core.data.backup.AttachmentSource
import app.opentasks.core.domain.LifecycleResult
import app.opentasks.core.domain.RemoteBackupLifecycleCoordinator
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupStatus
import app.opentasks.core.model.TaskId
import app.opentasks.feature.tasks.AttachmentRowState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The attachment side of the product surface: picking, opening, and sharing.
 *
 * Picked content is untrusted. Its name, type, and length are read from the
 * provider, capped here, and sanitised again in the coordinator, and only the
 * transient read grant the picker hands over is used — nothing is ever taken
 * persistably, so a document this app was shown once cannot be re-read later.
 *
 * Row state is per record and never claims more than the operation proved: an
 * operation this vault may not perform reports the neutral unavailable state
 * rather than asserting what became of the backup.
 */
@HiltViewModel
class AttachmentIntakeViewModel internal constructor(
    private val context: Context,
    private val runtime: AttachmentRuntime,
    private val lifecycleCoordinator: RemoteBackupLifecycleCoordinator,
    remoteStatus: StateFlow<RemoteBackupStatus>,
) : ViewModel() {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        services: ActiveVaultServices,
    ) : this(
        context = context,
        runtime = services.requireSession().attachmentRuntime,
        lifecycleCoordinator = services.requireSession().remoteBackupLifecycleCoordinator,
        remoteStatus = services.requireSession().remoteBackupStatus,
    )

    private val resolver: ContentResolver = context.contentResolver
    private val cacheRoot = File(context.cacheDir, SHARE_DIRECTORY)
    private val fileProviderAuthority = "${context.packageName}.files"
    private val defaultDisplayName = context.getString(R.string.attachment_default_name)
    private val mutableStates = MutableStateFlow<Map<AttachmentId, AttachmentRowState>>(emptyMap())
    private val mutableCacheUsage = MutableStateFlow(0L)

    val rowStates: StateFlow<Map<AttachmentId, AttachmentRowState>> = mutableStates.asStateFlow()
    val cacheUsageBytes: StateFlow<Long> = mutableCacheUsage.asStateFlow()

    /**
     * Whether attachments have nowhere to live yet.
     *
     * Only the absence of a lineage counts. A configured backup that is
     * failing, waiting, or no longer owned is not a setup problem, and sending
     * someone back to setup for one would invite a second, competing lineage.
     */
    val setupRequired: StateFlow<Boolean> = remoteStatus
        .map(::needsSetup)
        .stateIn(viewModelScope, SharingStarted.Eagerly, needsSetup(remoteStatus.value))

    /** Ready plaintext, handed to the platform by the surface that can start it. */
    val deliveries = Channel<Intent>(Channel.BUFFERED)

    /** Bounded, resource-identified outcomes; never the content or its name. */
    val messages = Channel<Int>(Channel.BUFFERED)

    init {
        refreshCacheUsage()
    }

    fun addFromUri(taskId: TaskId, uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val picked = describe(uri)
            if (picked == null) {
                messages.send(R.string.attachment_source_unavailable)
                return@launch
            }
            messages.send(R.string.attachment_adding)
            val result = runtime.intake(
                taskId = taskId,
                displayName = picked.displayName,
                mimeType = picked.mimeType,
                source = ContentUriSource(resolver, uri, picked.byteCount),
            )
            messages.send(result.messageRes())
            refreshCacheUsage()
        }
    }

    fun open(attachment: Attachment) = deliver(attachment, share = false)

    fun share(attachment: Attachment) = deliver(attachment, share = true)

    /** Re-attempts the operation that left a row failed or unavailable. */
    fun retry(attachment: Attachment) {
        clear(attachment.id)
        deliver(attachment, share = false)
    }

    /**
     * Removes every attachment's bytes from the lineage, passphrase-guarded.
     *
     * The records stay: a person keeps knowing what was attached even after
     * deciding the content itself should no longer exist anywhere. What must
     * not stay is the plaintext: asking for the content to be gone and being
     * left with a decrypted copy of everything ever opened would break the
     * promise the action makes, so the staging root goes with it.
     */
    fun deleteRemoteContent(passphrase: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val secret = passphrase.toCharArray()
            val result = try {
                lifecycleCoordinator.deleteAttachmentContent(secret)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                LifecycleResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
            } finally {
                secret.fill('\u0000')
            }
            if (result == LifecycleResult.AttachmentContentDeleted) discardStagedPlaintext()
            mutableStates.value = emptyMap()
            messages.send(
                if (result == LifecycleResult.AttachmentContentDeleted) {
                    R.string.attachment_content_deleted
                } else {
                    R.string.attachment_content_delete_refused
                },
            )
            refreshCacheUsage()
        }
    }

    /**
     * Drops the decrypted copies this installation handed to other apps.
     *
     * The vault's premise is that attachment content is encrypted at rest, so
     * plaintext exists only for the length of a handoff. Clearing on teardown
     * bounds that to one session; by then the receiving app has been started
     * and has read what it was given.
     */
    override fun onCleared() {
        discardStagedPlaintext()
    }

    private fun discardStagedPlaintext() {
        try {
            cacheRoot.deleteRecursively()
        } catch (_: Exception) {
            // A later open or teardown clears what this attempt could not.
        }
    }

    private fun deliver(attachment: Attachment, share: Boolean) {
        if (attachment.deletedAt != null) return
        viewModelScope.launch(Dispatchers.IO) {
            mark(attachment.id, AttachmentRowState.DOWNLOADING)
            val staged = staged(attachment)
            if (staged == null) {
                mark(attachment.id, AttachmentRowState.FAILED)
                messages.send(R.string.attachment_download_failed)
                return@launch
            }
            val result = try {
                FileOutputStream(staged).use { runtime.open(attachment, it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                null
            }
            refreshCacheUsage()
            when (result) {
                is AttachmentOpenResult.Opened -> {
                    clear(attachment.id)
                    deliveries.send(intentFor(staged, attachment.mimeType, share))
                }
                AttachmentOpenResult.Unavailable -> {
                    staged.delete()
                    mark(attachment.id, AttachmentRowState.UNAVAILABLE)
                    messages.send(R.string.attachment_content_missing)
                }
                is AttachmentOpenResult.Failed -> {
                    staged.delete()
                    mark(attachment.id, AttachmentRowState.FAILED)
                    messages.send(result.reason.messageRes())
                }
                null -> {
                    staged.delete()
                    mark(attachment.id, AttachmentRowState.FAILED)
                    messages.send(R.string.attachment_download_failed)
                }
            }
        }
    }

    private fun intentFor(file: File, mimeType: String, share: Boolean): Intent {
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority, file)
        val base = if (share) {
            Intent(Intent.ACTION_SEND)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
        }
        base.clipData = ClipData.newRawUri("", uri)
        base.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (share) Intent.createChooser(base, null) else base
    }

    private fun mark(id: AttachmentId, state: AttachmentRowState) {
        mutableStates.value = mutableStates.value + (id to state)
    }

    private fun clear(id: AttachmentId) {
        mutableStates.value = mutableStates.value - id
    }

    /**
     * Every on-device byte of attachment content, from both roots that hold
     * any.
     *
     * `core:data` owns the encrypted-frame cache and knows nothing of the
     * staging root, which exists only because a FileProvider handoff needs a
     * real file — an app-layer concern. Summing here keeps that boundary
     * intact while still reporting one honest figure: a number that counted
     * only the encrypted frames would understate what is actually on the
     * device, and understating it is the direction that misleads.
     */
    private fun refreshCacheUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            mutableCacheUsage.value = try {
                runtime.cacheUsageBytes() + stagedPlaintextBytes()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                0L
            }
        }
    }

    private fun stagedPlaintextBytes(): Long = try {
        cacheRoot.walkTopDown().filter(File::isFile).sumOf(File::length)
    } catch (_: Exception) {
        0L
    }

    /**
     * A private staging file for one attachment's plaintext.
     *
     * The provider-visible directory is emptied first, so the only plaintext
     * this device offers another app is the copy just asked for.
     */
    private fun staged(attachment: Attachment): File? = try {
        val directory = File(cacheRoot, safeName(attachment.id.value))
        directory.deleteRecursively()
        directory.mkdirs()
        File(directory, safeName(attachment.displayName))
    } catch (_: Exception) {
        null
    }

    private fun describe(uri: Uri): PickedContent? {
        val mimeType = try {
            resolver.getType(uri)
        } catch (_: Exception) {
            null
        } ?: DEFAULT_MIME_TYPE
        var name: String? = null
        var byteCount = UNKNOWN_LENGTH
        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        name = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        byteCount = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        if (byteCount <= 0) {
            byteCount = try {
                resolver.openAssetFileDescriptor(uri, "r")?.use(AssetFileDescriptor::getLength)
                    ?: UNKNOWN_LENGTH
            } catch (_: Exception) {
                UNKNOWN_LENGTH
            }
        }
        if (byteCount <= 0 || byteCount == AssetFileDescriptor.UNKNOWN_LENGTH) return null
        return PickedContent(
            displayName = name
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.take(MAX_DISPLAY_NAME_LENGTH)
                ?: defaultDisplayName,
            mimeType = mimeType.take(MAX_MIME_TYPE_LENGTH),
            byteCount = byteCount,
        )
    }

    private data class PickedContent(
        val displayName: String,
        val mimeType: String,
        val byteCount: Long,
    )

    private class ContentUriSource(
        private val resolver: ContentResolver,
        private val uri: Uri,
        override val declaredByteCount: Long,
    ) : AttachmentSource {
        override fun open(): InputStream =
            resolver.openInputStream(uri) ?: throw IOException("The picked content is unreadable")
    }

    private companion object {
        const val SHARE_DIRECTORY = "share/attachments"
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val MAX_DISPLAY_NAME_LENGTH = 255
        const val MAX_MIME_TYPE_LENGTH = 255
        const val UNKNOWN_LENGTH = -1L
    }
}

private fun needsSetup(status: RemoteBackupStatus): Boolean =
    status is RemoteBackupStatus.Disabled || status is RemoteBackupStatus.Terminated

@StringRes
private fun AttachmentIntakeResult.messageRes(): Int = when (this) {
    is AttachmentIntakeResult.Registered -> R.string.attachment_added
    AttachmentIntakeResult.SourceUnavailable -> R.string.attachment_source_unavailable
    AttachmentIntakeResult.TooLarge -> R.string.attachment_too_large
    AttachmentIntakeResult.OwnershipUnavailable -> R.string.attachment_unavailable
    is AttachmentIntakeResult.Failed -> reason.messageRes()
}

@StringRes
private fun RemoteBackupFailureCategory.messageRes(): Int = when (this) {
    RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
    RemoteBackupFailureCategory.ACCOUNT_MISMATCH,
    -> R.string.attachment_authorisation_required
    RemoteBackupFailureCategory.PROVIDER_STORAGE -> R.string.attachment_storage_full
    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE -> R.string.attachment_damaged
    else -> R.string.attachment_unavailable
}

/** A provider-supplied name reduced to something safe to put on this disk. */
private fun safeName(value: String): String = value
    .map { character ->
        if (character.isLetterOrDigit() || character == '.' || character == '-') character else '_'
    }
    .joinToString(separator = "")
    .trim('.', '_')
    .ifEmpty { "attachment" }
    .take(MAX_STAGED_NAME_LENGTH)

private const val MAX_STAGED_NAME_LENGTH = 120
