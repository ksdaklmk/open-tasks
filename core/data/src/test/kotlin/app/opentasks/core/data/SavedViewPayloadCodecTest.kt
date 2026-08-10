package app.opentasks.core.data

import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TaskSortKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedViewPayloadCodecTest {

    private val v1 =
        """{"formatVersion":1,"text":"legacy","projectIds":[],"tagIds":[],"includeCompleted":true,"includeTrash":false}"""
    private val v2 =
        """{"formatVersion":2,"text":"q","projectIds":["a"],"tagIds":["z"],"includeCompleted":false,"includeTrash":true,"dueBuckets":["LATER","TODAY"],"priorities":["HIGH","URGENT"],"statuses":["BACKLOG","STARTED"],"sort":"TITLE"}"""

    private fun assertDecodeFails(json: String) = assertTrue(
        json,
        runCatching { SavedViewPayloadCodec.decode(json.encodeToByteArray()) }.isFailure,
    )

    private fun withoutField(source: String, key: String): String {
        val fields = Json.parseToJsonElement(source).jsonObject - key
        return JsonObject(fields).toString()
    }

    @Test
    fun v1DecodesWithV2DefaultsAndV2BytesAreCanonical() {
        assertEquals(SearchQuery("legacy"), SavedViewPayloadCodec.decode(v1.encodeToByteArray()))
        val query = SearchQuery(
            text = "q",
            projectIds = linkedSetOf(ProjectId("a")),
            tagIds = linkedSetOf(TagId("z")),
            includeCompleted = false,
            includeTrash = true,
            dueBuckets = linkedSetOf(DueBucket.TODAY, DueBucket.LATER),
            priorities = linkedSetOf(Priority.URGENT, Priority.HIGH),
            statuses = linkedSetOf(SemanticStatus.STARTED, SemanticStatus.BACKLOG),
            sort = TaskSortKey.TITLE,
        )
        assertEquals(query, SavedViewPayloadCodec.decode(v2.encodeToByteArray()))
        assertEquals(v2, SavedViewPayloadCodec.encode(query).decodeToString())
        assertEquals(
            """{"formatVersion":2,"text":"","projectIds":[],"tagIds":[],"includeCompleted":true,"includeTrash":false,"dueBuckets":[],"priorities":[],"statuses":[],"sort":null}""",
            SavedViewPayloadCodec.encode(SearchQuery("")).decodeToString(),
        )
    }

    @Test
    fun decodeIsVersionFirstAndEverySchemaFieldIsRequired() {
        listOf(
            "{}",
            v1.replace("\"formatVersion\":1,", ""),
            v1.replace("\"formatVersion\":1", "\"formatVersion\":\"1\""),
            v1.replace("\"formatVersion\":1", "\"formatVersion\":1.0"),
            v1.replace("\"formatVersion\":1", "\"formatVersion\":0"),
            v2.replace("\"formatVersion\":2", "\"formatVersion\":3"),
            v1.dropLast(1) + ",\"foreign\":true}",
            v2.dropLast(1) + ",\"foreign\":true}",
            v2.replace("[\"LATER\",\"TODAY\"]", "[\"UNKNOWN\"]"),
            v2.replace("[\"HIGH\",\"URGENT\"]", "[\"UNKNOWN\"]"),
            v2.replace("[\"BACKLOG\",\"STARTED\"]", "[\"UNKNOWN\"]"),
            v2.replace("\"TITLE\"", "\"UNKNOWN\""),
            "{",
        ).forEach(::assertDecodeFails)
        listOf(
            "formatVersion", "text", "projectIds", "tagIds",
            "includeCompleted", "includeTrash",
        ).forEach { assertDecodeFails(withoutField(v1, it)) }
        listOf(
            "formatVersion", "text", "projectIds", "tagIds", "includeCompleted",
            "includeTrash", "dueBuckets", "priorities", "statuses", "sort",
        ).forEach { assertDecodeFails(withoutField(v2, it)) }
    }

    @Test
    fun boundsAndUtf8FailClosed() {
        assertTrue(runCatching { SavedViewPayloadCodec.encode(SearchQuery("x".repeat(501))) }.isFailure)
        assertDecodeFails(v2.replace("\"q\"", "\"${"x".repeat(501)}\""))
        assertTrue(
            runCatching {
                SavedViewPayloadCodec.decode(ByteArray(SavedViewPayloadCodec.MAX_PAYLOAD_BYTES + 1))
            }.isFailure,
        )
        assertTrue(runCatching { SavedViewPayloadCodec.decode(byteArrayOf(0x7b, -1, 0x7d)) }.isFailure)
    }

    @Test
    fun encodeDecodeRoundTripPreservesEveryQueryField() {
        val query = SearchQuery(
            text = "deep work",
            projectIds = setOf(ProjectId("project-2"), ProjectId("project-1")),
            tagIds = setOf(TagId("tag-9"), TagId("tag-1")),
            includeCompleted = false,
            includeTrash = true,
        )

        val decoded = SavedViewPayloadCodec.decode(SavedViewPayloadCodec.encode(query))

        assertEquals(query, decoded)
    }

    @Test
    fun equalQueriesWithDifferentSetInsertionOrdersEncodeIdentically() {
        val first = SearchQuery(
            text = "q",
            projectIds = linkedSetOf(ProjectId("beta"), ProjectId("alpha")),
            tagIds = linkedSetOf(TagId("2"), TagId("1")),
        )
        val second = SearchQuery(
            text = "q",
            projectIds = linkedSetOf(ProjectId("alpha"), ProjectId("beta")),
            tagIds = linkedSetOf(TagId("1"), TagId("2")),
        )

        assertEquals(first, second)
        assertArrayEquals(
            SavedViewPayloadCodec.encode(first),
            SavedViewPayloadCodec.encode(second),
        )
    }

    @Test
    fun oversizedQueryTextFailsToEncodeAndToDecode() {
        val longText = "x".repeat(501)

        assertTrue(runCatching { SavedViewPayloadCodec.encode(SearchQuery(longText)) }.isFailure)

        val oversizedJson =
            """{"formatVersion":1,"text":"$longText","projectIds":[],"tagIds":[],""" +
                """"includeCompleted":true,"includeTrash":false}"""
        assertTrue(
            runCatching {
                SavedViewPayloadCodec.decode(oversizedJson.toByteArray(Charsets.UTF_8))
            }.isFailure,
        )
    }

    @Test
    fun payloadOverTheByteCeilingIsRejected() {
        val oversized = ByteArray(SavedViewPayloadCodec.MAX_PAYLOAD_BYTES + 1)

        assertTrue(runCatching { SavedViewPayloadCodec.decode(oversized) }.isFailure)
    }

    @Test
    fun malformedUtf8FailsClosed() {
        val malformed = byteArrayOf(0x7B, -1, -2, 0x7D)

        assertTrue(runCatching { SavedViewPayloadCodec.decode(malformed) }.isFailure)
    }

    @Test
    fun unsupportedFormatVersionFailsClosed() {
        val futureVersion =
            """{"formatVersion":99,"text":"q","projectIds":[],"tagIds":[],""" +
                """"includeCompleted":true,"includeTrash":false}"""

        assertTrue(
            runCatching {
                SavedViewPayloadCodec.decode(futureVersion.toByteArray(Charsets.UTF_8))
            }.isFailure,
        )
    }

    @Test
    fun foreignJsonFailsClosed() {
        val foreign = """{"totally":"foreign","formatVersion":1}"""

        assertTrue(
            runCatching {
                SavedViewPayloadCodec.decode(foreign.toByteArray(Charsets.UTF_8))
            }.isFailure,
        )
    }
}
