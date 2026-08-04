package app.opentasks.backup

import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.backup.AttachmentCacheStore
import app.opentasks.core.data.backup.BackupFieldType
import app.opentasks.core.data.backup.BackupRecordFamily
import app.opentasks.core.data.backup.BackupRecordV1
import app.opentasks.core.data.backup.BackupSnapshotPayloadV1
import app.opentasks.core.data.backup.OtVaultCodec
import app.opentasks.core.data.backup.OtVaultReadEvent
import app.opentasks.core.domain.AttachmentBlobSetManifest
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException

/**
 * What one staged `.otvault` archive would replace the active vault with.
 *
 * [Ready.attachmentsBeyondCache] names the attachments whose bytes this device
 * could not retain under the attachment cache bound. They are a recorded
 * consequence the person confirms, not silent loss: the archive file remains
 * their copy of those bytes.
 *
 * [Rejected.reason] is already resolved, generic UK copy. It distinguishes only
 * "this passphrase does not open the archive" from "this file is not an archive
 * this version can import" — both facts about the file the person just chose,
 * never anything the archive contains.
 */
sealed interface OtVaultImportPreview {
    data class Ready(
        val recordCount: Int,
        val attachmentCount: Int,
        val attachmentsBeyondCache: List<String>,
    ) : OtVaultImportPreview

    data class Rejected(val reason: String) : OtVaultImportPreview
}

/**
 * Stages one `.otvault` v1 archive, previews it, and — only once confirmed —
 * makes it this device's vault.
 *
 * [stage] authenticates every archive frame, proves the archive is internally
 * complete, and retains what attachment bytes the cache bound allows. It builds
 * no database and touches no vault slot, so a corrupt archive, a wrong
 * passphrase, or a person who changes their mind costs the active vault
 * nothing. [activate] is the first step that can replace anything.
 *
 * The archive header's envelope is a real recovery envelope: the passphrase
 * this archive was exported with becomes the imported vault's recovery
 * passphrase, and the content key it wraps becomes the imported vault's content
 * key. Nothing derived from the person's own vault is reused.
 *
 * Archives are snapshot-only baselines, so an archive carrying an operation
 * segment is refused rather than silently reduced to its snapshot, and
 * [activate] hands the vault an empty segment list.
 *
 * Attachment bytes are staged in [stagingRoot], never in the live attachment
 * cache: [cache] is what the vault this device still holds is using, and an
 * import that is refused or never confirmed must not have cost it a single
 * frame. Only [activate] promotes the staged frames into [cache], and only
 * after the vault itself has been replaced.
 */
class OtVaultImporter(
    private val codec: OtVaultCodec,
    private val authenticatedCodec: AuthenticatedCloudObjectCodec,
    private val crypto: VaultCrypto,
    private val cache: AttachmentCacheStore,
    private val stagingRoot: File,
    private val activateImportedVault: suspend (
        snapshot: BackupSnapshotPayloadV1,
        recoveryEnvelope: VaultKeyEnvelope,
        contentKey: VaultKey,
    ) -> Unit,
) {
    private var staged: StagedArchive? = null

    /**
     * Reads [source] to its end, authenticating every frame under [passphrase],
     * and holds the result until [activate] or [abandon]. [passphrase] is
     * always wiped, and any staging this importer already held is discarded
     * first.
     */
    suspend fun stage(source: InputStream, passphrase: CharArray): OtVaultImportPreview = try {
        abandon()
        runStage(source, passphrase)
    } finally {
        passphrase.fill('\u0000')
    }

    /**
     * Replaces the active vault with the staged archive, then promotes the
     * attachment frames this import staged into the live cache.
     *
     * Returns `false` when nothing is staged or the replacement failed, having
     * already discarded everything this import staged. The active vault is
     * restored by the slot replacement itself, which publishes the imported
     * slot only after it has proved that slot opens — the prior slot is the
     * rollback until then, and is released only afterwards.
     *
     * Promotion runs last, and only on success: by then the replaced vault's
     * own cached frames are stale, so displacing them under the cache's
     * ordinary bound is exactly what should happen.
     */
    suspend fun activate(): Boolean {
        val archive = staged ?: return false
        return try {
            activateImportedVault(archive.snapshot, archive.envelope, archive.contentKey)
            staged = null
            try {
                promote(archive)
            } finally {
                // The imported vault owns these bytes now; the staging root and
                // the key material are released either way.
                archive.close()
                clearStagingRoot()
            }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            abandon()
            false
        }
    }

    /**
     * Discards everything [stage] held. The live attachment cache is not
     * touched: nothing this import staged was ever written to it.
     */
    suspend fun abandon() {
        val archive = staged ?: return
        staged = null
        archive.close()
        clearStagingRoot()
    }

    private fun runStage(source: InputStream, passphrase: CharArray): OtVaultImportPreview {
        clearStagingRoot()
        val header = try {
            codec.readHeader(source)
        } catch (_: Exception) {
            return rejectedArchive()
        }
        val contentKey = try {
            crypto.unlock(passphrase, header.envelope)
        } catch (_: Exception) {
            // Nothing after the public header has been read, so no frame has
            // been authenticated and none has been decrypted.
            return OtVaultImportPreview.Rejected(OT_VAULT_IMPORT_PASSPHRASE_REASON)
        }
        val staging = try {
            AttachmentCacheStore(stagingRoot) { stagingRoot.usableSpace }
        } catch (_: Exception) {
            contentKey.close()
            return rejectedArchive()
        }
        val reader = ArchiveReader(contentKey, staging, retentionBudgetBytes())
        return try {
            codec.readAll(source, contentKey, header, reader::accept)
            val snapshot = reader.finish()
            staged = StagedArchive(
                snapshot = snapshot,
                envelope = header.envelope,
                contentKey = contentKey,
                staging = staging,
                retained = reader.retained.toList(),
            )
            OtVaultImportPreview.Ready(
                recordCount = snapshot.records.size,
                attachmentCount = reader.attachmentCount,
                attachmentsBeyondCache = reader.attachmentsBeyondCache.toList(),
            )
        } catch (failure: Throwable) {
            contentKey.close()
            clearStagingRoot()
            if (failure is CancellationException) throw failure
            rejectedArchive()
        }
    }

    /**
     * How many bytes this import may stage.
     *
     * The `min(128 MiB, 5% available storage)` bound is one figure for the
     * whole installation, so an import may claim only what the live cache is
     * not already using: staging beside a full cache would otherwise put twice
     * that bound on disk. A cache that cannot be measured retains nothing
     * rather than guessing in the direction that overshoots.
     */
    private fun retentionBudgetBytes(): Long = try {
        (cache.ceilingBytes() - cache.usageBytes()).coerceAtLeast(0)
    } catch (_: Exception) {
        0L
    }

    /** Moves the staged frames into the live cache, under its ordinary bound. */
    private fun promote(archive: StagedArchive) {
        archive.retained.forEach { retained ->
            repeat(retained.chunkCount) { chunkIndex ->
                val frame = try {
                    archive.staging.read(retained.blobSetId, chunkIndex)
                } catch (_: Exception) {
                    null
                } ?: return@repeat
                try {
                    cache.write(retained.blobSetId, chunkIndex, frame)
                } catch (_: Exception) {
                    // The imported vault is already live; a frame the cache
                    // will not take is simply one it does not hold.
                } finally {
                    frame.fill(0)
                }
            }
        }
    }

    private fun clearStagingRoot() {
        try {
            stagingRoot.deleteRecursively()
        } catch (_: Exception) {
            // A staging root that will not delete holds only opaque ciphertext,
            // and the next import clears it before it stages anything.
        }
    }

    private fun rejectedArchive() =
        OtVaultImportPreview.Rejected(OT_VAULT_IMPORT_FAILED_REASON)

    /**
     * Accumulates one archive as its frames arrive, enforcing the import policy
     * the codec deliberately leaves to its reader.
     *
     * [OtVaultCodec.readAll] proves each frame's own authenticity, the
     * inventory, and the header counts. What it does not prove — and what this
     * decides — is that the archive is one complete snapshot-only vault: every
     * blob set the records name has a manifest, every manifest has exactly the
     * chunks it declares, and those chunks reproduce the content the manifest
     * and the attachment record both claim.
     */
    private inner class ArchiveReader(
        private val contentKey: VaultKey,
        private val staging: AttachmentCacheStore,
        private val budgetBytes: Long,
    ) {
        val retained = mutableListOf<RetainedBlobSet>()
        val attachmentsBeyondCache = mutableListOf<String>()
        var attachmentCount = 0
            private set

        private var snapshot: BackupSnapshotPayloadV1? = null
        private var stagedBytes = 0L
        private var pending: PendingBlobSet? = null
        private val completedBlobSets = mutableSetOf<BlobSetId>()

        fun accept(event: OtVaultReadEvent) {
            when (event) {
                is OtVaultReadEvent.Snapshot -> {
                    require(snapshot == null) { "The archive holds more than one snapshot" }
                    snapshot = event.payload
                }

                is OtVaultReadEvent.Segment ->
                    // Exports are snapshot-only baselines; an archive claiming
                    // operations describes a vault this reader cannot rebuild
                    // faithfully, so it is refused rather than truncated.
                    throw IllegalArgumentException("The archive holds an operation segment")

                is OtVaultReadEvent.AttachmentManifest -> beginBlobSet(event.manifest)

                is OtVaultReadEvent.AttachmentChunk -> acceptChunk(event)
            }
        }

        /** Proves the whole archive once its last frame has been delivered. */
        fun finish(): BackupSnapshotPayloadV1 {
            completePendingBlobSet()
            val payload = requireNotNull(snapshot) { "The archive holds no snapshot" }
            // Every live attachment the records name must have arrived: an
            // archive is the only copy of the bytes it carries, so one that
            // omits a blob set is incomplete rather than merely smaller.
            val archived = payload.records
                .filter { it.family == BackupRecordFamily.ATTACHMENT }
                .filter { it.field("deletedAtEpochMillis") == null }
                .mapNotNullTo(mutableSetOf()) { record ->
                    record.field("blobSetId")?.let(::BlobSetId)
                }
            require(archived == completedBlobSets) {
                "The archive attachment inventory does not match its blob sets"
            }
            return payload
        }

        private fun beginBlobSet(manifest: AttachmentBlobSetManifest) {
            completePendingBlobSet()
            require(completedBlobSets.add(manifest.blobSetId)) {
                "The archive repeats an attachment manifest"
            }
            attachmentCount += 1
            val record = attachmentRecordFor(manifest.blobSetId)
            require(record.field("contentHash") == manifest.contentSha256.value) {
                "An archived attachment does not match its manifest content"
            }
            require(record.field("byteCount") == manifest.totalByteCount.toString()) {
                "An archived attachment does not match its manifest length"
            }
            // The chunk count is authenticated by the manifest alone: an
            // archive chunk frame binds the format's fixed 25-chunk domain, not
            // this blob set's real count, so its header is never read for one.
            require(record.field("chunkCount") == manifest.chunks.size.toString()) {
                "An archived attachment does not match its manifest chunk count"
            }
            pending = PendingBlobSet(manifest, displayNameOf(record))
        }

        private fun acceptChunk(event: OtVaultReadEvent.AttachmentChunk) {
            val current = requireNotNull(pending) {
                "The archive holds a chunk before its manifest"
            }
            require(current.manifest.blobSetId == event.blobSetId) {
                "The archive holds a chunk outside its manifest"
            }
            current.accept(event.chunkIndex, event.plaintext)
            if (current.retaining) {
                retain(current, event.chunkIndex, event.plaintext)
            }
        }

        /**
         * Re-encrypts one chunk under the imported vault's own content key and
         * hands it to the attachment cache, or gives up on this blob set.
         *
         * A blob set is retained whole or not at all: the moment one of its
         * frames would push this import past its share of the cache bound,
         * everything already staged for it is dropped and the attachment is
         * named in the preview instead. Bytes are never written in the clear, and the
         * archive manifest's sentinel `ciphertextSha256` and `providerObjectId`
         * are never used — the frames are re-authenticated at an import-scoped
         * identity of this vault, so nothing here can be mistaken for a live
         * provider object or replayed into one.
         *
         * What that identity costs is that
         * [app.opentasks.core.data.backup.AttachmentOpenCoordinator] cannot
         * read these frames: a live open is scoped to a remote lineage, which
         * an imported vault deliberately has none of, and it authenticates a
         * frame against the provider manifest's real ciphertext digest. These
         * are the imported bytes held for the re-upload path a later stage
         * owns, which is why the preview names every attachment they do not
         * cover.
         */
        private fun retain(current: PendingBlobSet, chunkIndex: Int, plaintext: ByteArray) {
            val blobSetId = current.manifest.blobSetId
            val frame = try {
                authenticatedCodec.encrypt(
                    importedChunkIdentity(blobSetId, chunkIndex, current.manifest.chunks.size),
                    plaintext,
                    contentKey,
                )
            } catch (_: Exception) {
                return giveUp(current)
            }
            try {
                if (stagedBytes + frame.size > budgetBytes) return giveUp(current)
                staging.write(blobSetId, chunkIndex, frame)
            } catch (_: Exception) {
                return giveUp(current)
            } finally {
                frame.fill(0)
            }
            stagedBytes += frame.size
            current.retainedFrameBytes += frame.size
            val last = retained.lastOrNull()
            if (last != null && last.blobSetId == blobSetId) {
                last.chunkCount = chunkIndex + 1
            } else {
                retained += RetainedBlobSet(blobSetId, chunkIndex + 1)
            }
        }

        private fun giveUp(current: PendingBlobSet) {
            current.retaining = false
            val blobSetId = current.manifest.blobSetId
            // Everything already staged for this blob set is about to go, so
            // the budget it consumed returns to the attachments still to come.
            stagedBytes -= current.retainedFrameBytes
            current.retainedFrameBytes = 0
            if (retained.lastOrNull()?.blobSetId == blobSetId) {
                retained.removeAt(retained.lastIndex)
                try {
                    staging.evict(blobSetId)
                } catch (_: Exception) {
                    // The whole staging root is deleted on every outcome, so a
                    // frame this cannot remove is still never promoted.
                }
            }
            attachmentsBeyondCache += current.displayName
        }

        private fun completePendingBlobSet() {
            val current = pending ?: return
            pending = null
            current.requireComplete()
        }

        private fun attachmentRecordFor(blobSetId: BlobSetId): BackupRecordV1 {
            val payload = requireNotNull(snapshot) {
                "The archive holds an attachment manifest before its snapshot"
            }
            val matches = payload.records
                .filter { it.family == BackupRecordFamily.ATTACHMENT }
                .filter { it.field("blobSetId") == blobSetId.value }
                .filter { it.field("deletedAtEpochMillis") == null }
            require(matches.size == 1) {
                "The archive holds a blob set no live attachment names"
            }
            return matches.single()
        }
    }

    /** One blob set the archive is still delivering chunks for. */
    private class PendingBlobSet(
        val manifest: AttachmentBlobSetManifest,
        val displayName: String,
    ) {
        var retaining = true

        /** What this blob set has already spent of the cache budget. */
        var retainedFrameBytes = 0L

        private val digest = MessageDigest.getInstance("SHA-256")
        private var nextIndex = 0

        fun accept(chunkIndex: Int, plaintext: ByteArray) {
            require(chunkIndex == nextIndex) {
                "The archive delivers a blob set's chunks out of order"
            }
            val declared = manifest.chunks.getOrNull(chunkIndex)
            requireNotNull(declared) { "The archive holds a chunk its manifest does not declare" }
            require(declared.plaintextByteCount == plaintext.size) {
                "An archived chunk is not the length its manifest declares"
            }
            digest.update(plaintext)
            nextIndex += 1
        }

        fun requireComplete() {
            require(nextIndex == manifest.chunks.size) {
                "The archive is missing a chunk its manifest declares"
            }
            require(digest.digest().toHex() == manifest.contentSha256.value) {
                "An archived attachment does not reproduce its manifest content"
            }
        }
    }

    /**
     * Everything one confirmed import needs, held between the preview and the
     * confirmation. [contentKey] is the archive's own key: closing it is the
     * last thing either outcome does.
     */
    private class StagedArchive(
        val snapshot: BackupSnapshotPayloadV1,
        val envelope: VaultKeyEnvelope,
        val contentKey: VaultKey,
        val staging: AttachmentCacheStore,
        val retained: List<RetainedBlobSet>,
    ) {
        fun close() = contentKey.close()
    }

    /** One blob set fully staged, and how many chunks of it are there. */
    private class RetainedBlobSet(
        val blobSetId: BlobSetId,
        var chunkCount: Int,
    )

    private companion object {
        /**
         * The identity every retained chunk frame is authenticated at.
         *
         * It is neither a live provider chunk identity (which is scoped to a
         * remote lineage this imported vault has none of) nor an archive chunk
         * identity, so a retained frame can be confused with neither.
         */
        fun importedChunkIdentity(
            blobSetId: BlobSetId,
            chunkIndex: Int,
            chunkCount: Int,
        ) = CloudHeaderIdentity(
            family = CloudObjectFamily.ATTACHMENT_CHUNK,
            schemaVersion = 1,
            cryptoVersion = 1,
            minimumReaderVersion = 1,
            vaultId = IMPORTED_CHUNK_VAULT,
            objectId = "otvault-import:attachment-chunk:${blobSetId.value}",
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
        )

        const val IMPORTED_CHUNK_VAULT = "otvault-import"

        /**
         * Attachment display names are held as UTF-8 bytes in
         * `displayNameCiphertext`, exactly as `AttachmentBlobCoordinator` and
         * the entity mappers already read them.
         */
        fun displayNameOf(record: BackupRecordV1): String {
            val encoded = record.field("displayNameCiphertext") ?: return ""
            return try {
                Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                ""
            }
        }

        /** The value of [name], or null when the record holds it as null. */
        fun BackupRecordV1.field(name: String): String? =
            fields.firstOrNull { it.name == name }
                ?.takeIf { it.type != BackupFieldType.NULL }
                ?.value

        fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** The archive opened, but not with the passphrase the person supplied. */
internal const val OT_VAULT_IMPORT_PASSPHRASE_REASON =
    "That passphrase does not open this vault archive."

/**
 * The one generic UK copy every other import refusal surfaces. It never
 * distinguishes a truncated archive from a corrupt, incomplete, or unsupported
 * one, so it never risks describing what an archive holds.
 */
internal const val OT_VAULT_IMPORT_FAILED_REASON =
    "This file is not a vault archive this version of Open Tasks can import."

/** The vault could not be replaced; this device still holds the vault it had. */
internal const val OT_VAULT_IMPORT_ACTIVATION_FAILED_REASON =
    "The vault could not be replaced. This device still holds the vault it had."
