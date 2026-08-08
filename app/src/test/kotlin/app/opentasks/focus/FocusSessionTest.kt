package app.opentasks.focus

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class FocusSessionTest {

    private val start = Instant.parse("2026-08-08T09:00:00Z")
    private val initial = FocusSession(
        taskId = "task-1",
        preset = FocusPreset.TWENTY_FIVE_FIVE,
        phase = FocusPhaseKind.FOCUS,
        phaseEndsAt = start.plus(Duration.ofMinutes(25)),
    )

    private fun controllerAt(now: Instant) =
        FocusSessionController(Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun startPersistsFocusPhaseAndItsEnd() {
        val session = controllerAt(start).start("task-1", FocusPreset.TWENTY_FIVE_FIVE)
        assertEquals(FocusPhaseKind.FOCUS, session.phase)
        assertEquals(start.plus(Duration.ofMinutes(25)), session.phaseEndsAt)
    }

    @Test
    fun exactBoundaryAndDelayedReconcileReachTheCurrentPhase() {
        val rest = controllerAt(start.plus(Duration.ofMinutes(25))).reconcile(initial)!!
        assertEquals(FocusPhaseKind.REST, rest.phase)
        assertEquals(start.plus(Duration.ofMinutes(30)), rest.phaseEndsAt)

        val secondFocus = controllerAt(start.plus(Duration.ofMinutes(31))).reconcile(initial)!!
        assertEquals(FocusPhaseKind.FOCUS, secondFocus.phase)
        assertEquals(start.plus(Duration.ofMinutes(55)), secondFocus.phaseEndsAt)
    }

    @Test
    fun preBoundaryClockLeavesSessionUnchanged() {
        assertEquals(
            initial,
            controllerAt(start.plus(Duration.ofMinutes(24))).reconcile(initial),
        )
    }
}
