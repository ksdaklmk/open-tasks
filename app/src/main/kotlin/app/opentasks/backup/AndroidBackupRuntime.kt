package app.opentasks.backup

import app.opentasks.core.data.backup.BackupStateEntity
import app.opentasks.core.data.backup.BackupStateStore
import app.opentasks.core.domain.AndroidBackupStatusSource
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.BackupPackageInfo
import app.opentasks.core.model.BackupUnavailableReason
import app.opentasks.core.model.RestoredPackageCondition
import app.opentasks.core.model.VaultId
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AndroidBackupRuntime {
    fun start()
    fun retry()
}

class DefaultAndroidBackupRuntime(
    private val scope: CoroutineScope,
    private val restoredPackageIntake: suspend () -> RestoredPackageIntakeResult,
    private val bootstrapContentKey: suspend () -> Boolean,
    private val requestLocalBackup: suspend () -> Unit,
    private val observeBackupState: () -> Flow<BackupStateEntity>,
    private val envelopeAvailable: suspend () -> Boolean,
    private val recordStatus: suspend (AndroidBackupStatus) -> Unit,
    private val restoredPublicationBlocked: suspend () -> Boolean,
    private val refreshPortablePackage: suspend () -> Unit,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) : AndroidBackupRuntime {
    private val started = AtomicBoolean()
    private val running = AtomicBoolean()
    private val keyBootstrapped = AtomicBoolean()
    private val keyBootstrapMutex = Mutex()

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
        val intakeResult = try {
            restoredPackageIntake()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            RestoredPackageIntakeResult.PreservationBlocked
        }
        val detectedStatus = when (intakeResult) {
            is RestoredPackageIntakeResult.Preserved ->
                AndroidBackupStatus.RestoredPackageDetected(intakeResult.condition)
            RestoredPackageIntakeResult.PreservationBlocked ->
                AndroidBackupStatus.RestoredPackageDetected(
                    RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT,
                )
            else -> null
        }
        if (detectedStatus != null) {
            try {
                recordStatus(detectedStatus)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                // Current-process publication remains blocked even if persistence failed.
            }
        }
        val blockedByIntake = detectedStatus != null
        val blockedByPersistedStatus = try {
            restoredPublicationBlocked()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            true
        }
        val publicationBlocked = blockedByIntake || blockedByPersistedStatus
        if (!bootstrapKeyOnce()) return
        requestLocalBackup()
        var firstGeneration = true
        val states = observeBackupState()
            .distinctUntilChangedBy { it.currentGeneration to it.recoveryEnvelopeReady }
        val debounced = if (debounceMillis > 0) states.debounce(debounceMillis) else states
        debounced.collect { state ->
            if (!firstGeneration) requestLocalBackup()
            firstGeneration = false
            if (
                !publicationBlocked &&
                state.recoveryEnvelopeReady &&
                envelopeAvailable()
            ) {
                refreshPortablePackage()
            }
        }
    }

    private suspend fun bootstrapKeyOnce(): Boolean {
        if (keyBootstrapped.get()) return true
        return keyBootstrapMutex.withLock {
            if (keyBootstrapped.get()) {
                true
            } else if (bootstrapContentKey()) {
                keyBootstrapped.set(true)
                true
            } else {
                false
            }
        }
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 500L
    }
}

class PersistedAndroidBackupStatusSource(
    scope: CoroutineScope,
    observeBackupState: () -> Flow<BackupStateEntity>,
) : AndroidBackupStatusSource {
    override val status = observeBackupState()
        .map(::storedAndroidBackupStatus)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AndroidBackupStatus.NotPrepared,
        )
}

internal suspend fun recordRestoredPackageStatus(
    stateStore: BackupStateStore,
    vaultId: VaultId,
    status: AndroidBackupStatus.RestoredPackageDetected,
) {
    repeat(STATUS_UPDATE_ATTEMPTS) {
        val state = stateStore.get(vaultId) ?: return
        val detected = state.copy(
            packageState = PACKAGE_RESTORED_DETECTED,
            failureCategory = status.condition.name,
        )
        if (stateStore.compareAndUpdate(detected, state.currentGeneration) == 1) return
    }
    error("Restored package status changed during persistence")
}

internal suspend fun restoredPackagePublicationBlocked(
    stateStore: BackupStateStore,
    vaultId: VaultId,
): Boolean = stateStore.get(vaultId)?.packageState == PACKAGE_RESTORED_DETECTED

private fun storedAndroidBackupStatus(state: BackupStateEntity): AndroidBackupStatus =
    when (state.packageState) {
        PACKAGE_RESTORED_DETECTED -> AndroidBackupStatus.RestoredPackageDetected(
            enumValues<RestoredPackageCondition>().firstOrNull {
                it.name == state.failureCategory
            } ?: RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT,
        )
        "PREPARING" -> AndroidBackupStatus.Preparing
        "READY" -> state.packageInfoOrNull()?.let(AndroidBackupStatus::Ready)
            ?: AndroidBackupStatus.Unavailable(BackupUnavailableReason.FILE_IO)
        "UPDATE_PENDING" -> state.packageInfoOrNull()?.let(AndroidBackupStatus::UpdatePending)
            ?: AndroidBackupStatus.Unavailable(BackupUnavailableReason.FILE_IO)
        "UNAVAILABLE" -> AndroidBackupStatus.Unavailable(
            enumValues<BackupUnavailableReason>().firstOrNull {
                it.name == state.failureCategory
            } ?: BackupUnavailableReason.FILE_IO,
        )
        else -> AndroidBackupStatus.NotPrepared
    }

private fun BackupStateEntity.packageInfoOrNull(): BackupPackageInfo? {
    val generation = portablePackageGeneration ?: return null
    val bytes = portablePackageBytes ?: return null
    val producedAt = portablePackageProducedAtEpochMillis ?: return null
    return BackupPackageInfo(
        packageGeneration = BackupGeneration(generation),
        currentGeneration = BackupGeneration(currentGeneration),
        byteCount = bytes,
        producedAt = Instant.ofEpochMilli(producedAt),
    )
}

private const val PACKAGE_RESTORED_DETECTED = "RESTORED_PACKAGE_DETECTED"
private const val STATUS_UPDATE_ATTEMPTS = 3
