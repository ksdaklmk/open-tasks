package app.opentasks.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (supportingText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(16.dp))
            action()
        }
    }
}

@Composable
fun TaskRow(
    task: Task,
    projectName: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val completionLabel = if (task.isCompleted) "Reopen ${task.title}" else "Complete ${task.title}"
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(completionLabel) {
                        onComplete()
                        true
                    },
                )
            },
        color = when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            else -> Color.Transparent
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Open ${task.title}",
                    onClick = onSelect,
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onComplete,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = if (task.isCompleted) {
                        Icons.Rounded.CheckCircle
                    } else {
                        Icons.Rounded.Check
                    },
                    contentDescription = completionLabel,
                    tint = if (task.isCompleted) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (task.isBlocked) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Rounded.Block,
                            contentDescription = "Blocked",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = projectName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    task.due?.let { due ->
                        Text(
                            text = "• ${
                                DATE_FORMAT.format(
                                    due.instant.atZone(ZoneId.of(due.zoneId)),
                                )
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (task.priority >= Priority.HIGH) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (task.recurrence != null) {
                        Icon(
                            Icons.Rounded.Repeat,
                            contentDescription = "Repeats",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            PriorityGlyph(task.priority)
        }
    }
}

@Composable
private fun PriorityGlyph(priority: Priority) {
    if (priority == Priority.NONE) return
    val bars = when (priority) {
        Priority.LOW -> 1
        Priority.MEDIUM -> 2
        Priority.HIGH -> 3
        Priority.URGENT -> 4
        Priority.NONE -> 0
    }
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(bars) { index ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((6 + index * 3).dp)
                    .background(
                        if (priority >= Priority.HIGH) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
fun ProjectProgressRow(
    project: Project,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = "Open ${project.name}",
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectHealthIcon(project.status)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${project.completedTasks}/${project.totalTasks}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { project.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun ProjectHealthIcon(status: ProjectHealth) {
    val (icon, tint, description) = when (status) {
        ProjectHealth.ON_TRACK -> Triple(
            Icons.Rounded.CheckCircle,
            MaterialTheme.colorScheme.tertiary,
            "On track",
        )
        ProjectHealth.AT_RISK -> Triple(
            Icons.Rounded.Schedule,
            MaterialTheme.colorScheme.secondary,
            "At risk",
        )
        ProjectHealth.BLOCKED -> Triple(
            Icons.Rounded.Block,
            MaterialTheme.colorScheme.error,
            "Blocked",
        )
        ProjectHealth.COMPLETE -> Triple(
            Icons.Rounded.CheckCircle,
            MaterialTheme.colorScheme.tertiary,
            "Complete",
        )
    }
    Icon(icon, description, tint = tint, modifier = Modifier.size(24.dp))
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

fun SemanticStatus.readableName(): String = when (this) {
    SemanticStatus.BACKLOG -> "Backlog"
    SemanticStatus.PLANNED -> "Planned"
    SemanticStatus.STARTED -> "In progress"
    SemanticStatus.BLOCKED -> "Blocked"
    SemanticStatus.COMPLETED -> "Done"
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.UK)
