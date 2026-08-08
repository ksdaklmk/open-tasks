package app.opentasks.core.model

data class ReviewQueue(
    val overdue: List<Task>,
    val stale: List<Task>,
    val unscheduled: List<Task>,
    val projects: List<Project>,
)
