package app.opentasks.focus

import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant

/** The two focus/break cycles the timer offers. */
enum class FocusPreset(val focus: Duration, val rest: Duration) {
    TWENTY_FIVE_FIVE(Duration.ofMinutes(25), Duration.ofMinutes(5)),
    FIFTY_TEN(Duration.ofMinutes(50), Duration.ofMinutes(10)),
}

enum class FocusPhaseKind { FOCUS, REST }

/**
 * One running focus cycle, as persisted device-locally.
 *
 * [taskId] is the raw identifier value rather than a `TaskId` so the whole
 * model stays free of anything a plain JVM test cannot construct, and so the
 * store can round-trip it through [android.content.SharedPreferences]
 * unchanged.
 */
data class FocusSession(
    val taskId: String,
    val preset: FocusPreset,
    val phase: FocusPhaseKind,
    val phaseEndsAt: Instant,
)

/**
 * Produces and advances [FocusSession] phases against a [Clock].
 *
 * Phase edges stay anchored to the previously persisted end rather than to
 * "now", so a boundary alarm the system delivers late -- or a process that was
 * dead across several boundaries -- lands on exactly the phase the cycle
 * reached, with no drift and no unbounded catch-up loop.
 */
class FocusSessionController(private val clock: Clock) {
    fun start(taskId: String, preset: FocusPreset): FocusSession = FocusSession(
        taskId = taskId,
        preset = preset,
        phase = FocusPhaseKind.FOCUS,
        phaseEndsAt = clock.instant().plus(preset.focus),
    )

    /**
     * Returns [session] advanced to the phase whose end is strictly after the
     * current instant, the same session when that boundary has not been
     * reached yet, or `null` when the arithmetic would leave the representable
     * range -- a session no phase can be computed for is one the caller must
     * abandon, not one it may guess at.
     */
    fun reconcile(session: FocusSession): FocusSession? {
        val now = clock.instant()
        if (now.isBefore(session.phaseEndsAt)) return session
        val focusSeconds = session.preset.focus.seconds
        val restSeconds = session.preset.rest.seconds
        val cycleSeconds = focusSeconds + restSeconds
        if (cycleSeconds <= 0L) return null
        // The phase that follows the persisted one; every later edge is a whole
        // cycle away from one of these two.
        val nextPhaseSeconds = when (session.phase) {
            FocusPhaseKind.FOCUS -> restSeconds
            FocusPhaseKind.REST -> focusSeconds
        }
        return try {
            val elapsedSeconds = Duration.between(session.phaseEndsAt, now).seconds
            val wholeCycles = elapsedSeconds / cycleSeconds
            val remainderSeconds = elapsedSeconds % cycleSeconds
            val withinNextPhase = remainderSeconds < nextPhaseSeconds
            val offsetSeconds = if (withinNextPhase) {
                Math.addExact(
                    Math.multiplyExact(wholeCycles, cycleSeconds),
                    nextPhaseSeconds,
                )
            } else {
                Math.multiplyExact(Math.addExact(wholeCycles, 1L), cycleSeconds)
            }
            session.copy(
                phase = if (withinNextPhase) session.phase.next() else session.phase,
                phaseEndsAt = session.phaseEndsAt.plusSeconds(offsetSeconds),
            )
        } catch (_: ArithmeticException) {
            null
        } catch (_: DateTimeException) {
            null
        }
    }
}

private fun FocusPhaseKind.next(): FocusPhaseKind = when (this) {
    FocusPhaseKind.FOCUS -> FocusPhaseKind.REST
    FocusPhaseKind.REST -> FocusPhaseKind.FOCUS
}

internal enum class FocusTimerAction { START, STOP, NONE, CLEAR_SESSION }

/**
 * The single decision point for every focus-driven timer change.
 *
 * Both the boundary alarm receiver and the foreground reconciler route through
 * this, so the safety rule is stated once: a focus transition may only start or
 * stop the timer the persisted focus task owns. A session task that is gone or
 * binned, and a timer another task is running, both resolve to
 * [FocusTimerAction.CLEAR_SESSION] -- the focus session and its alarm go away
 * and that other timer is left exactly as it was.
 */
internal fun focusTimerAction(
    session: FocusSession,
    activeTimerTaskId: String?,
    sessionTaskAvailable: Boolean,
): FocusTimerAction {
    if (!sessionTaskAvailable) return FocusTimerAction.CLEAR_SESSION
    if (activeTimerTaskId != null && activeTimerTaskId != session.taskId) {
        return FocusTimerAction.CLEAR_SESSION
    }
    return when (session.phase) {
        FocusPhaseKind.FOCUS ->
            if (activeTimerTaskId == null) FocusTimerAction.START else FocusTimerAction.NONE
        FocusPhaseKind.REST ->
            if (activeTimerTaskId == null) FocusTimerAction.NONE else FocusTimerAction.STOP
    }
}

/**
 * Whether a decision computed from [decidedFrom] may still be acted on, given
 * what the store holds now ([persisted]).
 *
 * Deciding what to do needs the workspace, and reading the workspace suspends,
 * so the world can move underneath a decision that is already made. Whole-value
 * equality is deliberate rather than a task-identifier comparison: a session
 * that was stopped, replaced, re-preset, or advanced to its next phase while the
 * decision was in flight all invalidate it equally, and the caller must abort
 * instead of applying a conclusion drawn from a session that no longer exists.
 */
internal fun focusSessionStillCurrent(
    persisted: FocusSession?,
    decidedFrom: FocusSession,
): Boolean = persisted == decidedFrom
