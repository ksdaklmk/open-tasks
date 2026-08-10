package app.opentasks

import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.TaskArrangement
import app.opentasks.core.model.TaskSortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewArrangementStateTest {
    @Test
    fun missingProjectsReturnDefaultsWithoutCreatingEntries() {
        val state = ViewArrangementState()
        val projectId = ProjectId("missing")

        assertEquals(TaskArrangement(), state.workbenchFor(projectId))
        assertEquals(TaskSortKey.PRIORITY, state.boardSortFor(projectId))
        assertTrue(state.workbenchByProject.isEmpty())
        assertTrue(state.boardSortByProject.isEmpty())
    }
}
