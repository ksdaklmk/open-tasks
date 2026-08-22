package app.opentasks.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.opentasks.core.data.DefaultVaultRuntimeManager
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.ImportedTaskRow
import app.opentasks.core.model.Priority
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BenchmarkFixtureReceiver : BroadcastReceiver() {
    @Inject
    lateinit var vaultRuntimeManager: DefaultVaultRuntimeManager

    override fun onReceive(context: Context, intent: Intent) {
        val size = intent.getIntExtra(EXTRA_SIZE, -1)
        if (intent.action != ACTION_FIXTURE || size !in SUPPORTED_SIZES) {
            resultCode = RESULT_REJECTED
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            pendingResult.resultCode = runCatching {
                val rows = List(size) { index -> benchmarkRow(index) }
                when (
                    vaultRuntimeManager.requireActive().repository.execute(
                        DomainCommand.ImportTasks(rows),
                    )
                ) {
                    is CommandResult.Success -> RESULT_SEEDED
                    is CommandResult.Rejected -> RESULT_FAILED
                }
            }.getOrDefault(RESULT_FAILED)
            pendingResult.finish()
        }
    }

    private fun benchmarkRow(index: Int) = ImportedTaskRow(
        sourceRowNumber = index + 2,
        title = "Benchmark task ${index.toString().padStart(5, '0')}",
        projectName = "Benchmark project ${index % 20}",
        statusName = null,
        priority = Priority.entries[index % Priority.entries.size],
        start = null,
        due = null,
        completedAt = null,
        estimateMinutes = (index % 240 + 1).toLong(),
        tagNames = listOf("Benchmark tag ${index % 10}"),
        description = "Synthetic benchmark row.",
    )

    companion object {
        const val ACTION_FIXTURE = "app.opentasks.action.BENCHMARK_FIXTURE"
        const val EXTRA_SIZE = "size"
        const val RESULT_SEEDED = 1
        const val RESULT_REJECTED = 2
        const val RESULT_FAILED = 3
        val SUPPORTED_SIZES = setOf(500, 5_000)
    }
}
