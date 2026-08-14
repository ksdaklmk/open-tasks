package app.opentasks.feature.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleTaskActions(
    task: Task,
    reminder: Reminder?,
    initialDate: LocalDate,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onRemoveTaskSchedule: (TaskId) -> Unit,
) {
    if (task.isCompleted || task.deletedAt != null) return

    var menuExpanded by rememberSaveable(task.id.value) { mutableStateOf(false) }
    var showDatePicker by rememberSaveable(task.id.value) { mutableStateOf(false) }
    var confirmReminderRemoval by rememberSaveable(task.id.value) { mutableStateOf(false) }
    val scheduled = task.start != null || task.due != null

    Box {
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier
                .size(48.dp)
                .testTag("schedule-actions-${task.id.value}"),
        ) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(
                    R.string.schedule_actions_description,
                    task.title,
                ),
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.schedule_reschedule)) },
                onClick = {
                    menuExpanded = false
                    showDatePicker = true
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("schedule-reschedule-${task.id.value}"),
            )
            if (scheduled && task.recurrence == null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.schedule_remove)) },
                    onClick = {
                        menuExpanded = false
                        if (reminder == null) {
                            onRemoveTaskSchedule(task.id)
                        } else {
                            confirmReminderRemoval = true
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("schedule-remove-${task.id.value}"),
                )
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialDate
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selected ->
                            onRescheduleTask(
                                task.id,
                                Instant.ofEpochMilli(selected)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate(),
                            )
                        }
                        showDatePicker = false
                    },
                    enabled = datePickerState.selectedDateMillis != null,
                    modifier = Modifier
                        .widthIn(min = 48.dp)
                        .heightIn(min = 48.dp)
                        .testTag("schedule-reschedule-confirm-${task.id.value}"),
                ) {
                    Text(stringResource(R.string.schedule_set_date))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false },
                    modifier = Modifier
                        .widthIn(min = 48.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.schedule_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (confirmReminderRemoval) {
        AlertDialog(
            onDismissRequest = { confirmReminderRemoval = false },
            title = { Text(stringResource(R.string.schedule_remove_reminder_title)) },
            text = { Text(stringResource(R.string.schedule_remove_reminder_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReminderRemoval = false
                        onRemoveTaskSchedule(task.id)
                    },
                    modifier = Modifier
                        .widthIn(min = 48.dp)
                        .heightIn(min = 48.dp)
                        .testTag("schedule-remove-confirm-${task.id.value}"),
                ) {
                    Text(stringResource(R.string.schedule_remove))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmReminderRemoval = false },
                    modifier = Modifier
                        .widthIn(min = 48.dp)
                        .heightIn(min = 48.dp)
                        .testTag("schedule-remove-cancel-${task.id.value}"),
                ) {
                    Text(stringResource(R.string.schedule_cancel))
                }
            },
        )
    }
}
