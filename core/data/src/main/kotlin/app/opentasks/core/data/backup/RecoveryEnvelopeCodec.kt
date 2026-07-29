package app.opentasks.core.data.backup

import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.model.VaultId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

@Serializable
data class RecoveryEnvelopePayloadV1(
    val formatVersion: Int,
    val kdfAlgorithm: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val saltBase64: String,
    val nonceBase64: String,
    val wrappedKeysetBase64: String,
)

object RecoveryEnvelopeCodec {
    const val MAX_CANONICAL_BYTES: Int = 16 * 1024

    fun encode(envelope: VaultKeyEnvelope): ByteArray {
        val encoded = StrictRecoveryEnvelopeJson.json
            .encodeToString(toPayload(envelope))
            .toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_CANONICAL_BYTES) {
            "Recovery envelope exceeds $MAX_CANONICAL_BYTES bytes"
        }
        return encoded
    }

    fun decode(source: ByteArray): VaultKeyEnvelope {
        require(source.size <= MAX_CANONICAL_BYTES) {
            "Recovery envelope exceeds $MAX_CANONICAL_BYTES bytes"
        }
        return decodeOwned(source.copyOf())
    }

    fun decodeOwned(source: ByteArray): VaultKeyEnvelope {
        try {
            require(source.isNotEmpty()) { "Recovery envelope is empty" }
            require(source.size <= MAX_CANONICAL_BYTES) {
                "Recovery envelope exceeds $MAX_CANONICAL_BYTES bytes"
            }
            val payload = try {
                StrictRecoveryEnvelopeJson.json.decodeFromString<RecoveryEnvelopePayloadV1>(
                    strictUtf8(source),
                )
            } catch (failure: SerializationException) {
                throw IllegalArgumentException("Invalid recovery envelope", failure)
            }
            val envelope = fromPayload(payload)
            try {
                val canonical = encode(envelope)
                try {
                    require(source.contentEquals(canonical)) {
                        "Recovery envelope is not canonical"
                    }
                } finally {
                    canonical.fill(0)
                }
                return envelope
            } catch (failure: Throwable) {
                envelope.clear()
                throw failure
            }
        } finally {
            source.fill(0)
        }
    }

    fun toPayload(envelope: VaultKeyEnvelope): RecoveryEnvelopePayloadV1 {
        validateEnvelope(envelope)
        val encoder = Base64.getEncoder().withoutPadding()
        return RecoveryEnvelopePayloadV1(
            formatVersion = envelope.formatVersion,
            kdfAlgorithm = KDF_ALGORITHM,
            memoryKiB = envelope.kdf.memoryKiB,
            iterations = envelope.kdf.iterations,
            parallelism = envelope.kdf.parallelism,
            saltBase64 = encoder.encodeToString(envelope.kdf.salt),
            nonceBase64 = encoder.encodeToString(envelope.nonce),
            wrappedKeysetBase64 = encoder.encodeToString(envelope.wrappedKeyset),
        )
    }

    fun fromPayload(payload: RecoveryEnvelopePayloadV1): VaultKeyEnvelope {
        validateMetadata(payload)
        val salt = decodeCanonicalBase64(payload.saltBase64, "Recovery envelope salt")
        var nonce: ByteArray? = null
        var wrappedKeyset: ByteArray? = null
        try {
            nonce = decodeCanonicalBase64(payload.nonceBase64, "Recovery envelope nonce")
            wrappedKeyset = decodeCanonicalBase64(
                payload.wrappedKeysetBase64,
                "Recovery envelope wrapped keyset",
            )
            val envelope = VaultKeyEnvelope(
                formatVersion = payload.formatVersion,
                kdf = Argon2Metadata(
                    salt = salt,
                    memoryKiB = payload.memoryKiB,
                    iterations = payload.iterations,
                    parallelism = payload.parallelism,
                ),
                nonce = nonce,
                wrappedKeyset = wrappedKeyset,
            )
            validateEnvelope(envelope)
            return envelope
        } catch (failure: Throwable) {
            salt.fill(0)
            nonce?.fill(0)
            wrappedKeyset?.fill(0)
            throw failure
        }
    }

    fun toEntity(
        vaultId: VaultId,
        envelope: VaultKeyEnvelope,
    ): VaultRecoveryEnvelopeEntity {
        validateEnvelope(envelope)
        return VaultRecoveryEnvelopeEntity(
            vaultId = vaultId.value,
            formatVersion = envelope.formatVersion,
            kdfAlgorithm = KDF_ALGORITHM,
            memoryKiB = envelope.kdf.memoryKiB,
            iterations = envelope.kdf.iterations,
            parallelism = envelope.kdf.parallelism,
            salt = envelope.kdf.salt.copyOf(),
            nonce = envelope.nonce.copyOf(),
            wrappedKeyset = envelope.wrappedKeyset.copyOf(),
        )
    }

    fun fromEntity(entity: VaultRecoveryEnvelopeEntity): VaultKeyEnvelope {
        val envelope = VaultKeyEnvelope(
            formatVersion = entity.formatVersion,
            kdf = Argon2Metadata(
                salt = entity.salt.copyOf(),
                memoryKiB = entity.memoryKiB,
                iterations = entity.iterations,
                parallelism = entity.parallelism,
            ),
            nonce = entity.nonce.copyOf(),
            wrappedKeyset = entity.wrappedKeyset.copyOf(),
        )
        try {
            require(entity.kdfAlgorithm == KDF_ALGORITHM) {
                "Unsupported recovery KDF ${entity.kdfAlgorithm}"
            }
            validateEnvelope(envelope)
            return envelope
        } catch (failure: Throwable) {
            envelope.clear()
            throw failure
        }
    }

    private fun validateMetadata(payload: RecoveryEnvelopePayloadV1) {
        require(payload.formatVersion == FORMAT_VERSION) {
            "Unsupported recovery envelope format ${payload.formatVersion}"
        }
        require(payload.kdfAlgorithm == KDF_ALGORITHM) {
            "Unsupported recovery KDF ${payload.kdfAlgorithm}"
        }
        require(payload.memoryKiB == MEMORY_KIB) {
            "Unsupported recovery memory cost ${payload.memoryKiB}"
        }
        require(payload.iterations == ITERATIONS) {
            "Unsupported recovery iteration count ${payload.iterations}"
        }
        require(payload.parallelism == PARALLELISM) {
            "Unsupported recovery parallelism ${payload.parallelism}"
        }
    }

    private fun validateEnvelope(envelope: VaultKeyEnvelope) {
        require(envelope.formatVersion == FORMAT_VERSION) {
            "Unsupported recovery envelope format ${envelope.formatVersion}"
        }
        require(envelope.kdf.memoryKiB == MEMORY_KIB) {
            "Unsupported recovery memory cost ${envelope.kdf.memoryKiB}"
        }
        require(envelope.kdf.iterations == ITERATIONS) {
            "Unsupported recovery iteration count ${envelope.kdf.iterations}"
        }
        require(envelope.kdf.parallelism == PARALLELISM) {
            "Unsupported recovery parallelism ${envelope.kdf.parallelism}"
        }
        require(envelope.kdf.salt.size == SALT_BYTES) {
            "Recovery envelope salt must contain $SALT_BYTES bytes"
        }
        require(envelope.nonce.size == NONCE_BYTES) {
            "Recovery envelope nonce must contain $NONCE_BYTES bytes"
        }
        require(
            envelope.wrappedKeyset.isNotEmpty() &&
                envelope.wrappedKeyset.size <= MAX_WRAPPED_KEYSET_BYTES,
        ) {
            "Recovery envelope wrapped keyset must contain 1..$MAX_WRAPPED_KEYSET_BYTES bytes"
        }
    }

    private fun decodeCanonicalBase64(
        encoded: String,
        label: String,
    ): ByteArray {
        require(encoded.length <= MAX_BASE64_CHARACTERS) { "$label is too large" }
        require(!encoded.contains('=')) { "$label uses padded Base64" }
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("$label is not Base64", failure)
        }
        try {
            require(
                Base64.getEncoder().withoutPadding().encodeToString(decoded) == encoded,
            ) {
                "$label is not canonical Base64"
            }
            return decoded
        } catch (failure: Throwable) {
            decoded.fill(0)
            throw failure
        }
    }

    private fun strictUtf8(source: ByteArray): String = try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(source))
            .toString()
    } catch (failure: Exception) {
        throw IllegalArgumentException("Recovery envelope is not valid UTF-8", failure)
    }

    private fun VaultKeyEnvelope.clear() {
        kdf.salt.fill(0)
        nonce.fill(0)
        wrappedKeyset.fill(0)
    }

    private const val FORMAT_VERSION = 1
    private const val KDF_ALGORITHM = "ARGON2ID"
    private const val MEMORY_KIB = 65_536
    private const val ITERATIONS = 3
    private const val PARALLELISM = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val MAX_WRAPPED_KEYSET_BYTES = 8 * 1024
    private const val MAX_BASE64_CHARACTERS = 11 * 1024
}

@OptIn(ExperimentalSerializationApi::class)
private object StrictRecoveryEnvelopeJson {
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowTrailingComma = false
    }
}
