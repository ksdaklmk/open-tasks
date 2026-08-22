package app.opentasks.widget

import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ZonedMoment
import app.opentasks.lock.AppLockController
import app.opentasks.lock.AppLockSettings
import app.opentasks.lock.FakeSharedPreferences
import app.opentasks.lock.LockDelay
import app.opentasks.lock.onUnlocked
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetActionGateTest {
    @Test
    fun backgroundImmediatelyBlocksWidgetTitlePublication() {
        var elapsedRealtime = Duration.ofHours(12).toMillis()
        val settings = AppLockSettings(FakeSharedPreferences()).apply {
            lockEnabled = true
            lockDelay = LockDelay.FIVE_MINUTES
        }
        val controller = AppLockController(settings, elapsedRealtime = { elapsedRealtime })
        controller.onUnlocked()
        controller.onAppBackgrounded()

        assertFalse(widgetTitlesAuthorized(true, controller, settings))

        elapsedRealtime += Duration.ofMinutes(5).toMillis()

        assertFalse(widgetTitlesAuthorized(true, controller, settings))
    }

    private val zone = ZoneId.of("Asia/Bangkok")
    private val today = LocalDate.of(2026, 8, 9)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val task = OpenTasksFixtures.snapshot.tasks.first().copy(
        start = null,
        due = ZonedMoment(today.atTime(16, 0).atZone(zone).toInstant(), zone.id),
    )
    private val snapshot = OpenTasksFixtures.snapshot.copy(tasks = listOf(task))

    @Test
    fun capturedTapReadsLiveAuthorizationBeforeDispatch() = runBlocking {
        withTimeout(5_000) {
            val gate = WidgetActionGate()
            val captured = gate.capture()
            val settings = AppLockSettings(FakeSharedPreferences()).apply {
                lockEnabled = true
                lockDelay = LockDelay.IMMEDIATE
            }
            val controller = AppLockController(settings, elapsedRealtime = { 0L })
            controller.onUnlocked()
            val commands = mutableListOf<DomainCommand>()
            controller.onAppBackgrounded()

            gate.dispatchCompletion(
                captured,
                snapshot,
                task.id.value,
                today,
                zone,
                now,
                appLockController = controller,
                appLockSettings = settings,
                executeAuthorized = { command, isAuthorized ->
                    if (isAuthorized()) commands += command
                },
            )

            assertTrue(commands.isEmpty())
        }
    }

    @Test
    fun capturedTapIsInvalidAfterPublisherStops() = runBlocking {
        withTimeout(5_000) {
            val gate = WidgetActionGate()
            val captured = gate.capture()
            val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }
            val controller = AppLockController(settings, elapsedRealtime = { 0L })
            controller.onUnlocked()
            val commands = mutableListOf<DomainCommand>()
            gate.invalidate()

            gate.dispatchCompletion(
                captured,
                snapshot,
                task.id.value,
                today,
                zone,
                now,
                appLockController = controller,
                appLockSettings = settings,
                executeAuthorized = { command, isAuthorized ->
                    if (isAuthorized()) commands += command
                },
            )

            assertTrue(commands.isEmpty())
        }
    }

    @Test
    fun invalidationDuringLiveAuthorizationPreventsDispatch() = runBlocking {
        withTimeout(5_000) {
            val gate = WidgetActionGate()
            val authorizationStarted = CountDownLatch(1)
            val finishAuthorization = CountDownLatch(1)
            var dispatched = false

            val action = launch(Dispatchers.Default) {
                gate.dispatch(
                    capturedGeneration = gate.capture(),
                    isAuthorized = {
                        authorizationStarted.countDown()
                        assertTrue(finishAuthorization.await(5, TimeUnit.SECONDS))
                        true
                    },
                    action = { dispatched = true },
                )
            }
            assertTrue(authorizationStarted.await(5, TimeUnit.SECONDS))
            gate.invalidate()
            finishAuthorization.countDown()
            action.join()

            assertFalse(dispatched)
        }
    }

    @Test
    fun liveAuthorizedTapDispatchesExactlyOnce() = runBlocking {
        withTimeout(5_000) {
            val gate = WidgetActionGate()
            val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }
            val controller = AppLockController(settings, elapsedRealtime = { 0L })
            controller.onUnlocked()
            val commands = mutableListOf<DomainCommand>()

            gate.dispatchCompletion(
                gate.capture(),
                snapshot,
                task.id.value,
                today,
                zone,
                now,
                appLockController = controller,
                appLockSettings = settings,
                executeAuthorized = { command, isAuthorized ->
                    if (isAuthorized()) commands += command
                },
            )

            assertEquals(1, commands.size)
            assertEquals(task.id, (commands.single() as DomainCommand.CompleteTask).taskId)
        }
    }

    @Test
    fun invalidatedGenerationIsRejectedAtCommit() = runBlocking {
        withTimeout(5_000) {
            val gate = WidgetActionGate()
            val settings = AppLockSettings(FakeSharedPreferences()).apply { lockEnabled = true }
            val controller = AppLockController(settings, elapsedRealtime = { 0L })
            controller.onUnlocked()
            val commands = mutableListOf<DomainCommand>()

            gate.dispatchCompletion(
                gate.capture(),
                snapshot,
                task.id.value,
                today,
                zone,
                now,
                appLockController = controller,
                appLockSettings = settings,
                executeAuthorized = { command, isAuthorized ->
                    gate.invalidate()
                    if (isAuthorized()) commands += command
                },
            )

            assertTrue(commands.isEmpty())
        }
    }

    @Test
    fun staleBackgroundExpiryCannotDispatchWidgetCompletion() = runBlocking {
        withTimeout(5_000) {
            var elapsedRealtime = Duration.ofHours(12).toMillis()
            val waitForever = CompletableDeferred<Unit>()
            val settings = AppLockSettings(FakeSharedPreferences()).apply {
                lockEnabled = true
                lockDelay = LockDelay.FIVE_MINUTES
            }
            val controller = AppLockController(
                settings = settings,
                elapsedRealtime = { elapsedRealtime },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                wait = { waitForever.await() },
            )
            controller.onUnlocked()
            controller.onAppBackgrounded()
            val gate = WidgetActionGate()
            val captured = gate.capture()
            elapsedRealtime += Duration.ofMinutes(5).toMillis()
            val commands = mutableListOf<DomainCommand>()

            gate.dispatchCompletion(
                captured,
                snapshot,
                task.id.value,
                today,
                zone,
                now,
                appLockController = controller,
                appLockSettings = settings,
                executeAuthorized = { command, isAuthorized ->
                    if (isAuthorized()) commands += command
                },
            )

            assertTrue(commands.isEmpty())
        }
    }
}
