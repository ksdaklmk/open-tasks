package app.opentasks

import android.content.SharedPreferences
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.TaskArrangement
import app.opentasks.core.model.TaskGroupKey
import app.opentasks.core.model.TaskSortKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ViewArrangementState(
    val tasks: TaskArrangement = TaskArrangement(),
    val workbenchByProject: Map<ProjectId, TaskArrangement> = emptyMap(),
    val boardSortByProject: Map<ProjectId, TaskSortKey> = emptyMap(),
) {
    fun workbenchFor(projectId: ProjectId): TaskArrangement =
        workbenchByProject[projectId] ?: TaskArrangement()

    fun boardSortFor(projectId: ProjectId): TaskSortKey =
        boardSortByProject[projectId] ?: TaskSortKey.PRIORITY
}

class ViewArrangementStore(private val prefs: SharedPreferences) {
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<ViewArrangementState> = mutableState.asStateFlow()

    fun load(): ViewArrangementState = read().also { mutableState.value = it }

    fun saveTasks(arrangement: TaskArrangement) {
        prefs.edit()
            .putString(TASKS_SORT, arrangement.sort.name)
            .applyGroup(TASKS_GROUP, arrangement.groupBy)
            .apply()
        mutableState.update { it.copy(tasks = arrangement) }
    }

    fun saveWorkbench(projectId: ProjectId, arrangement: TaskArrangement) {
        require(projectId.value.isNotBlank())
        val normalised = arrangement.copy(
            groupBy = arrangement.groupBy?.takeIf {
                it == TaskGroupKey.DUE_BUCKET || it == TaskGroupKey.PRIORITY
            },
        )
        prefs.edit()
            .putString("$WORKBENCH_SORT${projectId.value}", normalised.sort.name)
            .applyGroup("$WORKBENCH_GROUP${projectId.value}", normalised.groupBy)
            .apply()
        mutableState.update {
            it.copy(workbenchByProject = it.workbenchByProject + (projectId to normalised))
        }
    }

    fun saveBoardSort(projectId: ProjectId, sort: TaskSortKey) {
        require(projectId.value.isNotBlank())
        val normalised = sort.takeIf {
            it == TaskSortKey.PRIORITY || it == TaskSortKey.DUE || it == TaskSortKey.TITLE
        } ?: TaskSortKey.PRIORITY
        prefs.edit().putString("$BOARD_SORT${projectId.value}", normalised.name).apply()
        mutableState.update {
            it.copy(boardSortByProject = it.boardSortByProject + (projectId to normalised))
        }
    }

    private fun read(): ViewArrangementState {
        val values = prefs.all
        val tasks = TaskArrangement(
            sort = TaskSortKey.entries.firstOrNull {
                it.name == values[TASKS_SORT] as? String
            } ?: TaskSortKey.DUE,
            groupBy = TaskGroupKey.entries.firstOrNull {
                it.name == values[TASKS_GROUP] as? String
            },
        )
        val workbench = values.entries.mapNotNull { (key, value) ->
            key.removePrefix(WORKBENCH_SORT).takeIf {
                key.startsWith(WORKBENCH_SORT) && it.isNotEmpty()
            }?.let { projectValue ->
                val sort = TaskSortKey.entries.firstOrNull { it.name == value as? String }
                    ?: TaskSortKey.DUE
                val group = TaskGroupKey.entries.firstOrNull {
                    it.name == values["$WORKBENCH_GROUP$projectValue"] as? String
                }?.takeIf { it == TaskGroupKey.DUE_BUCKET || it == TaskGroupKey.PRIORITY }
                ProjectId(projectValue) to TaskArrangement(sort, group)
            }
        }.toMap()
        val boardSort = values.entries.mapNotNull { (key, value) ->
            key.removePrefix(BOARD_SORT).takeIf {
                key.startsWith(BOARD_SORT) && it.isNotEmpty()
            }?.let { projectValue ->
                val sort = TaskSortKey.entries.firstOrNull { it.name == value as? String }
                    ?.takeIf {
                        it == TaskSortKey.PRIORITY || it == TaskSortKey.DUE || it == TaskSortKey.TITLE
                    } ?: TaskSortKey.PRIORITY
                ProjectId(projectValue) to sort
            }
        }.toMap()
        return ViewArrangementState(tasks, workbench, boardSort)
    }

    private fun SharedPreferences.Editor.applyGroup(
        key: String,
        group: TaskGroupKey?,
    ): SharedPreferences.Editor = if (group == null) remove(key) else putString(key, group.name)

    private companion object {
        const val TASKS_SORT = "tasks_sort"
        const val TASKS_GROUP = "tasks_group"
        const val WORKBENCH_SORT = "workbench_sort:"
        const val WORKBENCH_GROUP = "workbench_group:"
        const val BOARD_SORT = "board_sort:"
    }
}
