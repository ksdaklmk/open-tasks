package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.data.VerifiedStagedVault
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.ImmutableDownloadResult
import app.opentasks.core.domain.ImmutableUploadRequest
import app.opentasks.core.domain.ImmutableUploadResult
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RecoveryCandidate
import app.opentasks.core.domain.RecoveryCoordinator
import app.opentasks.core.domain.RecoveryResult
import app.opentasks.core.domain.RecoverySource
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.domain.RemoteListRequest
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
import app.opentasks.core.model.RecoveryFailureCategory
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The exact ordered phases an interrupted recovery resumes from. */
internal enum class RecoveryTakeoverPhase {
    SOURCE_AUTHENTICATED,
    STAGING_RECONSTRUCTED,
    STAGING_VERIFIED,
    TAKEOVER_IDENTITIES_STORED,
    BASE_A_VERIFIED,
    BASE_B_VERIFIED,
    BASELINE_CREATED,
    BASELINE_VERIFIED,
    PREDECESSOR_RECHECKED,
    CONFIRMATION_REQUIRED,
    CLAIM_CREATED,
    CLAIM_VERIFIED,
    ACTIVATED,
    COMPLETED,
}

/**
 * One object the successor epoch's baseline will name.
 *
 * A segment is copied forward verbatim from the authenticated predecessor
 * publication and carries no [snapshotPayloadSha256] plan. Both complete bases
 * are always created by this takeover: they name the exact bytes it intends to
 * upload, recorded durably before the first network mutation that could create
 * them, and stay null until that record has been persisted.
 */
@Serializable
internal data class RecoveryObjectV1(
    val logicalObjectId: String,
    val providerFileId: String,
    val role: RemoteObjectRoleV1,
    val firstGeneration: Long,
    val lastGeneration: Long,
    val frameLength: Long?,
    val frameSha256: String?,
    val snapshotPayloadSha256: String?,
) {
    override fun toString(): String = "RecoveryObjectV1(role=$role)"
}

/**
 * Durable takeover state. Every identity is generated once and persisted here,
 * so a restart claims the same reserved successor at the same epoch from the
 * same staged slot instead of allocating a second one.
 */
@Serializable
internal data class RecoveryTakeoverStateV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val phase: String,
    val operationId: String,
    val lineageId: String,
    val vaultId: String,
    val deviceId: String,
    val rootProviderFileId: String,
    val predecessorProviderFileId: String,
    val predecessorClaimId: String,
    val predecessorClaimSha256: String,
    val predecessorEpoch: Long,
    val claimProviderFileId: String,
    val claimId: String,
    val nextSuccessorProviderFileId: String,
    val publicationProviderFileId: String,
    val publicationId: String,
    val recoveredGeneration: Long,
    val activationGeneration: Long,
    val recoveryCredentialGeneration: Long,
    val currentBaseLogicalObjectId: String,
    val fallbackBaseLogicalObjectId: String,
    val objects: List<RecoveryObjectV1>,
    val baselineSha256: String?,
    val claimSha256: String?,
) {
    /** Never reveals a lineage, device, claim, publication, or provider identity. */
    override fun toString(): String = "RecoveryTakeoverStateV1(phase=$phase)"
}

/**
 * One inactive staging slot, from the moment it is reserved until it is
 * published or abandoned.
 *
 * The staged database is the recovery's own durable store: it is the only
 * storage a device recovering from
 * [app.opentasks.core.data.VaultRuntimeState.NoVault] has, and the remote
 * identities recorded in it are exactly the ones the recovered vault will keep
 * once it becomes live.
 */
internal interface RecoveryStagingSession : AutoCloseable {
    val operationId: String

    val slot: VaultSlot

    /** The staged slot's own remote-backup tables, opened for this session. */
    val remoteStateStore: RemoteBackupStateStore

    /**
     * Creates the staged database under a fresh key, installs [contentKey] as
     * the slot's content key, imports [request], and proves the result.
     */
    suspend fun reconstruct(
        request: RecoveryImportRequest,
        contentKey: VaultKey,
    ): VerifiedStagedVault

    /** Opens the slot-scoped content key a previous [reconstruct] installed. */
    fun openContentKey(vaultId: VaultId): VaultKey
}

internal interface RecoveryStagingFactory {
    /** Registers [operationId] as the staged recovery and reserves its slot. */
    suspend fun begin(operationId: String): RecoveryStagingSession

    /** Reopens the slot [operationId] already reserved, or null when there is none. */
    suspend fun resume(operationId: String): RecoveryStagingSession?

    /**
     * Closes [session] and publishes its slot, returning the live vault's own
     * remote-backup store so the recovery can record its final phase against
     * the database it just activated.
     */
    suspend fun activate(
        session: RecoveryStagingSession,
        staged: VerifiedStagedVault,
    ): RemoteBackupStateStore

    /** Closes [session] and discards its slot without ever publishing it. */
    suspend fun abandon(session: RecoveryStagingSession)
}

/**
 * Bounded authentication, staged reconstruction, self-contained takeover, and
 * activation.
 *
 * The order is forced by what each step has to prove before the next may run:
 * the public bootstrap is bounded and its KDF checked before a key is derived,
 * the ownership chain is authenticated from its root, exactly one publication
 * pair resolves, the named inventory is downloaded at its exact identities and
 * digests, a separate staging slot is rebuilt and proved, the successor epoch's
 * two complete bases and its baseline are created and read back — and only then
 * is an explicit confirmation allowed to create the one reserved successor
 * claim that changes ownership. Nothing activates before that claim has been
 * re-resolved from the provider and found to name this device and this exact
 * baseline.
 *
 * Every provider, codec, and local failure becomes a bounded
 * [RecoveryResult.Failed]; no exception escapes as control flow and no message,
 * identifier, or key material reaches a caller.
 */
class DefaultRecoveryCoordinator internal constructor(
    private val expectedVaultId: VaultId,
    private val crypto: VaultCrypto,
    private val authenticatedCodec: AuthenticatedCloudObjectCodec,
    private val ownershipCodec: OwnershipClaimCodec,
    private val publicationCodec: PublicationCodec,
    private val portableCodec: PortableBackupCodec,
    private val staging: RecoveryStagingFactory,
    private val stagingRoot: File,
    private val expectedAccountBindingDigest: ByteArray?,
    private val now: () -> Instant = Instant::now,
    private val newDeviceId: () -> CloudDeviceId = CloudDeviceId::new,
    private val newClaimId: () -> OwnershipClaimId = OwnershipClaimId::new,
    private val newPublicationId: () -> PublicationId = PublicationId::new,
    private val newLogicalObjectId: () -> RemoteLogicalObjectId = RemoteLogicalObjectId::new,
) : RecoveryCoordinator {

    private val offers = mutableMapOf<String, RecoveryOffer>()

    /**
     * The complete base the last preparation actually rebuilt from.
     *
     * A damaged current base is not a failed recovery: the independent fallback
     * is authenticated on its own and reconstruction continues from it. Visible
     * to this module's tests only.
     */
    internal var baseUsed: String? = null
        private set

    /** Every phase this coordinator has recorded, in order. Test visibility only. */
    internal val phases = mutableListOf<String>()

    override suspend fun discover(
        objectStore: CreateOnlyBackupObjectStore?,
        portablePackage: File?,
    ): List<RecoveryCandidate> {
        // Each pass mints its own handles and retires the previous pass's, so
        // the map a coordinator holds stays as bounded as one discovery.
        offers.clear()
        val discovered = mutableListOf<RecoveryCandidate>()
        if (portablePackage != null && portablePackage.isFile) {
            discovered += offer(
                RecoveryOffer(
                    source = RecoverySource.ANDROID_BACKUP_PACKAGE,
                    portablePackage = portablePackage,
                ),
            )
        }
        if (objectStore != null) discovered += discoverDriveOffers(objectStore)
        return discovered
    }

    /**
     * Lists ownership roots and terminal tombstones, and offers one candidate
     * per lineage either can prove exists.
     *
     * Discovery reads a provider index, so it must be able to fail closed: an
     * unreadable page or a malformed object listed under an ownership role is
     * offered as a blocked candidate rather than reported as "no backups
     * exist". Preparing a blocked candidate reports the bounded reason and
     * creates nothing; recourse is outside this application.
     */
    private suspend fun discoverDriveOffers(
        objectStore: CreateOnlyBackupObjectStore,
    ): List<RecoveryCandidate> {
        val chainStore = DefaultOwnershipChainStore(objectStore, ownershipCodec)
        val roots = when (val discovery = chainStore.discoverPublicRoots()) {
            is OwnershipRootDiscovery.Blocked -> return listOf(blockedOffer(discovery.reason))
            is OwnershipRootDiscovery.Discovered -> discovery.roots
        }
        val tombstones = try {
            terminalLineages(objectStore)
        } catch (failure: BoundedRemoteFailure) {
            return listOf(blockedOffer(failure.reason))
        }
        val offered = mutableListOf<RecoveryCandidate>()
        val seen = mutableSetOf<String>()
        roots.forEach { header ->
            if (!seen.add(header.lineageId)) return@forEach
            offered += offer(
                RecoveryOffer(
                    source = RecoverySource.GOOGLE_DRIVE,
                    lineageId = runBounded { CloudLineageId.parse(header.lineageId) },
                    rootProviderId = runBounded { ProviderObjectId.of(header.providerFileId) },
                    terminated = header.lineageId in tombstones,
                ),
            )
        }
        // A lineage whose tombstone survives but whose root does not is still a
        // lineage this account holds, and it is refused rather than recreated.
        tombstones.forEach { lineageId ->
            if (!seen.add(lineageId)) return@forEach
            offered += offer(
                RecoveryOffer(source = RecoverySource.GOOGLE_DRIVE, terminated = true),
            )
        }
        return offered
    }

    private suspend fun terminalLineages(
        objectStore: CreateOnlyBackupObjectStore,
    ): Set<String> {
        val listed = listAllBounded(
            objectStore,
            RemoteListRequest(
                lineageId = null,
                role = RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE,
                writerEpoch = null,
                ownerDeviceId = null,
                pageToken = null,
                pageSize = DefaultOwnershipChainStore.PAGE_SIZE,
            ),
            maximum = DefaultOwnershipChainStore.MAX_OWNERSHIP_ROOTS,
        )
        val lineages = mutableSetOf<String>()
        listed.forEach { candidate ->
            val bytes = when (
                val read = readSmallBounded(
                    objectStore,
                    candidate.providerObjectId,
                    DefaultOwnershipChainStore.MAX_CLAIM_FILE_BYTES,
                )
            ) {
                is ReadSmallResult.Found -> read.bytes
                ReadSmallResult.Missing -> return@forEach
                is ReadSmallResult.Failed -> throw BoundedRemoteFailure(read.reason)
            }
            val header = bytes.useOwned { source ->
                runBounded { ownershipCodec.readPublicHeader(source) }
            } ?: throw BoundedRemoteFailure(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            if (header.role != RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE ||
                header.providerFileId != candidate.providerObjectId.value
            ) {
                throw BoundedRemoteFailure(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            lineages += header.lineageId
        }
        return lineages
    }

    override suspend fun prepare(
        candidate: RecoveryCandidate,
        passphrase: CharArray,
        objectStore: CreateOnlyBackupObjectStore?,
        accountBindingDigest: ByteArray?,
    ): RecoveryResult {
        val offer = offers[candidate.handle]
            ?: return RecoveryResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        if (offer.source != candidate.source) {
            return RecoveryResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        }
        return try {
            when (offer.source) {
                RecoverySource.ANDROID_BACKUP_PACKAGE -> preparePortable(offer, passphrase)
                RecoverySource.GOOGLE_DRIVE -> {
                    // The account is decided before any lineage is touched: a
                    // known lineage reconnects only to the account it is bound
                    // to, and an unauthorized store is never asked to list.
                    val accountFailure = accountRejection(accountBindingDigest)
                    if (accountFailure != null) return RecoveryResult.Failed(accountFailure)
                    offer.blocked?.let { return RecoveryResult.Failed(it.toRecoveryReason()) }
                    if (offer.terminated) {
                        return RecoveryResult.Failed(RecoveryFailureCategory.TERMINATED)
                    }
                    val store = objectStore
                        ?: return RecoveryResult.Failed(
                            RecoveryFailureCategory.AUTHORIZATION_REQUIRED,
                        )
                    prepareDrive(
                        offer = offer,
                        passphrase = passphrase,
                        objectStore = store,
                        accountBindingDigest = checkNotNull(accountBindingDigest),
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            RecoveryResult.Failed(failure.toRecoveryReason())
        } finally {
            passphrase.fill(' ')
        }
    }

    override suspend fun confirmTakeover(
        operationId: String,
        objectStore: CreateOnlyBackupObjectStore,
    ): RecoveryResult {
        val session = staging.resume(operationId)
            ?: return RecoveryResult.Failed(RecoveryFailureCategory.STAGING_INVARIANT)
        var contentKey: VaultKey? = null
        return try {
            val stored = session.remoteStateStore.operation(operationId)
                ?: return RecoveryResult.Failed(RecoveryFailureCategory.STAGING_INVARIANT)
            val state = runBounded { decodeTakeoverState(stored.stateBytes) }
                ?: return RecoveryResult.Failed(
                    RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                )
            val phase = RecoveryTakeoverPhase.valueOf(state.phase)
            if (phase < RecoveryTakeoverPhase.CONFIRMATION_REQUIRED) {
                return RecoveryResult.Failed(RecoveryFailureCategory.STAGING_INVARIANT)
            }
            // The content key was installed into the staged slot before the
            // slot was proved, so confirming a takeover never asks for the
            // recovery passphrase a second time.
            val key = try {
                session.openContentKey(VaultId(state.vaultId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return RecoveryResult.Failed(RecoveryFailureCategory.LOCAL_KEY_UNAVAILABLE)
            }
            contentKey = key
            Takeover(objectStore, session, key, state).confirm()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            RecoveryResult.Failed(failure.toRecoveryReason())
        } finally {
            contentKey?.close()
            session.close()
        }
    }

    // -- Portable ------------------------------------------------------------------------

    /**
     * A portable package carries no lineage, so there is nothing to take over:
     * the staged vault is proved and published with no remote configuration at
     * all, and connecting a new lineage stays an explicit later choice.
     */
    private suspend fun preparePortable(
        offer: RecoveryOffer,
        passphrase: CharArray,
    ): RecoveryResult {
        val source = checkNotNull(offer.portablePackage)
        unsafeKdfOf(source)?.let { return RecoveryResult.Failed(it) }
        val decoded = try {
            portableCodec.decodeComplete(source, passphrase, crypto)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return RecoveryResult.Failed(failure.toRecoveryReason())
        }
        return decoded.use {
            if (decoded.snapshot.vaultId != expectedVaultId.value) {
                return@use RecoveryResult.Failed(foreignVaultRejection())
            }
            markPhase(RecoveryTakeoverPhase.SOURCE_AUTHENTICATED)
            val request = RecoveryImportRequest(
                snapshot = decoded.snapshot,
                segments = emptyList(),
                recoveryEnvelope = decoded.recoveryEnvelope,
                expectedGeneration = decoded.generation,
            )
            val session = staging.begin(portableOperationId())
            val result = try {
                activatePortable(session, request, passphrase)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                RecoveryResult.Failed(failure.toStagingReason())
            }
            // Nothing about a portable recovery is resumable, so a slot that
            // did not activate is discarded and the runtime released with it.
            if (result is RecoveryResult.Failed) staging.abandon(session)
            result
        }
    }

    private suspend fun activatePortable(
        session: RecoveryStagingSession,
        request: RecoveryImportRequest,
        passphrase: CharArray,
    ): RecoveryResult {
        val key = try {
            crypto.unlock(passphrase, request.recoveryEnvelope)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return RecoveryResult.Failed(RecoveryFailureCategory.WRONG_PASSPHRASE)
        }
        val staged = key.use { session.reconstruct(request, it) }
        markPhase(RecoveryTakeoverPhase.STAGING_RECONSTRUCTED)
        markPhase(RecoveryTakeoverPhase.STAGING_VERIFIED)
        staging.activate(session, staged)
        markPhase(RecoveryTakeoverPhase.ACTIVATED)
        markPhase(RecoveryTakeoverPhase.COMPLETED)
        return RecoveryResult.Activated(staged.activationGeneration, lineageId = null)
    }

    // -- Drive ---------------------------------------------------------------------------

    private suspend fun prepareDrive(
        offer: RecoveryOffer,
        passphrase: CharArray,
        objectStore: CreateOnlyBackupObjectStore,
        accountBindingDigest: ByteArray,
    ): RecoveryResult {
        val lineageId = offer.lineageId
        val rootProviderId = offer.rootProviderId
        if (lineageId == null || rootProviderId == null) {
            return RecoveryResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        }
        val chainStore = DefaultOwnershipChainStore(objectStore, ownershipCodec)
        val catalog = DefaultPublicationCatalog(objectStore, publicationCodec)

        val envelope = when (val read = readRecoveryEnvelope(objectStore, lineageId)) {
            is StepResult.Failed -> return RecoveryResult.Failed(read.reason)
            is StepResult.Ok -> read.value
        }
        val contentKey = try {
            crypto.unlock(passphrase, envelope)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return RecoveryResult.Failed(RecoveryFailureCategory.WRONG_PASSPHRASE)
        } finally {
            envelope.clearEnvelope()
        }
        return contentKey.use { key ->
            val tip = when (val resolution = chainStore.resolve(rootProviderId, key)) {
                is OwnershipResolution.Blocked ->
                    return@use RecoveryResult.Failed(resolution.reason.toRecoveryReason())

                is OwnershipResolution.Terminated ->
                    return@use RecoveryResult.Failed(RecoveryFailureCategory.TERMINATED)

                is OwnershipResolution.Active -> resolution.tip
            }
            val epoch = WriterEpoch(tip.claim.writerEpoch)
            val claimProviderId = runBounded {
                ProviderObjectId.of(checkNotNull(tip.claim.nextSuccessorProviderFileId))
            } ?: return@use RecoveryResult.Failed(
                RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
            val candidates = when (
                val discovery = catalog.discoverBootstraps(
                    lineageId,
                    epoch,
                    runBounded { ProviderObjectId.of(tip.claim.providerFileId) }
                        ?: return@use RecoveryResult.Failed(
                            RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                        ),
                )
            ) {
                is PublicationCandidateDiscovery.Blocked ->
                    return@use RecoveryResult.Failed(discovery.reason.toRecoveryReason())

                is PublicationCandidateDiscovery.Discovered -> discovery.candidates
            }
            val resolved = when (val resolution = catalog.resolve(tip, candidates, key)) {
                is PublicationResolution.Failed ->
                    return@use RecoveryResult.Failed(resolution.reason.toRecoveryReason())

                is PublicationResolution.Resolved -> resolution.current
            }
            if (resolved.manifest.sourceVaultId != expectedVaultId.value) {
                return@use RecoveryResult.Failed(foreignVaultRejection())
            }
            markPhase(RecoveryTakeoverPhase.SOURCE_AUTHENTICATED)
            Takeover(
                objectStore = objectStore,
                session = null,
                contentKey = key,
                state = null,
            ).prepare(
                lineageId = lineageId,
                rootProviderId = rootProviderId,
                tip = tip,
                claimProviderId = claimProviderId,
                publication = resolved,
                accountBindingDigest = accountBindingDigest,
            )
        }
    }

    /**
     * Reads one bounded public publication bootstrap of [lineageId] and takes
     * the recovery envelope of its highest recovery-credential generation.
     *
     * The bootstrap is public by design so a device holding nothing but a
     * passphrase can start. Its KDF parameters and byte bounds are checked
     * before any key is derived; an envelope that is not the supported
     * Argon2id profile is refused outright rather than derived from.
     */
    private suspend fun readRecoveryEnvelope(
        objectStore: CreateOnlyBackupObjectStore,
        lineageId: CloudLineageId,
    ): StepResult<VaultKeyEnvelope> {
        val listed = try {
            listAllBounded(
                objectStore,
                RemoteListRequest(
                    lineageId = lineageId,
                    role = RemoteObjectRoleV1.PUBLICATION,
                    writerEpoch = null,
                    ownerDeviceId = null,
                    pageToken = null,
                    pageSize = DefaultPublicationCatalog.PAGE_SIZE,
                ),
                maximum = MAX_LINEAGE_PUBLICATIONS,
            )
        } catch (failure: BoundedRemoteFailure) {
            return StepResult.Failed(failure.reason.toRecoveryReason())
        }
        var best: PublicationBootstrapV1? = null
        listed.forEach { candidate ->
            val bytes = when (
                val read = readSmallBounded(
                    objectStore,
                    candidate.providerObjectId,
                    DefaultPublicationCatalog.MAX_PUBLICATION_FILE_BYTES,
                )
            ) {
                is ReadSmallResult.Found -> read.bytes
                ReadSmallResult.Missing -> return@forEach
                is ReadSmallResult.Failed ->
                    return StepResult.Failed(read.reason.toRecoveryReason())
            }
            val bootstrap = bytes.useOwned { source ->
                val unsafe = unsafeKdfOf(source)
                if (unsafe != null) return StepResult.Failed(unsafe)
                runBounded { publicationCodec.readBootstrap(source) }
            } ?: return StepResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            if (bootstrap.lineageId != lineageId.value) return@forEach
            val current = best
            if (current == null ||
                bootstrap.recoveryCredentialGeneration > current.recoveryCredentialGeneration
            ) {
                best = bootstrap
            }
        }
        val bootstrap = best
            ?: return StepResult.Failed(RecoveryFailureCategory.MISSING_REQUIRED_OBJECT)
        val envelope = runBounded { RecoveryEnvelopeCodec.fromPayload(bootstrap.recoveryEnvelope) }
            ?: return StepResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        return StepResult.Ok(envelope)
    }

    /**
     * One preparation or confirmation of a Drive takeover.
     *
     * The session and the durable state are absent while preparation is still
     * deciding what to reserve, and present from
     * [RecoveryTakeoverPhase.TAKEOVER_IDENTITIES_STORED] onwards.
     */
    private inner class Takeover(
        private val objectStore: CreateOnlyBackupObjectStore,
        private var session: RecoveryStagingSession?,
        private val contentKey: VaultKey,
        private var state: RecoveryTakeoverStateV1?,
    ) {
        private val chainStore = DefaultOwnershipChainStore(objectStore, ownershipCodec)
        private val catalog = DefaultPublicationCatalog(objectStore, publicationCodec)

        suspend fun prepare(
            lineageId: CloudLineageId,
            rootProviderId: ProviderObjectId,
            tip: VerifiedOwnershipClaim,
            claimProviderId: ProviderObjectId,
            publication: VerifiedPublication,
            accountBindingDigest: ByteArray,
        ): RecoveryResult {
            // Epoch overflow fails closed before anything is reserved: a
            // maximum-epoch predecessor can never reserve a successor.
            if (tip.claim.writerEpoch == Long.MAX_VALUE) {
                return RecoveryResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            val manifest = publication.manifest
            val source = when (val downloaded = downloadBases(lineageId, manifest)) {
                is StepResult.Failed -> return RecoveryResult.Failed(downloaded.reason)
                is StepResult.Ok -> downloaded.value
            }
            baseUsed = source.item.logicalObjectId
            val segments = when (
                val downloaded = downloadSegments(lineageId, manifest, source)
            ) {
                is StepResult.Failed -> return RecoveryResult.Failed(downloaded.reason)
                is StepResult.Ok -> downloaded.value
            }
            val envelope = runBounded {
                RecoveryEnvelopeCodec.fromPayload(publication.bootstrap.recoveryEnvelope)
            } ?: return RecoveryResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)

            return try {
                stage(
                    lineageId = lineageId,
                    rootProviderId = rootProviderId,
                    tip = tip,
                    claimProviderId = claimProviderId,
                    manifest = manifest,
                    source = source,
                    segments = segments,
                    envelope = envelope,
                    accountBindingDigest = accountBindingDigest,
                )
            } finally {
                // The envelope holds wrapped key material; it is cleared as
                // soon as the staged vault and the epoch baseline that publish
                // it have been written.
                envelope.clearEnvelope()
            }
        }

        /**
         * Reserves the staged slot and prepares the successor epoch in it.
         *
         * A preparation that does not reach confirmation is never resumable:
         * every attempt begins its own slot, so a failure here abandons that
         * slot rather than leaving the runtime stuck recovering into a staged
         * database no later call can name.
         */
        @Suppress("LongParameterList")
        private suspend fun stage(
            lineageId: CloudLineageId,
            rootProviderId: ProviderObjectId,
            tip: VerifiedOwnershipClaim,
            claimProviderId: ProviderObjectId,
            manifest: PublicationManifestV1,
            source: AuthenticatedBase,
            segments: List<BackupOperationSegmentPayloadV1>,
            envelope: VaultKeyEnvelope,
            accountBindingDigest: ByteArray,
        ): RecoveryResult {
            val opened = staging.begin(driveOperationId())
            session = opened
            val result = try {
                prepareStaged(
                    lineageId = lineageId,
                    rootProviderId = rootProviderId,
                    tip = tip,
                    claimProviderId = claimProviderId,
                    manifest = manifest,
                    source = source,
                    segments = segments,
                    envelope = envelope,
                    accountBindingDigest = accountBindingDigest,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                RecoveryResult.Failed(failure.toRecoveryReason())
            }
            if (result is RecoveryResult.Failed) {
                session?.let { staging.abandon(it) }
                session = null
            }
            return result
        }

        @Suppress("LongParameterList")
        private suspend fun prepareStaged(
            lineageId: CloudLineageId,
            rootProviderId: ProviderObjectId,
            tip: VerifiedOwnershipClaim,
            claimProviderId: ProviderObjectId,
            manifest: PublicationManifestV1,
            source: AuthenticatedBase,
            segments: List<BackupOperationSegmentPayloadV1>,
            envelope: VaultKeyEnvelope,
            accountBindingDigest: ByteArray,
        ): RecoveryResult {
            val staged = try {
                requireSession().reconstruct(
                    request = RecoveryImportRequest(
                        snapshot = source.snapshot,
                        segments = segments,
                        recoveryEnvelope = envelope,
                        expectedGeneration = BackupGeneration(manifest.localGeneration),
                    ),
                    contentKey = contentKey,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return RecoveryResult.Failed(failure.toStagingReason())
            }
            markPhase(RecoveryTakeoverPhase.STAGING_RECONSTRUCTED)
            markPhase(RecoveryTakeoverPhase.STAGING_VERIFIED)

            val stored = storeIdentities(
                lineageId = lineageId,
                rootProviderId = rootProviderId,
                tip = tip,
                claimProviderId = claimProviderId,
                manifest = manifest,
                staged = staged,
                accountBindingDigest = accountBindingDigest,
            )
            if (stored is StepResult.Failed) return RecoveryResult.Failed(stored.reason)
            failure(publishBase(source, first = true))?.let { return it }
            failure(publishBase(source, first = false))?.let { return it }
            failure(createBaseline(envelope))?.let { return it }
            failure(verifyBaseline())?.let { return it }
            failure(recheckPredecessor(RecoveryTakeoverPhase.PREDECESSOR_RECHECKED))?.let {
                return it
            }
            failure(advance(RecoveryTakeoverPhase.CONFIRMATION_REQUIRED, requireState()))
                ?.let { return it }
            val current = requireState()
            return RecoveryResult.TakeoverConfirmationRequired(
                operationId = current.operationId,
                generation = BackupGeneration(current.recoveredGeneration),
                nextWriterEpoch = WriterEpoch(Math.addExact(current.predecessorEpoch, 1L)),
            )
        }

        suspend fun confirm(): RecoveryResult {
            (recheckPredecessor(null) as? StepResult.Failed)?.let { return failed(it.reason) }
            (createClaim() as? StepResult.Failed)?.let { return failed(it.reason) }
            (verifyClaim() as? StepResult.Failed)?.let { return failed(it.reason) }
            return activate()
        }

        // -- Inventory ------------------------------------------------------------------

        /**
         * Downloads, authenticates, decodes, and compares both declared complete
         * bases at their exact provider files, digests, and remote logical
         * identities, and returns the one this recovery rebuilds from.
         *
         * Two copies of the same generation are meant to hold identical content,
         * so a disagreement between them is ambiguity and fails closed rather
         * than being resolved by preferring the declared current base. Two bases
         * covering different generations are independent bases the format
         * permits and carry no content the other can be compared with.
         */
        private suspend fun downloadBases(
            lineageId: CloudLineageId,
            manifest: PublicationManifestV1,
        ): StepResult<AuthenticatedBase> {
            val currentItem = manifest.inventory
                .firstOrNull { it.logicalObjectId == manifest.currentBaseObjectId }
                ?: return StepResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            val fallbackItem = manifest.inventory
                .firstOrNull { it.logicalObjectId == manifest.fallbackBaseObjectId }
                ?: return StepResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            val current = downloadBase(lineageId, currentItem)
            val fallback = downloadBase(lineageId, fallbackItem)
            if (current != null &&
                fallback != null &&
                current.item.lastGeneration == fallback.item.lastGeneration &&
                current.payloadSha256 != fallback.payloadSha256
            ) {
                return StepResult.Failed(RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            val chosen = current ?: fallback
                ?: return StepResult.Failed(RecoveryFailureCategory.MISSING_REQUIRED_OBJECT)
            return StepResult.Ok(chosen)
        }

        private suspend fun downloadBase(
            lineageId: CloudLineageId,
            item: RemoteInventoryItemV1,
        ): AuthenticatedBase? {
            val plaintext = downloadPlaintext(
                lineageId = lineageId,
                item = item,
                family = CloudObjectFamily.SNAPSHOT,
            ) ?: return null
            val snapshot = runBounded { BackupSnapshotCodec.decodeOwned(plaintext) }
                ?: return null
            if (snapshot.coveredGeneration != item.lastGeneration) return null
            val canonical = BackupSnapshotCodec.encode(snapshot)
            val payloadSha256 = try {
                sha256Hex(canonical)
            } finally {
                canonical.fill(0)
            }
            return AuthenticatedBase(item, snapshot, payloadSha256)
        }

        /**
         * Downloads the exact contiguous segment chain the chosen base needs to
         * reach the published generation. A gap, an overlap, or a missing
         * object is a recovery this device cannot complete.
         */
        private suspend fun downloadSegments(
            lineageId: CloudLineageId,
            manifest: PublicationManifestV1,
            base: AuthenticatedBase,
        ): StepResult<List<BackupOperationSegmentPayloadV1>> {
            val pool = manifest.inventory.filter { it.role == RemoteObjectRoleV1.SEGMENT }
            val chain = mutableListOf<BackupOperationSegmentPayloadV1>()
            var covered = base.item.lastGeneration
            while (covered < manifest.localGeneration) {
                val next = pool
                    .filter { it.firstGeneration == covered + 1 }
                    .maxByOrNull { it.lastGeneration }
                    ?: return StepResult.Failed(
                        RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                    )
                val plaintext = downloadPlaintext(
                    lineageId = lineageId,
                    item = next,
                    family = CloudObjectFamily.OPERATION_SEGMENT,
                ) ?: return StepResult.Failed(RecoveryFailureCategory.MISSING_REQUIRED_OBJECT)
                val segment = runBounded { BackupOperationSegmentCodec.decodeOwned(plaintext) }
                    ?: return StepResult.Failed(
                        RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE,
                    )
                if (segment.firstGeneration != next.firstGeneration ||
                    segment.lastGeneration != next.lastGeneration
                ) {
                    return StepResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
                }
                chain += segment
                covered = next.lastGeneration
                if (chain.size > MAX_RECOVERED_SEGMENTS) {
                    return StepResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
                }
            }
            return StepResult.Ok(chain)
        }

        /**
         * Downloads one inventory object at its exact provider file, declared
         * length, and declared frame digest, then authenticates it at the
         * lineage plus the remote logical object it was published as.
         *
         * The byte bounds of the payload itself are the strict Stage 2 ones,
         * enforced by the authenticated frame codec and the payload decoder the
         * caller runs on the returned plaintext.
         */
        private suspend fun downloadPlaintext(
            lineageId: CloudLineageId,
            item: RemoteInventoryItemV1,
            family: CloudObjectFamily,
        ): ByteArray? {
            val expected = runBounded { Sha256Digest.of(item.frameSha256) } ?: return null
            val providerObjectId = runBounded { ProviderObjectId.of(item.providerFileId) }
                ?: return null
            val downloaded = objectStore.downloadImmutable(
                providerObjectId = providerObjectId,
                maximumBytes = item.frameLength,
                expectedSha256 = expected,
            )
            val frame = when (downloaded) {
                is ImmutableDownloadResult.Downloaded -> downloaded.frame
                ImmutableDownloadResult.Missing,
                ImmutableDownloadResult.Corrupt,
                -> return null

                is ImmutableDownloadResult.Failed -> return null
            }
            return frame.use {
                val decoded = runBounded {
                    frame.file.inputStream().use { stream ->
                        authenticatedCodec.decrypt(stream, frame.file.length(), contentKey)
                    }
                }
                val success = decoded as? CloudDecodeResult.Success ?: return@use null
                success.value.use { value ->
                    if (value.identity != remoteIdentity(family, lineageId, item.logicalObjectId)) {
                        return@use null
                    }
                    value.takePlaintext()
                }
            }
        }

        // -- Phases ---------------------------------------------------------------------

        /**
         * Reserves every provider slot this takeover will occupy and records
         * them, the planned epoch, and the activation facts durably — inside
         * the staged vault, which is the only storage a device recovering into
         * no vault at all can rely on.
         */
        private suspend fun storeIdentities(
            lineageId: CloudLineageId,
            rootProviderId: ProviderObjectId,
            tip: VerifiedOwnershipClaim,
            claimProviderId: ProviderObjectId,
            manifest: PublicationManifestV1,
            staged: VerifiedStagedVault,
            accountBindingDigest: ByteArray,
        ): StepResult<Unit> {
            val reserved = mutableListOf<ProviderObjectId>()
            reserved += generateId(RemoteObjectRoleV1.OWNERSHIP_CLAIM)
            reserved += generateId(RemoteObjectRoleV1.PUBLICATION)
            // The successor epoch always creates its own two complete bases: a
            // takeover proves two independently identified copies of the content
            // it is about to own, never copies another epoch published.
            repeat(2) { reserved += generateId(RemoteObjectRoleV1.SNAPSHOT) }
            // A provider that repeats a generated ID would let one object
            // occupy another's exact slot.
            if (reserved.distinct().size != reserved.size || claimProviderId in reserved) {
                return StepResult.Failed(RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            val nextSuccessorProviderId = reserved[0]
            val publicationProviderId = reserved[1]

            val currentObject = plannedBase(reserved[2], manifest.localGeneration)
            val fallbackObject = plannedBase(reserved[3], manifest.localGeneration)
            val segments = manifest.inventory
                .filter { it.role == RemoteObjectRoleV1.SEGMENT }
                .map(::retained)
            val objects = (listOf(currentObject, fallbackObject) + segments)
                .sortedBy(RecoveryObjectV1::logicalObjectId)
            if (objects.map { it.logicalObjectId }.toSet().size != objects.size ||
                objects.map { it.providerFileId }.toSet().size != objects.size
            ) {
                return StepResult.Failed(RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }

            val fresh = RecoveryTakeoverStateV1(
                phase = RecoveryTakeoverPhase.TAKEOVER_IDENTITIES_STORED.name,
                operationId = driveOperationId(),
                lineageId = lineageId.value,
                vaultId = staged.vaultId.value,
                deviceId = newDeviceId().value,
                rootProviderFileId = rootProviderId.value,
                predecessorProviderFileId = tip.claim.providerFileId,
                predecessorClaimId = tip.claim.claimId,
                predecessorClaimSha256 = tip.completeSha256.value,
                predecessorEpoch = tip.claim.writerEpoch,
                claimProviderFileId = claimProviderId.value,
                claimId = newClaimId().value,
                nextSuccessorProviderFileId = nextSuccessorProviderId.value,
                publicationProviderFileId = publicationProviderId.value,
                publicationId = newPublicationId().value,
                recoveredGeneration = manifest.localGeneration,
                activationGeneration = staged.activationGeneration.value,
                recoveryCredentialGeneration = manifest.recoveryCredentialGeneration,
                currentBaseLogicalObjectId = currentObject.logicalObjectId,
                fallbackBaseLogicalObjectId = fallbackObject.logicalObjectId,
                objects = objects,
                baselineSha256 = null,
                claimSha256 = null,
            )
            requireSession().remoteStateStore.insertConnecting(
                RemoteBackupConfiguration(
                    lineageId = lineageId,
                    vaultId = staged.vaultId,
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
                    recoveryCredentialGeneration = fresh.recoveryCredentialGeneration,
                    failureCategory = null,
                    stateVersion = RemoteBackupStateVersion(0),
                ),
            )
            requireSession().remoteStateStore.putOperation(operationOf(fresh))
            state = fresh
            phases += fresh.phase
            return StepResult.Ok(Unit)
        }

        /**
         * Creates and proves one of the successor epoch's two complete bases.
         *
         * The canonical payload the authenticated source base decoded to — the
         * same content the staged vault was proved against — is re-encoded under
         * a fresh remote logical identity into a freshly reserved slot. Its
         * intended bytes are recorded durably before the first network mutation,
         * so a restart adopts them instead of uploading a second, differently
         * nonced copy, and it is read back and decoded before it may count.
         */
        private suspend fun publishBase(
            source: AuthenticatedBase,
            first: Boolean,
        ): StepResult<Unit> {
            val phase = if (first) {
                RecoveryTakeoverPhase.BASE_A_VERIFIED
            } else {
                RecoveryTakeoverPhase.BASE_B_VERIFIED
            }
            if (reached(phase)) return StepResult.Ok(Unit)
            val current = requireState()
            val logicalObjectId = if (first) {
                current.currentBaseLogicalObjectId
            } else {
                current.fallbackBaseLogicalObjectId
            }
            val index = current.objects.indexOfFirst { it.logicalObjectId == logicalObjectId }
            val planned = current.objects[index]
            val staged = try {
                stageEpochBase(
                    lineageId = CloudLineageId.parse(current.lineageId),
                    logicalObjectId = RemoteLogicalObjectId.of(planned.logicalObjectId),
                    snapshot = source.snapshot,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return StepResult.Failed(RecoveryFailureCategory.INSUFFICIENT_STORAGE)
            }
            val record = planned.copy(
                frameLength = staged.frameLength,
                frameSha256 = staged.frameSha256.value,
                snapshotPayloadSha256 = staged.payloadSha256.value,
            )
            val planning = record(
                current.copy(objects = current.objects.replacedAt(index, record)),
            )
            if (planning is StepResult.Failed) {
                staged.close()
                return planning
            }
            staged.use {
                val upload = objectStore.uploadImmutable(
                    ImmutableUploadRequest(
                        lineageId = CloudLineageId.parse(current.lineageId),
                        writerEpoch = WriterEpoch(Math.addExact(current.predecessorEpoch, 1L)),
                        ownerDeviceId = CloudDeviceId.parse(current.deviceId),
                        operationId = current.operationId,
                        logicalObjectId = RemoteLogicalObjectId.of(record.logicalObjectId),
                        providerObjectId = ProviderObjectId.of(record.providerFileId),
                        role = RemoteObjectRoleV1.SNAPSHOT,
                        firstGeneration = BackupGeneration(record.firstGeneration),
                        lastGeneration = BackupGeneration(record.lastGeneration),
                        frameLength = staged.frameLength,
                        frameSha256 = staged.frameSha256,
                        frame = staged,
                    ),
                )
                when (upload) {
                    ImmutableUploadResult.UploadedAndVerified,
                    ImmutableUploadResult.OccupiedByExpectedBytes,
                    -> Unit

                    ImmutableUploadResult.OccupiedByDifferentBytes ->
                        return StepResult.Failed(
                            RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE,
                        )

                    is ImmutableUploadResult.Failed ->
                        return StepResult.Failed(upload.reason.toRecoveryReason())
                }
            }
            val readBack = downloadPlaintext(
                lineageId = CloudLineageId.parse(current.lineageId),
                item = RemoteInventoryItemV1(
                    logicalObjectId = record.logicalObjectId,
                    providerFileId = record.providerFileId,
                    role = RemoteObjectRoleV1.SNAPSHOT,
                    firstGeneration = record.firstGeneration,
                    lastGeneration = record.lastGeneration,
                    frameLength = checkNotNull(record.frameLength),
                    frameSha256 = checkNotNull(record.frameSha256),
                ),
                family = CloudObjectFamily.SNAPSHOT,
            ) ?: return StepResult.Failed(RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE)
            val decoded = runBounded { BackupSnapshotCodec.decodeOwned(readBack) }
                ?: return StepResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            val canonical = BackupSnapshotCodec.encode(decoded)
            val matches = try {
                sha256Hex(canonical) == record.snapshotPayloadSha256
            } finally {
                canonical.fill(0)
            }
            if (!matches) {
                return StepResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            return advance(phase, requireState())
        }

        /** Creates the sequence-zero baseline of the epoch this takeover plans. */
        private suspend fun createBaseline(envelope: VaultKeyEnvelope): StepResult<Unit> {
            if (reached(RecoveryTakeoverPhase.BASELINE_CREATED)) return StepResult.Ok(Unit)
            val current = requireState()
            val encoded = try {
                publicationCodec.encode(baselineManifest(current, envelope), envelope, contentKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return StepResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            val created = try {
                catalog.create(
                    ProviderObjectId.of(current.publicationProviderFileId),
                    ownedCopyOf(encoded),
                    contentKey,
                )
            } finally {
                encoded.fill(0)
            }
            return when (created) {
                is PublicationCreateResult.Created,
                is PublicationCreateResult.OccupiedByExpected,
                -> advance(RecoveryTakeoverPhase.BASELINE_CREATED, current)

                // Re-encoding produces a fresh nonce, so a previous attempt's
                // own baseline occupies the slot with different bytes. Adopt it
                // only when it authenticates to the identity this takeover
                // planned, which nothing without the content key can forge.
                PublicationCreateResult.OccupiedByDifferent ->
                    when (val occupant = readPlannedBaseline()) {
                        is StepResult.Failed -> occupant
                        is StepResult.Ok ->
                            advance(RecoveryTakeoverPhase.BASELINE_CREATED, current)
                    }

                is PublicationCreateResult.Failed ->
                    StepResult.Failed(created.reason.toRecoveryReason())
            }
        }

        private suspend fun verifyBaseline(): StepResult<Unit> {
            if (reached(RecoveryTakeoverPhase.BASELINE_VERIFIED)) return StepResult.Ok(Unit)
            return when (val verified = readPlannedBaseline()) {
                is StepResult.Failed -> verified
                is StepResult.Ok -> advance(
                    RecoveryTakeoverPhase.BASELINE_VERIFIED,
                    requireState().copy(baselineSha256 = verified.value.completeSha256.value),
                )
            }
        }

        /**
         * Reads the publication slot this takeover reserved and requires the
         * occupant to be exactly the baseline it planned. Any other occupant is
         * ambiguity, and no alternate slot is ever reserved.
         */
        private suspend fun readPlannedBaseline(): StepResult<VerifiedPublication> {
            val current = requireState()
            val providerObjectId = ProviderObjectId.of(current.publicationProviderFileId)
            val bytes = when (
                val read = readSmallBounded(
                    objectStore,
                    providerObjectId,
                    DefaultPublicationCatalog.MAX_PUBLICATION_FILE_BYTES,
                )
            ) {
                is ReadSmallResult.Found -> read.bytes
                ReadSmallResult.Missing ->
                    return StepResult.Failed(RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE)

                is ReadSmallResult.Failed ->
                    return StepResult.Failed(read.reason.toRecoveryReason())
            }
            val verified = bytes.useOwned { source ->
                runBounded { publicationCodec.verify(source, contentKey) }
            } ?: return StepResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            val manifest = verified.manifest
            val planned = manifest.lineageId == current.lineageId &&
                manifest.publicationId == current.publicationId &&
                manifest.publicationProviderFileId == providerObjectId.value &&
                manifest.publicationSequence == BASELINE_SEQUENCE &&
                manifest.baseline &&
                manifest.writerEpoch == Math.addExact(current.predecessorEpoch, 1L) &&
                manifest.activeDeviceId == current.deviceId &&
                manifest.sourceVaultId == current.vaultId &&
                manifest.plannedClaimProviderFileId == current.claimProviderFileId &&
                manifest.plannedClaimId == current.claimId &&
                manifest.predecessorClaimSha256 == current.predecessorClaimSha256
            if (!planned) {
                return StepResult.Failed(RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            return StepResult.Ok(verified)
        }

        /**
         * Rereads the chain and refuses to continue unless the tip is still the
         * exact predecessor this takeover recorded. A newer claim means another
         * writer already took the epoch this takeover planned.
         */
        private suspend fun recheckPredecessor(
            phase: RecoveryTakeoverPhase?,
        ): StepResult<Unit> {
            if (phase != null && reached(phase)) return StepResult.Ok(Unit)
            val current = requireState()
            val resolution = chainStore.resolve(
                ProviderObjectId.of(current.rootProviderFileId),
                contentKey,
            )
            val tip = when (resolution) {
                is OwnershipResolution.Blocked ->
                    return StepResult.Failed(resolution.reason.toRecoveryReason())

                is OwnershipResolution.Terminated ->
                    return StepResult.Failed(RecoveryFailureCategory.TERMINATED)

                is OwnershipResolution.Active -> resolution.tip
            }
            val unchanged = tip.completeSha256.value == current.predecessorClaimSha256 &&
                tip.claim.claimId == current.predecessorClaimId &&
                tip.claim.providerFileId == current.predecessorProviderFileId
            if (!unchanged) {
                // A tip that is already this takeover's own claim is a crash
                // after the create, not another writer. A foreign claim at the
                // exact slot this takeover reserved is the race it lost;
                // anything further along is a chain that moved without it.
                val atReservedSlot = tip.claim.providerFileId == current.claimProviderFileId
                val ours = atReservedSlot && tip.claim.claimId == current.claimId
                if (!ours) {
                    return StepResult.Failed(
                        if (atReservedSlot) {
                            RecoveryFailureCategory.OWNERSHIP_LOST
                        } else {
                            RecoveryFailureCategory.OWNERSHIP_CHANGED
                        },
                    )
                }
            }
            return if (phase == null) StepResult.Ok(Unit) else advance(phase, current)
        }

        /**
         * Creates the one claim the predecessor reserved, at the predecessor's
         * exact successor provider file and at its epoch plus one.
         *
         * The claim's plaintext is fully determined by persisted state, so an
         * occupant whose authenticated claim equals the claim this takeover
         * intended is its own earlier create recovering across a crash — not
         * another writer. Only the content key can produce those authenticated
         * bytes, and every other occupant is ownership loss.
         */
        private suspend fun createClaim(): StepResult<Unit> {
            if (reached(RecoveryTakeoverPhase.CLAIM_CREATED)) return StepResult.Ok(Unit)
            val current = requireState()
            val predecessor = when (val read = readPredecessor()) {
                is StepResult.Failed -> return StepResult.Failed(read.reason)
                is StepResult.Ok -> read.value
            }
            val intended = successorClaim(current)
            val encoded = try {
                ownershipCodec.encode(intended, contentKey)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return StepResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
            val created = try {
                chainStore.createClaim(predecessor, ownedCopyOf(encoded), contentKey)
            } finally {
                encoded.fill(0)
            }
            return when (created) {
                is OwnershipClaimCreateResult.Won ->
                    advance(RecoveryTakeoverPhase.CLAIM_CREATED, current)

                is OwnershipClaimCreateResult.Lost ->
                    if (created.winner.claim == intended) {
                        advance(RecoveryTakeoverPhase.CLAIM_CREATED, current)
                    } else {
                        StepResult.Failed(RecoveryFailureCategory.OWNERSHIP_LOST)
                    }

                OwnershipClaimCreateResult.AmbiguousRemoteState ->
                    StepResult.Failed(RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE)

                is OwnershipClaimCreateResult.Failed ->
                    StepResult.Failed(created.reason.toRecoveryReason())
            }
        }

        private suspend fun readPredecessor(): StepResult<VerifiedOwnershipClaim> {
            val current = requireState()
            val bytes = when (
                val read = readSmallBounded(
                    objectStore,
                    ProviderObjectId.of(current.predecessorProviderFileId),
                    DefaultOwnershipChainStore.MAX_CLAIM_FILE_BYTES,
                )
            ) {
                is ReadSmallResult.Found -> read.bytes
                ReadSmallResult.Missing ->
                    return StepResult.Failed(RecoveryFailureCategory.MISSING_REQUIRED_OBJECT)

                is ReadSmallResult.Failed ->
                    return StepResult.Failed(read.reason.toRecoveryReason())
            }
            val verified = bytes.useOwned { source ->
                runBounded { ownershipCodec.verify(source, contentKey) }
            } ?: return StepResult.Failed(RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            if (verified.completeSha256.value != current.predecessorClaimSha256) {
                return StepResult.Failed(RecoveryFailureCategory.OWNERSHIP_CHANGED)
            }
            return StepResult.Ok(verified)
        }

        /**
         * Re-resolves the whole chain and the successor epoch's catalog from
         * the provider, and requires the tip to be this device's claim binding
         * this takeover's exact baseline.
         */
        private suspend fun verifyClaim(): StepResult<Unit> {
            if (reached(RecoveryTakeoverPhase.CLAIM_VERIFIED)) return StepResult.Ok(Unit)
            val current = requireState()
            val epoch = WriterEpoch(Math.addExact(current.predecessorEpoch, 1L))
            val resolution = chainStore.resolve(
                ProviderObjectId.of(current.rootProviderFileId),
                contentKey,
            )
            val tip = when (resolution) {
                is OwnershipResolution.Blocked ->
                    return StepResult.Failed(resolution.reason.toRecoveryReason())

                is OwnershipResolution.Terminated ->
                    return StepResult.Failed(RecoveryFailureCategory.TERMINATED)

                is OwnershipResolution.Active -> resolution.tip
            }
            val won = tip.claim.claimId == current.claimId &&
                tip.claim.providerFileId == current.claimProviderFileId &&
                tip.claim.writerEpoch == epoch.value &&
                tip.claim.lineageId == current.lineageId &&
                tip.claim.sourceVaultId == current.vaultId &&
                tip.claim.activeDeviceId == current.deviceId &&
                tip.claim.baselinePublicationSha256 == current.baselineSha256
            if (!won) return StepResult.Failed(RecoveryFailureCategory.OWNERSHIP_LOST)
            val candidates = when (
                val discovery = catalog.discoverBootstraps(
                    CloudLineageId.parse(current.lineageId),
                    epoch,
                    ProviderObjectId.of(current.claimProviderFileId),
                )
            ) {
                is PublicationCandidateDiscovery.Blocked ->
                    return StepResult.Failed(discovery.reason.toRecoveryReason())

                is PublicationCandidateDiscovery.Discovered -> discovery.candidates
            }
            val resolved = when (val outcome = catalog.resolve(tip, candidates, contentKey)) {
                is PublicationResolution.Failed ->
                    return StepResult.Failed(outcome.reason.toRecoveryReason())

                is PublicationResolution.Resolved -> outcome
            }
            if (resolved.previous != null ||
                resolved.current.completeSha256.value != current.baselineSha256
            ) {
                return StepResult.Failed(RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE)
            }
            return advance(
                RecoveryTakeoverPhase.CLAIM_VERIFIED,
                current.copy(claimSha256 = tip.completeSha256.value),
            )
        }

        /**
         * Publishes the staged slot only after the claim has been re-resolved.
         *
         * The active remote configuration is written into the staged vault
         * first, so the vault that becomes live already owns the lineage it
         * will publish to, and the recovered local state — which carries no
         * Stage 2 base at all — will demand a fresh complete base before it can
         * publish anything.
         */
        private suspend fun activate(): RecoveryResult {
            val current = requireState()
            val lineageId = CloudLineageId.parse(current.lineageId)
            val epoch = WriterEpoch(Math.addExact(current.predecessorEpoch, 1L))
            val session = requireSession()
            if (!reached(RecoveryTakeoverPhase.ACTIVATED)) {
                val stored = session.remoteStateStore.known(lineageId)
                    ?: return failed(RecoveryFailureCategory.STAGING_INVARIANT)
                val applied = session.remoteStateStore.compareAndSet(
                    lineageId = lineageId,
                    expected = stored.stateVersion,
                    next = stored.copy(
                        lifecycle = RemoteBackupLifecycle.ACTIVE,
                        activeDeviceId = CloudDeviceId.parse(current.deviceId),
                        writerEpoch = epoch,
                        ownershipClaim = OwnershipClaimRef(
                            providerId = ProviderObjectId.of(current.claimProviderFileId),
                            logicalId = OwnershipClaimId.parse(current.claimId),
                            sha256 = Sha256Digest.of(checkNotNull(current.claimSha256)),
                            writerEpoch = epoch,
                        ),
                        nextSuccessorProviderId =
                            ProviderObjectId.of(current.nextSuccessorProviderFileId),
                        currentPublication = PublicationRef(
                            providerId = ProviderObjectId.of(current.publicationProviderFileId),
                            logicalId = PublicationId.parse(current.publicationId),
                            sha256 = Sha256Digest.of(checkNotNull(current.baselineSha256)),
                            sequence = PublicationSequence(BASELINE_SEQUENCE),
                            generation = BackupGeneration(current.recoveredGeneration),
                        ),
                        previousPublication = null,
                        lastVerifiedGeneration = BackupGeneration(current.recoveredGeneration),
                        lastVerifiedAt = now(),
                        failureCategory = null,
                        stateVersion = RemoteBackupStateVersion(stored.stateVersion.value + 1),
                    ),
                )
                if (!applied) return failed(RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE)
                val advanced = advance(RecoveryTakeoverPhase.ACTIVATED, current)
                if (advanced is StepResult.Failed) return failed(advanced.reason)
            }
            val staged = VerifiedStagedVault(
                slot = session.slot,
                vaultId = VaultId(current.vaultId),
                recoveredGeneration = BackupGeneration(current.recoveredGeneration),
                activationGeneration = BackupGeneration(current.activationGeneration),
            )
            val liveStore = staging.activate(session, staged)
            this.session = null
            liveStore.transitionOperation(
                operationId = current.operationId,
                expectedPhase = RecoveryTakeoverPhase.ACTIVATED.name,
                next = operationOf(
                    requireState().copy(phase = RecoveryTakeoverPhase.COMPLETED.name),
                ),
            )
            phases += RecoveryTakeoverPhase.COMPLETED.name
            return RecoveryResult.Activated(
                generation = BackupGeneration(current.activationGeneration),
                lineageId = lineageId,
            )
        }

        // -- Encoding -------------------------------------------------------------------

        private fun baselineManifest(
            current: RecoveryTakeoverStateV1,
            envelope: VaultKeyEnvelope,
        ): PublicationManifestV1 {
            val draft = PublicationManifestV1(
                bootstrapSha256 = ZERO_SHA256,
                lineageId = current.lineageId,
                sourceVaultId = current.vaultId,
                writerEpoch = Math.addExact(current.predecessorEpoch, 1L),
                activeDeviceId = current.deviceId,
                publicationProviderFileId = current.publicationProviderFileId,
                publicationId = current.publicationId,
                publicationSequence = BASELINE_SEQUENCE,
                predecessorPublicationProviderFileId = null,
                predecessorPublicationId = null,
                predecessorPublicationSha256 = null,
                baseline = true,
                plannedClaimProviderFileId = current.claimProviderFileId,
                plannedClaimId = current.claimId,
                predecessorClaimProviderFileId = current.predecessorProviderFileId,
                predecessorClaimId = current.predecessorClaimId,
                predecessorClaimSha256 = current.predecessorClaimSha256,
                ownershipClaimProviderFileId = null,
                ownershipClaimId = null,
                ownershipClaimSha256 = null,
                localGeneration = current.recoveredGeneration,
                publicationOperationId = current.operationId,
                currentBaseObjectId = current.currentBaseLogicalObjectId,
                fallbackBaseObjectId = current.fallbackBaseLogicalObjectId,
                inventory = current.objects
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
                recoveryCredentialGeneration = current.recoveryCredentialGeneration,
            )
            return draft.copy(bootstrapSha256 = publicationCodec.bootstrapSha256(draft, envelope))
        }

        private fun successorClaim(current: RecoveryTakeoverStateV1): OwnershipClaimV1 =
            OwnershipClaimV1(
                lineageId = current.lineageId,
                writerEpoch = Math.addExact(current.predecessorEpoch, 1L),
                state = OwnershipStateV1.ACTIVE,
                predecessorProviderFileId = current.predecessorProviderFileId,
                predecessorClaimId = current.predecessorClaimId,
                predecessorClaimSha256 = current.predecessorClaimSha256,
                providerFileId = current.claimProviderFileId,
                claimId = current.claimId,
                predecessorReservedSuccessorProviderFileId = current.claimProviderFileId,
                sourceVaultId = current.vaultId,
                activeDeviceId = current.deviceId,
                nextSuccessorProviderFileId = current.nextSuccessorProviderFileId,
                baselinePublicationProviderFileId = current.publicationProviderFileId,
                baselinePublicationId = current.publicationId,
                baselinePublicationSha256 = checkNotNull(current.baselineSha256),
                recoveryCredentialGeneration = current.recoveryCredentialGeneration,
                creationOperationId = current.operationId,
                tombstoneId = null,
            )

        private fun retained(item: RemoteInventoryItemV1): RecoveryObjectV1 = RecoveryObjectV1(
            logicalObjectId = item.logicalObjectId,
            providerFileId = item.providerFileId,
            role = item.role,
            firstGeneration = item.firstGeneration,
            lastGeneration = item.lastGeneration,
            frameLength = item.frameLength,
            frameSha256 = item.frameSha256,
            snapshotPayloadSha256 = null,
        )

        private fun plannedBase(
            providerObjectId: ProviderObjectId,
            generation: Long,
        ): RecoveryObjectV1 = RecoveryObjectV1(
            logicalObjectId = newLogicalObjectId().value,
            providerFileId = providerObjectId.value,
            role = RemoteObjectRoleV1.SNAPSHOT,
            firstGeneration = generation,
            lastGeneration = generation,
            frameLength = null,
            frameSha256 = null,
            snapshotPayloadSha256 = null,
        )

        /**
         * Encrypts the canonical recovered payload under a fresh remote logical
         * identity, so neither new copy shares an identity, associated data,
         * nonce, or ciphertext with the other or with any source object.
         */
        private fun stageEpochBase(
            lineageId: CloudLineageId,
            logicalObjectId: RemoteLogicalObjectId,
            snapshot: BackupSnapshotPayloadV1,
        ): ReauthenticatedRemoteObject {
            val plaintext = BackupSnapshotCodec.encode(snapshot)
            var frame: ByteArray? = null
            var file: File? = null
            try {
                val payloadSha256 = Sha256Digest.of(sha256Hex(plaintext))
                frame = authenticatedCodec.encrypt(
                    remoteIdentity(
                        CloudObjectFamily.SNAPSHOT,
                        lineageId,
                        logicalObjectId.value,
                    ),
                    plaintext,
                    contentKey,
                )
                stagingRoot.mkdirs()
                file = File.createTempFile("recovered-", ".otr", stagingRoot)
                FileOutputStream(file).use { output ->
                    output.write(frame)
                    output.fd.sync()
                }
                return ReauthenticatedRemoteObject(
                    logicalObjectId = logicalObjectId,
                    role = RemoteObjectRoleV1.SNAPSHOT,
                    firstGeneration = BackupGeneration(snapshot.coveredGeneration),
                    lastGeneration = BackupGeneration(snapshot.coveredGeneration),
                    frameLength = frame.size.toLong(),
                    frameSha256 = Sha256Digest.of(sha256Hex(frame)),
                    payloadSha256 = payloadSha256,
                    backing = file,
                )
            } catch (failure: Throwable) {
                file?.delete()
                throw failure
            } finally {
                frame?.fill(0)
                plaintext.fill(0)
            }
        }

        // -- Phase bookkeeping ----------------------------------------------------------

        private fun requireState(): RecoveryTakeoverStateV1 =
            checkNotNull(state) { "The takeover has stored no identities" }

        private fun requireSession(): RecoveryStagingSession =
            checkNotNull(session) { "The takeover holds no staged vault" }

        private fun reached(phase: RecoveryTakeoverPhase): Boolean =
            RecoveryTakeoverPhase.valueOf(requireState().phase).ordinal >= phase.ordinal

        /**
         * Persists state durably without leaving the current phase, so intent
         * recorded before a network mutation survives a crash that happens
         * before the phase itself can advance.
         */
        private suspend fun record(next: RecoveryTakeoverStateV1): StepResult<Unit> =
            advance(RecoveryTakeoverPhase.valueOf(requireState().phase), next)

        private suspend fun advance(
            phase: RecoveryTakeoverPhase,
            next: RecoveryTakeoverStateV1,
        ): StepResult<Unit> {
            val expectedPhase = requireState().phase
            val updated = next.copy(phase = phase.name)
            val applied = requireSession().remoteStateStore.transitionOperation(
                operationId = updated.operationId,
                expectedPhase = expectedPhase,
                next = operationOf(updated),
            )
            if (!applied) return StepResult.Failed(RecoveryFailureCategory.STAGING_INVARIANT)
            state = updated
            // Recording intent inside a phase is a durable write, not a
            // transition, so it never appears twice in the phase sequence.
            if (updated.phase != expectedPhase) phases += updated.phase
            return StepResult.Ok(Unit)
        }

        private fun operationOf(value: RecoveryTakeoverStateV1): RemoteBackupOperation {
            val encoded = encodeTakeoverState(value)
            return try {
                RemoteBackupOperation(
                    operationId = value.operationId,
                    lineageId = CloudLineageId.parse(value.lineageId),
                    kind = RECOVERY_OPERATION_KIND,
                    phase = value.phase,
                    targetEpoch = WriterEpoch(Math.addExact(value.predecessorEpoch, 1L)),
                    targetGeneration = BackupGeneration(value.recoveredGeneration),
                    candidateClaimProviderId = ProviderObjectId.of(value.claimProviderFileId),
                    candidatePublicationProviderId =
                        ProviderObjectId.of(value.publicationProviderFileId),
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

        private fun failure(result: StepResult<Unit>): RecoveryResult? =
            (result as? StepResult.Failed)?.let { RecoveryResult.Failed(it.reason) }

        /**
         * Ends a takeover that must not activate.
         *
         * Ownership loss closes staging rather than keeping a slot no claim
         * entitles this device to publish; every other bounded failure leaves
         * the staged slot registered so the same operation can be resumed.
         */
        private suspend fun failed(reason: RecoveryFailureCategory): RecoveryResult {
            if (reason == RecoveryFailureCategory.OWNERSHIP_LOST) {
                session?.let { staging.abandon(it) }
                session = null
            }
            return RecoveryResult.Failed(reason)
        }

        private fun driveOperationId(): String = state?.operationId ?: operationId

        private val operationId: String = RECOVERY_OPERATION_PREFIX + randomHandle()
    }

    // -- Shared helpers ------------------------------------------------------------------

    private fun markPhase(phase: RecoveryTakeoverPhase) {
        phases += phase.name
    }

    private fun portableOperationId(): String = RECOVERY_OPERATION_PREFIX + randomHandle()

    /**
     * M3: a recovered vault whose identity is not the one this repository
     * journals under would append every later mutation to the wrong vault, so a
     * foreign identity is refused rather than activated silently broken.
     */
    private fun foreignVaultRejection(): RecoveryFailureCategory =
        RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE

    private fun accountRejection(
        accountBindingDigest: ByteArray?,
    ): RecoveryFailureCategory? {
        if (accountBindingDigest == null) {
            return RecoveryFailureCategory.AUTHORIZATION_REQUIRED
        }
        val expected = expectedAccountBindingDigest ?: return null
        return if (expected.contentEquals(accountBindingDigest)) {
            null
        } else {
            RecoveryFailureCategory.ACCOUNT_MISMATCH
        }
    }

    private fun offer(value: RecoveryOffer): RecoveryCandidate {
        val handle = randomHandle()
        offers[handle] = value
        return RecoveryCandidate(handle = handle, source = value.source)
    }

    private fun blockedOffer(reason: RemoteBackupFailureCategory): RecoveryCandidate =
        offer(RecoveryOffer(source = RecoverySource.GOOGLE_DRIVE, blocked = reason))

    private fun remoteIdentity(
        family: CloudObjectFamily,
        lineageId: CloudLineageId,
        objectId: String,
    ): CloudHeaderIdentity = CloudHeaderIdentity(
        family = family,
        schemaVersion = FORMAT_VERSION,
        cryptoVersion = FORMAT_VERSION,
        minimumReaderVersion = MINIMUM_READER_VERSION,
        vaultId = lineageId.value,
        objectId = objectId,
    )

    private data class RecoveryOffer(
        val source: RecoverySource,
        val lineageId: CloudLineageId? = null,
        val rootProviderId: ProviderObjectId? = null,
        val portablePackage: File? = null,
        val blocked: RemoteBackupFailureCategory? = null,
        val terminated: Boolean = false,
    )

    private class AuthenticatedBase(
        val item: RemoteInventoryItemV1,
        val snapshot: BackupSnapshotPayloadV1,
        /** Digest of the canonical decoded payload, so two copies compare. */
        val payloadSha256: String,
    )

    private sealed interface StepResult<out T> {
        data class Ok<T>(val value: T) : StepResult<T>
        data class Failed(val reason: RecoveryFailureCategory) : StepResult<Nothing>
    }

    private companion object {
        const val RECOVERY_OPERATION_PREFIX = "recovery:"
        const val RECOVERY_OPERATION_KIND = "RECOVERY"
        const val BASELINE_SEQUENCE = 0L
        const val FORMAT_VERSION = 1
        const val MINIMUM_READER_VERSION = 1
        const val MAX_LINEAGE_PUBLICATIONS = 512
        const val MAX_RECOVERED_SEGMENTS = 512
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}

/**
 * A bounded probe of the public recovery-envelope parameters a source declares.
 *
 * The strict codecs already refuse a weakened envelope, but they refuse it as
 * "unreadable", which tells a recovering user nothing. This reads only the
 * public JSON prefix — never a frame, never a key — so a source published under
 * anything but the supported Argon2id profile is named for what it is before
 * any derivation is attempted.
 */
private fun unsafeKdfOf(source: ByteArray): RecoveryFailureCategory? {
    val payload = runCatching { LenientRecoveryBootstrapJson.kdfOf(source) }.getOrNull()
        ?: return null
    return if (payload.isSupported()) null else RecoveryFailureCategory.UNSAFE_KDF
}

private fun unsafeKdfOf(source: File): RecoveryFailureCategory? {
    val prefix = runCatching {
        source.inputStream().use { stream ->
            val bounded = ByteArray(minOf(source.length(), MAX_PROBE_BYTES).toInt())
            var read = 0
            while (read < bounded.size) {
                val count = stream.read(bounded, read, bounded.size - read)
                if (count < 0) break
                read += count
            }
            bounded.copyOf(read)
        }
    }.getOrNull() ?: return null
    return try {
        unsafeKdfOf(prefix)
    } finally {
        prefix.fill(0)
    }
}

private fun RecoveryEnvelopePayloadV1.isSupported(): Boolean =
    formatVersion == SUPPORTED_ENVELOPE_FORMAT &&
        kdfAlgorithm == SUPPORTED_KDF_ALGORITHM &&
        memoryKiB >= SUPPORTED_MEMORY_KIB &&
        iterations >= SUPPORTED_ITERATIONS &&
        parallelism == SUPPORTED_PARALLELISM

@OptIn(ExperimentalSerializationApi::class)
private object LenientRecoveryBootstrapJson {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
        allowTrailingComma = false
    }

    /**
     * Reads the length-prefixed public JSON both the publication and the
     * portable formats begin with, and returns the recovery envelope it
     * declares. Anything that does not parse is not this probe's business.
     */
    fun kdfOf(source: ByteArray): RecoveryEnvelopePayloadV1? {
        require(source.size > LENGTH_PREFIX_BYTES) { "The public prefix is truncated" }
        val length = ((source[0].toInt() and 0xff) shl 24) or
            ((source[1].toInt() and 0xff) shl 16) or
            ((source[2].toInt() and 0xff) shl 8) or
            (source[3].toInt() and 0xff)
        require(length in 1..MAX_PROBE_BYTES.toInt()) { "The public prefix is not bounded" }
        require(source.size >= LENGTH_PREFIX_BYTES + length) { "The public prefix is truncated" }
        val text = source.copyOfRange(LENGTH_PREFIX_BYTES, LENGTH_PREFIX_BYTES + length)
        return try {
            json.decodeFromString(ProbeBootstrapV1.serializer(), text.toString(Charsets.UTF_8))
                .recoveryEnvelope
        } catch (_: SerializationException) {
            null
        } finally {
            text.fill(0)
        }
    }

    private const val LENGTH_PREFIX_BYTES = 4
}

@Serializable
private data class ProbeBootstrapV1(val recoveryEnvelope: RecoveryEnvelopePayloadV1)

@OptIn(ExperimentalSerializationApi::class)
private object StrictRecoveryTakeoverJson {
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowTrailingComma = false
    }
}

private fun encodeTakeoverState(value: RecoveryTakeoverStateV1): ByteArray =
    StrictRecoveryTakeoverJson.json
        .encodeToString(RecoveryTakeoverStateV1.serializer(), value)
        .toByteArray(Charsets.UTF_8)

private fun decodeTakeoverState(source: ByteArray): RecoveryTakeoverStateV1 = try {
    StrictRecoveryTakeoverJson.json.decodeFromString(
        RecoveryTakeoverStateV1.serializer(),
        source.toString(Charsets.UTF_8),
    )
} catch (failure: SerializationException) {
    throw IllegalArgumentException("Invalid recovery takeover state", failure)
}

private fun VaultKeyEnvelope.clearEnvelope() {
    kdf.salt.fill(0)
    nonce.fill(0)
    wrappedKeyset.fill(0)
}

private fun <T> List<T>.replacedAt(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }

private fun Exception.toRecoveryReason(): RecoveryFailureCategory = when (this) {
    is BoundedRemoteFailure -> reason.toRecoveryReason()
    is RecoveryPassphraseException -> RecoveryFailureCategory.WRONG_PASSPHRASE
    is IOException -> RecoveryFailureCategory.INSUFFICIENT_STORAGE
    is IllegalArgumentException -> RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE
    is IllegalStateException -> RecoveryFailureCategory.STAGING_INVARIANT
    else -> toBoundedReason().toRecoveryReason()
}

/** A staging failure is local: it never means the remote source was wrong. */
private fun Exception.toStagingReason(): RecoveryFailureCategory = when (this) {
    is IOException -> RecoveryFailureCategory.INSUFFICIENT_STORAGE
    else -> RecoveryFailureCategory.STAGING_INVARIANT
}

private fun RemoteBackupFailureCategory.toRecoveryReason(): RecoveryFailureCategory = when (this) {
    RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED ->
        RecoveryFailureCategory.AUTHORIZATION_REQUIRED

    RemoteBackupFailureCategory.ACCOUNT_MISMATCH -> RecoveryFailureCategory.ACCOUNT_MISMATCH
    RemoteBackupFailureCategory.OWNERSHIP_LOST -> RecoveryFailureCategory.OWNERSHIP_LOST
    RemoteBackupFailureCategory.TERMINATED -> RecoveryFailureCategory.TERMINATED
    RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE ->
        RecoveryFailureCategory.AMBIGUOUS_REMOTE_STATE

    RemoteBackupFailureCategory.PROVIDER_STORAGE,
    RemoteBackupFailureCategory.LOCAL_STORAGE,
    -> RecoveryFailureCategory.INSUFFICIENT_STORAGE

    // Nothing was created, and the object this recovery still needs could not
    // be obtained; retrying is the caller's next step.
    RemoteBackupFailureCategory.RETRYABLE_PROVIDER ->
        RecoveryFailureCategory.MISSING_REQUIRED_OBJECT

    RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE ->
        RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE
}

/** Random, process-local, bounded, and derived from nothing observable. */
private fun randomHandle(): String {
    val random = ByteArray(HANDLE_BYTES).also(HANDLE_RANDOM::nextBytes)
    return try {
        buildString(HANDLE_BYTES * 2) {
            random.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HANDLE_ALPHABET[value ushr 4])
                append(HANDLE_ALPHABET[value and 0x0f])
            }
        }
    } finally {
        random.fill(0)
    }
}

private const val HANDLE_BYTES = 16
private const val HANDLE_ALPHABET = "0123456789abcdef"
private const val MAX_PROBE_BYTES = 4L + 16 * 1024
private const val SUPPORTED_ENVELOPE_FORMAT = 1
private const val SUPPORTED_KDF_ALGORITHM = "ARGON2ID"
private const val SUPPORTED_MEMORY_KIB = 65_536
private const val SUPPORTED_ITERATIONS = 3
private const val SUPPORTED_PARALLELISM = 1
private val HANDLE_RANDOM = SecureRandom()
