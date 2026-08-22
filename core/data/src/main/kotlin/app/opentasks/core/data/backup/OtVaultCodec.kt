package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeFailure
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.DecryptedCloudObject
import app.opentasks.core.domain.AttachmentBlobSetManifest
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.VaultId
import app.opentasks.core.sync.CloudBounds
import app.opentasks.core.sync.CloudFormatException
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The public prefix of one `.otvault` archive.
 *
 * [envelope] is a recovery envelope in the frozen Stage 1 sense: the export
 * passphrase wraps the archive's content key, so importing an archive makes
 * that passphrase the imported vault's recovery passphrase. Nothing here is
 * secret, and nothing here is authenticated on its own — the inventory written
 * last carries the digest of these exact bytes, so a reader that verifies the
 * inventory has verified the header.
 */
data class OtVaultHeaderV1(
    val formatVersion: Int,
    val vaultId: VaultId,
    val createdAtEpochMillis: Long,
    val envelope: VaultKeyEnvelope,
    val recordCount: Int,
    val attachmentCount: Int,
)

/** One archive object as the inventory declares it: exact frame bytes and digest. */
@Serializable
data class OtVaultInventoryEntryV1(
    val objectId: String,
    val family: String,
    val sha256: String,
    val byteCount: Long,
)

/**
 * One authenticated archive object, delivered in the order the archive holds.
 *
 * [AttachmentChunk.plaintext] is owned by the codec and cleared as soon as the
 * callback returns; a consumer that needs the bytes afterwards must copy them.
 */
sealed interface OtVaultReadEvent {
    data class Snapshot(val payload: BackupSnapshotPayloadV1) : OtVaultReadEvent

    data class Segment(val payload: BackupOperationSegmentPayloadV1) : OtVaultReadEvent

    data class AttachmentManifest(val manifest: AttachmentBlobSetManifest) : OtVaultReadEvent

    data class AttachmentChunk(
        val blobSetId: BlobSetId,
        val chunkIndex: Int,
        val plaintext: ByteArray,
    ) : OtVaultReadEvent
}

/**
 * The archive is not a readable `.otvault` v1 archive.
 *
 * Messages describe the structural failure only. They never carry archive
 * content, identifiers a user chose, key material, or derivation metadata.
 */
class OtVaultFormatException(message: String) : Exception(message)

internal class ArchiveByteBudget(initialBytes: Long) {
    var byteCount: Long = initialBytes
        private set

    init {
        if (initialBytes !in 0..OtVaultCodec.MAX_ARCHIVE_BYTES) {
            throw OtVaultFormatException("Vault archive exceeds its aggregate bound")
        }
    }

    fun reserve(byteCount: Long) {
        if (byteCount < 0 || byteCount > OtVaultCodec.MAX_ARCHIVE_BYTES - this.byteCount) {
            throw OtVaultFormatException("Vault archive exceeds its aggregate bound")
        }
        this.byteCount += byteCount
    }
}

/**
 * Reads and writes the frozen `.otvault` v1 archive format.
 *
 * An archive is a public header followed by length-prefixed
 * [app.opentasks.core.sync.CloudObjectFormat] frames and closed by one
 * inventory frame:
 *
 * ```
 * "OPEN_TASKS_VAULT" u32(formatVersion) u32(headerLength) headerJson
 * (u32(frameLength) frame)*                 snapshot, segments, manifests, chunks
 * u32(frameLength) frame                    the inventory, always last
 * ```
 *
 * Every frame is encrypted under the content key at an archive-scoped object
 * ID, so no archive object can be replayed into the Stage 3 remote namespace
 * and no remote object can be replayed into an archive. Nothing is buffered
 * beyond the single frame in flight, and the whole archive is capped at
 * [MAX_ARCHIVE_BYTES] in addition to the frozen per-frame bounds.
 *
 * [readAll] authenticates every frame before it is delivered, verifies the
 * inventory last — object count, per-object digest, and the header digest —
 * and throws [OtVaultFormatException] on any mismatch, truncation, unknown
 * family, or bound violation.
 */
class OtVaultCodec(
    private val codec: AuthenticatedCloudObjectCodec,
) {
    fun writeHeader(
        destination: OutputStream,
        header: OtVaultHeaderV1,
    ) {
        val bytes = canonicalHeaderBytes(header)
        try {
            destination.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Reads the public header, bounding every length before it is honoured.
     *
     * The magic, the format version, and the declared header length are each
     * checked before the byte after them is read, so an archive claiming an
     * oversized header or a newer format is refused without allocating for it.
     */
    fun readHeader(source: InputStream): OtVaultHeaderV1 {
        val magic = readExact(source, MAGIC_BYTES.size)
        try {
            if (!magic.contentEquals(MAGIC_BYTES)) {
                throw OtVaultFormatException("Unsupported vault archive magic")
            }
        } finally {
            magic.fill(0)
        }
        val version = readInt(source)
        if (version != FORMAT_VERSION) {
            throw OtVaultFormatException(
                "Unsupported vault archive format version $version",
            )
        }
        val length = readInt(source)
        if (length < 1 || length > MAX_HEADER_BYTES) {
            throw OtVaultFormatException("Vault archive header length is outside its bound")
        }
        val body = readExact(source, length)
        return try {
            decodeHeaderBody(body)
        } finally {
            body.fill(0)
        }
    }

    fun writeSnapshot(
        destination: OutputStream,
        key: VaultKey,
        header: OtVaultHeaderV1,
        payload: BackupSnapshotPayloadV1,
    ): OtVaultInventoryEntryV1 {
        requireArchiveVault(header, payload.vaultId)
        val plaintext = try {
            BackupSnapshotCodec.encode(payload)
        } catch (_: IllegalArgumentException) {
            throw OtVaultFormatException("Vault archive snapshot cannot be encoded")
        }
        return writeObject(
            destination = destination,
            key = key,
            identity = identity(
                header = header,
                family = CloudObjectFamily.SNAPSHOT,
                objectId = snapshotObjectId(payload.coveredGeneration),
            ),
            plaintext = plaintext,
        )
    }

    fun writeSegment(
        destination: OutputStream,
        key: VaultKey,
        header: OtVaultHeaderV1,
        payload: BackupOperationSegmentPayloadV1,
    ): OtVaultInventoryEntryV1 {
        requireArchiveVault(header, payload.vaultId)
        val plaintext = try {
            BackupOperationSegmentCodec.encode(payload)
        } catch (_: IllegalArgumentException) {
            throw OtVaultFormatException("Vault archive segment cannot be encoded")
        }
        return writeObject(
            destination = destination,
            key = key,
            identity = identity(
                header = header,
                family = CloudObjectFamily.OPERATION_SEGMENT,
                objectId = segmentObjectId(payload.firstGeneration, payload.lastGeneration),
            ),
            plaintext = plaintext,
        )
    }

    fun writeAttachmentManifest(
        destination: OutputStream,
        key: VaultKey,
        header: OtVaultHeaderV1,
        manifest: AttachmentBlobSetManifest,
    ): OtVaultInventoryEntryV1 {
        val plaintext = try {
            encodeAttachmentManifestPlaintext(manifest)
        } catch (_: IllegalArgumentException) {
            throw OtVaultFormatException("Vault archive attachment manifest cannot be encoded")
        }
        return writeObject(
            destination = destination,
            key = key,
            identity = identity(
                header = header,
                family = CloudObjectFamily.MANIFEST,
                objectId = attachmentManifestObjectId(manifest.blobSetId),
            ),
            plaintext = plaintext,
        )
    }

    /**
     * Writes one attachment chunk and clears [plaintext] before returning.
     *
     * The codec takes ownership of the buffer so no caller has to remember to
     * wipe attachment bytes; the buffer is cleared whether the write succeeds
     * or is refused.
     */
    fun writeAttachmentChunk(
        destination: OutputStream,
        key: VaultKey,
        header: OtVaultHeaderV1,
        blobSetId: BlobSetId,
        chunkIndex: Int,
        plaintext: ByteArray,
    ): OtVaultInventoryEntryV1 = try {
        validateBlobSetId(blobSetId)
        if (chunkIndex !in 0 until MAX_CHUNKS_PER_BLOB_SET) {
            throw OtVaultFormatException("Vault archive chunk index is outside its bound")
        }
        if (plaintext.isEmpty() ||
            plaintext.size > CloudBounds.MAX_ATTACHMENT_CHUNK_PLAINTEXT_BYTES
        ) {
            throw OtVaultFormatException("Vault archive chunk is outside its bound")
        }
        writeObject(
            destination = destination,
            key = key,
            identity = identity(
                header = header,
                family = CloudObjectFamily.ATTACHMENT_CHUNK,
                objectId = attachmentChunkObjectId(blobSetId, chunkIndex),
                chunkIndex = chunkIndex,
                chunkCount = MAX_CHUNKS_PER_BLOB_SET,
            ),
            plaintext = plaintext,
        )
    } finally {
        plaintext.fill(0)
    }

    /** Closes the archive with the inventory of everything written before it. */
    fun writeInventory(
        destination: OutputStream,
        key: VaultKey,
        header: OtVaultHeaderV1,
        entries: List<OtVaultInventoryEntryV1>,
    ) {
        validateInventoryEntries(entries)
        val payload = OtVaultInventoryPayloadV1(
            formatVersion = FORMAT_VERSION,
            vaultId = header.vaultId.value,
            headerSha256 = headerDigest(header),
            entryCount = entries.size,
            entries = entries,
        )
        writeObject(
            destination = destination,
            key = key,
            identity = identity(
                header = header,
                family = CloudObjectFamily.MANIFEST,
                objectId = INVENTORY_OBJECT_ID,
            ),
            plaintext = canonicalInventoryBytes(payload),
        )
    }

    /**
     * Authenticates and delivers every archive object, verifying last that the
     * inventory names exactly what was read and the header it was written for.
     *
     * [source] must be positioned where [readHeader] left it. Objects are
     * delivered in archive order; a corrupt object is refused before its event
     * is delivered.
     */
    fun readAll(
        source: InputStream,
        key: VaultKey,
        header: OtVaultHeaderV1,
        onEvent: (OtVaultReadEvent) -> Unit,
    ) {
        val budget = canonicalHeaderBytes(header).let { bytes ->
            try {
                ArchiveByteBudget(bytes.size.toLong())
            } finally {
                bytes.fill(0)
            }
        }
        val expectedHeaderDigest = headerDigest(header)
        val observed = mutableListOf<OtVaultInventoryEntryV1>()
        var snapshotRecords = 0L
        var attachmentManifests = 0L
        var inventoryRead = false
        while (!inventoryRead) {
            val frame = readFrame(source, budget)
                ?: throw OtVaultFormatException("Vault archive ends before its inventory")
            try {
                decrypt(frame, key).use { decrypted ->
                    val identity = decrypted.identity
                    if (identity.vaultId != header.vaultId.value) {
                        throw OtVaultFormatException("Vault archive object names another vault")
                    }
                    val plaintext = decrypted.takePlaintext()
                    try {
                        if (identity.family == CloudObjectFamily.MANIFEST &&
                            identity.objectId == INVENTORY_OBJECT_ID
                        ) {
                            verifyInventory(
                                plaintext = plaintext,
                                header = header,
                                expectedHeaderDigest = expectedHeaderDigest,
                                observed = observed,
                                snapshotRecords = snapshotRecords,
                                attachmentManifests = attachmentManifests,
                            )
                            inventoryRead = true
                        } else {
                            observed += OtVaultInventoryEntryV1(
                                objectId = identity.objectId,
                                family = identity.family.name,
                                sha256 = sha256(frame),
                                byteCount = frame.size.toLong(),
                            )
                            if (observed.size > CloudBounds.MAX_MANIFEST_INVENTORY_ENTRIES) {
                                throw OtVaultFormatException(
                                    "Vault archive inventory accumulation exceeds its bound",
                                )
                            }
                            when (val event = decodeObject(identity, plaintext)) {
                                is OtVaultReadEvent.Snapshot -> {
                                    snapshotRecords += event.payload.records.size
                                    onEvent(event)
                                }

                                is OtVaultReadEvent.AttachmentManifest -> {
                                    attachmentManifests += 1
                                    onEvent(event)
                                }

                                else -> onEvent(event)
                            }
                        }
                    } finally {
                        plaintext.fill(0)
                    }
                }
            } finally {
                frame.fill(0)
            }
        }
        if (source.read() != -1) {
            throw OtVaultFormatException("Vault archive contains trailing bytes")
        }
    }

    private fun decodeObject(
        identity: CloudHeaderIdentity,
        plaintext: ByteArray,
    ): OtVaultReadEvent = when (identity.family) {
        CloudObjectFamily.SNAPSHOT -> {
            val payload = try {
                BackupSnapshotCodec.decode(plaintext)
            } catch (_: IllegalArgumentException) {
                throw OtVaultFormatException("Vault archive snapshot is malformed")
            }
            requireObjectId(identity, snapshotObjectId(payload.coveredGeneration))
            requireArchiveVault(vaultId = identity.vaultId, declared = payload.vaultId)
            OtVaultReadEvent.Snapshot(payload)
        }

        CloudObjectFamily.OPERATION_SEGMENT -> {
            val payload = try {
                BackupOperationSegmentCodec.decode(plaintext)
            } catch (_: IllegalArgumentException) {
                throw OtVaultFormatException("Vault archive segment is malformed")
            }
            requireObjectId(
                identity,
                segmentObjectId(payload.firstGeneration, payload.lastGeneration),
            )
            requireArchiveVault(vaultId = identity.vaultId, declared = payload.vaultId)
            OtVaultReadEvent.Segment(payload)
        }

        CloudObjectFamily.MANIFEST -> {
            val manifest = try {
                decodeAttachmentManifestPlaintext(plaintext)
            } catch (_: IllegalArgumentException) {
                throw OtVaultFormatException("Vault archive attachment manifest is malformed")
            }
            requireObjectId(identity, attachmentManifestObjectId(manifest.blobSetId))
            OtVaultReadEvent.AttachmentManifest(manifest)
        }

        CloudObjectFamily.ATTACHMENT_CHUNK -> {
            val suffix = identity.objectId.removePrefix(ATTACHMENT_CHUNK_PREFIX)
            val separator = suffix.lastIndexOf(':')
            if (separator <= 0) {
                throw OtVaultFormatException(OUTSIDE_ARCHIVE_NAMESPACE)
            }
            val blobSetId = BlobSetId(suffix.substring(0, separator))
            val chunkIndex = suffix.substring(separator + 1).toIntOrNull()
                ?: throw OtVaultFormatException("Vault archive chunk index is malformed")
            requireObjectId(identity, attachmentChunkObjectId(blobSetId, chunkIndex))
            if (identity.chunkIndex != chunkIndex ||
                identity.chunkCount != MAX_CHUNKS_PER_BLOB_SET
            ) {
                throw OtVaultFormatException("Vault archive chunk identity is inconsistent")
            }
            if (plaintext.isEmpty() ||
                plaintext.size > CloudBounds.MAX_ATTACHMENT_CHUNK_PLAINTEXT_BYTES
            ) {
                throw OtVaultFormatException("Vault archive chunk is outside its bound")
            }
            OtVaultReadEvent.AttachmentChunk(blobSetId, chunkIndex, plaintext)
        }
    }

    private fun verifyInventory(
        plaintext: ByteArray,
        header: OtVaultHeaderV1,
        expectedHeaderDigest: String,
        observed: List<OtVaultInventoryEntryV1>,
        snapshotRecords: Long,
        attachmentManifests: Long,
    ) {
        val payload = decodeInventory(plaintext)
        if (payload.formatVersion != FORMAT_VERSION) {
            throw OtVaultFormatException("Unsupported vault archive inventory version")
        }
        if (payload.vaultId != header.vaultId.value) {
            throw OtVaultFormatException("Vault archive inventory names another vault")
        }
        if (payload.headerSha256 != expectedHeaderDigest) {
            throw OtVaultFormatException("Vault archive header does not match its inventory")
        }
        if (payload.entryCount != payload.entries.size ||
            payload.entries != observed
        ) {
            throw OtVaultFormatException("Vault archive inventory does not match its objects")
        }
        if (snapshotRecords != header.recordCount.toLong() ||
            attachmentManifests != header.attachmentCount.toLong()
        ) {
            throw OtVaultFormatException("Vault archive counts do not match its header")
        }
    }

    private fun writeObject(
        destination: OutputStream,
        key: VaultKey,
        identity: CloudHeaderIdentity,
        plaintext: ByteArray,
    ): OtVaultInventoryEntryV1 {
        val frame = try {
            codec.encrypt(identity, plaintext, key)
        } catch (_: CloudFormatException) {
            throw OtVaultFormatException("Vault archive object cannot be encoded")
        } finally {
            plaintext.fill(0)
        }
        try {
            if (frame.isEmpty() || frame.size > MAX_FRAME_BYTES) {
                throw OtVaultFormatException("Vault archive object is outside its bound")
            }
            val prefix = intBytes(frame.size)
            destination.write(prefix)
            destination.write(frame)
            return OtVaultInventoryEntryV1(
                objectId = identity.objectId,
                family = identity.family.name,
                sha256 = sha256(frame),
                byteCount = frame.size.toLong(),
            )
        } finally {
            frame.fill(0)
        }
    }

    private fun decrypt(frame: ByteArray, key: VaultKey): DecryptedCloudObject {
        val result = codec.decrypt(
            source = frame.inputStream(),
            totalLength = frame.size.toLong(),
            key = key,
        )
        return when (result) {
            is CloudDecodeResult.Success -> result.value
            is CloudDecodeResult.Failure -> throw OtVaultFormatException(
                when (result.reason) {
                    CloudDecodeFailure.AUTHENTICATION_FAILED ->
                        "Vault archive object failed authentication"

                    CloudDecodeFailure.LIMIT_EXCEEDED ->
                        "Vault archive object is outside its bound"

                    CloudDecodeFailure.TRUNCATED -> "Vault archive is truncated"
                    else -> "Vault archive object is malformed"
                },
            )
        }
    }

    internal fun readFrame(
        source: InputStream,
        budget: ArchiveByteBudget,
    ): ByteArray? {
        val first = source.read()
        if (first < 0) return null
        val prefix = ByteArray(LENGTH_PREFIX_BYTES)
        prefix[0] = first.toByte()
        readExactInto(source, prefix, 1)
        val length = ByteBuffer.wrap(prefix).int
        if (length < 1 || length > MAX_FRAME_BYTES) {
            throw OtVaultFormatException("Vault archive object length is outside its bound")
        }
        budget.reserve(LENGTH_PREFIX_BYTES.toLong() + length)
        return readExact(source, length)
    }

    private fun decodeHeaderBody(body: ByteArray): OtVaultHeaderV1 {
        val payload = try {
            StrictOtVaultJson.json.decodeFromString(
                OtVaultHeaderPayloadV1.serializer(),
                strictUtf8(body),
            )
        } catch (_: SerializationException) {
            throw OtVaultFormatException("Vault archive header is malformed")
        } catch (_: IllegalArgumentException) {
            throw OtVaultFormatException("Vault archive header is malformed")
        }
        if (payload.formatVersion != FORMAT_VERSION) {
            throw OtVaultFormatException(
                "Unsupported vault archive format version ${payload.formatVersion}",
            )
        }
        val envelope = try {
            RecoveryEnvelopeCodec.fromPayload(payload.recoveryEnvelope)
        } catch (_: IllegalArgumentException) {
            throw OtVaultFormatException("Vault archive header envelope is malformed")
        }
        val header = OtVaultHeaderV1(
            formatVersion = payload.formatVersion,
            vaultId = VaultId(payload.vaultId),
            createdAtEpochMillis = payload.createdAtEpochMillis,
            envelope = envelope,
            recordCount = payload.recordCount,
            attachmentCount = payload.attachmentCount,
        )
        try {
            val canonical = canonicalHeaderBody(header)
            try {
                if (!body.contentEquals(canonical)) {
                    throw OtVaultFormatException("Vault archive header is not canonical")
                }
            } finally {
                canonical.fill(0)
            }
            return header
        } catch (failure: Throwable) {
            envelope.clear()
            throw failure
        }
    }

    private fun headerDigest(header: OtVaultHeaderV1): String {
        val bytes = canonicalHeaderBytes(header)
        return try {
            sha256(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun canonicalHeaderBytes(header: OtVaultHeaderV1): ByteArray {
        val body = canonicalHeaderBody(header)
        return try {
            ByteArray(MAGIC_BYTES.size + 2 * LENGTH_PREFIX_BYTES + body.size).also { block ->
                MAGIC_BYTES.copyInto(block)
                ByteBuffer.wrap(block, MAGIC_BYTES.size, 2 * LENGTH_PREFIX_BYTES)
                    .putInt(FORMAT_VERSION)
                    .putInt(body.size)
                body.copyInto(block, MAGIC_BYTES.size + 2 * LENGTH_PREFIX_BYTES)
            }
        } finally {
            body.fill(0)
        }
    }

    private fun canonicalHeaderBody(header: OtVaultHeaderV1): ByteArray {
        validateHeader(header)
        val envelope = try {
            RecoveryEnvelopeCodec.toPayload(header.envelope)
        } catch (_: IllegalArgumentException) {
            throw OtVaultFormatException("Vault archive header envelope is unsupported")
        }
        val encoded = StrictOtVaultJson.json
            .encodeToString(
                OtVaultHeaderPayloadV1.serializer(),
                OtVaultHeaderPayloadV1(
                    formatVersion = header.formatVersion,
                    vaultId = header.vaultId.value,
                    createdAtEpochMillis = header.createdAtEpochMillis,
                    recoveryEnvelope = envelope,
                    recordCount = header.recordCount,
                    attachmentCount = header.attachmentCount,
                ),
            )
            .toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_HEADER_BYTES) {
            encoded.fill(0)
            throw OtVaultFormatException("Vault archive header exceeds its bound")
        }
        return encoded
    }

    private fun validateHeader(header: OtVaultHeaderV1) {
        if (header.formatVersion != FORMAT_VERSION) {
            throw OtVaultFormatException(
                "Unsupported vault archive format version ${header.formatVersion}",
            )
        }
        validateIdentifier(header.vaultId.value, "Vault archive vault")
        if (header.createdAtEpochMillis < 0) {
            throw OtVaultFormatException("Vault archive creation time is negative")
        }
        if (header.recordCount < 0 || header.recordCount > CloudBounds.MAX_RECORDS_PER_SNAPSHOT) {
            throw OtVaultFormatException("Vault archive record count is outside its bound")
        }
        if (header.attachmentCount < 0 ||
            header.attachmentCount > CloudBounds.MAX_MANIFEST_INVENTORY_ENTRIES
        ) {
            throw OtVaultFormatException("Vault archive attachment count is outside its bound")
        }
    }

    private fun decodeInventory(plaintext: ByteArray): OtVaultInventoryPayloadV1 {
        if (plaintext.isEmpty() || plaintext.size > MAX_INVENTORY_PLAINTEXT_BYTES) {
            throw OtVaultFormatException("Vault archive inventory is outside its bound")
        }
        val payload = try {
            StrictOtVaultJson.json.decodeFromString(
                OtVaultInventoryPayloadV1.serializer(),
                strictUtf8(plaintext),
            )
        } catch (_: SerializationException) {
            throw OtVaultFormatException("Vault archive inventory is malformed")
        } catch (_: IllegalArgumentException) {
            throw OtVaultFormatException("Vault archive inventory is malformed")
        }
        validateInventoryEntries(payload.entries)
        val canonical = canonicalInventoryBytes(payload)
        try {
            if (!plaintext.contentEquals(canonical)) {
                throw OtVaultFormatException("Vault archive inventory is not canonical")
            }
        } finally {
            canonical.fill(0)
        }
        return payload
    }

    private fun canonicalInventoryBytes(payload: OtVaultInventoryPayloadV1): ByteArray {
        val encoded = StrictOtVaultJson.json
            .encodeToString(OtVaultInventoryPayloadV1.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_INVENTORY_PLAINTEXT_BYTES) {
            encoded.fill(0)
            throw OtVaultFormatException("Vault archive inventory exceeds its bound")
        }
        return encoded
    }

    private fun validateInventoryEntries(entries: List<OtVaultInventoryEntryV1>) {
        if (entries.isEmpty() || entries.size > CloudBounds.MAX_MANIFEST_INVENTORY_ENTRIES) {
            throw OtVaultFormatException("Vault archive inventory size is outside its bound")
        }
        val objectIds = mutableSetOf<String>()
        entries.forEach { entry ->
            validateIdentifier(entry.objectId, "Vault archive object")
            if (CloudObjectFamily.entries.none { it.name == entry.family }) {
                throw OtVaultFormatException("Vault archive inventory names an unknown family")
            }
            if (!LOWERCASE_SHA256.matches(entry.sha256)) {
                throw OtVaultFormatException("Vault archive inventory digest is malformed")
            }
            if (entry.byteCount < 1 || entry.byteCount > MAX_FRAME_BYTES) {
                throw OtVaultFormatException("Vault archive inventory length is outside its bound")
            }
            if (!objectIds.add(entry.objectId)) {
                throw OtVaultFormatException("Vault archive inventory repeats an object")
            }
        }
    }

    private fun identity(
        header: OtVaultHeaderV1,
        family: CloudObjectFamily,
        objectId: String,
        chunkIndex: Int? = null,
        chunkCount: Int? = null,
    ): CloudHeaderIdentity {
        validateHeader(header)
        return CloudHeaderIdentity(
            family = family,
            schemaVersion = FORMAT_VERSION,
            cryptoVersion = FORMAT_VERSION,
            minimumReaderVersion = FORMAT_VERSION,
            vaultId = header.vaultId.value,
            objectId = objectId,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
        )
    }

    private fun requireObjectId(
        identity: CloudHeaderIdentity,
        expected: String,
    ) {
        if (identity.objectId != expected) {
            throw OtVaultFormatException(OUTSIDE_ARCHIVE_NAMESPACE)
        }
    }

    private fun requireArchiveVault(
        header: OtVaultHeaderV1,
        declared: String,
    ) = requireArchiveVault(header.vaultId.value, declared)

    private fun requireArchiveVault(
        vaultId: String,
        declared: String,
    ) {
        if (vaultId != declared) {
            throw OtVaultFormatException("Vault archive object names another vault")
        }
    }

    private fun validateBlobSetId(blobSetId: BlobSetId) =
        validateIdentifier(blobSetId.value, "Vault archive blob set")

    private fun validateIdentifier(value: String, label: String) {
        if (value.isEmpty() || value.length > MAX_IDENTIFIER_LENGTH) {
            throw OtVaultFormatException("$label identifier is outside its bound")
        }
        if (value.any(Char::isISOControl)) {
            throw OtVaultFormatException("$label identifier contains control characters")
        }
    }

    private fun snapshotObjectId(generation: Long): String = "otvault:snapshot:$generation"

    private fun segmentObjectId(
        firstGeneration: Long,
        lastGeneration: Long,
    ): String = "otvault:segment:$firstGeneration:$lastGeneration"

    private fun attachmentManifestObjectId(blobSetId: BlobSetId): String =
        "$ATTACHMENT_MANIFEST_PREFIX${blobSetId.value}"

    private fun attachmentChunkObjectId(
        blobSetId: BlobSetId,
        chunkIndex: Int,
    ): String = "$ATTACHMENT_CHUNK_PREFIX${blobSetId.value}:$chunkIndex"

    private fun readExact(source: InputStream, size: Int): ByteArray =
        ByteArray(size).also { target -> readExactInto(source, target, 0) }

    private fun readExactInto(
        source: InputStream,
        target: ByteArray,
        offset: Int,
    ) {
        var position = offset
        while (position < target.size) {
            val count = source.read(target, position, target.size - position)
            if (count < 0) {
                target.fill(0)
                throw OtVaultFormatException("Vault archive is truncated")
            }
            if (count == 0) {
                val next = source.read()
                if (next < 0) {
                    target.fill(0)
                    throw OtVaultFormatException("Vault archive is truncated")
                }
                target[position] = next.toByte()
                position += 1
            } else {
                position += count
            }
        }
    }

    private fun readInt(source: InputStream): Int {
        val bytes = readExact(source, LENGTH_PREFIX_BYTES)
        return try {
            ByteBuffer.wrap(bytes).int
        } finally {
            bytes.fill(0)
        }
    }

    private fun intBytes(value: Int): ByteArray =
        ByteArray(LENGTH_PREFIX_BYTES).also { ByteBuffer.wrap(it).putInt(value) }

    companion object {
        const val MAGIC = "OPEN_TASKS_VAULT"
        const val FORMAT_VERSION = 1
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_ARCHIVE_BYTES: Long = 512L * 1024 * 1024

        /**
         * The chunk domain every archive chunk frame binds.
         *
         * A chunk frame is written before the codec can know how many chunks a
         * caller will write, so the frozen frame header binds the archive's
         * fixed chunk domain rather than a per-attachment count. The true count
         * of a blob set is authenticated by that blob set's manifest frame.
         */
        const val MAX_CHUNKS_PER_BLOB_SET =
            AttachmentBlobSetManifestCodec.MAX_BLOB_SET_CHUNKS

        private const val LENGTH_PREFIX_BYTES = 4
        private const val MAX_IDENTIFIER_LENGTH = 512
        private const val OUTSIDE_ARCHIVE_NAMESPACE =
            "Vault archive object is outside the archive namespace"
        private const val INVENTORY_OBJECT_ID = "otvault:inventory"
        private const val ATTACHMENT_MANIFEST_PREFIX = "otvault:attachment-manifest:"
        private const val ATTACHMENT_CHUNK_PREFIX = "otvault:attachment-chunk:"
        private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.UTF_8)
        private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
        private const val MAX_FRAME_BYTES =
            LENGTH_PREFIX_BYTES + CloudBounds.MAX_HEADER_BYTES +
                CloudBounds.MAX_SNAPSHOT_CIPHERTEXT_BYTES
        private val MAX_INVENTORY_PLAINTEXT_BYTES =
            (
                CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES -
                    CloudBounds.AES_GCM_V1_CIPHERTEXT_OVERHEAD_BYTES
                ).toInt()
    }
}

@Serializable
private data class OtVaultHeaderPayloadV1(
    val formatVersion: Int,
    val vaultId: String,
    val createdAtEpochMillis: Long,
    val recoveryEnvelope: RecoveryEnvelopePayloadV1,
    val recordCount: Int,
    val attachmentCount: Int,
)

@Serializable
private data class OtVaultInventoryPayloadV1(
    val formatVersion: Int,
    val vaultId: String,
    val headerSha256: String,
    val entryCount: Int,
    val entries: List<OtVaultInventoryEntryV1>,
)

@OptIn(ExperimentalSerializationApi::class)
private object StrictOtVaultJson {
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowTrailingComma = false
    }
}

private fun VaultKeyEnvelope.clear() {
    kdf.salt.fill(0)
    nonce.fill(0)
    wrappedKeyset.fill(0)
}

private fun strictUtf8(source: ByteArray): String = try {
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(source))
        .toString()
} catch (failure: Exception) {
    throw IllegalArgumentException("Vault archive text is not valid UTF-8", failure)
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

private const val HEX_ALPHABET = "0123456789abcdef"
