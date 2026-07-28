package app.opentasks.core.data

import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.sync.CloudFormatException
import app.opentasks.core.sync.CloudFormatFailure
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudHeaderIdentityEncoding
import app.opentasks.core.sync.CloudObjectFormat
import app.opentasks.core.sync.CloudObjectFrameCodec
import java.io.InputStream
import java.security.GeneralSecurityException

interface AuthenticatedCloudObjectCodec {
    fun encrypt(
        identity: CloudHeaderIdentity,
        plaintext: ByteArray,
        key: VaultKey,
    ): ByteArray

    fun decrypt(
        source: InputStream,
        totalLength: Long,
        key: VaultKey,
    ): CloudDecodeResult
}

enum class CloudDecodeFailure {
    MALFORMED_FRAME,
    UNSUPPORTED_FORMAT,
    LIMIT_EXCEEDED,
    LENGTH_MISMATCH,
    CHECKSUM_MISMATCH,
    TRUNCATED,
    AUTHENTICATION_FAILED,
}

sealed interface CloudDecodeResult {
    data class Success(
        val value: DecryptedCloudObject,
    ) : CloudDecodeResult

    data class Failure(
        val reason: CloudDecodeFailure,
    ) : CloudDecodeResult
}

class DecryptedCloudObject internal constructor(
    val identity: CloudHeaderIdentity,
    plaintext: ByteArray,
) : AutoCloseable {
    private var plaintextBytes: ByteArray? = plaintext

    fun copyPlaintext(): ByteArray = synchronized(this) {
        checkNotNull(plaintextBytes) {
            "Plaintext ownership has already ended"
        }.copyOf()
    }

    fun takePlaintext(): ByteArray = synchronized(this) {
        checkNotNull(plaintextBytes) {
            "Plaintext ownership has already ended"
        }.also {
            plaintextBytes = null
        }
    }

    override fun close() = synchronized(this) {
        plaintextBytes?.fill(0)
        plaintextBytes = null
    }
}

class DefaultAuthenticatedCloudObjectCodec(
    private val crypto: VaultCrypto,
    private val frameCodec: CloudObjectFrameCodec = CloudObjectFormat,
) : AuthenticatedCloudObjectCodec {
    override fun encrypt(
        identity: CloudHeaderIdentity,
        plaintext: ByteArray,
        key: VaultKey,
    ): ByteArray {
        val associatedData =
            CloudHeaderIdentityEncoding.associatedData(identity)
        val ciphertext = try {
            crypto.encryptBytes(key, plaintext, associatedData)
        } finally {
            associatedData.fill(0)
        }
        return try {
            frameCodec.encode(identity, ciphertext)
        } finally {
            ciphertext.fill(0)
        }
    }

    override fun decrypt(
        source: InputStream,
        totalLength: Long,
        key: VaultKey,
    ): CloudDecodeResult {
        val frame = try {
            frameCodec.decode(source, totalLength)
        } catch (failure: CloudFormatException) {
            return CloudDecodeResult.Failure(failure.failure.toDecodeFailure())
        }
        val ciphertext = frame.takeCiphertext()
        val associatedData =
            CloudHeaderIdentityEncoding.associatedData(frame.header.identity)
        return try {
            CloudDecodeResult.Success(
                DecryptedCloudObject(
                    identity = frame.header.identity,
                    plaintext = crypto.decryptBytes(
                        key,
                        ciphertext,
                        associatedData,
                    ),
                ),
            )
        } catch (_: GeneralSecurityException) {
            CloudDecodeResult.Failure(
                CloudDecodeFailure.AUTHENTICATION_FAILED,
            )
        } finally {
            associatedData.fill(0)
            ciphertext.fill(0)
        }
    }
}

private fun CloudFormatFailure.toDecodeFailure(): CloudDecodeFailure =
    when (this) {
        CloudFormatFailure.MALFORMED ->
            CloudDecodeFailure.MALFORMED_FRAME
        CloudFormatFailure.UNSUPPORTED_FORMAT ->
            CloudDecodeFailure.UNSUPPORTED_FORMAT
        CloudFormatFailure.LIMIT_EXCEEDED ->
            CloudDecodeFailure.LIMIT_EXCEEDED
        CloudFormatFailure.LENGTH_MISMATCH ->
            CloudDecodeFailure.LENGTH_MISMATCH
        CloudFormatFailure.CHECKSUM_MISMATCH ->
            CloudDecodeFailure.CHECKSUM_MISMATCH
        CloudFormatFailure.TRUNCATED ->
            CloudDecodeFailure.TRUNCATED
    }
