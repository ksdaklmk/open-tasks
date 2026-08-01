package app.opentasks.backup

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentasks.ActiveVaultServices
import app.opentasks.core.domain.LifecycleResult
import app.opentasks.core.domain.PassphraseChangeResult
import app.opentasks.core.domain.RemoteBackupConnectResult
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class EncryptedBackupPresentation(
    val status: RemoteBackupStatus,
    val canBackUpNow: Boolean,
    val canRestore: Boolean,
    val canReauthorise: Boolean,
    val canTakeOver: Boolean,
    val canPreserveAsNewLineage: Boolean,
    val canChangePassphrase: Boolean,
    val canDisconnect: Boolean,
    val canDeleteHistory: Boolean,
    val passphraseChangeDisclosureVisible: Boolean,
)

sealed interface EncryptedBackupActionResult {
    data object Completed : EncryptedBackupActionResult
    data class ResolutionRequired(val pendingIntent: PendingIntent) : EncryptedBackupActionResult
    data class ConnectResult(val result: RemoteBackupConnectResult) : EncryptedBackupActionResult
    data class Failed(val reason: RemoteBackupFailureCategory) : EncryptedBackupActionResult
}

@HiltViewModel
class EncryptedBackupViewModel internal constructor(
    private val status: StateFlow<RemoteBackupStatus>,
    private val savedStateHandle: SavedStateHandle,
    private val connectBackup: suspend (Boolean, Intent?) -> EncryptedBackupActionResult,
    private val reauthoriseBackup: suspend (Intent?) -> EncryptedBackupActionResult,
    private val requestBackupNow: () -> Unit,
    private val requestRestore: () -> Unit,
    private val preserveDivergentWork: suspend () -> RemoteBackupConnectResult,
    private val changeRecoveryPassphrase: suspend (CharArray, CharArray) -> PassphraseChangeResult,
    private val disconnectBackup: suspend () -> LifecycleResult,
    private val deleteBackupHistory: suspend (CharArray) -> LifecycleResult,
) : ViewModel() {
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        services: ActiveVaultServices,
    ) : this(
        status = services.requireSession().remoteBackupStatus,
        savedStateHandle = savedStateHandle,
        connectBackup = services.requireSession()::connectRemoteBackup,
        reauthoriseBackup = services.requireSession()::reauthoriseRemoteBackup,
        requestBackupNow = services.requireSession().remoteBackupRuntime::requestNow,
        requestRestore = {},
        preserveDivergentWork =
            services.requireSession().remoteBackupLifecycleCoordinator::preserveDivergentWorkAsNewLineage,
        changeRecoveryPassphrase = services.requireSession().recoveryPassphraseChanger::change,
        disconnectBackup = services.requireSession().remoteBackupLifecycleCoordinator::disconnect,
        deleteBackupHistory =
            services.requireSession().remoteBackupLifecycleCoordinator::deleteHistory,
    )

    private val operation = Mutex()
    private val presented = MutableStateFlow(presentation(status.value))
    private var pendingAction: PendingAction? = null

    val presentation: StateFlow<EncryptedBackupPresentation> = presented.asStateFlow()
    val resolutionEffects = Channel<PendingIntent>(Channel.BUFFERED)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            status.drop(1).collect { presented.value = presentation(it) }
        }
    }

    fun connect() = launchOperation {
        acceptAction(connectBackup(false, null), PendingAction.CONNECT)
    }

    fun acceptResolution(data: Intent) {
        viewModelScope.launch(Dispatchers.Default) {
            operation.lock()
            try {
                when (pendingAction.also { pendingAction = null }) {
                    PendingAction.CONNECT ->
                        acceptAction(connectBackup(false, data), PendingAction.CONNECT)
                    PendingAction.REAUTHORISE ->
                        acceptAction(reauthoriseBackup(data), PendingAction.REAUTHORISE)
                    null -> Unit
                }
            } finally {
                operation.unlock()
            }
        }
    }

    fun reauthorise() = launchOperation {
        val result = reauthoriseBackup(null)
        acceptAction(result, PendingAction.REAUTHORISE)
        if (result == EncryptedBackupActionResult.Completed) requestBackupNow()
    }

    fun backUpNow() {
        if (!operation.tryLock()) return
        try {
            requestBackupNow()
        } finally {
            operation.unlock()
        }
    }

    fun restoreOrTakeOver() {
        if (!operation.tryLock()) return
        try {
            requestRestore()
        } finally {
            operation.unlock()
        }
    }

    fun preserveAsNewLineage() = launchOperation {
        if (presented.value.canRestore) {
            acceptAction(connectBackup(true, null), PendingAction.CONNECT)
        } else {
            acceptConnectResult(preserveDivergentWork())
        }
    }

    fun disconnect() = launchOperation {
        when (disconnectBackup()) {
            is LifecycleResult.Disconnected ->
                presented.value = presentation(RemoteBackupStatus.Disabled)
            else -> Unit
        }
    }

    fun deleteHistory(passphrase: String) = launchOperation {
        withPassphrase(passphrase) { mutable ->
            presented.value = presentation(RemoteBackupStatus.Deleting)
            when (deleteBackupHistory(mutable)) {
                LifecycleResult.HistoryDeleted ->
                    presented.value = presentation(RemoteBackupStatus.Terminated)
                else -> Unit
            }
        }
    }

    fun changePassphrase(current: String, new: String) = launchOperation {
        val currentChars = current.toCharArray()
        val newChars = new.toCharArray()
        try {
            val result = changeRecoveryPassphrase(currentChars, newChars)
            if (result is PassphraseChangeResult.Changed) {
                presented.value = presentation(
                    status.value,
                    passphraseChangeDisclosureVisible = result.olderCopiesRemainUsable,
                )
            }
        } finally {
            currentChars.fill('\u0000')
            newChars.fill('\u0000')
        }
    }

    internal fun savedStateForTest(): String =
        savedStateHandle.keys().joinToString { key -> "$key=${savedStateHandle.get<Any?>(key)}" }

    private suspend fun acceptAction(
        result: EncryptedBackupActionResult,
        action: PendingAction,
    ) {
        when (result) {
            EncryptedBackupActionResult.Completed -> Unit
            is EncryptedBackupActionResult.ResolutionRequired -> {
                pendingAction = action
                resolutionEffects.send(result.pendingIntent)
            }
            is EncryptedBackupActionResult.ConnectResult -> acceptConnectResult(result.result)
            is EncryptedBackupActionResult.Failed -> presented.value = presentation(
                RemoteBackupStatus.ActionRequired(result.reason),
            )
        }
    }

    private fun acceptConnectResult(result: RemoteBackupConnectResult) {
        when (result) {
            is RemoteBackupConnectResult.ExistingBackupsFound -> presented.value = presentation(
                RemoteBackupStatus.Disabled,
                canRestore = true,
                canPreserveAsNewLineage = true,
            )
            is RemoteBackupConnectResult.Connected ->
                presented.value = presentation(RemoteBackupStatus.Preparing)
            is RemoteBackupConnectResult.Failed ->
                presented.value = presentation(RemoteBackupStatus.ActionRequired(result.reason))
        }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            operation.lock()
            try {
                block()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
            } finally {
                operation.unlock()
            }
        }
    }

    private suspend fun <T> withPassphrase(value: String, block: suspend (CharArray) -> T): T {
        val mutable = value.toCharArray()
        return try {
            block(mutable)
        } finally {
            mutable.fill('\u0000')
        }
    }

    private enum class PendingAction { CONNECT, REAUTHORISE }
}

private fun presentation(
    status: RemoteBackupStatus,
    canRestore: Boolean = false,
    canPreserveAsNewLineage: Boolean = status is RemoteBackupStatus.OwnershipLost,
    passphraseChangeDisclosureVisible: Boolean = false,
): EncryptedBackupPresentation {
    val enabled = status !is RemoteBackupStatus.Disabled && status !is RemoteBackupStatus.Terminated
    return EncryptedBackupPresentation(
        status = status,
        canBackUpNow = status is RemoteBackupStatus.RetryScheduled,
        canRestore = canRestore,
        canReauthorise = status is RemoteBackupStatus.ActionRequired &&
            status.reason in setOf(
                RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
                RemoteBackupFailureCategory.ACCOUNT_MISMATCH,
            ),
        canTakeOver = status is RemoteBackupStatus.OwnershipLost,
        canPreserveAsNewLineage = canPreserveAsNewLineage,
        canChangePassphrase = enabled,
        canDisconnect = enabled,
        canDeleteHistory = enabled,
        passphraseChangeDisclosureVisible = passphraseChangeDisclosureVisible,
    )
}
