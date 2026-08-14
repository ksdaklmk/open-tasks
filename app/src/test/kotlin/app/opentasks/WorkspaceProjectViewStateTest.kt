package app.opentasks

import androidx.lifecycle.SavedStateHandle
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.ProjectPresentation
import app.opentasks.core.model.TaskId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceProjectViewStateTest {
    @Test
    fun presentationAnchorAndDependencySelectionRestorePerProject() {
        val handle = SavedStateHandle()
        val state = WorkspaceProjectViewState(handle)
        val first = ProjectId("first-project")
        val second = ProjectId("second-project")
        val firstAnchor = LocalDate.of(2024, 1, 1)
        val secondAnchor = LocalDate.of(2024, 1, 8)
        val firstTask = TaskId("first-task")
        val secondTask = TaskId("second-task")

        state.setProjectPresentation(first, ProjectPresentation.BOARD)
        state.setProjectTimelineFirstDate(first, firstAnchor)
        state.setProjectTimelineSelection(first, firstTask)

        assertEquals(
            mapOf(first to ProjectPresentation.BOARD),
            state.state.value.presentationByProject,
        )
        assertEquals(
            mapOf(first to firstAnchor),
            state.state.value.timelineFirstDateByProject,
        )
        assertEquals(
            mapOf(first to firstTask),
            state.state.value.selectedTimelineTaskByProject,
        )

        state.setProjectPresentation(second, ProjectPresentation.TIMELINE)
        state.setProjectTimelineFirstDate(second, secondAnchor)
        state.setProjectTimelineSelection(second, secondTask)

        val expected = ProjectWorkbenchViewState(
            presentationByProject = mapOf(
                first to ProjectPresentation.BOARD,
                second to ProjectPresentation.TIMELINE,
            ),
            timelineFirstDateByProject = mapOf(first to firstAnchor, second to secondAnchor),
            selectedTimelineTaskByProject = mapOf(first to firstTask, second to secondTask),
        )
        assertEquals(expected, state.state.value)

        val restored = WorkspaceProjectViewState(
            SavedStateHandle(
                mapOf(
                    WorkspaceProjectViewState.PROJECT_BOARD_MODE_IDS to
                        handle.get<List<String>>(WorkspaceProjectViewState.PROJECT_BOARD_MODE_IDS),
                    WorkspaceProjectViewState.PROJECT_TIMELINE_MODE_IDS to
                        handle.get<List<String>>(
                            WorkspaceProjectViewState.PROJECT_TIMELINE_MODE_IDS,
                        ),
                    WorkspaceProjectViewState.PROJECT_TIMELINE_ANCHORS to
                        handle.get<List<String>>(
                            WorkspaceProjectViewState.PROJECT_TIMELINE_ANCHORS,
                        ),
                    WorkspaceProjectViewState.PROJECT_TIMELINE_SELECTIONS to
                        handle.get<List<String>>(
                            WorkspaceProjectViewState.PROJECT_TIMELINE_SELECTIONS,
                        ),
                ),
            ),
        )
        assertEquals(expected, restored.state.value)

        state.setProjectPresentation(first, ProjectPresentation.LIST)
        assertEquals(
            mapOf(second to ProjectPresentation.TIMELINE),
            state.state.value.presentationByProject,
        )
        assertEquals(
            emptyList<String>(),
            handle.get<List<String>>(WorkspaceProjectViewState.PROJECT_BOARD_MODE_IDS),
        )

        state.setProjectTimelineSelection(second, null)
        assertEquals(
            mapOf(first to firstTask),
            state.state.value.selectedTimelineTaskByProject,
        )
    }

    @Test
    fun legacyBoardIdsRestoreAsBoardPresentation() {
        val legacy = ProjectId("legacy-project")
        val handle = SavedStateHandle(
            mapOf(
                WorkspaceProjectViewState.PROJECT_BOARD_MODE_IDS to arrayListOf(legacy.value),
            ),
        )

        val state = WorkspaceProjectViewState(handle)

        assertEquals(
            mapOf(legacy to ProjectPresentation.BOARD),
            state.state.value.presentationByProject,
        )
        assertEquals(emptyMap<ProjectId, LocalDate>(), state.state.value.timelineFirstDateByProject)
        assertEquals(
            emptyMap<ProjectId, TaskId>(),
            state.state.value.selectedTimelineTaskByProject,
        )
    }

    @Test
    fun malformedSavedStateFallsBackWithoutThrowing() {
        val monday = LocalDate.of(2024, 1, 1)
        val tuesday = LocalDate.of(2024, 1, 2)
        val anchors = listOf(
            "", monday.toEpochDay().toString(),
            "blank-date-project", "",
            "invalid-date-project", "not-a-number",
            "non-monday-project", tuesday.toEpochDay().toString(),
            "duplicate-project", monday.toEpochDay().toString(),
            "duplicate-project", monday.plusDays(7).toEpochDay().toString(),
            "unpaired-project",
        )
        val state = WorkspaceProjectViewState(
            SavedStateHandle(
                mapOf(
                    WorkspaceProjectViewState.PROJECT_BOARD_MODE_IDS to
                        listOf("conflicted-project", "", " ", 42, "board-only-project"),
                    WorkspaceProjectViewState.PROJECT_TIMELINE_MODE_IDS to
                        listOf("conflicted-project"),
                    WorkspaceProjectViewState.PROJECT_TIMELINE_ANCHORS to anchors,
                    WorkspaceProjectViewState.PROJECT_TIMELINE_SELECTIONS to 99,
                ),
            ),
        )

        assertEquals(
            mapOf(
                ProjectId("conflicted-project") to ProjectPresentation.TIMELINE,
                ProjectId("board-only-project") to ProjectPresentation.BOARD,
            ),
            state.state.value.presentationByProject,
        )
        assertEquals(
            mapOf(ProjectId("duplicate-project") to monday),
            state.state.value.timelineFirstDateByProject,
        )
        assertEquals(emptyMap<ProjectId, TaskId>(), state.state.value.selectedTimelineTaskByProject)
    }
}
