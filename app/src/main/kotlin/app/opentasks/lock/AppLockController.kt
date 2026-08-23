package app.opentasks.lock

import android.os.SystemClock
import java.time.Duration
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
 * overlay calls [onUnlocked] once biometric authentication succeeds.
 * [elapsedRealtime] is injected so delay-boundary behaviour can be tested
 * without sleeping, and [wait] lets tests expire the background timer
 * directly.
 */
class AppLockController(
    private val settings: AppLockSettings,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val wait: suspend (Duration) -> Unit = { delay(it.toMillis()) },
    private val scheduleDurableExpiry: (Duration) -> Unit = {},
    private val cancelDurableExpiry: () -> Unit = {},
) {
    private val stateGuard = Any()
    private val lockedState = MutableStateFlow(settings.lockEnabled)
    private val externalContentConcealedState = MutableStateFlow(settings.lockEnabled)
    private var backgroundedAtElapsedRealtime: Long? = null
    private var unlockAttemptGeneration: Long = 0
    private var expiryJob: Job? = null
    private var expiryGeneration: Long = 0

    /** `true` on cold start when [AppLockSettings.lockEnabled] is set. */
    val locked: StateFlow<Boolean> = lockedState.asStateFlow()

    /** `true` while widgets and reminder notifications must omit private content. */
    val externalContentConcealed: StateFlow<Boolean> =
        externalContentConcealedState.asStateFlow()

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
                        if (backgroundedAtElapsedRealtime != null) {
                            externalContentConcealedState.value = true
                            scheduleExpiryLocked()
                        }
                    } else {
                        cancelExpiryLocked()
                        lockedState.value = false
                        externalContentConcealedState.value = false
                    }
                }
            }
        }
    }

    fun onAppBackgrounded() {
        synchronized(stateGuard) {
            unlockAttemptGeneration += 1
            backgroundedAtElapsedRealtime = elapsedRealtime()
            if (settings.lockEnabled) {
                externalContentConcealedState.value = true
                scheduleExpiryLocked()
            } else {
                cancelExpiryLocked()
            }
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
            val since = backgroundedAtElapsedRealtime
            backgroundedAtElapsedRealtime = null
            if (!settings.lockEnabled) {
                externalContentConcealedState.value = false
                return@synchronized false
            }
            val mustLock = since == null ||
                elapsedSince(since) >= settings.lockDelay.duration
            if (mustLock) {
                lockedState.value = true
                externalContentConcealedState.value = true
            } else {
                externalContentConcealedState.value = lockedState.value
            }
            mustLock
        }
    }

    fun beginUnlockAttempt(): Long = synchronized(stateGuard) {
        unlockAttemptGeneration += 1
        unlockAttemptGeneration
    }

    fun onUnlocked(attemptGeneration: Long) {
        synchronized(stateGuard) {
            if (
                attemptGeneration != unlockAttemptGeneration ||
                backgroundedAtElapsedRealtime != null
            ) {
                return@synchronized
            }
            cancelExpiryLocked()
            lockedState.value = false
            externalContentConcealedState.value = false
        }
    }

    /** Refreshes elapsed lock state before authorizing an external action. */
    fun isExternalActionAuthorized(): Boolean = synchronized(stateGuard) {
        if (!settings.lockEnabled) return@synchronized true
        val expired = backgroundedAtElapsedRealtime?.let { since ->
            elapsedSince(since) >= settings.lockDelay.duration
        } ?: false
        if (expired) {
            lockedState.value = true
            externalContentConcealedState.value = true
        }
        !lockedState.value
    }

    private fun scheduleExpiryLocked() {
        cancelProcessExpiryLocked()
        val since = backgroundedAtElapsedRealtime ?: return
        val generation = expiryGeneration
        val remaining = settings.lockDelay.duration.minus(elapsedSince(since))
            .let { if (it.isNegative) Duration.ZERO else it }
        scheduleDurableExpiry(remaining)
        expiryJob = scope.launch {
            wait(remaining)
            synchronized(stateGuard) {
                if (
                    generation == expiryGeneration &&
                    backgroundedAtElapsedRealtime == since &&
                    settings.lockEnabled
                ) {
                    lockedState.value = true
                    externalContentConcealedState.value = true
                }
            }
        }
    }

    private fun elapsedSince(since: Long): Duration =
        Duration.ofMillis(elapsedRealtime() - since)

    private fun cancelExpiryLocked() {
        cancelProcessExpiryLocked()
        cancelDurableExpiry()
    }

    private fun cancelProcessExpiryLocked() {
        expiryGeneration += 1
        expiryJob?.cancel()
        expiryJob = null
    }
}
