package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.domain.BackupCoordinator
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.ImmutableDownloadResult
import app.opentasks.core.domain.ImmutableUploadRequest
import app.opentasks.core.domain.ImmutableUploadResult
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupConfigurator
import app.opentasks.core.domain.RemoteBackupConnectResult
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipClaimRef
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.PublicationRef
import app.opentasks.core.model.PublicationSequence
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The exact ordered phases an interrupted initial connection resumes from. */
internal enum class RemoteConnectPhase {
    DISCOVERY_COMPLETED,
    IDENTITIES_STORED,
    LOCAL_BASE_CAPTURED,
    BASE_A_VERIFIED,
    BASE_B_VERIFIED,
    BASELINE_CREATED,
    BASELINE_VERIFIED,
    ROOT_CREATED,
    ROOT_VERIFIED,
    COMPLETED,
}

@Serializable
internal data class RemoteConnectObjectV1(
    val logicalObjectId: String,
    val providerFileId: String,
    val frameLength: Long,
    val frameSha256: String,
    val payloadSha256: String,
) {
    override fun toString(): String = "RemoteConnectObjectV1(redacted)"
}

/**
 * Durable connection state. Every identity is generated once and persisted
 * here, so a restart reuses the same lineage, device, claim, publication, and
 * base identities instead of creating a second ownership root.
 */
@Serializable
internal data class RemoteConnectStateV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val phase: String,
    val lineageId: String,
    val deviceId: String,
    val rootClaimId: String,
    val publicationId: String,
    val baseALogicalObjectId: String,
    val baseBLogicalObjectId: String,
    val rootProviderFileId: String?,
    val successorProviderFileId: String?,
    val publicationProviderFileId: String?,
    val baseAProviderFileId: String?,
    val baseBProviderFileId: String?,
    val localBaseObjectId: String?,
    val localGeneration: Long?,
    val baseA: RemoteConnectObjectV1?,
    val baseB: RemoteConnectObjectV1?,
    val baselineSha256: String?,
) {
    /** Never reveals a lineage, device, claim, publication, or provider identity. */
    override fun toString(): String = "RemoteConnectStateV1(phase=$phase)"
}

/**
 * Initial two-base, baseline-first, ownership-root connection.
 *
 * The order is forced by the format: the ownership root binds the digest of
 * the epoch baseline, and the baseline binds only the *planned* root identity,
 * so the baseline is created and read back before the root exists. Nothing is
 * generated before existing roots have been discovered, and remote backup is
 * marked active only after the root is read back from the provider and the
 * whole chain plus its publication catalog re-resolve to exactly what was
 * written.
 *
 * Every provider and local failure becomes a bounded
 * [RemoteBackupConnectResult.Failed]; no exception escapes as control flow and
 * no message, identifier, or key material reaches a caller.
 */
class DefaultRemoteBackupConfigurator(
    private val vaultId: VaultId,
    private val backupCoordinator: BackupCoordinator,
    private val backupStateStore: BackupStateStore,
    private val recoveryEnvelopeStore: RecoveryEnvelopeStore,
    private val contentKeyStore: VaultContentKeyStore,
    private val remoteStateStore: RemoteBackupStateStore,
    private val remoteObjectCodec: RemoteObjectCodec,
    private val ownershipCodec: OwnershipClaimCodec,
    private val publicationCodec: PublicationCodec,
    private val now: () -> Instant = Instant::now,
    private val newLineageId: () -> CloudLineageId = CloudLineageId::new,
    private val newDeviceId: () -> CloudDeviceId = CloudDeviceId::new,
    private val newClaimId: () -> OwnershipClaimId = OwnershipClaimId::new,
    private val newPublicationId: () -> PublicationId = PublicationId::new,
    private val newLogicalObjectId: () -> RemoteLogicalObjectId = RemoteLogicalObjectId::new,
) : RemoteBackupConfigurator {

    override suspend fun connect(
        objectStore: CreateOnlyBackupObjectStore,
        accountBindingDigest: ByteArray,
        allowSeparateLineage: Boolean,
    ): RemoteBackupConnectResult {
        val operationId = if (allowSeparateLineage) {
            SEPARATE_CONNECT_OPERATION_PREFIX + vaultId.value
        } else {
            CONNECT_OPERATION_PREFIX + vaultId.value
        }
        val stored = remoteStateStore.operation(operationId)
        val resumed = stored?.let { operation ->
            runBounded {
                require(operation.kind == CONNECT_OPERATION_KIND) {
                    "Another operation already owns this identifier"
                }
                decodeState(operation.stateBytes)
            }
        }
        // A recorded operation that cannot be decoded is never restarted from
        // scratch: doing so would generate a second set of identities beside
        // remote objects this installation can no longer account for.
        if (stored != null && resumed == null) {
            return RemoteBackupConnectResult.Failed(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
        }
        if (resumed != null && resumed.phase == RemoteConnectPhase.COMPLETED.name) {
            return RemoteBackupConnectResult.Connected(
                lineageId = CloudLineageId.parse(resumed.lineageId),
                generation = BackupGeneration(checkNotNull(resumed.localGeneration)),
            )
        }
        val contentKey = try {
            contentKeyStore.openExisting(vaultId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return RemoteBackupConnectResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
        }
        return try {
            Session(
                objectStore = objectStore,
                operationId = operationId,
                accountBindingDigest = accountBindingDigest,
                contentKey = contentKey,
            ).run(resumed, allowSeparateLineage)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            RemoteBackupConnectResult.Failed(failure.toBoundedReason())
        } finally {
            contentKey.close()
        }
    }

    private inner class Session(
        private val objectStore: CreateOnlyBackupObjectStore,
        private val operationId: String,
        private val accountBindingDigest: ByteArray,
        private val contentKey: VaultKey,
    ) {
        private val chainStore = DefaultOwnershipChainStore(objectStore, ownershipCodec)
        private val catalog = DefaultPublicationCatalog(objectStore, publicationCodec)
        private lateinit var state: RemoteConnectStateV1

        suspend fun run(
            resumed: RemoteConnectStateV1?,
            allowSeparateLineage: Boolean,
        ): RemoteBackupConnectResult {
            if (resumed == null) {
                when (val discovery = chainStore.discoverPublicRoots()) {
                    is OwnershipRootDiscovery.Blocked ->
                        return RemoteBackupConnectResult.Failed(discovery.reason)

                    is OwnershipRootDiscovery.Discovered ->
                        if (discovery.roots.isNotEmpty() && !allowSeparateLineage) {
                            return RemoteBackupConnectResult.ExistingBackupsFound(
                                discovery.roots.size,
                            )
                        }
                }
                state = RemoteConnectStateV1(
                    phase = RemoteConnectPhase.DISCOVERY_COMPLETED.name,
                    lineageId = newLineageId().value,
                    deviceId = newDeviceId().value,
                    rootClaimId = newClaimId().value,
                    publicationId = newPublicationId().value,
                    baseALogicalObjectId = newLogicalObjectId().value,
                    baseBLogicalObjectId = newLogicalObjectId().value,
                    rootProviderFileId = null,
                    successorProviderFileId = null,
                    publicationProviderFileId = null,
                    baseAProviderFileId = null,
                    baseBProviderFileId = null,
                    localBaseObjectId = null,
                    localGeneration = null,
                    baseA = null,
                    baseB = null,
                    baselineSha256 = null,
                )
                remoteStateStore.putOperation(operation(state))
            } else {
                state = resumed
            }

            failure(storeIdentities())?.let { return it }
            failure(captureLocalBase())?.let { return it }
            failure(publishBase(first = true))?.let { return it }
            failure(publishBase(first = false))?.let { return it }
            failure(createBaseline())?.let { return it }
            failure(verifyBaseline())?.let { return it }
            failure(createRoot())?.let { return it }
            val tip = when (val verified = verifyRoot()) {
                is StepResult.Failed -> return RemoteBackupConnectResult.Failed(verified.reason)
                is StepResult.Ok -> verified.value
            }
            failure(activate(tip))?.let { return it }
            return RemoteBackupConnectResult.Connected(
                lineageId = CloudLineageId.parse(state.lineageId),
                generation = BackupGeneration(checkNotNull(state.localGeneration)),
            )
        }

        // -- Phases ---------------------------------------------------------------------

        /** Reserves every provider slot once and records the connecting lineage. */
        private suspend fun storeIdentities(): StepResult<Unit> {
            if (reached(RemoteConnectPhase.IDENTITIES_STORED)) return StepResult.Ok(Unit)
            val rootProviderId = generateId(RemoteObjectRoleV1.OWNERSHIP_ROOT)
            val successorProviderId = generateId(RemoteObjectRoleV1.OWNERSHIP_CLAIM)
            val publicationProviderId = generateId(RemoteObjectRoleV1.PUBLICATION)
            val baseAProviderId = generateId(RemoteObjectRoleV1.SNAPSHOT)
            val baseBProviderId = generateId(RemoteObjectRoleV1.SNAPSHOT)
            val reserved = listOf(
                rootProviderId,
                successorProviderId,
                publicationProviderId,
                baseAProviderId,
                baseBProviderId,
            )
            // Every reserved slot must be distinct: a provider that repeats a
            // generated ID would let one object occupy another's exact slot.
            if (reserved.distinct().size != reserved.size) {
                return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            remoteStateStore.insertConnecting(
                RemoteBackupConfiguration(
                    lineageId = CloudLineageId.parse(state.lineageId),
                    vaultId = vaultId,
                    rootClaimProviderId = rootProviderId,
                    accountBindingDigest = accountBindingDigest,
                    lifecycle = RemoteBackupLifecycle.CONNECTING,
                    activeDeviceId = null,
                    writerEpoch = null,
                    ownershipClaim = null,
                    nextSuccessorProviderId = null,
                    currentPublication = null,
                    previousPublication = null,
                    lastVerifiedGeneration = null,
                    lastVerifiedAt = null,
                    recoveryCredentialGeneration = INITIAL_RECOVERY_CREDENTIAL_GENERATION,
                    failureCategory = null,
                    stateVersion = RemoteBackupStateVersion(0),
                ),
            )
            return advance(
                RemoteConnectPhase.IDENTITIES_STORED,
                state.copy(
                    rootProviderFileId = rootProviderId.value,
                    successorProviderFileId = successorProviderId.value,
                    publicationProviderFileId = publicationProviderId.value,
                    baseAProviderFileId = baseAProviderId.value,
                    baseBProviderFileId = baseBProviderId.value,
                ),
            )
        }

        /** Requires a Stage 2 complete base, never a segment-only checkpoint. */
        private suspend fun captureLocalBase(): StepResult<Unit> {
            if (reached(RemoteConnectPhase.LOCAL_BASE_CAPTURED)) return StepResult.Ok(Unit)
            backupCoordinator.request()
            val local = backupStateStore.get(vaultId)
                ?: return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            val baseObjectId = local.currentBaseObjectId
            val baseGeneration = local.lastVerifiedSnapshotGeneration
            if (baseObjectId == null || baseGeneration == null) {
                return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            return advance(
                RemoteConnectPhase.LOCAL_BASE_CAPTURED,
                state.copy(
                    localBaseObjectId = baseObjectId,
                    localGeneration = baseGeneration,
                ),
            )
        }

        /**
         * Uploads, downloads, authenticates, decodes, and compares one of two
         * independently identified copies of the same complete capture.
         *
         * Re-encoding a base produces a fresh AES-GCM nonce, so a restart can
         * never reproduce the bytes a previous attempt uploaded. The intended
         * bytes are therefore recorded durably *before* the first network
         * mutation: a restart that finds those exact bytes already present
         * adopts them instead of uploading a second, differently nonced copy
         * into the same slot.
         */
        private suspend fun publishBase(first: Boolean): StepResult<Unit> {
            val phase = if (first) {
                RemoteConnectPhase.BASE_A_VERIFIED
            } else {
                RemoteConnectPhase.BASE_B_VERIFIED
            }
            if (reached(phase)) return StepResult.Ok(Unit)
            val logicalObjectId = RemoteLogicalObjectId.of(
                if (first) state.baseALogicalObjectId else state.baseBLogicalObjectId,
            )
            val providerObjectId = ProviderObjectId.of(
                checkNotNull(if (first) state.baseAProviderFileId else state.baseBProviderFileId),
            )
            val generation = BackupGeneration(checkNotNull(state.localGeneration))

            // An unverified record at this phase is a previous attempt's plan.
            val planned = if (first) state.baseA else state.baseB
            if (planned != null) {
                when (val adopted = adoptPlannedBase(planned, logicalObjectId, generation)) {
                    is StepResult.Failed -> return adopted
                    is StepResult.Ok ->
                        if (adopted.value) return advance(phase, state)
                }
            }

            val remote = try {
                remoteObjectCodec.reauthenticateLocalObject(
                    localObjectId = checkNotNull(state.localBaseObjectId),
                    vaultId = vaultId,
                    lineageId = CloudLineageId.parse(state.lineageId),
                    logicalObjectId = logicalObjectId,
                    contentKey = contentKey,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            val record = RemoteConnectObjectV1(
                logicalObjectId = logicalObjectId.value,
                providerFileId = providerObjectId.value,
                frameLength = remote.frameLength,
                frameSha256 = remote.frameSha256.value,
                payloadSha256 = remote.payloadSha256.value,
            )
            val planning =
                record(if (first) state.copy(baseA = record) else state.copy(baseB = record))
            if (planning is StepResult.Failed) {
                remote.close()
                return planning
            }
            remote.use {
                val upload = objectStore.uploadImmutable(
                    ImmutableUploadRequest(
                        lineageId = CloudLineageId.parse(state.lineageId),
                        writerEpoch = FIRST_EPOCH,
                        ownerDeviceId = CloudDeviceId.parse(state.deviceId),
                        operationId = operationId,
                        logicalObjectId = logicalObjectId,
                        providerObjectId = providerObjectId,
                        role = RemoteObjectRoleV1.SNAPSHOT,
                        firstGeneration = generation,
                        lastGeneration = generation,
                        frameLength = remote.frameLength,
                        frameSha256 = remote.frameSha256,
                        frame = remote,
                    ),
                )
                when (upload) {
                    ImmutableUploadResult.UploadedAndVerified,
                    ImmutableUploadResult.OccupiedByExpectedBytes -> Unit

                    ImmutableUploadResult.OccupiedByDifferentBytes ->
                        return StepResult.Failed(
                            RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE,
                        )

                    is ImmutableUploadResult.Failed ->
                        return StepResult.Failed(upload.reason)
                }
            }
            return when (val adopted = adoptPlannedBase(record, logicalObjectId, generation)) {
                is StepResult.Failed -> adopted
                is StepResult.Ok ->
                    if (adopted.value) {
                        advance(phase, state)
                    } else {
                        StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
                    }
            }
        }

        /**
         * Downloads the exact bytes [planned] records, authenticates them at
         * the persisted lineage plus remote logical object, and checks the
         * decoded payload.
         *
         * `Ok(true)` means the provider holds this connection's own copy —
         * either the one just uploaded or one a previous attempt uploaded
         * before it could persist its phase. `Ok(false)` means the slot is
         * empty and the object still has to be created. Anything else fails
         * closed: bytes at the slot that are not the planned bytes are never
         * adopted, and no alternate slot is ever generated.
         */
        private suspend fun adoptPlannedBase(
            planned: RemoteConnectObjectV1,
            logicalObjectId: RemoteLogicalObjectId,
            generation: BackupGeneration,
        ): StepResult<Boolean> {
            val providerObjectId = ProviderObjectId.of(planned.providerFileId)
            val expected = runBounded { Sha256Digest.of(planned.frameSha256) }
                ?: return StepResult.Failed(
                    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            val downloaded = objectStore.downloadImmutable(
                providerObjectId = providerObjectId,
                maximumBytes = planned.frameLength,
                expectedSha256 = expected,
            )
            val authenticated = when (downloaded) {
                is ImmutableDownloadResult.Downloaded ->
                    downloaded.frame.use { frame ->
                        runBounded {
                            remoteObjectCodec.readRemoteBase(
                                frame = frame,
                                lineageId = CloudLineageId.parse(state.lineageId),
                                logicalObjectId = logicalObjectId,
                                contentKey = contentKey,
                            )
                        }
                    } ?: return StepResult.Failed(
                        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                    )

                // Nothing was ever created at this slot; the caller encodes and
                // uploads a fresh copy under the same identity.
                ImmutableDownloadResult.Missing -> return StepResult.Ok(false)

                ImmutableDownloadResult.Corrupt ->
                    return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)

                is ImmutableDownloadResult.Failed ->
                    return StepResult.Failed(downloaded.reason)
            }
            if (authenticated.coveredGeneration != generation.value ||
                authenticated.payloadSha256.value != planned.payloadSha256
            ) {
                return StepResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            // The two bases must be independent objects carrying identical
            // structured content; only then are two complete bases proven.
            val other = state.baseA
            if (other != null && other.logicalObjectId != planned.logicalObjectId) {
                if (other.payloadSha256 != planned.payloadSha256 ||
                    other.providerFileId == planned.providerFileId ||
                    other.frameSha256 == planned.frameSha256
                ) {
                    return StepResult.Failed(
                        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                    )
                }
            }
            return StepResult.Ok(true)
        }

        /** Creates the sequence-zero baseline that names the planned root. */
        private suspend fun createBaseline(): StepResult<Unit> {
            if (reached(RemoteConnectPhase.BASELINE_CREATED)) return StepResult.Ok(Unit)
            val envelope = recoveryEnvelopeStore.get(vaultId)
                ?: return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            // The envelope holds wrapped key material; it is cleared as soon as
            // the bootstrap that publishes it has been encoded.
            val encoded = try {
                publicationCodec.encode(baselineManifest(envelope), envelope, contentKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return StepResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            } finally {
                envelope.kdf.salt.fill(0)
                envelope.nonce.fill(0)
                envelope.wrappedKeyset.fill(0)
            }
            val providerObjectId =
                ProviderObjectId.of(checkNotNull(state.publicationProviderFileId))
            val created = try {
                catalog.create(providerObjectId, ownedCopyOf(encoded), contentKey)
            } finally {
                encoded.fill(0)
            }
            return when (created) {
                is PublicationCreateResult.Created ->
                    advance(RemoteConnectPhase.BASELINE_CREATED, state)

                is PublicationCreateResult.OccupiedByExpected ->
                    advance(RemoteConnectPhase.BASELINE_CREATED, state)

                // Re-encoding produces a fresh nonce, so a previous attempt's
                // own baseline occupies the slot with different bytes. Adopt it
                // only when it authenticates to the identity this connection
                // planned, which nothing without the content key can forge.
                PublicationCreateResult.OccupiedByDifferent ->
                    when (val occupant = readPlannedBaseline()) {
                        is StepResult.Failed -> occupant
                        is StepResult.Ok -> advance(RemoteConnectPhase.BASELINE_CREATED, state)
                    }

                is PublicationCreateResult.Failed -> StepResult.Failed(created.reason)
            }
        }

        /** Reads the baseline back and records the digest the root must bind. */
        private suspend fun verifyBaseline(): StepResult<Unit> {
            if (reached(RemoteConnectPhase.BASELINE_VERIFIED)) return StepResult.Ok(Unit)
            return when (val verified = readPlannedBaseline()) {
                is StepResult.Failed -> verified
                is StepResult.Ok -> advance(
                    RemoteConnectPhase.BASELINE_VERIFIED,
                    state.copy(baselineSha256 = verified.value.completeSha256.value),
                )
            }
        }

        /**
         * Reads the publication slot this connection reserved and requires the
         * occupant to be the sequence-zero baseline it planned: same lineage,
         * publication identity, provider file, epoch, device, source vault, and
         * planned ownership claim. Any other occupant is ambiguity.
         */
        private suspend fun readPlannedBaseline(): StepResult<VerifiedPublication> {
            val providerObjectId =
                ProviderObjectId.of(checkNotNull(state.publicationProviderFileId))
            val bytes = when (val read = readPublication(providerObjectId)) {
                is ReadSmallResult.Found -> read.bytes
                ReadSmallResult.Missing ->
                    return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)

                is ReadSmallResult.Failed -> return StepResult.Failed(read.reason)
            }
            val verified = bytes.useOwned { source ->
                runBounded { publicationCodec.verify(source, contentKey) }
            } ?: return StepResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            val manifest = verified.manifest
            val planned = manifest.lineageId == state.lineageId &&
                manifest.publicationId == state.publicationId &&
                manifest.publicationProviderFileId == providerObjectId.value &&
                manifest.publicationSequence == BASELINE_SEQUENCE &&
                manifest.baseline &&
                manifest.writerEpoch == FIRST_EPOCH.value &&
                manifest.activeDeviceId == state.deviceId &&
                manifest.sourceVaultId == vaultId.value &&
                manifest.plannedClaimProviderFileId == state.rootProviderFileId &&
                manifest.plannedClaimId == state.rootClaimId
            if (!planned) {
                return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            return StepResult.Ok(verified)
        }

        private suspend fun createRoot(): StepResult<Unit> {
            if (reached(RemoteConnectPhase.ROOT_CREATED)) return StepResult.Ok(Unit)
            val intended = rootClaim()
            val encoded = try {
                ownershipCodec.encode(intended, contentKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return StepResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            val created = try {
                chainStore.createClaim(null, ownedCopyOf(encoded), contentKey)
            } finally {
                encoded.fill(0)
            }
            return when (created) {
                is OwnershipClaimCreateResult.Won ->
                    advance(RemoteConnectPhase.ROOT_CREATED, state)

                // The root claim's plaintext is fully determined by persisted
                // state, so an occupant whose authenticated claim equals the
                // claim this connection intended is its own earlier create
                // recovering across a crash — not another writer. Only the
                // content key can produce those authenticated bytes, and every
                // other occupant is still ownership loss.
                is OwnershipClaimCreateResult.Lost ->
                    if (created.winner.claim == intended) {
                        advance(RemoteConnectPhase.ROOT_CREATED, state)
                    } else {
                        StepResult.Failed(RemoteBackupFailureCategory.OWNERSHIP_LOST)
                    }

                OwnershipClaimCreateResult.AmbiguousRemoteState ->
                    StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)

                is OwnershipClaimCreateResult.Failed -> StepResult.Failed(created.reason)
            }
        }

        /** Re-resolves the whole chain from the provider before enabling anything. */
        private suspend fun verifyRoot(): StepResult<VerifiedOwnershipClaim> {
            val rootProviderId = ProviderObjectId.of(checkNotNull(state.rootProviderFileId))
            return when (val resolution = chainStore.resolve(rootProviderId, contentKey)) {
                is OwnershipResolution.Blocked -> StepResult.Failed(resolution.reason)
                is OwnershipResolution.Terminated ->
                    StepResult.Failed(RemoteBackupFailureCategory.TERMINATED)

                is OwnershipResolution.Active -> {
                    if (resolution.tip.completeSha256 != resolution.root.completeSha256 ||
                        resolution.tip.claim.claimId != state.rootClaimId ||
                        resolution.tip.claim.activeDeviceId != state.deviceId
                    ) {
                        StepResult.Failed(RemoteBackupFailureCategory.OWNERSHIP_LOST)
                    } else {
                        when (
                            val advanced =
                                advance(RemoteConnectPhase.ROOT_VERIFIED, state)
                        ) {
                            is StepResult.Failed -> StepResult.Failed(advanced.reason)
                            is StepResult.Ok -> StepResult.Ok(resolution.tip)
                        }
                    }
                }
            }
        }

        /**
         * Re-resolves the publication catalog under the authenticated tip and
         * only then records an active lineage.
         */
        private suspend fun activate(tip: VerifiedOwnershipClaim): StepResult<Unit> {
            if (reached(RemoteConnectPhase.COMPLETED)) return StepResult.Ok(Unit)
            val lineageId = CloudLineageId.parse(state.lineageId)
            val rootProviderId = ProviderObjectId.of(checkNotNull(state.rootProviderFileId))
            val candidates = when (
                val discovery = catalog.discoverBootstraps(lineageId, FIRST_EPOCH, rootProviderId)
            ) {
                is PublicationCandidateDiscovery.Blocked ->
                    return StepResult.Failed(discovery.reason)

                is PublicationCandidateDiscovery.Discovered -> discovery.candidates
            }
            val resolved = when (
                val resolution = catalog.resolve(tip, candidates, contentKey)
            ) {
                is PublicationResolution.Failed -> return StepResult.Failed(resolution.reason)
                is PublicationResolution.Resolved -> resolution
            }
            if (resolved.previous != null ||
                resolved.current.completeSha256.value != state.baselineSha256
            ) {
                return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            val generation = BackupGeneration(checkNotNull(state.localGeneration))
            val stored = remoteStateStore.known(lineageId)
                ?: return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            val applied = remoteStateStore.compareAndSet(
                lineageId = lineageId,
                expected = stored.stateVersion,
                next = stored.copy(
                    lifecycle = RemoteBackupLifecycle.ACTIVE,
                    activeDeviceId = CloudDeviceId.parse(state.deviceId),
                    writerEpoch = FIRST_EPOCH,
                    ownershipClaim = OwnershipClaimRef(
                        providerId = rootProviderId,
                        logicalId = OwnershipClaimId.parse(state.rootClaimId),
                        sha256 = tip.completeSha256,
                        writerEpoch = FIRST_EPOCH,
                    ),
                    nextSuccessorProviderId =
                        ProviderObjectId.of(checkNotNull(state.successorProviderFileId)),
                    currentPublication = PublicationRef(
                        providerId = ProviderObjectId.of(
                            checkNotNull(state.publicationProviderFileId),
                        ),
                        logicalId = PublicationId.parse(state.publicationId),
                        sha256 = resolved.current.completeSha256,
                        sequence = PublicationSequence(BASELINE_SEQUENCE),
                        generation = generation,
                    ),
                    previousPublication = null,
                    lastVerifiedGeneration = generation,
                    lastVerifiedAt = now(),
                    failureCategory = null,
                    stateVersion = RemoteBackupStateVersion(stored.stateVersion.value + 1),
                ),
            )
            if (!applied) {
                return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            return advance(RemoteConnectPhase.COMPLETED, state)
        }

        // -- Encoding -------------------------------------------------------------------

        private fun baselineManifest(envelope: VaultKeyEnvelope): PublicationManifestV1 {
            val baseA = checkNotNull(state.baseA)
            val baseB = checkNotNull(state.baseB)
            val generation = checkNotNull(state.localGeneration)
            val draft = PublicationManifestV1(
                bootstrapSha256 = ZERO_SHA256,
                lineageId = state.lineageId,
                sourceVaultId = vaultId.value,
                writerEpoch = FIRST_EPOCH.value,
                activeDeviceId = state.deviceId,
                publicationProviderFileId = checkNotNull(state.publicationProviderFileId),
                publicationId = state.publicationId,
                publicationSequence = BASELINE_SEQUENCE,
                predecessorPublicationProviderFileId = null,
                predecessorPublicationId = null,
                predecessorPublicationSha256 = null,
                baseline = true,
                plannedClaimProviderFileId = checkNotNull(state.rootProviderFileId),
                plannedClaimId = state.rootClaimId,
                predecessorClaimProviderFileId = null,
                predecessorClaimId = null,
                predecessorClaimSha256 = null,
                ownershipClaimProviderFileId = null,
                ownershipClaimId = null,
                ownershipClaimSha256 = null,
                localGeneration = generation,
                publicationOperationId = operationId,
                currentBaseObjectId = baseA.logicalObjectId,
                fallbackBaseObjectId = baseB.logicalObjectId,
                inventory = listOf(baseA, baseB)
                    .map { base ->
                        RemoteInventoryItemV1(
                            logicalObjectId = base.logicalObjectId,
                            providerFileId = base.providerFileId,
                            role = RemoteObjectRoleV1.SNAPSHOT,
                            firstGeneration = generation,
                            lastGeneration = generation,
                            frameLength = base.frameLength,
                            frameSha256 = base.frameSha256,
                        )
                    }
                    .sortedBy(RemoteInventoryItemV1::logicalObjectId),
                recoveryCredentialGeneration = INITIAL_RECOVERY_CREDENTIAL_GENERATION,
            )
            return draft.copy(
                bootstrapSha256 = publicationCodec.bootstrapSha256(draft, envelope),
            )
        }

        private fun rootClaim(): OwnershipClaimV1 = OwnershipClaimV1(
            lineageId = state.lineageId,
            writerEpoch = FIRST_EPOCH.value,
            state = OwnershipStateV1.ACTIVE,
            predecessorProviderFileId = null,
            predecessorClaimId = null,
            predecessorClaimSha256 = null,
            providerFileId = checkNotNull(state.rootProviderFileId),
            claimId = state.rootClaimId,
            predecessorReservedSuccessorProviderFileId = null,
            sourceVaultId = vaultId.value,
            activeDeviceId = state.deviceId,
            nextSuccessorProviderFileId = checkNotNull(state.successorProviderFileId),
            baselinePublicationProviderFileId = checkNotNull(state.publicationProviderFileId),
            baselinePublicationId = state.publicationId,
            baselinePublicationSha256 = checkNotNull(state.baselineSha256),
            recoveryCredentialGeneration = INITIAL_RECOVERY_CREDENTIAL_GENERATION,
            creationOperationId = operationId,
            tombstoneId = null,
        )

        // -- Phase bookkeeping ----------------------------------------------------------

        private fun reached(phase: RemoteConnectPhase): Boolean =
            RemoteConnectPhase.valueOf(state.phase).ordinal >= phase.ordinal

        /**
         * Persists state durably without leaving the current phase, so intent
         * recorded before a network mutation survives a crash that happens
         * before the phase itself can advance.
         */
        private suspend fun record(next: RemoteConnectStateV1): StepResult<Unit> =
            advance(RemoteConnectPhase.valueOf(state.phase), next)

        private suspend fun advance(
            phase: RemoteConnectPhase,
            next: RemoteConnectStateV1,
        ): StepResult<Unit> {
            val expectedPhase = state.phase
            val updated = next.copy(phase = phase.name)
            val applied = remoteStateStore.transitionOperation(
                operationId = operationId,
                expectedPhase = expectedPhase,
                next = operation(updated),
            )
            if (!applied) return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            state = updated
            return StepResult.Ok(Unit)
        }

        private fun operation(value: RemoteConnectStateV1): RemoteBackupOperation {
            val encoded = encodeState(value)
            return try {
                RemoteBackupOperation(
                    operationId = operationId,
                    lineageId = CloudLineageId.parse(value.lineageId),
                    kind = CONNECT_OPERATION_KIND,
                    phase = value.phase,
                    targetEpoch = FIRST_EPOCH,
                    targetGeneration = value.localGeneration?.let(::BackupGeneration),
                    candidateClaimProviderId = value.rootProviderFileId?.let(ProviderObjectId::of),
                    candidatePublicationProviderId =
                        value.publicationProviderFileId?.let(ProviderObjectId::of),
                    stateBytes = encoded,
                    startedAt = now(),
                    updatedAt = now(),
                )
            } finally {
                encoded.fill(0)
            }
        }

        private suspend fun generateId(role: RemoteObjectRoleV1): ProviderObjectId =
            objectStore.generateProviderIds(1, role).single()

        private suspend fun readPublication(
            providerObjectId: ProviderObjectId,
        ): ReadSmallResult = try {
            objectStore.readSmall(
                providerObjectId,
                DefaultPublicationCatalog.MAX_PUBLICATION_FILE_BYTES,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ReadSmallResult.Failed(failure.toBoundedReason())
        }

        private fun failure(result: StepResult<Unit>): RemoteBackupConnectResult? =
            (result as? StepResult.Failed)?.let {
                RemoteBackupConnectResult.Failed(it.reason)
            }
    }

    private sealed interface StepResult<out T> {
        data class Ok<T>(val value: T) : StepResult<T>
        data class Failed(val reason: RemoteBackupFailureCategory) : StepResult<Nothing>
    }

    private companion object {
        const val CONNECT_OPERATION_PREFIX = "remote-connect:"
        const val SEPARATE_CONNECT_OPERATION_PREFIX = "remote-connect-separate:"
        const val CONNECT_OPERATION_KIND = "CONNECT"
        const val BASELINE_SEQUENCE = 0L
        const val INITIAL_RECOVERY_CREDENTIAL_GENERATION = 0L
        val FIRST_EPOCH = WriterEpoch(1)
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}

@OptIn(ExperimentalSerializationApi::class)
private object StrictRemoteConnectJson {
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowTrailingComma = false
    }
}

private fun encodeState(value: RemoteConnectStateV1): ByteArray =
    StrictRemoteConnectJson.json
        .encodeToString(RemoteConnectStateV1.serializer(), value)
        .toByteArray(Charsets.UTF_8)

private fun decodeState(source: ByteArray): RemoteConnectStateV1 = try {
    StrictRemoteConnectJson.json.decodeFromString(
        RemoteConnectStateV1.serializer(),
        source.toString(Charsets.UTF_8),
    )
} catch (failure: SerializationException) {
    throw IllegalArgumentException("Invalid remote connection state", failure)
}
