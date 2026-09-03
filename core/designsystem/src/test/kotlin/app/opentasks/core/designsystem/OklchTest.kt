package app.opentasks.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OklchTest {
    @Test
    fun colourConstantsInitialiseBeforeTheThemeFacadeWithoutBlankingTheScheme() {
        // Touch the constants first, as a widget or notification can on a cold
        // start, before anything has referenced the theme file.
        assertEquals(1f, OpenTasksColors.LightInk.alpha)

        // oklch() must live in its own facade so that initialising
        // OpenTasksColors never runs OpenTasksThemeKt's static initialiser.
        val oklchFacade = Class.forName("app.opentasks.core.designsystem.OklchKt")
        assertTrue(oklchFacade.declaredMethods.any { it.name.startsWith("oklch") })

        val themeFacade = Class.forName("app.opentasks.core.designsystem.OpenTasksThemeKt")
        val schemeField = themeFacade.getDeclaredField("LightColorScheme")
        schemeField.isAccessible = true
        val scheme = schemeField.get(null)
        for (getter in listOf("getPrimary", "getBackground", "getOnSurface", "getSecondary")) {
            val method = scheme.javaClass.methods.first { it.name.startsWith(getter) }
            val packed = method.invoke(scheme) as Long
            assertEquals(getter, 1f, Color(packed.toULong()).alpha)
        }
    }
}
