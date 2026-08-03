package app.opentasks.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import java.text.NumberFormat
import java.util.Locale

/**
 * What one attachment row can currently say about its content.
 *
 * The record is always present; only the bytes come and go. Every value is
 * rendered as text so the state is never carried by colour alone, and no value
 * disables anything the person is editing.
 */
enum class AttachmentRowState { REMOTE, DOWNLOADING, UNAVAILABLE, TOMBSTONED, FAILED }

/**
 * A task's attachments and the two ways to add one.
 *
 * Attachment bytes live in the encrypted cloud backup, so when no backup is
 * configured this offers the one route to that existing setup rather than
 * forking a second one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskAttachmentsSection(
    attachments: List<Attachment>,
    states: Map<AttachmentId, AttachmentRowState>,
    onAddFromPhotos: () -> Unit,
    onAddFromFiles: () -> Unit,
    onOpen: (AttachmentId) -> Unit,
    onShare: (AttachmentId) -> Unit,
    onDelete: (AttachmentId) -> Unit,
    onRetry: (AttachmentId) -> Unit,
    setupRequired: Boolean = false,
    onOpenBackupSetup: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.task_attachments_heading),
            supportingText = pluralStringResource(
                R.plurals.task_attachment_count,
                attachments.size,
                attachments.size,
            ),
        )
        Spacer(Modifier.height(12.dp))
        if (setupRequired) {
            Text(
                stringResource(R.string.attachments_setup_required),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenBackupSetup,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("attachment-setup-backup"),
            ) {
                Text(stringResource(R.string.attachments_setup_action))
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onAddFromPhotos,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("add-attachment-photos"),
                ) {
                    Icon(
                        Icons.Rounded.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.attachment_add_from_photos))
                }
                OutlinedButton(
                    onClick = onAddFromFiles,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("add-attachment-files"),
                ) {
                    Icon(
                        Icons.Rounded.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.attachment_add_from_files))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (attachments.isEmpty()) {
            Text(
                stringResource(R.string.task_attachments_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            attachments.forEach { attachment ->
                AttachmentRow(
                    attachment = attachment,
                    state = attachment.rowState(states),
                    onOpen = onOpen,
                    onShare = onShare,
                    onDelete = onDelete,
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: Attachment,
    state: AttachmentRowState,
    onOpen: (AttachmentId) -> Unit,
    onShare: (AttachmentId) -> Unit,
    onDelete: (AttachmentId) -> Unit,
    onRetry: (AttachmentId) -> Unit,
) {
    val stateLabel = stringResource(state.labelRes())
    val identity = attachment.id.value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("attachment-$identity"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (attachment.mimeType.startsWith("image/")) {
                Icons.Rounded.Image
            } else {
                Icons.AutoMirrored.Rounded.InsertDriveFile
            },
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { stateDescription = stateLabel },
        ) {
            Text(
                attachment.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.attachment_facts,
                    BYTE_FORMAT.format(attachment.byteCount),
                    stateLabel,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            AttachmentRowState.REMOTE -> {
                RowAction(
                    icon = Icons.Rounded.Download,
                    description = stringResource(
                        R.string.attachment_open_action,
                        attachment.displayName,
                    ),
                    tag = "attachment-open-$identity",
                    onClick = { onOpen(attachment.id) },
                )
                RowAction(
                    icon = Icons.Rounded.Share,
                    description = stringResource(
                        R.string.attachment_share_action,
                        attachment.displayName,
                    ),
                    tag = "attachment-share-$identity",
                    onClick = { onShare(attachment.id) },
                )
            }
            AttachmentRowState.UNAVAILABLE, AttachmentRowState.FAILED -> RowAction(
                icon = Icons.Rounded.Refresh,
                description = stringResource(
                    R.string.attachment_retry_action,
                    attachment.displayName,
                ),
                tag = "attachment-retry-$identity",
                onClick = { onRetry(attachment.id) },
            )
            AttachmentRowState.DOWNLOADING, AttachmentRowState.TOMBSTONED -> Unit
        }
        if (state != AttachmentRowState.TOMBSTONED) {
            RowAction(
                icon = Icons.Rounded.Delete,
                description = stringResource(
                    R.string.attachment_delete_action,
                    attachment.displayName,
                ),
                tag = "attachment-delete-$identity",
                onClick = { onDelete(attachment.id) },
            )
        }
    }
}

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tag: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .testTag(tag),
    ) {
        Icon(icon, contentDescription = description)
    }
}

/**
 * The record outranks any transfer state, because it carries facts a transfer
 * cannot overturn.
 *
 * A tombstone is never presented as something to download. Neither is a live
 * record whose blob set is gone: that is how a released content set is
 * durably recorded, so a record still describing an attachment whose bytes no
 * longer exist reads as unavailable — after the destructive action on this
 * device, and on any installation that later recovers those records.
 */
private fun Attachment.rowState(
    states: Map<AttachmentId, AttachmentRowState>,
): AttachmentRowState = when {
    deletedAt != null -> AttachmentRowState.TOMBSTONED
    blobSetId == null -> AttachmentRowState.UNAVAILABLE
    else -> states[id] ?: AttachmentRowState.REMOTE
}

private fun AttachmentRowState.labelRes(): Int = when (this) {
    AttachmentRowState.REMOTE -> R.string.attachment_state_remote
    AttachmentRowState.DOWNLOADING -> R.string.attachment_state_downloading
    AttachmentRowState.UNAVAILABLE -> R.string.attachment_state_unavailable
    AttachmentRowState.TOMBSTONED -> R.string.attachment_state_tombstoned
    AttachmentRowState.FAILED -> R.string.attachment_state_failed
}

private val BYTE_FORMAT: NumberFormat = NumberFormat.getIntegerInstance(Locale.UK)
