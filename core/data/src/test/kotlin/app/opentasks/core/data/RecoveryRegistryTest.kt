package app.opentasks.core.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryRegistryTest {
    private val directory = File("/registry")
    private val file = File(directory, "recovery_registry.bin")
    private val stagedSlot = VaultSlot.new()
    private val priorSlot = VaultSlot.new()

    @Test
    fun recordRoundTripsThroughTheSealedRegistry() {
        val operations = RecordingFileOperations()
        val registry = RecoveryRegistry(file, operations, ReversibleSecretBoundary())
        val record = record()

        registry.write(record)

        assertEquals(record, registry.read())
    }

    @Test
    fun absentRegistryReadsAsNoRecord() {
        val registry = RecoveryRegistry(file, RecordingFileOperations(), ReversibleSecretBoundary())

        assertNull(registry.read())
    }

    @Test
    fun writesAreStagedBeforeTheyAreCommitted() {
        val operations = RecordingFileOperations()
        val registry = RecoveryRegistry(file, operations, ReversibleSecretBoundary())

        registry.write(record())
        registry.clear()

        assertEquals(listOf("ensureDirectory", "stage", "commit", "delete"), operations.log)
        assertNull(registry.read())
    }

    @Test
    fun recordToStringNeverExposesSlotsProviderReferencesOrOperationState() {
        val record = record()

        val rendered = record.toString()

        assertEquals("RecoveryRegistryRecord([redacted])", rendered)
        listOf(
            record.operationId,
            stagedSlot.value,
            priorSlot.value,
            "provider-file",
            "claim-file",
            "publication-file",
        ).forEach { secret ->
            assertFalse(rendered.contains(secret))
        }
    }

    @Test
    fun storedPayloadIsSealedRatherThanPlaintextCanonicalJson() {
        val operations = RecordingFileOperations()
        val registry = RecoveryRegistry(file, operations, ReversibleSecretBoundary())

        registry.write(record())

        val stored = checkNotNull(operations.committed(file)).decodeToString()
        assertFalse(stored.contains(stagedSlot.value))
        assertFalse(stored.contains("provider-file"))
        assertTrue(stored.startsWith("sealed:"))
    }

    @Test
    fun unopenableRegistryIsDiscardedWithoutReportingItsContent() {
        val operations = RecordingFileOperations()
        RecoveryRegistry(file, operations, ReversibleSecretBoundary()).write(record())
        val lockedOut = RecoveryRegistry(file, operations, FailingSecretBoundary())

        val failure = assertThrows(IllegalStateException::class.java) { lockedOut.read() }

        assertEquals("The vault recovery registry is unreadable", failure.message)
        assertNull(failure.cause)
        assertTrue(lockedOut.readOrDiscard() == null)
        assertNull(operations.committed(file))
    }

    @Test
    fun tamperedSealedPayloadFailsClosedAndNeverYieldsARecord() {
        val operations = RecordingFileOperations()
        RecoveryRegistry(file, operations, ReversibleSecretBoundary()).write(record())
        val stored = checkNotNull(operations.committed(file))
        stored[stored.lastIndex] = (stored.last().toInt() xor 0x01).toByte()
        operations.seed(file, stored)
        val registry = RecoveryRegistry(file, operations, ReversibleSecretBoundary())

        assertThrows(IllegalStateException::class.java) { registry.read() }
    }

    @Test
    fun unboundedReferencesAreRejectedBeforeTheyReachStorage() {
        val operations = RecordingFileOperations()
        val registry = RecoveryRegistry(file, operations, ReversibleSecretBoundary())

        assertThrows(IllegalArgumentException::class.java) {
            record(operationId = "o".repeat(200))
        }
        assertThrows(IllegalArgumentException::class.java) {
            record(providerReference = "p".repeat(300))
        }
        assertThrows(IllegalArgumentException::class.java) { record(operationId = "") }
        assertNull(operations.committed(file))
        assertNull(registry.read())
    }

    @Test
    fun phaseActivationAndCleanupStatesSurviveEveryTransition() {
        val operations = RecordingFileOperations()
        val registry = RecoveryRegistry(file, operations, ReversibleSecretBoundary())

        RecoveryPhase.entries.forEach { phase ->
            ActivationState.entries.forEach { activation ->
                CleanupState.entries.forEach { cleanup ->
                    val record = record().copy(
                        phase = phase,
                        activationState = activation,
                        cleanupState = cleanup,
                    )
                    registry.write(record)
                    assertEquals(record, registry.read())
                }
            }
        }
    }

    @Test
    fun absentPriorSlotAndEpochRoundTripAsAbsent() {
        val operations = RecordingFileOperations()
        val registry = RecoveryRegistry(file, operations, ReversibleSecretBoundary())
        val record = record().copy(
            priorSlot = null,
            claimedEpoch = null,
            providerReference = null,
            claimReference = null,
            publicationReference = null,
        )

        registry.write(record)

        assertEquals(record, registry.read())
    }

    private fun record(
        operationId: String = "operation-1",
        providerReference: String? = "provider-file",
    ) = RecoveryRegistryRecord(
        operationId = operationId,
        phase = RecoveryPhase.STAGING,
        priorSlot = priorSlot,
        stagedSlot = stagedSlot,
        providerReference = providerReference,
        claimReference = "claim-file",
        publicationReference = "publication-file",
        claimedEpoch = 7L,
        activationState = ActivationState.PENDING,
        cleanupState = CleanupState.PENDING,
    )
}

private class ReversibleSecretBoundary : RegistrySecretBoundary {
    override fun seal(plaintext: ByteArray): ByteArray =
        ("sealed:" + plaintext.reversedArray().decodeToString()).encodeToByteArray()

    override fun open(sealed: ByteArray): ByteArray {
        val text = sealed.decodeToString()
        require(text.startsWith("sealed:")) { "not sealed" }
        return text.removePrefix("sealed:").encodeToByteArray().reversedArray()
    }
}

private class FailingSecretBoundary : RegistrySecretBoundary {
    override fun seal(plaintext: ByteArray): ByteArray = error("unavailable")

    override fun open(sealed: ByteArray): ByteArray = error("unavailable")
}
