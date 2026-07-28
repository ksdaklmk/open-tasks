package app.opentasks.core.sync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

interface CloudObjectFrameCodec {
    fun encode(header: CloudObjectHeader, ciphertext: ByteArray): ByteArray

    fun encode(identity: CloudHeaderIdentity, ciphertext: ByteArray): ByteArray

    fun decode(source: InputStream, totalLength: Long): CloudObjectFrame
}

@OptIn(ExperimentalSerializationApi::class)
object CloudObjectFormat : CloudObjectFrameCodec {
    private const val MAGIC = "OPEN_TASKS"
    private const val LENGTH_PREFIX_BYTES = 4
    private const val MAX_CIPHERTEXT_READ_BUFFER_BYTES = 8 * 1024
    private val lowercaseSha256 = Regex("[0-9a-f]{64}")
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        allowTrailingComma = false
    }

    override fun encode(header: CloudObjectHeader, ciphertext: ByteArray): ByteArray {
        validateHeader(header)
        if (header.ciphertextLength != ciphertext.size.toLong()) {
            throw CloudFormatException(
                CloudFormatFailure.LENGTH_MISMATCH,
                "Ciphertext length does not match header",
            )
        }
        if (header.ciphertextSha256 != sha256(ciphertext)) {
            throw CloudFormatException(
                CloudFormatFailure.CHECKSUM_MISMATCH,
                "Ciphertext checksum does not match header",
            )
        }

        val headerBytes = canonicalHeaderBytes(header)
        if (headerBytes.size > CloudBounds.MAX_HEADER_BYTES) {
            throw CloudFormatException(
                CloudFormatFailure.LIMIT_EXCEEDED,
                "Cloud header exceeds ${CloudBounds.MAX_HEADER_BYTES} bytes",
            )
        }
        val frameLength = checkedFrameLength(headerBytes.size, header.ciphertextLength)
        if (frameLength > Int.MAX_VALUE) {
            throw CloudFormatException(
                CloudFormatFailure.LIMIT_EXCEEDED,
                "Cloud frame is too large",
            )
        }

        return ByteArray(frameLength.toInt()).also { frame ->
            ByteBuffer.wrap(frame, 0, LENGTH_PREFIX_BYTES).putInt(headerBytes.size)
            headerBytes.copyInto(frame, LENGTH_PREFIX_BYTES)
            ciphertext.copyInto(frame, LENGTH_PREFIX_BYTES + headerBytes.size)
        }
    }

    override fun encode(
        identity: CloudHeaderIdentity,
        ciphertext: ByteArray,
    ): ByteArray {
        validateCloudHeaderIdentity(identity)
        return encode(
            CloudObjectHeader(
                family = identity.family,
                schemaVersion = identity.schemaVersion,
                cryptoVersion = identity.cryptoVersion,
                minimumReaderVersion = identity.minimumReaderVersion,
                vaultId = identity.vaultId,
                objectId = identity.objectId,
                ciphertextLength = ciphertext.size.toLong(),
                ciphertextSha256 = sha256(ciphertext),
                chunkIndex = identity.chunkIndex,
                chunkCount = identity.chunkCount,
            ),
            ciphertext,
        )
    }

    override fun decode(source: InputStream, totalLength: Long): CloudObjectFrame {
        val prefix = readExact(source, LENGTH_PREFIX_BYTES, "length prefix")
        val headerLength = ByteBuffer.wrap(prefix).int
        if (headerLength <= 0) {
            throw CloudFormatException(
                CloudFormatFailure.MALFORMED,
                "Cloud header length must be positive",
            )
        }
        if (headerLength > CloudBounds.MAX_HEADER_BYTES) {
            throw CloudFormatException(
                CloudFormatFailure.LIMIT_EXCEEDED,
                "Cloud header exceeds ${CloudBounds.MAX_HEADER_BYTES} bytes",
            )
        }

        val headerBytes = readExact(source, headerLength, "header")
        val header = decodeCanonicalHeader(headerBytes)
        val expectedLength = checkedFrameLength(headerLength, header.ciphertextLength)
        if (totalLength != expectedLength) {
            throw CloudFormatException(
                CloudFormatFailure.LENGTH_MISMATCH,
                "Cloud frame length does not match its declaration",
            )
        }

        val ciphertext = readExactOwned(
            source,
            header.ciphertextLength.toInt(),
            "ciphertext",
        )
        if (header.ciphertextSha256 != sha256(ciphertext)) {
            throw CloudFormatException(
                CloudFormatFailure.CHECKSUM_MISMATCH,
                "Ciphertext checksum does not match header",
            )
        }
        return CloudObjectFrame(header, ciphertext)
    }

    private fun decodeCanonicalHeader(headerBytes: ByteArray): CloudObjectHeader {
        val text = try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(headerBytes))
                .toString()
        } catch (failure: Exception) {
            throw CloudFormatException(
                CloudFormatFailure.MALFORMED,
                "Cloud header is not valid UTF-8",
                failure,
            )
        }
        val header = try {
            json.decodeFromString<CloudObjectHeader>(text)
        } catch (failure: Exception) {
            throw CloudFormatException(
                CloudFormatFailure.MALFORMED,
                "Cloud header is not valid JSON",
                failure,
            )
        }
        validateHeader(header)
        if (!headerBytes.contentEquals(canonicalHeaderBytes(header))) {
            throw CloudFormatException(
                CloudFormatFailure.MALFORMED,
                "Cloud header is not canonical",
            )
        }
        return header
    }

    private fun canonicalHeaderBytes(header: CloudObjectHeader): ByteArray {
        val text = json.encodeToString(header)
        val encoded = try {
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
        } catch (failure: Exception) {
            throw CloudFormatException(
                CloudFormatFailure.MALFORMED,
                "Cloud header cannot be encoded as UTF-8",
                failure,
            )
        }
        return ByteArray(encoded.remaining()).also(encoded::get)
    }

    private fun validateHeader(header: CloudObjectHeader) {
        if (header.magic != MAGIC) {
            throw CloudFormatException(
                CloudFormatFailure.UNSUPPORTED_FORMAT,
                "Unsupported cloud object magic",
            )
        }
        validateCloudHeaderIdentity(header.identity)
        if (header.ciphertextLength <= 0) {
            throw CloudFormatException(
                CloudFormatFailure.LENGTH_MISMATCH,
                "Ciphertext length must be positive",
            )
        }
        if (header.ciphertextLength >= Long.MAX_VALUE - LENGTH_PREFIX_BYTES) {
            throw CloudFormatException(
                CloudFormatFailure.LENGTH_MISMATCH,
                "Cloud frame length overflows",
            )
        }
        if (header.ciphertextLength > CloudBounds.maximumCiphertextBytes(header.family)) {
            throw CloudFormatException(
                CloudFormatFailure.LIMIT_EXCEEDED,
                "Ciphertext exceeds the ${header.family} bound",
            )
        }
        if (!lowercaseSha256.matches(header.ciphertextSha256)) {
            throw CloudFormatException(
                CloudFormatFailure.MALFORMED,
                "Ciphertext checksum must be lowercase SHA-256",
            )
        }
    }

    private fun checkedFrameLength(headerLength: Int, ciphertextLength: Long): Long =
        try {
            Math.addExact(
                Math.addExact(LENGTH_PREFIX_BYTES.toLong(), headerLength.toLong()),
                ciphertextLength,
            )
        } catch (failure: ArithmeticException) {
            throw CloudFormatException(
                CloudFormatFailure.LENGTH_MISMATCH,
                "Cloud frame length overflows",
                failure,
            )
        }

    private fun readExact(source: InputStream, size: Int, label: String): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = source.read(bytes, offset, size - offset)
            if (count < 0) {
                throw CloudFormatException(
                    CloudFormatFailure.TRUNCATED,
                    "Truncated cloud $label",
                )
            }
            if (count == 0) {
                val next = source.read()
                if (next < 0) {
                    throw CloudFormatException(
                        CloudFormatFailure.TRUNCATED,
                        "Truncated cloud $label",
                    )
                }
                bytes[offset] = next.toByte()
                offset += 1
            } else {
                offset += count
            }
        }
        return bytes
    }

    private fun readExactOwned(source: InputStream, size: Int, label: String): ByteArray {
        val owned = ByteArray(size)
        val scratch = ByteArray(minOf(size, MAX_CIPHERTEXT_READ_BUFFER_BYTES))
        var offset = 0
        while (offset < size) {
            val requested = minOf(scratch.size, size - offset)
            val count = source.read(scratch, 0, requested)
            if (count < 0) {
                throw CloudFormatException(
                    CloudFormatFailure.TRUNCATED,
                    "Truncated cloud $label",
                )
            }
            if (count == 0) {
                val next = source.read()
                if (next < 0) {
                    throw CloudFormatException(
                        CloudFormatFailure.TRUNCATED,
                        "Truncated cloud $label",
                    )
                }
                owned[offset] = next.toByte()
                offset += 1
            } else {
                scratch.copyInto(owned, offset, 0, count)
                offset += count
            }
        }
        return owned
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val alphabet = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }
}
