package app.opentasks.core.domain

import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskArrangement
import app.opentasks.core.model.TaskGroup
import app.opentasks.core.model.TaskGroupKey
import app.opentasks.core.model.TaskGroupValue
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TaskSortKey
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.ZonedMoment
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskArrangementRulesTest {
    private val clock = Clock.fixed(
        Instant.parse("2026-08-10T03:00:00Z"),
        ZoneId.of("Asia/Bangkok"),
    )
    private val base = OpenTasksFixtures.tasks.first().copy(
        due = ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), "Asia/Bangkok"),
        priority = Priority.HIGH,
        revision = Revision(DeviceId("arrangement-test"), 100L, 0),
    )
    private val alphaA = base.copy(id = TaskId("a"), title = "Alpha")
    private val alphaB = base.copy(id = TaskId("b"), title = "alpha")
    private val betaB = base.copy(id = TaskId("c"), title = "Beta")

    @Test
    fun everyComparatorFallsBackToTitleThenId() {
        TaskSortKey.entries.forEach { sort ->
            val actual = listOf(betaB, alphaB, alphaA).sortedWith(taskComparator(sort))
            assertEquals(listOf(alphaA.id, alphaB.id, betaB.id), actual.map(Task::id))
        }
    }

    @Test
    fun comparatorsUseFixedPrimaryDirectionsAndPlaceNullDueLast() {
        val early = alphaA.copy(due = ZonedMoment(Instant.parse("2026-08-10T04:00:00Z"), "Asia/Bangkok"))
        val late = alphaB.copy(due = ZonedMoment(Instant.parse("2026-08-12T04:00:00Z"), "Asia/Bangkok"))
        val noDue = betaB.copy(due = null)
        assertEquals(listOf(early.id, late.id, noDue.id), listOf(noDue, late, early).sortedWith(taskComparator(TaskSortKey.DUE)).map(Task::id))

        val urgent = alphaA.copy(priority = Priority.URGENT)
        val high = alphaB.copy(priority = Priority.HIGH)
        val none = betaB.copy(priority = Priority.NONE)
        assertEquals(listOf(urgent.id, high.id, none.id), listOf(none, high, urgent).sortedWith(taskComparator(TaskSortKey.PRIORITY)).map(Task::id))

        val older = alphaA.copy(revision = base.revision.copy(wallTimeMillis = 90L))
        val newer = alphaB.copy(revision = base.revision.copy(wallTimeMillis = 110L))
        assertEquals(listOf(newer.id, older.id), listOf(older, newer).sortedWith(taskComparator(TaskSortKey.UPDATED)).map(Task::id))
    }

    @Test
    fun flatArrangementReturnsOneSortedGroupEvenWhenEmpty() {
        assertEquals(
            listOf(TaskGroup(null, emptyList())),
            arrangeTasks(emptyList(), TaskArrangement(TaskSortKey.TITLE), emptyMap(), clock),
        )
        assertEquals(
            listOf(alphaA.id, alphaB.id, betaB.id),
            arrangeTasks(
                listOf(betaB, alphaB, alphaA),
                TaskArrangement(TaskSortKey.TITLE),
                emptyMap(),
                clock,
            ).single().tasks.map(Task::id),
        )
    }

    @Test
    fun dueGroupsFollowBucketOrder() {
        val groups = arrangeTasks(
            listOf(
                alphaA.copy(due = null),
                alphaB.copy(due = ZonedMoment(Instant.parse("2026-08-17T00:00:00Z"), "Asia/Bangkok")),
                betaB.copy(due = ZonedMoment(Instant.parse("2026-08-12T10:00:00Z"), "Asia/Bangkok")),
                base.copy(id = TaskId("today"), due = ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), "Asia/Bangkok")),
                base.copy(id = TaskId("overdue"), due = ZonedMoment(Instant.parse("2026-08-10T02:00:00Z"), "Asia/Bangkok")),
            ),
            TaskArrangement(TaskSortKey.TITLE, TaskGroupKey.DUE_BUCKET),
            emptyMap(),
            clock,
        )
        assertEquals(
            listOf(
                TaskGroupValue.Due(app.opentasks.core.model.DueBucket.OVERDUE),
                TaskGroupValue.Due(app.opentasks.core.model.DueBucket.TODAY),
                TaskGroupValue.Due(app.opentasks.core.model.DueBucket.THIS_WEEK),
                TaskGroupValue.Due(app.opentasks.core.model.DueBucket.LATER),
                TaskGroupValue.Due(app.opentasks.core.model.DueBucket.NO_DATE),
            ),
            groups.map(TaskGroup::value),
        )
    }

    @Test
    fun projectGroupsPutInboxThenKnownNamesThenMissingIds() {
        val alphaId = ProjectId("project-alpha")
        val zuluId = ProjectId("project-zulu")
        val missingA = ProjectId("missing-a")
        val missingZ = ProjectId("missing-z")
        val groups = arrangeTasks(
            listOf(
                base.copy(id = TaskId("zulu"), projectId = zuluId),
                base.copy(id = TaskId("inbox"), projectId = null),
                base.copy(id = TaskId("alpha"), projectId = alphaId),
                base.copy(id = TaskId("missing-z-task"), projectId = missingZ),
                base.copy(id = TaskId("missing-a-task"), projectId = missingA),
            ),
            TaskArrangement(TaskSortKey.TITLE, TaskGroupKey.PROJECT),
            mapOf(zuluId to "Zulu", alphaId to "alpha"),
            clock,
        )
        assertEquals(
            listOf(
                TaskGroupValue.Project(null),
                TaskGroupValue.Project(alphaId),
                TaskGroupValue.Project(zuluId),
                TaskGroupValue.Project(missingA),
                TaskGroupValue.Project(missingZ),
            ),
            groups.map(TaskGroup::value),
        )
    }

    @Test
    fun priorityGroupsRunFromUrgentToNone() {
        val groups = arrangeTasks(
            Priority.entries.map { priority -> base.copy(id = TaskId(priority.name), priority = priority) },
            TaskArrangement(TaskSortKey.TITLE, TaskGroupKey.PRIORITY),
            emptyMap(),
            clock,
        )
        assertEquals(
            listOf(Priority.URGENT, Priority.HIGH, Priority.MEDIUM, Priority.LOW, Priority.NONE)
                .map(TaskGroupValue::PriorityValue),
            groups.map(TaskGroup::value),
        )
    }

    @Test
    fun boardColumnsFilterCardsAndUseTheRequestedSharedComparator() {
        val project = OpenTasksFixtures.studioProject
        val statuses = OpenTasksFixtures.workflowStatuses
        val backlog = statuses.single { it.id == OpenTasksFixtures.backlog }
        val titleFirst = alphaA.copy(projectId = project.id, statusId = backlog.id)
        val titleLast = betaB.copy(projectId = project.id, statusId = backlog.id)
        val priorityFirst = titleLast.copy(id = TaskId("priority-first"), priority = Priority.URGENT)
        val priorityLast = titleFirst.copy(id = TaskId("priority-last"), priority = Priority.NONE)
        val dueFirst = titleLast.copy(
            id = TaskId("due-first"),
            due = ZonedMoment(Instant.parse("2026-08-10T04:00:00Z"), "Asia/Bangkok"),
        )
        val dueLast = titleFirst.copy(
            id = TaskId("due-last"),
            due = ZonedMoment(Instant.parse("2026-08-12T04:00:00Z"), "Asia/Bangkok"),
        )
        val updatedFirst = titleLast.copy(
            id = TaskId("updated-first"),
            revision = titleLast.revision.copy(wallTimeMillis = 110L),
        )
        val updatedLast = titleFirst.copy(
            id = TaskId("updated-last"),
            revision = titleFirst.revision.copy(wallTimeMillis = 90L),
        )
        val completed = titleLast.copy(
            id = TaskId("completed"),
            semanticStatus = SemanticStatus.COMPLETED,
            completedAt = clock.instant(),
        )
        val deleted = titleLast.copy(id = TaskId("deleted"), deletedAt = clock.instant())
        val cards = listOf(
            titleLast,
            titleFirst,
            priorityFirst,
            priorityLast,
            dueFirst,
            dueLast,
            updatedFirst,
            updatedLast,
            completed,
            deleted,
        )
        fun cardIds(sort: TaskSortKey) = boardColumns(
            project,
            statuses,
            cards,
            sort,
        ).single { it.status == backlog }.tasks.map(Task::id)

        assertEquals(
            statuses.filter { it.projectId == project.id && it.archivedAt == null }
                .sortedBy(WorkflowStatus::rank).map(WorkflowStatus::id),
            boardColumns(project, statuses, cards, TaskSortKey.TITLE).map { it.status.id },
        )
        assertEquals(
            listOf(titleFirst.id, dueLast.id, priorityLast.id, updatedLast.id, titleLast.id, dueFirst.id, priorityFirst.id, updatedFirst.id),
            cardIds(TaskSortKey.TITLE),
        )
        assertEquals(
            listOf(priorityFirst.id, titleFirst.id, dueLast.id, updatedLast.id, titleLast.id, dueFirst.id, updatedFirst.id, priorityLast.id),
            cardIds(TaskSortKey.PRIORITY),
        )
        assertEquals(
            listOf(dueFirst.id, titleFirst.id, priorityLast.id, updatedLast.id, titleLast.id, priorityFirst.id, updatedFirst.id, dueLast.id),
            cardIds(TaskSortKey.DUE),
        )
        assertEquals(
            listOf(updatedFirst.id, titleFirst.id, dueLast.id, priorityLast.id, titleLast.id, dueFirst.id, priorityFirst.id, updatedLast.id),
            cardIds(TaskSortKey.UPDATED),
        )
    }

    @Test
    fun arrangeTasksNestsChildrenDirectlyUnderTheirParent() {
        val base = OpenTasksFixtures.tasks.first { it.deletedAt == null }
        fun task(id: String, title: String, parent: String? = null) = base.copy(
            id = TaskId(id),
            title = title,
            parentTaskId = parent?.let(::TaskId),
            priority = Priority.NONE,
            due = null,
        )
        val tasks = listOf(
            task("b-parent", "Beta parent"),
            task("a-parent", "Alpha parent"),
            task("z-child", "Zulu child", parent = "a-parent"),
            task("m-child", "Mike child", parent = "a-parent"),
            task("orphan-child", "Orphan child", parent = "filtered-out"),
        )
        val groups = arrangeTasks(
            tasks = tasks,
            arrangement = TaskArrangement(sort = TaskSortKey.TITLE, groupBy = null),
            projectNames = emptyMap(),
            clock = Clock.systemUTC(),
        )
        assertEquals(
            listOf("a-parent", "m-child", "z-child", "b-parent", "orphan-child"),
            groups.single().tasks.map { it.id.value },
        )
        assertEquals(
            setOf(TaskId("m-child"), TaskId("z-child")),
            indentedTaskIds(groups),
        )
    }

    @Test
    fun childWhoseParentLandsInAnotherGroupRendersFlat() {
        val base = OpenTasksFixtures.tasks.first { it.deletedAt == null }
        fun task(id: String, title: String, priority: Priority, parent: String? = null) = base.copy(
            id = TaskId(id),
            title = title,
            parentTaskId = parent?.let(::TaskId),
            priority = priority,
            due = null,
        )
        val tasks = listOf(
            task("parent", "High parent", Priority.HIGH),
            task("child", "Low child", Priority.LOW, parent = "parent"),
        )
        val groups = arrangeTasks(
            tasks = tasks,
            arrangement = TaskArrangement(sort = TaskSortKey.TITLE, groupBy = TaskGroupKey.PRIORITY),
            projectNames = emptyMap(),
            clock = Clock.systemUTC(),
        )
        val lowGroup = groups.single { it.value == TaskGroupValue.PriorityValue(Priority.LOW) }
        assertEquals(listOf(TaskId("child")), lowGroup.tasks.map(Task::id))
        assertEquals(emptySet<TaskId>(), indentedTaskIds(groups))
    }

    @Test
    fun recoveredDeeperTreesClampToASingleIndentLevel() {
        // grandparent <- parent <- child (legal in recovered foreign data):
        // order is depth-first under the top ancestor; only tasks whose
        // parent is present in the group are indented -- one visual level.
        val base = OpenTasksFixtures.tasks.first { it.deletedAt == null }
        fun task(id: String, title: String, parent: String? = null) = base.copy(
            id = TaskId(id),
            title = title,
            parentTaskId = parent?.let(::TaskId),
            priority = Priority.NONE,
            due = null,
        )
        val tasks = listOf(
            task("grandparent", "Zulu grandparent"),
            task("parent", "Alpha parent", parent = "grandparent"),
            task("child", "Mike child", parent = "parent"),
        )
        val groups = arrangeTasks(
            tasks = tasks,
            arrangement = TaskArrangement(sort = TaskSortKey.TITLE, groupBy = null),
            projectNames = emptyMap(),
            clock = Clock.systemUTC(),
        )
        assertEquals(
            listOf("grandparent", "parent", "child"),
            groups.single().tasks.map { it.id.value },
        )
        assertEquals(
            setOf(TaskId("parent"), TaskId("child")),
            indentedTaskIds(groups),
        )
    }
}
