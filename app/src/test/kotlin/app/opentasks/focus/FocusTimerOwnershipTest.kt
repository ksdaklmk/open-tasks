package app.opentasks.focus

import org.junit.Assert.assertEquals
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
