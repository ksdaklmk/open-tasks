package app.opentasks.backup

import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.data.backup.BackupSnapshotCodec
import app.opentasks.core.data.backup.OtVaultCodec
import app.opentasks.core.data.backup.OtVaultHeaderV1
import app.opentasks.core.data.backup.OtVaultInventoryEntryV1
import app.opentasks.core.data.backup.StructuredBackupCapture
import app.opentasks.core.domain.AttachmentBlobSetManifest
import app.opentasks.core.domain.AttachmentChunkRef
import app.opentasks.core.domain.BackupCaptureSource
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * The outcome of one `.otvault` export attempt.
 *
 * [Failed.reason] is generic UK copy only: the codec's write side collapses
 * every distinguishable cause (an oversized vault, a malformed record) into
 * one opaque failure, so this exporter has nothing more specific it could
 * honestly report.
 */
sealed interface OtVaultExportResult {
    data class Completed(val byteCount: Long, val attachmentCount: Int) : OtVaultExportResult

    data class MissingAttachmentBytes(val displayNames: List<String>) : OtVaultExportResult

    data class Failed(val reason: String) : OtVaultExportResult
}

/**
 * Produces a frozen `.otvault` v1 archive of the whole current vault.
 *
 * The export passphrase becomes the archive's own recovery envelope: whoever
 * later imports this file unlocks it with the same passphrase used here.
 * Every active attachment is pre-flighted — a cache probe, then a session
 * probe, exactly as [app.opentasks.core.data.backup.AttachmentOpenCoordinator]
 * already verifies a live open — before a single byte reaches [destination],
 * so a vault with any unfetchable attachment writes nothing at all. Nothing
 * beyond one chunk at a time is ever held in memory, and no plaintext archive
 * is staged on disk; the caller owns [destination] and must discard it on
 * anything other than [OtVaultExportResult.Completed].
 */
class OtVaultExporter(
    private val vaultId: VaultId,
    private val captureSource: BackupCaptureSource<StructuredBackupCapture>,
    private val vaultRepository: VaultRepository,
    private val contentKeyStore: VaultContentKeyStore,
    private val codec: OtVaultCodec,
    private val prepareEnvelope: (CharArray) -> PreparedRecoveryEnvelope,
    private val readChunksForExport: suspend (
        attachment: Attachment,
        onChunk: suspend (chunkIndex: Int, plaintext: ByteArray) -> Unit,
    ) -> Boolean,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun export(destination: OutputStream, passphrase: CharArray): OtVaultExportResult = try {
        runExport(destination, passphrase)
    } finally {
        passphrase.fill(' ')
    }

    private suspend fun runExport(
        destination: OutputStream,
        passphrase: CharArray,
    ): OtVaultExportResult {
        val capture = try {
            captureSource.capture()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return genericFailure()
        }
        if (capture.vaultId != vaultId) return genericFailure()

        val activeAttachments = try {
            vaultRepository.currentWorkspace().attachments.filter { it.deletedAt == null }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return genericFailure()
        }

        val prepared = try {
            prepareEnvelope(passphrase)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return genericFailure()
        }
        return try {
            when (val preflight = preflight(activeAttachments)) {
                is Preflight.Missing -> OtVaultExportResult.MissingAttachmentBytes(
                    preflight.displayNames,
                )
                is Preflight.Ready -> write(
                    destination = destination,
                    capture = capture,
                    activeAttachments = activeAttachments,
                    manifests = preflight.manifests,
                    prepared = prepared,
                )
            }
        } finally {
            prepared.close()
        }
    }

    /**
     * Verifies every active attachment is fetchable and learns each chunk's
     * real plaintext length and digest — before a single archive byte is
     * written. A chunk is read exactly once here; the write phase reads it
     * again, by then served from the cache this pass already populated.
     */
    private suspend fun preflight(attachments: List<Attachment>): Preflight {
        val manifests = mutableMapOf<AttachmentId, PendingManifest>()
        val missing = mutableListOf<String>()
        attachments.forEach { attachment ->
            val chunkCount = attachment.chunkCount
            val plaintextByteCounts = arrayOfNulls<Int>(chunkCount)
            val chunkDigests = arrayOfNulls<String>(chunkCount)
            val fetched = try {
                readChunksForExport(attachment) { chunkIndex, plaintext ->
                    if (chunkIndex in 0 until chunkCount) {
                        plaintextByteCounts[chunkIndex] = plaintext.size
                        chunkDigests[chunkIndex] = sha256(plaintext)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            if (!fetched || chunkDigests.any { it == null } || plaintextByteCounts.any { it == null }) {
                missing += attachment.displayName
            } else {
                manifests[attachment.id] = PendingManifest(
                    plaintextByteCounts = plaintextByteCounts.map { requireNotNull(it) },
                    chunkDigests = chunkDigests.map { requireNotNull(it) },
                )
            }
        }
        return if (missing.isNotEmpty()) {
            Preflight.Missing(missing)
        } else {
            Preflight.Ready(manifests)
        }
    }

    private suspend fun write(
        destination: OutputStream,
        capture: StructuredBackupCapture,
        activeAttachments: List<Attachment>,
        manifests: Map<AttachmentId, PendingManifest>,
        prepared: PreparedRecoveryEnvelope,
    ): OtVaultExportResult {
        val key = try {
            contentKeyStore.openExisting(vaultId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return genericFailure()
        }
        return try {
            val counting = CountingOutputStream(destination)
            val header = OtVaultHeaderV1(
                formatVersion = OtVaultCodec.FORMAT_VERSION,
                vaultId = vaultId,
                createdAtEpochMillis = now().toEpochMilli(),
                envelope = prepared.envelope,
                recordCount = capture.records.size,
                attachmentCount = activeAttachments.size,
            )
            codec.writeHeader(counting, header)
            val entries = mutableListOf<OtVaultInventoryEntryV1>()
            entries += codec.writeSnapshot(
                counting,
                key,
                header,
                BackupSnapshotCodec.fromCapture(capture),
            )
            activeAttachments.forEach { attachment ->
                val blobSetId = requireNotNull(attachment.blobSetId) {
                    "A pre-flighted attachment must name its blob set"
                }
                val pending = requireNotNull(manifests[attachment.id]) {
                    "A pre-flighted attachment must have pending manifest data"
                }
                entries += codec.writeAttachmentManifest(
                    counting,
                    key,
                    header,
                    pending.toManifest(attachment, blobSetId),
                )
                val fetched = readChunksForExport(attachment) { chunkIndex, plaintext ->
                    entries += codec.writeAttachmentChunk(
                        counting,
                        key,
                        header,
                        blobSetId,
                        chunkIndex,
                        plaintext,
                    )
                }
                if (!fetched) return genericFailure()
            }
            codec.writeInventory(counting, key, header, entries)
            OtVaultExportResult.Completed(
                byteCount = counting.count,
                attachmentCount = activeAttachments.size,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            genericFailure()
        } finally {
            key.close()
        }
    }

    private fun genericFailure() = OtVaultExportResult.Failed(OT_VAULT_EXPORT_FAILED_REASON)

    /** One attachment's real per-chunk lengths and digests, learned in [preflight]. */
    private class PendingManifest(
        private val plaintextByteCounts: List<Int>,
        private val chunkDigests: List<String>,
    ) {
        /**
         * A manifest scoped to this archive alone: [AttachmentChunkRef.providerObjectId]
         * and [AttachmentChunkRef.ciphertextSha256] never claim to describe the live
         * remote object — only this archive's own chunk frames, which
         * [OtVaultCodec] authenticates independently through its own frame
         * encryption and inventory digest.
         */
        fun toManifest(attachment: Attachment, blobSetId: BlobSetId): AttachmentBlobSetManifest =
            AttachmentBlobSetManifest(
                blobSetId = blobSetId,
                contentSha256 = Sha256Digest.of(attachment.contentHash),
                totalByteCount = attachment.byteCount,
                chunks = plaintextByteCounts.mapIndexed { index, byteCount ->
                    AttachmentChunkRef(
                        index = index,
                        providerObjectId = ProviderObjectId.of(
                            "otvault-export-chunk:${blobSetId.value}:$index",
                        ),
                        ciphertextSha256 = Sha256Digest.of(chunkDigests[index]),
                        plaintextByteCount = byteCount,
                    )
                },
            )
    }

    private sealed interface Preflight {
        data class Missing(val displayNames: List<String>) : Preflight

        class Ready(val manifests: Map<AttachmentId, PendingManifest>) : Preflight
    }
}

/**
 * The one generic UK failure copy every export failure surfaces — from the
 * exporter itself, and from the ViewModel that opens its destination stream.
 * It never distinguishes causes, so it never risks leaking one.
 */
internal const val OT_VAULT_EXPORT_FAILED_REASON = "The vault could not be exported."

/** Counts every byte written to [delegate] without buffering any of it. */
private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var count = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        count += 1
    }

    override fun write(b: ByteArray) {
        delegate.write(b)
        count += b.size
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        count += len
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}

private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
}

private const val HEX = "0123456789abcdef"
