package app.opentasks

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
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
import kotlinx.coroutines.awaitCancellation
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class QuickAddSheetInstrumentedTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    private val zone = ZoneId.of("Asia/Bangkok")
    private val now = Instant.parse("2026-08-10T03:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val projects = listOf(OpenTasksFixtures.studioProject, OpenTasksFixtures.taxProject)
    private val tags = OpenTasksFixtures.tags

    private fun dispatchDialogIme() {
        onView(isRoot()).inRoot(isDialog()).perform(
            object : ViewAction {
                override fun getConstraints(): Matcher<View> = isRoot()

                override fun getDescription() = "dispatch a visible 300 dp dialog IME inset"

                override fun perform(uiController: UiController, view: View) {
                    val bottom = (300 * view.resources.displayMetrics.density).toInt()
                    ViewCompat.dispatchApplyWindowInsets(
                        view,
                        WindowInsetsCompat.Builder()
                            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                            .setVisible(WindowInsetsCompat.Type.ime(), true)
                            .build(),
                    )
                }
            },
        )
    }

    private fun parsed(text: String) = parseQuickAdd(text, now, zone, projects, tags)

    private fun confirm(
        text: String,
        kind: QuickAddTokenKind,
        occurrence: Int = 0,
    ): String {
        val matches = parsed(text)
        val match = matches.filter { it.kind == kind }[occurrence]
        composeRule.onNodeWithTag(suggestionTag(match))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
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

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun largeTextActionsCanBeReachedWithImeVisible() {
        val submittedTitle = AtomicReference<String?>()

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                OpenTasksTheme {
                    InterceptPlatformTextInput(
                        interceptor = remember {
                            PlatformTextInputInterceptor { _, _ -> awaitCancellation() }
                        },
                    ) {
                        QuickAddSheet(
                            onDismiss = {},
                            onAdd = { command -> submittedTitle.set(command.title) },
                        )
                    }
                }
            }
        }

        dispatchDialogIme()
        val title = composeRule.onNodeWithTag("quick-add-title")
        title.performTextInput("Reachable task")
        title.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.InputText,
                AnnotatedString("Reachable task"),
            ),
        )

        composeRule.onNode(hasText("Cancel") and hasClickAction())
            .performScrollTo()
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNode(hasText("Add task") and hasClickAction())
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { submittedTitle.get() != null }
        assertEquals("Reachable task", submittedTitle.get())
    }

    @Test
    fun detectedTokensDoNothingUntilConfirmed() {
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        val text = "Plan: #stu @Admin !1 ~45m tomorrow"
        setSheet(submitted::set)

        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)
        composeRule.onNodeWithTag("quick-add-title").performImeAction()

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
            composeRule.onNodeWithTag(suggestionTag(match))
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
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
        composeRule.onNodeWithTag("quick-add-title").performImeAction()

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
        composeRule.onNodeWithTag("quick-add-title").performImeAction()

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
        composeRule.onNodeWithTag("quick-add-title").performImeAction()

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
        composeRule.onNodeWithTag("quick-add-title").performImeAction()

        assertEquals(listOf("Admin", "Roadmap"), submitted.get()?.tagNames)
    }

    @Test
    fun editingTheTitleResetsADismissedSuggestion() {
        val original = "Plan @Admin"
        val match = parsed(original).single()
        setSheet()
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(original)

        composeRule.onNodeWithTag(dismissTag(match))
            .performSemanticsAction(SemanticsActions.OnClick)
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

        composeRule.onNodeWithTag(dismissTag(tag))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(suggestionTag(project))
            .performSemanticsAction(SemanticsActions.OnClick)
        val reparsed = parsed("Plan @Admin").single()

        composeRule.onNodeWithTag(suggestionTag(reparsed)).assertDoesNotExist()
    }

    @Test
    fun identicalTokensAreIndividuallyDismissible() {
        val text = "@Admin @Admin"
        val matches = parsed(text)
        setSheet()
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)

        composeRule.onNodeWithTag(dismissTag(matches.last()))
            .performSemanticsAction(SemanticsActions.OnClick)

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
        composeRule.onNodeWithTag(suggestionTag(match))
            .performSemanticsAction(SemanticsActions.OnClick)

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
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("quick-add-title").performImeAction()

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

        composeRule.onNodeWithTag("quick-add-date-clear")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("quick-add-title").performImeAction()

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

        composeRule.onNodeWithTag("quick-add-clear-recurrence")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("quick-add-title").performImeAction()

        assertEquals(
            DomainCommand.CreateTask(
                title = "Plan",
                due = ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), zone.id),
            ),
            submitted.get(),
        )
    }
}
