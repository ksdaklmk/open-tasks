package app.opentasks.feature.projects

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.designsystem.R as DesignSystemR
import app.opentasks.core.model.BoardColumn
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.TaskId
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoardStaleBadgeInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun staleTaskIdsMembershipExposesTheBadgeOnlyForMatchingCards() {
        val base = OpenTasksFixtures.tasks.first().copy(completedAt = null, deletedAt = null)
        val backlog = OpenTasksFixtures.workflowStatuses.single {
            it.id == OpenTasksFixtures.backlog
        }
        val stale = base.copy(
            id = TaskId("stale-card"),
            title = "Stale card",
            statusId = backlog.id,
            semanticStatus = backlog.semanticStatus,
        )
        val fresh = base.copy(
            id = TaskId("fresh-card"),
            title = "Fresh card",
            statusId = backlog.id,
            semanticStatus = backlog.semanticStatus,
        )

        composeRule.setContent {
            OpenTasksTheme {
                BoardView(
                    columns = listOf(BoardColumn(status = backlog, tasks = listOf(stale, fresh))),
                    columnWidth = 272.dp,
                    onMoveTask = { _, _ -> },
                    onOpenTask = {},
                    staleTaskIds = setOf(stale.id),
                )
            }
        }

        composeRule
            .onAllNodesWithContentDescription(
                context.getString(DesignSystemR.string.task_stale_description),
            )
            .assertCountEquals(1)
    }
}
