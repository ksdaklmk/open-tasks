package app.opentasks.input

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.QuickAddSheet
import app.opentasks.SearchSurface
import app.opentasks.suggestionTag
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.domain.DomainCommand
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the same key-event wiring `OpenTasksApp` puts at its root --
 * `Ctrl` combinations on the preview (top-down) pass, single keys on the
 * bubbling (bottom-up) pass -- against the real [SearchSurface] and
 * [QuickAddSheet] surfaces the dispatcher opens and closes. The mapping
 * itself is covered by the plain-JVM `ShortcutDispatcherTest`; this checks
 * that wiring it up the way `OpenTasksApp` does actually moves focus and
 * dismisses a surface. It needs a device, so per the task brief it is only
 * compiled now (`:app:compileDebugAndroidTestKotlin`) and runs starting at
 * Task 13.
 */
@RunWith(AndroidJUnit4::class)
class ShortcutRootWiringInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ctrlKOpensSearchAndFocusesTheQueryField() {
        val rootFocused = CountDownLatch(1)
        composeRule.setContent {
            val rootFocusRequester = remember { FocusRequester() }
            var showSearch by remember { mutableStateOf(false) }
            var editableFocused by remember { mutableStateOf(false) }
            OpenTasksTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("shortcut-root")
                        .focusRequester(rootFocusRequester)
                        .onFocusEvent { focusState ->
                            editableFocused = focusState.hasFocus
                            if (focusState.isFocused) rootFocused.countDown()
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                                return@onPreviewKeyEvent false
                            }
                            val action = shortcutActionFor(
                                key = event.key,
                                isCtrlPressed = true,
                                isShiftPressed = event.isShiftPressed,
                                inProjectsRoute = false,
                                editableFocused = editableFocused,
                            )
                            if (action == ShortcutAction.OPEN_SEARCH) showSearch = true
                            action == ShortcutAction.OPEN_SEARCH
                        }
                        .focusable(),
                ) {
                    if (showSearch) {
                        SearchSurface(
                            results = emptyList(),
                            onQueryChange = {},
                            onDismiss = { showSearch = false },
                            onOpenTask = {},
                            onOpenProject = {},
                        )
                    }
                }
            }
            LaunchedEffect(Unit) { rootFocusRequester.requestFocus() }
        }

        assertTrue(
            "The shortcut root never received Compose focus",
            rootFocused.await(10, TimeUnit.SECONDS),
        )

        composeRule.onNodeWithTag("shortcut-root").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.K) }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodes(hasTestTag("workspace-search-query") and isFocused())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("workspace-search-query").assertIsFocused()
    }

    @Test
    fun ctrlNOpensQuickAddAndSubmitsEveryConfirmedField() {
        val rootFocused = CountDownLatch(1)
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        val zone = ZoneId.of("Asia/Bangkok")
        val clock = Clock.fixed(Instant.parse("2026-08-10T03:00:00Z"), zone)
        val projects = listOf(OpenTasksFixtures.studioProject)
        val tags = OpenTasksFixtures.tags
        composeRule.setContent {
            val rootFocusRequester = remember { FocusRequester() }
            var showQuickAdd by remember { mutableStateOf(false) }
            var editableFocused by remember { mutableStateOf(false) }
            OpenTasksTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("shortcut-root")
                        .focusRequester(rootFocusRequester)
                        .onFocusEvent { focusState ->
                            editableFocused = focusState.hasFocus
                            if (focusState.isFocused) rootFocused.countDown()
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                                return@onPreviewKeyEvent false
                            }
                            val action = shortcutActionFor(
                                key = event.key,
                                isCtrlPressed = true,
                                isShiftPressed = event.isShiftPressed,
                                inProjectsRoute = false,
                                editableFocused = editableFocused,
                            )
                            if (action == ShortcutAction.QUICK_ADD) showQuickAdd = true
                            action == ShortcutAction.QUICK_ADD
                        }
                        .focusable(),
                ) {
                    if (showQuickAdd) {
                        QuickAddSheet(
                            onDismiss = { showQuickAdd = false },
                            onAdd = { command ->
                                submitted.set(command)
                                showQuickAdd = false
                            },
                            projects = projects,
                            tags = tags,
                            clock = clock,
                        )
                    }
                }
            }
            LaunchedEffect(Unit) { rootFocusRequester.requestFocus() }
        }

        assertTrue(
            "The shortcut root never received Compose focus",
            rootFocused.await(10, TimeUnit.SECONDS),
        )
        composeRule.onNodeWithTag("shortcut-root").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.N) }
        }

        var text = "Shortcut #stu @Admin !2 every monday tomorrow ~45m"
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)
        while (true) {
            val matches = parseQuickAdd(text, clock.instant(), clock.zone, projects, tags)
            val match = matches.lastOrNull() ?: break
            composeRule.onNodeWithTag(suggestionTag(match)).performScrollTo().performClick()
            text = stripQuickAddToken(text, match)
        }
        composeRule.onNodeWithText("Add task").performScrollTo().performClick()

        assertEquals(
            DomainCommand.CreateTask(
                title = "Shortcut",
                projectId = OpenTasksFixtures.studioProject.id,
                priority = Priority.HIGH,
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
}

@RunWith(AndroidJUnit4::class)
class ShortcutRootEscapeInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun escapeDismissesTheOpenSheet() {
        composeRule.setContent {
            var showQuickAdd by remember { mutableStateOf(true) }
            var editableFocused by remember { mutableStateOf(false) }
            OpenTasksTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("shortcut-root")
                        .onFocusEvent { focusState -> editableFocused = focusState.hasFocus }
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.isCtrlPressed) {
                                return@onKeyEvent false
                            }
                            val action = shortcutActionFor(
                                key = event.key,
                                isCtrlPressed = false,
                                isShiftPressed = event.isShiftPressed,
                                inProjectsRoute = false,
                                editableFocused = editableFocused,
                            )
                            if (action == ShortcutAction.DISMISS_TOP) showQuickAdd = false
                            action == ShortcutAction.DISMISS_TOP
                        }
                        .focusable(),
                ) {
                    if (showQuickAdd) {
                        QuickAddSheet(onDismiss = { showQuickAdd = false }, onAdd = {})
                    }
                }
            }
        }

        composeRule.onNodeWithTag("shortcut-root").requestFocus()
        composeRule.onNodeWithTag("shortcut-root").performKeyInput { pressKey(Key.Escape) }

        composeRule.onNodeWithTag("quick-add-title").assertDoesNotExist()
    }
}
