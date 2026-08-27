package app.opentasks.feature.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.RecoveryFailureCategory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryShellScreenInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun emptyDiscoveryOffersBackAndOfflineWithoutPassphrase() {
        val back = AtomicInteger()
        val offline = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                RecoveryShellScreen(
                    mode = RecoveryShellMode.NoCandidates,
                    canStartWithoutRestoring = true,
                    onBack = { back.incrementAndGet() },
                    onStartWithoutRestoring = { offline.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithText("No encrypted backup found").assertIsDisplayed()
        composeRule.onNodeWithTag("recovery-passphrase").assertDoesNotExist()
        composeRule.onNodeWithText("Back").performClick()
        assertEquals(1, back.get())
        assertEquals(0, offline.get())
        composeRule.onNodeWithText("Start with a local workspace").performClick()
        assertEquals(1, back.get())
        assertEquals(1, offline.get())
    }

    @Test
    fun activeReplacementEmptyDiscoveryNeverOffersNewVaultCreation() {
        composeRule.setContent {
            OpenTasksTheme {
                RecoveryShellScreen(mode = RecoveryShellMode.NoCandidates)
            }
        }

        composeRule.onNodeWithText("No encrypted backup found").assertIsDisplayed()
        composeRule.onNodeWithText("Start with a local workspace").assertDoesNotExist()
    }

    @Test
    fun activeReplacementOffersRecoveryWithoutStartingOver() {
        composeRule.setContent {
            OpenTasksTheme {
                RecoveryShellScreen(mode = RecoveryShellMode.ActiveReplacement)
            }
        }

        composeRule.onNodeWithTag("recovery-drive").assertIsDisplayed()
        composeRule.onNodeWithTag("recovery-portable").assertIsDisplayed()
        composeRule.onNodeWithText("Start with a local workspace").assertDoesNotExist()
    }

    @Test
    fun candidatePassphraseIsMaskedNotSaveableAndImeDoneSubmits() {
        val restoration = StateRestorationTester(composeRule)
        val submission = AtomicReference<Pair<String, String>>()
        restoration.setContent {
            OpenTasksTheme {
                RecoveryShellScreen(
                    mode = RecoveryShellMode.Candidates,
                    candidates = listOf(RecoveryShellCandidate("opaque", drive = true)),
                    onRestore = { handle, passphrase -> submission.set(handle to passphrase) },
                )
            }
        }

        val passphrase = composeRule.onNodeWithTag("recovery-passphrase")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        passphrase.performTextInput("private phrase")
        passphrase.performImeAction()
        composeRule.waitUntil { submission.get() != null }
        assertEquals("opaque" to "private phrase", submission.get())
        composeRule.onNodeWithTag("recovery-passphrase").performTextInput("not saved")
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("recovery-passphrase").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
    }

    @Test
    fun failedRecoveryExplainsReasonAndKeepsBothRetriesIndependent() {
        val drive = AtomicInteger()
        val portable = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                RecoveryShellScreen(
                    mode = RecoveryShellMode.Failed,
                    failureReason = RecoveryFailureCategory.ACCOUNT_MISMATCH,
                    onDiscoverDrive = { drive.incrementAndGet() },
                    onDiscoverPortable = { portable.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithText("Choose the Google account used for this backup.")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Error,
                    "Choose the Google account used for this backup.",
                ),
            )
        composeRule.onNodeWithTag("recovery-drive").performClick()
        assertEquals(1, drive.get())
        assertEquals(0, portable.get())
        composeRule.onNodeWithTag("recovery-portable").performClick()
        assertEquals(1, drive.get())
        assertEquals(1, portable.get())
    }

    @Test
    fun retryableProviderFailureOffersRetryWithoutSignInGuidance() {
        composeRule.setContent {
            OpenTasksTheme {
                RecoveryShellScreen(
                    mode = RecoveryShellMode.Failed,
                    failureReason = RecoveryFailureCategory.RETRYABLE_PROVIDER,
                )
            }
        }

        composeRule.onNodeWithText("Google Drive is temporarily unavailable. Try again.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Sign in to Google Drive to continue recovery.")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("recovery-drive").assertIsDisplayed()
        composeRule.onNodeWithTag("recovery-portable").assertIsDisplayed()
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
        composeRule.onNodeWithTag("recovery-export-guidance")
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        composeRule.onNodeWithText("Start with a local workspace").assertDoesNotExist()
    }
}
