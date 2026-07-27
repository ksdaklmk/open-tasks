package app.opentasks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.opentasks.reminders.ReminderIntents
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var quickAddSignal by mutableIntStateOf(0)
    private var openTaskSignal by mutableIntStateOf(0)
    private var openTaskId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            val signal = quickAddSignal
            OpenTasksApp(
                activity = this,
                quickAddSignal = signal,
                openTaskSignal = openTaskSignal,
                openTaskId = openTaskId,
            )
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
