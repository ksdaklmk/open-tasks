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
import app.opentasks.core.domain.RemoteBackupCoordinator
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.domain.RemoteBackupRunResult
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.PublicationRef
import app.opentasks.core.model.PublicationSequence
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectLifecycle
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The exact ordered phases an interrupted publication resumes from. */
internal enum class RemotePublishPhase {
    OWNERSHIP_RESOLVED,
    LOCAL_GENERATION_VERIFIED,
    CANDIDATE_IDS_STORED,
    CANDIDATES_VERIFIED,
    OWNERSHIP_RECHECKED,
    PUBLICATION_CREATED,
    PUBLICATION_VERIFIED,
    FINAL_OWNERSHIP_RECHECKED,
    CHECKPOINTED,
    CLEANUP_STARTED,
    COMPLETED,
}

/**
 * One object the successor publication will name.
 *
 * A reused object carries no [localObjectId]: it is already present in the
 * authenticated predecessor and is copied forward verbatim. A planned object
 * names the Stage 2 local object it re-authenticates, and its declared bytes
 * stay null until they have been recorded durably — before the first network
 * mutation that could create them.
 */
@Serializable
internal data class RemotePublishObjectV1(
    val logicalObjectId: String,
    val providerFileId: String,
    val role: RemoteObjectRoleV1,
    val firstGeneration: Long,
    val lastGeneration: Long,
    val localObjectId: String?,
    val frameLength: Long?,
    val frameSha256: String?,
    val payloadSha256: String?,
) {
    override fun toString(): String = "RemotePublishObjectV1(role=$role)"
}

/**
 * Durable publication state. Every identity is generated once and persisted
 * here, so a restart republishes the same successor into the same reserved
 * slots instead of creating a second, competing publication.
 */
@Serializable
internal data class RemotePublishStateV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val phase: String,
    val lineageId: String,
    val deviceId: String,
    val writerEpoch: Long,
    val claimProviderFileId: String,
    val claimId: String,
    val predecessorProviderFileId: String,
    val predecessorPublicationId: String,
    val publicationId: String,
    val publicationProviderFileId: String?,
    val publicationSequence: Long,
    val localGeneration: Long,
    val recoveryCredentialGeneration: Long,
    val currentBaseLogicalObjectId: String?,
    val fallbackBaseLogicalObjectId: String?,
    val objects: List<RemotePublishObjectV1>,
    val publicationSha256: String?,
) {
    /** Never reveals a lineage, device, claim, publication, or provider identity. */
    override fun toString(): String = "RemotePublishStateV1(phase=$phase)"
}

/**
 * Routine create-only publication inside an established ownership epoch.
 *
 * One process-scoped run per coordinator executes at a time; a concurrent
 * caller joins the run in flight and observes its result, its failure, or its
 * cancellation. Releasing ownership and checking for a coalesced request is
 * one mutex transition, so a request either joins this run or becomes the next
 * owner.
 *
 * The publication order is forced by create-only storage and by what each
 * step has to prove before the next may run:
 * ownership is authenticated, the predecessor publication is authenticated,
 * Stage 2 is asked for a locally verified generation, every new immutable
 * candidate is uploaded and read back, ownership is rechecked, the successor
 * is created at a pre-generated slot, the whole catalog is re-resolved, and
 * ownership is checked once more. Only then does the remote checkpoint move.
 * Creation alone never advances it, and no failure, cancellation, or tip
 * change can.
 *
 * Every provider and local failure becomes a bounded [RemoteBackupRunResult];
 * no exception escapes as control flow and no message, identifier, or key
 * material reaches a caller.
 */
class DefaultRemoteBackupCoordinator(
    private val vaultId: VaultId,
    private val backupCoordinator: BackupCoordinator,
    private val backupStateStore: BackupStateStore,
    private val recoveryEnvelopeStore: RecoveryEnvelopeStore,
    private val contentKeyStore: VaultContentKeyStore,
    private val remoteStateStore: RemoteBackupStateStore,
    private val transferStore: RemoteBackupTransferStore,
    private val localObjectStore: LocalBackupObjectStore,
    private val remoteObjectCodec: RemoteObjectCodec,
    private val ownershipCodec: OwnershipClaimCodec,
    private val publicationCodec: PublicationCodec,
    private val now: () -> Instant = Instant::now,
    private val newPublicationId: () -> PublicationId = PublicationId::new,
    private val newLogicalObjectId: () -> RemoteLogicalObjectId = RemoteLogicalObjectId::new,
) : RemoteBackupCoordinator {

    private val mutex = Mutex()
    private var running = false
    private var pending = false
    private var completion: CompletableDeferred<RemoteBackupRunResult>? = null

    override suspend fun run(objectStore: CreateOnlyBackupObjectStore): RemoteBackupRunResult {
        var joined: CompletableDeferred<RemoteBackupRunResult>? = null
        mutex.withLock {
            if (running) {
                pending = true
                joined = checkNotNull(completion)
            } else {
                running = true
                completion = CompletableDeferred()
            }
        }
        joined?.let {
            // A joined caller must observe owner cancellation or failure rather
            // than silently losing its coalesced request.
            return it.await()
        }
        try {
            var outcome: RemoteBackupRunResult? = null
            while (true) {
                val result = execute(objectStore)
                // A coalesced pass that finds nothing new does not undo the
                // generation an earlier pass of this same flight verified.
                outcome = if (result is RemoteBackupRunResult.NoChanges &&
                    outcome is RemoteBackupRunResult.Verified
                ) {
                    outcome
                } else {
                    result
                }
                var completed: CompletableDeferred<RemoteBackupRunResult>? = null
                val runAgain = mutex.withLock {
                    if (pending && result.allowsAnotherPass()) {
                        pending = false
                        true
                    } else {
                        // Checking for pending work and releasing ownership is
                        // one transition, so a concurrent request either joins
                        // this flight or becomes the next owner.
                        running = false
                        pending = false
                        completed = completion
                        completion = null
                        false
                    }
                }
                if (!runAgain) {
                    val settled = checkNotNull(outcome)
                    completed?.complete(settled)
                    return settled
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                val completed = mutex.withLock {
                    running = false
                    pending = false
                    completion.also { completion = null }
                }
                completed?.completeExceptionally(failure)
            }
            throw failure
        }
    }

    private suspend fun execute(
        objectStore: CreateOnlyBackupObjectStore,
    ): RemoteBackupRunResult {
        val configuration = remoteStateStore.active(vaultId)
            ?: return RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.LOCAL_STORAGE)
        if (configuration.lifecycle != RemoteBackupLifecycle.ACTIVE) {
            return RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.LOCAL_STORAGE)
        }
        val operationId = PUBLISH_OPERATION_PREFIX + vaultId.value
        val stored = remoteStateStore.operation(operationId)
        val resumed = stored?.let { operation ->
            runBounded {
                require(operation.kind == PUBLISH_OPERATION_KIND) {
                    "Another operation already owns this identifier"
                }
                decodePublishState(operation.stateBytes)
            }
        }
        // A recorded operation that cannot be decoded is never restarted from
        // scratch: doing so would create a second publication beside remote
        // objects this installation can no longer account for.
        if (stored != null && resumed == null) {
            return RemoteBackupRunResult.Blocked(
                RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
        }
        val contentKey = try {
            contentKeyStore.openExisting(vaultId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return RemoteBackupRunResult.Blocked(RemoteBackupFailureCategory.LOCAL_STORAGE)
        }
        return try {
            Session(
                objectStore = objectStore,
                operationId = operationId,
                configuration = configuration,
                contentKey = contentKey,
            ).run(hasStoredOperation = stored != null, resumed = resumed)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failure.toBoundedReason().toRunResult()
        } finally {
            contentKey.close()
        }
    }

    private inner class Session(
        private val objectStore: CreateOnlyBackupObjectStore,
        private val operationId: String,
        private val configuration: RemoteBackupConfiguration,
        private val contentKey: VaultKey,
    ) {
        private val chainStore = DefaultOwnershipChainStore(objectStore, ownershipCodec)
        private val catalog = DefaultPublicationCatalog(objectStore, publicationCodec)
        private val lineageId = configuration.lineageId
        private val claim = checkNotNull(configuration.ownershipClaim) {
            "An active remote lineage always holds an ownership claim"
        }
        private val epoch = claim.writerEpoch

        private lateinit var state: RemotePublishStateV1
        private lateinit var tip: VerifiedOwnershipClaim
        private lateinit var predecessor: VerifiedPublication
        private lateinit var localState: BackupStateEntity
        private var published: VerifiedPublication? = null

        suspend fun run(
            hasStoredOperation: Boolean,
            resumed: RemotePublishStateV1?,
        ): RemoteBackupRunResult {
            tip = when (val resolution = resolveTip()) {
                is StepResult.Failed -> return resolution.reason.toRunResult()
                is StepResult.Ok -> resolution.value
            }
            val usable = resumed?.takeIf { candidate ->
                candidate.lineageId == lineageId.value &&
                    candidate.writerEpoch == epoch.value &&
                    candidate.claimProviderFileId == claim.providerId.value &&
                    candidate.claimId == claim.logicalId.value &&
                    RemotePublishPhase.valueOf(candidate.phase) >=
                        RemotePublishPhase.CANDIDATE_IDS_STORED &&
                    candidate.phase != RemotePublishPhase.COMPLETED.name
            }
            val adopted = when (val resolution = resolvePublications(usable)) {
                is StepResult.Failed -> return resolution.reason.toRunResult()
                is StepResult.Ok -> resolution.value
            }
            predecessor = adopted.predecessor
            published = adopted.created

            failure(captureLocalGeneration())?.let { return it }
            val continues = usable != null &&
                usable.predecessorProviderFileId ==
                predecessor.manifest.publicationProviderFileId &&
                usable.predecessorPublicationId == predecessor.manifest.publicationId
            val resumable = when {
                !continues -> false
                else -> when (val fulfillable = isPlanFulfillable(checkNotNull(usable))) {
                    is StepResult.Failed -> return fulfillable.reason.toRunResult()
                    is StepResult.Ok -> fulfillable.value
                }
            }
            if (resumable) {
                state = checkNotNull(usable)
            } else {
                when (val started = start(hasStoredOperation, resumed)) {
                    is StepResult.Failed -> return started.reason.toRunResult()
                    is StepResult.Ok -> if (!started.value) return RemoteBackupRunResult.NoChanges
                }
            }

            failure(planCandidates())?.let { return it }
            failure(verifyCandidates())?.let { return it }
            failure(recheckOwnership(RemotePublishPhase.OWNERSHIP_RECHECKED))?.let { return it }
            failure(createPublication())?.let { return it }
            failure(verifyPublication())?.let { return it }
            failure(
                recheckOwnership(RemotePublishPhase.FINAL_OWNERSHIP_RECHECKED),
            )?.let { return it }
            failure(checkpoint())?.let { return it }
            failure(cleanUp())?.let { return it }
            return RemoteBackupRunResult.Verified(BackupGeneration(state.localGeneration))
        }

        // -- Authority ------------------------------------------------------------------

        /**
         * Resolves the chain from the persisted root and requires the tip to be
         * exactly the claim this installation holds. A newer claim, another
         * device, another lineage, or another source vault is ownership loss;
         * an unreadable chain fails closed on its own category.
         */
        private suspend fun resolveTip(): StepResult<VerifiedOwnershipClaim> =
            when (
                val resolution =
                    chainStore.resolve(configuration.rootClaimProviderId, contentKey)
            ) {
                is OwnershipResolution.Blocked -> StepResult.Failed(resolution.reason)
                is OwnershipResolution.Terminated ->
                    StepResult.Failed(RemoteBackupFailureCategory.TERMINATED)

                is OwnershipResolution.Active -> {
                    val current = resolution.tip
                    val holds = current.completeSha256 == claim.sha256 &&
                        current.claim.claimId == claim.logicalId.value &&
                        current.claim.providerFileId == claim.providerId.value &&
                        current.claim.writerEpoch == epoch.value &&
                        current.claim.lineageId == lineageId.value &&
                        current.claim.sourceVaultId == vaultId.value &&
                        current.claim.activeDeviceId == configuration.activeDeviceId?.value
                    if (holds) {
                        StepResult.Ok(current)
                    } else {
                        StepResult.Failed(RemoteBackupFailureCategory.OWNERSHIP_LOST)
                    }
                }
            }

        /**
         * Authenticates the publication catalog under the resolved tip.
         *
         * The remote tip is normally the publication this installation
         * checkpointed. It may instead be the successor a previous attempt
         * created but could not checkpoint; that is adopted only when the
         * durable operation planned exactly that publication identity and the
         * retained predecessor is still the checkpointed one. Anything else is
         * ambiguity.
         */
        private suspend fun resolvePublications(
            usable: RemotePublishStateV1?,
        ): StepResult<AdoptedPublications> {
            val candidates = when (
                val discovery = catalog.discoverBootstraps(lineageId, epoch, claim.providerId)
            ) {
                is PublicationCandidateDiscovery.Blocked ->
                    return StepResult.Failed(discovery.reason)

                is PublicationCandidateDiscovery.Discovered -> discovery.candidates
            }
            val resolved = when (val resolution = catalog.resolve(tip, candidates, contentKey)) {
                is PublicationResolution.Failed -> return StepResult.Failed(resolution.reason)
                is PublicationResolution.Resolved -> resolution
            }
            val checkpointed = checkNotNull(configuration.currentPublication)
            if (isCheckpointed(resolved.current, checkpointed)) {
                return StepResult.Ok(AdoptedPublications(resolved.current, null))
            }
            val previous = resolved.previous
            val ours = usable != null &&
                resolved.current.manifest.publicationId == usable.publicationId &&
                resolved.current.manifest.publicationProviderFileId ==
                usable.publicationProviderFileId &&
                previous != null &&
                isCheckpointed(previous, checkpointed)
            return if (ours) {
                StepResult.Ok(AdoptedPublications(checkNotNull(previous), resolved.current))
            } else {
                StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
        }

        private fun isCheckpointed(
            publication: VerifiedPublication,
            checkpointed: PublicationRef,
        ): Boolean = publication.manifest.publicationId == checkpointed.logicalId.value &&
            publication.manifest.publicationProviderFileId == checkpointed.providerId.value &&
            publication.completeSha256 == checkpointed.sha256

        /** Rechecks the tip and refuses to continue if ownership moved at all. */
        private suspend fun recheckOwnership(phase: RemotePublishPhase): StepResult<Unit> {
            if (reached(phase)) return StepResult.Ok(Unit)
            return when (val resolution = resolveTip()) {
                is StepResult.Failed -> StepResult.Failed(resolution.reason)
                is StepResult.Ok -> advance(phase, state)
            }
        }

        // -- Local generation -----------------------------------------------------------

        /** Requires a Stage 2 complete base plus its verified segment coverage. */
        private suspend fun captureLocalGeneration(): StepResult<Unit> {
            try {
                backupCoordinator.request()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            val local = backupStateStore.get(vaultId)
                ?: return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            val baseGeneration = local.lastVerifiedSnapshotGeneration
            if (local.currentBaseObjectId == null || baseGeneration == null) {
                return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            if ((local.latestVerifiedSegmentGeneration ?: baseGeneration) < baseGeneration) {
                return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            localState = local
            return StepResult.Ok(Unit)
        }

        private fun targetGeneration(): Long {
            val baseGeneration = checkNotNull(localState.lastVerifiedSnapshotGeneration)
            val segmentGeneration = localState.latestVerifiedSegmentGeneration
            return maxOf(segmentGeneration ?: baseGeneration, baseGeneration)
        }

        /**
         * Starts a new publication run, recording its first phase durably.
         *
         * `Ok(false)` means the lineage already publishes this local generation
         * under the current recovery credential, so nothing is created at all —
         * not even a durable operation record.
         */
        private suspend fun start(
            hasStoredOperation: Boolean,
            resumed: RemotePublishStateV1?,
        ): StepResult<Boolean> {
            val target = targetGeneration()
            val publishedGeneration = predecessor.manifest.localGeneration
            if (target < publishedGeneration) {
                // Local generations never decrease; a regression means the two
                // sides no longer describe one history.
                return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            val credentialGeneration = configuration.recoveryCredentialGeneration
            val changed = target > publishedGeneration ||
                credentialGeneration > predecessor.manifest.recoveryCredentialGeneration
            if (!changed) return StepResult.Ok(false)

            val fresh = RemotePublishStateV1(
                phase = RemotePublishPhase.OWNERSHIP_RESOLVED.name,
                lineageId = lineageId.value,
                deviceId = checkNotNull(configuration.activeDeviceId).value,
                writerEpoch = epoch.value,
                claimProviderFileId = claim.providerId.value,
                claimId = claim.logicalId.value,
                predecessorProviderFileId = predecessor.manifest.publicationProviderFileId,
                predecessorPublicationId = predecessor.manifest.publicationId,
                publicationId = newPublicationId().value,
                publicationProviderFileId = null,
                publicationSequence = predecessor.manifest.publicationSequence + 1,
                localGeneration = publishedGeneration,
                recoveryCredentialGeneration = credentialGeneration,
                currentBaseLogicalObjectId = null,
                fallbackBaseLogicalObjectId = null,
                objects = emptyList(),
                publicationSha256 = null,
            )
            val applied = if (hasStoredOperation) {
                remoteStateStore.transitionOperation(
                    operationId = operationId,
                    expectedPhase = checkNotNull(resumed).phase,
                    next = operation(fresh),
                )
            } else {
                remoteStateStore.putOperation(operation(fresh))
                true
            }
            if (!applied) return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            state = fresh
            return when (
                val advanced = advance(
                    RemotePublishPhase.LOCAL_GENERATION_VERIFIED,
                    fresh.copy(localGeneration = target),
                )
            ) {
                is StepResult.Failed -> StepResult.Failed(advanced.reason)
                is StepResult.Ok -> StepResult.Ok(true)
            }
        }

        /**
         * Decides whether a durably frozen plan can still be completed.
         *
         * A plan names the exact Stage 2 objects it re-authenticates, and
         * Stage 2 owns its own retention: a base rotation can drop a local
         * object a frozen plan still names. If that object was never created
         * remotely either, the plan can never be fulfilled and would fail
         * identically on every later run, so it is abandoned in favour of a
         * fresh plan. The abandoned reservations are ordinary create-only
         * residue that cleanup already accounts for.
         *
         * Only a provably empty reserved slot may re-plan. An occupied,
         * corrupt, or unreadable slot keeps its existing fail-closed handling,
         * so a plan is never discarded to escape ambiguity.
         */
        private suspend fun isPlanFulfillable(
            plan: RemotePublishStateV1,
        ): StepResult<Boolean> {
            if (RemotePublishPhase.valueOf(plan.phase) >=
                RemotePublishPhase.CANDIDATES_VERIFIED
            ) {
                return StepResult.Ok(true)
            }
            val localObjectIds = try {
                localObjectStore.objectIds().toSet()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            plan.objects.forEach { candidate ->
                val localObjectId = candidate.localObjectId ?: return@forEach
                if (localObjectId in localObjectIds) return@forEach
                // Nothing was ever encoded for this object, so nothing can be
                // occupying its slot either.
                if (candidate.frameSha256 == null) return StepResult.Ok(false)
                when (val adopted = adoptPlannedObject(candidate)) {
                    is StepResult.Failed -> return adopted
                    is StepResult.Ok -> if (!adopted.value) return StepResult.Ok(false)
                }
            }
            return StepResult.Ok(true)
        }

        // -- Candidate selection --------------------------------------------------------

        /**
         * Selects the minimal successor inventory and reserves one provider
         * slot for every object that does not already exist.
         *
         * Both declared complete bases cover the *same* local base generation:
         * one is reused from the authenticated predecessor when it already
         * covers that generation, otherwise a fresh, independently identified
         * copy is uploaded, exactly as epoch one does. Segments then bridge
         * that base generation to the published generation.
         *
         * The same-generation pair is a deliberate policy choice, not a format
         * constraint. [PublicationCodec] validates each declared base by
         * walking the retained segments from that base's own last generation,
         * so an older base is perfectly legal whenever retained segments bridge
         * it. This coordinator declines that option — the
         * `firstGeneration == baseGeneration` filter below is where — because
         * two same-generation copies keep the epoch-one safety property that
         * both bases decode to identical content, at the cost of one extra base
         * upload per Stage 2 base rotation. The previous verified base is still
         * retained: it is named by the retained previous publication, which
         * cleanup never prunes.
         */
        private suspend fun planCandidates(): StepResult<Unit> {
            if (reached(RemotePublishPhase.CANDIDATE_IDS_STORED)) return StepResult.Ok(Unit)
            val baseGeneration = checkNotNull(localState.lastVerifiedSnapshotGeneration)
            val localBaseObjectId = checkNotNull(localState.currentBaseObjectId)
            val inventory = predecessor.manifest.inventory
            val reusableBases = listOfNotNull(
                inventory.firstOrNull {
                    it.logicalObjectId == predecessor.manifest.currentBaseObjectId
                },
                inventory.firstOrNull {
                    it.logicalObjectId == predecessor.manifest.fallbackBaseObjectId
                },
            ).filter {
                it.role == RemoteObjectRoleV1.SNAPSHOT &&
                    it.firstGeneration == baseGeneration
            }

            val planned = mutableListOf<RemotePublishObjectV1>()
            val bases = (0 until 2).map { index ->
                reusableBases.getOrNull(index)?.let(::reused)
                    ?: plannedObject(
                        role = RemoteObjectRoleV1.SNAPSHOT,
                        localObjectId = localBaseObjectId,
                        firstGeneration = baseGeneration,
                        lastGeneration = baseGeneration,
                    ).also { planned += it }
            }
            val segments = when (val chain = segmentChain(baseGeneration, targetGeneration())) {
                is StepResult.Failed -> return StepResult.Failed(chain.reason)
                is StepResult.Ok -> chain.value
            }
            segments.forEach { if (it.localObjectId != null) planned += it }

            val reservations = when (val reserved = reserveProviderIds(planned)) {
                is StepResult.Failed -> return StepResult.Failed(reserved.reason)
                is StepResult.Ok -> reserved.value
            }
            val objects = (bases + segments).map { candidate ->
                reservations[candidate.logicalObjectId]?.let { candidate.copy(providerFileId = it) }
                    ?: candidate
            }
            // Every object must hold one distinct identity and one distinct
            // reserved slot; an unreplaced placeholder or a repeated slot would
            // let one object occupy another's exact provider file.
            if (objects.map { it.logicalObjectId }.toSet().size != objects.size ||
                objects.map { it.providerFileId }.toSet().size != objects.size ||
                objects.any { it.providerFileId == UNRESERVED_PROVIDER_FILE_ID }
            ) {
                return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            return advance(
                RemotePublishPhase.CANDIDATE_IDS_STORED,
                state.copy(
                    publicationProviderFileId = reservations.getValue(PUBLICATION_RESERVATION),
                    currentBaseLogicalObjectId = bases[0].logicalObjectId,
                    fallbackBaseLogicalObjectId = bases[1].logicalObjectId,
                    objects = objects,
                ),
            )
        }

        /**
         * Builds the shortest gap-free segment chain from [baseGeneration] to
         * [target], preferring objects the predecessor already published so a
         * generation is never uploaded twice.
         */
        private fun segmentChain(
            baseGeneration: Long,
            target: Long,
        ): StepResult<List<RemotePublishObjectV1>> {
            val pool = mutableListOf<RemotePublishObjectV1>()
            predecessor.manifest.inventory
                .filter { it.role == RemoteObjectRoleV1.SEGMENT }
                .forEach { pool += reused(it) }
            val localSegments = try {
                localObjectStore.objectIds().mapNotNull(::localSegmentRange)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            localSegments.forEach { (objectId, range) ->
                pool += plannedObject(
                    role = RemoteObjectRoleV1.SEGMENT,
                    localObjectId = objectId,
                    firstGeneration = range.first,
                    lastGeneration = range.second,
                )
            }
            val chain = mutableListOf<RemotePublishObjectV1>()
            var covered = baseGeneration
            while (covered < target) {
                val next = pool
                    .filter { it.firstGeneration <= covered + 1 && it.lastGeneration > covered }
                    .sortedWith(
                        compareByDescending<RemotePublishObjectV1> { it.lastGeneration }
                            .thenBy { if (it.localObjectId == null) 0 else 1 }
                            .thenBy { it.logicalObjectId },
                    )
                    .firstOrNull()
                    ?: return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
                chain += next
                covered = next.lastGeneration
                if (chain.size > MAX_PUBLISHED_SEGMENTS) {
                    return StepResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
                }
            }
            return StepResult.Ok(chain)
        }

        /** Reserves one distinct provider slot per new object plus the publication. */
        private suspend fun reserveProviderIds(
            planned: List<RemotePublishObjectV1>,
        ): StepResult<Map<String, String>> {
            val reservations = mutableMapOf<String, String>()
            val issued = mutableListOf<String>()
            RemoteObjectRoleV1.entries.forEach { role ->
                val forRole = planned.filter { it.role == role }
                if (forRole.isEmpty()) return@forEach
                val ids = objectStore.generateProviderIds(forRole.size, role)
                if (ids.size != forRole.size) {
                    return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
                }
                forRole.forEachIndexed { index, candidate ->
                    reservations[candidate.logicalObjectId] = ids[index].value
                    issued += ids[index].value
                }
            }
            val publicationProviderId = objectStore
                .generateProviderIds(1, RemoteObjectRoleV1.PUBLICATION)
                .single()
            reservations[PUBLICATION_RESERVATION] = publicationProviderId.value
            issued += publicationProviderId.value
            // A provider that repeats a generated ID would let one object
            // occupy another's exact slot.
            if (issued.distinct().size != issued.size) {
                return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            return StepResult.Ok(reservations)
        }

        // -- Candidate publication ------------------------------------------------------

        /**
         * Uploads and reads back every planned object.
         *
         * Re-encoding produces a fresh AES-GCM nonce, so a restart can never
         * reproduce the bytes a previous attempt uploaded. The intended bytes
         * are therefore recorded durably before the first network mutation: a
         * restart that finds those exact bytes present adopts them, and one
         * that finds the slot empty re-encodes.
         */
        private suspend fun verifyCandidates(): StepResult<Unit> {
            if (reached(RemotePublishPhase.CANDIDATES_VERIFIED)) return StepResult.Ok(Unit)
            state.objects.forEachIndexed { index, candidate ->
                if (candidate.localObjectId == null) return@forEachIndexed
                if (candidate.frameSha256 != null) {
                    when (val adopted = adoptPlannedObject(candidate)) {
                        is StepResult.Failed -> return adopted
                        is StepResult.Ok -> if (adopted.value) return@forEachIndexed
                    }
                    // The reserved slot is empty, so this object still has to be
                    // created. A partial upload may already have registered the
                    // previous attempt's expected bytes, and the re-encode below
                    // cannot reproduce them, so that expectation is cleared
                    // before it can reject the fresh frame.
                    transferStore.removeObjectState(
                        lineageId,
                        RemoteLogicalObjectId.of(candidate.logicalObjectId),
                    )
                }
                val uploaded = uploadCandidate(index, candidate)
                if (uploaded is StepResult.Failed) return uploaded
            }
            return advance(RemotePublishPhase.CANDIDATES_VERIFIED, state)
        }

        private suspend fun uploadCandidate(
            index: Int,
            candidate: RemotePublishObjectV1,
        ): StepResult<Unit> {
            val logicalObjectId = RemoteLogicalObjectId.of(candidate.logicalObjectId)
            val remote = try {
                remoteObjectCodec.reauthenticateLocalObject(
                    localObjectId = checkNotNull(candidate.localObjectId),
                    vaultId = vaultId,
                    lineageId = lineageId,
                    logicalObjectId = logicalObjectId,
                    contentKey = contentKey,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            }
            val record = candidate.copy(
                frameLength = remote.frameLength,
                frameSha256 = remote.frameSha256.value,
                payloadSha256 = remote.payloadSha256.value,
            )
            val planning = record(state.copy(objects = state.objects.replacedAt(index, record)))
            if (planning is StepResult.Failed) {
                remote.close()
                return planning
            }
            remote.use {
                val upload = objectStore.uploadImmutable(
                    ImmutableUploadRequest(
                        lineageId = lineageId,
                        writerEpoch = epoch,
                        ownerDeviceId = CloudDeviceId.parse(state.deviceId),
                        operationId = operationId,
                        logicalObjectId = logicalObjectId,
                        providerObjectId = ProviderObjectId.of(record.providerFileId),
                        role = record.role,
                        firstGeneration = BackupGeneration(record.firstGeneration),
                        lastGeneration = BackupGeneration(record.lastGeneration),
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

                    is ImmutableUploadResult.Failed -> return StepResult.Failed(upload.reason)
                }
            }
            return when (val adopted = adoptPlannedObject(record)) {
                is StepResult.Failed -> adopted
                is StepResult.Ok ->
                    if (adopted.value) {
                        StepResult.Ok(Unit)
                    } else {
                        StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
                    }
            }
        }

        /**
         * Downloads the exact bytes [planned] records and authenticates them.
         *
         * A complete base is additionally decrypted at the persisted lineage
         * plus remote logical object and its decoded payload compared, so a
         * base that was swapped or re-identified is rejected. `Ok(false)` means
         * the reserved slot is empty; anything but the planned bytes fails
         * closed, and no alternate slot is ever reserved.
         */
        private suspend fun adoptPlannedObject(
            planned: RemotePublishObjectV1,
        ): StepResult<Boolean> {
            val expected = runBounded { Sha256Digest.of(checkNotNull(planned.frameSha256)) }
                ?: return StepResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            val downloaded = objectStore.downloadImmutable(
                providerObjectId = ProviderObjectId.of(planned.providerFileId),
                maximumBytes = checkNotNull(planned.frameLength),
                expectedSha256 = expected,
            )
            return when (downloaded) {
                is ImmutableDownloadResult.Downloaded -> downloaded.frame.use { frame ->
                    if (planned.role != RemoteObjectRoleV1.SNAPSHOT) {
                        return@use StepResult.Ok(true)
                    }
                    val base = runBounded {
                        remoteObjectCodec.readRemoteBase(
                            frame = frame,
                            lineageId = lineageId,
                            logicalObjectId = RemoteLogicalObjectId.of(planned.logicalObjectId),
                            contentKey = contentKey,
                        )
                    } ?: return@use StepResult.Failed(
                        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                    )
                    if (base.coveredGeneration != planned.lastGeneration ||
                        base.payloadSha256.value != planned.payloadSha256
                    ) {
                        StepResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
                    } else {
                        StepResult.Ok(true)
                    }
                }

                ImmutableDownloadResult.Missing -> StepResult.Ok(false)
                ImmutableDownloadResult.Corrupt ->
                    StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)

                is ImmutableDownloadResult.Failed -> StepResult.Failed(downloaded.reason)
            }
        }

        // -- Publication ----------------------------------------------------------------

        private suspend fun createPublication(): StepResult<Unit> {
            if (reached(RemotePublishPhase.PUBLICATION_CREATED)) return StepResult.Ok(Unit)
            val envelope = recoveryEnvelopeStore.get(vaultId)
                ?: return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            // The envelope holds wrapped key material; it is cleared as soon as
            // the bootstrap that publishes it has been encoded.
            val encoded = try {
                publicationCodec.encode(successorManifest(envelope), envelope, contentKey)
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
                recordPublicationCandidate(providerObjectId, encoded)
                catalog.create(providerObjectId, ownedCopyOf(encoded), contentKey)
            } finally {
                encoded.fill(0)
            }
            return when (created) {
                is PublicationCreateResult.Created ->
                    advance(RemotePublishPhase.PUBLICATION_CREATED, state)

                is PublicationCreateResult.OccupiedByExpected ->
                    advance(RemotePublishPhase.PUBLICATION_CREATED, state)

                // Re-encoding produces a fresh nonce, so a previous attempt's
                // own publication occupies the slot with different bytes. Adopt
                // it only when it authenticates to the identity this run
                // planned, which nothing without the content key can forge.
                PublicationCreateResult.OccupiedByDifferent ->
                    when (val occupant = readPlannedPublication()) {
                        is StepResult.Failed -> occupant
                        is StepResult.Ok ->
                            advance(RemotePublishPhase.PUBLICATION_CREATED, state)
                    }

                is PublicationCreateResult.Failed -> StepResult.Failed(created.reason)
            }
        }

        /**
         * Re-resolves the whole catalog and requires the published successor to
         * be the exact publication this run planned, retaining the predecessor
         * it was built from.
         */
        private suspend fun verifyPublication(): StepResult<Unit> {
            if (reached(RemotePublishPhase.PUBLICATION_VERIFIED)) {
                return if (published != null) {
                    StepResult.Ok(Unit)
                } else {
                    StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
                }
            }
            val candidates = when (
                val discovery = catalog.discoverBootstraps(lineageId, epoch, claim.providerId)
            ) {
                is PublicationCandidateDiscovery.Blocked ->
                    return StepResult.Failed(discovery.reason)

                is PublicationCandidateDiscovery.Discovered -> discovery.candidates
            }
            val resolved = when (val resolution = catalog.resolve(tip, candidates, contentKey)) {
                is PublicationResolution.Failed -> return StepResult.Failed(resolution.reason)
                is PublicationResolution.Resolved -> resolution
            }
            val manifest = resolved.current.manifest
            val expected = manifest.publicationId == state.publicationId &&
                manifest.publicationProviderFileId == state.publicationProviderFileId &&
                manifest.publicationSequence == state.publicationSequence &&
                manifest.localGeneration == state.localGeneration &&
                resolved.previous?.manifest?.publicationId == state.predecessorPublicationId
            if (!expected) {
                return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            markPublicationVerified(resolved.current)
            published = resolved.current
            return advance(
                RemotePublishPhase.PUBLICATION_VERIFIED,
                state.copy(publicationSha256 = resolved.current.completeSha256.value),
            )
        }

        /**
         * Reads the publication slot this run reserved and requires the
         * occupant to be exactly the successor it planned. Any other occupant
         * is ambiguity.
         */
        private suspend fun readPlannedPublication(): StepResult<VerifiedPublication> {
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
                manifest.publicationSequence == state.publicationSequence &&
                !manifest.baseline &&
                manifest.writerEpoch == epoch.value &&
                manifest.activeDeviceId == state.deviceId &&
                manifest.sourceVaultId == vaultId.value &&
                manifest.ownershipClaimProviderFileId == state.claimProviderFileId &&
                manifest.ownershipClaimId == state.claimId &&
                manifest.predecessorPublicationId == state.predecessorPublicationId
            if (!planned) {
                return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            return StepResult.Ok(verified)
        }

        // -- Checkpoint and cleanup -----------------------------------------------------

        private suspend fun checkpoint(): StepResult<Unit> {
            if (reached(RemotePublishPhase.CHECKPOINTED)) return StepResult.Ok(Unit)
            val current = checkNotNull(published)
            if (current.manifest.localGeneration != state.localGeneration) {
                return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            val generation = BackupGeneration(state.localGeneration)
            val stored = remoteStateStore.known(lineageId)
                ?: return StepResult.Failed(RemoteBackupFailureCategory.LOCAL_STORAGE)
            val applied = remoteStateStore.compareAndSet(
                lineageId = lineageId,
                expected = stored.stateVersion,
                next = stored.copy(
                    currentPublication = PublicationRef(
                        providerId = ProviderObjectId.of(
                            current.manifest.publicationProviderFileId,
                        ),
                        logicalId = PublicationId.parse(current.manifest.publicationId),
                        sha256 = current.completeSha256,
                        sequence = PublicationSequence(current.manifest.publicationSequence),
                        generation = generation,
                    ),
                    previousPublication = PublicationRef(
                        providerId = ProviderObjectId.of(
                            predecessor.manifest.publicationProviderFileId,
                        ),
                        logicalId = PublicationId.parse(predecessor.manifest.publicationId),
                        sha256 = predecessor.completeSha256,
                        sequence = PublicationSequence(
                            predecessor.manifest.publicationSequence,
                        ),
                        generation = BackupGeneration(predecessor.manifest.localGeneration),
                    ),
                    lastVerifiedGeneration = generation,
                    lastVerifiedAt = now(),
                    failureCategory = null,
                    stateVersion = RemoteBackupStateVersion(stored.stateVersion.value + 1),
                ),
            )
            if (!applied) {
                return StepResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            return advance(RemotePublishPhase.CHECKPOINTED, state)
        }

        /**
         * Runs bounded retention cleanup after — never before — the checkpoint.
         *
         * Cleanup is opportunistic: the generation is already verified and
         * durable, so a provider or local problem while pruning leaves residue
         * for a later run rather than turning a completed publication into a
         * failure.
         */
        private suspend fun cleanUp(): StepResult<Unit> {
            if (!reached(RemotePublishPhase.CLEANUP_STARTED)) {
                val started = advance(RemotePublishPhase.CLEANUP_STARTED, state)
                if (started is StepResult.Failed) return started
            }
            if (!reached(RemotePublishPhase.COMPLETED)) {
                val cleanup = DefaultNamespaceSafeRemoteCleanup(
                    objectStore = objectStore,
                    chainStore = chainStore,
                    transferStore = transferStore,
                    lineageId = lineageId,
                    rootClaimProviderId = configuration.rootClaimProviderId,
                    contentKey = contentKey,
                )
                try {
                    var batches = 0
                    while (batches < MAX_CLEANUP_BATCHES) {
                        val result = cleanup.runBatch(
                            ownership = tip,
                            current = checkNotNull(published),
                            previous = predecessor,
                            now = now(),
                        )
                        batches += 1
                        if (result.stoppedForOwnershipChange || result.deletedCount == 0) break
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Residue stays; the next verified publication retries it.
                }
            }
            return advance(RemotePublishPhase.COMPLETED, state)
        }

        // -- Encoding -------------------------------------------------------------------

        private fun successorManifest(envelope: VaultKeyEnvelope): PublicationManifestV1 {
            val draft = PublicationManifestV1(
                bootstrapSha256 = ZERO_SHA256,
                lineageId = state.lineageId,
                sourceVaultId = vaultId.value,
                writerEpoch = epoch.value,
                activeDeviceId = state.deviceId,
                publicationProviderFileId = checkNotNull(state.publicationProviderFileId),
                publicationId = state.publicationId,
                publicationSequence = state.publicationSequence,
                predecessorPublicationProviderFileId = state.predecessorProviderFileId,
                predecessorPublicationId = state.predecessorPublicationId,
                predecessorPublicationSha256 = predecessor.completeSha256.value,
                baseline = false,
                plannedClaimProviderFileId = null,
                plannedClaimId = null,
                predecessorClaimProviderFileId = null,
                predecessorClaimId = null,
                predecessorClaimSha256 = null,
                ownershipClaimProviderFileId = state.claimProviderFileId,
                ownershipClaimId = state.claimId,
                ownershipClaimSha256 = tip.completeSha256.value,
                localGeneration = state.localGeneration,
                publicationOperationId = operationId,
                currentBaseObjectId = checkNotNull(state.currentBaseLogicalObjectId),
                fallbackBaseObjectId = checkNotNull(state.fallbackBaseLogicalObjectId),
                inventory = state.objects
                    .map { candidate ->
                        RemoteInventoryItemV1(
                            logicalObjectId = candidate.logicalObjectId,
                            providerFileId = candidate.providerFileId,
                            role = candidate.role,
                            firstGeneration = candidate.firstGeneration,
                            lastGeneration = candidate.lastGeneration,
                            frameLength = checkNotNull(candidate.frameLength),
                            frameSha256 = checkNotNull(candidate.frameSha256),
                        )
                    }
                    .sortedBy(RemoteInventoryItemV1::logicalObjectId),
                recoveryCredentialGeneration = state.recoveryCredentialGeneration,
            )
            return draft.copy(bootstrapSha256 = publicationCodec.bootstrapSha256(draft, envelope))
        }

        private fun reused(item: RemoteInventoryItemV1): RemotePublishObjectV1 =
            RemotePublishObjectV1(
                logicalObjectId = item.logicalObjectId,
                providerFileId = item.providerFileId,
                role = item.role,
                firstGeneration = item.firstGeneration,
                lastGeneration = item.lastGeneration,
                localObjectId = null,
                frameLength = item.frameLength,
                frameSha256 = item.frameSha256,
                payloadSha256 = null,
            )

        private fun plannedObject(
            role: RemoteObjectRoleV1,
            localObjectId: String,
            firstGeneration: Long,
            lastGeneration: Long,
        ): RemotePublishObjectV1 = RemotePublishObjectV1(
            logicalObjectId = newLogicalObjectId().value,
            providerFileId = UNRESERVED_PROVIDER_FILE_ID,
            role = role,
            firstGeneration = firstGeneration,
            lastGeneration = lastGeneration,
            localObjectId = localObjectId,
            frameLength = null,
            frameSha256 = null,
            payloadSha256 = null,
        )

        // -- Local transfer bookkeeping -------------------------------------------------

        /**
         * Records the publication's own first-observed time before it is
         * created, so retention can later prove how long an abandoned or
         * superseded publication has existed without trusting provider time.
         */
        private suspend fun recordPublicationCandidate(
            providerObjectId: ProviderObjectId,
            encoded: ByteArray,
        ) {
            val logicalObjectId = RemoteLogicalObjectId.of(state.publicationId)
            val generation = BackupGeneration(state.localGeneration)
            val existing = transferStore.objectState(lineageId, logicalObjectId)
            val candidate = RemoteBackupObject(
                lineageId = lineageId,
                logicalObjectId = logicalObjectId,
                providerObjectId = providerObjectId,
                role = RemoteObjectRoleV1.PUBLICATION,
                writerEpoch = epoch,
                ownerDeviceId = CloudDeviceId.parse(state.deviceId),
                operationId = operationId,
                firstGeneration = generation,
                lastGeneration = generation,
                frameLength = encoded.size.toLong(),
                frameSha256 = Sha256Digest.of(sha256Hex(encoded)),
                lifecycle = RemoteObjectLifecycle.PLANNED,
                resumableSessionUri = null,
                uploadedBytes = 0,
                createdAt = existing?.createdAt ?: now(),
                verifiedAt = null,
            )
            if (existing == null) {
                transferStore.insertObject(candidate)
            } else {
                transferStore.compareAndSetObject(existing, candidate)
            }
        }

        private suspend fun markPublicationVerified(current: VerifiedPublication) {
            val logicalObjectId = RemoteLogicalObjectId.of(state.publicationId)
            val existing = transferStore.objectState(lineageId, logicalObjectId) ?: return
            transferStore.compareAndSetObject(
                existing,
                existing.copy(
                    frameSha256 = current.completeSha256,
                    lifecycle = RemoteObjectLifecycle.VERIFIED,
                    uploadedBytes = existing.frameLength,
                    verifiedAt = now(),
                ),
            )
        }

        // -- Phase bookkeeping ----------------------------------------------------------

        private fun reached(phase: RemotePublishPhase): Boolean =
            RemotePublishPhase.valueOf(state.phase).ordinal >= phase.ordinal

        /**
         * Persists state durably without leaving the current phase, so intent
         * recorded before a network mutation survives a crash that happens
         * before the phase itself can advance.
         */
        private suspend fun record(next: RemotePublishStateV1): StepResult<Unit> =
            advance(RemotePublishPhase.valueOf(state.phase), next)

        private suspend fun advance(
            phase: RemotePublishPhase,
            next: RemotePublishStateV1,
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

        private fun operation(value: RemotePublishStateV1): RemoteBackupOperation {
            val encoded = encodePublishState(value)
            return try {
                RemoteBackupOperation(
                    operationId = operationId,
                    lineageId = CloudLineageId.parse(value.lineageId),
                    kind = PUBLISH_OPERATION_KIND,
                    phase = value.phase,
                    targetEpoch = WriterEpoch(value.writerEpoch),
                    targetGeneration = BackupGeneration(value.localGeneration),
                    candidateClaimProviderId = ProviderObjectId.of(value.claimProviderFileId),
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

        private suspend fun readPublication(
            providerObjectId: ProviderObjectId,
        ): ReadSmallResult = readSmallBounded(
            objectStore,
            providerObjectId,
            DefaultPublicationCatalog.MAX_PUBLICATION_FILE_BYTES,
        )

        private fun failure(result: StepResult<Unit>): RemoteBackupRunResult? =
            (result as? StepResult.Failed)?.reason?.toRunResult()
    }

    private data class AdoptedPublications(
        val predecessor: VerifiedPublication,
        val created: VerifiedPublication?,
    )

    private sealed interface StepResult<out T> {
        data class Ok<T>(val value: T) : StepResult<T>
        data class Failed(val reason: RemoteBackupFailureCategory) : StepResult<Nothing>
    }

    private companion object {
        const val PUBLISH_OPERATION_PREFIX = "remote-publish:"
        const val PUBLISH_OPERATION_KIND = "PUBLISH"
        const val PUBLICATION_RESERVATION = "publication"
        const val UNRESERVED_PROVIDER_FILE_ID = "unreserved"
        const val MAX_PUBLISHED_SEGMENTS = 512
        const val MAX_CLEANUP_BATCHES = 8
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}

private fun RemoteBackupRunResult.allowsAnotherPass(): Boolean =
    this is RemoteBackupRunResult.Verified || this is RemoteBackupRunResult.NoChanges

private fun RemoteBackupFailureCategory.toRunResult(): RemoteBackupRunResult = when (this) {
    RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED ->
        RemoteBackupRunResult.AuthorizationRequired

    RemoteBackupFailureCategory.ACCOUNT_MISMATCH -> RemoteBackupRunResult.AccountMismatch
    RemoteBackupFailureCategory.OWNERSHIP_LOST -> RemoteBackupRunResult.OwnershipLost
    RemoteBackupFailureCategory.TERMINATED -> RemoteBackupRunResult.Terminated
    RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE ->
        RemoteBackupRunResult.AmbiguousRemoteState

    RemoteBackupFailureCategory.RETRYABLE_PROVIDER -> RemoteBackupRunResult.Retryable(this)
    RemoteBackupFailureCategory.PROVIDER_STORAGE,
    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
    RemoteBackupFailureCategory.LOCAL_STORAGE,
    -> RemoteBackupRunResult.Blocked(this)
}

private fun <T> List<T>.replacedAt(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }

/** `segment:first:last` parsed back to the range it covers. */
private fun localSegmentRange(objectId: String): Pair<String, Pair<Long, Long>>? {
    val match = LOCAL_SEGMENT_ID.matchEntire(objectId) ?: return null
    val first = match.groupValues[1].toLongOrNull() ?: return null
    val last = match.groupValues[2].toLongOrNull() ?: return null
    if (first > last) return null
    return objectId to (first to last)
}

private val LOCAL_SEGMENT_ID = Regex("segment:([0-9]+):([0-9]+)")

@OptIn(ExperimentalSerializationApi::class)
private object StrictRemotePublishJson {
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowTrailingComma = false
    }
}

private fun encodePublishState(value: RemotePublishStateV1): ByteArray =
    StrictRemotePublishJson.json
        .encodeToString(RemotePublishStateV1.serializer(), value)
        .toByteArray(Charsets.UTF_8)

private fun decodePublishState(source: ByteArray): RemotePublishStateV1 = try {
    StrictRemotePublishJson.json.decodeFromString(
        RemotePublishStateV1.serializer(),
        source.toString(Charsets.UTF_8),
    )
} catch (failure: SerializationException) {
    throw IllegalArgumentException("Invalid remote publication state", failure)
}
