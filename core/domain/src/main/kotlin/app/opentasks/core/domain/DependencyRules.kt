package app.opentasks.core.domain

import app.opentasks.core.model.TaskDependency
import app.opentasks.core.model.TaskId

object DependencyRules {
    fun wouldCreateCycle(
        existing: Collection<TaskDependency>,
        taskId: TaskId,
        dependsOn: TaskId,
    ): Boolean {
        if (taskId == dependsOn) return true
        val dependencies = existing
            .groupBy(TaskDependency::taskId)
            .mapValues { (_, edges) -> edges.map(TaskDependency::dependsOnTaskId).toSet() }

        val pending = ArrayDeque<TaskId>()
        val visited = mutableSetOf<TaskId>()
        pending += dependsOn
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current == taskId) return true
            if (visited.add(current)) {
                dependencies[current].orEmpty().forEach(pending::addLast)
            }
        }
        return false
    }
}
