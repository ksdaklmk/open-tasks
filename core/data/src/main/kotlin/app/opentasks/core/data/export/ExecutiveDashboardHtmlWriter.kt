@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.opentasks.core.data.export

import app.opentasks.core.domain.InsightsEngine
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.OverdueBand
import app.opentasks.core.model.Project
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkspaceSnapshot
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

data class ExecutiveDashboardRequest(
    val workspace: WorkspaceSnapshot,
    val selection: InsightsSelection,
    val now: Instant,
    val zoneId: ZoneId,
    val includeTaskDetails: Boolean,
)

class ExecutiveDashboardHtmlWriter internal constructor(
    private val insightsEngine: InsightsEngine,
    private val maximumBytes: Long,
) {
    constructor(insightsEngine: InsightsEngine) : this(insightsEngine, MAX_DASHBOARD_BYTES)

    fun write(
        request: ExecutiveDashboardRequest,
        destination: OutputStream,
    ): Long {
        val dashboard = buildDashboard(request)
        val bounded = DashboardByteLimitOutputStream(destination, maximumBytes)
        val csp = "default-src 'none'; connect-src 'none'; img-src 'none'; object-src 'none'; " +
            "base-uri 'none'; form-action 'none'; style-src 'sha256-$DASHBOARD_CSS_HASH'; " +
            "script-src 'sha256-$DASHBOARD_JAVASCRIPT_HASH'"

        bounded.appendUtf8(
            "<!doctype html>\n" +
                "<html lang=\"en-GB\">\n" +
                "<head>\n" +
                "<meta charset=\"utf-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                "<meta http-equiv=\"Content-Security-Policy\" content=\"$csp\">\n" +
                "<title>Open Tasks executive dashboard</title>\n" +
                "<style>$DASHBOARD_CSS</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<a class=\"skip-link\" href=\"#main-content\">Skip to report</a>\n" +
                "<header class=\"report-header\">\n" +
                "<p class=\"eyebrow\">Open Tasks / frozen executive report</p>\n" +
                "<h1>Executive dashboard</h1>\n" +
                "<p>Snapshot frozen at ${request.now} · ${request.zoneId.id}</p>\n" +
                "</header>\n",
        )
        appendNoScriptSummary(bounded, dashboard)
        bounded.appendUtf8(DASHBOARD_BODY)
        bounded.appendUtf8("<script id=\"dashboard-data\" type=\"application/json\">")
        val jsonDestination = ScriptSafeJsonOutputStream(bounded)
        DASHBOARD_JSON.encodeToStream(ExecutiveDashboardDto.serializer(), dashboard, jsonDestination)
        jsonDestination.finish()
        bounded.appendUtf8(
            "</script>\n" +
                "<script id=\"dashboard-app\">$DASHBOARD_JAVASCRIPT</script>\n" +
                "</body>\n" +
                "</html>\n",
        )
        bounded.flush()
        return bounded.byteCount
    }

    private fun buildDashboard(request: ExecutiveDashboardRequest): ExecutiveDashboardDto {
        val workspace = request.workspace
        val selection = request.selection
        val insights = insightsEngine.calculate(
            workspace = workspace,
            selection = selection,
            now = request.now,
            zoneId = request.zoneId,
        )
        val selectedTasks = workspace.tasks
            .asSequence()
            .filter { it.deletedAt == null }
            .filter { selection.projectIds.isEmpty() || it.projectId in selection.projectIds }
            .filter { selection.tagIds.isEmpty() || it.tagIds.any(selection.tagIds::contains) }
            .sortedWith(compareBy<Task> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id.value })
            .toList()
        val selectedProjectIds = selectedTasks.mapNotNull(Task::projectId).toSet()
        val visibleProjects = workspace.projects
            .asSequence()
            .filter { it.archivedAt == null }
            .filter { selection.projectIds.isEmpty() || it.id in selection.projectIds }
            .filter { selection.tagIds.isEmpty() || it.id in selectedProjectIds }
            .sortedWith(compareBy<Project> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id.value })
            .toList()
        val projectsById = workspace.projects.associateBy(Project::id)
        val taskTitles = workspace.tasks.associate { it.id to it.title }
        val tasksByProject = selectedTasks.groupBy(Task::projectId)
        val actualSeconds = includedActualSecondsByTask(request, insights)
        val projects = visibleProjects.map { project ->
            val tasks = tasksByProject[project.id].orEmpty()
            PortfolioProjectDto(
                name = project.name,
                health = project.status.name,
                dueDate = project.dueDate?.toString(),
                completedTasks = tasks.count { it.semanticStatus == SemanticStatus.COMPLETED },
                totalTasks = tasks.size,
                overdueTasks = tasks.count { task -> task.isOverdue(request.now) },
                blockedTasks = tasks.count(Task::isBlocked),
            )
        }
        val visibleProjectIds = visibleProjects.map(Project::id).toSet()
        val selectedMilestoneIds = workspace.milestones
            .asSequence()
            .filter { it.projectId in visibleProjectIds }
            .map { it.id }
            .toSet()
        val milestones = insights.milestoneHealth
            .asSequence()
            .filter { it.milestoneId in selectedMilestoneIds }
            .map { row ->
                MilestoneDto(
                    name = row.displayName,
                    project = row.projectName,
                    dueAt = row.dueAt?.toString(),
                    health = row.projectHealth.name,
                    completedTasks = row.completedTasks,
                    totalTasks = row.totalTasks,
                    overdueTasks = row.overdueTasks,
                )
            }
            .sortedWith(compareBy<MilestoneDto> { it.dueAt ?: "" }.thenBy { it.name })
            .toList()
        val blockers = tasksByProject
            .mapNotNull { (projectId, tasks) ->
                val blockedTasks = tasks.count(Task::isBlocked)
                val dependencyLinks = tasks.sumOf { (it.dependencyIds + it.blockedBy).size }
                if (blockedTasks == 0 && dependencyLinks == 0) return@mapNotNull null
                BlockerDto(
                    project = projectId?.let(projectsById::get)?.name ?: "Inbox",
                    blockedTasks = blockedTasks,
                    dependencyLinks = dependencyLinks,
                )
            }
            .sortedBy { it.project.lowercase(Locale.ROOT) }
        val details = if (request.includeTaskDetails) {
            selectedTasks.map { task ->
                TaskDetailDto(
                    title = task.title,
                    project = task.projectId?.let(projectsById::get)?.name ?: "Inbox",
                    status = task.semanticStatus.name,
                    priority = task.priority.name,
                    start = task.start?.instant?.toString(),
                    due = task.due?.instant?.toString(),
                    estimateSeconds = task.estimate?.seconds,
                    actualSeconds = actualSeconds[task.id] ?: 0L,
                    blockedBy = task.blockedBy.mapNotNull(taskTitles::get).sorted(),
                )
            }
        } else {
            emptyList()
        }

        return ExecutiveDashboardDto(
            generatedAt = request.now.toString(),
            zoneId = request.zoneId.id,
            reportingWindow = ReportingWindowDto(
                startDate = insights.interval.startInclusive.atZone(request.zoneId).toLocalDate().toString(),
                endDate = insights.interval.endExclusive
                    .minusNanos(1)
                    .atZone(request.zoneId)
                    .toLocalDate()
                    .toString(),
                dayCount = selection.range.dayCount,
            ),
            scope = ScopeDto(
                projects = selection.projectIds
                    .mapNotNull(projectsById::get)
                    .map(Project::name)
                    .sorted(),
                tags = selection.tagIds
                    .mapNotNull { id -> workspace.tags.firstOrNull { it.id == id }?.name }
                    .sorted(),
                conflictedTimeIncluded = selection.includeConflictedTime,
            ),
            summary = SummaryDto(
                projectCount = visibleProjects.size,
                taskCount = selectedTasks.size,
                activeTaskCount = selectedTasks.count { it.semanticStatus != SemanticStatus.COMPLETED },
                completedCurrent = insights.completed.current,
                completedPrevious = insights.completed.previous,
                overdueTaskCount = insights.overdue.size,
            ),
            projects = projects,
            statusBreakdown = SemanticStatus.entries.map { status ->
                StatusCountDto(status.name, selectedTasks.count { it.semanticStatus == status })
            },
            milestones = milestones,
            overdueAgeing = OverdueBand.entries.map { band ->
                AgeingBucketDto(
                    band = when (band) {
                        OverdueBand.ONE_TO_SEVEN_DAYS -> "1–7 days"
                        OverdueBand.EIGHT_TO_THIRTY_DAYS -> "8–30 days"
                        OverdueBand.THIRTY_ONE_DAYS_OR_MORE -> "31+ days"
                    },
                    taskCount = insights.overdue.count { it.band == band },
                )
            },
            completionTrend = insights.completionTrend.map { point ->
                CompletionPointDto(point.date.toString(), point.completed)
            },
            estimateActual = EstimateActualDto(
                estimatedSeconds = insights.estimateActual.estimated.seconds,
                actualIncludedSeconds = insights.estimateActual.actual.included.seconds,
                estimatedTaskCount = insights.estimateActual.estimatedTaskCount,
                unestimatedTaskCount = insights.estimateActual.unestimatedTaskCount,
                actualTaskCount = insights.estimateActual.actualTaskCount,
            ),
            projectTime = insights.projectTime.map { row ->
                AllocationDto(
                    label = row.displayName,
                    trustedSeconds = row.duration.trusted.seconds,
                    conflictedSeconds = row.duration.conflicted.seconds,
                    includedSeconds = row.duration.included.seconds,
                )
            },
            tagTime = insights.tagTime.map { row ->
                AllocationDto(
                    label = row.displayName,
                    trustedSeconds = row.duration.trusted.seconds,
                    conflictedSeconds = row.duration.conflicted.seconds,
                    includedSeconds = row.duration.included.seconds,
                )
            },
            blockers = blockers,
            quality = QualityDto(
                missingEstimateTaskCount = selectedTasks.count { it.estimate == null },
                missingDueDateTaskCount = selectedTasks.count { it.due == null },
                conflictedSeconds = insights.quality.recordedTime.conflicted.seconds,
                conflictedSecondsExcluded = if (selection.includeConflictedTime) {
                    0L
                } else {
                    insights.quality.recordedTime.conflicted.seconds
                },
                notTracked = NOT_TRACKED,
            ),
            taskDetails = details,
        )
    }

    private fun includedActualSecondsByTask(
        request: ExecutiveDashboardRequest,
        insights: InsightsSnapshot,
    ): Map<TaskId, Long> {
        if (!request.includeTaskDetails) return emptyMap()
        val conflicts = request.workspace.timeEntryConflicts
            .flatMap { listOf(it.firstEntryId, it.secondEntryId) }
            .toSet()
        val seconds = mutableMapOf<TaskId, Long>()
        request.workspace.timeEntries.forEach { entry ->
            val stoppedAt = entry.stoppedAt ?: return@forEach
            if (!request.selection.includeConflictedTime && entry.id in conflicts) return@forEach
            val start = maxOf(entry.startedAt, insights.interval.startInclusive)
            val end = minOf(stoppedAt, insights.interval.endExclusive)
            if (start.isBefore(end)) {
                seconds[entry.taskId] = (seconds[entry.taskId] ?: 0L) + Duration.between(start, end).seconds
            }
        }
        return seconds
    }

    private fun appendNoScriptSummary(
        output: OutputStream,
        dashboard: ExecutiveDashboardDto,
    ) {
        output.appendUtf8(
            "<noscript>\n" +
                "<section class=\"noscript-summary\" aria-labelledby=\"noscript-heading\">\n" +
                "<h2 id=\"noscript-heading\">Executive summary without JavaScript</h2>\n" +
                "<p>This frozen report remains readable offline. Interactive filters require JavaScript.</p>\n" +
                "<p><strong>Projects:</strong> ",
        )
        output.appendEscapedHtmlText(
            dashboard.scope.projects.ifEmpty { listOf("All projects") }.joinToString(", "),
        )
        output.appendUtf8("</p>\n<p><strong>Tags:</strong> ")
        output.appendEscapedHtmlText(
            dashboard.scope.tags.ifEmpty { listOf("All tags") }.joinToString(", "),
        )
        output.appendUtf8(
            "</p>\n" +
                "<p>${dashboard.summary.projectCount} projects · " +
                "${dashboard.summary.taskCount} tasks · " +
                "${dashboard.summary.overdueTaskCount} overdue · " +
                "${dashboard.summary.completedCurrent} completed in the reporting window.</p>\n",
        )
        if (dashboard.summary.taskCount == 0) {
            output.appendUtf8("<p>No active task data was available in this frozen snapshot.</p>\n")
        }
        output.appendUtf8("</section>\n</noscript>\n")
    }

    companion object {
        const val MAX_DASHBOARD_BYTES: Long = 10L * 1024 * 1024
    }
}

internal class DashboardByteLimitOutputStream(
    private val delegate: OutputStream,
    private val maximumBytes: Long,
) : OutputStream() {
    var byteCount: Long = 0
        private set

    init {
        require(maximumBytes > 0) { "Dashboard byte bound must be positive" }
    }

    override fun write(value: Int) {
        requireCapacity(1)
        delegate.write(value)
        byteCount++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "Invalid output range"
        }
        requireCapacity(length.toLong())
        delegate.write(bytes, offset, length)
        byteCount += length
    }

    override fun flush() = delegate.flush()

    private fun requireCapacity(additionalBytes: Long) {
        if (additionalBytes > maximumBytes - byteCount) {
            throw IOException(
                "Executive dashboard exceeds ${String.format(Locale.US, "%,d", maximumBytes)} bytes",
            )
        }
    }
}

private class ScriptSafeJsonOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {
    private val buffer = ByteArray(8 * 1024)
    private var buffered = 0
    private var utf8State = 0

    override fun write(value: Int) {
        accept(value and 0xff)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "Invalid JSON output range"
        }
        for (index in offset until offset + length) accept(bytes[index].toInt() and 0xff)
    }

    fun finish() {
        when (utf8State) {
            1 -> emit(0xe2)
            2 -> {
                emit(0xe2)
                emit(0x80)
            }
        }
        utf8State = 0
        flushBuffer()
    }

    private fun accept(value: Int) {
        when (utf8State) {
            1 -> if (value == 0x80) {
                utf8State = 2
            } else {
                utf8State = 0
                emit(0xe2)
                accept(value)
            }

            2 -> {
                utf8State = 0
                when (value) {
                    0xa8 -> emitAscii("\\u2028")
                    0xa9 -> emitAscii("\\u2029")
                    else -> {
                        emit(0xe2)
                        emit(0x80)
                        accept(value)
                    }
                }
            }

            else -> when (value) {
                '<'.code -> emitAscii("\\u003c")
                '>'.code -> emitAscii("\\u003e")
                '&'.code -> emitAscii("\\u0026")
                0xe2 -> utf8State = 1
                else -> emit(value)
            }
        }
    }

    private fun emitAscii(value: String) {
        value.forEach { emit(it.code) }
    }

    private fun emit(value: Int) {
        if (buffered == buffer.size) flushBuffer()
        buffer[buffered++] = value.toByte()
    }

    private fun flushBuffer() {
        if (buffered == 0) return
        delegate.write(buffer, 0, buffered)
        buffered = 0
    }
}

private fun OutputStream.appendUtf8(value: String) {
    write(value.toByteArray(UTF_8))
}

private fun OutputStream.appendEscapedHtmlText(value: String) {
    appendUtf8(buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    '\u2028' -> "&#8232;"
                    '\u2029' -> "&#8233;"
                    else -> character
                },
            )
        }
    })
}

private fun Task.isOverdue(now: Instant): Boolean =
    semanticStatus != SemanticStatus.COMPLETED && due?.instant?.isBefore(now) == true

@Serializable
private data class ExecutiveDashboardDto(
    val generatedAt: String,
    val zoneId: String,
    val reportingWindow: ReportingWindowDto,
    val scope: ScopeDto,
    val summary: SummaryDto,
    val projects: List<PortfolioProjectDto>,
    val statusBreakdown: List<StatusCountDto>,
    val milestones: List<MilestoneDto>,
    val overdueAgeing: List<AgeingBucketDto>,
    val completionTrend: List<CompletionPointDto>,
    val estimateActual: EstimateActualDto,
    val projectTime: List<AllocationDto>,
    val tagTime: List<AllocationDto>,
    val blockers: List<BlockerDto>,
    val quality: QualityDto,
    val taskDetails: List<TaskDetailDto>,
)

@Serializable
private data class ReportingWindowDto(
    val startDate: String,
    val endDate: String,
    val dayCount: Long,
)

@Serializable
private data class ScopeDto(
    val projects: List<String>,
    val tags: List<String>,
    val conflictedTimeIncluded: Boolean,
)

@Serializable
private data class SummaryDto(
    val projectCount: Int,
    val taskCount: Int,
    val activeTaskCount: Int,
    val completedCurrent: Long,
    val completedPrevious: Long,
    val overdueTaskCount: Int,
)

@Serializable
private data class PortfolioProjectDto(
    val name: String,
    val health: String,
    val dueDate: String?,
    val completedTasks: Int,
    val totalTasks: Int,
    val overdueTasks: Int,
    val blockedTasks: Int,
)

@Serializable
private data class StatusCountDto(val status: String, val taskCount: Int)

@Serializable
private data class MilestoneDto(
    val name: String,
    val project: String,
    val dueAt: String?,
    val health: String,
    val completedTasks: Long,
    val totalTasks: Long,
    val overdueTasks: Long,
)

@Serializable
private data class AgeingBucketDto(val band: String, val taskCount: Int)

@Serializable
private data class CompletionPointDto(val date: String, val completedTasks: Long)

@Serializable
private data class EstimateActualDto(
    val estimatedSeconds: Long,
    val actualIncludedSeconds: Long,
    val estimatedTaskCount: Long,
    val unestimatedTaskCount: Long,
    val actualTaskCount: Long,
)

@Serializable
private data class AllocationDto(
    val label: String,
    val trustedSeconds: Long,
    val conflictedSeconds: Long,
    val includedSeconds: Long,
)

@Serializable
private data class BlockerDto(
    val project: String,
    val blockedTasks: Int,
    val dependencyLinks: Int,
)

@Serializable
private data class QualityDto(
    val missingEstimateTaskCount: Int,
    val missingDueDateTaskCount: Int,
    val conflictedSeconds: Long,
    val conflictedSecondsExcluded: Long,
    val notTracked: List<String>,
)

@Serializable
private data class TaskDetailDto(
    val title: String,
    val project: String,
    val status: String,
    val priority: String,
    val start: String?,
    val due: String?,
    val estimateSeconds: Long?,
    val actualSeconds: Long,
    val blockedBy: List<String>,
)

private val DASHBOARD_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
}

private val NOT_TRACKED = listOf(
    "Budget",
    "Cost",
    "Team capacity",
    "Resource allocation",
    "Benefits",
)

private val DASHBOARD_CSS = """
    :root {
      color-scheme: light;
      --paper: #fbf8f4;
      --ink: #252321;
      --muted: #6a625e;
      --line: #ded5ce;
      --ember: #c64e2b;
      --pale-ember: #f7e9e3;
      font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      font-size: 100%;
    }
    * { box-sizing: border-box; }
    body { margin: 0; background: var(--paper); color: var(--ink); line-height: 1.55; }
    body, button, select { font: inherit; }
    .skip-link { position: absolute; left: 1rem; top: -5rem; padding: .75rem 1rem; background: var(--ink); color: white; z-index: 10; }
    .skip-link:focus { top: 1rem; }
    .report-header, main, .noscript-summary { width: min(76rem, calc(100% - 2rem)); margin-inline: auto; }
    .report-header { padding: 4rem 0 2rem; border-bottom: 1px solid var(--ink); }
    .eyebrow { color: var(--ember); font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }
    h1 { max-width: 18ch; margin: .3rem 0 1rem; font-size: clamp(2.2rem, 7vw, 5rem); line-height: .94; letter-spacing: -.045em; }
    h2 { margin: 0 0 1rem; font-size: clamp(1.35rem, 3vw, 2rem); letter-spacing: -.025em; }
    h3 { font-size: 1rem; }
    p { max-width: 74ch; }
    main { padding-bottom: 5rem; }
    section { padding: 2.25rem 0; border-bottom: 1px solid var(--line); }
    .noscript-summary { margin-top: 1rem; padding: 1rem; border: 2px solid var(--ember); }
    .definition, .muted { color: var(--muted); }
    .filters { display: flex; flex-wrap: wrap; gap: 1rem; align-items: end; padding: 1rem 0 1.5rem; }
    .field { display: grid; gap: .35rem; min-width: min(100%, 13rem); }
    label { font-weight: 700; }
    select { min-height: 3rem; border: 1px solid var(--ink); border-radius: .2rem; padding: .6rem 2.2rem .6rem .7rem; background: white; color: var(--ink); }
    :focus-visible { outline: 3px solid var(--ember); outline-offset: 3px; }
    .headline-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr)); margin: 1.5rem 0; border-top: 1px solid var(--line); }
    .headline-list div { padding: 1rem 1rem 1rem 0; border-bottom: 1px solid var(--line); }
    dt { color: var(--muted); font-size: .9rem; }
    dd { margin: .25rem 0 0; font-size: 1.45rem; font-variant-numeric: tabular-nums; font-weight: 750; }
    .table-wrap { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; font-variant-numeric: tabular-nums; }
    th, td { padding: .75rem .65rem; border-bottom: 1px solid var(--line); text-align: left; vertical-align: top; }
    th { color: var(--muted); font-size: .82rem; letter-spacing: .04em; text-transform: uppercase; }
    .status { display: inline-flex; align-items: center; gap: .45rem; font-weight: 700; }
    .status::before { content: ""; width: .65rem; height: .65rem; border: 2px solid currentColor; border-radius: 50%; }
    .status[data-state="AT_RISK"], .status[data-state="BLOCKED"] { color: #96381f; }
    .dot-run { display: inline-flex; gap: .22rem; min-width: 7rem; margin-right: .5rem; }
    .dot { width: .5rem; height: .5rem; border: 1px solid var(--muted); border-radius: 50%; }
    .dot.filled { border-color: var(--ember); background: var(--ember); }
    .chart-list, .plain-list { list-style: none; padding: 0; margin: 1rem 0; }
    .chart-list li { display: grid; grid-template-columns: minmax(7rem, 10rem) 1fr auto; gap: 1rem; align-items: center; padding: .55rem 0; border-bottom: 1px solid var(--line); }
    .number { font-variant-numeric: tabular-nums; font-weight: 750; }
    details { margin-top: 1rem; border-top: 1px solid var(--line); }
    summary { min-height: 3rem; display: flex; align-items: center; cursor: pointer; font-weight: 750; }
    .empty { padding: 1rem 0; color: var(--muted); font-style: italic; }
    .caveat { border-left: .25rem solid var(--ember); padding: .7rem 1rem; background: var(--pale-ember); }
    footer { width: min(76rem, calc(100% - 2rem)); margin: 0 auto; padding: 2rem 0 4rem; color: var(--muted); }
    @media (max-width: 44rem) {
      .report-header { padding-top: 2.5rem; }
      .filters, .field { display: grid; width: 100%; }
      .chart-list li { grid-template-columns: 1fr auto; }
      .chart-list .dot-run { grid-column: 1 / -1; }
    }
    @media (prefers-reduced-motion: reduce) {
      *, *::before, *::after { scroll-behavior: auto !important; animation-duration: .01ms !important; transition-duration: .01ms !important; }
    }
    @media print {
      :root { --paper: white; --ink: black; --muted: #444; --line: #bbb; }
      .skip-link, .filters { display: none !important; }
      .report-header, main, footer { width: 100%; }
      section, table, details { break-inside: avoid; }
      details > * { display: block !important; }
      body { font-size: 10pt; }
    }
""".trimIndent()

private val DASHBOARD_JAVASCRIPT = """
    "use strict";
    (() => {
      const dashboard = JSON.parse(document.getElementById("dashboard-data").textContent);
      const byId = (id) => document.getElementById(id);
      const create = (name, value) => {
        const node = document.createElement(name);
        if (value !== undefined && value !== null) node.textContent = String(value);
        return node;
      };
      const clear = (node) => node.replaceChildren();
      const pretty = (value) => String(value).toLowerCase().replaceAll("_", " ").replace(/^./, (letter) => letter.toUpperCase());
      const duration = (seconds) => {
        const totalMinutes = Math.floor(Number(seconds) / 60);
        const hours = Math.floor(totalMinutes / 60);
        const minutes = totalMinutes % 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
      };
      const emptyRows = (body, message, columns) => {
        clear(body);
        const tableRow = create("tr");
        const cell = create("td", message);
        cell.className = "empty";
        cell.colSpan = columns;
        tableRow.append(cell);
        body.append(tableRow);
      };
      const row = (body, values) => {
        const tableRow = create("tr");
        values.forEach((value) => tableRow.append(create("td", value)));
        body.append(tableRow);
      };
      const options = (select, values, allLabel) => {
        clear(select);
        const all = create("option", allLabel);
        all.value = "";
        select.append(all);
        values.forEach((value) => {
          const option = create("option", value.label);
          option.value = value.value;
          select.append(option);
        });
      };
      const dots = (ratio) => {
        const run = create("span");
        run.className = "dot-run";
        const filled = Math.round(Math.max(0, Math.min(1, ratio)) * 10);
        for (let index = 0; index < 10; index += 1) {
          const dot = create("span");
          dot.className = index < filled ? "dot filled" : "dot";
          run.append(dot);
        }
        return run;
      };
      const projectFilter = byId("project-filter");
      const statusFilter = byId("status-filter");
      const riskFilter = byId("risk-filter");
      options(projectFilter, dashboard.projects.map((project) => ({ value: project.name, label: project.name })), "All projects");
      options(statusFilter, dashboard.statusBreakdown.map((item) => ({ value: item.status, label: pretty(item.status) })), "All statuses");
      options(riskFilter, [
        { value: "ON_TRACK", label: "On track" },
        { value: "AT_RISK", label: "At risk" },
        { value: "BLOCKED", label: "Blocked" },
        { value: "COMPLETE", label: "Complete" },
        { value: "OVERDUE", label: "Overdue task" }
      ], "All risk states");

      const summary = byId("summary-values");
      [
        ["Projects in scope", dashboard.summary.projectCount],
        ["Tasks in scope", dashboard.summary.taskCount],
        ["Active tasks", dashboard.summary.activeTaskCount],
        ["Overdue tasks", dashboard.summary.overdueTaskCount],
        ["Completed this window", dashboard.summary.completedCurrent],
        ["Completed previous window", dashboard.summary.completedPrevious]
      ].forEach((item) => {
        const group = create("div");
        group.append(create("dt", item[0]), create("dd", item[1]));
        summary.append(group);
      });
      byId("window-label").textContent = dashboard.reportingWindow.startDate + " to " + dashboard.reportingWindow.endDate + " (" + dashboard.reportingWindow.dayCount + " days)";

      const taskRisk = (task) => {
        if (task.status === "COMPLETED") return "COMPLETE";
        if (task.blockedBy.length > 0 || task.status === "BLOCKED") return "BLOCKED";
        if (task.due && Date.parse(task.due) < Date.parse(dashboard.generatedAt)) return "OVERDUE";
        return "ON_TRACK";
      };
      const projectVisible = (project) => (!projectFilter.value || project.name === projectFilter.value) && (!riskFilter.value || project.health === riskFilter.value);
      const taskVisible = (task) => (!projectFilter.value || task.project === projectFilter.value) && (!statusFilter.value || task.status === statusFilter.value) && (!riskFilter.value || taskRisk(task) === riskFilter.value);

      const renderProjects = () => {
        const body = byId("portfolio-rows");
        clear(body);
        const projects = dashboard.projects.filter(projectVisible);
        if (projects.length === 0) return emptyRows(body, "No projects match the current filters.", 6);
        projects.forEach((project) => {
          const tr = create("tr");
          tr.append(create("td", project.name));
          const health = create("span", pretty(project.health));
          health.className = "status";
          health.dataset.state = project.health;
          const healthCell = create("td");
          healthCell.append(health);
          tr.append(healthCell);
          const progressCell = create("td");
          const ratio = project.totalTasks === 0 ? 0 : project.completedTasks / project.totalTasks;
          progressCell.append(dots(ratio), create("span", project.completedTasks + " / " + project.totalTasks));
          tr.append(progressCell, create("td", project.overdueTasks), create("td", project.blockedTasks), create("td", project.dueDate || "Not set"));
          body.append(tr);
        });
      };
      const renderStatuses = () => {
        const list = byId("status-list");
        clear(list);
        dashboard.statusBreakdown.filter((item) => !statusFilter.value || item.status === statusFilter.value).forEach((item) => {
          const li = create("li");
          li.append(create("span", pretty(item.status)), dots(dashboard.summary.taskCount === 0 ? 0 : item.taskCount / dashboard.summary.taskCount), create("span", item.taskCount));
          list.append(li);
        });
      };
      const renderMilestones = () => {
        const body = byId("milestone-rows");
        clear(body);
        const rows = dashboard.milestones.filter((item) => (!projectFilter.value || item.project === projectFilter.value) && (!riskFilter.value || item.health === riskFilter.value));
        if (rows.length === 0) return emptyRows(body, "No open milestones match the current filters.", 6);
        rows.forEach((item) => row(body, [item.name, item.project, pretty(item.health), item.dueAt || "Not set", item.completedTasks + " / " + item.totalTasks, item.overdueTasks]));
      };
      const renderAgeing = () => {
        const list = byId("ageing-list");
        clear(list);
        dashboard.overdueAgeing.forEach((item) => {
          const li = create("li");
          li.append(create("span", item.band), dots(dashboard.summary.overdueTaskCount === 0 ? 0 : item.taskCount / dashboard.summary.overdueTaskCount), create("span", item.taskCount));
          list.append(li);
        });
      };
      const renderTrend = () => {
        const list = byId("trend-list");
        clear(list);
        const maximum = Math.max(1, ...dashboard.completionTrend.map((point) => point.completedTasks));
        dashboard.completionTrend.forEach((point) => {
          const li = create("li");
          li.append(create("span", point.date), dots(point.completedTasks / maximum), create("span", point.completedTasks));
          list.append(li);
        });
      };
      const renderEstimate = () => {
        byId("estimated-time").textContent = duration(dashboard.estimateActual.estimatedSeconds);
        byId("actual-time").textContent = duration(dashboard.estimateActual.actualIncludedSeconds);
        byId("estimate-coverage").textContent = dashboard.estimateActual.estimatedTaskCount + " estimated / " + dashboard.estimateActual.unestimatedTaskCount + " unestimated completed tasks";
        byId("actual-coverage").textContent = dashboard.estimateActual.actualTaskCount + " completed tasks with included recorded time";
      };
      const renderAllocation = (id, values) => {
        const body = byId(id);
        clear(body);
        if (values.length === 0) return emptyRows(body, "No stopped time entries fell inside the reporting window.", 4);
        values.forEach((item) => row(body, [item.label, duration(item.includedSeconds), duration(item.trustedSeconds), duration(item.conflictedSeconds)]));
      };
      const renderBlockers = () => {
        const body = byId("blocker-rows");
        clear(body);
        const values = dashboard.blockers.filter((item) => !projectFilter.value || item.project === projectFilter.value);
        if (values.length === 0) return emptyRows(body, "No blocker or dependency exposure is recorded for this scope.", 3);
        values.forEach((item) => row(body, [item.project, item.blockedTasks, item.dependencyLinks]));
      };
      const renderQuality = () => {
        byId("missing-estimates").textContent = dashboard.quality.missingEstimateTaskCount;
        byId("missing-dates").textContent = dashboard.quality.missingDueDateTaskCount;
        byId("conflicted-time").textContent = duration(dashboard.quality.conflictedSeconds);
        byId("excluded-time").textContent = duration(dashboard.quality.conflictedSecondsExcluded);
        const list = byId("not-tracked-list");
        dashboard.quality.notTracked.forEach((label) => list.append(create("li", label)));
      };
      const renderDetails = () => {
        const body = byId("task-detail-rows");
        clear(body);
        const values = dashboard.taskDetails.filter(taskVisible);
        if (dashboard.taskDetails.length === 0) return emptyRows(body, "Task details were not included in this aggregate export.", 9);
        if (values.length === 0) return emptyRows(body, "No task details match the current filters.", 9);
        values.forEach((task) => row(body, [task.title, task.project, pretty(task.status), pretty(task.priority), task.start || "Not set", task.due || "Not set", task.estimateSeconds === null ? "Not set" : duration(task.estimateSeconds), duration(task.actualSeconds), task.blockedBy.length === 0 ? "None" : task.blockedBy.join(", ")]));
      };
      const renderFiltered = () => {
        renderProjects();
        renderStatuses();
        renderMilestones();
        renderBlockers();
        renderDetails();
      };
      [projectFilter, statusFilter, riskFilter].forEach((control) => control.addEventListener("change", renderFiltered));
      renderFiltered();
      renderAgeing();
      renderTrend();
      renderEstimate();
      renderAllocation("project-time-rows", dashboard.projectTime);
      renderAllocation("tag-time-rows", dashboard.tagTime);
      renderQuality();
    })();
""".trimIndent()

private val DASHBOARD_CSS_HASH = sha256Base64(DASHBOARD_CSS)
private val DASHBOARD_JAVASCRIPT_HASH = sha256Base64(DASHBOARD_JAVASCRIPT)

private fun sha256Base64(value: String): String = Base64.getEncoder().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)),
)

private val DASHBOARD_BODY = """
    <main id="main-content">
    <div class="filters" role="group" aria-label="Dashboard filters">
      <div class="field"><label for="project-filter">Project</label><select id="project-filter"></select></div>
      <div class="field"><label for="status-filter">Task status</label><select id="status-filter"></select></div>
      <div class="field"><label for="risk-filter">Risk</label><select id="risk-filter"></select></div>
    </div>
    <section id="executive-summary" aria-labelledby="executive-summary-heading">
      <h2 id="executive-summary-heading">1. Executive summary</h2>
      <p class="definition">Scope is the frozen project and tag selection. The reporting window is <span id="window-label"></span>. Completion compares tasks completed inside equal current and previous windows; overdue means an incomplete task due before generation.</p>
      <dl id="summary-values" class="headline-list"></dl>
    </section>
    <section id="portfolio-health" aria-labelledby="portfolio-health-heading">
      <h2 id="portfolio-health-heading">2. Portfolio health</h2>
      <p class="definition">Progress is completed tasks divided by all non-deleted tasks in each project inside the selected scope. Health is the recorded project health, shown with text and a marker.</p>
      <div class="table-wrap"><table><thead><tr><th>Project</th><th>Health</th><th>Progress</th><th>Overdue</th><th>Blocked</th><th>Due</th></tr></thead><tbody id="portfolio-rows"></tbody></table></div>
      <details><summary>Status distribution</summary><ul id="status-list" class="chart-list"></ul></details>
    </section>
    <section id="milestone-risk" aria-labelledby="milestone-risk-heading">
      <h2 id="milestone-risk-heading">3. Milestone risk</h2>
      <p class="definition">Open milestones show completed and total selected tasks, overdue selected tasks, project health, and the recorded due date.</p>
      <div class="table-wrap"><table><thead><tr><th>Milestone</th><th>Project</th><th>Health</th><th>Due</th><th>Completed / total</th><th>Overdue</th></tr></thead><tbody id="milestone-rows"></tbody></table></div>
    </section>
    <section id="overdue-ageing" aria-labelledby="overdue-ageing-heading">
      <h2 id="overdue-ageing-heading">4. Overdue ageing</h2>
      <p class="definition">Age is whole local calendar days between the due date and the frozen generation date. The denominator is every overdue task in scope.</p>
      <ul id="ageing-list" class="chart-list"></ul>
    </section>
    <section id="completion-trend" aria-labelledby="completion-trend-heading">
      <h2 id="completion-trend-heading">5. Completion trend and throughput</h2>
      <p class="definition">Daily throughput counts selected tasks whose completion timestamp falls on that local date inside the reporting window.</p>
      <div role="img" aria-label="Completion trend; daily completion counts are listed as text below"><ul id="trend-list" class="chart-list"></ul></div>
    </section>
    <section id="estimate-actual" aria-labelledby="estimate-actual-heading">
      <h2 id="estimate-actual-heading">6. Estimate versus actual recorded time</h2>
      <p class="definition">Estimate is the sum for completed tasks with an estimate in this window. Actual is included stopped time clipped to the window for those completed tasks.</p>
      <dl class="headline-list"><div><dt>Estimated</dt><dd id="estimated-time"></dd><p id="estimate-coverage" class="muted"></p></div><div><dt>Actual included</dt><dd id="actual-time"></dd><p id="actual-coverage" class="muted"></p></div></dl>
    </section>
    <section id="time-allocation" aria-labelledby="time-allocation-heading">
      <h2 id="time-allocation-heading">7. Time allocation by project and tag</h2>
      <p class="caveat">Tag totals overlap when one task has multiple tags; do not add tag rows to infer a portfolio total.</p>
      <h3>By project</h3><div class="table-wrap"><table><thead><tr><th>Project</th><th>Included</th><th>Trusted</th><th>Conflicted</th></tr></thead><tbody id="project-time-rows"></tbody></table></div>
      <details><summary>Time allocation by tag</summary><div class="table-wrap"><table><thead><tr><th>Tag</th><th>Included</th><th>Trusted</th><th>Conflicted</th></tr></thead><tbody id="tag-time-rows"></tbody></table></div></details>
    </section>
    <section id="blocker-exposure" aria-labelledby="blocker-exposure-heading">
      <h2 id="blocker-exposure-heading">8. Blockers and dependency exposure</h2>
      <p class="definition">Blocked counts tasks with blocked status or a recorded blocker. Dependency links count explicit dependency references; both use tasks in scope.</p>
      <div class="table-wrap"><table><thead><tr><th>Project</th><th>Blocked tasks</th><th>Dependency links</th></tr></thead><tbody id="blocker-rows"></tbody></table></div>
    </section>
    <section id="data-quality" aria-labelledby="data-quality-heading">
      <h2 id="data-quality-heading">9. Data quality and caveats</h2>
      <p class="definition">Missing fields reduce the coverage of schedule and estimate analysis. Conflicted time is reported separately and is included only when the frozen selection requests it.</p>
      <dl class="headline-list"><div><dt>Tasks missing estimates</dt><dd id="missing-estimates"></dd></div><div><dt>Tasks missing due dates</dt><dd id="missing-dates"></dd></div><div><dt>Conflicted time</dt><dd id="conflicted-time"></dd></div><div><dt>Conflicted time excluded</dt><dd id="excluded-time"></dd></div></dl>
      <details><summary>Not tracked in Open Tasks</summary><p>Budget, cost, team capacity, resource allocation, and benefits are not tracked in Open Tasks.</p><ul id="not-tracked-list" class="plain-list"></ul></details>
    </section>
    <section id="task-details" aria-labelledby="task-details-heading">
      <h2 id="task-details-heading">10. Task-detail appendix</h2>
      <p class="definition">Optional plaintext detail is limited to title, project, workflow status, priority, dates, estimate, included actual time, and blocker relationships.</p>
      <div class="table-wrap"><table><thead><tr><th>Task</th><th>Project</th><th>Status</th><th>Priority</th><th>Start</th><th>Due</th><th>Estimate</th><th>Actual</th><th>Blocked by</th></tr></thead><tbody id="task-detail-rows"></tbody></table></div>
    </section>
    </main>
    <footer>Generated offline by Open Tasks. This plaintext file is readable by anyone who receives it.</footer>
""".trimIndent() + "\n"
