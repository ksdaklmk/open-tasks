package app.opentasks.core.domain

import app.opentasks.core.model.CloudObjectId

data class EncryptedSource(
    val bytes: ByteArray,
    val checksum: String,
)

data class CloudObject(
    val id: CloudObjectId,
    val name: String,
    val checksum: String,
)

data class ChangePage(
    val changes: List<CloudObject>,
    val nextPageToken: String?,
    val newStartPageToken: String?,
)

interface CloudObjectStore {
    suspend fun listChanges(pageToken: String?): ChangePage
    suspend fun upload(objectName: String, source: EncryptedSource): CloudObject
    suspend fun download(id: CloudObjectId): EncryptedSource
    suspend fun delete(id: CloudObjectId)
}
