package app.opentasks.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.OpenTasksColors
import app.opentasks.core.designsystem.ProjectProgressRow
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.designsystem.TaskRow
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import java.time.format.DateTimeFormatter
import java.time.Duration
import java.util.Locale

@Composable
fun HomeScreen(
    snapshot: HomeSnapshot,
    projectNames: Map<ProjectId, String>,
    onOpenSearch: () -> Unit,
    onPlanToday: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onCompleteTask: (Task) -> Unit,
    onOpenProject: (ProjectId) -> Unit,
    insightsSummary: InsightsSnapshot,
    onOpenInsights: () -> Unit,
    onToggleTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 12.dp,
            end = 16.dp,
            bottom = 112.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            HomeHeader(snapshot, onOpenSearch)
        }

        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader(
                title = "Today focus",
                supportingText = snapshot.overdueCount
                    .takeIf { it > 0 }
                    ?.let { count ->
                        "$count overdue ${if (count == 1) "item" else "items"}"
                    },
                action = {
                    TextButton(onClick = onPlanToday) {
                        Text("Plan")
                    }
                },
            )
        }

        items(snapshot.focusTasks, key = { it.id.value }) { task ->
            TaskRow(
                task = task,
                projectName = projectNames[task.projectId] ?: "Inbox",
                selected = false,
                onSelect = { onOpenTask(task.id) },
                onComplete = { onCompleteTask(task) },
            )
        }

        snapshot.activeTimer?.let { timer ->
            item {
                Spacer(Modifier.height(16.dp))
                ActiveTimer(
                    title = timer.taskTitle,
                    project = timer.projectName,
                    elapsed = buildString {
                        append("%02d".format(timer.elapsed.toHours()))
                        append(':')
                        append("%02d".format(timer.elapsed.toMinutesPart()))
                        append(':')
                        append("%02d".format(timer.elapsed.toSecondsPart()))
                    },
                    onToggle = onToggleTimer,
                )
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
            SectionHeader(title = "Projects in motion")
        }

        items(snapshot.projects, key = { it.id.value }) { project ->
            ProjectProgressRow(
                project = project,
                onClick = { onOpenProject(project.id) },
            )
        }

        item {
            Spacer(Modifier.height(20.dp))
            HomeInsightsSummary(
                snapshot = insightsSummary,
                onOpenInsights = onOpenInsights,
            )
        }

        item {
            Spacer(Modifier.height(20.dp))
            SectionHeader(title = "Coming up")
        }

        items(snapshot.upcomingTasks, key = { "upcoming-${it.id.value}" }) { task ->
            UpcomingRow(task, projectNames[task.projectId] ?: "Inbox") {
                onOpenTask(task.id)
            }
        }
    }
}

@Composable
private fun HomeInsightsSummary(
    snapshot: InsightsSnapshot,
    onOpenInsights: () -> Unit,
) {
    Column {
        SectionHeader(
            title = stringResource(R.string.home_insights_heading),
            action = {
                TextButton(
                    onClick = onOpenInsights,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("home-open-insights"),
                ) {
                    Text(stringResource(R.string.home_insights_open))
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.home_insights_summary,
                snapshot.completed.current,
                homeDurationText(snapshot.quality.recordedTime.included),
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun homeDurationText(duration: Duration): String {
    val minutes = duration.toMinutes().coerceAtLeast(0)
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        duration > Duration.ZERO && minutes == 0L ->
            stringResource(R.string.home_insights_less_than_minute)
        hours == 0L -> stringResource(R.string.home_insights_minutes, remainingMinutes)
        remainingMinutes == 0L -> stringResource(R.string.home_insights_hours, hours)
        else -> stringResource(
            R.string.home_insights_hours_minutes,
            hours,
            remainingMinutes,
        )
    }
}

@Composable
private fun HomeHeader(
    snapshot: HomeSnapshot,
    onOpenSearch: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = snapshot.today.format(HOME_DATE_FORMAT),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = "Good afternoon",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            IconButton(onClick = onOpenSearch, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Search, contentDescription = "Search workspace")
            }
        }
    }
}

@Composable
private fun ActiveTimer(
    title: String,
    project: String?,
    elapsed: String,
    onToggle: () -> Unit,
) {
    Surface(
        color = OpenTasksColors.LightInk,
        contentColor = Color.White,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(OpenTasksColors.LightEmber, CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Active timer",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.72f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                if (project != null) {
                    Text(
                        project,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    elapsed,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                    color = OpenTasksColors.LightEmber,
                )
                IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Pause, contentDescription = "Pause timer")
                }
            }
        }
    }
}

@Composable
private fun UpcomingRow(
    task: Task,
    projectName: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                task.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                projectName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            task.due?.let {
                UPCOMING_DATE_FORMAT.format(
                    it.instant.atZone(java.time.ZoneId.of(it.zoneId)),
                )
            } ?: "Unscheduled",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

private val HOME_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.UK)
private val UPCOMING_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
