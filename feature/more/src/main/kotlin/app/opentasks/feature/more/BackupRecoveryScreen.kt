package app.opentasks.feature.more

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
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
import app.opentasks.core.model.RestoredPackageCondition
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRecoveryScreen(
    status: AndroidBackupStatus,
    canReprepareInitialPackage: Boolean = false,
    validatePassphrase: (
        passphrase: String,
        confirmation: String,
    ) -> RecoveryPassphraseValidation,
    onPrepare: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPassphraseSheet by remember { mutableStateOf(false) }

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
                    stringResource(R.string.backup_android_package_heading),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
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
}

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

private object NumberFormatHolder {
    val bytes = java.text.NumberFormat.getIntegerInstance(Locale.UK)
}

private val PRODUCED_AT_FORMAT =
    DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm", Locale.UK)
