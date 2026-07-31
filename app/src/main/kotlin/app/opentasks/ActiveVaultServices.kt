package app.opentasks

import app.opentasks.backup.AndroidBackupRuntime
import app.opentasks.backup.PortableBackupPublisher
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.domain.AndroidBackupStatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * The app services that only exist while one vault runtime is active.
 *
 * A session is bound to exactly one slot: it is opened when that runtime
 * becomes active and closed before the slot can be replaced, so no coroutine,
 * observer, or coordinator outlives the database it reads.
 */
interface ActiveVaultSession : AutoCloseable {
    val backupRuntime: AndroidBackupRuntime

    val statusSource: AndroidBackupStatusSource

    val portableBackupPublisher: PortableBackupPublisher
}

class DefaultActiveVaultSession(
    private val scope: CoroutineScope,
    override val backupRuntime: AndroidBackupRuntime,
    override val statusSource: AndroidBackupStatusSource,
    override val portableBackupPublisher: PortableBackupPublisher,
) : ActiveVaultSession {
    override fun close() {
        scope.cancel()
    }
}

/**
 * Starts vault-bound app services for an active runtime and only for one.
 *
 * Every other runtime state leaves the services closed, so a device without a
 * vault, or with an unreadable one, constructs no repository, no backup
 * coordinator, and no content-key store.
 */
class ActiveVaultServices(
    private val openSession: () -> ActiveVaultSession,
) {
    private val lock = Any()
    private var session: ActiveVaultSession? = null

    val isRunning: Boolean
        get() = synchronized(lock) { session != null }

    fun onVaultRuntimeState(state: VaultRuntimeState) {
        applyActive(state is VaultRuntimeState.Active)
    }

    fun applyActive(active: Boolean) {
        if (active) start() else quiesce()
    }

    /** Closes the running session before its slot is replaced. */
    fun quiesce() {
        val closing = synchronized(lock) { session.also { session = null } }
        closing?.close()
    }

    fun requireSession(): ActiveVaultSession = synchronized(lock) {
        checkNotNull(session) { "The active vault services are not running" }
    }

    /**
     * Opens a session and starts its backup runtime.
     *
     * The runtime is bound to the session's scope, so closing the session is
     * what stops it again; a session that cannot start is closed rather than
     * retained half-alive.
     */
    private fun start() {
        synchronized(lock) {
            if (session != null) return
            val opened = openSession()
            session = opened
            try {
                opened.backupRuntime.start()
            } catch (failure: Throwable) {
                session = null
                try {
                    opened.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }
    }
}
