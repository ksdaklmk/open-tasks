package app.opentasks.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.backup.AttachmentCacheStore
import app.opentasks.core.data.backup.BackupFieldType
import app.opentasks.core.data.backup.BackupFieldV1
import app.opentasks.core.data.backup.BackupRecordFamily
import app.opentasks.core.data.backup.BackupRecordV1
import app.opentasks.core.data.backup.BackupSnapshotPayloadV1
import app.opentasks.core.data.backup.OtVaultCodec
import app.opentasks.core.data.backup.OtVaultHeaderV1
import app.opentasks.core.data.backup.OtVaultInventoryEntryV1
import app.opentasks.core.domain.AttachmentBlobSetManifest
import app.opentasks.core.domain.AttachmentChunkRef
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.sync.CloudHeaderIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtVaultImporterTest {
    private val crypto: VaultCrypto = TinkVaultCrypto()
    private val authenticatedCodec = CountingCloudObjectCodec(
        DefaultAuthenticatedCloudObjectCodec(crypto),
    )
    private val codec = OtVaultCodec(authenticatedCodec)

    @Test
    fun stagePresentsArchiveCountsAndStagesEveryChunkClearOfTheLiveCache() = runBlocking {
        withTimeout(5_000) {
            withRoots(GENEROUS_AVAILABLE_BYTES) { roots ->
                val archive = writeArchive(ATTACHMENTS)
                val passphrase = PASSPHRASE.toCharArray()

                val preview = importer(roots).stage(ByteArrayInputStream(archive), passphrase)

                assertEquals(
                    OtVaultImportPreview.Ready(
                        recordCount = records(ATTACHMENTS).size,
                        attachmentCount = 2,
                        attachmentsBeyondCache = emptyList(),
                    ),
                    preview,
                )
                assertTrue(roots.stagedBytes() > 0)
                // Nothing is confirmed yet, so the live vault's cache is
                // exactly as this import found it.
                assertEquals(0L, roots.liveBytes())
                assertTrue(passphrase.all { it == '\u0000' })
            }
        }
    }

    @Test
    fun stagingNeverDisplacesFramesTheLiveVaultIsUsing() = runBlocking {
        withTimeout(5_000) {
            withRoots(SMALL_AVAILABLE_BYTES) { roots ->
                val live = ByteArray(40_000) { (it % 211).toByte() }
                roots.cache.write(LIVE_BLOB_SET, 0, live.copyOf())

                importer(roots).stage(
                    ByteArrayInputStream(writeArchive(ATTACHMENTS)),
                    PASSPHRASE.toCharArray(),
                )

                assertArrayEquals(live, roots.cache.read(LIVE_BLOB_SET, 0))
                assertEquals(live.size.toLong(), roots.liveBytes())
                assertTrue(roots.stagedBytes() > 0)
            }
        }
    }

    @Test
    fun corruptArchiveIsRejectedAndLeavesNoStagingResidue() = runBlocking {
        withTimeout(5_000) {
            withRoots(SMALL_AVAILABLE_BYTES) { roots ->
                val live = ByteArray(40_000) { (it % 211).toByte() }
                roots.cache.write(LIVE_BLOB_SET, 0, live.copyOf())
                // The inventory is the archive's last frame, so flipping its
                // final byte fails authentication only after every chunk has
                // already been staged.
                val archive = writeArchive(ATTACHMENTS).also { bytes ->
                    bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
                }

                val preview = importer(roots)
                    .stage(ByteArrayInputStream(archive), PASSPHRASE.toCharArray())

                assertTrue(preview is OtVaultImportPreview.Rejected)
                assertEquals(0L, roots.stagedBytes())
                assertArrayEquals(live, roots.cache.read(LIVE_BLOB_SET, 0))
            }
        }
    }

    @Test
    fun wrongPassphraseIsRejectedWithoutDecryptingAnyFrame() = runBlocking {
        withTimeout(5_000) {
            withRoots(GENEROUS_AVAILABLE_BYTES) { roots ->
                val archive = writeArchive(ATTACHMENTS)
                authenticatedCodec.decryptions.set(0)

                val preview = importer(roots)
                    .stage(ByteArrayInputStream(archive), "not the passphrase".toCharArray())

                assertEquals(
                    OtVaultImportPreview.Rejected(OT_VAULT_IMPORT_PASSPHRASE_REASON),
                    preview,
                )
                assertEquals(0, authenticatedCodec.decryptions.get())
                assertEquals(0L, roots.stagedBytes())
            }
        }
    }

    @Test
    fun attachmentsBeyondTheCacheBoundAreNamedInThePreview() = runBlocking {
        withTimeout(5_000) {
            withRoots(SMALL_AVAILABLE_BYTES) { roots ->
                // 5% of 2,000,000 leaves a 100,000 byte ceiling: the small
                // attachment fits inside it, the 200,000 byte one cannot.
                val attachments = listOf(
                    ATTACHMENTS.first(),
                    archiveAttachment(
                        id = "attachment-b",
                        displayName = "plan.pdf",
                        bytes = ByteArray(200_000) { (it % 251).toByte() },
                    ),
                )

                val preview = importer(roots)
                    .stage(ByteArrayInputStream(writeArchive(attachments)), PASSPHRASE.toCharArray())

                assertEquals(
                    OtVaultImportPreview.Ready(
                        recordCount = records(attachments).size,
                        attachmentCount = 2,
                        attachmentsBeyondCache = listOf("plan.pdf"),
                    ),
                    preview,
                )
                assertTrue(roots.stagedBytes() in 1 until 100_000)
            }
        }
    }

    @Test
    fun theRetentionBudgetAccountsForWhatTheLiveCacheAlreadyHolds() = runBlocking {
        withTimeout(5_000) {
            withRoots(SMALL_AVAILABLE_BYTES) { roots ->
                // The live cache is already at the whole installation's bound,
                // so this import may claim none of it.
                roots.cache.write(LIVE_BLOB_SET, 0, ByteArray(100_000) { (it % 211).toByte() })

                val preview = importer(roots)
                    .stage(ByteArrayInputStream(writeArchive(ATTACHMENTS)), PASSPHRASE.toCharArray())

                assertEquals(
                    OtVaultImportPreview.Ready(
                        recordCount = records(ATTACHMENTS).size,
                        attachmentCount = 2,
                        attachmentsBeyondCache = listOf("notes.pdf", "plan.pdf"),
                    ),
                    preview,
                )
                assertEquals(0L, roots.stagedBytes())
                assertEquals(100_000L, roots.liveBytes())
            }
        }
    }

    @Test
    fun anAttachmentGivenUpOnReturnsItsBudgetToTheAttachmentsAfterIt() = runBlocking {
        withTimeout(5_000) {
            withRoots(SMALL_AVAILABLE_BYTES) { roots ->
                // A 100,000 byte ceiling: the first attachment's second chunk
                // pushes it past the bound, and the 80,000 byte attachment
                // after it only fits if the first one's bytes were given back.
                val attachments = listOf(
                    ArchiveAttachment(
                        id = "attachment-big",
                        displayName = "big.pdf",
                        blobSetId = BlobSetId("blob-set-big"),
                        chunks = listOf(
                            ByteArray(60_000) { (it % 251).toByte() },
                            ByteArray(60_000) { (it % 239).toByte() },
                        ),
                    ),
                    archiveAttachment(
                        id = "attachment-fits",
                        displayName = "fits.pdf",
                        bytes = ByteArray(80_000) { (it % 197).toByte() },
                    ),
                )

                val preview = importer(roots)
                    .stage(ByteArrayInputStream(writeArchive(attachments)), PASSPHRASE.toCharArray())

                assertEquals(
                    OtVaultImportPreview.Ready(
                        recordCount = records(attachments).size,
                        attachmentCount = 2,
                        attachmentsBeyondCache = listOf("big.pdf"),
                    ),
                    preview,
                )
                assertTrue(roots.stagedBytes() in 80_000 until 100_000)
            }
        }
    }

    @Test
    fun aTinyCacheBoundRetainsNothingAndNamesEveryAttachment() = runBlocking {
        withTimeout(5_000) {
            withRoots(20L) { roots ->
                val preview = importer(roots)
                    .stage(ByteArrayInputStream(writeArchive(ATTACHMENTS)), PASSPHRASE.toCharArray())

                assertEquals(
                    OtVaultImportPreview.Ready(
                        recordCount = records(ATTACHMENTS).size,
                        attachmentCount = 2,
                        attachmentsBeyondCache = listOf("notes.pdf", "plan.pdf"),
                    ),
                    preview,
                )
                assertEquals(0L, roots.stagedBytes())
            }
        }
    }

    @Test
    fun abandonDiscardsTheStagingRootAndLeavesNothingToActivate() = runBlocking {
        withTimeout(5_000) {
            withRoots(GENEROUS_AVAILABLE_BYTES) { roots ->
                val importer = importer(roots)
                importer.stage(
                    ByteArrayInputStream(writeArchive(ATTACHMENTS)),
                    PASSPHRASE.toCharArray(),
                )

                importer.abandon()

                assertEquals(0L, roots.stagedBytes())
                assertEquals(0L, roots.liveBytes())
                assertFalse(importer.activate())
            }
        }
    }

    @Test
    fun activateHandsTheArchiveSnapshotToTheVaultAndPromotesItsFrames() = runBlocking {
        withTimeout(5_000) {
            withRoots(GENEROUS_AVAILABLE_BYTES) { roots ->
                var activated: BackupSnapshotPayloadV1? = null
                var envelope: VaultKeyEnvelope? = null
                val importer = importer(roots) { snapshot, recoveryEnvelope, _ ->
                    activated = snapshot
                    envelope = recoveryEnvelope
                    // Promotion may only follow a replaced vault, never precede it.
                    assertEquals(0L, roots.liveBytes())
                }
                importer.stage(
                    ByteArrayInputStream(writeArchive(ATTACHMENTS)),
                    PASSPHRASE.toCharArray(),
                )
                val stagedBytes = roots.stagedBytes()

                assertTrue(importer.activate())

                assertEquals(VAULT_ID.value, checkNotNull(activated).vaultId)
                assertEquals(GENERATION, checkNotNull(activated).coveredGeneration)
                assertNotNull(envelope)
                assertEquals(stagedBytes, roots.liveBytes())
                assertEquals(0L, roots.stagedBytes())
            }
        }
    }

    @Test
    fun aFailedActivationReportsFailureAndDiscardsTheStagingRoot() = runBlocking {
        withTimeout(5_000) {
            withRoots(SMALL_AVAILABLE_BYTES) { roots ->
                val live = ByteArray(40_000) { (it % 211).toByte() }
                roots.cache.write(LIVE_BLOB_SET, 0, live.copyOf())
                val importer = importer(roots) { _, _, _ ->
                    error("The staged vault could not be published")
                }
                importer.stage(
                    ByteArrayInputStream(writeArchive(ATTACHMENTS)),
                    PASSPHRASE.toCharArray(),
                )

                assertFalse(importer.activate())

                assertEquals(0L, roots.stagedBytes())
                assertArrayEquals(live, roots.cache.read(LIVE_BLOB_SET, 0))
            }
        }
    }

    @Test
    fun anArchiveMissingAChunkItsManifestDeclaresIsRejected() = runBlocking {
        withTimeout(5_000) {
            withRoots(GENEROUS_AVAILABLE_BYTES) { roots ->
                val archive = writeArchive(
                    attachments = ATTACHMENTS,
                    omitChunksOf = ATTACHMENTS.last().blobSetId,
                )

                val preview = importer(roots)
                    .stage(ByteArrayInputStream(archive), PASSPHRASE.toCharArray())

                assertEquals(
                    OtVaultImportPreview.Rejected(OT_VAULT_IMPORT_FAILED_REASON),
                    preview,
                )
                assertEquals(0L, roots.stagedBytes())
            }
        }
    }

    // ------------------------------------------------------------- Fixtures

    private fun importer(
        roots: ImportRoots,
        activate: suspend (BackupSnapshotPayloadV1, VaultKeyEnvelope, VaultKey) -> Unit =
            { _, _, _ -> },
    ) = OtVaultImporter(
        codec = codec,
        authenticatedCodec = authenticatedCodec,
        crypto = crypto,
        cache = roots.cache,
        stagingRoot = roots.stagingRoot,
        activateImportedVault = activate,
    )

    /**
     * Writes one `.otvault` archive with the frozen Task 5 codec, optionally
     * leaving out one blob set's chunk frames so the importer's own
     * manifest-to-chunk completeness rule can be exercised.
     */
    private fun writeArchive(
        attachments: List<ArchiveAttachment>,
        omitChunksOf: BlobSetId? = null,
    ): ByteArray {
        val records = records(attachments)
        val key = crypto.createKey()
        return try {
            val header = OtVaultHeaderV1(
                formatVersion = OtVaultCodec.FORMAT_VERSION,
                vaultId = VAULT_ID,
                createdAtEpochMillis = 1_700_000_000_000,
                envelope = crypto.wrapForRecovery(key, PASSPHRASE.toCharArray()),
                recordCount = records.size,
                attachmentCount = attachments.size,
            )
            val destination = ByteArrayOutputStream()
            codec.writeHeader(destination, header)
            val entries = mutableListOf<OtVaultInventoryEntryV1>()
            entries += codec.writeSnapshot(
                destination,
                key,
                header,
                BackupSnapshotPayloadV1(
                    vaultId = VAULT_ID.value,
                    coveredGeneration = GENERATION,
                    records = records,
                ),
            )
            attachments.forEach { attachment ->
                entries += codec.writeAttachmentManifest(
                    destination,
                    key,
                    header,
                    attachment.manifest(),
                )
                if (attachment.blobSetId == omitChunksOf) return@forEach
                attachment.chunks.forEachIndexed { index, chunk ->
                    entries += codec.writeAttachmentChunk(
                        destination,
                        key,
                        header,
                        attachment.blobSetId,
                        index,
                        chunk.copyOf(),
                    )
                }
            }
            codec.writeInventory(destination, key, header, entries)
            destination.toByteArray()
        } finally {
            key.close()
        }
    }

    /**
     * The live attachment cache and the import staging root, on separate
     * directories exactly as the product wires them.
     */
    private class ImportRoots(parent: File, availableBytes: Long) {
        val stagingRoot: File = parent.resolve("import-staging")
        val cache = AttachmentCacheStore(parent.resolve("cache").also(File::mkdirs)) {
            availableBytes
        }

        private val cacheRoot: File = parent.resolve("cache")

        /** Bytes the live cache holds, read from disk rather than from the store. */
        fun liveBytes(): Long = frameBytes(cacheRoot)

        /** Bytes an unconfirmed import has staged, clear of the live cache. */
        fun stagedBytes(): Long = frameBytes(stagingRoot)

        private fun frameBytes(root: File): Long =
            root.walkTopDown().filter(File::isFile).sumOf(File::length)
    }

    private inline fun withRoots(availableBytes: Long, block: (ImportRoots) -> Unit) {
        val parent = Files.createTempDirectory("otvault-import-test").toFile()
        try {
            block(ImportRoots(parent, availableBytes))
        } finally {
            parent.deleteRecursively()
        }
    }

    /** One attachment as the archive carries it: a blob set and its chunks. */
    private class ArchiveAttachment(
        val id: String,
        val displayName: String,
        val blobSetId: BlobSetId,
        val chunks: List<ByteArray>,
    ) {
        val byteCount: Long = chunks.sumOf { it.size.toLong() }

        val contentHash: String = sha256(chunks.fold(ByteArray(0)) { all, chunk -> all + chunk })

        /**
         * The sentinel provider identity and ciphertext digest every archive
         * manifest carries, matching what `OtVaultExporter` writes.
         */
        fun manifest(): AttachmentBlobSetManifest = AttachmentBlobSetManifest(
            blobSetId = blobSetId,
            contentSha256 = Sha256Digest.of(contentHash),
            totalByteCount = byteCount,
            chunks = chunks.mapIndexed { index, chunk ->
                AttachmentChunkRef(
                    index = index,
                    providerObjectId = ProviderObjectId.of(
                        "otvault:archive-manifest-sentinel",
                    ),
                    ciphertextSha256 = Sha256Digest.of(ZERO_SHA256),
                    plaintextByteCount = chunk.size,
                )
            },
        )
    }

    /** Counts every frame this codec is asked to authenticate. */
    private class CountingCloudObjectCodec(
        private val delegate: AuthenticatedCloudObjectCodec,
    ) : AuthenticatedCloudObjectCodec {
        val decryptions = AtomicInteger()

        override fun encrypt(
            identity: CloudHeaderIdentity,
            plaintext: ByteArray,
            key: VaultKey,
        ): ByteArray = delegate.encrypt(identity, plaintext, key)

        override fun decrypt(
            source: InputStream,
            totalLength: Long,
            key: VaultKey,
        ): CloudDecodeResult {
            decryptions.incrementAndGet()
            return delegate.decrypt(source, totalLength, key)
        }
    }

    private companion object {
        val VAULT_ID = VaultId("vault-import-test")
        const val PASSPHRASE = "correct horse battery staple"
        const val GENERATION = 12L
        const val GENEROUS_AVAILABLE_BYTES = 200_000_000L

        /** 5% of this is a 100,000 byte cache ceiling. */
        const val SMALL_AVAILABLE_BYTES = 2_000_000L
        val LIVE_BLOB_SET = BlobSetId("blob-set-already-cached")
        const val WORKSPACE_ID = "workspace-1"
        const val MEMBER_ID = "member-1"
        const val TASK_ID = "task-1"
        const val DEVICE_ID = "device-alpha"
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"

        val ATTACHMENTS = listOf(
            archiveAttachment("attachment-a", "notes.pdf", "hello there".toByteArray()),
            archiveAttachment("attachment-b", "plan.pdf", "goodbye now".toByteArray()),
        )

        fun archiveAttachment(
            id: String,
            displayName: String,
            bytes: ByteArray,
        ) = ArchiveAttachment(
            id = id,
            displayName = displayName,
            blobSetId = BlobSetId("blob-set-$id"),
            chunks = listOf(bytes),
        )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
                "%02x".format(byte)
            }

        /**
         * The smallest vault state `BackupSnapshotCodec` accepts that can also
         * own attachments: one vault, one workspace and its owner, an inbox
         * workflow covering every semantic status, one task, and one attachment
         * record per archived blob set. `BackupRecordV1` construction helpers
         * are `internal` to `core:data`, so the fields are spelled out here in
         * the exact schema order that module enforces.
         */
        fun records(attachments: List<ArchiveAttachment>): List<BackupRecordV1> = buildList {
            add(
                BackupRecordV1(
                    family = BackupRecordFamily.VAULT,
                    identity = listOf(VAULT_ID.value),
                    fields = listOf(
                        BackupFieldV1("id", BackupFieldType.STRING, VAULT_ID.value),
                        BackupFieldV1("createdAtEpochMillis", BackupFieldType.LONG, "1"),
                        BackupFieldV1("schemaVersion", BackupFieldType.INT, "7"),
                        BackupFieldV1("cryptoVersion", BackupFieldType.INT, "1"),
                        BackupFieldV1("minimumReaderVersion", BackupFieldType.INT, "1"),
                    ),
                ),
            )
            add(
                BackupRecordV1(
                    family = BackupRecordFamily.WORKSPACE,
                    identity = listOf(WORKSPACE_ID),
                    fields = listOf(
                        BackupFieldV1("id", BackupFieldType.STRING, WORKSPACE_ID),
                        BackupFieldV1("vaultId", BackupFieldType.STRING, VAULT_ID.value),
                        BackupFieldV1("ownerId", BackupFieldType.STRING, MEMBER_ID),
                        BackupFieldV1("name", BackupFieldType.STRING, "Workspace"),
                    ),
                ),
            )
            add(
                BackupRecordV1(
                    family = BackupRecordFamily.MEMBER,
                    identity = listOf(MEMBER_ID),
                    fields = listOf(
                        BackupFieldV1("id", BackupFieldType.STRING, MEMBER_ID),
                        BackupFieldV1("displayName", BackupFieldType.STRING, "Member"),
                    ),
                ),
            )
            SEMANTIC_STATUSES.forEachIndexed { index, semantic ->
                add(
                    BackupRecordV1(
                        family = BackupRecordFamily.WORKFLOW_STATUS,
                        identity = listOf(statusId(semantic)),
                        fields = listOf(
                            BackupFieldV1("id", BackupFieldType.STRING, statusId(semantic)),
                            BackupFieldV1("projectId", BackupFieldType.NULL, null),
                            BackupFieldV1(
                                "name",
                                BackupFieldType.STRING,
                                "Inbox ${semantic.lowercase()}",
                            ),
                            BackupFieldV1("semanticStatus", BackupFieldType.STRING, semantic),
                            BackupFieldV1("rank", BackupFieldType.STRING, "inbox-$index"),
                            BackupFieldV1("archivedAtEpochMillis", BackupFieldType.NULL, null),
                            BackupFieldV1("revisionWallMillis", BackupFieldType.LONG, "1"),
                            BackupFieldV1("revisionLogical", BackupFieldType.INT, "$index"),
                            BackupFieldV1("revisionDeviceId", BackupFieldType.STRING, DEVICE_ID),
                        ),
                    ),
                )
            }
            add(
                BackupRecordV1(
                    family = BackupRecordFamily.TASK,
                    identity = listOf(TASK_ID),
                    fields = listOf(
                        BackupFieldV1("id", BackupFieldType.STRING, TASK_ID),
                        BackupFieldV1("workspaceId", BackupFieldType.STRING, WORKSPACE_ID),
                        BackupFieldV1("projectId", BackupFieldType.NULL, null),
                        BackupFieldV1("parentTaskId", BackupFieldType.NULL, null),
                        BackupFieldV1(
                            "statusId",
                            BackupFieldType.STRING,
                            statusId("STARTED"),
                        ),
                        BackupFieldV1("semanticStatus", BackupFieldType.STRING, "STARTED"),
                        BackupFieldV1("title", BackupFieldType.STRING, "Imported task"),
                        BackupFieldV1(
                            "descriptionCiphertext",
                            BackupFieldType.BYTES,
                            base64("described".toByteArray()),
                        ),
                        BackupFieldV1("priority", BackupFieldType.STRING, "MEDIUM"),
                        BackupFieldV1("startEpochMillis", BackupFieldType.NULL, null),
                        BackupFieldV1("startZoneId", BackupFieldType.NULL, null),
                        BackupFieldV1("dueEpochMillis", BackupFieldType.NULL, null),
                        BackupFieldV1("dueZoneId", BackupFieldType.NULL, null),
                        BackupFieldV1("recurrenceFrequency", BackupFieldType.NULL, null),
                        BackupFieldV1("recurrenceInterval", BackupFieldType.NULL, null),
                        BackupFieldV1("recurrenceWeekdays", BackupFieldType.NULL, null),
                        BackupFieldV1("recurrenceCount", BackupFieldType.NULL, null),
                        BackupFieldV1("recurrenceEndDate", BackupFieldType.NULL, null),
                        BackupFieldV1("recurrenceSeriesId", BackupFieldType.NULL, null),
                        BackupFieldV1("recurrenceAnchorEpochMillis", BackupFieldType.NULL, null),
                        BackupFieldV1("recurrenceAnchorZoneId", BackupFieldType.NULL, null),
                        BackupFieldV1("recurrenceOccurrenceIndex", BackupFieldType.NULL, null),
                        BackupFieldV1("estimateSeconds", BackupFieldType.NULL, null),
                        BackupFieldV1("milestoneId", BackupFieldType.NULL, null),
                        BackupFieldV1("completedAtEpochMillis", BackupFieldType.NULL, null),
                        BackupFieldV1("deletedAtEpochMillis", BackupFieldType.NULL, null),
                        BackupFieldV1("revisionWallMillis", BackupFieldType.LONG, "1"),
                        BackupFieldV1("revisionLogical", BackupFieldType.INT, "0"),
                        BackupFieldV1("revisionDeviceId", BackupFieldType.STRING, DEVICE_ID),
                    ),
                ),
            )
            attachments.forEach { attachment ->
                add(
                    BackupRecordV1(
                        family = BackupRecordFamily.ATTACHMENT,
                        identity = listOf(attachment.id),
                        fields = listOf(
                            BackupFieldV1("id", BackupFieldType.STRING, attachment.id),
                            BackupFieldV1("taskId", BackupFieldType.STRING, TASK_ID),
                            BackupFieldV1(
                                "displayNameCiphertext",
                                BackupFieldType.BYTES,
                                base64(attachment.displayName.toByteArray()),
                            ),
                            BackupFieldV1("mimeType", BackupFieldType.STRING, "application/pdf"),
                            BackupFieldV1(
                                "byteCount",
                                BackupFieldType.LONG,
                                attachment.byteCount.toString(),
                            ),
                            BackupFieldV1(
                                "contentHash",
                                BackupFieldType.STRING,
                                attachment.contentHash,
                            ),
                            BackupFieldV1(
                                "blobSetId",
                                BackupFieldType.STRING,
                                attachment.blobSetId.value,
                            ),
                            BackupFieldV1(
                                "chunkCount",
                                BackupFieldType.INT,
                                attachment.chunks.size.toString(),
                            ),
                            BackupFieldV1("deletedAtEpochMillis", BackupFieldType.NULL, null),
                            BackupFieldV1("revisionWallMillis", BackupFieldType.LONG, "1"),
                            BackupFieldV1("revisionLogical", BackupFieldType.INT, "0"),
                            BackupFieldV1(
                                "revisionDeviceId",
                                BackupFieldType.STRING,
                                DEVICE_ID,
                            ),
                        ),
                    ),
                )
            }
        }

        val SEMANTIC_STATUSES =
            listOf("BACKLOG", "PLANNED", "STARTED", "BLOCKED", "COMPLETED")

        fun statusId(semantic: String) = "status-inbox-${semantic.lowercase()}"

        fun base64(bytes: ByteArray): String =
            Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }
}
