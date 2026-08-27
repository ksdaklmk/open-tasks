package app.opentasks.feature.more

import app.opentasks.core.model.TaskCsvBlockingIssue
import app.opentasks.core.model.TaskCsvMapping
import app.opentasks.core.model.TaskCsvWarning

enum class TaskMigrationLoadFailure {
    UNREADABLE,
    TOO_LARGE,
    INVALID_UTF8,
    MALFORMED,
    TOO_MANY_ROWS,
    TOO_MANY_COLUMNS,
    MISSING_HEADER,
    ROW_WIDER_THAN_HEADER,
}

data class TaskMigrationColumnUi(
    val index: Int,
    val header: String,
    val samples: List<String>,
)

data class TaskMigrationSummaryUi(
    val readyTaskCount: Int,
    val skippedTaskCount: Int,
    val omittedValueCount: Int,
    val newProjectCount: Int,
    val newTagCount: Int,
)

sealed interface TaskMigrationUiState {
    data class LoadFailure(
        val fileName: String?,
        val reason: TaskMigrationLoadFailure,
        val rowNumber: Int?,
    ) : TaskMigrationUiState

    data class Review(
        val fileName: String,
        val sourceRowCount: Int,
        val sourceColumnCount: Int,
        val columns: List<TaskMigrationColumnUi>,
        val mapping: TaskCsvMapping,
        val statusValues: List<String>,
        val priorityValues: List<String>,
        val ambiguousDatesPresent: Boolean,
        val estimateValuesPresent: Boolean,
        val tagValuesPresent: Boolean,
        val tagSamples: List<String>,
        val capturedZoneId: String,
        val summary: TaskMigrationSummaryUi,
        val warnings: List<TaskCsvWarning>,
        val blockingIssues: Set<TaskCsvBlockingIssue>,
        val blockingMessage: String?,
        val ignoredHeaders: List<String>,
        val isCommitting: Boolean,
    ) : TaskMigrationUiState {
        val canImport: Boolean
            get() = summary.readyTaskCount > 0 &&
                blockingIssues.isEmpty() &&
                !isCommitting
    }
}
