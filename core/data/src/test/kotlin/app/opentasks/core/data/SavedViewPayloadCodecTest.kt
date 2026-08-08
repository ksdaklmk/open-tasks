package app.opentasks.core.data

import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.TagId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedViewPayloadCodecTest {

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
