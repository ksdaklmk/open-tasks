package app.opentasks.core.data.backup

import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectHealth
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.SemanticStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

internal object BackupMutationCodec {
    const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024 - 33
    private const val FORMAT_VERSION = 1
    private const val MINIMUM_READER_VERSION = 1
    private const val MAX_IDENTIFIER_LENGTH = 200
    private const val MAX_GENERAL_STRING_LENGTH = 20_000
    private const val MAX_BINARY_FIELD_BYTES = 2 * 1024 * 1024
    private const val MAX_NOTE_BODY_CIPHERTEXT_BYTES = 40_000

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    fun encode(payload: BackupMutationPayloadV1): ByteArray {
        validate(payload)
        return json.encodeToString(payload)
            .toByteArray(Charsets.UTF_8)
            .also { encoded ->
                require(encoded.size <= MAX_PAYLOAD_BYTES) {
                    "Backup mutation payload exceeds $MAX_PAYLOAD_BYTES bytes"
                }
            }
    }

    fun decode(source: ByteArray): BackupMutationPayloadV1 =
        decodeOwned(source.copyOf())

    fun decodeOwned(source: ByteArray): BackupMutationPayloadV1 {
        try {
            require(source.size <= MAX_PAYLOAD_BYTES) {
                "Backup mutation payload exceeds $MAX_PAYLOAD_BYTES bytes"
            }
            require(source.isNotEmpty()) { "Backup mutation payload is empty" }
            val text = strictUtf8(source)
            val decoded = try {
                json.decodeFromString<BackupMutationPayloadV1>(text)
            } catch (failure: SerializationException) {
                throw IllegalArgumentException("Invalid backup mutation payload", failure)
            }
            validate(decoded)
            require(source.contentEquals(encode(decoded))) {
                "Backup mutation payload is not canonical"
            }
            return decoded
        } finally {
            source.fill(0)
        }
    }

    fun validate(payload: BackupMutationPayloadV1) {
        require(payload.formatVersion == FORMAT_VERSION) {
            "Unsupported backup mutation format ${payload.formatVersion}"
        }
        require(payload.minimumReaderVersion == MINIMUM_READER_VERSION) {
            "Unsupported backup mutation minimum reader ${payload.minimumReaderVersion}"
        }
        when (payload.mutationKind) {
            BackupMutationKind.UPSERT -> {
                requireNotNull(payload.record) { "Upsert mutation requires a record" }
                require(payload.deletedFamily == null && payload.deletedIdentity == null) {
                    "Upsert mutation cannot contain a deletion"
                }
                validateRecord(payload.record)
            }
            BackupMutationKind.DELETE -> {
                require(payload.record == null) { "Delete mutation cannot contain a record" }
                val family = requireNotNull(payload.deletedFamily) {
                    "Delete mutation requires a family"
                }
                val identity = requireNotNull(payload.deletedIdentity) {
                    "Delete mutation requires an identity"
                }
                validateIdentity(family, identity)
            }
            BackupMutationKind.LEGACY ->
                throw IllegalArgumentException("Legacy payloads are not mutation payload v1")
        }
    }

    internal fun validateRecord(record: BackupRecordV1) {
        val schema = schemas.getValue(record.family)
        val minimum = schema.fields.size
        val maximum = minimum + schema.optionalTrailing.size
        require(record.fields.size in minimum..maximum) {
            "${record.family} has the wrong field count"
        }
        val expectedFields = schema.fields +
            schema.optionalTrailing.take(record.fields.size - minimum)
        record.fields.zip(expectedFields).forEach { (field, expected) ->
            require(field.name == expected.name) {
                "${record.family} field ${field.name} is out of order"
            }
            if (field.type == BackupFieldType.NULL) {
                require(expected.nullable) { "${field.name} cannot be null" }
                require(field.value == null) { "NULL fields cannot contain values" }
            } else {
                require(field.type == expected.type) {
                    "${field.name} has type ${field.type}, expected ${expected.type}"
                }
                requireNotNull(field.value) { "${field.name} requires a value" }
                validateCanonicalValue(field)
            }
        }
        validateIdentity(record.family, record.identity)
        val expectedIdentity = schema.identityFieldIndexes.map { index ->
            val field = record.fields[index]
            require(field.type == BackupFieldType.STRING && field.value != null) {
                "Identity fields must be non-null strings"
            }
            field.value
        }
        require(record.identity == expectedIdentity) {
            "${record.family} identity does not match its record"
        }
        validateFamilyValues(record)
    }

    private fun validateIdentity(
        family: BackupRecordFamily,
        identity: List<String>,
    ) {
        require(identity.size == schemas.getValue(family).identityFieldIndexes.size) {
            "$family has the wrong identity size"
        }
        identity.forEach(::validateIdentifier)
    }

    private fun validateCanonicalValue(field: BackupFieldV1) {
        val value = requireNotNull(field.value)
        when (field.type) {
            BackupFieldType.STRING -> {
                require(value.length <= MAX_GENERAL_STRING_LENGTH) {
                    "${field.name} exceeds $MAX_GENERAL_STRING_LENGTH characters"
                }
            }
            BackupFieldType.LONG -> {
                val parsed = value.toLongOrNull()
                    ?: throw IllegalArgumentException("${field.name} is not a long")
                require(parsed.toString() == value) { "${field.name} is not canonical decimal" }
            }
            BackupFieldType.INT -> {
                val parsed = value.toIntOrNull()
                    ?: throw IllegalArgumentException("${field.name} is not an int")
                require(parsed.toString() == value) { "${field.name} is not canonical decimal" }
            }
            BackupFieldType.BOOLEAN ->
                require(value == "true" || value == "false") {
                    "${field.name} is not a canonical boolean"
                }
            BackupFieldType.BYTES -> {
                require(!value.endsWith('=')) { "${field.name} uses padded Base64" }
                val decoded = try {
                    Base64.getDecoder().decode(value)
                } catch (failure: IllegalArgumentException) {
                    throw IllegalArgumentException("${field.name} is not Base64", failure)
                }
                try {
                    require(decoded.size <= MAX_BINARY_FIELD_BYTES) {
                        "${field.name} exceeds $MAX_BINARY_FIELD_BYTES bytes"
                    }
                    require(
                        Base64.getEncoder().withoutPadding().encodeToString(decoded) == value,
                    ) {
                        "${field.name} is not canonical Base64"
                    }
                } finally {
                    decoded.fill(0)
                }
            }
            BackupFieldType.NULL ->
                error("NULL values are validated separately")
        }
    }

    private fun validateFamilyValues(record: BackupRecordV1) {
        val fields = record.fields.associateBy(BackupFieldV1::name)
        fun value(name: String): String? = fields.getValue(name).value
        fun optionalValue(name: String): String? = fields[name]?.value
        fun nonNegativeLong(name: String) {
            value(name)?.let { require(it.toLong() >= 0) { "$name cannot be negative" } }
        }
        fun nonNegativeInt(name: String) {
            value(name)?.let { require(it.toInt() >= 0) { "$name cannot be negative" } }
        }
        fun positiveInt(name: String) {
            value(name)?.let { require(it.toInt() > 0) { "$name must be positive" } }
        }
        fun positiveLong(name: String) {
            value(name)?.let { require(it.toLong() > 0) { "$name must be positive" } }
        }
        fun identifier(name: String) {
            value(name)?.let(::validateIdentifier)
        }
        fun bounded(name: String, maximum: Int, allowEmpty: Boolean = true) {
            value(name)?.let {
                require(it.length <= maximum) { "$name exceeds $maximum characters" }
                require(allowEmpty || it.isNotBlank()) { "$name cannot be blank" }
            }
        }
        fun boundedBytes(name: String, maximumBytes: Int) {
            value(name)?.let {
                val decoded = Base64.getDecoder().decode(it)
                try {
                    require(decoded.size <= maximumBytes) { "$name exceeds $maximumBytes bytes" }
                } finally {
                    decoded.fill(0)
                }
            }
        }
        fun date(name: String) {
            value(name)?.let {
                val parsed = runCatching { LocalDate.parse(it) }
                    .getOrElse { failure ->
                        throw IllegalArgumentException("$name is not an ISO date", failure)
                    }
                require(parsed.toString() == it) { "$name is not a canonical ISO date" }
            }
        }
        fun zone(name: String) {
            value(name)?.let {
                require(it.length <= 64 && it.isNotBlank()) { "$name is not a bounded zone ID" }
                runCatching { ZoneId.of(it) }
                    .getOrElse { failure ->
                        throw IllegalArgumentException("$name is not a zone ID", failure)
                    }
            }
        }
        fun revision() {
            nonNegativeLong("revisionWallMillis")
            nonNegativeInt("revisionLogical")
            identifier("revisionDeviceId")
        }

        when (record.family) {
            BackupRecordFamily.VAULT -> {
                positiveInt("schemaVersion")
                positiveInt("cryptoVersion")
                positiveInt("minimumReaderVersion")
            }
            BackupRecordFamily.WORKSPACE -> {
                identifier("vaultId")
                identifier("ownerId")
                bounded("name", 120, allowEmpty = false)
            }
            BackupRecordFamily.MEMBER ->
                bounded("displayName", 120, allowEmpty = false)
            BackupRecordFamily.PROJECT -> {
                identifier("workspaceId")
                bounded("name", 120, allowEmpty = false)
                bounded("summary", 1_000)
                require(value("health") in ProjectHealth.entries.map(ProjectHealth::name))
                date("dueDate")
                nonNegativeInt("completedTasks")
                nonNegativeInt("totalTasks")
                require(
                    requireNotNull(value("completedTasks")).toInt() <=
                        requireNotNull(value("totalTasks")).toInt(),
                )
                revision()
            }
            BackupRecordFamily.WORKFLOW_STATUS -> {
                identifier("projectId")
                bounded("name", 64, allowEmpty = false)
                require(value("semanticStatus") in SemanticStatus.entries.map(SemanticStatus::name))
                bounded("rank", 200, allowEmpty = false)
                revision()
                optionalValue("wipLimit")?.let {
                    require(it.toInt() in 1..200) { "wipLimit out of range" }
                }
            }
            BackupRecordFamily.MILESTONE -> {
                identifier("projectId")
                bounded("name", 120, allowEmpty = false)
                date("dueDate")
                revision()
            }
            BackupRecordFamily.TASK -> {
                identifier("workspaceId")
                identifier("projectId")
                identifier("parentTaskId")
                identifier("statusId")
                require(value("semanticStatus") in SemanticStatus.entries.map(SemanticStatus::name))
                bounded("title", 240, allowEmpty = false)
                require(value("priority") in Priority.entries.map(Priority::name))
                zone("startZoneId")
                require((value("startEpochMillis") == null) == (value("startZoneId") == null))
                zone("dueZoneId")
                require((value("dueEpochMillis") == null) == (value("dueZoneId") == null))
                value("recurrenceFrequency")?.let {
                    require(it in RecurrenceFrequency.entries.map(RecurrenceFrequency::name))
                }
                positiveInt("recurrenceInterval")
                value("recurrenceWeekdays")?.let { encoded ->
                    val weekdays = encoded.split(',').filter(String::isNotEmpty)
                    weekdays.forEach(DayOfWeek::valueOf)
                    require(weekdays.distinct().size == weekdays.size)
                    require(weekdays == weekdays.sortedBy { DayOfWeek.valueOf(it).value })
                }
                positiveInt("recurrenceCount")
                date("recurrenceEndDate")
                identifier("recurrenceSeriesId")
                zone("recurrenceAnchorZoneId")
                require(
                    (value("recurrenceAnchorEpochMillis") == null) ==
                        (value("recurrenceAnchorZoneId") == null),
                )
                value("recurrenceOccurrenceIndex")?.let {
                    require(it.toInt() >= -1) {
                        "recurrenceOccurrenceIndex cannot be below -1"
                    }
                }
                positiveLong("estimateSeconds")
                identifier("milestoneId")
                revision()
                validateTaskRecurrence(::value)
            }
            BackupRecordFamily.CHECKLIST_ITEM -> {
                identifier("taskId")
                bounded("text", 500, allowEmpty = false)
                bounded("rank", 200, allowEmpty = false)
            }
            BackupRecordFamily.TASK_DEPENDENCY -> {
                identifier("taskId")
                identifier("dependsOnTaskId")
                require(value("taskId") != value("dependsOnTaskId"))
                revision()
            }
            BackupRecordFamily.TAG -> {
                identifier("workspaceId")
                bounded("name", 64, allowEmpty = false)
            }
            BackupRecordFamily.TASK_TAG -> {
                identifier("taskId")
                identifier("tagId")
                revision()
            }
            BackupRecordFamily.REMINDER -> {
                identifier("taskId")
                zone("zoneId")
            }
            BackupRecordFamily.ATTACHMENT -> {
                identifier("taskId")
                bounded("mimeType", 255, allowEmpty = false)
                nonNegativeLong("byteCount")
                bounded("contentHash", 512, allowEmpty = false)
                identifier("blobSetId")
                nonNegativeInt("chunkCount")
                revision()
            }
            BackupRecordFamily.ACTIVITY_ENTRY -> {
                identifier("taskId")
                identifier("projectId")
                bounded("kind", 120, allowEmpty = false)
            }
            BackupRecordFamily.TIME_ENTRY -> {
                identifier("taskId")
                identifier("deviceId")
                value("stoppedAtEpochMillis")?.let { stopped ->
                    require(
                        stopped.toLong() >=
                            requireNotNull(value("startedAtEpochMillis")).toLong(),
                    )
                }
            }
            BackupRecordFamily.TEMPLATE -> {
                identifier("workspaceId")
                bounded("name", 120, allowEmpty = false)
                revision()
            }
            BackupRecordFamily.SAVED_VIEW -> {
                identifier("workspaceId")
                bounded("name", 120, allowEmpty = false)
            }
            BackupRecordFamily.NOTE -> {
                identifier("id")
                identifier("taskId")
                identifier("projectId")
                require((value("taskId") == null) != (value("projectId") == null)) {
                    "Note must have exactly one owner"
                }
                boundedBytes("bodyCiphertext", MAX_NOTE_BODY_CIPHERTEXT_BYTES)
                nonNegativeLong("createdAtEpochMillis")
                nonNegativeLong("editedAtEpochMillis")
                revision()
            }
            BackupRecordFamily.RETIRED_BLOB_SET -> {
                identifier("blobSetId")
                val maxChunks = AttachmentBlobSetManifestCodec.MAX_BLOB_SET_CHUNKS
                require(requireNotNull(value("chunkCount")).toInt() in 0..maxChunks) {
                    "chunkCount must be between 0 and $maxChunks"
                }
                nonNegativeLong("retiredAtEpochMillis")
                revision()
            }
            BackupRecordFamily.TOMBSTONE -> {
                bounded("objectType", 120, allowEmpty = false)
                require(
                    requireNotNull(value("purgeAfterEpochMillis")).toLong() >=
                        requireNotNull(value("deletedAtEpochMillis")).toLong(),
                )
                revision()
            }
        }
    }

    private fun validateTaskRecurrence(value: (String) -> String?) {
        val frequency = value("recurrenceFrequency")
        val ruleValues = listOf(
            value("recurrenceInterval"),
            value("recurrenceWeekdays"),
            value("recurrenceCount"),
            value("recurrenceEndDate"),
        )
        val metadataValues = listOf(
            value("recurrenceSeriesId"),
            value("recurrenceAnchorEpochMillis"),
            value("recurrenceAnchorZoneId"),
            value("recurrenceOccurrenceIndex"),
        )
        if (frequency == null) {
            require(ruleValues.all { it == null } && metadataValues.all { it == null }) {
                "Non-recurring task contains recurrence state"
            }
            return
        }

        require(value("startEpochMillis") != null || value("dueEpochMillis") != null) {
            "Recurring task requires a start or due instant"
        }
        require(value("recurrenceCount") == null || value("recurrenceEndDate") == null) {
            "Recurrence cannot contain both count and end date"
        }
        require(metadataValues.all { it == null } || metadataValues.all { it != null }) {
            "Recurrence series metadata is incomplete"
        }

        if (value("recurrenceOccurrenceIndex") == "-1") {
            val weekdays = requireNotNull(value("recurrenceWeekdays"))
                .split(',')
                .filter(String::isNotEmpty)
                .map(DayOfWeek::valueOf)
            require(frequency == RecurrenceFrequency.WEEKLY.name && weekdays.isNotEmpty()) {
                "Pending recurrence occurrence requires explicit weekly weekdays"
            }
            val anchorDay = Instant.ofEpochMilli(
                requireNotNull(value("recurrenceAnchorEpochMillis")).toLong(),
            ).atZone(
                ZoneId.of(requireNotNull(value("recurrenceAnchorZoneId"))),
            ).dayOfWeek
            require(anchorDay !in weekdays) {
                "Pending recurrence occurrence requires an anchor outside the weekly schedule"
            }
        }

        val recurrenceEnd = value("recurrenceEndDate")
        val dueEpochMillis = value("dueEpochMillis")
        val dueZoneId = value("dueZoneId")
        if (recurrenceEnd != null && dueEpochMillis != null && dueZoneId != null) {
            val dueDate = Instant.ofEpochMilli(dueEpochMillis.toLong())
                .atZone(ZoneId.of(dueZoneId))
                .toLocalDate()
            require(!LocalDate.parse(recurrenceEnd).isBefore(dueDate)) {
                "Recurrence end date is before the task due date"
            }
        }
    }

    private fun validateIdentifier(value: String) {
        require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH) {
            "Identifier must contain 1..$MAX_IDENTIFIER_LENGTH characters"
        }
    }

    private fun strictUtf8(source: ByteArray): String = try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(source))
            .toString()
    } catch (failure: Exception) {
        throw IllegalArgumentException("Backup mutation payload is not valid UTF-8", failure)
    }

    private data class FieldSchema(
        val name: String,
        val type: BackupFieldType,
        val nullable: Boolean = false,
    )

    private data class RecordSchema(
        val fields: List<FieldSchema>,
        val identityFieldIndexes: List<Int> = listOf(0),
        val optionalTrailing: List<FieldSchema> = emptyList(),
    )

    private fun string(name: String, nullable: Boolean = false) =
        FieldSchema(name, BackupFieldType.STRING, nullable)

    private fun long(name: String, nullable: Boolean = false) =
        FieldSchema(name, BackupFieldType.LONG, nullable)

    private fun int(name: String, nullable: Boolean = false) =
        FieldSchema(name, BackupFieldType.INT, nullable)

    private fun boolean(name: String) =
        FieldSchema(name, BackupFieldType.BOOLEAN)

    private fun bytes(name: String) =
        FieldSchema(name, BackupFieldType.BYTES)

    private val schemas = mapOf(
        BackupRecordFamily.VAULT to RecordSchema(
            listOf(
                string("id"),
                long("createdAtEpochMillis"),
                int("schemaVersion"),
                int("cryptoVersion"),
                int("minimumReaderVersion"),
            ),
        ),
        BackupRecordFamily.WORKSPACE to RecordSchema(
            listOf(string("id"), string("vaultId"), string("ownerId"), string("name")),
        ),
        BackupRecordFamily.MEMBER to RecordSchema(
            listOf(string("id"), string("displayName")),
        ),
        BackupRecordFamily.PROJECT to RecordSchema(
            listOf(
                string("id"),
                string("workspaceId"),
                string("name"),
                string("summary"),
                string("health"),
                string("dueDate", nullable = true),
                int("completedTasks"),
                int("totalTasks"),
                long("archivedAtEpochMillis", nullable = true),
                long("revisionWallMillis"),
                int("revisionLogical"),
                string("revisionDeviceId"),
            ),
        ),
        BackupRecordFamily.WORKFLOW_STATUS to RecordSchema(
            fields = listOf(
                string("id"),
                string("projectId", nullable = true),
                string("name"),
                string("semanticStatus"),
                string("rank"),
                long("archivedAtEpochMillis", nullable = true),
                long("revisionWallMillis"),
                int("revisionLogical"),
                string("revisionDeviceId"),
            ),
            optionalTrailing = listOf(int("wipLimit", nullable = true)),
        ),
        BackupRecordFamily.MILESTONE to RecordSchema(
            listOf(
                string("id"),
                string("projectId"),
                string("name"),
                string("dueDate", nullable = true),
                long("completedAtEpochMillis", nullable = true),
                long("revisionWallMillis"),
                int("revisionLogical"),
                string("revisionDeviceId"),
            ),
        ),
        BackupRecordFamily.TASK to RecordSchema(
            listOf(
                string("id"),
                string("workspaceId"),
                string("projectId", nullable = true),
                string("parentTaskId", nullable = true),
                string("statusId"),
                string("semanticStatus"),
                string("title"),
                bytes("descriptionCiphertext"),
                string("priority"),
                long("startEpochMillis", nullable = true),
                string("startZoneId", nullable = true),
                long("dueEpochMillis", nullable = true),
                string("dueZoneId", nullable = true),
                string("recurrenceFrequency", nullable = true),
                int("recurrenceInterval", nullable = true),
                string("recurrenceWeekdays", nullable = true),
                int("recurrenceCount", nullable = true),
                string("recurrenceEndDate", nullable = true),
                string("recurrenceSeriesId", nullable = true),
                long("recurrenceAnchorEpochMillis", nullable = true),
                string("recurrenceAnchorZoneId", nullable = true),
                int("recurrenceOccurrenceIndex", nullable = true),
                long("estimateSeconds", nullable = true),
                string("milestoneId", nullable = true),
                long("completedAtEpochMillis", nullable = true),
                long("deletedAtEpochMillis", nullable = true),
                long("revisionWallMillis"),
                int("revisionLogical"),
                string("revisionDeviceId"),
            ),
        ),
        BackupRecordFamily.CHECKLIST_ITEM to RecordSchema(
            listOf(
                string("id"),
                string("taskId"),
                string("text"),
                boolean("completed"),
                string("rank"),
            ),
        ),
        BackupRecordFamily.TASK_DEPENDENCY to RecordSchema(
            fields = listOf(
                string("taskId"),
                string("dependsOnTaskId"),
                long("revisionWallMillis"),
                int("revisionLogical"),
                string("revisionDeviceId"),
            ),
            identityFieldIndexes = listOf(0, 1),
        ),
        BackupRecordFamily.TAG to RecordSchema(
            listOf(string("id"), string("workspaceId"), string("name")),
        ),
        BackupRecordFamily.TASK_TAG to RecordSchema(
            fields = listOf(
                string("taskId"),
                string("tagId"),
                boolean("present"),
                long("revisionWallMillis"),
                int("revisionLogical"),
                string("revisionDeviceId"),
            ),
            identityFieldIndexes = listOf(0, 1),
        ),
        BackupRecordFamily.REMINDER to RecordSchema(
            listOf(
                string("id"),
                string("taskId"),
                long("triggerAtEpochMillis"),
                string("zoneId"),
                boolean("precise"),
            ),
        ),
        BackupRecordFamily.ATTACHMENT to RecordSchema(
            listOf(
                string("id"),
                string("taskId"),
                bytes("displayNameCiphertext"),
                string("mimeType"),
                long("byteCount"),
                string("contentHash"),
                string("blobSetId", nullable = true),
                int("chunkCount"),
                long("deletedAtEpochMillis", nullable = true),
                long("revisionWallMillis"),
                int("revisionLogical"),
                string("revisionDeviceId"),
            ),
        ),
        BackupRecordFamily.ACTIVITY_ENTRY to RecordSchema(
            listOf(
                string("id"),
                string("taskId", nullable = true),
                string("projectId", nullable = true),
                string("kind"),
                bytes("bodyCiphertext"),
                long("createdAtEpochMillis"),
            ),
        ),
        BackupRecordFamily.TIME_ENTRY to RecordSchema(
            listOf(
                string("id"),
                string("taskId"),
                string("deviceId"),
                long("startedAtEpochMillis"),
                long("stoppedAtEpochMillis", nullable = true),
                bytes("noteCiphertext"),
            ),
        ),
        BackupRecordFamily.TEMPLATE to RecordSchema(
            listOf(
                string("id"),
                string("workspaceId"),
                string("name"),
                bytes("encryptedPayload"),
                long("revisionWallMillis"),
                int("revisionLogical"),
                string("revisionDeviceId"),
            ),
        ),
        BackupRecordFamily.SAVED_VIEW to RecordSchema(
            listOf(
                string("id"),
                string("workspaceId"),
                string("name"),
                bytes("encryptedQuery"),
            ),
        ),
        BackupRecordFamily.NOTE to RecordSchema(
            listOf(
                string("id"),
                string("taskId", nullable = true),
                string("projectId", nullable = true),
                bytes("bodyCiphertext"),
                long("createdAtEpochMillis"),
                long("editedAtEpochMillis", nullable = true),
                long("revisionWallMillis"),
                int("revisionLogical"),
                string("revisionDeviceId"),
            ),
        ),
        BackupRecordFamily.RETIRED_BLOB_SET to RecordSchema(
            listOf(
                string("blobSetId"),
                int("chunkCount"),
                long("retiredAtEpochMillis"),
                long("revisionWallMillis"),
                int("revisionLogical"),
                string("revisionDeviceId"),
            ),
        ),
        BackupRecordFamily.TOMBSTONE to RecordSchema(
            fields = listOf(
                string("objectId"),
                string("objectType"),
                long("deletedAtEpochMillis"),
                long("purgeAfterEpochMillis"),
                long("revisionWallMillis"),
                int("revisionLogical"),
                string("revisionDeviceId"),
            ),
            identityFieldIndexes = listOf(0, 1),
        ),
    )
}
