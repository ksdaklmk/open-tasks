package app.opentasks.core.sync

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object CloudHeaderIdentityEncoding {
    private const val DOMAIN = "open-tasks:cloud-header-identity:v1"

    fun associatedData(identity: CloudHeaderIdentity): ByteArray {
        validateCloudHeaderIdentity(identity)
        val fields = listOf(
            DOMAIN,
            identity.family.name,
            identity.schemaVersion.toString(),
            identity.cryptoVersion.toString(),
            identity.minimumReaderVersion.toString(),
            identity.vaultId,
            identity.objectId,
            identity.chunkIndex?.toString().orEmpty(),
            identity.chunkCount?.toString().orEmpty(),
        ).map(::strictUtf8)
        val size = fields.sumOf { Integer.BYTES + it.size }
        return ByteBuffer.allocate(size).also { target ->
            fields.forEach { bytes ->
                target.putInt(bytes.size)
                target.put(bytes)
            }
        }.array()
    }
}

internal fun validateCloudHeaderIdentity(identity: CloudHeaderIdentity) {
    if (identity.schemaVersion != 1) {
        throw CloudFormatException(
            CloudFormatFailure.UNSUPPORTED_FORMAT,
            "Unsupported cloud schema version ${identity.schemaVersion}",
        )
    }
    if (identity.cryptoVersion != 1) {
        throw CloudFormatException(
            CloudFormatFailure.UNSUPPORTED_FORMAT,
            "Unsupported cloud crypto version ${identity.cryptoVersion}",
        )
    }
    if (identity.minimumReaderVersion != 1) {
        throw CloudFormatException(
            CloudFormatFailure.UNSUPPORTED_FORMAT,
            "Unsupported minimum reader version ${identity.minimumReaderVersion}",
        )
    }
    requireIdentity(
        identity.vaultId.isNotBlank(),
        "Vault ID must not be blank",
    )
    requireIdentity(
        identity.objectId.isNotBlank(),
        "Object ID must not be blank",
    )
    strictUtf8(identity.vaultId, "Vault ID")
    strictUtf8(identity.objectId, "Object ID")

    if (identity.family == CloudObjectFamily.ATTACHMENT_CHUNK) {
        val count = identity.chunkCount ?: throw CloudFormatException(
            CloudFormatFailure.MALFORMED,
            "Attachment chunk count is required",
        )
        val index = identity.chunkIndex ?: throw CloudFormatException(
            CloudFormatFailure.MALFORMED,
            "Attachment chunk index is required",
        )
        requireIdentity(
            count in 1..CloudBounds.MAX_ATTACHMENT_CHUNKS,
            "Attachment chunk count is out of bounds",
        )
        requireIdentity(
            index in 0 until count,
            "Attachment chunk index is out of bounds",
        )
    } else {
        requireIdentity(
            identity.chunkIndex == null && identity.chunkCount == null,
            "Chunk metadata is attachment-only",
        )
    }
}

private fun requireIdentity(condition: Boolean, message: String) {
    if (!condition) {
        throw CloudFormatException(CloudFormatFailure.MALFORMED, message)
    }
}

private fun strictUtf8(value: String, label: String = "Cloud identity field"): ByteArray {
    val encoded = try {
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
    } catch (failure: Exception) {
        throw CloudFormatException(
            CloudFormatFailure.MALFORMED,
            "$label cannot be encoded as UTF-8",
            failure,
        )
    }
    return ByteArray(encoded.remaining()).also(encoded::get)
}
