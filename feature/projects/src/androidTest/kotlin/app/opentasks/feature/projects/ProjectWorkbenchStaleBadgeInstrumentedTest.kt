package app.opentasks.feature.projects

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.designsystem.R as DesignSystemR
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.TaskGroup
import app.opentasks.core.model.TaskId
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectWorkbenchStaleBadgeInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun staleTaskIdsMembershipExposesTheBadgeOnlyForMatchingWorkbenchRows() {
        val project = OpenTasksFixtures.studioProject
        val base = OpenTasksFixtures.tasks.first { it.projectId == project.id }
            .copy(completedAt = null, deletedAt = null)
        val stale = base.copy(id = TaskId("stale-workbench-task"), title = "Stale workbench task")
        val fresh = base.copy(id = TaskId("fresh-workbench-task"), title = "Fresh workbench task")

        composeRule.setContent {
            OpenTasksTheme {
                ProjectsScreen(
                    projects = OpenTasksFixtures.snapshot.projects,
                    tasks = listOf(stale, fresh),
                    milestones = OpenTasksFixtures.milestones.filter { it.projectId == project.id },
                    workflowStatuses = OpenTasksFixtures.snapshot.workflowStatuses,
                    selectedProjectId = project.id,
                    showDetailPane = false,
                    workbenchTaskGroups = listOf(TaskGroup(null, listOf(stale, fresh))),
                    onSelectProject = {},
                    onCloseDetail = {},
                    onUpdateProject = { _, _ -> },
                    onArchiveProject = {},
                    onOpenTask = {},
                    staleTaskIds = setOf(stale.id),
                )
            }
        }

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("workbench-task-${fresh.id.value}"))

        composeRule
            .onAllNodesWithContentDescription(
                context.getString(DesignSystemR.string.task_stale_description),
            )
            .assertCountEquals(1)
    }
}
