package app.opentasks.core.data

import app.opentasks.core.data.db.TemplateEntity
import app.opentasks.core.domain.ProjectTemplatePlanner
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Priority
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RelativeZonedMoment
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Template
import app.opentasks.core.model.TemplateChecklistItem
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.TemplateMilestone
import app.opentasks.core.model.TemplateRecurrence
import app.opentasks.core.model.TemplateTask
import app.opentasks.core.model.TemplateWorkflowStatus
import app.opentasks.core.model.WorkspaceId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.Locale

internal object TemplatePayloadCodec {
    const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
    private const val FORMAT_VERSION = 1
    private const val MAX_TEMPLATE_NAME_LENGTH = 120
    private const val MAX_PROJECT_NAME_LENGTH = 120
    private const val MAX_PROJECT_SUMMARY_LENGTH = 1_000
    private const val MAX_WORKFLOW_STATUSES = 20
    private const val MAX_WORKFLOW_STATUS_NAME_LENGTH = 64
    private const val MAX_MILESTONES = 100
    private const val MAX_MILESTONE_NAME_LENGTH = 120
    private const val MAX_TASK_TITLE_LENGTH = 240
    private const val MAX_TASK_DESCRIPTION_LENGTH = 20_000
    private const val MAX_CHECKLIST_ITEMS = 200
    private const val MAX_CHECKLIST_ITEM_LENGTH = 500
    private const val MAX_TASK_TAGS = 50
    private const val MAX_TAG_NAME_LENGTH = 64
    private const val MAX_TASK_DEPENDENCIES = 100
    private const val MAX_KEY_LENGTH = 200
    private const val MAX_ZONE_ID_LENGTH = 64
    private const val MAX_RANK_LENGTH = 200
    private const val MAX_RECURRENCE_INTERVAL = 999
    private const val MAX_RECURRENCE_COUNT = 9_999

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(template: Template): ByteArray {
        validate(template)
        val bytes = json.encodeToString(TemplatePayload.from(template))
            .toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PAYLOAD_BYTES) {
            "Template payload exceeds $MAX_PAYLOAD_BYTES bytes"
        }
        return bytes
    }

    fun decode(entity: TemplateEntity): Template {
        require(entity.encryptedPayload.size <= MAX_PAYLOAD_BYTES) {
            "Template payload exceeds $MAX_PAYLOAD_BYTES bytes"
        }
        val payload = json.decodeFromString<TemplatePayload>(
            entity.encryptedPayload.toString(Charsets.UTF_8),
        )
        require(payload.formatVersion == FORMAT_VERSION) {
            "Unsupported template format ${payload.formatVersion}"
        }
        return payload.toModel(entity).also(::validate)
    }

    fun validate(template: Template) {
        template.id.value.validateKey()
        template.workspaceId.value.validateKey()
        require(
            template.name.isNotBlank() &&
                template.name == template.name.trim() &&
                template.name.length <= MAX_TEMPLATE_NAME_LENGTH,
        )
        template.revision.deviceId.value.validateKey()
        require(template.revision.wallTimeMillis >= 0)
        require(template.revision.logicalCounter >= 0)
        require(template.projectName.isNotBlank())
        require(template.projectName == template.projectName.trim())
        require(template.projectName.length <= MAX_PROJECT_NAME_LENGTH)
        require(template.projectSummary.length <= MAX_PROJECT_SUMMARY_LENGTH)
        template.projectDueOffsetDays?.validateOffset()
        require(template.workflowStatuses.size in 5..MAX_WORKFLOW_STATUSES)
        require(template.milestones.size <= MAX_MILESTONES)
        require(template.tasks.size <= ProjectTemplatePlanner.MAX_TEMPLATE_TASKS)

        val statusKeys = template.workflowStatuses.mapTo(hashSetOf()) { status ->
            status.key.validateKey()
            require(status.name.isNotBlank())
            require(status.name == status.name.trim())
            require(status.name.length <= MAX_WORKFLOW_STATUS_NAME_LENGTH)
            require(status.rank.isNotBlank() && status.rank.length <= MAX_RANK_LENGTH)
            status.key
        }
        require(statusKeys.size == template.workflowStatuses.size)
        require(
            template.workflowStatuses
                .map { it.name.lowercase(Locale.ROOT) }
                .toSet()
                .size == template.workflowStatuses.size,
        )
        require(
            template.workflowStatuses
                .map(TemplateWorkflowStatus::rank)
                .toSet()
                .size == template.workflowStatuses.size,
        )
        SemanticStatus.entries.forEach { semantic ->
            require(template.workflowStatuses.any { it.semanticStatus == semantic })
        }

        val milestoneKeys = template.milestones.mapTo(hashSetOf()) { milestone ->
            milestone.key.validateKey()
            require(milestone.name.isNotBlank())
            require(milestone.name == milestone.name.trim())
            require(milestone.name.length <= MAX_MILESTONE_NAME_LENGTH)
            milestone.dueOffsetDays?.validateOffset()
            milestone.key
        }
        require(milestoneKeys.size == template.milestones.size)
        require(
            template.milestones
                .map { it.name.lowercase(Locale.ROOT) }
                .toSet()
                .size == template.milestones.size,
        )

        val taskKeys = template.tasks.mapTo(hashSetOf()) { task ->
            task.key.validateKey()
            task.key
        }
        require(taskKeys.size == template.tasks.size)
        template.tasks.forEach { task ->
            require(task.statusKey in statusKeys)
            require(task.parentKey == null || task.parentKey in taskKeys)
            require(task.title.isNotBlank() && task.title.length <= MAX_TASK_TITLE_LENGTH)
            require(task.title == task.title.trim())
            require(task.description.length <= MAX_TASK_DESCRIPTION_LENGTH)
            task.start?.validate()
            task.due?.validate()
            task.recurrence?.validate()
            require(task.recurrence == null || task.due != null || task.start != null)
            task.recurrence?.endOffsetDays?.let { end ->
                val anchorOffset = (task.due ?: task.start)?.dayOffset ?: 0
                require(end >= anchorOffset)
            }
            task.estimateSeconds?.let { require(it > 0) }
            require(task.milestoneKey == null || task.milestoneKey in milestoneKeys)
            require(task.tagNames.size <= MAX_TASK_TAGS)
            task.tagNames.forEach { name ->
                require(
                    name.isNotBlank() &&
                        name == name.trim() &&
                        name.length <= MAX_TAG_NAME_LENGTH,
                )
            }
            require(
                task.tagNames.map { it.lowercase(Locale.ROOT) }.toSet().size ==
                    task.tagNames.size,
            )
            require(task.checklist.size <= MAX_CHECKLIST_ITEMS)
            task.checklist.forEach { item ->
                item.key.validateKey()
                require(item.text.isNotBlank() && item.text.length <= MAX_CHECKLIST_ITEM_LENGTH)
                require(item.text == item.text.trim())
                require(item.rank.isNotBlank() && item.rank.length <= MAX_RANK_LENGTH)
            }
            require(task.checklist.map(TemplateChecklistItem::key).toSet().size == task.checklist.size)
            require(
                task.checklist.map(TemplateChecklistItem::rank).toSet().size ==
                    task.checklist.size,
            )
            require(task.dependencyKeys.size <= MAX_TASK_DEPENDENCIES)
            require(task.key !in task.dependencyKeys)
            require(task.dependencyKeys.all(taskKeys::contains))
        }
        requireAcyclic(
            template.tasks.associate { task ->
                task.key to setOfNotNull(task.parentKey)
            },
        )
        requireAcyclic(
            template.tasks.associate { task ->
                task.key to task.dependencyKeys
            },
        )
    }

    private fun RelativeZonedMoment.validate() {
        dayOffset.validateOffset()
        require(secondOfDay in 0..86_399)
        require(zoneId.isNotBlank() && zoneId.length <= MAX_ZONE_ID_LENGTH)
        ZoneId.of(zoneId)
    }

    private fun TemplateRecurrence.validate() {
        require(interval in 1..MAX_RECURRENCE_INTERVAL)
        require(count == null || count in 1..MAX_RECURRENCE_COUNT)
        require(count == null || endOffsetDays == null)
        endOffsetDays?.validateOffset()
    }

    private fun Long.validateOffset() {
        require(this in 0..ProjectTemplatePlanner.MAX_RELATIVE_DAY_OFFSET)
    }

    private fun String.validateKey() {
        require(isNotBlank() && length <= MAX_KEY_LENGTH)
    }

    private fun requireAcyclic(edges: Map<String, Set<String>>) {
        val remainingDependencies = edges.mapValuesTo(linkedMapOf()) { (_, dependencies) ->
            dependencies.toMutableSet()
        }
        val ready = ArrayDeque(
            remainingDependencies
                .filterValues(Set<String>::isEmpty)
                .keys,
        )
        var visited = 0
        while (ready.isNotEmpty()) {
            val resolved = ready.removeFirst()
            visited += 1
            remainingDependencies.forEach { (key, dependencies) ->
                if (dependencies.remove(resolved) && dependencies.isEmpty()) {
                    ready.addLast(key)
                }
            }
        }
        require(visited == edges.size) { "Template relationships must be acyclic" }
    }

    @Serializable
    private data class TemplatePayload(
        val formatVersion: Int = FORMAT_VERSION,
        val templateId: String,
        val workspaceId: String,
        val templateName: String,
        val projectName: String,
        val projectSummary: String,
        val projectDueOffsetDays: Long?,
        val workflowStatuses: List<WorkflowPayload>,
        val milestones: List<MilestonePayload>,
        val tasks: List<TaskPayload>,
    ) {
        fun toModel(entity: TemplateEntity): Template {
            require(templateId == entity.id)
            require(workspaceId == entity.workspaceId)
            require(templateName == entity.name)
            return Template(
                id = TemplateId(templateId),
                workspaceId = WorkspaceId(workspaceId),
                name = templateName,
                projectName = projectName,
                projectSummary = projectSummary,
                projectDueOffsetDays = projectDueOffsetDays,
                workflowStatuses = workflowStatuses.map(WorkflowPayload::toModel),
                milestones = milestones.map(MilestonePayload::toModel),
                tasks = tasks.map(TaskPayload::toModel),
                revision = Revision(
                    deviceId = DeviceId(entity.revisionDeviceId),
                    wallTimeMillis = entity.revisionWallMillis,
                    logicalCounter = entity.revisionLogical,
                ),
            )
        }

        companion object {
            fun from(template: Template): TemplatePayload = TemplatePayload(
                templateId = template.id.value,
                workspaceId = template.workspaceId.value,
                templateName = template.name,
                projectName = template.projectName,
                projectSummary = template.projectSummary,
                projectDueOffsetDays = template.projectDueOffsetDays,
                workflowStatuses = template.workflowStatuses.map(WorkflowPayload::from),
                milestones = template.milestones.map(MilestonePayload::from),
                tasks = template.tasks.map(TaskPayload::from),
            )
        }
    }

    @Serializable
    private data class WorkflowPayload(
        val key: String,
        val name: String,
        val semanticStatus: String,
        val rank: String,
    ) {
        fun toModel(): TemplateWorkflowStatus = TemplateWorkflowStatus(
            key = key,
            name = name,
            semanticStatus = SemanticStatus.valueOf(semanticStatus),
            rank = rank,
        )

        companion object {
            fun from(status: TemplateWorkflowStatus): WorkflowPayload = WorkflowPayload(
                key = status.key,
                name = status.name,
                semanticStatus = status.semanticStatus.name,
                rank = status.rank,
            )
        }
    }

    @Serializable
    private data class MilestonePayload(
        val key: String,
        val name: String,
        val dueOffsetDays: Long?,
    ) {
        fun toModel(): TemplateMilestone = TemplateMilestone(key, name, dueOffsetDays)

        companion object {
            fun from(milestone: TemplateMilestone): MilestonePayload = MilestonePayload(
                key = milestone.key,
                name = milestone.name,
                dueOffsetDays = milestone.dueOffsetDays,
            )
        }
    }

    @Serializable
    private data class MomentPayload(
        val dayOffset: Long,
        val secondOfDay: Int,
        val zoneId: String,
    ) {
        fun toModel(): RelativeZonedMoment =
            RelativeZonedMoment(dayOffset, secondOfDay, zoneId)

        companion object {
            fun from(moment: RelativeZonedMoment): MomentPayload = MomentPayload(
                dayOffset = moment.dayOffset,
                secondOfDay = moment.secondOfDay,
                zoneId = moment.zoneId,
            )
        }
    }

    @Serializable
    private data class RecurrencePayload(
        val frequency: String,
        val interval: Int,
        val weekdays: List<String>,
        val count: Int?,
        val endOffsetDays: Long?,
    ) {
        fun toModel(): TemplateRecurrence = TemplateRecurrence(
            frequency = RecurrenceFrequency.valueOf(frequency),
            interval = interval,
            weekdays = weekdays.mapTo(linkedSetOf(), DayOfWeek::valueOf),
            count = count,
            endOffsetDays = endOffsetDays,
        )

        companion object {
            fun from(recurrence: TemplateRecurrence): RecurrencePayload = RecurrencePayload(
                frequency = recurrence.frequency.name,
                interval = recurrence.interval,
                weekdays = recurrence.weekdays.sortedBy { it.value }.map(DayOfWeek::name),
                count = recurrence.count,
                endOffsetDays = recurrence.endOffsetDays,
            )
        }
    }

    @Serializable
    private data class ChecklistPayload(
        val key: String,
        val text: String,
        val rank: String,
    ) {
        fun toModel(): TemplateChecklistItem = TemplateChecklistItem(key, text, rank)

        companion object {
            fun from(item: TemplateChecklistItem): ChecklistPayload =
                ChecklistPayload(item.key, item.text, item.rank)
        }
    }

    @Serializable
    private data class TaskPayload(
        val key: String,
        val parentKey: String?,
        val statusKey: String,
        val title: String,
        val description: String,
        val priority: String,
        val start: MomentPayload?,
        val due: MomentPayload?,
        val recurrence: RecurrencePayload?,
        val estimateSeconds: Long?,
        val milestoneKey: String?,
        val tagNames: List<String>,
        val checklist: List<ChecklistPayload>,
        val dependencyKeys: List<String>,
    ) {
        fun toModel(): TemplateTask = TemplateTask(
            key = key,
            parentKey = parentKey,
            statusKey = statusKey,
            title = title,
            description = description,
            priority = Priority.valueOf(priority),
            start = start?.toModel(),
            due = due?.toModel(),
            recurrence = recurrence?.toModel(),
            estimateSeconds = estimateSeconds,
            milestoneKey = milestoneKey,
            tagNames = tagNames.toCollection(linkedSetOf()),
            checklist = checklist.map(ChecklistPayload::toModel),
            dependencyKeys = dependencyKeys.toCollection(linkedSetOf()),
        )

        companion object {
            fun from(task: TemplateTask): TaskPayload = TaskPayload(
                key = task.key,
                parentKey = task.parentKey,
                statusKey = task.statusKey,
                title = task.title,
                description = task.description,
                priority = task.priority.name,
                start = task.start?.let(MomentPayload::from),
                due = task.due?.let(MomentPayload::from),
                recurrence = task.recurrence?.let(RecurrencePayload::from),
                estimateSeconds = task.estimateSeconds,
                milestoneKey = task.milestoneKey,
                tagNames = task.tagNames.sorted(),
                checklist = task.checklist.map(ChecklistPayload::from),
                dependencyKeys = task.dependencyKeys.sorted(),
            )
        }
    }
}
