package app.opentasks.lock

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives whether the app's workspace content is concealed behind the app
 * lock overlay.
 *
 * `MainActivity` is the only observer this needs: the single activity's own
 * `onStart`/`onStop` report foreground and background transitions, and its
 * overlay calls [onUnlocked] once biometric authentication succeeds --
 * there is no process-level lifecycle to track beyond that one activity.
 * [now] is injected so delay-boundary behaviour can be tested without
 * sleeping.
 */
class AppLockController(
    private val settings: AppLockSettings,
    private val now: () -> Instant = Instant::now,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lockedState = MutableStateFlow(settings.lockEnabled)
    private var backgroundedAt: Instant? = null

    /** `true` on cold start when [AppLockSettings.lockEnabled] is set. */
    val locked: StateFlow<Boolean> = lockedState.asStateFlow()

    init {
        // Turning the feature off elsewhere (the More screen, while this
        // overlay is up) must not leave a person stuck behind a prompt for
        // a lock that no longer applies. UNDISPATCHED runs this coroutine
        // synchronously up to its first suspension point, so the listener
        // [AppLockSettings.observe] registers before the constructor
        // returns -- a change made the instant construction completes can
        // never race past a not-yet-registered listener.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settings.observe().collect {
                if (!settings.lockEnabled) lockedState.value = false
            }
        }
    }

    fun onAppBackgrounded() {
        backgroundedAt = now()
    }

    /**
     * Reports a foreground transition and returns `true` if it must lock
     * the app now.
     *
     * No prior [onAppBackgrounded] (a fresh cold start) is treated the same
     * as an elapsed span at or beyond the delay: the safe default is
     * locked, matching [locked]'s own cold-start value.
     */
    fun onAppForegrounded(): Boolean {
        if (!settings.lockEnabled) return false
        val since = backgroundedAt
        backgroundedAt = null
        val mustLock = since == null ||
            Duration.between(since, now()) >= settings.lockDelay.duration
        if (mustLock) lockedState.value = true
        return mustLock
    }

    fun onUnlocked() {
        lockedState.value = false
    }
}
