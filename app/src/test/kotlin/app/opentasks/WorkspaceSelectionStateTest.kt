package app.opentasks

import androidx.lifecycle.SavedStateHandle
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceSelectionStateTest {
    @Test
    fun selectedRecordsRestoreFromSavedStateHandle() {
        val taskId = TaskId("restored-task")
        val projectId = ProjectId("restored-project")
        val initialHandle = SavedStateHandle()
        val initialState = WorkspaceSelectionState(initialHandle)

        initialState.selectTask(taskId)
        initialState.selectProject(projectId)

        val restoredState = WorkspaceSelectionState(
            SavedStateHandle(
                mapOf(
                    WorkspaceSelectionState.SELECTED_TASK_ID to
                        initialHandle.get<String>(WorkspaceSelectionState.SELECTED_TASK_ID),
                    WorkspaceSelectionState.SELECTED_PROJECT_ID to
                        initialHandle.get<String>(WorkspaceSelectionState.SELECTED_PROJECT_ID),
                ),
            ),
        )

        assertEquals(taskId.value, restoredState.selectedTaskId.value)
        assertEquals(projectId.value, restoredState.selectedProjectId.value)

        restoredState.closeTask()
        restoredState.closeProject()
        assertNull(restoredState.selectedTaskId.value)
        assertNull(restoredState.selectedProjectId.value)
    }
}
