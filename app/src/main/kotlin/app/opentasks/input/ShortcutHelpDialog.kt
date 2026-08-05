package app.opentasks.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentasks.R

/**
 * Lists every keyboard shortcut recognised by [shortcutActionFor], opened
 * by the `?` shortcut it documents. Every row is plain [Text] inside the
 * platform [AlertDialog], so it keeps the dialog's own focus order,
 * Enter/Space activation on the close button, and visible focus/hover --
 * nothing here is a custom, drag-only, or pointer-only control.
 */
@Composable
fun ShortcutHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("shortcut-help-dialog"),
        title = { Text(stringResource(R.string.shortcut_help_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ShortcutHelpRow(
                    keyLabel = stringResource(R.string.shortcut_help_key_search),
                    actionLabel = stringResource(R.string.shortcut_help_action_search),
                )
                ShortcutHelpRow(
                    keyLabel = stringResource(R.string.shortcut_help_key_quick_add),
                    actionLabel = stringResource(R.string.shortcut_help_action_quick_add),
                )
                ShortcutHelpRow(
                    keyLabel = stringResource(R.string.shortcut_help_key_new_project),
                    actionLabel = stringResource(R.string.shortcut_help_action_new_project),
                )
                ShortcutHelpRow(
                    keyLabel = stringResource(R.string.shortcut_help_key_show_help),
                    actionLabel = stringResource(R.string.shortcut_help_action_show_help),
                )
                ShortcutHelpRow(
                    keyLabel = stringResource(R.string.shortcut_help_key_dismiss),
                    actionLabel = stringResource(R.string.shortcut_help_action_dismiss),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shortcut_help_close))
            }
        },
    )
}

@Composable
private fun ShortcutHelpRow(keyLabel: String, actionLabel: String) {
    Column {
        Text(
            text = keyLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
