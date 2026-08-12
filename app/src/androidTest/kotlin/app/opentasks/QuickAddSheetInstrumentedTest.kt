package app.opentasks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.QuickAddTokenKind
import app.opentasks.core.domain.parseQuickAdd
import app.opentasks.core.domain.stripQuickAddToken
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.ZonedMoment
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class QuickAddSheetInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.of("Asia/Bangkok")
    private val now = Instant.parse("2026-08-10T03:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val projects = listOf(OpenTasksFixtures.studioProject, OpenTasksFixtures.taxProject)
    private val tags = OpenTasksFixtures.tags

    private fun parsed(text: String) = parseQuickAdd(text, now, zone, projects, tags)

    private fun confirm(
        text: String,
        kind: QuickAddTokenKind,
        occurrence: Int = 0,
    ): String {
        val matches = parsed(text)
        val match = matches.filter { it.kind == kind }[occurrence]
        composeRule.onNodeWithTag(suggestionTag(match)).performScrollTo().performClick()
        return stripQuickAddToken(text, match)
    }

    private fun setSheet(onAdd: (DomainCommand.CreateTask) -> Unit = {}) {
        composeRule.setContent {
            OpenTasksTheme {
                QuickAddSheet(
                    onDismiss = {},
                    onAdd = onAdd,
                    projects = projects,
                    tags = tags,
                    clock = clock,
                )
            }
        }
    }

    @Test
    fun largeTextActionsCanBeReachedWithImeVisible() {
        val imeVisible = AtomicBoolean(false)
        val submittedTitle = AtomicReference<String?>()

        composeRule.setContent {
            val density = LocalDensity.current
            val imeBottom = WindowInsets.ime.getBottom(density)
            SideEffect { imeVisible.set(imeBottom > 0) }

            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                OpenTasksTheme {
                    QuickAddSheet(
                        onDismiss = {},
                        onAdd = { command -> submittedTitle.set(command.title) },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("quick-add-title").performTextInput("Reachable task")

        // Whether a stock CI emulator image has a software keyboard
        // registered at all is an environment property this test cannot
        // control -- waiting on the real platform IME to attach makes the
        // test's outcome depend on that availability rather than on the
        // app's own `Modifier.imePadding()` layout, which is what this
        // test is actually meant to prove. Drive the same window-inset
        // dispatch a real keyboard would produce directly, so the
        // assertions below exercise that layout path deterministically
        // regardless of the host's IME.
        composeRule.runOnUiThread {
            val decorView = composeRule.activity.window.decorView
            val simulatedImeInsetPx =
                (300 * composeRule.activity.resources.displayMetrics.density).toInt()
            val current = ViewCompat.getRootWindowInsets(decorView)
                ?: WindowInsetsCompat.CONSUMED
            val simulated = WindowInsetsCompat.Builder(current)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, simulatedImeInsetPx))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(decorView, simulated)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { imeVisible.get() }

        composeRule.onNode(hasText("Cancel") and hasClickAction())
            .performScrollTo()
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNode(hasText("Add task") and hasClickAction())
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
            .performClick()

        assertTrue(imeVisible.get())
        assertEquals("Reachable task", submittedTitle.get())
    }

    @Test
    fun detectedTokensDoNothingUntilConfirmed() {
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        val text = "Plan: #stu @Admin !1 ~45m tomorrow"
        setSheet(submitted::set)

        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)
        composeRule.onNode(hasText("Add task") and hasClickAction())
            .performScrollTo()
            .performClick()

        assertEquals(DomainCommand.CreateTask(text), submitted.get())
    }

    @Test
    fun confirmingEveryGrammarTokenSubmitsTheExactCommand() {
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        var text = "Plan #stu @Admin !1 every monday ~45m tomorrow"
        setSheet(submitted::set)
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)

        text = confirm(text, QuickAddTokenKind.RECURRENCE)
        while (parsed(text).isNotEmpty()) {
            val match = parsed(text).last()
            composeRule.onNodeWithTag(suggestionTag(match)).performScrollTo().performClick()
            text = stripQuickAddToken(text, match)
        }
        listOf(
            "quick-add-applied-project",
            "quick-add-applied-priority",
            "quick-add-date-chip",
            "quick-add-applied-recurrence",
            "quick-add-applied-estimate",
            "quick-add-applied-tag-admin",
            "quick-add-clear-project",
            "quick-add-clear-priority",
            "quick-add-date-clear",
            "quick-add-clear-recurrence",
            "quick-add-clear-estimate",
            "quick-add-clear-tag-admin",
        ).forEach { composeRule.onNodeWithTag(it).assertExists() }
        composeRule.onNode(hasText("Add task") and hasClickAction())
            .performScrollTo()
            .performClick()

        assertEquals(
            DomainCommand.CreateTask(
                title = "Plan",
                projectId = OpenTasksFixtures.studioProject.id,
                priority = Priority.URGENT,
                due = ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), zone.id),
                tagNames = listOf("Admin"),
                estimate = Duration.ofMinutes(45),
                recurrence = RecurrenceRule(
                    RecurrenceFrequency.WEEKLY,
                    weekdays = setOf(DayOfWeek.MONDAY),
                ),
            ),
            submitted.get(),
        )
    }

    @Test
    fun confirmingAProjectReplacesTheAppliedProject() {
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        var text = "Task #stu #quarter"
        setSheet(submitted::set)
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)

        text = confirm(text, QuickAddTokenKind.PROJECT)
        confirm(text, QuickAddTokenKind.PROJECT)
        composeRule.onNodeWithText("Add task").performScrollTo().performClick()

        assertEquals(OpenTasksFixtures.taxProject.id, submitted.get()?.projectId)
    }

    @Test
    fun confirmingAPriorityReplacesTheAppliedPriority() {
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        var text = "Task !4 !1"
        setSheet(submitted::set)
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)

        text = confirm(text, QuickAddTokenKind.PRIORITY)
        confirm(text, QuickAddTokenKind.PRIORITY)
        composeRule.onNodeWithText("Add task").performScrollTo().performClick()

        assertEquals(Priority.URGENT, submitted.get()?.priority)
    }

    @Test
    fun confirmingTagsAccumulatesCaseInsensitively() {
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        var text = "Task @Admin @admin @Roadmap"
        setSheet(submitted::set)
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)

        text = confirm(text, QuickAddTokenKind.TAG)
        text = confirm(text, QuickAddTokenKind.TAG)
        confirm(text, QuickAddTokenKind.TAG)
        composeRule.onNodeWithText("Add task").performScrollTo().performClick()

        assertEquals(listOf("Admin", "Roadmap"), submitted.get()?.tagNames)
    }

    @Test
    fun editingTheTitleResetsADismissedSuggestion() {
        val original = "Plan @Admin"
        val match = parsed(original).single()
        setSheet()
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(original)

        composeRule.onNodeWithTag(dismissTag(match)).performClick()
        composeRule.onNodeWithTag(suggestionTag(match)).assertDoesNotExist()
        val edited = "$original now"
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(edited)

        composeRule.onNodeWithTag(suggestionTag(parsed(edited).single())).assertIsDisplayed()
    }

    @Test
    fun dismissedSuggestionSurvivesEarlierConfirmationAndReparse() {
        val original = "Plan #stu @Admin"
        val matches = parsed(original)
        val project = matches.first { it.kind == QuickAddTokenKind.PROJECT }
        val tag = matches.first { it.kind == QuickAddTokenKind.TAG }
        setSheet()
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(original)

        composeRule.onNodeWithTag(dismissTag(tag)).performClick()
        composeRule.onNodeWithTag(suggestionTag(project)).performClick()
        val reparsed = parsed("Plan @Admin").single()

        composeRule.onNodeWithTag(suggestionTag(reparsed)).assertDoesNotExist()
    }

    @Test
    fun identicalTokensAreIndividuallyDismissible() {
        val text = "@Admin @Admin"
        val matches = parsed(text)
        setSheet()
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)

        composeRule.onNodeWithTag(dismissTag(matches.last())).performClick()

        composeRule.onAllNodesWithText("Tag: Admin").assertCountEquals(1)
        composeRule.onNodeWithTag(suggestionTag(matches.first())).assertIsDisplayed()
        composeRule.onNodeWithTag(suggestionTag(matches.last())).assertDoesNotExist()
    }

    @Test
    fun tagSuggestionLabelsDistinguishExistingAndNewTags() {
        setSheet()
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement("@Roadmap")
        composeRule.onNodeWithText("New tag: Roadmap").assertIsDisplayed()

        composeRule.onNodeWithTag("quick-add-title").performTextReplacement("@Admin")
        composeRule.onNodeWithText("Tag: Admin").assertIsDisplayed()
    }

    @Test
    fun recurrenceSuggestionShowsItsImplicitDueBeforeConfirmation() {
        setSheet()
        composeRule.onNodeWithTag("quick-add-title")
            .performTextReplacement("Plan every monday")

        composeRule.onNodeWithText("Repeat: every monday · due 10 Aug 17:00")
            .assertIsDisplayed()
    }

    @Test
    fun recurrenceSuggestionShowsThePreservedExplicitDueBeforeConfirmation() {
        val text = "Plan tomorrow every monday"
        setSheet()
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)

        confirm(text, QuickAddTokenKind.DATE)

        composeRule.onNodeWithText("Repeat: every monday · due 11 Aug 17:00")
            .assertIsDisplayed()
    }

    @Test
    fun numericPrioritySuggestionsUseExactDisplayNames() {
        setSheet()

        mapOf(
            "!1" to "Priority: Urgent",
            "!2" to "Priority: High",
            "!3" to "Priority: Medium",
            "!4" to "Priority: Low",
        ).forEach { (token, label) ->
            composeRule.onNodeWithTag("quick-add-title").performTextReplacement(token)
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun dismissSuggestionIsFortyEightDpAndKeepsTheUnchangedTitle() {
        val text = "Plan tomorrow"
        val match = parsed(text).single()
        setSheet()
        composeRule.onNodeWithTag("quick-add-title").performTextInput(text)

        composeRule.onNodeWithTag(suggestionTag(match))
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(dismissTag(match), useUnmergedTree = true)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.onNodeWithTag(suggestionTag(match)).assertDoesNotExist()
        composeRule.onNodeWithTag("quick-add-title")
            .assertTextContains(text, substring = true)
    }

    @Test
    fun longSuggestionKeepsTheDismissTargetAtFortyEightDp() {
        val longProject = OpenTasksFixtures.studioProject.copy(
            name = "An intentionally very long project suggestion that must not steal dismiss width",
        )
        val text = "Task #an"
        val match = parseQuickAdd(text, now, zone, listOf(longProject), tags).single()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.width(280.dp)) {
                    OpenTasksTheme {
                        QuickAddSheet(
                            onDismiss = {},
                            onAdd = {},
                            projects = listOf(longProject),
                            tags = tags,
                            clock = clock,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)
        composeRule.onNodeWithTag(dismissTag(match), useUnmergedTree = true)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun longAppliedProjectKeepsTheClearTargetAtFortyEightDp() {
        val longProject = OpenTasksFixtures.studioProject.copy(
            name = "An intentionally very long applied project that must not steal clear width",
        )
        val text = "Task #an"
        val match = parseQuickAdd(text, now, zone, listOf(longProject), tags).single()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.width(280.dp)) {
                    OpenTasksTheme {
                        QuickAddSheet(
                            onDismiss = {},
                            onAdd = {},
                            projects = listOf(longProject),
                            tags = tags,
                            clock = clock,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)
        composeRule.onNodeWithTag(suggestionTag(match)).performClick()

        composeRule.onNodeWithTag("quick-add-applied-project")
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("quick-add-clear-project", useUnmergedTree = true)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun distinctFiftyFirstTagSuggestionStaysVisibleAndDisabled() {
        val appliedTokens = (0 until 50).map { "@${it.toString(36).padStart(2, '0')}" }
        var text = "Task ${appliedTokens.joinToString(" ")} @overflow"
        setSheet()
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)

        repeat(50) {
            text = confirm(text, QuickAddTokenKind.TAG)
        }
        val overflow = parsed(text).single { it.kind == QuickAddTokenKind.TAG }

        composeRule.onNodeWithTag(suggestionTag(overflow))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText("New tag: overflow · 50-tag limit reached")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("quick-add-title")
            .assertTextContains("@overflow", substring = true)
    }

    @Test
    fun duplicateTagAtLimitStillConfirms() {
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        val appliedTokens = listOf("@Admin") +
            (0 until 49).map { "@${it.toString(36).padStart(2, '0')}" }
        var text = "Task ${appliedTokens.joinToString(" ")} @admin"
        setSheet(submitted::set)
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)

        repeat(50) {
            text = confirm(text, QuickAddTokenKind.TAG)
        }
        val duplicate = parsed(text).single { it.kind == QuickAddTokenKind.TAG }
        composeRule.onNodeWithTag(suggestionTag(duplicate))
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithText("Add task").performScrollTo().performClick()

        assertEquals("Task", submitted.get()?.title)
        assertEquals(50, submitted.get()?.tagNames?.size)
        assertEquals(1, submitted.get()?.tagNames?.count { it.equals("Admin", true) })
    }

    @Test
    fun clearingDateAlsoClearsRecurrence() {
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        var text = "Plan every monday tomorrow"
        setSheet(submitted::set)
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)
        text = confirm(text, QuickAddTokenKind.RECURRENCE)
        confirm(text, QuickAddTokenKind.DATE)

        composeRule.onNodeWithTag("quick-add-date-clear").performClick()
        composeRule.onNodeWithText("Add task").performScrollTo().performClick()

        assertEquals(DomainCommand.CreateTask("Plan"), submitted.get())
    }

    @Test
    fun clearingRecurrenceKeepsTheExplicitDue() {
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        var text = "Plan tomorrow every monday"
        setSheet(submitted::set)
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)
        text = confirm(text, QuickAddTokenKind.DATE)
        confirm(text, QuickAddTokenKind.RECURRENCE)

        composeRule.onNodeWithTag("quick-add-clear-recurrence").performClick()
        composeRule.onNodeWithText("Add task").performScrollTo().performClick()

        assertEquals(
            DomainCommand.CreateTask(
                title = "Plan",
                due = ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), zone.id),
            ),
            submitted.get(),
        )
    }
}
