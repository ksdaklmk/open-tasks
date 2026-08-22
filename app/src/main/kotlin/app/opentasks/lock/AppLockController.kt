package app.opentasks.lock

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * sleeping, and [wait] lets tests expire the background timer directly.
 */
class AppLockController(
    private val settings: AppLockSettings,
    private val now: () -> Instant = Instant::now,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val wait: suspend (Duration) -> Unit = { delay(it.toMillis()) },
) {
    private val stateGuard = Any()
    private val lockedState = MutableStateFlow(settings.lockEnabled)
    private var backgroundedAt: Instant? = null
    private var expiryJob: Job? = null
    private var expiryGeneration: Long = 0

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
                synchronized(stateGuard) {
                    if (settings.lockEnabled) {
                        if (backgroundedAt != null) scheduleExpiryLocked()
                    } else {
                        cancelExpiryLocked()
                        lockedState.value = false
                    }
                }
            }
        }
    }

    fun onAppBackgrounded() {
        synchronized(stateGuard) {
            backgroundedAt = now()
            if (settings.lockEnabled) scheduleExpiryLocked() else cancelExpiryLocked()
        }
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
        return synchronized(stateGuard) {
            cancelExpiryLocked()
            val since = backgroundedAt
            backgroundedAt = null
            if (!settings.lockEnabled) return@synchronized false
            val mustLock = since == null ||
                Duration.between(since, now()) >= settings.lockDelay.duration
            if (mustLock) lockedState.value = true
            mustLock
        }
    }

    fun onUnlocked() {
        synchronized(stateGuard) {
            cancelExpiryLocked()
            lockedState.value = false
        }
    }

    private fun scheduleExpiryLocked() {
        cancelExpiryLocked()
        val since = backgroundedAt ?: return
        val generation = expiryGeneration
        val remaining = settings.lockDelay.duration.minus(Duration.between(since, now()))
            .let { if (it.isNegative) Duration.ZERO else it }
        expiryJob = scope.launch {
            wait(remaining)
            synchronized(stateGuard) {
                if (
                    generation == expiryGeneration &&
                    backgroundedAt == since &&
                    settings.lockEnabled
                ) {
                    lockedState.value = true
                }
            }
        }
    }

    private fun cancelExpiryLocked() {
        expiryGeneration += 1
        expiryJob?.cancel()
        expiryJob = null
    }
}
