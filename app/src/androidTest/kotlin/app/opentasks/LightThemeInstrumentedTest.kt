package app.opentasks

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.core.designsystem.OpenTasksColors
import app.opentasks.core.designsystem.OpenTasksTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LightThemeInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun darkDeviceConfigurationStillUsesTheLightScheme() {
        val observed = AtomicReference<Pair<Color, Color>>()
        composeRule.setContent {
            val darkConfiguration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    Configuration.UI_MODE_NIGHT_YES
            }
            CompositionLocalProvider(LocalConfiguration provides darkConfiguration) {
                OpenTasksTheme {
                    observed.set(
                        MaterialTheme.colorScheme.background to
                            MaterialTheme.colorScheme.surface,
                    )
                    Text("Light only", Modifier.testTag("light-theme-content"))
                }
            }
        }

        composeRule.onNodeWithTag("light-theme-content").assertIsDisplayed()
        assertEquals(
            OpenTasksColors.LightBackground to OpenTasksColors.LightSurface,
            observed.get(),
        )
    }

    @Test
    fun darkDeviceConfigurationKeepsMainActivitySystemBarIconsDark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uiModeManager = context.getSystemService(UiModeManager::class.java)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))

            scenario.onActivity { activity ->
                assertEquals(
                    Configuration.UI_MODE_NIGHT_YES,
                    activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
                )
                val controller = WindowInsetsControllerCompat(
                    activity.window,
                    activity.window.decorView,
                )
                assertTrue(controller.isAppearanceLightStatusBars)
                assertTrue(controller.isAppearanceLightNavigationBars)
            }
        } finally {
            scenario?.close()
            uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
        }
    }
}
