package app.opentasks.core.domain

import app.opentasks.core.model.Attachment
import app.opentasks.core.model.ChecklistItem
import app.opentasks.core.model.Note
import app.opentasks.core.model.Project
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.TagId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskSortKey
import app.opentasks.core.model.WorkspaceSnapshot
import java.time.Clock

const val MAX_WORKSPACE_SEARCH_RESULTS: Int = 50

private enum class SearchTier { EXACT, PREFIX, WORD_BOUNDARY, SUBSTRING }

private data class RankedResult(val result: SearchResult, val tier: SearchTier)

fun searchWorkspace(
    snapshot: WorkspaceSnapshot,
    query: SearchQuery,
    clock: Clock,
): List<SearchResult> {
    val needle = SearchNormalizer.normalize(query.text)
    val projectNames = snapshot.projects.associate { it.id to it.name }
    if (needle.isBlank()) {
        if (!query.hasV2Criterion()) return emptyList()
        return snapshot.tasks
            .filter { taskPasses(it, query, clock) }
            .sortedWith(taskComparator(query.sort ?: TaskSortKey.TITLE))
            .map { SearchResult.TaskResult(it, projectNames[it.projectId] ?: "Inbox") }
            .take(MAX_WORKSPACE_SEARCH_RESULTS)
    }

    val tagNames = snapshot.tags.associate { it.id to it.name }
    val notesByTask = snapshot.notes.filter { it.taskId != null }.groupBy { it.taskId }
        .mapValues { (_, values) ->
            values.sortedWith(compareBy<Note> { it.createdAt }.thenBy { it.id.value })
        }
    val notesByProject = snapshot.notes.filter { it.projectId != null }.groupBy { it.projectId }
        .mapValues { (_, values) ->
            values.sortedWith(compareBy<Note> { it.createdAt }.thenBy { it.id.value })
        }
    val attachmentsByTask = snapshot.attachments.filter { it.deletedAt == null }.groupBy { it.taskId }
        .mapValues { (_, values) ->
            values.sortedWith(
                compareBy<Attachment> { SearchNormalizer.normalize(it.displayName) }
                    .thenBy { it.id.value },
            )
        }

    fun taskHaystack(task: Task): String = SearchNormalizer.normalize(
        listOf(
            task.title,
            task.description,
            projectNames[task.projectId].orEmpty(),
            task.checklist.sortedWith(compareBy<ChecklistItem> { it.rank }.thenBy { it.id })
                .joinToString(" ", transform = ChecklistItem::text),
            task.tagIds.mapNotNull { id -> tagNames[id]?.let { id to it } }
                .sortedWith(
                    compareBy<Pair<TagId, String>> { SearchNormalizer.normalize(it.second) }
                        .thenBy { it.first.value },
                ).joinToString(" ") { it.second },
            notesByTask[task.id].orEmpty().joinToString(" ") { it.body },
            attachmentsByTask[task.id].orEmpty().joinToString(" ") { it.displayName },
        ).joinToString(" "),
    )

    fun projectHaystack(project: Project): String = SearchNormalizer.normalize(
        listOf(
            project.name,
            project.summary,
            notesByProject[project.id].orEmpty().joinToString(" ") { it.body },
        ).joinToString(" "),
    )

    val candidates = buildList {
        snapshot.tasks.filter { taskPasses(it, query, clock) }.forEach { task ->
            val title = SearchNormalizer.normalize(task.title)
            val tier = titleTier(title, needle)
                ?: SearchTier.SUBSTRING.takeIf { needle in taskHaystack(task) }
            if (tier != null) {
                add(
                    RankedResult(
                        SearchResult.TaskResult(task, projectNames[task.projectId] ?: "Inbox"),
                        tier,
                    ),
                )
            }
        }
        snapshot.projects.filter { it.archivedAt == null }.forEach { project ->
            val title = SearchNormalizer.normalize(project.name)
            val tier = titleTier(title, needle)
                ?: SearchTier.SUBSTRING.takeIf { needle in projectHaystack(project) }
            if (tier != null) {
                add(RankedResult(SearchResult.ProjectResult(project, "Project"), tier))
            }
        }
    }

    val ranked = candidates.sortedWith(
        compareBy<RankedResult> { it.tier.ordinal }
            .thenBy { if (it.result is SearchResult.TaskResult) 0 else 1 }
            .thenBy { SearchNormalizer.normalize(it.result.title) }
            .thenBy {
                when (val result = it.result) {
                    is SearchResult.TaskResult -> result.task.id.value
                    is SearchResult.ProjectResult -> result.project.id.value
                }
            },
    )
    val survivors = ranked.take(MAX_WORKSPACE_SEARCH_RESULTS)
    val sort = query.sort ?: return survivors.map(RankedResult::result)
    val tasks = survivors.mapNotNull { it.result as? SearchResult.TaskResult }
        .sortedWith { first, second ->
            taskComparator(sort).compare(first.task, second.task)
        }
    val projects = survivors.mapNotNull { it.result as? SearchResult.ProjectResult }
    return tasks + projects
}

private fun titleTier(title: String, needle: String): SearchTier? = when {
    title == needle -> SearchTier.EXACT
    title.startsWith(needle) -> SearchTier.PREFIX
    title.indices.any { index ->
        (index == 0 || !title[index - 1].isLetterOrDigit()) &&
            title.startsWith(needle, index)
    } -> SearchTier.WORD_BOUNDARY
    needle in title -> SearchTier.SUBSTRING
    else -> null
}

private fun SearchQuery.hasV2Criterion(): Boolean =
    dueBuckets.isNotEmpty() || priorities.isNotEmpty() ||
        statuses.isNotEmpty() || sort != null

private fun taskPasses(task: Task, query: SearchQuery, clock: Clock): Boolean =
    (query.includeTrash || task.deletedAt == null) &&
        (query.includeCompleted || !task.isCompleted) &&
        (query.projectIds.isEmpty() || task.projectId in query.projectIds) &&
        (query.tagIds.isEmpty() || task.tagIds.any(query.tagIds::contains)) &&
        (query.dueBuckets.isEmpty() || classifyDueBucket(task.due, clock) in query.dueBuckets) &&
        (query.priorities.isEmpty() || task.priority in query.priorities) &&
        (query.statuses.isEmpty() || task.semanticStatus in query.statuses)
