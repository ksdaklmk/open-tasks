package app.opentasks.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.backup.BackupFieldType
import app.opentasks.core.data.backup.BackupFieldV1
import app.opentasks.core.data.backup.BackupRecordFamily
import app.opentasks.core.data.backup.BackupRecordV1
import app.opentasks.core.data.backup.OtVaultCodec
import app.opentasks.core.data.backup.OtVaultFormatException
import app.opentasks.core.data.backup.OtVaultReadEvent
import app.opentasks.core.data.backup.StructuredBackupCapture
import app.opentasks.core.domain.BackupCaptureSource
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.ActiveTimerSnapshot
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WorkspaceSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OtVaultExporterTest {
    private val crypto: VaultCrypto = TinkVaultCrypto()
    private val codec = OtVaultCodec(DefaultAuthenticatedCloudObjectCodec(crypto))

    @Test
    fun fullExportRoundTripsThroughReadAllWithMatchingCounts() = runBlocking {
        withTimeout(5_000) {
            val firstBytes = "hello there".toByteArray()
            val secondBytes = "goodbye now".toByteArray()
            val first = attachment(id = "attachment-a", displayName = "a.pdf", bytes = firstBytes)
            val second = attachment(id = "attachment-b", displayName = "b.pdf", bytes = secondBytes)
            val content = mapOf(first.id to firstBytes, second.id to secondBytes)
            val destination = ByteArrayOutputStream()
            val passphrase = "correct horse battery staple".toCharArray()
            val passphraseCopy = passphrase.copyOf()
            val exporter = exporter(
                attachments = listOf(first, second),
                readChunks = fakeChunkReader(content, unfetchable = emptySet()),
            )

            val result = exporter.export(destination, passphrase)

            assertTrue(passphrase.all { it == '\u0000' })
            assertTrue(result is OtVaultExportResult.Completed)
            val completed = result as OtVaultExportResult.Completed
            assertEquals(2, completed.attachmentCount)
            assertEquals(destination.size().toLong(), completed.byteCount)

            val input = ByteArrayInputStream(destination.toByteArray())
            val header = codec.readHeader(input)
            assertEquals(2, header.attachmentCount)
            assertEquals(RECORDS.size, header.recordCount)
            val key = crypto.unlock(passphraseCopy, header.envelope)
            val events = try {
                buildList {
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
            } finally {
                key.close()
            }

            val snapshots = events.filterIsInstance<OtVaultReadEvent.Snapshot>()
            assertEquals(1, snapshots.size)
            assertEquals(RECORDS.size, snapshots.single().payload.records.size)
            val manifests = events.filterIsInstance<OtVaultReadEvent.AttachmentManifest>()
            assertEquals(2, manifests.size)
            val chunks = events.filterIsInstance<OtVaultReadEvent.AttachmentChunk>()
            assertEquals(2, chunks.size)
            val decodedDigests = chunks.map { chunk -> sha256(chunk.plaintext) }.toSet()
            val expectedDigests = content.values.map(::sha256).toSet()
            assertEquals(expectedDigests, decodedDigests)
        }
    }

    @Test
    fun zeroAttachmentExportRoundTripsCleanly() = runBlocking {
        withTimeout(5_000) {
            val destination = ByteArrayOutputStream()
            val passphrase = "correct horse battery staple".toCharArray()
            val passphraseCopy = passphrase.copyOf()
            val exporter = exporter(
                attachments = emptyList(),
                readChunks = fakeChunkReader(emptyMap(), unfetchable = emptySet()),
            )

            val result = exporter.export(destination, passphrase)

            assertTrue(result is OtVaultExportResult.Completed)
            val completed = result as OtVaultExportResult.Completed
            assertEquals(0, completed.attachmentCount)
            assertEquals(destination.size().toLong(), completed.byteCount)

            val input = ByteArrayInputStream(destination.toByteArray())
            val header = codec.readHeader(input)
            assertEquals(0, header.attachmentCount)
            assertEquals(RECORDS.size, header.recordCount)
            val key = crypto.unlock(passphraseCopy, header.envelope)
            val events = try {
                buildList {
                    codec.readAll(input, key, header) { event -> add(event) }
                }
            } finally {
                key.close()
            }

            assertEquals(1, events.size)
            val snapshot = events.single() as OtVaultReadEvent.Snapshot
            assertEquals(RECORDS.size, snapshot.payload.records.size)
        }
    }

    @Test
    fun unfetchableAttachmentYieldsMissingBytesAndWritesNothing() = runBlocking {
        withTimeout(5_000) {
            val bytes = "content".toByteArray()
            val fetchable = attachment(id = "attachment-fetchable", displayName = "fine.pdf", bytes = bytes)
            val stuck = attachment(id = "attachment-stuck", displayName = "stuck.pdf", bytes = bytes)
            val content = mapOf(fetchable.id to bytes, stuck.id to bytes)
            val destination = ByteArrayOutputStream()
            val exporter = exporter(
                attachments = listOf(fetchable, stuck),
                readChunks = fakeChunkReader(content, unfetchable = setOf(stuck.id)),
            )

            val result = exporter.export(destination, "correct horse battery staple".toCharArray())

            assertEquals(
                OtVaultExportResult.MissingAttachmentBytes(listOf("stuck.pdf")),
                result,
            )
            assertEquals(0, destination.size())
        }
    }

    @Test
    fun passphraseArrayIsZeroedEvenWhenExportFails() = runBlocking {
        withTimeout(5_000) {
            val destination = ByteArrayOutputStream()
            val passphrase = "correct horse battery staple".toCharArray()
            val exporter = exporter(
                attachments = emptyList(),
                captureSource = BackupCaptureSource { error("capture unavailable") },
                readChunks = fakeChunkReader(emptyMap(), unfetchable = emptySet()),
            )

            val result = exporter.export(destination, passphrase)

            assertTrue(result is OtVaultExportResult.Failed)
            assertTrue(passphrase.all { it == '\u0000' })
            assertEquals(0, destination.size())
        }
    }

    @Test
    fun aggregateLimitIsCheckedBeforeTheDelegateReceivesAWrite() {
        val delegate = ByteCountingOutputStream()
        val bounded = BoundedCountingOutputStream(delegate)
        val block = ByteArray(1024 * 1024)

        repeat(512) { bounded.write(block) }
        assertEquals(OtVaultCodec.MAX_ARCHIVE_BYTES, bounded.count)
        assertEquals(OtVaultCodec.MAX_ARCHIVE_BYTES, delegate.count)

        assertThrows(OtVaultFormatException::class.java) { bounded.write(0) }
        assertEquals(OtVaultCodec.MAX_ARCHIVE_BYTES, bounded.count)
        assertEquals(OtVaultCodec.MAX_ARCHIVE_BYTES, delegate.count)
    }

    @Test
    fun destinationFailureCannotReturnACompletedExport() = runBlocking {
        withTimeout(5_000) {
            val result = exporter(
                attachments = emptyList(),
                readChunks = fakeChunkReader(emptyMap(), unfetchable = emptySet()),
            ).export(
                destination = object : OutputStream() {
                    override fun write(b: Int) = throw IOException("destination unavailable")
                },
                passphrase = "correct horse battery staple".toCharArray(),
            )

            assertTrue(result.toString(), result is OtVaultExportResult.Failed)
        }
    }

    private fun attachment(
        id: String,
        displayName: String,
        bytes: ByteArray,
    ) = Attachment(
        id = AttachmentId(id),
        taskId = TaskId("task-1"),
        displayName = displayName,
        mimeType = "application/pdf",
        byteCount = bytes.size.toLong(),
        contentHash = sha256(bytes),
        blobSetId = BlobSetId("blob-set-$id"),
        chunkCount = 1,
        deletedAt = null,
        revision = Revision(DeviceId("device-a"), 1, 0),
    )

    private fun fakeChunkReader(
        content: Map<AttachmentId, ByteArray>,
        unfetchable: Set<AttachmentId>,
    ): suspend (Attachment, suspend (Int, ByteArray) -> Unit) -> Boolean = { attachment, onChunk ->
        val bytes = content[attachment.id]
        if (bytes == null || attachment.id in unfetchable) {
            false
        } else {
            onChunk(0, bytes.copyOf())
            true
        }
    }

    private fun exporter(
        attachments: List<Attachment>,
        captureSource: BackupCaptureSource<StructuredBackupCapture> = BackupCaptureSource {
            StructuredBackupCapture(
                vaultId = VAULT_ID,
                generation = BackupGeneration(1),
                records = RECORDS,
            )
        },
        readChunks: suspend (Attachment, suspend (Int, ByteArray) -> Unit) -> Boolean,
    ): OtVaultExporter {
        val contentKeyStore = FixedContentKeyStore(crypto)
        return OtVaultExporter(
            vaultId = VAULT_ID,
            captureSource = captureSource,
            vaultRepository = FakeVaultRepository(attachments),
            contentKeyStore = contentKeyStore,
            codec = codec,
            prepareEnvelope = RecoveryEnvelopePreparer(
                vaultId = VAULT_ID,
                keyStore = contentKeyStore,
                crypto = crypto,
            )::prepare,
            readChunksForExport = readChunks,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte)
        }

    /** A minimal [VaultRepository] exposing only the attachments the exporter reads. */
    private class FakeVaultRepository(private val attachments: List<Attachment>) : VaultRepository {
        override fun observeHome(): Flow<HomeSnapshot> = error("Not used by the exporter")

        override fun observeWorkspace(): StateFlow<WorkspaceSnapshot> =
            MutableStateFlow(currentSnapshot())

        override fun observeTask(id: TaskId): Flow<app.opentasks.core.model.Task?> =
            error("Not used by the exporter")

        override suspend fun currentWorkspace(): WorkspaceSnapshot = currentSnapshot()

        override suspend fun execute(command: DomainCommand): CommandResult =
            error("Not used by the exporter")

        override suspend fun search(query: SearchQuery): List<SearchResult> =
            error("Not used by the exporter")

        private fun currentSnapshot(): WorkspaceSnapshot = WorkspaceSnapshot(
            home = HomeSnapshot(
                today = LocalDate.of(2026, 8, 3),
                focusTasks = emptyList(),
                upcomingTasks = emptyList(),
                projects = emptyList(),
                activeTimer = null as ActiveTimerSnapshot?,
                overdueCount = 0,
            ),
            tasks = emptyList(),
            projects = emptyList(),
            workflowStatuses = emptyList(),
            milestones = emptyList(),
            tags = emptyList(),
            attachments = attachments,
        )
    }

    private class FixedContentKeyStore(private val crypto: VaultCrypto) : VaultContentKeyStore {
        private val internalPassphrase = "fixed-internal-test-passphrase-abc".toCharArray()
        private val envelope: VaultKeyEnvelope = run {
            val key = crypto.createKey()
            try {
                crypto.wrapForRecovery(key, internalPassphrase)
            } finally {
                key.close()
            }
        }

        override fun getOrCreate(vaultId: VaultId): VaultKey =
            throw AssertionError("Export must not bootstrap the content key")

        override fun openExisting(vaultId: VaultId): VaultKey =
            crypto.unlock(internalPassphrase, envelope)

        override fun replace(vaultId: VaultId, key: VaultKey) =
            throw AssertionError("Export must not replace the content key")

        override fun delete(vaultId: VaultId) =
            throw AssertionError("Export must not delete the content key")
    }

    private class ByteCountingOutputStream : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            count += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count += len
        }
    }

    private companion object {
        val VAULT_ID = VaultId("vault-export-test")

        /**
         * The smallest snapshot [BackupPayloadCodec][app.opentasks.core.data.backup.BackupSnapshotCodec]
         * accepts: one vault, one workspace owned by one member, and every
         * required semantic workflow status. `BackupRecordV1` construction
         * helpers live inside `core:data` and are `internal`, so the fields
         * are spelled out here in the exact schema order that module enforces.
         */
        val RECORDS = listOf(
            BackupRecordV1(
                family = BackupRecordFamily.VAULT,
                identity = listOf(VAULT_ID.value),
                fields = listOf(
                    BackupFieldV1("id", BackupFieldType.STRING, VAULT_ID.value),
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
                    BackupFieldV1("vaultId", BackupFieldType.STRING, VAULT_ID.value),
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
        ) + listOf("BACKLOG", "PLANNED", "STARTED", "BLOCKED", "COMPLETED").mapIndexed {
            index,
            semantic,
            ->
            BackupRecordV1(
                family = BackupRecordFamily.WORKFLOW_STATUS,
                identity = listOf("status-inbox-${semantic.lowercase()}"),
                fields = listOf(
                    BackupFieldV1("id", BackupFieldType.STRING, "status-inbox-${semantic.lowercase()}"),
                    BackupFieldV1("projectId", BackupFieldType.NULL, null),
                    BackupFieldV1("name", BackupFieldType.STRING, "Inbox ${semantic.lowercase()}"),
                    BackupFieldV1("semanticStatus", BackupFieldType.STRING, semantic),
                    BackupFieldV1("rank", BackupFieldType.STRING, "inbox-$index"),
                    BackupFieldV1("archivedAtEpochMillis", BackupFieldType.NULL, null),
                    BackupFieldV1("revisionWallMillis", BackupFieldType.LONG, "1"),
                    BackupFieldV1("revisionLogical", BackupFieldType.INT, "$index"),
                    BackupFieldV1("revisionDeviceId", BackupFieldType.STRING, "device-alpha"),
                ),
            )
        }
    }
}
