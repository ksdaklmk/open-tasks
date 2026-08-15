package app.opentasks.feature.projects

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProjectNotesInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun projectTimelineShowsItsOwnNotesAndEventsNewestFirst() {
        composeRule.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = PROJECT_ID,
                    showDetailPane = false,
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onOpenTask = {},
                    notes = listOf(PROJECT_NOTE, OTHER_PROJECT_NOTE),
                    activityEntries = listOf(PROJECT_EVENT),
                )
            }
        }

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("note-field"))
        val rows = composeRule.onAllNodesWithTag("timeline-item")
        rows.assertCountEquals(2)
        rows[0].assertTextContains(PROJECT_EVENT.body, substring = true)
        rows[1].assertTextContains(PROJECT_NOTE.body, substring = true)
    }

    @Test
    fun addingProjectNoteDispatchesTrimmedBodyForThatProject() {
        val added = AtomicReference<Pair<ProjectId, String>?>()
        composeRule.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = PROJECT_ID,
                    showDetailPane = false,
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onOpenTask = {},
                    notes = emptyList(),
                    activityEntries = emptyList(),
                    onAddNote = { projectId, body -> added.set(projectId to body) },
                )
            }
        }

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("note-field"))
        composeRule.onNodeWithTag("note-field")
            .performTextInput("  Brand palette locked  ")
        composeRule.onNodeWithTag("add-note").performScrollTo().performClick()

        assertEquals(PROJECT_ID to "Brand palette locked", added.get())
    }

    @Test
    fun selectingAnotherProjectDiscardsTheDraftAndTheEditTarget() {
        val other = OpenTasksFixtures.taxProject.id
        val selected = mutableStateOf(PROJECT_ID)
        val added = AtomicReference<Pair<ProjectId, String>?>()
        val updated = AtomicReference<Pair<NoteId, String>?>()
        composeRule.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = OpenTasksFixtures.snapshot.tasks,
                    milestones = OpenTasksFixtures.snapshot.milestones,
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = selected.value,
                    showDetailPane = false,
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onOpenTask = {},
                    notes = listOf(PROJECT_NOTE),
                    onAddNote = { projectId, body -> added.set(projectId to body) },
                    onUpdateNote = { noteId, body -> updated.set(noteId to body) },
                )
            }
        }

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("timeline-edit-${PROJECT_NOTE.id.value}"))
        composeRule.onNodeWithTag("timeline-edit-${PROJECT_NOTE.id.value}").performClick()
        composeRule.runOnIdle { selected.value = other }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("note-field"))
        composeRule.onNodeWithTag("note-field").performTextInput("Filing pack ready")
        composeRule.onNodeWithTag("add-note").performScrollTo().performClick()

        assertNull(updated.get())
        assertEquals(other to "Filing pack ready", added.get())
    }

    private companion object {
        val PROJECT_ID = OpenTasksFixtures.studioProject.id
        val REVISION = Revision(DeviceId("fixture-device"), 1_722_000_000_000, 0)
        val PROJECT_NOTE = Note(
            id = NoteId("note-studio"),
            taskId = null,
            projectId = PROJECT_ID,
            body = "Kickoff deck approved",
            createdAt = Instant.parse("2026-07-15T13:00:00Z"),
            editedAt = null,
            revision = REVISION,
        )
        val OTHER_PROJECT_NOTE = Note(
            id = NoteId("note-tax"),
            taskId = null,
            projectId = OpenTasksFixtures.taxProject.id,
            body = "Filing pack still open",
            createdAt = Instant.parse("2026-07-18T13:00:00Z"),
            editedAt = null,
            revision = REVISION,
        )
        val PROJECT_EVENT = ActivityEntry(
            id = "activity-project-created",
            taskId = null,
            projectId = PROJECT_ID,
            kind = ActivityKind.RECORD_CREATED,
            body = "Project created",
            createdAt = Instant.parse("2026-07-16T13:00:00Z"),
        )
    }
}
