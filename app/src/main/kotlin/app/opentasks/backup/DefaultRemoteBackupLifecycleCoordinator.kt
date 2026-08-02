package app.opentasks.backup

import app.opentasks.backup.drive.AuthorizedDriveSession
import app.opentasks.backup.drive.DriveAuthorizationMode
import app.opentasks.backup.drive.DriveAuthorizationResult
import app.opentasks.backup.drive.GoogleDriveAuthorizationManager
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.backup.CreateOnlyDriveAttachmentBlobStore
import app.opentasks.core.data.backup.DefaultOwnershipChainStore
import app.opentasks.core.data.backup.DefaultPublicationCatalog
import app.opentasks.core.data.backup.OwnershipChainStore
import app.opentasks.core.data.backup.OwnershipClaimCodec
import app.opentasks.core.data.backup.OwnershipClaimCreateResult
import app.opentasks.core.data.backup.OwnershipClaimV1
import app.opentasks.core.data.backup.OwnershipResolution
import app.opentasks.core.data.backup.PublicationCodec
import app.opentasks.core.data.backup.RecoveryEnvelopeStore
import app.opentasks.core.data.backup.RemoteBackupStateStore
import app.opentasks.core.data.backup.RemoteBackupTransferStore
import app.opentasks.core.data.backup.RemoteInventoryItemV1
import app.opentasks.core.data.backup.VerifiedOwnershipClaim
import app.opentasks.core.data.backup.VerifiedPublication
import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.domain.AttachmentBlobStore
import app.opentasks.core.domain.AttachmentListedObject
import app.opentasks.core.domain.BackupWorkScheduler
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.DeleteObjectResult
import app.opentasks.core.domain.LifecycleResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RemoteBackupConfigurator
import app.opentasks.core.domain.RemoteBackupConnectResult
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupLifecycleCoordinator
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipClaimRef
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal enum class TerminalDeletionPhase {
    DELETE_INTENT_STORED,
    TOMBSTONE_ID_STORED,
    TOMBSTONE_CREATED,
    TOMBSTONE_VERIFIED,
    PAYLOAD_CLEANUP,
    ATTACHMENT_CLEANUP,
    CLAIM_CLEANUP,
    COMPLETED,
}

@Serializable
private data class TerminalDeletionStateV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val phase: String,
    val tombstoneProviderFileId: String?,
    val tombstoneClaimId: String?,
    val encodedTombstoneBase64: String?,
    val tombstoneSha256: String? = null,
    val payloadLocalFactsStored: Boolean = false,
    val payloadRoleIndex: Int = 0,
    val payloadRolePageCount: Int = 0,
    val payloadPageToken: String? = null,
    val payloadSeenPageTokens: List<String> = emptyList(),
    val payloadCandidates: List<TerminalPayloadCandidateV1> = emptyList(),
    val payloadInventory: List<TerminalInventoryFactV1> = emptyList(),
    val payloadFinalScan: Boolean = false,
    val payloadFinalScanCompleted: Boolean = false,
    val attachmentPhase: String = AttachmentDeletionPhase.CHUNKS.name,
    val attachmentPageToken: String? = null,
    val attachmentCycleFastPageToken: String? = null,
    val attachmentCycleFastEnded: Boolean = false,
) {
    override fun toString(): String = "TerminalDeletionStateV1(phase=$phase)"
}

private enum class AttachmentDeletionPhase {
    CHUNKS,
    MANIFESTS,
    COMPLETED,
}

@Serializable
private data class AttachmentContentDeletionStateV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val phase: String,
    val pageToken: String? = null,
    val cycleFastPageToken: String? = null,
    val cycleFastEnded: Boolean = false,
) {
    override fun toString(): String = "AttachmentContentDeletionStateV1(phase=$phase)"
}

@Serializable
private data class TerminalPayloadCandidateV1(
    val providerFileId: String,
    val role: RemoteObjectRoleV1,
    val firstObservedAtEpochMillis: Long,
    val authenticatedSha256: String?,
    val localLogicalObjectId: String?,
    val deleted: Boolean = false,
)

@Serializable
private data class TerminalInventoryFactV1(
    val providerFileId: String,
    val role: RemoteObjectRoleV1,
    val frameLength: Long,
    val frameSha256: String,
)

private data class PayloadCleanupResult(
    val state: TerminalDeletionStateV1,
    val failure: LifecycleResult?,
)

private data class TerminalAttachmentCleanupResult(
    val state: TerminalDeletionStateV1,
    val failure: LifecycleResult?,
)

private data class AttachmentPage(
    val objects: List<AttachmentListedObject>,
    val nextPageToken: String?,
    val nextCycleFastPageToken: String?,
    val cycleFastEnded: Boolean,
)

private sealed interface AttachmentPageRead {
    data class Found(val page: AttachmentPage) : AttachmentPageRead
    data class Failed(val reason: RemoteBackupFailureCategory) : AttachmentPageRead
}

private sealed interface RawAttachmentPageRead {
    data class Found(
        val objects: List<AttachmentListedObject>,
        val nextPageToken: String?,
    ) : RawAttachmentPageRead
    data class Failed(val reason: RemoteBackupFailureCategory) : RawAttachmentPageRead
}

private sealed interface AttachmentChunkProbe {
    data object Absent : AttachmentChunkProbe
    data object Present : AttachmentChunkProbe
    data class Failed(val reason: RemoteBackupFailureCategory) : AttachmentChunkProbe
}

private class DeletionBudget(var remaining: Int)

private sealed interface PublicationAuthentication {
    data class Authenticated(val publication: VerifiedPublication) : PublicationAuthentication
    data class Failed(val reason: RemoteBackupFailureCategory) : PublicationAuthentication
    data object Missing : PublicationAuthentication
    data object Unverified : PublicationAuthentication
}

private sealed interface TerminalAuthentication {
    data class Authenticated(val terminal: VerifiedOwnershipClaim) : TerminalAuthentication
    data class Failed(val result: LifecycleResult) : TerminalAuthentication
}

class DefaultRemoteBackupLifecycleCoordinator(
    private val vaultId: VaultId,
    private val crypto: VaultCrypto,
    private val recoveryEnvelopeStore: RecoveryEnvelopeStore,
    private val remoteStateStore: RemoteBackupStateStore,
    private val transferStore: RemoteBackupTransferStore,
    private val scheduler: BackupWorkScheduler,
    private val authorizationManager: GoogleDriveAuthorizationManager,
    private val openObjectStore: (CreateOnlyDriveTransport) -> CreateOnlyBackupObjectStore,
    private val ownershipStore:
        (CreateOnlyBackupObjectStore) -> OwnershipChainStore,
    private val ownershipCodec: OwnershipClaimCodec,
    private val publicationCodec: PublicationCodec,
    private val configurator: RemoteBackupConfigurator,
    private val openAttachmentStore:
        (CreateOnlyDriveTransport, CloudLineageId) -> AttachmentBlobStore =
        { transport, lineageId -> CreateOnlyDriveAttachmentBlobStore(transport, lineageId) },
    private val now: () -> Instant = Instant::now,
    private val newClaimId: () -> OwnershipClaimId = OwnershipClaimId::new,
    private val publicationGate: Mutex = Mutex(),
) : RemoteBackupLifecycleCoordinator {
    private val mutex = Mutex()

    override suspend fun disconnect(): LifecycleResult = mutex.withLock {
        val configurations = try {
            remoteStateStore.configurations(vaultId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@withLock failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        }
        val stored = configurations.singleOrNull {
            it.lifecycle == RemoteBackupLifecycle.ACTIVE ||
                it.lifecycle == RemoteBackupLifecycle.DORMANT
        } ?: return@withLock failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        if (stored.lifecycle == RemoteBackupLifecycle.ACTIVE) {
            val dormant = stored.copy(
                lifecycle = RemoteBackupLifecycle.DORMANT,
                failureCategory = null,
                stateVersion = RemoteBackupStateVersion(stored.stateVersion.value + 1),
            )
            val persisted = try {
                remoteStateStore.compareAndSet(stored.lineageId, stored.stateVersion, dormant)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            if (!persisted) return@withLock failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        }
        try {
            scheduler.cancelAll()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@withLock failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        }

        val digest = stored.accountBindingDigest
        val authorization = try {
            authorizationManager.authorize(DriveAuthorizationMode.NON_INTERACTIVE, digest)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } finally {
            digest.fill(0)
        }
        val session = (authorization as? DriveAuthorizationResult.Authorized)?.session
            ?: return@withLock LifecycleResult.Disconnected(authorizationRevoked = false)
        val revoked = try {
            authorizationManager.revokeAccess(session)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        } finally {
            session.close()
        }
        LifecycleResult.Disconnected(authorizationRevoked = revoked)
    }

    override suspend fun deleteHistory(passphrase: CharArray): LifecycleResult = try {
        publicationGate.withLock {
            mutex.withLock { deleteLocked(passphrase) }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
    } finally {
        passphrase.fill('\u0000')
    }

    override suspend fun deleteAttachmentContent(passphrase: CharArray): LifecycleResult = try {
        publicationGate.withLock {
            mutex.withLock { deleteAttachmentContentLocked(passphrase) }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
    } finally {
        passphrase.fill('\u0000')
    }

    private suspend fun deleteAttachmentContentLocked(passphrase: CharArray): LifecycleResult {
        val configuration = remoteStateStore.configurations(vaultId).singleOrNull {
            it.lifecycle == RemoteBackupLifecycle.ACTIVE
        } ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        val envelope = recoveryEnvelopeStore.get(vaultId)
            ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        val contentKey = try {
            crypto.unlock(passphrase, envelope)
        } catch (cancellation: CancellationException) {
            envelope.clearOwned()
            throw cancellation
        } catch (_: Exception) {
            envelope.clearOwned()
            return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        }
        try {
            val operationId = ATTACHMENT_DELETE_OPERATION_PREFIX + configuration.lineageId.value
            val operation = remoteStateStore.operation(operationId)
            if (operation != null && operation.lineageId != configuration.lineageId) {
                return failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            var state = operation?.let(::decodeAttachmentDeletionState)
            if (operation != null && state == null) {
                return failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            if (state == null) {
                state = AttachmentContentDeletionStateV1(
                    phase = AttachmentDeletionPhase.CHUNKS.name,
                )
                remoteStateStore.putOperation(
                    attachmentDeletionOperation(operationId, configuration, state),
                )
            }

            val authorization = authorize(configuration)
            val session = (authorization as? DriveAuthorizationResult.Authorized)?.session
                ?: return authorizationFailure(authorization)
            return try {
                deleteAttachmentContentAuthorized(
                    operationId,
                    configuration,
                    state,
                    contentKey,
                    session,
                )
            } finally {
                session.close()
            }
        } finally {
            contentKey.close()
            envelope.clearOwned()
        }
    }

    private suspend fun deleteAttachmentContentAuthorized(
        operationId: String,
        configuration: RemoteBackupConfiguration,
        initialState: AttachmentContentDeletionStateV1,
        contentKey: VaultKey,
        session: AuthorizedDriveSession,
    ): LifecycleResult {
        val objectStore = openObjectStore(session.transport)
        val chainStore = ownershipStore(objectStore)
        val expectedTip = when (
            val resolution = chainStore.resolve(configuration.rootClaimProviderId, contentKey)
        ) {
            is OwnershipResolution.Active -> resolution.tip
            is OwnershipResolution.Terminated -> return LifecycleResult.OwnershipRequired
            is OwnershipResolution.Blocked -> return failed(resolution.reason)
        }
        if (!holds(configuration, expectedTip)) {
            markOwnershipLost(configuration)
            return LifecycleResult.OwnershipRequired
        }
        val attachmentStore = openAttachmentStore(session.transport, configuration.lineageId)
        var state = initialState
        val budget = DeletionBudget(MAX_DELETES_PER_BATCH)
        var pages = 0
        while (pages < MAX_ATTACHMENT_PAGES_PER_INVOCATION) {
            val phase = AttachmentDeletionPhase.valueOf(state.phase)
            if (phase == AttachmentDeletionPhase.COMPLETED) {
                state = transitionAttachmentDeletion(
                    operationId,
                    configuration,
                    state,
                    state.copy(
                        phase = AttachmentDeletionPhase.CHUNKS.name,
                        pageToken = null,
                        cycleFastPageToken = null,
                        cycleFastEnded = false,
                    ),
                ) ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
                continue
            }
            val read = readAttachmentPage(
                attachmentStore,
                state.pageToken,
                state.cycleFastPageToken,
                state.cycleFastEnded,
            )
            val page = when (read) {
                is AttachmentPageRead.Found -> read.page
                is AttachmentPageRead.Failed -> return failed(read.reason)
            }
            pages += 1
            if (phase == AttachmentDeletionPhase.MANIFESTS &&
                page.objects.any { it.role == ATTACHMENT_CHUNK_ROLE }
            ) {
                state = transitionAttachmentDeletion(
                    operationId,
                    configuration,
                    state,
                    state.copy(
                        phase = AttachmentDeletionPhase.CHUNKS.name,
                        pageToken = null,
                        cycleFastPageToken = null,
                        cycleFastEnded = false,
                    ),
                ) ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
                continue
            }
            val role = if (phase == AttachmentDeletionPhase.CHUNKS) {
                ATTACHMENT_CHUNK_ROLE
            } else {
                ATTACHMENT_MANIFEST_ROLE
            }
            val targets = page.objects.filter { it.role == role }
            if (targets.isEmpty()) {
                val next = if (page.nextPageToken != null) {
                    state.copy(
                        pageToken = page.nextPageToken,
                        cycleFastPageToken = page.nextCycleFastPageToken,
                        cycleFastEnded = page.cycleFastEnded,
                    )
                } else {
                    state.copy(
                        phase = if (phase == AttachmentDeletionPhase.CHUNKS) {
                            AttachmentDeletionPhase.MANIFESTS.name
                        } else {
                            AttachmentDeletionPhase.COMPLETED.name
                        },
                        pageToken = null,
                        cycleFastPageToken = null,
                        cycleFastEnded = false,
                    )
                }
                state = transitionAttachmentDeletion(
                    operationId,
                    configuration,
                    state,
                    next,
                ) ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
                if (next.phase == AttachmentDeletionPhase.COMPLETED.name) {
                    return LifecycleResult.AttachmentContentDeleted
                }
                continue
            }
            if (budget.remaining == 0) {
                return failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
            }
            when (
                val resolution = chainStore.resolve(
                    configuration.rootClaimProviderId,
                    contentKey,
                )
            ) {
                is OwnershipResolution.Active -> if (
                    resolution.tip.completeSha256 != expectedTip.completeSha256
                ) {
                    markOwnershipLost(configuration)
                    return LifecycleResult.OwnershipRequired
                }
                is OwnershipResolution.Terminated -> return LifecycleResult.OwnershipRequired
                is OwnershipResolution.Blocked -> return failed(resolution.reason)
            }
            state = transitionAttachmentDeletion(
                operationId,
                configuration,
                state,
                state.copy(
                    phase = if (phase == AttachmentDeletionPhase.MANIFESTS) {
                        AttachmentDeletionPhase.CHUNKS.name
                    } else {
                        state.phase
                    },
                    pageToken = null,
                    cycleFastPageToken = null,
                    cycleFastEnded = false,
                ),
            ) ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            if (phase == AttachmentDeletionPhase.MANIFESTS) {
                when (val probe = probeForAttachmentChunks(attachmentStore)) {
                    AttachmentChunkProbe.Absent -> Unit
                    AttachmentChunkProbe.Present -> continue
                    is AttachmentChunkProbe.Failed -> return failed(probe.reason)
                }
            }
            val batch = targets.take(budget.remaining)
            for (target in batch) {
                if (!deleteAttachment(attachmentStore, target.providerObjectId)) {
                    return failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
                }
                budget.remaining -= 1
            }
            if (targets.size > batch.size || budget.remaining == 0) {
                return failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
            }
        }
        return failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
    }

    private suspend fun deleteLocked(passphrase: CharArray): LifecycleResult {
        var configuration = remoteStateStore.configurations(vaultId).singleOrNull {
            it.lifecycle == RemoteBackupLifecycle.ACTIVE ||
                it.lifecycle == RemoteBackupLifecycle.DELETING ||
                it.lifecycle == RemoteBackupLifecycle.TERMINATED
        } ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        val envelope = recoveryEnvelopeStore.get(vaultId)
            ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        val contentKey = try {
            crypto.unlock(passphrase, envelope)
        } catch (cancellation: CancellationException) {
            envelope.clearOwned()
            throw cancellation
        } catch (_: Exception) {
            envelope.clearOwned()
            return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        }
        try {
            val operationId = DELETE_OPERATION_PREFIX + vaultId.value
            var operation = remoteStateStore.operation(operationId)
            var state = operation?.let(::decodeDeletionState)
            if (operation != null && state == null) {
                return failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            if (configuration.lifecycle == RemoteBackupLifecycle.TERMINATED &&
                operation?.phase == TerminalDeletionPhase.COMPLETED.name &&
                state?.payloadFinalScanCompleted == true &&
                state.attachmentPhase == AttachmentDeletionPhase.COMPLETED.name
            ) {
                return LifecycleResult.HistoryDeleted
            }
            if (configuration.lifecycle == RemoteBackupLifecycle.ACTIVE) {
                val deleting = configuration.copy(
                    lifecycle = RemoteBackupLifecycle.DELETING,
                    failureCategory = null,
                    stateVersion = RemoteBackupStateVersion(
                        configuration.stateVersion.value + 1,
                    ),
                )
                if (!remoteStateStore.compareAndSet(
                        configuration.lineageId,
                        configuration.stateVersion,
                        deleting,
                    )
                ) {
                    return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
                }
                configuration = deleting
            }
            scheduler.cancelAll()
            if (state == null) {
                state = TerminalDeletionStateV1(
                    phase = TerminalDeletionPhase.DELETE_INTENT_STORED.name,
                    tombstoneProviderFileId = null,
                    tombstoneClaimId = null,
                    encodedTombstoneBase64 = null,
                )
                operation = deletionOperation(operationId, configuration, state)
                remoteStateStore.putOperation(operation)
            }

            val authorization = authorize(configuration)
            val session = (authorization as? DriveAuthorizationResult.Authorized)?.session
                ?: return authorizationFailure(authorization)
            return try {
                deleteAuthorized(
                    operationId,
                    configuration,
                    checkNotNull(state),
                    contentKey,
                    session,
                )
            } finally {
                session.close()
            }
        } finally {
            contentKey.close()
            envelope.clearOwned()
        }
    }

    private suspend fun deleteAuthorized(
        operationId: String,
        configuration: RemoteBackupConfiguration,
        initialState: TerminalDeletionStateV1,
        contentKey: VaultKey,
        session: AuthorizedDriveSession,
    ): LifecycleResult {
        val objectStore = openObjectStore(session.transport)
        val chainStore = ownershipStore(objectStore)
        var state = initialState
        var resolution: OwnershipResolution? = null
        var predecessor: VerifiedOwnershipClaim? = null
        var tombstone: VerifiedOwnershipClaim? = null
        if (!reached(state, TerminalDeletionPhase.TOMBSTONE_VERIFIED)) {
            resolution = chainStore.resolve(configuration.rootClaimProviderId, contentKey)
            predecessor = (resolution as? OwnershipResolution.Active)?.tip
            tombstone = (resolution as? OwnershipResolution.Terminated)?.tombstone

            if (predecessor != null && !holds(configuration, checkNotNull(predecessor))) {
                markOwnershipLost(configuration)
                return LifecycleResult.OwnershipRequired
            }
            if (predecessor == null && tombstone == null) {
                return failed(
                    (resolution as? OwnershipResolution.Blocked)?.reason
                        ?: RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
                )
            }
        }

        var encodedTombstone: ByteArray? = null
        try {
            if (!reached(state, TerminalDeletionPhase.TOMBSTONE_ID_STORED)) {
                val tip = predecessor ?: return LifecycleResult.OwnershipRequired
                val providerId = checkNotNull(configuration.nextSuccessorProviderId)
                if (tip.claim.nextSuccessorProviderFileId != providerId.value) {
                    markOwnershipLost(configuration)
                    return LifecycleResult.OwnershipRequired
                }
                val claimId = newClaimId()
                val claim = terminalClaim(tip, providerId, claimId, operationId)
                encodedTombstone = ownershipCodec.encode(claim, contentKey)
                val authenticatedTombstone = ownershipCodec.verify(
                    checkNotNull(encodedTombstone),
                    contentKey,
                )
                state = transition(
                    operationId,
                    configuration,
                    state.copy(
                        tombstoneProviderFileId = providerId.value,
                        tombstoneClaimId = claimId.value,
                        tombstoneSha256 = authenticatedTombstone.completeSha256.value,
                        encodedTombstoneBase64 = Base64.getEncoder().withoutPadding()
                            .encodeToString(checkNotNull(encodedTombstone)),
                    ),
                    TerminalDeletionPhase.TOMBSTONE_ID_STORED,
                ) ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            } else {
                encodedTombstone = decodeBytes(state.encodedTombstoneBase64)
                    ?: return failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            val expectedTombstone = ownershipCodec.verify(
                checkNotNull(encodedTombstone),
                contentKey,
            )
            if (state.tombstoneSha256 != expectedTombstone.completeSha256.value) {
                return failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }

            if (reached(state, TerminalDeletionPhase.TOMBSTONE_VERIFIED)) {
                when (
                    val authentication = authenticateExpectedTerminal(
                        expectedTombstone,
                        state,
                        objectStore,
                        contentKey,
                    )
                ) {
                    is TerminalAuthentication.Authenticated -> {
                        tombstone = authentication.terminal
                    }
                    is TerminalAuthentication.Failed -> return authentication.result
                }
            }

            if (!reached(state, TerminalDeletionPhase.TOMBSTONE_CREATED)) {
                val tip = predecessor
                if (tip == null) {
                    if (
                        tombstone == null || !isExpectedTerminal(
                            checkNotNull(tombstone),
                            expectedTombstone,
                            state,
                        )
                    ) {
                        markOwnershipLost(configuration)
                        return LifecycleResult.OwnershipRequired
                    }
                } else {
                    when (
                        val created = chainStore.createClaim(
                            tip,
                            ownedCopy(checkNotNull(encodedTombstone)),
                            contentKey,
                        )
                    ) {
                        is OwnershipClaimCreateResult.Won -> tombstone = created.claim
                        is OwnershipClaimCreateResult.Lost -> {
                            if (
                                !isExpectedTerminal(
                                    created.winner,
                                    expectedTombstone,
                                    state,
                                )
                            ) {
                                markOwnershipLost(configuration)
                                return LifecycleResult.OwnershipRequired
                            }
                            tombstone = created.winner
                        }
                        OwnershipClaimCreateResult.AmbiguousRemoteState ->
                            return failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
                        is OwnershipClaimCreateResult.Failed -> return failed(created.reason)
                    }
                }
                state = transition(
                    operationId,
                    configuration,
                    state,
                    TerminalDeletionPhase.TOMBSTONE_CREATED,
                ) ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }

            if (!reached(state, TerminalDeletionPhase.TOMBSTONE_VERIFIED)) {
                resolution = chainStore.resolve(configuration.rootClaimProviderId, contentKey)
                val verified = (resolution as? OwnershipResolution.Terminated)?.tombstone
                    ?: run {
                        if (resolution is OwnershipResolution.Active) {
                            markOwnershipLost(configuration)
                            return LifecycleResult.OwnershipRequired
                        }
                        return failed(
                            (resolution as? OwnershipResolution.Blocked)?.reason
                                ?: RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
                        )
                    }
                if (!isExpectedTerminal(verified, expectedTombstone, state)) {
                    return failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
                }
                tombstone = verified
                state = transition(
                    operationId,
                    configuration,
                    state,
                    TerminalDeletionPhase.TOMBSTONE_VERIFIED,
                ) ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }

            if (!reached(state, TerminalDeletionPhase.PAYLOAD_CLEANUP)) {
                state = transition(
                    operationId,
                    configuration,
                    state,
                    TerminalDeletionPhase.PAYLOAD_CLEANUP,
                ) ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            val deletionBudget = DeletionBudget(MAX_DELETES_PER_BATCH)
            val payloadCleanup = cleanupPayloadBatch(
                operationId = operationId,
                configuration = configuration,
                initialState = state,
                objectStore = objectStore,
                contentKey = contentKey,
                deletionBudget = deletionBudget,
                authenticateTerminal = {
                    authenticateExpectedTerminalResult(
                        expectedTombstone,
                        state,
                        objectStore,
                        contentKey,
                    )
                },
            )
            state = payloadCleanup.state
            payloadCleanup.failure?.let { return it }

            if (!reached(state, TerminalDeletionPhase.ATTACHMENT_CLEANUP)) {
                state = transition(
                    operationId,
                    configuration,
                    state,
                    TerminalDeletionPhase.ATTACHMENT_CLEANUP,
                ) ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            val attachmentCleanup = cleanupTerminalAttachmentBatch(
                operationId = operationId,
                configuration = configuration,
                initialState = state,
                store = openAttachmentStore(session.transport, configuration.lineageId),
                deletionBudget = deletionBudget,
                authenticateTerminal = {
                    authenticateExpectedTerminalResult(
                        expectedTombstone,
                        state,
                        objectStore,
                        contentKey,
                    )
                },
            )
            state = attachmentCleanup.state
            attachmentCleanup.failure?.let { return it }

            if (!reached(state, TerminalDeletionPhase.CLAIM_CLEANUP)) {
                state = transition(
                    operationId,
                    configuration,
                    state,
                    TerminalDeletionPhase.CLAIM_CLEANUP,
                ) ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            cleanupKnownClaims(
                configuration,
                ProviderObjectId.of(checkNotNull(state.tombstoneProviderFileId)),
                objectStore,
                deletionBudget,
                authenticateTerminal = {
                    authenticateExpectedTerminalResult(
                        expectedTombstone,
                        state,
                        objectStore,
                        contentKey,
                    )
                },
            )?.let { return it }

            val terminal = tombstone
                ?: return failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            var latest = remoteStateStore.known(configuration.lineageId)
                ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            if (latest.lifecycle != RemoteBackupLifecycle.TERMINATED) {
                val terminated = latest.copy(
                    lifecycle = RemoteBackupLifecycle.TERMINATED,
                    activeDeviceId = null,
                    writerEpoch = WriterEpoch(terminal.claim.writerEpoch),
                    ownershipClaim = terminal.ref(),
                    nextSuccessorProviderId = null,
                    currentPublication = null,
                    previousPublication = null,
                    lastVerifiedGeneration = null,
                    lastVerifiedAt = null,
                    failureCategory = null,
                    stateVersion = RemoteBackupStateVersion(latest.stateVersion.value + 1),
                )
                if (!remoteStateStore.compareAndSet(
                        latest.lineageId,
                        latest.stateVersion,
                        terminated,
                    )
                ) return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
                latest = terminated
            }
            if (!reached(state, TerminalDeletionPhase.COMPLETED)) {
                transition(
                    operationId,
                    latest,
                    state,
                    TerminalDeletionPhase.COMPLETED,
                ) ?: return failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            return LifecycleResult.HistoryDeleted
        } finally {
            encodedTombstone?.fill(0)
        }
    }

    private suspend fun cleanupPayloadBatch(
        operationId: String,
        configuration: RemoteBackupConfiguration,
        initialState: TerminalDeletionStateV1,
        objectStore: CreateOnlyBackupObjectStore,
        contentKey: VaultKey,
        deletionBudget: DeletionBudget,
        authenticateTerminal: suspend () -> LifecycleResult?,
    ): PayloadCleanupResult {
        var state = initialState
        if (!state.payloadLocalFactsStored) {
            val local = transferStore.objectsForLineage(configuration.lineageId)
                .filter { it.role in PAYLOAD_ROLES }
            if (local.size > MAX_PAYLOAD_CANDIDATES) {
                return payloadFailure(state, RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            var candidates = state.payloadCandidates
            var inventory = state.payloadInventory
            for (record in local) {
                candidates = mergeCandidate(
                    candidates,
                    TerminalPayloadCandidateV1(
                        providerFileId = record.providerObjectId.value,
                        role = record.role,
                        firstObservedAtEpochMillis = record.createdAt.toEpochMilli(),
                        authenticatedSha256 = record.frameSha256.value
                            .takeIf { record.verifiedAt != null },
                        localLogicalObjectId = record.logicalObjectId.value,
                    ),
                ) ?: return payloadFailure(
                    state,
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
                if (record.verifiedAt != null && record.role != RemoteObjectRoleV1.PUBLICATION) {
                    inventory = mergeInventory(
                        inventory,
                        TerminalInventoryFactV1(
                            providerFileId = record.providerObjectId.value,
                            role = record.role,
                            frameLength = record.frameLength,
                            frameSha256 = record.frameSha256.value,
                        ),
                    ) ?: return payloadFailure(
                        state,
                        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                    )
                }
            }
            state = checkpointDeletion(
                operationId,
                configuration,
                state.copy(
                    payloadLocalFactsStored = true,
                    payloadCandidates = candidates,
                    payloadInventory = inventory,
                ),
            ) ?: return payloadFailure(state, RemoteBackupFailureCategory.LOCAL_STORAGE)
        }

        if (state.payloadFinalScanCompleted) return PayloadCleanupResult(state, null)

        while (true) {
            while (state.payloadRoleIndex < PAYLOAD_ROLES.size) {
                if (state.payloadRolePageCount >= MAX_PAYLOAD_PAGES_PER_ROLE) {
                    return payloadFailure(
                        state,
                        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                    )
                }
                val role = PAYLOAD_ROLES[state.payloadRoleIndex]
                val page = try {
                    objectStore.list(
                        RemoteListRequest(
                            lineageId = configuration.lineageId,
                            role = role,
                            writerEpoch = null,
                            ownerDeviceId = null,
                            pageToken = state.payloadPageToken,
                            pageSize = PAYLOAD_PAGE_SIZE,
                        ),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return payloadFailure(state, RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
                }
                if (page.objects.size > PAYLOAD_PAGE_SIZE) {
                    return payloadFailure(
                        state,
                        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                    )
                }
                var candidates = state.payloadCandidates
                var inventory = state.payloadInventory
                for (listed in page.objects.filter { it.role == role }) {
                    if (candidates.none { it.providerFileId == listed.providerObjectId.value } &&
                        candidates.size >= MAX_PAYLOAD_CANDIDATES
                    ) {
                        return payloadFailure(
                            state,
                            RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                        )
                    }
                    var authenticatedSha256: String? = inventory.firstOrNull {
                        it.providerFileId == listed.providerObjectId.value && it.role == role
                    }?.frameSha256
                    if (role == RemoteObjectRoleV1.PUBLICATION) {
                        when (
                            val authentication = authenticatePublication(
                                listed.providerObjectId,
                                configuration,
                                objectStore,
                                contentKey,
                            )
                        ) {
                            is PublicationAuthentication.Authenticated -> {
                                authenticatedSha256 = authentication.publication.completeSha256.value
                                candidates = mergeCandidate(
                                    candidates,
                                    TerminalPayloadCandidateV1(
                                        providerFileId = listed.providerObjectId.value,
                                        role = role,
                                        firstObservedAtEpochMillis = now().toEpochMilli(),
                                        authenticatedSha256 = authenticatedSha256,
                                        localLogicalObjectId = null,
                                    ),
                                ) ?: return payloadFailure(
                                    state,
                                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                                )
                                for (item in authentication.publication.manifest.inventory) {
                                    val fact = item.toTerminalFact()
                                    inventory = mergeInventory(inventory, fact)
                                        ?: return payloadFailure(
                                            state,
                                            RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                                        )
                                    candidates = mergeCandidate(
                                        candidates,
                                        TerminalPayloadCandidateV1(
                                            providerFileId = fact.providerFileId,
                                            role = fact.role,
                                            firstObservedAtEpochMillis = now().toEpochMilli(),
                                            authenticatedSha256 = fact.frameSha256,
                                            localLogicalObjectId = null,
                                        ),
                                    ) ?: return payloadFailure(
                                        state,
                                        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                                    )
                                    if (inventory.size > MAX_PAYLOAD_CANDIDATES ||
                                        candidates.size > MAX_PAYLOAD_CANDIDATES
                                    ) {
                                        return payloadFailure(
                                            state,
                                            RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                                        )
                                    }
                                }
                            }

                            PublicationAuthentication.Missing -> continue
                            PublicationAuthentication.Unverified -> Unit
                            is PublicationAuthentication.Failed ->
                                return payloadFailure(state, authentication.reason)
                        }
                    }
                    candidates = mergeCandidate(
                        candidates,
                        TerminalPayloadCandidateV1(
                            providerFileId = listed.providerObjectId.value,
                            role = role,
                            firstObservedAtEpochMillis = now().toEpochMilli(),
                            authenticatedSha256 = authenticatedSha256,
                            localLogicalObjectId = null,
                        ),
                    ) ?: return payloadFailure(
                        state,
                        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                    )
                }
                val nextToken = page.nextPageToken
                if (nextToken != null &&
                    (nextToken.length > MAX_PAGE_TOKEN_CHARACTERS ||
                        nextToken in state.payloadSeenPageTokens)
                ) {
                    return payloadFailure(
                        state,
                        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                    )
                }
                val roleComplete = nextToken == null
                state = checkpointDeletion(
                    operationId,
                    configuration,
                    state.copy(
                        payloadRoleIndex = if (roleComplete) {
                            state.payloadRoleIndex + 1
                        } else {
                            state.payloadRoleIndex
                        },
                        payloadRolePageCount = if (roleComplete) {
                            0
                        } else {
                            state.payloadRolePageCount + 1
                        },
                        payloadPageToken = nextToken,
                        payloadSeenPageTokens = if (roleComplete) {
                            emptyList()
                        } else {
                            state.payloadSeenPageTokens + checkNotNull(nextToken)
                        },
                        payloadCandidates = candidates,
                        payloadInventory = inventory,
                    ),
                ) ?: return payloadFailure(state, RemoteBackupFailureCategory.LOCAL_STORAGE)
            }

            val trustedPublications = listOfNotNull(
                configuration.currentPublication,
                configuration.previousPublication,
            )
            if (trustedPublications.any { trusted ->
                    state.payloadCandidates.none {
                        it.role == RemoteObjectRoleV1.PUBLICATION &&
                            it.providerFileId == trusted.providerId.value &&
                            it.authenticatedSha256 == trusted.sha256.value
                    }
                }
            ) {
                return payloadFailure(
                    state,
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            }

            val cutoff = now().minus(MINIMUM_RESIDUE_AGE)
            val eligible = state.payloadCandidates
                .filter {
                    !it.deleted && (
                        it.authenticatedSha256 != null ||
                            Instant.ofEpochMilli(it.firstObservedAtEpochMillis) <= cutoff
                    )
                }
                .sortedBy(TerminalPayloadCandidateV1::firstObservedAtEpochMillis)
            if (state.payloadFinalScan) {
                if (eligible.isNotEmpty()) {
                    state = checkpointDeletion(
                        operationId,
                        configuration,
                        state.copy(payloadFinalScan = false),
                    ) ?: return payloadFailure(
                        state,
                        RemoteBackupFailureCategory.LOCAL_STORAGE,
                    )
                    return payloadFailure(
                        state,
                        RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
                    )
                }
                state = checkpointDeletion(
                    operationId,
                    configuration,
                    state.copy(payloadFinalScanCompleted = true),
                ) ?: return payloadFailure(
                    state,
                    RemoteBackupFailureCategory.LOCAL_STORAGE,
                )
                return PayloadCleanupResult(state, null)
            }
            if (eligible.isNotEmpty()) {
                authenticateTerminal()?.let { return PayloadCleanupResult(state, it) }
            }
            val batch = eligible.take(deletionBudget.remaining)
            for (candidate in batch) {
                when (val deletion = objectStore.delete(ProviderObjectId.of(candidate.providerFileId))) {
                    DeleteObjectResult.Deleted, DeleteObjectResult.Missing -> Unit
                    is DeleteObjectResult.Failed -> return payloadFailure(state, deletion.reason)
                }
                deletionBudget.remaining -= 1
                candidate.localLogicalObjectId?.let { logicalId ->
                    transferStore.removeObjectState(
                        configuration.lineageId,
                        app.opentasks.core.model.RemoteLogicalObjectId.of(logicalId),
                    )
                }
                state = checkpointDeletion(
                    operationId,
                    configuration,
                    state.copy(
                        payloadCandidates = state.payloadCandidates.map {
                            if (it.providerFileId == candidate.providerFileId) {
                                it.copy(deleted = true)
                            } else {
                                it
                            }
                        },
                    ),
                ) ?: return payloadFailure(state, RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            if (eligible.size > batch.size) {
                return payloadFailure(state, RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
            }
            state = checkpointDeletion(
                operationId,
                configuration,
                state.copy(
                    payloadRoleIndex = 0,
                    payloadRolePageCount = 0,
                    payloadPageToken = null,
                    payloadSeenPageTokens = emptyList(),
                    payloadFinalScan = true,
                    payloadFinalScanCompleted = false,
                ),
            ) ?: return payloadFailure(state, RemoteBackupFailureCategory.LOCAL_STORAGE)
        }
    }

    private suspend fun authenticatePublication(
        providerObjectId: ProviderObjectId,
        configuration: RemoteBackupConfiguration,
        objectStore: CreateOnlyBackupObjectStore,
        contentKey: VaultKey,
    ): PublicationAuthentication = when (
        val read = objectStore.readSmall(
            providerObjectId,
            DefaultPublicationCatalog.MAX_PUBLICATION_FILE_BYTES,
        )
    ) {
        ReadSmallResult.Missing -> PublicationAuthentication.Missing
        is ReadSmallResult.Failed -> PublicationAuthentication.Failed(read.reason)
        is ReadSmallResult.Found -> {
            val owned = read.bytes
            val bytes = owned.take()
            try {
                val publication = try {
                    publicationCodec.verify(bytes, contentKey)
                } catch (_: Exception) {
                    null
                }
                if (publication != null &&
                    publication.manifest.lineageId == configuration.lineageId.value &&
                    publication.manifest.publicationProviderFileId == providerObjectId.value
                ) {
                    PublicationAuthentication.Authenticated(publication)
                } else {
                    PublicationAuthentication.Unverified
                }
            } finally {
                bytes.fill(0)
                owned.close()
            }
        }
    }

    private suspend fun cleanupTerminalAttachmentBatch(
        operationId: String,
        configuration: RemoteBackupConfiguration,
        initialState: TerminalDeletionStateV1,
        store: AttachmentBlobStore,
        deletionBudget: DeletionBudget,
        authenticateTerminal: suspend () -> LifecycleResult?,
    ): TerminalAttachmentCleanupResult {
        var state = initialState
        var pages = 0
        while (pages < MAX_ATTACHMENT_PAGES_PER_INVOCATION) {
            val phase = AttachmentDeletionPhase.valueOf(state.attachmentPhase)
            if (phase == AttachmentDeletionPhase.COMPLETED) {
                return TerminalAttachmentCleanupResult(state, null)
            }
            val read = readAttachmentPage(
                store,
                state.attachmentPageToken,
                state.attachmentCycleFastPageToken,
                state.attachmentCycleFastEnded,
            )
            val page = when (read) {
                is AttachmentPageRead.Found -> read.page
                is AttachmentPageRead.Failed -> return TerminalAttachmentCleanupResult(
                    state,
                    failed(read.reason),
                )
            }
            pages += 1
            if (phase == AttachmentDeletionPhase.MANIFESTS &&
                page.objects.any { it.role == ATTACHMENT_CHUNK_ROLE }
            ) {
                state = checkpointDeletion(
                    operationId,
                    configuration,
                    state.copy(
                        attachmentPhase = AttachmentDeletionPhase.CHUNKS.name,
                        attachmentPageToken = null,
                        attachmentCycleFastPageToken = null,
                        attachmentCycleFastEnded = false,
                    ),
                ) ?: return TerminalAttachmentCleanupResult(
                    state,
                    failed(RemoteBackupFailureCategory.LOCAL_STORAGE),
                )
                continue
            }
            val role = if (phase == AttachmentDeletionPhase.CHUNKS) {
                ATTACHMENT_CHUNK_ROLE
            } else {
                ATTACHMENT_MANIFEST_ROLE
            }
            val targets = page.objects.filter { it.role == role }
            if (targets.isEmpty()) {
                val next = if (page.nextPageToken != null) {
                    state.copy(
                        attachmentPageToken = page.nextPageToken,
                        attachmentCycleFastPageToken = page.nextCycleFastPageToken,
                        attachmentCycleFastEnded = page.cycleFastEnded,
                    )
                } else {
                    state.copy(
                        attachmentPhase = if (phase == AttachmentDeletionPhase.CHUNKS) {
                            AttachmentDeletionPhase.MANIFESTS.name
                        } else {
                            AttachmentDeletionPhase.COMPLETED.name
                        },
                        attachmentPageToken = null,
                        attachmentCycleFastPageToken = null,
                        attachmentCycleFastEnded = false,
                    )
                }
                state = checkpointDeletion(
                    operationId,
                    configuration,
                    next,
                ) ?: return TerminalAttachmentCleanupResult(
                    state,
                    failed(RemoteBackupFailureCategory.LOCAL_STORAGE),
                )
                if (next.attachmentPhase == AttachmentDeletionPhase.COMPLETED.name) {
                    return TerminalAttachmentCleanupResult(state, null)
                }
                continue
            }
            if (deletionBudget.remaining == 0) {
                return TerminalAttachmentCleanupResult(
                    state,
                    failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
                )
            }
            authenticateTerminal()?.let {
                return TerminalAttachmentCleanupResult(state, it)
            }
            state = checkpointDeletion(
                operationId,
                configuration,
                state.copy(
                    attachmentPhase = if (phase == AttachmentDeletionPhase.MANIFESTS) {
                        AttachmentDeletionPhase.CHUNKS.name
                    } else {
                        state.attachmentPhase
                    },
                    attachmentPageToken = null,
                    attachmentCycleFastPageToken = null,
                    attachmentCycleFastEnded = false,
                ),
            ) ?: return TerminalAttachmentCleanupResult(
                state,
                failed(RemoteBackupFailureCategory.LOCAL_STORAGE),
            )
            if (phase == AttachmentDeletionPhase.MANIFESTS) {
                when (val probe = probeForAttachmentChunks(store)) {
                    AttachmentChunkProbe.Absent -> Unit
                    AttachmentChunkProbe.Present -> continue
                    is AttachmentChunkProbe.Failed -> return TerminalAttachmentCleanupResult(
                        state,
                        failed(probe.reason),
                    )
                }
            }
            val batch = targets.take(deletionBudget.remaining)
            for (target in batch) {
                if (!deleteAttachment(store, target.providerObjectId)) {
                    return TerminalAttachmentCleanupResult(
                        state,
                        failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
                    )
                }
                deletionBudget.remaining -= 1
            }
            if (targets.size > batch.size || deletionBudget.remaining == 0) {
                return TerminalAttachmentCleanupResult(
                    state,
                    failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
                )
            }
        }
        return TerminalAttachmentCleanupResult(
            state,
            failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
        )
    }

    private suspend fun readAttachmentPage(
        store: AttachmentBlobStore,
        pageToken: String?,
        cycleFastPageToken: String?,
        cycleFastEnded: Boolean,
    ): AttachmentPageRead {
        val main = when (val read = readRawAttachmentPage(store, pageToken)) {
            is RawAttachmentPageRead.Found -> read
            is RawAttachmentPageRead.Failed -> return AttachmentPageRead.Failed(read.reason)
        }
        if (cycleFastEnded) {
            return AttachmentPageRead.Found(
                AttachmentPage(
                    main.objects,
                    main.nextPageToken,
                    null,
                    true,
                ),
            )
        }
        val fastFirst = if (cycleFastPageToken == pageToken) {
            main
        } else {
            when (val read = readRawAttachmentPage(store, cycleFastPageToken)) {
                is RawAttachmentPageRead.Found -> read
                is RawAttachmentPageRead.Failed -> return AttachmentPageRead.Failed(read.reason)
            }
        }
        val fastFirstNext = fastFirst.nextPageToken
            ?: return AttachmentPageRead.Found(
                AttachmentPage(main.objects, main.nextPageToken, null, true),
            )
        val fastSecond = if (fastFirstNext == pageToken) {
            main
        } else {
            when (val read = readRawAttachmentPage(store, fastFirstNext)) {
                is RawAttachmentPageRead.Found -> read
                is RawAttachmentPageRead.Failed -> return AttachmentPageRead.Failed(read.reason)
            }
        }
        val fastNext = fastSecond.nextPageToken
            ?: return AttachmentPageRead.Found(
                AttachmentPage(main.objects, main.nextPageToken, null, true),
            )
        if (main.nextPageToken != null && main.nextPageToken == fastNext) {
            return AttachmentPageRead.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
        }
        return AttachmentPageRead.Found(
            AttachmentPage(
                main.objects,
                main.nextPageToken,
                fastNext,
                false,
            ),
        )
    }

    private suspend fun readRawAttachmentPage(
        store: AttachmentBlobStore,
        pageToken: String?,
        exactRole: String? = null,
    ): RawAttachmentPageRead {
        val page = try {
            store.listNamespace(pageToken, exactRole)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return RawAttachmentPageRead.Failed(
                RemoteBackupFailureCategory.RETRYABLE_PROVIDER,
            )
        }
        if (page.first.size > ATTACHMENT_PAGE_SIZE ||
            !attachmentRowsAreUnderstood(page.first) ||
            (exactRole != null && page.first.any { it.role != exactRole })
        ) {
            return RawAttachmentPageRead.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
        }
        return RawAttachmentPageRead.Found(page.first, page.second)
    }

    private suspend fun probeForAttachmentChunks(
        store: AttachmentBlobStore,
    ): AttachmentChunkProbe {
        val seenTokens = mutableSetOf<String>()
        var token: String? = null
        repeat(MAX_ATTACHMENT_ROLE_PROBE_PAGES) {
            val page = when (
                val read = readRawAttachmentPage(store, token, ATTACHMENT_CHUNK_ROLE)
            ) {
                is RawAttachmentPageRead.Found -> read
                is RawAttachmentPageRead.Failed -> return AttachmentChunkProbe.Failed(read.reason)
            }
            if (page.objects.isNotEmpty()) return AttachmentChunkProbe.Present
            token = page.nextPageToken ?: return AttachmentChunkProbe.Absent
            if (!seenTokens.add(token)) {
                return AttachmentChunkProbe.Failed(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            }
        }
        return AttachmentChunkProbe.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
    }

    private fun attachmentRowsAreUnderstood(objects: List<AttachmentListedObject>): Boolean =
        objects.all {
            (it.role == ATTACHMENT_CHUNK_ROLE || it.role == ATTACHMENT_MANIFEST_ROLE) &&
                !it.blobSetId.isNullOrEmpty()
        } && objects.map { it.providerObjectId }.distinct().size == objects.size

    private suspend fun deleteAttachment(
        store: AttachmentBlobStore,
        providerObjectId: ProviderObjectId,
    ): Boolean = try {
        store.delete(providerObjectId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }

    private fun mergeCandidate(
        current: List<TerminalPayloadCandidateV1>,
        incoming: TerminalPayloadCandidateV1,
    ): List<TerminalPayloadCandidateV1>? {
        val index = current.indexOfFirst { it.providerFileId == incoming.providerFileId }
        if (index < 0) return current + incoming
        val existing = current[index]
        if (existing.role != incoming.role ||
            existing.authenticatedSha256 != null && incoming.authenticatedSha256 != null &&
            existing.authenticatedSha256 != incoming.authenticatedSha256 ||
            existing.localLogicalObjectId != null && incoming.localLogicalObjectId != null &&
            existing.localLogicalObjectId != incoming.localLogicalObjectId
        ) {
            return null
        }
        val merged = existing.copy(
            firstObservedAtEpochMillis = minOf(
                existing.firstObservedAtEpochMillis,
                incoming.firstObservedAtEpochMillis,
            ),
            authenticatedSha256 = existing.authenticatedSha256
                ?: incoming.authenticatedSha256,
            localLogicalObjectId = existing.localLogicalObjectId
                ?: incoming.localLogicalObjectId,
            deleted = existing.deleted || incoming.deleted,
        )
        return current.toMutableList().also { it[index] = merged }
    }

    private fun mergeInventory(
        current: List<TerminalInventoryFactV1>,
        incoming: TerminalInventoryFactV1,
    ): List<TerminalInventoryFactV1>? {
        val existing = current.firstOrNull { it.providerFileId == incoming.providerFileId }
            ?: return current + incoming
        return current.takeIf { existing == incoming }
    }

    private fun RemoteInventoryItemV1.toTerminalFact() = TerminalInventoryFactV1(
        providerFileId = providerFileId,
        role = role,
        frameLength = frameLength,
        frameSha256 = frameSha256,
    )

    private suspend fun checkpointDeletion(
        operationId: String,
        configuration: RemoteBackupConfiguration,
        state: TerminalDeletionStateV1,
    ): TerminalDeletionStateV1? = transition(
        operationId,
        configuration,
        state,
        TerminalDeletionPhase.valueOf(state.phase),
    )

    private fun payloadFailure(
        state: TerminalDeletionStateV1,
        reason: RemoteBackupFailureCategory,
    ) = PayloadCleanupResult(state, failed(reason))

    private suspend fun cleanupKnownClaims(
        configuration: RemoteBackupConfiguration,
        terminalProvider: ProviderObjectId,
        objectStore: CreateOnlyBackupObjectStore,
        deletionBudget: DeletionBudget,
        authenticateTerminal: suspend () -> LifecycleResult?,
    ): LifecycleResult? {
        val targets = linkedSetOf(configuration.rootClaimProviderId)
        configuration.ownershipClaim?.providerId?.let(targets::add)
        for (role in NON_TERMINAL_CLAIM_ROLES) {
            var pageToken: String? = null
            val seenTokens = mutableSetOf<String>()
            do {
                val page = try {
                    objectStore.list(
                        RemoteListRequest(
                            lineageId = configuration.lineageId,
                            role = role,
                            writerEpoch = null,
                            ownerDeviceId = null,
                            pageToken = pageToken,
                            pageSize = CLAIM_PAGE_SIZE,
                        ),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
                }
                page.objects
                    .filter { it.role == role }
                    .forEach { targets += it.providerObjectId }
                if (targets.size > MAX_CLAIMS_PER_LINEAGE) {
                    return failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
                }
                pageToken = page.nextPageToken
                if (pageToken != null && !seenTokens.add(checkNotNull(pageToken))) {
                    return failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
                }
            } while (pageToken != null)
        }
        targets.remove(terminalProvider)
        val rootProvider = configuration.rootClaimProviderId
        val rootPending = rootProvider != terminalProvider
        targets.remove(rootProvider)
        if (targets.isNotEmpty() || rootPending) authenticateTerminal()?.let { return it }
        val batch = targets.take(deletionBudget.remaining)
        batch.forEach { providerId ->
            when (val deletion = objectStore.delete(providerId)) {
                DeleteObjectResult.Deleted, DeleteObjectResult.Missing -> Unit
                is DeleteObjectResult.Failed -> return failed(deletion.reason)
            }
            deletionBudget.remaining -= 1
        }
        if (targets.size > batch.size || rootPending && deletionBudget.remaining == 0) {
            return failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
        }
        if (rootPending) {
            when (val deletion = objectStore.delete(rootProvider)) {
                DeleteObjectResult.Deleted, DeleteObjectResult.Missing -> Unit
                is DeleteObjectResult.Failed -> return failed(deletion.reason)
            }
            deletionBudget.remaining -= 1
        }
        return null
    }

    private fun terminalClaim(
        predecessor: VerifiedOwnershipClaim,
        providerId: ProviderObjectId,
        claimId: OwnershipClaimId,
        operationId: String,
    ) = OwnershipClaimV1(
        lineageId = predecessor.claim.lineageId,
        writerEpoch = Math.addExact(predecessor.claim.writerEpoch, 1),
        state = OwnershipStateV1.TERMINATED,
        predecessorProviderFileId = predecessor.claim.providerFileId,
        predecessorClaimId = predecessor.claim.claimId,
        predecessorClaimSha256 = predecessor.completeSha256.value,
        providerFileId = providerId.value,
        claimId = claimId.value,
        predecessorReservedSuccessorProviderFileId = providerId.value,
        sourceVaultId = null,
        activeDeviceId = null,
        nextSuccessorProviderFileId = null,
        baselinePublicationProviderFileId = null,
        baselinePublicationId = null,
        baselinePublicationSha256 = null,
        recoveryCredentialGeneration = null,
        creationOperationId = operationId,
        tombstoneId = claimId.value,
    )

    private fun isExpectedTerminal(
        claim: VerifiedOwnershipClaim,
        expected: VerifiedOwnershipClaim,
        state: TerminalDeletionStateV1,
    ): Boolean = claim.claim.state == OwnershipStateV1.TERMINATED &&
        claim.completeSha256 == expected.completeSha256 &&
        claim.claim.providerFileId == state.tombstoneProviderFileId &&
        claim.claim.claimId == state.tombstoneClaimId &&
        claim.claim.tombstoneId == state.tombstoneClaimId &&
        claim.claim.nextSuccessorProviderFileId == null

    private suspend fun authenticateExpectedTerminalResult(
        expected: VerifiedOwnershipClaim,
        state: TerminalDeletionStateV1,
        objectStore: CreateOnlyBackupObjectStore,
        contentKey: VaultKey,
    ): LifecycleResult? = when (
        val authentication = authenticateExpectedTerminal(
            expected,
            state,
            objectStore,
            contentKey,
        )
    ) {
        is TerminalAuthentication.Authenticated -> null
        is TerminalAuthentication.Failed -> authentication.result
    }

    private suspend fun authenticateExpectedTerminal(
        expected: VerifiedOwnershipClaim,
        state: TerminalDeletionStateV1,
        objectStore: CreateOnlyBackupObjectStore,
        contentKey: VaultKey,
    ): TerminalAuthentication {
        val providerId = state.tombstoneProviderFileId?.let(ProviderObjectId::of)
            ?: return TerminalAuthentication.Failed(
                failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE),
            )
        val read = try {
            objectStore.readSmall(
                providerId,
                DefaultOwnershipChainStore.MAX_CLAIM_FILE_BYTES,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return TerminalAuthentication.Failed(
                failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER),
            )
        }
        return when (read) {
            ReadSmallResult.Missing -> TerminalAuthentication.Failed(
                failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE),
            )
            is ReadSmallResult.Failed -> TerminalAuthentication.Failed(failed(read.reason))
            is ReadSmallResult.Found -> {
                val owned = read.bytes
                val bytes = owned.take()
                try {
                    val verified = try {
                        ownershipCodec.verify(bytes, contentKey)
                    } catch (_: Exception) {
                        null
                    }
                    if (verified != null &&
                        state.tombstoneSha256 == verified.completeSha256.value &&
                        isExpectedTerminal(verified, expected, state)
                    ) {
                        TerminalAuthentication.Authenticated(verified)
                    } else {
                        TerminalAuthentication.Failed(
                            failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE),
                        )
                    }
                } finally {
                    bytes.fill(0)
                    owned.close()
                }
            }
        }
    }

    private fun holds(
        configuration: RemoteBackupConfiguration,
        tip: VerifiedOwnershipClaim,
    ): Boolean {
        val expected = configuration.ownershipClaim ?: return false
        return tip.claim.lineageId == configuration.lineageId.value &&
            tip.claim.providerFileId == expected.providerId.value &&
            tip.claim.claimId == expected.logicalId.value &&
            tip.completeSha256 == expected.sha256
    }

    private suspend fun markOwnershipLost(configuration: RemoteBackupConfiguration) {
        val latest = remoteStateStore.known(configuration.lineageId) ?: return
        if (latest.lifecycle == RemoteBackupLifecycle.TERMINATED) return
        remoteStateStore.compareAndSet(
            latest.lineageId,
            latest.stateVersion,
            latest.copy(
                lifecycle = RemoteBackupLifecycle.OWNERSHIP_LOST,
                failureCategory = RemoteBackupFailureCategory.OWNERSHIP_LOST,
                stateVersion = RemoteBackupStateVersion(latest.stateVersion.value + 1),
            ),
        )
    }

    private suspend fun authorize(configuration: RemoteBackupConfiguration): DriveAuthorizationResult? {
        val digest = configuration.accountBindingDigest
        return try {
            authorizationManager.authorize(DriveAuthorizationMode.NON_INTERACTIVE, digest)
        } finally {
            digest.fill(0)
        }
    }

    private fun authorizationFailure(result: DriveAuthorizationResult?): LifecycleResult = when (result) {
        DriveAuthorizationResult.AccountMismatch -> failed(RemoteBackupFailureCategory.ACCOUNT_MISMATCH)
        else -> failed(RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED)
    }

    private suspend fun transition(
        operationId: String,
        configuration: RemoteBackupConfiguration,
        current: TerminalDeletionStateV1,
        phase: TerminalDeletionPhase,
    ): TerminalDeletionStateV1? {
        val next = current.copy(phase = phase.name)
        return if (
            remoteStateStore.transitionOperation(
                operationId,
                current.phase,
                deletionOperation(operationId, configuration, next),
            )
        ) next else null
    }

    private suspend fun transitionAttachmentDeletion(
        operationId: String,
        configuration: RemoteBackupConfiguration,
        current: AttachmentContentDeletionStateV1,
        next: AttachmentContentDeletionStateV1,
    ): AttachmentContentDeletionStateV1? {
        return if (
            remoteStateStore.transitionOperation(
                operationId,
                current.phase,
                attachmentDeletionOperation(operationId, configuration, next),
            )
        ) next else null
    }

    private fun deletionOperation(
        operationId: String,
        configuration: RemoteBackupConfiguration,
        state: TerminalDeletionStateV1,
    ): RemoteBackupOperation {
        val encoded = DeletionJson.json.encodeToString(state).toByteArray(Charsets.UTF_8)
        return try {
            RemoteBackupOperation(
                operationId = operationId,
                lineageId = configuration.lineageId,
                kind = DELETE_OPERATION_KIND,
                phase = state.phase,
                targetEpoch = configuration.writerEpoch?.let { WriterEpoch(it.value + 1) },
                targetGeneration = configuration.lastVerifiedGeneration,
                candidateClaimProviderId =
                    state.tombstoneProviderFileId?.let(ProviderObjectId::of),
                candidatePublicationProviderId = null,
                stateBytes = encoded,
                startedAt = now(),
                updatedAt = now(),
            )
        } finally {
            encoded.fill(0)
        }
    }

    private fun attachmentDeletionOperation(
        operationId: String,
        configuration: RemoteBackupConfiguration,
        state: AttachmentContentDeletionStateV1,
    ): RemoteBackupOperation {
        val encoded = DeletionJson.json.encodeToString(state).toByteArray(Charsets.UTF_8)
        return try {
            RemoteBackupOperation(
                operationId = operationId,
                lineageId = configuration.lineageId,
                kind = ATTACHMENT_DELETE_OPERATION_KIND,
                phase = state.phase,
                targetEpoch = configuration.writerEpoch,
                targetGeneration = configuration.lastVerifiedGeneration,
                candidateClaimProviderId = null,
                candidatePublicationProviderId = null,
                stateBytes = encoded,
                startedAt = now(),
                updatedAt = now(),
            )
        } finally {
            encoded.fill(0)
        }
    }

    private fun decodeDeletionState(operation: RemoteBackupOperation): TerminalDeletionStateV1? {
        if (operation.kind != DELETE_OPERATION_KIND) return null
        val encoded = operation.stateBytes
        return try {
            val state = DeletionJson.json.decodeFromString<TerminalDeletionStateV1>(
                encoded.toString(Charsets.UTF_8),
            )
            state.takeIf {
                it.phase == operation.phase && it.formatVersion == 1 &&
                    it.minimumReaderVersion == 1 &&
                    runCatching { TerminalDeletionPhase.valueOf(it.phase) }.isSuccess &&
                    runCatching {
                        AttachmentDeletionPhase.valueOf(it.attachmentPhase)
                    }.isSuccess &&
                    validAttachmentCycleState(
                        it.attachmentCycleFastPageToken,
                        it.attachmentCycleFastEnded,
                    )
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            encoded.fill(0)
        }
    }

    private fun decodeAttachmentDeletionState(
        operation: RemoteBackupOperation,
    ): AttachmentContentDeletionStateV1? {
        if (operation.kind != ATTACHMENT_DELETE_OPERATION_KIND) return null
        val encoded = operation.stateBytes
        return try {
            val state = DeletionJson.json.decodeFromString<AttachmentContentDeletionStateV1>(
                encoded.toString(Charsets.UTF_8),
            )
            state.takeIf {
                it.phase == operation.phase && it.formatVersion == 1 &&
                    it.minimumReaderVersion == 1 &&
                    runCatching { AttachmentDeletionPhase.valueOf(it.phase) }.isSuccess &&
                    validAttachmentCycleState(
                        it.cycleFastPageToken,
                        it.cycleFastEnded,
                    )
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            encoded.fill(0)
        }
    }

    private fun decodeBytes(value: String?): ByteArray? = try {
        value?.let(Base64.getDecoder()::decode)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun validAttachmentCycleState(
        cycleFastPageToken: String?,
        cycleFastEnded: Boolean,
    ): Boolean = !(cycleFastEnded && cycleFastPageToken != null)

    private fun reached(state: TerminalDeletionStateV1, phase: TerminalDeletionPhase) =
        TerminalDeletionPhase.valueOf(state.phase) >= phase

    private fun VerifiedOwnershipClaim.ref() = OwnershipClaimRef(
        providerId = ProviderObjectId.of(claim.providerFileId),
        logicalId = OwnershipClaimId.parse(claim.claimId),
        sha256 = completeSha256,
        writerEpoch = WriterEpoch(claim.writerEpoch),
    )

    private fun ownedCopy(source: ByteArray): OwnedRemoteBytes = object : OwnedRemoteBytes {
        private var bytes: ByteArray? = source.copyOf()
        override val size: Int get() = checkNotNull(bytes).size
        override fun take(): ByteArray = checkNotNull(bytes).also { bytes = null }
        override fun close() {
            bytes?.fill(0)
            bytes = null
        }
    }

    private fun VaultKeyEnvelope.clearOwned() {
        kdf.salt.fill(0)
        nonce.fill(0)
        wrappedKeyset.fill(0)
    }

    override suspend fun preserveDivergentWorkAsNewLineage(): RemoteBackupConnectResult =
        mutex.withLock {
            val lost = try {
                remoteStateStore.configurations(vaultId).singleOrNull {
                    it.lifecycle == RemoteBackupLifecycle.OWNERSHIP_LOST
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            } ?: return@withLock RemoteBackupConnectResult.Failed(
                RemoteBackupFailureCategory.OWNERSHIP_LOST,
            )
            val expectedDigest = lost.accountBindingDigest
            val authorization = try {
                authorizationManager.authorize(
                    DriveAuthorizationMode.NON_INTERACTIVE,
                    expectedDigest,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            } finally {
                expectedDigest.fill(0)
            }
            val session = (authorization as? DriveAuthorizationResult.Authorized)?.session
                ?: return@withLock RemoteBackupConnectResult.Failed(
                    RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
                )
            try {
                val accountDigest = session.copyAccountBindingDigest()
                try {
                    configurator.connect(
                        objectStore = openObjectStore(session.transport),
                        accountBindingDigest = accountDigest,
                        allowSeparateLineage = true,
                    )
                } finally {
                    accountDigest.fill(0)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                RemoteBackupConnectResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            } finally {
                session.close()
            }
        }

    @OptIn(ExperimentalSerializationApi::class)
    private object DeletionJson {
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
            isLenient = false
            allowStructuredMapKeys = false
        }
    }

    private companion object {
        const val DELETE_OPERATION_PREFIX = "remote-history-delete:"
        const val DELETE_OPERATION_KIND = "DELETE_REMOTE_HISTORY"
        const val ATTACHMENT_DELETE_OPERATION_PREFIX = "remote-attachment-delete:"
        const val ATTACHMENT_DELETE_OPERATION_KIND = "DELETE_ATTACHMENT_CONTENT"
        const val MAX_DELETES_PER_BATCH = 32
        const val CLAIM_PAGE_SIZE = 100
        const val MAX_CLAIMS_PER_LINEAGE = 4_096
        const val PAYLOAD_PAGE_SIZE = 100
        const val MAX_PAYLOAD_PAGES_PER_ROLE = 64
        const val MAX_PAYLOAD_CANDIDATES = 4_096
        const val MAX_PAGE_TOKEN_CHARACTERS = 1_024
        const val ATTACHMENT_PAGE_SIZE = 100
        const val MAX_ATTACHMENT_PAGES_PER_INVOCATION = 8
        const val MAX_ATTACHMENT_ROLE_PROBE_PAGES = 8
        const val ATTACHMENT_CHUNK_ROLE = "attachment-chunk"
        const val ATTACHMENT_MANIFEST_ROLE = "attachment-manifest"
        val MINIMUM_RESIDUE_AGE: Duration = Duration.ofDays(7)
        val PAYLOAD_ROLES = listOf(
            RemoteObjectRoleV1.PUBLICATION,
            RemoteObjectRoleV1.SNAPSHOT,
            RemoteObjectRoleV1.SEGMENT,
        )
        val NON_TERMINAL_CLAIM_ROLES = listOf(
            RemoteObjectRoleV1.OWNERSHIP_ROOT,
            RemoteObjectRoleV1.OWNERSHIP_CLAIM,
        )
    }

    private fun failed(reason: RemoteBackupFailureCategory) = LifecycleResult.Failed(reason)
}
