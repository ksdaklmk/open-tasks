package app.opentasks

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import app.opentasks.backup.AndroidAtomicPackageFile
import app.opentasks.backup.DefaultRecoveryPassphraseChanger
import app.opentasks.backup.DefaultRemoteBackupLifecycleCoordinator
import app.opentasks.backup.DefaultRemoteBackupRunner
import app.opentasks.backup.PortableBackupPublisher
import app.opentasks.backup.drive.AuthorizedDriveSession
import app.opentasks.backup.drive.DriveAuthorizationMode
import app.opentasks.backup.drive.DriveAuthorizationResult
import app.opentasks.backup.drive.GoogleDriveAuthorizationManager
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.DefaultVaultRuntimeManager
import app.opentasks.core.data.LocalVaultRepositoryFactory
import app.opentasks.core.data.LocalVaultRuntime
import app.opentasks.core.data.LocalVaultRuntimeFactory
import app.opentasks.core.data.VaultRuntimeState
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.data.backup.AttachmentBlobSetManifestCodec
import app.opentasks.core.data.backup.AttachmentCacheStore
import app.opentasks.core.data.backup.AttachmentGcResult
import app.opentasks.core.data.backup.AttachmentIntakeResult
import app.opentasks.core.data.backup.AttachmentOpenResult
import app.opentasks.core.data.backup.AttachmentProviderSession
import app.opentasks.core.data.backup.AttachmentRuntime
import app.opentasks.core.data.backup.AttachmentSessionResult
import app.opentasks.core.data.backup.AttachmentSource
import app.opentasks.core.data.backup.BackupSnapshotCodec
import app.opentasks.core.data.backup.CreateOnlyDriveObjectStore
import app.opentasks.core.data.backup.DefaultLocalBackupObjectStore
import app.opentasks.core.data.backup.DefaultOwnershipChainStore
import app.opentasks.core.data.backup.DefaultPublicationCatalog
import app.opentasks.core.data.backup.DefaultRemoteBackupCoordinator
import app.opentasks.core.data.backup.LocalBackupObjectStore
import app.opentasks.core.data.backup.OwnershipClaimCodec
import app.opentasks.core.data.backup.PortableBackupCodec
import app.opentasks.core.data.backup.PublicationCodec
import app.opentasks.core.data.backup.RemoteBackupTransferStore
import app.opentasks.core.data.backup.RemoteObjectCodec
import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.data.backup.drive.DriveChunkResult
import app.opentasks.core.data.backup.drive.DriveCreateRequest
import app.opentasks.core.data.backup.drive.DriveCreateResult
import app.opentasks.core.data.backup.drive.DriveDownloadReceipt
import app.opentasks.core.data.backup.drive.DriveFileMetadata
import app.opentasks.core.data.backup.drive.DriveListPage
import app.opentasks.core.data.backup.drive.DriveResumableSession
import app.opentasks.core.data.backup.drive.DriveTransportException
import app.opentasks.core.data.backup.drive.DriveTransportFailureCategory
import app.opentasks.core.domain.AttachmentBlobStore
import app.opentasks.core.domain.AttachmentListedObject
import app.opentasks.core.domain.AttachmentManifestLookup
import app.opentasks.core.domain.AttachmentObjectResult
import app.opentasks.core.domain.AttachmentReadResult
import app.opentasks.core.domain.BackupCoordinator
import app.opentasks.core.domain.BackupWorkScheduler
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.CreateSmallResult
import app.opentasks.core.domain.DeleteObjectResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.ImmutableDownloadResult
import app.opentasks.core.domain.ImmutableUploadRequest
import app.opentasks.core.domain.ImmutableUploadResult
import app.opentasks.core.domain.LifecycleResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.OwnedRemoteFile
import app.opentasks.core.domain.PassphraseChangeResult
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RecoveryCoordinator
import app.opentasks.core.domain.RecoveryResult
import app.opentasks.core.domain.RemoteBackupConnectResult
import app.opentasks.core.domain.RemoteBackupCoordinator
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.domain.RemoteBackupRunResult
import app.opentasks.core.domain.RemoteListPage
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.domain.RemoteListedObject
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectLifecycle
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.WriterEpoch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 14's process-local two-installation qualification over production protocols. */
class RecoveryTakeoverInstrumentedTest {
    @Test
    fun createOnlyRecoveryTakeoverLifecyclePreservesCanonicalWorkspace() = runBlocking {
        withTimeout(180_000) {
            val base = ApplicationProvider.getApplicationContext<OpenTasksApplication>()
            val crypto = TinkVaultCrypto()
            val codec = DefaultAuthenticatedCloudObjectCodec(crypto)
            val provider = TestProviderStore(File(base.cacheDir, "task14-provider-${UUID.randomUUID()}"))
            val blobs = TestAttachmentNamespace()
            val contexts = mutableListOf<IsolatedContext>()
            val managers = mutableListOf<Pair<IsolatedContext, DefaultVaultRuntimeManager>>()
            val aContext = isolated(base, "a").also(contexts::add)
            val aSlot = VaultSlot.new()
            val runtimeA = LocalVaultRepositoryFactory.createRuntime(aContext, aSlot, crypto)
            try {
                assertTrue(runtimeA.repository.execute(DomainCommand.CreateTask("baseline")) is CommandResult.Success)
                installRecoveryEnvelope(runtimeA, crypto, OLD_PASSPHRASE)
                val a = backupStack(aContext, runtimeA, codec)
                val connected = a.configurator.connect(provider, ACCOUNT_DIGEST, false)
                    as RemoteBackupConnectResult.Connected
                val originalLineage = connected.lineageId
                assertEquals(2, provider.uploads(RemoteObjectRoleV1.SNAPSHOT, originalLineage).size)

                assertTrue(
                    runtimeA.repository.execute(DomainCommand.CreateTask("incremental"))
                        is CommandResult.Success,
                )
                assertTrue(a.remote.run(provider) is RemoteBackupRunResult.Verified)
                val expectedWorkspace = canonicalWorkspace(runtimeA)
                assertTrue(provider.entries(originalLineage).count {
                    it.metadata.role == RemoteObjectRoleV1.PUBLICATION
                } >= 2)

                val activeA = checkNotNull(runtimeA.remoteBackupStore.active(runtimeA.vaultId))
                val publication = verifiedPublication(runtimeA, provider, codec)
                val currentBase = publication.manifest.inventory.single {
                    it.logicalObjectId == publication.manifest.currentBaseObjectId
                }.providerFileId
                val fallbackBase = publication.manifest.inventory.single {
                    it.logicalObjectId == publication.manifest.fallbackBaseObjectId
                }.providerFileId
                provider.corrupt(currentBase)

                val b = recoveryInstallation(base, "b", crypto, codec).also {
                    contexts += it.context
                    managers += it.context to it.manager
                }
                val c = recoveryInstallation(base, "c", crypto, codec).also {
                    contexts += it.context
                    managers += it.context to it.manager
                }
                val bPrepared = prepareDrive(b.coordinator, provider, OLD_PASSPHRASE)
                val cPrepared = prepareDrive(c.coordinator, provider, OLD_PASSPHRASE)
                assertTrue(provider.downloadIds.contains(currentBase))
                assertTrue(provider.downloadIds.contains(fallbackBase))

                assertTrue(
                    runtimeA.repository.execute(DomainCommand.CreateTask("divergent-old-owner"))
                        is CommandResult.Success,
                )
                val publicationsBeforeStaleFinish = provider.entries(originalLineage).count {
                    it.metadata.role == RemoteObjectRoleV1.PUBLICATION
                }
                var raceResults: List<RecoveryResult>? = null
                provider.beforeCreate = { metadata ->
                    if (metadata.role == RemoteObjectRoleV1.PUBLICATION && raceResults == null) {
                        raceResults = coroutineScope {
                            listOf(
                                async { b.coordinator.confirmTakeover(bPrepared.operationId, provider) },
                                async { c.coordinator.confirmTakeover(cPrepared.operationId, provider) },
                            ).awaitAll()
                        }
                    }
                }
                val runner = DefaultRemoteBackupRunner(
                    vaultId = runtimeA.vaultId,
                    remoteStateStore = runtimeA.remoteBackupStore,
                    coordinator = a.remote,
                    authorize = {
                        DriveAuthorizationResult.Authorized(
                            AuthorizedDriveSession(
                                transport = UnusedDriveTransport(),
                                accountBindingDigest = ACCOUNT_DIGEST,
                                accessToken = "opaque",
                                account = null,
                            ),
                        )
                    },
                    clearToken = {},
                    openObjectStore = { provider },
                )
                assertEquals(RemoteBackupRunResult.OwnershipLost, runner.run())
                val raced = checkNotNull(raceResults)
                assertEquals(1, raced.count { it is RecoveryResult.Activated })
                assertEquals(1, raced.count {
                    it == RecoveryResult.Failed(app.opentasks.core.model.RecoveryFailureCategory.OWNERSHIP_LOST)
                })
                assertTrue(provider.entries(originalLineage).count {
                    it.metadata.role == RemoteObjectRoleV1.PUBLICATION
                } > publicationsBeforeStaleFinish)
                assertEquals(
                    RemoteBackupLifecycle.OWNERSHIP_LOST,
                    checkNotNull(runtimeA.remoteBackupStore.known(originalLineage)).lifecycle,
                )

                val winner = if (raced[0] is RecoveryResult.Activated) b else c
                val winnerRuntime = winner.manager.requireActive()
                assertArrayEquals(expectedWorkspace, canonicalWorkspace(winnerRuntime))

                val winnerStack = backupStack(winner.context, winnerRuntime, codec)
                val portable = File(winner.context.filesDir, "portable.otb")
                val publisher = PortableBackupPublisher(
                    vaultId = winnerRuntime.vaultId,
                    captureSource = winnerRuntime.backupCaptureSource,
                    stateStore = winnerRuntime.backupStateStore,
                    envelopeStore = winnerRuntime.recoveryEnvelopeStore,
                    contentKeyStore = winnerRuntime.contentKeyStore,
                    packageFile = AndroidAtomicPackageFile(portable),
                    codec = PortableBackupCodec(codec),
                    prepareEnvelope = { error("Rotation supplies its envelope") },
                )
                val authorization = TestAuthorizationManager()
                val changer = DefaultRecoveryPassphraseChanger(
                    vaultId = winnerRuntime.vaultId,
                    crypto = crypto,
                    recoveryEnvelopeStore = winnerRuntime.recoveryEnvelopeStore,
                    remoteStateStore = winnerRuntime.remoteBackupStore,
                    publishPortable = publisher::publishWithEnvelope,
                    authorizationManager = authorization,
                    openObjectStore = { provider },
                    ownershipStore = { DefaultOwnershipChainStore(it, OwnershipClaimCodec(codec)) },
                    publicationCatalog = { DefaultPublicationCatalog(it, PublicationCodec(codec)) },
                    publicationCodec = PublicationCodec(codec),
                )
                val beforeRotation = checkNotNull(
                    winnerRuntime.remoteBackupStore.active(winnerRuntime.vaultId),
                )
                val rotation = changer.change(
                    OLD_PASSPHRASE.copyOf(),
                    NEW_PASSPHRASE.copyOf(),
                )
                assertEquals(
                    PassphraseChangeResult.Changed(),
                    rotation,
                )
                val afterRotation = checkNotNull(
                    winnerRuntime.remoteBackupStore.active(winnerRuntime.vaultId),
                )
                assertEquals(beforeRotation.writerEpoch, afterRotation.writerEpoch)
                assertEquals(beforeRotation.lastVerifiedGeneration, afterRotation.lastVerifiedGeneration)
                assertEquals(
                    checkNotNull(beforeRotation.currentPublication).sequence.value + 1,
                    checkNotNull(afterRotation.currentPublication).sequence.value,
                )
                assertEquals(
                    beforeRotation.recoveryCredentialGeneration + 1,
                    afterRotation.recoveryCredentialGeneration,
                )

                val aLifecycle = lifecycle(
                    runtimeA,
                    a.configurator,
                    crypto,
                    codec,
                    provider,
                    blobs,
                    TestAuthorizationManager(),
                )
                val preserved = aLifecycle.preserveDivergentWorkAsNewLineage()
                    as RemoteBackupConnectResult.Connected
                assertNotEquals(originalLineage, preserved.lineageId)
                assertEquals(
                    runtimeA.backupCaptureSource.capture().generation,
                    checkNotNull(runtimeA.remoteBackupStore.active(runtimeA.vaultId))
                        .lastVerifiedGeneration,
                )

                val winnerLifecycle = lifecycle(
                    winnerRuntime,
                    winnerStack.configurator,
                    crypto,
                    codec,
                    provider,
                    blobs,
                    authorization,
                )
                val callsBeforeDisconnect = provider.callOrder.size
                assertTrue(winnerLifecycle.disconnect() is LifecycleResult.Disconnected)
                assertEquals(callsBeforeDisconnect, provider.callOrder.size)
                assertTrue(authorization.revoked)
                val callsBeforeMismatch = provider.callOrder.size
                assertEquals(
                    RemoteBackupConnectResult.Failed(RemoteBackupFailureCategory.ACCOUNT_MISMATCH),
                    winnerStack.configurator.connect(provider, WRONG_ACCOUNT_DIGEST, false),
                )
                assertEquals(callsBeforeMismatch, provider.callOrder.size)
                assertTrue(
                    winnerStack.configurator.connect(provider, ACCOUNT_DIGEST, false)
                        is RemoteBackupConnectResult.Connected,
                )
                assertEquals(callsBeforeMismatch, provider.callOrder.size)

                val fresh = recoveryInstallation(base, "fresh", crypto, codec).also {
                    contexts += it.context
                    managers += it.context to it.manager
                }
                var freshPreparation: RecoveryResult.TakeoverConfirmationRequired? = null
                for (candidate in fresh.coordinator.discover(provider, null)) {
                    val result = fresh.coordinator.prepare(
                            candidate,
                            NEW_PASSPHRASE.copyOf(),
                            provider,
                            ACCOUNT_DIGEST,
                        )
                    if (result is RecoveryResult.TakeoverConfirmationRequired) {
                        freshPreparation = result
                        break
                    }
                }
                val preparedFresh = checkNotNull(freshPreparation)
                assertTrue(
                    fresh.coordinator.confirmTakeover(preparedFresh.operationId, provider)
                        is RecoveryResult.Activated,
                )
                val freshRuntime = fresh.manager.requireActive()
                assertArrayEquals(expectedWorkspace, canonicalWorkspace(freshRuntime))

                val freshStack = backupStack(fresh.context, freshRuntime, codec)
                val deleteLifecycle = lifecycle(
                    freshRuntime,
                    freshStack.configurator,
                    crypto,
                    codec,
                    provider,
                    blobs,
                    TestAuthorizationManager(),
                )
                provider.failNextDelete = true
                var deletion: LifecycleResult = deleteLifecycle.deleteHistory(NEW_PASSPHRASE.copyOf())
                repeat(20) {
                    if (deletion == LifecycleResult.HistoryDeleted) return@repeat
                    deletion = deleteLifecycle.deleteHistory(NEW_PASSPHRASE.copyOf())
                }
                assertEquals(LifecycleResult.HistoryDeleted, deletion)
                val remaining = provider.entries(originalLineage)
                assertEquals(1, remaining.size)
                assertEquals(RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE, remaining.single().metadata.role)

                val callsBeforePortable = provider.callOrder.size
                val portableRecovery = recoveryInstallation(base, "portable", crypto, codec).also {
                    contexts += it.context
                    managers += it.context to it.manager
                }
                val portableCandidate = portableRecovery.coordinator.discover(null, portable).single()
                assertTrue(
                    portableRecovery.coordinator.prepare(
                        portableCandidate,
                        NEW_PASSPHRASE.copyOf(),
                        null,
                        null,
                    ) is RecoveryResult.Activated,
                )
                assertArrayEquals(
                    expectedWorkspace,
                    canonicalWorkspace(portableRecovery.manager.requireActive()),
                )
                assertEquals(callsBeforePortable, provider.callOrder.size)
                assertEquals(activeA.writerEpoch, WriterEpoch(1))
            } finally {
                runtimeA.close()
                LocalVaultRuntimeFactory(aContext, crypto).discard(aSlot)
                managers.forEach { (context, manager) -> cleanup(context, manager, crypto) }
                contexts.forEach(IsolatedContext::clearPreferences)
                provider.close()
            }
        }
    }

    @Test
    fun resumableUploadAboveFiveMiBResumesAfterObjectStoreRecreation() = runBlocking {
        withTimeout(30_000) {
            val base = ApplicationProvider.getApplicationContext<OpenTasksApplication>()
            val root = File(base.cacheDir, "task14-resumable-${UUID.randomUUID()}").also { it.mkdirs() }
            val bytes = ByteArray(5 * 1024 * 1024 + 1) { (it % 251).toByte() }
            val frame = ownedFile(bytes, root)
            val transfer = MemoryTransferStore()
            val transport = ResumableDriveTransport()
            val request = ImmutableUploadRequest(
                lineageId = CloudLineageId.new(),
                writerEpoch = WriterEpoch(1),
                ownerDeviceId = CloudDeviceId.new(),
                operationId = "task14-resume",
                logicalObjectId = RemoteLogicalObjectId.new(),
                providerObjectId = ProviderObjectId.of("task14-large-provider"),
                role = RemoteObjectRoleV1.SEGMENT,
                firstGeneration = BackupGeneration(1),
                lastGeneration = BackupGeneration(1),
                frameLength = bytes.size.toLong(),
                frameSha256 = Sha256Digest.of(sha256(bytes)),
                frame = frame,
            )
            try {
                transport.failAfterChunks = 1
                val interrupted = CreateOnlyDriveObjectStore(transport, transfer, root)
                    .uploadImmutable(request)
                assertEquals(
                    ImmutableUploadResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
                    interrupted,
                )
                val confirmedOffset = transport.received.size.toLong()
                assertTrue(confirmedOffset > 0)

                transport.failAfterChunks = null
                val resumed = CreateOnlyDriveObjectStore(transport, transfer, root)
                    .uploadImmutable(request)

                assertEquals(ImmutableUploadResult.UploadedAndVerified, resumed)
                assertEquals(1, transport.starts)
                assertEquals(listOf("task14-session"), transport.queries)
                assertEquals(confirmedOffset, transport.firstOffsetAfterQuery)
                assertArrayEquals(bytes, transport.remoteBytes)
            } finally {
                frame.close()
                root.deleteRecursively()
            }
        }
    }

    /**
     * Task 12's attachment lifecycle over the same production protocols.
     *
     * One installation uploads bytes, a second recovers the workspace and
     * reads them back from manifest discovery alone, the superseded
     * installation is refused before it touches the namespace, divergent work
     * keeps its metadata while its bytes stay behind in the lineage that owns
     * them, retired bytes are collected only once every retained base covers
     * their tombstone, and terminal deletion leaves nothing but the tombstone.
     */
    @Test
    fun createOnlyAttachmentLifecycleSurvivesRecoveryPreservationAndTerminalDeletion() =
        runBlocking {
            withTimeout(180_000) {
                val base = ApplicationProvider.getApplicationContext<OpenTasksApplication>()
                val crypto = TinkVaultCrypto()
                val codec = DefaultAuthenticatedCloudObjectCodec(crypto)
                val provider = TestProviderStore(
                    File(base.cacheDir, "task12-provider-${UUID.randomUUID()}"),
                )
                val blobs = TestAttachmentNamespace()
                val contexts = mutableListOf<IsolatedContext>()
                val managers = mutableListOf<Pair<IsolatedContext, DefaultVaultRuntimeManager>>()
                val aContext = isolated(base, "attach-a").also(contexts::add)
                val aSlot = VaultSlot.new()
                val runtimeA = LocalVaultRepositoryFactory.createRuntime(aContext, aSlot, crypto)
                var clockB = INTAKE_AT
                try {
                    assertTrue(
                        runtimeA.repository.execute(DomainCommand.CreateTask("baseline"))
                            is CommandResult.Success,
                    )
                    installRecoveryEnvelope(runtimeA, crypto, OLD_PASSPHRASE)
                    val a = backupStack(aContext, runtimeA, codec)
                    val lineage = (
                        a.configurator.connect(provider, ACCOUNT_DIGEST, false)
                            as RemoteBackupConnectResult.Connected
                        ).lineageId
                    val attachmentsA =
                        attachmentRuntime(aContext, runtimeA, codec, provider, blobs) { INTAKE_AT }
                    val taskId = runtimeA.repository.currentWorkspace()
                        .tasks.single { it.title == "baseline" }.id

                    // One 9 MiB source becomes three authenticated chunks and a manifest.
                    val payload = ByteArray(9 * 1024 * 1024) { (it % 251).toByte() }
                    val attachmentId = (
                        attachmentsA.intake(
                            taskId,
                            "receipt.pdf",
                            "application/pdf",
                            BytesAttachmentSource(payload),
                        ) as AttachmentIntakeResult.Registered
                        ).attachmentId
                    assertEquals(4, blobs.entries(lineage).size)
                    assertTrue(a.remote.run(provider) is RemoteBackupRunResult.Verified)

                    val b = recoveryInstallation(base, "attach-b", crypto, codec).also {
                        contexts += it.context
                        managers += it.context to it.manager
                    }
                    val preparedB = prepareDrive(b.coordinator, provider, OLD_PASSPHRASE)
                    assertTrue(
                        b.coordinator.confirmTakeover(preparedB.operationId, provider)
                            is RecoveryResult.Activated,
                    )
                    val runtimeB = b.manager.requireActive()
                    val bStack = backupStack(b.context, runtimeB, codec)
                    val attachmentsB =
                        attachmentRuntime(b.context, runtimeB, codec, provider, blobs) { clockB }

                    // The recovered installation holds no provider identity of
                    // its own: the manifest is discovered from the lineage.
                    val recovered = runtimeB.repository.currentWorkspace().attachments.single()
                    assertEquals(attachmentId, recovered.id)
                    val opened = ByteArrayOutputStream()
                    assertEquals(
                        AttachmentOpenResult.Opened(payload.size.toLong()),
                        attachmentsB.open(recovered, opened),
                    )
                    assertArrayEquals(payload, opened.toByteArray())
                    assertTrue(attachmentsB.cacheUsageBytes() > 0)

                    // The superseded installation still believes it is active.
                    val namespaceBeforeStaleA = blobs.all().size
                    assertEquals(
                        AttachmentIntakeResult.OwnershipUnavailable,
                        attachmentsA.intake(
                            taskId,
                            "second.bin",
                            "application/octet-stream",
                            BytesAttachmentSource(ByteArray(2_048) { 7 }),
                        ),
                    )
                    assertTrue(
                        runtimeA.repository.execute(
                            DomainCommand.DeleteAttachment(attachmentId, DELETED_AT),
                        ) is CommandResult.Success,
                    )
                    // A cannot reach the namespace for collection either. It
                    // is refused locally first — the base it retains does not
                    // cover the retirement it just recorded — and the refusal
                    // that follows a tip change is proven directly in
                    // AttachmentOwnershipBoundaryTest.
                    assertEquals(
                        AttachmentGcResult(0, false, 0),
                        attachmentsA.collectRetiredBytes(),
                    )
                    assertEquals(namespaceBeforeStaleA, blobs.all().size)

                    // Divergent work keeps its metadata; its bytes belong to
                    // the lineage it lost, and the new one copies nothing.
                    val runner = DefaultRemoteBackupRunner(
                        vaultId = runtimeA.vaultId,
                        remoteStateStore = runtimeA.remoteBackupStore,
                        coordinator = a.remote,
                        authorize = {
                            DriveAuthorizationResult.Authorized(
                                AuthorizedDriveSession(
                                    transport = UnusedDriveTransport(),
                                    accountBindingDigest = ACCOUNT_DIGEST,
                                    accessToken = "opaque",
                                    account = null,
                                ),
                            )
                        },
                        clearToken = {},
                        openObjectStore = { provider },
                    )
                    assertEquals(RemoteBackupRunResult.OwnershipLost, runner.run())
                    val preserved = lifecycle(
                        runtimeA,
                        a.configurator,
                        crypto,
                        codec,
                        provider,
                        blobs,
                        TestAuthorizationManager(),
                    ).preserveDivergentWorkAsNewLineage()
                        as RemoteBackupConnectResult.Connected
                    assertNotEquals(lineage, preserved.lineageId)
                    val divergent = runtimeA.repository.currentWorkspace().attachments.single()
                    assertEquals(recovered.blobSetId, divergent.blobSetId)
                    assertEquals(
                        AttachmentOpenResult.Unavailable,
                        attachmentsA.open(divergent, ByteArrayOutputStream()),
                    )
                    assertTrue(blobs.entries(preserved.lineageId).isEmpty())
                    assertEquals(namespaceBeforeStaleA, blobs.all().size)

                    // A second blob set nothing retires stays untouched by
                    // collection and is removed only by terminal deletion.
                    assertTrue(
                        attachmentsB.intake(
                            taskId,
                            "kept.bin",
                            "application/octet-stream",
                            BytesAttachmentSource(ByteArray(4_096) { 3 }),
                        ) is AttachmentIntakeResult.Registered,
                    )
                    assertEquals(6, blobs.entries(lineage).size)
                    assertTrue(
                        runtimeB.repository.execute(
                            DomainCommand.DeleteAttachment(recovered.id, DELETED_AT),
                        ) is CommandResult.Success,
                    )
                    repeat(2) { index ->
                        assertTrue(
                            runtimeB.repository.execute(DomainCommand.CreateTask("after-$index"))
                                is CommandResult.Success,
                        )
                        assertTrue(bStack.remote.run(provider) is RemoteBackupRunResult.Verified)
                    }

                    clockB = DELETED_AT.plus(Duration.ofDays(31))
                    val collected = attachmentsB.collectRetiredBytes()
                    assertEquals(4, collected.deletedObjects)
                    assertEquals(2, blobs.entries(lineage).size)

                    // The record survives collection with everything that says
                    // what its content was; only the link to released bytes is
                    // gone, which is what stops it being offered again.
                    val afterCollection = runtimeB.repository.currentWorkspace().attachments
                    assertEquals(2, afterCollection.size)
                    val collectedRecord = afterCollection.single { it.id == recovered.id }
                    assertNull(collectedRecord.blobSetId)
                    assertEquals(DELETED_AT, collectedRecord.deletedAt)
                    assertEquals(recovered.contentHash, collectedRecord.contentHash)
                    assertEquals(recovered.displayName, collectedRecord.displayName)
                    assertEquals(recovered.byteCount, collectedRecord.byteCount)
                    assertEquals(AttachmentGcResult(0, false, 0), attachmentsB.collectRetiredBytes())

                    // Disconnect keeps every record and every cached frame.
                    val bLifecycle = lifecycle(
                        runtimeB,
                        bStack.configurator,
                        crypto,
                        codec,
                        provider,
                        blobs,
                        TestAuthorizationManager(),
                    )
                    val cachedAfterCollection = attachmentsB.cacheUsageBytes()
                    assertTrue(cachedAfterCollection > 0)
                    assertTrue(bLifecycle.disconnect() is LifecycleResult.Disconnected)
                    val namespaceBeforeDormantOpen = blobs.all().size
                    assertEquals(
                        AttachmentOpenResult.Unavailable,
                        attachmentsB.open(recovered, ByteArrayOutputStream()),
                    )
                    assertEquals(namespaceBeforeDormantOpen, blobs.all().size)
                    assertEquals(2, runtimeB.repository.currentWorkspace().attachments.size)
                    assertEquals(cachedAfterCollection, attachmentsB.cacheUsageBytes())
                    attachmentsB.evictCachedBytes(checkNotNull(recovered.blobSetId))
                    assertEquals(0L, attachmentsB.cacheUsageBytes())

                    assertTrue(
                        bStack.configurator.connect(provider, ACCOUNT_DIGEST, false)
                            is RemoteBackupConnectResult.Connected,
                    )
                    var deletion: LifecycleResult = bLifecycle.deleteHistory(
                        OLD_PASSPHRASE.copyOf(),
                    )
                    repeat(20) {
                        if (deletion == LifecycleResult.HistoryDeleted) return@repeat
                        deletion = bLifecycle.deleteHistory(OLD_PASSPHRASE.copyOf())
                    }
                    assertEquals(LifecycleResult.HistoryDeleted, deletion)
                    assertTrue(blobs.entries(lineage).isEmpty())
                    val remaining = provider.entries(lineage)
                    assertEquals(1, remaining.size)
                    assertEquals(
                        RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE,
                        remaining.single().metadata.role,
                    )
                    assertEquals(2, runtimeB.repository.currentWorkspace().attachments.size)
                } finally {
                    runtimeA.close()
                    LocalVaultRuntimeFactory(aContext, crypto).discard(aSlot)
                    managers.forEach { (context, manager) -> cleanup(context, manager, crypto) }
                    contexts.forEach(IsolatedContext::clearPreferences)
                    provider.close()
                }
            }
        }

    private data class BackupStack(
        val coordinator: BackupCoordinator,
        val local: LocalBackupObjectStore,
        val configurator: app.opentasks.core.domain.RemoteBackupConfigurator,
        val remote: RemoteBackupCoordinator,
    )

    private fun backupStack(
        context: Context,
        runtime: LocalVaultRuntime,
        codec: DefaultAuthenticatedCloudObjectCodec,
    ): BackupStack {
        val local = DefaultLocalBackupObjectStore(File(context.noBackupFilesDir, "local-backup"))
        val coordinator = LocalVaultRepositoryFactory.createBackupCoordinator(
            runtime,
            local,
            codec,
            runtime.contentKeyStore,
        )
        val remoteCodec = RemoteObjectCodec(
            codec,
            local,
            File(context.noBackupFilesDir, "remote-staging"),
        )
        val configurator = LocalVaultRepositoryFactory.createRemoteBackupConfigurator(
            runtime,
            coordinator,
            local,
            codec,
            File(context.noBackupFilesDir, "connect-staging"),
        )
        return BackupStack(
            coordinator,
            local,
            configurator,
            DefaultRemoteBackupCoordinator(
                vaultId = runtime.vaultId,
                backupCoordinator = coordinator,
                backupStateStore = runtime.backupStateStore,
                recoveryEnvelopeStore = runtime.recoveryEnvelopeStore,
                contentKeyStore = runtime.contentKeyStore,
                remoteStateStore = runtime.remoteBackupStore,
                transferStore = runtime.remoteBackupStore,
                localObjectStore = local,
                remoteObjectCodec = remoteCodec,
                ownershipCodec = OwnershipClaimCodec(codec),
                publicationCodec = PublicationCodec(codec),
            ),
        )
    }

    private data class RecoveryInstallation(
        val context: IsolatedContext,
        val manager: DefaultVaultRuntimeManager,
        val coordinator: RecoveryCoordinator,
    )

    private suspend fun recoveryInstallation(
        base: Context,
        label: String,
        crypto: TinkVaultCrypto,
        codec: DefaultAuthenticatedCloudObjectCodec,
    ): RecoveryInstallation {
        val context = isolated(base, label)
        val manager = DefaultVaultRuntimeManager(context, crypto)
        manager.initialize()
        assertTrue(manager.state.value is VaultRuntimeState.NoVault)
        return RecoveryInstallation(
            context,
            manager,
            LocalVaultRepositoryFactory.createRecoveryCoordinator(
                context,
                crypto,
                manager,
                codec,
                File(context.noBackupFilesDir, "recovery-staging"),
            ),
        )
    }

    private suspend fun prepareDrive(
        coordinator: RecoveryCoordinator,
        provider: TestProviderStore,
        passphrase: CharArray,
    ): RecoveryResult.TakeoverConfirmationRequired {
        val candidate = coordinator.discover(provider, null).single()
        return coordinator.prepare(candidate, passphrase.copyOf(), provider, ACCOUNT_DIGEST)
            as RecoveryResult.TakeoverConfirmationRequired
    }

    /**
     * The lifecycle coordinator reaches the attachment namespace through its
     * own provider handle, so the fake namespace has to be supplied here too:
     * terminal deletion exhausts the two attachment roles before claims, and a
     * transport that refuses every call would fail the run rather than prove it.
     */
    private fun lifecycle(
        runtime: LocalVaultRuntime,
        configurator: app.opentasks.core.domain.RemoteBackupConfigurator,
        crypto: TinkVaultCrypto,
        codec: DefaultAuthenticatedCloudObjectCodec,
        provider: TestProviderStore,
        blobs: TestAttachmentNamespace,
        authorization: TestAuthorizationManager,
    ) = DefaultRemoteBackupLifecycleCoordinator(
        vaultId = runtime.vaultId,
        crypto = crypto,
        recoveryEnvelopeStore = runtime.recoveryEnvelopeStore,
        remoteStateStore = runtime.remoteBackupStore,
        transferStore = runtime.remoteBackupStore,
        scheduler = TestScheduler(),
        authorizationManager = authorization,
        openObjectStore = { provider },
        ownershipStore = { DefaultOwnershipChainStore(it, OwnershipClaimCodec(codec)) },
        ownershipCodec = OwnershipClaimCodec(codec),
        publicationCodec = PublicationCodec(codec),
        configurator = configurator,
        openAttachmentStore = { _, lineageId -> blobs.view(lineageId) },
    )

    /**
     * The attachment services one installation owns, built exactly as the app
     * builds them: collaborators are bound per operation to whichever lineage
     * the vault is active on, and the cache is the only thing that survives a
     * lineage change.
     */
    private fun attachmentRuntime(
        context: IsolatedContext,
        runtime: LocalVaultRuntime,
        codec: DefaultAuthenticatedCloudObjectCodec,
        provider: TestProviderStore,
        blobs: TestAttachmentNamespace,
        now: () -> Instant,
    ) = AttachmentRuntime(
        vaultId = runtime.vaultId,
        repository = runtime.repository,
        remoteStateStore = runtime.remoteBackupStore,
        transferDao = runtime.attachmentTransferDao,
        journalDao = runtime.backupJournalDao,
        codec = codec,
        manifestCodec = AttachmentBlobSetManifestCodec(codec),
        cache = AttachmentCacheStore(File(context.cacheDir, "attachments/v1")) { 1L shl 30 },
        contentKeyStore = runtime.contentKeyStore,
        openSession = { configuration ->
            AttachmentSessionResult.Opened(
                AttachmentProviderSession(
                    blobStore = blobs.view(configuration.lineageId),
                    ownershipChainStore = DefaultOwnershipChainStore(
                        provider,
                        OwnershipClaimCodec(codec),
                    ),
                ) {},
            )
        },
        now = now,
    )

    private suspend fun installRecoveryEnvelope(
        runtime: LocalVaultRuntime,
        crypto: TinkVaultCrypto,
        passphrase: CharArray,
    ) {
        val key = runtime.contentKeyStore.getOrCreate(runtime.vaultId)
        val envelope = try {
            crypto.wrapForRecovery(key, passphrase.copyOf())
        } finally {
            key.close()
        }
        try {
            runtime.recoveryEnvelopeStore.upsert(runtime.vaultId, envelope)
        } finally {
            envelope.kdf.salt.fill(0)
            envelope.nonce.fill(0)
            envelope.wrappedKeyset.fill(0)
        }
    }

    private suspend fun verifiedPublication(
        runtime: LocalVaultRuntime,
        provider: TestProviderStore,
        codec: DefaultAuthenticatedCloudObjectCodec,
    ): app.opentasks.core.data.backup.VerifiedPublication {
        val configuration = checkNotNull(runtime.remoteBackupStore.active(runtime.vaultId))
        val key = runtime.contentKeyStore.openExisting(runtime.vaultId)
        return try {
            PublicationCodec(codec).verify(
                provider.bytes(checkNotNull(configuration.currentPublication).providerId.value),
                key,
            )
        } finally {
            key.close()
        }
    }

    private suspend fun canonicalWorkspace(runtime: LocalVaultRuntime): ByteArray {
        val payload = BackupSnapshotCodec.fromCapture(runtime.backupCaptureSource.capture())
        return BackupSnapshotCodec.encode(payload.copy(coveredGeneration = 0))
    }

    private fun isolated(base: Context, label: String) = IsolatedContext(
        base,
        File(base.cacheDir, "task14-$label-${UUID.randomUUID()}"),
        "task14-$label-${UUID.randomUUID()}",
    )

    private fun cleanup(
        context: IsolatedContext,
        manager: DefaultVaultRuntimeManager,
        crypto: TinkVaultCrypto,
    ) {
        val factory = LocalVaultRuntimeFactory(context, crypto)
        val active = (manager.state.value as? VaultRuntimeState.Active)?.runtime
        active?.let {
            val slot = it.slot
            it.close()
            factory.discard(slot)
        }
        factory.listStagedSlots().forEach(factory::discard)
        context.root.deleteRecursively()
    }

    private companion object {
        val OLD_PASSPHRASE = "correct horse battery".toCharArray()
        val NEW_PASSPHRASE = "correct horse battery rotated".toCharArray()
        val ACCOUNT_DIGEST = ByteArray(32) { (it + 3).toByte() }
        val WRONG_ACCOUNT_DIGEST = ByteArray(32) { (it + 31).toByte() }
        val INTAKE_AT: Instant = Instant.parse("2026-06-01T00:00:00Z")
        val DELETED_AT: Instant = Instant.parse("2026-06-02T00:00:00Z")
    }
}

private class IsolatedContext(
    base: Context,
    val root: File,
    private val preferencePrefix: String,
) : ContextWrapper(base) {
    private val preferenceNames = mutableSetOf<String>()
    private fun directory(name: String) = File(root, name).also { it.mkdirs() }

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = directory("files")
    override fun getNoBackupFilesDir(): File = directory("no-backup")
    override fun getCacheDir(): File = directory("cache")
    override fun getDatabasePath(name: String): File = File(directory("databases"), name)

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val isolated = "$preferencePrefix-$name"
        preferenceNames += isolated
        return baseContext.getSharedPreferences(isolated, mode)
    }

    override fun deleteSharedPreferences(name: String): Boolean =
        baseContext.deleteSharedPreferences("$preferencePrefix-$name")

    override fun deleteDatabase(name: String): Boolean {
        val file = getDatabasePath(name)
        val targets = listOf(file, File("${file.path}-wal"), File("${file.path}-shm"))
        val existed = targets.any(File::exists)
        targets.forEach { it.delete() }
        return !existed || targets.none(File::exists)
    }

    fun clearPreferences() {
        preferenceNames.forEach(baseContext::deleteSharedPreferences)
        root.deleteRecursively()
    }
}

private class TestAuthorizationManager : GoogleDriveAuthorizationManager {
    var revoked = false

    override suspend fun authorize(
        mode: DriveAuthorizationMode,
        expectedAccountDigest: ByteArray?,
    ): DriveAuthorizationResult = if (
        expectedAccountDigest != null &&
        !MessageDigest.isEqual(expectedAccountDigest, RecoveryTakeoverAccount.digest)
    ) {
        DriveAuthorizationResult.AccountMismatch
    } else {
        DriveAuthorizationResult.Authorized(
            AuthorizedDriveSession(
                transport = UnusedDriveTransport(),
                accountBindingDigest = RecoveryTakeoverAccount.digest,
                accessToken = "opaque",
                account = null,
            ),
        )
    }

    override suspend fun acceptResolution(
        data: Intent,
        expectedAccountDigest: ByteArray?,
    ): DriveAuthorizationResult = authorize(DriveAuthorizationMode.NON_INTERACTIVE, expectedAccountDigest)

    override suspend fun clearToken(session: AuthorizedDriveSession) = Unit

    override suspend fun revokeAccess(session: AuthorizedDriveSession) {
        revoked = true
    }
}

private object RecoveryTakeoverAccount {
    val digest = ByteArray(32) { (it + 3).toByte() }
}

private class TestScheduler : BackupWorkScheduler {
    override fun onPendingGeneration() = Unit
    override fun ensurePeriodic() = Unit
    override fun cancelAll() = Unit
}

private class UnusedDriveTransport : CreateOnlyDriveTransport {
    override suspend fun readCurrentUserPermissionId(): String = error("Unused")
    override suspend fun generateAppDataFileIds(count: Int): List<String> = error("Unused")
    override suspend fun listAppDataFiles(query: String, pageToken: String?, pageSize: Int): DriveListPage = error("Unused")
    override suspend fun createFileIfAbsent(request: DriveCreateRequest): DriveCreateResult = error("Unused")
    override suspend fun downloadFile(providerFileId: String, destination: File, maximumBytes: Long): DriveDownloadReceipt = error("Unused")
    override suspend fun startResumableCreate(metadata: DriveFileMetadata, totalBytes: Long): DriveResumableSession = error("Unused")
    override suspend fun queryResumableUpload(sessionUri: String, totalBytes: Long): DriveChunkResult = error("Unused")
    override suspend fun uploadChunk(sessionUri: String, firstByte: Long, totalBytes: Long, content: ByteArray): DriveChunkResult = error("Unused")
    override suspend fun deleteFile(providerFileId: String): Boolean = error("Unused")
    override fun close() = Unit
}

private class TestProviderStore(private val root: File) : CreateOnlyBackupObjectStore {
    data class Entry(
        var bytes: ByteArray,
        val metadata: RemoteListedObject,
        val lineageId: CloudLineageId,
    )

    private val stored = linkedMapOf<String, Entry>()
    private var generated = 0
    val callOrder = mutableListOf<String>()
    val downloadIds = mutableListOf<String>()
    var beforeCreate: (suspend (RemoteListedObject) -> Unit)? = null
    var failNextDelete = false

    init {
        root.mkdirs()
    }

    override suspend fun generateProviderIds(count: Int, role: RemoteObjectRoleV1): List<ProviderObjectId> =
        synchronized(stored) {
            callOrder += "generate:${role.name}"
            (1..count).map { ProviderObjectId.of("task14-${role.name}-${++generated}") }
        }

    override suspend fun createSmallIfAbsent(
        providerObjectId: ProviderObjectId,
        lineageId: CloudLineageId,
        metadata: RemoteListedObject,
        bytes: OwnedRemoteBytes,
    ): CreateSmallResult {
        callOrder += "create:${metadata.role}"
        beforeCreate?.invoke(metadata)
        val content = bytes.take()
        return try {
            synchronized(stored) {
                if (providerObjectId.value in stored) {
                    CreateSmallResult.AlreadyExists
                } else {
                    stored[providerObjectId.value] = Entry(content.copyOf(), metadata, lineageId)
                    CreateSmallResult.Created
                }
            }
        } finally {
            content.fill(0)
            bytes.close()
        }
    }

    override suspend fun readSmall(providerObjectId: ProviderObjectId, maximumBytes: Long): ReadSmallResult {
        callOrder += "read"
        val content = synchronized(stored) { stored[providerObjectId.value]?.bytes?.copyOf() }
            ?: return ReadSmallResult.Missing
        return if (content.size > maximumBytes) {
            content.fill(0)
            ReadSmallResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        } else {
            ReadSmallResult.Found(ownedBytes(content))
        }
    }

    override suspend fun list(request: RemoteListRequest): RemoteListPage {
        callOrder += "list:${request.role.name}"
        val matches = synchronized(stored) {
            stored.values.filter { entry ->
                entry.metadata.role == request.role &&
                    (request.lineageId == null || entry.lineageId == request.lineageId) &&
                    (request.writerEpoch == null || entry.metadata.writerEpoch == request.writerEpoch) &&
                    (request.ownerDeviceId == null || entry.metadata.ownerDeviceId == request.ownerDeviceId)
            }.map(Entry::metadata)
        }
        val offset = request.pageToken?.toIntOrNull() ?: 0
        val page = matches.drop(offset).take(request.pageSize)
        val next = offset + page.size
        return RemoteListPage(page, if (next < matches.size) next.toString() else null)
    }

    override suspend fun uploadImmutable(request: ImmutableUploadRequest): ImmutableUploadResult {
        callOrder += "upload:${request.role.name}"
        val content = request.frame.file.readBytes()
        if (content.size.toLong() != request.frameLength || sha256(content) != request.frameSha256.value) {
            return ImmutableUploadResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        }
        return synchronized(stored) {
            val current = stored[request.providerObjectId.value]
            when {
                current == null -> {
                    stored[request.providerObjectId.value] = Entry(
                        content.copyOf(),
                        RemoteListedObject(
                            request.providerObjectId,
                            request.logicalObjectId.value,
                            request.role,
                            request.writerEpoch,
                            request.ownerDeviceId,
                        ),
                        request.lineageId,
                    )
                    ImmutableUploadResult.UploadedAndVerified
                }
                current.bytes.contentEquals(content) -> ImmutableUploadResult.OccupiedByExpectedBytes
                else -> ImmutableUploadResult.OccupiedByDifferentBytes
            }
        }
    }

    override suspend fun downloadImmutable(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
        expectedSha256: Sha256Digest,
    ): ImmutableDownloadResult {
        callOrder += "download"
        downloadIds += providerObjectId.value
        val content = synchronized(stored) { stored[providerObjectId.value]?.bytes?.copyOf() }
            ?: return ImmutableDownloadResult.Missing
        if (content.size > maximumBytes) return ImmutableDownloadResult.Failed(
            RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
        )
        if (sha256(content) != expectedSha256.value) return ImmutableDownloadResult.Corrupt
        return ImmutableDownloadResult.Downloaded(ownedFile(content, root))
    }

    override suspend fun delete(providerObjectId: ProviderObjectId): DeleteObjectResult {
        callOrder += "delete"
        if (failNextDelete) {
            failNextDelete = false
            return DeleteObjectResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
        }
        return if (synchronized(stored) { stored.remove(providerObjectId.value) } != null) {
            DeleteObjectResult.Deleted
        } else {
            DeleteObjectResult.Missing
        }
    }

    fun bytes(providerObjectId: String): ByteArray = synchronized(stored) {
        checkNotNull(stored[providerObjectId]).bytes.copyOf()
    }

    fun corrupt(providerObjectId: String) = synchronized(stored) {
        val entry = checkNotNull(stored[providerObjectId])
        entry.bytes = entry.bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
    }

    fun entries(lineageId: CloudLineageId): List<Entry> = synchronized(stored) {
        stored.values.filter { it.lineageId == lineageId }.toList()
    }

    fun uploads(role: RemoteObjectRoleV1, lineageId: CloudLineageId): List<Entry> =
        entries(lineageId).filter { it.metadata.role == role }

    fun close() {
        synchronized(stored) {
            stored.values.forEach { it.bytes.fill(0) }
            stored.clear()
        }
        root.deleteRecursively()
    }
}

private class BytesAttachmentSource(private val bytes: ByteArray) : AttachmentSource {
    override val declaredByteCount: Long = bytes.size.toLong()

    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

/**
 * One create-only attachment namespace shared by every installation.
 *
 * Discovery and enumeration are scoped to the lineage that created an object,
 * exactly as the Drive adapter's `lineageId` app property scopes them, while
 * reads and deletes address an exact provider identity. That is what makes a
 * separate lineage report absent bytes instead of another lineage's.
 */
private class TestAttachmentNamespace {
    private class Entry(
        val bytes: ByteArray,
        val role: String,
        val blobSetId: String,
        val lineageId: CloudLineageId,
    )

    private val stored = linkedMapOf<String, Entry>()
    private var generated = 0

    fun all(): List<String> = synchronized(stored) { stored.keys.toList() }

    fun entries(lineageId: CloudLineageId): List<String> = synchronized(stored) {
        stored.filterValues { it.lineageId == lineageId }.keys.toList()
    }

    fun view(lineageId: CloudLineageId): AttachmentBlobStore = View(lineageId)

    private inner class View(private val lineageId: CloudLineageId) : AttachmentBlobStore {
        override suspend fun generateObjectIds(count: Int): List<ProviderObjectId> =
            synchronized(stored) {
                (1..count).map { ProviderObjectId.of("attachment-object-${++generated}") }
            }

        override suspend fun createChunk(
            providerObjectId: ProviderObjectId,
            blobSetId: BlobSetId,
            chunkIndex: Int,
            chunkCount: Int,
            frameBytes: ByteArray,
        ): AttachmentObjectResult =
            create(providerObjectId, blobSetId, ATTACHMENT_CHUNK_ROLE, frameBytes)

        override suspend fun createManifest(
            providerObjectId: ProviderObjectId,
            blobSetId: BlobSetId,
            frameBytes: ByteArray,
        ): AttachmentObjectResult =
            create(providerObjectId, blobSetId, ATTACHMENT_MANIFEST_ROLE, frameBytes)

        override suspend fun readObject(
            providerObjectId: ProviderObjectId,
            maximumBytes: Long,
        ): AttachmentReadResult {
            val bytes = synchronized(stored) { stored[providerObjectId.value]?.bytes?.copyOf() }
                ?: return AttachmentReadResult.Missing
            return if (bytes.size > maximumBytes) {
                bytes.fill(0)
                AttachmentReadResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            } else {
                AttachmentReadResult.Found(bytes)
            }
        }

        override suspend fun findManifest(blobSetId: BlobSetId): AttachmentManifestLookup {
            val matches = synchronized(stored) {
                stored.filterValues {
                    it.lineageId == lineageId &&
                        it.role == ATTACHMENT_MANIFEST_ROLE &&
                        it.blobSetId == blobSetId.value
                }.keys.toList()
            }
            return when (matches.size) {
                0 -> AttachmentManifestLookup.Missing
                1 -> AttachmentManifestLookup.Found(ProviderObjectId.of(matches.single()))
                else -> AttachmentManifestLookup.Ambiguous
            }
        }

        override suspend fun listNamespace(
            pageToken: String?,
            exactRole: String?,
        ): Pair<List<AttachmentListedObject>, String?> {
            val listed = synchronized(stored) {
                stored.entries
                    .filter { (_, entry) ->
                        entry.lineageId == lineageId &&
                            (exactRole == null || entry.role == exactRole)
                    }
                    .map { (providerId, entry) ->
                        AttachmentListedObject(
                            providerObjectId = ProviderObjectId.of(providerId),
                            role = entry.role,
                            blobSetId = entry.blobSetId,
                            createdAtEpochMillis = null,
                        )
                    }
            }
            val offset = pageToken?.toIntOrNull() ?: 0
            val page = listed.drop(offset).take(ATTACHMENT_PAGE_SIZE)
            val next = (offset + page.size).takeIf { it < listed.size }?.toString()
            return page to next
        }

        override suspend fun delete(providerObjectId: ProviderObjectId): Boolean {
            synchronized(stored) { stored.remove(providerObjectId.value)?.bytes?.fill(0) }
            return true
        }

        private fun create(
            providerObjectId: ProviderObjectId,
            blobSetId: BlobSetId,
            role: String,
            frameBytes: ByteArray,
        ): AttachmentObjectResult = synchronized(stored) {
            if (providerObjectId.value in stored) {
                AttachmentObjectResult.AlreadyExists
            } else {
                stored[providerObjectId.value] = Entry(
                    bytes = frameBytes.copyOf(),
                    role = role,
                    blobSetId = blobSetId.value,
                    lineageId = lineageId,
                )
                AttachmentObjectResult.Created
            }
        }
    }
}

private const val ATTACHMENT_CHUNK_ROLE = "attachment-chunk"
private const val ATTACHMENT_MANIFEST_ROLE = "attachment-manifest"
private const val ATTACHMENT_PAGE_SIZE = 100

private class MemoryTransferStore : RemoteBackupTransferStore {
    private val objects = linkedMapOf<Pair<String, String>, RemoteBackupObject>()

    override suspend fun objectState(lineageId: CloudLineageId, logicalObjectId: RemoteLogicalObjectId): RemoteBackupObject? =
        objects[lineageId.value to logicalObjectId.value]

    override suspend fun insertObject(value: RemoteBackupObject) {
        objects[value.lineageId.value to value.logicalObjectId.value] = value
    }

    override suspend fun compareAndSetObject(expected: RemoteBackupObject, next: RemoteBackupObject): Boolean {
        val key = expected.lineageId.value to expected.logicalObjectId.value
        if (objects[key] != expected) return false
        objects[key] = next
        return true
    }

    override suspend fun objectsForLineage(lineageId: CloudLineageId): List<RemoteBackupObject> =
        objects.values.filter { it.lineageId == lineageId }

    override suspend fun removeObjectState(lineageId: CloudLineageId, logicalObjectId: RemoteLogicalObjectId): Boolean =
        objects.remove(lineageId.value to logicalObjectId.value) != null
}

private class ResumableDriveTransport : CreateOnlyDriveTransport {
    val received = ArrayList<Byte>()
    var remoteBytes: ByteArray? = null
    var starts = 0
    val queries = mutableListOf<String>()
    var firstOffsetAfterQuery: Long? = null
    var failAfterChunks: Int? = null
    private var chunks = 0
    private var queried = false

    override suspend fun readCurrentUserPermissionId(): String = "unused"
    override suspend fun generateAppDataFileIds(count: Int): List<String> = error("IDs are pre-generated")
    override suspend fun listAppDataFiles(query: String, pageToken: String?, pageSize: Int) = DriveListPage(emptyList(), null)
    override suspend fun createFileIfAbsent(request: DriveCreateRequest) = error("Large uploads are resumable")

    override suspend fun downloadFile(providerFileId: String, destination: File, maximumBytes: Long): DriveDownloadReceipt {
        val bytes = checkNotNull(remoteBytes)
        destination.writeBytes(bytes)
        return DriveDownloadReceipt(bytes.size.toLong())
    }

    override suspend fun startResumableCreate(metadata: DriveFileMetadata, totalBytes: Long): DriveResumableSession {
        starts += 1
        return DriveResumableSession("task14-session")
    }

    override suspend fun queryResumableUpload(sessionUri: String, totalBytes: Long): DriveChunkResult {
        queries += sessionUri
        queried = true
        return DriveChunkResult.ResumeAt(received.size.toLong())
    }

    override suspend fun uploadChunk(
        sessionUri: String,
        firstByte: Long,
        totalBytes: Long,
        content: ByteArray,
    ): DriveChunkResult {
        if (queried && firstOffsetAfterQuery == null) firstOffsetAfterQuery = firstByte
        failAfterChunks?.let { allowed ->
            if (chunks >= allowed) throw DriveTransportException(DriveTransportFailureCategory.RETRYABLE)
        }
        assertEquals(received.size.toLong(), firstByte)
        content.forEach(received::add)
        chunks += 1
        return if (received.size.toLong() == totalBytes) {
            remoteBytes = received.toByteArray()
            DriveChunkResult.Complete
        } else {
            DriveChunkResult.ResumeAt(received.size.toLong())
        }
    }

    override suspend fun deleteFile(providerFileId: String): Boolean = error("Unused")
    override fun close() = Unit
}

private fun ownedBytes(source: ByteArray): OwnedRemoteBytes = object : OwnedRemoteBytes {
    private var bytes: ByteArray? = source
    override val size: Int = source.size
    override fun take(): ByteArray = checkNotNull(bytes).also { bytes = null }
    override fun close() {
        bytes?.fill(0)
        bytes = null
    }
}

private fun ownedFile(source: ByteArray, root: File): OwnedRemoteFile {
    root.mkdirs()
    val file = File.createTempFile("task14-", ".bin", root).also { it.writeBytes(source) }
    return object : OwnedRemoteFile {
        override val file: File = file
        override val length: Long get() = file.length()
        override fun close() {
            file.delete()
        }
    }
}

private fun sha256(source: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(source)
    .joinToString("") { "%02x".format(it) }
