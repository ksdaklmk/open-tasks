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
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupViewModelTest {
    @Test
    fun statusIsPassedThroughUnchanged() {
        val source = FakeStatusSource(
            AndroidBackupStatus.Unavailable(BackupUnavailableReason.VERIFICATION_FAILED),
        )

        val viewModel = BackupViewModel(
            statusSource = source,
            preparePackage = { AndroidBackupStatus.NotPrepared },
            retryPackage = {},
        )

        assertSame(source.status, viewModel.status)
        source.status.value = AndroidBackupStatus.Preparing
        assertEquals(AndroidBackupStatus.Preparing, viewModel.status.value)
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
        assertEquals(AndroidBackupStatus.NotPrepared, viewModel.status.value)
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
            viewModel.status.value,
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
    }
}
