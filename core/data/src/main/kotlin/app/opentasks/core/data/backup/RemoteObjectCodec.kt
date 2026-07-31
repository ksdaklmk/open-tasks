package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.domain.OwnedRemoteFile
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.sync.CloudBounds
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * A Stage 2 object re-encrypted under a remote lineage identity.
 *
 * The staged file is private to the caller, who owns it from the moment it is
 * returned: closing removes it whether or not it was uploaded.
 */
class ReauthenticatedRemoteObject internal constructor(
    val logicalObjectId: RemoteLogicalObjectId,
    val role: RemoteObjectRoleV1,
    val firstGeneration: BackupGeneration,
    val lastGeneration: BackupGeneration,
    val frameLength: Long,
    val frameSha256: Sha256Digest,
    /**
     * Digest of the canonical plaintext this frame encrypts. Two copies of one
     * generation share it even though their nonces, ciphertext, and frame
     * digests differ, so a copy read back later can be compared by content.
     */
    val payloadSha256: Sha256Digest,
    backing: File,
) : OwnedRemoteFile {
    private var staged: File? = backing

    override val file: File
        get() = checkNotNull(staged) { "The reauthenticated remote object was already closed" }

    override val length: Long get() = frameLength

    override fun close() {
        val current = staged ?: return
        staged = null
        current.delete()
    }

    override fun toString(): String = "ReauthenticatedRemoteObject(role=$role)"
}

/**
 * What a downloaded complete base proved about itself: the generation it
 * covers and a digest of its canonical decoded payload.
 */
data class VerifiedRemoteBase(
    val coveredGeneration: Long,
    val payloadSha256: Sha256Digest,
)

/**
 * Verifies one Stage 2 local object and re-authenticates it for a remote
 * lineage.
 *
 * The local frame is authenticated first, its decoded payload is checked
 * against the local object identity it claims to be, and only then is the
 * canonical plaintext re-encoded and encrypted under the *explicit* new
 * [RemoteLogicalObjectId]. Because the remote identity is supplied rather than
 * derived, two copies of one generation never share identity, associated data,
 * nonce, or ciphertext, and each is independently authenticated on recovery.
 *
 * Nothing is streamed to the provider from Stage 2 storage: the result is a
 * fresh private file that is synced before it is returned and deleted if any
 * step fails.
 */
class RemoteObjectCodec(
    private val authenticatedCodec: AuthenticatedCloudObjectCodec,
    private val localObjectStore: LocalBackupObjectStore,
    private val stagingRoot: File,
    private val snapshotCodec: BackupSnapshotCodec = BackupSnapshotCodec,
    private val segmentCodec: BackupOperationSegmentCodec = BackupOperationSegmentCodec,
) {
    fun reauthenticateLocalObject(
        localObjectId: String,
        vaultId: VaultId,
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
        contentKey: VaultKey,
    ): ReauthenticatedRemoteObject {
        val descriptor = LocalObjectDescriptor.parse(localObjectId)
        val plaintext = readCanonicalPlaintext(descriptor, vaultId, contentKey)
        var frame: ByteArray? = null
        var staged: File? = null
        try {
            val payloadSha256 = Sha256Digest.of(sha256Hex(plaintext))
            frame = authenticatedCodec.encrypt(
                CloudHeaderIdentity(
                    family = descriptor.family,
                    schemaVersion = FORMAT_VERSION,
                    cryptoVersion = FORMAT_VERSION,
                    minimumReaderVersion = MINIMUM_READER_VERSION,
                    vaultId = lineageId.value,
                    objectId = logicalObjectId.value,
                ),
                plaintext,
                contentKey,
            )
            require(frame.size <= descriptor.maximumFrameBytes) {
                "Reauthenticated remote object exceeds its family frame bound"
            }
            stagingRoot.mkdirs()
            staged = File.createTempFile("remote-", ".otr", stagingRoot)
            FileOutputStream(staged).use { output ->
                output.write(frame)
                output.fd.sync()
            }
            return ReauthenticatedRemoteObject(
                logicalObjectId = logicalObjectId,
                role = descriptor.role,
                firstGeneration = BackupGeneration(descriptor.firstGeneration),
                lastGeneration = BackupGeneration(descriptor.lastGeneration),
                frameLength = frame.size.toLong(),
                frameSha256 = Sha256Digest.of(sha256Hex(frame)),
                payloadSha256 = payloadSha256,
                backing = staged,
            )
        } catch (failure: Throwable) {
            staged?.delete()
            throw failure
        } finally {
            frame?.fill(0)
            plaintext.fill(0)
        }
    }

    /**
     * Authenticates a complete base downloaded back from the provider.
     *
     * The frame must decrypt under the content key at exactly the lineage plus
     * the remote logical object it was created for, and its payload must decode
     * strictly, so a copy that was swapped, truncated, or re-identified is
     * rejected before it can count as one of the two verified bases. The
     * returned payload digest lets two independently identified copies of one
     * generation be compared for equality without holding either plaintext.
     */
    fun readRemoteBase(
        frame: OwnedRemoteFile,
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
        contentKey: VaultKey,
    ): VerifiedRemoteBase {
        val decoded = frame.file.inputStream().use {
            authenticatedCodec.decrypt(it, frame.file.length(), contentKey)
        }
        val success = decoded as? CloudDecodeResult.Success
            ?: throw IllegalArgumentException("Remote base authentication failed")
        return success.value.use { value ->
            require(
                value.identity == CloudHeaderIdentity(
                    family = CloudObjectFamily.SNAPSHOT,
                    schemaVersion = FORMAT_VERSION,
                    cryptoVersion = FORMAT_VERSION,
                    minimumReaderVersion = MINIMUM_READER_VERSION,
                    vaultId = lineageId.value,
                    objectId = logicalObjectId.value,
                ),
            ) {
                "Remote base identity mismatch"
            }
            val payload = snapshotCodec.decodeOwned(value.takePlaintext())
            val canonical = snapshotCodec.encode(payload)
            try {
                VerifiedRemoteBase(
                    coveredGeneration = payload.coveredGeneration,
                    payloadSha256 = Sha256Digest.of(sha256Hex(canonical)),
                )
            } finally {
                canonical.fill(0)
            }
        }
    }

    /**
     * Authenticates the local Stage 2 frame, strictly decodes its payload,
     * checks the payload against the local identity it claims, and returns the
     * canonical plaintext the remote copy must carry.
     */
    private fun readCanonicalPlaintext(
        descriptor: LocalObjectDescriptor,
        vaultId: VaultId,
        contentKey: VaultKey,
    ): ByteArray {
        val length = localObjectStore.length(descriptor.objectId)
        val decoded = localObjectStore.open(descriptor.objectId).use {
            authenticatedCodec.decrypt(it, length, contentKey)
        }
        val success = decoded as? CloudDecodeResult.Success
            ?: throw IllegalArgumentException("Local backup object authentication failed")
        return success.value.use { value ->
            require(
                value.identity == CloudHeaderIdentity(
                    family = descriptor.family,
                    schemaVersion = FORMAT_VERSION,
                    cryptoVersion = FORMAT_VERSION,
                    minimumReaderVersion = MINIMUM_READER_VERSION,
                    vaultId = vaultId.value,
                    objectId = descriptor.objectId,
                ),
            ) {
                "Local backup object identity mismatch"
            }
            when (descriptor.role) {
                RemoteObjectRoleV1.SNAPSHOT -> {
                    val payload = snapshotCodec.decodeOwned(value.takePlaintext())
                    require(payload.vaultId == vaultId.value) {
                        "Local backup snapshot belongs to another vault"
                    }
                    require(payload.coveredGeneration == descriptor.firstGeneration) {
                        "Local backup snapshot covers another generation"
                    }
                    snapshotCodec.encode(payload)
                }

                else -> {
                    val payload = segmentCodec.decodeOwned(value.takePlaintext())
                    require(payload.vaultId == vaultId.value) {
                        "Local backup segment belongs to another vault"
                    }
                    require(
                        payload.firstGeneration == descriptor.firstGeneration &&
                            payload.lastGeneration == descriptor.lastGeneration,
                    ) {
                        "Local backup segment covers another generation range"
                    }
                    segmentCodec.encode(payload)
                }
            }
        }
    }
}

private data class LocalObjectDescriptor(
    val objectId: String,
    val role: RemoteObjectRoleV1,
    val family: CloudObjectFamily,
    val firstGeneration: Long,
    val lastGeneration: Long,
) {
    val maximumFrameBytes: Long
        get() = when (role) {
            RemoteObjectRoleV1.SNAPSHOT ->
                LENGTH_PREFIX + CloudBounds.MAX_HEADER_BYTES +
                    CloudBounds.MAX_SNAPSHOT_CIPHERTEXT_BYTES

            else ->
                LENGTH_PREFIX + CloudBounds.MAX_HEADER_BYTES +
                    CloudBounds.MAX_OPERATION_SEGMENT_CIPHERTEXT_BYTES
        }

    companion object {
        private const val LENGTH_PREFIX = 4L
        private val SNAPSHOT_ID = Regex("snapshot:([0-9]+)")
        private val SEGMENT_ID = Regex("segment:([0-9]+):([0-9]+)")

        fun parse(objectId: String): LocalObjectDescriptor {
            SNAPSHOT_ID.matchEntire(objectId)?.let { match ->
                val generation = match.groupValues[1].toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid local backup object ID")
                return LocalObjectDescriptor(
                    objectId = objectId,
                    role = RemoteObjectRoleV1.SNAPSHOT,
                    family = CloudObjectFamily.SNAPSHOT,
                    firstGeneration = generation,
                    lastGeneration = generation,
                )
            }
            SEGMENT_ID.matchEntire(objectId)?.let { match ->
                val first = match.groupValues[1].toLongOrNull()
                val last = match.groupValues[2].toLongOrNull()
                if (first == null || last == null || first > last) {
                    throw IllegalArgumentException("Invalid local backup object ID")
                }
                return LocalObjectDescriptor(
                    objectId = objectId,
                    role = RemoteObjectRoleV1.SEGMENT,
                    family = CloudObjectFamily.OPERATION_SEGMENT,
                    firstGeneration = first,
                    lastGeneration = last,
                )
            }
            throw IllegalArgumentException("Invalid local backup object ID")
        }
    }
}

private const val FORMAT_VERSION = 1
private const val MINIMUM_READER_VERSION = 1
private const val REMOTE_HEX_ALPHABET = "0123456789abcdef"

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return try {
        buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(REMOTE_HEX_ALPHABET[value ushr 4])
                append(REMOTE_HEX_ALPHABET[value and 0x0f])
            }
        }
    } finally {
        digest.fill(0)
    }
}
