package app.opentasks.backup

import app.opentasks.core.data.backup.BackupStateEntity
import app.opentasks.core.data.backup.BackupStateMutation
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val recordStatus:
        suspend (AndroidBackupStatus.RestoredPackageDetected) -> Boolean,
    private val restoredPublicationBlocked: suspend () -> Boolean,
    private val refreshPortablePackage: suspend () -> Unit,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) : AndroidBackupRuntime {
    private val started = AtomicBoolean()
    private val running = AtomicBoolean()
    private val keyBootstrapped = AtomicBoolean()
    private val keyBootstrapMutex = Mutex()
    private val retryRequests = Channel<Unit>(Channel.CONFLATED)

    override fun start() {
        if (started.compareAndSet(false, true)) launch()
    }

    override fun retry() {
        if (started.get()) {
            retryRequests.trySend(Unit)
            launch()
        }
    }

    private fun launch() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            // A signal that launched a stopped owner is represented by that
            // owner's normal first pass, not an immediate duplicate pass.
            retryRequests.tryReceive()
            try {
                run()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                // Repository editing remains independent; retry is an explicit lifecycle entry.
            } finally {
                running.set(false)
                if (retryRequests.tryReceive().isSuccess) launch()
            }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun run() {
        var intakeDecision = inspectRestoredInput()
        var detectedStatus = intakeDecision.detectedStatus
        var statusRecorded = detectedStatus == null || tryRecordStatus(detectedStatus)
        val blockedByPersistedStatus = try {
            restoredPublicationBlocked()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            true
        }
        var publicationBlocked = intakeDecision.blocksPublication || blockedByPersistedStatus
        if (!bootstrapKeyOnce()) {
            if (!statusRecorded && detectedStatus != null) {
                observeBackupState().first()
                statusRecorded = tryRecordStatus(checkNotNull(detectedStatus))
            }
            return
        }
        requestLocalBackup()
        var firstGeneration = true
        var latestState: BackupStateEntity? = null
        val states = observeBackupState()
            .distinctUntilChangedBy { it.currentGeneration to it.recoveryEnvelopeReady }
        val debounced = if (debounceMillis > 0) states.debounce(debounceMillis) else states
        val events = merge(
            debounced.map(RuntimeEvent::StateChanged),
            retryRequests.receiveAsFlow().map { RuntimeEvent.RetryRequested },
        )
        events.collect { event ->
            when (event) {
                is RuntimeEvent.StateChanged -> {
                    val state = event.state
                    latestState = state
                    if (!firstGeneration) requestLocalBackup()
                    firstGeneration = false
                    if (!statusRecorded && detectedStatus != null) {
                        statusRecorded = tryRecordStatus(checkNotNull(detectedStatus))
                    }
                    if (
                        !publicationBlocked &&
                        state.recoveryEnvelopeReady &&
                        envelopeAvailable()
                    ) {
                        refreshPortablePackage()
                    }
                }
                RuntimeEvent.RetryRequested -> {
                    intakeDecision = inspectRestoredInput()
                    detectedStatus = intakeDecision.detectedStatus
                    statusRecorded = detectedStatus?.let { status ->
                        tryRecordStatus(status)
                    } ?: true
                    val persistedBlock = try {
                        restoredPublicationBlocked()
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        true
                    }
                    publicationBlocked =
                        intakeDecision.blocksPublication || persistedBlock
                    if (!bootstrapKeyOnce()) return@collect
                    requestLocalBackup()
                    latestState?.let { state ->
                        if (
                            !publicationBlocked &&
                            state.recoveryEnvelopeReady &&
                            envelopeAvailable()
                        ) {
                            refreshPortablePackage()
                        }
                    }
                }
            }
        }
    }

    private suspend fun inspectRestoredInput(): IntakeDecision {
        val result = try {
            restoredPackageIntake()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            RestoredPackageIntakeResult.RetryableFailure
        }
        val detected = when (result) {
            is RestoredPackageIntakeResult.Preserved ->
                AndroidBackupStatus.RestoredPackageDetected(result.condition)
            RestoredPackageIntakeResult.PreservationBlocked ->
                AndroidBackupStatus.RestoredPackageDetected(
                    RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT,
                )
            else -> null
        }
        return IntakeDecision(
            detectedStatus = detected,
            blocksPublication = when (result) {
                RestoredPackageIntakeResult.NoPackage,
                RestoredPackageIntakeResult.CurrentSelfProduced,
                RestoredPackageIntakeResult.ReconciledSelfProduced,
                -> false
                RestoredPackageIntakeResult.RetryableFailure,
                RestoredPackageIntakeResult.PreservationBlocked,
                is RestoredPackageIntakeResult.Preserved,
                -> true
            },
        )
    }

    private suspend fun tryRecordStatus(
        status: AndroidBackupStatus.RestoredPackageDetected,
    ): Boolean = try {
        recordStatus(status)
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        // Current-process publication remains blocked while a later state can retry.
        false
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
): Boolean {
    repeat(STATUS_UPDATE_ATTEMPTS) {
        val state = stateStore.get(vaultId) ?: return false
        if (
            state.packageState == PACKAGE_RESTORED_DETECTED &&
            state.failureCategory == status.condition.name
        ) {
            return true
        }
        val detected = stateStore.mutate(
            vaultId,
            BackupStateMutation { latest ->
                latest.copy(
                    packageState = PACKAGE_RESTORED_DETECTED,
                    failureCategory = status.condition.name,
                )
            },
        )
        if (
            detected?.packageState == PACKAGE_RESTORED_DETECTED &&
            detected.failureCategory == status.condition.name
        ) {
            return true
        }
    }
    return false
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
        "READY" -> state.packageInfoOrNull()?.let { packageInfo ->
            if (packageInfo.packageGeneration == packageInfo.currentGeneration) {
                AndroidBackupStatus.Ready(packageInfo)
            } else {
                AndroidBackupStatus.UpdatePending(packageInfo)
            }
        } ?: AndroidBackupStatus.Unavailable(BackupUnavailableReason.FILE_IO)
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

private data class IntakeDecision(
    val detectedStatus: AndroidBackupStatus.RestoredPackageDetected?,
    val blocksPublication: Boolean,
)

private sealed interface RuntimeEvent {
    data class StateChanged(val state: BackupStateEntity) : RuntimeEvent
    data object RetryRequested : RuntimeEvent
}
