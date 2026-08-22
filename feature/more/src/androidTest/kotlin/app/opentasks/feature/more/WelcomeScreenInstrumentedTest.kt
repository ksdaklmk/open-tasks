package app.opentasks.feature.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WelcomeScreenInstrumentedTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun approvedCopyAndActionsRemainIndependent() {
        val google = AtomicInteger()
        val offline = AtomicInteger()
        val portable = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                WelcomeScreen(
                    onContinueWithGoogle = { google.incrementAndGet() },
                    onContinueOffline = { offline.incrementAndGet() },
                    onRestoreFromDevice = { portable.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithText("Welcome to Open Tasks")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText(
            "Plan projects and focused work in a private workspace on this device.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Optional — Google Drive is used only for encrypted backup and recovery.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Works offline").assertDoesNotExist()
        composeRule.onNodeWithText("Encrypted locally").assertDoesNotExist()

        composeRule.onNodeWithTag("welcome-google")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, google.get())
        assertEquals(0, offline.get())
        assertEquals(0, portable.get())

        composeRule.onNodeWithTag("welcome-offline")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, google.get())
        assertEquals(1, offline.get())
        assertEquals(0, portable.get())

        composeRule.onNodeWithTag("welcome-portable")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, google.get())
        assertEquals(1, offline.get())
        assertEquals(1, portable.get())
    }

    @Test
    fun compactWidthUsesOneColumnStructure() {
        composeRule.setContent {
            OpenTasksTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    WelcomeScreen({}, {}, {})
                }
            }
        }

        composeRule.onNodeWithTag("welcome-compact").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome-expanded").assertDoesNotExist()
    }

    @Test
    fun expandedWidthUsesSplitStructure() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density / 3f, density.fontScale),
            ) {
                OpenTasksTheme {
                    Box(Modifier.width(1000.dp).height(700.dp)) {
                        WelcomeScreen({}, {}, {})
                    }
                }
            }
        }

        composeRule.onNodeWithTag("welcome-expanded").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome-compact").assertDoesNotExist()
    }

    @Test
    fun everyActionRemainsReachableAtTwoHundredPercentFont() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                OpenTasksTheme {
                    Box(Modifier.width(320.dp).height(520.dp)) {
                        WelcomeScreen({}, {}, {})
                    }
                }
            }
        }

        listOf("welcome-google", "welcome-offline", "welcome-portable").forEach { tag ->
            composeRule.onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
    }
}
