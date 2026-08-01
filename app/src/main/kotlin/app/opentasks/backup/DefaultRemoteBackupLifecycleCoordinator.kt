package app.opentasks.backup

import app.opentasks.backup.drive.AuthorizedDriveSession
import app.opentasks.backup.drive.DriveAuthorizationMode
import app.opentasks.backup.drive.DriveAuthorizationResult
import app.opentasks.backup.drive.GoogleDriveAuthorizationManager
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.backup.OwnershipChainStore
import app.opentasks.core.data.backup.OwnershipClaimCodec
import app.opentasks.core.data.backup.OwnershipClaimCreateResult
import app.opentasks.core.data.backup.OwnershipClaimV1
import app.opentasks.core.data.backup.OwnershipResolution
import app.opentasks.core.data.backup.RecoveryEnvelopeStore
import app.opentasks.core.data.backup.RemoteBackupStateStore
import app.opentasks.core.data.backup.RemoteBackupTransferStore
import app.opentasks.core.data.backup.VerifiedOwnershipClaim
import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.domain.BackupWorkScheduler
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.DeleteObjectResult
import app.opentasks.core.domain.LifecycleResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.RemoteBackupConfigurator
import app.opentasks.core.domain.RemoteBackupConnectResult
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupLifecycleCoordinator
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipClaimRef
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.WriterEpoch
import app.opentasks.core.model.VaultId
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
) {
    override fun toString(): String = "TerminalDeletionStateV1(phase=$phase)"
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
    private val configurator: RemoteBackupConfigurator,
    private val now: () -> Instant = Instant::now,
    private val newClaimId: () -> OwnershipClaimId = OwnershipClaimId::new,
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
        mutex.withLock { deleteLocked(passphrase) }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
    } finally {
        passphrase.fill('\u0000')
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
                operation?.phase == TerminalDeletionPhase.COMPLETED.name
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
        var resolution = chainStore.resolve(configuration.rootClaimProviderId, contentKey)
        var predecessor = (resolution as? OwnershipResolution.Active)?.tip
        var tombstone = (resolution as? OwnershipResolution.Terminated)?.tombstone

        if (predecessor != null && !holds(configuration, predecessor)) {
            markOwnershipLost(configuration)
            return LifecycleResult.OwnershipRequired
        }
        if (predecessor == null && tombstone == null) {
            return failed(
                (resolution as? OwnershipResolution.Blocked)?.reason
                    ?: RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
            )
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
                state = transition(
                    operationId,
                    configuration,
                    state.copy(
                        tombstoneProviderFileId = providerId.value,
                        tombstoneClaimId = claimId.value,
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
            cleanupPayloadBatch(configuration, objectStore)?.let { return it }

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
        configuration: RemoteBackupConfiguration,
        objectStore: CreateOnlyBackupObjectStore,
    ): LifecycleResult? {
        val cutoff = now().minus(MINIMUM_RESIDUE_AGE)
        val eligible = transferStore.objectsForLineage(configuration.lineageId)
            .filter { it.role in PAYLOAD_ROLES }
            .filter { it.verifiedAt != null || !it.createdAt.isAfter(cutoff) }
            .sortedBy { it.createdAt }
        for (record in eligible.take(MAX_DELETES_PER_BATCH)) {
            when (val deletion = objectStore.delete(record.providerObjectId)) {
                DeleteObjectResult.Deleted, DeleteObjectResult.Missing ->
                    transferStore.removeObjectState(
                        configuration.lineageId,
                        record.logicalObjectId,
                    )
                is DeleteObjectResult.Failed -> return failed(deletion.reason)
            }
        }
        return if (eligible.size > MAX_DELETES_PER_BATCH) {
            failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
        } else {
            null
        }
    }

    private suspend fun cleanupKnownClaims(
        configuration: RemoteBackupConfiguration,
        terminalProvider: ProviderObjectId,
        objectStore: CreateOnlyBackupObjectStore,
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
        targets.take(MAX_DELETES_PER_BATCH).forEach { providerId ->
            when (val deletion = objectStore.delete(providerId)) {
                DeleteObjectResult.Deleted, DeleteObjectResult.Missing -> Unit
                is DeleteObjectResult.Failed -> return failed(deletion.reason)
            }
        }
        return if (targets.size > MAX_DELETES_PER_BATCH) {
            failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
        } else {
            null
        }
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

    private fun decodeDeletionState(operation: RemoteBackupOperation): TerminalDeletionStateV1? {
        if (operation.kind != DELETE_OPERATION_KIND) return null
        val encoded = operation.stateBytes
        return try {
            val state = DeletionJson.json.decodeFromString<TerminalDeletionStateV1>(
                encoded.toString(Charsets.UTF_8),
            )
            state.takeIf {
                it.phase == operation.phase && it.formatVersion == 1 &&
                    it.minimumReaderVersion == 1
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
        const val MAX_DELETES_PER_BATCH = 32
        const val CLAIM_PAGE_SIZE = 100
        const val MAX_CLAIMS_PER_LINEAGE = 4_096
        val MINIMUM_RESIDUE_AGE: Duration = Duration.ofDays(7)
        val PAYLOAD_ROLES = setOf(
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
