package app.opentasks.core.data

import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TaskSortKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Bounded JSON codec for the `saved_views.encryptedQuery` column. The codec
 * serialises only the query; entity identity fields stay on the row, so they
 * can never drift from the payload. Filter id sets are stored as sorted lists
 * so logically equal queries always encode to identical bytes and the
 * content-based journal fingerprint stays stable.
 */
internal object SavedViewPayloadCodec {
    const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
    private const val MAX_QUERY_TEXT_LENGTH = 500

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(query: SearchQuery): ByteArray {
        validate(query)
        val bytes = json.encodeToString(SavedViewPayloadV2.from(query))
            .toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PAYLOAD_BYTES) {
            "Saved view payload exceeds $MAX_PAYLOAD_BYTES bytes"
        }
        return bytes
    }

    fun decode(payload: ByteArray): SearchQuery {
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "Saved view payload exceeds $MAX_PAYLOAD_BYTES bytes"
        }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
        return decodeText(text)
    }

    private fun decodeText(text: String): SearchQuery {
        val primitive = json.parseToJsonElement(text).jsonObject["formatVersion"]
            as? JsonPrimitive ?: error("Missing formatVersion")
        require(!primitive.isString) { "formatVersion must be an integer" }
        val version = primitive.intOrNull ?: error("formatVersion must be an integer")
        return when (version) {
            1 -> json.decodeFromString<SavedViewPayloadV1>(text).toModel()
            2 -> json.decodeFromString<SavedViewPayloadV2>(text).toModel()
            else -> error("Unsupported saved view format $version")
        }.also(::validate)
    }

    private fun validate(query: SearchQuery) {
        require(query.text.length <= MAX_QUERY_TEXT_LENGTH) {
            "Saved view query text exceeds $MAX_QUERY_TEXT_LENGTH characters"
        }
    }

    @Serializable
    private data class SavedViewPayloadV1(
        val formatVersion: Int,
        val text: String,
        val projectIds: List<String>,
        val tagIds: List<String>,
        val includeCompleted: Boolean,
        val includeTrash: Boolean,
    ) {
        fun toModel() = SearchQuery(
            text, projectIds.mapTo(linkedSetOf(), ::ProjectId),
            tagIds.mapTo(linkedSetOf(), ::TagId), includeCompleted, includeTrash,
        )
    }

    @Serializable
    private data class SavedViewPayloadV2(
        val formatVersion: Int,
        val text: String,
        val projectIds: List<String>,
        val tagIds: List<String>,
        val includeCompleted: Boolean,
        val includeTrash: Boolean,
        val dueBuckets: List<DueBucket>,
        val priorities: List<Priority>,
        val statuses: List<SemanticStatus>,
        val sort: TaskSortKey?,
    ) {
        fun toModel() = SearchQuery(
            text = text,
            projectIds = projectIds.mapTo(linkedSetOf(), ::ProjectId),
            tagIds = tagIds.mapTo(linkedSetOf(), ::TagId),
            includeCompleted = includeCompleted,
            includeTrash = includeTrash,
            dueBuckets = dueBuckets.toSet(),
            priorities = priorities.toSet(),
            statuses = statuses.toSet(),
            sort = sort,
        )

        companion object {
            fun from(query: SearchQuery) = SavedViewPayloadV2(
                formatVersion = 2,
                text = query.text,
                projectIds = query.projectIds.map(ProjectId::value).sorted(),
                tagIds = query.tagIds.map(TagId::value).sorted(),
                includeCompleted = query.includeCompleted,
                includeTrash = query.includeTrash,
                dueBuckets = query.dueBuckets.sortedBy { it.name },
                priorities = query.priorities.sortedBy { it.name },
                statuses = query.statuses.sortedBy { it.name },
                sort = query.sort,
            )
        }
    }
}
