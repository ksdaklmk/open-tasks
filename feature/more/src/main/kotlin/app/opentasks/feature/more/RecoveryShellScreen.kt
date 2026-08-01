package app.opentasks.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.opentasks.core.model.RecoveryFailureCategory

enum class RecoveryShellMode {
    NoVault,
    ActiveReplacement,
    UnreadableVault,
    Discovering,
    Candidates,
    Authenticating,
    TakeoverConfirmation,
    Activating,
    Failed,
}

data class RecoveryShellCandidate(
    val handle: String,
    val drive: Boolean,
)

@Composable
fun RecoveryShellScreen(
    mode: RecoveryShellMode,
    candidates: List<RecoveryShellCandidate> = emptyList(),
    takeoverGeneration: Long? = null,
    failureText: String? = null,
    failureReason: RecoveryFailureCategory? = null,
    onDiscoverDrive: () -> Unit = {},
    onDiscoverPortable: () -> Unit = {},
    onRestore: (String, String) -> Unit = { _, _ -> },
    onConfirmTakeover: () -> Unit = {},
    onStartWithoutRestoring: () -> Unit = {},
    onRetryUnreadable: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var passphrase by remember { mutableStateOf("") }
    val submit: (RecoveryShellCandidate) -> Unit = { candidate ->
        val submitted = passphrase
        passphrase = ""
        onRestore(candidate.handle, submitted)
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag("recovery-shell"),
        contentPadding = PaddingValues(16.dp, 24.dp, 16.dp, 64.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.recovery_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        when (mode) {
            RecoveryShellMode.NoVault -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecoverySources(onDiscoverDrive, onDiscoverPortable)
                    OutlinedButton(
                        onClick = onStartWithoutRestoring,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.recovery_start_without_restore)) }
                }
            }
            RecoveryShellMode.ActiveReplacement -> item {
                RecoverySources(onDiscoverDrive, onDiscoverPortable)
            }
            RecoveryShellMode.UnreadableVault -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.recovery_unreadable), style = MaterialTheme.typography.bodyLarge)
                    Button(
                        onClick = onRetryUnreadable,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.recovery_retry_unreadable)) }
                    Text(
                        stringResource(R.string.recovery_export_guidance),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("recovery-export-guidance"),
                    )
                }
            }
            RecoveryShellMode.Discovering,
            RecoveryShellMode.Authenticating,
            RecoveryShellMode.Activating,
            -> item { CircularProgressIndicator() }
            RecoveryShellMode.Candidates -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecoverySources(onDiscoverDrive, onDiscoverPortable)
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.recovery_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { candidates.firstOrNull()?.let(submit) },
                        ),
                        modifier = Modifier.testTag("recovery-passphrase"),
                    )
                    candidates.forEach { candidate ->
                        Button(
                            onClick = { submit(candidate) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (candidate.drive) R.string.recovery_restore_drive
                                    else R.string.recovery_restore_portable,
                                ),
                            )
                        }
                    }
                }
            }
            RecoveryShellMode.TakeoverConfirmation -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    takeoverGeneration?.let {
                        Text(
                            stringResource(R.string.recovery_verified_generation, it),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Button(
                        onClick = onConfirmTakeover,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("recovery-takeover-confirm"),
                    ) { Text(stringResource(R.string.recovery_confirm_takeover)) }
                }
            }
            RecoveryShellMode.Failed -> item {
                val message = failureReason?.let { recoveryFailureMessage(it) }
                    ?: failureText
                    ?: stringResource(R.string.recovery_failed)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { error(message) },
                    )
                    RecoverySources(onDiscoverDrive, onDiscoverPortable)
                }
            }
        }
    }
}

@Composable
private fun recoveryFailureMessage(reason: RecoveryFailureCategory): String = stringResource(
    when (reason) {
        RecoveryFailureCategory.AUTHORIZATION_REQUIRED ->
            R.string.recovery_failure_authorization
        RecoveryFailureCategory.ACCOUNT_MISMATCH ->
            R.string.recovery_failure_account_mismatch
        RecoveryFailureCategory.WRONG_PASSPHRASE ->
            R.string.recovery_failure_wrong_passphrase
        RecoveryFailureCategory.UNSAFE_KDF -> R.string.recovery_failure_unsafe_kdf
        RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE ->
            R.string.recovery_failure_corrupt
        RecoveryFailureCategory.MISSING_REQUIRED_OBJECT ->
            R.string.recovery_failure_missing
        RecoveryFailureCategory.INSUFFICIENT_STORAGE ->
            R.string.recovery_failure_storage
        RecoveryFailureCategory.STAGING_INVARIANT ->
            R.string.recovery_failure_staging
        RecoveryFailureCategory.OWNERSHIP_CHANGED,
        RecoveryFailureCategory.OWNERSHIP_LOST,
        -> R.string.recovery_failure_ownership
        RecoveryFailureCategory.TERMINATED -> R.string.recovery_failure_terminated
        RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE ->
            R.string.recovery_failure_ambiguous
        RecoveryFailureCategory.LOCAL_KEY_UNAVAILABLE ->
            R.string.recovery_failure_local_key
    },
)

@Composable
private fun RecoverySources(onDrive: () -> Unit, onPortable: () -> Unit) {
    Button(
        onClick = onDrive,
        modifier = Modifier.heightIn(min = 48.dp).testTag("recovery-drive"),
    ) { Text(stringResource(R.string.recovery_drive)) }
    OutlinedButton(
        onClick = onPortable,
        modifier = Modifier.heightIn(min = 48.dp).testTag("recovery-portable"),
    ) { Text(stringResource(R.string.recovery_portable)) }
}
