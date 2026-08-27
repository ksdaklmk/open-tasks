package app.opentasks.backup

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentasks.core.data.VaultRuntimeManager
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.domain.RecoveryCandidate
import app.opentasks.core.domain.RecoveryResult
import app.opentasks.core.domain.RecoverySource
import app.opentasks.core.model.RecoveryFailureCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class RecoveryCandidateSummary(
    val handle: String,
    val source: RecoverySource,
)

sealed interface RecoveryPresentation {
    data object NoVault : RecoveryPresentation
    data object UnreadableVault : RecoveryPresentation
    data object Discovering : RecoveryPresentation
    data class NoCandidates(val source: RecoverySource) : RecoveryPresentation
    data class Candidates(val values: List<RecoveryCandidateSummary>) : RecoveryPresentation
    data object Authenticating : RecoveryPresentation
    data class TakeoverConfirmation(
        val operationId: String,
        val generation: Long,
    ) : RecoveryPresentation
    data object Activating : RecoveryPresentation
    data class Failed(val reason: RecoveryFailureCategory) : RecoveryPresentation
}

internal sealed interface RecoveryDiscoveryResult {
    data class Candidates(val values: List<RecoveryCandidate>) : RecoveryDiscoveryResult
    data class ResolutionRequired(val pendingIntent: PendingIntent) : RecoveryDiscoveryResult
    data class Failed(val reason: RecoveryFailureCategory) : RecoveryDiscoveryResult
}

@HiltViewModel
class RecoveryViewModel internal constructor(
    initialPresentation: RecoveryPresentation,
    private val savedStateHandle: SavedStateHandle,
    private val discoverDriveCandidates: suspend (Intent?) -> RecoveryDiscoveryResult,
    private val discoverPortableCandidates: suspend () -> List<RecoveryCandidate>,
    private val prepareRecovery: suspend (String, CharArray) -> RecoveryResult,
    private val confirmRecoveryTakeover: suspend (String) -> RecoveryResult,
    private val createNewVault: suspend () -> Unit,
    private val retryUnreadable: () -> Unit,
    private val closeOperations: () -> Unit = {},
) : ViewModel() {
    @Inject
    internal constructor(
        savedStateHandle: SavedStateHandle,
        runtimeManager: VaultRuntimeManager,
        operations: RecoveryUiOperations,
    ) : this(
        initialPresentation = when (runtimeManager.state.value) {
            is VaultRuntimeState.Unreadable -> RecoveryPresentation.UnreadableVault
            else -> RecoveryPresentation.NoVault
        },
        savedStateHandle = savedStateHandle,
        discoverDriveCandidates = operations::discoverDrive,
        discoverPortableCandidates = operations::discoverPortable,
        prepareRecovery = operations::prepare,
        confirmRecoveryTakeover = operations::confirm,
        createNewVault = runtimeManager::createNewVault,
        retryUnreadable = {},
        closeOperations = operations::close,
    )

    private val operation = Mutex()
    private val presented = MutableStateFlow(initialPresentation)

    val presentation: StateFlow<RecoveryPresentation> = presented.asStateFlow()
    val resolutionEffects = Channel<PendingIntent>(Channel.BUFFERED)

    fun discoverDrive() = launchOperation {
        presented.value = RecoveryPresentation.Discovering
        acceptDiscovery(RecoverySource.GOOGLE_DRIVE, discoverDriveCandidates(null))
    }

    fun acceptResolution(data: Intent) {
        viewModelScope.launch(Dispatchers.Default) {
            operation.lock()
            try {
                presented.value = RecoveryPresentation.Discovering
                acceptDiscovery(RecoverySource.GOOGLE_DRIVE, discoverDriveCandidates(data))
            } finally {
                operation.unlock()
            }
        }
    }

    fun discoverPortable() = launchOperation {
        presented.value = RecoveryPresentation.Discovering
        showCandidates(RecoverySource.ANDROID_BACKUP_PACKAGE, discoverPortableCandidates())
    }

    fun restore(handle: String, passphrase: String) = launchOperation {
        presented.value = RecoveryPresentation.Authenticating
        val mutable = passphrase.toCharArray()
        try {
            acceptRecoveryResult(prepareRecovery(handle, mutable))
        } finally {
            mutable.fill('\u0000')
        }
    }

    fun confirmTakeover() {
        val confirmation = presented.value as? RecoveryPresentation.TakeoverConfirmation ?: return
        launchOperation {
            presented.value = RecoveryPresentation.Activating
            acceptRecoveryResult(confirmRecoveryTakeover(confirmation.operationId))
        }
    }

    fun startWithoutRestoring() = launchOperation(waitForTurn = false) {
        createNewVault()
        presented.value = RecoveryPresentation.Activating
    }

    fun returnToSources() {
        presented.value = RecoveryPresentation.NoVault
    }

    fun retryUnreadableVault() {
        retryUnreadable()
    }

    internal fun savedStateForTest(): String =
        savedStateHandle.keys().joinToString { key -> "$key=${savedStateHandle.get<Any?>(key)}" }

    override fun onCleared() {
        closeOperations()
    }

    private suspend fun acceptDiscovery(
        source: RecoverySource,
        result: RecoveryDiscoveryResult,
    ) {
        when (result) {
            is RecoveryDiscoveryResult.Candidates -> showCandidates(source, result.values)
            is RecoveryDiscoveryResult.ResolutionRequired -> resolutionEffects.send(result.pendingIntent)
            is RecoveryDiscoveryResult.Failed ->
                presented.value = RecoveryPresentation.Failed(result.reason)
        }
    }

    private fun showCandidates(source: RecoverySource, values: List<RecoveryCandidate>) {
        presented.value = if (values.isEmpty()) {
            RecoveryPresentation.NoCandidates(source)
        } else {
            RecoveryPresentation.Candidates(
                values.map { RecoveryCandidateSummary(it.handle, it.source) },
            )
        }
    }

    private fun acceptRecoveryResult(result: RecoveryResult) {
        when (result) {
            is RecoveryResult.TakeoverConfirmationRequired ->
                presented.value = RecoveryPresentation.TakeoverConfirmation(
                    result.operationId,
                    result.generation.value,
                )
            is RecoveryResult.Activated -> presented.value = RecoveryPresentation.Activating
            is RecoveryResult.Failed -> presented.value = RecoveryPresentation.Failed(result.reason)
        }
    }

    private fun launchOperation(
        waitForTurn: Boolean = true,
        block: suspend () -> Unit,
    ) {
        if (!waitForTurn && !operation.tryLock()) return
        viewModelScope.launch(Dispatchers.Default) {
            if (waitForTurn) {
                operation.lock()
            }
            try {
                block()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                presented.value = RecoveryPresentation.Failed(
                    RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            } finally {
                operation.unlock()
            }
        }
    }
}
