package app.opentasks.feature.tasks

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Revision
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NotesActivitySectionInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun timelineInterleavesNotesAndEventsNewestFirst() {
        composeRule.setContent {
            OpenTasksTheme {
                NotesActivitySection(
                    notes = listOf(OLDER_NOTE, NEWER_NOTE),
                    activity = listOf(MIDDLE_EVENT),
                    onAddNote = {},
                    onUpdateNote = { _, _ -> },
                    onDeleteNote = {},
                )
            }
        }

        val rows = composeRule.onAllNodesWithTag("timeline-item")
        rows.assertCountEquals(3)
        rows[0].assertTextContains(NEWER_NOTE.body, substring = true)
        rows[1].assertTextContains(MIDDLE_EVENT.body, substring = true)
        rows[2].assertTextContains(OLDER_NOTE.body, substring = true)
    }

    @Test
    fun addingNoteDispatchesTrimmedBody() {
        val added = AtomicReference<String?>()
        composeRule.setContent {
            OpenTasksTheme {
                NotesActivitySection(
                    notes = emptyList(),
                    activity = emptyList(),
                    onAddNote = added::set,
                    onUpdateNote = { _, _ -> },
                    onDeleteNote = {},
                )
            }
        }

        composeRule.onNodeWithTag("note-field")
            .performTextInput("   Client approved the scope   ")
        composeRule.onNodeWithTag("add-note").performClick()

        assertEquals("Client approved the scope", added.get())
    }

    @Test
    fun editingNoteDispatchesTheEditedBodyForThatNote() {
        val updated = AtomicReference<Pair<NoteId, String>?>()
        composeRule.setContent {
            OpenTasksTheme {
                NotesActivitySection(
                    notes = listOf(OLDER_NOTE, NEWER_NOTE),
                    activity = listOf(MIDDLE_EVENT),
                    onAddNote = {},
                    onUpdateNote = { noteId, body -> updated.set(noteId to body) },
                    onDeleteNote = {},
                )
            }
        }

        composeRule.onNodeWithTag("timeline-edit-${NEWER_NOTE.id.value}").performClick()
        composeRule.onNodeWithTag("note-field")
            .performTextReplacement("Scope confirmed in writing")
        composeRule.onNodeWithTag("add-note").performClick()

        assertEquals(NEWER_NOTE.id to "Scope confirmed in writing", updated.get())
    }

    @Test
    fun deletingNoteDispatchesOnlyThatNoteIdentity() {
        val deleted = AtomicReference<NoteId?>()
        composeRule.setContent {
            OpenTasksTheme {
                NotesActivitySection(
                    notes = listOf(OLDER_NOTE, NEWER_NOTE),
                    activity = listOf(MIDDLE_EVENT),
                    onAddNote = {},
                    onUpdateNote = { _, _ -> },
                    onDeleteNote = deleted::set,
                )
            }
        }

        composeRule.onNodeWithTag("timeline-delete-${OLDER_NOTE.id.value}").performClick()

        assertEquals(OLDER_NOTE.id, deleted.get())
    }

    @Test
    fun activityRowsAreReadOnly() {
        composeRule.setContent {
            OpenTasksTheme {
                NotesActivitySection(
                    notes = listOf(NEWER_NOTE),
                    activity = listOf(MIDDLE_EVENT),
                    onAddNote = {},
                    onUpdateNote = { _, _ -> },
                    onDeleteNote = {},
                )
            }
        }

        composeRule.onNodeWithTag("timeline-edit-${MIDDLE_EVENT.id}").assertDoesNotExist()
        composeRule.onNodeWithTag("timeline-delete-${MIDDLE_EVENT.id}").assertDoesNotExist()
    }

    @Test
    fun taskDetailAddsNoteForTheSelectedTask() {
        val task = OpenTasksFixtures.tasks.first()
        val added = AtomicReference<Pair<TaskId, String>?>()
        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(task),
                    projectNames = emptyMap(),
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = emptyList(),
                    selectedTaskId = task.id,
                    showDetailPane = false,
                    onSelectTask = {},
                    onCloseDetail = {},
                    onCompleteTask = {},
                    onChangeTaskStatus = { _, _ -> },
                    onDeleteTask = {},
                    activeTimerTaskId = null,
                    onToggleTimer = {},
                    onUpdateTask = { _, _ -> },
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                    notes = listOf(NEWER_NOTE.copy(taskId = task.id)),
                    activityEntries = listOf(MIDDLE_EVENT.copy(taskId = task.id)),
                    onAddNote = { taskId, body -> added.set(taskId to body) },
                    onUpdateNote = { _, _ -> },
                    onDeleteNote = {},
                )
            }
        }

        composeRule.onNodeWithTag("note-field")
            .performScrollTo()
            .performTextInput(" Filed the brief ")
        composeRule.onNodeWithTag("add-note").performScrollTo().performClick()

        assertEquals(task.id to "Filed the brief", added.get())
    }

    @Test
    fun selectingAnotherTaskDiscardsTheDraftAndTheEditTarget() {
        val first = OpenTasksFixtures.tasks[0]
        val second = OpenTasksFixtures.tasks[1]
        val firstNote = NEWER_NOTE.copy(taskId = first.id)
        val selected = mutableStateOf(first.id)
        val added = AtomicReference<Pair<TaskId, String>?>()
        val updated = AtomicReference<Pair<NoteId, String>?>()
        composeRule.setContent {
            OpenTasksTheme {
                TasksScreen(
                    tasks = listOf(first, second),
                    projectNames = emptyMap(),
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    tags = emptyList(),
                    selectedTaskId = selected.value,
                    showDetailPane = false,
                    onSelectTask = {},
                    onCloseDetail = {},
                    onCompleteTask = {},
                    onChangeTaskStatus = { _, _ -> },
                    onDeleteTask = {},
                    activeTimerTaskId = null,
                    onToggleTimer = {},
                    onUpdateTask = { _, _ -> },
                    onAddChecklistItem = { _, _ -> },
                    onUpdateChecklistItem = { _, _ -> },
                    onDeleteChecklistItem = { _, _ -> },
                    onSetTaskTag = { _, _, _ -> },
                    onCreateAndAssignTag = { _, _ -> },
                    notes = listOf(firstNote),
                    onAddNote = { taskId, body -> added.set(taskId to body) },
                    onUpdateNote = { noteId, body -> updated.set(noteId to body) },
                    onDeleteNote = {},
                )
            }
        }

        composeRule.onNodeWithTag("timeline-edit-${firstNote.id.value}")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { selected.value = second.id }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("note-field")
            .performScrollTo()
            .performTextInput("Chased the invoice")
        composeRule.onNodeWithTag("add-note").performScrollTo().performClick()

        assertNull(updated.get())
        assertEquals(second.id to "Chased the invoice", added.get())
    }

    private companion object {
        val REVISION = Revision(DeviceId("fixture-device"), 1_722_000_000_000, 0)
        val OLDER_NOTE = Note(
            id = NoteId("note-older"),
            taskId = TaskId("task-proposal"),
            projectId = null,
            body = "Kickoff decisions captured",
            createdAt = Instant.parse("2026-07-20T09:00:00Z"),
            editedAt = null,
            revision = REVISION,
        )
        val MIDDLE_EVENT = ActivityEntry(
            id = "activity-status",
            taskId = TaskId("task-proposal"),
            projectId = null,
            kind = ActivityKind.STATUS_CHANGED,
            body = "Status changed to In progress",
            createdAt = Instant.parse("2026-07-22T09:00:00Z"),
        )
        val NEWER_NOTE = Note(
            id = NoteId("note-newer"),
            taskId = TaskId("task-proposal"),
            projectId = null,
            body = "Waiting on the client signature",
            createdAt = Instant.parse("2026-07-24T09:00:00Z"),
            editedAt = null,
            revision = REVISION,
        )
    }
}
