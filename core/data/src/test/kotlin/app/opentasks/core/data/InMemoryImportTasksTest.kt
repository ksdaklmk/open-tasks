package app.opentasks.core.data

import app.opentasks.core.data.backup.InMemoryBackupJournal
import app.opentasks.core.data.export.CsvParseResult
import app.opentasks.core.data.export.CsvTable
import app.opentasks.core.data.export.WorkspaceCsvWriter
import app.opentasks.core.data.export.parseTasksCsv
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.ImportedTaskRow
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkflowStatus
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryImportTasksTest {
    @Test
    fun emptyImportRejectsWithoutAnyWriteOrJournalEntry() = runBlocking {
        val journal = InMemoryBackupJournal()
        val repository = InMemoryVaultRepository(now = fixedNow, backupJournal = journal)
        val before = repository.currentWorkspace()

        val result = repository.execute(DomainCommand.ImportTasks(emptyList()))
            as CommandResult.Rejected

        assertEquals(RejectionReason.IMPORT_EMPTY, result.reason)
        assertEquals(before, repository.currentWorkspace())
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun importCreatesTasksOneProjectFiveStatusesAndNewTags() = runBlocking {
        withTimeout(10_000) {
            val repository = InMemoryVaultRepository(now = fixedNow)
            val before = repository.currentWorkspace()

            val result = repository.execute(
                DomainCommand.ImportTasks(
                    listOf(
                        row(1, "First", project = "Imported project", tags = listOf("New tag")),
                        row(2, "Second", project = "Imported project", tags = listOf("New tag", "Admin")),
                    ),
                ),
            )

            assertTrue(result is CommandResult.Success)
            val after = repository.currentWorkspace()
            assertEquals(before.tasks.size + 2, after.tasks.size)
            assertEquals(before.projects.size + 1, after.projects.size)
            val project = after.projects.single { it.name == "Imported project" }
            assertEquals(5, after.workflowStatuses.count { it.projectId == project.id })
            assertEquals(before.tags.size + 1, after.tags.size)
            assertTrue(after.tasks.filter { it.title in setOf("First", "Second") }.all { it.projectId == project.id })
        }
    }

    @Test
    fun forwardImportPublishesOneCompleteSnapshot() = runBlocking {
        withTimeout(10_000) {
            val repository = InMemoryVaultRepository(now = fixedNow)
            val observed = mutableListOf<app.opentasks.core.model.WorkspaceSnapshot>()
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                repository.observeWorkspace().drop(1).collect(observed::add)
            }

            val result = repository.execute(
                DomainCommand.ImportTasks(
                    listOf(
                        row(1, "First atomic", project = "Atomic project", tags = listOf("Atomic tag")),
                        row(2, "Second atomic", project = "Atomic project", tags = listOf("Atomic tag")),
                    ),
                ),
            ) as CommandResult.Success
            observer.cancel()

            assertEquals(1, observed.size)
            val final = observed.single()
            val receipt = (result.undo as DomainCommand.RemoveImportedRecords).receipt
            val project = receipt.projects.single()
            assertTrue(final.projects.any { it.id == project.project.id })
            assertEquals(
                project.statuses.toSet(),
                final.workflowStatuses.filter { it.projectId == project.project.id }.toSet(),
            )
            assertEquals(
                receipt.tasks.map { it.taskId }.toSet(),
                final.tasks.filter { it.projectId == project.project.id }.map { it.id }.toSet(),
            )
            assertEquals(
                receipt.tasks.map { it.activityEntryId }.toSet() + project.activityEntryId,
                final.activityEntries
                    .filter { it.projectId == project.project.id }
                    .map { it.id }
                    .toSet(),
            )
            val tag = receipt.tags.single().tag
            assertTrue(final.tags.any { it.id == tag.id })
            assertTrue(
                final.tasks.filter { it.id in receipt.tasks.map { task -> task.taskId } }
                    .all { tag.id in it.tagIds },
            )
        }
    }

    @Test
    fun exportedTaskWithExactStatusImportsIntoFreshProjectDefaults() = runBlocking {
        withTimeout(10_000) {
            val sourceProject = Project(
                id = ProjectId("source-csv-project"),
                workspaceId = OpenTasksFixtures.workspaceId,
                name = "CSV-only project",
                summary = "",
                status = ProjectHealth.ON_TRACK,
                dueDate = null,
                completedTasks = 0,
                totalTasks = 1,
            )
            val sourceStatuses = WorkflowStatus.defaults(sourceProject.id)
            val sourceTask = OpenTasksFixtures.snapshot.tasks.first().copy(
                id = TaskId("source-csv-task"),
                projectId = sourceProject.id,
                statusId = sourceStatuses[2].id,
                semanticStatus = sourceStatuses[2].semanticStatus,
                title = "Exported task",
                tagIds = emptySet(),
                completedAt = null,
                deletedAt = null,
            )
            val source = OpenTasksFixtures.snapshot.copy(
                tasks = listOf(sourceTask),
                projects = listOf(sourceProject),
                workflowStatuses = sourceStatuses,
                tags = emptyList(),
            )
            val csv = StringBuilder().also {
                WorkspaceCsvWriter(ZoneId.of("UTC")).write(CsvTable.TASKS, source, it)
            }.toString().toByteArray()
            val parsed = parseTasksCsv(csv) as CsvParseResult.Parsed
            val repository = InMemoryVaultRepository(now = fixedNow)

            val result = repository.execute(DomainCommand.ImportTasks(parsed.rows))

            assertTrue(result.toString(), result is CommandResult.Success)
            val after = repository.currentWorkspace()
            val importedProject = after.projects.single { it.name == sourceProject.name }
            val importedTask = after.tasks.single { it.title == sourceTask.title }
            val importedStatus = after.workflowStatuses.single { it.id == importedTask.statusId }
            assertEquals(importedProject.id, importedTask.projectId)
            assertEquals(importedProject.id, importedStatus.projectId)
            assertEquals("In progress", importedStatus.name)
        }
    }

    @Test
    fun reimportAlwaysCreatesDuplicateTasks() = runBlocking {
        withTimeout(10_000) {
            val repository = InMemoryVaultRepository(now = fixedNow)
            val command = DomainCommand.ImportTasks(listOf(row(1, "Duplicate", project = "Studio refresh")))

            repository.execute(command)
            repository.execute(command)

            assertEquals(2, repository.currentWorkspace().tasks.count { it.title == "Duplicate" })
        }
    }

    @Test
    fun undoRemovesOnlyReceiptRecordsAndPreservesPreexistingWorkspace() = runBlocking {
        withTimeout(10_000) {
            val repository = InMemoryVaultRepository(now = fixedNow)
            val before = repository.currentWorkspace()
            val imported = repository.execute(
                DomainCommand.ImportTasks(
                    listOf(row(1, "Temporary", project = "Temporary project", tags = listOf("Temporary tag"))),
                ),
            ) as CommandResult.Success

            val undo = repository.execute(requireNotNull(imported.undo))

            assertTrue(undo is CommandResult.Success)
            val after = repository.currentWorkspace()
            assertEquals(before.tasks.toSet(), after.tasks.toSet())
            assertEquals(before.projects.toSet(), after.projects.toSet())
            assertEquals(before.workflowStatuses.toSet(), after.workflowStatuses.toSet())
            assertEquals(before.milestones.toSet(), after.milestones.toSet())
            assertEquals(before.tags.toSet(), after.tags.toSet())
            assertEquals(before.reminders.toSet(), after.reminders.toSet())
            assertEquals(before.templates.toSet(), after.templates.toSet())
            assertEquals(before.timeEntries.toSet(), after.timeEntries.toSet())
            assertEquals(before.notes.toSet(), after.notes.toSet())
            assertEquals(before.attachments.toSet(), after.attachments.toSet())
            assertEquals(before.activityEntries.toSet(), after.activityEntries.toSet())
            assertEquals(before.retiredBlobSets.toSet(), after.retiredBlobSets.toSet())
            assertEquals(before.savedViews.toSet(), after.savedViews.toSet())
        }
    }

    @Test
    fun undoRejectsBeforeMutationWhenProjectedStateIsNotBackupRepresentable() = runBlocking {
        withTimeout(10_000) {
            val sourceRepository = InMemoryVaultRepository(now = fixedNow)
            val imported = sourceRepository.execute(
                DomainCommand.ImportTasks(listOf(row(1, "Imported target"))),
            ) as CommandResult.Success
            val undo = imported.undo as DomainCommand.RemoveImportedRecords
            val importedTask = sourceRepository.currentWorkspace().tasks.single {
                it.id == undo.receipt.tasks.single().taskId
            }
            val invalidTask = importedTask.copy(
                id = TaskId("unrepresentable-task"),
                statusId = WorkflowStatusId("missing-status"),
            )
            val journal = InMemoryBackupJournal()
            val repository = InMemoryVaultRepository(
                initial = sourceRepository.currentWorkspace().copy(
                    tasks = sourceRepository.currentWorkspace().tasks + invalidTask,
                ),
                now = fixedNow,
                backupJournal = journal,
            )
            val before = repository.currentWorkspace()

            val result = repository.execute(undo)

            assertTrue(result.toString(), result is CommandResult.Rejected)
            assertEquals(RejectionReason.IMPORT_UNDO_CONFLICT, (result as CommandResult.Rejected).reason)
            assertEquals(before, repository.currentWorkspace())
            assertTrue(journal.entries.isEmpty())
        }
    }

    @Test
    fun undoPublishesOnlyOneFinalSnapshotWithTaskOwnedRowsRemovedFirst() = runBlocking {
        withTimeout(10_000) {
            val repository = InMemoryVaultRepository(now = fixedNow)
            val imported = repository.execute(
                DomainCommand.ImportTasks(
                    listOf(row(1, "Atomic undo", project = "Atomic project", tags = listOf("Atomic tag"))),
                ),
            ) as CommandResult.Success
            val observed = mutableListOf<app.opentasks.core.model.WorkspaceSnapshot>()
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                repository.observeWorkspace().drop(1).collect(observed::add)
            }

            val result = repository.execute(requireNotNull(imported.undo))
            observer.cancel()

            assertTrue(result is CommandResult.Success)
            assertEquals(1, observed.size)
            val final = observed.single()
            assertTrue(final.tasks.none { it.title == "Atomic undo" })
            assertTrue(final.projects.none { it.name == "Atomic project" })
            assertTrue(final.workflowStatuses.all { status ->
                status.projectId == null || final.projects.any { it.id == status.projectId }
            })
            assertTrue(final.tags.none { it.name == "Atomic tag" })
        }
    }

    @Test
    fun fiveThousandAndOneRowsRejectWithoutAnyWriteOrJournalEntry() = runBlocking {
        withTimeout(10_000) {
            val journal = InMemoryBackupJournal()
            val repository = InMemoryVaultRepository(now = fixedNow, backupJournal = journal)
            val before = repository.currentWorkspace()

            val result = repository.execute(
                DomainCommand.ImportTasks(List(5_001) { row(it + 1, "Task ${it + 1}") }),
            ) as CommandResult.Rejected

            assertEquals(RejectionReason.IMPORT_TOO_LARGE, result.reason)
            assertEquals(before, repository.currentWorkspace())
            assertTrue(journal.entries.isEmpty())
        }
    }

    @Test
    fun projectedBackupPastPlaintextBoundRejectsUntouched() = runBlocking {
        withTimeout(60_000) {
            val journal = InMemoryBackupJournal()
            val repository = InMemoryVaultRepository(now = fixedNow, backupJournal = journal)
            val before = repository.currentWorkspace()
            val description = "x".repeat(20_000)

            val result = repository.execute(
                DomainCommand.ImportTasks(List(3_300) { row(it + 1, "Large ${it + 1}", description = description) }),
            ) as CommandResult.Rejected

            assertEquals(RejectionReason.IMPORT_BACKUP_LIMIT_EXCEEDED, result.reason)
            assertEquals(before, repository.currentWorkspace())
            assertTrue(journal.entries.isEmpty())
        }
    }

    @Test
    fun undoConflictPreservesEverything() = runBlocking {
        withTimeout(10_000) {
            val journal = InMemoryBackupJournal()
            val repository = InMemoryVaultRepository(now = fixedNow, backupJournal = journal)
            val imported = repository.execute(
                DomainCommand.ImportTasks(listOf(row(1, "Keep after conflict"))),
            ) as CommandResult.Success
            val undo = imported.undo as DomainCommand.RemoveImportedRecords
            val importedTaskId = undo
                .receipt.tasks.single().taskId
            repository.execute(DomainCommand.AddChecklistItem(importedTaskId, "New child"))
            val beforeUndo = repository.currentWorkspace()
            val journalCount = journal.entries.size

            val result = repository.execute(undo) as CommandResult.Rejected

            assertEquals(RejectionReason.IMPORT_UNDO_CONFLICT, result.reason)
            assertEquals(beforeUndo, repository.currentWorkspace())
            assertEquals(journalCount, journal.entries.size)
        }
    }

    @Test
    fun caseOnlyProjectAndTagCollisionsRejectBeforeWrite() = runBlocking {
        withTimeout(10_000) {
            listOf(
                row(4, "Project collision", project = "studio REFRESH"),
                row(7, "Tag collision", tags = listOf("deep WORK")),
            ).forEach { sourceRow ->
                val journal = InMemoryBackupJournal()
                val repository = InMemoryVaultRepository(now = fixedNow, backupJournal = journal)
                val before = repository.currentWorkspace()

                val result = repository.execute(
                    DomainCommand.ImportTasks(listOf(sourceRow)),
                ) as CommandResult.Rejected

                assertEquals(RejectionReason.IMPORT_NAME_COLLISION, result.reason)
                assertTrue(result.message.contains("row ${sourceRow.sourceRowNumber}", ignoreCase = true))
                assertEquals(before, repository.currentWorkspace())
                assertTrue(journal.entries.isEmpty())
            }
        }
    }

    @Test
    fun completedStatusWithoutInstantRejectsBeforeWriteWithSourceRow() = runBlocking {
        withTimeout(10_000) {
            val journal = InMemoryBackupJournal()
            val repository = InMemoryVaultRepository(now = fixedNow, backupJournal = journal)
            val before = repository.currentWorkspace()

            val result = repository.execute(
                DomainCommand.ImportTasks(
                    listOf(row(9, "Contradiction", project = "Studio refresh", status = "Done")),
                ),
            ) as CommandResult.Rejected

            assertEquals(RejectionReason.IMPORT_STATUS_CONFLICT, result.reason)
            assertTrue(result.message.contains("row 9", ignoreCase = true))
            assertEquals(before, repository.currentWorkspace())
            assertTrue(journal.entries.isEmpty())
        }
    }

    @Test
    fun semanticHintSelectsTheFirstActiveStatusWithThatMeaning() = runBlocking {
        val base = OpenTasksFixtures.snapshot
        val project = OpenTasksFixtures.studioProject
        val customStarted = base.workflowStatuses.map { status ->
            if (status.projectId == project.id && status.semanticStatus == SemanticStatus.STARTED) {
                status.copy(name = "Doing")
            } else {
                status
            }
        }
        val repository = InMemoryVaultRepository(initial = base.copy(workflowStatuses = customStarted))

        val result = repository.execute(
            DomainCommand.ImportTasks(
                listOf(
                    row(1, "Mapped work", project = project.name).copy(
                        statusName = "In progress",
                        statusSemantic = SemanticStatus.STARTED,
                    ),
                ),
            ),
        )

        assertTrue(result is CommandResult.Success)
        val imported = repository.currentWorkspace().tasks.single { it.title == "Mapped work" }
        assertEquals("Doing", repository.currentWorkspace().workflowStatuses.single {
            it.id == imported.statusId
        }.name)
    }

    @Test
    fun semanticHintRejectsAtomicallyWhenTheCategoryIsUnavailable() = runBlocking {
        val base = OpenTasksFixtures.snapshot
        val project = OpenTasksFixtures.studioProject.copy(
            id = ProjectId("project-without-started"),
            name = "Project without started",
        )
        val withoutStarted = base.workflowStatuses +
            WorkflowStatus.defaults(project.id).filterNot {
                it.semanticStatus == SemanticStatus.STARTED
            }
        val journal = InMemoryBackupJournal()
        val repository = InMemoryVaultRepository(
            initial = base.copy(
                projects = base.projects + project,
                workflowStatuses = withoutStarted,
            ),
            backupJournal = journal,
        )
        val before = repository.currentWorkspace()

        val result = repository.execute(
            DomainCommand.ImportTasks(
                listOf(
                    row(4, "Unavailable state", project = project.name).copy(
                        statusName = "Doing",
                        statusSemantic = SemanticStatus.STARTED,
                    ),
                ),
            ),
        ) as CommandResult.Rejected

        assertEquals(RejectionReason.IMPORT_STATUS_CONFLICT, result.reason)
        assertEquals(before, repository.currentWorkspace())
        assertTrue(journal.entries.isEmpty())
    }

    private fun row(
        sourceRow: Int,
        title: String,
        project: String? = null,
        status: String? = null,
        tags: List<String> = emptyList(),
        description: String = "",
    ) = ImportedTaskRow(
        sourceRowNumber = sourceRow,
        title = title,
        projectName = project,
        statusName = status,
        priority = Priority.NONE,
        start = null,
        due = null,
        completedAt = null,
        estimateMinutes = null,
        tagNames = tags,
        description = description,
    )

    private companion object {
        val fixedNow: () -> Instant = { Instant.parse("2026-08-09T08:00:00Z") }
    }
}
