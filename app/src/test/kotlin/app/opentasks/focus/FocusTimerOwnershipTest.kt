package app.opentasks.focus

import app.opentasks.core.data.InMemoryVaultRepository
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.TaskId
import app.opentasks.lock.FakeSharedPreferences
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one decision point every focus transition goes through.
 *
 * Both the boundary alarm receiver and the foreground reconciler call
 * [focusTimerAction] rather than restating these rules, so a stale alarm, a
 * removed task, or a timer another task owns can never reach the vault's
 * timer commands.
 */
class FocusTimerOwnershipTest {

    private val phaseEndsAt = Instant.parse("2026-08-08T09:25:00Z")
    private val taskA = OpenTasksFixtures.tasks[0].id
    private val taskB = OpenTasksFixtures.tasks[1].id

    private fun session(phase: FocusPhaseKind) = FocusSession(
        taskId = "task-1",
        preset = FocusPreset.TWENTY_FIVE_FIVE,
        phase = phase,
        phaseEndsAt = phaseEndsAt,
    )

    @Test
    fun unavailableSessionTaskClearsTheSessionInEitherPhase() {
        FocusPhaseKind.entries.forEach { phase ->
            assertEquals(
                FocusTimerAction.CLEAR_SESSION,
                focusTimerAction(
                    session = session(phase),
                    activeTimerTaskId = null,
                    sessionTaskAvailable = false,
                ),
            )
            assertEquals(
                FocusTimerAction.CLEAR_SESSION,
                focusTimerAction(
                    session = session(phase),
                    activeTimerTaskId = "task-1",
                    sessionTaskAvailable = false,
                ),
            )
        }
    }

    @Test
    fun focusPhaseWithoutARunningTimerStartsTheSessionTask() {
        assertEquals(
            FocusTimerAction.START,
            focusTimerAction(
                session = session(FocusPhaseKind.FOCUS),
                activeTimerTaskId = null,
                sessionTaskAvailable = true,
            ),
        )
    }

    @Test
    fun restPhaseStopsTheTimerTheSessionTaskOwns() {
        assertEquals(
            FocusTimerAction.STOP,
            focusTimerAction(
                session = session(FocusPhaseKind.REST),
                activeTimerTaskId = "task-1",
                sessionTaskAvailable = true,
            ),
        )
    }

    @Test
    fun anotherTaskOwningTheTimerNeverStartsOrStopsInEitherPhase() {
        FocusPhaseKind.entries.forEach { phase ->
            assertEquals(
                FocusTimerAction.CLEAR_SESSION,
                focusTimerAction(
                    session = session(phase),
                    activeTimerTaskId = "task-2",
                    sessionTaskAvailable = true,
                ),
            )
        }
    }

    @Test
    fun aClearedOrReplacedSessionInvalidatesADecisionInFlight() {
        val decidedFrom = session(FocusPhaseKind.FOCUS)

        assertTrue(focusSessionStillCurrent(decidedFrom, decidedFrom))
        // Stopped while the decision was being made.
        assertFalse(focusSessionStillCurrent(null, decidedFrom))
        // Advanced to its next phase by a boundary that landed first.
        assertFalse(
            focusSessionStillCurrent(
                decidedFrom.copy(phase = FocusPhaseKind.REST),
                decidedFrom,
            ),
        )
        // Replaced by a cycle on another task.
        assertFalse(
            focusSessionStillCurrent(decidedFrom.copy(taskId = "task-2"), decidedFrom),
        )
        // Same task, same phase, a different boundary: still a different
        // session. Pins that the whole value participates, so narrowing this
        // to a task-identifier comparison cannot quietly reopen the race.
        assertFalse(
            focusSessionStillCurrent(
                decidedFrom.copy(phaseEndsAt = decidedFrom.phaseEndsAt.plusSeconds(60)),
                decidedFrom,
            ),
        )
    }

    @Test
    fun alreadySettledPhasesDoNothing() {
        assertEquals(
            FocusTimerAction.NONE,
            focusTimerAction(
                session = session(FocusPhaseKind.FOCUS),
                activeTimerTaskId = "task-1",
                sessionTaskAvailable = true,
            ),
        )
        assertEquals(
            FocusTimerAction.NONE,
            focusTimerAction(
                session = session(FocusPhaseKind.REST),
                activeTimerTaskId = null,
                sessionTaskAvailable = true,
            ),
        )
    }

    @Test
    fun manualStopRacingBoundaryCannotRestartTimer() = focusTest {
        val repository = repositoryWithoutTimer()
        repository.execute(DomainCommand.StartTimer(taskA, phaseEndsAt.minusSeconds(60)))
        val store = focusStore(taskA, phaseEndsAt)
        val coordinator = coordinator(store, phaseEndsAt)
        val blockingRepository = BlockingVaultRepository(repository) {
            it is DomainCommand.StopTimerIfOwned
        }

        val stop = async { coordinator.stopTimer(taskA, blockingRepository) }
        blockingRepository.entered.await()
        val boundary = launch { coordinator.onBoundary { blockingRepository } }
        yield()
        assertFalse(boundary.isCompleted)
        blockingRepository.release.complete(Unit)
        stop.await()
        boundary.join()

        assertNull(store.load())
        assertNull(repository.currentWorkspace().home.activeTimer)
    }

    @Test
    fun manualStopRacingNewFocusStartLeavesNoOrphanedSession() = focusTest {
        val repository = repositoryWithoutTimer()
        val store = FocusSessionStore(FakeSharedPreferences())
        val coordinator = coordinator(store, phaseEndsAt)
        val blockingRepository = BlockingVaultRepository(repository) {
            it is DomainCommand.StartTimer
        }

        val start = async {
            coordinator.start(taskA, FocusPreset.TWENTY_FIVE_FIVE, blockingRepository)
        }
        blockingRepository.entered.await()
        val stop = async { coordinator.stopTimer(taskA, blockingRepository) }
        yield()
        assertFalse(stop.isCompleted)
        blockingRepository.release.complete(Unit)
        start.await()
        stop.await()

        assertNull(store.load())
        assertNull(repository.currentWorkspace().home.activeTimer)
    }

    @Test
    fun manualStopDoesNotTouchAnotherTimerOwner() = focusTest {
        val repository = repositoryWithoutTimer()
        repository.execute(DomainCommand.StartTimer(taskB, phaseEndsAt.minusSeconds(60)))
        val store = focusStore(taskA, phaseEndsAt)
        val coordinator = coordinator(store, phaseEndsAt)

        val result = coordinator.stopTimer(taskA, repository)

        assertTrue(result is CommandResult.Rejected)
        assertEquals(
            RejectionReason.TIMER_OWNERSHIP_CHANGED,
            (result as CommandResult.Rejected).reason,
        )
        assertNull(store.load())
        assertEquals(taskB, repository.currentWorkspace().home.activeTimer?.taskId)
    }

    @Test
    fun onResumeAfterManualStopDoesNotStartReplacementEntry() = focusTest {
        val repository = repositoryWithoutTimer()
        repository.execute(DomainCommand.StartTimer(taskA, phaseEndsAt.minusSeconds(60)))
        val store = focusStore(taskA, phaseEndsAt.plusSeconds(60))
        val coordinator = coordinator(store, phaseEndsAt)

        coordinator.stopTimer(taskA, repository)
        val entryCountAfterStop = repository.currentWorkspace().timeEntries.size
        coordinator.reconcile { repository }

        assertNull(store.load())
        assertNull(repository.currentWorkspace().home.activeTimer)
        assertEquals(entryCountAfterStop, repository.currentWorkspace().timeEntries.size)
    }

    @Test
    fun unrelatedTimerStopKeepsGenericStopBehavior() = focusTest {
        val repository = repositoryWithoutTimer()
        repository.execute(DomainCommand.StartTimer(taskB, phaseEndsAt.minusSeconds(60)))
        val store = focusStore(taskA, phaseEndsAt)
        val coordinator = coordinator(store, phaseEndsAt)

        val result = coordinator.stopTimer(taskB, repository)

        assertTrue(result is CommandResult.Success)
        assertEquals(taskA.value, store.load()?.taskId)
        assertNull(repository.currentWorkspace().home.activeTimer)
    }

    private fun focusTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000) { coroutineScope { block() } }
    }

    private suspend fun repositoryWithoutTimer(): InMemoryVaultRepository =
        inMemoryRepository().also {
            it.execute(DomainCommand.StopTimer)
        }

    private fun inMemoryRepository(): InMemoryVaultRepository {
        // The real constructor is module-internal; keep the production module
        // untouched and cross that boundary only in this JVM test.
        val constructor = InMemoryVaultRepository::class.java.declaredConstructors
            .single { it.parameterCount == 6 }
            .apply { isAccessible = true }
        return constructor.newInstance(
            OpenTasksFixtures.snapshot,
            null,
            null,
            null,
            14,
            null,
        ) as InMemoryVaultRepository
    }

    private fun focusStore(taskId: TaskId, endsAt: Instant): FocusSessionStore =
        FocusSessionStore(FakeSharedPreferences()).also { store ->
            store.save(
                FocusSession(
                    taskId = taskId.value,
                    preset = FocusPreset.TWENTY_FIVE_FIVE,
                    phase = FocusPhaseKind.FOCUS,
                    phaseEndsAt = endsAt,
                ),
            )
        }

    private fun coordinator(store: FocusSessionStore, now: Instant): FocusCoordinator =
        FocusCoordinator(
            store = store,
            controller = FocusSessionController(Clock.fixed(now, ZoneOffset.UTC)),
            scheduleAlarm = {},
            cancelAlarm = {},
            notifyPhaseStarted = {},
        )

    private class BlockingVaultRepository(
        private val delegate: VaultRepository,
        private val shouldBlock: (DomainCommand) -> Boolean,
    ) : VaultRepository by delegate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun execute(command: DomainCommand): CommandResult {
            if (shouldBlock(command)) {
                entered.complete(Unit)
                release.await()
            }
            return delegate.execute(command)
        }
    }
}
