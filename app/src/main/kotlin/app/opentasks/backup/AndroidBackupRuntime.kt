package app.opentasks.backup

import app.opentasks.core.data.backup.BackupStateEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

interface AndroidBackupRuntime {
    fun start()
    fun retry()
}

class DefaultAndroidBackupRuntime(
    private val scope: CoroutineScope,
    private val restoredPackageIntake: suspend () -> RestoredPackageIntakeResult,
    private val bootstrapContentKey: () -> Unit,
    private val requestLocalBackup: suspend () -> Unit,
    private val observeBackupState: () -> Flow<BackupStateEntity>,
    private val envelopeAvailable: suspend () -> Boolean,
    private val refreshPortablePackage: suspend () -> Unit,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) : AndroidBackupRuntime {
    private val started = AtomicBoolean()
    private val running = AtomicBoolean()
    private val keyBootstrapped = AtomicBoolean()

    override fun start() {
        if (started.compareAndSet(false, true)) launch()
    }

    override fun retry() {
        if (started.get()) launch()
    }

    private fun launch() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                run()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                // Repository editing remains independent; retry is an explicit lifecycle entry.
            } finally {
                running.set(false)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun run() {
        if (restoredPackageIntake() == RestoredPackageIntakeResult.PreservationBlocked) return
        bootstrapKeyOnce()
        requestLocalBackup()
        var firstGeneration = true
        val states = observeBackupState()
            .distinctUntilChangedBy { it.currentGeneration to it.recoveryEnvelopeReady }
        val debounced = if (debounceMillis > 0) states.debounce(debounceMillis) else states
        debounced.collect { state ->
            if (!firstGeneration) requestLocalBackup()
            firstGeneration = false
            if (state.recoveryEnvelopeReady && envelopeAvailable()) {
                refreshPortablePackage()
            }
        }
    }

    private fun bootstrapKeyOnce() {
        if (keyBootstrapped.get()) return
        synchronized(keyBootstrapped) {
            if (!keyBootstrapped.get()) {
                bootstrapContentKey()
                keyBootstrapped.set(true)
            }
        }
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 500L
    }
}
