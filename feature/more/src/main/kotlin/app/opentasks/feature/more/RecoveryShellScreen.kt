package app.opentasks.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

enum class RecoveryShellMode {
    NoVault,
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
    onDiscoverDrive: () -> Unit = {},
    onDiscoverPortable: () -> Unit = {},
    onRestore: (String, String) -> Unit = { _, _ -> },
    onConfirmTakeover: () -> Unit = {},
    onStartWithoutRestoring: () -> Unit = {},
    onRetryUnreadable: () -> Unit = {},
    onExportUnreadable: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var passphrase by remember { mutableStateOf("") }
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
            RecoveryShellMode.UnreadableVault -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.recovery_unreadable), style = MaterialTheme.typography.bodyLarge)
                    Button(
                        onClick = onRetryUnreadable,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.recovery_retry_unreadable)) }
                    OutlinedButton(
                        onClick = onExportUnreadable,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.recovery_export_unreadable)) }
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
                        modifier = Modifier.testTag("recovery-passphrase"),
                    )
                    candidates.forEach { candidate ->
                        Button(
                            onClick = {
                                val submitted = passphrase
                                passphrase = ""
                                onRestore(candidate.handle, submitted)
                            },
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
                Text(
                    failureText ?: stringResource(R.string.recovery_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

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
