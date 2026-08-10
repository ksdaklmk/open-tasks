package app.opentasks

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.TaskArrangement
import app.opentasks.core.model.TaskGroupKey
import app.opentasks.core.model.TaskSortKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewArrangementStoreInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        context.deleteSharedPreferences(PREFS_NAME)
    }

    @Test
    fun arrangementsRoundTripUsingOnlyArrangementKeysAndValues() {
        val first = ProjectId("first-project")
        val second = ProjectId("second-project")
        val taskTitle = "never persist this task title"
        val store = ViewArrangementStore(prefs)

        store.saveTasks(TaskArrangement(TaskSortKey.TITLE, TaskGroupKey.PRIORITY))
        store.saveWorkbench(first, TaskArrangement(TaskSortKey.UPDATED, TaskGroupKey.DUE_BUCKET))
        store.saveWorkbench(second, TaskArrangement(TaskSortKey.DUE))
        store.saveBoardSort(first, TaskSortKey.TITLE)
        store.saveBoardSort(second, TaskSortKey.DUE)

        assertEquals("TITLE", prefs.getString("tasks_sort", null))
        assertEquals("PRIORITY", prefs.getString("tasks_group", null))
        assertEquals("UPDATED", prefs.getString("workbench_sort:${first.value}", null))
        assertEquals("DUE_BUCKET", prefs.getString("workbench_group:${first.value}", null))
        assertEquals("DUE", prefs.getString("workbench_sort:${second.value}", null))
        assertFalse(prefs.contains("workbench_group:${second.value}"))
        assertEquals("TITLE", prefs.getString("board_sort:${first.value}", null))
        assertEquals("DUE", prefs.getString("board_sort:${second.value}", null))
        assertEquals(
            setOf(
                "tasks_sort",
                "tasks_group",
                "workbench_sort:${first.value}",
                "workbench_group:${first.value}",
                "workbench_sort:${second.value}",
                "board_sort:${first.value}",
                "board_sort:${second.value}",
            ),
            prefs.all.keys,
        )
        assertEquals(
            setOf("TITLE", "PRIORITY", "UPDATED", "DUE_BUCKET", "DUE"),
            prefs.all.values.toSet(),
        )
        assertTrue(prefs.all.keys.none { taskTitle in it })
        assertTrue(prefs.all.values.none { it == taskTitle })

        assertEquals(store.state.value, ViewArrangementStore(prefs).state.value)
    }

    @Test
    fun unknownAndUnsupportedStoredSelectionsUseDefaults() {
        val projectId = ProjectId("stored-project")
        val unknownProjectId = ProjectId("unknown-project")
        prefs.edit()
            .putString("tasks_sort", "UNKNOWN")
            .putString("tasks_group", "UNKNOWN")
            .putString("workbench_sort:${projectId.value}", TaskSortKey.UPDATED.name)
            .putString("workbench_group:${projectId.value}", TaskGroupKey.PROJECT.name)
            .putString("board_sort:${projectId.value}", TaskSortKey.UPDATED.name)
            .putString("workbench_sort:${unknownProjectId.value}", "UNKNOWN")
            .putString("workbench_group:${unknownProjectId.value}", "UNKNOWN")
            .putString("board_sort:${unknownProjectId.value}", "UNKNOWN")
            .apply()

        val state = ViewArrangementStore(prefs).state.value

        assertEquals(TaskArrangement(), state.tasks)
        assertEquals(TaskArrangement(TaskSortKey.UPDATED), state.workbenchFor(projectId))
        assertEquals(TaskSortKey.PRIORITY, state.boardSortFor(projectId))
        assertEquals(TaskArrangement(), state.workbenchFor(unknownProjectId))
        assertEquals(TaskSortKey.PRIORITY, state.boardSortFor(unknownProjectId))
    }

    @Test
    fun invalidSelectionsNormaliseBeforeStateAndPreferences() {
        val projectId = ProjectId("project-normalisation")
        val store = ViewArrangementStore(prefs)

        store.saveWorkbench(
            projectId,
            TaskArrangement(TaskSortKey.UPDATED, TaskGroupKey.PROJECT),
        )
        store.saveBoardSort(projectId, TaskSortKey.UPDATED)

        assertEquals(
            TaskArrangement(TaskSortKey.UPDATED, groupBy = null),
            store.state.value.workbenchFor(projectId),
        )
        assertEquals(TaskSortKey.PRIORITY, store.state.value.boardSortFor(projectId))

        val reloaded = ViewArrangementStore(prefs).state.value
        assertEquals(store.state.value, reloaded)
        assertEquals("UPDATED", prefs.getString("workbench_sort:${projectId.value}", null))
        assertFalse(prefs.contains("workbench_group:${projectId.value}"))
        assertEquals("PRIORITY", prefs.getString("board_sort:${projectId.value}", null))
    }

    @Test
    fun blankProjectIdsAreRejectedWithoutMutation() {
        val store = ViewArrangementStore(prefs)
        val initialPreferences = prefs.all
        val initialState = store.state.value
        val blankProjectId = ProjectId("")

        assertThrows(IllegalArgumentException::class.java) {
            store.saveWorkbench(blankProjectId, TaskArrangement(TaskSortKey.TITLE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.saveBoardSort(blankProjectId, TaskSortKey.TITLE)
        }

        assertEquals(initialPreferences, prefs.all)
        assertEquals(initialState, store.state.value)
    }

    @Test
    fun loadRefreshesStateAndIgnoresEmptyOrUnrelatedProjectKeys() {
        val store = ViewArrangementStore(prefs)
        prefs.edit()
            .putString("tasks_sort", TaskSortKey.UPDATED.name)
            .putString("tasks_group", TaskGroupKey.PRIORITY.name)
            .putString("workbench_sort:", TaskSortKey.TITLE.name)
            .putString("not_a_view_key:project", TaskSortKey.DUE.name)
            .apply()

        val loaded = store.load()

        assertEquals(
            TaskArrangement(TaskSortKey.UPDATED, TaskGroupKey.PRIORITY),
            loaded.tasks,
        )
        assertTrue(loaded.workbenchByProject.isEmpty())
        assertEquals(loaded, store.state.value)
    }

    private companion object {
        const val PREFS_NAME = "view_arrangement_store_instrumented_test"
    }
}
