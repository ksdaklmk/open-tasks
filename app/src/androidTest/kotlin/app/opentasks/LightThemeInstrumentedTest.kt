package app.opentasks

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksColors
import app.opentasks.core.designsystem.OpenTasksTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LightThemeInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

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
}
