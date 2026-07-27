package app.opentasks.feature.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.DurationQuality
import app.opentasks.core.model.EstimateActual
import app.opentasks.core.model.InsightsQuality
import app.opentasks.core.model.InsightsRange
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.InstantRange
import app.opentasks.core.model.MetricComparison
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.ProjectTimeRow
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TagTimeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class InsightsScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun moreOpensInsightsAndOnScreenAndSystemBackReturnToOverview() {
        composeRule.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = emptyList(),
                    projects = emptyList(),
                    insightsState = populatedState(),
                    onInsightsRangeChange = {},
                    onInsightsProjectFilter = { _, _ -> },
                    onInsightsTagFilter = { _, _ -> },
                    onInsightsIncludeConflictedTimeChange = {},
                    onInsightsPresentationChange = {},
                    onRestoreProject = {},
                    onRestoreTask = {},
                    onPermanentlyDeleteTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("open-insights")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("insights-screen").assertIsDisplayed()

        composeRule.onNodeWithTag("insights-back").performClick()
        composeRule.onNodeWithTag("more-overview").assertIsDisplayed()

        composeRule.onNodeWithTag("open-insights")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("insights-screen").assertIsDisplayed()
        Espresso.pressBack()
        composeRule.onNodeWithTag("more-overview").assertIsDisplayed()
    }

    @Test
    fun sevenThirtyAndNinetyDayControlsEmitTheirExactRanges() {
        val selected = mutableListOf<InsightsRange>()
        composeRule.setContent {
            OpenTasksTheme {
                InsightsScreen(
                    state = populatedState(),
                    onRangeChange = selected::add,
                    onProjectFilter = { _, _ -> },
                    onTagFilter = { _, _ -> },
                    onIncludeConflictedTimeChange = {},
                    onPresentationChange = {},
                    onBack = {},
                )
            }
        }

        listOf(
            "insights-range-7" to InsightsRange.SEVEN_DAYS,
            "insights-range-30" to InsightsRange.THIRTY_DAYS,
            "insights-range-90" to InsightsRange.NINETY_DAYS,
        ).forEach { (tag, _) ->
            composeRule.onNodeWithTag(tag).performClick()
        }

        assertEquals(
            listOf(
                InsightsRange.SEVEN_DAYS,
                InsightsRange.THIRTY_DAYS,
                InsightsRange.NINETY_DAYS,
            ),
            selected,
        )
    }

    @Test
    fun projectAndTagFiltersStayAvailableForAnEmptyFilteredResultAndSupportMultiSelect() {
        val changes = mutableListOf<String>()
        composeRule.setContent {
            var state by mutableStateOf(
                populatedState().copy(snapshot = emptySnapshot()),
            )
            OpenTasksTheme {
                InsightsScreen(
                    state = state,
                    onRangeChange = {},
                    onProjectFilter = { id, selected ->
                        changes += "project:${id.value}:$selected"
                        state = state.copy(
                            selection = state.selection.copy(
                                projectIds = state.selection.projectIds
                                    .withSelection(id, selected),
                            ),
                        )
                    },
                    onTagFilter = { id, selected ->
                        changes += "tag:${id.value}:$selected"
                        state = state.copy(
                            selection = state.selection.copy(
                                tagIds = state.selection.tagIds.withSelection(id, selected),
                            ),
                        )
                    },
                    onIncludeConflictedTimeChange = {},
                    onPresentationChange = {},
                    onBack = {},
                )
            }
        }

        listOf(
            "insights-project-filter-alpha",
            "insights-project-filter-beta",
            "insights-tag-filter-focus",
            "insights-tag-filter-urgent",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag)
                .performScrollTo()
                .performClick()
                .assertIsSelected()
        }

        assertEquals(
            listOf(
                "project:alpha:true",
                "project:beta:true",
                "tag:focus:true",
                "tag:urgent:true",
            ),
            changes,
        )
    }

    @Test
    fun conflictedTimeIsDisclosedExcludedByDefaultAndCanBeIncluded() {
        composeRule.setContent {
            var includeConflicted by mutableStateOf(false)
            OpenTasksTheme {
                InsightsScreen(
                    state = populatedState(includeConflicted = includeConflicted),
                    onRangeChange = {},
                    onProjectFilter = { _, _ -> },
                    onTagFilter = { _, _ -> },
                    onIncludeConflictedTimeChange = { includeConflicted = it },
                    onPresentationChange = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Trusted time 1 h").assertIsDisplayed()
        composeRule.onNodeWithText("Conflicted time 30 min, excluded").assertIsDisplayed()
        composeRule.onNodeWithText("Included total 1 h").assertIsDisplayed()

        composeRule.onNodeWithTag("insights-include-conflicted")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("Conflicted time 30 min, included").assertIsDisplayed()
        composeRule.onNodeWithText("Included total 1 h 30 min").assertIsDisplayed()
    }

    @Test
    fun chartAndTableExposeTheSameOrderedLabelsAndValues() {
        composeRule.setContent {
            var presentation by mutableStateOf(InsightsPresentation.CHART)
            OpenTasksTheme {
                InsightsScreen(
                    state = populatedState().copy(presentation = presentation),
                    onRangeChange = {},
                    onProjectFilter = { _, _ -> },
                    onTagFilter = { _, _ -> },
                    onIncludeConflictedTimeChange = {},
                    onPresentationChange = { presentation = it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("insights-chart").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Alpha, 1 h")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Focus, 45 min")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag("insights-presentation-table")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("insights-table").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("insights-project-row-alpha")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Alpha")
            .assertTextContains("1 h")
        composeRule.onNodeWithTag("insights-tag-row-focus")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Focus")
            .assertTextContains("45 min")
    }

    @Test
    fun noDataStateNamesTheSelectedRange() {
        composeRule.setContent {
            OpenTasksTheme {
                TestInsightsScreen(emptyState())
            }
        }

        composeRule.onNodeWithText("No insight data for these 7 days.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun completedWorkWithoutEstimatesHasAnExplicitState() {
        val state = emptyState().copy(
            snapshot = emptyState().snapshot.copy(
                completed = MetricComparison(current = 2, previous = 1),
                estimateActual = EstimateActual(
                    estimated = Duration.ZERO,
                    actual = DurationQuality(
                        trusted = Duration.ofMinutes(25),
                        conflicted = Duration.ZERO,
                        included = Duration.ofMinutes(25),
                    ),
                    estimatedTaskCount = 0,
                    unestimatedTaskCount = 2,
                    actualTaskCount = 1,
                ),
            ),
        )
        composeRule.setContent {
            OpenTasksTheme {
                TestInsightsScreen(state)
            }
        }

        composeRule.onNodeWithText("No estimates for 2 completed tasks.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun rangeWithoutRecordedTimeHasAnExplicitState() {
        val state = emptyState().copy(
            snapshot = emptyState().snapshot.copy(
                completed = MetricComparison(current = 1, previous = 0),
            ),
        )
        composeRule.setContent {
            OpenTasksTheme {
                TestInsightsScreen(state)
            }
        }

        composeRule.onNodeWithText("No recorded time in this range.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun allConflictedRecordedTimeNamesItsExcludedDenominator() {
        val conflicted = Duration.ofMinutes(45)
        val state = emptyState().copy(
            snapshot = emptyState().snapshot.copy(
                quality = InsightsQuality(
                    recordedTime = DurationQuality(
                        trusted = Duration.ZERO,
                        conflicted = conflicted,
                        included = Duration.ZERO,
                    ),
                ),
                projectTime = listOf(
                    ProjectTimeRow(
                        projectId = ProjectId("alpha"),
                        displayName = "Alpha",
                        duration = DurationQuality(
                            trusted = Duration.ZERO,
                            conflicted = conflicted,
                            included = Duration.ZERO,
                        ),
                    ),
                ),
            ),
        )
        composeRule.setContent {
            OpenTasksTheme {
                TestInsightsScreen(state)
            }
        }

        composeRule.onNodeWithText("All 45 min of recorded time is conflicted and excluded.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun twoHundredPercentTextKeepsEverySectionAndFinalActionsReachable() {
        composeRule.setContent {
            val density = LocalDensity.current
            var state by mutableStateOf(populatedState())
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                OpenTasksTheme {
                    Box(
                        modifier = Modifier
                            .width(320.dp)
                            .height(640.dp),
                    ) {
                        InsightsScreen(
                            state = state,
                            onRangeChange = {},
                            onProjectFilter = { _, _ -> },
                            onTagFilter = { _, _ -> },
                            onIncludeConflictedTimeChange = {},
                            onPresentationChange = {
                                state = state.copy(presentation = it)
                            },
                            onBack = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Insights").assertIsDisplayed()
        composeRule.onNodeWithTag("insights-include-conflicted")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("insights-presentation-table")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("insights-tag-row-urgent")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun expandedFoldableContentUsesTwoColumnsAfterTheNavigationRail() {
        composeRule.setContent {
            OpenTasksTheme {
                Box(
                    modifier = Modifier
                        .width(772.dp)
                        .height(800.dp),
                ) {
                    TestInsightsScreen(populatedState())
                }
            }
        }

        val projects = composeRule.onNodeWithText("Projects")
            .fetchSemanticsNode()
            .boundsInRoot
        val view = composeRule.onNodeWithText("View")
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(
            "The report should be to the right of filters at the usable expanded width",
            view.left > projects.left,
        )
        assertEquals(projects.top, view.top, 1f)
    }

    @Test
    fun keyboardTabTraversalFollowsTheVisibleControlOrder() {
        composeRule.setContent {
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(inputModeManager) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
            }
            OpenTasksTheme {
                TestInsightsScreen(populatedState())
            }
        }

        composeRule.onNodeWithTag("insights-range-7")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Tab) }
        composeRule.onNodeWithTag("insights-range-30")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Tab) }
        composeRule.onNodeWithTag("insights-range-90")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Tab) }
        composeRule.onNodeWithTag("insights-project-filter-alpha").assertIsFocused()
    }

    @Test
    fun everyInsightsActionHasAtLeastAFortyEightDpTarget() {
        composeRule.setContent {
            OpenTasksTheme {
                TestInsightsScreen(populatedState())
            }
        }

        listOf(
            "insights-back",
            "insights-range-7",
            "insights-range-30",
            "insights-range-90",
            "insights-project-filter-alpha",
            "insights-project-filter-beta",
            "insights-tag-filter-focus",
            "insights-tag-filter-urgent",
            "insights-include-conflicted",
            "insights-presentation-chart",
            "insights-presentation-table",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag)
                .performScrollTo()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
    }

    @androidx.compose.runtime.Composable
    private fun TestInsightsScreen(state: InsightsUiState) {
        InsightsScreen(
            state = state,
            onRangeChange = {},
            onProjectFilter = { _, _ -> },
            onTagFilter = { _, _ -> },
            onIncludeConflictedTimeChange = {},
            onPresentationChange = {},
            onBack = {},
        )
    }

    private fun populatedState(includeConflicted: Boolean = false): InsightsUiState {
        val trusted = Duration.ofHours(1)
        val conflicted = Duration.ofMinutes(30)
        val included = if (includeConflicted) trusted.plus(conflicted) else trusted
        return InsightsUiState(
            snapshot = emptySnapshot().copy(
                completed = MetricComparison(current = 4, previous = 3),
                estimateActual = EstimateActual(
                    estimated = Duration.ofHours(2),
                    actual = DurationQuality(
                        trusted = trusted,
                        conflicted = conflicted,
                        included = included,
                    ),
                    estimatedTaskCount = 2,
                    unestimatedTaskCount = 1,
                    actualTaskCount = 2,
                ),
                projectTime = listOf(
                    ProjectTimeRow(
                        projectId = ProjectId("alpha"),
                        displayName = "Alpha",
                        duration = DurationQuality(
                            trusted = Duration.ofHours(1),
                            conflicted = Duration.ZERO,
                            included = Duration.ofHours(1),
                        ),
                    ),
                    ProjectTimeRow(
                        projectId = ProjectId("beta"),
                        displayName = "Beta",
                        duration = DurationQuality(
                            trusted = Duration.ofMinutes(30),
                            conflicted = Duration.ZERO,
                            included = Duration.ofMinutes(30),
                        ),
                    ),
                ),
                tagTime = listOf(
                    TagTimeRow(
                        tagId = TagId("focus"),
                        displayName = "Focus",
                        duration = DurationQuality(
                            trusted = Duration.ofMinutes(45),
                            conflicted = Duration.ZERO,
                            included = Duration.ofMinutes(45),
                        ),
                    ),
                    TagTimeRow(
                        tagId = TagId("urgent"),
                        displayName = "Urgent",
                        duration = DurationQuality(
                            trusted = Duration.ofMinutes(15),
                            conflicted = Duration.ZERO,
                            included = Duration.ofMinutes(15),
                        ),
                    ),
                ),
                quality = InsightsQuality(
                    recordedTime = DurationQuality(
                        trusted = trusted,
                        conflicted = conflicted,
                        included = included,
                    ),
                ),
            ),
            selection = InsightsSelection(includeConflictedTime = includeConflicted),
            presentation = InsightsPresentation.CHART,
            projectOptions = listOf(
                InsightsProjectOption(ProjectId("alpha"), "Alpha"),
                InsightsProjectOption(ProjectId("beta"), "Beta"),
            ),
            tagOptions = listOf(
                InsightsTagOption(TagId("focus"), "Focus"),
                InsightsTagOption(TagId("urgent"), "Urgent"),
            ),
        )
    }

    private fun emptyState(): InsightsUiState = InsightsUiState(
        snapshot = emptySnapshot(),
        selection = InsightsSelection(),
        presentation = InsightsPresentation.CHART,
        projectOptions = emptyList(),
        tagOptions = emptyList(),
    )

    private fun emptySnapshot(): InsightsSnapshot = InsightsSnapshot(
        interval = InstantRange(
            startInclusive = Instant.parse("2026-07-21T00:00:00Z"),
            endExclusive = Instant.parse("2026-07-28T00:00:00Z"),
        ),
        comparisonInterval = InstantRange(
            startInclusive = Instant.parse("2026-07-14T00:00:00Z"),
            endExclusive = Instant.parse("2026-07-21T00:00:00Z"),
        ),
        completed = MetricComparison(current = 0, previous = 0),
        overdue = emptyList(),
        estimateActual = EstimateActual(
            estimated = Duration.ZERO,
            actual = DurationQuality(
                trusted = Duration.ZERO,
                conflicted = Duration.ZERO,
                included = Duration.ZERO,
            ),
        ),
        projectTime = emptyList(),
        tagTime = emptyList(),
        milestoneHealth = emptyList(),
        quality = InsightsQuality(
            recordedTime = DurationQuality(
                trusted = Duration.ZERO,
                conflicted = Duration.ZERO,
                included = Duration.ZERO,
            ),
        ),
    )

    private fun <T> Set<T>.withSelection(value: T, selected: Boolean): Set<T> =
        if (selected) this + value else this - value
}
