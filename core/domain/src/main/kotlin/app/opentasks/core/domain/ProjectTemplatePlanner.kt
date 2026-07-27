package app.opentasks.core.domain

import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Milestone
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.RelativeZonedMoment
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.Template
import app.opentasks.core.model.TemplateChecklistItem
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.TemplateMilestone
import app.opentasks.core.model.TemplateRecurrence
import app.opentasks.core.model.TemplateTask
import app.opentasks.core.model.TemplateWorkflowStatus
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.ZonedMoment
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

data class TemplateInstantiation(
    val project: Project,
    val workflowStatuses: List<WorkflowStatus>,
    val milestones: List<Milestone>,
    val tasks: List<Task>,
    val tagNamesByTaskId: Map<TaskId, Set<String>>,
)

object ProjectTemplatePlanner {
    const val MAX_TEMPLATE_TASKS = 500
    const val MAX_RELATIVE_DAY_OFFSET = 36_525L

    fun capture(
        templateId: TemplateId,
        templateName: String,
        project: Project,
        workflowStatuses: List<WorkflowStatus>,
        milestones: List<Milestone>,
        tasks: List<Task>,
        tags: List<Tag>,
        revision: Revision,
        fallbackAnchor: LocalDate,
    ): Template {
        val activeStatuses = workflowStatuses
            .filter { it.projectId == project.id && it.archivedAt == null }
            .sortedBy(WorkflowStatus::rank)
        require(activeStatuses.isNotEmpty()) { "A template needs an active workflow" }

        val openMilestones = milestones
            .filter { it.projectId == project.id && it.completedAt == null }
        val openTasks = tasks
            .filter {
                it.projectId == project.id &&
                    it.deletedAt == null &&
                    !it.isCompleted
            }
        require(openTasks.size <= MAX_TEMPLATE_TASKS) {
            "A template can contain up to $MAX_TEMPLATE_TASKS tasks"
        }

        val anchor = buildList {
            project.dueDate?.let(::add)
            openMilestones.mapNotNullTo(this, Milestone::dueDate)
            openTasks.forEach { task ->
                task.start?.localDate()?.let(::add)
                task.due?.localDate()?.let(::add)
                task.recurrence?.endDate?.let(::add)
            }
        }.minOrNull() ?: fallbackAnchor

        val dateSpan = buildList {
            project.dueDate?.let(::add)
            openMilestones.mapNotNullTo(this, Milestone::dueDate)
            openTasks.forEach { task ->
                task.start?.localDate()?.let(::add)
                task.due?.localDate()?.let(::add)
                task.recurrence?.endDate?.let(::add)
            }
        }.maxOfOrNull { date -> ChronoUnit.DAYS.between(anchor, date) } ?: 0L
        require(dateSpan <= MAX_RELATIVE_DAY_OFFSET) {
            "Template dates must fit within $MAX_RELATIVE_DAY_OFFSET days"
        }

        val statusById = activeStatuses.associateBy(WorkflowStatus::id)
        val fallbackStatusBySemantic = activeStatuses
            .groupBy(WorkflowStatus::semanticStatus)
            .mapValues { (_, statuses) -> statuses.first() }
        val includedTaskIds = openTasks.mapTo(hashSetOf(), Task::id)
        val includedMilestoneIds = openMilestones.mapTo(hashSetOf(), Milestone::id)
        val tagNames = tags.associate { it.id to it.name }

        return Template(
            id = templateId,
            workspaceId = project.workspaceId,
            name = templateName,
            projectName = project.name,
            projectSummary = project.summary,
            projectDueOffsetDays = project.dueDate?.offsetFrom(anchor),
            workflowStatuses = activeStatuses.map { status ->
                TemplateWorkflowStatus(
                    key = status.id.value,
                    name = status.name,
                    semanticStatus = status.semanticStatus,
                    rank = status.rank,
                )
            },
            milestones = openMilestones.map { milestone ->
                TemplateMilestone(
                    key = milestone.id.value,
                    name = milestone.name,
                    dueOffsetDays = milestone.dueDate?.offsetFrom(anchor),
                )
            },
            tasks = openTasks.map { task ->
                val status = statusById[task.statusId]
                    ?: fallbackStatusBySemantic.getValue(task.semanticStatus)
                TemplateTask(
                    key = task.id.value,
                    parentKey = task.parentTaskId
                        ?.takeIf(includedTaskIds::contains)
                        ?.value,
                    statusKey = status.id.value,
                    title = task.title,
                    description = task.description,
                    priority = task.priority,
                    start = task.start?.relativeTo(anchor),
                    due = task.due?.relativeTo(anchor),
                    recurrence = task.recurrence?.relativeTo(anchor),
                    estimateSeconds = task.estimate?.seconds,
                    milestoneKey = task.milestoneId
                        ?.takeIf(includedMilestoneIds::contains)
                        ?.value,
                    tagNames = task.tagIds.mapNotNullTo(linkedSetOf(), tagNames::get),
                    checklist = task.checklist.map { item ->
                        TemplateChecklistItem(
                            key = item.id,
                            text = item.text,
                            rank = item.rank,
                        )
                    },
                    dependencyKeys = task.dependencyIds
                        .filterTo(linkedSetOf(), includedTaskIds::contains)
                        .mapTo(linkedSetOf(), TaskId::value),
                )
            },
            revision = revision,
        )
    }

    fun instantiate(
        template: Template,
        projectId: ProjectId,
        projectName: String,
        anchorDate: LocalDate,
        revision: Revision,
    ): TemplateInstantiation {
        val statusIds = template.workflowStatuses.associate { status ->
            status.key to WorkflowStatusId(derivedId(projectId, "status", status.key))
        }
        val milestoneIds = template.milestones.associate { milestone ->
            milestone.key to MilestoneId(derivedId(projectId, "milestone", milestone.key))
        }
        val taskIds = template.tasks.associate { task ->
            task.key to TaskId(derivedId(projectId, "task", task.key))
        }

        val statuses = template.workflowStatuses.map { status ->
            WorkflowStatus(
                id = statusIds.getValue(status.key),
                projectId = projectId,
                name = status.name,
                semanticStatus = status.semanticStatus,
                rank = status.rank,
            )
        }
        val statusesByKey = template.workflowStatuses.associateBy(TemplateWorkflowStatus::key)
        val createdTasks = template.tasks.map { task ->
            val taskId = taskIds.getValue(task.key)
            val recurrence = task.recurrence?.at(anchorDate)
            val start = task.start?.at(anchorDate)
            val due = task.due?.at(anchorDate)
            Task(
                id = taskId,
                workspaceId = template.workspaceId,
                projectId = projectId,
                parentTaskId = task.parentKey?.let(taskIds::get),
                statusId = statusIds.getValue(task.statusKey),
                semanticStatus = statusesByKey.getValue(task.statusKey).semanticStatus,
                title = task.title,
                description = task.description,
                priority = task.priority,
                start = start,
                due = due,
                recurrence = recurrence,
                recurrenceSeriesId = taskId.takeIf { recurrence != null },
                recurrenceAnchor = (due ?: start).takeIf { recurrence != null },
                recurrenceOccurrenceIndex = 0.takeIf { recurrence != null },
                estimate = task.estimateSeconds?.let(java.time.Duration::ofSeconds),
                milestoneId = task.milestoneKey?.let(milestoneIds::get),
                checklist = task.checklist.map { item ->
                    ChecklistItem(
                        id = derivedId(projectId, "checklist:${task.key}", item.key),
                        text = item.text,
                        completed = false,
                        rank = item.rank,
                    )
                },
                dependencyIds = task.dependencyKeys.mapNotNullTo(
                    linkedSetOf(),
                    taskIds::get,
                ),
                blockedBy = task.dependencyKeys.mapNotNullTo(
                    linkedSetOf(),
                    taskIds::get,
                ),
                revision = revision,
            )
        }
        return TemplateInstantiation(
            project = Project(
                id = projectId,
                workspaceId = template.workspaceId,
                name = projectName,
                summary = template.projectSummary,
                status = ProjectHealth.ON_TRACK,
                dueDate = template.projectDueOffsetDays?.let(anchorDate::plusDays),
                completedTasks = 0,
                totalTasks = createdTasks.size,
            ),
            workflowStatuses = statuses,
            milestones = template.milestones.map { milestone ->
                Milestone(
                    id = milestoneIds.getValue(milestone.key),
                    projectId = projectId,
                    name = milestone.name,
                    dueDate = milestone.dueOffsetDays?.let(anchorDate::plusDays),
                )
            },
            tasks = createdTasks,
            tagNamesByTaskId = template.tasks.associate { task ->
                taskIds.getValue(task.key) to task.tagNames
            },
        )
    }

    private fun ZonedMoment.localDate(): LocalDate =
        instant.atZone(ZoneId.of(zoneId)).toLocalDate()

    private fun ZonedMoment.relativeTo(anchor: LocalDate): RelativeZonedMoment {
        val local = instant.atZone(ZoneId.of(zoneId))
        return RelativeZonedMoment(
            dayOffset = local.toLocalDate().offsetFrom(anchor),
            secondOfDay = local.toLocalTime().toSecondOfDay(),
            zoneId = zoneId,
        )
    }

    private fun RelativeZonedMoment.at(anchor: LocalDate): ZonedMoment {
        require(dayOffset in 0..MAX_RELATIVE_DAY_OFFSET)
        val localDateTime = anchor
            .plusDays(dayOffset)
            .atTime(LocalTime.ofSecondOfDay(secondOfDay.toLong()))
        return ZonedMoment(
            instant = localDateTime.atZone(ZoneId.of(zoneId)).toInstant(),
            zoneId = zoneId,
        )
    }

    private fun RecurrenceRule.relativeTo(anchor: LocalDate): TemplateRecurrence =
        TemplateRecurrence(
            frequency = frequency,
            interval = interval,
            weekdays = weekdays,
            count = count,
            endOffsetDays = endDate?.offsetFrom(anchor),
        )

    private fun TemplateRecurrence.at(anchor: LocalDate): RecurrenceRule =
        RecurrenceRule(
            frequency = frequency,
            interval = interval,
            weekdays = weekdays,
            count = count,
            endDate = endOffsetDays?.let(anchor::plusDays),
        )

    private fun LocalDate.offsetFrom(anchor: LocalDate): Long =
        ChronoUnit.DAYS.between(anchor, this).also { offset ->
            require(offset in 0..MAX_RELATIVE_DAY_OFFSET)
        }

    private fun derivedId(projectId: ProjectId, kind: String, sourceKey: String): String {
        val source = "${projectId.value}\u0000$kind\u0000$sourceKey"
            .toByteArray(StandardCharsets.UTF_8)
        return UUID.nameUUIDFromBytes(source).toString()
    }
}
