package app.opentasks

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import app.opentasks.core.designsystem.OpenTasksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class QuickAddSheetInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

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
                        onAdd = submittedTitle::set,
                    )
                }
            }
        }

        composeRule.onNodeWithTag("quick-add-title").performTextInput("Reachable task")
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
}
