package app.opentasks.core.data

import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.ZonedMoment
import app.opentasks.core.domain.DomainCommand
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SearchExtensionTest {

    @Test
    fun blankDueBucketSearchUsesTheInjectedClock() = runBlocking {
        val instant = Instant.parse("2026-08-10T03:00:00Z")
        val zone = ZoneId.of("Asia/Bangkok")
        val due = ZonedMoment(instant.plusSeconds(3_600), zone.id)
        val local = InMemoryVaultRepository(
            now = { instant },
            zoneId = { zone },
        )
        local.execute(DomainCommand.CreateTask("Today adapter", due = due))
        assertEquals(
            listOf("Today adapter"),
            local.search(SearchQuery("", dueBuckets = setOf(DueBucket.TODAY)))
                .map(SearchResult::title),
        )
    }

    @Test
    fun taskNoteBodyMatchReturnsOwningTask() = runBlocking {
        withTimeout(5_000) {
            val task = OpenTasksFixtures.tasks.first()
            val repository = InMemoryVaultRepository(
                initial = OpenTasksFixtures.snapshot.copy(
                    notes = OpenTasksFixtures.notes + Note(
                        id = NoteId("note-task-search"),
                        taskId = task.id,
                        projectId = null,
                        body = "Venue catering confirmation",
                        createdAt = Instant.parse("2026-08-02T10:00:00Z"),
                        editedAt = null,
                        revision = task.revision,
                    ),
                ),
            )

            val result = repository.search(SearchQuery("catering confirmation")).single()

            assertEquals(task.id, (result as SearchResult.TaskResult).task.id)
        }
    }

    @Test
    fun projectNoteBodyMatchReturnsOwningProject() = runBlocking {
        withTimeout(5_000) {
            val project = OpenTasksFixtures.studioProject
            val repository = InMemoryVaultRepository(
                initial = OpenTasksFixtures.snapshot.copy(
                    notes = OpenTasksFixtures.notes + Note(
                        id = NoteId("note-project-search"),
                        taskId = null,
                        projectId = project.id,
                        body = "Nebula roadmap decisions",
                        createdAt = Instant.parse("2026-08-02T10:00:00Z"),
                        editedAt = null,
                        revision = OpenTasksFixtures.tasks.first().revision,
                    ),
                ),
            )

            val result = repository.search(SearchQuery("roadmap decisions")).single()

            assertEquals(project.id, (result as SearchResult.ProjectResult).project.id)
        }
    }

    @Test
    fun attachmentDisplayNameMatchReturnsOwningTask() = runBlocking {
        withTimeout(5_000) {
            val task = OpenTasksFixtures.tasks.first()
            val repository = InMemoryVaultRepository(
                initial = OpenTasksFixtures.snapshot.copy(
                    attachments = listOf(
                        Attachment(
                            id = AttachmentId("attachment-search"),
                            taskId = task.id,
                            displayName = "Launch runbook final.pdf",
                            mimeType = "application/pdf",
                            byteCount = 1,
                            contentHash = "sha256:search",
                            blobSetId = null,
                            chunkCount = 1,
                            deletedAt = null,
                            revision = task.revision,
                        ),
                    ),
                ),
            )

            val result = repository.search(SearchQuery("runbook final")).single()

            assertEquals(task.id, (result as SearchResult.TaskResult).task.id)
        }
    }

    @Test
    fun tombstonedAttachmentDisplayNameDoesNotMatch() = runBlocking {
        withTimeout(5_000) {
            val task = OpenTasksFixtures.tasks.first()
            val repository = InMemoryVaultRepository(
                initial = OpenTasksFixtures.snapshot.copy(
                    attachments = listOf(
                        Attachment(
                            id = AttachmentId("attachment-tombstone-search"),
                            taskId = task.id,
                            displayName = "Removed-only archive.zip",
                            mimeType = "application/zip",
                            byteCount = 1,
                            contentHash = "sha256:tombstone",
                            blobSetId = null,
                            chunkCount = 1,
                            deletedAt = Instant.parse("2026-08-02T10:00:00Z"),
                            revision = task.revision,
                        ),
                    ),
                ),
            )

            assertTrue(repository.search(SearchQuery("removed-only")).isEmpty())
        }
    }

    @Test
    fun activityBodyDoesNotMatch() = runBlocking {
        withTimeout(5_000) {
            val task = OpenTasksFixtures.tasks.first()
            val repository = InMemoryVaultRepository(
                initial = OpenTasksFixtures.snapshot.copy(
                    activityEntries = listOf(
                        ActivityEntry(
                            id = "activity-search",
                            taskId = task.id,
                            projectId = null,
                            kind = ActivityKind.RECORD_CREATED,
                            body = "Timeline-only log entry",
                            createdAt = Instant.parse("2026-08-02T10:00:00Z"),
                        ),
                    ),
                ),
            )

            assertTrue(repository.search(SearchQuery("timeline-only")).isEmpty())
        }
    }

    @Test
    fun noteBodySearchNormalizesDiacritics() = runBlocking {
        withTimeout(5_000) {
            val task = OpenTasksFixtures.tasks.first()
            val repository = InMemoryVaultRepository(
                initial = OpenTasksFixtures.snapshot.copy(
                    notes = OpenTasksFixtures.notes + Note(
                        id = NoteId("note-diacritic-search"),
                        taskId = task.id,
                        projectId = null,
                        body = "Café du matin",
                        createdAt = Instant.parse("2026-08-02T10:00:00Z"),
                        editedAt = null,
                        revision = task.revision,
                    ),
                ),
            )

            val result = repository.search(SearchQuery("cafe du matin")).single()

            assertEquals(task.id, (result as SearchResult.TaskResult).task.id)
        }
    }

    @Test
    fun taskNoteSearchUsesCreationOrderWhenBodiesMeetAtWordBoundary() = runBlocking {
        withTimeout(5_000) {
            val task = OpenTasksFixtures.tasks.first()
            val repository = InMemoryVaultRepository(
                initial = OpenTasksFixtures.snapshot.copy(
                    notes = listOf(
                        Note(
                            id = NoteId("note-later-search"),
                            taskId = task.id,
                            projectId = null,
                            body = "Gamma",
                            createdAt = Instant.parse("2026-08-02T11:00:00Z"),
                            editedAt = null,
                            revision = task.revision,
                        ),
                        Note(
                            id = NoteId("note-earlier-search"),
                            taskId = task.id,
                            projectId = null,
                            body = "Alpha beta",
                            createdAt = Instant.parse("2026-08-02T10:00:00Z"),
                            editedAt = null,
                            revision = task.revision,
                        ),
                    ),
                ),
            )

            val result = repository.search(SearchQuery("alpha beta gamma")).single()

            assertEquals(task.id, (result as SearchResult.TaskResult).task.id)
        }
    }
}
