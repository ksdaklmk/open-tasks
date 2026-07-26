package app.opentasks.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.EmptyState
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ScheduleScreen(
    tasks: List<Task>,
    projectNames: Map<ProjectId, String>,
    expanded: Boolean,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheduled = tasks.filter { it.deletedAt == null && (it.start != null || it.due != null) }
    if (expanded) {
        ExpandedWeek(
            tasks = scheduled,
            unscheduled = tasks.filter { it.deletedAt == null && it.start == null && it.due == null },
            projectNames = projectNames,
            onOpenTask = onOpenTask,
            modifier = modifier,
        )
    } else {
        CompactAgenda(
            tasks = scheduled,
            projectNames = projectNames,
            onOpenTask = onOpenTask,
            modifier = modifier,
        )
    }
}

@Composable
private fun CompactAgenda(
    tasks: List<Task>,
    projectNames: Map<ProjectId, String>,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(
            "Schedule",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Sunday, 26 July",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        SectionHeader("Day agenda", supportingText = "${tasks.size} scheduled items")
        Spacer(Modifier.height(8.dp))
        if (tasks.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.CalendarMonth,
                title = "No dated work",
            )
        } else {
            tasks.forEach { task ->
                AgendaRow(task, projectNames[task.projectId] ?: "Inbox") {
                    onOpenTask(task.id)
                }
            }
        }
    }
}

@Composable
private fun ExpandedWeek(
    tasks: List<Task>,
    unscheduled: List<Task>,
    projectNames: Map<ProjectId, String>,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(24.dp),
        ) {
            Text(
                "Schedule",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Week of 26 July",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Sun 26", "Mon 27", "Tue 28", "Wed 29", "Thu 30", "Fri 31", "Sat 1").forEachIndexed { index, day ->
                    DayColumn(
                        day = day,
                        tasks = if (index < tasks.size) listOf(tasks[index]) else emptyList(),
                        projectNames = projectNames,
                        onOpenTask = onOpenTask,
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp),
        )
        Column(
            modifier = Modifier
                .width(300.dp)
                .padding(20.dp),
        ) {
            SectionHeader(title = "Unscheduled")
            Spacer(Modifier.height(12.dp))
            unscheduled.forEach { task ->
                Surface(
                    onClick = { onOpenTask(task.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        task.title,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    day: String,
    tasks: List<Task>,
    projectNames: Map<ProjectId, String>,
    onOpenTask: (TaskId) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(152.dp)
            .fillMaxHeight(),
    ) {
        Text(
            day,
            style = MaterialTheme.typography.titleSmall,
            color = if (day == "Sun 26") {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        tasks.forEach { task ->
            Surface(
                onClick = { onOpenTask(task.id) },
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        projectNames[task.projectId] ?: "Inbox",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(
    task: Task,
    projectName: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.width(64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    task.due?.let {
                        DateTimeFormatter.ofPattern("HH:mm").format(
                            it.instant.atZone(ZoneId.of(it.zoneId)),
                        )
                    } ?: "Any",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Box(
                Modifier
                    .width(1.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Column(Modifier.padding(start = 16.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    projectName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
