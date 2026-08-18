package app.opentasks.core.data

import app.opentasks.core.data.backup.InMemoryBackupJournal
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.model.AutomationRule
import app.opentasks.core.model.AutomationRuleId
import app.opentasks.core.model.AutomationRuleType
import app.opentasks.core.model.MyDayEntry
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.ZonedMoment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationEvaluationTest {
    private val now = Instant.parse("2026-07-26T10:00:00Z")

    private fun addTagRule(
        id: String,
        statusId: WorkflowStatusId,
        tagId: TagId,
    ) = AutomationRule(
        id = AutomationRuleId(id),
        workspaceId = OpenTasksFixtures.workspaceId,
        type = AutomationRuleType.ON_ENTER_ADD_TAG,
        enabled = true,
        statusId = statusId,
        tagId = tagId,
    )

    private suspend fun taskById(
        repository: InMemoryVaultRepository,
        id: TaskId,
    ): Task = repository.currentWorkspace().tasks.first { it.id == id }

    @Test
    fun ruleFiresInTheTriggersGenerationAndOneUndoRevertsEverything() = runBlocking {
        withTimeout(5_000) {
            val journal = InMemoryBackupJournal()
            val repository = InMemoryVaultRepository(now = { now }, backupJournal = journal)
            val snapshot = repository.currentWorkspace()
            val task = snapshot.tasks.first {
                it.deletedAt == null && !it.isCompleted && !it.isBlocked
            }
            val destination = snapshot.workflowStatuses.first {
                it.projectId == task.projectId && it.id != task.statusId &&
                    it.archivedAt == null && it.semanticStatus != SemanticStatus.COMPLETED
            }
            val tag = snapshot.tags.first { it.id !in task.tagIds }
            repository.execute(
                DomainCommand.CreateAutomationRule(
                    addTagRule("rule-tag", destination.id, tag.id),
                ),
            )
            val generationBefore = journal.currentGeneration

            val moved = repository.execute(
                DomainCommand.ChangeTaskStatus(task.id, destination.id),
            )

            assertTrue(moved is CommandResult.Success)
            assertTrue(tag.id in taskById(repository, task.id).tagIds)
            // Trigger and rule output share ONE journal generation.
            assertEquals(generationBefore + 1, journal.currentGeneration)

            // One undo reverts the move AND the rule's tag.
            val undo = requireNotNull((moved as CommandResult.Success).undo)
            assertTrue(undo is DomainCommand.UndoBatch)
            assertTrue(repository.execute(undo) is CommandResult.Success)
            val reverted = taskById(repository, task.id)
            assertEquals(task.statusId, reverted.statusId)
            assertFalse(tag.id in reverted.tagIds)
            // And the undo replay did NOT re-fire the rule.
            assertFalse(tag.id in taskById(repository, task.id).tagIds)
        }
    }

    @Test
    fun completionAndBulkCompletionFireRulesOnTheCompletedColumn() = runBlocking {
        withTimeout(5_000) {
            val journal = InMemoryBackupJournal()
            val repository = InMemoryVaultRepository(now = { now }, backupJournal = journal)
            val snapshot = repository.currentWorkspace()
            val single = snapshot.tasks.first {
                it.deletedAt == null && !it.isCompleted && !it.isBlocked
            }
            val bulk = snapshot.tasks.first {
                it.deletedAt == null && !it.isCompleted && !it.isBlocked &&
                    it.id != single.id && it.projectId == single.projectId
            }
            val completed = snapshot.workflowStatuses.first {
                it.projectId == single.projectId &&
                    it.semanticStatus == SemanticStatus.COMPLETED &&
                    it.archivedAt == null
            }
            val tag = snapshot.tags.first {
                it.id !in single.tagIds && it.id !in bulk.tagIds
            }
            repository.execute(
                DomainCommand.CreateAutomationRule(
                    addTagRule("rule-done", completed.id, tag.id),
                ),
            )

            // The completed column fires on the single-task path…
            val generationBeforeSingle = journal.currentGeneration
            val completedSingle = repository.execute(DomainCommand.CompleteTask(single.id))
            assertTrue(completedSingle is CommandResult.Success)
            assertTrue(tag.id in taskById(repository, single.id).tagIds)
            assertEquals(generationBeforeSingle + 1, journal.currentGeneration)

            // …and on the bulk path, whose stored inverses stay replayable.
            val generationBeforeBulk = journal.currentGeneration
            val completedBulk = repository.execute(
                DomainCommand.CompleteTasks(listOf(bulk.id)),
            )
            assertTrue(completedBulk is CommandResult.Success)
            assertTrue(tag.id in taskById(repository, bulk.id).tagIds)
            assertEquals(generationBeforeBulk + 1, journal.currentGeneration)

            val undo = requireNotNull((completedBulk as CommandResult.Success).undo)
            assertTrue(repository.execute(undo) is CommandResult.Success)
            val reverted = taskById(repository, bulk.id)
            assertEquals(bulk.statusId, reverted.statusId)
            assertFalse(tag.id in reverted.tagIds)
        }
    }

    @Test
    fun projectMoveRemapAndRecurrenceSpawnNeverFireRules() = runBlocking {
        withTimeout(5_000) {
            val repository = InMemoryVaultRepository(now = { now })
            val snapshot = repository.currentWorkspace()
            val tag = snapshot.tags.first()

            // A rule on every backlog and planned column a remap or a spawn
            // could land in.
            snapshot.workflowStatuses
                .filter {
                    it.semanticStatus == SemanticStatus.BACKLOG ||
                        it.semanticStatus == SemanticStatus.PLANNED
                }
                .forEachIndexed { index, status ->
                    repository.execute(
                        DomainCommand.CreateAutomationRule(
                            addTagRule("rule-open-$index", status.id, tag.id),
                        ),
                    )
                }

            // A project move remaps the task onto the destination's status of
            // the same semantic category; that is not a status entry.
            val moving = snapshot.tasks.first {
                it.deletedAt == null && !it.isCompleted &&
                    it.semanticStatus == SemanticStatus.PLANNED
            }
            val destinationProject = snapshot.projects.first { it.id != moving.projectId }
            val moved = repository.execute(
                DomainCommand.MoveTasksToProject(listOf(moving.id), destinationProject.id),
            )
            assertTrue(moved is CommandResult.Success)
            val remapped = taskById(repository, moving.id)
            assertEquals(destinationProject.id, remapped.projectId)
            assertFalse(tag.id in remapped.tagIds)

            // A recurrence spawn lands in Planned without firing rules, while
            // the completed occurrence itself still fires its own column's.
            val created = repository.execute(
                DomainCommand.CreateTask(
                    title = "Weekly review",
                    due = ZonedMoment(
                        Instant.parse("2026-07-28T10:00:00Z"),
                        "Asia/Bangkok",
                    ),
                    recurrence = RecurrenceRule(RecurrenceFrequency.DAILY),
                ),
            )
            assertTrue(created is CommandResult.Success)
            val recurringId = repository.currentWorkspace().tasks
                .first { it.title == "Weekly review" }.id
            // The create landed in Backlog: a rule fires on creation only if
            // creation were a transition, and it is not.
            assertFalse(tag.id in taskById(repository, recurringId).tagIds)

            val done = repository.execute(DomainCommand.CompleteTask(recurringId))
            assertTrue(done is CommandResult.Success)
            val spawn = repository.currentWorkspace().tasks
                .first { it.title == "Weekly review" && it.id != recurringId }
            assertEquals(SemanticStatus.PLANNED, spawn.semanticStatus)
            assertFalse(tag.id in spawn.tagIds)
        }
    }

    @Test
    fun sweepMyDayCommandIsIdempotentAndRuleGated() = runBlocking {
        withTimeout(5_000) {
            val journal = InMemoryBackupJournal()
            val repository = InMemoryVaultRepository(now = { now }, backupJournal = journal)
            val snapshot = repository.currentWorkspace()
            val stale = snapshot.tasks.first { it.isCompleted && it.deletedAt == null }
            val open = snapshot.tasks.first { !it.isCompleted && it.deletedAt == null }
            repository.execute(DomainCommand.AddTaskToMyDay(stale.id))
            repository.execute(DomainCommand.AddTaskToMyDay(open.id))
            assertEquals(2, repository.currentWorkspace().myDay.size)

            val swept = repository.execute(
                DomainCommand.SweepMyDay(before = Instant.parse("2026-07-26T00:00:00Z")),
            )
            assertTrue(swept is CommandResult.Success)
            assertEquals(
                listOf(open.id),
                repository.currentWorkspace().myDay.map(MyDayEntry::taskId),
            )

            // Idempotent: a second sweep changes nothing and journals nothing.
            val generationBefore = journal.currentGeneration
            val again = repository.execute(
                DomainCommand.SweepMyDay(before = Instant.parse("2026-07-26T00:00:00Z")),
            )
            assertTrue(again is CommandResult.Success)
            assertNull((again as CommandResult.Success).undo)
            assertEquals(generationBefore, journal.currentGeneration)
            assertEquals(
                listOf(open.id),
                repository.currentWorkspace().myDay.map(MyDayEntry::taskId),
            )
        }
    }
}
