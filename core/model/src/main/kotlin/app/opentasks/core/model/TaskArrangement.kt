package app.opentasks.core.model

enum class TaskSortKey { DUE, PRIORITY, TITLE, UPDATED }

enum class TaskGroupKey { DUE_BUCKET, PROJECT, PRIORITY }

data class TaskArrangement(
    val sort: TaskSortKey = TaskSortKey.DUE,
    val groupBy: TaskGroupKey? = null,
)

sealed interface TaskGroupValue {
    data class Due(val bucket: DueBucket) : TaskGroupValue

    data class Project(val projectId: ProjectId?) : TaskGroupValue

    data class PriorityValue(val priority: Priority) : TaskGroupValue
}

data class TaskGroup(
    val value: TaskGroupValue?,
    val tasks: List<Task>,
)

data class BoardColumn(
    val status: WorkflowStatus,
    val tasks: List<Task>,
)
