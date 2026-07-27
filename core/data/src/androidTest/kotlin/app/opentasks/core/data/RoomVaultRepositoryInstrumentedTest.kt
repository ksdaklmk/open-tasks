package app.opentasks.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.domain.WorkflowMoveDirection
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.ZonedMoment
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomVaultRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var databaseKey: ByteArray
    private var database: VaultDatabase? = null
    private var repository: RoomVaultRepository? = null

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
        val taskId = withTimeout(5_000) {
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
        assertEquals(3, database!!.syncOperationDao().pendingCount())

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
    fun activeTimerSurvivesEncryptedDatabaseAndProcessRestart() = runBlocking {
        val startedAt = Instant.parse("2026-07-27T03:00:00Z")
        val beforeRestart = Instant.parse("2026-07-27T03:30:00Z")
        val afterRestart = Instant.parse("2026-07-27T04:00:00Z")
        openRepository(now = { beforeRestart })
        repository!!.execute(DomainCommand.StopTimer)
        val task = withTimeout(5_000) {
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
        val running = withTimeout(5_000) {
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
        val restored = withTimeout(5_000) {
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
        val task = withTimeout(5_000) {
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
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.timeEntryConflicts.size == 1
            }
        }
        assertEquals(3, database!!.syncOperationDao().pendingCount())

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository(now = { changedAt.plusSeconds(60) })
        val restored = withTimeout(5_000) {
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
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.timeEntryConflicts.isEmpty() &&
                    snapshot.timeEntries.first { it.id == secondId }.note == "Review updated"
            }
        }
        repository!!.execute(checkNotNull(updated.undo))
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.timeEntryConflicts.size == 1 &&
                    snapshot.timeEntries.first { it.id == secondId }.note == "Review"
            }
        }

        val deleted = repository!!.execute(DomainCommand.DeleteTimeEntry(firstId))
            as CommandResult.Success
        repository!!.execute(checkNotNull(deleted.undo))
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.timeEntries.any { it.id == firstId }
            }
        }
        Unit
    }

    @Test
    fun reminderAndTaskUpdateAreAtomicAndSurviveEncryptedDatabaseRestart() = runBlocking {
        openRepository()
        val original = withTimeout(5_000) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { !it.isCompleted } }
                .filterNotNull()
                .first()
        }
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
                due = due,
                recurrence = original.recurrence,
                estimate = original.estimate,
                reminder = reminder,
            ),
        ) as CommandResult.Success
        val expectedReminder = reminder.copy(id = Reminder.primaryId(original.id))

        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == original.id }?.due == due &&
                    snapshot.reminders.singleOrNull { it.taskId == original.id } ==
                    expectedReminder
            }
        }
        assertEquals(2, database!!.syncOperationDao().pendingCount())

        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository()

        val restored = withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.reminders.singleOrNull { it.taskId == original.id } == expectedReminder
            }
        }
        assertEquals(due, restored.tasks.first { it.id == original.id }.due)

        assertTrue(repository!!.execute(checkNotNull(update.undo)) is CommandResult.Success)
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == original.id }?.due == original.due &&
                    snapshot.reminders.none { it.taskId == original.id }
            }
        }
        assertEquals(4, database!!.syncOperationDao().pendingCount())
        assertTrue(
            database!!.syncOperationDao().pending(10).any {
                it.objectId == expectedReminder.id && it.objectType == "reminder.delete"
            },
        )
    }

    @Test
    fun checklistAndTagRelationsSurviveEncryptedDatabaseRestart() = runBlocking {
        openRepository()
        val task = withTimeout(5_000) {
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

        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                val updated = snapshot.tasks.firstOrNull { it.id == task.id }
                    ?: return@first false
                updated.checklist.any { it.text == PERSISTED_CHECKLIST_TEXT } &&
                    snapshot.tags.any { tag ->
                        tag.name == PERSISTED_TAG_NAME && tag.id in updated.tagIds
                    }
            }
        }
        assertEquals(3, database!!.syncOperationDao().pendingCount())

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
        val restored = withTimeout(5_000) {
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
        val original = withTimeout(5_000) {
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

        val completedSnapshot = withTimeout(5_000) {
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
        assertEquals(5, database!!.syncOperationDao().pendingCount())

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository(testNow)
        val restoredSnapshot = withTimeout(5_000) {
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
        withTimeout(5_000) {
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
        assertEquals(8, database!!.syncOperationDao().pendingCount())
        assertTrue(
            database!!.syncOperationDao().pending(20).any {
                it.objectId == nextReminder.id && it.objectType == "reminder.delete"
            },
        )

        val completedAgain = repository!!.execute(
            DomainCommand.CompleteTask(
                taskId = original.id,
                completedAt = Instant.parse("2026-08-01T10:00:00Z"),
            ),
        ) as CommandResult.Success
        assertEquals("Task completed • next occurrence scheduled", completedAgain.message)
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.any { it.id == nextBeforeRestart.id } &&
                    snapshot.tasks.firstOrNull { it.id == original.id }?.isCompleted == true &&
                    snapshot.reminders.any { it == nextReminder }
            }
        }
        assertEquals(11, database!!.syncOperationDao().pendingCount())
    }

    @Test
    fun repeatedRecurringCompletionAcrossRestartCreatesExactlyOneOccurrence() = runBlocking {
        openRepository()
        val original = withTimeout(5_000) {
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
        val generated = withTimeout(5_000) {
            repository!!.observeWorkspace()
                .map { snapshot ->
                    snapshot.tasks.singleOrNull {
                        it.recurrenceSeriesId == original.id && it.id != original.id
                    }
                }
                .filterNotNull()
                .first()
        }
        assertEquals(3, database!!.syncOperationDao().pendingCount())

        repository!!.execute(
            DomainCommand.CompleteTask(
                taskId = original.id,
                completedAt = Instant.parse("2026-07-31T10:00:01Z"),
            ),
        )
        assertEquals(3, database!!.syncOperationDao().pendingCount())

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
        val afterRedelivery = withTimeout(5_000) {
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
        assertEquals(3, database!!.syncOperationDao().pendingCount())

        assertTrue(repository!!.execute(checkNotNull(firstCompletion.undo)) is CommandResult.Success)
        withTimeout(5_000) {
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
        val original = withTimeout(5_000) {
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
        val occurrence = withTimeout(5_000) {
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
                due = occurrence.due,
                recurrence = RecurrenceRule(RecurrenceFrequency.WEEKLY),
                estimate = occurrence.estimate,
            ),
        ) as CommandResult.Success
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == occurrence.id }
                    ?.recurrenceSeriesId == occurrence.id
            }
        }

        assertTrue(repository!!.execute(checkNotNull(ruleChange.undo)) is CommandResult.Success)
        val restored = withTimeout(5_000) {
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
        val persisted = withTimeout(5_000) {
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
        val captured = withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.templates.any { it.id == templateId }
            }.templates.single { it.id == templateId }
        }
        assertTrue(
            database!!.syncOperationDao().pending(100).any {
                it.objectId == templateId.value && it.objectType == "template.upsert"
            },
        )

        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository(now = { Instant.parse("2026-07-27T06:01:00Z") })
        withTimeout(5_000) {
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
        val instantiated = withTimeout(5_000) {
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
        val operations = database!!.syncOperationDao().pending(1_000)
        assertTrue(
            operations.any {
                it.objectId == projectId.value && it.objectType == "project.template_create"
            },
        )
        assertEquals(
            captured.tasks.size,
            operations.count { it.objectType == "task.template_create" },
        )

        repository!!.execute(checkNotNull(capture.undo))
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { it.templates.isEmpty() }
        }
        repository!!.execute(DomainCommand.RestoreTemplate(captured))
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { it.templates.singleOrNull()?.id == templateId }
        }
        Unit
    }

    @Test
    fun trashRestoreAndPermanentDeleteSurviveEncryptedDatabaseRestart() = runBlocking {
        openRepository()
        val task = withTimeout(5_000) {
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
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == task.id }?.deletedAt == deletedAt
            }
        }

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository()
        val trashed = withTimeout(5_000) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.id == task.id } }
                .filterNotNull()
                .first { restored -> restored.deletedAt == deletedAt }
        }
        assertTrue(trashed.checklist.isNotEmpty())

        assertTrue(repository!!.execute(DomainCommand.RestoreTask(task.id)) is CommandResult.Success)
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == task.id }?.deletedAt == null
            }
        }

        repository!!.execute(DomainCommand.DeleteTask(task.id, deletedAt))
        assertTrue(
            repository!!.execute(DomainCommand.PermanentlyDeleteTask(task.id)) is
                CommandResult.Success,
        )
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.none { it.id == task.id }
            }
        }
        assertTrue(
            database!!.workspaceDao().getTombstone(task.id.value, "task") != null,
        )
        assertTrue(
            database!!.syncOperationDao().pending(20).any {
                it.objectId == reminder.id && it.objectType == "reminder.delete"
            },
        )

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository()
        withTimeout(5_000) {
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
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.projects.firstOrNull { it.id == original.id }?.name ==
                    PERSISTED_PROJECT_NAME
            }
        }
        assertEquals(1, database!!.syncOperationDao().pendingCount())

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        val encryptedBytes = context.getDatabasePath(databaseName).readBytes()
        assertFalse(encryptedBytes.containsSubsequence(PERSISTED_PROJECT_SUMMARY.toByteArray()))

        openRepository()
        val restored = withTimeout(5_000) {
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
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.workflowStatuses.firstOrNull { it.id == customId }?.name ==
                    "Review queue" &&
                    snapshot.workflowStatuses
                        .firstOrNull { it.id == OpenTasksFixtures.planned }
                        ?.archivedAt != null
            }
        }
        assertEquals(5, database!!.syncOperationDao().pendingCount())

        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository()

        val restored = withTimeout(5_000) {
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
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.workflowStatuses
                    .firstOrNull { it.id == OpenTasksFixtures.planned }
                    ?.archivedAt == null
            }
        }
        assertEquals(6, database!!.syncOperationDao().pendingCount())
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
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.projects.firstOrNull { it.id == projectId }?.archivedAt == archivedAt &&
                    snapshot.tasks.any {
                        it.projectId == projectId && it.title == LIFECYCLE_TASK_TITLE
                    } &&
                    snapshot.home.projects.none { it.id == projectId }
            }
        }
        assertEquals(8, database!!.syncOperationDao().pendingCount())

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository()
        val archivedSnapshot = withTimeout(5_000) {
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
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.projects.firstOrNull { it.id == projectId }?.archivedAt == null &&
                    snapshot.home.projects.any { it.id == projectId }
            }
        }
        assertEquals(9, database!!.syncOperationDao().pendingCount())
    }

    @Test
    fun milestoneLifecycleMembershipUndoAndOutboxSurviveRestart() = runBlocking {
        openRepository(now = { Instant.parse("2026-07-27T04:00:00Z") })
        val project = OpenTasksFixtures.studioProject
        val task = withTimeout(5_000) {
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
        assertEquals(3, database!!.syncOperationDao().pendingCount())

        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository(now = { Instant.parse("2026-07-27T04:01:00Z") })

        val restored = withTimeout(5_000) {
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
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.milestones.any { it.id == milestoneId } &&
                    snapshot.tasks.any {
                        it.id == task.id && it.milestoneId == milestoneId
                    }
            }
        }
        assertEquals(7, database!!.syncOperationDao().pendingCount())
    }

    @Test
    fun dependencyLifecycleCycleGuardResolutionAndOutboxSurviveRestart() = runBlocking {
        openRepository(now = { Instant.parse("2026-07-27T05:00:00Z") })
        val candidates = withTimeout(5_000) {
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
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == first.id }?.blockedBy == setOf(second.id)
            }
        }
        val dependencyOperations = database!!.syncOperationDao().pending(100)
            .filter { it.objectType == "task.dependencies.update" }
        assertEquals(2, dependencyOperations.size)
        assertTrue(
            dependencyOperations.all {
                it.encryptedPayload.containsSubsequence("v5\u0000".toByteArray())
            },
        )

        repository!!.close()
        database!!.close()
        repository = null
        database = null
        openRepository(now = { Instant.parse("2026-07-27T05:01:00Z") })

        withTimeout(5_000) {
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
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == first.id }?.let { observed ->
                    observed.dependencyIds == setOf(second.id) && observed.blockedBy.isEmpty()
                } == true
            }
        }

        repository!!.execute(checkNotNull(completion.undo))
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == first.id }?.blockedBy == setOf(second.id)
            }
        }
        repository!!.execute(checkNotNull(firstLink.undo))
        withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.firstOrNull { it.id == first.id }?.let { observed ->
                    observed.dependencyIds.isEmpty() && observed.blockedBy.isEmpty()
                } == true
            }
        }
        Unit
    }

    private fun openRepository(
        now: () -> Instant = { Instant.now() },
    ) {
        database = VaultDatabase.create(context, databaseName, databaseKey)
        repository = RoomVaultRepository(
            database = database!!,
            deviceId = DeviceId("instrumented-test-device"),
            now = now,
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

    private companion object {
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
    }
}
