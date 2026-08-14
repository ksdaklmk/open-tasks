package app.opentasks.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.opentasks.core.data.backup.BackupJournalAppendBoundary
import app.opentasks.core.data.backup.BackupMutationCodec
import app.opentasks.core.data.backup.BackupRecordFamily
import app.opentasks.core.data.backup.BackupStateMutation
import app.opentasks.core.data.backup.RoomBackupStateStore
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.domain.WorkflowMoveDirection
import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.Note
import app.opentasks.core.model.NoteId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.TimeEntry
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.WorkflowStatus
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.ZonedMoment
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomVaultRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var databaseKey: ByteArray
    private var database: VaultDatabase? = null
    private var repository: RoomVaultRepository? = null

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
        val seed = duplicationSnapshot()
        openRepository(now = { fixedNow }, seedSnapshot = seed)
        val before = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                val source = snapshot.tasks.singleOrNull {
                    it.id == TaskId("duplicate-source")
                } ?: return@first false
                source.blockedBy == setOf(TaskId("duplicate-dependency-active")) &&
                    snapshot.reminders.any { it.taskId == source.id } &&
                    snapshot.notes.any { it.taskId == source.id } &&
                    snapshot.activityEntries.any { it.taskId == source.id } &&
                    snapshot.timeEntries.any { it.taskId == source.id } &&
                    snapshot.attachments.any { it.taskId == source.id }
            }
        }
        val source = before.tasks.single { it.id == TaskId("duplicate-source") }
        val incomingBefore = before.tasks.single { it.id == TaskId("duplicate-incoming") }
        val generationBefore =
            database!!.backupStateDao().require("vault-primary").currentGeneration
        val success = repository!!.execute(DomainCommand.DuplicateTask(source.id))
            as CommandResult.Success
        val undo = checkNotNull(success.undo)
        val duplicateId = (undo as DomainCommand.DeleteTask).taskId
        val generationAfter =
            database!!.backupStateDao().require("vault-primary").currentGeneration
        assertEquals(generationBefore + 1, generationAfter)
        val rows = journalRows(generationAfter)
        val mutations = rows.map { BackupMutationCodec.decode(it.payload) }
        assertEquals(rows.indices.toList(), rows.map { it.sequence })
        assertTrue(mutations.all {
            it.mutationKind == BackupMutationKind.UPSERT &&
                it.record != null &&
                it.deletedFamily == null &&
                it.deletedIdentity == null
        })
        assertEquals(
            2 + source.checklist.size + source.tagIds.size + source.dependencyIds.size,
            rows.size,
        )
        val families = mutations.map { checkNotNull(it.record).family }
        val expectedCounts = mapOf(
            BackupRecordFamily.TASK to 1,
            BackupRecordFamily.ACTIVITY_ENTRY to 1,
            BackupRecordFamily.CHECKLIST_ITEM to source.checklist.size,
            BackupRecordFamily.TASK_TAG to source.tagIds.size,
            BackupRecordFamily.TASK_DEPENDENCY to source.dependencyIds.size,
        )
        expectedCounts.forEach { (family, count) ->
            assertEquals(count, families.count { it == family })
        }
        BackupRecordFamily.entries.filterNot(expectedCounts::containsKey).forEach { family ->
            assertEquals(0, families.count { it == family })
        }
        val afterDuplicate = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                snapshot.tasks.any { it.id == duplicateId } &&
                    snapshot.activityEntries.count {
                        it.taskId == duplicateId && it.kind == ActivityKind.RECORD_CREATED
                    } == 1
            }
        }
        assertEquals(
            incomingBefore,
            afterDuplicate.tasks.single { it.id == TaskId("duplicate-incoming") },
        )
        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository(now = { fixedNow }, seedSnapshot = seed)
        val reopened = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                snapshot.tasks.singleOrNull { it.id == duplicateId }?.let { duplicate ->
                    duplicate.dependencyIds == source.dependencyIds &&
                        duplicate.blockedBy ==
                            setOf(TaskId("duplicate-dependency-active")) &&
                        snapshot.activityEntries.count {
                            it.taskId == duplicateId &&
                                it.kind == ActivityKind.RECORD_CREATED
                        } == 1
                } == true
            }
        }
        val duplicate = reopened.tasks.single { it.id == duplicateId }
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
        assertDuplicateIsolation(before, reopened, source, duplicate)
        assertEquals(
            incomingBefore,
            reopened.tasks.single { it.id == TaskId("duplicate-incoming") },
        )

        repository!!.execute(undo)
        val undone = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                snapshot.tasks.single { it.id == duplicateId }.deletedAt == fixedNow
            }
        }
        assertNotNull(undone.tasks.single { it.id == duplicateId }.deletedAt)
        assertEquals(source, undone.tasks.single { it.id == source.id })
        assertEquals(
            incomingBefore,
            undone.tasks.single { it.id == TaskId("duplicate-incoming") },
        )
        assertEquals(
            reopened.tasks.filter { it.id != duplicateId },
            undone.tasks.filter { it.id != duplicateId },
        )
    }

    @Test
    fun duplicateTaskMovesACompletedSourceToTheFirstActiveBacklog() = runBlocking {
        val seed = duplicationSnapshot()
        openRepository(
            now = { Instant.parse("2026-08-10T10:00:00Z") },
            seedSnapshot = seed,
        )
        val before = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                snapshot.tasks.any { it.id == TaskId("duplicate-source-completed") }
            }
        }
        val completedSource = before.tasks.single {
            it.id == TaskId("duplicate-source-completed")
        }
        val success = repository!!.execute(
            DomainCommand.DuplicateTask(completedSource.id),
        ) as CommandResult.Success
        val duplicateId = (checkNotNull(success.undo) as DomainCommand.DeleteTask).taskId
        val after = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                snapshot.tasks.singleOrNull { it.id == duplicateId }?.statusId ==
                    WorkflowStatusId("duplicate-status-backlog-first")
            }
        }
        val duplicate = after.tasks.single { it.id == duplicateId }
        assertEquals(WorkflowStatusId("duplicate-status-backlog-first"), duplicate.statusId)
        assertEquals(SemanticStatus.BACKLOG, duplicate.semanticStatus)
        assertFalse(duplicate.isCompleted)
    }

    @Test
    fun duplicateTaskRejectionsAreMutationFree() = runBlocking {
        openRepository(
            now = { Instant.parse("2026-08-10T10:00:00Z") },
            seedSnapshot = duplicationSnapshot(),
        )
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                snapshot.tasks.mapTo(hashSetOf(), Task::id).containsAll(
                    duplicationSnapshot().tasks.map(Task::id),
                )
            }
        }
        val generationBeforePrime =
            database!!.backupStateDao().require("vault-primary").currentGeneration
        assertTrue(
            repository!!.execute(
                DomainCommand.RenameTask(
                    TaskId("duplicate-dependency-active"),
                    "Primed unrelated task",
                ),
            ) is CommandResult.Success,
        )
        val generationAfterPrime =
            database!!.backupStateDao().require("vault-primary").currentGeneration
        assertEquals(generationBeforePrime + 1, generationAfterPrime)
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                snapshot.tasks.singleOrNull {
                    it.id == TaskId("duplicate-dependency-active")
                }?.title == "Primed unrelated task"
            }
        }
        assertTrue(journalRows(generationAfterPrime).isNotEmpty())
        val payloadSentinel = ByteArray(0)
        listOf(
            DomainCommand.DuplicateTask(TaskId("missing")) to RejectionReason.NOT_FOUND,
            DomainCommand.DuplicateTask(TaskId("duplicate-source-deleted")) to
                RejectionReason.INVALID_STATE,
            DomainCommand.DuplicateTask(TaskId("duplicate-source-missing-status")) to
                RejectionReason.INVALID_STATE,
        ).forEach { (command, expectedReason) ->
            val before = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
                repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                    snapshot.tasks.mapTo(hashSetOf(), Task::id).containsAll(
                        duplicationSnapshot().tasks.map(Task::id),
                    )
                }
            }
            val generationBefore =
                database!!.backupStateDao().require("vault-primary").currentGeneration
            val rowsBefore = database!!.backupJournalDao()
                .between("vault-primary", 0, generationBefore)
            val result = repository!!.execute(command) as CommandResult.Rejected
            assertEquals(expectedReason, result.reason)
            val after = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
                repository!!.observeWorkspace().filterNotNull().first { it == before }
            }
            val generationAfter =
                database!!.backupStateDao().require("vault-primary").currentGeneration
            val rowsAfter = database!!.backupJournalDao()
                .between("vault-primary", 0, generationAfter)
            assertEquals(before, after)
            assertEquals(generationBefore, generationAfter)
            assertEquals(rowsBefore.size, rowsAfter.size)
            assertEquals(
                rowsBefore.map { it.copy(payload = payloadSentinel) },
                rowsAfter.map { it.copy(payload = payloadSentinel) },
            )
            rowsBefore.zip(rowsAfter).forEach { (expected, actual) ->
                assertArrayEquals(expected.payload, actual.payload)
            }
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "vault-test-${UUID.randomUUID()}.db"
        databaseKey = ByteArray(32) { index -> (index + 1).toByte() }
    }

    @After
    fun tearDown() {
        repository?.close()
        database?.close()
        context.deleteDatabase(databaseName)
        databaseKey.fill(0)
    }

    @Test
    fun taskAndOutboxSurviveEncryptedDatabaseRestart() = runBlocking {
        openRepository()
        val result = repository!!.execute(DomainCommand.CreateTask(PERSISTED_TITLE))

        assertTrue(result is CommandResult.Success)
        val taskId = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.title == PERSISTED_TITLE } }
                .filterNotNull()
                .first()
                .id
        }
        val due = ZonedMoment(
            Instant.parse("2026-08-03T10:00:00Z"),
            "Asia/Bangkok",
        )
        val update = repository!!.execute(
            DomainCommand.UpdateTask(
                taskId = taskId,
                title = UPDATED_TITLE,
                description = PERSISTED_DESCRIPTION,
                projectId = OpenTasksFixtures.taxProject.id,
                priority = Priority.URGENT,
                start = null,
                due = due,
                recurrence = null,
                estimate = Duration.ofMinutes(45),
            ),
        )
        val statusUpdate = repository!!.execute(
            DomainCommand.ChangeTaskStatus(
                taskId,
                OpenTasksFixtures.statusId(
                    OpenTasksFixtures.taxProject.id,
                    SemanticStatus.STARTED,
                ),
            ),
        )

        assertTrue(update is CommandResult.Success)
        assertTrue(statusUpdate is CommandResult.Success)

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        val databaseFile = context.getDatabasePath(databaseName)
        assertTrue(databaseFile.exists())
        assertFalse(
            databaseFile.readBytes().containsSubsequence(PERSISTED_TITLE.toByteArray()),
        )
        assertFalse(
            databaseFile.readBytes().containsSubsequence(PERSISTED_DESCRIPTION.toByteArray()),
        )

        openRepository()
        val results = repository!!.search(SearchQuery(PERSISTED_DESCRIPTION))
        val restored = (results.single() as app.opentasks.core.model.SearchResult.TaskResult).task

        assertEquals(UPDATED_TITLE, restored.title)
        assertEquals(PERSISTED_DESCRIPTION, restored.description)
        assertEquals(OpenTasksFixtures.taxProject.id, restored.projectId)
        assertEquals(Priority.URGENT, restored.priority)
        assertEquals(due, restored.due)
        assertEquals(Duration.ofMinutes(45), restored.estimate)
        assertEquals(
            OpenTasksFixtures.statusId(
                OpenTasksFixtures.taxProject.id,
                SemanticStatus.STARTED,
            ),
            restored.statusId,
        )
        assertEquals(SemanticStatus.STARTED, restored.semanticStatus)
    }

    @Test
    fun createTaskPersistsProvidedDue() = runBlocking {
        openRepository()
        val due = ZonedMoment(
            Instant.parse("2026-08-10T09:00:00Z"),
            "Asia/Bangkok",
        )

        assertTrue(
            repository!!.execute(
                DomainCommand.CreateTask(PERSISTED_DUE_TITLE, due = due),
            ) is CommandResult.Success,
        )

        val created = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.title == PERSISTED_DUE_TITLE } }
                .filterNotNull()
                .first()
        }

        assertEquals(due, created.due)
    }

    @Test
    fun createTaskRejectsTheWholeCommandBeforeRoomMutation() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            openRepository(now = { Instant.parse("2026-08-10T09:00:00Z") })
            val due = ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), "UTC")
            invalidCreates(due).forEach { (command, reason) ->
                val before = repository!!.currentWorkspace()
                val generation =
                    database!!.backupStateDao().require("vault-primary").currentGeneration
                val result = repository!!.execute(command) as CommandResult.Rejected
                assertEquals(reason, result.reason)
                assertEquals(before, repository!!.currentWorkspace())
                assertEquals(
                    generation,
                    database!!.backupStateDao().require("vault-primary").currentGeneration,
                )
            }
        }
    }

    @Test
    fun createTaskCreatesEnrichedTaskAtomicallyAndSurvivesRestart() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            val fixedNow = Instant.parse("2026-07-26T10:00:00Z")
            openRepository(now = { fixedNow })
            assertTrue(
                repository!!.execute(
                    DomainCommand.CreateTask(
                        "Deduped tags",
                        tagNames = List(51) { " deep WORK " },
                    ),
                ) is CommandResult.Success,
            )
            val before = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
                repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                    val task = snapshot.tasks.singleOrNull { it.title == "Deduped tags" }
                        ?: return@first false
                    task.tagIds == setOf(TagId("tag-deep-work")) &&
                        snapshot.activityEntries.any {
                            it.taskId == task.id && it.kind == ActivityKind.RECORD_CREATED
                        }
                }
            }

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
            val beforeActivityIds = before.activityEntries.mapTo(hashSetOf(), ActivityEntry::id)
            val generationBefore =
                database!!.backupStateDao().require("vault-primary").currentGeneration
            val success = repository!!.execute(command) as CommandResult.Success
            val generationAfter =
                database!!.backupStateDao().require("vault-primary").currentGeneration
            val rows = journalRows(generationAfter)
            val mutations = rows.map { BackupMutationCodec.decode(it.payload) }
            assertEquals(generationBefore + 1, generationAfter)
            assertEquals(rows.indices.toList(), rows.map { it.sequence })
            assertEquals(5, mutations.size)
            assertTrue(mutations.all { it.record != null && it.deletedFamily == null })
            assertEquals(1, mutations.count { it.record!!.family == BackupRecordFamily.TASK })
            assertEquals(1, mutations.count { it.record!!.family == BackupRecordFamily.TAG })
            assertEquals(2, mutations.count { it.record!!.family == BackupRecordFamily.TASK_TAG })
            assertEquals(
                1,
                mutations.count { it.record!!.family == BackupRecordFamily.ACTIVITY_ENTRY },
            )
            val after = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
                repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                    val created = snapshot.tasks.singleOrNull {
                        it.title == "Enriched" && it.recurrenceAnchor == due
                    } ?: return@first false
                    created.tagIds.size == 2 &&
                        TagId("tag-deep-work") in created.tagIds &&
                        snapshot.tags.size == before.tags.size + 1 &&
                        snapshot.tags.any { it.id in created.tagIds && it.name == "New tag" } &&
                        snapshot.activityEntries.count {
                            it.taskId == created.id && it.kind == ActivityKind.RECORD_CREATED
                        } == 1
                }
            }
            val created = after.tasks.single {
                it.title == "Enriched" && it.recurrenceAnchor == due
            }
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

            repository!!.close()
            database!!.close()
            repository = null
            database = null
            openRepository(now = { fixedNow })
            val reopened = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
                repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                    snapshot.tasks.any {
                        it.id == created.id &&
                            it.recurrenceAnchor == due &&
                            it.tagIds == created.tagIds
                    }
                }
            }
            val reopenedTask = reopened.tasks.single { it.id == created.id }
            assertEquals(Duration.ofMinutes(45), reopenedTask.estimate)
            assertEquals(rule, reopenedTask.recurrence)
            assertEquals(created.id, reopenedTask.recurrenceSeriesId)
            assertEquals(due, reopenedTask.recurrenceAnchor)
            assertEquals(0, reopenedTask.recurrenceOccurrenceIndex)
            assertEquals(created.tagIds, reopenedTask.tagIds)

            repository!!.execute(checkNotNull(success.undo))
            val undone = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
                repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                    snapshot.tasks.single { it.id == created.id }.deletedAt == fixedNow
                }
            }
            assertEquals(fixedNow, undone.tasks.single { it.id == created.id }.deletedAt)
        }
    }

    @Test
    fun blankDueBucketSearchUsesTheInjectedClock() = runBlocking {
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            val instant = Instant.parse("2026-08-10T03:00:00Z")
            val zone = ZoneId.of("Asia/Bangkok")
            openRepository(now = { instant }, zoneId = { zone })
            repository!!.execute(
                DomainCommand.CreateTask(
                    "Today adapter",
                    due = ZonedMoment(instant.plusSeconds(3_600), zone.id),
                ),
            )
            repository!!.observeWorkspace().filterNotNull().first { snapshot ->
                snapshot.tasks.any { it.title == "Today adapter" }
            }
            assertEquals(
                listOf("Today adapter"),
                repository!!.search(SearchQuery("", dueBuckets = setOf(DueBucket.TODAY)))
                    .map(SearchResult::title),
            )
        }
    }

    @Test
    fun activeTimerSurvivesEncryptedDatabaseAndProcessRestart() = runBlocking {
        val startedAt = Instant.parse("2026-07-27T03:00:00Z")
        val beforeRestart = Instant.parse("2026-07-27T03:30:00Z")
        val afterRestart = Instant.parse("2026-07-27T04:00:00Z")
        openRepository(now = { beforeRestart })
        repository!!.execute(DomainCommand.StopTimer)
        val task = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.deletedAt == null } }
                .filterNotNull()
                .first()
        }

        assertTrue(
            repository!!.execute(
                DomainCommand.StartTimer(task.id, startedAt),
            ) is CommandResult.Success,
        )
        val running = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.home.activeTimer }
                .filterNotNull()
                .first { timer -> timer.taskId == task.id }
        }
        assertEquals(startedAt, running.startedAt)
        assertEquals(Duration.ofMinutes(30), running.elapsed)

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository(now = { afterRestart })
        val restored = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.home.activeTimer }
                .filterNotNull()
                .first { timer -> timer.entryId == running.entryId }
        }

        assertEquals(task.id, restored.taskId)
        assertEquals(startedAt, restored.startedAt)
        assertEquals(Duration.ofHours(1), restored.elapsed)
    }

    @Test
    fun manualTimeEntriesAndOverlapReconciliationSurviveEncryptedRestart() = runBlocking {
        val changedAt = Instant.parse("2026-07-27T05:00:00Z")
        openRepository(now = { changedAt })
        repository!!.execute(DomainCommand.StopTimer)
        val task = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.deletedAt == null } }
                .filterNotNull()
                .first()
        }
        val firstId = TimeEntryId("room-manual-first")
        val secondId = TimeEntryId("room-manual-second")
        assertTrue(
            repository!!.execute(
                DomainCommand.AddTimeEntry(
                    entryId = firstId,
                    taskId = task.id,
                    startedAt = Instant.parse("2026-07-27T06:00:00Z"),
                    stoppedAt = Instant.parse("2026-07-27T07:00:00Z"),
                    note = "Planning",
                    changedAt = changedAt,
                ),
            ) is CommandResult.Success,
        )
        assertTrue(
            repository!!.execute(
                DomainCommand.AddTimeEntry(
                    entryId = secondId,
                    taskId = task.id,
                    startedAt = Instant.parse("2026-07-27T06:30:00Z"),
                    stoppedAt = Instant.parse("2026-07-27T07:30:00Z"),
                    note = "Review",
                    changedAt = changedAt.plusMillis(1),
                ),
            ) is CommandResult.Success,
        )
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.timeEntryConflicts.size == 1
            }
        }

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository(now = { changedAt.plusSeconds(60) })
        val restored = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.timeEntries.any { it.id == secondId } &&
                    snapshot.timeEntryConflicts.size == 1
            }
        }
        assertEquals("Planning", restored.timeEntries.first { it.id == firstId }.note)
        assertEquals(Duration.ofMinutes(30), restored.timeEntryConflicts.single().overlap)

        val updated = repository!!.execute(
            DomainCommand.UpdateTimeEntry(
                entryId = secondId,
                startedAt = Instant.parse("2026-07-27T07:00:00Z"),
                stoppedAt = Instant.parse("2026-07-27T08:00:00Z"),
                note = "Review updated",
                changedAt = changedAt.plusSeconds(61),
            ),
        ) as CommandResult.Success
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.timeEntryConflicts.isEmpty() &&
                    snapshot.timeEntries.first { it.id == secondId }.note == "Review updated"
            }
        }
        repository!!.execute(checkNotNull(updated.undo))
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.timeEntryConflicts.size == 1 &&
                    snapshot.timeEntries.first { it.id == secondId }.note == "Review"
            }
        }

        val deleted = repository!!.execute(DomainCommand.DeleteTimeEntry(firstId))
            as CommandResult.Success
        repository!!.execute(checkNotNull(deleted.undo))
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.timeEntries.any { it.id == firstId }
            }
        }
        Unit
    }

    @Test
    fun reminderAndTaskUpdateAreAtomicAndSurviveEncryptedDatabaseRestart() = runBlocking {
        openRepository()
        val original = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { !it.isCompleted } }
                .filterNotNull()
                .first()
        }
        val start = ZonedMoment(
            instant = Instant.parse("2026-08-03T08:00:00Z"),
            zoneId = "UTC",
        )
        val due = ZonedMoment(
            instant = Instant.ofEpochMilli(
                Instant.now().plus(Duration.ofDays(7)).toEpochMilli(),
            ),
            zoneId = "UTC",
        )
        val reminder = Reminder(
            id = "caller-provided-id",
            taskId = original.id,
            triggerAt = due.copy(instant = due.instant.minus(Duration.ofHours(1))),
            precise = true,
        )

        val update = repository!!.execute(
            DomainCommand.UpdateTask(
                taskId = original.id,
                title = original.title,
                description = original.description,
                projectId = original.projectId,
                priority = original.priority,
                start = start,
                due = due,
                recurrence = original.recurrence,
                estimate = original.estimate,
                reminder = reminder,
            ),
        ) as CommandResult.Success
        val expectedReminder = reminder.copy(id = Reminder.primaryId(original.id))

        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == original.id }?.let { task ->
                    task.start == start && task.due == due
                } == true &&
                    snapshot.reminders.singleOrNull { it.taskId == original.id } ==
                    expectedReminder
            }
        }

        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository()

        val restored = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.reminders.singleOrNull { it.taskId == original.id } == expectedReminder
            }
        }
        assertEquals(start, restored.tasks.first { it.id == original.id }.start)
        assertEquals(due, restored.tasks.first { it.id == original.id }.due)

        assertTrue(repository!!.execute(checkNotNull(update.undo)) is CommandResult.Success)
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == original.id }?.let { task ->
                    task.start == original.start && task.due == original.due
                } == true &&
                    snapshot.reminders.none { it.taskId == original.id }
            }
        }
        assertTrue(
            latestJournalPayloads().any {
                it.deletedFamily == BackupRecordFamily.REMINDER &&
                    it.deletedIdentity == listOf(expectedReminder.id)
            },
        )
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
        openRepository(
            now = { SCHEDULE_NOW },
            seedSnapshot = scheduleSnapshot(original, existingReminder),
        )
        val before = repository!!.currentWorkspace()
        val beforeTask = before.tasks.single { it.id == original.id }
        val generationBefore =
            database!!.backupStateDao().require("vault-primary").currentGeneration
        val start = ZonedMoment(Instant.parse("2026-08-05T07:30:00Z"), "Asia/Bangkok")
        val due = ZonedMoment(Instant.parse("2026-08-05T09:30:00Z"), "Asia/Bangkok")
        val reminder = Reminder(
            Reminder.primaryId(original.id),
            original.id,
            ZonedMoment(Instant.parse("2026-08-05T08:30:00Z"), "Asia/Bangkok"),
            precise = true,
        )

        val result = repository!!.execute(
            DomainCommand.SetTaskSchedule(original.id, start, due, reminder),
        ) as CommandResult.Success
        val updated = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.single { it.id == original.id }.let { task ->
                    task.start == start && task.due == due
                } && snapshot.reminders.singleOrNull { it.taskId == original.id } == reminder
            }
        }
        val updatedTask = updated.tasks.single { it.id == original.id }
        assertEquals(
            beforeTask.copy(start = start, due = due, revision = updatedTask.revision),
            updatedTask,
        )
        assertEquals(beforeTask.revision.logicalCounter + 1, updatedTask.revision.logicalCounter)
        assertEquals(before.activityEntries, updated.activityEntries)
        val generationAfter =
            database!!.backupStateDao().require("vault-primary").currentGeneration
        assertEquals(generationBefore + 1, generationAfter)
        assertEquals(
            listOf(BackupRecordFamily.TASK, BackupRecordFamily.REMINDER),
            journalRows(generationAfter).map { row ->
                BackupMutationCodec.decode(row.payload).let { mutation ->
                    mutation.record?.family ?: mutation.deletedFamily
                }
            },
        )
        assertEquals(
            DomainCommand.SetTaskSchedule(
                original.id,
                beforeTask.start,
                beforeTask.due,
                existingReminder,
                restorePastReminder = true,
            ),
            result.undo,
        )

        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository(now = { SCHEDULE_NOW })
        val reopened = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.single { it.id == original.id }.let { task ->
                    task.start == start && task.due == due
                } && snapshot.reminders.singleOrNull { it.taskId == original.id } == reminder
            }
        }
        assertEquals(start, reopened.tasks.single { it.id == original.id }.start)

        repository!!.execute(checkNotNull(result.undo))

        val restored = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.single { it.id == original.id }.let { task ->
                    task.start == beforeTask.start && task.due == beforeTask.due
                } && snapshot.reminders.singleOrNull { it.taskId == original.id } ==
                    existingReminder
            }
        }
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
        openRepository(
            now = { SCHEDULE_NOW },
            seedSnapshot = scheduleSnapshot(task, existingReminder),
        )
        val revisionBefore = repository!!.currentWorkspace().tasks.single { it.id == task.id }.revision
        val generationBefore =
            database!!.backupStateDao().require("vault-primary").currentGeneration

        repository!!.execute(
            DomainCommand.SetTaskSchedule(task.id, task.start, task.due, replacement),
        )
        val replaced = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.reminders.singleOrNull { it.taskId == task.id } == replacement
            }
        }
        val replacedTask = replaced.tasks.single { it.id == task.id }
        assertEquals(revisionBefore.logicalCounter + 1, replacedTask.revision.logicalCounter)
        val replacementGeneration =
            database!!.backupStateDao().require("vault-primary").currentGeneration
        assertEquals(generationBefore + 1, replacementGeneration)
        assertEquals(
            listOf(BackupRecordFamily.TASK, BackupRecordFamily.REMINDER),
            changedFamilies(replacementGeneration),
        )

        repository!!.execute(
            DomainCommand.SetTaskSchedule(task.id, task.start, task.due, reminder = null),
        )
        val removed = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.reminders.none { it.taskId == task.id }
            }
        }
        assertEquals(
            replacedTask.revision.logicalCounter + 1,
            removed.tasks.single { it.id == task.id }.revision.logicalCounter,
        )
        val removalGeneration =
            database!!.backupStateDao().require("vault-primary").currentGeneration
        assertEquals(replacementGeneration + 1, removalGeneration)
        assertEquals(
            listOf(BackupRecordFamily.TASK, BackupRecordFamily.REMINDER),
            changedFamilies(removalGeneration),
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
        openRepository(now = { SCHEDULE_NOW }, seedSnapshot = scheduleSnapshot(original))
        val start = ZonedMoment(Instant.parse("2026-08-10T08:00:00Z"), "UTC")
        val due = ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), "UTC")

        repository!!.execute(DomainCommand.SetTaskSchedule(original.id, start, due, null))

        val updated = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.single { it.id == original.id }.start == start
            }.tasks.single { it.id == original.id }
        }
        assertEquals(original.recurrence, updated.recurrence)
        assertEquals(original.recurrenceSeriesId, updated.recurrenceSeriesId)
        assertEquals(original.recurrenceAnchor, updated.recurrenceAnchor)
        assertEquals(original.recurrenceOccurrenceIndex, updated.recurrenceOccurrenceIndex)
    }

    @Test
    fun setTaskScheduleRejectsMismatchedReminderIdentityWithoutMutation() = runBlocking {
        val task = OpenTasksFixtures.tasks.first { !it.isCompleted && it.recurrence == null }
            .copy(due = ZonedMoment(Instant.parse("2026-08-04T10:00:00Z"), "UTC"))
        openRepository(now = { SCHEDULE_NOW }, seedSnapshot = scheduleSnapshot(task))
        val mismatched = Reminder(
            "wrong-reminder",
            TaskId("wrong-task"),
            ZonedMoment(Instant.parse("2026-08-04T09:00:00Z"), "UTC"),
            precise = false,
        )

        val result = executeRoomScheduleWithoutMutation(
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
        openRepository(now = { SCHEDULE_NOW }, seedSnapshot = scheduleSnapshot(task))
        val reminder = Reminder(
            Reminder.primaryId(task.id),
            task.id,
            ZonedMoment(Instant.parse("2026-08-04T09:00:00Z"), "UTC"),
            precise = false,
        )

        val result = executeRoomScheduleWithoutMutation(
            DomainCommand.SetTaskSchedule(task.id, task.start, null, reminder),
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
        openRepository(now = { SCHEDULE_NOW }, seedSnapshot = scheduleSnapshot(task))

        val result = executeRoomScheduleWithoutMutation(
            DomainCommand.SetTaskSchedule(task.id, null, null, null),
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
        openRepository(now = { SCHEDULE_NOW }, seedSnapshot = scheduleSnapshot(task))
        val due = ZonedMoment(Instant.parse("2026-08-05T10:00:00Z"), "UTC")

        val result = executeRoomScheduleWithoutMutation(
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
        openRepository(now = { SCHEDULE_NOW }, seedSnapshot = scheduleSnapshot(task))
        val due = ZonedMoment(Instant.parse("2026-08-10T18:00:00Z"), "Asia/Bangkok")

        val result = executeRoomScheduleWithoutMutation(
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
        openRepository(now = { SCHEDULE_NOW }, seedSnapshot = scheduleSnapshot(task))
        val start = ZonedMoment(Instant.parse("2026-08-05T10:00:00Z"), "UTC")
        val due = ZonedMoment(Instant.parse("2026-08-05T09:59:59Z"), "UTC")

        val result = executeRoomScheduleWithoutMutation(
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
        openRepository(now = { SCHEDULE_NOW }, seedSnapshot = scheduleSnapshot(task))
        val start = ZonedMoment(Instant.parse("2026-08-05T08:00:00Z"), "UTC")
        val due = ZonedMoment(Instant.parse("2026-08-05T10:00:00Z"), "UTC")
        val reminder = Reminder(
            Reminder.primaryId(task.id),
            task.id,
            SCHEDULE_NOW.atZone(ZoneId.of("UTC")).let {
                ZonedMoment(it.toInstant(), it.zone.id)
            },
            precise = true,
        )

        val result = executeRoomScheduleWithoutMutation(
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
        val seed = OpenTasksFixtures.snapshot.copy(
            tasks = OpenTasksFixtures.snapshot.tasks.map {
                if (it.id == binned.id) binned else it
            },
            reminders = OpenTasksFixtures.snapshot.reminders.filterNot {
                it.taskId == completed.id || it.taskId == binned.id
            },
        )
        openRepository(now = { SCHEDULE_NOW }, seedSnapshot = seed)
        listOf(completed, binned).forEach { task ->
            val result = executeRoomScheduleWithoutMutation(
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
        openRepository(
            now = { SCHEDULE_NOW },
            seedSnapshot = scheduleSnapshot(task, pastReminder),
        )
        val start = ZonedMoment(Instant.parse("2026-08-05T08:00:00Z"), "UTC")
        val due = ZonedMoment(Instant.parse("2026-08-05T10:00:00Z"), "UTC")

        val changed = repository!!.execute(
            DomainCommand.SetTaskSchedule(task.id, start, due, null),
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

        repository!!.execute(undo)

        val restored = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.single { it.id == task.id }.let { restoredTask ->
                    restoredTask.start == task.start && restoredTask.due == task.due
                } && snapshot.reminders.singleOrNull { it.taskId == task.id } == pastReminder
            }
        }
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
        openRepository(
            now = { SCHEDULE_NOW },
            seedSnapshot = scheduleSnapshot(task, pastReminder),
        )

        val result = executeRoomScheduleWithoutMutation(
            DomainCommand.SetTaskSchedule(task.id, task.start, task.due, pastReminder),
        ) as CommandResult.Success

        assertNull(result.undo)
    }

    @Test
    fun checklistAndTagRelationsSurviveEncryptedDatabaseRestart() = runBlocking {
        openRepository()
        val task = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.checklist.isNotEmpty() } }
                .filterNotNull()
                .first()
        }

        val checklistResult = repository!!.execute(
            DomainCommand.AddChecklistItem(task.id, PERSISTED_CHECKLIST_TEXT),
        )
        val tagResult = repository!!.execute(
            DomainCommand.CreateAndAssignTag(task.id, PERSISTED_TAG_NAME),
        )
        assertTrue(checklistResult is CommandResult.Success)
        assertTrue(tagResult is CommandResult.Success)

        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                val updated = snapshot.tasks.firstOrNull { it.id == task.id }
                    ?: return@first false
                updated.checklist.any { it.text == PERSISTED_CHECKLIST_TEXT } &&
                    snapshot.tags.any { tag ->
                        tag.name == PERSISTED_TAG_NAME && tag.id in updated.tagIds
                    }
            }
        }

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        val encryptedBytes = context.getDatabasePath(databaseName).readBytes()
        assertFalse(
            encryptedBytes.containsSubsequence(PERSISTED_CHECKLIST_TEXT.toByteArray()),
        )
        assertFalse(encryptedBytes.containsSubsequence(PERSISTED_TAG_NAME.toByteArray()))

        openRepository()
        val restored = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                val updated = snapshot.tasks.firstOrNull { it.id == task.id }
                    ?: return@first false
                updated.checklist.any { it.text == PERSISTED_CHECKLIST_TEXT } &&
                    snapshot.tags.any { tag ->
                        tag.name == PERSISTED_TAG_NAME && tag.id in updated.tagIds
                    }
            }
        }
        val restoredTask = restored.tasks.first { it.id == task.id }
        assertTrue(restoredTask.checklist.any { it.text == PERSISTED_CHECKLIST_TEXT })
        assertTrue(repository!!.search(SearchQuery(PERSISTED_CHECKLIST_TEXT)).isNotEmpty())
        assertTrue(repository!!.search(SearchQuery(PERSISTED_TAG_NAME)).isNotEmpty())
    }

    @Test
    fun recurringCompletionAndUndoSurviveEncryptedDatabaseRestart() = runBlocking {
        val testNow = { Instant.parse("2026-07-26T10:00:00Z") }
        openRepository(testNow)
        val original = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot ->
                    snapshot.tasks.firstOrNull {
                        it.checklist.isNotEmpty() && it.tagIds.isNotEmpty()
                    }
                }
                .filterNotNull()
                .first()
        }
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
        assertTrue(
            repository!!.execute(
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
            ) is CommandResult.Success,
        )
        val completion = repository!!.execute(
            DomainCommand.CompleteTask(
                taskId = original.id,
                completedAt = Instant.parse("2026-07-31T10:00:00Z"),
            ),
        ) as CommandResult.Success
        assertEquals("Task completed • next occurrence scheduled", completion.message)

        val completedSnapshot = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                val next = snapshot.tasks.firstOrNull {
                    it.recurrenceSeriesId == original.id && it.id != original.id
                }
                next != null &&
                    next.tagIds == original.tagIds &&
                    next.checklist.map { it.text } == original.checklist.map { it.text } &&
                    snapshot.reminders.any { it.taskId == next.id }
            }
        }
        val nextBeforeRestart = completedSnapshot.tasks.single {
            it.recurrenceSeriesId == original.id && it.id != original.id
        }
        val nextReminder = completedSnapshot.reminders.single {
            it.taskId == nextBeforeRestart.id
        }
        assertEquals("2026-08-31", nextBeforeRestart.due?.localDateString())
        assertEquals(SemanticStatus.PLANNED, nextBeforeRestart.semanticStatus)
        assertEquals(rule, nextBeforeRestart.recurrence)
        assertEquals(due, nextBeforeRestart.recurrenceAnchor)
        assertEquals(1, nextBeforeRestart.recurrenceOccurrenceIndex)
        assertEquals(original.tagIds, nextBeforeRestart.tagIds)
        assertEquals(
            original.checklist.map { it.text },
            nextBeforeRestart.checklist.map { it.text },
        )
        assertTrue(nextBeforeRestart.checklist.none { it.completed })
        assertNotEquals(
            original.checklist.first().id,
            nextBeforeRestart.checklist.first().id,
        )
        assertEquals(
            checkNotNull(nextBeforeRestart.due).instant.minus(Duration.ofHours(1)),
            nextReminder.triggerAt.instant,
        )
        assertTrue(nextReminder.precise)

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository(testNow)
        val restoredSnapshot = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == nextBeforeRestart.id } ==
                    nextBeforeRestart &&
                    snapshot.tasks.firstOrNull { it.id == original.id }?.isCompleted == true &&
                    snapshot.reminders.any { it == nextReminder }
            }
        }
        assertEquals(
            nextBeforeRestart,
            restoredSnapshot.tasks.first { it.id == nextBeforeRestart.id },
        )

        assertTrue(repository!!.execute(checkNotNull(completion.undo)) is CommandResult.Success)
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.none { it.id == nextBeforeRestart.id } &&
                    snapshot.tasks.firstOrNull { it.id == original.id }?.isCompleted == false &&
                    snapshot.reminders.none { it.taskId == nextBeforeRestart.id } &&
                    snapshot.reminders.any { it == reminder }
            }
        }
        assertTrue(
            database!!.workspaceDao().getTombstone(
                nextBeforeRestart.id.value,
                "task",
            ) != null,
        )
        assertTrue(
            latestJournalPayloads().any {
                it.deletedFamily == BackupRecordFamily.REMINDER &&
                    it.deletedIdentity == listOf(nextReminder.id)
            },
        )

        val completedAgain = repository!!.execute(
            DomainCommand.CompleteTask(
                taskId = original.id,
                completedAt = Instant.parse("2026-08-01T10:00:00Z"),
            ),
        ) as CommandResult.Success
        assertEquals("Task completed • next occurrence scheduled", completedAgain.message)
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.any { it.id == nextBeforeRestart.id } &&
                    snapshot.tasks.firstOrNull { it.id == original.id }?.isCompleted == true &&
                    snapshot.reminders.any { it == nextReminder }
            }
        }
        Unit
    }

    @Test
    fun undoingGeneratedOccurrenceCompletionRetiresItsBlobBearingAttachments() = runBlocking {
        openRepository()
        val original = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.checklist.isNotEmpty() } }
                .filterNotNull()
                .first()
        }
        val due = ZonedMoment(
            instant = Instant.parse("2026-07-31T09:30:00Z"),
            zoneId = "Asia/Bangkok",
        )
        repository!!.execute(
            DomainCommand.UpdateTask(
                taskId = original.id,
                title = original.title,
                description = original.description,
                projectId = original.projectId,
                priority = original.priority,
                start = original.start,
                due = due,
                recurrence = RecurrenceRule(RecurrenceFrequency.MONTHLY, count = 3),
                estimate = original.estimate,
            ),
        )
        val completed = repository!!.execute(
            DomainCommand.CompleteTask(
                taskId = original.id,
                completedAt = Instant.parse("2026-07-31T10:00:00Z"),
            ),
        ) as CommandResult.Success
        val generated = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot ->
                    snapshot.tasks.singleOrNull {
                        it.recurrenceSeriesId == original.id && it.id != original.id
                    }
                }
                .filterNotNull()
                .first()
        }
        val blobSetId = BlobSetId.new()
        repository!!.execute(
            DomainCommand.RegisterAttachment(
                Attachment(
                    id = AttachmentId.new(), taskId = generated.id,
                    displayName = "occurrence-notes.pdf", mimeType = "application/pdf",
                    byteCount = 1_000_000L, contentHash = "34".repeat(32),
                    blobSetId = blobSetId, chunkCount = 1, deletedAt = null,
                    revision = generated.revision,
                ),
            ),
        )

        repository!!.execute(checkNotNull(completed.undo))

        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.none { it.id == generated.id }
            }
        }
        assertTrue(
            database!!.workspaceDao()
                .getAttachmentsWithBlobSetForTask(generated.id.value)
                .isEmpty(),
        )
        assertEquals(
            blobSetId.value,
            database!!.workspaceDao().getRetiredBlobSet(blobSetId.value)?.blobSetId,
        )
    }

    @Test
    fun repeatedRecurringCompletionAcrossRestartCreatesExactlyOneOccurrence() = runBlocking {
        openRepository()
        val original = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.checklist.isNotEmpty() } }
                .filterNotNull()
                .first()
        }
        val due = ZonedMoment(
            instant = Instant.parse("2026-07-31T09:30:00Z"),
            zoneId = "Asia/Bangkok",
        )
        repository!!.execute(
            DomainCommand.UpdateTask(
                taskId = original.id,
                title = original.title,
                description = original.description,
                projectId = original.projectId,
                priority = original.priority,
                start = original.start,
                due = due,
                recurrence = RecurrenceRule(RecurrenceFrequency.MONTHLY, count = 3),
                estimate = original.estimate,
            ),
        )
        val firstCompletion = repository!!.execute(
            DomainCommand.CompleteTask(
                taskId = original.id,
                completedAt = Instant.parse("2026-07-31T10:00:00Z"),
            ),
        ) as CommandResult.Success
        val generated = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot ->
                    snapshot.tasks.singleOrNull {
                        it.recurrenceSeriesId == original.id && it.id != original.id
                    }
                }
                .filterNotNull()
                .first()
        }

        repository!!.execute(
            DomainCommand.CompleteTask(
                taskId = original.id,
                completedAt = Instant.parse("2026-07-31T10:00:01Z"),
            ),
        )

        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository()

        repository!!.execute(
            DomainCommand.CompleteTask(
                taskId = original.id,
                completedAt = Instant.parse("2026-07-31T10:00:02Z"),
            ),
        )
        val afterRedelivery = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.count {
                    it.recurrenceSeriesId == original.id && it.id != original.id
                } == 1
            }
        }
        assertEquals(
            generated.id,
            afterRedelivery.tasks.single {
                it.recurrenceSeriesId == original.id && it.id != original.id
            }.id,
        )

        assertTrue(repository!!.execute(checkNotNull(firstCompletion.undo)) is CommandResult.Success)
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.none { it.id == generated.id } &&
                    snapshot.tasks.firstOrNull { it.id == original.id }?.isCompleted == false
            }
        }
        Unit
    }

    @Test
    fun ruleChangeUndoOnGeneratedOccurrenceRestoresSeriesMetadataAcrossRestart() = runBlocking {
        openRepository()
        val original = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.checklist.isNotEmpty() } }
                .filterNotNull()
                .first()
        }
        val anchor = ZonedMoment(
            instant = Instant.parse("2026-07-31T09:30:00Z"),
            zoneId = "Asia/Bangkok",
        )
        val monthly = RecurrenceRule(
            frequency = RecurrenceFrequency.MONTHLY,
            count = 3,
        )
        assertTrue(
            repository!!.execute(
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
            ) is CommandResult.Success,
        )
        assertTrue(
            repository!!.execute(
                DomainCommand.CompleteTask(
                    taskId = original.id,
                    completedAt = Instant.parse("2026-07-31T10:00:00Z"),
                ),
            ) is CommandResult.Success,
        )
        val occurrence = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot ->
                    snapshot.tasks.firstOrNull {
                        it.recurrenceSeriesId == original.id && it.id != original.id
                    }
                }
                .filterNotNull()
                .first()
        }
        assertEquals(1, occurrence.recurrenceOccurrenceIndex)

        val ruleChange = repository!!.execute(
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
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == occurrence.id }
                    ?.recurrenceSeriesId == occurrence.id
            }
        }

        assertTrue(repository!!.execute(checkNotNull(ruleChange.undo)) is CommandResult.Success)
        val restored = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot ->
                    snapshot.tasks.firstOrNull {
                        it.id == occurrence.id && it.recurrenceSeriesId == original.id
                    }
                }
                .filterNotNull()
                .first()
        }
        assertEquals(monthly, restored.recurrence)
        assertEquals(occurrence.due, restored.due)
        assertEquals(anchor, restored.recurrenceAnchor)
        assertEquals(1, restored.recurrenceOccurrenceIndex)

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository()
        val persisted = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.id == occurrence.id } }
                .filterNotNull()
                .first()
        }
        assertEquals(monthly, persisted.recurrence)
        assertEquals(occurrence.due, persisted.due)
        assertEquals(original.id, persisted.recurrenceSeriesId)
        assertEquals(anchor, persisted.recurrenceAnchor)
        assertEquals(1, persisted.recurrenceOccurrenceIndex)
    }

    @Test
    fun migrationOneToTwoPreservesTasksAndAddsSeriesMetadata() {
        val migrationName = "migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(migrationName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE vaults (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    schemaVersion INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE tasks (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    title TEXT NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL("INSERT INTO vaults VALUES ('vault', 1)")
                            db.execSQL(
                                "INSERT INTO tasks VALUES ('existing-task', 'Keep me')",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            VaultDatabase.MIGRATION_1_2.migrate(db)

            val columns = buildSet {
                db.query("PRAGMA table_info(tasks)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue("recurrenceSeriesId" in columns)
            assertTrue("recurrenceAnchorEpochMillis" in columns)
            assertTrue("recurrenceAnchorZoneId" in columns)
            assertTrue("recurrenceOccurrenceIndex" in columns)

            db.query("SELECT title FROM tasks WHERE id = 'existing-task'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Keep me", cursor.getString(0))
            }
            db.query("SELECT schemaVersion FROM vaults WHERE id = 'vault'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(migrationName)
        }
    }

    @Test
    fun migrationTwoToThreeCreatesProjectAndInboxWorkflowsWithoutLosingStatus() {
        val migrationName = "workflow-migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(migrationName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE vaults (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    schemaVersion INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                "CREATE TABLE projects (id TEXT NOT NULL PRIMARY KEY)",
                            )
                            db.execSQL(
                                """
                                CREATE TABLE workflow_statuses (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    projectId TEXT NOT NULL,
                                    name TEXT NOT NULL,
                                    semanticStatus TEXT NOT NULL,
                                    rank TEXT NOT NULL,
                                    archivedAtEpochMillis INTEGER
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE UNIQUE INDEX index_workflow_statuses_projectId_rank
                                ON workflow_statuses(projectId, rank)
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE tasks (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    projectId TEXT,
                                    statusId TEXT NOT NULL,
                                    semanticStatus TEXT NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL("INSERT INTO vaults VALUES ('vault', 2)")
                            db.execSQL("INSERT INTO projects VALUES ('project-one')")
                            SemanticStatus.entries.forEachIndexed { index, semantic ->
                                db.execSQL(
                                    """
                                    INSERT INTO workflow_statuses VALUES (
                                        'status-${semantic.name.lowercase()}',
                                        'legacy-owner',
                                        '${semantic.name}',
                                        '${semantic.name}',
                                        'a$index',
                                        NULL
                                    )
                                    """.trimIndent(),
                                )
                            }
                            db.execSQL(
                                """
                                INSERT INTO tasks VALUES (
                                    'project-task',
                                    'project-one',
                                    'status-planned',
                                    'PLANNED'
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO tasks VALUES (
                                    'inbox-task',
                                    NULL,
                                    'status-started',
                                    'STARTED'
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            VaultDatabase.MIGRATION_2_3.migrate(db)

            db.query("SELECT COUNT(*) FROM workflow_statuses").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(10, cursor.getInt(0))
            }
            db.query(
                "SELECT statusId FROM tasks WHERE id = 'project-task'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("workflow:project-one:planned", cursor.getString(0))
            }
            db.query(
                "SELECT statusId FROM tasks WHERE id = 'inbox-task'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("workflow:inbox:started", cursor.getString(0))
            }
            db.query(
                "SELECT COUNT(*) FROM workflow_statuses WHERE projectId IS NULL",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5, cursor.getInt(0))
            }
            db.query("SELECT schemaVersion FROM vaults WHERE id = 'vault'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(migrationName)
        }
    }

    @Test
    fun migrationThreeToFourAddsMilestoneRevisionsWithoutLosingRows() {
        val migrationName = "milestone-migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(migrationName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE vaults (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    schemaVersion INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE milestones (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    projectId TEXT NOT NULL,
                                    name TEXT NOT NULL,
                                    dueDate TEXT,
                                    completedAtEpochMillis INTEGER
                                )
                                """.trimIndent(),
                            )
                            db.execSQL("INSERT INTO vaults VALUES ('vault', 3)")
                            db.execSQL(
                                """
                                INSERT INTO milestones VALUES (
                                    'milestone-one',
                                    'project-one',
                                    'Release',
                                    '2026-08-14',
                                    NULL
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            VaultDatabase.MIGRATION_3_4.migrate(db)

            db.query(
                """
                SELECT name, dueDate, revisionWallMillis, revisionLogical, revisionDeviceId
                FROM milestones WHERE id = 'milestone-one'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Release", cursor.getString(0))
                assertEquals("2026-08-14", cursor.getString(1))
                assertEquals(0L, cursor.getLong(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals("migration", cursor.getString(4))
            }
            db.query("SELECT schemaVersion FROM vaults WHERE id = 'vault'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4, cursor.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(migrationName)
        }
    }

    @Test
    fun migrationFourToFiveAddsTemplateRevisionsWithoutLosingPayload() {
        val migrationName = "template-migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(migrationName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE vaults (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    schemaVersion INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE templates (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    workspaceId TEXT NOT NULL,
                                    name TEXT NOT NULL,
                                    encryptedPayload BLOB NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL("INSERT INTO vaults VALUES ('vault', 4)")
                            db.execSQL(
                                """
                                INSERT INTO templates (
                                    id, workspaceId, name, encryptedPayload
                                ) VALUES (?, ?, ?, ?)
                                """.trimIndent(),
                                arrayOf(
                                    "template-one",
                                    "workspace-one",
                                    "Delivery",
                                    byteArrayOf(1, 2, 3),
                                ),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            VaultDatabase.MIGRATION_4_5.migrate(db)

            db.query(
                """
                SELECT name, encryptedPayload, revisionWallMillis,
                    revisionLogical, revisionDeviceId
                FROM templates WHERE id = 'template-one'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Delivery", cursor.getString(0))
                assertTrue(cursor.getBlob(1).contentEquals(byteArrayOf(1, 2, 3)))
                assertEquals(0L, cursor.getLong(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals("migration", cursor.getString(4))
            }
            db.query("SELECT schemaVersion FROM vaults WHERE id = 'vault'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5, cursor.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(migrationName)
        }
    }

    @Test
    fun templateCaptureInstantiationOutboxAndUndoSurviveEncryptedRestart() = runBlocking {
        openRepository(now = { Instant.parse("2026-07-27T06:00:00Z") })
        val templateId = TemplateId("room-template")
        val capture = repository!!.execute(
            DomainCommand.CaptureProjectTemplate(
                templateId = templateId,
                projectId = OpenTasksFixtures.taxProject.id,
                name = "Quarterly filing template",
            ),
        ) as CommandResult.Success
        val captured = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.templates.any { it.id == templateId }
            }.templates.single { it.id == templateId }
        }
        assertTrue(
            latestJournalPayloads().any {
                it.record?.family == BackupRecordFamily.TEMPLATE &&
                    it.record.identity == listOf(templateId.value)
            },
        )

        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository(now = { Instant.parse("2026-07-27T06:01:00Z") })
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.templates.singleOrNull()?.id == templateId
            }
        }

        val projectId = ProjectId("room-template-project")
        assertTrue(
            repository!!.execute(
                DomainCommand.InstantiateProjectTemplate(
                    templateId = templateId,
                    projectId = projectId,
                    projectName = "October filing",
                    anchorDate = LocalDate.of(2026, 10, 1),
                ),
            ) is CommandResult.Success,
        )
        val instantiated = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.projects.any { it.id == projectId } &&
                    snapshot.tasks.count { it.projectId == projectId } == captured.tasks.size &&
                    snapshot.workflowStatuses.count { it.projectId == projectId } ==
                    captured.workflowStatuses.size &&
                    snapshot.milestones.count { it.projectId == projectId } ==
                    captured.milestones.size
            }
        }
        assertEquals(
            captured.workflowStatuses.size,
            instantiated.workflowStatuses.count { it.projectId == projectId },
        )
        assertEquals(
            captured.milestones.size,
            instantiated.milestones.count { it.projectId == projectId },
        )
        val operations = latestJournalPayloads()
        assertTrue(
            operations.any {
                it.record?.family == BackupRecordFamily.PROJECT &&
                    it.record.identity == listOf(projectId.value)
            },
        )
        assertEquals(
            captured.tasks.size,
            operations.count { it.record?.family == BackupRecordFamily.TASK },
        )

        repository!!.execute(checkNotNull(capture.undo))
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { it.templates.isEmpty() }
        }
        repository!!.execute(DomainCommand.RestoreTemplate(captured))
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { it.templates.singleOrNull()?.id == templateId }
        }
        Unit
    }

    @Test
    fun trashRestoreAndPermanentDeleteSurviveEncryptedDatabaseRestart() = runBlocking {
        openRepository()
        val task = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.checklist.isNotEmpty() } }
                .filterNotNull()
                .first()
        }
        val reminder = Reminder(
            id = Reminder.primaryId(task.id),
            taskId = task.id,
            triggerAt = ZonedMoment(
                instant = Instant.now().plus(Duration.ofDays(7)),
                zoneId = "UTC",
            ),
            precise = false,
        )
        assertTrue(
            repository!!.execute(
                DomainCommand.SetTaskReminder(
                    taskId = task.id,
                    triggerAt = reminder.triggerAt,
                ),
            ) is CommandResult.Success,
        )
        repository!!.execute(DomainCommand.StopTimer)
        val deletedAt = Instant.parse("2026-07-26T10:05:00Z")

        val deleted = repository!!.execute(DomainCommand.DeleteTask(task.id, deletedAt))
        assertTrue(deleted is CommandResult.Success)
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == task.id }?.deletedAt == deletedAt
            }
        }

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository()
        val trashed = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.id == task.id } }
                .filterNotNull()
                .first { restored -> restored.deletedAt == deletedAt }
        }
        assertTrue(trashed.checklist.isNotEmpty())

        assertTrue(repository!!.execute(DomainCommand.RestoreTask(task.id)) is CommandResult.Success)
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == task.id }?.deletedAt == null
            }
        }

        repository!!.execute(DomainCommand.DeleteTask(task.id, deletedAt))
        assertTrue(
            repository!!.execute(DomainCommand.PermanentlyDeleteTask(task.id)) is
                CommandResult.Success,
        )
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.none { it.id == task.id }
            }
        }
        assertTrue(
            database!!.workspaceDao().getTombstone(task.id.value, "task") != null,
        )
        assertTrue(
            latestJournalPayloads().any {
                it.deletedFamily == BackupRecordFamily.REMINDER &&
                    it.deletedIdentity == listOf(reminder.id)
            },
        )

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository()
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.none { it.id == task.id }
            }
        }
        assertEquals(
            null,
            repository!!.search(
                SearchQuery(task.title, includeTrash = true),
            ).firstOrNull(),
        )
    }

    @Test
    fun projectWorkbenchEditsSurviveEncryptedDatabaseRestart() = runBlocking {
        openRepository()
        val original = OpenTasksFixtures.taxProject
        val result = repository!!.execute(
            DomainCommand.UpdateProject(
                projectId = original.id,
                name = PERSISTED_PROJECT_NAME,
                summary = PERSISTED_PROJECT_SUMMARY,
                health = ProjectHealth.ON_TRACK,
                dueDate = LocalDate.of(2026, 8, 2),
            ),
        )
        assertTrue(result is CommandResult.Success)
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.projects.firstOrNull { it.id == original.id }?.name ==
                    PERSISTED_PROJECT_NAME
            }
        }

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        val encryptedBytes = context.getDatabasePath(databaseName).readBytes()
        assertFalse(encryptedBytes.containsSubsequence(PERSISTED_PROJECT_SUMMARY.toByteArray()))

        openRepository()
        val restored = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.projects.firstOrNull { it.id == original.id } }
                .filterNotNull()
                .first { it.name == PERSISTED_PROJECT_NAME }
        }
        assertEquals(PERSISTED_PROJECT_SUMMARY, restored.summary)
        assertEquals(ProjectHealth.ON_TRACK, restored.status)
        assertEquals(LocalDate.of(2026, 8, 2), restored.dueDate)
        assertTrue(repository!!.search(SearchQuery(PERSISTED_PROJECT_SUMMARY)).isNotEmpty())
    }

    @Test
    fun projectWorkflowLifecycleIsAtomicAndSurvivesEncryptedRestart() = runBlocking {
        openRepository()
        val project = OpenTasksFixtures.studioProject
        val customId = WorkflowStatusId("instrumented-review-status")

        assertTrue(
            repository!!.execute(
                DomainCommand.CreateWorkflowStatus(
                    statusId = customId,
                    projectId = project.id,
                    name = "Ready for review",
                    semanticStatus = SemanticStatus.PLANNED,
                ),
            ) is CommandResult.Success,
        )
        assertTrue(
            repository!!.execute(
                DomainCommand.RenameWorkflowStatus(customId, "Review queue"),
            ) is CommandResult.Success,
        )
        assertTrue(
            repository!!.execute(
                DomainCommand.MoveWorkflowStatus(
                    customId,
                    WorkflowMoveDirection.EARLIER,
                ),
            ) is CommandResult.Success,
        )
        assertTrue(
            repository!!.execute(
                DomainCommand.ArchiveWorkflowStatus(OpenTasksFixtures.planned),
            ) is CommandResult.Success,
        )
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.workflowStatuses.firstOrNull { it.id == customId }?.name ==
                    "Review queue" &&
                    snapshot.workflowStatuses
                        .firstOrNull { it.id == OpenTasksFixtures.planned }
                        ?.archivedAt != null
            }
        }

        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository()

        val restored = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.workflowStatuses.firstOrNull { it.id == customId }?.name ==
                    "Review queue"
            }
        }
        assertTrue(
            restored.workflowStatuses
                .first { it.id == OpenTasksFixtures.planned }
                .archivedAt != null,
        )
        assertTrue(
            repository!!.execute(
                DomainCommand.RestoreArchivedWorkflowStatus(OpenTasksFixtures.planned),
            ) is CommandResult.Success,
        )
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.workflowStatuses
                    .firstOrNull { it.id == OpenTasksFixtures.planned }
                    ?.archivedAt == null
            }
        }
        Unit
    }

    @Test
    fun projectLifecycleAndAssignedTasksSurviveEncryptedDatabaseRestart() = runBlocking {
        openRepository()
        val projectId = ProjectId("instrumented-project-lifecycle")
        val archivedAt = Instant.parse("2026-07-26T11:00:00Z")

        assertTrue(
            repository!!.execute(
                DomainCommand.CreateProject(
                    projectId = projectId,
                    name = LIFECYCLE_PROJECT_NAME,
                    summary = LIFECYCLE_PROJECT_SUMMARY,
                ),
            ) is CommandResult.Success,
        )
        assertTrue(
            repository!!.execute(
                DomainCommand.CreateTask(
                    title = LIFECYCLE_TASK_TITLE,
                    projectId = projectId,
                ),
            ) is CommandResult.Success,
        )
        assertTrue(
            repository!!.execute(
                DomainCommand.ArchiveProject(projectId, archivedAt),
            ) is CommandResult.Success,
        )
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.projects.firstOrNull { it.id == projectId }?.archivedAt == archivedAt &&
                    snapshot.tasks.any {
                        it.projectId == projectId && it.title == LIFECYCLE_TASK_TITLE
                    } &&
                    snapshot.home.projects.none { it.id == projectId }
            }
        }

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository()
        val archivedSnapshot = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.projects.firstOrNull { it.id == projectId }?.archivedAt == archivedAt
            }
        }
        assertTrue(
            archivedSnapshot.tasks.any {
                it.projectId == projectId && it.title == LIFECYCLE_TASK_TITLE
            },
        )
        assertFalse(archivedSnapshot.home.projects.any { it.id == projectId })
        assertFalse(
            repository!!.search(SearchQuery(LIFECYCLE_PROJECT_SUMMARY))
                .any { it is app.opentasks.core.model.SearchResult.ProjectResult },
        )

        assertTrue(
            repository!!.execute(
                DomainCommand.RestoreArchivedProject(projectId),
            ) is CommandResult.Success,
        )
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.projects.firstOrNull { it.id == projectId }?.archivedAt == null &&
                    snapshot.home.projects.any { it.id == projectId }
            }
        }
        Unit
    }

    @Test
    fun milestoneLifecycleMembershipUndoAndOutboxSurviveRestart() = runBlocking {
        openRepository(now = { Instant.parse("2026-07-27T04:00:00Z") })
        val project = OpenTasksFixtures.studioProject
        val task = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.any { it.projectId == project.id && !it.isCompleted }
            }.tasks.first { it.projectId == project.id && !it.isCompleted }
        }
        val milestoneId = MilestoneId("room-milestone-lifecycle")

        assertTrue(
            repository!!.execute(
                DomainCommand.CreateMilestone(
                    milestoneId = milestoneId,
                    projectId = project.id,
                    name = "Release candidate",
                    dueDate = LocalDate.of(2026, 8, 12),
                ),
            ) is CommandResult.Success,
        )
        assertTrue(
            repository!!.execute(
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
            ) is CommandResult.Success,
        )
        assertTrue(
            repository!!.execute(
                DomainCommand.UpdateMilestone(
                    milestoneId = milestoneId,
                    name = "Release ready",
                    dueDate = LocalDate.of(2026, 8, 13),
                    completedAt = null,
                ),
            ) is CommandResult.Success,
        )

        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository(now = { Instant.parse("2026-07-27T04:01:00Z") })

        val restored = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.milestones.any {
                    it.id == milestoneId && it.name == "Release ready"
                } && snapshot.tasks.any {
                    it.id == task.id && it.milestoneId == milestoneId
                }
            }
        }
        assertTrue(restored.milestones.any { it.id == milestoneId })

        val deleted = repository!!.execute(
            DomainCommand.DeleteMilestone(milestoneId),
        ) as CommandResult.Success
        repository!!.execute(checkNotNull(deleted.undo))
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.milestones.any { it.id == milestoneId } &&
                    snapshot.tasks.any {
                        it.id == task.id && it.milestoneId == milestoneId
                    }
            }
        }
        Unit
    }

    @Test
    fun dependencyLifecycleCycleGuardResolutionAndOutboxSurviveRestart() = runBlocking {
        openRepository(now = { Instant.parse("2026-07-27T05:00:00Z") })
        val candidates = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.count {
                    !it.isCompleted && !it.isBlocked && it.deletedAt == null &&
                        it.recurrence == null && it.dependencyIds.isEmpty()
                } >= 3
            }.tasks.filter {
                !it.isCompleted && !it.isBlocked && it.deletedAt == null &&
                    it.recurrence == null && it.dependencyIds.isEmpty()
            }.take(3)
        }
        val first = candidates[0]
        val second = candidates[1]
        val third = candidates[2]

        val firstLink = repository!!.execute(
            DomainCommand.SetTaskDependency(first.id, second.id, present = true),
        ) as CommandResult.Success
        assertTrue(
            repository!!.execute(
                DomainCommand.SetTaskDependency(second.id, third.id, present = true),
            ) is CommandResult.Success,
        )
        val cycle = repository!!.execute(
            DomainCommand.SetTaskDependency(third.id, first.id, present = true),
        )
        assertEquals(
            RejectionReason.DEPENDENCY_CYCLE,
            (cycle as CommandResult.Rejected).reason,
        )
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == first.id }?.blockedBy == setOf(second.id)
            }
        }
        assertTrue(
            latestJournalPayloads().any {
                it.record?.family == BackupRecordFamily.TASK_DEPENDENCY
            },
        )

        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository(now = { Instant.parse("2026-07-27T05:01:00Z") })

        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == first.id }?.let { observed ->
                    observed.dependencyIds == setOf(second.id) &&
                        observed.blockedBy == setOf(second.id)
                } == true
            }
        }
        val completion = repository!!.execute(
            DomainCommand.CompleteTask(second.id, acknowledgeBlocked = true),
        ) as CommandResult.Success
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == first.id }?.let { observed ->
                    observed.dependencyIds == setOf(second.id) && observed.blockedBy.isEmpty()
                } == true
            }
        }

        repository!!.execute(checkNotNull(completion.undo))
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == first.id }?.blockedBy == setOf(second.id)
            }
        }
        repository!!.execute(checkNotNull(firstLink.undo))
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == first.id }?.let { observed ->
                    observed.dependencyIds.isEmpty() && observed.blockedBy.isEmpty()
                } == true
            }
        }
        Unit
    }

    @Test
    fun taskEditBootstrapsStateAndCommitsOneGenerationAtomically() = runBlocking {
        openRepository(now = { Instant.parse("2026-07-29T10:00:00Z") })
        val task = repository!!.currentWorkspace().tasks.first()
        val before = database!!.backupStateDao().require("vault-primary")

        val result = repository!!.execute(DomainCommand.RenameTask(task.id, "Atomic title"))

        val after = database!!.backupStateDao().require("vault-primary")
        val rows = journalRows(after.currentGeneration)
        assertTrue(result is CommandResult.Success)
        assertEquals(before.currentGeneration + 1, after.currentGeneration)
        assertEquals(listOf(0), rows.map { it.sequence })
        assertEquals(
            BackupRecordFamily.TASK,
            BackupMutationCodec.decode(rows.single().payload).record!!.family,
        )
    }

    @Test
    fun acceptedMutationAtomicallyMarksVerifiedReadyPackageUpdatePending() = runBlocking {
        openRepository(now = { Instant.parse("2026-07-29T10:00:00Z") })
        val task = repository!!.currentWorkspace().tasks.first()
        val initial = database!!.backupStateDao().require("vault-primary")
        val ready = initial.copy(
            portablePackageGeneration = initial.currentGeneration,
            portablePackageBytes = 4_096,
            portablePackageProducedAtEpochMillis = 1_234,
            packageState = "READY",
            failureCategory = null,
            recoveryEnvelopeReady = true,
        )
        assertEquals(
            ready,
            RoomBackupStateStore(database!!).mutate(
                VaultId("vault-primary"),
                BackupStateMutation { ready },
            ),
        )

        val result = repository!!.execute(
            DomainCommand.RenameTask(task.id, "Package becomes stale"),
        )

        val after = database!!.backupStateDao().require("vault-primary")
        assertTrue(result is CommandResult.Success)
        assertEquals(ready.currentGeneration + 1, after.currentGeneration)
        assertEquals(ready.portablePackageGeneration, after.portablePackageGeneration)
        assertEquals(ready.portablePackageBytes, after.portablePackageBytes)
        assertEquals(
            ready.portablePackageProducedAtEpochMillis,
            after.portablePackageProducedAtEpochMillis,
        )
        assertEquals("UPDATE_PENDING", after.packageState)
        assertEquals(null, after.failureCategory)
        assertEquals(true, after.recoveryEnvelopeReady)
        assertEquals(1, journalRows(after.currentGeneration).size)
    }

    @Test
    fun projectCreationCommitsProjectAndFiveWorkflowsUnderOneGeneration() = runBlocking {
        openRepository(now = { Instant.parse("2026-07-29T10:00:00Z") })
        repository!!.currentWorkspace()
        val before = database!!.backupStateDao().require("vault-primary")

        val result = repository!!.execute(
            DomainCommand.CreateProject(
                projectId = ProjectId("room-atomic-project"),
                name = "Atomic project",
            ),
        )

        val after = database!!.backupStateDao().require("vault-primary")
        val rows = journalRows(after.currentGeneration)
        assertTrue(result is CommandResult.Success)
        assertEquals(before.currentGeneration + 1, after.currentGeneration)
        // Project creation also generates its own activity entry, so the one
        // generation carries seven records rather than the original six.
        assertEquals(List(7) { it }, rows.map { it.sequence })
        val families = rows.map { BackupMutationCodec.decode(it.payload).record!!.family }
        assertEquals(1, families.count { it == BackupRecordFamily.PROJECT })
        assertEquals(5, families.count { it == BackupRecordFamily.WORKFLOW_STATUS })
        assertEquals(1, families.count { it == BackupRecordFamily.ACTIVITY_ENTRY })
    }

    @Test
    fun workflowReorderCommitsBothRowsUnderOneGeneration() = runBlocking {
        openRepository(now = { Instant.parse("2026-07-29T10:00:00Z") })
        val statuses = repository!!.currentWorkspace().workflowStatuses
            .filter { it.projectId == OpenTasksFixtures.studioProject.id && it.archivedAt == null }
            .sortedBy { it.rank }
        val before = database!!.backupStateDao().require("vault-primary")

        val result = repository!!.execute(
            DomainCommand.MoveWorkflowStatus(
                statusId = statuses[2].id,
                direction = WorkflowMoveDirection.EARLIER,
            ),
        )

        val after = database!!.backupStateDao().require("vault-primary")
        val rows = journalRows(after.currentGeneration)
        assertTrue(result is CommandResult.Success)
        assertEquals(before.currentGeneration + 1, after.currentGeneration)
        assertEquals(listOf(0, 1), rows.map { it.sequence })
        assertTrue(
            rows.all {
                BackupMutationCodec.decode(it.payload).record!!.family ==
                    BackupRecordFamily.WORKFLOW_STATUS
            },
        )
    }

    @Test
    fun templateInstantiationCommitsEveryInsertedFamilyUnderOneGeneration() = runBlocking {
        openRepository(now = { Instant.parse("2026-07-29T10:00:00Z") })
        val uniqueTagName = "Atomic template-only tag"
        val sourceTask = repository!!.currentWorkspace().tasks
            .single { it.projectId == OpenTasksFixtures.taxProject.id }
        val tagResult = repository!!.execute(
            DomainCommand.CreateAndAssignTag(sourceTask.id, uniqueTagName),
        )
        assertTrue(tagResult is CommandResult.Success)
        val tagUndo = (tagResult as CommandResult.Success).undo as DomainCommand.SetTaskTag
        val uniqueTagId = tagUndo.tagId
        withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .first { snapshot ->
                    snapshot.tags.any { it.id == uniqueTagId && it.name == uniqueTagName } &&
                        snapshot.tasks.single { it.id == sourceTask.id }
                            .tagIds.contains(uniqueTagId)
                }
        }
        val templateId = TemplateId("room-atomic-template")
        repository!!.execute(
            DomainCommand.CaptureProjectTemplate(
                templateId = templateId,
                projectId = OpenTasksFixtures.taxProject.id,
                name = "Atomic template",
            ),
        )
        val sourceTag = database!!.workspaceDao().getTagById(uniqueTagId.value)!!
        database!!.workspaceDao().upsertTag(
            sourceTag.copy(name = "Atomic source tag after capture"),
        )
        assertTrue(
            database!!.workspaceDao().findTagByName(
                OpenTasksFixtures.workspaceId.value,
                uniqueTagName,
            ) == null,
        )
        val before = database!!.backupStateDao().require("vault-primary")

        val result = repository!!.execute(
            DomainCommand.InstantiateProjectTemplate(
                templateId = templateId,
                projectId = ProjectId("room-atomic-instantiation"),
                projectName = "Atomic instantiation",
                anchorDate = LocalDate.of(2026, 10, 1),
            ),
        )

        val after = database!!.backupStateDao().require("vault-primary")
        val rows = journalRows(after.currentGeneration)
        val payloads = rows.map { BackupMutationCodec.decode(it.payload) }
        val families = payloads.map { it.record!!.family }.toSet()
        val insertedTag = payloads.single {
            it.record?.family == BackupRecordFamily.TAG
        }.record!!
        assertTrue(result is CommandResult.Success)
        assertEquals(before.currentGeneration + 1, after.currentGeneration)
        assertEquals(rows.indices.toList(), rows.map { it.sequence })
        assertTrue(BackupRecordFamily.PROJECT in families)
        assertTrue(BackupRecordFamily.WORKFLOW_STATUS in families)
        assertTrue(BackupRecordFamily.MILESTONE in families)
        assertTrue(BackupRecordFamily.TASK in families)
        assertTrue(BackupRecordFamily.TAG in families)
        assertTrue(BackupRecordFamily.TASK_TAG in families)
        assertEquals(
            uniqueTagName,
            insertedTag.fields.single { it.name == "name" }.value,
        )
    }

    @Test
    fun permanentTaskPurgeCommitsRelationDeletionsAndTombstoneTogether() = runBlocking {
        val now = Instant.parse("2026-07-29T10:00:00Z")
        openRepository(now = { now })
        repository!!.execute(DomainCommand.StopTimer)
        val task = repository!!.currentWorkspace().tasks.first { it.checklist.isNotEmpty() }
        val dependency = repository!!.currentWorkspace().tasks.first {
            it.id != task.id && it.deletedAt == null && !it.isCompleted
        }
        assertTrue(
            repository!!.execute(
                DomainCommand.SetTaskDependency(
                    taskId = task.id,
                    dependsOnTaskId = dependency.id,
                    present = true,
                ),
            ) is CommandResult.Success,
        )
        val dependencyEntity = checkNotNull(
            database!!.taskDao().getById(dependency.id.value),
        )
        database!!.taskDao().upsert(
            dependencyEntity.copy(parentTaskId = task.id.value),
        )
        val attachmentId = "room-purge-attachment"
        val activityId = "room-purge-activity"
        database!!.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO attachments (
                id, taskId, displayNameCiphertext, mimeType, byteCount,
                contentHash, blobSetId, chunkCount, deletedAtEpochMillis,
                revisionWallMillis, revisionLogical, revisionDeviceId
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                attachmentId,
                task.id.value,
                byteArrayOf(1, 2, 3),
                "text/plain",
                3L,
                "room-purge-content-hash",
                null,
                0,
                null,
                0L,
                0,
                "",
            ),
        )
        database!!.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO activity_entries (
                id, taskId, projectId, kind, bodyCiphertext, createdAtEpochMillis
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                activityId,
                task.id.value,
                task.projectId!!.value,
                "comment",
                byteArrayOf(4, 5, 6),
                now.minusSeconds(120).toEpochMilli(),
            ),
        )
        val stoppedTimer = database!!.timeEntryDao().getAll()
            .single { it.taskId == task.id.value }
        repository!!.execute(
            DomainCommand.SetTaskReminder(
                taskId = task.id,
                triggerAt = ZonedMoment(now.plusSeconds(3_600), "UTC"),
            ),
        )
        repository!!.execute(DomainCommand.DeleteTask(task.id, now.minusSeconds(60)))
        val before = database!!.backupStateDao().require("vault-primary")
        val activityIdsBeforePurge = database!!.recoveryImportDao()
            .allActivityEntries()
            .filter { it.taskId == task.id.value }
            .map { it.id }

        val result = repository!!.execute(DomainCommand.PermanentlyDeleteTask(task.id, now))

        val after = database!!.backupStateDao().require("vault-primary")
        val rows = journalRows(after.currentGeneration)
        val payloads = rows.map { BackupMutationCodec.decode(it.payload) }
        assertTrue(result is CommandResult.Success)
        assertEquals(before.currentGeneration + 1, after.currentGeneration)
        assertEquals(rows.indices.toList(), rows.map { it.sequence })
        assertEquals(3, payloads.count { it.deletedFamily == BackupRecordFamily.CHECKLIST_ITEM })
        assertEquals(1, payloads.count { it.deletedFamily == BackupRecordFamily.TASK_TAG })
        assertEquals(
            listOf(listOf(task.id.value, dependency.id.value)),
            payloads.filter {
                it.deletedFamily == BackupRecordFamily.TASK_DEPENDENCY
            }.map { it.deletedIdentity },
        )
        assertEquals(1, payloads.count { it.deletedFamily == BackupRecordFamily.REMINDER })
        assertEquals(
            listOf(listOf(attachmentId)),
            payloads.filter {
                it.deletedFamily == BackupRecordFamily.ATTACHMENT
            }.map { it.deletedIdentity },
        )
        assertEquals(
            activityIdsBeforePurge.sorted(),
            payloads.filter {
                it.deletedFamily == BackupRecordFamily.ACTIVITY_ENTRY
            }.map { checkNotNull(it.deletedIdentity).single() }.sorted(),
        )
        assertTrue(
            database!!.recoveryImportDao().allActivityEntries()
                .none { it.taskId == task.id.value },
        )
        assertEquals(
            listOf(listOf(stoppedTimer.id)),
            payloads.filter {
                it.deletedFamily == BackupRecordFamily.TIME_ENTRY
            }.map { it.deletedIdentity },
        )
        assertEquals(1, payloads.count { it.deletedFamily == BackupRecordFamily.TASK })
        assertEquals(
            null,
            checkNotNull(database!!.taskDao().getById(dependency.id.value)).parentTaskId,
        )
        assertEquals(
            1,
            payloads.count {
                it.record?.family == BackupRecordFamily.TASK &&
                    it.record.identity.single() == dependency.id.value
            },
        )
        assertEquals(
            1,
            payloads.count { it.record?.family == BackupRecordFamily.TOMBSTONE },
        )
    }

    @Test
    fun multiTaskExpiryPurgeUsesOneGeneration() = runBlocking {
        val deletedAt = Instant.parse("2026-06-01T10:00:00Z")
        val purgedAt = Instant.parse("2026-07-29T10:00:00Z")
        openRepository(now = { deletedAt })
        listOf("Expired one", "Expired two").forEach { title ->
            repository!!.execute(DomainCommand.CreateTask(title))
            val task = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
                repository!!.observeWorkspace()
                    .map { snapshot -> snapshot.tasks.singleOrNull { it.title == title } }
                    .filterNotNull()
                    .first()
            }
            repository!!.execute(DomainCommand.DeleteTask(task.id, deletedAt))
        }
        val before = database!!.backupStateDao().require("vault-primary")

        val result = repository!!.execute(DomainCommand.PurgeExpiredTrash(purgedAt))

        val after = database!!.backupStateDao().require("vault-primary")
        val rows = journalRows(after.currentGeneration)
        val payloads = rows.map { BackupMutationCodec.decode(it.payload) }
        assertTrue(result is CommandResult.Success)
        assertEquals(before.currentGeneration + 1, after.currentGeneration)
        assertEquals(rows.indices.toList(), rows.map { it.sequence })
        assertEquals(2, payloads.count { it.deletedFamily == BackupRecordFamily.TASK })
        assertEquals(2, payloads.count { it.record?.family == BackupRecordFamily.TOMBSTONE })
    }

    @Test
    fun expiryPurgeDetachesASurvivingDirectChildInTheSameGeneration() = runBlocking {
        val deletedAt = Instant.parse("2026-06-01T10:00:00Z")
        val purgedAt = Instant.parse("2026-07-29T10:00:00Z")
        openRepository(now = { deletedAt })
        repository!!.execute(DomainCommand.CreateTask("Expired parent"))
        repository!!.execute(DomainCommand.CreateTask("Surviving child"))
        val tasks = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.any { it.title == "Expired parent" } &&
                    snapshot.tasks.any { it.title == "Surviving child" }
            }
        }.tasks.associateBy { it.title }
        val parent = checkNotNull(tasks["Expired parent"])
        val child = checkNotNull(tasks["Surviving child"])
        val childEntity = checkNotNull(database!!.taskDao().getById(child.id.value))
        database!!.taskDao().upsert(childEntity.copy(parentTaskId = parent.id.value))
        repository!!.execute(DomainCommand.DeleteTask(parent.id, deletedAt))
        val before = database!!.backupStateDao().require("vault-primary")

        repository!!.execute(DomainCommand.PurgeExpiredTrash(purgedAt))

        val after = database!!.backupStateDao().require("vault-primary")
        val detached = checkNotNull(database!!.taskDao().getById(child.id.value))
        val payloads = journalRows(after.currentGeneration)
            .map { BackupMutationCodec.decode(it.payload) }
        val detachedRecord = payloads.single {
            it.record?.family == BackupRecordFamily.TASK &&
                it.record.identity.single() == child.id.value
        }.record
        assertEquals(before.currentGeneration + 1, after.currentGeneration)
        assertEquals(null, detached.parentTaskId)
        assertTrue(detached.revisionWallMillis > childEntity.revisionWallMillis)
        assertEquals(childEntity.revisionLogical + 1, detached.revisionLogical)
        assertEquals(null, checkNotNull(detachedRecord).fields.single {
            it.name == "parentTaskId"
        }.value)
    }

    @Test
    fun expiryPurgeRetiresBlobBearingAttachments() = runBlocking {
        val deletedAt = Instant.parse("2026-06-01T10:00:00Z")
        val purgedAt = Instant.parse("2026-07-29T10:00:00Z")
        openRepository(now = { deletedAt })
        repository!!.execute(DomainCommand.CreateTask("Expired with attachment"))
        val task = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
            repository!!.observeWorkspace()
                .map { snapshot ->
                    snapshot.tasks.singleOrNull { it.title == "Expired with attachment" }
                }
                .filterNotNull()
                .first()
        }
        val blobSetId = BlobSetId.new()
        repository!!.execute(
            DomainCommand.RegisterAttachment(
                Attachment(
                    id = AttachmentId.new(), taskId = task.id,
                    displayName = "plan.pdf", mimeType = "application/pdf",
                    byteCount = 2_000_000L, contentHash = "ab".repeat(32),
                    blobSetId = blobSetId, chunkCount = 1, deletedAt = null,
                    revision = task.revision,
                ),
            ),
        )
        repository!!.execute(DomainCommand.DeleteTask(task.id, deletedAt))

        repository!!.execute(DomainCommand.PurgeExpiredTrash(purgedAt))

        val retired = database!!.workspaceDao().getRetiredBlobSet(blobSetId.value)
        assertEquals(blobSetId.value, retired?.blobSetId)
        assertEquals(1, retired?.chunkCount)
        assertTrue(
            database!!.workspaceDao().getAttachmentsWithBlobSetForTask(task.id.value).isEmpty(),
        )
    }

    @Test
    fun rejectedAndIdempotentCommandsDoNotAllocateGenerations() = runBlocking {
        openRepository(now = { Instant.parse("2026-07-29T10:00:00Z") })
        val firstStatus = repository!!.currentWorkspace().workflowStatuses
            .filter { it.projectId == OpenTasksFixtures.studioProject.id && it.archivedAt == null }
            .minBy { it.rank }
        val before = database!!.backupStateDao().require("vault-primary")

        val rejected = repository!!.execute(
            DomainCommand.CreateProject(ProjectId("room-rejected"), " "),
        )
        val missingDelete = repository!!.execute(
            DomainCommand.DeleteTemplate(TemplateId("missing-template")),
        )
        val unchangedMove = repository!!.execute(
            DomainCommand.MoveWorkflowStatus(
                firstStatus.id,
                WorkflowMoveDirection.EARLIER,
            ),
        )

        val after = database!!.backupStateDao().require("vault-primary")
        assertTrue(rejected is CommandResult.Rejected)
        assertTrue(missingDelete is CommandResult.Success)
        assertTrue(unchangedMove is CommandResult.Success)
        assertEquals(before.currentGeneration, after.currentGeneration)
        assertTrue(
            database!!.backupJournalDao()
                .after("vault-primary", before.currentGeneration, 10)
                .isEmpty(),
        )
    }

    @Test
    fun sameTitleRenameDoesNotAllocateGenerationOrJournalEntry() = runBlocking {
        openRepository(now = { Instant.parse("2026-07-29T10:00:00Z") })
        val task = repository!!.currentWorkspace().tasks.first()
        val beforeState = database!!.backupStateDao().require("vault-primary")
        val beforeEntity = database!!.taskDao().getById(task.id.value)

        val result = repository!!.execute(DomainCommand.RenameTask(task.id, task.title))

        val afterState = database!!.backupStateDao().require("vault-primary")
        val afterEntity = database!!.taskDao().getById(task.id.value)
        assertTrue(result is CommandResult.Success)
        assertEquals(beforeEntity!!.title, afterEntity!!.title)
        assertEquals(beforeEntity.revisionWallMillis, afterEntity.revisionWallMillis)
        assertEquals(beforeEntity.revisionLogical, afterEntity.revisionLogical)
        assertEquals(beforeEntity.revisionDeviceId, afterEntity.revisionDeviceId)
        assertEquals(beforeState.currentGeneration, afterState.currentGeneration)
        assertTrue(
            database!!.backupJournalDao()
                .after("vault-primary", beforeState.currentGeneration, 10)
                .isEmpty(),
        )
    }

    @Test
    fun appendFailureRollsBackProductRowsGenerationAndJournal() = runBlocking {
        openRepository(
            now = { Instant.parse("2026-07-29T10:00:00Z") },
            appendBoundary = BackupJournalAppendBoundary { _, _ ->
                throw InjectedAppendFailure()
            },
        )
        val task = repository!!.currentWorkspace().tasks.first()
        val initial = database!!.backupStateDao().require("vault-primary")
        val ready = initial.copy(
                    portablePackageGeneration = initial.currentGeneration,
                    portablePackageBytes = 4_096,
                    portablePackageProducedAtEpochMillis = 1_234,
                    packageState = "READY",
                    recoveryEnvelopeReady = true,
                )
        assertEquals(
            ready,
            RoomBackupStateStore(database!!).mutate(
                VaultId("vault-primary"),
                BackupStateMutation { ready },
            ),
        )
        val beforeState = database!!.backupStateDao().require("vault-primary")
        val beforeEntity = database!!.taskDao().getById(task.id.value)

        assertThrows(InjectedAppendFailure::class.java) {
            runBlocking {
                repository!!.execute(DomainCommand.RenameTask(task.id, "Must roll back"))
            }
        }

        val afterState = database!!.backupStateDao().require("vault-primary")
        val afterEntity = database!!.taskDao().getById(task.id.value)
        assertEquals(beforeEntity!!.title, afterEntity!!.title)
        assertEquals(beforeState.currentGeneration, afterState.currentGeneration)
        assertEquals("READY", afterState.packageState)
        assertEquals(beforeState.portablePackageGeneration, afterState.portablePackageGeneration)
        assertTrue(
            database!!.backupJournalDao()
                .after("vault-primary", beforeState.currentGeneration, 10)
                .isEmpty(),
        )
    }

    @Test
    fun stopTimerIfOwnedStopsTheMatchingTimer() = runBlocking {
        val now = Instant.parse("2026-07-29T10:00:00Z")
        openRepository(now = { now })
        repository!!.execute(DomainCommand.StopTimer)
        val task = repository!!.currentWorkspace().tasks.first { it.deletedAt == null }
        assertTrue(
            repository!!.execute(
                DomainCommand.StartTimer(task.id, Instant.parse("2026-07-29T09:30:00Z")),
            ) is CommandResult.Success,
        )

        val stopped = repository!!.execute(DomainCommand.StopTimerIfOwned(task.id))

        assertTrue(stopped is CommandResult.Success)
        assertEquals(null, database!!.timeEntryDao().getActive())
    }

    @Test
    fun stopTimerIfOwnedRejectsAnotherOwnerWithoutMutation() = runBlocking {
        val now = Instant.parse("2026-07-29T10:00:00Z")
        openRepository(now = { now })
        repository!!.execute(DomainCommand.StopTimer)
        val tasks = repository!!.currentWorkspace().tasks.filter { it.deletedAt == null }
        val owner = tasks.first()
        val other = tasks.last { it.id != owner.id }
        assertTrue(
            repository!!.execute(
                DomainCommand.StartTimer(owner.id, Instant.parse("2026-07-29T09:30:00Z")),
            ) is CommandResult.Success,
        )
        val activeBefore = checkNotNull(database!!.timeEntryDao().getActive())
        val stateBefore = database!!.backupStateDao().require("vault-primary")

        val rejected = repository!!.execute(DomainCommand.StopTimerIfOwned(other.id))

        assertEquals(
            RejectionReason.TIMER_OWNERSHIP_CHANGED,
            (rejected as CommandResult.Rejected).reason,
        )
        val stateAfter = database!!.backupStateDao().require("vault-primary")
        val activeAfter = checkNotNull(database!!.timeEntryDao().getActive())
        val ignoredCiphertext = byteArrayOf()
        assertEquals(
            activeBefore.copy(noteCiphertext = ignoredCiphertext),
            activeAfter.copy(noteCiphertext = ignoredCiphertext),
        )
        assertArrayEquals(activeBefore.noteCiphertext, activeAfter.noteCiphertext)
        assertEquals(stateBefore.currentGeneration, stateAfter.currentGeneration)
        assertTrue(
            database!!.backupJournalDao()
                .after("vault-primary", stateBefore.currentGeneration, 10)
                .isEmpty(),
        )
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

    private suspend fun journalRows(generation: Long) =
        database!!.backupJournalDao().between("vault-primary", generation, generation)

    private suspend fun latestJournalPayloads() =
        database!!.backupStateDao().require("vault-primary").currentGeneration.let { generation ->
            journalRows(generation).map { BackupMutationCodec.decode(it.payload) }
        }

    private suspend fun changedFamilies(generation: Long): List<BackupRecordFamily?> =
        journalRows(generation).map { row ->
            BackupMutationCodec.decode(row.payload).let { mutation ->
                mutation.record?.family ?: mutation.deletedFamily
            }
        }

    private fun scheduleSnapshot(
        task: Task,
        reminder: Reminder? = null,
    ): WorkspaceSnapshot = OpenTasksFixtures.snapshot.copy(
        tasks = OpenTasksFixtures.snapshot.tasks.map {
            if (it.id == task.id) task else it
        },
        reminders = OpenTasksFixtures.snapshot.reminders
            .filterNot { it.taskId == task.id } + listOfNotNull(reminder),
    )

    private suspend fun executeRoomScheduleWithoutMutation(
        command: DomainCommand.SetTaskSchedule,
    ): CommandResult {
        val before = repository!!.currentWorkspace()
        val revisionBefore = before.tasks.single { it.id == command.taskId }.revision
        val generationBefore =
            database!!.backupStateDao().require("vault-primary").currentGeneration
        val rowsBefore = database!!.backupJournalDao()
            .between("vault-primary", 0, generationBefore)
        val activityBefore = before.activityEntries

        val result = repository!!.execute(command)

        val after = repository!!.currentWorkspace()
        val generationAfter =
            database!!.backupStateDao().require("vault-primary").currentGeneration
        val rowsAfter = database!!.backupJournalDao()
            .between("vault-primary", 0, generationAfter)
        assertEquals(before, after)
        assertEquals(revisionBefore, after.tasks.single { it.id == command.taskId }.revision)
        assertEquals(generationBefore, generationAfter)
        assertEquals(rowsBefore.size, rowsAfter.size)
        assertEquals(
            rowsBefore.map { it.copy(payload = ByteArray(0)) },
            rowsAfter.map { it.copy(payload = ByteArray(0)) },
        )
        rowsBefore.zip(rowsAfter).forEach { (expected, actual) ->
            assertArrayEquals(expected.payload, actual.payload)
        }
        assertEquals(activityBefore, after.activityEntries)
        return result
    }

    private fun openRepository(
        now: () -> Instant = Instant::now,
        zoneId: () -> ZoneId = ZoneId::systemDefault,
        seedSnapshot: WorkspaceSnapshot = OpenTasksFixtures.snapshot,
        appendBoundary: BackupJournalAppendBoundary = BackupJournalAppendBoundary { dao, entity ->
            dao.insert(entity)
        },
    ) {
        database = VaultDatabase.create(context, databaseName, databaseKey)
        repository = RoomVaultRepository(
            database = database!!,
            deviceId = DeviceId("instrumented-test-device"),
            now = now,
            zoneId = zoneId,
            seedSnapshot = seedSnapshot,
            backupJournalAppendBoundary = appendBoundary,
        )
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return indices
            .take(size - needle.size + 1)
            .any { offset ->
                needle.indices.all { index -> this[offset + index] == needle[index] }
            }
    }

    private fun ZonedMoment.localDateString(): String =
        instant.atZone(java.time.ZoneId.of(zoneId)).toLocalDate().toString()

    private class InjectedAppendFailure : RuntimeException()

    private companion object {
        val SCHEDULE_NOW: Instant = Instant.parse("2026-07-26T10:00:00Z")
        const val PERSISTED_TITLE = "persists-across-restart"
        const val UPDATED_TITLE = "encrypted-editor-update"
        const val PERSISTED_DESCRIPTION = "private editor details survive restart"
        const val PERSISTED_CHECKLIST_TEXT = "encrypted checklist survives restart"
        const val PERSISTED_TAG_NAME = "Encrypted tag"
        const val PERSISTED_PROJECT_NAME = "Quarterly filing"
        const val PERSISTED_PROJECT_SUMMARY = "project workbench details survive restart"
        const val LIFECYCLE_PROJECT_NAME = "Lifecycle persistence"
        const val LIFECYCLE_PROJECT_SUMMARY = "archived project details survive restart"
        const val LIFECYCLE_TASK_TITLE = "Retain this archived project task"
        const val PERSISTED_DUE_TITLE = "create-task-persists-due"
    }
}
