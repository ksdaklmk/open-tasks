package app.opentasks.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.DotRunBar

@Composable
fun WelcomeScreen(
    onContinueWithGoogle: () -> Unit,
    onContinueOffline: () -> Unit,
    onRestoreFromDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag("welcome-screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(Modifier.safeDrawingPadding()) {
            if (maxWidth >= 840.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(32.dp)
                        .testTag("welcome-expanded"),
                    horizontalArrangement = Arrangement.spacedBy(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) { WelcomeIdentity() }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        WelcomeActions(
                            onContinueWithGoogle = onContinueWithGoogle,
                            onContinueOffline = onContinueOffline,
                            onRestoreFromDevice = onRestoreFromDevice,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .testTag("welcome-compact"),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    WelcomeIdentity()
                    WelcomeActions(
                        onContinueWithGoogle = onContinueWithGoogle,
                        onContinueOffline = onContinueOffline,
                        onRestoreFromDevice = onRestoreFromDevice,
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeIdentity() {
    Column(modifier = Modifier.widthIn(max = 520.dp)) {
        DotRunBar(
            progress = 1f,
            unitCount = 8,
            maxDots = 8,
            modifier = Modifier.width(132.dp).height(12.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WelcomeActions(
    onContinueWithGoogle: () -> Unit,
    onContinueOffline: () -> Unit,
    onRestoreFromDevice: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onContinueWithGoogle,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("welcome-google"),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_google_g),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.Unspecified,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.welcome_google))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.welcome_google_disclosure),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onContinueOffline,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("welcome-offline"),
        ) {
            Text(stringResource(R.string.welcome_offline))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onRestoreFromDevice,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("welcome-portable"),
        ) {
            Text(stringResource(R.string.welcome_restore_device))
        }
    }
}
