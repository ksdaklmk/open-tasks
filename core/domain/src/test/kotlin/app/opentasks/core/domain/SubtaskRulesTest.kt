package app.opentasks.core.domain

import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.SubtaskRollup
import app.opentasks.core.model.TaskId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SubtaskRulesTest {
    @Test
    fun parentViolationEnforcesOneLevelSameProjectLiveParents() {
        val base = OpenTasksFixtures.tasks.first { it.deletedAt == null }
        val project = requireNotNull(base.projectId)
        fun task(id: String, parent: String? = null, projectId: ProjectId? = project) =
            base.copy(
                id = TaskId(id),
                parentTaskId = parent?.let(::TaskId),
                projectId = projectId,
                deletedAt = null,
            )
        val parent = task("parent")
        val child = task("child", parent = "parent")
        val loose = task("loose")
        val elsewhere = task("elsewhere", projectId = OpenTasksFixtures.taxProject.id)
        val binned = task("binned").copy(deletedAt = Instant.parse("2026-08-01T00:00:00Z"))
        val tasks = listOf(parent, child, loose, elsewhere, binned)

        assertNull(SubtaskRules.parentViolation(tasks, loose.id, parent.id))
        assertEquals(
            SubtaskViolation.SELF,
            SubtaskRules.parentViolation(tasks, loose.id, loose.id),
        )
        assertEquals(
            SubtaskViolation.PARENT_MISSING_OR_BINNED,
            SubtaskRules.parentViolation(tasks, loose.id, TaskId("missing")),
        )
        assertEquals(
            SubtaskViolation.PARENT_MISSING_OR_BINNED,
            SubtaskRules.parentViolation(tasks, loose.id, binned.id),
        )
        assertEquals(
            SubtaskViolation.CROSS_PROJECT,
            SubtaskRules.parentViolation(tasks, elsewhere.id, parent.id),
        )
        assertEquals(
            SubtaskViolation.PARENT_IS_A_SUBTASK,
            SubtaskRules.parentViolation(tasks, loose.id, child.id),
        )
        assertEquals(
            SubtaskViolation.TASK_HAS_SUBTASKS,
            SubtaskRules.parentViolation(tasks, parent.id, loose.id),
        )
    }

    @Test
    fun rollupsCountLiveChildrenAndCompletion() {
        val base = OpenTasksFixtures.tasks.first { it.deletedAt == null }
        val project = requireNotNull(base.projectId)
        fun task(
            id: String,
            parent: String? = null,
            completed: Boolean = false,
            deleted: Boolean = false,
        ) = base.copy(
            id = TaskId(id),
            parentTaskId = parent?.let(::TaskId),
            projectId = project,
            semanticStatus = if (completed) SemanticStatus.COMPLETED else SemanticStatus.PLANNED,
            deletedAt = if (deleted) Instant.parse("2026-08-01T00:00:00Z") else null,
        )
        val parent = task("parent")
        val openChild = task("open-child", parent = "parent")
        val doneChild = task("done-child", parent = "parent", completed = true)
        val binnedChild = task("binned-child", parent = "parent", deleted = true)
        val childless = task("childless")
        val tasks = listOf(parent, openChild, doneChild, binnedChild, childless)

        val rollups = SubtaskRules.subtaskRollups(tasks)

        assertEquals(SubtaskRollup(completed = 1, total = 2), rollups[parent.id])
        assertFalse(rollups.containsKey(childless.id))
        assertFalse(rollups.containsKey(binnedChild.id))
    }

    @Test
    fun attachableSubtasksFilterMirrorsTheGuard() {
        val base = OpenTasksFixtures.tasks.first { it.deletedAt == null }
        val project = requireNotNull(base.projectId)
        fun task(id: String, parent: String? = null, projectId: ProjectId? = project) =
            base.copy(
                id = TaskId(id),
                parentTaskId = parent?.let(::TaskId),
                projectId = projectId,
                deletedAt = null,
            )
        val parent = task("parent")
        val child = task("child", parent = "parent")
        val loose = task("loose")
        val elsewhere = task("elsewhere", projectId = OpenTasksFixtures.taxProject.id)
        val binned = task("binned").copy(deletedAt = Instant.parse("2026-08-01T00:00:00Z"))
        val tasks = listOf(parent, child, loose, elsewhere, binned)

        // Exactly the live, currently-parentless tasks for which parentViolation
        // is null: `child` and `binned` both have a null violation too (neither's
        // own liveness/parent-slot is checked by parentViolation itself), so
        // attachableSubtasks must apply its own extra filters on top of the guard.
        assertEquals(listOf(loose), SubtaskRules.attachableSubtasks(tasks, parent))
    }
}
