package app.opentasks.core.domain

import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.ZonedMoment
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

data class RecurrenceSeriesMetadata(
    val seriesId: TaskId,
    val anchor: ZonedMoment,
    val occurrenceIndex: Int,
)

object RecurringTaskPlanner {
    fun metadataForUpdate(
        task: Task,
        due: ZonedMoment?,
        rule: RecurrenceRule?,
    ): RecurrenceSeriesMetadata? {
        if (rule == null) return null
        val anchor = due ?: task.start ?: return null
        val existingSeriesId = task.recurrenceSeriesId
        val existingAnchor = task.recurrenceAnchor
        val existingOccurrenceIndex = task.recurrenceOccurrenceIndex
        if (
            task.recurrence == rule &&
            task.due == due &&
            existingSeriesId != null &&
            existingAnchor != null &&
            existingOccurrenceIndex != null
        ) {
            return RecurrenceSeriesMetadata(
                seriesId = existingSeriesId,
                anchor = existingAnchor,
                occurrenceIndex = existingOccurrenceIndex,
            )
        }
        return RecurrenceSeriesMetadata(
            seriesId = task.id,
            anchor = anchor,
            occurrenceIndex = initialOccurrenceIndex(anchor, rule),
        )
    }

    fun next(
        current: Task,
        nextStatusId: WorkflowStatusId,
        nextSemanticStatus: SemanticStatus,
        revision: Revision,
    ): Task? {
        val rule = current.recurrence ?: return null
        val anchor = current.recurrenceAnchor ?: current.due ?: current.start ?: return null
        val seriesId = current.recurrenceSeriesId ?: current.id
        val currentIndex = current.recurrenceOccurrenceIndex
            ?: initialOccurrenceIndex(anchor, rule)
        val nextIndex = currentIndex + 1
        val anchorDateTime = anchor.instant.atZone(ZoneId.of(anchor.zoneId))
        val occurrence = RecurrenceEngine.occurrences(
            seriesId = seriesId,
            firstStart = anchorDateTime,
            rule = rule,
            limit = nextIndex + 1,
        ).getOrNull(nextIndex) ?: return null
        val occurrenceMoment = ZonedMoment(
            instant = occurrence.startsAt.toInstant(),
            zoneId = occurrence.startsAt.zone.id,
        )
        val currentDue = current.due
        val currentStart = current.start
        val nextStart = when {
            currentDue != null && currentStart != null -> {
                val offset = Duration.between(currentDue.instant, currentStart.instant)
                ZonedMoment(
                    instant = occurrenceMoment.instant.plus(offset),
                    zoneId = currentStart.zoneId,
                )
            }
            currentDue == null && currentStart != null -> occurrenceMoment
            else -> currentStart
        }
        val nextDue = occurrenceMoment.takeIf { currentDue != null }

        return current.copy(
            id = occurrence.id,
            statusId = nextStatusId,
            semanticStatus = nextSemanticStatus,
            start = nextStart,
            due = nextDue,
            recurrenceSeriesId = seriesId,
            recurrenceAnchor = anchor,
            recurrenceOccurrenceIndex = nextIndex,
            checklist = current.checklist.map { item ->
                item.copy(
                    id = deterministicChecklistId(occurrence.id, item),
                    completed = false,
                )
            },
            dependencyIds = emptySet(),
            blockedBy = emptySet(),
            completedAt = null,
            deletedAt = null,
            revision = revision,
        )
    }

    private fun initialOccurrenceIndex(
        anchor: ZonedMoment,
        rule: RecurrenceRule,
    ): Int {
        if (rule.frequency != RecurrenceFrequency.WEEKLY || rule.weekdays.isEmpty()) return 0
        val anchorDay = anchor.instant.atZone(ZoneId.of(anchor.zoneId)).dayOfWeek
        return if (anchorDay in rule.weekdays) 0 else -1
    }

    private fun deterministicChecklistId(
        occurrenceId: TaskId,
        item: ChecklistItem,
    ): String {
        val source = "${occurrenceId.value}:checklist:${item.id}"
            .toByteArray(StandardCharsets.UTF_8)
        return UUID.nameUUIDFromBytes(source).toString()
    }
}
