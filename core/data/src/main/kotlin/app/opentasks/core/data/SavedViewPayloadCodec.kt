package app.opentasks.core.data

import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.TagId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    private const val FORMAT_VERSION = 1
    private const val MAX_QUERY_TEXT_LENGTH = 500

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(query: SearchQuery): ByteArray {
        validate(query)
        val bytes = json.encodeToString(SavedViewPayload.from(query))
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
        val decoded = json.decodeFromString<SavedViewPayload>(text)
        require(decoded.formatVersion == FORMAT_VERSION) {
            "Unsupported saved view format ${decoded.formatVersion}"
        }
        return decoded.toModel().also(::validate)
    }

    private fun validate(query: SearchQuery) {
        require(query.text.length <= MAX_QUERY_TEXT_LENGTH) {
            "Saved view query text exceeds $MAX_QUERY_TEXT_LENGTH characters"
        }
    }

    @Serializable
    private data class SavedViewPayload(
        val formatVersion: Int = FORMAT_VERSION,
        val text: String,
        val projectIds: List<String>,
        val tagIds: List<String>,
        val includeCompleted: Boolean,
        val includeTrash: Boolean,
    ) {
        fun toModel(): SearchQuery = SearchQuery(
            text = text,
            projectIds = projectIds.mapTo(linkedSetOf(), ::ProjectId),
            tagIds = tagIds.mapTo(linkedSetOf(), ::TagId),
            includeCompleted = includeCompleted,
            includeTrash = includeTrash,
        )

        companion object {
            fun from(query: SearchQuery): SavedViewPayload = SavedViewPayload(
                text = query.text,
                projectIds = query.projectIds.map(ProjectId::value).sorted(),
                tagIds = query.tagIds.map(TagId::value).sorted(),
                includeCompleted = query.includeCompleted,
                includeTrash = query.includeTrash,
            )
        }
    }
}
