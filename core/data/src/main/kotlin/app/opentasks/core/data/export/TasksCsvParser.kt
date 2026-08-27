package app.opentasks.core.data.export

import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.ImportedTaskRow
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkspaceId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import java.time.Duration
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

const val MAX_TASKS_CSV_BYTES: Int = 5 * 1024 * 1024

sealed interface CsvParseResult {
    data class Parsed(val rows: List<ImportedTaskRow>) : CsvParseResult
    data class Malformed(val rowNumber: Int, val reason: String) : CsvParseResult
}

data class CsvImportPreviewSummary(
    val taskCount: Int,
    val newProjectCount: Int,
    val newTagCount: Int,
)

sealed interface CsvImportPreviewResult {
    data class Ready(val summary: CsvImportPreviewSummary) : CsvImportPreviewResult
    data class Invalid(
        val rowNumber: Int?,
        val reason: RejectionReason,
        val message: String,
    ) : CsvImportPreviewResult
}

internal data class PlannedImportedTask(
    val task: Task,
    val activity: ActivityEntry,
)

internal data class PlannedImportedProject(
    val project: Project,
    val statuses: List<WorkflowStatus>,
    val activity: ActivityEntry,
)

internal data class TasksImportPlan(
    val tasks: List<PlannedImportedTask>,
    val projects: List<PlannedImportedProject>,
    val tags: List<Tag>,
)

internal sealed interface TasksImportPlanResult {
    data class Ready(val plan: TasksImportPlan) : TasksImportPlanResult
    data class Invalid(val rejection: CommandResult.Rejected) : TasksImportPlanResult
}

fun parseTasksCsv(bytes: ByteArray): CsvParseResult {
    if (bytes.size > MAX_TASKS_CSV_BYTES) return malformed(0, "CSV exceeds 5 MiB")
    val text = when (val decoded = decodeCsvUtf8(bytes)) {
        is CsvTextResult.Ready -> decoded.text
        is CsvTextResult.Failed -> return malformed(0, "CSV is not valid UTF-8")
    }
    val records = when (
        val parsed = readCsvRecords(
            text,
            maxDataRows = MAX_IMPORT_ROWS,
            maxColumns = 14,
        )
    ) {
        is CsvRecordResult.Ready -> parsed.records
        is CsvRecordResult.Failed -> return malformed(
            parsed.rowNumber,
            when (parsed.kind) {
                CsvRecordFailureKind.INVALID_UTF8 -> "CSV is not valid UTF-8"
                CsvRecordFailureKind.MALFORMED -> parsed.message
                CsvRecordFailureKind.TOO_MANY_ROWS -> "Import at most $MAX_IMPORT_ROWS tasks"
                CsvRecordFailureKind.TOO_MANY_COLUMNS -> "Expected 14 fields"
            },
        )
    }
    if (records.firstOrNull() != WorkspaceCsvWriter.TASKS_HEADER) {
        return malformed(0, "CSV header does not match the Tasks export")
    }
    val rows = ArrayList<ImportedTaskRow>(records.size - 1)
    records.drop(1).forEachIndexed { index, fields ->
        val rowNumber = index + 1
        if (fields.size != WorkspaceCsvWriter.TASKS_HEADER.size) {
            return malformed(rowNumber, "Expected 14 fields")
        }
        when (val row = fields.toImportedTaskRow(rowNumber)) {
            is RowParseResult.Invalid -> return malformed(rowNumber, row.reason)
            is RowParseResult.Valid -> rows += row.row
        }
    }
    return CsvParseResult.Parsed(rows)
}

fun previewTasksImport(
    rows: List<ImportedTaskRow>,
    snapshot: WorkspaceSnapshot,
): CsvImportPreviewResult = when (val result = resolveTaskImport(rows, snapshot)) {
    is ResolutionResult.Invalid -> CsvImportPreviewResult.Invalid(
        rowNumber = result.rowNumber,
        reason = result.reason,
        message = result.message,
    )
    is ResolutionResult.Ready -> CsvImportPreviewResult.Ready(
        CsvImportPreviewSummary(
            taskCount = rows.size,
            newProjectCount = result.resolution.newProjectNames.size,
            newTagCount = result.resolution.newTagNames.size,
        ),
    )
}

internal fun buildTasksImportPlan(
    rows: List<ImportedTaskRow>,
    snapshot: WorkspaceSnapshot,
    workspaceId: WorkspaceId,
    revision: Revision,
    at: Instant,
    freshId: () -> String,
): TasksImportPlanResult {
    val resolution = when (val result = resolveTaskImport(rows, snapshot)) {
        is ResolutionResult.Invalid -> return TasksImportPlanResult.Invalid(result.rejection())
        is ResolutionResult.Ready -> result.resolution
    }
    val projects = resolution.newProjectNames.associateWith { name ->
        val project = Project(
            id = ProjectId(freshId()),
            workspaceId = workspaceId,
            name = name,
            summary = "",
            status = ProjectHealth.ON_TRACK,
            dueDate = null,
            completedTasks = 0,
            totalTasks = 0,
        )
        PlannedImportedProject(
            project = project,
            statuses = WorkflowStatus.defaults(project.id),
            activity = ActivityEntry(
                id = freshId(),
                taskId = null,
                projectId = project.id,
                kind = ActivityKind.RECORD_CREATED,
                body = "Created",
                createdAt = at,
            ),
        )
    }
    val tags = resolution.newTagNames.associateWith { name ->
        Tag(TagId(freshId()), workspaceId, name)
    }
    val tasks = resolution.rows.map { resolved ->
        val project = when (val selected = resolved.project) {
            is ProjectSelection.Existing -> selected.project
            is ProjectSelection.New -> projects.getValue(selected.name).project
            ProjectSelection.Inbox -> null
        }
        val workflow = when (val selected = resolved.status) {
            is StatusSelection.Existing -> when (val selectedProject = resolved.project) {
                is ProjectSelection.New -> projects.getValue(selectedProject.name).statuses.first {
                    it.name == selected.status.name
                }
                else -> selected.status
            }
            is StatusSelection.Default -> {
                val statuses = if (project == null) {
                    snapshot.workflowStatuses.filter { it.projectId == null }
                } else {
                    projects[project.name]?.statuses
                        ?: snapshot.workflowStatuses.filter { it.projectId == project.id }
                }
                statuses
                    .filter {
                        it.archivedAt == null && it.semanticStatus == selected.semanticStatus
                    }
                    .minBy(WorkflowStatus::rank)
            }
        }
        val tagIds = resolved.tags.mapTo(linkedSetOf()) { selected ->
            when (selected) {
                is TagSelection.Existing -> selected.tag.id
                is TagSelection.New -> tags.getValue(selected.name).id
            }
        }
        val task = Task(
            id = TaskId(freshId()),
            workspaceId = workspaceId,
            projectId = project?.id,
            statusId = workflow.id,
            semanticStatus = workflow.semanticStatus,
            title = resolved.source.title.trim(),
            description = resolved.source.description,
            priority = resolved.source.priority,
            start = resolved.source.start,
            due = resolved.source.due,
            estimate = resolved.source.estimateMinutes?.let(Duration::ofMinutes),
            tagIds = tagIds,
            completedAt = resolved.source.completedAt,
            revision = revision,
        )
        PlannedImportedTask(
            task = task,
            activity = ActivityEntry(
                id = freshId(),
                taskId = task.id,
                projectId = task.projectId,
                kind = ActivityKind.RECORD_CREATED,
                body = "Created",
                createdAt = at,
            ),
        )
    }
    return TasksImportPlanResult.Ready(
        TasksImportPlan(tasks, projects.values.toList(), tags.values.toList()),
    )
}

private sealed interface RowParseResult {
    data class Valid(val row: ImportedTaskRow) : RowParseResult
    data class Invalid(val reason: String) : RowParseResult
}

private fun List<String>.toImportedTaskRow(rowNumber: Int): RowParseResult {
    val title = reverseFormula(get(1)).trim()
    val project = reverseFormula(get(2)).trim().ifBlank { null }
    val status = reverseFormula(get(3)).trim().ifBlank { null }
    val priorityName = reverseFormula(get(4)).ifBlank { Priority.NONE.name }
    val priority = Priority.entries.firstOrNull { it.name == priorityName }
        ?: return RowParseResult.Invalid("Priority is invalid")
    val start = parseMoment(reverseFormula(get(6)))
        ?: if (get(6).isBlank()) null else return RowParseResult.Invalid("Start is not ISO offset time")
    val due = parseMoment(reverseFormula(get(8)))
        ?: if (get(8).isBlank()) null else return RowParseResult.Invalid("Due is not ISO offset time")
    val completedText = reverseFormula(get(10))
    val completed = if (completedText.isBlank()) null else try {
        Instant.from(DateTimeFormatter.ISO_INSTANT.parse(completedText))
    } catch (_: DateTimeParseException) {
        return RowParseResult.Invalid("Completion is not an ISO instant")
    }
    val estimateText = reverseFormula(get(11))
    val estimate = if (estimateText.isBlank()) null else estimateText.toLongOrNull()
        ?.takeIf { it in 1..MAX_DURATION_MINUTES }
        ?: return RowParseResult.Invalid("Estimate must be positive minutes")
    val tags = when (val parsed = parseTags(reverseFormula(get(12)))) {
        is TagParseResult.Invalid -> return RowParseResult.Invalid(parsed.reason)
        is TagParseResult.Valid -> parsed.tags
    }
    val description = reverseFormula(get(13))
    return when {
        title.isBlank() -> RowParseResult.Invalid("Title is blank")
        title.length > 240 -> RowParseResult.Invalid("Title exceeds 240 characters")
        description.length > 20_000 -> RowParseResult.Invalid("Description exceeds 20000 characters")
        project != null && project.length > 120 -> RowParseResult.Invalid("Project exceeds 120 characters")
        status != null && status.length > 64 -> RowParseResult.Invalid("Status exceeds 64 characters")
        tags.size > 50 -> RowParseResult.Invalid("More than 50 unique tags")
        tags.any { it.isBlank() } -> RowParseResult.Invalid("Tag is blank")
        tags.any { it.length > 64 } -> RowParseResult.Invalid("Tag exceeds 64 characters")
        else -> RowParseResult.Valid(
            ImportedTaskRow(
                sourceRowNumber = rowNumber,
                title = title,
                projectName = project,
                statusName = status,
                priority = priority,
                start = start,
                due = due,
                completedAt = completed,
                estimateMinutes = estimate,
                tagNames = tags,
                description = description,
            ),
        )
    }
}

private fun parseMoment(text: String): ZonedMoment? {
    if (text.isBlank()) return null
    return try {
        OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).let {
            ZonedMoment(it.toInstant(), it.offset.id)
        }
    } catch (_: DateTimeParseException) {
        null
    }
}

private sealed interface TagParseResult {
    data class Valid(val tags: List<String>) : TagParseResult
    data class Invalid(val reason: String) : TagParseResult
}

private fun parseTags(text: String): TagParseResult {
    if (text.isBlank()) return TagParseResult.Valid(emptyList())
    val tags = mutableListOf<String>()
    val tag = StringBuilder()
    var index = 0
    while (index < text.length) {
        when (val char = text[index]) {
            ';' -> {
                tags += tag.toString().trim()
                tag.setLength(0)
            }
            '\\' -> {
                if (index + 1 >= text.length || text[index + 1] !in charArrayOf('\\', ';')) {
                    return TagParseResult.Invalid("Tag list has an invalid escape")
                }
                tag.append(text[++index])
            }
            else -> tag.append(char)
        }
        index++
    }
    tags += tag.toString().trim()
    return TagParseResult.Valid(tags.distinct())
}

private fun reverseFormula(value: String): String {
    val apostrophes = value.indexOfFirst { it != '\'' }.let { if (it < 0) value.length else it }
    return if (
        apostrophes % 2 == 1 &&
        apostrophes < value.length &&
        value[apostrophes] in FORMULA_PREFIXES
    ) {
        "'".repeat((apostrophes - 1) / 2) + value.drop(apostrophes)
    } else value
}

private sealed interface ResolutionResult {
    data class Ready(val resolution: ImportResolution) : ResolutionResult
    data class Invalid(
        val rowNumber: Int?,
        val reason: RejectionReason,
        val message: String,
    ) : ResolutionResult {
        fun rejection() = CommandResult.Rejected(reason, message)
    }
}

private data class ImportResolution(
    val rows: List<ResolvedRow>,
    val newProjectNames: List<String>,
    val newTagNames: List<String>,
)

private data class ResolvedRow(
    val source: ImportedTaskRow,
    val project: ProjectSelection,
    val status: StatusSelection,
    val tags: List<TagSelection>,
)

private sealed interface ProjectSelection {
    data object Inbox : ProjectSelection
    data class Existing(val project: Project) : ProjectSelection
    data class New(val name: String) : ProjectSelection
}

private sealed interface StatusSelection {
    data class Existing(val status: WorkflowStatus) : StatusSelection
    data class Default(val semanticStatus: SemanticStatus) : StatusSelection
}

private sealed interface TagSelection {
    data class Existing(val tag: Tag) : TagSelection
    data class New(val name: String) : TagSelection
}

/** The single pure collision/fallback pass shared by preview and repository planning. */
private fun resolveTaskImport(
    rows: List<ImportedTaskRow>,
    snapshot: WorkspaceSnapshot,
): ResolutionResult {
    if (rows.isEmpty()) return invalid(null, RejectionReason.IMPORT_EMPTY, "The CSV has no tasks.")
    if (rows.size > MAX_IMPORT_ROWS) {
        return invalid(null, RejectionReason.IMPORT_TOO_LARGE, "Import at most $MAX_IMPORT_ROWS tasks.")
    }
    val activeProjects = snapshot.projects.filter { it.archivedAt == null }
    val existingProjectsByExact = activeProjects.associateBy(Project::name)
    val existingProjectsByFolded = activeProjects.associateBy { it.name.folded() }
    val existingTagsByExact = snapshot.tags.associateBy(Tag::name)
    val existingTagsByFolded = snapshot.tags.associateBy { it.name.folded() }
    val newProjectsByExact = linkedMapOf<String, String>()
    val newProjectsByFolded = linkedMapOf<String, String>()
    val newTagsByExact = linkedMapOf<String, String>()
    val newTagsByFolded = linkedMapOf<String, String>()
    val resolved = mutableListOf<ResolvedRow>()
    rows.forEach { row ->
        validateRow(row)?.let { return it }
        val projectName = row.projectName?.trim()?.ifBlank { null }
        val project = if (projectName == null) {
            ProjectSelection.Inbox
        } else {
            val folded = projectName.folded()
            when {
                existingProjectsByExact[projectName] != null ->
                    ProjectSelection.Existing(existingProjectsByExact.getValue(projectName))
                existingProjectsByFolded[folded] != null ->
                    return collision(row, "project", projectName)
                newProjectsByExact[projectName] != null ->
                    ProjectSelection.New(newProjectsByExact.getValue(projectName))
                newProjectsByFolded[folded] != null ->
                    return collision(row, "project", projectName)
                newProjectsByExact.size >= MAX_IMPORTED_NEW_PROJECTS -> return invalid(
                    row.sourceRowNumber,
                    RejectionReason.IMPORT_TOO_LARGE,
                    "Import at most $MAX_IMPORTED_NEW_PROJECTS new projects.",
                )
                else -> {
                    newProjectsByExact[projectName] = projectName
                    newProjectsByFolded[folded] = projectName
                    ProjectSelection.New(projectName)
                }
            }
        }
        val availableStatuses = when (project) {
            is ProjectSelection.Existing -> snapshot.workflowStatuses.filter {
                it.projectId == project.project.id && it.archivedAt == null
            }
            is ProjectSelection.New -> WorkflowStatus.defaults(ProjectId("preview:${project.name}"))
            ProjectSelection.Inbox -> snapshot.workflowStatuses.filter {
                it.projectId == null && it.archivedAt == null
            }
        }
        val semanticHint = row.statusSemantic
        if (
            semanticHint != null && semanticHint !in setOf(
                SemanticStatus.BACKLOG,
                SemanticStatus.STARTED,
                SemanticStatus.COMPLETED,
            )
        ) {
            return invalid(
                row.sourceRowNumber,
                RejectionReason.IMPORT_STATUS_CONFLICT,
                "CSV row ${row.sourceRowNumber} uses an unsupported mapped status.",
            )
        }
        val statusName = row.statusName?.trim()?.ifBlank { null }
        val exact = statusName?.let { name -> availableStatuses.firstOrNull { it.name == name } }
        if (
            semanticHint == null && exact == null && statusName != null &&
            availableStatuses.any { it.name.equals(statusName, ignoreCase = true) }
        ) return collision(row, "status", statusName)
        val semanticMatch = semanticHint?.let { requested ->
            availableStatuses
                .filter { it.semanticStatus == requested }
                .minByOrNull(WorkflowStatus::rank)
        }
        val status = when {
            row.completedAt != null -> {
                val completed = exact?.takeIf { it.semanticStatus == SemanticStatus.COMPLETED }
                    ?: availableStatuses
                        .filter { it.semanticStatus == SemanticStatus.COMPLETED }
                        .minByOrNull(WorkflowStatus::rank)
                    ?: return invalid(
                        row.sourceRowNumber,
                        RejectionReason.IMPORT_STATUS_CONFLICT,
                        "CSV row ${row.sourceRowNumber} has no active completed status.",
                    )
                StatusSelection.Existing(completed)
            }
            semanticHint == SemanticStatus.COMPLETED -> return invalid(
                row.sourceRowNumber,
                RejectionReason.IMPORT_STATUS_CONFLICT,
                "CSV row ${row.sourceRowNumber} uses a completed status without a completion instant.",
            )
            semanticHint != null -> StatusSelection.Existing(
                exact?.takeIf { it.semanticStatus == semanticHint }
                    ?: semanticMatch
                    ?: return invalid(
                        row.sourceRowNumber,
                        RejectionReason.IMPORT_STATUS_CONFLICT,
                        "CSV row ${row.sourceRowNumber} has no active mapped status.",
                    ),
            )
            exact?.semanticStatus == SemanticStatus.COMPLETED -> return invalid(
                row.sourceRowNumber,
                RejectionReason.IMPORT_STATUS_CONFLICT,
                "CSV row ${row.sourceRowNumber} uses a completed status without a completion instant.",
            )
            exact != null -> StatusSelection.Existing(exact)
            else -> StatusSelection.Default(SemanticStatus.BACKLOG)
        }
        if (
            status is StatusSelection.Default &&
            availableStatuses.none { it.semanticStatus == status.semanticStatus }
        ) {
            return invalid(
                row.sourceRowNumber,
                RejectionReason.IMPORT_STATUS_CONFLICT,
                "CSV row ${row.sourceRowNumber} has no active ${status.semanticStatus.name.lowercase()} status.",
            )
        }
        val tags = row.tagNames.map { rawName ->
            val name = rawName.trim()
            val folded = name.folded()
            when {
                existingTagsByExact[name] != null ->
                    TagSelection.Existing(existingTagsByExact.getValue(name))
                existingTagsByFolded[folded] != null -> return collision(row, "tag", name)
                newTagsByExact[name] != null -> TagSelection.New(newTagsByExact.getValue(name))
                newTagsByFolded[folded] != null -> return collision(row, "tag", name)
                newTagsByExact.size >= MAX_IMPORTED_NEW_TAGS -> return invalid(
                    row.sourceRowNumber,
                    RejectionReason.IMPORT_TOO_LARGE,
                    "Import at most $MAX_IMPORTED_NEW_TAGS new tags.",
                )
                else -> {
                    newTagsByExact[name] = name
                    newTagsByFolded[folded] = name
                    TagSelection.New(name)
                }
            }
        }
        resolved += ResolvedRow(row, project, status, tags)
    }
    return ResolutionResult.Ready(
        ImportResolution(
            resolved,
            newProjectsByExact.keys.toList(),
            newTagsByExact.keys.toList(),
        ),
    )
}

private fun String.folded(): String = lowercase(Locale.ROOT)

private fun validateRow(row: ImportedTaskRow): ResolutionResult.Invalid? {
    val estimate = row.estimateMinutes
    return when {
        row.sourceRowNumber <= 0 -> invalid(
            null,
            RejectionReason.INVALID_STATE,
            "CSV row number is invalid.",
        )
        row.title.trim().isEmpty() -> invalid(
            row.sourceRowNumber,
            RejectionReason.EMPTY_TITLE,
            rowMessage(row, "has a blank title"),
        )
        row.title.trim().length > 240 -> invalid(
            row.sourceRowNumber,
            RejectionReason.TITLE_TOO_LONG,
            rowMessage(row, "has a title over 240 characters"),
        )
        row.description.length > 20_000 -> invalid(
            row.sourceRowNumber,
            RejectionReason.DESCRIPTION_TOO_LONG,
            rowMessage(row, "has a description over 20000 characters"),
        )
        (row.projectName?.trim()?.length ?: 0) > 120 -> invalid(
            row.sourceRowNumber,
            RejectionReason.PROJECT_NAME_TOO_LONG,
            rowMessage(row, "has a project name over 120 characters"),
        )
        (row.statusName?.trim()?.length ?: 0) > 64 -> invalid(
            row.sourceRowNumber,
            RejectionReason.WORKFLOW_STATUS_NAME_TOO_LONG,
            rowMessage(row, "has a status name over 64 characters"),
        )
        row.tagNames.map(String::trim).distinct().size > 50 -> invalid(
            row.sourceRowNumber,
            RejectionReason.TAG_LIMIT_REACHED,
            rowMessage(row, "has more than 50 tags"),
        )
        row.tagNames.any { it.trim().isEmpty() } -> invalid(
            row.sourceRowNumber,
            RejectionReason.EMPTY_TAG_NAME,
            rowMessage(row, "has a blank tag"),
        )
        row.tagNames.any { it.trim().length > 64 } -> invalid(
            row.sourceRowNumber,
            RejectionReason.TAG_NAME_TOO_LONG,
            rowMessage(row, "has a tag over 64 characters"),
        )
        estimate != null && estimate !in 1..MAX_DURATION_MINUTES -> invalid(
            row.sourceRowNumber,
            RejectionReason.INVALID_STATE,
            rowMessage(row, "has invalid estimate minutes"),
        )
        listOfNotNull(row.start, row.due).any { !isOffsetId(it.zoneId) } -> invalid(
            row.sourceRowNumber,
            RejectionReason.INVALID_STATE,
            rowMessage(row, "has an invalid offset"),
        )
        else -> null
    }
}

private fun isOffsetId(value: String): Boolean = try {
    ZoneOffset.of(value)
    true
} catch (_: DateTimeException) {
    false
}

private fun collision(row: ImportedTaskRow, kind: String, name: String) = invalid(
    row.sourceRowNumber,
    RejectionReason.IMPORT_NAME_COLLISION,
    "CSV row ${row.sourceRowNumber} has a case-only $kind collision for '$name'.",
)

private fun rowMessage(row: ImportedTaskRow, problem: String) = "CSV row ${row.sourceRowNumber} $problem."

private fun invalid(row: Int?, reason: RejectionReason, message: String) =
    ResolutionResult.Invalid(row, reason, message)

private fun malformed(rowNumber: Int, reason: String) = CsvParseResult.Malformed(rowNumber, reason)

private const val MAX_IMPORTED_NEW_PROJECTS = 500
private const val MAX_IMPORTED_NEW_TAGS = 1_000
private const val MAX_DURATION_MINUTES = Long.MAX_VALUE / 60
private val FORMULA_PREFIXES = setOf('=', '+', '-', '@', '\t', '\r', '\n')
