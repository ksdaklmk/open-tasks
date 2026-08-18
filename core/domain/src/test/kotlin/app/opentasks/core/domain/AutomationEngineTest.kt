package app.opentasks.core.domain

import app.opentasks.core.model.AutomationRule
import app.opentasks.core.model.AutomationRuleId
import app.opentasks.core.model.AutomationRuleType
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatusId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class AutomationEngineTest {
    private val task: Task = OpenTasksFixtures.tasks
        .first { it.deletedAt == null && !it.isCompleted }
        .copy(tagIds = setOf(TagId("tag-existing")))

    private fun rule(
        id: String,
        type: AutomationRuleType,
        enabled: Boolean = true,
        statusId: WorkflowStatusId? = task.statusId,
        projectId: ProjectId? = null,
        tagId: TagId? = null,
        dueInDays: Int? = null,
        thresholdDays: Int? = null,
    ) = AutomationRule(
        id = AutomationRuleId(id),
        workspaceId = OpenTasksFixtures.workspaceId,
        type = type,
        enabled = enabled,
        projectId = projectId,
        statusId = statusId,
        tagId = tagId,
        dueInDays = dueInDays,
        thresholdDays = thresholdDays,
    )

    private fun sweepRule(id: String, enabled: Boolean = true) = rule(
        id = id,
        type = AutomationRuleType.MY_DAY_AUTO_REMOVE,
        enabled = enabled,
        statusId = null,
    )

    private fun trigger(
        myDayMemberIds: Set<TaskId> = emptySet(),
    ) = StatusTransitionTrigger(
        task = task,
        reminder = null,
        myDayMemberIds = myDayMemberIds,
        today = LocalDate.parse("2026-08-17"),
        zoneId = "Asia/Bangkok",
    )

    @Test
    fun matchingRulesEmitVerbsInRuleIdOrderWithIdempotentSkips() {
        val outputs = evaluateAutomationRules(
            listOf(
                rule("b-tag", AutomationRuleType.ON_ENTER_ADD_TAG, tagId = TagId("tag-new")),
                rule("a-my-day", AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY),
                rule(
                    "c-skip",
                    AutomationRuleType.ON_ENTER_ADD_TAG,
                    tagId = TagId("tag-existing"),
                ),
                rule("d-due", AutomationRuleType.ON_ENTER_SET_DUE, dueInDays = 3),
            ),
            trigger(),
        )
        assertEquals(3, outputs.size)
        assertEquals(DomainCommand.AddTaskToMyDay(task.id), outputs[0]) // "a-my-day"
        val tag = outputs[1] as DomainCommand.SetTaskTag // "b-tag"
        assertEquals(TagId("tag-new"), tag.tagId)
        assertTrue(tag.present)
        val schedule = outputs[2] as DomainCommand.SetTaskSchedule // "d-due"
        assertEquals(task.start, schedule.start)
        assertEquals(
            ZonedDateTime.of(2026, 8, 20, 17, 0, 0, 0, ZoneId.of("Asia/Bangkok")).toInstant(),
            requireNotNull(schedule.due).instant,
        )
        assertEquals("Asia/Bangkok", requireNotNull(schedule.due).zoneId)
        // Repository-produced output: an already-fired reminder must not turn
        // the rule into a permanent REMINDER_IN_PAST no-op.
        assertTrue(schedule.restorePastReminder)
    }

    @Test
    fun nonMatchingDisabledWrongStatusAndCapRulesEmitNothing() {
        val otherStatus = OpenTasksFixtures.workflowStatuses
            .first { it.id != task.statusId }
            .id
        val outputs = evaluateAutomationRules(
            listOf(
                // Disabled.
                rule(
                    "a-disabled",
                    AutomationRuleType.ON_ENTER_ADD_TAG,
                    enabled = false,
                    tagId = TagId("tag-new"),
                ),
                // A different entered status.
                rule(
                    "b-other-status",
                    AutomationRuleType.ON_ENTER_ADD_TAG,
                    statusId = otherStatus,
                    tagId = TagId("tag-new"),
                ),
                // Crafted rule whose project scope contradicts its status.
                rule(
                    "c-foreign-project",
                    AutomationRuleType.ON_ENTER_ADD_TAG,
                    projectId = ProjectId("project-elsewhere"),
                    tagId = TagId("tag-new"),
                ),
                // Sweep and badge types never fire on a status entry.
                sweepRule("d-sweep"),
                rule(
                    "e-stale",
                    AutomationRuleType.STALE_BADGE,
                    statusId = null,
                    thresholdDays = 7,
                ),
                // Already a My Day member.
                rule("f-my-day", AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY),
            ),
            trigger(myDayMemberIds = setOf(task.id)),
        )
        assertTrue(outputs.isEmpty())

        // My Day already at its bound: the add is skipped rather than
        // dispatched into a rejection.
        val full = evaluateAutomationRules(
            listOf(rule("a-my-day", AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY)),
            trigger(
                myDayMemberIds = List(MAX_MY_DAY_ENTRIES) { TaskId("filler-$it") }.toSet(),
            ),
        )
        assertTrue(full.isEmpty())
    }

    @Test
    fun ruleMatchingHonoursAnAgreeingProjectScope() {
        val outputs = evaluateAutomationRules(
            listOf(
                rule(
                    "a-scoped",
                    AutomationRuleType.ON_ENTER_ADD_TAG,
                    projectId = task.projectId,
                    tagId = TagId("tag-new"),
                ),
            ),
            trigger(),
        )
        assertEquals(
            listOf(DomainCommand.SetTaskTag(task.id, TagId("tag-new"), present = true)),
            outputs,
        )
    }

    @Test
    fun transitionedTaskIdsComeOnlyFromTheThreeTriggerCommands() {
        val first = TaskId("task-a")
        val second = TaskId("task-b")
        fun restore(id: TaskId) = DomainCommand.RestoreTaskStatus(
            taskId = id,
            statusId = task.statusId,
            completedAt = null,
        )

        // ChangeTaskStatus and CompleteTask each carry one RestoreTaskStatus.
        assertEquals(
            listOf(first),
            automationTransitionedTaskIds(
                DomainCommand.ChangeTaskStatus(first, task.statusId),
                CommandResult.Success("Moved", restore(first)),
            ),
        )
        assertEquals(
            listOf(first),
            automationTransitionedTaskIds(
                DomainCommand.CompleteTask(first),
                CommandResult.Success("Task completed", restore(first)),
            ),
        )

        // CompleteTasks stores its inverses in reverse application order; the
        // derivation must hand them back in the order they were applied.
        assertEquals(
            listOf(first, second),
            automationTransitionedTaskIds(
                DomainCommand.CompleteTasks(listOf(first, second)),
                CommandResult.Success(
                    "2 tasks completed",
                    DomainCommand.UndoBatch(listOf(restore(second), restore(first))),
                ),
            ),
        )

        // A no-op transition returns no undo at all and never registers.
        assertTrue(
            automationTransitionedTaskIds(
                DomainCommand.ChangeTaskStatus(first, task.statusId),
                CommandResult.Success("Status is already Backlog"),
            ).isEmpty(),
        )

        // The command whitelist, not the undo shape, is the authority: a
        // project move's per-task inverses never register…
        assertTrue(
            automationTransitionedTaskIds(
                DomainCommand.MoveTasksToProject(listOf(first), ProjectId("project-other")),
                CommandResult.Success(
                    "1 task moved",
                    DomainCommand.UndoBatch(
                        listOf(
                            DomainCommand.UpdateTask(
                                taskId = first,
                                title = "Moved task",
                                description = "",
                                projectId = null,
                                priority = Priority.NONE,
                                start = null,
                                due = null,
                                recurrence = null,
                                estimate = null,
                            ),
                        ),
                    ),
                ),
            ).isEmpty(),
        )
        // …and neither does an undo replay whose own undo is a RestoreTaskStatus.
        assertTrue(
            automationTransitionedTaskIds(
                DomainCommand.RestoreTaskStatus(first, task.statusId, completedAt = null),
                CommandResult.Success("Status restored", restore(first)),
            ).isEmpty(),
        )
    }

    @Test
    fun myDaySweepIsGatedOnAnEnabledAutoRemoveRule() {
        assertTrue(myDaySweepEnabled(listOf(sweepRule("a"))))
        assertFalse(myDaySweepEnabled(listOf(sweepRule("a", enabled = false))))
        assertFalse(myDaySweepEnabled(emptyList()))
        assertFalse(
            myDaySweepEnabled(
                listOf(
                    rule("a", AutomationRuleType.ON_ENTER_ADD_TAG, tagId = TagId("tag-new")),
                    rule("b", AutomationRuleType.STALE_BADGE, statusId = null, thresholdDays = 7),
                ),
            ),
        )
    }
}
