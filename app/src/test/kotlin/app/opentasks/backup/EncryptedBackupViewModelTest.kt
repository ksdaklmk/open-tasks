package app.opentasks.backup

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import app.opentasks.core.domain.LifecycleResult
import app.opentasks.core.domain.PassphraseChangeResult
import app.opentasks.core.domain.RemoteBackupConnectResult
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class EncryptedBackupViewModelTest {
    @Test
    fun processStateContainsNoCredentialOrRemoteIdentity() {
        val state = SavedStateHandle()
        val viewModel = viewModel(savedStateHandle = state)

        viewModel.connect()

        val saved = viewModel.savedStateForTest()
        assertFalse(saved.contains("PendingIntent"))
        assertFalse(saved.contains("ProviderObjectId"))
        assertFalse(saved.contains("CloudLineageId"))
        assertFalse(saved.contains(TEST_PASSPHRASE))
        assertTrue(state.keys().isEmpty())
    }

    @Test
    fun connectUsesExplicitAccountSelectionAndResolutionIsOneShot() {
        val pendingIntent = pendingIntent()
        val connectCalls = AtomicInteger()
        val viewModel = viewModel(
            connect = { allowSeparate, resolution ->
                connectCalls.incrementAndGet()
                assertFalse(allowSeparate)
                if (resolution == null) {
                    EncryptedBackupActionResult.ResolutionRequired(pendingIntent)
                } else {
                    EncryptedBackupActionResult.Completed
                }
            },
        )

        viewModel.connect()

        assertSame(pendingIntent, takeResolution(viewModel))
        assertFalse(viewModel.resolutionEffects.tryReceive().isSuccess)
        viewModel.acceptResolution(Intent())
        assertTrue(waitUntil { connectCalls.get() == 2 })
        assertFalse(viewModel.resolutionEffects.tryReceive().isSuccess)
    }

    @Test
    fun existingBackupOffersRestoreOrAnExplicitSeparateLineage() {
        val separate = AtomicReference<Boolean>()
        val viewModel = viewModel(
            connect = { allowSeparate, _ ->
                separate.set(allowSeparate)
                if (allowSeparate) {
                    EncryptedBackupActionResult.ConnectResult(
                        RemoteBackupConnectResult.Connected(
                            lineageId = CloudLineageId.new(),
                            generation = BackupGeneration(3),
                        ),
                    )
                } else {
                    EncryptedBackupActionResult.ConnectResult(
                        RemoteBackupConnectResult.ExistingBackupsFound(1),
                    )
                }
            },
        )

        viewModel.connect()
        assertTrue(waitUntil { viewModel.presentation.value.canRestore })
        assertTrue(viewModel.presentation.value.canPreserveAsNewLineage)

        viewModel.preserveAsNewLineage()
        assertTrue(waitUntil {
            separate.get() == true && !viewModel.presentation.value.canRestore
        })
    }

    @Test
    fun backUpNowAlwaysUsesTheSharedRuntimeRequestPath() {
        val requests = AtomicInteger()
        val status = MutableStateFlow<RemoteBackupStatus>(
            RemoteBackupStatus.RetryScheduled(
                generation = BackupGeneration(8),
                reason = RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
            ),
        )
        val viewModel = viewModel(
            status = status,
            requestBackupNow = { requests.incrementAndGet() },
        )

        viewModel.backUpNow()

        assertEquals(1, requests.get())
        assertTrue(viewModel.presentation.value.canBackUpNow)
    }

    @Test
    fun foregroundReauthorisationKeepsWrongAccountActionable() {
        val calls = AtomicInteger()
        val status = MutableStateFlow<RemoteBackupStatus>(
            RemoteBackupStatus.ActionRequired(RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED),
        )
        val viewModel = viewModel(
            status = status,
            reauthorise = { resolution ->
                calls.incrementAndGet()
                assertEquals(null, resolution)
                EncryptedBackupActionResult.Failed(RemoteBackupFailureCategory.ACCOUNT_MISMATCH)
            },
        )

        viewModel.reauthorise()

        assertTrue(waitUntil {
            calls.get() == 1 && viewModel.presentation.value.status ==
                RemoteBackupStatus.ActionRequired(RemoteBackupFailureCategory.ACCOUNT_MISMATCH)
        })
        assertEquals(
            RemoteBackupStatus.ActionRequired(RemoteBackupFailureCategory.ACCOUNT_MISMATCH),
            viewModel.presentation.value.status,
        )
        assertTrue(viewModel.presentation.value.canReauthorise)
    }

    @Test
    fun ownershipLossOffersTakeoverAndNewLineage() {
        val status = MutableStateFlow<RemoteBackupStatus>(RemoteBackupStatus.OwnershipLost)
        val preserve = AtomicInteger()
        val viewModel = viewModel(
            status = status,
            preserveDivergent = {
                preserve.incrementAndGet()
                RemoteBackupConnectResult.Connected(
                    CloudLineageId.new(),
                    BackupGeneration(4),
                )
            },
        )

        assertTrue(viewModel.presentation.value.canTakeOver)
        assertTrue(viewModel.presentation.value.canPreserveAsNewLineage)
        viewModel.restoreOrTakeOver()
        viewModel.preserveAsNewLineage()

        assertEquals(Unit, takeRecoveryRoute(viewModel))
        assertFalse(viewModel.recoveryEffects.tryReceive().isSuccess)
        assertTrue(waitUntil { preserve.get() == 1 })
    }

    @Test
    fun disconnectAndDeleteHistoryAreDistinctOperations() {
        val disconnects = AtomicInteger()
        val deletions = AtomicInteger()
        val viewModel = viewModel(
            status = MutableStateFlow(RemoteBackupStatus.Preparing),
            disconnect = {
                disconnects.incrementAndGet()
                LifecycleResult.Disconnected(authorizationRevoked = true)
            },
            deleteHistory = {
                deletions.incrementAndGet()
                LifecycleResult.HistoryDeleted
            },
        )

        viewModel.disconnect()
        assertTrue(waitUntil { disconnects.get() == 1 })
        assertEquals(0, deletions.get())

        viewModel.deleteHistory(TEST_PASSPHRASE)
        assertTrue(waitUntil { deletions.get() == 1 })
        assertEquals(1, disconnects.get())
    }

    @Test
    fun passphrasesAreFreshAtEachBoundaryAndClearedAfterSuccessOrFailure() {
        val deleteArray = AtomicReference<CharArray>()
        val currentArray = AtomicReference<CharArray>()
        val newArray = AtomicReference<CharArray>()
        val calls = CountDownLatch(2)
        val viewModel = viewModel(
            deleteHistory = { passphrase ->
                deleteArray.set(passphrase)
                calls.countDown()
                LifecycleResult.HistoryDeleted
            },
            changePassphrase = { current, new ->
                currentArray.set(current)
                newArray.set(new)
                calls.countDown()
                PassphraseChangeResult.Failed(
                    app.opentasks.core.domain.PassphraseChangeFailureCategory.REMOTE_BACKUP,
                )
            },
        )

        viewModel.deleteHistory(TEST_PASSPHRASE)
        assertTrue(waitUntil { deleteArray.get() != null && deleteArray.get().all { it == '\u0000' } })
        viewModel.changePassphrase(TEST_PASSPHRASE, NEW_PASSPHRASE)

        assertTrue(calls.await(5, TimeUnit.SECONDS))
        assertTrue(waitUntil { currentArray.get().all { it == '\u0000' } })
        assertTrue(newArray.get().all { it == '\u0000' })
        assertFalse(deleteArray.get() === currentArray.get())
        assertArrayEquals(CharArray(TEST_PASSPHRASE.length), currentArray.get())
    }

    @Test
    fun oneOperationMutexRejectsConcurrentActions() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disconnects = AtomicInteger()
        val requests = AtomicInteger()
        val viewModel = viewModel(
            disconnect = {
                disconnects.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                LifecycleResult.Disconnected(false)
            },
            requestBackupNow = { requests.incrementAndGet() },
        )

        viewModel.disconnect()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        viewModel.backUpNow()
        assertEquals(0, requests.get())
        release.countDown()
        assertTrue(waitUntil { disconnects.get() == 1 })
    }

    private fun viewModel(
        status: MutableStateFlow<RemoteBackupStatus> = MutableStateFlow(RemoteBackupStatus.Disabled),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        connect: suspend (Boolean, Intent?) -> EncryptedBackupActionResult = { _, _ ->
            EncryptedBackupActionResult.Completed
        },
        reauthorise: suspend (Intent?) -> EncryptedBackupActionResult = {
            EncryptedBackupActionResult.Completed
        },
        requestBackupNow: () -> Unit = {},
        preserveDivergent: suspend () -> RemoteBackupConnectResult = {
            RemoteBackupConnectResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        },
        changePassphrase: suspend (CharArray, CharArray) -> PassphraseChangeResult = { _, _ ->
            PassphraseChangeResult.Failed(
                app.opentasks.core.domain.PassphraseChangeFailureCategory.LOCAL_STORAGE,
            )
        },
        disconnect: suspend () -> LifecycleResult = {
            LifecycleResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        },
        deleteHistory: suspend (CharArray) -> LifecycleResult = {
            LifecycleResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        },
    ) = EncryptedBackupViewModel(
        status = status,
        savedStateHandle = savedStateHandle,
        connectBackup = connect,
        reauthoriseBackup = reauthorise,
        requestBackupNow = requestBackupNow,
        preserveDivergentWork = preserveDivergent,
        changeRecoveryPassphrase = changePassphrase,
        disconnectBackup = disconnect,
        deleteBackupHistory = deleteHistory,
    )

    private fun takeResolution(viewModel: EncryptedBackupViewModel): PendingIntent {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            viewModel.resolutionEffects.tryReceive().getOrNull()?.let { return it }
            Thread.sleep(10)
        }
        error("No resolution effect")
    }

    private fun takeRecoveryRoute(viewModel: EncryptedBackupViewModel) =
        viewModel.recoveryEffects.tryReceive().getOrThrow()

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun pendingIntent(): PendingIntent = unsafe.allocateInstance(PendingIntent::class.java)
        as PendingIntent

    private companion object {
        const val TEST_PASSPHRASE = "correct horse"
        const val NEW_PASSPHRASE = "correct battery"
        val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let { field ->
            field.isAccessible = true
            field.get(null) as Unsafe
        }
    }
}
