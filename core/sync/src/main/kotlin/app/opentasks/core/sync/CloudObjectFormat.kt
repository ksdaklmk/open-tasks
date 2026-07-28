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

    fun decode(source: InputStream, totalLength: Long): CloudObjectFrame
}

@OptIn(ExperimentalSerializationApi::class)
object CloudObjectFormat : CloudObjectFrameCodec {
    private const val MAGIC = "OPEN_TASKS"
    private const val SCHEMA_VERSION = 1
    private const val CRYPTO_VERSION = 1
    private const val READER_VERSION = 1
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
        require(header.ciphertextLength == ciphertext.size.toLong()) {
            "Ciphertext length does not match header"
        }
        require(header.ciphertextSha256 == sha256(ciphertext)) {
            "Ciphertext checksum does not match header"
        }

        val headerBytes = canonicalHeaderBytes(header)
        require(headerBytes.size <= CloudBounds.MAX_HEADER_BYTES) {
            "Cloud header exceeds ${CloudBounds.MAX_HEADER_BYTES} bytes"
        }
        val frameLength = checkedFrameLength(headerBytes.size, header.ciphertextLength)
        require(frameLength <= Int.MAX_VALUE) { "Cloud frame is too large" }

        return ByteArray(frameLength.toInt()).also { frame ->
            ByteBuffer.wrap(frame, 0, LENGTH_PREFIX_BYTES).putInt(headerBytes.size)
            headerBytes.copyInto(frame, LENGTH_PREFIX_BYTES)
            ciphertext.copyInto(frame, LENGTH_PREFIX_BYTES + headerBytes.size)
        }
    }

    override fun decode(source: InputStream, totalLength: Long): CloudObjectFrame {
        val prefix = readExact(source, LENGTH_PREFIX_BYTES, "length prefix")
        val headerLength = ByteBuffer.wrap(prefix).int
        require(headerLength > 0) { "Cloud header length must be positive" }
        require(headerLength <= CloudBounds.MAX_HEADER_BYTES) {
            "Cloud header exceeds ${CloudBounds.MAX_HEADER_BYTES} bytes"
        }

        val headerBytes = readExact(source, headerLength, "header")
        val header = decodeCanonicalHeader(headerBytes)
        val expectedLength = checkedFrameLength(headerLength, header.ciphertextLength)
        require(totalLength == expectedLength) {
            "Cloud frame length does not match its declaration"
        }

        val ciphertext = readExactOwned(
            source,
            header.ciphertextLength.toInt(),
            "ciphertext",
        )
        require(header.ciphertextSha256 == sha256(ciphertext)) {
            "Ciphertext checksum does not match header"
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
            throw IllegalArgumentException("Cloud header is not valid UTF-8", failure)
        }
        val header = try {
            json.decodeFromString<CloudObjectHeader>(text)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Cloud header is not valid JSON", failure)
        }
        validateHeader(header)
        require(headerBytes.contentEquals(canonicalHeaderBytes(header))) {
            "Cloud header is not canonical"
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
            throw IllegalArgumentException("Cloud header cannot be encoded as UTF-8", failure)
        }
        return ByteArray(encoded.remaining()).also(encoded::get)
    }

    private fun validateHeader(header: CloudObjectHeader) {
        require(header.magic == MAGIC) { "Unsupported cloud object magic" }
        require(header.schemaVersion == SCHEMA_VERSION) {
            "Unsupported cloud schema version ${header.schemaVersion}"
        }
        require(header.cryptoVersion == CRYPTO_VERSION) {
            "Unsupported cloud crypto version ${header.cryptoVersion}"
        }
        require(header.minimumReaderVersion in 1..READER_VERSION) {
            "Unsupported minimum reader version ${header.minimumReaderVersion}"
        }
        require(header.vaultId.isNotBlank()) { "Vault ID must not be blank" }
        require(header.objectId.isNotBlank()) { "Object ID must not be blank" }
        requireUtf8Encodable(header.vaultId, "Vault ID")
        requireUtf8Encodable(header.objectId, "Object ID")
        require(header.ciphertextLength > 0) { "Ciphertext length must be positive" }
        require(
            header.ciphertextLength <=
                CloudBounds.maximumCiphertextBytes(header.family),
        ) {
            "Ciphertext exceeds the ${header.family} bound"
        }
        require(lowercaseSha256.matches(header.ciphertextSha256)) {
            "Ciphertext checksum must be lowercase SHA-256"
        }

        if (header.family == CloudObjectFamily.ATTACHMENT_CHUNK) {
            val count = requireNotNull(header.chunkCount) {
                "Attachment chunk count is required"
            }
            val index = requireNotNull(header.chunkIndex) {
                "Attachment chunk index is required"
            }
            require(count in 1..CloudBounds.MAX_ATTACHMENT_CHUNKS) {
                "Attachment chunk count is out of bounds"
            }
            require(index in 0 until count) {
                "Attachment chunk index is out of bounds"
            }
        } else {
            require(header.chunkIndex == null && header.chunkCount == null) {
                "Chunk metadata is attachment-only"
            }
        }
    }

    private fun requireUtf8Encodable(value: String, label: String) {
        try {
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
        } catch (failure: Exception) {
            throw IllegalArgumentException("$label cannot be encoded as UTF-8", failure)
        }
    }

    private fun checkedFrameLength(headerLength: Int, ciphertextLength: Long): Long =
        try {
            Math.addExact(
                Math.addExact(LENGTH_PREFIX_BYTES.toLong(), headerLength.toLong()),
                ciphertextLength,
            )
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("Cloud frame length overflows", failure)
        }

    private fun readExact(source: InputStream, size: Int, label: String): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = source.read(bytes, offset, size - offset)
            if (count < 0) {
                throw IllegalArgumentException("Truncated cloud $label")
            }
            if (count == 0) {
                val next = source.read()
                if (next < 0) {
                    throw IllegalArgumentException("Truncated cloud $label")
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
                throw IllegalArgumentException("Truncated cloud $label")
            }
            if (count == 0) {
                val next = source.read()
                if (next < 0) {
                    throw IllegalArgumentException("Truncated cloud $label")
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
