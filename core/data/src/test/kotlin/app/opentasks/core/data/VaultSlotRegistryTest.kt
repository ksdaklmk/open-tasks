package app.opentasks.core.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSlotRegistryTest {
    private val directory = File("/registry")
    private val marker = File(directory, "active_slot.json")

    @Test
    fun newSlotsAreRandomAndNeverCollideWithTheLegacySlot() {
        val slots = (1..32).map { VaultSlot.new() }

        assertEquals(32, slots.map { it.value }.toSet().size)
        slots.forEach { slot ->
            assertNotEquals(VaultSlot.LEGACY, slot)
            assertEquals(slot, VaultSlot.parse(slot.value))
        }
    }

    @Test
    fun slotToStringNeverExposesTheSlotValue() {
        val slot = VaultSlot.new()

        assertEquals("VaultSlot([redacted])", slot.toString())
        assertEquals("VaultSlot([redacted])", VaultSlot.LEGACY.toString())
        assertFalse(slot.toString().contains(slot.value))
    }

    @Test
    fun parseRejectsValuesOutsideTheSlotAlphabet() {
        listOf("", "Legacy", "legacy ", "../escape", "s", "s0", "s" + "z".repeat(32))
            .forEach { candidate ->
                assertThrows(IllegalArgumentException::class.java) {
                    VaultSlot.parse(candidate)
                }
            }
        assertEquals(VaultSlot.LEGACY, VaultSlot.parse("legacy"))
    }

    @Test
    fun slotDigestIsStableAndDistinctPerSlot() {
        val first = VaultSlot.new()
        val second = VaultSlot.new()

        assertEquals(first.digest, VaultSlot.parse(first.value).digest)
        assertNotEquals(first.digest, second.digest)
        assertEquals(64, first.digest.length)
        assertFalse(first.digest.contains(first.value))
    }

    @Test
    fun absentMarkerReadsAsNoSlot() {
        val registry = VaultSlotRegistry(directory, RecordingFileOperations())

        assertNull(registry.read())
    }

    @Test
    fun markerRoundTripsThroughCanonicalJson() {
        val operations = RecordingFileOperations()
        val registry = VaultSlotRegistry(directory, operations)
        val slot = VaultSlot.new()

        registry.replace(slot)

        assertEquals(slot, registry.read())
        assertEquals(
            """{"formatVersion":1,"slot":"${slot.value}"}""",
            operations.committed(marker)?.decodeToString(),
        )
    }

    @Test
    fun stagedMarkerIsInvisibleUntilItIsCommitted() {
        val operations = RecordingFileOperations()
        val registry = VaultSlotRegistry(directory, operations)
        registry.replace(VaultSlot.LEGACY)
        val staged = VaultSlot.new()

        registry.stageReplacement(staged)

        assertEquals(VaultSlot.LEGACY, registry.read())

        registry.commitReplacement()

        assertEquals(staged, registry.read())
        assertEquals(
            listOf("ensureDirectory", "stage", "commit", "stage", "commit"),
            operations.log,
        )
    }

    @Test
    fun discardedReplacementLeavesThePriorMarkerUnchanged() {
        val operations = RecordingFileOperations()
        val registry = VaultSlotRegistry(directory, operations)
        registry.replace(VaultSlot.LEGACY)

        registry.stageReplacement(VaultSlot.new())
        registry.discardReplacement()

        assertEquals(VaultSlot.LEGACY, registry.read())
    }

    @Test
    fun oversizedMarkerIsRejectedWithoutParsing() {
        val operations = RecordingFileOperations()
        operations.seed(marker, ByteArray(65_537) { '{'.code.toByte() })
        val registry = VaultSlotRegistry(directory, operations)

        val failure = assertThrows(IllegalStateException::class.java) { registry.read() }

        assertEquals("The vault slot registry is unreadable", failure.message)
    }

    @Test
    fun nonCanonicalMarkerEncodingsAreRejected() {
        val slot = VaultSlot.new()
        listOf(
            """ {"formatVersion":1,"slot":"${slot.value}"}""",
            """{"formatVersion": 1,"slot":"${slot.value}"}""",
            """{"slot":"${slot.value}","formatVersion":1}""",
            """{"formatVersion":1,"formatVersion":1,"slot":"${slot.value}"}""",
            """{"formatVersion":01,"slot":"${slot.value}"}""",
            """{"formatVersion":1,"slot":"${slot.value}"} """,
            """{"formatVersion":1,"slot":"${slot.value}"}{}""",
            """{"formatVersion":1,"slot":"${slot.value}","extra":1}""",
            """{"formatVersion":2,"slot":"${slot.value}"}""",
            """{"formatVersion":1}""",
            """{"formatVersion":true,"slot":"${slot.value}"}""",
            "[]",
        ).forEach { encoded ->
            val operations = RecordingFileOperations()
            operations.seed(marker, encoded.encodeToByteArray())
            val registry = VaultSlotRegistry(directory, operations)

            val failure = assertThrows(IllegalStateException::class.java) { registry.read() }

            assertEquals("The vault slot registry is unreadable", failure.message)
        }
    }

    @Test
    fun markerFailuresNeverExposeStoredSlotValues() {
        val slot = VaultSlot.new()
        val operations = RecordingFileOperations()
        operations.seed(marker, """{"formatVersion":9,"slot":"${slot.value}"}""".encodeToByteArray())
        val registry = VaultSlotRegistry(directory, operations)

        val failure = assertThrows(IllegalStateException::class.java) { registry.read() }

        assertFalse(failure.message.orEmpty().contains(slot.value))
        assertNull(failure.cause)
    }

    @Test
    fun canonicalJsonRejectsUnsortedDuplicateAndUnterminatedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.decode("""{"b":1,"a":1}""".encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.decode("""{"a":1,"a":1}""".encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.decode("""{"a":1""".encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.decode("""{"a":-0}""".encodeToByteArray())
        }
    }

    @Test
    fun canonicalJsonSortsKeysAndEscapesControlCharacters() {
        val encoded = CanonicalJson.encode(
            mapOf(
                "zeta" to null,
                "alpha" to "quote\"back\\slash\u0001",
                "count" to -12L,
                "flag" to true,
            ),
        )

        assertEquals(
            """{"alpha":"quote\"back\\slash\u0001","count":-12,"flag":true,"zeta":null}""",
            encoded.decodeToString(),
        )
        assertEquals(
            mapOf<String, Any?>(
                "alpha" to "quote\"back\\slash\u0001",
                "count" to -12L,
                "flag" to true,
                "zeta" to null,
            ),
            CanonicalJson.decode(encoded),
        )
    }

    @Test
    fun canonicalJsonRejectsPayloadsOverTheRegistryBound() {
        val oversized = CanonicalJson.encode(mapOf("a" to "x".repeat(65_536)))

        assertTrue(oversized.size > 65_536)
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.decode(oversized)
        }
    }
}

internal class RecordingFileOperations : VaultRegistryFileOperations {
    private val committed = mutableMapOf<String, ByteArray>()
    private val staged = mutableMapOf<String, ByteArray>()
    val log = mutableListOf<String>()

    fun seed(file: File, bytes: ByteArray) {
        committed[file.path] = bytes
    }

    fun committed(file: File): ByteArray? = committed[file.path]

    override fun readBytes(file: File): ByteArray? = committed[file.path]?.copyOf()

    override fun stageWrite(file: File, bytes: ByteArray) {
        log += "stage"
        staged[file.path] = bytes.copyOf()
    }

    override fun commitWrite(file: File) {
        log += "commit"
        committed[file.path] = checkNotNull(staged.remove(file.path))
    }

    override fun discardWrite(file: File) {
        log += "discard"
        staged.remove(file.path)
    }

    override fun delete(file: File) {
        log += "delete"
        committed.remove(file.path)
        staged.remove(file.path)
    }

    override fun ensureDirectory(directory: File) {
        log += "ensureDirectory"
    }
}
