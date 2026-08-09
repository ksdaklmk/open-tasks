package app.opentasks

import androidx.lifecycle.SavedStateHandle
import app.opentasks.core.model.ProjectId
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceBoardViewStateTest {
    @Test
    fun boardModeProjectIdsRestoreFromSavedState() {
        val handle = SavedStateHandle()
        val state = WorkspaceBoardViewState(handle)
        val first = ProjectId("first-project")
        val second = ProjectId("second-project")

        state.setBoardMode(first, enabled = true)
        state.setBoardMode(second, enabled = true)

        val restored = WorkspaceBoardViewState(
            SavedStateHandle(
                mapOf(
                    WorkspaceBoardViewState.PROJECT_BOARD_MODE_IDS to
                        handle.get<List<String>>(
                            WorkspaceBoardViewState.PROJECT_BOARD_MODE_IDS,
                        ),
                ),
            ),
        )

        assertEquals(setOf(first, second), restored.projectIds.value)
    }
}
