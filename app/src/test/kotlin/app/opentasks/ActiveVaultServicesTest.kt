package app.opentasks

import app.opentasks.backup.AndroidBackupRuntime
import app.opentasks.backup.PortableBackupPublisher
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.domain.AndroidBackupStatusSource
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

    private class CountingSessionFactory {
        var opened: Int = 0
            private set
        var closed: Int = 0
            private set
        var last: ActiveVaultSession? = null
            private set

        fun open(): ActiveVaultSession {
            opened += 1
            return FakeSession { closed += 1 }.also { last = it }
        }
    }

    private class FakeSession(private val onClose: () -> Unit) : ActiveVaultSession {
        override val backupRuntime: AndroidBackupRuntime
            get() = error("The fake session exposes no backup runtime")

        override val statusSource: AndroidBackupStatusSource
            get() = error("The fake session exposes no status source")

        override val portableBackupPublisher: PortableBackupPublisher
            get() = error("The fake session exposes no publisher")

        override fun close() {
            onClose()
        }
    }
}
