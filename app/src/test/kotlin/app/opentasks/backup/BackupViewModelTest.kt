package app.opentasks.backup

import app.opentasks.core.domain.AndroidBackupStatusSource
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.BackupPackageInfo
import app.opentasks.core.model.BackupUnavailableReason
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupViewModelTest {
    @Test
    fun idleSourceStatusIsPassedThroughUnchanged() {
        val source = FakeStatusSource(
            AndroidBackupStatus.Unavailable(BackupUnavailableReason.VERIFICATION_FAILED),
        )

        val viewModel = BackupViewModel(
            statusSource = source,
            preparePackage = { AndroidBackupStatus.NotPrepared },
            retryPackage = {},
        )

        assertEquals(source.status.value, viewModel.presentation.value.status)
        source.status.value = AndroidBackupStatus.Ready(PACKAGE_INFO)
        assertTrue(
            waitUntil {
                viewModel.presentation.value.status == AndroidBackupStatus.Ready(PACKAGE_INFO)
            },
        )
    }

    @Test
    fun preparePublishesPreparingImmediatelyAndBlocksDuplicateSetup() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val viewModel = viewModel(
            preparePackage = {
                calls.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                AndroidBackupStatus.Ready(PACKAGE_INFO)
            },
        )

        viewModel.prepare("correct horse")

        assertEquals(AndroidBackupStatus.Preparing, viewModel.presentation.value.status)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        viewModel.prepare("another valid passphrase")
        assertEquals(1, calls.get())
        assertEquals(AndroidBackupStatus.Preparing, viewModel.presentation.value.status)
        release.countDown()
    }

    @Test
    fun returnedBoundedUnavailableIsSurfacedWhenSourceRemainsIdle() {
        val unavailable =
            AndroidBackupStatus.Unavailable(BackupUnavailableReason.ENCODING_OR_CRYPTO)
        val viewModel = viewModel(
            preparePackage = { unavailable },
        )

        viewModel.prepare("correct horse")

        assertTrue(waitUntil { viewModel.presentation.value.status == unavailable })
    }

    @Test
    fun everyReturnedInitialFailureExposesTransientReprepareProvenance() {
        enumValues<BackupUnavailableReason>().forEach { reason ->
            val unavailable = AndroidBackupStatus.Unavailable(reason)
            val viewModel = viewModel(
                preparePackage = { unavailable },
            )

            viewModel.prepare("correct horse")

            assertTrue(
                waitUntil {
                    viewModel.presentation.value == BackupPresentation(
                        status = unavailable,
                        canReprepareInitialPackage = true,
                    )
                },
            )
        }
    }

    @Test
    fun durableUnavailableSourceNeverExposesTransientReprepareProvenance() {
        enumValues<BackupUnavailableReason>().forEach { reason ->
            val unavailable = AndroidBackupStatus.Unavailable(reason)
            val viewModel = BackupViewModel(
                statusSource = FakeStatusSource(unavailable),
                preparePackage = { AndroidBackupStatus.NotPrepared },
                retryPackage = {},
            )

            assertEquals(
                BackupPresentation(
                    status = unavailable,
                    canReprepareInitialPackage = false,
                ),
                viewModel.presentation.value,
            )
        }
    }

    @Test
    fun successfulResultReconcilesWithSourceThenResumesSourcePassthrough() {
        val source = FakeStatusSource(AndroidBackupStatus.NotPrepared)
        val ready = AndroidBackupStatus.Ready(PACKAGE_INFO)
        val viewModel = BackupViewModel(
            statusSource = source,
            preparePackage = { ready },
            retryPackage = {},
        )

        viewModel.prepare("correct horse")
        assertTrue(waitUntil { viewModel.presentation.value.status == ready })

        source.status.value = ready
        assertTrue(waitUntil { viewModel.presentation.value.status == ready })
        val pending = AndroidBackupStatus.UpdatePending(UPDATE_PACKAGE_INFO)
        source.status.value = pending
        assertTrue(waitUntil { viewModel.presentation.value.status == pending })
    }

    @Test
    fun prepareRejectsCodePointLengthWithoutTrimmingOrNormalising() {
        val whitespaceCalls = AtomicInteger()
        viewModel(
            preparePackage = {
                whitespaceCalls.incrementAndGet()
                AndroidBackupStatus.NotPrepared
            },
        ).prepare(" 123456789 ")
        assertFalse(waitUntil(timeoutMillis = 250) { whitespaceCalls.get() != 0 })

        val tooLongCalls = AtomicInteger()
        viewModel(
            preparePackage = {
                tooLongCalls.incrementAndGet()
                AndroidBackupStatus.NotPrepared
            },
        ).prepare("🙂".repeat(129))
        assertFalse(waitUntil(timeoutMillis = 250) { tooLongCalls.get() != 0 })

        listOf(
            "12345678901é",
            "12345678901e\u0301",
            "🙂".repeat(128),
        ).forEach { input ->
            val supplied = AtomicReference<CharArray>()
            val called = CountDownLatch(1)
            viewModel(
                preparePackage = { passphrase ->
                    supplied.set(passphrase.copyOf())
                    called.countDown()
                    AndroidBackupStatus.NotPrepared
                },
            ).prepare(input)
            assertTrue(called.await(5, TimeUnit.SECONDS))
            assertArrayEquals(input.toCharArray(), supplied.get())
        }
    }

    @Test
    fun validInputIsCopiedAtPublisherBoundaryAndClearedAfterSuccess() {
        val supplied = AtomicReference<CharArray>()
        val observedAtBoundary = AtomicReference<CharArray>()
        val called = CountDownLatch(1)
        val viewModel = viewModel(
            preparePackage = { passphrase ->
                supplied.set(passphrase)
                observedAtBoundary.set(passphrase.copyOf())
                called.countDown()
                AndroidBackupStatus.Ready(PACKAGE_INFO)
            },
        )

        viewModel.prepare("correct horse")

        assertTrue(called.await(5, TimeUnit.SECONDS))
        assertArrayEquals("correct horse".toCharArray(), observedAtBoundary.get())
        assertTrue(waitUntil { supplied.get().all { it == '\u0000' } })
    }

    @Test
    fun mutableInputIsClearedWhenPublisherFails() {
        val supplied = AtomicReference<CharArray>()
        val called = CountDownLatch(1)
        val viewModel = viewModel(
            preparePackage = { passphrase ->
                supplied.set(passphrase)
                called.countDown()
                throw IllegalStateException("private passphrase must not escape")
            },
        )

        viewModel.prepare("correct horse")

        assertTrue(called.await(5, TimeUnit.SECONDS))
        assertTrue(waitUntil { supplied.get().all { it == '\u0000' } })
        assertEquals(
            AndroidBackupStatus.NotPrepared,
            viewModel.presentation.value.status,
        )
    }

    @Test
    fun cancellationClearsMutableInputAndReturnsPresentationToLatestSource() {
        val supplied = AtomicReference<CharArray>()
        val source = FakeStatusSource(AndroidBackupStatus.NotPrepared)
        val latest = AndroidBackupStatus.Ready(PACKAGE_INFO)
        val called = CountDownLatch(1)
        val viewModel = BackupViewModel(
            statusSource = source,
            preparePackage = { passphrase ->
                supplied.set(passphrase)
                source.status.value = latest
                called.countDown()
                throw CancellationException("cancel test publication")
            },
            retryPackage = {},
        )

        viewModel.prepare("correct horse")

        assertTrue(called.await(5, TimeUnit.SECONDS))
        assertTrue(waitUntil { supplied.get().all { it == '\u0000' } })
        assertTrue(
            waitUntil {
                viewModel.presentation.value == BackupPresentation(
                    status = latest,
                    canReprepareInitialPackage = false,
                )
            },
        )
    }

    @Test
    fun preparingStatusIgnoresConcurrentSetup() {
        val calls = AtomicInteger()
        val source = FakeStatusSource(AndroidBackupStatus.Preparing)
        val viewModel = BackupViewModel(
            statusSource = source,
            preparePackage = {
                calls.incrementAndGet()
                AndroidBackupStatus.NotPrepared
            },
            retryPackage = {},
        )

        viewModel.prepare("correct horse")

        assertFalse(waitUntil(timeoutMillis = 250) { calls.get() != 0 })
        assertEquals(0, calls.get())
    }

    @Test
    fun concurrentPrepareCallsAreSingleFlight() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val viewModel = viewModel(
            preparePackage = {
                calls.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                AndroidBackupStatus.NotPrepared
            },
        )

        viewModel.prepare("correct horse")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        viewModel.prepare("another valid passphrase")
        release.countDown()

        assertFalse(waitUntil(timeoutMillis = 250) { calls.get() > 1 })
        assertEquals(1, calls.get())
    }

    @Test
    fun prepareCanRunAgainAfterPreviousPublisherCallCompletes() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val unavailable =
            AndroidBackupStatus.Unavailable(BackupUnavailableReason.ENCODING_OR_CRYPTO)
        val viewModel = viewModel(
            preparePackage = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                if (calls.incrementAndGet() == 1) {
                    unavailable
                } else {
                    AndroidBackupStatus.NotPrepared
                }
            },
        )

        viewModel.prepare("correct horse")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(AndroidBackupStatus.Preparing, viewModel.presentation.value.status)
        release.countDown()
        assertTrue(waitUntil { viewModel.presentation.value.status == unavailable })

        viewModel.prepare("another valid passphrase")

        assertTrue(waitUntil { calls.get() == 2 })
    }

    @Test
    fun transientFailureResubmitPublishesPreparingAndRemainsSingleFlight() {
        val calls = AtomicInteger()
        val enteredRetry = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val unavailable =
            AndroidBackupStatus.Unavailable(BackupUnavailableReason.PACKAGE_TOO_LARGE)
        val viewModel = viewModel(
            preparePackage = {
                if (calls.incrementAndGet() == 1) {
                    unavailable
                } else {
                    enteredRetry.countDown()
                    releaseRetry.await(5, TimeUnit.SECONDS)
                    AndroidBackupStatus.NotPrepared
                }
            },
        )
        viewModel.prepare("correct horse")
        assertTrue(
            waitUntil {
                viewModel.presentation.value.canReprepareInitialPackage
            },
        )

        viewModel.prepare("another valid passphrase")

        assertEquals(
            BackupPresentation(
                status = AndroidBackupStatus.Preparing,
                canReprepareInitialPackage = false,
            ),
            viewModel.presentation.value,
        )
        assertTrue(enteredRetry.await(5, TimeUnit.SECONDS))
        viewModel.prepare("third valid passphrase")
        assertEquals(2, calls.get())
        releaseRetry.countDown()
    }

    @Test
    fun retryDelegatesOnlyToTheLocalRuntime() {
        val retries = AtomicInteger()
        val viewModel = BackupViewModel(
            statusSource = FakeStatusSource(
                AndroidBackupStatus.Unavailable(BackupUnavailableReason.FILE_IO),
            ),
            preparePackage = { AndroidBackupStatus.NotPrepared },
            retryPackage = { retries.incrementAndGet() },
        )

        viewModel.retry()

        assertEquals(1, retries.get())
        assertEquals(
            AndroidBackupStatus.Unavailable(BackupUnavailableReason.FILE_IO),
            viewModel.presentation.value.status,
        )
    }

    private fun viewModel(
        preparePackage: suspend (CharArray) -> AndroidBackupStatus,
    ) = BackupViewModel(
        statusSource = FakeStatusSource(AndroidBackupStatus.NotPrepared),
        preparePackage = preparePackage,
        retryPackage = {},
    )

    private fun waitUntil(
        timeoutMillis: Long = 5_000,
        predicate: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return false
    }

    private class FakeStatusSource(
        initial: AndroidBackupStatus,
    ) : AndroidBackupStatusSource {
        override val status = MutableStateFlow(initial)
    }

    private companion object {
        val PACKAGE_INFO = BackupPackageInfo(
            packageGeneration = BackupGeneration(7),
            currentGeneration = BackupGeneration(7),
            byteCount = 12_345,
            producedAt = Instant.parse("2026-02-14T10:05:00Z"),
        )
        val UPDATE_PACKAGE_INFO = PACKAGE_INFO.copy(
            currentGeneration = BackupGeneration(8),
        )
    }
}
