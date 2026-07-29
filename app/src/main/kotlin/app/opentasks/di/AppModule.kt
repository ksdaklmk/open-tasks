package app.opentasks.di

import android.content.Context
import app.opentasks.backup.AndroidAtomicPackageFile
import app.opentasks.backup.AndroidBackupFiles
import app.opentasks.backup.AndroidBackupRuntime
import app.opentasks.backup.DefaultAndroidBackupRuntime
import app.opentasks.backup.PersistedAndroidBackupStatusSource
import app.opentasks.backup.PortableBackupPublisher
import app.opentasks.backup.RecoveryEnvelopePreparer
import app.opentasks.backup.RestoredPackageIntake
import app.opentasks.backup.recordRestoredPackageStatus
import app.opentasks.backup.restoredPackagePublicationBlocked
import app.opentasks.core.crypto.AndroidVaultContentKeyStore
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.LocalVaultRuntime
import app.opentasks.core.data.LocalVaultRepositoryFactory
import app.opentasks.core.data.backup.DefaultLocalBackupObjectStore
import app.opentasks.core.data.backup.PortableBackupCodec
import app.opentasks.core.data.backup.PortablePackageCodec
import app.opentasks.core.domain.BackupCoordinator
import app.opentasks.core.domain.AndroidBackupStatusSource
import app.opentasks.core.domain.DefaultInsightsEngine
import app.opentasks.core.domain.InsightsEngine
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.InsightsTimeProvider
import app.opentasks.SystemInsightsTimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    fun provideLocalVaultRuntime(
        @ApplicationContext context: Context,
    ): LocalVaultRuntime = LocalVaultRepositoryFactory.createRuntime(context)

    @Provides
    fun provideVaultRepository(runtime: LocalVaultRuntime): VaultRepository = runtime.repository

    @Provides
    @Singleton
    fun provideVaultCrypto(): VaultCrypto = TinkVaultCrypto()

    @Provides
    @Singleton
    fun provideVaultContentKeyStore(
        @ApplicationContext context: Context,
        crypto: VaultCrypto,
    ): VaultContentKeyStore = AndroidVaultContentKeyStore(context, crypto)

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

    @Provides
    @Singleton
    fun provideAndroidBackupFiles(
        @ApplicationContext context: Context,
    ): AndroidBackupFiles = AndroidBackupFiles(context)

    @Provides
    @Singleton
    fun provideBackupApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideBackupCoordinator(
        runtime: LocalVaultRuntime,
        files: AndroidBackupFiles,
        codec: AuthenticatedCloudObjectCodec,
        contentKeyStore: VaultContentKeyStore,
    ): BackupCoordinator = LocalVaultRepositoryFactory.createBackupCoordinator(
        runtime = runtime,
        objectStore = DefaultLocalBackupObjectStore(files.localBackupRoot),
        authenticatedCodec = codec,
        contentKeyStore = contentKeyStore,
    )

    @Provides
    @Singleton
    fun provideRecoveryEnvelopePreparer(
        runtime: LocalVaultRuntime,
        contentKeyStore: VaultContentKeyStore,
        crypto: VaultCrypto,
    ): RecoveryEnvelopePreparer = RecoveryEnvelopePreparer(
        vaultId = runtime.vaultId,
        keyStore = contentKeyStore,
        crypto = crypto,
    )

    @Provides
    @Singleton
    fun providePortableBackupPublisher(
        runtime: LocalVaultRuntime,
        files: AndroidBackupFiles,
        contentKeyStore: VaultContentKeyStore,
        codec: PortablePackageCodec,
        envelopePreparer: RecoveryEnvelopePreparer,
    ): PortableBackupPublisher = PortableBackupPublisher(
        vaultId = runtime.vaultId,
        captureSource = runtime.backupCaptureSource,
        stateStore = runtime.backupStateStore,
        envelopeStore = runtime.recoveryEnvelopeStore,
        contentKeyStore = contentKeyStore,
        packageFile = AndroidAtomicPackageFile(files.eligiblePackage),
        codec = codec,
        prepareEnvelope = envelopePreparer::prepare,
    )

    @Provides
    @Singleton
    fun provideRestoredPackageIntake(
        runtime: LocalVaultRuntime,
        files: AndroidBackupFiles,
        contentKeyStore: VaultContentKeyStore,
        codec: PortablePackageCodec,
    ): RestoredPackageIntake = RestoredPackageIntake(
        vaultId = runtime.vaultId,
        eligiblePackage = files.eligiblePackage,
        recoveryInbox = files.recoveryInbox,
        packageFile = AndroidAtomicPackageFile(files.eligiblePackage),
        stateStore = runtime.backupStateStore,
        envelopeStore = runtime.recoveryEnvelopeStore,
        contentKeyStore = contentKeyStore,
        codec = codec,
    )

    @Provides
    @Singleton
    fun provideAndroidBackupRuntime(
        scope: CoroutineScope,
        runtime: LocalVaultRuntime,
        intake: RestoredPackageIntake,
        contentKeyStore: VaultContentKeyStore,
        coordinator: BackupCoordinator,
        publisher: PortableBackupPublisher,
    ): AndroidBackupRuntime = DefaultAndroidBackupRuntime(
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
                if (state?.recoveryEnvelopeReady == true || envelope != null) {
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
            if (status is AndroidBackupStatus.RestoredPackageDetected) {
                recordRestoredPackageStatus(
                    stateStore = runtime.backupStateStore,
                    vaultId = runtime.vaultId,
                    status = status,
                )
            }
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
    )

    @Provides
    @Singleton
    fun provideAndroidBackupStatusSource(
        scope: CoroutineScope,
        runtime: LocalVaultRuntime,
    ): AndroidBackupStatusSource = PersistedAndroidBackupStatusSource(
        scope = scope,
        observeBackupState = {
            runtime.backupStateStore.observe(runtime.vaultId)
        },
    )
}
