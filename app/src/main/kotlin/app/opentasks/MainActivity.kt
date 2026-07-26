package app.opentasks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var quickAddSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.action == QUICK_ADD_ACTION) quickAddSignal++

        setContent {
            val signal = quickAddSignal
            OpenTasksApp(
                activity = this,
                quickAddSignal = signal,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == QUICK_ADD_ACTION) quickAddSignal++
    }

    private companion object {
        const val QUICK_ADD_ACTION = "app.opentasks.action.QUICK_ADD"
    }
}
