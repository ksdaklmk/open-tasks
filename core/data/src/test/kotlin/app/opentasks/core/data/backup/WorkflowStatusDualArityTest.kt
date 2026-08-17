package app.opentasks.core.data.backup

import app.opentasks.core.domain.BackupMutationKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkflowStatusDualArityTest {

    private fun field(name: String, type: BackupFieldType, value: String?) =
        BackupFieldV1(name, type, value)

    private fun legacyNineFieldRecord(): BackupRecordV1 = BackupRecordV1(
        family = BackupRecordFamily.WORKFLOW_STATUS,
        identity = listOf("status-legacy"),
        fields = listOf(
            field("id", BackupFieldType.STRING, "status-legacy"),
            field("projectId", BackupFieldType.STRING, "project-1"),
            field("name", BackupFieldType.STRING, "Backlog"),
            field("semanticStatus", BackupFieldType.STRING, "BACKLOG"),
            field("rank", BackupFieldType.STRING, "a0"),
            field("archivedAtEpochMillis", BackupFieldType.NULL, null),
            field("revisionWallMillis", BackupFieldType.LONG, "10"),
            field("revisionLogical", BackupFieldType.INT, "0"),
            field("revisionDeviceId", BackupFieldType.STRING, "device-alpha"),
        ),
    )

    private fun tenFieldRecord(wipLimit: String?): BackupRecordV1 {
        val legacy = legacyNineFieldRecord()
        val tail = if (wipLimit == null) {
            field("wipLimit", BackupFieldType.NULL, null)
        } else {
            field("wipLimit", BackupFieldType.INT, wipLimit)
        }
        return legacy.copy(fields = legacy.fields + tail)
    }

    @Test
    fun legacyNineFieldRecordStaysValidAndByteCanonical() {
        val payload = BackupMutationPayloadV1(
            mutationKind = BackupMutationKind.UPSERT,
            record = legacyNineFieldRecord(),
            deletedFamily = null,
            deletedIdentity = null,
        )
        val encoded = BackupMutationCodec.encode(payload)
        val decoded = BackupMutationCodec.decode(encoded)
        assertEquals(9, requireNotNull(decoded.record).fields.size)
        assertArrayEquals(encoded, BackupMutationCodec.encode(decoded))
    }

    @Test
    fun tenFieldRecordValidatesWithNullAndBoundedValues() {
        BackupMutationCodec.validateRecord(tenFieldRecord(null))
        BackupMutationCodec.validateRecord(tenFieldRecord("3"))
        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.validateRecord(tenFieldRecord("0"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.validateRecord(tenFieldRecord("201"))
        }
    }

    @Test
    fun elevenFieldRecordFailsClosed() {
        val eleven = tenFieldRecord("3").let {
            it.copy(fields = it.fields + field("extra", BackupFieldType.INT, "1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupMutationCodec.validateRecord(eleven)
        }
    }

    @Test
    fun legacyRecordImportsAsNoLimitAndTenFieldRoundTrips() {
        assertNull(
            BackupRecordFields.of(legacyNineFieldRecord())
                .toWorkflowStatusEntity().wipLimit,
        )
        assertEquals(
            3,
            BackupRecordFields.of(tenFieldRecord("3"))
                .toWorkflowStatusEntity().wipLimit,
        )
    }

    @Test
    fun encoderEmitsTheTenFieldShape() {
        val entity = app.opentasks.core.data.db.WorkflowStatusEntity(
            id = "status-new",
            projectId = "project-1",
            name = "Started",
            semanticStatus = "STARTED",
            rank = "a2",
            archivedAtEpochMillis = null,
            revisionWallMillis = 10,
            revisionLogical = 0,
            revisionDeviceId = "device-alpha",
            wipLimit = 5,
        )
        val record = entity.toBackupRecordV1()
        assertEquals(10, record.fields.size)
        assertEquals("wipLimit", record.fields.last().name)
        assertEquals("5", record.fields.last().value)
    }
}
