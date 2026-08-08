package app.opentasks

import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
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
import androidx.core.content.ContextCompat
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
import app.opentasks.lock.AppLockController
import app.opentasks.lock.AppLockScreen
import app.opentasks.lock.AppLockSettings
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

    @Inject
    lateinit var appLockSettings: AppLockSettings

    @Inject
    lateinit var appLockController: AppLockController

    private var quickAddSignal by mutableIntStateOf(0)
    private var quickAddPrefillText by mutableStateOf<String?>(null)
    private var openTaskSignal by mutableIntStateOf(0)
    private var openTaskId by mutableStateOf<String?>(null)
    private var activeRuntime by mutableStateOf<LocalVaultRuntime?>(null)
    private var runtimeState by mutableStateOf<VaultRuntimeState>(VaultRuntimeState.Initializing)
    private var activeRecovery by mutableStateOf(false)
    private var biometricUnavailable by mutableStateOf(false)
    private var biometricCancellationSignal: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        observeVaultRuntime()
        applyPrivacyFlags()
        lifecycleScope.launch {
            appLockSettings.observe().collect { applyPrivacyFlags() }
        }

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
                    // Checked before `activeRecovery`: the in-app recovery
                    // shell exposes destructive vault-replacement actions
                    // (restore, takeover, start-without-restoring), so a
                    // background span that reaches the lock delay must not
                    // return to it unauthenticated. `NoVault`/`Unreadable`/
                    // `Recovering` above are unaffected -- there is no
                    // workspace to protect yet, and they must stay reachable.
                    val locked by appLockController.locked.collectAsStateWithLifecycle()
                    if (locked) {
                        LaunchedEffect(Unit) { promptUnlock() }
                        OpenTasksTheme {
                            AppLockScreen(
                                onUnlockClick = ::promptUnlock,
                                unlockUnavailable = biometricUnavailable,
                            )
                        }
                    } else if (activeRecovery) {
                        RecoverySurface(runtimeState, activeReplacement = true)
                    } else {
                        val signal = quickAddSignal
                        OpenTasksApp(
                            activity = this,
                            appLockSettings = appLockSettings,
                            quickAddSignal = signal,
                            quickAddPrefillText = quickAddPrefillText,
                            onQuickAddConsumed = { quickAddPrefillText = null },
                            openTaskSignal = openTaskSignal,
                            openTaskId = openTaskId,
                            onOpenRecovery = { activeRecovery = true },
                        )
                    }
                }
            }
        }
    }

    /**
     * Applies the recents-thumbnail and screenshot-blocking flags for the
     * settings' current values.
     *
     * Recents hiding follows the settings alone, not [appLockController]'s
     * runtime [AppLockController.locked] state: once either privacy feature
     * is turned on, the thumbnail must stay hidden even in the instant
     * before a background span reaches the lock delay.
     */
    private fun applyPrivacyFlags() {
        setRecentsScreenshotEnabled(
            !(appLockSettings.lockEnabled || appLockSettings.titlePrivacy),
        )
        if (appLockSettings.screenshotBlocking) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Shows the platform biometric prompt, with a device-credential
     * fallback, and unlocks on success. Triggered automatically when the
     * overlay appears and again by its "Unlock Open Tasks" button, so a
     * dismissed or failed attempt can always be retried.
     *
     * When the platform cannot show anything at all -- most commonly no
     * device credential is enrolled, but a transient hardware error hits
     * this same path -- [biometricUnavailable] tells the overlay to say so
     * instead of leaving the button looking like it did nothing.
     */
    private fun promptUnlock() {
        val biometricManager = getSystemService(BiometricManager::class.java)
        val allowedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (biometricManager == null ||
            biometricManager.canAuthenticate(allowedAuthenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            biometricUnavailable = true
            return
        }
        biometricUnavailable = false

        biometricCancellationSignal?.cancel()
        val cancellationSignal = CancellationSignal()
        biometricCancellationSignal = cancellationSignal

        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.app_lock_unlock_action))
            .setAllowedAuthenticators(allowedAuthenticators)
            .build()
        prompt.authenticate(
            cancellationSignal,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    appLockController.onUnlocked()
                }
            },
        )
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
        // The single activity makes a process-level foreground observer
        // unnecessary: every foregrounding of the app passes through here.
        appLockController.onAppForegrounded()
        lifecycleScope.launch {
            runCatching { vaultRuntimeManager.initialize() }
        }
    }

    override fun onStop() {
        super.onStop()
        biometricCancellationSignal?.cancel()
        appLockController.onAppBackgrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_QUICK_ADD, false) == true) {
            // An explicit quick-add trigger requests an empty sheet: the
            // non-null "" wins the composition-time `quickAddPrefillText ?:
            // quickAddSheetTitle` fallback in `OpenTasksApp` over any stale
            // title left behind by an earlier, already-consumed share, so a
            // same-pass remount while the sheet is open seeds empty rather
            // than resurrecting that share's text. Last explicit trigger
            // wins over a still-pending share too, including one shared
            // while locked and not yet opened.
            quickAddPrefillText = ""
            quickAddSignal++
            return
        }
        when (intent?.action) {
            QUICK_ADD_ACTION -> {
                // See the comment on the `EXTRA_OPEN_QUICK_ADD` branch above
                // -- the static launcher shortcut is the same kind of
                // explicit, no-prefill trigger as the widget tap.
                quickAddPrefillText = ""
                quickAddSignal++
            }
            Intent.ACTION_SEND -> {
                val prefill = quickAddPrefill(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
                if (prefill != null) {
                    quickAddPrefillText = prefill
                    quickAddSignal++
                }
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val prefill =
                    quickAddPrefill(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
                if (prefill != null) {
                    quickAddPrefillText = prefill
                    quickAddSignal++
                }
            }
            ReminderIntents.ACTION_OPEN_TASK -> {
                openTaskId = intent.getStringExtra(ReminderIntents.EXTRA_TASK_ID)
                if (openTaskId != null) openTaskSignal++
            }
        }
    }

    companion object {
        internal const val QUICK_ADD_ACTION = "app.opentasks.action.QUICK_ADD"

        /** Matches the Today widget's Quick Add action parameter key. */
        const val EXTRA_OPEN_QUICK_ADD = "open_quick_add"
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
