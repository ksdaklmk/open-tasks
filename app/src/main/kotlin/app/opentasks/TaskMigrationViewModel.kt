package app.opentasks

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentasks.core.data.export.GenericTasksCsvDocument
import app.opentasks.core.data.export.GenericTasksCsvFailure
import app.opentasks.core.data.export.GenericTasksCsvParseResult
import app.opentasks.core.data.export.TaskCsvTarget
import app.opentasks.core.data.export.describeGenericTasksCsv
import app.opentasks.core.data.export.parseGenericTasksCsv
import app.opentasks.core.data.export.reviewGenericTasksCsv
import app.opentasks.core.data.export.suggestTaskCsvMapping
import app.opentasks.core.data.export.MAX_TASKS_CSV_BYTES
import app.opentasks.core.domain.ImportedTaskRow
import app.opentasks.core.model.TaskCsvDateOrder
import app.opentasks.core.model.TaskCsvEstimateUnit
import app.opentasks.core.model.TaskCsvField
import app.opentasks.core.model.TaskCsvMapping
import app.opentasks.core.model.TaskCsvPriorityChoice
import app.opentasks.core.model.TaskCsvStatusChoice
import app.opentasks.core.model.TaskCsvTagMode
import app.opentasks.feature.more.TaskMigrationColumnUi
import app.opentasks.feature.more.TaskMigrationLoadFailure
import app.opentasks.feature.more.TaskMigrationSummaryUi
import app.opentasks.feature.more.TaskMigrationUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SelectedTaskMigrationDocument(
    val displayName: String,
    val bytes: ByteArray,
)

@HiltViewModel
class TaskMigrationViewModel internal constructor(
    private val readDocument: suspend (Uri) -> SelectedTaskMigrationDocument?,
    private val now: () -> Instant,
    private val zoneProvider: () -> ZoneId,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        readDocument = { uri -> context.readTaskMigrationDocument(uri) },
        now = Instant::now,
        zoneProvider = ZoneId::systemDefault,
        ioDispatcher = Dispatchers.IO,
    )

    private data class Draft(
        val document: GenericTasksCsvDocument,
        val mapping: TaskCsvMapping,
        val target: TaskCsvTarget,
        val zone: ZoneId,
        val displayName: String,
    )

    private val mutableState = MutableStateFlow<TaskMigrationUiState?>(null)
    private var draft: Draft? = null
    private var targetAtSelection: TaskCsvTarget? = null
    private var pendingWelcomeRows: List<ImportedTaskRow>? = null

    val openDocumentRequests = Channel<Unit>(Channel.BUFFERED)
    val state: StateFlow<TaskMigrationUiState?> = mutableState.asStateFlow()

    fun begin(target: TaskCsvTarget) {
        draft = null
        mutableState.value = null
        targetAtSelection = target
        openDocumentRequests.trySend(Unit)
    }

    fun onDocumentSelected(uri: Uri?) {
        if (uri == null) {
            if (draft == null) targetAtSelection = null
            return
        }
        viewModelScope.launch(ioDispatcher) {
            var selected: SelectedTaskMigrationDocument? = null
            try {
                selected = try {
                    readDocument(uri)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: SecurityException) {
                    null
                } catch (_: IOException) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (selected == null) {
                    draft = null
                    targetAtSelection = null
                    mutableState.value = TaskMigrationUiState.LoadFailure(
                        fileName = null,
                        reason = TaskMigrationLoadFailure.UNREADABLE,
                        rowNumber = null,
                    )
                } else {
                    acceptDocument(selected.displayName, selected.bytes)
                }
            } finally {
                selected?.bytes?.fill(0)
            }
        }
    }

    internal fun acceptDocument(displayName: String, bytes: ByteArray) {
        try {
            when (val parsed = parseGenericTasksCsv(bytes)) {
                is GenericTasksCsvParseResult.Failed -> {
                    draft = null
                    targetAtSelection = null
                    mutableState.value = TaskMigrationUiState.LoadFailure(
                        fileName = displayName,
                        reason = parsed.reason.toLoadFailure(),
                        rowNumber = parsed.rowNumber,
                    )
                }
                is GenericTasksCsvParseResult.Parsed -> {
                    val target = targetAtSelection ?: return
                    val nextDraft = Draft(
                        document = parsed.document,
                        mapping = suggestTaskCsvMapping(parsed.document),
                        target = target,
                        zone = zoneProvider(),
                        displayName = displayName,
                    )
                    draft = nextDraft
                    targetAtSelection = null
                    publish(nextDraft)
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    fun mapField(field: TaskCsvField, columnIndex: Int?) {
        updateMapping { current, document -> remap(current, field, columnIndex, document) }
    }

    fun chooseStatus(value: String, choice: TaskCsvStatusChoice) {
        updateMapping { current, _ ->
            current.copy(statusChoices = current.statusChoices + (value to choice))
        }
    }

    fun choosePriority(value: String, choice: TaskCsvPriorityChoice) {
        updateMapping { current, _ ->
            current.copy(priorityChoices = current.priorityChoices + (value to choice))
        }
    }

    fun chooseDateOrder(order: TaskCsvDateOrder) {
        updateMapping { current, _ -> current.copy(dateOrder = order) }
    }

    fun chooseEstimateUnit(unit: TaskCsvEstimateUnit) {
        updateMapping { current, _ -> current.copy(estimateUnit = unit) }
    }

    fun chooseTagMode(mode: TaskCsvTagMode) {
        updateMapping { current, _ -> current.copy(tagMode = mode) }
    }

    fun confirm(latestTarget: TaskCsvTarget): List<ImportedTaskRow>? {
        val current = draft ?: return null
        if ((mutableState.value as? TaskMigrationUiState.Review)?.isCommitting == true) return null
        val nextDraft = current.copy(target = latestTarget)
        val review = review(nextDraft)
        draft = nextDraft
        if (review.blockingIssues.isNotEmpty()) {
            mutableState.value = review.toUi(nextDraft, isCommitting = false)
            return null
        }
        mutableState.value = review.toUi(nextDraft, isCommitting = true)
        return review.rows
    }

    fun confirmForWelcome(latestTarget: TaskCsvTarget): Boolean {
        val rows = confirm(latestTarget) ?: return false
        pendingWelcomeRows = rows
        return true
    }

    fun takeWelcomeRows(): List<ImportedTaskRow>? =
        pendingWelcomeRows.also { pendingWelcomeRows = null }

    fun abandonWelcomeHandoff(): Boolean {
        if (pendingWelcomeRows == null) return false
        pendingWelcomeRows = null
        cancel()
        return true
    }

    fun onCommitFinished(success: Boolean) {
        if (success) {
            cancel()
        } else {
            (mutableState.value as? TaskMigrationUiState.Review)?.let { review ->
                mutableState.value = review.copy(isCommitting = false)
            }
        }
    }

    fun chooseAnother() {
        if (isCommitting()) return
        val current = draft ?: return
        targetAtSelection = current.target
        openDocumentRequests.trySend(Unit)
    }

    fun cancel() {
        pendingWelcomeRows = null
        draft = null
        targetAtSelection = null
        mutableState.value = null
    }

    private fun updateMapping(
        transform: (TaskCsvMapping, GenericTasksCsvDocument) -> TaskCsvMapping,
    ) {
        if (isCommitting()) return
        val current = draft ?: return
        val nextDraft = current.copy(mapping = transform(current.mapping, current.document))
        draft = nextDraft
        publish(nextDraft)
    }

    private fun publish(draft: Draft) {
        mutableState.value = review(draft).toUi(draft, isCommitting = false)
    }

    private fun review(draft: Draft) = reviewGenericTasksCsv(
        document = draft.document,
        mapping = draft.mapping,
        target = draft.target,
        zone = draft.zone,
        completionFallback = now(),
    )

    private fun isCommitting() =
        (mutableState.value as? TaskMigrationUiState.Review)?.isCommitting == true

    private fun app.opentasks.core.data.export.GenericTasksCsvReview.toUi(
        draft: Draft,
        isCommitting: Boolean,
    ) = TaskMigrationUiState.Review(
        fileName = draft.displayName,
        sourceRowCount = draft.document.rows.size,
        sourceColumnCount = draft.document.headers.size,
        columns = describeGenericTasksCsv(draft.document).map { column ->
            TaskMigrationColumnUi(column.index, column.header, column.samples)
        },
        mapping = draft.mapping,
        statusValues = statusValues,
        priorityValues = priorityValues,
        ambiguousDatesPresent = ambiguousDatesPresent,
        estimateValuesPresent = estimateValuesPresent,
        tagValuesPresent = tagValuesPresent,
        tagSamples = tagSamples,
        capturedZoneId = capturedZoneId,
        summary = TaskMigrationSummaryUi(
            readyTaskCount = rows.size,
            skippedTaskCount = skippedTaskCount,
            omittedValueCount = omittedValueCount,
            newProjectCount = newProjectCount,
            newTagCount = newTagCount,
        ),
        warnings = warnings,
        blockingIssues = blockingIssues,
        blockingMessage = blockingMessage,
        ignoredHeaders = ignoredColumnIndexes.map(draft.document.headers::get),
        isCommitting = isCommitting,
    )

    private fun GenericTasksCsvFailure.toLoadFailure() = when (this) {
        GenericTasksCsvFailure.TOO_LARGE -> TaskMigrationLoadFailure.TOO_LARGE
        GenericTasksCsvFailure.INVALID_UTF8 -> TaskMigrationLoadFailure.INVALID_UTF8
        GenericTasksCsvFailure.MALFORMED -> TaskMigrationLoadFailure.MALFORMED
        GenericTasksCsvFailure.TOO_MANY_ROWS -> TaskMigrationLoadFailure.TOO_MANY_ROWS
        GenericTasksCsvFailure.TOO_MANY_COLUMNS -> TaskMigrationLoadFailure.TOO_MANY_COLUMNS
        GenericTasksCsvFailure.MISSING_HEADER -> TaskMigrationLoadFailure.MISSING_HEADER
        GenericTasksCsvFailure.ROW_WIDER_THAN_HEADER -> TaskMigrationLoadFailure.ROW_WIDER_THAN_HEADER
    }
}

private fun remap(
    current: TaskCsvMapping,
    field: TaskCsvField,
    columnIndex: Int?,
    document: GenericTasksCsvDocument,
): TaskCsvMapping {
    val withoutField = current.columns - field
    val withoutDuplicate = if (columnIndex == null) {
        withoutField
    } else {
        withoutField.filterValues { it != columnIndex }
    }
    val columns = if (columnIndex == null) {
        withoutDuplicate
    } else {
        withoutDuplicate + (field to columnIndex)
    }
    val suggested = suggestTaskCsvMapping(document, columns)
    return current.copy(
        columns = columns,
        statusChoices = if (
            current.columns[TaskCsvField.STATUS] != columns[TaskCsvField.STATUS]
        ) suggested.statusChoices else current.statusChoices,
        priorityChoices = if (
            current.columns[TaskCsvField.PRIORITY] != columns[TaskCsvField.PRIORITY]
        ) suggested.priorityChoices else current.priorityChoices,
        estimateUnit = if (
            current.columns[TaskCsvField.ESTIMATE] != columns[TaskCsvField.ESTIMATE]
        ) suggested.estimateUnit else current.estimateUnit,
        tagMode = if (
            current.columns[TaskCsvField.TAGS] != columns[TaskCsvField.TAGS]
        ) suggested.tagMode else current.tagMode,
    )
}

private fun Context.readTaskMigrationDocument(uri: Uri): SelectedTaskMigrationDocument? {
    val displayName = taskMigrationDisplayName {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString)
                    ?.takeIf(String::isNotBlank)
            } else {
                null
            }
        }
    }
    val bytes = contentResolver.openInputStream(uri)?.use { stream ->
        stream.readNBytes(MAX_TASKS_CSV_BYTES + 1)
    } ?: return null
    return SelectedTaskMigrationDocument(displayName, bytes)
}

internal fun taskMigrationDisplayName(query: () -> String?): String = try {
    query()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    null
} ?: "tasks.csv"
