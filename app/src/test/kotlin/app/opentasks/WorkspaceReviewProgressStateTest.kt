package app.opentasks

import androidx.lifecycle.SavedStateHandle
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceReviewProgressStateTest {
    @Test
    fun progressRestoresThenFinishAndLaterStartClearBothOwners() {
        val handle = SavedStateHandle()
        val state = WorkspaceReviewProgressState(handle)
        val taskId = TaskId("task")
        val projectId = ProjectId("project")

        state.markReviewed(taskId, null)
        state.markReviewed(null, projectId)
        state.setActionPending(true)

        val restored = WorkspaceReviewProgressState(
            SavedStateHandle(
                mapOf(
                    WorkspaceReviewProgressState.REVIEWED_TASK_IDS to
                        handle.get<Any?>(WorkspaceReviewProgressState.REVIEWED_TASK_IDS),
                    WorkspaceReviewProgressState.REVIEWED_PROJECT_IDS to
                        handle.get<Any?>(WorkspaceReviewProgressState.REVIEWED_PROJECT_IDS),
                ),
            ),
        )

        assertEquals(setOf(taskId), restored.reviewedTaskIds.value)
        assertEquals(setOf(projectId), restored.reviewedProjectIds.value)
        assertFalse(restored.actionPending.value)

        restored.setActionPending(true)
        restored.finishReview()
        assertTrue(restored.reviewedTaskIds.value.isEmpty())
        assertTrue(restored.reviewedProjectIds.value.isEmpty())
        assertFalse(restored.actionPending.value)

        restored.markReviewed(taskId, null)
        restored.markReviewed(null, projectId)
        restored.setActionPending(true)
        restored.startReview()
        assertTrue(restored.reviewedTaskIds.value.isEmpty())
        assertTrue(restored.reviewedProjectIds.value.isEmpty())
        assertFalse(restored.actionPending.value)
    }
}
