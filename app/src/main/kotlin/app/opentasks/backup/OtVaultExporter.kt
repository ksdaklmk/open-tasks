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
 *
 * The write phase re-reads every attachment a second time (see [preflight]),
 * so a cache large enough to hold the whole pre-flighted corpus serves that
 * second read for free. A corpus above the cache ceiling
 * (`min(128 MiB, 5% available storage)`) has no such guarantee: an entry
 * fetched early in pre-flight can be evicted before the write phase asks for
 * it again, and that attachment is then re-downloaded from the live session —
 * a real, honest 2x transfer cost, not a defect. A second-read failure still
 * fails safe: `readChunksForExport` returns `false`, the export becomes a
 * generic [OtVaultExportResult.Failed], and the caller deletes the partial
 * document exactly as it would for any other failure.
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
        passphrase.fill('\u0000')
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
     * real plaintext length — before a single archive byte is written. A
     * chunk is read once here; the write phase reads it again (see the class
     * doc for what that costs when the cache can't hold the whole corpus).
     */
    private suspend fun preflight(attachments: List<Attachment>): Preflight {
        val manifests = mutableMapOf<AttachmentId, PendingManifest>()
        val missing = mutableListOf<String>()
        attachments.forEach { attachment ->
            val chunkCount = attachment.chunkCount
            val plaintextByteCounts = arrayOfNulls<Int>(chunkCount)
            val fetched = try {
                readChunksForExport(attachment) { chunkIndex, plaintext ->
                    if (chunkIndex in 0 until chunkCount) {
                        plaintextByteCounts[chunkIndex] = plaintext.size
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            if (!fetched || plaintextByteCounts.any { it == null }) {
                missing += attachment.displayName
            } else {
                manifests[attachment.id] = PendingManifest(
                    plaintextByteCounts = plaintextByteCounts.map { requireNotNull(it) },
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

    /** One attachment's real per-chunk plaintext lengths, learned in [preflight]. */
    private class PendingManifest(
        private val plaintextByteCounts: List<Int>,
    ) {
        /**
         * A manifest scoped to this archive alone.
         *
         * The codec's mandated write order puts the manifest before its
         * chunks, so the real archive frame for a chunk — and therefore its
         * ciphertext — does not exist yet when this manifest is built.
         * [AttachmentChunkRef.providerObjectId] and
         * [AttachmentChunkRef.ciphertextSha256] carry the same fixed sentinel
         * every other place in this codebase uses for "not meaningful here"
         * (see `ZERO_SHA256` in `PortableBackupCodec`/`PublicationCodec`):
         * writing the plaintext's own digest into a field whose one meaning
         * everywhere else is "sha256 of the encrypted frame"
         * ([app.opentasks.core.data.backup.AttachmentOpenCoordinator] checks
         * exactly that) would look plausible and be wrong — every chunk would
         * fail as corrupt the moment anything fed this manifest to the live
         * open path. Per-chunk integrity for the archive itself lives in the
         * codec's own frame encryption plus the inventory's per-object digest,
         * not in this manifest.
         */
        fun toManifest(attachment: Attachment, blobSetId: BlobSetId): AttachmentBlobSetManifest =
            AttachmentBlobSetManifest(
                blobSetId = blobSetId,
                contentSha256 = Sha256Digest.of(attachment.contentHash),
                totalByteCount = attachment.byteCount,
                chunks = plaintextByteCounts.mapIndexed { index, byteCount ->
                    AttachmentChunkRef(
                        index = index,
                        providerObjectId = ProviderObjectId.of(SENTINEL_PROVIDER_OBJECT_ID),
                        ciphertextSha256 = Sha256Digest.of(SENTINEL_SHA256),
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

/**
 * The fixed placeholder [AttachmentChunkRef.ciphertextSha256] carries in an
 * archive manifest, matching the `ZERO_SHA256` convention already used
 * elsewhere in this module for "this field has no meaning in this context".
 */
private const val SENTINEL_SHA256 =
    "0000000000000000000000000000000000000000000000000000000000000000"

/**
 * The fixed placeholder [AttachmentChunkRef.providerObjectId] carries in an
 * archive manifest. It never names a real provider object — the archive's
 * actual chunk frame identity lives entirely in [OtVaultCodec]'s own object
 * IDs, authenticated by its frame encryption and inventory digest.
 */
private const val SENTINEL_PROVIDER_OBJECT_ID = "otvault:archive-manifest-sentinel"
