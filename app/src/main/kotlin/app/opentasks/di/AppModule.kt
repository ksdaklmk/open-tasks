package app.opentasks.di

import android.content.Context
import app.opentasks.ActiveVaultServices
import app.opentasks.ActiveVaultSession
import app.opentasks.DefaultActiveVaultSession
import app.opentasks.backup.AndroidAtomicPackageFile
import app.opentasks.backup.AndroidBackupFiles
import app.opentasks.backup.AndroidBackupRuntime
import app.opentasks.backup.DefaultAndroidBackupRuntime
import app.opentasks.backup.PersistedAndroidBackupStatusSource
import app.opentasks.backup.PortableBackupPublisher
import app.opentasks.backup.RecoveryEnvelopePreparer
import app.opentasks.backup.RestoredPackageIntake
import app.opentasks.backup.drive.DefaultGoogleDriveAuthorizationManager
import app.opentasks.backup.drive.GoogleDriveAuthorizationManager
import app.opentasks.backup.recordRestoredPackageStatus
import app.opentasks.backup.requiresEstablishedContentKey
import app.opentasks.backup.restoredPackagePublicationBlocked
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.DefaultVaultRuntimeManager
import app.opentasks.core.data.LocalVaultRuntime
import app.opentasks.core.data.LocalVaultRepositoryFactory
import app.opentasks.core.data.VaultRuntimeManager
import app.opentasks.core.data.backup.DefaultLocalBackupObjectStore
import app.opentasks.core.data.backup.PortableBackupCodec
import app.opentasks.core.data.backup.PortablePackageCodec
import app.opentasks.core.domain.BackupCoordinator
import app.opentasks.core.domain.AndroidBackupStatusSource
import app.opentasks.core.domain.DefaultInsightsEngine
import app.opentasks.core.domain.InsightsEngine
import app.opentasks.core.domain.VaultRepository
import app.opentasks.InsightsTimeProvider
import app.opentasks.SystemInsightsTimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideInsightsTimeProvider(): InsightsTimeProvider = SystemInsightsTimeProvider()

    @Provides
    @Singleton
    fun provideInsightsEngine(): InsightsEngine = DefaultInsightsEngine()

    @Provides
    @Singleton
    fun provideDefaultVaultRuntimeManager(
        @ApplicationContext context: Context,
        crypto: VaultCrypto,
    ): DefaultVaultRuntimeManager = DefaultVaultRuntimeManager(context, crypto)

    @Provides
    fun provideVaultRuntimeManager(
        manager: DefaultVaultRuntimeManager,
    ): VaultRuntimeManager = manager

    // Deliberately unscoped: the manager owns the single live runtime, so every
    // injection resolves the currently active slot instead of a captured one.
    @Provides
    fun provideLocalVaultRuntime(
        manager: DefaultVaultRuntimeManager,
    ): LocalVaultRuntime = manager.requireActive()

    @Provides
    fun provideVaultRepository(runtime: LocalVaultRuntime): VaultRepository = runtime.repository

    @Provides
    @Singleton
    fun provideVaultCrypto(): VaultCrypto = TinkVaultCrypto()

    @Provides
    fun provideVaultContentKeyStore(
        runtime: LocalVaultRuntime,
    ): VaultContentKeyStore = runtime.contentKeyStore

    @Provides
    @Singleton
    fun provideAuthenticatedCloudObjectCodec(
        crypto: VaultCrypto,
    ): AuthenticatedCloudObjectCodec = DefaultAuthenticatedCloudObjectCodec(crypto)

    @Provides
    @Singleton
    fun providePortablePackageCodec(
        codec: AuthenticatedCloudObjectCodec,
    ): PortablePackageCodec = PortableBackupCodec(codec)

    // Inert: portable package discovery stays available without a vault.
    @Provides
    @Singleton
    fun provideAndroidBackupFiles(
        @ApplicationContext context: Context,
    ): AndroidBackupFiles = AndroidBackupFiles(context)

    // Device-level Google account authorization, independent of which vault
    // slot is active: a Drive account binding outlives any single vault.
    @Provides
    @Singleton
    fun provideGoogleDriveAuthorizationManager(
        @ApplicationContext context: Context,
    ): GoogleDriveAuthorizationManager = DefaultGoogleDriveAuthorizationManager(context)

    @Provides
    @Singleton
    fun provideActiveVaultServices(
        session: Provider<ActiveVaultSession>,
    ): ActiveVaultServices = ActiveVaultServices(session::get)

    @Provides
    fun provideAndroidBackupRuntime(
        services: ActiveVaultServices,
    ): AndroidBackupRuntime = services.requireSession().backupRuntime

    @Provides
    fun provideAndroidBackupStatusSource(
        services: ActiveVaultServices,
    ): AndroidBackupStatusSource = services.requireSession().statusSource

    @Provides
    fun providePortableBackupPublisher(
        services: ActiveVaultServices,
    ): PortableBackupPublisher = services.requireSession().portableBackupPublisher

    /**
     * Builds every vault-bound service for the active slot in one bundle.
     *
     * The members are constructed here rather than bound in the graph so that
     * closing the session releases them all together, and so that no binding
     * can hand a caller a service from a replaced slot.
     */
    @Provides
    fun provideActiveVaultSession(
        runtime: LocalVaultRuntime,
        files: AndroidBackupFiles,
        codec: AuthenticatedCloudObjectCodec,
        packageCodec: PortablePackageCodec,
        crypto: VaultCrypto,
    ): ActiveVaultSession {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val contentKeyStore = runtime.contentKeyStore
        val coordinator: BackupCoordinator = LocalVaultRepositoryFactory.createBackupCoordinator(
            runtime = runtime,
            objectStore = DefaultLocalBackupObjectStore(files.localBackupRoot),
            authenticatedCodec = codec,
            contentKeyStore = contentKeyStore,
        )
        val envelopePreparer = RecoveryEnvelopePreparer(
            vaultId = runtime.vaultId,
            keyStore = contentKeyStore,
            crypto = crypto,
        )
        val publisher = PortableBackupPublisher(
            vaultId = runtime.vaultId,
            captureSource = runtime.backupCaptureSource,
            stateStore = runtime.backupStateStore,
            envelopeStore = runtime.recoveryEnvelopeStore,
            contentKeyStore = contentKeyStore,
            packageFile = AndroidAtomicPackageFile(files.eligiblePackage),
            codec = packageCodec,
            prepareEnvelope = envelopePreparer::prepare,
            publicationBlocked = {
                files.recoveryInbox.isFile ||
                    restoredPackagePublicationBlocked(
                        stateStore = runtime.backupStateStore,
                        vaultId = runtime.vaultId,
                    )
            },
        )
        val intake = RestoredPackageIntake(
            vaultId = runtime.vaultId,
            eligiblePackage = files.eligiblePackage,
            recoveryInbox = files.recoveryInbox,
            packageFile = AndroidAtomicPackageFile(files.eligiblePackage),
            stateStore = runtime.backupStateStore,
            envelopeStore = runtime.recoveryEnvelopeStore,
            contentKeyStore = contentKeyStore,
            codec = packageCodec,
        )
        return DefaultActiveVaultSession(
            scope = scope,
            backupRuntime = DefaultAndroidBackupRuntime(
                scope = scope,
                restoredPackageIntake = intake::inspect,
                bootstrapContentKey = bootstrap@{
                    val state = try {
                        runtime.backupStateStore.get(runtime.vaultId)
                    } catch (_: Throwable) {
                        return@bootstrap false
                    }
                    val envelope = try {
                        runtime.recoveryEnvelopeStore.get(runtime.vaultId)
                    } catch (_: Throwable) {
                        return@bootstrap false
                    }
                    try {
                        val localBackupObjectPresent = try {
                            files.localBackupRoot.walkTopDown().any { it.isFile }
                        } catch (_: Throwable) {
                            return@bootstrap false
                        }
                        val requiresExisting = requiresEstablishedContentKey(
                            state = state,
                            recoveryEnvelopePresent = envelope != null,
                            eligiblePackagePresent = files.eligiblePackage.isFile,
                            recoveryInboxPresent = files.recoveryInbox.isFile,
                            localBackupObjectPresent = localBackupObjectPresent,
                        )
                        if (requiresExisting) {
                            contentKeyStore.openExisting(runtime.vaultId).close()
                        } else {
                            contentKeyStore.getOrCreate(runtime.vaultId).close()
                        }
                        true
                    } catch (_: Throwable) {
                        false
                    } finally {
                        envelope?.kdf?.salt?.fill(0)
                        envelope?.nonce?.fill(0)
                        envelope?.wrappedKeyset?.fill(0)
                    }
                },
                requestLocalBackup = coordinator::request,
                observeBackupState = {
                    runtime.backupStateStore.observe(runtime.vaultId)
                },
                envelopeAvailable = {
                    runtime.recoveryEnvelopeStore.get(runtime.vaultId)?.let { envelope ->
                        envelope.kdf.salt.fill(0)
                        envelope.nonce.fill(0)
                        envelope.wrappedKeyset.fill(0)
                        true
                    } ?: false
                },
                recordStatus = { status ->
                    recordRestoredPackageStatus(
                        stateStore = runtime.backupStateStore,
                        vaultId = runtime.vaultId,
                        status = status,
                    )
                },
                restoredPublicationBlocked = {
                    restoredPackagePublicationBlocked(
                        stateStore = runtime.backupStateStore,
                        vaultId = runtime.vaultId,
                    )
                },
                refreshPortablePackage = {
                    publisher.refresh()
                    Unit
                },
            ),
            statusSource = PersistedAndroidBackupStatusSource(
                scope = scope,
                observeBackupState = {
                    runtime.backupStateStore.observe(runtime.vaultId)
                },
            ),
            portableBackupPublisher = publisher,
        )
    }
}
