package app.opentasks.core.data

import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class InMemoryVaultRepositoryTest {
    private val repository = InMemoryVaultRepository(
        now = { Instant.parse("2026-07-26T10:00:00Z") },
    )

    @Test
    fun blockedCompletionRequiresExplicitAcknowledgement() = runBlocking {
        val blocked = OpenTasksFixtures.tasks.first { it.isBlocked }

        val first = repository.execute(DomainCommand.CompleteTask(blocked.id))
        assertEquals(
            RejectionReason.BLOCKED_TASK_WARNING_REQUIRED,
            (first as CommandResult.Rejected).reason,
        )

        repository.execute(
            DomainCommand.CompleteTask(
                blocked.id,
                acknowledgeBlocked = true,
            ),
        )
        assertTrue(repository.observeWorkspace().value.tasks.first { it.id == blocked.id }.isCompleted)
    }

    @Test
    fun undoCommandReopensCompletedTask() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted && !it.isBlocked }
        val result = repository.execute(DomainCommand.CompleteTask(task.id)) as CommandResult.Success

        repository.execute(checkNotNull(result.undo))

        assertFalse(repository.observeWorkspace().value.tasks.first { it.id == task.id }.isCompleted)
    }

    @Test
    fun recurringCompletionCreatesNextOccurrenceAndUndoRemovesOnlyThatOccurrence() = runBlocking {
        val original = OpenTasksFixtures.tasks.first { it.checklist.isNotEmpty() }
        val due = ZonedMoment(
            instant = Instant.parse("2026-07-31T09:30:00Z"),
            zoneId = "Asia/Bangkok",
        )
        val rule = RecurrenceRule(
            frequency = RecurrenceFrequency.MONTHLY,
            count = 3,
        )
        val updated = repository.execute(
            DomainCommand.UpdateTask(
                taskId = original.id,
                title = original.title,
                description = original.description,
                projectId = original.projectId,
                priority = original.priority,
                due = due,
                recurrence = rule,
                estimate = original.estimate,
            ),
        )
        assertTrue(updated is CommandResult.Success)

        val configured = repository.observeWorkspace().value.tasks.first { it.id == original.id }
        assertEquals(original.id, configured.recurrenceSeriesId)
        assertEquals(due, configured.recurrenceAnchor)
        assertEquals(0, configured.recurrenceOccurrenceIndex)

        val completed = repository.execute(
            DomainCommand.CompleteTask(
                taskId = original.id,
                completedAt = Instant.parse("2026-07-31T10:00:00Z"),
            ),
        ) as CommandResult.Success
        assertEquals("Task completed • next occurrence scheduled", completed.message)

        val afterComplete = repository.observeWorkspace().value
        val firstOccurrence = afterComplete.tasks.first { it.id == original.id }
        val nextOccurrence = afterComplete.tasks.single {
            it.recurrenceSeriesId == original.id && it.id != original.id
        }
        assertTrue(firstOccurrence.isCompleted)
        assertEquals("2026-08-31", nextOccurrence.due?.localDateString())
        assertEquals(SemanticStatus.PLANNED, nextOccurrence.semanticStatus)
        assertEquals(1, nextOccurrence.recurrenceOccurrenceIndex)
        assertEquals(original.tagIds, nextOccurrence.tagIds)
        assertEquals(
            original.checklist.map { it.text },
            nextOccurrence.checklist.map { it.text },
        )
        assertTrue(nextOccurrence.checklist.none { it.completed })

        repository.execute(checkNotNull(completed.undo))

        val afterUndo = repository.observeWorkspace().value
        assertFalse(afterUndo.tasks.first { it.id == original.id }.isCompleted)
        assertFalse(afterUndo.tasks.any { it.id == nextOccurrence.id })
    }

    @Test
    fun repeatedRecurringCompletionIsIdempotentAndOriginalUndoStillApplies() = runBlocking {
        val original = OpenTasksFixtures.tasks.first { it.checklist.isNotEmpty() }
        val due = ZonedMoment(
            instant = Instant.parse("2026-07-31T09:30:00Z"),
            zoneId = "Asia/Bangkok",
        )
        val rule = RecurrenceRule(RecurrenceFrequency.MONTHLY, count = 3)
        repository.execute(
            DomainCommand.UpdateTask(
                taskId = original.id,
                title = original.title,
                description = original.description,
                projectId = original.projectId,
                priority = original.priority,
                due = due,
                recurrence = rule,
                estimate = original.estimate,
            ),
        )
        val firstCompletion = repository.execute(
            DomainCommand.CompleteTask(
                taskId = original.id,
                completedAt = Instant.parse("2026-07-31T10:00:00Z"),
            ),
        ) as CommandResult.Success
        val afterFirst = repository.observeWorkspace().value
        val generated = afterFirst.tasks.single {
            it.recurrenceSeriesId == original.id && it.id != original.id
        }

        val repeated = repository.execute(
            DomainCommand.CompleteTask(
                taskId = original.id,
                completedAt = Instant.parse("2026-07-31T10:00:01Z"),
            ),
        )
        val afterRepeated = repository.observeWorkspace().value

        assertTrue(repeated is CommandResult.Success)
        assertEquals(
            afterFirst.tasks.map { it.id }.toSet(),
            afterRepeated.tasks.map { it.id }.toSet(),
        )
        assertEquals(
            1,
            afterRepeated.tasks.count {
                it.recurrenceSeriesId == original.id && it.id != original.id
            },
        )

        repository.execute(checkNotNull(firstCompletion.undo))
        val afterUndo = repository.observeWorkspace().value
        assertFalse(afterUndo.tasks.first { it.id == original.id }.isCompleted)
        assertFalse(afterUndo.tasks.any { it.id == generated.id })
    }

    @Test
    fun undoingRuleChangeOnGeneratedOccurrenceRestoresSeriesMetadata() = runBlocking {
        val original = OpenTasksFixtures.tasks.first { it.checklist.isNotEmpty() }
        val anchor = ZonedMoment(
            instant = Instant.parse("2026-07-31T09:30:00Z"),
            zoneId = "Asia/Bangkok",
        )
        val monthly = RecurrenceRule(
            frequency = RecurrenceFrequency.MONTHLY,
            count = 3,
        )
        repository.execute(
            DomainCommand.UpdateTask(
                taskId = original.id,
                title = original.title,
                description = original.description,
                projectId = original.projectId,
                priority = original.priority,
                due = anchor,
                recurrence = monthly,
                estimate = original.estimate,
            ),
        )
        repository.execute(
            DomainCommand.CompleteTask(
                taskId = original.id,
                completedAt = Instant.parse("2026-07-31T10:00:00Z"),
            ),
        )
        val occurrence = repository.observeWorkspace().value.tasks.single {
            it.recurrenceSeriesId == original.id && it.id != original.id
        }
        assertEquals(1, occurrence.recurrenceOccurrenceIndex)

        val ruleChange = repository.execute(
            DomainCommand.UpdateTask(
                taskId = occurrence.id,
                title = occurrence.title,
                description = occurrence.description,
                projectId = occurrence.projectId,
                priority = occurrence.priority,
                due = occurrence.due,
                recurrence = RecurrenceRule(RecurrenceFrequency.WEEKLY),
                estimate = occurrence.estimate,
            ),
        ) as CommandResult.Success

        val changed = repository.observeWorkspace().value.tasks.first { it.id == occurrence.id }
        assertEquals(occurrence.id, changed.recurrenceSeriesId)
        assertEquals(occurrence.due, changed.recurrenceAnchor)
        assertEquals(0, changed.recurrenceOccurrenceIndex)

        repository.execute(checkNotNull(ruleChange.undo))

        val restored = repository.observeWorkspace().value.tasks.first { it.id == occurrence.id }
        assertEquals(monthly, restored.recurrence)
        assertEquals(occurrence.due, restored.due)
        assertEquals(original.id, restored.recurrenceSeriesId)
        assertEquals(anchor, restored.recurrenceAnchor)
        assertEquals(1, restored.recurrenceOccurrenceIndex)
    }

    @Test
    fun recurringTaskRequiresAnAnchorDate() = runBlocking {
        val original = OpenTasksFixtures.tasks.first { it.due == null && it.start == null }
        val result = repository.execute(
            DomainCommand.UpdateTask(
                taskId = original.id,
                title = original.title,
                description = original.description,
                projectId = original.projectId,
                priority = original.priority,
                due = null,
                recurrence = RecurrenceRule(RecurrenceFrequency.DAILY),
                estimate = original.estimate,
            ),
        )

        assertEquals(
            RejectionReason.INVALID_STATE,
            (result as CommandResult.Rejected).reason,
        )
        assertEquals(
            null,
            repository.observeWorkspace().value.tasks
                .first { it.id == original.id }
                .recurrence,
        )
    }

    @Test
    fun workflowStatusChangeUsesStatusSemanticsAndUndoRestoresExactState() = runBlocking {
        val completed = OpenTasksFixtures.tasks.first { it.isCompleted }
        val originalCompletedAt = completed.completedAt

        val result = repository.execute(
            DomainCommand.ChangeTaskStatus(
                taskId = completed.id,
                statusId = OpenTasksFixtures.started,
            ),
        ) as CommandResult.Success

        val reopened = repository.observeWorkspace().value.tasks.first { it.id == completed.id }
        assertEquals(OpenTasksFixtures.started, reopened.statusId)
        assertEquals(SemanticStatus.STARTED, reopened.semanticStatus)
        assertEquals(null, reopened.completedAt)

        repository.execute(checkNotNull(result.undo))

        val restored = repository.observeWorkspace().value.tasks.first { it.id == completed.id }
        assertEquals(completed.statusId, restored.statusId)
        assertEquals(SemanticStatus.COMPLETED, restored.semanticStatus)
        assertEquals(originalCompletedAt, restored.completedAt)
    }

    @Test
    fun movingTaskToTrashStopsItsTimerAndUndoRestoresIt() = runBlocking {
        val timedTask = OpenTasksFixtures.tasks.first {
            it.id == repository.observeWorkspace().value.home.activeTimer?.taskId
        }
        val deletedAt = Instant.parse("2026-07-26T10:05:00Z")

        val result = repository.execute(
            DomainCommand.DeleteTask(timedTask.id, deletedAt),
        ) as CommandResult.Success

        val trashed = repository.observeWorkspace().value.tasks.first { it.id == timedTask.id }
        assertEquals(deletedAt, trashed.deletedAt)
        assertEquals(null, repository.observeWorkspace().value.home.activeTimer)

        repository.execute(checkNotNull(result.undo))

        assertEquals(
            null,
            repository.observeWorkspace().value.tasks.first { it.id == timedTask.id }.deletedAt,
        )
    }

    @Test
    fun permanentAndExpiryPurgeRequireTrashAndRemoveEligibleTasks() = runBlocking {
        val active = OpenTasksFixtures.tasks[1]
        val protected = repository.execute(DomainCommand.PermanentlyDeleteTask(active.id))
        assertEquals(RejectionReason.INVALID_STATE, (protected as CommandResult.Rejected).reason)

        val expired = OpenTasksFixtures.tasks[2]
        val retained = OpenTasksFixtures.tasks[3]
        repository.execute(
            DomainCommand.DeleteTask(
                expired.id,
                Instant.parse("2026-06-25T10:00:00Z"),
            ),
        )
        repository.execute(
            DomainCommand.DeleteTask(
                retained.id,
                Instant.parse("2026-06-27T10:00:00Z"),
            ),
        )

        repository.execute(
            DomainCommand.PurgeExpiredTrash(Instant.parse("2026-07-26T10:00:00Z")),
        )

        assertFalse(repository.observeWorkspace().value.tasks.any { it.id == expired.id })
        assertTrue(repository.observeWorkspace().value.tasks.any { it.id == retained.id })

        repository.execute(DomainCommand.PermanentlyDeleteTask(retained.id))
        assertFalse(repository.observeWorkspace().value.tasks.any { it.id == retained.id })
    }

    @Test
    fun searchCoversDescriptionsAndProjects() = runBlocking {
        val descriptionResult = repository.search(SearchQuery("decision-ready"))
        val projectResult = repository.search(SearchQuery("quarterly accounts"))

        assertEquals(TaskId("task-proposal"), descriptionResult.first().let {
            (it as app.opentasks.core.model.SearchResult.TaskResult).task.id
        })
        assertTrue(projectResult.isNotEmpty())
    }

    @Test
    fun projectEditValidatesPersistsAndUndoRestoresEveryField() = runBlocking {
        val original = OpenTasksFixtures.taxProject
        val invalid = repository.execute(
            DomainCommand.UpdateProject(
                projectId = original.id,
                name = " ",
                summary = original.summary,
                health = original.status,
                dueDate = original.dueDate,
            ),
        )
        assertEquals(
            RejectionReason.EMPTY_PROJECT_NAME,
            (invalid as CommandResult.Rejected).reason,
        )

        val result = repository.execute(
            DomainCommand.UpdateProject(
                projectId = original.id,
                name = "  Quarterly filing  ",
                summary = "Prepare and submit the complete filing pack.",
                health = ProjectHealth.ON_TRACK,
                dueDate = LocalDate.of(2026, 8, 2),
            ),
        ) as CommandResult.Success

        val updated = repository.observeWorkspace().value.projects.first { it.id == original.id }
        assertEquals("Quarterly filing", updated.name)
        assertEquals("Prepare and submit the complete filing pack.", updated.summary)
        assertEquals(ProjectHealth.ON_TRACK, updated.status)
        assertEquals(LocalDate.of(2026, 8, 2), updated.dueDate)
        assertTrue(repository.search(SearchQuery("complete filing pack")).isNotEmpty())

        repository.execute(checkNotNull(result.undo))
        assertEquals(
            original,
            repository.observeWorkspace().value.projects.first { it.id == original.id },
        )
    }

    @Test
    fun projectCreateArchiveAndRestorePreserveTaskRecordsAndActiveViews() = runBlocking {
        val createdId = ProjectId("project-client-portal")
        val created = repository.execute(
            DomainCommand.CreateProject(
                projectId = createdId,
                name = "  Client portal  ",
                summary = "Prepare a secure client launch.",
            ),
        )
        assertTrue(created is CommandResult.Success)
        assertEquals(
            "Client portal",
            repository.observeWorkspace().value.projects.first { it.id == createdId }.name,
        )
        assertTrue(repository.observeWorkspace().value.home.projects.any { it.id == createdId })

        val duplicate = repository.execute(
            DomainCommand.CreateProject(
                projectId = ProjectId("project-client-portal-copy"),
                name = "client PORTAL",
            ),
        )
        assertEquals(
            RejectionReason.DUPLICATE_PROJECT_NAME,
            (duplicate as CommandResult.Rejected).reason,
        )

        val project = OpenTasksFixtures.studioProject
        val originalTasks = repository.observeWorkspace().value.tasks
            .filter { it.projectId == project.id }
        val archived = repository.execute(
            DomainCommand.ArchiveProject(
                projectId = project.id,
                archivedAt = Instant.parse("2026-07-26T11:00:00Z"),
            ),
        ) as CommandResult.Success
        val archivedSnapshot = repository.observeWorkspace().value

        assertEquals(
            Instant.parse("2026-07-26T11:00:00Z"),
            archivedSnapshot.projects.first { it.id == project.id }.archivedAt,
        )
        assertFalse(archivedSnapshot.home.projects.any { it.id == project.id })
        assertEquals(
            originalTasks,
            archivedSnapshot.tasks.filter { it.projectId == project.id },
        )
        assertFalse(
            repository.search(SearchQuery(project.summary))
                .any { it is app.opentasks.core.model.SearchResult.ProjectResult },
        )
        val assignment = repository.execute(
            DomainCommand.CreateTask("Should stay active-only", project.id),
        )
        assertEquals(
            RejectionReason.INVALID_STATE,
            (assignment as CommandResult.Rejected).reason,
        )

        repository.execute(checkNotNull(archived.undo))
        assertTrue(repository.observeWorkspace().value.home.projects.any { it.id == project.id })
        assertEquals(
            null,
            repository.observeWorkspace().value.projects.first { it.id == project.id }.archivedAt,
        )
    }

    @Test
    fun timerEnforcesOneActiveEntryAndCanStop() = runBlocking {
        repository.execute(DomainCommand.StopTimer)
        val task = OpenTasksFixtures.tasks.first()

        val started = repository.execute(
            DomainCommand.StartTimer(task.id, Instant.parse("2026-07-26T10:00:00Z")),
        )
        val competing = repository.execute(
            DomainCommand.StartTimer(OpenTasksFixtures.tasks.last().id),
        )

        assertTrue(started is CommandResult.Success)
        assertEquals(
            RejectionReason.INVALID_STATE,
            (competing as CommandResult.Rejected).reason,
        )
        assertEquals(task.id, repository.observeWorkspace().value.home.activeTimer?.taskId)

        repository.execute(DomainCommand.StopTimer)
        assertEquals(null, repository.observeWorkspace().value.home.activeTimer)
    }

    @Test
    fun taskDetailsUpdateTogetherAndUndoRestoresEveryField() = runBlocking {
        val original = OpenTasksFixtures.tasks.first { !it.isCompleted }
        val due = ZonedMoment(
            instant = Instant.parse("2026-08-03T10:00:00Z"),
            zoneId = "Asia/Bangkok",
        )

        val result = repository.execute(
            DomainCommand.UpdateTask(
                taskId = original.id,
                title = "  Updated launch proposal  ",
                description = "Persist the agreed decisions.",
                projectId = OpenTasksFixtures.taxProject.id,
                priority = Priority.URGENT,
                due = due,
                recurrence = original.recurrence,
                estimate = Duration.ofMinutes(45),
            ),
        ) as CommandResult.Success

        val updated = repository.observeWorkspace().value.tasks.first { it.id == original.id }
        assertEquals("Updated launch proposal", updated.title)
        assertEquals("Persist the agreed decisions.", updated.description)
        assertEquals(OpenTasksFixtures.taxProject.id, updated.projectId)
        assertEquals(Priority.URGENT, updated.priority)
        assertEquals(due, updated.due)
        assertEquals(Duration.ofMinutes(45), updated.estimate)
        assertTrue(updated.revision.logicalCounter > original.revision.logicalCounter)

        repository.execute(checkNotNull(result.undo))

        val restored = repository.observeWorkspace().value.tasks.first { it.id == original.id }
        assertEquals(original.title, restored.title)
        assertEquals(original.description, restored.description)
        assertEquals(original.projectId, restored.projectId)
        assertEquals(original.priority, restored.priority)
        assertEquals(original.due, restored.due)
        assertEquals(original.estimate, restored.estimate)
    }

    @Test
    fun taskEditorRejectsInvalidTextWithoutChangingTask() = runBlocking {
        val original = OpenTasksFixtures.tasks.first()
        val result = repository.execute(
            DomainCommand.UpdateTask(
                taskId = original.id,
                title = "x".repeat(241),
                description = original.description,
                projectId = original.projectId,
                priority = original.priority,
                due = original.due,
                recurrence = original.recurrence,
                estimate = original.estimate,
            ),
        )

        assertEquals(
            RejectionReason.TITLE_TOO_LONG,
            (result as CommandResult.Rejected).reason,
        )
        assertEquals(
            original,
            repository.observeWorkspace().value.tasks.first { it.id == original.id },
        )
    }

    @Test
    fun checklistCommandsAddEditDeleteAndUndoWithoutLosingOrder() = runBlocking {
        val original = OpenTasksFixtures.tasks.first { it.checklist.isNotEmpty() }

        val added = repository.execute(
            DomainCommand.AddChecklistItem(original.id, "  Verify final figures  "),
        ) as CommandResult.Success
        val afterAdd = repository.observeWorkspace().value.tasks.first { it.id == original.id }
        val newItem = afterAdd.checklist.single { it.text == "Verify final figures" }
        assertEquals(original.checklist.size + 1, afterAdd.checklist.size)
        assertEquals(newItem, afterAdd.checklist.last())

        val edited = repository.execute(
            DomainCommand.UpdateChecklistItem(
                taskId = original.id,
                itemId = newItem.id,
                text = "Verify signed figures",
                completed = true,
            ),
        ) as CommandResult.Success
        val afterEdit = repository.observeWorkspace().value.tasks
            .first { it.id == original.id }
            .checklist
            .single { it.id == newItem.id }
        assertEquals("Verify signed figures", afterEdit.text)
        assertTrue(afterEdit.completed)

        repository.execute(checkNotNull(edited.undo))
        val afterEditUndo = repository.observeWorkspace().value.tasks
            .first { it.id == original.id }
            .checklist
            .single { it.id == newItem.id }
        assertEquals(newItem, afterEditUndo)

        val deleted = repository.execute(
            DomainCommand.DeleteChecklistItem(original.id, newItem.id),
        ) as CommandResult.Success
        assertFalse(
            repository.observeWorkspace().value.tasks
                .first { it.id == original.id }
                .checklist
                .any { it.id == newItem.id },
        )

        repository.execute(checkNotNull(deleted.undo))
        assertEquals(
            newItem,
            repository.observeWorkspace().value.tasks
                .first { it.id == original.id }
                .checklist
                .single { it.id == newItem.id },
        )
        assertTrue(repository.search(SearchQuery("Verify final figures")).isNotEmpty())

        assertTrue(repository.execute(checkNotNull(added.undo)) is CommandResult.Success)
    }

    @Test
    fun creatingAndAssigningTagIsSearchableAndUndoable() = runBlocking {
        val original = OpenTasksFixtures.tasks.first()

        val created = repository.execute(
            DomainCommand.CreateAndAssignTag(original.id, "  Waiting  "),
        ) as CommandResult.Success
        val snapshot = repository.observeWorkspace().value
        val tag = snapshot.tags.single { it.name == "Waiting" }
        assertTrue(tag.id in snapshot.tasks.first { it.id == original.id }.tagIds)
        assertTrue(repository.search(SearchQuery("waiting")).isNotEmpty())

        repository.execute(checkNotNull(created.undo))

        val afterUndo = repository.observeWorkspace().value
        assertTrue(afterUndo.tags.any { it.id == tag.id })
        assertFalse(tag.id in afterUndo.tasks.first { it.id == original.id }.tagIds)
    }

    private fun ZonedMoment.localDateString(): String =
        instant.atZone(java.time.ZoneId.of(zoneId)).toLocalDate().toString()
}
