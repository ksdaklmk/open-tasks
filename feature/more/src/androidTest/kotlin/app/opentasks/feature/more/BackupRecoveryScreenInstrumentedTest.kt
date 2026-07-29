package app.opentasks.feature.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.BackupPackageInfo
import app.opentasks.core.model.BackupUnavailableReason
import app.opentasks.core.model.RecoveryPassphraseValidation
import app.opentasks.core.model.RestoredPackageCondition
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRecoveryScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun moreOverviewShowsExactLocalSummaryAndNavigatesToBackupRecovery() {
        composeRule.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = emptyList(),
                    projects = emptyList(),
                    backupStatus = AndroidBackupStatus.Ready(PACKAGE_INFO),
                    onRestoreProject = {},
                    onRestoreTask = {},
                    onPermanentlyDeleteTask = {},
                )
            }
        }

        composeRule.onNodeWithText("Backup & recovery").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Local package ready • generation 7")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("open-backup-recovery").performClick()
        composeRule.onNodeWithTag("backup-screen").assertIsDisplayed()
    }

    @Test
    fun notPreparedExplainsSupplementaryPackageAndOpensSecurePreparation() {
        composeRule.setContent {
            OpenTasksTheme {
                TestScreen(AndroidBackupStatus.NotPrepared)
            }
        }

        composeRule.onNodeWithText("Backup & recovery")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Android backup package")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Android backup is supplementary. App-managed cloud backup is not yet " +
                "available, and a prepared package does not confirm that Android uploaded it.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("backup-prepare").performScrollTo().performClick()

        composeRule.onNodeWithText("Recovery passphrase").assertIsDisplayed()
        composeRule.onNodeWithTag("backup-passphrase")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithTag("backup-confirmation")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithText(
            "Use 12–128 characters. This passphrase cannot be recovered. If you forget it, " +
                "the Android backup package cannot be used.",
        ).assertIsDisplayed()
    }

    @Test
    fun preparingHasProgressSemanticsAndNoPassphraseEntry() {
        composeRule.setContent {
            OpenTasksTheme {
                TestScreen(AndroidBackupStatus.Preparing)
            }
        }

        composeRule.onNodeWithText("Preparing package").assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("backup-passphrase").assertDoesNotExist()
        composeRule.onNodeWithText("Prepare Android backup").assertDoesNotExist()
    }

    @Test
    fun readyShowsVerifiedLocalPackageFactsWithoutUploadClaim() {
        composeRule.setContent {
            OpenTasksTheme {
                TestScreen(AndroidBackupStatus.Ready(PACKAGE_INFO))
            }
        }

        composeRule.onNodeWithText("Package ready").assertIsDisplayed()
        composeRule.onNodeWithText("Package generation 7").assertIsDisplayed()
        composeRule.onNodeWithText("Current local generation 9").assertIsDisplayed()
        composeRule.onNodeWithText("12,345 bytes").assertIsDisplayed()
        composeRule.onNodeWithText("Produced locally 14 February 2026 at 17:05")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Backed up", substring = true).assertDoesNotExist()
    }

    @Test
    fun updatePendingKeepsPriorFactsAndShowsWorkInProgress() {
        composeRule.setContent {
            OpenTasksTheme {
                TestScreen(AndroidBackupStatus.UpdatePending(PACKAGE_INFO))
            }
        }

        composeRule.onNodeWithText("Package update in progress").assertIsDisplayed()
        composeRule.onNodeWithText(
            "The previous verified package remains available while local changes are prepared.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Package generation 7").assertIsDisplayed()
        composeRule.onNodeWithText("Current local generation 9").assertIsDisplayed()
        composeRule.onNodeWithText("12,345 bytes").assertIsDisplayed()
    }

    @Test
    fun unavailableReasonsAreBoundedAndOnlyRetryableReasonsOfferRetry() {
        val cases = listOf(
            BackupUnavailableReason.PACKAGE_TOO_LARGE to
                ("The package is over the 24 MiB local limit." to false),
            BackupUnavailableReason.RECOVERY_ENVELOPE_UNAVAILABLE to
                ("The recovery envelope is unavailable." to false),
            BackupUnavailableReason.ENCODING_OR_CRYPTO to
                ("The package could not be encoded or encrypted." to true),
            BackupUnavailableReason.VERIFICATION_FAILED to
                ("The package could not be verified." to true),
            BackupUnavailableReason.FILE_IO to
                ("The local package file is unavailable." to true),
        )

        cases.forEach { (reason, expected) ->
            composeRule.setContent {
                OpenTasksTheme {
                    TestScreen(AndroidBackupStatus.Unavailable(reason))
                }
            }

            composeRule.onNodeWithText("Package unavailable").assertIsDisplayed()
            composeRule.onNodeWithText(expected.first).assertIsDisplayed()
            if (expected.second) {
                composeRule.onNodeWithTag("backup-retry").assertIsDisplayed()
            } else {
                composeRule.onNodeWithTag("backup-retry").assertDoesNotExist()
            }
        }
    }

    @Test
    fun restoredPackageIsInertAndOffersNoActivationOrDiscard() {
        listOf(
            RestoredPackageCondition.PRESERVED to
                "An Android backup package was preserved in the recovery inbox. " +
                "Recovery activation is not available yet.",
            RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT to
                "An incompatible or corrupt Android backup package was preserved in the " +
                "recovery inbox. Recovery activation is not available yet.",
        ).forEach { (condition, copy) ->
            composeRule.setContent {
                OpenTasksTheme {
                    TestScreen(AndroidBackupStatus.RestoredPackageDetected(condition))
                }
            }

            composeRule.onNodeWithText("Restored package detected").assertIsDisplayed()
            composeRule.onNodeWithText(copy).assertIsDisplayed()
            composeRule.onNodeWithText("Restore").assertDoesNotExist()
            composeRule.onNodeWithText("Discard").assertDoesNotExist()
        }
    }

    @Test
    fun passphraseValidationUsesExactLengthAndMismatchGuidance() {
        composeRule.setContent {
            OpenTasksTheme {
                TestScreen(
                    status = AndroidBackupStatus.NotPrepared,
                    validatePassphrase = { passphrase, confirmation ->
                        when {
                            passphrase.length < 12 -> RecoveryPassphraseValidation.TooShort
                            passphrase != confirmation ->
                                RecoveryPassphraseValidation.ConfirmationMismatch
                            else -> RecoveryPassphraseValidation.Valid
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag("backup-prepare").performScrollTo().performClick()
        composeRule.onNodeWithTag("backup-passphrase").performTextInput("too short")
        composeRule.onNodeWithTag("backup-submit").performClick()
        composeRule.onNodeWithText("Use 12–128 characters.").assertIsDisplayed()

        composeRule.onNodeWithTag("backup-passphrase").performTextInput(" and now valid")
        composeRule.onNodeWithTag("backup-confirmation").performTextInput("not the same")
        composeRule.onNodeWithTag("backup-submit").performClick()
        composeRule.onNodeWithText("Passphrases do not match.").assertIsDisplayed()
    }

    @Test
    fun submitClearsAndDismissesFieldsBeforeForwardingThePassphrase() {
        val prepared = AtomicReference<String>()
        composeRule.setContent {
            OpenTasksTheme {
                TestScreen(
                    status = AndroidBackupStatus.NotPrepared,
                    validatePassphrase = { passphrase, confirmation ->
                        if (passphrase == confirmation) {
                            RecoveryPassphraseValidation.Valid
                        } else {
                            RecoveryPassphraseValidation.ConfirmationMismatch
                        }
                    },
                    onPrepare = prepared::set,
                )
            }
        }

        composeRule.onNodeWithTag("backup-prepare").performScrollTo().performClick()
        composeRule.onNodeWithTag("backup-passphrase").performTextInput("correct horse")
        composeRule.onNodeWithTag("backup-confirmation").performTextInput("correct horse")
        composeRule.onNodeWithTag("backup-submit").performClick()

        assertEquals("correct horse", prepared.get())
        composeRule.onNodeWithTag("backup-passphrase").assertDoesNotExist()
        composeRule.onNodeWithTag("backup-confirmation").assertDoesNotExist()
    }

    @Test
    fun passphraseFieldsDoNotRestoreAfterSavedInstanceStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            OpenTasksTheme {
                TestScreen(AndroidBackupStatus.NotPrepared)
            }
        }

        composeRule.onNodeWithTag("backup-prepare").performScrollTo().performClick()
        composeRule.onNodeWithTag("backup-passphrase").performTextInput("private phrase")
        composeRule.onNodeWithTag("backup-confirmation").performTextInput("private phrase")

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("backup-prepare").performScrollTo().performClick()

        composeRule.onNodeWithTag("backup-passphrase").assertTextEquals("")
        composeRule.onNodeWithTag("backup-confirmation").assertTextEquals("")
    }

    @Test
    fun actionsHaveMinimumTargetsAndForwardOnlyTheirNamedIntent() {
        val back = AtomicInteger()
        val retry = AtomicInteger()
        val settings = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                TestScreen(
                    status = AndroidBackupStatus.Unavailable(BackupUnavailableReason.FILE_IO),
                    onBack = { back.incrementAndGet() },
                    onRetry = { retry.incrementAndGet() },
                    onOpenSystemSettings = { settings.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithTag("backup-back")
            .assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals("Back")
            .performClick()
        composeRule.onNodeWithTag("backup-retry")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("backup-system-settings")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, back.get())
        assertEquals(1, retry.get())
        assertEquals(1, settings.get())
    }

    @Test
    fun keyboardTraversalFollowsVisibleSheetControlOrder() {
        composeRule.setContent {
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(inputModeManager) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
            }
            OpenTasksTheme {
                TestScreen(AndroidBackupStatus.NotPrepared)
            }
        }
        composeRule.onNodeWithTag("backup-prepare").performScrollTo().performClick()

        val traversal = listOf(
            "backup-passphrase",
            "backup-confirmation",
            "backup-cancel",
            "backup-submit",
        )
        composeRule.onNodeWithTag(traversal.first())
            .performSemanticsAction(SemanticsActions.RequestFocus)
        traversal.forEachIndexed { index, tag ->
            val node = composeRule.onNodeWithTag(tag).assertIsFocused()
            if (index < traversal.lastIndex) {
                node.performKeyInput { pressKey(Key.Tab) }
            }
        }
    }

    @Test
    fun keyboardEnterActivatesTheFocusedSystemSettingsAction() {
        val settings = AtomicInteger()
        composeRule.setContent {
            val inputModeManager = LocalInputModeManager.current
            LaunchedEffect(inputModeManager) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
            }
            OpenTasksTheme {
                TestScreen(
                    status = AndroidBackupStatus.NotPrepared,
                    onOpenSystemSettings = { settings.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithTag("backup-system-settings")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }

        assertEquals(1, settings.get())
    }

    @Test
    fun compactAndExpandedWidthsRemainUsableAtLargeTextScales() {
        listOf(
            Triple(320.dp, 1.3f, AndroidBackupStatus.NotPrepared),
            Triple(320.dp, 2f, AndroidBackupStatus.Ready(PACKAGE_INFO)),
            Triple(840.dp, 2f, AndroidBackupStatus.UpdatePending(PACKAGE_INFO)),
        ).forEach { (width, fontScale, status) ->
            composeRule.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                ) {
                    OpenTasksTheme {
                        Box(Modifier.width(width)) {
                            TestScreen(status)
                        }
                    }
                }
            }

            composeRule.onNodeWithTag("backup-screen").assertIsDisplayed()
            composeRule.onNodeWithTag("backup-system-settings")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @androidx.compose.runtime.Composable
    private fun TestScreen(
        status: AndroidBackupStatus,
        validatePassphrase: (String, String) -> RecoveryPassphraseValidation = { _, _ ->
            RecoveryPassphraseValidation.Valid
        },
        onPrepare: (String) -> Unit = {},
        onRetry: () -> Unit = {},
        onOpenSystemSettings: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        BackupRecoveryScreen(
            status = status,
            validatePassphrase = validatePassphrase,
            onPrepare = onPrepare,
            onRetry = onRetry,
            onOpenSystemSettings = onOpenSystemSettings,
            onBack = onBack,
        )
    }

    private companion object {
        val PACKAGE_INFO = BackupPackageInfo(
            packageGeneration = BackupGeneration(7),
            currentGeneration = BackupGeneration(9),
            byteCount = 12_345,
            producedAt = LocalDateTime.of(2026, 2, 14, 17, 5)
                .atZone(ZoneId.systemDefault())
                .toInstant(),
        )
    }
}
