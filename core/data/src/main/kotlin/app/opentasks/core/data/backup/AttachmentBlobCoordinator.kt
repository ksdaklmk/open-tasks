package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.db.AttachmentTransferDao
import app.opentasks.core.data.db.AttachmentTransferEntity
import app.opentasks.core.domain.AttachmentBlobSetManifest
import app.opentasks.core.domain.AttachmentBlobStore
import app.opentasks.core.domain.AttachmentChunkRef
import app.opentasks.core.domain.AttachmentObjectResult
import app.opentasks.core.domain.AttachmentReadResult
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.Revision
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.TaskId
import app.opentasks.core.sync.CloudBounds
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

interface AttachmentSource {
    val declaredByteCount: Long
    fun open(): InputStream
}

sealed interface AttachmentIntakeResult {
    data class Registered(val attachmentId: AttachmentId) : AttachmentIntakeResult
    data object SourceUnavailable : AttachmentIntakeResult
    data object TooLarge : AttachmentIntakeResult
    data object OwnershipUnavailable : AttachmentIntakeResult
    data class Failed(val reason: RemoteBackupFailureCategory) : AttachmentIntakeResult
}

class AttachmentBlobCoordinator(
    private val repository: VaultRepository,
    private val transferDao: AttachmentTransferDao,
    private val codec: AuthenticatedCloudObjectCodec,
    private val manifestCodec: AttachmentBlobSetManifestCodec,
    private val lineageId: CloudLineageId,
    private val contentKey: () -> VaultKey,
    private val holdsOwnership: suspend () -> Boolean,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun intake(
        store: AttachmentBlobStore,
        taskId: TaskId,
        displayName: String,
        mimeType: String,
        source: AttachmentSource,
    ): AttachmentIntakeResult {
        if (source.declaredByteCount > MAX_TOTAL_BYTES) return AttachmentIntakeResult.TooLarge
        if (source.declaredByteCount <= 0 || mimeType.length > MAX_METADATA_LENGTH) {
            return corruptFailure()
        }
        val input = try {
            source.open()
        } catch (_: IOException) {
            return AttachmentIntakeResult.SourceUnavailable
        } catch (_: SecurityException) {
            return AttachmentIntakeResult.SourceUnavailable
        }
        return input.use {
            intakeOpened(store, taskId, displayName, mimeType, source.declaredByteCount, it)
        }
    }

    suspend fun resume(store: AttachmentBlobStore): Int {
        var registered = 0
        transferDao.pending().forEach { session ->
            if (session.phase !in RESUMABLE_PHASES) return@forEach
            val state = decodeState(session) ?: return@forEach
            if (state.invalid) return@forEach
            val verification = verifyChunks(store, session, state)
            if (verification !is ChunkVerification.Success) return@forEach
            val result = publish(
                store = store,
                session = session,
                state = state,
                manifest = verification.manifest,
                createManifest = session.phase != PHASE_MANIFEST_CREATED,
            )
            if (result is AttachmentIntakeResult.Registered) registered += 1
        }
        return registered
    }

    suspend fun expireStaleSessions(store: AttachmentBlobStore): Int {
        if (!holdsOwnership()) return 0
        val cutoff = now().minus(STALE_AFTER).toEpochMilli()
        var expired = 0
        transferDao.stale(cutoff).forEach { session ->
            val state = decodeState(session) ?: return@forEach
            val ownedIds = authenticateCleanupObjects(store, session, state) ?: return@forEach
            var deleted = true
            ownedIds.forEach { providerObjectId ->
                if (!holdsOwnership() || !store.delete(providerObjectId)) deleted = false
            }
            if (deleted && transferDao.delete(session.blobSetId) == 1) expired += 1
        }
        return expired
    }

    private suspend fun intakeOpened(
        store: AttachmentBlobStore,
        taskId: TaskId,
        displayName: String,
        mimeType: String,
        declaredByteCount: Long,
        input: InputStream,
    ): AttachmentIntakeResult {
        val chunkCount = ((declaredByteCount + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        val objectIds = try {
            store.generateObjectIds(chunkCount + 1)
        } catch (_: IOException) {
            return AttachmentIntakeResult.Failed(RemoteBackupFailureCategory.RETRYABLE_PROVIDER)
        }
        if (objectIds.size != chunkCount + 1 || objectIds.distinct().size != objectIds.size) {
            return corruptFailure()
        }
        val timestamp = now().toEpochMilli()
        var state = AttachmentTransferStateV1(
            chunks = objectIds.dropLast(1).map { AttachmentTransferChunkStateV1(it.value) },
        )
        var session = AttachmentTransferEntity(
            blobSetId = BlobSetId.new().value,
            attachmentId = AttachmentId.new().value,
            taskId = taskId.value,
            phase = PHASE_PLANNED,
            displayNameCiphertext = sanitizeAttachmentDisplayName(displayName).toByteArray(),
            mimeType = mimeType,
            declaredByteCount = declaredByteCount,
            contentHash = null,
            chunkCount = chunkCount,
            chunkStateEncoded = encodeState(state),
            manifestProviderFileId = objectIds.last().value,
            createdAtEpochMillis = timestamp,
            updatedAtEpochMillis = timestamp,
        )
        transferDao.upsert(session)
        if (!holdsOwnership()) return AttachmentIntakeResult.OwnershipUnavailable
        session = session.copy(phase = PHASE_UPLOADING, updatedAtEpochMillis = now().toEpochMilli())
        transferDao.upsert(session)

        val plaintextDigest = MessageDigest.getInstance(SHA_256)
        val source = DigestInputStream(input, plaintextDigest)
        var consumed = 0L
        for (index in 0 until chunkCount) {
            val size = minOf(CHUNK_BYTES, declaredByteCount - consumed).toInt()
            val plaintext = ByteArray(size)
            val complete = try {
                source.readFully(plaintext)
            } catch (_: IOException) {
                state = state.copy(invalid = true)
                transferDao.upsert(session.withState(state))
                plaintext.fill(0)
                return AttachmentIntakeResult.SourceUnavailable
            }
            if (!complete) {
                state = state.copy(invalid = true)
                transferDao.upsert(session.withState(state))
                plaintext.fill(0)
                return corruptFailure()
            }
            val frame = try {
                codec.encrypt(chunkIdentity(session, index), plaintext, contentKey())
            } finally {
                plaintext.fill(0)
            }
            val frameDigest = sha256Hex(frame)
            state = state.copy(
                chunks = state.chunks.mapIndexed { stateIndex, chunk ->
                    if (stateIndex == index) {
                        chunk.copy(ciphertextSha256 = frameDigest, plaintextByteCount = size)
                    } else {
                        chunk
                    }
                },
            )
            session = session.withState(state)
            transferDao.upsert(session)
            val createResult = try {
                store.createChunk(
                    providerObjectId = objectIds[index],
                    blobSetId = BlobSetId(session.blobSetId),
                    chunkIndex = index,
                    chunkCount = chunkCount,
                    frameBytes = frame,
                )
            } finally {
                frame.fill(0)
            }
            if (createResult is AttachmentObjectResult.Failed) {
                return AttachmentIntakeResult.Failed(createResult.reason)
            }
            consumed += size
        }
        val extra = try {
            source.read()
        } catch (_: IOException) {
            state = state.copy(invalid = true)
            transferDao.upsert(session.withState(state))
            return AttachmentIntakeResult.SourceUnavailable
        }
        if (extra >= 0) {
            state = state.copy(invalid = true)
            transferDao.upsert(session.withState(state))
            return if (source.exceedsCap(consumed + 1)) {
                AttachmentIntakeResult.TooLarge
            } else {
                corruptFailure()
            }
        }
        session = session.copy(
            contentHash = plaintextDigest.digest().toHex(),
            chunkStateEncoded = encodeState(state),
            updatedAtEpochMillis = now().toEpochMilli(),
        )
        transferDao.upsert(session)
        return when (val verification = verifyChunks(store, session, state)) {
            is ChunkVerification.Failed -> AttachmentIntakeResult.Failed(verification.reason)
            is ChunkVerification.Success ->
                publish(store, session, state, verification.manifest, createManifest = true)
        }
    }

    private suspend fun publish(
        store: AttachmentBlobStore,
        session: AttachmentTransferEntity,
        state: AttachmentTransferStateV1,
        manifest: AttachmentBlobSetManifest,
        createManifest: Boolean,
    ): AttachmentIntakeResult {
        var current = session
        if (current.phase != PHASE_MANIFEST_CREATED) {
            current = current.copy(
                phase = PHASE_CHUNKS_VERIFIED,
                updatedAtEpochMillis = now().toEpochMilli(),
            )
            transferDao.upsert(current)
        }
        val manifestId = providerId(current.manifestProviderFileId) ?: return corruptFailure()
        if (createManifest) {
            if (!holdsOwnership()) return AttachmentIntakeResult.OwnershipUnavailable
            val frame = try {
                manifestCodec.encode(manifest, lineageId, contentKey())
            } catch (_: IllegalArgumentException) {
                return corruptFailure()
            }
            val createResult = try {
                store.createManifest(manifestId, manifest.blobSetId, frame)
            } finally {
                frame.fill(0)
            }
            if (createResult is AttachmentObjectResult.Failed) {
                return AttachmentIntakeResult.Failed(createResult.reason)
            }
            if (createResult != AttachmentObjectResult.Ambiguous) {
                current = current.copy(
                    phase = PHASE_MANIFEST_CREATED,
                    updatedAtEpochMillis = now().toEpochMilli(),
                )
                transferDao.upsert(current)
            }
        }
        when (
            val read = store.readObject(
                manifestId,
                AttachmentBlobSetManifestCodec.MAX_FRAME_BYTES.toLong(),
            )
        ) {
            is AttachmentReadResult.Failed -> return AttachmentIntakeResult.Failed(read.reason)
            AttachmentReadResult.Missing -> return corruptFailure()
            is AttachmentReadResult.Found -> {
                val decoded = try {
                    manifestCodec.decode(read.bytes, lineageId, manifest.blobSetId, contentKey())
                } catch (_: IllegalArgumentException) {
                    return corruptFailure()
                } finally {
                    read.bytes.fill(0)
                }
                if (decoded != manifest) return corruptFailure()
            }
        }
        if (current.phase != PHASE_MANIFEST_CREATED) {
            current = current.copy(
                phase = PHASE_MANIFEST_CREATED,
                updatedAtEpochMillis = now().toEpochMilli(),
            )
            transferDao.upsert(current)
        }
        val attachmentId = AttachmentId(current.attachmentId)
        val result = repository.execute(
            DomainCommand.RegisterAttachment(
                Attachment(
                    id = attachmentId,
                    taskId = TaskId(current.taskId),
                    displayName = current.displayNameCiphertext.toString(Charsets.UTF_8),
                    mimeType = current.mimeType,
                    byteCount = current.declaredByteCount,
                    contentHash = manifest.contentSha256.value,
                    blobSetId = manifest.blobSetId,
                    chunkCount = state.chunks.size,
                    deletedAt = null,
                    revision = Revision(
                        deviceId = DeviceId(REVISION_DEVICE_ID),
                        wallTimeMillis = now().toEpochMilli(),
                        logicalCounter = 0,
                    ),
                ),
            ),
        )
        if (result !is CommandResult.Success) return corruptFailure()
        transferDao.upsert(
            current.copy(phase = PHASE_REGISTERED, updatedAtEpochMillis = now().toEpochMilli()),
        )
        return AttachmentIntakeResult.Registered(attachmentId)
    }

    private suspend fun verifyChunks(
        store: AttachmentBlobStore,
        session: AttachmentTransferEntity,
        state: AttachmentTransferStateV1,
    ): ChunkVerification {
        val expectedHash = session.contentHash ?: return corruptVerification()
        val chunkCount = session.chunkCount ?: return corruptVerification()
        if (state.invalid || state.chunks.size != chunkCount) return corruptVerification()
        val digest = MessageDigest.getInstance(SHA_256)
        val refs = ArrayList<AttachmentChunkRef>(chunkCount)
        var total = 0L
        state.chunks.forEachIndexed { index, chunk ->
            val providerId = providerId(chunk.providerObjectId) ?: return corruptVerification()
            val ciphertextDigest = chunk.ciphertextSha256 ?: return corruptVerification()
            val plaintextByteCount = chunk.plaintextByteCount ?: return corruptVerification()
            val read = store.readObject(providerId, MAX_CHUNK_FRAME_BYTES)
            val frame = when (read) {
                is AttachmentReadResult.Failed -> return ChunkVerification.Failed(read.reason)
                AttachmentReadResult.Missing -> return corruptVerification()
                is AttachmentReadResult.Found -> read.bytes
            }
            if (sha256Hex(frame) != ciphertextDigest) {
                frame.fill(0)
                return corruptVerification()
            }
            val decrypted = codec.decrypt(
                ByteArrayInputStream(frame),
                frame.size.toLong(),
                contentKey(),
            )
            frame.fill(0)
            if (decrypted !is CloudDecodeResult.Success) return corruptVerification()
            val plaintext = decrypted.value.use { value ->
                if (value.identity != chunkIdentity(session, index)) return corruptVerification()
                value.takePlaintext()
            }
            if (plaintext.size != plaintextByteCount) {
                plaintext.fill(0)
                return corruptVerification()
            }
            digest.update(plaintext)
            plaintext.fill(0)
            total += plaintextByteCount
            refs += AttachmentChunkRef(
                index = index,
                providerObjectId = providerId,
                ciphertextSha256 = Sha256Digest.of(ciphertextDigest),
                plaintextByteCount = plaintextByteCount,
            )
        }
        val verifiedHash = digest.digest().toHex()
        if (total != session.declaredByteCount || verifiedHash != expectedHash) {
            return corruptVerification()
        }
        return ChunkVerification.Success(
            AttachmentBlobSetManifest(
                blobSetId = BlobSetId(session.blobSetId),
                contentSha256 = Sha256Digest.of(expectedHash),
                totalByteCount = total,
                chunks = refs,
            ),
        )
    }

    private suspend fun authenticateCleanupObjects(
        store: AttachmentBlobStore,
        session: AttachmentTransferEntity,
        state: AttachmentTransferStateV1,
    ): List<ProviderObjectId>? {
        val owned = mutableListOf<ProviderObjectId>()
        state.chunks.forEachIndexed { index, chunk ->
            val id = providerId(chunk.providerObjectId) ?: return null
            when (val read = store.readObject(id, MAX_CHUNK_FRAME_BYTES)) {
                is AttachmentReadResult.Failed -> return null
                AttachmentReadResult.Missing -> Unit
                is AttachmentReadResult.Found -> {
                    val expectedDigest = chunk.ciphertextSha256 ?: return null
                    val expectedBytes = chunk.plaintextByteCount ?: return null
                    if (sha256Hex(read.bytes) != expectedDigest) {
                        read.bytes.fill(0)
                        return null
                    }
                    val decoded = codec.decrypt(
                        ByteArrayInputStream(read.bytes),
                        read.bytes.size.toLong(),
                        contentKey(),
                    )
                    read.bytes.fill(0)
                    if (decoded !is CloudDecodeResult.Success) return null
                    val valid = decoded.value.use { value ->
                        if (value.identity != chunkIdentity(session, index)) return@use false
                        val plaintext = value.takePlaintext()
                        try {
                            plaintext.size == expectedBytes
                        } finally {
                            plaintext.fill(0)
                        }
                    }
                    if (!valid) return null
                    owned += id
                }
            }
        }
        val manifestId = providerId(session.manifestProviderFileId) ?: return null
        when (
            val read = store.readObject(
                manifestId,
                AttachmentBlobSetManifestCodec.MAX_FRAME_BYTES.toLong(),
            )
        ) {
            is AttachmentReadResult.Failed -> return null
            AttachmentReadResult.Missing -> Unit
            is AttachmentReadResult.Found -> {
                val expected = state.expectedManifest(session) ?: return null
                val decoded = try {
                    manifestCodec.decode(read.bytes, lineageId, expected.blobSetId, contentKey())
                } catch (_: IllegalArgumentException) {
                    return null
                } finally {
                    read.bytes.fill(0)
                }
                if (decoded != expected) return null
                owned += manifestId
            }
        }
        return owned
    }

    private fun AttachmentTransferStateV1.expectedManifest(
        session: AttachmentTransferEntity,
    ): AttachmentBlobSetManifest? {
        val hash = session.contentHash ?: return null
        val chunks = chunks.mapIndexed { index, chunk ->
            AttachmentChunkRef(
                index = index,
                providerObjectId = providerId(chunk.providerObjectId) ?: return null,
                ciphertextSha256 = Sha256Digest.of(chunk.ciphertextSha256 ?: return null),
                plaintextByteCount = chunk.plaintextByteCount ?: return null,
            )
        }
        return AttachmentBlobSetManifest(
            blobSetId = BlobSetId(session.blobSetId),
            contentSha256 = Sha256Digest.of(hash),
            totalByteCount = session.declaredByteCount,
            chunks = chunks,
        )
    }

    private fun decodeState(session: AttachmentTransferEntity): AttachmentTransferStateV1? {
        val state = try {
            TRANSFER_JSON.decodeFromString(
                AttachmentTransferStateV1.serializer(),
                session.chunkStateEncoded,
            )
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val chunkCount = session.chunkCount ?: return null
        if (
            state.version != 1 ||
            session.blobSetId.isBlank() ||
            session.declaredByteCount !in 1..MAX_TOTAL_BYTES ||
            chunkCount !in 1..MAX_CHUNKS ||
            chunkCount.toLong() !=
            (session.declaredByteCount + CHUNK_BYTES - 1) / CHUNK_BYTES ||
            state.chunks.size != chunkCount
        ) {
            return null
        }
        if (state.chunks.map { it.providerObjectId }.distinct().size != state.chunks.size) {
            return null
        }
        val manifestId = providerId(session.manifestProviderFileId) ?: return null
        if (state.chunks.any { it.providerObjectId == manifestId.value }) return null
        session.contentHash?.let {
            try {
                Sha256Digest.of(it)
            } catch (_: IllegalArgumentException) {
                return null
            }
        }
        state.chunks.forEachIndexed { index, chunk ->
            if (providerId(chunk.providerObjectId) == null) return null
            if ((chunk.ciphertextSha256 == null) != (chunk.plaintextByteCount == null)) {
                return null
            }
            chunk.ciphertextSha256?.let {
                try {
                    Sha256Digest.of(it)
                } catch (_: IllegalArgumentException) {
                    return null
                }
                val maximum = expectedChunkBytes(session.declaredByteCount, index)
                if (chunk.plaintextByteCount != maximum) return null
            }
        }
        return state
    }
    private fun encodeState(state: AttachmentTransferStateV1): String =
        TRANSFER_JSON.encodeToString(AttachmentTransferStateV1.serializer(), state)

    private fun AttachmentTransferEntity.withState(
        state: AttachmentTransferStateV1,
    ): AttachmentTransferEntity = copy(
        chunkStateEncoded = encodeState(state),
        updatedAtEpochMillis = now().toEpochMilli(),
    )

    private fun chunkIdentity(session: AttachmentTransferEntity, index: Int) =
        CloudHeaderIdentity(
            family = CloudObjectFamily.ATTACHMENT_CHUNK,
            schemaVersion = 1,
            cryptoVersion = 1,
            minimumReaderVersion = 1,
            vaultId = lineageId.value,
            objectId = "attachment-chunk:${session.blobSetId}",
            chunkIndex = index,
            chunkCount = session.chunkCount,
        )

    private fun providerId(value: String?): ProviderObjectId? = try {
        value?.let(ProviderObjectId::of)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun expectedChunkBytes(total: Long, index: Int): Int =
        minOf(CHUNK_BYTES, total - index * CHUNK_BYTES).toInt()

    private fun corruptFailure() = AttachmentIntakeResult.Failed(
        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
    )

    private fun corruptVerification() = ChunkVerification.Failed(
        RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE,
    )

    private companion object {
        const val PHASE_PLANNED = "PLANNED"
        const val PHASE_UPLOADING = "UPLOADING"
        const val PHASE_CHUNKS_VERIFIED = "CHUNKS_VERIFIED"
        const val PHASE_MANIFEST_CREATED = "MANIFEST_CREATED"
        const val PHASE_REGISTERED = "REGISTERED"
        const val SHA_256 = "SHA-256"
        const val REVISION_DEVICE_ID = "attachment-intake"
        const val MAX_METADATA_LENGTH = 255
        const val MAX_CHUNKS = AttachmentBlobSetManifestCodec.MAX_BLOB_SET_CHUNKS
        const val MAX_TOTAL_BYTES = AttachmentBlobSetManifestCodec.MAX_TOTAL_BYTES
        const val CHUNK_BYTES = CloudBounds.MAX_ATTACHMENT_CHUNK_PLAINTEXT_BYTES
        val MAX_CHUNK_FRAME_BYTES =
            4L + CloudBounds.MAX_HEADER_BYTES +
                CloudBounds.MAX_ATTACHMENT_CHUNK_CIPHERTEXT_BYTES_V1
        val STALE_AFTER: Duration = Duration.ofHours(24)
        val RESUMABLE_PHASES = setOf(
            PHASE_PLANNED,
            PHASE_UPLOADING,
            PHASE_CHUNKS_VERIFIED,
            PHASE_MANIFEST_CREATED,
        )
    }
}

fun sanitizeAttachmentDisplayName(raw: String): String {
    val clean = raw.filterNot(Char::isISOControl)
        .trimStart { it == '.' || it == '/' || it == '\\' }
    val result = buildString(minOf(clean.length, 255)) {
        var pendingSpace = false
        clean.forEach { character ->
            when {
                character.isWhitespace() -> pendingSpace = isNotEmpty()
                character == '/' || character == '\\' -> {
                    if (pendingSpace && lastOrNull() != ' ') append(' ')
                    pendingSpace = false
                    if (lastOrNull() != '_') append('_')
                }
                else -> {
                    if (pendingSpace && lastOrNull() != ' ') append(' ')
                    pendingSpace = false
                    append(character)
                }
            }
        }
    }.trim().take(255)
    return result.ifEmpty { "attachment" }
}

private sealed interface ChunkVerification {
    data class Success(val manifest: AttachmentBlobSetManifest) : ChunkVerification
    data class Failed(val reason: RemoteBackupFailureCategory) : ChunkVerification
}

@Serializable
private data class AttachmentTransferStateV1(
    val version: Int = 1,
    val invalid: Boolean = false,
    val chunks: List<AttachmentTransferChunkStateV1>,
)

@Serializable
private data class AttachmentTransferChunkStateV1(
    val providerObjectId: String,
    val ciphertextSha256: String? = null,
    val plaintextByteCount: Int? = null,
)

private val TRANSFER_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    allowTrailingComma = false
}

private fun InputStream.readFully(destination: ByteArray): Boolean {
    var offset = 0
    while (offset < destination.size) {
        val count = read(destination, offset, destination.size - offset)
        if (count < 0) return false
        if (count == 0) {
            val byte = read()
            if (byte < 0) return false
            destination[offset++] = byte.toByte()
        } else {
            offset += count
        }
    }
    return true
}

private fun InputStream.exceedsCap(alreadyRead: Long): Boolean {
    var total = alreadyRead
    val buffer = ByteArray(8 * 1024)
    try {
        while (total <= AttachmentBlobSetManifestCodec.MAX_TOTAL_BYTES) {
            val count = read(buffer)
            if (count < 0) return false
            total += if (count == 0) 1 else count
            if (count == 0 && read() < 0) return false
        }
        return true
    } finally {
        buffer.fill(0)
    }
}

private fun ByteArray.toHex(): String = try {
    buildString(size * 2) {
        this@toHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
} finally {
    fill(0)
}

private const val HEX = "0123456789abcdef"
