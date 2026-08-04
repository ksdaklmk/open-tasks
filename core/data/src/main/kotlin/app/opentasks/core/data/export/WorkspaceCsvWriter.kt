package app.opentasks.core.data.export

import app.opentasks.core.model.Note
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TimeEntry
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The four tables plaintext CSV export can produce; export-only, no import path. */
enum class CsvTable { TASKS, PROJECTS, TIME_ENTRIES, NOTES }

/**
 * Renders one [CsvTable] of a [WorkspaceSnapshot] as RFC 4180 CSV text.
 *
 * Pure Kotlin — no Android import anywhere in this file — so its behaviour is
 * proven entirely on the JVM. Every field is formula-injection neutralised
 * (a leading `=`, `+`, `-`, or `@` gets a `'` prefix) and RFC 4180 quoted
 * before it reaches [out]; the writer holds nothing beyond the one row it is
 * currently building. It is a plaintext writer only, never touching
 * encryption or Android storage — the caller streams [out] to wherever it is
 * going and owns any retention decision.
 */
class WorkspaceCsvWriter(private val zone: ZoneId) {

    fun write(table: CsvTable, snapshot: WorkspaceSnapshot, out: Appendable) {
        when (table) {
            CsvTable.TASKS -> writeTasks(snapshot, out)
            CsvTable.PROJECTS -> writeProjects(snapshot, out)
            CsvTable.TIME_ENTRIES -> writeTimeEntries(snapshot, out)
            CsvTable.NOTES -> writeNotes(snapshot, out)
        }
    }

    private fun writeTasks(snapshot: WorkspaceSnapshot, out: Appendable) {
        writeRow(out, TASKS_HEADER)
        val projectNames = snapshot.projects.associate { it.id to it.name }
        val statusNames = snapshot.workflowStatuses.associate { it.id to it.name }
        snapshot.tasks
            .filter { it.deletedAt == null }
            .forEach { task ->
                writeRow(
                    out,
                    listOf(
                        task.id.value,
                        task.title,
                        task.projectId?.let(projectNames::get).orEmpty(),
                        statusNames[task.statusId] ?: task.semanticStatus.name,
                        task.priority.name,
                        displayOfMoment(task.start),
                        isoOfMoment(task.start),
                        displayOfMoment(task.due),
                        isoOfMoment(task.due),
                        displayOfInstant(task.completedAt),
                        isoOfInstant(task.completedAt),
                        task.estimate?.toMinutes()?.toString().orEmpty(),
                        tagNames(snapshot, task.tagIds),
                        task.description,
                    ),
                )
            }
    }

    private fun writeProjects(snapshot: WorkspaceSnapshot, out: Appendable) {
        writeRow(out, PROJECTS_HEADER)
        snapshot.projects.forEach { project ->
            writeRow(
                out,
                listOf(
                    project.id.value,
                    project.name,
                    project.summary,
                    project.status.name,
                    displayOfDate(project.dueDate),
                    isoOfDate(project.dueDate),
                    project.completedTasks.toString(),
                    project.totalTasks.toString(),
                ),
            )
        }
    }

    private fun writeTimeEntries(snapshot: WorkspaceSnapshot, out: Appendable) {
        writeRow(out, TIME_ENTRIES_HEADER)
        val taskTitles = snapshot.tasks.associate { it.id to it.title }
        snapshot.timeEntries.forEach { entry ->
            writeRow(
                out,
                listOf(
                    entry.id.value,
                    entry.taskId.value,
                    taskTitles[entry.taskId].orEmpty(),
                    displayOfInstant(entry.startedAt),
                    isoOfInstant(entry.startedAt),
                    displayOfInstant(entry.stoppedAt),
                    isoOfInstant(entry.stoppedAt),
                    durationMinutes(entry),
                    entry.note,
                ),
            )
        }
    }

    private fun writeNotes(snapshot: WorkspaceSnapshot, out: Appendable) {
        writeRow(out, NOTES_HEADER)
        val taskTitles = snapshot.tasks.associate { it.id to it.title }
        val projectNames = snapshot.projects.associate { it.id to it.name }
        snapshot.notes.forEach { note ->
            writeRow(
                out,
                listOf(
                    note.id.value,
                    ownerType(note),
                    ownerId(note),
                    ownerTitle(note, taskTitles, projectNames),
                    displayOfInstant(note.createdAt),
                    isoOfInstant(note.createdAt),
                    isoOfInstant(note.editedAt),
                    note.body,
                ),
            )
        }
    }

    private fun ownerType(note: Note): String = if (note.taskId != null) "task" else "project"

    private fun ownerId(note: Note): String = note.taskId?.value ?: note.projectId?.value.orEmpty()

    private fun ownerTitle(
        note: Note,
        taskTitles: Map<TaskId, String>,
        projectNames: Map<ProjectId, String>,
    ): String = note.taskId?.let(taskTitles::get)
        ?: note.projectId?.let(projectNames::get)
        ?: ""

    private fun durationMinutes(entry: TimeEntry): String =
        entry.stoppedAt
            ?.let { Duration.between(entry.startedAt, it).toMinutes().toString() }
            .orEmpty()

    private fun tagNames(snapshot: WorkspaceSnapshot, tagIds: Set<TagId>): String =
        snapshot.tags.filter { it.id in tagIds }.joinToString(";") { it.name }

    private fun displayOfMoment(moment: ZonedMoment?): String =
        moment?.let { UK_DATE_TIME_FORMAT.format(it.instant.atZone(it.zone())) }.orEmpty()

    private fun isoOfMoment(moment: ZonedMoment?): String =
        moment?.let {
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(it.instant.atZone(it.zone()))
        }.orEmpty()

    private fun displayOfInstant(instant: Instant?): String =
        instant?.let { UK_DATE_TIME_FORMAT.format(it.atZone(zone)) }.orEmpty()

    private fun isoOfInstant(instant: Instant?): String =
        instant?.let(DateTimeFormatter.ISO_INSTANT::format).orEmpty()

    private fun displayOfDate(date: LocalDate?): String =
        date?.let(UK_DATE_FORMAT::format).orEmpty()

    private fun isoOfDate(date: LocalDate?): String =
        date?.let(DateTimeFormatter.ISO_LOCAL_DATE::format).orEmpty()

    private fun writeRow(out: Appendable, fields: List<String>) {
        fields.forEachIndexed { index, field ->
            if (index > 0) out.append(',')
            out.append(csvField(field))
        }
        out.append("\r\n")
    }

    private fun csvField(raw: String): String {
        val neutralised = if (raw.isNotEmpty() && raw[0] in FORMULA_PREFIXES) {
            "'$raw"
        } else {
            raw
        }
        val needsQuoting = neutralised.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        return if (needsQuoting) {
            "\"" + neutralised.replace("\"", "\"\"") + "\""
        } else {
            neutralised
        }
    }

    private companion object {
        val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@')

        val UK_DATE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", Locale.UK)
        val UK_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)

        val TASKS_HEADER = listOf(
            "id", "title", "project", "status", "priority", "start_display", "start_iso",
            "due_display", "due_iso", "completed_display", "completed_iso", "estimate_minutes",
            "tags", "description",
        )
        val PROJECTS_HEADER = listOf(
            "id", "name", "summary", "health", "due_display", "due_iso", "completed_tasks",
            "total_tasks",
        )
        val TIME_ENTRIES_HEADER = listOf(
            "id", "task_id", "task_title", "started_display", "started_iso", "stopped_display",
            "stopped_iso", "duration_minutes", "note",
        )
        val NOTES_HEADER = listOf(
            "id", "owner_type", "owner_id", "owner_title", "created_display", "created_iso",
            "edited_iso", "body",
        )
    }
}
