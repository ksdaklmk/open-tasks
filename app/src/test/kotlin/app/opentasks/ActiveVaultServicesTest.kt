package app.opentasks

import app.opentasks.backup.AndroidBackupRuntime
import app.opentasks.backup.PortableBackupPublisher
import app.opentasks.backup.RemoteBackupRuntime
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.domain.AndroidBackupStatusSource
import app.opentasks.core.domain.RecoveryPassphraseChanger
import app.opentasks.core.domain.RemoteBackupLifecycleCoordinator
import app.opentasks.core.domain.RemoteBackupRunResult
import app.opentasks.core.domain.RemoteBackupRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveVaultServicesTest {
    @Test
    fun activeRuntimeStartsTheSessionExactlyOnce() {
        val factory = CountingSessionFactory()
        val services = ActiveVaultServices(factory::open)

        services.applyActive(true)
        services.applyActive(true)

        assertTrue(services.isRunning)
        assertEquals(1, factory.opened)
        assertEquals(0, factory.closed)
    }

    @Test
    fun everyActivationStartsItsOwnBackupRuntimeExactlyOnce() {
        val factory = CountingSessionFactory()
        val services = ActiveVaultServices(factory::open)

        services.applyActive(true)
        services.applyActive(true)

        val first = services.requireSession().backupRuntime as CountingBackupRuntime
        assertEquals(1, first.starts)

        services.quiesce()
        services.applyActive(true)

        val second = services.requireSession().backupRuntime as CountingBackupRuntime
        assertEquals(1, second.starts)
        assertFalse(first === second)
    }

    @Test
    fun remoteBackupSchedulingStartsWithTheSlotAndStopsBeforeItIsReplaced() {
        val factory = CountingSessionFactory()
        val services = ActiveVaultServices(factory::open)

        services.applyActive(true)
        val session = services.requireSession() as FakeSession
        assertEquals(1, session.remoteBackupRuntime.starts)
        assertEquals(0, session.remoteBackupRuntime.stops)

        services.quiesce()

        assertEquals(1, session.remoteBackupRuntime.stops)
    }

    @Test
    fun aBackupRuntimeThatCannotStartLeavesNoRunningSession() {
        val factory = CountingSessionFactory(startFailure = ExpectedStartFailure())

        val services = ActiveVaultServices(factory::open)

        assertThrows(ExpectedStartFailure::class.java) { services.applyActive(true) }
        assertFalse(services.isRunning)
        assertEquals(1, factory.closed)
    }

    @Test
    fun nonActiveRuntimeStatesNeverStartServices() {
        val factory = CountingSessionFactory()
        val services = ActiveVaultServices(factory::open)

        listOf(
            VaultRuntimeState.Initializing,
            VaultRuntimeState.NoVault,
            VaultRuntimeState.Unreadable(VaultSlot.LEGACY),
            VaultRuntimeState.Recovering("operation-1"),
        ).forEach(services::onVaultRuntimeState)

        assertFalse(services.isRunning)
        assertEquals(0, factory.opened)
        assertEquals(0, factory.startedRuntimes)
    }

    @Test
    fun servicesCloseBeforeTheSlotIsReplaced() {
        val factory = CountingSessionFactory()
        val services = ActiveVaultServices(factory::open)
        services.applyActive(true)

        services.quiesce()

        assertFalse(services.isRunning)
        assertEquals(1, factory.closed)
    }

    @Test
    fun quiescingAnIdleServiceSetIsASafeNoOperation() {
        val factory = CountingSessionFactory()
        val services = ActiveVaultServices(factory::open)

        services.quiesce()
        services.quiesce()

        assertEquals(0, factory.opened)
        assertEquals(0, factory.closed)
    }

    @Test
    fun aReplacedSlotStartsAFreshSessionInsteadOfReusingTheClosedOne() {
        val factory = CountingSessionFactory()
        val services = ActiveVaultServices(factory::open)

        services.applyActive(true)
        val first = services.requireSession()
        services.quiesce()
        services.applyActive(true)

        assertEquals(2, factory.opened)
        assertEquals(1, factory.closed)
        assertFalse(first === services.requireSession())
    }

    @Test
    fun requireSessionFailsWhileNoActiveRuntimeExists() {
        val factory = CountingSessionFactory()
        val services = ActiveVaultServices(factory::open)

        val failure = assertThrows(IllegalStateException::class.java) {
            services.requireSession()
        }

        assertEquals("The active vault services are not running", failure.message)
        assertEquals(0, factory.opened)
    }

    @Test
    fun theRunningSessionIsTheOneHandedToDependants() {
        val factory = CountingSessionFactory()
        val services = ActiveVaultServices(factory::open)

        services.applyActive(true)

        assertSame(factory.last, services.requireSession())
    }

    private class CountingSessionFactory(
        private val startFailure: RuntimeException? = null,
    ) {
        var opened: Int = 0
            private set
        var closed: Int = 0
            private set
        var startedRuntimes: Int = 0
            private set
        var last: ActiveVaultSession? = null
            private set

        fun open(): ActiveVaultSession {
            opened += 1
            val runtime = CountingBackupRuntime(startFailure) { startedRuntimes += 1 }
            return FakeSession(runtime) { closed += 1 }.also { last = it }
        }
    }

    private class CountingBackupRuntime(
        private val startFailure: RuntimeException?,
        private val onStart: () -> Unit,
    ) : AndroidBackupRuntime {
        var starts: Int = 0
            private set

        override fun start() {
            startFailure?.let { throw it }
            starts += 1
            onStart()
        }

        override fun retry() = Unit
    }

    private class FakeSession(
        override val backupRuntime: AndroidBackupRuntime,
        private val onClose: () -> Unit,
    ) : ActiveVaultSession {
        override val remoteBackupRuntime = CountingRemoteBackupRuntime()

        override val remoteBackupRunner = object : RemoteBackupRunner {
            override suspend fun run(): RemoteBackupRunResult = RemoteBackupRunResult.NoChanges
        }

        override val statusSource: AndroidBackupStatusSource
            get() = error("The fake session exposes no status source")

        override val portableBackupPublisher: PortableBackupPublisher
            get() = error("The fake session exposes no publisher")

        override val recoveryPassphraseChanger: RecoveryPassphraseChanger
            get() = error("The fake session exposes no passphrase changer")

        override val remoteBackupLifecycleCoordinator: RemoteBackupLifecycleCoordinator
            get() = error("The fake session exposes no lifecycle coordinator")

        override fun close() {
            remoteBackupRuntime.stop()
            onClose()
        }
    }

    private class CountingRemoteBackupRuntime : RemoteBackupRuntime {
        var starts: Int = 0
            private set

        var stops: Int = 0
            private set

        override fun start() {
            starts += 1
        }

        override fun requestNow() = Unit

        override fun stop() {
            stops += 1
        }
    }

    private class ExpectedStartFailure : RuntimeException()
}
