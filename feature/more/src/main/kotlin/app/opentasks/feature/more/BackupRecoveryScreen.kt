package app.opentasks.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Sync
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.BackupPackageInfo
import app.opentasks.core.model.BackupUnavailableReason
import app.opentasks.core.model.RecoveryPassphraseValidation
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupStatus
import app.opentasks.core.model.RestoredPackageCondition
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The outcome of one whole-vault export attempt, ready for the transfer
 * section to show. [Failed.reason] is already resolved, generic UK copy —
 * never a resource ID — so it is rendered as-is.
 */
sealed interface VaultExportOutcome {
    data class Completed(val byteCount: Long, val attachmentCount: Int) : VaultExportOutcome

    data class MissingAttachmentBytes(val displayNames: List<String>) : VaultExportOutcome

    data class Failed(val reason: String) : VaultExportOutcome
}

/**
 * Where one whole-vault import has got to.
 *
 * [Ready] is a staged archive awaiting an explicit replacement decision;
 * dismissing it discards that staging. [Failed.reason] is already resolved,
 * generic UK copy — never a resource ID — so it is rendered as-is.
 */
sealed interface VaultImportOutcome {
    data class Ready(
        val recordCount: Int,
        val attachmentCount: Int,
        val attachmentsBeyondCache: List<String>,
    ) : VaultImportOutcome

    data object Completed : VaultImportOutcome

    data class Failed(val reason: String) : VaultImportOutcome
}

/**
 * The four tables a plaintext CSV export can include. Distinct from
 * `:core:data`'s writer-facing `CsvTable` — feature modules depend only on
 * `:core:model` and `:core:designsystem`, so the app layer maps between the
 * two when it dispatches an export.
 */
enum class CsvExportTable { TASKS, PROJECTS, TIME_ENTRIES, NOTES }

/**
 * The outcome of one plaintext CSV export attempt, which may have written
 * more than one table as separate documents. [Failed.reason] is already
 * resolved, generic UK copy — never a resource ID — so it is rendered as-is.
 */
sealed interface CsvExportOutcome {
    data class Completed(val tableCount: Int) : CsvExportOutcome

    data class Failed(val reason: String) : CsvExportOutcome
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRecoveryScreen(
    status: AndroidBackupStatus,
    remoteStatus: RemoteBackupStatus = RemoteBackupStatus.Disabled,
    canBackUpNow: Boolean = false,
    canRestore: Boolean = false,
    canReauthorise: Boolean = false,
    canTakeOver: Boolean = false,
    canPreserveAsNewLineage: Boolean = false,
    canChangePassphrase: Boolean = false,
    canDisconnect: Boolean = false,
    canDeleteHistory: Boolean = false,
    passphraseChangeDisclosureVisible: Boolean = false,
    canReprepareInitialPackage: Boolean = false,
    attachmentCacheUsageBytes: Long = 0L,
    vaultExportInProgress: Boolean = false,
    vaultExportOutcome: VaultExportOutcome? = null,
    vaultImportInProgress: Boolean = false,
    vaultImportOutcome: VaultImportOutcome? = null,
    csvExportInProgress: Boolean = false,
    csvExportOutcome: CsvExportOutcome? = null,
    validatePassphrase: (
        passphrase: String,
        confirmation: String,
    ) -> RecoveryPassphraseValidation,
    onPrepare: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onConnect: () -> Unit = {},
    onBackUpNow: () -> Unit = {},
    onRestore: () -> Unit = {},
    onReauthorise: () -> Unit = {},
    onTakeOver: () -> Unit = {},
    onPreserveAsNewLineage: () -> Unit = {},
    onChangePassphrase: (String, String) -> Unit = { _, _ -> },
    onDisconnect: () -> Unit = {},
    onDeleteHistory: (String) -> Unit = {},
    onDeleteAttachmentContent: (String) -> Unit = {},
    onExportVaultPassphraseConfirmed: (String) -> Unit = {},
    onDismissVaultExportOutcome: () -> Unit = {},
    onImportVaultPassphraseConfirmed: (String) -> Unit = {},
    onConfirmVaultImport: () -> Unit = {},
    onDismissVaultImport: () -> Unit = {},
    onExportCsv: (Set<CsvExportTable>) -> Unit = {},
    onDismissCsvExportOutcome: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPassphraseSheet by remember { mutableStateOf(false) }
    var remoteSecretAction by remember { mutableStateOf<RemoteSecretAction?>(null) }
    var showVaultExportSheet by remember { mutableStateOf(false) }
    var showVaultImportSheet by remember { mutableStateOf(false) }
    var showCsvTableSheet by remember { mutableStateOf(false) }
    var csvDisclosureTables by remember { mutableStateOf<Set<CsvExportTable>?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("backup-screen"),
    ) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 16.dp
        val contentWidth = minOf(maxWidth, 720.dp)
        LazyColumn(
            modifier = Modifier
                .width(contentWidth)
                .fillMaxHeight()
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                top = 8.dp,
                end = horizontalPadding,
                bottom = 112.dp,
            ),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("backup-back"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.backup_recovery_back),
                        )
                    }
                    Text(
                        stringResource(R.string.backup_recovery_title),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.backup_encrypted_heading),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .testTag("encrypted-backup-heading")
                        .semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                EncryptedBackupContent(
                    status = remoteStatus,
                    canBackUpNow = canBackUpNow,
                    canRestore = canRestore,
                    canReauthorise = canReauthorise,
                    canTakeOver = canTakeOver,
                    canPreserveAsNewLineage = canPreserveAsNewLineage,
                    canChangePassphrase = canChangePassphrase,
                    canDisconnect = canDisconnect,
                    canDeleteHistory = canDeleteHistory,
                    passphraseChangeDisclosureVisible = passphraseChangeDisclosureVisible,
                    onConnect = onConnect,
                    onBackUpNow = onBackUpNow,
                    onRestore = onRestore,
                    onReauthorise = onReauthorise,
                    onTakeOver = onTakeOver,
                    onPreserveAsNewLineage = onPreserveAsNewLineage,
                    onChangePassphrase = {
                        remoteSecretAction = RemoteSecretAction.CHANGE
                    },
                    onDisconnect = onDisconnect,
                    onDeleteHistory = {
                        remoteSecretAction = RemoteSecretAction.DELETE
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 24.dp))
                Text(
                    stringResource(R.string.attachments_heading),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .testTag("cloud-attachments-heading")
                        .semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                CloudAttachmentContent(
                    status = remoteStatus,
                    cacheUsageBytes = attachmentCacheUsageBytes,
                    onDeleteContent = {
                        remoteSecretAction = RemoteSecretAction.DELETE_ATTACHMENTS
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 24.dp))
                Text(
                    stringResource(R.string.backup_android_package_heading),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .testTag("android-backup-heading")
                        .semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                BackupStatusContent(
                    status = status,
                    canReprepareInitialPackage = canReprepareInitialPackage,
                    onPrepare = { showPassphraseSheet = true },
                    onRetry = onRetry,
                )
                HorizontalDivider(Modifier.padding(vertical = 24.dp))
                Text(
                    stringResource(R.string.backup_system_settings_heading),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.backup_system_settings_explanation),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onOpenSystemSettings,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("backup-system-settings"),
                ) {
                    Text(stringResource(R.string.backup_system_settings_action))
                }
                HorizontalDivider(Modifier.padding(vertical = 24.dp))
                Text(
                    stringResource(R.string.vault_transfer_heading),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .testTag("vault-transfer-heading")
                        .semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                VaultTransferContent(
                    exportInProgress = vaultExportInProgress,
                    exportOutcome = vaultExportOutcome,
                    importInProgress = vaultImportInProgress,
                    importOutcome = vaultImportOutcome,
                    onExportClick = { showVaultExportSheet = true },
                    onDismissExportOutcome = onDismissVaultExportOutcome,
                    onImportClick = { showVaultImportSheet = true },
                    onDismissImportOutcome = onDismissVaultImport,
                )
                HorizontalDivider(Modifier.padding(vertical = 24.dp))
                Text(
                    stringResource(R.string.csv_export_heading),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .testTag("csv-export-heading")
                        .semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                CsvExportContent(
                    inProgress = csvExportInProgress,
                    outcome = csvExportOutcome,
                    onExportClick = { showCsvTableSheet = true },
                    onDismissOutcome = onDismissCsvExportOutcome,
                )
            }
        }
    }

    if (showPassphraseSheet) {
        RecoveryPassphraseSheet(
            validatePassphrase = validatePassphrase,
            onDismiss = { showPassphraseSheet = false },
            onPrepare = { passphrase ->
                showPassphraseSheet = false
                onPrepare(passphrase)
            },
        )
    }
    if (showVaultExportSheet) {
        VaultExportPassphraseSheet(
            validatePassphrase = validatePassphrase,
            onDismiss = { showVaultExportSheet = false },
            onExport = { passphrase ->
                showVaultExportSheet = false
                onExportVaultPassphraseConfirmed(passphrase)
            },
        )
    }
    if (showVaultImportSheet) {
        VaultImportPassphraseSheet(
            onDismiss = { showVaultImportSheet = false },
            onImport = { passphrase ->
                showVaultImportSheet = false
                onImportVaultPassphraseConfirmed(passphrase)
            },
        )
    }
    if (showCsvTableSheet) {
        CsvTableSelectionSheet(
            onDismiss = { showCsvTableSheet = false },
            onContinue = { tables ->
                showCsvTableSheet = false
                csvDisclosureTables = tables
            },
        )
    }
    csvDisclosureTables?.let { tables ->
        CsvDisclosureDialog(
            onConfirm = {
                csvDisclosureTables = null
                onExportCsv(tables)
            },
            onCancel = { csvDisclosureTables = null },
        )
    }
    (vaultImportOutcome as? VaultImportOutcome.Ready)?.let { ready ->
        VaultImportPreviewDialog(
            preview = ready,
            onReplace = onConfirmVaultImport,
            onCancel = onDismissVaultImport,
        )
    }
    remoteSecretAction?.let { action ->
        RemoteSecretSheet(
            action = action,
            onDismiss = { remoteSecretAction = null },
            onSubmit = { first, second ->
                remoteSecretAction = null
                when (action) {
                    RemoteSecretAction.CHANGE -> onChangePassphrase(first, second)
                    RemoteSecretAction.DELETE -> onDeleteHistory(first)
                    RemoteSecretAction.DELETE_ATTACHMENTS -> onDeleteAttachmentContent(first)
                }
            },
        )
    }
}

/**
 * What this installation can say about the attachment bytes it keeps.
 *
 * Attachment content lives in the same Drive lineage as the encrypted backup,
 * so there is nothing to offer — and nothing to delete — when no lineage is
 * connected. The cache line is about this device only; it is never a claim
 * about what the provider holds.
 */
@Composable
private fun CloudAttachmentContent(
    status: RemoteBackupStatus,
    cacheUsageBytes: Long,
    onDeleteContent: () -> Unit,
) {
    val connected = status !is RemoteBackupStatus.Disabled &&
        status !is RemoteBackupStatus.Terminated
    Text(
        stringResource(
            if (connected) R.string.attachments_connected else R.string.attachments_not_connected,
        ),
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(
            R.string.attachments_cache_usage,
            NumberFormatHolder.bytes.format(cacheUsageBytes),
        ),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        stringResource(R.string.attachments_cache_explanation),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (connected) {
        BackupAction(
            R.string.attachments_delete_content,
            "attachments-delete-content",
            onDeleteContent,
        )
        Text(
            stringResource(R.string.attachments_delete_disclosure),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The whole-vault export and import actions and their most recent progress or
 * outcome.
 *
 * Both actions stay tappable throughout: re-entrancy is the transfer's own
 * concern, not something these rows enforce by hiding themselves. An import
 * awaiting confirmation is shown by its own dialog rather than here, so this
 * section never states an outcome the person has not yet decided on.
 */
@Composable
private fun VaultTransferContent(
    exportInProgress: Boolean,
    exportOutcome: VaultExportOutcome?,
    importInProgress: Boolean,
    importOutcome: VaultImportOutcome?,
    onExportClick: () -> Unit,
    onDismissExportOutcome: () -> Unit,
    onImportClick: () -> Unit,
    onDismissImportOutcome: () -> Unit,
) {
    Text(
        stringResource(R.string.vault_transfer_explanation),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    BackupAction(R.string.vault_export_action, "vault-export", onExportClick)
    if (exportInProgress) {
        TransferProgress(R.string.vault_export_in_progress)
    }
    if (exportOutcome != null) {
        TransferOutcome(
            message = when (exportOutcome) {
                is VaultExportOutcome.Completed -> pluralStringResource(
                    R.plurals.vault_export_completed,
                    exportOutcome.attachmentCount,
                    exportOutcome.attachmentCount,
                )
                is VaultExportOutcome.MissingAttachmentBytes -> stringResource(
                    R.string.vault_export_missing_attachments,
                    exportOutcome.displayNames.joinToString(", "),
                )
                is VaultExportOutcome.Failed -> exportOutcome.reason
            },
            testTag = "vault-export-outcome",
            dismissTestTag = "vault-export-dismiss",
            onDismiss = onDismissExportOutcome,
        )
    }
    Spacer(Modifier.height(12.dp))
    BackupAction(R.string.vault_import_action, "vault-import", onImportClick)
    if (importInProgress) {
        TransferProgress(R.string.vault_import_in_progress)
    }
    when (importOutcome) {
        VaultImportOutcome.Completed -> TransferOutcome(
            message = stringResource(R.string.vault_import_completed),
            testTag = "vault-import-outcome",
            dismissTestTag = "vault-import-dismiss",
            onDismiss = onDismissImportOutcome,
        )
        is VaultImportOutcome.Failed -> TransferOutcome(
            message = importOutcome.reason,
            testTag = "vault-import-outcome",
            dismissTestTag = "vault-import-dismiss",
            onDismiss = onDismissImportOutcome,
        )
        is VaultImportOutcome.Ready, null -> Unit
    }
}

/**
 * The plaintext CSV export action and its most recent progress or outcome.
 *
 * Unlike the encrypted whole-vault transfer above, this writes unencrypted
 * files, so tapping the action always leads through a table choice and a
 * fresh disclosure before anything is written — never straight to export.
 */
@Composable
private fun CsvExportContent(
    inProgress: Boolean,
    outcome: CsvExportOutcome?,
    onExportClick: () -> Unit,
    onDismissOutcome: () -> Unit,
) {
    Text(
        stringResource(R.string.csv_export_explanation),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    BackupAction(R.string.csv_export_action, "csv-export", onExportClick)
    if (inProgress) {
        TransferProgress(R.string.csv_export_in_progress)
    }
    if (outcome != null) {
        TransferOutcome(
            message = when (outcome) {
                is CsvExportOutcome.Completed -> pluralStringResource(
                    R.plurals.csv_export_completed,
                    outcome.tableCount,
                    outcome.tableCount,
                )
                is CsvExportOutcome.Failed -> outcome.reason
            },
            testTag = "csv-export-outcome",
            dismissTestTag = "csv-export-dismiss",
            onDismiss = onDismissOutcome,
        )
    }
}

private val CSV_TABLE_OPTIONS = listOf(
    Triple(CsvExportTable.TASKS, R.string.csv_export_table_tasks, "csv-export-table-tasks"),
    Triple(
        CsvExportTable.PROJECTS,
        R.string.csv_export_table_projects,
        "csv-export-table-projects",
    ),
    Triple(
        CsvExportTable.TIME_ENTRIES,
        R.string.csv_export_table_time_entries,
        "csv-export-table-time-entries",
    ),
    Triple(CsvExportTable.NOTES, R.string.csv_export_table_notes, "csv-export-table-notes"),
)

/**
 * Which tables to export, chosen before the disclosure so the disclosure can
 * describe a concrete, already-decided action rather than a hypothetical one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CsvTableSelectionSheet(
    onDismiss: () -> Unit,
    onContinue: (Set<CsvExportTable>) -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<CsvExportTable>()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("csv-export-table-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.csv_export_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            CSV_TABLE_OPTIONS.forEach { (table, labelRes, tag) ->
                val checked = table in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable {
                            selected = if (checked) selected - table else selected + table
                        }
                        .testTag(tag),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("csv-export-tables-cancel"),
                ) {
                    Text(stringResource(R.string.backup_cancel_action))
                }
                Button(
                    onClick = { onContinue(selected) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("csv-export-tables-continue"),
                ) {
                    Text(stringResource(R.string.csv_export_sheet_continue))
                }
            }
        }
    }
}

/**
 * The fresh, every-time disclosure a plaintext CSV export always presents
 * before anything is written — there is no "do not ask again".
 */
@Composable
private fun CsvDisclosureDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.csv_export_disclosure_title)) },
        text = {
            Text(
                stringResource(R.string.csv_export_disclosure_body),
                modifier = Modifier.testTag("csv-export-disclosure"),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("csv-export-disclosure-confirm"),
            ) {
                Text(stringResource(R.string.csv_export_disclosure_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("csv-export-disclosure-cancel"),
            ) {
                Text(stringResource(R.string.backup_cancel_action))
            }
        },
    )
}

@Composable
private fun TransferProgress(@StringRes message: Int) {
    Spacer(Modifier.height(12.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val description = stringResource(message)
        CircularProgressIndicator(
            modifier = Modifier
                .size(24.dp)
                .semantics { contentDescription = description },
            strokeWidth = 3.dp,
        )
        Text(description, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TransferOutcome(
    message: String,
    testTag: String,
    dismissTestTag: String,
    onDismiss: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Text(
        message,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag(testTag),
    )
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = onDismiss,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(dismissTestTag),
    ) {
        Text(stringResource(R.string.vault_export_dismiss_action))
    }
}

/**
 * Everything replacing this device's vault costs, before anything is replaced.
 *
 * The counts come from an archive that has already been authenticated whole,
 * so they describe exactly what would arrive. Cancelling here discards the
 * staged archive; only Replace touches the live vault.
 */
@Composable
private fun VaultImportPreviewDialog(
    preview: VaultImportOutcome.Ready,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.vault_import_preview_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag("vault-import-preview"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        R.string.vault_import_preview_counts,
                        pluralStringResource(
                            R.plurals.vault_import_preview_records,
                            preview.recordCount,
                            preview.recordCount,
                        ),
                        pluralStringResource(
                            R.plurals.vault_import_preview_attachments,
                            preview.attachmentCount,
                            preview.attachmentCount,
                        ),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.vault_import_preview_replacement),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.vault_import_preview_remote),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.vault_import_preview_passphrase),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (preview.attachmentsBeyondCache.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.vault_import_preview_beyond_cache,
                            preview.attachmentsBeyondCache.joinToString(", "),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onReplace,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("vault-import-replace"),
            ) {
                Text(stringResource(R.string.vault_import_replace_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("vault-import-cancel"),
            ) {
                Text(stringResource(R.string.backup_cancel_action))
            }
        },
    )
}

@Composable
private fun EncryptedBackupContent(
    status: RemoteBackupStatus,
    canBackUpNow: Boolean,
    canRestore: Boolean,
    canReauthorise: Boolean,
    canTakeOver: Boolean,
    canPreserveAsNewLineage: Boolean,
    canChangePassphrase: Boolean,
    canDisconnect: Boolean,
    canDeleteHistory: Boolean,
    passphraseChangeDisclosureVisible: Boolean,
    onConnect: () -> Unit,
    onBackUpNow: () -> Unit,
    onRestore: () -> Unit,
    onReauthorise: () -> Unit,
    onTakeOver: () -> Unit,
    onPreserveAsNewLineage: () -> Unit,
    onChangePassphrase: () -> Unit,
    onDisconnect: () -> Unit,
    onDeleteHistory: () -> Unit,
) {
    Text(
        remoteStatusText(status),
        style = MaterialTheme.typography.titleMedium,
    )
    if (status is RemoteBackupStatus.Verified) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                R.string.backup_remote_verified_facts,
                status.info.generation.value,
                status.info.verifiedAt.atZone(ZoneId.systemDefault()).format(PRODUCED_AT_FORMAT),
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    if (status is RemoteBackupStatus.Disabled) {
        BackupAction(R.string.backup_remote_connect, "encrypted-backup-connect", onConnect)
    }
    if (canBackUpNow) {
        BackupAction(R.string.backup_remote_now, "encrypted-backup-now", onBackUpNow)
    }
    if (canRestore) {
        BackupAction(R.string.backup_remote_restore, "encrypted-backup-takeover", onRestore)
    }
    if (canReauthorise) {
        BackupAction(
            R.string.backup_remote_reauthorise,
            "encrypted-backup-reauthorize",
            onReauthorise,
        )
    }
    if (canTakeOver) {
        BackupAction(R.string.backup_remote_takeover, "encrypted-backup-takeover", onTakeOver)
    }
    if (canPreserveAsNewLineage) {
        BackupAction(
            R.string.backup_remote_preserve,
            "encrypted-backup-preserve",
            onPreserveAsNewLineage,
        )
    }
    if (canChangePassphrase) {
        BackupAction(
            R.string.backup_remote_change_passphrase,
            "encrypted-backup-change-passphrase",
            onChangePassphrase,
        )
        Text(
            stringResource(R.string.backup_remote_passphrase_disclosure),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (passphraseChangeDisclosureVisible) {
        Text(
            stringResource(R.string.backup_remote_passphrase_changed),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    if (canDisconnect) {
        BackupAction(
            R.string.backup_remote_disconnect,
            "encrypted-backup-disconnect",
            onDisconnect,
        )
        Text(
            stringResource(R.string.backup_remote_disconnect_disclosure),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (canDeleteHistory) {
        BackupAction(
            R.string.backup_remote_delete,
            "encrypted-backup-delete",
            onDeleteHistory,
        )
        Text(
            stringResource(R.string.backup_remote_delete_disclosure),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackupAction(label: Int, tag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(tag),
    ) {
        Text(stringResource(label))
    }
}

private enum class RemoteSecretAction { CHANGE, DELETE, DELETE_ATTACHMENTS }

private fun RemoteSecretAction.titleRes(): Int = when (this) {
    RemoteSecretAction.CHANGE -> R.string.backup_remote_change_passphrase
    RemoteSecretAction.DELETE -> R.string.backup_remote_delete
    RemoteSecretAction.DELETE_ATTACHMENTS -> R.string.attachments_delete_content
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteSecretSheet(
    action: RemoteSecretAction,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                stringResource(action.titleRes()),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = first,
                onValueChange = { first = it },
                label = {
                    Text(
                        stringResource(
                            if (action == RemoteSecretAction.CHANGE) {
                                R.string.backup_current_passphrase
                            } else {
                                R.string.backup_passphrase_label
                            },
                        ),
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (action == RemoteSecretAction.CHANGE) {
                        ImeAction.Next
                    } else {
                        ImeAction.Done
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("encrypted-current-passphrase"),
            )
            if (action == RemoteSecretAction.CHANGE) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it },
                    label = { Text(stringResource(R.string.backup_new_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("encrypted-new-passphrase"),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val submittedFirst = first
                    val submittedSecond = second
                    first = ""
                    second = ""
                    onSubmit(submittedFirst, submittedSecond)
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("encrypted-secret-submit"),
            ) {
                Text(stringResource(action.titleRes()))
            }
        }
    }
}

@Composable
private fun remoteStatusText(status: RemoteBackupStatus): String = stringResource(
    when (status) {
        RemoteBackupStatus.Disabled -> R.string.backup_remote_off
        RemoteBackupStatus.Preparing -> R.string.backup_remote_preparing
        is RemoteBackupStatus.BackingUp -> R.string.backup_remote_backing_up
        is RemoteBackupStatus.Verified -> R.string.backup_remote_backed_up
        is RemoteBackupStatus.RetryScheduled -> R.string.backup_remote_waiting_retry
        is RemoteBackupStatus.ActionRequired -> when (status.reason) {
            RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED ->
                R.string.backup_remote_needs_reauthorisation
            RemoteBackupFailureCategory.ACCOUNT_MISMATCH -> R.string.backup_remote_wrong_account
            RemoteBackupFailureCategory.OWNERSHIP_LOST -> R.string.backup_remote_inactive_device
            RemoteBackupFailureCategory.PROVIDER_STORAGE -> R.string.backup_remote_storage
            RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE -> R.string.backup_remote_damaged
            RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE -> R.string.backup_remote_ambiguous
            else -> R.string.backup_remote_blocked
        }
        RemoteBackupStatus.OwnershipLost -> R.string.backup_remote_inactive_device
        RemoteBackupStatus.AmbiguousRemoteState -> R.string.backup_remote_ambiguous
        RemoteBackupStatus.Deleting -> R.string.backup_remote_deleting
        RemoteBackupStatus.Terminated -> R.string.backup_remote_deleted
    },
)

@Composable
private fun BackupStatusContent(
    status: AndroidBackupStatus,
    canReprepareInitialPackage: Boolean,
    onPrepare: () -> Unit,
    onRetry: () -> Unit,
) {
    when (status) {
        AndroidBackupStatus.NotPrepared -> StatusBlock(
            icon = { StatusIcon(Icons.Rounded.Inventory2) },
            title = stringResource(R.string.backup_not_prepared_summary),
            body = stringResource(R.string.backup_not_prepared_explanation),
            action = {
                Button(
                    onClick = onPrepare,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("backup-prepare"),
                ) {
                    Text(stringResource(R.string.backup_prepare_action))
                }
            },
        )
        AndroidBackupStatus.Preparing -> StatusBlock(
            icon = {
                val description = stringResource(R.string.backup_preparing_title)
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(32.dp)
                        .semantics { contentDescription = description },
                    color = MaterialTheme.colorScheme.secondary,
                    strokeWidth = 3.dp,
                )
            },
            title = stringResource(R.string.backup_preparing_title),
            body = stringResource(R.string.backup_preparing_explanation),
        )
        is AndroidBackupStatus.Ready -> PackageFacts(
            icon = { StatusIcon(Icons.Rounded.CheckCircle) },
            title = stringResource(R.string.backup_ready_title),
            packageInfo = status.packageInfo,
        )
        is AndroidBackupStatus.UpdatePending -> PackageFacts(
            icon = { StatusIcon(Icons.Rounded.Sync) },
            title = stringResource(R.string.backup_update_pending_title),
            packageInfo = status.packageInfo,
            body = stringResource(R.string.backup_update_pending_explanation),
        )
        is AndroidBackupStatus.Unavailable -> {
            val canRetry = status.reason == BackupUnavailableReason.ENCODING_OR_CRYPTO ||
                status.reason == BackupUnavailableReason.VERIFICATION_FAILED ||
                status.reason == BackupUnavailableReason.FILE_IO
            StatusBlock(
                icon = { StatusIcon(Icons.Rounded.ErrorOutline) },
                title = stringResource(R.string.backup_unavailable_title),
                body = unavailableReason(status.reason),
                action = if (canReprepareInitialPackage) {
                    {
                        TextButton(
                            onClick = onPrepare,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("backup-reprepare"),
                        ) {
                            Text(stringResource(R.string.backup_reprepare_action))
                        }
                    }
                } else if (canRetry) {
                    {
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("backup-retry"),
                        ) {
                            Text(stringResource(R.string.backup_retry_action))
                        }
                    }
                } else {
                    null
                },
            )
        }
        is AndroidBackupStatus.RestoredPackageDetected -> StatusBlock(
            icon = { StatusIcon(Icons.Rounded.MoveToInbox) },
            title = stringResource(R.string.backup_restored_title),
            body = when (status.condition) {
                RestoredPackageCondition.PRESERVED ->
                    stringResource(R.string.backup_restored_preserved)
                RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT ->
                    stringResource(R.string.backup_restored_incompatible)
            },
        )
    }
}

@Composable
private fun StatusIcon(imageVector: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.size(32.dp),
    )
}

@Composable
private fun StatusBlock(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.let {
                Spacer(Modifier.height(12.dp))
                it()
            }
        }
    }
}

@Composable
private fun PackageFacts(
    icon: @Composable () -> Unit,
    title: String,
    packageInfo: BackupPackageInfo,
    body: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            body?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.backup_package_generation,
                    packageInfo.packageGeneration.value,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(
                    R.string.backup_current_generation,
                    packageInfo.currentGeneration.value,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(
                    R.string.backup_byte_count,
                    NumberFormatHolder.bytes.format(packageInfo.byteCount),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(
                    R.string.backup_produced_locally,
                    packageInfo.producedAt
                        .atZone(ZoneId.systemDefault())
                        .format(PRODUCED_AT_FORMAT),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun unavailableReason(reason: BackupUnavailableReason): String = stringResource(
    when (reason) {
        BackupUnavailableReason.PACKAGE_TOO_LARGE ->
            R.string.backup_unavailable_too_large
        BackupUnavailableReason.RECOVERY_ENVELOPE_UNAVAILABLE ->
            R.string.backup_unavailable_envelope
        BackupUnavailableReason.ENCODING_OR_CRYPTO ->
            R.string.backup_unavailable_encoding
        BackupUnavailableReason.VERIFICATION_FAILED ->
            R.string.backup_unavailable_verification
        BackupUnavailableReason.FILE_IO ->
            R.string.backup_unavailable_file
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoveryPassphraseSheet(
    validatePassphrase: (String, String) -> RecoveryPassphraseValidation,
    onDismiss: () -> Unit,
    onPrepare: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var validation by remember {
        mutableStateOf<RecoveryPassphraseValidation?>(null)
    }
    val focusManager = LocalFocusManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dismiss = {
        passphrase = ""
        confirmation = ""
        validation = null
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.backup_passphrase_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.backup_passphrase_explanation),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    validation = null
                },
                label = { Text(stringResource(R.string.backup_passphrase_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Next) },
                ),
                isError = validation is RecoveryPassphraseValidation.TooShort ||
                    validation is RecoveryPassphraseValidation.TooLong,
                supportingText = if (
                    validation is RecoveryPassphraseValidation.TooShort ||
                    validation is RecoveryPassphraseValidation.TooLong
                ) {
                    {
                        Text(stringResource(R.string.backup_passphrase_length_error))
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("backup-passphrase"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = confirmation,
                onValueChange = {
                    confirmation = it
                    validation = null
                },
                label = { Text(stringResource(R.string.backup_confirmation_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.moveFocus(FocusDirection.Next) },
                ),
                isError = validation is RecoveryPassphraseValidation.ConfirmationMismatch,
                supportingText = if (
                    validation is RecoveryPassphraseValidation.ConfirmationMismatch
                ) {
                    {
                        Text(stringResource(R.string.backup_passphrase_mismatch_error))
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("backup-confirmation"),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = dismiss,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("backup-cancel"),
                ) {
                    Text(stringResource(R.string.backup_cancel_action))
                }
                Button(
                    onClick = {
                        when (
                            val result = validatePassphrase(passphrase, confirmation)
                        ) {
                            RecoveryPassphraseValidation.Valid -> {
                                val submittedPassphrase = passphrase
                                passphrase = ""
                                confirmation = ""
                                validation = null
                                onPrepare(submittedPassphrase)
                            }
                            else -> validation = result
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("backup-submit"),
                ) {
                    Text(stringResource(R.string.backup_submit_action))
                }
            }
        }
    }
}

/**
 * A fresh passphrase and confirmation for one vault export, following the
 * same shape as [RecoveryPassphraseSheet]. It is a separate entry rather than
 * a shared one because this passphrase and the Android package's recovery
 * passphrase protect independent artefacts: changing one must never read as
 * changing the other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultExportPassphraseSheet(
    validatePassphrase: (String, String) -> RecoveryPassphraseValidation,
    onDismiss: () -> Unit,
    onExport: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var validation by remember {
        mutableStateOf<RecoveryPassphraseValidation?>(null)
    }
    val focusManager = LocalFocusManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dismiss = {
        passphrase = ""
        confirmation = ""
        validation = null
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.vault_export_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.vault_export_sheet_explanation),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    validation = null
                },
                label = { Text(stringResource(R.string.vault_export_passphrase_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Next) },
                ),
                isError = validation is RecoveryPassphraseValidation.TooShort ||
                    validation is RecoveryPassphraseValidation.TooLong,
                supportingText = if (
                    validation is RecoveryPassphraseValidation.TooShort ||
                    validation is RecoveryPassphraseValidation.TooLong
                ) {
                    {
                        Text(stringResource(R.string.backup_passphrase_length_error))
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("vault-export-passphrase"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = confirmation,
                onValueChange = {
                    confirmation = it
                    validation = null
                },
                label = { Text(stringResource(R.string.vault_export_confirmation_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.moveFocus(FocusDirection.Next) },
                ),
                isError = validation is RecoveryPassphraseValidation.ConfirmationMismatch,
                supportingText = if (
                    validation is RecoveryPassphraseValidation.ConfirmationMismatch
                ) {
                    {
                        Text(stringResource(R.string.backup_passphrase_mismatch_error))
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("vault-export-confirmation"),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = dismiss,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("vault-export-cancel"),
                ) {
                    Text(stringResource(R.string.backup_cancel_action))
                }
                Button(
                    onClick = {
                        when (
                            val result = validatePassphrase(passphrase, confirmation)
                        ) {
                            RecoveryPassphraseValidation.Valid -> {
                                val submittedPassphrase = passphrase
                                passphrase = ""
                                confirmation = ""
                                validation = null
                                onExport(submittedPassphrase)
                            }
                            else -> validation = result
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("vault-export-submit"),
                ) {
                    Text(stringResource(R.string.vault_export_submit_action))
                }
            }
        }
    }
}

/**
 * The passphrase one archive was exported with.
 *
 * There is no confirmation field: this is an existing secret being recalled,
 * not a new one being chosen, so a second field could only disagree with a
 * passphrase the archive itself is the authority on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultImportPassphraseSheet(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dismiss = {
        passphrase = ""
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.vault_import_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.vault_import_sheet_explanation),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.vault_import_passphrase_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("vault-import-passphrase"),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = dismiss,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("vault-import-passphrase-cancel"),
                ) {
                    Text(stringResource(R.string.backup_cancel_action))
                }
                Button(
                    onClick = {
                        val submitted = passphrase
                        passphrase = ""
                        onImport(submitted)
                    },
                    enabled = passphrase.isNotEmpty(),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("vault-import-submit"),
                ) {
                    Text(stringResource(R.string.vault_import_submit_action))
                }
            }
        }
    }
}

private object NumberFormatHolder {
    val bytes = java.text.NumberFormat.getIntegerInstance(Locale.UK)
}

private val PRODUCED_AT_FORMAT =
    DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm", Locale.UK)
