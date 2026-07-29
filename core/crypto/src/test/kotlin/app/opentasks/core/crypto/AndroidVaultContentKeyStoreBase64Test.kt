package app.opentasks.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidVaultContentKeyStoreBase64Test {
    @Test
    fun decodableNoncanonicalInputClearsRejectedDecodedBytes() {
        val boundary = NoncanonicalBase64Boundary()

        assertThrows(IllegalStateException::class.java) {
            decodeCanonicalLocalEnvelopeBase64("/x==", boundary)
        }

        assertArrayEquals(byteArrayOf(0), boundary.decoded)
    }

    private class NoncanonicalBase64Boundary : LocalEnvelopeBase64Boundary {
        val decoded = byteArrayOf(0xff.toByte())

        override fun encode(bytes: ByteArray): String {
            check(bytes === decoded)
            return "/w=="
        }

        override fun decode(encoded: String): ByteArray {
            check(encoded == "/x==")
            return decoded
        }
    }
}
