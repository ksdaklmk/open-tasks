package app.opentasks.core.data.backup

import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.db.TagEntity
import app.opentasks.core.data.db.WorkflowStatusEntity
import app.opentasks.core.domain.AttachmentBlobSetManifest
import app.opentasks.core.domain.AttachmentChunkRef
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.sync.CloudBounds
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OtVaultCodecTest {
    private val crypto = TinkVaultCrypto()
    private val codec = OtVaultCodec(DefaultAuthenticatedCloudObjectCodec(crypto))

    @Test
    fun smallArchiveRoundTripsEveryObjectInOrder() {
        val key = crypto.createKey()
        try {
            val archive = writeArchive(key, header())
            val events = readArchive(archive.bytes, key)

            assertEquals(5, events.size)
            val snapshot = events[0] as OtVaultReadEvent.Snapshot
            assertEquals(snapshot(), snapshot.payload)
            val segment = events[1] as OtVaultReadEvent.Segment
            assertEquals(segment(), segment.payload)
            val manifest = events[2] as OtVaultReadEvent.AttachmentManifest
            assertEquals(manifest(), manifest.manifest)
            val first = events[3] as OtVaultReadEvent.AttachmentChunk
            val second = events[4] as OtVaultReadEvent.AttachmentChunk
            assertEquals(BLOB_SET, first.blobSetId)
            assertEquals(BLOB_SET, second.blobSetId)
            assertEquals(0, first.chunkIndex)
            assertEquals(1, second.chunkIndex)
            assertEquals(
                listOf(
                    "otvault:snapshot:53" to CloudObjectFamily.SNAPSHOT.name,
                    "otvault:segment:54:54" to CloudObjectFamily.OPERATION_SEGMENT.name,
                    "otvault:attachment-manifest:blob-set-1" to CloudObjectFamily.MANIFEST.name,
                    "otvault:attachment-chunk:blob-set-1:0" to
                        CloudObjectFamily.ATTACHMENT_CHUNK.name,
                    "otvault:attachment-chunk:blob-set-1:1" to
                        CloudObjectFamily.ATTACHMENT_CHUNK.name,
                ),
                archive.entries.map { it.objectId to it.family },
            )
            assertEquals(
                archive.entries.map(OtVaultInventoryEntryV1::sha256),
                frameDigests(archive),
            )
        } finally {
            key.close()
        }
    }

    @Test
    fun deliveredAndWrittenChunkBuffersAreCleared() {
        val key = crypto.createKey()
        try {
            val written = chunkPlaintexts().first()
            val destination = ByteArrayOutputStream()
            val header = header()
            codec.writeHeader(destination, header)
            codec.writeAttachmentChunk(destination, key, header, BLOB_SET, 0, written)
            assertTrue(written.all { it == 0.toByte() })

            val archive = writeArchive(key, header)
            val delivered = mutableListOf<ByteArray>()
            val input = ByteArrayInputStream(archive.bytes)
            codec.readAll(input, key, codec.readHeader(input)) { event ->
                if (event is OtVaultReadEvent.AttachmentChunk) {
                    assertTrue(event.plaintext.any { it != 0.toByte() })
                    delivered += event.plaintext
                }
            }

            assertEquals(2, delivered.size)
            delivered.forEach { buffer -> assertTrue(buffer.all { it == 0.toByte() }) }
        } finally {
            key.close()
        }
    }

    @Test
    fun flippedCiphertextByteIsRejectedBeforeItsEventIsDelivered() {
        val key = crypto.createKey()
        try {
            val archive = writeArchive(key, header())
            val snapshotFrameEnd = archive.headerBlockLength + 4 +
                archive.entries.first().byteCount.toInt()
            val tampered = archive.bytes.copyOf()
            tampered[snapshotFrameEnd - 1] = (tampered[snapshotFrameEnd - 1] + 1).toByte()

            val events = mutableListOf<OtVaultReadEvent>()
            val input = ByteArrayInputStream(tampered)
            val header = codec.readHeader(input)
            assertThrows(OtVaultFormatException::class.java) {
                codec.readAll(input, key, header) { events += it }
            }

            assertTrue(events.isEmpty())
        } finally {
            key.close()
        }
    }

    @Test
    fun truncatedFinalFrameIsRejectedAfterItsPredecessors() {
        val key = crypto.createKey()
        try {
            val archive = writeArchive(key, header())
            val truncated = archive.bytes.copyOf(archive.bytes.size - 8)

            val events = mutableListOf<OtVaultReadEvent>()
            val input = ByteArrayInputStream(truncated)
            val header = codec.readHeader(input)
            assertThrows(OtVaultFormatException::class.java) {
                codec.readAll(input, key, header) { events += it }
            }

            assertEquals(5, events.size)
        } finally {
            key.close()
        }
    }

    @Test
    fun archiveWithoutAnInventoryIsRejected() {
        val key = crypto.createKey()
        try {
            val archive = writeArchive(key, header())
            val inventoryStart = archive.bytes.size - inventoryFrameLength(archive)
            val withoutInventory = archive.bytes.copyOf(inventoryStart)

            val input = ByteArrayInputStream(withoutInventory)
            val header = codec.readHeader(input)
            assertThrows(OtVaultFormatException::class.java) {
                codec.readAll(input, key, header) { }
            }
        } finally {
            key.close()
        }
    }

    @Test
    fun oversizedHeaderIsRejectedBeforeItsBodyIsRead() {
        val declared = OtVaultCodec.MAX_HEADER_BYTES + 1
        val source = CountingInputStream(
            OtVaultCodec.MAGIC.toByteArray(Charsets.UTF_8) +
                intBytes(OtVaultCodec.FORMAT_VERSION) +
                intBytes(declared) +
                ByteArray(64),
        )

        assertThrows(OtVaultFormatException::class.java) { codec.readHeader(source) }

        assertEquals(
            (OtVaultCodec.MAGIC.length + 8).toLong(),
            source.bytesRead,
        )
    }

    @Test
    fun wrongMagicIsRejected() {
        val key = crypto.createKey()
        try {
            val archive = writeArchive(key, header())
            val tampered = archive.bytes.copyOf()
            "OPEN_TASKS_OTHER".toByteArray(Charsets.UTF_8).copyInto(tampered)

            assertThrows(OtVaultFormatException::class.java) {
                codec.readHeader(ByteArrayInputStream(tampered))
            }
        } finally {
            key.close()
        }
    }

    @Test
    fun newerFormatVersionIsRefusedBeforeTheHeaderBodyIsRead() {
        val key = crypto.createKey()
        try {
            val archive = writeArchive(key, header())
            val tampered = archive.bytes.copyOf()
            intBytes(2).copyInto(tampered, OtVaultCodec.MAGIC.length)
            val source = CountingInputStream(tampered)

            assertThrows(OtVaultFormatException::class.java) { codec.readHeader(source) }

            assertEquals((OtVaultCodec.MAGIC.length + 4).toLong(), source.bytesRead)
        } finally {
            key.close()
        }
    }

    @Test
    fun inventoryDigestMismatchIsRejected() {
        val key = crypto.createKey()
        try {
            val header = header()
            val destination = ByteArrayOutputStream()
            codec.writeHeader(destination, header)
            val entries = writeObjects(destination, key, header)
            codec.writeInventory(
                destination,
                key,
                header,
                entries.mapIndexed { index, entry ->
                    if (index == 0) entry.copy(sha256 = ZERO_SHA256) else entry
                },
            )
            val archive = destination.toByteArray()

            val input = ByteArrayInputStream(archive)
            val read = codec.readHeader(input)
            assertThrows(OtVaultFormatException::class.java) {
                codec.readAll(input, key, read) { }
            }
        } finally {
            key.close()
        }
    }

    @Test
    fun headerCountTamperingIsRejectedByTheInventory() {
        val key = crypto.createKey()
        try {
            val archive = writeArchive(key, header())
            val replacement = ByteArrayOutputStream()
            codec.writeHeader(replacement, header().copy(recordCount = SNAPSHOT_RECORDS + 1))
            val tampered = replacement.toByteArray() +
                archive.bytes.copyOfRange(archive.headerBlockLength, archive.bytes.size)

            val input = ByteArrayInputStream(tampered)
            val header = codec.readHeader(input)
            assertEquals(SNAPSHOT_RECORDS + 1, header.recordCount)
            assertThrows(OtVaultFormatException::class.java) {
                codec.readAll(input, key, header) { }
            }
        } finally {
            key.close()
        }
    }

    @Test
    fun objectOutsideTheArchiveNamespaceIsRejected() {
        val key = crypto.createKey()
        try {
            val header = header()
            val destination = ByteArrayOutputStream()
            codec.writeHeader(destination, header)
            val entries = writeObjects(destination, key, header)
            val foreign = DefaultAuthenticatedCloudObjectCodec(crypto).encrypt(
                CloudHeaderIdentity(
                    family = CloudObjectFamily.SNAPSHOT,
                    schemaVersion = 1,
                    cryptoVersion = 1,
                    minimumReaderVersion = 1,
                    vaultId = VAULT_ID,
                    objectId = "snapshot:53",
                ),
                BackupSnapshotCodec.encode(snapshot()),
                key,
            )
            destination.write(intBytes(foreign.size))
            destination.write(foreign)
            codec.writeInventory(destination, key, header, entries)

            val input = ByteArrayInputStream(destination.toByteArray())
            val read = codec.readHeader(input)
            assertThrows(OtVaultFormatException::class.java) {
                codec.readAll(input, key, read) { }
            }
        } finally {
            key.close()
        }
    }

    @Test
    fun observedInventoryAccumulationFailsClosedPastItsBound() {
        val key = crypto.createKey()
        try {
            val header = header()
            val destination = ByteArrayOutputStream()
            codec.writeHeader(destination, header)
            val overLimitCount = CloudBounds.MAX_MANIFEST_INVENTORY_ENTRIES + 1
            repeat(overLimitCount) { index ->
                codec.writeAttachmentChunk(
                    destination,
                    key,
                    header,
                    BlobSetId("blob-set-$index"),
                    0,
                    "chunk".toByteArray(Charsets.UTF_8),
                )
            }
            // No inventory frame is written: a hostile archive would keep
            // streaming non-inventory frames past the bound rather than ever
            // closing with a valid one.

            val input = ByteArrayInputStream(destination.toByteArray())
            val read = codec.readHeader(input)
            val failure = assertThrows(OtVaultFormatException::class.java) {
                codec.readAll(input, key, read) { }
            }

            assertEquals(
                "Vault archive inventory accumulation exceeds its bound",
                failure.message,
            )
        } finally {
            key.close()
        }
    }

    @Test
    fun headerEnvelopeUnlocksTheArchiveUnderTheExportPassphrase() {
        val key = crypto.createKey()
        val passphrase = "export passphrase".toCharArray()
        val envelope = crypto.wrapForRecovery(key, passphrase)
        val archive = try {
            writeArchive(key, header().copy(envelope = envelope))
        } finally {
            key.close()
        }

        val input = ByteArrayInputStream(archive.bytes)
        val header = codec.readHeader(input)
        val unlocked = crypto.unlock(passphrase, header.envelope)
        val events = try {
            mutableListOf<OtVaultReadEvent>().also { events ->
                codec.readAll(input, unlocked, header) { events += it }
            }
        } finally {
            unlocked.close()
        }

        assertEquals(5, events.size)
        assertEquals(SNAPSHOT_RECORDS, header.recordCount)
        assertEquals(1, header.attachmentCount)
        assertEquals(65_536, header.envelope.kdf.memoryKiB)
        assertEquals(3, header.envelope.kdf.iterations)
        assertEquals(1, header.envelope.kdf.parallelism)
        assertEquals(16, header.envelope.kdf.salt.size)
    }

    private fun writeArchive(
        key: VaultKey,
        header: OtVaultHeaderV1,
    ): Archive {
        val destination = ByteArrayOutputStream()
        codec.writeHeader(destination, header)
        val headerBlockLength = destination.size()
        val entries = writeObjects(destination, key, header)
        codec.writeInventory(destination, key, header, entries)
        return Archive(destination.toByteArray(), headerBlockLength, entries)
    }

    private fun writeObjects(
        destination: ByteArrayOutputStream,
        key: VaultKey,
        header: OtVaultHeaderV1,
    ): List<OtVaultInventoryEntryV1> = buildList {
        add(codec.writeSnapshot(destination, key, header, snapshot()))
        add(codec.writeSegment(destination, key, header, segment()))
        add(codec.writeAttachmentManifest(destination, key, header, manifest()))
        chunkPlaintexts().forEachIndexed { index, plaintext ->
            add(codec.writeAttachmentChunk(destination, key, header, BLOB_SET, index, plaintext))
        }
    }

    private fun readArchive(archive: ByteArray, key: VaultKey): List<OtVaultReadEvent> {
        val input = ByteArrayInputStream(archive)
        val header = codec.readHeader(input)
        return buildList {
            codec.readAll(input, key, header) { event ->
                add(
                    when (event) {
                        is OtVaultReadEvent.AttachmentChunk ->
                            event.copy(plaintext = event.plaintext.copyOf())
                        else -> event
                    },
                )
            }
        }
    }

    private fun frameDigests(archive: Archive): List<String> {
        var offset = archive.headerBlockLength
        return archive.entries.map { entry ->
            offset += 4
            val frame = archive.bytes.copyOfRange(offset, offset + entry.byteCount.toInt())
            offset += entry.byteCount.toInt()
            sha256(frame)
        }
    }

    private fun inventoryFrameLength(archive: Archive): Int {
        val objectBytes = archive.entries.sumOf { it.byteCount + 4 }
        return archive.bytes.size - archive.headerBlockLength - objectBytes.toInt()
    }

    private fun header(): OtVaultHeaderV1 = OtVaultHeaderV1(
        formatVersion = OtVaultCodec.FORMAT_VERSION,
        vaultId = VaultId(VAULT_ID),
        createdAtEpochMillis = CREATED_AT,
        envelope = VaultKeyEnvelope(
            formatVersion = 1,
            kdf = Argon2Metadata(ByteArray(16) { it.toByte() }),
            nonce = ByteArray(12) { (it + 16).toByte() },
            wrappedKeyset = ByteArray(8) { (it + 28).toByte() },
        ),
        recordCount = SNAPSHOT_RECORDS,
        attachmentCount = 1,
    )

    private fun snapshot(): BackupSnapshotPayloadV1 = BackupSnapshotPayloadV1(
        vaultId = VAULT_ID,
        coveredGeneration = 53,
        records = listOf(
            BackupRecordV1(
                family = BackupRecordFamily.VAULT,
                identity = listOf(VAULT_ID),
                fields = listOf(
                    BackupFieldV1("id", BackupFieldType.STRING, VAULT_ID),
                    BackupFieldV1("createdAtEpochMillis", BackupFieldType.LONG, "1"),
                    BackupFieldV1("schemaVersion", BackupFieldType.INT, "9"),
                    BackupFieldV1("cryptoVersion", BackupFieldType.INT, "1"),
                    BackupFieldV1("minimumReaderVersion", BackupFieldType.INT, "1"),
                ),
            ),
            BackupRecordV1(
                family = BackupRecordFamily.WORKSPACE,
                identity = listOf("workspace-1"),
                fields = listOf(
                    BackupFieldV1("id", BackupFieldType.STRING, "workspace-1"),
                    BackupFieldV1("vaultId", BackupFieldType.STRING, VAULT_ID),
                    BackupFieldV1("ownerId", BackupFieldType.STRING, "member-1"),
                    BackupFieldV1("name", BackupFieldType.STRING, "Workspace"),
                ),
            ),
            BackupRecordV1(
                family = BackupRecordFamily.MEMBER,
                identity = listOf("member-1"),
                fields = listOf(
                    BackupFieldV1("id", BackupFieldType.STRING, "member-1"),
                    BackupFieldV1("displayName", BackupFieldType.STRING, "Member"),
                ),
            ),
        ) + SEMANTIC_STATUSES.mapIndexed { index, semantic ->
            WorkflowStatusEntity(
                id = "status-inbox-${semantic.lowercase()}",
                projectId = null,
                name = "Inbox ${semantic.lowercase()}",
                semanticStatus = semantic,
                rank = "inbox-$index",
                archivedAtEpochMillis = null,
                revisionWallMillis = 1,
                revisionLogical = index,
                revisionDeviceId = "device-alpha",
            ).toBackupRecordV1()
        }.sortedBy { record -> record.identity.first() },
    )

    private fun segment(): BackupOperationSegmentPayloadV1 {
        val payload = BackupMutationCodec.encode(
            BackupMutationPayloadV1(
                mutationKind = BackupMutationKind.UPSERT,
                record = TagEntity(
                    id = "tag-1",
                    workspaceId = "workspace-1",
                    name = "Urgent",
                ).toBackupRecordV1(),
                deletedFamily = null,
                deletedIdentity = null,
            ),
        )
        val entry = BackupSegmentEntryV1(
            operationId = "operation-1",
            generation = 54,
            sequence = 0,
            objectId = "tag-1",
            objectType = BackupRecordFamily.TAG.name,
            revisionWallMillis = 54,
            revisionLogical = 0,
            sourceDeviceId = "device-alpha",
            payloadBase64 = Base64.getEncoder().withoutPadding().encodeToString(payload),
        )
        return BackupOperationSegmentPayloadV1(
            vaultId = VAULT_ID,
            firstGeneration = 54,
            lastGeneration = 54,
            entries = listOf(entry),
            entryCount = 1,
        )
    }

    private fun manifest(): AttachmentBlobSetManifest {
        val plaintexts = chunkPlaintexts()
        return AttachmentBlobSetManifest(
            blobSetId = BLOB_SET,
            contentSha256 = Sha256Digest.of(sha256(plaintexts[0] + plaintexts[1])),
            totalByteCount = plaintexts.sumOf { it.size }.toLong(),
            chunks = plaintexts.mapIndexed { index, plaintext ->
                AttachmentChunkRef(
                    index = index,
                    providerObjectId = ProviderObjectId.of("provider-chunk-$index"),
                    ciphertextSha256 = Sha256Digest.of(("%02x".format(index + 10)).repeat(32)),
                    plaintextByteCount = plaintext.size,
                )
            },
        )
    }

    private fun chunkPlaintexts(): List<ByteArray> = listOf(
        "otvault-chunk-zero".toByteArray(Charsets.UTF_8),
        "otvault-chunk-one!".toByteArray(Charsets.UTF_8),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private fun intBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).putInt(value).array()

    private class Archive(
        val bytes: ByteArray,
        val headerBlockLength: Int,
        val entries: List<OtVaultInventoryEntryV1>,
    )

    private class CountingInputStream(
        private val bytes: ByteArray,
    ) : InputStream() {
        var bytesRead: Long = 0
            private set
        private var offset = 0

        override fun read(): Int {
            if (offset == bytes.size) return -1
            bytesRead += 1
            return bytes[offset++].toInt() and 0xff
        }

        override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
            if (offset == bytes.size) return -1
            val count = minOf(length, bytes.size - offset)
            bytes.copyInto(target, targetOffset, offset, offset + count)
            offset += count
            bytesRead += count
            return count
        }
    }

    private companion object {
        const val VAULT_ID = "vault-alpha"
        const val CREATED_AT = 1_754_000_000_000L
        const val SNAPSHOT_RECORDS = 8
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
        val BLOB_SET = BlobSetId("blob-set-1")
        val SEMANTIC_STATUSES =
            listOf("BACKLOG", "PLANNED", "STARTED", "BLOCKED", "COMPLETED")
    }
}
