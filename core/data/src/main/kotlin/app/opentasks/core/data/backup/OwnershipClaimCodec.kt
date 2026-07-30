package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.DecryptedCloudObject
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
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
data class OwnershipPublicHeaderV1(
    val magic: String = "OPEN_TASKS_OWNERSHIP",
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val lineageId: String,
    val claimId: String,
    val writerEpoch: Long,
    val state: OwnershipStateV1,
    val role: RemoteObjectRoleV1,
    val providerFileId: String,
    val nextSuccessorProviderFileId: String?,
    val encryptedFrameLength: Long,
    val encryptedFrameSha256: String,
)

@Serializable
data class OwnershipClaimV1(
    val formatVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val lineageId: String,
    val writerEpoch: Long,
    val state: OwnershipStateV1,
    val predecessorProviderFileId: String?,
    val predecessorClaimId: String?,
    val predecessorClaimSha256: String?,
    val providerFileId: String,
    val claimId: String,
    val predecessorReservedSuccessorProviderFileId: String?,
    val sourceVaultId: String?,
    val activeDeviceId: String?,
    val nextSuccessorProviderFileId: String?,
    val baselinePublicationProviderFileId: String?,
    val baselinePublicationId: String?,
    val baselinePublicationSha256: String?,
    val recoveryCredentialGeneration: Long?,
    val creationOperationId: String,
    val tombstoneId: String?,
)

data class VerifiedOwnershipClaim(
    val header: OwnershipPublicHeaderV1,
    val claim: OwnershipClaimV1,
    val completeSha256: Sha256Digest,
)

/**
 * Immutable create-only ownership-claim format.
 *
 * Every complete claim file is exactly:
 *
 * ```text
 * 4-byte unsigned big-endian public JSON length
 * canonical public JSON bytes
 * authenticated cloud frame bytes
 * ```
 *
 * The public header is a navigation index only. The authenticated frame
 * repeats the provider ID and every public authoritative identity, and
 * verification rejects any file whose header does not restate the
 * authenticated claim exactly.
 */
class OwnershipClaimCodec(
    private val authenticatedCodec: AuthenticatedCloudObjectCodec,
) {
    fun encode(
        claim: OwnershipClaimV1,
        contentKey: VaultKey,
    ): ByteArray {
        validateClaim(claim)
        var plaintext: ByteArray? = null
        var frame: ByteArray? = null
        var headerBytes: ByteArray? = null
        try {
            plaintext = canonicalClaimBytes(claim)
            frame = authenticatedCodec.encrypt(
                identity(claim.lineageId, claim.claimId),
                plaintext,
                contentKey,
            )
            val header = headerFor(claim, frame)
            headerBytes = canonicalHeaderBytes(header)
            val total = checkedFileLength(headerBytes.size, header.encryptedFrameLength)
            return ByteArray(total.toInt()).also { file ->
                ByteBuffer.wrap(file, 0, LENGTH_PREFIX_BYTES).putInt(headerBytes.size)
                headerBytes.copyInto(file, LENGTH_PREFIX_BYTES)
                frame.copyInto(file, LENGTH_PREFIX_BYTES + headerBytes.size)
            }
        } finally {
            headerBytes?.fill(0)
            frame?.fill(0)
            plaintext?.fill(0)
        }
    }

    /**
     * Reads the bounded public header of a claim file without decrypting it.
     *
     * Every declared length is validated before any frame buffer is allocated.
     */
    fun readPublicHeader(source: ByteArray): OwnershipPublicHeaderV1 {
        require(source.size >= MINIMUM_FILE_BYTES) { "Ownership claim file is truncated" }
        val headerLength = ByteBuffer.wrap(source, 0, LENGTH_PREFIX_BYTES).int
        require(headerLength in 1..MAX_PUBLIC_HEADER_BYTES) {
            "Ownership public header length is outside its bound"
        }
        require(source.size >= LENGTH_PREFIX_BYTES + headerLength) {
            "Ownership claim file is truncated"
        }
        val headerBytes = source.copyOfRange(
            LENGTH_PREFIX_BYTES,
            LENGTH_PREFIX_BYTES + headerLength,
        )
        try {
            val header = decodeCanonicalHeader(headerBytes)
            validateHeader(header)
            checkedFileLength(headerLength, header.encryptedFrameLength)
            return header
        } finally {
            headerBytes.fill(0)
        }
    }

    fun verify(
        source: ByteArray,
        contentKey: VaultKey,
    ): VerifiedOwnershipClaim {
        val header = readPublicHeader(source)
        val headerLength = ByteBuffer.wrap(source, 0, LENGTH_PREFIX_BYTES).int
        require(
            checkedFileLength(headerLength, header.encryptedFrameLength) ==
                source.size.toLong(),
        ) {
            "Ownership claim length does not match its declaration"
        }
        val frame = source.copyOfRange(LENGTH_PREFIX_BYTES + headerLength, source.size)
        val claim = try {
            require(sha256(frame) == header.encryptedFrameSha256) {
                "Ownership claim frame checksum mismatch"
            }
            decrypt(frame, contentKey, identity(header.lineageId, header.claimId))
                .usePlaintext(::decodeCanonicalClaim)
        } finally {
            frame.fill(0)
        }
        validateClaim(claim)
        val restated = headerFor(
            claim = claim,
            frameLength = header.encryptedFrameLength,
            frameSha256 = header.encryptedFrameSha256,
        )
        require(restated == header) {
            "Ownership public header does not restate the authenticated claim"
        }
        return VerifiedOwnershipClaim(
            header = header,
            claim = claim,
            completeSha256 = Sha256Digest.of(sha256(source)),
        )
    }

    /**
     * Authenticates one candidate as the only permitted successor of
     * [predecessor]: it must occupy the exact reserved provider slot, name and
     * digest that predecessor, and increment the writer epoch by exactly one.
     */
    fun verifySuccessor(
        predecessor: VerifiedOwnershipClaim,
        candidate: ByteArray,
        contentKey: VaultKey,
    ): VerifiedOwnershipClaim {
        val successor = verify(candidate, contentKey)
        require(predecessor.claim.state == OwnershipStateV1.ACTIVE) {
            "A terminated ownership claim accepts no successor"
        }
        val reserved = requireNotNull(predecessor.claim.nextSuccessorProviderFileId) {
            "The predecessor reserved no successor slot"
        }
        require(successor.claim.lineageId == predecessor.claim.lineageId) {
            "Ownership successor belongs to another lineage"
        }
        require(
            successor.claim.writerEpoch ==
                addExact(predecessor.claim.writerEpoch, 1, "Ownership epoch"),
        ) {
            "Ownership successor does not increment the writer epoch by one"
        }
        require(successor.claim.providerFileId == reserved) {
            "Ownership successor does not occupy the reserved provider slot"
        }
        require(successor.claim.predecessorReservedSuccessorProviderFileId == reserved) {
            "Ownership successor names another reserved provider slot"
        }
        require(successor.claim.predecessorProviderFileId == predecessor.claim.providerFileId) {
            "Ownership successor names another predecessor provider file"
        }
        require(successor.claim.predecessorClaimId == predecessor.claim.claimId) {
            "Ownership successor names another predecessor claim"
        }
        require(successor.claim.predecessorClaimSha256 == predecessor.completeSha256.value) {
            "Ownership successor does not digest its predecessor"
        }
        require(successor.claim.claimId != predecessor.claim.claimId) {
            "Ownership successor repeats the predecessor claim identity"
        }
        return successor
    }

    private fun decrypt(
        frame: ByteArray,
        contentKey: VaultKey,
        expected: CloudHeaderIdentity,
    ): DecryptedCloudObject {
        val result = authenticatedCodec.decrypt(
            source = ByteArrayInputStream(frame),
            totalLength = frame.size.toLong(),
            key = contentKey,
        )
        require(result is CloudDecodeResult.Success) {
            "Ownership claim frame authentication failed"
        }
        return result.value.also { decrypted ->
            if (decrypted.identity != expected) {
                decrypted.close()
                throw IllegalArgumentException("Ownership claim frame identity mismatch")
            }
        }
    }

    private fun headerFor(
        claim: OwnershipClaimV1,
        frame: ByteArray,
    ): OwnershipPublicHeaderV1 = headerFor(claim, frame.size.toLong(), sha256(frame))

    private fun headerFor(
        claim: OwnershipClaimV1,
        frameLength: Long,
        frameSha256: String,
    ): OwnershipPublicHeaderV1 = OwnershipPublicHeaderV1(
        lineageId = claim.lineageId,
        claimId = claim.claimId,
        writerEpoch = claim.writerEpoch,
        state = claim.state,
        role = roleFor(claim),
        providerFileId = claim.providerFileId,
        nextSuccessorProviderFileId = claim.nextSuccessorProviderFileId,
        encryptedFrameLength = frameLength,
        encryptedFrameSha256 = frameSha256,
    )

    private fun roleFor(claim: OwnershipClaimV1): RemoteObjectRoleV1 = when {
        claim.state == OwnershipStateV1.TERMINATED -> RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE
        claim.predecessorProviderFileId == null -> RemoteObjectRoleV1.OWNERSHIP_ROOT
        else -> RemoteObjectRoleV1.OWNERSHIP_CLAIM
    }

    private fun validateHeader(header: OwnershipPublicHeaderV1) {
        require(header.magic == MAGIC) { "Unsupported ownership magic" }
        require(header.formatVersion == FORMAT_VERSION) {
            "Unsupported ownership format version ${header.formatVersion}"
        }
        require(header.minimumReaderVersion == MINIMUM_READER_VERSION) {
            "Unsupported ownership minimum reader version ${header.minimumReaderVersion}"
        }
        CloudLineageId.parse(header.lineageId)
        OwnershipClaimId.parse(header.claimId)
        WriterEpoch(header.writerEpoch)
        ProviderObjectId.of(header.providerFileId)
        header.nextSuccessorProviderFileId?.let(ProviderObjectId::of)
        require(header.encryptedFrameLength in 1..MAX_CLAIM_FRAME_BYTES) {
            "Ownership frame length is outside its bound"
        }
        Sha256Digest.of(header.encryptedFrameSha256)
        when (header.role) {
            RemoteObjectRoleV1.OWNERSHIP_ROOT, RemoteObjectRoleV1.OWNERSHIP_CLAIM ->
                require(header.state == OwnershipStateV1.ACTIVE) {
                    "An active ownership role requires the active state"
                }

            RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE ->
                require(header.state == OwnershipStateV1.TERMINATED) {
                    "The tombstone role requires the terminated state"
                }

            else -> throw IllegalArgumentException("Unsupported ownership role")
        }
    }

    private fun validateClaim(claim: OwnershipClaimV1) {
        require(claim.formatVersion == FORMAT_VERSION) {
            "Unsupported ownership claim version ${claim.formatVersion}"
        }
        require(claim.minimumReaderVersion == MINIMUM_READER_VERSION) {
            "Unsupported ownership claim minimum reader ${claim.minimumReaderVersion}"
        }
        CloudLineageId.parse(claim.lineageId)
        OwnershipClaimId.parse(claim.claimId)
        WriterEpoch(claim.writerEpoch)
        ProviderObjectId.of(claim.providerFileId)
        ProviderObjectId.of(claim.creationOperationId)

        val role = roleFor(claim)
        if (role == RemoteObjectRoleV1.OWNERSHIP_ROOT) {
            require(claim.writerEpoch == FIRST_WRITER_EPOCH) {
                "An ownership root must own the first writer epoch"
            }
            require(
                claim.predecessorProviderFileId == null &&
                    claim.predecessorClaimId == null &&
                    claim.predecessorClaimSha256 == null &&
                    claim.predecessorReservedSuccessorProviderFileId == null,
            ) {
                "An ownership root carries no predecessor binding"
            }
        } else {
            require(claim.writerEpoch > FIRST_WRITER_EPOCH) {
                "An ownership successor must follow the first writer epoch"
            }
            val predecessorProviderFileId = requireNotNull(claim.predecessorProviderFileId) {
                "An ownership successor names its predecessor provider file"
            }
            ProviderObjectId.of(predecessorProviderFileId)
            OwnershipClaimId.parse(
                requireNotNull(claim.predecessorClaimId) {
                    "An ownership successor names its predecessor claim"
                },
            )
            Sha256Digest.of(
                requireNotNull(claim.predecessorClaimSha256) {
                    "An ownership successor digests its predecessor claim"
                },
            )
            val reserved = requireNotNull(claim.predecessorReservedSuccessorProviderFileId) {
                "An ownership successor names its reserved provider slot"
            }
            require(reserved == claim.providerFileId) {
                "An ownership successor must occupy its own reserved provider slot"
            }
            require(predecessorProviderFileId != claim.providerFileId) {
                "An ownership successor cannot occupy its predecessor provider file"
            }
        }

        when (claim.state) {
            OwnershipStateV1.ACTIVE -> validateActiveFields(claim)
            OwnershipStateV1.TERMINATED -> validateTerminalFields(claim)
        }
    }

    private fun validateActiveFields(claim: OwnershipClaimV1) {
        ProviderObjectId.of(
            requireNotNull(claim.sourceVaultId) { "An active claim names its source vault" },
        )
        CloudDeviceId.parse(
            requireNotNull(claim.activeDeviceId) { "An active claim names its active device" },
        )
        val successor = requireNotNull(claim.nextSuccessorProviderFileId) {
            "An active claim reserves exactly one successor provider slot"
        }
        ProviderObjectId.of(successor)
        require(successor != claim.providerFileId) {
            "An active claim cannot reserve its own provider file"
        }
        require(successor != claim.predecessorProviderFileId) {
            "An active claim cannot reserve its predecessor provider file"
        }
        ProviderObjectId.of(
            requireNotNull(claim.baselinePublicationProviderFileId) {
                "An active claim binds its epoch baseline publication file"
            },
        )
        PublicationId.parse(
            requireNotNull(claim.baselinePublicationId) {
                "An active claim binds its epoch baseline publication identity"
            },
        )
        Sha256Digest.of(
            requireNotNull(claim.baselinePublicationSha256) {
                "An active claim digests its epoch baseline publication"
            },
        )
        val recoveryCredentialGeneration = requireNotNull(claim.recoveryCredentialGeneration) {
            "An active claim names its recovery credential generation"
        }
        require(recoveryCredentialGeneration >= 0) {
            "Recovery credential generation is negative"
        }
        require(claim.tombstoneId == null) { "An active claim carries no tombstone identity" }
    }

    private fun validateTerminalFields(claim: OwnershipClaimV1) {
        require(
            claim.sourceVaultId == null &&
                claim.activeDeviceId == null &&
                claim.nextSuccessorProviderFileId == null &&
                claim.baselinePublicationProviderFileId == null &&
                claim.baselinePublicationId == null &&
                claim.baselinePublicationSha256 == null &&
                claim.recoveryCredentialGeneration == null,
        ) {
            "A terminal claim carries no active ownership or recovery state"
        }
        OwnershipClaimId.parse(
            requireNotNull(claim.tombstoneId) {
                "A terminal claim carries a tombstone identity"
            },
        )
    }

    private fun decodeCanonicalHeader(source: ByteArray): OwnershipPublicHeaderV1 {
        val header = try {
            StrictOwnershipJson.json.decodeFromString(
                OwnershipPublicHeaderV1.serializer(),
                strictUtf8(source, "Ownership public header"),
            )
        } catch (failure: SerializationException) {
            throw IllegalArgumentException("Invalid ownership public header", failure)
        }
        val canonical = canonicalHeaderBytes(header)
        try {
            require(source.contentEquals(canonical)) {
                "Ownership public header is not canonical"
            }
        } finally {
            canonical.fill(0)
        }
        return header
    }

    private fun decodeCanonicalClaim(source: ByteArray): OwnershipClaimV1 {
        require(source.isNotEmpty()) { "Ownership claim is empty" }
        require(source.size <= MAX_CLAIM_PLAINTEXT_BYTES) {
            "Ownership claim exceeds $MAX_CLAIM_PLAINTEXT_BYTES bytes"
        }
        val claim = try {
            StrictOwnershipJson.json.decodeFromString(
                OwnershipClaimV1.serializer(),
                strictUtf8(source, "Ownership claim"),
            )
        } catch (failure: SerializationException) {
            throw IllegalArgumentException("Invalid ownership claim", failure)
        }
        val canonical = canonicalClaimBytes(claim)
        try {
            require(source.contentEquals(canonical)) { "Ownership claim is not canonical" }
        } finally {
            canonical.fill(0)
        }
        return claim
    }

    private fun canonicalHeaderBytes(header: OwnershipPublicHeaderV1): ByteArray =
        StrictOwnershipJson.json
            .encodeToString(OwnershipPublicHeaderV1.serializer(), header)
            .toByteArray(Charsets.UTF_8)
            .also {
                require(it.size <= MAX_PUBLIC_HEADER_BYTES) {
                    "Ownership public header exceeds $MAX_PUBLIC_HEADER_BYTES bytes"
                }
            }

    private fun canonicalClaimBytes(claim: OwnershipClaimV1): ByteArray =
        StrictOwnershipJson.json
            .encodeToString(OwnershipClaimV1.serializer(), claim)
            .toByteArray(Charsets.UTF_8)
            .also {
                require(it.size <= MAX_CLAIM_PLAINTEXT_BYTES) {
                    "Ownership claim exceeds $MAX_CLAIM_PLAINTEXT_BYTES bytes"
                }
            }

    private fun checkedFileLength(
        headerLength: Int,
        frameLength: Long,
    ): Long {
        require(headerLength in 1..MAX_PUBLIC_HEADER_BYTES) {
            "Ownership public header length is outside its bound"
        }
        require(frameLength in 1..MAX_CLAIM_FRAME_BYTES) {
            "Ownership frame length is outside its bound"
        }
        val total = addExact(
            addExact(LENGTH_PREFIX_BYTES.toLong(), headerLength.toLong(), "Ownership file"),
            frameLength,
            "Ownership file",
        )
        require(total <= Int.MAX_VALUE) { "Ownership claim file cannot be allocated" }
        return total
    }

    private fun identity(
        lineageId: String,
        claimId: String,
    ): CloudHeaderIdentity = CloudHeaderIdentity(
        family = CloudObjectFamily.MANIFEST,
        schemaVersion = FORMAT_VERSION,
        cryptoVersion = FORMAT_VERSION,
        minimumReaderVersion = MINIMUM_READER_VERSION,
        vaultId = lineageId,
        objectId = claimId,
    )

    companion object {
        const val MAX_PUBLIC_HEADER_BYTES: Int = CloudBounds.MAX_HEADER_BYTES
        val MAX_CLAIM_PLAINTEXT_BYTES: Int = (
            CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES -
                CloudBounds.AES_GCM_V1_CIPHERTEXT_OVERHEAD_BYTES
            ).toInt()
        val MAX_CLAIM_FRAME_BYTES: Long = LENGTH_PREFIX_BYTES.toLong() +
            CloudBounds.MAX_HEADER_BYTES + CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES
    }
}

@OptIn(ExperimentalSerializationApi::class)
private object StrictOwnershipJson {
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
private const val FIRST_WRITER_EPOCH = 1L
private const val FORMAT_VERSION = 1
private const val MINIMUM_READER_VERSION = 1
private const val MAGIC = "OPEN_TASKS_OWNERSHIP"
private const val MINIMUM_FILE_BYTES = LENGTH_PREFIX_BYTES + 2
private const val HEX_ALPHABET = "0123456789abcdef"

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
    throw IllegalArgumentException("$label length overflows", failure)
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
