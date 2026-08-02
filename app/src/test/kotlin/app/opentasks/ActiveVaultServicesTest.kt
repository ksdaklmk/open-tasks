package app.opentasks

import android.content.Intent
import app.opentasks.backup.AndroidBackupRuntime
import app.opentasks.backup.EncryptedBackupActionResult
import app.opentasks.backup.PortableBackupPublisher
import app.opentasks.backup.RemoteBackupRuntime
import app.opentasks.di.AppModule
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.data.backup.AttachmentRuntime
import app.opentasks.core.domain.AndroidBackupStatusSource
import app.opentasks.core.domain.RecoveryPassphraseChanger
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupLifecycleCoordinator
import app.opentasks.core.domain.RemoteBackupRunResult
import app.opentasks.core.domain.RemoteBackupRunner
import app.opentasks.core.model.RemoteBackupStatus
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.VaultId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveVaultServicesTest {
    @Test
    fun productionStatusObservationIncludesTheRunnerInFlightState() = runBlocking {
        val configuration = MutableStateFlow<RemoteBackupConfiguration?>(activeConfiguration())
        val generation = MutableStateFlow(BackupGeneration(7))
        val running = MutableStateFlow(false)
        val observed = async {
            AppModule.observeRemoteBackupStatus(configuration, generation, running)
                .first { it is RemoteBackupStatus.BackingUp }
        }

        yield()
        running.value = true

        assertEquals(
            RemoteBackupStatus.BackingUp(BackupGeneration(7)),
            kotlinx.coroutines.withTimeout(TimeUnit.SECONDS.toMillis(5)) { observed.await() },
        )
    }

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

        override val attachmentRuntime: AttachmentRuntime
            get() = error("The fake session exposes no attachment runtime")

        override val statusSource: AndroidBackupStatusSource
            get() = error("The fake session exposes no status source")

        override val portableBackupPublisher: PortableBackupPublisher
            get() = error("The fake session exposes no publisher")

        override val recoveryPassphraseChanger: RecoveryPassphraseChanger
            get() = error("The fake session exposes no passphrase changer")

        override val remoteBackupLifecycleCoordinator: RemoteBackupLifecycleCoordinator
            get() = error("The fake session exposes no lifecycle coordinator")

        override val remoteBackupStatus = MutableStateFlow<RemoteBackupStatus>(
            RemoteBackupStatus.Disabled,
        )

        override suspend fun recoveryAccountBindingDigest(): ByteArray? = null

        override suspend fun connectRemoteBackup(
            allowSeparateLineage: Boolean,
            resolution: Intent?,
        ): EncryptedBackupActionResult = EncryptedBackupActionResult.Completed

        override suspend fun reauthoriseRemoteBackup(
            resolution: Intent?,
        ): EncryptedBackupActionResult = EncryptedBackupActionResult.Completed

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

    private fun activeConfiguration() = RemoteBackupConfiguration(
        lineageId = CloudLineageId.parse("22222222-2222-4222-8222-222222222222"),
        vaultId = VaultId("11111111-1111-4111-8111-111111111111"),
        rootClaimProviderId = ProviderObjectId.of("root-claim"),
        accountBindingDigest = ByteArray(32),
        lifecycle = RemoteBackupLifecycle.ACTIVE,
        activeDeviceId = null,
        writerEpoch = null,
        ownershipClaim = null,
        nextSuccessorProviderId = null,
        currentPublication = null,
        previousPublication = null,
        lastVerifiedGeneration = null,
        lastVerifiedAt = null,
        recoveryCredentialGeneration = 0,
        failureCategory = null,
        stateVersion = RemoteBackupStateVersion(1),
    )

    private class ExpectedStartFailure : RuntimeException()
}
