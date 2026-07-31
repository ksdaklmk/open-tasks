package app.opentasks.backup.drive

import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DriveAccountBindingTest {
    @Test
    fun digestIsThirtyTwoBytesAndDeterministicForTheSameKeyAndPermissionId() {
        val binding = binding(keyBytes = 1)

        val first = binding.digest("permission-a")
        val second = binding.digest("permission-a")

        assertEquals(32, first.size)
        assertArrayEquals(first, second)
    }

    @Test
    fun differentPermissionIdsProduceDifferentDigestsUnderTheSameKey() {
        val binding = binding(keyBytes = 1)

        val digestA = binding.digest("permission-a")
        val digestB = binding.digest("permission-b")

        assertFalse(digestA.contentEquals(digestB))
    }

    @Test
    fun differentInstallKeysProduceDifferentDigestsForTheSamePermissionId() {
        val installOne = binding(keyBytes = 1)
        val installTwo = binding(keyBytes = 2)

        val digestOne = installOne.digest("permission-a")
        val digestTwo = installTwo.digest("permission-a")

        assertFalse(digestOne.contentEquals(digestTwo))
    }

    @Test
    fun digestMatchesAnIndependentlyComputedHmacSha256() {
        val key = SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")
        val binding = DriveAccountBinding(DriveAccountBindingKeyBoundary { key })

        val expected = Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal("permission-golden".encodeToByteArray())
        }

        assertArrayEquals(expected, binding.digest("permission-golden"))
    }

    @Test
    fun mutatingAReturnedDigestNeverAffectsALaterComputation() {
        val binding = binding(keyBytes = 1)

        val first = binding.digest("permission-a")
        first.fill(0)
        val second = binding.digest("permission-a")

        assertFalse(second.contentEquals(first))
    }

    @Test
    fun unpairedSurrogatePermissionIdsAreRejectedByStrictUtf8() {
        val binding = binding(keyBytes = 1)

        listOf("\uD800", "permission-\uDC00").forEach { malformed ->
            assertThrows(Exception::class.java) { binding.digest(malformed) }
        }
    }

    private fun binding(keyBytes: Int): DriveAccountBinding {
        val key: SecretKey = SecretKeySpec(ByteArray(32) { keyBytes.toByte() }, "HmacSHA256")
        return DriveAccountBinding(DriveAccountBindingKeyBoundary { key })
    }
}
