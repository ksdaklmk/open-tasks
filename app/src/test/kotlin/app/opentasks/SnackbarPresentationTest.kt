package app.opentasks

import androidx.compose.material3.SnackbarDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SnackbarPresentationTest {
    @Test
    fun undoFeedbackUsesCustomEightSecondDuration() {
        val presentation = snackbarPresentation(hasUndo = true)

        assertEquals(SnackbarDuration.Indefinite, presentation.duration)
        assertFalse(presentation.withDismissAction)
        assertEquals(8_000L, presentation.timeoutMillis)
    }

    @Test
    fun ordinaryFeedbackUsesFiniteShortDuration() {
        val presentation = snackbarPresentation(hasUndo = false)

        assertEquals(SnackbarDuration.Short, presentation.duration)
        assertFalse(presentation.withDismissAction)
        assertNull(presentation.timeoutMillis)
    }
}
