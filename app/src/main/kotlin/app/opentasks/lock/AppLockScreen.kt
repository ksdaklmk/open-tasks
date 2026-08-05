package app.opentasks.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opentasks.R

/**
 * Replaces all workspace content while [AppLockController.locked] is
 * `true`.
 *
 * This is deliberately thin and lives in `:app`, not a feature module: the
 * caller (`MainActivity`) must compose this *in place of* the workspace,
 * never alongside it, so no workspace data is ever composed behind it.
 *
 * @param unlockUnavailable `true` when the platform could not show a
 * prompt at all (most commonly no device credential is enrolled) --
 * without this, tapping "Unlock Open Tasks" would visibly do nothing.
 */
@Composable
fun AppLockScreen(
    onUnlockClick: () -> Unit,
    modifier: Modifier = Modifier,
    unlockUnavailable: Boolean = false,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .testTag("app-lock-screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Column(
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.app_lock_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (unlockUnavailable) {
                Text(
                    stringResource(R.string.app_lock_unavailable_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .testTag("app-lock-unavailable-message"),
                )
            }
            Button(
                onClick = onUnlockClick,
                modifier = Modifier.testTag("app-lock-unlock-button"),
            ) {
                Text(stringResource(R.string.app_lock_unlock_action))
            }
        }
    }
}
