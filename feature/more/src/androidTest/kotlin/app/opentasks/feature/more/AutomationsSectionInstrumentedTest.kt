package app.opentasks.feature.more

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.AutomationRule
import app.opentasks.core.model.AutomationRuleId
import app.opentasks.core.model.AutomationRuleType
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.WorkflowStatusId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationsSectionInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun automationsListRulesToggleEnabledAndDelete() {
        var updated: AutomationRule? = null
        var deleted: AutomationRuleId? = null
        val rule = AutomationRule(
            id = AutomationRuleId("rule-1"),
            workspaceId = OpenTasksFixtures.workspaceId,
            type = AutomationRuleType.STALE_BADGE,
            enabled = true,
            thresholdDays = 14,
        )
        composeRule.setContent {
            OpenTasksTheme {
                AutomationsSection(
                    rules = listOf(rule),
                    projects = OpenTasksFixtures.snapshot.projects,
                    workflowStatuses = OpenTasksFixtures.workflowStatuses,
                    tags = OpenTasksFixtures.tags,
                    onCreateRule = {},
                    onUpdateRule = { updated = it },
                    onDeleteRule = { deleted = it },
                )
            }
        }
        composeRule.onNodeWithTag("automation-enabled-rule-1").performClick()
        assertEquals(false, requireNotNull(updated).enabled)
        composeRule.onNodeWithTag("automation-delete-rule-1").performClick()
        composeRule.onNodeWithTag("automation-delete-confirm").performClick()
        assertEquals(rule.id, deleted)
    }

    @Test
    fun addFlowGatesPerTypeConfigAndEmitsAValidRule() {
        var created: AutomationRule? = null
        composeRule.setContent {
            OpenTasksTheme {
                AutomationsSection(
                    rules = emptyList(),
                    projects = OpenTasksFixtures.snapshot.projects,
                    workflowStatuses = OpenTasksFixtures.workflowStatuses,
                    tags = OpenTasksFixtures.tags,
                    onCreateRule = { created = it },
                    onUpdateRule = {},
                    onDeleteRule = {},
                )
            }
        }

        composeRule.onNodeWithTag("automation-add").performClick()
        composeRule.onNodeWithTag("automation-type-on_enter_add_tag").performClick()
        composeRule.onNodeWithTag("automation-create-confirm").assertIsNotEnabled()

        composeRule.onNodeWithTag("automation-status-picker").performClick()
        composeRule.onNodeWithTag(
            "automation-status-option-${OpenTasksFixtures.started.value}",
        ).performClick()
        composeRule.onNodeWithTag("automation-create-confirm").assertIsNotEnabled()

        composeRule.onNodeWithTag("automation-tag-picker").performClick()
        composeRule.onNodeWithTag("automation-tag-option-tag-deep-work").performClick()
        composeRule.onNodeWithTag("automation-create-confirm").assertIsEnabled()
        composeRule.onNodeWithTag("automation-create-confirm").performClick()

        val rule = requireNotNull(created)
        assertEquals(AutomationRuleType.ON_ENTER_ADD_TAG, rule.type)
        assertNotNull(rule.statusId)
        assertNotNull(rule.tagId)
    }

    @Test
    fun brokenReferenceRuleRendersItsErrorState() {
        val rule = AutomationRule(
            id = AutomationRuleId("rule-broken"),
            workspaceId = OpenTasksFixtures.workspaceId,
            type = AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY,
            enabled = true,
            statusId = WorkflowStatusId("missing-status"),
        )
        composeRule.setContent {
            OpenTasksTheme {
                AutomationsSection(
                    rules = listOf(rule),
                    projects = OpenTasksFixtures.snapshot.projects,
                    workflowStatuses = OpenTasksFixtures.workflowStatuses,
                    tags = OpenTasksFixtures.tags,
                    onCreateRule = {},
                    onUpdateRule = {},
                    onDeleteRule = {},
                )
            }
        }

        composeRule.onNodeWithText("This rule points at something that no longer exists")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("automation-delete-rule-broken").assertIsDisplayed()
    }
}
