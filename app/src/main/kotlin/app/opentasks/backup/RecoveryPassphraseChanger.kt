package app.opentasks.backup

import app.opentasks.backup.drive.AuthorizedDriveSession
import app.opentasks.backup.drive.DriveAuthorizationMode
import app.opentasks.backup.drive.DriveAuthorizationResult
import app.opentasks.backup.drive.GoogleDriveAuthorizationManager
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.backup.OwnershipChainStore
import app.opentasks.core.data.backup.OwnershipResolution
import app.opentasks.core.data.backup.PublicationCandidateDiscovery
import app.opentasks.core.data.backup.PublicationCatalog
import app.opentasks.core.data.backup.PublicationCodec
import app.opentasks.core.data.backup.PublicationCreateResult
import app.opentasks.core.data.backup.PublicationManifestV1
import app.opentasks.core.data.backup.PublicationResolution
import app.opentasks.core.data.backup.RecoveryEnvelopeCodec
import app.opentasks.core.data.backup.RecoveryEnvelopePayloadV1
import app.opentasks.core.data.backup.RecoveryEnvelopeStore
import app.opentasks.core.data.backup.RemoteBackupStateStore
import app.opentasks.core.data.backup.VerifiedOwnershipClaim
import app.opentasks.core.data.backup.VerifiedPublication
import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.PassphraseChangeFailureCategory
import app.opentasks.core.domain.PassphraseChangeResult
import app.opentasks.core.domain.RecoveryPassphraseChanger
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.PublicationRef
import app.opentasks.core.model.PublicationSequence
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.VaultId
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal enum class PassphraseRotationPhase {
    PENDING_ENVELOPE_STORED,
    PORTABLE_VERIFIED,
    REMOTE_PUBLICATION_CREATED,
    REMOTE_PUBLICATION_VERIFIED,
    OWNERSHIP_RECHECKED,
    LOCAL_ENVELOPE_PROMOTED,
    COMPLETED,
}

@Serializable
private data class PassphraseRotationStateV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val phase: String,
    val publicationProviderFileId: String,
    val publicationId: String,
    val recoveryEnvelope: RecoveryEnvelopePayloadV1,
    val encodedPublicationBase64: String,
) {
    override fun toString(): String = "PassphraseRotationStateV1(phase=$phase)"
}

/** Rotates only the recovery wrapping while keeping one authenticated content history. */
class DefaultRecoveryPassphraseChanger(
    private val vaultId: VaultId,
    private val crypto: VaultCrypto,
    private val recoveryEnvelopeStore: RecoveryEnvelopeStore,
    private val remoteStateStore: RemoteBackupStateStore,
    private val publishPortable: suspend (VaultKeyEnvelope) -> AndroidBackupStatus,
    private val authorizationManager: GoogleDriveAuthorizationManager,
    private val openObjectStore: (CreateOnlyDriveTransport) -> CreateOnlyBackupObjectStore,
    private val ownershipStore: (CreateOnlyBackupObjectStore) -> OwnershipChainStore,
    private val publicationCatalog: (CreateOnlyBackupObjectStore) -> PublicationCatalog,
    private val publicationCodec: PublicationCodec,
    private val now: () -> Instant = Instant::now,
    private val newPublicationId: () -> PublicationId = PublicationId::new,
) : RecoveryPassphraseChanger {
    private val mutex = Mutex()

    override suspend fun change(
        currentPassphrase: CharArray,
        newPassphrase: CharArray,
    ): PassphraseChangeResult = try {
        mutex.withLock { changeLocked(currentPassphrase, newPassphrase) }
    } finally {
        currentPassphrase.fill('\u0000')
        newPassphrase.fill('\u0000')
    }

    private suspend fun changeLocked(
        currentPassphrase: CharArray,
        newPassphrase: CharArray,
    ): PassphraseChangeResult {
        val configuration = try {
            remoteStateStore.active(vaultId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return localFailure()
        } ?: return localFailure()
        if (configuration.lifecycle != RemoteBackupLifecycle.ACTIVE) return localFailure()

        val currentEnvelope = try {
            recoveryEnvelopeStore.get(vaultId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return localFailure()
        } ?: return localFailure()
        val contentKey = try {
            crypto.unlock(currentPassphrase, currentEnvelope)
        } catch (cancellation: CancellationException) {
            currentEnvelope.clearOwned()
            throw cancellation
        } catch (_: Exception) {
            currentEnvelope.clearOwned()
            return PassphraseChangeResult.Failed(
                PassphraseChangeFailureCategory.CURRENT_PASSPHRASE_INVALID,
            )
        }
        try {
            return execute(configuration, contentKey, newPassphrase)
        } finally {
            contentKey.close()
            currentEnvelope.clearOwned()
        }
    }

    private suspend fun execute(
        configuration: RemoteBackupConfiguration,
        contentKey: VaultKey,
        newPassphrase: CharArray,
    ): PassphraseChangeResult {
        val expectedDigest = configuration.accountBindingDigest
        val authorization = try {
            authorizationManager.authorize(
                DriveAuthorizationMode.NON_INTERACTIVE,
                expectedDigest,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return remoteFailure()
        } finally {
            expectedDigest.fill(0)
        }
        val session = (authorization as? DriveAuthorizationResult.Authorized)?.session
            ?: return remoteFailure()
        return try {
            executeAuthorized(configuration, contentKey, newPassphrase, session)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            remoteFailure()
        } finally {
            session.close()
        }
    }

    private suspend fun executeAuthorized(
        configuration: RemoteBackupConfiguration,
        contentKey: VaultKey,
        newPassphrase: CharArray,
        session: AuthorizedDriveSession,
    ): PassphraseChangeResult {
        val objectStore = openObjectStore(session.transport)
        val chainStore = ownershipStore(objectStore)
        val catalog = publicationCatalog(objectStore)
        val ownership = resolveHeldOwnership(configuration, chainStore, contentKey)
            ?: return remoteFailure()
        var operationId = operationId(configuration.recoveryCredentialGeneration)
        var storedOperation = remoteStateStore.operation(operationId)
        var storedState = storedOperation?.let(::decodeState)
        if (storedOperation == null && configuration.recoveryCredentialGeneration > 0) {
            val priorOperationId = operationId(
                configuration.recoveryCredentialGeneration - 1,
            )
            val priorOperation = remoteStateStore.operation(priorOperationId)
            if (priorOperation?.phase == PassphraseRotationPhase.LOCAL_ENVELOPE_PROMOTED.name) {
                operationId = priorOperationId
                storedOperation = priorOperation
                storedState = decodeState(priorOperation)
            }
        }
        if (storedOperation != null && storedState == null) return localFailure()
        val resolved = resolvePublications(configuration, ownership, catalog, contentKey)
            ?: return remoteFailure()
        val expected = configuration.currentPublication ?: return remoteFailure()
        if (storedOperation?.phase == PassphraseRotationPhase.LOCAL_ENVELOPE_PROMOTED.name) {
            val resumable = checkNotNull(storedState)
            if (!resolved.current.matches(expected) || !isPlanned(resolved.current, resumable)) {
                return remoteFailure()
            }
            return if (
                transition(
                    operationId,
                    configuration,
                    resumable,
                    PassphraseRotationPhase.COMPLETED,
                ) != null
            ) {
                PassphraseChangeResult.Changed()
            } else {
                localFailure()
            }
        }
        val predecessor: VerifiedPublication
        var alreadyPublished: VerifiedPublication? = null
        if (resolved.current.matches(expected)) {
            predecessor = resolved.current
        } else {
            val previous = resolved.previous
            if (storedState == null || previous == null || !previous.matches(expected) ||
                !isPlanned(resolved.current, storedState)
            ) {
                return remoteFailure()
            }
            predecessor = previous
            alreadyPublished = resolved.current
        }

        var replacementEnvelope: VaultKeyEnvelope? = null
        var encodedPublication: ByteArray? = null
        try {
            val state = if (storedState == null) {
                val providerId = objectStore.generateProviderIds(
                    1,
                    RemoteObjectRoleV1.PUBLICATION,
                ).singleOrNull() ?: return remoteFailure()
                val publicationId = newPublicationId()
                replacementEnvelope = crypto.changePassphrase(contentKey, newPassphrase)
                val draft = successor(
                    predecessor,
                    providerId,
                    publicationId,
                    configuration.recoveryCredentialGeneration + 1,
                    operationId,
                )
                val manifest = draft.copy(
                    bootstrapSha256 = publicationCodec.bootstrapSha256(
                        draft,
                        checkNotNull(replacementEnvelope),
                    ),
                )
                encodedPublication = publicationCodec.encode(
                    manifest,
                    checkNotNull(replacementEnvelope),
                    contentKey,
                )
                val fresh = PassphraseRotationStateV1(
                    phase = PassphraseRotationPhase.PENDING_ENVELOPE_STORED.name,
                    publicationProviderFileId = providerId.value,
                    publicationId = publicationId.value,
                    recoveryEnvelope = RecoveryEnvelopeCodec.toPayload(
                        checkNotNull(replacementEnvelope),
                    ),
                    encodedPublicationBase64 = Base64.getEncoder()
                        .withoutPadding()
                        .encodeToString(checkNotNull(encodedPublication)),
                )
                remoteStateStore.putOperation(operation(operationId, configuration, fresh))
                fresh
            } else {
                replacementEnvelope = RecoveryEnvelopeCodec.fromPayload(storedState.recoveryEnvelope)
                encodedPublication = decodePublication(storedState.encodedPublicationBase64)
                    ?: return localFailure()
                val replacementKey = try {
                    crypto.unlock(newPassphrase, checkNotNull(replacementEnvelope))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return PassphraseChangeResult.Failed(
                        PassphraseChangeFailureCategory.CURRENT_PASSPHRASE_INVALID,
                    )
                }
                try {
                    publicationCodec.verify(checkNotNull(encodedPublication), replacementKey)
                } finally {
                    replacementKey.close()
                }
                storedState
            }
            var currentState = state

            if (!reached(currentState, PassphraseRotationPhase.PORTABLE_VERIFIED)) {
                when (publishPortable(checkNotNull(replacementEnvelope))) {
                    is AndroidBackupStatus.Ready,
                    is AndroidBackupStatus.UpdatePending,
                    -> Unit
                    else -> return PassphraseChangeResult.Failed(
                        PassphraseChangeFailureCategory.PORTABLE_PACKAGE,
                    )
                }
                currentState = transition(
                    operationId,
                    configuration,
                    currentState,
                    PassphraseRotationPhase.PORTABLE_VERIFIED,
                ) ?: return localFailure()
            }

            if (!reached(currentState, PassphraseRotationPhase.REMOTE_PUBLICATION_CREATED)) {
                val publication = alreadyPublished ?: run {
                    val created = catalog.create(
                        ProviderObjectId.of(currentState.publicationProviderFileId),
                        ownedCopy(checkNotNull(encodedPublication)),
                        contentKey,
                    )
                    when (created) {
                        is PublicationCreateResult.Created -> created.publication
                        is PublicationCreateResult.OccupiedByExpected -> created.publication
                        else -> return remoteFailure()
                    }
                }
                if (!isPlanned(publication, currentState)) return remoteFailure()
                currentState = transition(
                    operationId,
                    configuration,
                    currentState,
                    PassphraseRotationPhase.REMOTE_PUBLICATION_CREATED,
                ) ?: return localFailure()
            }

            val published = resolvePublicationAfterRotation(
                ownership,
                predecessor,
                currentState,
                catalog,
                contentKey,
            ) ?: return remoteFailure()
            if (!reached(currentState, PassphraseRotationPhase.REMOTE_PUBLICATION_VERIFIED)) {
                currentState = transition(
                    operationId,
                    configuration,
                    currentState,
                    PassphraseRotationPhase.REMOTE_PUBLICATION_VERIFIED,
                ) ?: return localFailure()
            }

            if (!reached(currentState, PassphraseRotationPhase.OWNERSHIP_RECHECKED)) {
                if (resolveHeldOwnership(configuration, chainStore, contentKey) == null) {
                    return remoteFailure()
                }
                currentState = transition(
                    operationId,
                    configuration,
                    currentState,
                    PassphraseRotationPhase.OWNERSHIP_RECHECKED,
                ) ?: return localFailure()
            }

            if (!reached(currentState, PassphraseRotationPhase.LOCAL_ENVELOPE_PROMOTED)) {
                val latest = remoteStateStore.known(configuration.lineageId)
                    ?: return localFailure()
                if (latest.stateVersion != configuration.stateVersion ||
                    latest.currentPublication != configuration.currentPublication
                ) {
                    return remoteFailure()
                }
                val generation = BackupGeneration(published.manifest.localGeneration)
                val nextConfiguration = latest.copy(
                    currentPublication = published.ref(),
                    previousPublication = predecessor.ref(),
                    lastVerifiedGeneration = generation,
                    lastVerifiedAt = now(),
                    recoveryCredentialGeneration =
                        published.manifest.recoveryCredentialGeneration,
                    failureCategory = null,
                    stateVersion = RemoteBackupStateVersion(latest.stateVersion.value + 1),
                )
                val promotedState = currentState.copy(
                    phase = PassphraseRotationPhase.LOCAL_ENVELOPE_PROMOTED.name,
                )
                val promoted = remoteStateStore.promoteRecoveryEnvelope(
                    lineageId = configuration.lineageId,
                    expected = latest.stateVersion,
                    next = nextConfiguration,
                    envelope = checkNotNull(replacementEnvelope),
                    operationId = operationId,
                    expectedOperationPhase = currentState.phase,
                    nextOperation = operation(operationId, configuration, promotedState),
                )
                if (!promoted) return localFailure()
                currentState = promotedState
            }

            if (!reached(currentState, PassphraseRotationPhase.COMPLETED)) {
                transition(
                    operationId,
                    configuration,
                    currentState,
                    PassphraseRotationPhase.COMPLETED,
                ) ?: return localFailure()
            }
            return PassphraseChangeResult.Changed()
        } finally {
            encodedPublication?.fill(0)
            replacementEnvelope?.clearOwned()
        }
    }

    private suspend fun resolveHeldOwnership(
        configuration: RemoteBackupConfiguration,
        chainStore: OwnershipChainStore,
        contentKey: VaultKey,
    ): VerifiedOwnershipClaim? {
        val expected = configuration.ownershipClaim ?: return null
        val resolution = chainStore.resolve(configuration.rootClaimProviderId, contentKey)
        val tip = (resolution as? OwnershipResolution.Active)?.tip ?: return null
        return tip.takeIf {
            it.claim.lineageId == configuration.lineageId.value &&
                it.claim.sourceVaultId == vaultId.value &&
                it.claim.providerFileId == expected.providerId.value &&
                it.claim.claimId == expected.logicalId.value &&
                it.completeSha256 == expected.sha256 &&
                it.claim.writerEpoch == expected.writerEpoch.value &&
                it.claim.activeDeviceId == configuration.activeDeviceId?.value
        }
    }

    private suspend fun resolvePublications(
        configuration: RemoteBackupConfiguration,
        ownership: VerifiedOwnershipClaim,
        catalog: PublicationCatalog,
        contentKey: VaultKey,
    ): PublicationResolution.Resolved? {
        val discovery = catalog.discoverBootstraps(
            configuration.lineageId,
            expectedEpoch(configuration),
            checkNotNull(configuration.ownershipClaim).providerId,
        ) as? PublicationCandidateDiscovery.Discovered ?: return null
        return catalog.resolve(ownership, discovery.candidates, contentKey)
            as? PublicationResolution.Resolved
    }

    private suspend fun resolvePublicationAfterRotation(
        ownership: VerifiedOwnershipClaim,
        predecessor: VerifiedPublication,
        state: PassphraseRotationStateV1,
        catalog: PublicationCatalog,
        contentKey: VaultKey,
    ): VerifiedPublication? {
        val discovery = catalog.discoverBootstraps(
            app.opentasks.core.model.CloudLineageId.parse(predecessor.manifest.lineageId),
            app.opentasks.core.model.WriterEpoch(predecessor.manifest.writerEpoch),
            ProviderObjectId.of(checkNotNull(predecessor.manifest.ownershipClaimProviderFileId)),
        ) as? PublicationCandidateDiscovery.Discovered ?: return null
        val resolution = catalog.resolve(ownership, discovery.candidates, contentKey)
            as? PublicationResolution.Resolved ?: return null
        publicationCodec.requireRetainedPair(resolution.current, resolution.previous, ownership)
        val previous = resolution.previous ?: return null
        return resolution.current.takeIf {
            isPlanned(it, state) &&
                previous.completeSha256 == predecessor.completeSha256 &&
                previous.manifest.publicationId == predecessor.manifest.publicationId
        }
    }

    private fun successor(
        predecessor: VerifiedPublication,
        providerId: ProviderObjectId,
        publicationId: PublicationId,
        recoveryCredentialGeneration: Long,
        operationId: String,
    ): PublicationManifestV1 = predecessor.manifest.copy(
        bootstrapSha256 = ZERO_SHA256,
        publicationProviderFileId = providerId.value,
        publicationId = publicationId.value,
        publicationSequence = predecessor.manifest.publicationSequence + 1,
        predecessorPublicationProviderFileId = predecessor.manifest.publicationProviderFileId,
        predecessorPublicationId = predecessor.manifest.publicationId,
        predecessorPublicationSha256 = predecessor.completeSha256.value,
        baseline = false,
        plannedClaimProviderFileId = null,
        plannedClaimId = null,
        predecessorClaimProviderFileId = null,
        predecessorClaimId = null,
        predecessorClaimSha256 = null,
        publicationOperationId = operationId,
        recoveryCredentialGeneration = recoveryCredentialGeneration,
    )

    private fun operationId(recoveryCredentialGeneration: Long): String =
        OPERATION_PREFIX + vaultId.value + ":" + recoveryCredentialGeneration

    private suspend fun transition(
        operationId: String,
        configuration: RemoteBackupConfiguration,
        current: PassphraseRotationStateV1,
        phase: PassphraseRotationPhase,
    ): PassphraseRotationStateV1? {
        val next = current.copy(phase = phase.name)
        return if (
            remoteStateStore.transitionOperation(
                operationId,
                current.phase,
                operation(operationId, configuration, next),
            )
        ) next else null
    }

    private fun operation(
        operationId: String,
        configuration: RemoteBackupConfiguration,
        state: PassphraseRotationStateV1,
    ): RemoteBackupOperation {
        val encoded = encodeState(state)
        return try {
            RemoteBackupOperation(
                operationId = operationId,
                lineageId = configuration.lineageId,
                kind = OPERATION_KIND,
                phase = state.phase,
                targetEpoch = configuration.writerEpoch,
                targetGeneration = configuration.lastVerifiedGeneration,
                candidateClaimProviderId = configuration.ownershipClaim?.providerId,
                candidatePublicationProviderId =
                    ProviderObjectId.of(state.publicationProviderFileId),
                stateBytes = encoded,
                startedAt = now(),
                updatedAt = now(),
            )
        } finally {
            encoded.fill(0)
        }
    }

    private fun isPlanned(
        publication: VerifiedPublication,
        state: PassphraseRotationStateV1,
    ): Boolean = publication.manifest.publicationProviderFileId ==
        state.publicationProviderFileId && publication.manifest.publicationId == state.publicationId

    private fun reached(
        state: PassphraseRotationStateV1,
        phase: PassphraseRotationPhase,
    ): Boolean = PassphraseRotationPhase.valueOf(state.phase) >= phase

    private fun expectedEpoch(configuration: RemoteBackupConfiguration) =
        checkNotNull(configuration.writerEpoch)

    private fun VerifiedPublication.matches(ref: PublicationRef): Boolean =
        manifest.publicationProviderFileId == ref.providerId.value &&
            manifest.publicationId == ref.logicalId.value && completeSha256 == ref.sha256

    private fun VerifiedPublication.ref(): PublicationRef = PublicationRef(
        providerId = ProviderObjectId.of(manifest.publicationProviderFileId),
        logicalId = PublicationId.parse(manifest.publicationId),
        sha256 = completeSha256,
        sequence = PublicationSequence(manifest.publicationSequence),
        generation = BackupGeneration(manifest.localGeneration),
    )

    private fun decodeState(operation: RemoteBackupOperation): PassphraseRotationStateV1? {
        if (operation.kind != OPERATION_KIND || operation.phase ==
            PassphraseRotationPhase.COMPLETED.name
        ) return null
        val bytes = operation.stateBytes
        return try {
            val state = RotationJson.json.decodeFromString<PassphraseRotationStateV1>(
                bytes.toString(Charsets.UTF_8),
            )
            if (state.phase != operation.phase || state.formatVersion != 1 ||
                state.minimumReaderVersion != 1
            ) null else state
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private fun encodeState(state: PassphraseRotationStateV1): ByteArray =
        RotationJson.json.encodeToString(state).toByteArray(Charsets.UTF_8)

    private fun decodePublication(value: String): ByteArray? = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun ownedCopy(source: ByteArray): OwnedRemoteBytes = object : OwnedRemoteBytes {
        private var bytes: ByteArray? = source.copyOf()
        override val size: Int get() = checkNotNull(bytes).size
        override fun take(): ByteArray = checkNotNull(bytes).also { bytes = null }
        override fun close() {
            bytes?.fill(0)
            bytes = null
        }
    }

    private fun localFailure() = PassphraseChangeResult.Failed(
        PassphraseChangeFailureCategory.LOCAL_STORAGE,
    )

    private fun remoteFailure() = PassphraseChangeResult.Failed(
        PassphraseChangeFailureCategory.REMOTE_BACKUP,
    )

    private fun VaultKeyEnvelope.clearOwned() {
        kdf.salt.fill(0)
        nonce.fill(0)
        wrappedKeyset.fill(0)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private object RotationJson {
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
            isLenient = false
            allowStructuredMapKeys = false
        }
    }

    private companion object {
        const val OPERATION_PREFIX = "recovery-passphrase-change:"
        const val OPERATION_KIND = "RECOVERY_PASSPHRASE_CHANGE"
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
