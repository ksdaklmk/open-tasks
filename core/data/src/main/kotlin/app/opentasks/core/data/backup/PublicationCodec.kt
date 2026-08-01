package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.DecryptedCloudObject
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.PublicationSequence
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.WriterEpoch
import app.opentasks.core.sync.CloudBounds
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class PublicationBootstrapV1(
    val magic: String = "OPEN_TASKS_PUBLICATION",
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val lineageId: String,
    val writerEpoch: Long,
    val plannedClaimProviderFileId: String?,
    val recoveryEnvelope: RecoveryEnvelopePayloadV1,
    val recoveryCredentialGeneration: Long,
    val encryptedFrameLength: Long,
    val encryptedFrameSha256: String,
)

@Serializable
data class RemoteInventoryItemV1(
    val logicalObjectId: String,
    val providerFileId: String,
    val role: RemoteObjectRoleV1,
    val firstGeneration: Long,
    val lastGeneration: Long,
    val frameLength: Long,
    val frameSha256: String,
)

@Serializable
data class PublicationManifestV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val bootstrapSha256: String,
    val lineageId: String,
    val sourceVaultId: String,
    val writerEpoch: Long,
    val activeDeviceId: String,
    val publicationProviderFileId: String,
    val publicationId: String,
    val publicationSequence: Long,
    val predecessorPublicationProviderFileId: String?,
    val predecessorPublicationId: String?,
    val predecessorPublicationSha256: String?,
    val baseline: Boolean,
    val plannedClaimProviderFileId: String?,
    val plannedClaimId: String?,
    val predecessorClaimProviderFileId: String?,
    val predecessorClaimId: String?,
    val predecessorClaimSha256: String?,
    val ownershipClaimProviderFileId: String?,
    val ownershipClaimId: String?,
    val ownershipClaimSha256: String?,
    val localGeneration: Long,
    val publicationOperationId: String,
    val currentBaseObjectId: String,
    val fallbackBaseObjectId: String,
    val inventory: List<RemoteInventoryItemV1>,
    val recoveryCredentialGeneration: Long,
)

data class VerifiedPublication(
    val bootstrap: PublicationBootstrapV1,
    val manifest: PublicationManifestV1,
    val completeSha256: Sha256Digest,
)

/**
 * Immutable create-only publication format.
 *
 * Every complete publication file is exactly:
 *
 * ```text
 * 4-byte unsigned big-endian public JSON length
 * canonical public JSON bytes
 * authenticated cloud frame bytes
 * ```
 *
 * The public bootstrap carries the recovery envelope, so its KDF and length
 * bounds are validated before any buffer is allocated or key derived. The
 * bootstrap cannot digest the frame that digests it, so the manifest binds the
 * bootstrap through its *binding pre-image*: the canonical bootstrap JSON with
 * a zero frame length and zero frame digest. Every recovery-critical bootstrap
 * field is covered; the two frame declarations are separately checked against
 * the actual frame bytes.
 */
class PublicationCodec(
    private val authenticatedCodec: AuthenticatedCloudObjectCodec,
) {
    /**
     * The digest a manifest must carry for the bootstrap that will publish it.
     */
    fun bootstrapSha256(
        manifest: PublicationManifestV1,
        envelope: VaultKeyEnvelope,
    ): String {
        val binding = canonicalBootstrapBytes(bindingBootstrap(manifest, envelope))
        return try {
            sha256(binding)
        } finally {
            binding.fill(0)
        }
    }

    fun encode(
        manifest: PublicationManifestV1,
        envelope: VaultKeyEnvelope,
        contentKey: VaultKey,
    ): ByteArray {
        validateManifest(manifest)
        require(manifest.bootstrapSha256 == bootstrapSha256(manifest, envelope)) {
            "Publication manifest does not digest its bootstrap"
        }
        var plaintext: ByteArray? = null
        var frame: ByteArray? = null
        var bootstrapBytes: ByteArray? = null
        try {
            plaintext = canonicalManifestBytes(manifest)
            frame = authenticatedCodec.encrypt(
                identity(manifest.lineageId, manifest.publicationId),
                plaintext,
                contentKey,
            )
            val bootstrap = bindingBootstrap(manifest, envelope).copy(
                encryptedFrameLength = frame.size.toLong(),
                encryptedFrameSha256 = sha256(frame),
            )
            bootstrapBytes = canonicalBootstrapBytes(bootstrap)
            val total = checkedFileLength(bootstrapBytes.size, bootstrap.encryptedFrameLength)
            return ByteArray(total.toInt()).also { file ->
                ByteBuffer.wrap(file, 0, LENGTH_PREFIX_BYTES).putInt(bootstrapBytes.size)
                bootstrapBytes.copyInto(file, LENGTH_PREFIX_BYTES)
                frame.copyInto(file, LENGTH_PREFIX_BYTES + bootstrapBytes.size)
            }
        } finally {
            bootstrapBytes?.fill(0)
            frame?.fill(0)
            plaintext?.fill(0)
        }
    }

    /**
     * Reads the bounded public bootstrap without decrypting the manifest.
     *
     * Recovery KDF parameters and every declared length are validated here, so
     * no oversized buffer is allocated and no key is derived from an untrusted
     * or weakened envelope.
     */
    fun readBootstrap(source: ByteArray): PublicationBootstrapV1 {
        require(source.size >= MINIMUM_FILE_BYTES) { "Publication file is truncated" }
        val bootstrapLength = ByteBuffer.wrap(source, 0, LENGTH_PREFIX_BYTES).int
        require(bootstrapLength in 1..MAX_BOOTSTRAP_BYTES) {
            "Publication bootstrap length is outside its bound"
        }
        require(source.size >= LENGTH_PREFIX_BYTES + bootstrapLength) {
            "Publication file is truncated"
        }
        val bootstrapBytes = source.copyOfRange(
            LENGTH_PREFIX_BYTES,
            LENGTH_PREFIX_BYTES + bootstrapLength,
        )
        try {
            val bootstrap = decodeCanonicalBootstrap(bootstrapBytes)
            validateBootstrap(bootstrap)
            checkedFileLength(bootstrapLength, bootstrap.encryptedFrameLength)
            return bootstrap
        } finally {
            bootstrapBytes.fill(0)
        }
    }

    fun verify(
        source: ByteArray,
        contentKey: VaultKey,
    ): VerifiedPublication {
        val bootstrap = readBootstrap(source)
        val bootstrapLength = ByteBuffer.wrap(source, 0, LENGTH_PREFIX_BYTES).int
        require(
            checkedFileLength(bootstrapLength, bootstrap.encryptedFrameLength) ==
                source.size.toLong(),
        ) {
            "Publication length does not match its declaration"
        }
        val frame = source.copyOfRange(LENGTH_PREFIX_BYTES + bootstrapLength, source.size)
        val decrypted = try {
            require(sha256(frame) == bootstrap.encryptedFrameSha256) {
                "Publication frame checksum mismatch"
            }
            decrypt(frame, contentKey)
        } finally {
            frame.fill(0)
        }
        val frameIdentity = decrypted.identity
        val manifest = decrypted.usePlaintext(::decodeCanonicalManifest)
        validateManifest(manifest)
        require(frameIdentity == identity(manifest.lineageId, manifest.publicationId)) {
            "Publication frame identity mismatch"
        }
        requireBootstrapAgreement(bootstrap, manifest)
        return VerifiedPublication(
            bootstrap = bootstrap,
            manifest = manifest,
            completeSha256 = Sha256Digest.of(sha256(source)),
        )
    }

    /**
     * Requires [current] to be the only permitted immediate successor of
     * [previous] inside one writer epoch.
     */
    fun requireSuccessor(
        previous: PublicationManifestV1,
        current: PublicationManifestV1,
    ) {
        validateManifest(previous)
        validateManifest(current)
        require(!current.baseline) { "A baseline publication has no predecessor" }
        require(current.lineageId == previous.lineageId) {
            "Publication successor belongs to another lineage"
        }
        require(current.sourceVaultId == previous.sourceVaultId) {
            "Publication successor names another source vault"
        }
        require(current.writerEpoch == previous.writerEpoch) {
            "Publication successor belongs to another writer epoch"
        }
        require(current.activeDeviceId == previous.activeDeviceId) {
            "Publication successor names another active device"
        }
        require(
            current.publicationSequence ==
                addExact(previous.publicationSequence, 1, "Publication sequence"),
        ) {
            "Publication sequence does not increase by exactly one"
        }
        require(current.publicationId != previous.publicationId) {
            "Publication successor repeats its predecessor identity"
        }
        require(current.publicationProviderFileId != previous.publicationProviderFileId) {
            "Publication successor repeats its predecessor provider file"
        }
        require(current.predecessorPublicationId == previous.publicationId) {
            "Publication successor names another predecessor publication"
        }
        require(
            current.predecessorPublicationProviderFileId ==
                previous.publicationProviderFileId,
        ) {
            "Publication successor names another predecessor provider file"
        }
        require(current.localGeneration >= previous.localGeneration) {
            "Local generation regressed across the retained publication pair"
        }
        require(
            current.recoveryCredentialGeneration >= previous.recoveryCredentialGeneration,
        ) {
            "Recovery credential generation regressed across the retained pair"
        }
        require(
            current.localGeneration > previous.localGeneration ||
                current.recoveryCredentialGeneration > previous.recoveryCredentialGeneration,
        ) {
            "An unchanged local generation requires a newer recovery credential"
        }
        require(claimProviderFileIdOf(current) == claimProviderFileIdOf(previous)) {
            "Publication successor names another ownership claim provider file"
        }
        require(claimIdOf(current) == claimIdOf(previous)) {
            "Publication successor names another ownership claim"
        }
    }

    /**
     * Requires the retained current/previous pair to be the single unambiguous
     * publication authority of [ownership].
     */
    fun requireRetainedPair(
        current: VerifiedPublication,
        previous: VerifiedPublication?,
        ownership: VerifiedOwnershipClaim,
    ) {
        require(ownership.claim.state == OwnershipStateV1.ACTIVE) {
            "A terminated ownership claim publishes nothing"
        }
        requireOwnershipBinding(current, ownership)
        if (previous == null) {
            require(current.manifest.baseline) {
                "A non-baseline publication requires its retained predecessor"
            }
            return
        }
        requireOwnershipBinding(previous, ownership)
        require(previous.completeSha256 != current.completeSha256) {
            "The retained publication pair repeats one file"
        }
        requireSuccessor(previous = previous.manifest, current = current.manifest)
        require(
            current.manifest.predecessorPublicationSha256 == previous.completeSha256.value,
        ) {
            "The current publication does not digest its retained predecessor"
        }
    }

    private fun requireOwnershipBinding(
        publication: VerifiedPublication,
        ownership: VerifiedOwnershipClaim,
    ) {
        val manifest = publication.manifest
        val claim = ownership.claim
        require(manifest.lineageId == claim.lineageId) {
            "Publication belongs to another lineage"
        }
        require(manifest.writerEpoch == claim.writerEpoch) {
            "Publication belongs to another writer epoch"
        }
        require(manifest.activeDeviceId == claim.activeDeviceId) {
            "Publication names another active device"
        }
        require(manifest.sourceVaultId == claim.sourceVaultId) {
            "Publication names another source vault"
        }
        require(
            manifest.recoveryCredentialGeneration >=
                (claim.recoveryCredentialGeneration ?: 0),
        ) {
            "Publication regresses the claimed recovery credential generation"
        }
        if (manifest.baseline) {
            require(manifest.plannedClaimProviderFileId == claim.providerFileId) {
                "The baseline publication plans another ownership claim provider file"
            }
            require(manifest.plannedClaimId == claim.claimId) {
                "The baseline publication plans another ownership claim"
            }
            require(
                manifest.predecessorClaimProviderFileId == claim.predecessorProviderFileId &&
                    manifest.predecessorClaimId == claim.predecessorClaimId &&
                    manifest.predecessorClaimSha256 == claim.predecessorClaimSha256,
            ) {
                "The baseline publication names another predecessor ownership claim"
            }
            require(
                manifest.publicationProviderFileId ==
                    claim.baselinePublicationProviderFileId,
            ) {
                "The ownership claim binds another baseline provider file"
            }
            require(manifest.publicationId == claim.baselinePublicationId) {
                "The ownership claim binds another baseline publication"
            }
            require(
                publication.completeSha256.value == claim.baselinePublicationSha256,
            ) {
                "The ownership claim digests another baseline publication"
            }
        } else {
            require(manifest.ownershipClaimProviderFileId == claim.providerFileId) {
                "Publication names another ownership claim provider file"
            }
            require(manifest.ownershipClaimId == claim.claimId) {
                "Publication names another ownership claim"
            }
            require(manifest.ownershipClaimSha256 == ownership.completeSha256.value) {
                "Publication digests another ownership claim"
            }
        }
    }

    /**
     * Authenticates one frame. The authenticated identity is compared with the
     * decrypted manifest afterwards, so a frame moved between publication files
     * never becomes authority.
     */
    private fun decrypt(
        frame: ByteArray,
        contentKey: VaultKey,
    ): DecryptedCloudObject {
        val result = authenticatedCodec.decrypt(
            source = ByteArrayInputStream(frame),
            totalLength = frame.size.toLong(),
            key = contentKey,
        )
        require(result is CloudDecodeResult.Success) {
            "Publication frame authentication failed"
        }
        return result.value
    }

    private fun bindingBootstrap(
        manifest: PublicationManifestV1,
        envelope: VaultKeyEnvelope,
    ): PublicationBootstrapV1 = PublicationBootstrapV1(
        lineageId = manifest.lineageId,
        writerEpoch = manifest.writerEpoch,
        plannedClaimProviderFileId = manifest.plannedClaimProviderFileId,
        recoveryEnvelope = RecoveryEnvelopeCodec.toPayload(envelope),
        recoveryCredentialGeneration = manifest.recoveryCredentialGeneration,
        encryptedFrameLength = 0,
        encryptedFrameSha256 = ZERO_SHA256,
    )

    private fun requireBootstrapAgreement(
        bootstrap: PublicationBootstrapV1,
        manifest: PublicationManifestV1,
    ) {
        require(bootstrap.lineageId == manifest.lineageId) {
            "Publication bootstrap names another lineage"
        }
        require(bootstrap.writerEpoch == manifest.writerEpoch) {
            "Publication bootstrap names another writer epoch"
        }
        require(
            bootstrap.plannedClaimProviderFileId == manifest.plannedClaimProviderFileId,
        ) {
            "Publication bootstrap names another planned ownership claim"
        }
        require(
            bootstrap.recoveryCredentialGeneration == manifest.recoveryCredentialGeneration,
        ) {
            "Publication bootstrap names another recovery credential generation"
        }
        val envelope = RecoveryEnvelopeCodec.fromPayload(bootstrap.recoveryEnvelope)
        val digest = try {
            bootstrapSha256(manifest, envelope)
        } finally {
            envelope.kdf.salt.fill(0)
            envelope.nonce.fill(0)
            envelope.wrappedKeyset.fill(0)
        }
        require(manifest.bootstrapSha256 == digest) {
            "Publication manifest does not digest its bootstrap"
        }
    }

    private fun validateBootstrap(bootstrap: PublicationBootstrapV1) {
        require(bootstrap.magic == MAGIC) { "Unsupported publication magic" }
        require(bootstrap.formatVersion == FORMAT_VERSION) {
            "Unsupported publication format version ${bootstrap.formatVersion}"
        }
        require(bootstrap.minimumReaderVersion == MINIMUM_READER_VERSION) {
            "Unsupported publication minimum reader ${bootstrap.minimumReaderVersion}"
        }
        CloudLineageId.parse(bootstrap.lineageId)
        WriterEpoch(bootstrap.writerEpoch)
        bootstrap.plannedClaimProviderFileId?.let(ProviderObjectId::of)
        require(bootstrap.recoveryCredentialGeneration >= 0) {
            "Recovery credential generation is negative"
        }
        val envelope = RecoveryEnvelopeCodec.fromPayload(bootstrap.recoveryEnvelope)
        envelope.kdf.salt.fill(0)
        envelope.nonce.fill(0)
        envelope.wrappedKeyset.fill(0)
        require(bootstrap.encryptedFrameLength in 1..MAX_FRAME_BYTES) {
            "Publication frame length is outside its bound"
        }
        Sha256Digest.of(bootstrap.encryptedFrameSha256)
    }

    private fun validateManifest(manifest: PublicationManifestV1) {
        require(manifest.formatVersion == FORMAT_VERSION) {
            "Unsupported publication manifest version ${manifest.formatVersion}"
        }
        require(manifest.minimumReaderVersion == MINIMUM_READER_VERSION) {
            "Unsupported publication manifest minimum reader ${manifest.minimumReaderVersion}"
        }
        Sha256Digest.of(manifest.bootstrapSha256)
        CloudLineageId.parse(manifest.lineageId)
        ProviderObjectId.of(manifest.sourceVaultId)
        WriterEpoch(manifest.writerEpoch)
        CloudDeviceId.parse(manifest.activeDeviceId)
        ProviderObjectId.of(manifest.publicationProviderFileId)
        PublicationId.parse(manifest.publicationId)
        PublicationSequence(manifest.publicationSequence)
        ProviderObjectId.of(manifest.publicationOperationId)
        require(manifest.localGeneration >= 0) { "Local generation is negative" }
        require(manifest.recoveryCredentialGeneration >= 0) {
            "Recovery credential generation is negative"
        }
        require(manifest.baseline == (manifest.publicationSequence == BASELINE_SEQUENCE)) {
            "Only sequence zero may be an epoch baseline"
        }
        if (manifest.baseline) {
            validateBaselineFields(manifest)
        } else {
            validateSuccessorFields(manifest)
        }
        validateInventory(manifest)
    }

    private fun validateBaselineFields(manifest: PublicationManifestV1) {
        require(
            manifest.predecessorPublicationProviderFileId == null &&
                manifest.predecessorPublicationId == null &&
                manifest.predecessorPublicationSha256 == null,
        ) {
            "An epoch baseline has no predecessor publication"
        }
        require(
            manifest.ownershipClaimProviderFileId == null &&
                manifest.ownershipClaimId == null &&
                manifest.ownershipClaimSha256 == null,
        ) {
            "An epoch baseline precedes its ownership claim"
        }
        ProviderObjectId.of(
            requireNotNull(manifest.plannedClaimProviderFileId) {
                "An epoch baseline names its planned ownership claim provider file"
            },
        )
        OwnershipClaimId.parse(
            requireNotNull(manifest.plannedClaimId) {
                "An epoch baseline names its planned ownership claim"
            },
        )
        val predecessorProviderFileId = manifest.predecessorClaimProviderFileId
        if (predecessorProviderFileId == null) {
            require(
                manifest.predecessorClaimId == null &&
                    manifest.predecessorClaimSha256 == null,
            ) {
                "An initial-lineage baseline names no predecessor ownership claim"
            }
        } else {
            ProviderObjectId.of(predecessorProviderFileId)
            OwnershipClaimId.parse(
                requireNotNull(manifest.predecessorClaimId) {
                    "A takeover baseline names its predecessor ownership claim"
                },
            )
            Sha256Digest.of(
                requireNotNull(manifest.predecessorClaimSha256) {
                    "A takeover baseline digests its predecessor ownership claim"
                },
            )
        }
    }

    private fun validateSuccessorFields(manifest: PublicationManifestV1) {
        require(
            manifest.plannedClaimProviderFileId == null &&
                manifest.plannedClaimId == null &&
                manifest.predecessorClaimProviderFileId == null &&
                manifest.predecessorClaimId == null &&
                manifest.predecessorClaimSha256 == null,
        ) {
            "Only an epoch baseline carries planned and predecessor claim fields"
        }
        val predecessorProviderFileId = requireNotNull(
            manifest.predecessorPublicationProviderFileId,
        ) {
            "A publication names its predecessor provider file"
        }
        ProviderObjectId.of(predecessorProviderFileId)
        val predecessorPublicationId = requireNotNull(manifest.predecessorPublicationId) {
            "A publication names its predecessor publication"
        }
        PublicationId.parse(predecessorPublicationId)
        Sha256Digest.of(
            requireNotNull(manifest.predecessorPublicationSha256) {
                "A publication digests its predecessor publication"
            },
        )
        require(predecessorPublicationId != manifest.publicationId) {
            "A publication cannot precede itself"
        }
        require(predecessorProviderFileId != manifest.publicationProviderFileId) {
            "A publication cannot occupy its predecessor provider file"
        }
        ProviderObjectId.of(
            requireNotNull(manifest.ownershipClaimProviderFileId) {
                "A publication names its ownership claim provider file"
            },
        )
        OwnershipClaimId.parse(
            requireNotNull(manifest.ownershipClaimId) {
                "A publication names its ownership claim"
            },
        )
        Sha256Digest.of(
            requireNotNull(manifest.ownershipClaimSha256) {
                "A publication digests its ownership claim"
            },
        )
    }

    private fun validateInventory(manifest: PublicationManifestV1) {
        require(manifest.inventory.size in 2..MAX_INVENTORY_ENTRIES) {
            "Publication inventory size is outside its bound"
        }
        val providerFileIds = mutableSetOf<String>()
        manifest.inventory.forEachIndexed { index, item ->
            RemoteLogicalObjectId.of(item.logicalObjectId)
            ProviderObjectId.of(item.providerFileId)
            Sha256Digest.of(item.frameSha256)
            require(
                index == 0 ||
                    manifest.inventory[index - 1].logicalObjectId < item.logicalObjectId,
            ) {
                "Publication inventory is not sorted by unique logical object"
            }
            require(providerFileIds.add(item.providerFileId)) {
                "Publication inventory repeats one provider file"
            }
            require(
                item.role == RemoteObjectRoleV1.SNAPSHOT ||
                    item.role == RemoteObjectRoleV1.SEGMENT,
            ) {
                "Publication inventory holds only bases and segments"
            }
            require(item.firstGeneration in 0..item.lastGeneration) {
                "Publication inventory generation range is inverted"
            }
            require(item.lastGeneration <= manifest.localGeneration) {
                "Publication inventory covers a generation beyond the publication"
            }
            require(item.frameLength in 1..maximumFrameBytes(item.role)) {
                "Publication inventory frame length is outside its bound"
            }
            if (item.role == RemoteObjectRoleV1.SNAPSHOT) {
                require(item.firstGeneration == item.lastGeneration) {
                    "A complete base covers exactly one generation"
                }
            }
        }
        require(manifest.currentBaseObjectId != manifest.fallbackBaseObjectId) {
            "The current and fallback bases must be independent objects"
        }
        val current = requireBase(manifest, manifest.currentBaseObjectId)
        val fallback = requireBase(manifest, manifest.fallbackBaseObjectId)
        requireSegmentCoverage(manifest, current)
        requireSegmentCoverage(manifest, fallback)
    }

    private fun requireBase(
        manifest: PublicationManifestV1,
        logicalObjectId: String,
    ): RemoteInventoryItemV1 {
        val base = requireNotNull(
            manifest.inventory.firstOrNull { it.logicalObjectId == logicalObjectId },
        ) {
            "A declared complete base is absent from the inventory"
        }
        require(base.role == RemoteObjectRoleV1.SNAPSHOT) {
            "A declared complete base is not a snapshot"
        }
        return base
    }

    private fun requireSegmentCoverage(
        manifest: PublicationManifestV1,
        base: RemoteInventoryItemV1,
    ) {
        var covered = base.lastGeneration
        manifest.inventory
            .filter { it.role == RemoteObjectRoleV1.SEGMENT }
            .sortedWith(
                compareBy(
                    RemoteInventoryItemV1::firstGeneration,
                    RemoteInventoryItemV1::lastGeneration,
                ),
            )
            .forEach { segment ->
                if (segment.lastGeneration <= covered) return@forEach
                require(segment.firstGeneration - 1 <= covered) {
                    "Publication inventory leaves a generation gap after a complete base"
                }
                covered = segment.lastGeneration
            }
        require(covered >= manifest.localGeneration) {
            "Publication inventory does not cover every generation after a complete base"
        }
    }

    private fun maximumFrameBytes(role: RemoteObjectRoleV1): Long = when (role) {
        RemoteObjectRoleV1.SNAPSHOT ->
            LENGTH_PREFIX_BYTES + CloudBounds.MAX_HEADER_BYTES +
                CloudBounds.MAX_SNAPSHOT_CIPHERTEXT_BYTES

        else ->
            LENGTH_PREFIX_BYTES + CloudBounds.MAX_HEADER_BYTES +
                CloudBounds.MAX_OPERATION_SEGMENT_CIPHERTEXT_BYTES
    }

    private fun claimProviderFileIdOf(manifest: PublicationManifestV1): String? =
        manifest.ownershipClaimProviderFileId ?: manifest.plannedClaimProviderFileId

    private fun claimIdOf(manifest: PublicationManifestV1): String? =
        manifest.ownershipClaimId ?: manifest.plannedClaimId

    private fun decodeCanonicalBootstrap(source: ByteArray): PublicationBootstrapV1 {
        val bootstrap = try {
            StrictPublicationJson.json.decodeFromString(
                PublicationBootstrapV1.serializer(),
                strictUtf8(source, "Publication bootstrap"),
            )
        } catch (failure: SerializationException) {
            throw IllegalArgumentException("Invalid publication bootstrap", failure)
        }
        val canonical = canonicalBootstrapBytes(bootstrap)
        try {
            require(source.contentEquals(canonical)) {
                "Publication bootstrap is not canonical"
            }
        } finally {
            canonical.fill(0)
        }
        return bootstrap
    }

    private fun decodeCanonicalManifest(source: ByteArray): PublicationManifestV1 {
        require(source.isNotEmpty()) { "Publication manifest is empty" }
        require(source.size <= MAX_MANIFEST_PLAINTEXT_BYTES) {
            "Publication manifest exceeds $MAX_MANIFEST_PLAINTEXT_BYTES bytes"
        }
        val manifest = try {
            StrictPublicationJson.json.decodeFromString(
                PublicationManifestV1.serializer(),
                strictUtf8(source, "Publication manifest"),
            )
        } catch (failure: SerializationException) {
            throw IllegalArgumentException("Invalid publication manifest", failure)
        }
        val canonical = canonicalManifestBytes(manifest)
        try {
            require(source.contentEquals(canonical)) {
                "Publication manifest is not canonical"
            }
        } finally {
            canonical.fill(0)
        }
        return manifest
    }

    private fun canonicalBootstrapBytes(bootstrap: PublicationBootstrapV1): ByteArray =
        StrictPublicationJson.json
            .encodeToString(PublicationBootstrapV1.serializer(), bootstrap)
            .toByteArray(Charsets.UTF_8)
            .also {
                require(it.size <= MAX_BOOTSTRAP_BYTES) {
                    "Publication bootstrap exceeds $MAX_BOOTSTRAP_BYTES bytes"
                }
            }

    private fun canonicalManifestBytes(manifest: PublicationManifestV1): ByteArray =
        StrictPublicationJson.json
            .encodeToString(PublicationManifestV1.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)
            .also {
                require(it.size <= MAX_MANIFEST_PLAINTEXT_BYTES) {
                    "Publication manifest exceeds $MAX_MANIFEST_PLAINTEXT_BYTES bytes"
                }
            }

    private fun checkedFileLength(
        bootstrapLength: Int,
        frameLength: Long,
    ): Long {
        require(bootstrapLength in 1..MAX_BOOTSTRAP_BYTES) {
            "Publication bootstrap length is outside its bound"
        }
        require(frameLength in 1..MAX_FRAME_BYTES) {
            "Publication frame length is outside its bound"
        }
        val total = addExact(
            addExact(LENGTH_PREFIX_BYTES.toLong(), bootstrapLength.toLong(), "Publication"),
            frameLength,
            "Publication",
        )
        require(total <= Int.MAX_VALUE) { "Publication file cannot be allocated" }
        return total
    }

    private fun identity(
        lineageId: String,
        publicationId: String,
    ): CloudHeaderIdentity = CloudHeaderIdentity(
        family = CloudObjectFamily.MANIFEST,
        schemaVersion = FORMAT_VERSION,
        cryptoVersion = FORMAT_VERSION,
        minimumReaderVersion = MINIMUM_READER_VERSION,
        vaultId = lineageId,
        objectId = publicationId,
    )

    companion object {
        const val MAX_BOOTSTRAP_BYTES: Int = CloudBounds.MAX_HEADER_BYTES
        const val MAX_INVENTORY_ENTRIES: Int = CloudBounds.MAX_MANIFEST_INVENTORY_ENTRIES
        val MAX_MANIFEST_PLAINTEXT_BYTES: Int = (
            CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES -
                CloudBounds.AES_GCM_V1_CIPHERTEXT_OVERHEAD_BYTES
            ).toInt()
        val MAX_FRAME_BYTES: Long = LENGTH_PREFIX_BYTES.toLong() +
            CloudBounds.MAX_HEADER_BYTES + CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES
    }
}

@OptIn(ExperimentalSerializationApi::class)
private object StrictPublicationJson {
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowTrailingComma = false
    }
}

private const val LENGTH_PREFIX_BYTES = 4
private const val FORMAT_VERSION = 1
private const val MINIMUM_READER_VERSION = 1
private const val BASELINE_SEQUENCE = 0L
private const val MAGIC = "OPEN_TASKS_PUBLICATION"
private const val MINIMUM_FILE_BYTES = LENGTH_PREFIX_BYTES + 2
private const val HEX_ALPHABET = "0123456789abcdef"
private const val ZERO_SHA256 =
    "0000000000000000000000000000000000000000000000000000000000000000"

private fun <T> DecryptedCloudObject.usePlaintext(block: (ByteArray) -> T): T {
    val plaintext = takePlaintext()
    return try {
        block(plaintext)
    } finally {
        plaintext.fill(0)
        close()
    }
}

private fun addExact(
    left: Long,
    right: Long,
    label: String,
): Long = try {
    Math.addExact(left, right)
} catch (failure: ArithmeticException) {
    throw IllegalArgumentException("$label overflows", failure)
}

private fun strictUtf8(
    source: ByteArray,
    label: String,
): String = try {
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(source))
        .toString()
} catch (failure: Exception) {
    throw IllegalArgumentException("$label is not valid UTF-8", failure)
}

private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return try {
        buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_ALPHABET[value ushr 4])
                append(HEX_ALPHABET[value and 0x0f])
            }
        }
    } finally {
        digest.fill(0)
    }
}
