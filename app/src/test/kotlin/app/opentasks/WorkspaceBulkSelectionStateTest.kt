package app.opentasks

import androidx.lifecycle.SavedStateHandle
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceBulkSelectionStateTest {
    @Test
    fun bulkSelectionRestoresFromRawSavedStateAndClears() {
        val initialHandle = SavedStateHandle()
        val initialState = WorkspaceBulkSelectionState(initialHandle)

        initialState.toggle(TaskId("bulk-first"))
        initialState.toggle(TaskId("bulk-second"))

        val restoredHandle = SavedStateHandle(
            mapOf(
                WorkspaceBulkSelectionState.BULK_SELECTION to
                    initialHandle.get<List<String>>(
                        WorkspaceBulkSelectionState.BULK_SELECTION,
                    ),
            ),
        )
        val restoredState = WorkspaceBulkSelectionState(restoredHandle)

        assertEquals(
            setOf(TaskId("bulk-first"), TaskId("bulk-second")),
            restoredState.selection.value,
        )

        restoredState.clear()

        assertEquals(
            emptyList<String>(),
            restoredHandle.get<List<String>>(WorkspaceBulkSelectionState.BULK_SELECTION),
        )
        assertTrue(restoredState.selection.value.isEmpty())
    }
}
