package app.opentasks.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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
}
