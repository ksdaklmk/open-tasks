package app.opentasks.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyDayPlanSheetInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun planSheetListsMembersAndAddsSuggestionsWithOneTap() {
        val members = listOf(
            OpenTasksFixtures.tasks.first { it.id.value == "task-proposal" },
            OpenTasksFixtures.tasks.first { it.id.value == "task-domain" },
        )
        val suggestions = listOf(
            OpenTasksFixtures.tasks.first { it.id.value == "task-invoices" },
            OpenTasksFixtures.tasks.first { it.id.value == "task-transcript" },
        )
        var added: TaskId? = null
        var removed: TaskId? = null

        composeRule.setContent {
            OpenTasksTheme {
                MyDayPlanSheet(
                    members = members,
                    suggestions = suggestions,
                    projectNames = emptyMap(),
                    onDismiss = {},
                    onAddToMyDay = { added = it },
                    onRemoveFromMyDay = { removed = it },
                    onMoveMyDayEntry = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Plan My Day").assertIsDisplayed()
        composeRule.onNodeWithTag("my-day-row-${members.first().id.value}").assertIsDisplayed()
        composeRule.onNodeWithText("Suggested for today").assertIsDisplayed()

        val suggestion = suggestions.first()
        composeRule.onNodeWithTag("my-day-suggestion-add-${suggestion.id.value}")
            .performClick()
        assertEquals(suggestion.id, added)

        composeRule.onNodeWithTag("my-day-menu-${members.first().id.value}").performClick()
        composeRule.onNodeWithTag("my-day-remove-${members.first().id.value}").performClick()
        assertEquals(members.first().id, removed)
    }

    @Test
    fun emptySuggestionsHideTheSuggestionsSection() {
        composeRule.setContent {
            OpenTasksTheme {
                MyDayPlanSheet(
                    members = listOf(
                        OpenTasksFixtures.tasks.first { it.id.value == "task-proposal" },
                    ),
                    suggestions = emptyList(),
                    projectNames = emptyMap(),
                    onDismiss = {},
                    onAddToMyDay = {},
                    onRemoveFromMyDay = {},
                    onMoveMyDayEntry = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Suggested for today").assertDoesNotExist()
    }
}
