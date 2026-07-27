package app.opentasks.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.TableRows
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.opentasks.core.designsystem.SectionHeader
import app.opentasks.core.model.DurationQuality
import app.opentasks.core.model.EstimateActual
import app.opentasks.core.model.InsightsQuality
import app.opentasks.core.model.InsightsRange
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.InstantRange
import app.opentasks.core.model.MetricComparison
import app.opentasks.core.model.MilestoneHealthRow
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.ProjectTimeRow
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TagTimeRow
import java.time.Duration
import java.time.Instant
import kotlin.math.max

data class InsightsUiState(
    val snapshot: InsightsSnapshot,
    val selection: InsightsSelection,
    val presentation: InsightsPresentation,
    val projectOptions: List<InsightsProjectOption>,
    val tagOptions: List<InsightsTagOption>,
)

enum class InsightsPresentation {
    CHART,
    TABLE,
}

data class InsightsProjectOption(
    val id: ProjectId,
    val displayName: String,
)

data class InsightsTagOption(
    val id: TagId,
    val displayName: String,
)

@Composable
fun InsightsScreen(
    state: InsightsUiState,
    onRangeChange: (InsightsRange) -> Unit,
    onProjectFilter: (ProjectId, Boolean) -> Unit,
    onTagFilter: (TagId, Boolean) -> Unit,
    onIncludeConflictedTimeChange: (Boolean) -> Unit,
    onPresentationChange: (InsightsPresentation) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("insights-screen"),
    ) {
        val expanded = maxWidth >= 720.dp
        val horizontalPadding = if (expanded) 32.dp else 16.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = horizontalPadding,
                    top = 8.dp,
                    end = horizontalPadding,
                    bottom = 112.dp,
                ),
        ) {
            InsightsHeader(onBack)
            Spacer(Modifier.height(24.dp))
            RangeControls(
                selected = state.selection.range,
                onRangeChange = onRangeChange,
            )
            Spacer(Modifier.height(24.dp))
            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    InsightsFilters(
                        state = state,
                        onProjectFilter = onProjectFilter,
                        onTagFilter = onTagFilter,
                        onIncludeConflictedTimeChange =
                            onIncludeConflictedTimeChange,
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .weight(0.36f, fill = false),
                    )
                    InsightsReport(
                        state = state,
                        onPresentationChange = onPresentationChange,
                        modifier = Modifier.weight(0.64f),
                    )
                }
            } else {
                Column {
                    InsightsFilters(
                        state = state,
                        onProjectFilter = onProjectFilter,
                        onTagFilter = onTagFilter,
                        onIncludeConflictedTimeChange =
                            onIncludeConflictedTimeChange,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 24.dp))
                    InsightsReport(
                        state = state,
                        onPresentationChange = onPresentationChange,
                    )
                }
            }
            Spacer(
                modifier = Modifier
                    .height(8.dp)
                    .testTag("insights-content-end"),
            )
        }
    }
}

@Composable
private fun InsightsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("insights-back"),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.insights_back),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.insights_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun RangeControls(
    selected: InsightsRange,
    onRangeChange: (InsightsRange) -> Unit,
) {
    SectionHeader(stringResource(R.string.insights_range_heading))
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            InsightsRange.SEVEN_DAYS to R.string.insights_range_7,
            InsightsRange.THIRTY_DAYS to R.string.insights_range_30,
            InsightsRange.NINETY_DAYS to R.string.insights_range_90,
        ).forEach { (range, label) ->
            FilterChip(
                selected = selected == range,
                onClick = { onRangeChange(range) },
                label = { Text(stringResource(label)) },
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("insights-range-${range.dayCount}"),
            )
        }
    }
}

@Composable
private fun InsightsFilters(
    state: InsightsUiState,
    onProjectFilter: (ProjectId, Boolean) -> Unit,
    onTagFilter: (TagId, Boolean) -> Unit,
    onIncludeConflictedTimeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader(stringResource(R.string.insights_project_filter_heading))
        Spacer(Modifier.height(8.dp))
        if (state.projectOptions.isEmpty()) {
            SupportingText(stringResource(R.string.insights_no_project_options))
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.projectOptions.forEach { option ->
                    val selected = option.id in state.selection.projectIds
                    FilterChip(
                        selected = selected,
                        onClick = { onProjectFilter(option.id, !selected) },
                        label = { Text(option.displayName) },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("insights-project-filter-${option.id.value}"),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader(stringResource(R.string.insights_tag_filter_heading))
        Spacer(Modifier.height(8.dp))
        if (state.tagOptions.isEmpty()) {
            SupportingText(stringResource(R.string.insights_no_tag_options))
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.tagOptions.forEach { option ->
                    val selected = option.id in state.selection.tagIds
                    FilterChip(
                        selected = selected,
                        onClick = { onTagFilter(option.id, !selected) },
                        label = { Text(option.displayName) },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("insights-tag-filter-${option.id.value}"),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = state.selection.includeConflictedTime,
                    role = Role.Switch,
                    onValueChange = onIncludeConflictedTimeChange,
                )
                .testTag("insights-include-conflicted"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.insights_include_conflicted),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                SupportingText(
                    stringResource(R.string.insights_include_conflicted_supporting),
                )
            }
            Switch(
                checked = state.selection.includeConflictedTime,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun InsightsReport(
    state: InsightsUiState,
    onPresentationChange: (InsightsPresentation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader(stringResource(R.string.insights_view_heading))
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PresentationChip(
                presentation = InsightsPresentation.CHART,
                selected = state.presentation == InsightsPresentation.CHART,
                label = stringResource(R.string.insights_chart),
                onPresentationChange = onPresentationChange,
            )
            PresentationChip(
                presentation = InsightsPresentation.TABLE,
                selected = state.presentation == InsightsPresentation.TABLE,
                label = stringResource(R.string.insights_table),
                onPresentationChange = onPresentationChange,
            )
        }
        Spacer(Modifier.height(24.dp))

        if (!state.snapshot.hasData()) {
            Text(
                text = stringResource(
                    R.string.insights_no_data,
                    state.selection.range.dayCount,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }

        CompletionSummary(state.snapshot)
        HorizontalDivider(Modifier.padding(vertical = 24.dp))
        TimeQuality(
            quality = state.snapshot.quality.recordedTime,
            includeConflicted = state.selection.includeConflictedTime,
        )
        HorizontalDivider(Modifier.padding(vertical = 24.dp))

        when (state.presentation) {
            InsightsPresentation.CHART -> InsightsChart(state.snapshot)
            InsightsPresentation.TABLE -> InsightsTable(state.snapshot)
        }

        if (state.snapshot.milestoneHealth.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 24.dp))
            MilestoneRows(state.snapshot.milestoneHealth)
        }
    }
}

@Composable
private fun PresentationChip(
    presentation: InsightsPresentation,
    selected: Boolean,
    label: String,
    onPresentationChange: (InsightsPresentation) -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = { onPresentationChange(presentation) },
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = when (presentation) {
                    InsightsPresentation.CHART -> Icons.Rounded.BarChart
                    InsightsPresentation.TABLE -> Icons.Rounded.TableRows
                },
                contentDescription = null,
            )
        },
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .testTag("insights-presentation-${presentation.name.lowercase()}"),
    )
}

@Composable
private fun CompletionSummary(snapshot: InsightsSnapshot) {
    SectionHeader(stringResource(R.string.insights_completed_heading))
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(
            R.string.insights_task_count,
            snapshot.completed.current,
        ),
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(
            R.string.insights_task_count,
            snapshot.completed.previous,
        ) + " · " + stringResource(R.string.insights_completed_previous),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.insights_overdue_count, snapshot.overdue.size),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TimeQuality(
    quality: DurationQuality,
    includeConflicted: Boolean,
) {
    SectionHeader(stringResource(R.string.insights_recorded_time_heading))
    Spacer(Modifier.height(12.dp))
    if (quality.trusted.isZero && quality.conflicted.isZero) {
        SupportingText(stringResource(R.string.insights_no_time))
    } else {
        if (quality.trusted.isZero && !quality.conflicted.isZero && !includeConflicted) {
            Text(
                text = stringResource(
                    R.string.insights_all_conflicted,
                    durationText(quality.conflicted),
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }
        QualityLine(
            stringResource(
                R.string.insights_trusted_time,
                durationText(quality.trusted),
            ),
        )
        QualityLine(
            stringResource(
                if (includeConflicted) {
                    R.string.insights_conflicted_included
                } else {
                    R.string.insights_conflicted_excluded
                },
                durationText(quality.conflicted),
            ),
        )
        QualityLine(
            stringResource(
                R.string.insights_included_total,
                durationText(quality.included),
            ),
            strong = true,
        )
    }
}

@Composable
private fun QualityLine(text: String, strong: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun InsightsChart(snapshot: InsightsSnapshot) {
    Column(modifier = Modifier.testTag("insights-chart")) {
        val completedMax = max(snapshot.completed.current, snapshot.completed.previous)
            .coerceAtLeast(1)
        MetricBar(
            label = stringResource(R.string.insights_completed_current),
            value = stringResource(
                R.string.insights_task_count,
                snapshot.completed.current,
            ),
            progress = snapshot.completed.current.toFloat() / completedMax,
        )
        MetricBar(
            label = stringResource(R.string.insights_completed_previous),
            value = stringResource(
                R.string.insights_task_count,
                snapshot.completed.previous,
            ),
            progress = snapshot.completed.previous.toFloat() / completedMax,
        )

        Spacer(Modifier.height(24.dp))
        EstimateChart(snapshot)
        Spacer(Modifier.height(24.dp))
        DurationChartSection(
            title = stringResource(R.string.insights_project_time_heading),
            rows = snapshot.projectTime,
        )
        Spacer(Modifier.height(24.dp))
        TagDurationChart(snapshot.tagTime)
    }
}

@Composable
private fun EstimateChart(snapshot: InsightsSnapshot) {
    SectionHeader(stringResource(R.string.insights_estimate_heading))
    Spacer(Modifier.height(12.dp))
    val estimate = snapshot.estimateActual
    if (estimate.estimatedTaskCount == 0L && estimate.unestimatedTaskCount > 0L) {
        Text(
            text = pluralStringResource(
                R.plurals.insights_no_estimates,
                estimate.unestimatedTaskCount.toInt(),
                estimate.unestimatedTaskCount,
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val maximum = max(
            estimate.estimated.toMinutes(),
            estimate.actual.included.toMinutes(),
        ).coerceAtLeast(1)
        MetricBar(
            label = stringResource(R.string.insights_estimated),
            value = durationText(estimate.estimated),
            progress = estimate.estimated.toMinutes().toFloat() / maximum,
        )
        MetricBar(
            label = stringResource(R.string.insights_actual),
            value = durationText(estimate.actual.included),
            progress = estimate.actual.included.toMinutes().toFloat() / maximum,
        )
        SupportingText(
            stringResource(
                R.string.insights_estimate_denominator,
                estimate.estimatedTaskCount,
                estimate.actualTaskCount,
            ),
        )
        if (estimate.unestimatedTaskCount > 0) {
            Spacer(Modifier.height(4.dp))
            SupportingText(
                stringResource(
                    R.string.insights_unestimated_count,
                    estimate.unestimatedTaskCount,
                ),
            )
        }
    }
}

@Composable
private fun DurationChartSection(
    title: String,
    rows: List<ProjectTimeRow>,
) {
    SectionHeader(title)
    Spacer(Modifier.height(12.dp))
    if (rows.isEmpty()) {
        SupportingText(stringResource(R.string.insights_no_project_time))
        return
    }
    val maximum = rows.maxOf { it.duration.included.toMinutes() }.coerceAtLeast(1)
    rows.forEach { row ->
        MetricBar(
            label = row.displayName,
            value = durationText(row.duration.included),
            progress = row.duration.included.toMinutes().toFloat() / maximum,
        )
    }
}

@Composable
private fun TagDurationChart(rows: List<TagTimeRow>) {
    SectionHeader(stringResource(R.string.insights_tag_time_heading))
    Spacer(Modifier.height(12.dp))
    if (rows.isEmpty()) {
        SupportingText(stringResource(R.string.insights_no_tag_time))
        return
    }
    val maximum = rows.maxOf { it.duration.included.toMinutes() }.coerceAtLeast(1)
    rows.forEach { row ->
        MetricBar(
            label = row.displayName,
            value = durationText(row.duration.included),
            progress = row.duration.included.toMinutes().toFloat() / maximum,
        )
    }
}

@Composable
private fun MetricBar(
    label: String,
    value: String,
    progress: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .semantics {
                contentDescription = "$label, $value"
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun InsightsTable(snapshot: InsightsSnapshot) {
    Column(modifier = Modifier.testTag("insights-table")) {
        TableSectionHeader(stringResource(R.string.insights_completed_heading))
        DataRow(
            label = stringResource(R.string.insights_completed_current),
            value = stringResource(
                R.string.insights_task_count,
                snapshot.completed.current,
            ),
        )
        DataRow(
            label = stringResource(R.string.insights_completed_previous),
            value = stringResource(
                R.string.insights_task_count,
                snapshot.completed.previous,
            ),
        )

        Spacer(Modifier.height(20.dp))
        TableSectionHeader(stringResource(R.string.insights_estimate_heading))
        val estimate = snapshot.estimateActual
        if (estimate.estimatedTaskCount == 0L && estimate.unestimatedTaskCount > 0L) {
            SupportingText(
                pluralStringResource(
                    R.plurals.insights_no_estimates,
                    estimate.unestimatedTaskCount.toInt(),
                    estimate.unestimatedTaskCount,
                ),
            )
        } else {
            DataRow(
                label = stringResource(R.string.insights_estimated),
                value = durationText(estimate.estimated),
            )
            DataRow(
                label = stringResource(R.string.insights_actual),
                value = durationText(estimate.actual.included),
            )
            SupportingText(
                stringResource(
                    R.string.insights_estimate_denominator,
                    estimate.estimatedTaskCount,
                    estimate.actualTaskCount,
                ),
            )
            if (estimate.unestimatedTaskCount > 0) {
                Spacer(Modifier.height(4.dp))
                SupportingText(
                    stringResource(
                        R.string.insights_unestimated_count,
                        estimate.unestimatedTaskCount,
                    ),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        TableSectionHeader(stringResource(R.string.insights_project_time_heading))
        if (snapshot.projectTime.isEmpty()) {
            SupportingText(stringResource(R.string.insights_no_project_time))
        } else {
            snapshot.projectTime.forEach { row ->
                DataRow(
                    label = row.displayName,
                    value = durationText(row.duration.included),
                    modifier = Modifier.testTag(
                        "insights-project-row-${row.projectId?.value ?: "inbox"}",
                    ),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        TableSectionHeader(stringResource(R.string.insights_tag_time_heading))
        if (snapshot.tagTime.isEmpty()) {
            SupportingText(stringResource(R.string.insights_no_tag_time))
        } else {
            snapshot.tagTime.forEach { row ->
                DataRow(
                    label = row.displayName,
                    value = durationText(row.duration.included),
                    modifier = Modifier.testTag("insights-tag-row-${row.tagId.value}"),
                )
            }
        }
    }
}

@Composable
private fun TableSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun DataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun MilestoneRows(rows: List<MilestoneHealthRow>) {
    SectionHeader(stringResource(R.string.insights_milestone_heading))
    Spacer(Modifier.height(12.dp))
    rows.forEach { row ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Text(row.displayName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = row.projectName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.insights_milestone_progress,
                    row.completedTasks,
                    row.totalTasks,
                    row.overdueTasks,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.overdueTasks > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun SupportingText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun durationText(duration: Duration): String {
    val minutes = duration.toMinutes().coerceAtLeast(0)
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours == 0L -> stringResource(R.string.insights_minutes, remainingMinutes)
        remainingMinutes == 0L -> stringResource(R.string.insights_hours, hours)
        else -> stringResource(
            R.string.insights_hours_minutes,
            hours,
            remainingMinutes,
        )
    }
}

private fun InsightsSnapshot.hasData(): Boolean =
    completed.current > 0 ||
        completed.previous > 0 ||
        overdue.isNotEmpty() ||
        estimateActual.estimatedTaskCount > 0 ||
        estimateActual.unestimatedTaskCount > 0 ||
        !quality.recordedTime.trusted.isZero ||
        !quality.recordedTime.conflicted.isZero ||
        projectTime.isNotEmpty() ||
        tagTime.isNotEmpty() ||
        milestoneHealth.isNotEmpty()

internal fun emptyInsightsUiState(): InsightsUiState {
    val zeroDuration = DurationQuality(
        trusted = Duration.ZERO,
        conflicted = Duration.ZERO,
        included = Duration.ZERO,
    )
    return InsightsUiState(
        snapshot = InsightsSnapshot(
            interval = InstantRange(Instant.EPOCH, Instant.EPOCH),
            comparisonInterval = InstantRange(Instant.EPOCH, Instant.EPOCH),
            completed = MetricComparison(current = 0, previous = 0),
            overdue = emptyList(),
            estimateActual = EstimateActual(
                estimated = Duration.ZERO,
                actual = zeroDuration,
            ),
            projectTime = emptyList(),
            tagTime = emptyList(),
            milestoneHealth = emptyList(),
            quality = InsightsQuality(recordedTime = zeroDuration),
        ),
        selection = InsightsSelection(),
        presentation = InsightsPresentation.CHART,
        projectOptions = emptyList(),
        tagOptions = emptyList(),
    )
}
