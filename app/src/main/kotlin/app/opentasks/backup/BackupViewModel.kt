package app.opentasks.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentasks.core.domain.AndroidBackupStatusSource
import app.opentasks.core.domain.RecoveryPassphrasePolicy
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.RecoveryPassphraseValidation
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class BackupViewModel internal constructor(
    statusSource: AndroidBackupStatusSource,
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

    val status: StateFlow<AndroidBackupStatus> = statusSource.status
    private val preparing = AtomicBoolean()

    fun prepare(passphrase: String) {
        if (status.value is AndroidBackupStatus.Preparing) return
        if (RecoveryPassphrasePolicy.validate(passphrase, passphrase) !is
            RecoveryPassphraseValidation.Valid
        ) {
            return
        }
        if (!preparing.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (RecoveryPassphrasePolicy.validate(passphrase, passphrase) !is
                    RecoveryPassphraseValidation.Valid
                ) {
                    return@launch
                }
                val mutablePassphrase = passphrase.toCharArray()
                try {
                    preparePackage(mutablePassphrase)
                } finally {
                    mutablePassphrase.fill('\u0000')
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                // The publisher persists only a bounded AndroidBackupStatus reason.
            } finally {
                preparing.set(false)
            }
        }
    }

    fun retry() {
        retryPackage()
    }
}
