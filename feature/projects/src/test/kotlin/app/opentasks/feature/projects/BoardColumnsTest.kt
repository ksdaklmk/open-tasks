package app.opentasks.feature.projects

import app.opentasks.core.model.BoardColumn
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardColumnsTest {
    @Test
    fun moveTargetsExcludeCurrentStatusAndKeepBoardOrder() {
        val started = status("started", "In progress")
        val backlog = status("backlog", "Backlog")
        val done = status("done", "Done")
        val columns = listOf(started, backlog, done).map {
            BoardColumn(status = it, tasks = emptyList())
        }

        assertEquals(
            listOf(backlog.id, WorkflowStatusId("done")),
            moveTargets(columns, started.id).map(WorkflowStatus::id),
        )
    }

    private fun status(id: String, name: String): WorkflowStatus =
        app.opentasks.core.model.OpenTasksFixtures.workflowStatuses.first().copy(
            id = WorkflowStatusId(id),
            name = name,
        )
}
