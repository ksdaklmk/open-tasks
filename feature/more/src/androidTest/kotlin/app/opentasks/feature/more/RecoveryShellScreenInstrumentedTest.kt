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
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryShellScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun noVaultOffersBothRecoverySourcesAndStartWithoutRestore() {
        composeRule.setContent {
            OpenTasksTheme {
                RecoveryShellScreen(mode = RecoveryShellMode.NoVault)
            }
        }

        composeRule.onNodeWithTag("recovery-shell").assertIsDisplayed()
        composeRule.onNodeWithTag("recovery-drive").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("recovery-portable").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Start without restoring").assertIsDisplayed()
    }

    @Test
    fun candidatePassphraseIsMaskedNotSaveableAndImeDoneSubmits() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            OpenTasksTheme {
                RecoveryShellScreen(
                    mode = RecoveryShellMode.Candidates,
                    candidates = listOf(RecoveryShellCandidate("opaque", drive = true)),
                )
            }
        }

        composeRule.onNodeWithTag("recovery-passphrase")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextInput("private phrase")
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("recovery-passphrase").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
    }

    @Test
    fun takeoverShowsVerifiedGenerationAndConfirmationOnlyWhenRequired() {
        composeRule.setContent {
            OpenTasksTheme {
                RecoveryShellScreen(
                    mode = RecoveryShellMode.TakeoverConfirmation,
                    takeoverGeneration = 12,
                )
            }
        }

        composeRule.onNodeWithText("Verified backup generation 12").assertIsDisplayed()
        composeRule.onNodeWithTag("recovery-takeover-confirm")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun unreadableGuidanceNeverOffersOverwriteAndRemainsReachableAtTwoHundredPercent() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                OpenTasksTheme {
                    Box(Modifier.width(320.dp).height(520.dp)) {
                        RecoveryShellScreen(mode = RecoveryShellMode.UnreadableVault)
                    }
                }
            }
        }

        composeRule.onNodeWithText("Retry opening the vault").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Export preserved vault").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Start without restoring").assertDoesNotExist()
    }
}
