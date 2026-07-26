package app.opentasks.core.domain

import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.TaskDependency
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyRulesTest {
    private val revision = Revision(DeviceId("test"), 1, 0)
    private val a = TaskId("a")
    private val b = TaskId("b")
    private val c = TaskId("c")

    @Test
    fun rejectsTransitiveCycle() {
        val existing = listOf(
            TaskDependency(a, b, revision),
            TaskDependency(b, c, revision),
        )

        assertTrue(DependencyRules.wouldCreateCycle(existing, c, a))
        assertFalse(DependencyRules.wouldCreateCycle(existing, c, TaskId("d")))
    }

    @Test
    fun rejectsSelfDependency() {
        assertTrue(DependencyRules.wouldCreateCycle(emptyList(), a, a))
    }
}
