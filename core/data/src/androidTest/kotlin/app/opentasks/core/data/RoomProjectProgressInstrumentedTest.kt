package app.opentasks.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Task
import app.opentasks.core.model.WorkspaceSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Twin of `InMemoryProjectProgressTest`: the workspace snapshot restates
 * project task counts, while the stored columns stay as written.
 */
@RunWith(AndroidJUnit4::class)
class RoomProjectProgressInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var databaseKey: ByteArray
    private var database: VaultDatabase? = null
    private var repository: RoomVaultRepository? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "vault-progress-${UUID.randomUUID()}.db"
        databaseKey = ByteArray(32) { index -> (index + 1).toByte() }
        database = VaultDatabase.create(context, databaseName, databaseKey)
        repository = RoomVaultRepository(
            database = database!!,
            deviceId = DeviceId("instrumented-test-device"),
            now = { FIXED_NOW },
            seedSnapshot = EMPTY_SNAPSHOT,
        )
    }

    @After
    fun tearDown() {
        repository?.close()
        database?.close()
        context.deleteDatabase(databaseName)
        databaseKey.fill(0)
    }

    @Test
    fun projectReportsCompletedOverTotalTasksThroughTheWorkspaceSnapshot() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            repository!!.execute(DomainCommand.CreateProject(PROJECT_ID, "Plan the week"))
            repeat(3) { index ->
                repository!!.execute(DomainCommand.CreateTask("Task $index", PROJECT_ID))
            }
            val tasks = repository!!.observeWorkspace().first { it.tasks.size == 3 }.tasks

            repository!!.execute(
                DomainCommand.CompleteTask(tasks.first().id, completedAt = FIXED_NOW),
            )

            val project = repository!!.observeWorkspace()
                .first { snapshot -> snapshot.tasks.any(Task::isCompleted) }
                .project()
            assertEquals(1, project.completedTasks)
            assertEquals(3, project.totalTasks)
            assertEquals(1f / 3f, project.progress, 0.0001f)
            assertEquals(project, repository!!.observeHome().first().projects.single())
        }
    }

    @Test
    fun deletedTasksLeaveTheProjectCount() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            repository!!.execute(DomainCommand.CreateProject(PROJECT_ID, "Plan the week"))
            repository!!.execute(DomainCommand.CreateTask("Kept", PROJECT_ID))
            repository!!.execute(DomainCommand.CreateTask("Dropped", PROJECT_ID))
            val dropped = repository!!.observeWorkspace()
                .first { it.tasks.size == 2 }
                .tasks
                .single { it.title == "Dropped" }

            repository!!.execute(DomainCommand.DeleteTask(dropped.id, deletedAt = FIXED_NOW))

            val project = repository!!.observeWorkspace()
                .first { snapshot -> snapshot.tasks.count { it.deletedAt == null } == 1 }
                .project()
            assertEquals(1, project.totalTasks)
            assertEquals(0, project.completedTasks)
        }
    }

    /**
     * The counts are a read projection only. The guards, undo payloads and
     * backup records all read the stored columns through the DAO, so those
     * columns must stay exactly as the create command wrote them.
     */
    @Test
    fun storedProjectRowKeepsTheColumnsTheCreateCommandWrote() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            repository!!.execute(DomainCommand.CreateProject(PROJECT_ID, "Plan the week"))
            repository!!.execute(DomainCommand.CreateTask("Only", PROJECT_ID))
            val project = repository!!.observeWorkspace().first { it.tasks.size == 1 }.project()
            assertEquals(1, project.totalTasks)

            val stored = database!!.workspaceDao().getProjectById(PROJECT_ID.value)
            assertEquals(0, stored!!.completedTasks)
            assertEquals(0, stored.totalTasks)
        }
    }

    private fun WorkspaceSnapshot.project(): Project = projects.single { it.id == PROJECT_ID }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        val FIXED_NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
        val PROJECT_ID = ProjectId("project-progress")
        val EMPTY_SNAPSHOT = WorkspaceSnapshot(
            home = HomeSnapshot(
                today = LocalDate.of(2026, 9, 4),
                focusTasks = emptyList(),
                upcomingTasks = emptyList(),
                projects = emptyList(),
                activeTimer = null,
                overdueCount = 0,
            ),
            tasks = emptyList(),
            projects = emptyList(),
            workflowStatuses = emptyList(),
            milestones = emptyList(),
            tags = emptyList(),
        )
    }
}
