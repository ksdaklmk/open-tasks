package app.opentasks.core.data.export

import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Task
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Streams one project's active work as CommonMark-safe plaintext. */
class ProjectMarkdownWriter {

    fun write(projectId: ProjectId, snapshot: WorkspaceSnapshot, out: Appendable) {
        val project = checkNotNull(snapshot.projects.firstOrNull { it.id == projectId })
        out.append("# ").append(escaped(project.name)).append('\n')
        if (project.summary.isNotEmpty()) {
            out.append('\n').append(escaped(project.summary)).append('\n')
        }
        project.dueDate?.let { dueDate ->
            out.append('\n').append("Due ").append(display(dueDate)).append('\n')
        }

        val milestones = snapshot.milestones.filter { it.projectId == projectId }
        if (milestones.isNotEmpty()) {
            out.append("\n## Milestones\n\n")
            milestones.forEach { milestone ->
                out.append(if (milestone.completedAt != null) "- [x] " else "- [ ] ")
                    .append(escaped(milestone.name))
                milestone.dueDate?.let { dueDate -> out.append(" — ").append(display(dueDate)) }
                out.append('\n')
            }
        }

        snapshot.workflowStatuses
            .asSequence()
            .filter { it.projectId == projectId && it.archivedAt == null }
            .sortedBy { it.rank }
            .forEach { status ->
                val tasks = snapshot.tasks.filter {
                    it.projectId == projectId && it.statusId == status.id && it.deletedAt == null
                }
                if (tasks.isEmpty()) return@forEach
                out.append("\n## ").append(escaped(status.name)).append("\n\n")
                tasks.forEach { task -> writeTask(task, snapshot, out) }
            }
    }

    private fun writeTask(task: Task, snapshot: WorkspaceSnapshot, out: Appendable) {
        out.append(if (task.isCompleted) "- [x] " else "- [ ] ").append(escaped(task.title))
        task.due?.let { due -> out.append(" — due ").append(display(due)) }
        snapshot.tags.filter { it.id in task.tagIds }.forEach { tag ->
            out.append(" \\#").append(escaped(tag.name))
        }
        out.append('\n')
        task.checklist.sortedBy { it.rank }.forEach { item ->
            out.append(if (item.completed) "  - [x] " else "  - [ ] ")
                .append(escaped(item.text))
                .append('\n')
        }
    }

    private fun display(date: LocalDate): String = UK_DATE_FORMAT.format(date)

    private fun display(moment: ZonedMoment): String =
        UK_DATE_TIME_FORMAT.format(moment.instant.atZone(moment.zone()))

    private fun escaped(raw: String): String = raw
        .replace(Regex("[\\r\\n]+"), " ")
        .map { character -> if (character in COMMONMARK_PUNCTUATION) "\\$character" else "$character" }
        .joinToString("")

    private companion object {
        val UK_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)
        val UK_DATE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", Locale.UK)
        const val COMMONMARK_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    }
}
