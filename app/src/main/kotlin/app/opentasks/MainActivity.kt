package app.opentasks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentasks.backup.RecoveryPresentation
import app.opentasks.backup.RecoveryViewModel
import app.opentasks.core.data.DefaultVaultRuntimeManager
import app.opentasks.core.data.LocalVaultRuntime
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.feature.more.RecoveryShellMode
import app.opentasks.feature.more.RecoveryShellCandidate
import app.opentasks.feature.more.RecoveryShellScreen
import app.opentasks.reminders.ReminderIntents
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var vaultRuntimeManager: DefaultVaultRuntimeManager

    @Inject
    lateinit var activeVaultServices: ActiveVaultServices

    private var quickAddSignal by mutableIntStateOf(0)
    private var openTaskSignal by mutableIntStateOf(0)
    private var openTaskId by mutableStateOf<String?>(null)
    private var activeRuntime by mutableStateOf<LocalVaultRuntime?>(null)
    private var runtimeState by mutableStateOf<VaultRuntimeState>(VaultRuntimeState.Initializing)
    private var activeRecovery by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        observeVaultRuntime()

        setContent {
            when (runtimeState) {
                VaultRuntimeState.Initializing -> OpenTasksTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {}
                }
                VaultRuntimeState.NoVault,
                is VaultRuntimeState.Unreadable,
                is VaultRuntimeState.Recovering,
                -> RecoverySurface(runtimeState)
                is VaultRuntimeState.Active -> {
                    if (activeRecovery) {
                        RecoverySurface(runtimeState, activeReplacement = true)
                    } else {
                        val signal = quickAddSignal
                        OpenTasksApp(
                            activity = this,
                            quickAddSignal = signal,
                            openTaskSignal = openTaskSignal,
                            openTaskId = openTaskId,
                            onOpenRecovery = { activeRecovery = true },
                        )
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun RecoverySurface(
        runtimeState: VaultRuntimeState,
        activeReplacement: Boolean = false,
    ) {
        val recoveryViewModel: RecoveryViewModel = viewModel()
        val presentation by recoveryViewModel.presentation.collectAsStateWithLifecycle()
        val resolutionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            result.data?.takeIf { result.resultCode == RESULT_OK }
                ?.let(recoveryViewModel::acceptResolution)
        }
        LaunchedEffect(recoveryViewModel) {
            for (pendingIntent in recoveryViewModel.resolutionEffects) {
                resolutionLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                )
            }
        }
        val mode = recoveryShellMode(
            runtimeRecovering = runtimeState is VaultRuntimeState.Recovering,
            presentation = presentation,
            activeReplacement = activeReplacement,
        )
        val candidates = (presentation as? RecoveryPresentation.Candidates)
            ?.values
            ?.map { RecoveryShellCandidate(it.handle, it.source.name == "GOOGLE_DRIVE") }
            .orEmpty()
        OpenTasksTheme {
            RecoveryShellScreen(
                mode = mode,
                candidates = candidates,
                takeoverGeneration =
                    (presentation as? RecoveryPresentation.TakeoverConfirmation)?.generation,
                failureReason = (presentation as? RecoveryPresentation.Failed)?.reason,
                onDiscoverDrive = recoveryViewModel::discoverDrive,
                onDiscoverPortable = recoveryViewModel::discoverPortable,
                onRestore = recoveryViewModel::restore,
                onConfirmTakeover = recoveryViewModel::confirmTakeover,
                onStartWithoutRestoring = recoveryViewModel::startWithoutRestoring,
                onRetryUnreadable = {
                    lifecycleScope.launch {
                        runCatching { vaultRuntimeManager.initialize() }
                    }
                },
            )
        }
    }

    /**
     * Tracks the runtime the composition is allowed to build against.
     *
     * A replaced slot publishes a different runtime, so the view models bound
     * to the previous one are cleared before the new composition can inject a
     * repository from the new slot.
     */
    private fun observeVaultRuntime() {
        lifecycleScope.launch {
            vaultRuntimeManager.state.collect { state ->
                // Services are opened before the composition that injects them
                // and closed before a composition can outlive its slot.
                activeVaultServices.onVaultRuntimeState(state)
                val next = (state as? VaultRuntimeState.Active)?.runtime
                if (next !== activeRuntime) {
                    if (activeRuntime != null) viewModelStore.clear()
                    activeRuntime = next
                    activeRecovery = false
                }
                runtimeState = state
            }
        }
    }

    /**
     * Re-drives initialisation whenever the user returns to the app.
     *
     * An open that failed while the device was locked leaves no runtime; the
     * call is a no-op once one is active.
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            runCatching { vaultRuntimeManager.initialize() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            QUICK_ADD_ACTION -> quickAddSignal++
            ReminderIntents.ACTION_OPEN_TASK -> {
                openTaskId = intent.getStringExtra(ReminderIntents.EXTRA_TASK_ID)
                if (openTaskId != null) openTaskSignal++
            }
        }
    }

    private companion object {
        const val QUICK_ADD_ACTION = "app.opentasks.action.QUICK_ADD"
    }
}

internal fun recoveryShellMode(
    runtimeRecovering: Boolean,
    presentation: RecoveryPresentation,
    activeReplacement: Boolean,
): RecoveryShellMode = when {
    presentation is RecoveryPresentation.TakeoverConfirmation ->
        RecoveryShellMode.TakeoverConfirmation
    runtimeRecovering -> RecoveryShellMode.Activating
    presentation == RecoveryPresentation.NoVault && activeReplacement ->
        RecoveryShellMode.ActiveReplacement
    presentation == RecoveryPresentation.NoVault -> RecoveryShellMode.NoVault
    presentation == RecoveryPresentation.UnreadableVault -> RecoveryShellMode.UnreadableVault
    presentation == RecoveryPresentation.Discovering -> RecoveryShellMode.Discovering
    presentation is RecoveryPresentation.Candidates -> RecoveryShellMode.Candidates
    presentation == RecoveryPresentation.Authenticating -> RecoveryShellMode.Authenticating
    presentation == RecoveryPresentation.Activating -> RecoveryShellMode.Activating
    presentation is RecoveryPresentation.Failed -> RecoveryShellMode.Failed
    else -> RecoveryShellMode.Activating
}
