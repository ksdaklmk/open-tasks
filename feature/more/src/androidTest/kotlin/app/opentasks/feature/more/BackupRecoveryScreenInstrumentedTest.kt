package app.opentasks.feature.more

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasTextExactly
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.BackupPackageInfo
import app.opentasks.core.model.BackupUnavailableReason
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecoveryPassphraseValidation
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupStatus
import app.opentasks.core.model.RestoredPackageCondition
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRecoveryScreenInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    private fun observeDialogIme(
        bottom: AtomicInteger,
        animationRunning: AtomicBoolean,
    ) {
        onView(isRoot()).inRoot(isDialog()).perform(
            object : ViewAction {
                override fun getConstraints(): Matcher<View> = isRoot()

                override fun getDescription() = "observe the dialog IME animation"

                override fun perform(uiController: UiController, view: View) {
                    bottom.set(
                        ViewCompat.getRootWindowInsets(view)
                            ?.getInsets(WindowInsetsCompat.Type.ime())
                            ?.bottom ?: 0,
                    )
                    ViewCompat.setWindowInsetsAnimationCallback(
                        view,
                        object : WindowInsetsAnimationCompat.Callback(
                            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
                        ) {
                            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                                    animationRunning.set(true)
                                }
                            }

                            override fun onProgress(
                                insets: WindowInsetsCompat,
                                runningAnimations: List<WindowInsetsAnimationCompat>,
                            ): WindowInsetsCompat = insets.also {
                                bottom.set(it.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                            }

                            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                                    animationRunning.set(false)
                                }
                            }
                        },
                    )
                }
            },
        )
    }

    @Test
    fun encryptedAndAndroidBackupAreDistinctCardsWithManualRetry() {
        val manualRequests = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                BackupRecoveryScreen(
                    status = AndroidBackupStatus.Ready(PACKAGE_INFO),
                    remoteStatus = RemoteBackupStatus.RetryScheduled(
                        BackupGeneration(9),
                        RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
                    ),
                    canBackUpNow = true,
                    validatePassphrase = { _, _ -> RecoveryPassphraseValidation.Valid },
                    onPrepare = {},
                    onRetry = {},
                    onOpenSystemSettings = {},
                    onBackUpNow = { manualRequests.incrementAndGet() },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("encrypted-backup-heading")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText("Waiting to retry").assertIsDisplayed()
        composeRule.onNodeWithTag("encrypted-backup-now")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("android-backup-heading")
            .performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertEquals(1, manualRequests.get())
    }

    @Test
    fun remoteActionsAndLifecycleDisclosuresStayScrollReachableAtLargeText() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                OpenTasksTheme {
                    Box(Modifier.width(320.dp).height(520.dp)) {
                        BackupRecoveryScreen(
                            status = AndroidBackupStatus.NotPrepared,
                            remoteStatus = RemoteBackupStatus.OwnershipLost,
                            canTakeOver = true,
                            canPreserveAsNewLineage = true,
                            canChangePassphrase = true,
                            canDisconnect = true,
                            canDeleteHistory = true,
                            validatePassphrase = { _, _ -> RecoveryPassphraseValidation.Valid },
                            onPrepare = {},
                            onRetry = {},
                            onOpenSystemSettings = {},
                            onBack = {},
                        )
                    }
                }
            }
        }

        listOf(
            "restore-existing-workspace",
            "encrypted-backup-takeover",
            "encrypted-backup-preserve",
            "encrypted-backup-change-passphrase",
            "encrypted-backup-disconnect",
            "encrypted-backup-delete",
            "backup-system-settings",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp)
        }
        composeRule.onNodeWithText(
            "Open Tasks sends no Google Drive file request after disconnecting.",
        ).assertExists()
        composeRule.onNodeWithText(
            "Older Drive backups, Android packages, and copied exports may still work with " +
                "the old passphrase.",
        ).assertExists()
    }

    @Test
    fun restoreExistingWorkspaceIsReachableAndForwardedOnce() {
        val restores = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = emptyList(),
                    projects = emptyList(),
                    onRestoreExistingWorkspace = { restores.incrementAndGet() },
                    onRestoreProject = {},
                    onRestoreTask = {},
                    onPermanentlyDeleteTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("open-backup-recovery")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(
            "Your current workspace stays unchanged until a verified restore is confirmed.",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("restore-existing-workspace")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, restores.get())
    }

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
        composeRule.onNodeWithTag("open-backup-recovery")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("backup-screen").assertIsDisplayed()
    }

    @Test
    fun migrationEntryIsTopLevelAndDoesNotOpenBackupRecovery() {
        val migrations = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = emptyList(),
                    projects = emptyList(),
                    onImportFromAnotherApp = { migrations.incrementAndGet() },
                    onRestoreProject = {},
                    onRestoreTask = {},
                    onPermanentlyDeleteTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("open-task-migration")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, migrations.get())
        composeRule.onNodeWithTag("backup-screen").assertDoesNotExist()
        composeRule.onNodeWithTag("more-overview").assertIsDisplayed()
    }

    @Test
    fun moreForwardsTransientInitialFailureToSecureReprepareAction() {
        val runtimeRetries = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = emptyList(),
                    projects = emptyList(),
                    backupStatus = AndroidBackupStatus.Unavailable(
                        BackupUnavailableReason.FILE_IO,
                    ),
                    canReprepareInitialBackup = true,
                    onRestoreProject = {},
                    onRestoreTask = {},
                    onPermanentlyDeleteTask = {},
                    onRetryBackup = { runtimeRetries.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithTag("open-backup-recovery")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("backup-retry").assertDoesNotExist()
        composeRule.onNodeWithTag("backup-reprepare")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("backup-passphrase")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithTag("backup-confirmation")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertEquals(0, runtimeRetries.get())
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
            .performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Android backup is supplementary. A prepared package does not confirm that " +
                "Android uploaded it.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("backup-prepare").performScrollTo().performClick()

        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and
                hasTextExactly("Recovery passphrase"),
        ).assertIsDisplayed()
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
        val reason = mutableStateOf(cases.first().first)
        composeRule.setContent {
            OpenTasksTheme {
                TestScreen(AndroidBackupStatus.Unavailable(reason.value))
            }
        }

        cases.forEach { (caseReason, expected) ->
            composeRule.runOnIdle {
                reason.value = caseReason
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Package unavailable").assertIsDisplayed()
            composeRule.onNodeWithText(expected.first).assertIsDisplayed()
            composeRule.onNodeWithTag("backup-reprepare").assertDoesNotExist()
            if (expected.second) {
                composeRule.onNodeWithTag("backup-retry").assertIsDisplayed()
            } else {
                composeRule.onNodeWithTag("backup-retry").assertDoesNotExist()
            }
        }
    }

    @Test
    fun everyTransientInitialFailureOffersSecureReprepareInsteadOfRuntimeRetry() {
        val reason = mutableStateOf(BackupUnavailableReason.PACKAGE_TOO_LARGE)
        val runtimeRetries = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                TestScreen(
                    status = AndroidBackupStatus.Unavailable(reason.value),
                    canReprepareInitialPackage = true,
                    onRetry = { runtimeRetries.incrementAndGet() },
                )
            }
        }
        val emptyInput = SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText,
            AnnotatedString(""),
        )

        enumValues<BackupUnavailableReason>().forEach { caseReason ->
            composeRule.runOnIdle {
                reason.value = caseReason
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("backup-retry").assertDoesNotExist()
            composeRule.onNodeWithTag("backup-reprepare")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            composeRule.onNodeWithTag("backup-passphrase")
                .assert(emptyInput)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            composeRule.onNodeWithTag("backup-confirmation")
                .assert(emptyInput)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            composeRule.onNodeWithTag("backup-cancel")
                .performScrollTo()
                .performClick()
        }

        assertEquals(0, runtimeRetries.get())
    }

    @Test
    fun transientReprepareSheetIsNotRestoredAndReopensEmpty() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            OpenTasksTheme {
                TestScreen(
                    status = AndroidBackupStatus.Unavailable(
                        BackupUnavailableReason.VERIFICATION_FAILED,
                    ),
                    canReprepareInitialPackage = true,
                )
            }
        }

        composeRule.onNodeWithTag("backup-reprepare").performScrollTo().performClick()
        composeRule.onNodeWithTag("backup-passphrase").performTextInput("private phrase")
        composeRule.onNodeWithTag("backup-confirmation").performTextInput("private phrase")

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("backup-passphrase").assertDoesNotExist()
        composeRule.onNodeWithTag("backup-reprepare").performScrollTo().performClick()

        val emptyInput = SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText,
            AnnotatedString(""),
        )
        composeRule.onNodeWithTag("backup-passphrase")
            .assert(emptyInput)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithTag("backup-confirmation")
            .assert(emptyInput)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test
    fun transientReprepareSubmissionMovesUiToPreparingWithoutRuntimeRetry() {
        val status = mutableStateOf<AndroidBackupStatus>(
            AndroidBackupStatus.Unavailable(BackupUnavailableReason.FILE_IO),
        )
        val canReprepare = mutableStateOf(true)
        val prepareCalls = AtomicInteger()
        val runtimeRetries = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                TestScreen(
                    status = status.value,
                    canReprepareInitialPackage = canReprepare.value,
                    onPrepare = {
                        prepareCalls.incrementAndGet()
                        canReprepare.value = false
                        status.value = AndroidBackupStatus.Preparing
                    },
                    onRetry = { runtimeRetries.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithTag("backup-reprepare").performScrollTo().performClick()
        composeRule.onNodeWithTag("backup-passphrase").performTextInput("correct horse")
        composeRule.onNodeWithTag("backup-confirmation").performTextInput("correct horse")
        composeRule.onNodeWithTag("backup-submit").performScrollTo().performClick()

        composeRule.onNodeWithText("Preparing package").assertIsDisplayed()
        composeRule.onNodeWithTag("backup-reprepare").assertDoesNotExist()
        composeRule.onNodeWithTag("backup-retry").assertDoesNotExist()
        composeRule.onNodeWithTag("backup-passphrase").assertDoesNotExist()
        assertEquals(1, prepareCalls.get())
        assertEquals(0, runtimeRetries.get())
    }

    @Test
    fun restoredPackageIsInertAndOffersNoActivationOrDiscard() {
        val cases = listOf(
            RestoredPackageCondition.PRESERVED to
                "An Android backup package was preserved in the recovery inbox. " +
                "Recovery activation is not available yet.",
            RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT to
                "An incompatible or corrupt Android backup package was preserved in the " +
                "recovery inbox. Recovery activation is not available yet.",
        )
        val condition = mutableStateOf(cases.first().first)
        composeRule.setContent {
            OpenTasksTheme {
                TestScreen(AndroidBackupStatus.RestoredPackageDetected(condition.value))
            }
        }

        cases.forEach { (caseCondition, copy) ->
            composeRule.runOnIdle {
                condition.value = caseCondition
            }
            composeRule.waitForIdle()
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
        composeRule.onNodeWithTag("backup-submit")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Use 12–128 characters.").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("backup-passphrase").performTextInput(" and now valid")
        composeRule.onNodeWithTag("backup-confirmation").performTextInput("not the same")
        composeRule.onNodeWithTag("backup-submit")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Passphrases do not match.").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithTag("backup-submit").performScrollTo().performClick()

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

        val emptyInput = SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText,
            AnnotatedString(""),
        )
        composeRule.onNodeWithTag("backup-passphrase").assert(emptyInput)
        composeRule.onNodeWithTag("backup-confirmation").assert(emptyInput)
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
        val cases = listOf(
            Triple(320.dp, 1.3f, AndroidBackupStatus.NotPrepared),
            Triple(320.dp, 2f, AndroidBackupStatus.Ready(PACKAGE_INFO)),
            Triple(840.dp, 2f, AndroidBackupStatus.UpdatePending(PACKAGE_INFO)),
        )
        val width = mutableStateOf(cases.first().first)
        val fontScale = mutableStateOf(cases.first().second)
        val status = mutableStateOf<AndroidBackupStatus>(cases.first().third)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale.value),
            ) {
                OpenTasksTheme {
                    Box(Modifier.width(width.value)) {
                        TestScreen(status.value)
                    }
                }
            }
        }

        cases.forEach { (caseWidth, caseFontScale, caseStatus) ->
            composeRule.runOnIdle {
                width.value = caseWidth
                fontScale.value = caseFontScale
                status.value = caseStatus
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("backup-screen").assertIsDisplayed()
            composeRule.onNodeWithTag("backup-system-settings")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun compactLargeTextKeepsFocusedPassphraseSheetActionsScrollReachable() {
        val fontScale = mutableStateOf(1.3f)
        val imeBottom = AtomicInteger()
        val imeAnimationRunning = AtomicBoolean()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale.value),
            ) {
                OpenTasksTheme {
                    Box(
                        Modifier
                            .width(320.dp)
                            .height(520.dp),
                    ) {
                        TestScreen(AndroidBackupStatus.NotPrepared)
                    }
                }
            }
        }

        listOf(1.3f, 2f).forEachIndexed { index, caseFontScale ->
            composeRule.runOnIdle {
                fontScale.value = caseFontScale
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("backup-prepare")
                .performScrollTo()
                .performClick()
            val passphrase = composeRule.onNodeWithTag("backup-passphrase")
            imeBottom.set(0)
            imeAnimationRunning.set(false)
            observeDialogIme(imeBottom, imeAnimationRunning)
            passphrase.performClick()
            passphrase.performTextInput("masked")
            passphrase.assertIsFocused()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            composeRule.waitUntil(timeoutMillis = 5_000) {
                imeBottom.get() > 0 && !imeAnimationRunning.get()
            }
            composeRule.onNodeWithTag("backup-confirmation")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("backup-cancel")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("backup-submit")
                .performScrollTo()
                .assertIsDisplayed()
            if (index == 0) {
                composeRule.onNodeWithTag("backup-cancel")
                    .performScrollTo()
                    .performClick()
                composeRule.waitForIdle()
            }
        }
    }

    @Test
    fun cloudAttachmentsBlockShowsCacheUsageAndGuardsContentDeletion() {
        val deleted = AtomicReference<String>()
        composeRule.setContent {
            OpenTasksTheme {
                BackupRecoveryScreen(
                    status = AndroidBackupStatus.Ready(PACKAGE_INFO),
                    remoteStatus = RemoteBackupStatus.BackingUp(BackupGeneration(9)),
                    attachmentCacheUsageBytes = 2_048,
                    validatePassphrase = { _, _ -> RecoveryPassphraseValidation.Valid },
                    onPrepare = {},
                    onRetry = {},
                    onOpenSystemSettings = {},
                    onDeleteAttachmentContent = deleted::set,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("cloud-attachments-heading")
            .performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText("Temporary cache 2,048 bytes").assertExists()
        composeRule.onNodeWithTag("attachments-delete-content")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("encrypted-current-passphrase")
            .performTextInput("correct horse battery")
        composeRule.onNodeWithTag("encrypted-secret-submit").performClick()

        assertEquals("correct horse battery", deleted.get())
    }

    @Test
    fun cloudAttachmentsWithoutRemoteBackupOfferNoContentDeletion() {
        composeRule.setContent {
            OpenTasksTheme {
                TestScreen(AndroidBackupStatus.NotPrepared)
            }
        }

        composeRule.onNodeWithTag("cloud-attachments-heading")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Connect an encrypted Google Drive backup before adding attachments.",
        ).assertExists()
        composeRule.onNodeWithTag("attachments-delete-content").assertDoesNotExist()
    }

    @Test
    fun markdownProjectSelectionMovesAndExportsTheSelectedProject() {
        val exported = AtomicReference<ProjectId>()
        val first = OpenTasksFixtures.studioProject
        val second = OpenTasksFixtures.taxProject
        composeRule.setContent {
            OpenTasksTheme {
                BackupRecoveryScreen(
                    status = AndroidBackupStatus.NotPrepared,
                    projects = listOf(first, second),
                    validatePassphrase = { _, _ -> RecoveryPassphraseValidation.Valid },
                    onPrepare = {},
                    onRetry = {},
                    onOpenSystemSettings = {},
                    onExportMarkdown = exported::set,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("markdown-export").performScrollTo().performClick()
        val firstRow = composeRule.onNodeWithTag("markdown-export-project-${first.id.value}")
        val secondRow = composeRule.onNodeWithTag("markdown-export-project-${second.id.value}")
        firstRow
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            )
            .assertIsNotSelected()
            .performClick()
            .assertIsSelected()
        secondRow.assertIsNotSelected().performClick().assertIsSelected()
        firstRow.assertIsNotSelected()

        composeRule.onNode(
            hasTextExactly("Export Markdown") and
                hasClickAction() and
                hasAnyAncestor(hasTestTag("markdown-export-project-sheet")),
        ).performClick()

        assertEquals(second.id, exported.get())
    }

    @androidx.compose.runtime.Composable
    private fun TestScreen(
        status: AndroidBackupStatus,
        canReprepareInitialPackage: Boolean = false,
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
            canReprepareInitialPackage = canReprepareInitialPackage,
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
