package app.opentasks.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentasks.core.domain.AndroidBackupStatusSource
import app.opentasks.core.domain.RecoveryPassphrasePolicy
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.RecoveryPassphraseValidation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class BackupPresentation(
    val status: AndroidBackupStatus,
    val canReprepareInitialPackage: Boolean = false,
)

@HiltViewModel
class BackupViewModel internal constructor(
    private val statusSource: AndroidBackupStatusSource,
    private val preparePackage: suspend (CharArray) -> AndroidBackupStatus,
    private val retryPackage: () -> Unit,
) : ViewModel() {
    @Inject
    constructor(
        statusSource: AndroidBackupStatusSource,
        publisher: PortableBackupPublisher,
        runtime: AndroidBackupRuntime,
    ) : this(
        statusSource = statusSource,
        preparePackage = publisher::prepare,
        retryPackage = runtime::retry,
    )

    private val operationLock = Any()
    private val presented = MutableStateFlow(
        BackupPresentation(statusSource.status.value),
    )
    private var operation: PreparationOperation? = null
    val presentation: StateFlow<BackupPresentation> = presented.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            statusSource.status.collect(::acceptSourceStatus)
        }
    }

    fun prepare(passphrase: String) {
        if (RecoveryPassphrasePolicy.validate(passphrase, passphrase) !is
            RecoveryPassphraseValidation.Valid
        ) {
            return
        }
        val started = synchronized(operationLock) {
            val activeOperation = operation
            if (
                (activeOperation != null && activeOperation.result == null) ||
                presented.value.status is AndroidBackupStatus.Preparing
            ) {
                false
            } else {
                operation = PreparationOperation(
                    sourceAtStart = statusSource.status.value,
                )
                presented.value = BackupPresentation(AndroidBackupStatus.Preparing)
                true
            }
        }
        if (!started) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (RecoveryPassphrasePolicy.validate(passphrase, passphrase) !is
                    RecoveryPassphraseValidation.Valid
                ) {
                    clearOperation()
                    return@launch
                }
                val mutablePassphrase = passphrase.toCharArray()
                val result = try {
                    preparePackage(mutablePassphrase)
                } finally {
                    mutablePassphrase.fill('\u0000')
                }
                publishResult(result)
            } catch (failure: Throwable) {
                clearOperation()
                if (failure is CancellationException) {
                    throw failure
                }
                // The publisher persists only a bounded AndroidBackupStatus reason.
            }
        }
    }

    fun retry() {
        retryPackage()
    }

    private fun acceptSourceStatus(sourceStatus: AndroidBackupStatus) {
        synchronized(operationLock) {
            val activeOperation = operation
            if (activeOperation == null) {
                presented.value = BackupPresentation(sourceStatus)
            } else if (
                activeOperation.result != null &&
                (
                    sourceStatus == activeOperation.result ||
                        sourceStatus != activeOperation.sourceAtStart
                    )
            ) {
                operation = null
                presented.value = BackupPresentation(sourceStatus)
            }
        }
    }

    private fun publishResult(result: AndroidBackupStatus) {
        synchronized(operationLock) {
            val activeOperation = operation ?: return
            val sourceStatus = statusSource.status.value
            if (sourceStatus == result || sourceStatus != activeOperation.sourceAtStart) {
                operation = null
                presented.value = BackupPresentation(sourceStatus)
            } else {
                operation = activeOperation.copy(result = result)
                presented.value = BackupPresentation(
                    status = result,
                    canReprepareInitialPackage =
                        activeOperation.sourceAtStart is AndroidBackupStatus.NotPrepared &&
                            sourceStatus is AndroidBackupStatus.NotPrepared &&
                            result is AndroidBackupStatus.Unavailable,
                )
            }
        }
    }

    private fun clearOperation() {
        synchronized(operationLock) {
            operation = null
            presented.value = BackupPresentation(statusSource.status.value)
        }
    }

    private data class PreparationOperation(
        val sourceAtStart: AndroidBackupStatus,
        val result: AndroidBackupStatus? = null,
    )
}
