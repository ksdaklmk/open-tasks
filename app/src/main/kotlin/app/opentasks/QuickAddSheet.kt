package app.opentasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.opentasks.core.domain.parseNaturalDate
import app.opentasks.core.model.ZonedMoment
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    onDismiss: () -> Unit,
    onAdd: (String, ZonedMoment?) -> Unit,
    initialTitle: String = "",
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var appliedDue by remember { mutableStateOf<ZonedMoment?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val suggestion = remember(title) {
        parseNaturalDate(title, Instant.now(), ZoneId.systemDefault())
    }
    val suggestedDue = suggestion?.due ?: appliedDue

    fun submit() {
        if (title.isNotBlank() && title.length <= MAX_QUICK_ADD_TITLE_LENGTH) {
            onAdd(title, appliedDue)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text("Quick add", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it.take(MAX_QUICK_ADD_TITLE_LENGTH + 1)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("quick-add-title"),
                label = { Text("Task title") },
                supportingText = {
                    Text(
                        if (title.length > MAX_QUICK_ADD_TITLE_LENGTH) {
                            "Keep task titles under $MAX_QUICK_ADD_TITLE_LENGTH characters"
                        } else {
                            "${title.length}/$MAX_QUICK_ADD_TITLE_LENGTH"
                        },
                    )
                },
                isError = title.length > MAX_QUICK_ADD_TITLE_LENGTH,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            if (suggestedDue != null) {
                Spacer(Modifier.height(12.dp))
                AssistChip(
                    onClick = {
                        val matched = suggestion
                        if (matched != null) {
                            appliedDue = matched.due
                            title = (
                                title.substring(0, matched.startIndex) +
                                    title.substring(matched.endIndex)
                                )
                                .replace(Regex("""\s+"""), " ")
                                .trim()
                        }
                    },
                    label = {
                        Text(
                            stringResource(
                                R.string.quick_add_date_suggestion,
                                DATE_SUGGESTION_FORMATTER.format(
                                    suggestedDue.instant.atZone(suggestedDue.zone()),
                                ),
                            ),
                        )
                    },
                    modifier = Modifier.testTag("quick-add-date-chip"),
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription =
                                stringResource(R.string.quick_add_date_suggestion_clear),
                            modifier = Modifier
                                .testTag("quick-add-date-clear")
                                .clickable { appliedDue = null },
                        )
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = ::submit,
                    enabled = title.isNotBlank() && title.length <= MAX_QUICK_ADD_TITLE_LENGTH,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text("Add task")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

private const val MAX_QUICK_ADD_TITLE_LENGTH = 240
private val DATE_SUGGESTION_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.UK)
