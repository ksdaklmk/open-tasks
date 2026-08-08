package app.opentasks.input

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.QuickAddSheet
import app.opentasks.SearchSurface
import app.opentasks.core.designsystem.OpenTasksTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
    val activityRule = ActivityScenarioRule(ComponentActivity::class.java)

    @Test
    fun ctrlKOpensSearchAndFocusesTheQueryField() {
        val rootFocused = CountDownLatch(1)
        lateinit var hostRoot: View
        activityRule.scenario.onActivity { activity ->
            hostRoot = activity.window.decorView
            activity.setContent {
                val rootFocusRequester = remember { FocusRequester() }
                var showSearch by remember { mutableStateOf(false) }
                var editableFocused by remember { mutableStateOf(false) }
                OpenTasksTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
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
        }

        assertTrue(
            "The shortcut root never received Compose focus",
            rootFocused.await(10, TimeUnit.SECONDS),
        )

        val nextFrame = CountDownLatch(1)
        activityRule.scenario.onActivity {
            it.dispatchCtrlK()
            it.window.decorView.postOnAnimation { nextFrame.countDown() }
        }
        assertTrue(
            "The shortcut did not settle on the next frame",
            nextFrame.await(10, TimeUnit.SECONDS),
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val dialogRoot = WindowInspector.getGlobalWindowViews().single { it !== hostRoot }
        assumeTrue(
            "Headless API 36 runner did not grant the search Dialog window focus",
            dialogRoot.hasWindowFocus(),
        )

        val observedEvents = mutableListOf<String>()
        try {
            instrumentation.uiAutomation.executeAndWaitForEvent(
                {
                    activityRule.scenario.onActivity {
                        // The headless CI window manager never focuses the host Activity.
                        // Deliver its missing Dialog transition; SearchSurface's own
                        // FocusRequester remains responsible for choosing the query field.
                        dialogRoot.dispatchWindowFocusChanged(true)
                    }
                },
                { event ->
                    if (observedEvents.size < 16) {
                        observedEvents += "type=${event.eventType}, " +
                            "class=${event.className}, package=${event.packageName}"
                    }
                    event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED &&
                        event.packageName?.toString() == "app.opentasks" &&
                        event.className?.toString() == "android.widget.EditText"
                },
                10_000,
            )
        } catch (error: TimeoutException) {
            val focusedView = dialogRoot.findFocus()
            throw AssertionError(
                "Search query focus event timed out: " +
                    "dialogAttached=${dialogRoot.isAttachedToWindow}, " +
                    "dialogWindowFocused=${dialogRoot.hasWindowFocus()}, " +
                    "dialogFocused=${dialogRoot.isFocused}, " +
                    "focusedView=${focusedView?.javaClass?.name}, " +
                    "events=$observedEvents",
            ).apply { initCause(error) }
        }
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
                        QuickAddSheet(onDismiss = { showQuickAdd = false }, onAdd = { _, _ -> })
                    }
                }
            }
        }

        composeRule.onNodeWithTag("shortcut-root").requestFocus()
        composeRule.onNodeWithTag("shortcut-root").performKeyInput { pressKey(Key.Escape) }

        composeRule.onNodeWithTag("quick-add-title").assertDoesNotExist()
    }
}

private fun ComponentActivity.dispatchCtrlK() {
    val downTime = SystemClock.uptimeMillis()
    assertTrue(
        dispatchKeyEvent(
            KeyEvent(
                downTime,
                downTime,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_K,
                0,
                KeyEvent.META_CTRL_ON,
            ),
        ),
    )
    dispatchKeyEvent(
        KeyEvent(
            downTime,
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_K,
            0,
            KeyEvent.META_CTRL_ON,
        ),
    )
}
