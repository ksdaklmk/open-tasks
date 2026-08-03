package app.opentasks.di

import android.content.Context
import android.content.Intent
import app.opentasks.ActiveVaultServices
import app.opentasks.ActiveVaultSession
import app.opentasks.DefaultActiveVaultSession
import app.opentasks.backup.AndroidAtomicPackageFile
import app.opentasks.backup.AndroidBackupFiles
import app.opentasks.backup.AndroidBackupRuntime
import app.opentasks.backup.DefaultAndroidBackupRuntime
import app.opentasks.backup.DefaultRecoveryPassphraseChanger
import app.opentasks.backup.DefaultRemoteBackupLifecycleCoordinator
import app.opentasks.backup.DefaultRemoteBackupRunner
import app.opentasks.backup.DefaultRemoteBackupRuntime
import app.opentasks.backup.EncryptedBackupActionResult
import app.opentasks.backup.OtVaultExporter
import app.opentasks.backup.PersistedAndroidBackupStatusSource
import app.opentasks.backup.PortableBackupPublisher
import app.opentasks.backup.RecoveryEnvelopePreparer
import app.opentasks.backup.RemoteBackupWorkerFactory
import app.opentasks.backup.RestoredPackageIntake
import app.opentasks.backup.WorkManagerRemoteBackupScheduler
import app.opentasks.backup.drive.DefaultGoogleDriveAuthorizationManager
import app.opentasks.backup.drive.DriveAuthorizationResult
import app.opentasks.backup.drive.DriveAuthorizationUnavailableReason
import app.opentasks.backup.drive.DriveAuthorizationMode
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
import app.opentasks.core.data.backup.AttachmentBlobSetManifestCodec
import app.opentasks.core.data.backup.AttachmentCacheStore
import app.opentasks.core.data.backup.AttachmentProviderSession
import app.opentasks.core.data.backup.AttachmentRuntime
import app.opentasks.core.data.backup.AttachmentSessionResult
import app.opentasks.core.data.backup.CreateOnlyDriveAttachmentBlobStore
import app.opentasks.core.data.backup.CreateOnlyDriveObjectStore
import app.opentasks.core.data.backup.DefaultLocalBackupObjectStore
import app.opentasks.core.data.backup.DefaultOwnershipChainStore
import app.opentasks.core.data.backup.DefaultPublicationCatalog
import app.opentasks.core.data.backup.DefaultRemoteBackupCoordinator
import app.opentasks.core.data.backup.OtVaultCodec
import app.opentasks.core.data.backup.OwnershipClaimCodec
import app.opentasks.core.data.backup.PortableBackupCodec
import app.opentasks.core.data.backup.PortablePackageCodec
import app.opentasks.core.data.backup.PublicationCodec
import app.opentasks.core.data.backup.RemoteObjectCodec
import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.domain.BackupCoordinator
import app.opentasks.core.domain.AndroidBackupStatusSource
import app.opentasks.core.domain.BackupWorkScheduler
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.DefaultInsightsEngine
import app.opentasks.core.domain.InsightsEngine
import app.opentasks.core.domain.RecoveryPassphraseChanger
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupConnectResult
import app.opentasks.core.domain.RemoteBackupLifecycleCoordinator
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStatus
import app.opentasks.core.model.RemoteBackupVerifiedInfo
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex

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

    // Process-scoped: unique background work outlives any one vault slot.
    @Provides
    @Singleton
    fun provideBackupWorkScheduler(
        @ApplicationContext context: Context,
    ): BackupWorkScheduler = WorkManagerRemoteBackupScheduler(context)

    /**
     * Resolves the runner of whichever slot is active when work actually
     * starts, so a worker never holds a service from a replaced slot and a
     * device with no open vault simply runs nothing.
     */
    @Provides
    @Singleton
    fun provideRemoteBackupWorkerFactory(
        services: ActiveVaultServices,
    ): RemoteBackupWorkerFactory = RemoteBackupWorkerFactory {
        services.sessionOrNull()?.remoteBackupRunner
    }

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
     * Built fresh per resolution rather than held on the session: an export is
     * a one-shot, on-demand action, not a service the active slot needs to
     * stop when replaced. It reads the active slot's own captures and
     * attachment runtime, so nothing here outlives that slot either.
     */
    @Provides
    fun provideOtVaultExporter(
        runtime: LocalVaultRuntime,
        crypto: VaultCrypto,
        codec: AuthenticatedCloudObjectCodec,
        services: ActiveVaultServices,
    ): OtVaultExporter = OtVaultExporter(
        vaultId = runtime.vaultId,
        captureSource = runtime.backupCaptureSource,
        vaultRepository = runtime.repository,
        contentKeyStore = runtime.contentKeyStore,
        codec = OtVaultCodec(codec),
        prepareEnvelope = RecoveryEnvelopePreparer(
            vaultId = runtime.vaultId,
            keyStore = runtime.contentKeyStore,
            crypto = crypto,
        )::prepare,
        readChunksForExport = { attachment, onChunk ->
            services.requireSession().attachmentRuntime.readChunksForExport(attachment, onChunk)
        },
    )

    @Provides
    fun provideRecoveryPassphraseChanger(
        services: ActiveVaultServices,
    ): RecoveryPassphraseChanger = services.requireSession().recoveryPassphraseChanger

    @Provides
    fun provideRemoteBackupLifecycleCoordinator(
        services: ActiveVaultServices,
    ): RemoteBackupLifecycleCoordinator =
        services.requireSession().remoteBackupLifecycleCoordinator

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
        authorizationManager: GoogleDriveAuthorizationManager,
        workScheduler: BackupWorkScheduler,
    ): ActiveVaultSession {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val contentKeyStore = runtime.contentKeyStore
        val localObjectStore = DefaultLocalBackupObjectStore(files.localBackupRoot)
        val coordinator: BackupCoordinator = LocalVaultRepositoryFactory.createBackupCoordinator(
            runtime = runtime,
            objectStore = localObjectStore,
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
        val ownershipCodec = OwnershipClaimCodec(codec)
        val publicationCodec = PublicationCodec(codec)
        val openRemoteObjectStore = { transport: CreateOnlyDriveTransport ->
            CreateOnlyDriveObjectStore(
                transport = transport,
                transferStore = runtime.remoteBackupStore,
                stagingRoot = files.remoteTransferRoot,
            )
        }
        val remoteConfigurator = LocalVaultRepositoryFactory.createRemoteBackupConfigurator(
            runtime = runtime,
            backupCoordinator = coordinator,
            localObjectStore = localObjectStore,
            authenticatedCodec = codec,
            remoteStagingRoot = files.remoteTransferRoot,
        )
        // Attachment bytes live in the lineage that created them, so nothing
        // here is bound to one: the runtime resolves the active lineage per
        // operation and builds its store, coordinators, and collector around
        // it. Only the ciphertext cache is per installation, because a frame
        // stays valid across every lineage change that keeps its blob set.
        val attachmentRuntime = AttachmentRuntime(
            vaultId = runtime.vaultId,
            repository = runtime.repository,
            remoteStateStore = runtime.remoteBackupStore,
            transferDao = runtime.attachmentTransferDao,
            journalDao = runtime.backupJournalDao,
            codec = codec,
            manifestCodec = AttachmentBlobSetManifestCodec(codec),
            cache = AttachmentCacheStore(files.attachmentCacheRoot) {
                files.attachmentCacheRoot.usableSpace
            },
            contentKeyStore = contentKeyStore,
            openSession = { configuration ->
                openAttachmentSession(
                    configuration = configuration,
                    authorizationManager = authorizationManager,
                    openObjectStore = openRemoteObjectStore,
                    ownershipCodec = ownershipCodec,
                )
            },
        )
        // Exactly one coordinator, and one runner around it, exists per open
        // slot: retention and durable publication both depend on no two runs
        // for a vault ever overlapping.
        val publicationGate = Mutex()
        val remoteRunner = DefaultRemoteBackupRunner(
            vaultId = runtime.vaultId,
            remoteStateStore = runtime.remoteBackupStore,
            coordinator = DefaultRemoteBackupCoordinator(
                vaultId = runtime.vaultId,
                backupCoordinator = coordinator,
                backupStateStore = runtime.backupStateStore,
                recoveryEnvelopeStore = runtime.recoveryEnvelopeStore,
                contentKeyStore = contentKeyStore,
                remoteStateStore = runtime.remoteBackupStore,
                transferStore = runtime.remoteBackupStore,
                localObjectStore = localObjectStore,
                remoteObjectCodec = RemoteObjectCodec(
                    authenticatedCodec = codec,
                    localObjectStore = localObjectStore,
                    stagingRoot = files.remoteTransferRoot,
                ),
                ownershipCodec = ownershipCodec,
                publicationCodec = publicationCodec,
            ),
            authorize = { expectedAccountDigest ->
                authorizationManager.authorize(
                    mode = DriveAuthorizationMode.NON_INTERACTIVE,
                    expectedAccountDigest = expectedAccountDigest,
                )
            },
            clearToken = authorizationManager::clearToken,
            openObjectStore = openRemoteObjectStore,
            publicationGate = publicationGate,
            collectAttachments = { attachmentRuntime.collectRetiredBytes() },
        )
        val recoveryPassphraseChanger = DefaultRecoveryPassphraseChanger(
            vaultId = runtime.vaultId,
            crypto = crypto,
            recoveryEnvelopeStore = runtime.recoveryEnvelopeStore,
            remoteStateStore = runtime.remoteBackupStore,
            publishPortable = publisher::publishWithEnvelope,
            authorizationManager = authorizationManager,
            openObjectStore = openRemoteObjectStore,
            ownershipStore = { DefaultOwnershipChainStore(it, ownershipCodec) },
            publicationCatalog = { DefaultPublicationCatalog(it, publicationCodec) },
            publicationCodec = publicationCodec,
            publicationGate = publicationGate,
        )
        val lifecycleCoordinator = DefaultRemoteBackupLifecycleCoordinator(
            vaultId = runtime.vaultId,
            crypto = crypto,
            recoveryEnvelopeStore = runtime.recoveryEnvelopeStore,
            remoteStateStore = runtime.remoteBackupStore,
            transferStore = runtime.remoteBackupStore,
            scheduler = workScheduler,
            authorizationManager = authorizationManager,
            openObjectStore = openRemoteObjectStore,
            ownershipStore = { DefaultOwnershipChainStore(it, ownershipCodec) },
            ownershipCodec = ownershipCodec,
            publicationCodec = publicationCodec,
            configurator = remoteConfigurator,
            publicationGate = publicationGate,
            onAttachmentContentReleased = attachmentRuntime::recordAllContentCollected,
        )
        val remoteStatus = observeRemoteBackupStatus(
            runtime.remoteBackupStore.observeActive(runtime.vaultId),
            runtime.backupStateStore.observe(runtime.vaultId)
                .map { BackupGeneration(it.currentGeneration) },
            remoteRunner.running,
        )
            .stateIn(scope, SharingStarted.Eagerly, RemoteBackupStatus.Disabled)
        val authorizeForConnect: suspend (Boolean, Intent?) -> EncryptedBackupActionResult =
            { allowSeparateLineage, resolution ->
                val authorization = if (resolution == null) {
                    authorizationManager.authorize(
                        DriveAuthorizationMode.EXPLICIT_ACCOUNT,
                        expectedAccountDigest = null,
                    )
                } else {
                    authorizationManager.acceptResolution(
                        resolution,
                        expectedAccountDigest = null,
                    )
                }
                when (authorization) {
                    is DriveAuthorizationResult.Authorized -> {
                        val digest = authorization.session.copyAccountBindingDigest()
                        try {
                            EncryptedBackupActionResult.ConnectResult(
                                remoteConfigurator.connect(
                                    objectStore = openRemoteObjectStore(
                                        authorization.session.transport,
                                    ),
                                    accountBindingDigest = digest,
                                    allowSeparateLineage = allowSeparateLineage,
                                ),
                            )
                        } finally {
                            digest.fill(0)
                            authorization.session.close()
                        }
                    }
                    is DriveAuthorizationResult.ResolutionRequired ->
                        EncryptedBackupActionResult.ResolutionRequired(
                            authorization.pendingIntent,
                        )
                    DriveAuthorizationResult.AccountMismatch ->
                        EncryptedBackupActionResult.Failed(
                            RemoteBackupFailureCategory.ACCOUNT_MISMATCH,
                        )
                    is DriveAuthorizationResult.Unavailable ->
                        EncryptedBackupActionResult.Failed(
                            authorization.reason.toRemoteFailure(),
                        )
                }
            }
        val reauthorise: suspend (Intent?) -> EncryptedBackupActionResult = { resolution ->
            val expected = runtime.remoteBackupStore.active(runtime.vaultId)
                ?.accountBindingDigest
            if (expected == null) {
                EncryptedBackupActionResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            } else {
                try {
                    val authorization = if (resolution == null) {
                        authorizationManager.authorize(
                            DriveAuthorizationMode.EXPLICIT_ACCOUNT,
                            expected,
                        )
                    } else {
                        authorizationManager.acceptResolution(resolution, expected)
                    }
                    when (authorization) {
                        is DriveAuthorizationResult.Authorized -> {
                            authorization.session.close()
                            EncryptedBackupActionResult.Completed
                        }
                        is DriveAuthorizationResult.ResolutionRequired ->
                            EncryptedBackupActionResult.ResolutionRequired(
                                authorization.pendingIntent,
                            )
                        DriveAuthorizationResult.AccountMismatch ->
                            EncryptedBackupActionResult.Failed(
                                RemoteBackupFailureCategory.ACCOUNT_MISMATCH,
                            )
                        is DriveAuthorizationResult.Unavailable ->
                            EncryptedBackupActionResult.Failed(
                                authorization.reason.toRemoteFailure(),
                            )
                    }
                } finally {
                    expected.fill(0)
                }
            }
        }
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
            remoteBackupRuntime = DefaultRemoteBackupRuntime(
                scope = scope,
                runner = remoteRunner,
                scheduler = workScheduler,
                observeConfiguration = {
                    runtime.remoteBackupStore.observeActive(runtime.vaultId)
                },
                // Stage 2's own checkpoint is what tells the lineage it is
                // behind; remote verification never advances it.
                observeLocalGeneration = {
                    runtime.backupStateStore.observe(runtime.vaultId)
                        .map { it.currentGeneration }
                },
                expireAttachmentSessions = { attachmentRuntime.expireStaleSessions() },
                resumeAttachmentSessions = { attachmentRuntime.resumeInterruptedSessions() },
            ),
            remoteBackupRunner = remoteRunner,
            attachmentRuntime = attachmentRuntime,
            recoveryPassphraseChanger = recoveryPassphraseChanger,
            remoteBackupLifecycleCoordinator = lifecycleCoordinator,
            remoteBackupStatus = remoteStatus,
            recoveryAccountDigest = {
                recoveryAccountBindingDigest(
                    runtime.remoteBackupStore.configurations(runtime.vaultId),
                )
            },
            connectRemote = authorizeForConnect,
            reauthoriseRemote = reauthorise,
        )
    }

    internal fun remoteBackupStatus(
        configuration: app.opentasks.core.domain.RemoteBackupConfiguration?,
        runnerInFlight: Boolean,
        localGeneration: BackupGeneration,
    ): RemoteBackupStatus {
        if (configuration == null) return RemoteBackupStatus.Disabled
        if (runnerInFlight && configuration.lifecycle == RemoteBackupLifecycle.ACTIVE) {
            return RemoteBackupStatus.BackingUp(localGeneration)
        }
        return when (configuration.lifecycle) {
            RemoteBackupLifecycle.CONNECTING -> RemoteBackupStatus.Preparing
            RemoteBackupLifecycle.DORMANT -> RemoteBackupStatus.Disabled
            RemoteBackupLifecycle.OWNERSHIP_LOST -> RemoteBackupStatus.OwnershipLost
            RemoteBackupLifecycle.DELETING -> RemoteBackupStatus.Deleting
            RemoteBackupLifecycle.TERMINATED -> RemoteBackupStatus.Terminated
            RemoteBackupLifecycle.BLOCKED ->
                RemoteBackupStatus.ActionRequired(
                    configuration.failureCategory ?: RemoteBackupFailureCategory.LOCAL_STORAGE,
                )
            RemoteBackupLifecycle.ACTIVE -> when (val failure = configuration.failureCategory) {
                RemoteBackupFailureCategory.OWNERSHIP_LOST -> RemoteBackupStatus.OwnershipLost
                RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE ->
                    RemoteBackupStatus.AmbiguousRemoteState
                RemoteBackupFailureCategory.TERMINATED -> RemoteBackupStatus.Terminated
                RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
                RemoteBackupFailureCategory.PROVIDER_STORAGE,
                -> RemoteBackupStatus.RetryScheduled(
                    configuration.lastVerifiedGeneration ?: BackupGeneration(0),
                    failure,
                )
                null -> if (
                    configuration.lastVerifiedGeneration != null &&
                    configuration.lastVerifiedAt != null
                ) {
                    RemoteBackupStatus.Verified(
                        RemoteBackupVerifiedInfo(
                            requireNotNull(configuration.lastVerifiedGeneration),
                            requireNotNull(configuration.lastVerifiedAt),
                        ),
                    )
                } else {
                    RemoteBackupStatus.Preparing
                }
                else -> RemoteBackupStatus.ActionRequired(failure)
            }
        }
    }

    internal fun observeRemoteBackupStatus(
        configurations: Flow<app.opentasks.core.domain.RemoteBackupConfiguration?>,
        localGenerations: Flow<BackupGeneration>,
        runnerInFlight: Flow<Boolean>,
    ): Flow<RemoteBackupStatus> = combine(
        configurations,
        localGenerations,
        runnerInFlight,
    ) { configuration, localGeneration, running ->
        remoteBackupStatus(configuration, running, localGeneration)
    }

    internal fun recoveryAccountBindingDigest(
        configurations: List<app.opentasks.core.domain.RemoteBackupConfiguration>,
    ): ByteArray? = configurations.singleOrNull {
        it.lifecycle == RemoteBackupLifecycle.ACTIVE ||
            it.lifecycle == RemoteBackupLifecycle.OWNERSHIP_LOST
    }?.accountBindingDigest
}

/**
 * Opens one attachment provider session for the lineage a configuration names.
 *
 * Authorization is always silent here: neither a person's own open nor a
 * background collection pass may raise a consent screen from the vault
 * session, so a grant this process cannot obtain becomes a bounded category
 * the product surfaces instead. The session owns the provider handle both
 * stores were built around, and closing it releases that handle.
 *
 * This lives outside the module object because a function-typed parameter on a
 * `@Module` member is not something the annotation processor can resolve.
 */
private suspend fun openAttachmentSession(
    configuration: RemoteBackupConfiguration,
    authorizationManager: GoogleDriveAuthorizationManager,
    openObjectStore: (CreateOnlyDriveTransport) -> CreateOnlyBackupObjectStore,
    ownershipCodec: OwnershipClaimCodec,
): AttachmentSessionResult {
    val expectedDigest = configuration.accountBindingDigest
    val authorization = try {
        authorizationManager.authorize(
            mode = DriveAuthorizationMode.NON_INTERACTIVE,
            expectedAccountDigest = expectedDigest,
        )
    } finally {
        expectedDigest.fill(0)
    }
    return when (authorization) {
        is DriveAuthorizationResult.Authorized -> AttachmentSessionResult.Opened(
            AttachmentProviderSession(
                blobStore = CreateOnlyDriveAttachmentBlobStore(
                    transport = authorization.session.transport,
                    lineageId = configuration.lineageId,
                ),
                ownershipChainStore = DefaultOwnershipChainStore(
                    openObjectStore(authorization.session.transport),
                    ownershipCodec,
                ),
                onClose = authorization.session::close,
            ),
        )

        DriveAuthorizationResult.AccountMismatch ->
            AttachmentSessionResult.Unavailable(RemoteBackupFailureCategory.ACCOUNT_MISMATCH)

        is DriveAuthorizationResult.ResolutionRequired ->
            AttachmentSessionResult.Unavailable(
                RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
            )

        is DriveAuthorizationResult.Unavailable ->
            AttachmentSessionResult.Unavailable(authorization.reason.toRemoteFailure())
    }
}

private fun DriveAuthorizationUnavailableReason.toRemoteFailure():
    RemoteBackupFailureCategory = when (this) {
    DriveAuthorizationUnavailableReason.AUTHORIZATION_REQUIRED,
    DriveAuthorizationUnavailableReason.REJECTED,
    -> RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED
    DriveAuthorizationUnavailableReason.RETRYABLE ->
        RemoteBackupFailureCategory.RETRYABLE_PROVIDER
    DriveAuthorizationUnavailableReason.PROVIDER_STORAGE ->
        RemoteBackupFailureCategory.PROVIDER_STORAGE
    DriveAuthorizationUnavailableReason.CORRUPT_OR_INCOMPATIBLE ->
        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE
}
