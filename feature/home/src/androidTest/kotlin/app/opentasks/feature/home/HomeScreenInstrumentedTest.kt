package app.opentasks.feature.home

import android.view.ViewConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.DurationQuality
import app.opentasks.core.model.EstimateActual
import app.opentasks.core.model.InsightsQuality
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.InstantRange
import app.opentasks.core.model.MetricComparison
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.TaskId
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * `OpenTasksFixtures` has no shared `insightsSummary` member yet — Home has
 * no prior Compose tests to copy one from. `HomeScreen` only reads
 * `completed.current` and `quality.recordedTime.included` off it, so a
 * minimal, valid [InsightsSnapshot] is enough. Kept as a file-local
 * extension property (not a `core:model` change) so this stays inside
 * feature/home's own test source set.
 */
private val OpenTasksFixtures.insightsSummary: InsightsSnapshot
    get() = InsightsSnapshot(
        interval = InstantRange(Instant.EPOCH, Instant.EPOCH.plusSeconds(1)),
        comparisonInterval = InstantRange(Instant.EPOCH, Instant.EPOCH.plusSeconds(1)),
        completed = MetricComparison(current = 4, previous = 3),
        overdue = emptyList(),
        estimateActual = EstimateActual(
            estimated = Duration.ZERO,
            actual = DurationQuality(trusted = Duration.ZERO, conflicted = Duration.ZERO),
        ),
        projectTime = emptyList(),
        tagTime = emptyList(),
        milestoneHealth = emptyList(),
        quality = InsightsQuality(
            recordedTime = DurationQuality(trusted = Duration.ZERO, conflicted = Duration.ZERO),
        ),
    )

@RunWith(AndroidJUnit4::class)
class HomeScreenInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun myDaySectionRendersRankOrderDimsCompletedAndFallsBackToMenu() {
        var moved: Pair<TaskId, TaskId?>? = null
        var removed: TaskId? = null
        val open = OpenTasksFixtures.tasks.first { !it.isCompleted && it.deletedAt == null }
        val done = OpenTasksFixtures.tasks.first { it.isCompleted }
        val snapshot = OpenTasksFixtures.snapshot.home.copy(
            myDayTasks = listOf(open, done),
        )
        composeRule.setContent {
            OpenTasksTheme {
                HomeScreen(
                    snapshot = snapshot,
                    projectNames = emptyMap(),
                    onOpenSearch = {}, onPlanToday = {},
                    onOpenTask = {}, onCompleteTask = {},
                    onOpenProject = {},
                    insightsSummary = OpenTasksFixtures.insightsSummary,
                    onOpenInsights = {}, onToggleTimer = {},
                    onRemoveFromMyDay = { removed = it },
                    onMoveMyDayEntry = { id, after -> moved = id to after },
                )
            }
        }
        composeRule.onNodeWithText("My Day").assertIsDisplayed()
        composeRule.onNodeWithTag("my-day-row-${done.id.value}").assertIsDisplayed()

        composeRule.onNodeWithTag("my-day-menu-${done.id.value}").performClick()
        composeRule.onNodeWithTag("my-day-move-up-${done.id.value}").performClick()
        assertEquals(done.id to null, moved)

        composeRule.onNodeWithTag("my-day-menu-${open.id.value}").performClick()
        composeRule.onNodeWithTag("my-day-remove-${open.id.value}").performClick()
        assertEquals(open.id, removed)
    }

    @Test
    fun longPressDragOntoOwnSuccessorRowIsANoOp() {
        // order.getOrNull(hoveredIndex - 1) resolves to the dragged row
        // itself when it is hovering its own immediate successor (it was
        // that row's predecessor before the drag started). MoveMyDayEntry
        // rejects a self-referential afterTaskId, so the drag must skip
        // dispatch here rather than fire a command the repository refuses.
        val open = OpenTasksFixtures.tasks.first { !it.isCompleted && it.deletedAt == null }
        val done = OpenTasksFixtures.tasks.first { it.isCompleted }
        val snapshot = OpenTasksFixtures.snapshot.home.copy(
            myDayTasks = listOf(open, done),
        )
        var moved: Pair<TaskId, TaskId?>? = null
        composeRule.setContent {
            OpenTasksTheme {
                HomeScreen(
                    snapshot = snapshot,
                    projectNames = emptyMap(),
                    onOpenSearch = {}, onPlanToday = {},
                    onOpenTask = {}, onCompleteTask = {},
                    onOpenProject = {},
                    insightsSummary = OpenTasksFixtures.insightsSummary,
                    onOpenInsights = {}, onToggleTimer = {},
                    onRemoveFromMyDay = {},
                    onMoveMyDayEntry = { id, after -> moved = id to after },
                )
            }
        }
        val openBounds = composeRule
            .onNodeWithTag("my-day-row-${open.id.value}")
            .fetchSemanticsNode()
            .boundsInRoot
        val doneBounds = composeRule
            .onNodeWithTag("my-day-row-${done.id.value}")
            .fetchSemanticsNode()
            .boundsInRoot

        composeRule.onRoot().performTouchInput {
            down(openBounds.center)
            advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 1)
            moveTo(doneBounds.center)
        }
        composeRule.onNodeWithTag("my-day-drag-preview-${open.id.value}").assertIsDisplayed()
        composeRule.onRoot().performTouchInput { up() }
        composeRule.waitForIdle()

        assertNull(moved)
    }

    @Test
    fun emptyMyDayShowsThePlanPrompt() {
        composeRule.setContent {
            OpenTasksTheme {
                HomeScreen(
                    snapshot = OpenTasksFixtures.snapshot.home.copy(
                        myDayTasks = emptyList(),
                    ),
                    projectNames = emptyMap(),
                    onOpenSearch = {}, onPlanToday = {},
                    onOpenTask = {}, onCompleteTask = {},
                    onOpenProject = {},
                    insightsSummary = OpenTasksFixtures.insightsSummary,
                    onOpenInsights = {}, onToggleTimer = {},
                    onRemoveFromMyDay = {},
                    onMoveMyDayEntry = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithTag("my-day-empty").assertIsDisplayed()
    }
}
