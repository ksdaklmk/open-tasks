package app.opentasks.backup

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import app.opentasks.core.domain.RecoveryCandidate
import app.opentasks.core.domain.RecoveryResult
import app.opentasks.core.domain.RecoverySource
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.RecoveryFailureCategory
import app.opentasks.core.model.WriterEpoch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class RecoveryViewModelTest {
    @Test
    fun constructionAndRecreationDoNotDiscoverBackups() {
        val driveCalls = AtomicInteger()
        val portableCalls = AtomicInteger()
        val savedState = SavedStateHandle()
        val create = {
            viewModel(
                savedStateHandle = savedState,
                discoverDrive = {
                    driveCalls.incrementAndGet()
                    RecoveryDiscoveryResult.Candidates(emptyList())
                },
                discoverPortable = {
                    portableCalls.incrementAndGet()
                    emptyList()
                },
            )
        }

        val first = create()
        val recreated = create()

        assertEquals(RecoveryPresentation.NoVault, first.presentation.value)
        assertEquals(RecoveryPresentation.NoVault, recreated.presentation.value)
        assertEquals(0, driveCalls.get())
        assertEquals(0, portableCalls.get())
    }

    @Test
    fun recoverySourceActionsCallOnlyTheirMatchingOperations() {
        val driveCalls = AtomicInteger()
        val portableCalls = AtomicInteger()
        val localStartCalls = AtomicInteger()
        val viewModel = viewModel(
            discoverDrive = {
                driveCalls.incrementAndGet()
                RecoveryDiscoveryResult.Candidates(emptyList())
            },
            discoverPortable = {
                portableCalls.incrementAndGet()
                emptyList()
            },
            createNewVault = { localStartCalls.incrementAndGet() },
        )

        viewModel.discoverDrive()
        assertTrue(waitUntil { driveCalls.get() == 1 })
        assertEquals(0, portableCalls.get())
        assertEquals(0, localStartCalls.get())

        viewModel.returnToSources()
        viewModel.discoverPortable()
        assertTrue(waitUntil { portableCalls.get() == 1 })
        assertEquals(1, driveCalls.get())
        assertEquals(0, localStartCalls.get())

        viewModel.returnToSources()
        viewModel.startWithoutRestoring()
        assertTrue(waitUntil { localStartCalls.get() == 1 })
        assertEquals(1, driveCalls.get())
        assertEquals(1, portableCalls.get())
    }

    @Test
    fun emptyDiscoveryKeepsItsSourceAndBackReturnsToSources() {
        val viewModel = viewModel()

        viewModel.discoverDrive()
        assertTrue(waitUntil {
            viewModel.presentation.value ==
                RecoveryPresentation.NoCandidates(RecoverySource.GOOGLE_DRIVE)
        })

        viewModel.returnToSources()
        assertEquals(RecoveryPresentation.NoVault, viewModel.presentation.value)

        viewModel.discoverPortable()
        assertTrue(waitUntil {
            viewModel.presentation.value ==
                RecoveryPresentation.NoCandidates(RecoverySource.ANDROID_BACKUP_PACKAGE)
        })
    }

    @Test
    fun discoveryShowsOnlyOpaqueHandlesAndSourcesBeforeAuthentication() {
        val viewModel = viewModel(
            discoverDrive = {
                RecoveryDiscoveryResult.Candidates(
                    listOf(RecoveryCandidate("opaque-random-handle", RecoverySource.GOOGLE_DRIVE)),
                )
            },
        )

        viewModel.discoverDrive()

        assertTrue(waitUntil { viewModel.presentation.value is RecoveryPresentation.Candidates })
        val candidates = viewModel.presentation.value as RecoveryPresentation.Candidates
        assertEquals(
            listOf(RecoveryCandidateSummary("opaque-random-handle", RecoverySource.GOOGLE_DRIVE)),
            candidates.values,
        )
        assertFalse(candidates.toString().contains("ProviderObjectId"))
        assertFalse(candidates.toString().contains("CloudLineageId"))
    }

    @Test
    fun driveResolutionIsOneShotAndNotSaved() {
        val pending = pendingIntent()
        val savedState = SavedStateHandle()
        val calls = AtomicInteger()
        val viewModel = viewModel(
            savedStateHandle = savedState,
            discoverDrive = { data ->
                if (calls.getAndIncrement() == 0) {
                    RecoveryDiscoveryResult.ResolutionRequired(pending)
                } else {
                    assertTrue(data != null)
                    RecoveryDiscoveryResult.Candidates(emptyList())
                }
            },
        )

        viewModel.discoverDrive()
        assertSame(pending, takeResolution(viewModel))
        assertFalse(viewModel.resolutionEffects.tryReceive().isSuccess)
        viewModel.acceptResolution(Intent())

        assertTrue(waitUntil { calls.get() == 2 })
        assertTrue(savedState.keys().isEmpty())
        assertFalse(viewModel.savedStateForTest().contains("PendingIntent"))
    }

    @Test
    fun recoveryProgressIsNotRestoredFromPrivateProcessState() {
        val state = SavedStateHandle()
        val viewModel = viewModel(
            savedStateHandle = state,
            discoverPortable = {
                listOf(RecoveryCandidate("process-local", RecoverySource.ANDROID_BACKUP_PACKAGE))
            },
        )

        viewModel.discoverPortable()
        assertTrue(waitUntil { viewModel.presentation.value is RecoveryPresentation.Candidates })
        assertTrue(state.keys().isEmpty())

        val restored = viewModel(savedStateHandle = state)
        assertEquals(RecoveryPresentation.NoVault, restored.presentation.value)
    }

    @Test
    fun portableDiscoveryDoesNotRequestDriveAuthorization() {
        val driveCalls = AtomicInteger()
        val viewModel = viewModel(
            discoverDrive = {
                driveCalls.incrementAndGet()
                RecoveryDiscoveryResult.Candidates(emptyList())
            },
            discoverPortable = {
                listOf(RecoveryCandidate("portable", RecoverySource.ANDROID_BACKUP_PACKAGE))
            },
        )

        viewModel.discoverPortable()

        assertTrue(waitUntil { viewModel.presentation.value is RecoveryPresentation.Candidates })
        assertEquals(0, driveCalls.get())
    }

    @Test
    fun passphraseIsFreshAndClearedForSuccessAndFailure() {
        val supplied = AtomicReference<CharArray>()
        val outcomes = ArrayDeque<RecoveryResult>().apply {
            add(
                RecoveryResult.TakeoverConfirmationRequired(
                    operationId = "operation",
                    generation = BackupGeneration(12),
                    nextWriterEpoch = WriterEpoch(3),
                ),
            )
            add(RecoveryResult.Failed(RecoveryFailureCategory.WRONG_PASSPHRASE))
        }
        val viewModel = viewModel(
            initialPresentation = RecoveryPresentation.Candidates(
                listOf(RecoveryCandidateSummary("drive", RecoverySource.GOOGLE_DRIVE)),
            ),
            prepare = { _, passphrase ->
                supplied.set(passphrase)
                outcomes.removeFirst()
            },
        )

        viewModel.restore("drive", TEST_PASSPHRASE)
        assertTrue(waitUntil { supplied.get() != null && supplied.get().all { it == '\u0000' } })
        assertEquals(
            RecoveryPresentation.TakeoverConfirmation("operation", 12),
            viewModel.presentation.value,
        )
        val first = supplied.get()

        viewModel.restore("drive", TEST_PASSPHRASE)
        assertTrue(waitUntil { viewModel.presentation.value == RecoveryPresentation.Failed(
            RecoveryFailureCategory.WRONG_PASSPHRASE,
        ) })
        assertTrue(supplied.get().all { it == '\u0000' })
        assertFalse(first === supplied.get())
        assertArrayEquals(CharArray(TEST_PASSPHRASE.length), supplied.get())
    }

    @Test
    fun takeoverRequiresExplicitConfirmationThenActivates() {
        val confirmations = AtomicInteger()
        val viewModel = viewModel(
            initialPresentation = RecoveryPresentation.TakeoverConfirmation("operation", 9),
            confirmTakeover = { operationId ->
                assertEquals("operation", operationId)
                confirmations.incrementAndGet()
                RecoveryResult.Activated(BackupGeneration(9), lineageId = null)
            },
        )

        assertEquals(0, confirmations.get())
        viewModel.confirmTakeover()

        assertTrue(waitUntil { confirmations.get() == 1 })
        assertEquals(RecoveryPresentation.Activating, viewModel.presentation.value)
    }

    @Test
    fun terminalAndAmbiguousFailuresStayTruthfullyBounded() {
        listOf(
            RecoveryFailureCategory.TERMINATED,
            RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE,
            RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE,
        ).forEach { reason ->
            val viewModel = viewModel(
                initialPresentation = RecoveryPresentation.Candidates(
                    listOf(RecoveryCandidateSummary("candidate", RecoverySource.GOOGLE_DRIVE)),
                ),
                prepare = { _, _ -> RecoveryResult.Failed(reason) },
            )

            viewModel.restore("candidate", TEST_PASSPHRASE)

            assertTrue(waitUntil { viewModel.presentation.value == RecoveryPresentation.Failed(reason) })
        }
    }

    @Test
    fun noVaultCanStartWithoutRestoringAndUnreadableCanRetry() {
        val starts = AtomicInteger()
        val retries = AtomicInteger()
        val noVault = viewModel(createNewVault = { starts.incrementAndGet() })
        val unreadable = viewModel(
            initialPresentation = RecoveryPresentation.UnreadableVault,
            retryUnreadable = { retries.incrementAndGet() },
        )

        noVault.startWithoutRestoring()
        unreadable.retryUnreadableVault()

        assertTrue(waitUntil { starts.get() == 1 })
        assertEquals(1, retries.get())
        assertEquals(RecoveryPresentation.UnreadableVault, unreadable.presentation.value)
    }

    @Test
    fun oneOperationMutexRejectsConcurrentRecoveryActions() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val portableCalls = AtomicInteger()
        val driveCalls = AtomicInteger()
        val viewModel = viewModel(
            discoverDrive = {
                driveCalls.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                RecoveryDiscoveryResult.Candidates(emptyList())
            },
            discoverPortable = {
                portableCalls.incrementAndGet()
                emptyList()
            },
        )

        viewModel.discoverDrive()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        viewModel.discoverPortable()
        assertEquals(0, portableCalls.get())
        release.countDown()
        assertTrue(waitUntil { driveCalls.get() == 1 })
    }

    private fun viewModel(
        initialPresentation: RecoveryPresentation = RecoveryPresentation.NoVault,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        discoverDrive: suspend (Intent?) -> RecoveryDiscoveryResult = {
            RecoveryDiscoveryResult.Candidates(emptyList())
        },
        discoverPortable: suspend () -> List<RecoveryCandidate> = { emptyList() },
        prepare: suspend (String, CharArray) -> RecoveryResult = { _, _ ->
            RecoveryResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        },
        confirmTakeover: suspend (String) -> RecoveryResult = {
            RecoveryResult.Failed(RecoveryFailureCategory.OWNERSHIP_CHANGED)
        },
        createNewVault: suspend () -> Unit = {},
        retryUnreadable: () -> Unit = {},
    ) = RecoveryViewModel(
        initialPresentation = initialPresentation,
        savedStateHandle = savedStateHandle,
        discoverDriveCandidates = discoverDrive,
        discoverPortableCandidates = discoverPortable,
        prepareRecovery = prepare,
        confirmRecoveryTakeover = confirmTakeover,
        createNewVault = createNewVault,
        retryUnreadable = retryUnreadable,
    )

    private fun takeResolution(viewModel: RecoveryViewModel): PendingIntent {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            viewModel.resolutionEffects.tryReceive().getOrNull()?.let { return it }
            Thread.sleep(10)
        }
        error("No resolution effect")
    }

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
        val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let { field ->
            field.isAccessible = true
            field.get(null) as Unsafe
        }
    }
}
