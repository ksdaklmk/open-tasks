package app.opentasks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.opentasks.core.designsystem.OpenTasksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class QuickAddSheetInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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
                        onAdd = { title, _ -> submittedTitle.set(title) },
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
    fun clearSuggestionIsFortyEightDpAndKeepsTheUnchangedTitle() {
        composeRule.setContent {
            OpenTasksTheme {
                QuickAddSheet(onDismiss = {}, onAdd = { _, _ -> })
            }
        }

        composeRule.onNodeWithTag("quick-add-title").performTextInput("Plan tomorrow")
        composeRule.onNodeWithTag("quick-add-date-clear", useUnmergedTree = true)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.onNodeWithTag("quick-add-date-chip").assertDoesNotExist()
        composeRule.onNodeWithTag("quick-add-title")
            .assertTextContains("Plan tomorrow", substring = true)
    }

    @Test
    fun clearingAnAppliedSuggestionSubmitsWithoutADueDate() {
        val submittedDue = AtomicReference<app.opentasks.core.model.ZonedMoment?>()
        composeRule.setContent {
            OpenTasksTheme {
                QuickAddSheet(
                    onDismiss = {},
                    onAdd = { _, due -> submittedDue.set(due) },
                )
            }
        }

        composeRule.onNodeWithTag("quick-add-title").performTextReplacement("Plan tomorrow")
        composeRule.onNodeWithTag("quick-add-date-chip").performClick()
        composeRule.onNodeWithTag("quick-add-date-clear").performClick()
        composeRule.onNode(hasText("Add task") and hasClickAction()).performClick()

        assertEquals(null, submittedDue.get())
    }
}
