package app.opentasks.core.data

import app.opentasks.core.data.backup.InMemoryBackupJournal
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.MAX_MY_DAY_RANK_LENGTH
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.domain.WorkflowMoveDirection
import app.opentasks.core.domain.myDayRankForIndex
import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.MyDayEntry
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.TimeEntry
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.ZonedMoment
import app.opentasks.core.model.WorkflowStatusId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class InMemoryVaultRepositoryTest {
    private val journal = InMemoryBackupJournal()
    private val repository = InMemoryVaultRepository(
        now = { Instant.parse("2026-07-26T10:00:00Z") },
        backupJournal = journal,
    )

    private fun invalidCreates(
        due: ZonedMoment,
    ): List<Pair<DomainCommand.CreateTask, RejectionReason>> =
        listOf(
            DomainCommand.CreateTask("   ") to RejectionReason.EMPTY_TITLE,
            DomainCommand.CreateTask("x".repeat(241)) to RejectionReason.TITLE_TOO_LONG,
            DomainCommand.CreateTask("Task", tagNames = listOf("   ")) to
                RejectionReason.EMPTY_TAG_NAME,
            DomainCommand.CreateTask("Task", tagNames = listOf("x".repeat(65))) to
                RejectionReason.TAG_NAME_TOO_LONG,
            DomainCommand.CreateTask("Task", tagNames = List(51) { "tag-$it" }) to
                RejectionReason.TAG_LIMIT_REACHED,
            DomainCommand.CreateTask("Task", estimate = Duration.ZERO) to
                RejectionReason.INVALID_STATE,
            DomainCommand.CreateTask("Task", estimate = Duration.ofMinutes(-1)) to
                RejectionReason.INVALID_STATE,
            DomainCommand.CreateTask(
                "Task",
                recurrence = RecurrenceRule(RecurrenceFrequency.DAILY),
            ) to RejectionReason.RECURRENCE_REQUIRES_DUE,
            DomainCommand.CreateTask(
                "Task",
                due = due,
                recurrence = RecurrenceRule(RecurrenceFrequency.DAILY, interval = 1_000),
            ) to RejectionReason.INVALID_STATE,
            DomainCommand.CreateTask(
                "Task",
                due = due,
                recurrence = RecurrenceRule(
                    RecurrenceFrequency.DAILY,
                    count = 2,
                    endDate = LocalDate.of(2026, 8, 12),
                ),
            ) to RejectionReason.INVALID_STATE,
            DomainCommand.CreateTask(
                "Task",
                due = due,
                recurrence = RecurrenceRule(
                    RecurrenceFrequency.DAILY,
                    endDate = LocalDate.of(2026, 8, 9),
                ),
            ) to RejectionReason.INVALID_STATE,
        )

    @Test
    fun duplicateTaskKeepsAnArchivedOpenStatus() = runBlocking {
        val fixedNow = Instant.parse("2026-08-10T10:00:00Z")
        val journal = InMemoryBackupJournal()
        val repository = InMemoryVaultRepository(
            initial = duplicationSnapshot(),
            now = { fixedNow },
            backupJournal = journal,
        )
        val before = repository.observeWorkspace().value
        val source = before.tasks.single { it.id == TaskId("duplicate-source") }
        val incomingBefore = before.tasks.single { it.id == TaskId("duplicate-incoming") }
        val result = repository.execute(DomainCommand.DuplicateTask(source.id))
            as CommandResult.Success
        val after = repository.observeWorkspace().value
        val duplicate = after.tasks.single {
            it.id != source.id && it.title == "${source.title} (copy)"
        }

        assertEquals(source.statusId, duplicate.statusId)
        assertEquals(source.semanticStatus, duplicate.semanticStatus)
        assertFalse(duplicate.isCompleted)
        assertEquals("${source.title} (copy)", duplicate.title)
        assertEquals(source.workspaceId, duplicate.workspaceId)
        assertEquals(source.projectId, duplicate.projectId)
        assertEquals(source.parentTaskId, duplicate.parentTaskId)
        assertEquals(source.description, duplicate.description)
        assertEquals(source.priority, duplicate.priority)
        assertEquals(source.start, duplicate.start)
        assertEquals(source.due, duplicate.due)
        assertEquals(source.estimate, duplicate.estimate)
        assertEquals(source.milestoneId, duplicate.milestoneId)
        assertEquals(source.tagIds, duplicate.tagIds)
        assertEquals(source.dependencyIds, duplicate.dependencyIds)
        assertEquals(setOf(TaskId("duplicate-dependency-active")), duplicate.blockedBy)
        assertEquals(source.checklist.size, duplicate.checklist.size)
        assertEquals(source.checklist.map { it.text }, duplicate.checklist.map { it.text })
        assertEquals(source.checklist.map { it.rank }, duplicate.checklist.map { it.rank })
        assertTrue(duplicate.checklist.none { it.completed })
        assertTrue(
            duplicate.checklist.map { it.id }
                .intersect(source.checklist.map { it.id }.toSet())
                .isEmpty(),
        )
        assertNull(duplicate.recurrence)
        assertDuplicateIsolation(before, after, source, duplicate)
        assertEquals(
            incomingBefore,
            after.tasks.single { it.id == TaskId("duplicate-incoming") },
        )

        repository.execute(checkNotNull(result.undo))
        val undone = repository.observeWorkspace().value
        assertNotNull(undone.tasks.single { it.id == duplicate.id }.deletedAt)
        assertEquals(source, undone.tasks.single { it.id == source.id })
        assertEquals(
            incomingBefore,
            undone.tasks.single { it.id == TaskId("duplicate-incoming") },
        )
    }

    @Test
    fun duplicateTaskMovesACompletedSourceToTheFirstActiveBacklog() = runBlocking {
        val repository = InMemoryVaultRepository(
            initial = duplicationSnapshot(),
            now = { Instant.parse("2026-08-10T10:00:00Z") },
        )
        val before = repository.observeWorkspace().value
        val completedSource = before.tasks.single {
            it.id == TaskId("duplicate-source-completed")
        }
        val success = repository.execute(
            DomainCommand.DuplicateTask(completedSource.id),
        ) as CommandResult.Success
        val duplicateId = (checkNotNull(success.undo) as DomainCommand.DeleteTask).taskId
        val duplicate = repository.observeWorkspace().value.tasks.single {
            it.id == duplicateId
        }
        assertEquals(WorkflowStatusId("duplicate-status-backlog-first"), duplicate.statusId)
        assertEquals(SemanticStatus.BACKLOG, duplicate.semanticStatus)
        assertFalse(duplicate.isCompleted)
    }

    @Test
    fun duplicateTaskRejectionsAreMutationFree() = runBlocking {
        val journal = InMemoryBackupJournal()
        val repository = InMemoryVaultRepository(
            initial = duplicationSnapshot(),
            now = { Instant.parse("2026-08-10T10:00:00Z") },
            backupJournal = journal,
        )
        assertTrue(
            repository.execute(
                DomainCommand.RenameTask(
                    TaskId("duplicate-dependency-active"),
                    "Primed unrelated task",
                ),
            ) is CommandResult.Success,
        )
        assertTrue(journal.currentGeneration > 0)
        assertTrue(journal.entries.isNotEmpty())
        val payloadSentinel = ByteArray(0)
        listOf(
            DomainCommand.DuplicateTask(TaskId("missing")) to RejectionReason.NOT_FOUND,
            DomainCommand.DuplicateTask(TaskId("duplicate-source-deleted")) to
                RejectionReason.INVALID_STATE,
            DomainCommand.DuplicateTask(TaskId("duplicate-source-missing-status")) to
                RejectionReason.INVALID_STATE,
        ).forEach { (command, expectedReason) ->
            val before = repository.observeWorkspace().value
            val generationBefore = journal.currentGeneration
            val journalBefore = journal.entries
            val result = repository.execute(command) as CommandResult.Rejected
            assertEquals(expectedReason, result.reason)
            assertEquals(before, repository.observeWorkspace().value)
            assertEquals(generationBefore, journal.currentGeneration)
            val journalAfter = journal.entries
            assertEquals(journalBefore.size, journalAfter.size)
            assertEquals(
                journalBefore.map { it.copy(payload = payloadSentinel) },
                journalAfter.map { it.copy(payload = payloadSentinel) },
            )
            journalBefore.zip(journalAfter).forEach { (expected, actual) ->
                assertArrayEquals(expected.payload, actual.payload)
            }
        }
    }

    @Test
    fun everyTaskPublicationSortsOnlySnapshotTasksById() = runBlocking {
        withTimeout(5_000) {
            val source = OpenTasksFixtures.tasks.first().copy(
                projectId = null,
                completedAt = null,
                deletedAt = null,
                checklist = emptyList(),
            )
            val z = source.copy(id = TaskId("z"), title = "Zulu")
            val a = source.copy(id = TaskId("a"), title = "Alpha")
            val m = source.copy(id = TaskId("m"), title = "Mike")
            val initial = OpenTasksFixtures.snapshot.copy(
                tasks = listOf(z, a, m),
                home = OpenTasksFixtures.snapshot.home.copy(
                    focusTasks = listOf(z, m, a),
                    upcomingTasks = listOf(m, z),
                ),
            )
            val local = InMemoryVaultRepository(initial = initial)

            assertEquals(listOf("a", "m", "z"), local.currentWorkspace().tasks.map { it.id.value })
            assertEquals(listOf("z", "m", "a"), local.currentWorkspace().home.focusTasks.map { it.id.value })
            assertEquals(listOf("m", "z"), local.currentWorkspace().home.upcomingTasks.map { it.id.value })

            local.execute(DomainCommand.DeleteTask(m.id, Instant.parse("2026-08-10T04:00:00Z")))
            val mutated = local.currentWorkspace()
            assertEquals(listOf("a", "m", "z"), mutated.tasks.map { it.id.value })
            assertEquals(m.id, mutated.tasks.single { it.id == m.id }.id)
        }
    }

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
    fun taskAndReminderUpdateTogetherAndUndoRestoresBoth() = runBlocking {
        val original = OpenTasksFixtures.tasks.first { !it.isCompleted }
        val due = ZonedMoment(
            instant = Instant.parse("2026-08-03T10:00:00Z"),
            zoneId = "Asia/Bangkok",
        )
        val reminder = Reminder(
            id = "caller-provided-id",
            taskId = TaskId("caller-provided-task"),
            triggerAt = due.copy(instant = due.instant.minus(Duration.ofHours(1))),
            precise = true,
        )

        val result = repository.execute(
            DomainCommand.UpdateTask(
                taskId = original.id,
                title = original.title,
                description = original.description,
                projectId = original.projectId,
                priority = original.priority,
                start = original.start,
                due = due,
                recurrence = original.recurrence,
                estimate = original.estimate,
                reminder = reminder,
            ),
        ) as CommandResult.Success

        val updated = repository.observeWorkspace().value
        assertEquals(due, updated.tasks.first { it.id == original.id }.due)
        assertEquals(
            reminder.copy(
                id = Reminder.primaryId(original.id),
                taskId = original.id,
            ),
            updated.reminders.single { it.taskId == original.id },
        )

        repository.execute(checkNotNull(result.undo))

        val restored = repository.observeWorkspace().value
        assertEquals(original.due, restored.tasks.first { it.id == original.id }.due)
        assertFalse(restored.reminders.any { it.taskId == original.id })
    }

    @Test
    fun setTaskScheduleUpdatesExactFieldsOnceAndUndoRestoresExactValues() = runBlocking {
        val original = OpenTasksFixtures.tasks.first { !it.isCompleted && it.recurrence == null }
            .copy(
                start = ZonedMoment(Instant.parse("2026-08-03T08:00:00Z"), "UTC"),
                due = ZonedMoment(Instant.parse("2026-08-03T10:00:00Z"), "UTC"),
            )
        val existingReminder = Reminder(
            Reminder.primaryId(original.id),
            original.id,
            ZonedMoment(Instant.parse("2026-08-03T09:00:00Z"), "UTC"),
            precise = false,
        )
        val journal = InMemoryBackupJournal()
        val local = scheduleRepository(original, existingReminder, journal)
        val before = local.currentWorkspace()
        val beforeTask = before.tasks.single { it.id == original.id }
        val generationBefore = journal.currentGeneration
        val journalSizeBefore = journal.entries.size
        val activityBefore = before.activityEntries
        val start = ZonedMoment(Instant.parse("2026-08-05T07:30:00Z"), "Asia/Bangkok")
        val due = ZonedMoment(Instant.parse("2026-08-05T09:30:00Z"), "Asia/Bangkok")
        val reminder = Reminder(
            Reminder.primaryId(original.id),
            original.id,
            ZonedMoment(Instant.parse("2026-08-05T08:30:00Z"), "Asia/Bangkok"),
            precise = true,
        )

        val result = local.execute(
            DomainCommand.SetTaskSchedule(original.id, start, due, reminder),
        ) as CommandResult.Success

        val updated = local.currentWorkspace()
        val updatedTask = updated.tasks.single { it.id == original.id }
        assertEquals(
            beforeTask.copy(start = start, due = due, revision = updatedTask.revision),
            updatedTask,
        )
        assertEquals(beforeTask.revision.logicalCounter + 1, updatedTask.revision.logicalCounter)
        assertEquals(reminder, updated.reminders.single { it.taskId == original.id })
        assertEquals(activityBefore, updated.activityEntries)
        assertEquals(generationBefore + 1, journal.currentGeneration)
        val entries = journal.entries.drop(journalSizeBefore)
        assertEquals(listOf(0, 1), entries.map { it.sequence })
        assertEquals(listOf("TASK", "REMINDER"), entries.map { it.objectType })
        assertEquals(
            DomainCommand.SetTaskSchedule(
                taskId = original.id,
                start = beforeTask.start,
                due = beforeTask.due,
                reminder = existingReminder,
                restorePastReminder = true,
            ),
            result.undo,
        )

        local.execute(checkNotNull(result.undo))

        val restored = local.currentWorkspace()
        val restoredTask = restored.tasks.single { it.id == original.id }
        assertEquals(beforeTask.start, restoredTask.start)
        assertEquals(beforeTask.due, restoredTask.due)
        assertEquals(existingReminder, restored.reminders.single { it.taskId == original.id })
    }

    @Test
    fun setTaskScheduleReplacesAndRemovesReminderInOneGeneration() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted && it.recurrence == null }
            .copy(due = ZonedMoment(Instant.parse("2026-08-04T10:00:00Z"), "UTC"))
        val existingReminder = Reminder(
            Reminder.primaryId(task.id),
            task.id,
            ZonedMoment(Instant.parse("2026-08-04T09:00:00Z"), "UTC"),
            precise = false,
        )
        val replacement = existingReminder.copy(
            triggerAt = ZonedMoment(Instant.parse("2026-08-04T08:00:00Z"), "UTC"),
            precise = true,
        )
        val journal = InMemoryBackupJournal()
        val local = scheduleRepository(task, existingReminder, journal)
        val revisionBefore = local.currentWorkspace().tasks.single { it.id == task.id }.revision
        val generationBefore = journal.currentGeneration
        val entriesBefore = journal.entries.size

        local.execute(
            DomainCommand.SetTaskSchedule(task.id, task.start, task.due, replacement),
        )

        val replaced = local.currentWorkspace()
        val replacedTask = replaced.tasks.single { it.id == task.id }
        assertEquals(revisionBefore.logicalCounter + 1, replacedTask.revision.logicalCounter)
        assertEquals(replacement, replaced.reminders.single { it.taskId == task.id })
        assertEquals(generationBefore + 1, journal.currentGeneration)
        assertEquals(
            listOf("TASK", "REMINDER"),
            journal.entries.drop(entriesBefore).map { it.objectType },
        )
        val replacementGeneration = journal.currentGeneration
        val entriesAfterReplacement = journal.entries.size

        local.execute(
            DomainCommand.SetTaskSchedule(task.id, task.start, task.due, reminder = null),
        )

        val removed = local.currentWorkspace()
        val removedTask = removed.tasks.single { it.id == task.id }
        assertEquals(replacedTask.revision.logicalCounter + 1, removedTask.revision.logicalCounter)
        assertFalse(removed.reminders.any { it.taskId == task.id })
        assertEquals(replacementGeneration + 1, journal.currentGeneration)
        assertEquals(
            listOf("TASK", "REMINDER"),
            journal.entries.drop(entriesAfterReplacement).map { it.objectType },
        )
    }

    @Test
    fun setTaskSchedulePreservesRecurrenceMetadata() = runBlocking {
        val original = OpenTasksFixtures.tasks.first { !it.isCompleted }
            .copy(
                start = ZonedMoment(Instant.parse("2026-08-03T08:00:00Z"), "UTC"),
                due = ZonedMoment(Instant.parse("2026-08-03T10:00:00Z"), "UTC"),
                recurrence = RecurrenceRule(RecurrenceFrequency.WEEKLY, count = 8),
                recurrenceSeriesId = TaskId("schedule-series"),
                recurrenceAnchor = ZonedMoment(
                    Instant.parse("2026-07-27T10:00:00Z"),
                    "UTC",
                ),
                recurrenceOccurrenceIndex = 3,
            )
        val local = scheduleRepository(original)
        val start = ZonedMoment(Instant.parse("2026-08-10T08:00:00Z"), "UTC")
        val due = ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), "UTC")

        local.execute(DomainCommand.SetTaskSchedule(original.id, start, due, null))

        val updated = local.currentWorkspace().tasks.single { it.id == original.id }
        assertEquals(original.recurrence, updated.recurrence)
        assertEquals(original.recurrenceSeriesId, updated.recurrenceSeriesId)
        assertEquals(original.recurrenceAnchor, updated.recurrenceAnchor)
        assertEquals(original.recurrenceOccurrenceIndex, updated.recurrenceOccurrenceIndex)
    }

    @Test
    fun setTaskScheduleRejectsMismatchedReminderIdentityWithoutMutation() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted && it.recurrence == null }
            .copy(due = ZonedMoment(Instant.parse("2026-08-04T10:00:00Z"), "UTC"))
        val journal = InMemoryBackupJournal()
        val local = scheduleRepository(task, journal = journal)
        val mismatched = Reminder(
            id = "wrong-reminder",
            taskId = TaskId("wrong-task"),
            triggerAt = ZonedMoment(Instant.parse("2026-08-04T09:00:00Z"), "UTC"),
            precise = false,
        )

        val result = executeScheduleWithoutMutation(
            local,
            journal,
            DomainCommand.SetTaskSchedule(task.id, task.start, task.due, mismatched),
        )

        assertEquals(
            CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "That reminder does not belong to this task.",
            ),
            result,
        )
    }

    @Test
    fun setTaskScheduleRejectsReminderWithoutDue() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted && it.recurrence == null }
        val journal = InMemoryBackupJournal()
        val local = scheduleRepository(task, journal = journal)
        val reminder = Reminder(
            Reminder.primaryId(task.id),
            task.id,
            ZonedMoment(Instant.parse("2026-08-04T09:00:00Z"), "UTC"),
            precise = false,
        )

        val result = executeScheduleWithoutMutation(
            local,
            journal,
            DomainCommand.SetTaskSchedule(task.id, task.start, due = null, reminder),
        )

        assertEquals(
            CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Add a due date before setting a reminder.",
            ),
            result,
        )
    }

    @Test
    fun setTaskScheduleRejectsRecurringTrayTarget() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted }
            .copy(
                due = ZonedMoment(Instant.parse("2026-08-04T10:00:00Z"), "UTC"),
                recurrence = RecurrenceRule(RecurrenceFrequency.DAILY),
            )
        val journal = InMemoryBackupJournal()
        val local = scheduleRepository(task, journal = journal)

        val result = executeScheduleWithoutMutation(
            local,
            journal,
            DomainCommand.SetTaskSchedule(task.id, start = null, due = null, reminder = null),
        )

        assertEquals(
            CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "A repeating task needs a start or due time.",
            ),
            result,
        )
    }

    @Test
    fun setTaskScheduleRejectsCountAndEndDateWithoutMutation() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted }
            .copy(
                due = ZonedMoment(Instant.parse("2026-08-04T10:00:00Z"), "UTC"),
                recurrence = RecurrenceRule(
                    RecurrenceFrequency.DAILY,
                    count = 4,
                    endDate = LocalDate.of(2026, 8, 20),
                ),
            )
        val journal = InMemoryBackupJournal()
        val local = scheduleRepository(task, journal = journal)
        val due = ZonedMoment(Instant.parse("2026-08-05T10:00:00Z"), "UTC")

        val result = executeScheduleWithoutMutation(
            local,
            journal,
            DomainCommand.SetTaskSchedule(task.id, task.start, due, null),
        )

        assertEquals(
            CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Choose either an occurrence count or an end date.",
            ),
            result,
        )
    }

    @Test
    fun setTaskScheduleRejectsEndBeforeSchedule() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted }
            .copy(
                due = ZonedMoment(Instant.parse("2026-08-04T10:00:00Z"), "UTC"),
                recurrence = RecurrenceRule(
                    RecurrenceFrequency.DAILY,
                    endDate = LocalDate.of(2026, 8, 10),
                ),
            )
        val journal = InMemoryBackupJournal()
        val local = scheduleRepository(task, journal = journal)
        val due = ZonedMoment(Instant.parse("2026-08-10T18:00:00Z"), "Asia/Bangkok")

        val result = executeScheduleWithoutMutation(
            local,
            journal,
            DomainCommand.SetTaskSchedule(task.id, task.start, due, null),
        )

        assertEquals(
            CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "The repeat end date cannot be before the schedule.",
            ),
            result,
        )
    }

    @Test
    fun setTaskScheduleRejectsDueBeforeStart() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted && it.recurrence == null }
        val journal = InMemoryBackupJournal()
        val local = scheduleRepository(task, journal = journal)
        val start = ZonedMoment(Instant.parse("2026-08-05T10:00:00Z"), "UTC")
        val due = ZonedMoment(Instant.parse("2026-08-05T09:59:59Z"), "UTC")

        val result = executeScheduleWithoutMutation(
            local,
            journal,
            DomainCommand.SetTaskSchedule(task.id, start, due, null),
        )

        assertEquals(
            CommandResult.Rejected(
                RejectionReason.INVALID_STATE,
                "Due time cannot be before start time.",
            ),
            result,
        )
    }

    @Test
    fun setTaskScheduleRejectsPastReminderWithoutPartialWrite() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted && it.recurrence == null }
        val journal = InMemoryBackupJournal()
        val local = scheduleRepository(task, journal = journal)
        val start = ZonedMoment(Instant.parse("2026-08-05T08:00:00Z"), "UTC")
        val due = ZonedMoment(Instant.parse("2026-08-05T10:00:00Z"), "UTC")
        val reminder = Reminder(
            Reminder.primaryId(task.id),
            task.id,
            ZonedMoment(Instant.parse("2026-07-26T10:00:00Z"), "UTC"),
            precise = true,
        )

        val result = executeScheduleWithoutMutation(
            local,
            journal,
            DomainCommand.SetTaskSchedule(task.id, start, due, reminder),
        )

        assertEquals(
            CommandResult.Rejected(
                RejectionReason.REMINDER_IN_PAST,
                "Choose a reminder time in the future.",
            ),
            result,
        )
    }

    @Test
    fun setTaskScheduleRejectsCompletedAndBinnedWithoutMutation() = runBlocking {
        val completed = OpenTasksFixtures.tasks.first { it.isCompleted }
        val binned = OpenTasksFixtures.tasks.first { !it.isCompleted }.copy(
            deletedAt = Instant.parse("2026-07-25T10:00:00Z"),
        )
        listOf(completed, binned).forEach { task ->
            val journal = InMemoryBackupJournal()
            val local = scheduleRepository(task, journal = journal)
            val result = executeScheduleWithoutMutation(
                local,
                journal,
                DomainCommand.SetTaskSchedule(task.id, task.start, task.due, null),
            )
            assertEquals(
                CommandResult.Rejected(
                    RejectionReason.INVALID_STATE,
                    "Only open tasks can be rescheduled.",
                ),
                result,
            )
        }
    }

    @Test
    fun setTaskScheduleUndoRestoresPastReminderMetadata() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted && it.recurrence == null }
            .copy(
                start = ZonedMoment(Instant.parse("2026-07-25T08:00:00Z"), "UTC"),
                due = ZonedMoment(Instant.parse("2026-07-25T10:00:00Z"), "UTC"),
            )
        val pastReminder = Reminder(
            Reminder.primaryId(task.id),
            task.id,
            ZonedMoment(Instant.parse("2026-07-25T09:13:00Z"), "Asia/Bangkok"),
            precise = true,
        )
        val local = scheduleRepository(task, pastReminder)
        val start = ZonedMoment(Instant.parse("2026-08-05T08:00:00Z"), "UTC")
        val due = ZonedMoment(Instant.parse("2026-08-05T10:00:00Z"), "UTC")

        val changed = local.execute(
            DomainCommand.SetTaskSchedule(task.id, start, due, reminder = null),
        ) as CommandResult.Success
        val undo = checkNotNull(changed.undo)
        assertEquals(
            DomainCommand.SetTaskSchedule(
                task.id,
                task.start,
                task.due,
                pastReminder,
                restorePastReminder = true,
            ),
            undo,
        )

        local.execute(undo)

        val restored = local.currentWorkspace()
        val restoredTask = restored.tasks.single { it.id == task.id }
        assertEquals(task.start, restoredTask.start)
        assertEquals(task.due, restoredTask.due)
        assertEquals(pastReminder, restored.reminders.single { it.taskId == task.id })
    }

    @Test
    fun setTaskSchedulePastReminderNoOpDoesNotValidateAdvanceOrJournal() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted && it.recurrence == null }
            .copy(due = ZonedMoment(Instant.parse("2026-07-25T10:00:00Z"), "UTC"))
        val pastReminder = Reminder(
            Reminder.primaryId(task.id),
            task.id,
            ZonedMoment(Instant.parse("2026-07-25T09:00:00Z"), "UTC"),
            precise = true,
        )
        val journal = InMemoryBackupJournal()
        val local = scheduleRepository(task, pastReminder, journal)

        val result = executeScheduleWithoutMutation(
            local,
            journal,
            DomainCommand.SetTaskSchedule(task.id, task.start, task.due, pastReminder),
        ) as CommandResult.Success

        assertNull(result.undo)
    }

    @Test
    fun createTaskPersistsProvidedDue() = runBlocking {
        val due = ZonedMoment(
            instant = Instant.parse("2026-08-10T09:00:00Z"),
            zoneId = "Asia/Bangkok",
        )

        assertTrue(
            repository.execute(
                DomainCommand.CreateTask("Due task", due = due),
            ) is CommandResult.Success,
        )

        val created = repository.observeWorkspace().value.tasks.first { it.title == "Due task" }
        assertEquals(due, created.due)
    }

    @Test
    fun createTaskRejectsTheWholeCommandBeforeMutation() = runBlocking {
        withTimeout(5_000) {
            val due = ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), "UTC")
            invalidCreates(due).forEach { (command, reason) ->
                val before = repository.currentWorkspace()
                val journalSize = journal.entries.size
                val result = repository.execute(command) as CommandResult.Rejected
                assertEquals(reason, result.reason)
                assertEquals(before, repository.currentWorkspace())
                assertEquals(journalSize, journal.entries.size)
            }
        }
    }

    @Test
    fun createTaskCreatesEnrichedTaskAtomically() = runBlocking {
        withTimeout(5_000) {
            val fixedNow = Instant.parse("2026-07-26T10:00:00Z")
            assertTrue(
                repository.execute(
                    DomainCommand.CreateTask(
                        "Deduped tags",
                        tagNames = List(51) { " deep WORK " },
                    ),
                ) is CommandResult.Success,
            )

            // This is 2026-08-09 in America/Los_Angeles but 2026-08-10 in UTC.
            val due = ZonedMoment(
                Instant.parse("2026-08-10T00:30:00Z"),
                "America/Los_Angeles",
            )
            val rule = RecurrenceRule(
                RecurrenceFrequency.WEEKLY,
                endDate = LocalDate.of(2026, 8, 9),
            )
            val command = DomainCommand.CreateTask(
                title = "Enriched",
                tagNames = listOf("deep WORK", "New tag", "NEW TAG"),
                estimate = Duration.ofMinutes(45),
                due = due,
                recurrence = rule,
            )
            val before = repository.currentWorkspace()
            val beforeActivityIds = before.activityEntries.mapTo(hashSetOf(), ActivityEntry::id)
            val success = repository.execute(command) as CommandResult.Success
            val after = repository.currentWorkspace()
            val created = after.tasks.single { task -> before.tasks.none { it.id == task.id } }
            assertEquals(before.tags.size + 1, after.tags.size)
            val beforeTagIds = before.tags.mapTo(hashSetOf()) { tag -> tag.id }
            val newTag = after.tags.single { it.id !in beforeTagIds }
            assertEquals("New tag", newTag.name)
            assertEquals(setOf(TagId("tag-deep-work"), newTag.id), created.tagIds)
            assertEquals(Duration.ofMinutes(45), created.estimate)
            assertEquals(rule, created.recurrence)
            assertEquals(created.id, created.recurrenceSeriesId)
            assertEquals(due, created.recurrenceAnchor)
            assertEquals(0, created.recurrenceOccurrenceIndex)
            val newActivities = after.activityEntries.filter { it.id !in beforeActivityIds }
            assertEquals(1, newActivities.size)
            assertEquals(ActivityKind.RECORD_CREATED, newActivities.single().kind)
            assertEquals(created.id, newActivities.single().taskId)
            assertEquals(DomainCommand.DeleteTask(created.id, fixedNow), success.undo)
            repository.execute(checkNotNull(success.undo))
            assertTrue(
                repository.currentWorkspace().tasks.single { it.id == created.id }.deletedAt != null,
            )
        }
    }

    @Test
    fun undoRestoresAnElapsedReminderButUserCommandsCannotCreateOne() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted }
        val elapsedReminder = Reminder(
            id = Reminder.primaryId(task.id),
            taskId = task.id,
            triggerAt = ZonedMoment(
                instant = Instant.parse("2026-07-26T09:00:00Z"),
                zoneId = "UTC",
            ),
            precise = false,
        )
        val localRepository = InMemoryVaultRepository(
            initial = OpenTasksFixtures.snapshot.copy(reminders = listOf(elapsedReminder)),
            now = { Instant.parse("2026-07-26T10:00:00Z") },
        )

        val rejected = localRepository.execute(
            DomainCommand.SetTaskReminder(
                taskId = task.id,
                triggerAt = elapsedReminder.triggerAt,
            ),
        )
        assertEquals(RejectionReason.REMINDER_IN_PAST, (rejected as CommandResult.Rejected).reason)

        val removed = localRepository.execute(
            DomainCommand.SetTaskReminder(taskId = task.id, triggerAt = null),
        ) as CommandResult.Success
        localRepository.execute(checkNotNull(removed.undo))

        assertEquals(
            elapsedReminder,
            localRepository.observeWorkspace().value.reminders.single(),
        )
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
        val reminder = Reminder(
            id = Reminder.primaryId(original.id),
            taskId = original.id,
            triggerAt = due.copy(instant = due.instant.minus(Duration.ofHours(1))),
            precise = true,
        )
        val updated = repository.execute(
            DomainCommand.UpdateTask(
                taskId = original.id,
                title = original.title,
                description = original.description,
                projectId = original.projectId,
                priority = original.priority,
                start = original.start,
                due = due,
                recurrence = rule,
                estimate = original.estimate,
                reminder = reminder,
            ),
        )
        assertTrue(updated is CommandResult.Success)

        val configured = repository.observeWorkspace().value.tasks.first { it.id == original.id }
        assertEquals(original.id, configured.recurrenceSeriesId)
        assertEquals(due, configured.recurrenceAnchor)
        assertEquals(0, configured.recurrenceOccurrenceIndex)
        assertEquals(reminder, repository.observeWorkspace().value.reminders.single())

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
        assertEquals(
            Reminder(
                id = Reminder.primaryId(nextOccurrence.id),
                taskId = nextOccurrence.id,
                triggerAt = checkNotNull(nextOccurrence.due).copy(
                    instant = checkNotNull(nextOccurrence.due).instant.minus(Duration.ofHours(1)),
                ),
                precise = true,
            ),
            afterComplete.reminders.single { it.taskId == nextOccurrence.id },
        )

        repository.execute(checkNotNull(completed.undo))

        val afterUndo = repository.observeWorkspace().value
        assertFalse(afterUndo.tasks.first { it.id == original.id }.isCompleted)
        assertFalse(afterUndo.tasks.any { it.id == nextOccurrence.id })
        assertEquals(reminder, afterUndo.reminders.single { it.taskId == original.id })
        assertFalse(afterUndo.reminders.any { it.taskId == nextOccurrence.id })
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
                start = original.start,
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
                start = original.start,
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
                start = occurrence.start,
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
                start = original.start,
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
    fun projectWorkflowLifecyclePreservesSemanticCategoriesAndExactUndo() = runBlocking {
        val project = OpenTasksFixtures.studioProject
        val customId = WorkflowStatusId("status-ready-for-review")

        val created = repository.execute(
            DomainCommand.CreateWorkflowStatus(
                statusId = customId,
                projectId = project.id,
                name = "Ready for review",
                semanticStatus = SemanticStatus.PLANNED,
            ),
        ) as CommandResult.Success
        val renamed = repository.execute(
            DomainCommand.RenameWorkflowStatus(customId, "Review queue"),
        ) as CommandResult.Success
        val beforeMove = repository.observeWorkspace().value.workflowStatuses
            .filter { it.projectId == project.id && it.archivedAt == null }
            .sortedBy { it.rank }
            .map { it.id }
        val moved = repository.execute(
            DomainCommand.MoveWorkflowStatus(customId, WorkflowMoveDirection.EARLIER),
        ) as CommandResult.Success
        val afterMove = repository.observeWorkspace().value.workflowStatuses
            .filter { it.projectId == project.id && it.archivedAt == null }
            .sortedBy { it.rank }
            .map { it.id }
        assertTrue(afterMove.indexOf(customId) < beforeMove.indexOf(customId))

        repository.execute(checkNotNull(moved.undo))
        assertEquals(
            beforeMove,
            repository.observeWorkspace().value.workflowStatuses
                .filter { it.projectId == project.id && it.archivedAt == null }
                .sortedBy { it.rank }
                .map { it.id },
        )

        repository.execute(checkNotNull(renamed.undo))
        assertEquals(
            "Ready for review",
            repository.observeWorkspace().value.workflowStatuses.first { it.id == customId }.name,
        )

        val archived = repository.execute(
            DomainCommand.ArchiveWorkflowStatus(OpenTasksFixtures.planned),
        ) as CommandResult.Success
        val assignedTask = repository.observeWorkspace().value.tasks.first {
            it.statusId == OpenTasksFixtures.planned
        }
        assertEquals(SemanticStatus.PLANNED, assignedTask.semanticStatus)
        assertTrue(
            repository.observeWorkspace().value.workflowStatuses
                .first { it.id == OpenTasksFixtures.planned }
                .archivedAt != null,
        )
        repository.execute(checkNotNull(archived.undo))
        assertEquals(
            null,
            repository.observeWorkspace().value.workflowStatuses
                .first { it.id == OpenTasksFixtures.planned }
                .archivedAt,
        )

        repository.execute(checkNotNull(created.undo))
        assertFalse(
            repository.observeWorkspace().value.workflowStatuses.any { it.id == customId },
        )
    }

    @Test
    fun taskStatusAndProjectMovesStayWithinTheirProjectWorkflow() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { it.projectId == OpenTasksFixtures.studioProject.id }
        val foreignStatus = OpenTasksFixtures.statusId(
            OpenTasksFixtures.taxProject.id,
            SemanticStatus.STARTED,
        )
        val rejected = repository.execute(
            DomainCommand.ChangeTaskStatus(task.id, foreignStatus),
        )
        assertEquals(RejectionReason.NOT_FOUND, (rejected as CommandResult.Rejected).reason)

        val originalStatus = task.statusId
        val moved = repository.execute(
            DomainCommand.UpdateTask(
                taskId = task.id,
                title = task.title,
                description = task.description,
                projectId = OpenTasksFixtures.taxProject.id,
                priority = task.priority,
                start = task.start,
                due = task.due,
                recurrence = task.recurrence,
                estimate = task.estimate,
            ),
        ) as CommandResult.Success
        val movedTask = repository.observeWorkspace().value.tasks.first { it.id == task.id }
        assertEquals(OpenTasksFixtures.taxProject.id, movedTask.projectId)
        assertEquals(
            OpenTasksFixtures.statusId(
                OpenTasksFixtures.taxProject.id,
                task.semanticStatus,
            ),
            movedTask.statusId,
        )

        repository.execute(checkNotNull(moved.undo))
        val restored = repository.observeWorkspace().value.tasks.first { it.id == task.id }
        assertEquals(task.projectId, restored.projectId)
        assertEquals(originalStatus, restored.statusId)
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
    fun permanentlyPurgingAParentDetachesItsSurvivingChild() = runBlocking {
        val parent = OpenTasksFixtures.tasks[2]
        val child = OpenTasksFixtures.tasks[3].copy(parentTaskId = parent.id)
        val initial = OpenTasksFixtures.snapshot.copy(
            tasks = OpenTasksFixtures.tasks.map { if (it.id == child.id) child else it },
        )
        val repository = InMemoryVaultRepository(initial = initial)

        repository.execute(DomainCommand.DeleteTask(parent.id))
        repository.execute(DomainCommand.PermanentlyDeleteTask(parent.id))

        assertEquals(
            null,
            repository.observeWorkspace().value.tasks.single { it.id == child.id }.parentTaskId,
        )
    }

    @Test
    fun expiryPurgingAParentDetachesItsSurvivingChild() = runBlocking {
        val parent = OpenTasksFixtures.tasks[2]
        val child = OpenTasksFixtures.tasks[3].copy(parentTaskId = parent.id)
        val initial = OpenTasksFixtures.snapshot.copy(
            tasks = OpenTasksFixtures.tasks.map { if (it.id == child.id) child else it },
        )
        val repository = InMemoryVaultRepository(initial = initial)
        val deletedAt = Instant.parse("2026-06-01T10:00:00Z")

        repository.execute(DomainCommand.DeleteTask(parent.id, deletedAt))
        repository.execute(DomainCommand.PurgeExpiredTrash(Instant.parse("2026-07-02T10:00:00Z")))

        assertEquals(
            null,
            repository.observeWorkspace().value.tasks.single { it.id == child.id }.parentTaskId,
        )
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
    fun stopTimerIfOwnedStopsTheMatchingTimer() = runBlocking {
        withTimeout(5_000) {
            repository.execute(DomainCommand.StopTimer)
            val task = OpenTasksFixtures.tasks.first()
            repository.execute(
                DomainCommand.StartTimer(task.id, Instant.parse("2026-07-26T09:30:00Z")),
            )

            val stopped = repository.execute(DomainCommand.StopTimerIfOwned(task.id))

            assertTrue(stopped is CommandResult.Success)
            assertEquals(null, repository.observeWorkspace().value.home.activeTimer)
        }
    }

    @Test
    fun stopTimerIfOwnedRejectsAnotherOwnerWithoutMutation() = runBlocking {
        withTimeout(5_000) {
            repository.execute(DomainCommand.StopTimer)
            val owner = OpenTasksFixtures.tasks.first()
            val other = OpenTasksFixtures.tasks.last()
            repository.execute(
                DomainCommand.StartTimer(owner.id, Instant.parse("2026-07-26T09:30:00Z")),
            )
            val activeBefore = repository.observeWorkspace().value.home.activeTimer
            val entriesBefore = repository.observeWorkspace().value.timeEntries
            val journalEntriesBefore = journal.entries.size

            val rejected = repository.execute(DomainCommand.StopTimerIfOwned(other.id))

            assertEquals(
                RejectionReason.TIMER_OWNERSHIP_CHANGED,
                (rejected as CommandResult.Rejected).reason,
            )
            assertEquals(activeBefore, repository.observeWorkspace().value.home.activeTimer)
            assertEquals(entriesBefore, repository.observeWorkspace().value.timeEntries)
            assertEquals(journalEntriesBefore, journal.entries.size)
        }
    }

    @Test
    fun manualTimeEntriesAreReversibleAndExposeOverlaps() = runBlocking {
        repository.execute(DomainCommand.StopTimer)
        val task = OpenTasksFixtures.tasks.first()
        val firstId = TimeEntryId("manual-first")
        val secondId = TimeEntryId("manual-second")
        val first = repository.execute(
            DomainCommand.AddTimeEntry(
                entryId = firstId,
                taskId = task.id,
                startedAt = Instant.parse("2026-07-25T08:00:00Z"),
                stoppedAt = Instant.parse("2026-07-25T09:00:00Z"),
                note = "  Planning  ",
            ),
        ) as CommandResult.Success

        assertEquals(
            "Planning",
            repository.observeWorkspace().value.timeEntries
                .first { it.id == firstId }
                .note,
        )
        assertTrue(repository.observeWorkspace().value.timeEntryConflicts.isEmpty())

        val overlapping = repository.execute(
            DomainCommand.AddTimeEntry(
                entryId = secondId,
                taskId = task.id,
                startedAt = Instant.parse("2026-07-25T08:30:00Z"),
                stoppedAt = Instant.parse("2026-07-25T09:30:00Z"),
            ),
        ) as CommandResult.Success
        assertTrue("overlap" in overlapping.message)
        assertEquals(
            Duration.ofMinutes(30),
            repository.observeWorkspace().value.timeEntryConflicts.single().overlap,
        )

        val updated = repository.execute(
            DomainCommand.UpdateTimeEntry(
                entryId = secondId,
                startedAt = Instant.parse("2026-07-25T09:00:00Z"),
                stoppedAt = Instant.parse("2026-07-25T10:00:00Z"),
                note = "Review",
            ),
        ) as CommandResult.Success
        assertTrue(repository.observeWorkspace().value.timeEntryConflicts.isEmpty())

        repository.execute(checkNotNull(updated.undo))
        assertEquals(
            Instant.parse("2026-07-25T08:30:00Z"),
            repository.observeWorkspace().value.timeEntries
                .first { it.id == secondId }
                .startedAt,
        )

        repository.execute(checkNotNull(first.undo))
        assertFalse(repository.observeWorkspace().value.timeEntries.any { it.id == firstId })
    }

    @Test
    fun manualTimeEntryValidationRejectsInvalidRangesAndLongNotes() = runBlocking {
        val task = OpenTasksFixtures.tasks.first()
        val invalidRange = repository.execute(
            DomainCommand.AddTimeEntry(
                entryId = TimeEntryId("invalid-range"),
                taskId = task.id,
                startedAt = Instant.parse("2026-07-25T09:00:00Z"),
                stoppedAt = Instant.parse("2026-07-25T09:00:00Z"),
            ),
        )
        val longNote = repository.execute(
            DomainCommand.AddTimeEntry(
                entryId = TimeEntryId("long-note"),
                taskId = task.id,
                startedAt = Instant.parse("2026-07-25T09:00:00Z"),
                stoppedAt = Instant.parse("2026-07-25T10:00:00Z"),
                note = "n".repeat(501),
            ),
        )

        assertEquals(
            RejectionReason.INVALID_TIME_ENTRY_RANGE,
            (invalidRange as CommandResult.Rejected).reason,
        )
        assertEquals(
            RejectionReason.TIME_ENTRY_NOTE_TOO_LONG,
            (longNote as CommandResult.Rejected).reason,
        )
    }

    @Test
    fun taskDetailsUpdateTogetherAndUndoRestoresEveryField() = runBlocking {
        val original = OpenTasksFixtures.tasks.first { !it.isCompleted }
        val start = ZonedMoment(
            instant = Instant.parse("2026-08-03T08:00:00Z"),
            zoneId = "Asia/Bangkok",
        )
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
                start = start,
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
        assertEquals(start, updated.start)
        assertEquals(due, updated.due)
        assertEquals(Duration.ofMinutes(45), updated.estimate)
        assertTrue(updated.revision.logicalCounter > original.revision.logicalCounter)

        repository.execute(checkNotNull(result.undo))

        val restored = repository.observeWorkspace().value.tasks.first { it.id == original.id }
        assertEquals(original.title, restored.title)
        assertEquals(original.description, restored.description)
        assertEquals(original.projectId, restored.projectId)
        assertEquals(original.priority, restored.priority)
        assertEquals(original.start, restored.start)
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
                start = original.start,
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

    @Test
    fun milestoneLifecycleAndDeleteUndoPreserveTaskMembership() = runBlocking {
        val project = OpenTasksFixtures.studioProject
        val task = OpenTasksFixtures.tasks.first { it.projectId == project.id && !it.isCompleted }
        val milestoneId = MilestoneId("milestone-editor-test")
        val created = repository.execute(
            DomainCommand.CreateMilestone(
                milestoneId = milestoneId,
                projectId = project.id,
                name = "Beta ready",
                dueDate = LocalDate.of(2026, 8, 10),
            ),
        )
        assertTrue(created is CommandResult.Success)

        repository.execute(
            DomainCommand.UpdateTask(
                taskId = task.id,
                title = task.title,
                description = task.description,
                projectId = task.projectId,
                priority = task.priority,
                start = task.start,
                due = task.due,
                recurrence = task.recurrence,
                estimate = task.estimate,
                milestoneId = milestoneId,
            ),
        )
        val completedAt = Instant.parse("2026-08-10T12:00:00Z")
        val completed = repository.execute(
            DomainCommand.UpdateMilestone(
                milestoneId = milestoneId,
                name = "Beta signed off",
                dueDate = LocalDate.of(2026, 8, 11),
                completedAt = completedAt,
            ),
        ) as CommandResult.Success
        assertEquals(
            completedAt,
            repository.observeWorkspace().value.milestones
                .single { it.id == milestoneId }
                .completedAt,
        )

        repository.execute(checkNotNull(completed.undo))
        assertEquals(
            "Beta ready",
            repository.observeWorkspace().value.milestones.single { it.id == milestoneId }.name,
        )

        val deleted = repository.execute(
            DomainCommand.DeleteMilestone(milestoneId),
        ) as CommandResult.Success
        assertFalse(repository.observeWorkspace().value.milestones.any { it.id == milestoneId })
        assertEquals(
            null,
            repository.observeWorkspace().value.tasks.single { it.id == task.id }.milestoneId,
        )

        repository.execute(checkNotNull(deleted.undo))
        assertTrue(repository.observeWorkspace().value.milestones.any { it.id == milestoneId })
        assertEquals(
            milestoneId,
            repository.observeWorkspace().value.tasks.single { it.id == task.id }.milestoneId,
        )
    }

    @Test
    fun taskMilestonesAreProjectScopedAndProjectMoveUndoRestoresMembership() = runBlocking {
        val task = OpenTasksFixtures.tasks.first {
            it.projectId == OpenTasksFixtures.studioProject.id && !it.isCompleted
        }
        val studioMilestone = OpenTasksFixtures.milestones.first {
            it.projectId == OpenTasksFixtures.studioProject.id
        }
        val foreignMilestone = OpenTasksFixtures.milestones.first {
            it.projectId == OpenTasksFixtures.taxProject.id
        }
        val rejected = repository.execute(
            DomainCommand.UpdateTask(
                taskId = task.id,
                title = task.title,
                description = task.description,
                projectId = task.projectId,
                priority = task.priority,
                start = task.start,
                due = task.due,
                recurrence = task.recurrence,
                estimate = task.estimate,
                milestoneId = foreignMilestone.id,
            ),
        )
        assertEquals(RejectionReason.INVALID_STATE, (rejected as CommandResult.Rejected).reason)

        repository.execute(
            DomainCommand.UpdateTask(
                taskId = task.id,
                title = task.title,
                description = task.description,
                projectId = task.projectId,
                priority = task.priority,
                start = task.start,
                due = task.due,
                recurrence = task.recurrence,
                estimate = task.estimate,
                milestoneId = studioMilestone.id,
            ),
        )
        val assigned = repository.observeWorkspace().value.tasks.single { it.id == task.id }
        val moved = repository.execute(
            DomainCommand.UpdateTask(
                taskId = assigned.id,
                title = assigned.title,
                description = assigned.description,
                projectId = OpenTasksFixtures.taxProject.id,
                priority = assigned.priority,
                start = assigned.start,
                due = assigned.due,
                recurrence = assigned.recurrence,
                estimate = assigned.estimate,
                milestoneId = null,
            ),
        ) as CommandResult.Success
        assertEquals(
            null,
            repository.observeWorkspace().value.tasks.single { it.id == task.id }.milestoneId,
        )

        repository.execute(checkNotNull(moved.undo))
        val restored = repository.observeWorkspace().value.tasks.single { it.id == task.id }
        assertEquals(task.projectId, restored.projectId)
        assertEquals(studioMilestone.id, restored.milestoneId)
    }

    @Test
    fun dependencyLifecycleResolvesOnCompletionAndUndoRestoresTheLink() = runBlocking {
        val candidates = repository.observeWorkspace().value.tasks
            .filter {
                !it.isCompleted && !it.isBlocked &&
                    it.deletedAt == null && it.dependencyIds.isEmpty()
            }
        val task = candidates[0]
        val dependency = candidates[1]

        val added = repository.execute(
            DomainCommand.SetTaskDependency(task.id, dependency.id, present = true),
        ) as CommandResult.Success
        var observed = repository.observeWorkspace().value.tasks.single { it.id == task.id }
        assertEquals(setOf(dependency.id), observed.dependencyIds)
        assertEquals(setOf(dependency.id), observed.blockedBy)

        val completion = repository.execute(
            DomainCommand.CompleteTask(dependency.id),
        ) as CommandResult.Success
        observed = repository.observeWorkspace().value.tasks.single { it.id == task.id }
        assertEquals(setOf(dependency.id), observed.dependencyIds)
        assertTrue(observed.blockedBy.isEmpty())
        assertFalse(observed.isBlocked)

        repository.execute(checkNotNull(completion.undo))
        observed = repository.observeWorkspace().value.tasks.single { it.id == task.id }
        assertEquals(setOf(dependency.id), observed.blockedBy)

        repository.execute(checkNotNull(added.undo))
        observed = repository.observeWorkspace().value.tasks.single { it.id == task.id }
        assertTrue(observed.dependencyIds.isEmpty())
        assertTrue(observed.blockedBy.isEmpty())
    }

    @Test
    fun dependencyEditorRejectsSelfAndTransitiveCycles() = runBlocking {
        val candidates = repository.observeWorkspace().value.tasks
            .filter {
                !it.isCompleted && !it.isBlocked &&
                    it.deletedAt == null && it.dependencyIds.isEmpty()
            }
            .take(3)
        val first = candidates[0]
        val second = candidates[1]
        val third = candidates[2]

        repository.execute(
            DomainCommand.SetTaskDependency(first.id, second.id, present = true),
        )
        repository.execute(
            DomainCommand.SetTaskDependency(second.id, third.id, present = true),
        )
        val transitiveCycle = repository.execute(
            DomainCommand.SetTaskDependency(third.id, first.id, present = true),
        )
        val selfCycle = repository.execute(
            DomainCommand.SetTaskDependency(first.id, first.id, present = true),
        )

        assertEquals(
            RejectionReason.DEPENDENCY_CYCLE,
            (transitiveCycle as CommandResult.Rejected).reason,
        )
        assertEquals(
            RejectionReason.DEPENDENCY_CYCLE,
            (selfCycle as CommandResult.Rejected).reason,
        )
    }

    @Test
    fun dependencyEditorEnforcesPerTaskLimit() = runBlocking {
        val template = OpenTasksFixtures.tasks.first { !it.isCompleted && !it.isBlocked }
        val dependencyTasks = (1..101).map { index ->
            template.copy(
                id = TaskId("dependency-limit-$index"),
                title = "Dependency $index",
                dependencyIds = emptySet(),
                blockedBy = emptySet(),
            )
        }
        val dependencyIds = dependencyTasks.take(100).mapTo(linkedSetOf(), Task::id)
        val target = template.copy(
            id = TaskId("dependency-limit-target"),
            title = "Target",
            dependencyIds = dependencyIds,
            blockedBy = dependencyIds,
        )
        val snapshot = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(target) + dependencyTasks,
        )
        val limitedRepository = InMemoryVaultRepository(
            initial = snapshot,
            now = { Instant.parse("2026-07-26T10:00:00Z") },
        )

        val result = limitedRepository.execute(
            DomainCommand.SetTaskDependency(
                target.id,
                dependencyTasks.last().id,
                present = true,
            ),
        )

        assertEquals(
            RejectionReason.DEPENDENCY_LIMIT_REACHED,
            (result as CommandResult.Rejected).reason,
        )
    }

    @Test
    fun projectTemplateCaptureInstantiationAndDeleteUndoPreserveReusableStructure() = runBlocking {
        val sourceProject = OpenTasksFixtures.snapshot.projects.first()
        val templateId = TemplateId("template-client")
        val capture = repository.execute(
            DomainCommand.CaptureProjectTemplate(
                templateId = templateId,
                projectId = sourceProject.id,
                name = "Client delivery",
            ),
        ) as CommandResult.Success

        val template = repository.observeWorkspace().value.templates.single {
            it.id == templateId
        }
        assertTrue(template.workflowStatuses.isNotEmpty())
        assertTrue(template.tasks.all { task ->
            OpenTasksFixtures.tasks.single { it.id.value == task.key }.let { source ->
                source.deletedAt == null && !source.isCompleted
            }
        })

        val createdProjectId = ProjectId("project-from-template")
        repository.execute(
            DomainCommand.InstantiateProjectTemplate(
                templateId = template.id,
                projectId = createdProjectId,
                projectName = "September delivery",
                anchorDate = LocalDate.of(2026, 9, 1),
            ),
        )
        val snapshot = repository.observeWorkspace().value
        assertEquals(
            snapshot.tasks.map { it.id.value }.sorted(),
            snapshot.tasks.map { it.id.value },
        )
        val createdTasks = snapshot.tasks.filter { it.projectId == createdProjectId }
        assertEquals(template.tasks.size, createdTasks.size)
        assertTrue(createdTasks.none(Task::isCompleted))
        assertEquals(
            template.workflowStatuses.size,
            snapshot.workflowStatuses.count { it.projectId == createdProjectId },
        )
        assertEquals(
            template.milestones.size,
            snapshot.milestones.count { it.projectId == createdProjectId },
        )

        repository.execute(checkNotNull(capture.undo))
        assertTrue(repository.observeWorkspace().value.templates.isEmpty())
        repository.execute(
            DomainCommand.RestoreTemplate(template),
        )
        assertEquals(template.name, repository.observeWorkspace().value.templates.single().name)
    }

    @Test
    fun addRemoveAndReorderMyDayEntries() = runBlocking {
        withTimeout(5_000) {
            val repository = InMemoryVaultRepository()
            val tasks = repository.currentWorkspace().tasks.filter { it.deletedAt == null }.take(3)
            tasks.forEach { task ->
                assertTrue(
                    repository.execute(DomainCommand.AddTaskToMyDay(task.id))
                        is CommandResult.Success,
                )
            }
            assertEquals(
                tasks.map(Task::id),
                repository.currentWorkspace().home.myDayTasks.map(Task::id),
            )

            // Move the last entry to the top.
            val move = repository.execute(
                DomainCommand.MoveMyDayEntry(tasks[2].id, afterTaskId = null),
            )
            assertTrue(move is CommandResult.Success)
            assertEquals(
                listOf(tasks[2].id, tasks[0].id, tasks[1].id),
                repository.currentWorkspace().home.myDayTasks.map(Task::id),
            )

            // Undo restores the previous rank.
            val undo = (move as CommandResult.Success).undo
            assertTrue(undo is DomainCommand.RestoreMyDayEntries)
            repository.execute(requireNotNull(undo))
            assertEquals(
                tasks.map(Task::id),
                repository.currentWorkspace().home.myDayTasks.map(Task::id),
            )

            // Duplicate add is an idempotent success that changes nothing.
            val duplicate = repository.execute(DomainCommand.AddTaskToMyDay(tasks[0].id))
            assertTrue(duplicate is CommandResult.Success)
            assertEquals(3, repository.currentWorkspace().myDay.size)

            // Remove round-trips through its undo.
            val removed = repository.execute(DomainCommand.RemoveTaskFromMyDay(tasks[1].id))
            assertTrue(removed is CommandResult.Success)
            assertEquals(2, repository.currentWorkspace().myDay.size)
            repository.execute(requireNotNull((removed as CommandResult.Success).undo))
            assertEquals(3, repository.currentWorkspace().myDay.size)
        }
    }

    @Test
    fun myDayBoundBinFilterAndSweep() = runBlocking {
        withTimeout(5_000) {
            val repository = InMemoryVaultRepository()
            val snapshot = repository.currentWorkspace()
            val open = snapshot.tasks.first { it.deletedAt == null && !it.isCompleted }

            repository.execute(DomainCommand.AddTaskToMyDay(open.id))

            // Binned member: row retained, projection hides it.
            repository.execute(DomainCommand.DeleteTask(open.id))
            assertEquals(1, repository.currentWorkspace().myDay.size)
            assertTrue(repository.currentWorkspace().home.myDayTasks.isEmpty())
            repository.execute(DomainCommand.RestoreTask(open.id))

            // Completed member stays visible (dimmed by the UI) until swept.
            repository.execute(DomainCommand.CompleteTask(open.id))
            assertEquals(
                listOf(open.id),
                repository.currentWorkspace().home.myDayTasks.map(Task::id),
            )
            val sweep = repository.execute(
                DomainCommand.SweepMyDay(before = Instant.parse("2100-01-01T00:00:00Z")),
            )
            assertTrue(sweep is CommandResult.Success)
            assertTrue(repository.currentWorkspace().myDay.isEmpty())

            // Sweeping again is a journal-free no-op.
            val again = repository.execute(
                DomainCommand.SweepMyDay(before = Instant.parse("2100-01-01T00:00:00Z")),
            )
            assertTrue(again is CommandResult.Success)
        }
    }

    @Test
    fun addToMyDayRejectsBinnedTasksAndTheBound() = runBlocking {
        withTimeout(5_000) {
            val repository = InMemoryVaultRepository()
            repeat(200) { index ->
                assertTrue(
                    repository.execute(DomainCommand.CreateTask(title = "seed-$index"))
                        is CommandResult.Success,
                )
                val seedId = repository.currentWorkspace()
                    .tasks.single { it.title == "seed-$index" }.id
                assertTrue(
                    repository.execute(DomainCommand.AddTaskToMyDay(seedId))
                        is CommandResult.Success,
                )
            }

            // The 200th sequential append exhausts the naive ponytail rank
            // (one character longer per append): it must have re-ranked the
            // whole list instead of writing a rank the journal codec
            // refuses, so every stored rank is an index rank again.
            assertEquals(
                List(200) { index -> myDayRankForIndex(index) },
                repository.currentWorkspace().myDay.map(MyDayEntry::rank),
            )
            assertTrue(
                repository.currentWorkspace().myDay.all {
                    it.rank.length <= MAX_MY_DAY_RANK_LENGTH
                },
            )

            // The 201st add hits the entry bound.
            assertTrue(
                repository.execute(DomainCommand.CreateTask(title = "one-too-many"))
                    is CommandResult.Success,
            )
            val overflowId = repository.currentWorkspace()
                .tasks.single { it.title == "one-too-many" }.id
            val overflow = repository.execute(DomainCommand.AddTaskToMyDay(overflowId))
            assertTrue(overflow is CommandResult.Rejected)
            assertEquals(
                RejectionReason.MY_DAY_LIMIT_REACHED,
                (overflow as CommandResult.Rejected).reason,
            )

            // Binned tasks cannot be planned onto My Day.
            val binnedId = repository.currentWorkspace().tasks.single { it.title == "seed-0" }.id
            assertTrue(
                repository.execute(DomainCommand.DeleteTask(binnedId)) is CommandResult.Success,
            )
            val binned = repository.execute(DomainCommand.AddTaskToMyDay(binnedId))
            assertTrue(binned is CommandResult.Rejected)
            assertEquals(RejectionReason.INVALID_STATE, (binned as CommandResult.Rejected).reason)
        }
    }

    private fun duplicationSnapshot(): WorkspaceSnapshot {
        val seed = OpenTasksFixtures.snapshot
        val project = OpenTasksFixtures.studioProject
        val sourceTemplate = OpenTasksFixtures.tasks.first { it.checklist.isNotEmpty() }
        val revision = sourceTemplate.revision
        val archivedAt = Instant.parse("2026-08-01T00:00:00Z")
        val relationAt = Instant.parse("2026-08-09T09:00:00Z")
        val anchor = ZonedMoment(Instant.parse("2026-08-12T09:00:00Z"), "UTC")
        val archivedOpen = WorkflowStatus(
            WorkflowStatusId("duplicate-status-archived-open"),
            project.id,
            "Archived in progress",
            SemanticStatus.STARTED,
            "m0",
            archivedAt,
        )
        val archivedBacklog = WorkflowStatus(
            WorkflowStatusId("duplicate-status-backlog-archived"),
            project.id,
            "Old backlog",
            SemanticStatus.BACKLOG,
            "a0",
            archivedAt,
        )
        val firstActiveBacklog = WorkflowStatus(
            WorkflowStatusId("duplicate-status-backlog-first"),
            project.id,
            "Backlog first",
            SemanticStatus.BACKLOG,
            "b0",
        )
        val laterActiveBacklog = WorkflowStatus(
            WorkflowStatusId("duplicate-status-backlog-later"),
            project.id,
            "Backlog later",
            SemanticStatus.BACKLOG,
            "z0",
        )
        val completedStatus = WorkflowStatus(
            WorkflowStatusId("duplicate-status-completed"),
            project.id,
            "Done",
            SemanticStatus.COMPLETED,
            "zz",
        )
        val activeDependency = sourceTemplate.copy(
            id = TaskId("duplicate-dependency-active"),
            statusId = firstActiveBacklog.id,
            semanticStatus = SemanticStatus.BACKLOG,
            title = "Active dependency",
            checklist = emptyList(),
            tagIds = emptySet(),
            dependencyIds = emptySet(),
            blockedBy = emptySet(),
            completedAt = null,
            deletedAt = null,
        )
        val completedDependency = activeDependency.copy(
            id = TaskId("duplicate-dependency-completed"),
            statusId = completedStatus.id,
            semanticStatus = SemanticStatus.COMPLETED,
            title = "Completed dependency",
            completedAt = relationAt,
        )
        val deletedDependency = activeDependency.copy(
            id = TaskId("duplicate-dependency-deleted"),
            title = "Deleted dependency",
            deletedAt = relationAt,
        )
        val source = sourceTemplate.copy(
            id = TaskId("duplicate-source"),
            projectId = project.id,
            parentTaskId = TaskId("duplicate-parent"),
            statusId = archivedOpen.id,
            semanticStatus = SemanticStatus.STARTED,
            title = "Duplicate source",
            start = anchor,
            due = anchor,
            recurrence = RecurrenceRule(RecurrenceFrequency.WEEKLY),
            recurrenceSeriesId = TaskId("duplicate-series"),
            recurrenceAnchor = anchor,
            recurrenceOccurrenceIndex = 4,
            milestoneId = OpenTasksFixtures.milestones.first {
                it.projectId == project.id
            }.id,
            dependencyIds = linkedSetOf(
                activeDependency.id,
                completedDependency.id,
                deletedDependency.id,
            ),
            blockedBy = linkedSetOf(
                activeDependency.id,
                completedDependency.id,
                deletedDependency.id,
            ),
            completedAt = null,
            deletedAt = null,
        )
        val incoming = activeDependency.copy(
            id = TaskId("duplicate-incoming"),
            title = "Incoming dependency owner",
            dependencyIds = setOf(source.id),
            blockedBy = setOf(source.id),
        )
        val completedSource = source.copy(
            id = TaskId("duplicate-source-completed"),
            statusId = completedStatus.id,
            semanticStatus = SemanticStatus.COMPLETED,
            title = "Completed source",
            checklist = emptyList(),
            tagIds = emptySet(),
            dependencyIds = emptySet(),
            blockedBy = emptySet(),
            completedAt = relationAt,
        )
        val deletedSource = source.copy(
            id = TaskId("duplicate-source-deleted"),
            title = "Deleted source",
            checklist = emptyList(),
            tagIds = emptySet(),
            dependencyIds = emptySet(),
            blockedBy = emptySet(),
            deletedAt = relationAt,
        )
        val sourceWithoutRequiredStatus = source.copy(
            id = TaskId("duplicate-source-missing-status"),
            statusId = WorkflowStatusId("duplicate-status-missing"),
            title = "Missing-status source",
            checklist = emptyList(),
            tagIds = emptySet(),
            dependencyIds = emptySet(),
            blockedBy = emptySet(),
        )
        val tasks = listOf(
            source,
            activeDependency,
            completedDependency,
            deletedDependency,
            incoming,
            completedSource,
            deletedSource,
            sourceWithoutRequiredStatus,
        )
        return seed.copy(
            home = seed.home.copy(
                focusTasks = emptyList(),
                upcomingTasks = emptyList(),
                projects = listOf(project),
                activeTimer = null,
            ),
            tasks = tasks,
            projects = listOf(project),
            workflowStatuses = listOf(
                archivedOpen,
                archivedBacklog,
                firstActiveBacklog,
                laterActiveBacklog,
                completedStatus,
            ),
            milestones = seed.milestones.filter { it.projectId == project.id },
            tags = seed.tags.filter { it.id in source.tagIds },
            reminders = listOf(
                Reminder(
                    Reminder.primaryId(source.id),
                    source.id,
                    ZonedMoment(Instant.parse("2026-08-11T09:00:00Z"), "UTC"),
                    precise = true,
                ),
            ),
            templates = emptyList(),
            timeEntries = listOf(
                TimeEntry(
                    TimeEntryId("duplicate-source-time"),
                    source.id,
                    DeviceId("duplicate-fixture-device"),
                    relationAt.minusSeconds(600),
                    relationAt,
                    "Source time",
                ),
            ),
            timeEntryConflicts = emptyList(),
            notes = listOf(
                Note(
                    NoteId("duplicate-source-note"),
                    source.id,
                    null,
                    "Source note",
                    relationAt,
                    null,
                    revision,
                ),
            ),
            attachments = listOf(
                Attachment(
                    AttachmentId("duplicate-source-attachment"),
                    source.id,
                    "source.txt",
                    "text/plain",
                    6,
                    "a".repeat(64),
                    BlobSetId("duplicate-source-blob"),
                    1,
                    null,
                    revision,
                ),
            ),
            activityEntries = listOf(
                ActivityEntry(
                    "duplicate-source-prior-activity",
                    source.id,
                    source.projectId,
                    ActivityKind.STATUS_CHANGED,
                    "Prior source activity",
                    relationAt,
                ),
            ),
            retiredBlobSets = emptyList(),
            savedViews = emptyList(),
        )
    }

    private fun assertDuplicateIsolation(
        before: WorkspaceSnapshot,
        after: WorkspaceSnapshot,
        source: Task,
        duplicate: Task,
    ) {
        assertEquals(source, after.tasks.single { it.id == source.id })
        assertEquals(
            before.reminders.filter { it.taskId == source.id },
            after.reminders.filter { it.taskId == source.id },
        )
        assertEquals(
            before.notes.filter { it.taskId == source.id },
            after.notes.filter { it.taskId == source.id },
        )
        assertEquals(
            before.activityEntries.filter { it.taskId == source.id },
            after.activityEntries.filter { it.taskId == source.id },
        )
        assertEquals(
            before.timeEntries.filter { it.taskId == source.id },
            after.timeEntries.filter { it.taskId == source.id },
        )
        assertEquals(
            before.attachments.filter { it.taskId == source.id },
            after.attachments.filter { it.taskId == source.id },
        )
        assertTrue(after.tasks.filter { it.id != duplicate.id }.none {
            duplicate.id in it.dependencyIds
        })
        assertFalse(after.reminders.any { it.taskId == duplicate.id })
        assertFalse(after.notes.any { it.taskId == duplicate.id })
        assertFalse(after.timeEntries.any { it.taskId == duplicate.id })
        assertFalse(after.attachments.any { it.taskId == duplicate.id })
        assertEquals(
            listOf(ActivityKind.RECORD_CREATED),
            after.activityEntries.filter { it.taskId == duplicate.id }.map { it.kind },
        )
    }

    private fun scheduleRepository(
        task: Task,
        reminder: Reminder? = null,
        journal: InMemoryBackupJournal = InMemoryBackupJournal(),
    ): InMemoryVaultRepository = InMemoryVaultRepository(
        initial = OpenTasksFixtures.snapshot.copy(
            tasks = OpenTasksFixtures.snapshot.tasks.map {
                if (it.id == task.id) task else it
            },
            reminders = OpenTasksFixtures.snapshot.reminders
                .filterNot { it.taskId == task.id } + listOfNotNull(reminder),
        ),
        now = { Instant.parse("2026-07-26T10:00:00Z") },
        backupJournal = journal,
    )

    private suspend fun executeScheduleWithoutMutation(
        repository: InMemoryVaultRepository,
        journal: InMemoryBackupJournal,
        command: DomainCommand.SetTaskSchedule,
    ): CommandResult {
        val before = repository.currentWorkspace()
        val revisionBefore = before.tasks.single { it.id == command.taskId }.revision
        val generationBefore = journal.currentGeneration
        val journalBefore = journal.entries
        val activityBefore = before.activityEntries

        val result = repository.execute(command)

        val after = repository.currentWorkspace()
        assertEquals(before, after)
        assertEquals(revisionBefore, after.tasks.single { it.id == command.taskId }.revision)
        assertEquals(generationBefore, journal.currentGeneration)
        assertEquals(journalBefore, journal.entries)
        assertEquals(activityBefore, after.activityEntries)
        return result
    }

    private fun ZonedMoment.localDateString(): String =
        instant.atZone(java.time.ZoneId.of(zoneId)).toLocalDate().toString()
}
