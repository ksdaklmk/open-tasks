package app.opentasks.focus

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Wraps the plain [SharedPreferences] file holding the one running focus
 * cycle, following the same shape as
 * [app.opentasks.lock.AppLockSettings].
 *
 * Nothing here is vault-scoped: a focus cycle is a device-local timing aid, so
 * it survives independently of which vault slot is active and holds no task
 * text -- only the identifier of the task whose timer the cycle drives.
 *
 * A value this cannot interpret is never guessed at: the whole session is
 * cleared and `null` returned, because a half-understood session would
 * otherwise drive real timer transitions on the wrong task, phase, or
 * boundary.
 */
class FocusSessionStore(private val prefs: SharedPreferences) {
    private val mutableSession = MutableStateFlow(read())

    val session: StateFlow<FocusSession?> = mutableSession.asStateFlow()

    fun load(): FocusSession? = read().also { mutableSession.value = it }

    fun save(session: FocusSession) {
        prefs.edit {
            putString(KEY_TASK_ID, session.taskId)
            putString(KEY_PRESET, session.preset.name)
            putString(KEY_PHASE, session.phase.name)
            putLong(KEY_PHASE_END, session.phaseEndsAt.toEpochMilli())
        }
        mutableSession.value = session
    }

    fun clear() {
        removeAll()
        mutableSession.value = null
    }

    private fun read(): FocusSession? {
        if (KEYS.none(prefs::contains)) return null
        val taskId = runCatching { prefs.getString(KEY_TASK_ID, null) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
        val preset = runCatching { prefs.getString(KEY_PRESET, null) }
            .getOrNull()
            ?.let { stored -> FocusPreset.entries.firstOrNull { it.name == stored } }
        val phase = runCatching { prefs.getString(KEY_PHASE, null) }
            .getOrNull()
            ?.let { stored -> FocusPhaseKind.entries.firstOrNull { it.name == stored } }
        val phaseEndsAt = runCatching { Instant.ofEpochMilli(prefs.getLong(KEY_PHASE_END, 0L)) }
            .getOrNull()
            .takeIf { prefs.contains(KEY_PHASE_END) }
        if (taskId == null || preset == null || phase == null || phaseEndsAt == null) {
            removeAll()
            return null
        }
        return FocusSession(
            taskId = taskId,
            preset = preset,
            phase = phase,
            phaseEndsAt = phaseEndsAt,
        )
    }

    private fun removeAll() {
        prefs.edit { KEYS.forEach(::remove) }
    }

    private companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_PRESET = "preset"
        const val KEY_PHASE = "phase"
        const val KEY_PHASE_END = "phase_end"
        val KEYS = listOf(KEY_TASK_ID, KEY_PRESET, KEY_PHASE, KEY_PHASE_END)
    }
}
