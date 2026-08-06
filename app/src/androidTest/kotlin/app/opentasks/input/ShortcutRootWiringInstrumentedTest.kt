package app.opentasks.input

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.QuickAddSheet
import app.opentasks.SearchSurface
import app.opentasks.core.designsystem.OpenTasksTheme
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
        composeRule.setContent {
            var showSearch by remember { mutableStateOf(false) }
            var editableFocused by remember { mutableStateOf(false) }
            OpenTasksTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("shortcut-root")
                        .onFocusEvent { focusState -> editableFocused = focusState.hasFocus }
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
        }

        composeRule.onNodeWithTag("shortcut-root").requestFocus()
        composeRule.onNodeWithTag("shortcut-root").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.K) }
        }
        // SearchSurface requests focus for its query field from a
        // `LaunchedEffect(Unit)` fired by its own composition, one step
        // behind the key event that flips `showSearch` on. `performKeyInput`
        // only synchronizes the input dispatch itself; it does not block
        // until that later effect has resumed and applied focus, so the
        // query field can still be unfocused the instant control returns
        // here. Waiting for idle lets that effect run before the assertion
        // reads the semantics tree.
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("workspace-search-query").assertIsFocused()
    }

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
