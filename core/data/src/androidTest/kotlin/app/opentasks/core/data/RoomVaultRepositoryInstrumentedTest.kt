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
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SemanticStatus
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
            DomainCommand.ChangeTaskStatus(taskId, OpenTasksFixtures.started),
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
        assertEquals(OpenTasksFixtures.started, restored.statusId)
        assertEquals(SemanticStatus.STARTED, restored.semanticStatus)
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
        val rule = RecurrenceRule(
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
                    due = due,
                    recurrence = rule,
                    estimate = original.estimate,
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

        val nextBeforeRestart = withTimeout(5_000) {
            repository!!.observeWorkspace()
                .map { snapshot ->
                    snapshot.tasks.firstOrNull {
                        it.recurrenceSeriesId == original.id && it.id != original.id
                    }
                }
                .filterNotNull()
                .first()
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
        assertEquals(3, database!!.syncOperationDao().pendingCount())

        repository!!.close()
        database!!.close()
        repository = null
        database = null

        openRepository()
        val restoredSnapshot = withTimeout(5_000) {
            repository!!.observeWorkspace().first { snapshot ->
                snapshot.tasks.any { it.id == nextBeforeRestart.id } &&
                    snapshot.tasks.firstOrNull { it.id == original.id }?.isCompleted == true
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
                    snapshot.tasks.firstOrNull { it.id == original.id }?.isCompleted == false
            }
        }
        assertTrue(
            database!!.workspaceDao().getTombstone(
                nextBeforeRestart.id.value,
                "task",
            ) != null,
        )
        assertEquals(5, database!!.syncOperationDao().pendingCount())

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
                    snapshot.tasks.firstOrNull { it.id == original.id }?.isCompleted == true
            }
        }
        assertEquals(7, database!!.syncOperationDao().pendingCount())
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
    fun trashRestoreAndPermanentDeleteSurviveEncryptedDatabaseRestart() = runBlocking {
        openRepository()
        val task = withTimeout(5_000) {
            repository!!.observeWorkspace()
                .map { snapshot -> snapshot.tasks.firstOrNull { it.checklist.isNotEmpty() } }
                .filterNotNull()
                .first()
        }
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
        assertEquals(3, database!!.syncOperationDao().pendingCount())

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
        assertEquals(4, database!!.syncOperationDao().pendingCount())
    }

    private fun openRepository() {
        database = VaultDatabase.create(context, databaseName, databaseKey)
        repository = RoomVaultRepository(
            database = database!!,
            deviceId = DeviceId("instrumented-test-device"),
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
