package app.opentasks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.opentasks.core.data.DefaultVaultRuntimeManager
import app.opentasks.core.data.LocalVaultRuntime
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.designsystem.OpenTasksTheme
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
    private var vaultCreationAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        observeVaultRuntime()

        setContent {
            val runtime = activeRuntime
            if (runtime == null) {
                // Nothing that reads vault data is composed before an active
                // runtime exists; Stage 3 recovery owns this surface next.
                OpenTasksTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {}
                }
            } else {
                val signal = quickAddSignal
                OpenTasksApp(
                    activity = this,
                    quickAddSignal = signal,
                    openTaskSignal = openTaskSignal,
                    openTaskId = openTaskId,
                )
            }
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
                }
                if (state is VaultRuntimeState.NoVault && !vaultCreationAttempted) {
                    vaultCreationAttempted = true
                    runCatching { vaultRuntimeManager.createNewVault() }
                }
            }
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
