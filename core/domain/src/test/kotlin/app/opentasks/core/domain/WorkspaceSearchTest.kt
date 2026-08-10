package app.opentasks.core.domain

import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TaskSortKey
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class WorkspaceSearchTest {
    private val zone = ZoneId.of("Asia/Bangkok")
    private val now = Instant.parse("2026-08-10T03:00:00Z")
    private val clock = Clock.fixed(now, zone)

    private fun task(id: String, title: String, priority: Priority = Priority.NONE) =
        OpenTasksFixtures.tasks.first().copy(
            id = TaskId(id),
            title = title,
            description = "",
            priority = priority,
            checklist = emptyList(),
            tagIds = emptySet(),
            deletedAt = null,
            completedAt = null,
            semanticStatus = SemanticStatus.PLANNED,
        )

    private fun project(id: String, name: String, summary: String = "") =
        OpenTasksFixtures.studioProject.copy(
            id = ProjectId(id),
            name = name,
            summary = summary,
            archivedAt = null,
        )

    private fun snapshot(tasks: List<Task>, projects: List<Project>) =
        OpenTasksFixtures.snapshot.copy(
            tasks = tasks,
            projects = projects,
            tags = emptyList(),
            notes = emptyList(),
            attachments = emptyList(),
        )

    private fun SearchResult.id() = when (this) {
        is SearchResult.TaskResult -> task.id.value
        is SearchResult.ProjectResult -> project.id.value
    }

    @Test
    fun titleTierPrecedesTypeAndUnicodeWordBoundaryPrecedesSubstring() {
        val input = snapshot(
            tasks = listOf(
                task("task-prefix", "alpha beta"),
                task("task-word", "go-alpha"),
                task("task-unicode-substring", "βalpha"),
                task("task-substring", "go2alpha"),
                task("task-exact", "ALPHA"),
            ),
            projects = listOf(project("project-exact", "alpha")),
        )
        assertEquals(
            listOf(
                "task-exact",
                "project-exact",
                "task-prefix",
                "task-word",
                "task-substring",
                "task-unicode-substring",
            ),
            searchWorkspace(input, SearchQuery("alpha"), clock).map { it.id() },
        )
        assertEquals(
            searchWorkspace(input, SearchQuery("alpha"), clock).map { it.id() },
            searchWorkspace(
                input.copy(
                    tasks = input.tasks.reversed(),
                    projects = input.projects.reversed(),
                ),
                SearchQuery("alpha"),
                clock,
            ).map { it.id() },
        )
    }

    @Test
    fun rankThenCapKeepsTheExactProject() {
        val tasks = (0..50).map { task("substring-%02d".format(it), "xalpha-$it") }
        val results = searchWorkspace(
            snapshot(tasks, listOf(project("exact-project", "alpha"))),
            SearchQuery("alpha"),
            clock,
        )
        assertEquals(50, results.size)
        assertEquals("exact-project", results.first().id())
    }

    @Test
    fun widerHaystackAndEveryTaskFilterComposeWhileProjectsRemainEligible() {
        val due = ZonedMoment(now.plusSeconds(3_600), zone.id)
        val keep = task("keep", "zzz", Priority.URGENT).copy(
            projectId = ProjectId("p"),
            due = due,
            tagIds = setOf(TagId("tag")),
            semanticStatus = SemanticStatus.STARTED,
            description = "alpha",
        )
        val input = snapshot(
            tasks = listOf(
                keep,
                keep.copy(id = TaskId("wrong-priority"), priority = Priority.LOW),
                keep.copy(id = TaskId("wrong-project"), projectId = ProjectId("other")),
                keep.copy(id = TaskId("wrong-tag"), tagIds = emptySet()),
                keep.copy(
                    id = TaskId("wrong-completed"),
                    semanticStatus = SemanticStatus.COMPLETED,
                    completedAt = now,
                ),
                keep.copy(id = TaskId("wrong-trash"), deletedAt = now),
                keep.copy(
                    id = TaskId("wrong-due"),
                    due = ZonedMoment(now.plusSeconds(86_400), zone.id),
                ),
                keep.copy(
                    id = TaskId("wrong-status"),
                    semanticStatus = SemanticStatus.BLOCKED,
                ),
            ),
            projects = listOf(project("project", "alpha")),
        ).copy(
            tags = listOf(Tag(TagId("tag"), OpenTasksFixtures.workspaceId, "Focus")),
        )
        val query = SearchQuery(
            text = "alpha",
            projectIds = setOf(ProjectId("p")),
            tagIds = setOf(TagId("tag")),
            includeCompleted = false,
            dueBuckets = setOf(DueBucket.TODAY),
            priorities = setOf(Priority.URGENT),
            statuses = setOf(SemanticStatus.STARTED, SemanticStatus.COMPLETED),
        )
        assertEquals(
            listOf("project", "keep"),
            searchWorkspace(input, query, clock).map { it.id() },
        )
        assertTrue(searchWorkspace(input, SearchQuery(""), clock).isEmpty())
        assertEquals(
            listOf("keep"),
            searchWorkspace(input, query.copy(text = ""), clock).map { it.id() },
        )
    }

    @Test
    fun textSortRanksAndCapsBeforeSortingSurvivingTasksAheadOfProjects() {
        val prefix = task("prefix", "alpha beta", Priority.LOW)
        val substringA = task("substring-a", "xalpha", Priority.URGENT)
        val substringB = task("substring-b", "yalpha", Priority.HIGH)
        val input = snapshot(
            listOf(prefix, substringA, substringB),
            listOf(project("exact-project", "alpha")),
        )
        assertEquals(
            listOf("substring-a", "substring-b", "prefix", "exact-project"),
            searchWorkspace(input, SearchQuery("alpha", sort = TaskSortKey.PRIORITY), clock)
                .map { it.id() },
        )
        assertEquals(
            listOf("substring-a", "substring-b", "prefix"),
            searchWorkspace(input, SearchQuery("", sort = TaskSortKey.PRIORITY), clock)
                .map { it.id() },
        )

        val capInput = snapshot(
            tasks = (0 until 50).map { index ->
                task("cap-prefix-%02d".format(index), "alpha cap $index", Priority.LOW)
            } + task("cap-urgent", "xalpha", Priority.URGENT),
            projects = emptyList(),
        )
        val capped = searchWorkspace(
            capInput,
            SearchQuery("alpha", sort = TaskSortKey.PRIORITY),
            clock,
        ).map { it.id() }
        assertEquals(50, capped.size)
        assertFalse(capped.contains("cap-urgent"))
    }

    @Test
    fun relationHaystacksAreSubstringTierAndIndependentOfInputOrder() {
        val hiddenTag = Tag(TagId("hidden-tag"), OpenTasksFixtures.workspaceId, "Hidden")
        val needleTag = Tag(TagId("needle-tag"), OpenTasksFixtures.workspaceId, "Needle")
        val exact = task("exact", "hidden needle")
        val checklist = task("checklist", "A checklist").copy(
            checklist = listOf(
                ChecklistItem("needle-item", "Needle", false, "b"),
                ChecklistItem("hidden-item", "Hidden", false, "a"),
            ),
        )
        val note = task("note", "B note")
        val tag = task("tag", "C tag").copy(
            tagIds = linkedSetOf(needleTag.id, hiddenTag.id),
        )
        val attachment = task("attachment", "D attachment")
        val relationNotes = listOf(
            Note(
                id = NoteId("task-note-later"),
                taskId = note.id,
                projectId = null,
                body = "Needle",
                createdAt = now.plusSeconds(1),
                editedAt = null,
                revision = note.revision,
            ),
            Note(
                id = NoteId("task-note-earlier"),
                taskId = note.id,
                projectId = null,
                body = "Hidden",
                createdAt = now,
                editedAt = null,
                revision = note.revision,
            ),
            Note(
                id = NoteId("project-note"),
                taskId = null,
                projectId = ProjectId("project"),
                body = "Needle",
                createdAt = now,
                editedAt = null,
                revision = note.revision,
            ),
        )
        val input = snapshot(
            tasks = listOf(exact, checklist, note, tag, attachment),
            projects = listOf(project("project", "E project", "Hidden")),
        ).copy(
            tags = listOf(needleTag, hiddenTag),
            notes = relationNotes,
            attachments = listOf(
                Attachment(
                    id = AttachmentId("attachment"),
                    taskId = attachment.id,
                    displayName = "Hidden needle.pdf",
                    mimeType = "application/pdf",
                    byteCount = 1,
                    contentHash = "sha256:search",
                    blobSetId = null,
                    chunkCount = 1,
                    deletedAt = null,
                    revision = attachment.revision,
                ),
            ),
        )
        val expected = listOf("exact", "checklist", "note", "tag", "attachment", "project")

        assertEquals(
            expected,
            searchWorkspace(input, SearchQuery("hidden needle"), clock).map { it.id() },
        )
        assertEquals(
            expected,
            searchWorkspace(
                input.copy(
                    tasks = input.tasks.reversed().map { task ->
                        task.copy(
                            checklist = task.checklist.reversed(),
                            tagIds = task.tagIds.reversed().toSet(),
                        )
                    },
                    projects = input.projects.reversed(),
                    tags = input.tags.reversed(),
                    notes = input.notes.reversed(),
                    attachments = input.attachments.reversed(),
                ),
                SearchQuery("hidden needle"),
                clock,
            ).map { it.id() },
        )
    }
}
