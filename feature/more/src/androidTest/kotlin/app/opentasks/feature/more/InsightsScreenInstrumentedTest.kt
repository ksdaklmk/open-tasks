package app.opentasks.feature.more

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.DurationQuality
import app.opentasks.core.model.EstimateActual
import app.opentasks.core.model.CompletionTrendPoint
import app.opentasks.core.model.InsightsQuality
import app.opentasks.core.model.InsightsRange
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.InstantRange
import app.opentasks.core.model.MilestoneHealthRow
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.MetricComparison
import app.opentasks.core.model.OverdueBand
import app.opentasks.core.model.OverdueRow
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.ProjectTimeRow
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TagTimeRow
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class InsightsScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val hasCompletionTrendDayTag = SemanticsMatcher(
        "has completion trend day test tag",
    ) { node ->
        SemanticsProperties.TestTag in node.config &&
            node.config[SemanticsProperties.TestTag]
                .startsWith("insights-completion-day-")
    }

    @Test
    fun moreOpensInsightsAndOnScreenAndSystemBackReturnToOverview() {
        lateinit var backDispatcher: OnBackPressedDispatcher
        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
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
        composeRule.runOnUiThread(backDispatcher::onBackPressed)
        composeRule.onNodeWithTag("more-overview").assertIsDisplayed()
    }

    @Test
    fun externalInsightsRequestIsOneShotAndClearsWhenSwitchingAway() {
        var openInsights by mutableStateOf(true)
        composeRule.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = emptyList(),
                    projects = emptyList(),
                    insightsState = populatedState(),
                    openInsights = openInsights,
                    onInsightsClosed = { openInsights = false },
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

        composeRule.onNodeWithTag("insights-screen").assertIsDisplayed()

        composeRule.runOnIdle { openInsights = false }
        composeRule.onNodeWithTag("insights-screen").assertIsDisplayed()

        composeRule.onNodeWithTag("insights-back").performClick()
        composeRule.onNodeWithTag("more-overview").assertIsDisplayed()
        assertFalse(openInsights)
    }

    @Test
    fun restoredInsightsDestinationSurvivesFalseExternalRequestUntilExplicitBack() {
        val restorationTester = StateRestorationTester(composeRule)
        var closeCount = 0
        restorationTester.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = emptyList(),
                    projects = emptyList(),
                    insightsState = populatedState(),
                    openInsights = false,
                    onInsightsClosed = { closeCount++ },
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
            .performClick()
        composeRule.onNodeWithTag("insights-screen").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("insights-screen").assertIsDisplayed()
        assertEquals(0, closeCount)

        composeRule.onNodeWithTag("insights-back").performClick()
        composeRule.onNodeWithTag("more-overview").assertIsDisplayed()
        assertEquals(1, closeCount)
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
            var state by remember {
                mutableStateOf(populatedState().copy(snapshot = emptySnapshot()))
            }
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
            var includeConflicted by remember { mutableStateOf(false) }
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

        composeRule.onNodeWithText("Trusted time 1 h").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Conflicted time 30 min, excluded")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Included total 1 h")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag("insights-include-conflicted")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("Conflicted time 30 min, included")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Included total 1 h 30 min")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun chartAndTableExposeTheSameOrderedLabelsAndValues() {
        composeRule.setContent {
            var presentation by remember { mutableStateOf(InsightsPresentation.CHART) }
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
        composeRule.onAllNodesWithTag("dot-run-bar", useUnmergedTree = true)
            .assertCountEquals(8)
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Alpha, 1 h").assertExists()
        composeRule.onNodeWithContentDescription("Focus, 45 min").assertExists()
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
    fun qualifiedEngineFieldsRemainExplicitInChartAndTablePresentations() {
        composeRule.setContent {
            var presentation by remember { mutableStateOf(InsightsPresentation.CHART) }
            OpenTasksTheme {
                InsightsScreen(
                    state = qualifiedState().copy(presentation = presentation),
                    onRangeChange = {},
                    onProjectFilter = { _, _ -> },
                    onTagFilter = { _, _ -> },
                    onIncludeConflictedTimeChange = {},
                    onPresentationChange = { presentation = it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("1 more completed task than the previous range.")
            .performScrollTo()
            .assertIsDisplayed()
        listOf(
            "1–7 days overdue",
            "8–30 days overdue",
            "31+ days overdue",
            "Due soon",
            "Due earlier",
            "Due oldest",
        ).forEach { text ->
            composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText(
            "Tag totals may overlap because a task can have more than one tag.",
        ).performScrollTo().assertIsDisplayed()
        val oldest = composeRule.onNodeWithTag("insights-overdue-row-due-oldest")
            .getUnclippedBoundsInRoot()
        val earlier = composeRule.onNodeWithTag("insights-overdue-row-due-earlier")
            .getUnclippedBoundsInRoot()
        val soon = composeRule.onNodeWithTag("insights-overdue-row-due-soon")
            .getUnclippedBoundsInRoot()
        assertTrue("Engine overdue order should be preserved across groups", oldest.top < earlier.top)
        assertTrue("Engine overdue order should be preserved across groups", earlier.top < soon.top)

        listOf(
            "On track" to "Due 30 Jul 2026",
            "At risk" to "Due 31 Jul 2026",
            "Blocked" to "No due date",
            "Complete" to "Due 1 Aug 2026",
        ).forEachIndexed { index, (health, due) ->
            composeRule.onNodeWithTag("insights-milestone-row-$index")
                .performScrollTo()
                .assertIsDisplayed()
                .assertTextContains("Project health: $health")
                .assertTextContains(due)
        }

        composeRule.onNodeWithTag("insights-presentation-table")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("insights-table").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Tag totals may overlap because a task can have more than one tag.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("insights-overdue-row-due-oldest")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Due oldest")
        composeRule.onNodeWithText(
            "1 Jun 2026 • Alpha",
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun positiveSubMinuteDurationsKeepNonZeroLabelsAndBarRatios() {
        val state = populatedState().copy(
            snapshot = populatedState().snapshot.copy(
                estimateActual = populatedState().snapshot.estimateActual.copy(
                    estimated = Duration.ofSeconds(30),
                    actual = DurationQuality(
                        trusted = Duration.ofSeconds(1),
                        conflicted = Duration.ZERO,
                        included = Duration.ofSeconds(1),
                    ),
                ),
                projectTime = listOf(
                    ProjectTimeRow(
                        projectId = ProjectId("alpha"),
                        displayName = "Alpha",
                        duration = DurationQuality(
                            trusted = Duration.ofSeconds(1),
                            conflicted = Duration.ZERO,
                            included = Duration.ofSeconds(1),
                        ),
                    ),
                ),
                tagTime = listOf(
                    TagTimeRow(
                        tagId = TagId("focus"),
                        displayName = "Focus",
                        duration = DurationQuality(
                            trusted = Duration.ofMillis(500),
                            conflicted = Duration.ZERO,
                            included = Duration.ofMillis(500),
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

        composeRule.onNodeWithContentDescription("Alpha, <1 min")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Focus, <1 min")
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(
            durationRatio(Duration.ofMillis(500), Duration.ofSeconds(30)) > 0f,
        )
        assertTrue(
            durationBarProgress(Duration.ofMillis(1), Duration.ofSeconds(30)) >= 0.01f,
        )
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
            var state by remember { mutableStateOf(populatedState()) }
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
    fun compactLightTwoHundredPercentStacksLongOverdueLabelAboveMetadata() {
        val base = populatedState()
        val state = base.copy(
            snapshot = base.snapshot.copy(
                overdue = listOf(
                    OverdueRow(
                        taskId = TaskId("long-overdue"),
                        title = "Reconcile July invoices",
                        projectName = "Quarterly accounts",
                        dueAt = Instant.parse("2026-07-27T12:00:00Z"),
                        band = OverdueBand.ONE_TO_SEVEN_DAYS,
                    ),
                ),
            ),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                OpenTasksTheme {
                    Box(
                        modifier = Modifier
                            .width(320.dp)
                            .height(640.dp),
                    ) {
                        TestInsightsScreen(state)
                    }
                }
            }
        }

        composeRule.onNodeWithTag("insights-overdue-row-long-overdue")
            .performScrollTo()
            .assertIsDisplayed()
        val label = composeRule.onNodeWithText(
            text = "Reconcile July invoices",
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()
        val metadata = composeRule.onNodeWithText(
            text = "Quarterly accounts",
            substring = true,
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()

        assertTrue(
            "At compact 200% text, the overdue label should be above long metadata",
            metadata.top > label.top,
        )
    }

    @Test
    fun expandedFoldableContentUsesTwoColumnsAfterTheNavigationRail() {
        composeRule.setContent {
            OpenTasksTheme {
                Box(
                    modifier = Modifier
                        .requiredWidth(772.dp)
                        .height(800.dp),
                ) {
                    TestInsightsScreen(populatedState())
                }
            }
        }

        val projects = composeRule.onNodeWithText("Projects")
            .getUnclippedBoundsInRoot()
        val view = composeRule.onNodeWithText("View")
            .getUnclippedBoundsInRoot()

        assertTrue(
            "The report should be to the right of filters at the usable expanded width",
            view.left > projects.left,
        )
        assertEquals(projects.top.value, view.top.value, 1f)
    }

    @Test
    fun contentWidthBelowSevenHundredTwentyDpUsesStackedSections() {
        composeRule.setContent {
            OpenTasksTheme {
                Box(
                    modifier = Modifier
                        .requiredWidth(719.dp)
                        .height(800.dp),
                ) {
                    TestInsightsScreen(populatedState())
                }
            }
        }

        val projects = composeRule.onNodeWithText("Projects")
            .getUnclippedBoundsInRoot()
        val view = composeRule.onNodeWithText("View")
            .getUnclippedBoundsInRoot()

        assertTrue("The report should be below filters at 719 dp", view.top > projects.top)
    }

    @Test
    fun contentWidthAtSevenHundredTwentyDpUsesTwoColumns() {
        composeRule.setContent {
            OpenTasksTheme {
                Box(
                    modifier = Modifier
                        .requiredWidth(720.dp)
                        .height(800.dp),
                ) {
                    TestInsightsScreen(populatedState())
                }
            }
        }

        val projects = composeRule.onNodeWithText("Projects")
            .getUnclippedBoundsInRoot()
        val view = composeRule.onNodeWithText("View")
            .getUnclippedBoundsInRoot()

        assertTrue("The report should be right of filters at 720 dp", view.left > projects.left)
        assertEquals(projects.top.value, view.top.value, 1f)
    }

    @Test
    fun expandedTwoHundredPercentTextKeepsQualifiedContentReachable() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                OpenTasksTheme {
                    Box(
                        modifier = Modifier
                            .requiredWidth(720.dp)
                            .height(640.dp),
                    ) {
                        TestInsightsScreen(qualifiedState())
                    }
                }
            }
        }

        val projects = composeRule.onNodeWithText("Projects")
            .getUnclippedBoundsInRoot()
        val view = composeRule.onNodeWithText("View")
            .getUnclippedBoundsInRoot()
        assertTrue(
            "At 200% text, the report should stack below filters",
            view.top > projects.top,
        )
        composeRule.onNodeWithTag("insights-milestone-row-3")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Project health: Complete")
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

        val traversal = listOf(
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
        )
        composeRule.onNodeWithTag(traversal.first())
            .performSemanticsAction(SemanticsActions.RequestFocus)

        traversal.forEachIndexed { index, tag ->
            val node = composeRule.onNodeWithTag(tag)
                .assertIsFocused()
                .assertIsDisplayed()
            if (index < traversal.lastIndex) {
                node.performKeyInput { pressKey(Key.Tab) }
            }
        }
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

    @Test
    fun defaultEmptyCompletionTrendRendersExistingFixturesWithoutATrendSection() {
        composeRule.setContent { OpenTasksTheme { TestInsightsScreen(populatedState()) } }
        composeRule.onNodeWithTag("insights-completion-trend-scroll").assertDoesNotExist()
    }

    @Test
    fun completionTrendIsDataForAnOtherwiseEmptySnapshot() {
        composeRule.setContent { OpenTasksTheme { TestInsightsScreen(trendOnlyState()) } }

        composeRule.onNodeWithText("No insight data for these 7 days.").assertDoesNotExist()
        composeRule.onNodeWithTag("insights-completion-trend-scroll").assertExists()
    }

    @Test
    fun sevenDayTrendChartHasOneExactSummary() {
        composeRule.setContent { OpenTasksTheme { TestInsightsScreen(trendState(7)) } }

        composeRule.onAllNodesWithContentDescription(
            "7 completions from 4 Aug 2026 to 10 Aug 2026",
        ).assertCountEquals(1)
    }

    @Test
    fun sevenDayTrendTableHasExactlyOneRowPerPoint() {
        composeRule.setContent {
            OpenTasksTheme {
                TestInsightsScreen(
                    trendState(7).copy(presentation = InsightsPresentation.TABLE),
                )
            }
        }

        composeRule.onAllNodes(hasCompletionTrendDayTag).assertCountEquals(7)
        listOf(
            Triple("2026-08-04", "4 Aug 2026", "0 tasks"),
            Triple("2026-08-05", "5 Aug 2026", "1 task"),
            Triple("2026-08-06", "6 Aug 2026", "0 tasks"),
            Triple("2026-08-07", "7 Aug 2026", "2 tasks"),
            Triple("2026-08-08", "8 Aug 2026", "1 task"),
            Triple("2026-08-09", "9 Aug 2026", "0 tasks"),
            Triple("2026-08-10", "10 Aug 2026", "3 tasks"),
        ).forEach { (date, label, value) ->
            composeRule.onNodeWithTag("insights-completion-day-$date")
                .assertExists()
                .assertTextContains(label)
                .assertTextContains(value)
        }
    }

    @Test
    fun ninetyDayTrendScrollsAndKeepsDotsDecorative() {
        composeRule.setContent { OpenTasksTheme { TestInsightsScreen(trendState(90)) } }
        composeRule.onNodeWithTag(
            "insights-completion-trend-scroll",
            useUnmergedTree = true,
        ).assert(hasScrollAction())
        composeRule.onNodeWithTag("dotted-area-chart", useUnmergedTree = true).assertExists()
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

    private fun trendState(dayCount: Int): InsightsUiState {
        require(dayCount > 0)
        val counts = if (dayCount == 7) {
            listOf(0L, 1L, 0L, 2L, 1L, 0L, 3L)
        } else {
            List(dayCount) { index -> if (index % 10 == 0) 1L else 0L }
        }
        val lastDate = LocalDate.of(2026, 8, 10)
        val points = counts.mapIndexed { index, completed ->
            CompletionTrendPoint(
                date = lastDate.minusDays((counts.lastIndex - index).toLong()),
                completed = completed,
            )
        }
        val base = populatedState()
        return base.copy(
            snapshot = base.snapshot.copy(completionTrend = points),
        )
    }

    private fun trendOnlyState(): InsightsUiState {
        val points = trendState(7).snapshot.completionTrend
        return emptyState().copy(
            snapshot = emptySnapshot().copy(completionTrend = points),
        )
    }

    private fun qualifiedState(): InsightsUiState = populatedState().copy(
        snapshot = populatedState().snapshot.copy(
            overdue = listOf(
                OverdueRow(
                    taskId = TaskId("due-oldest"),
                    title = "Due oldest",
                    projectName = "Alpha",
                    dueAt = Instant.parse("2026-06-01T12:00:00Z"),
                    band = OverdueBand.THIRTY_ONE_DAYS_OR_MORE,
                ),
                OverdueRow(
                    taskId = TaskId("due-earlier"),
                    title = "Due earlier",
                    projectName = "Beta",
                    dueAt = Instant.parse("2026-07-01T12:00:00Z"),
                    band = OverdueBand.EIGHT_TO_THIRTY_DAYS,
                ),
                OverdueRow(
                    taskId = TaskId("due-soon"),
                    title = "Due soon",
                    projectName = null,
                    dueAt = Instant.parse("2026-07-24T12:00:00Z"),
                    band = OverdueBand.ONE_TO_SEVEN_DAYS,
                ),
            ),
            milestoneHealth = listOf(
                milestone(0, ProjectHealth.ON_TRACK, "2026-07-30T12:00:00Z"),
                milestone(1, ProjectHealth.AT_RISK, "2026-07-31T12:00:00Z"),
                milestone(2, ProjectHealth.BLOCKED, null),
                milestone(3, ProjectHealth.COMPLETE, "2026-08-01T12:00:00Z"),
            ),
        ),
    )

    private fun milestone(
        index: Int,
        health: ProjectHealth,
        dueAt: String?,
    ): MilestoneHealthRow = MilestoneHealthRow(
        milestoneId = MilestoneId("milestone-$index"),
        displayName = "Milestone $index",
        projectName = "Project $index",
        dueAt = dueAt?.let(Instant::parse),
        projectHealth = health,
        completedTasks = index.toLong(),
        totalTasks = 4,
        overdueTasks = if (index == 1) 1 else 0,
    )

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
