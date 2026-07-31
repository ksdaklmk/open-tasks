package app.opentasks.core.data.backup

import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.data.backup.drive.DriveChunkResult
import app.opentasks.core.data.backup.drive.DriveCreateRequest
import app.opentasks.core.data.backup.drive.DriveCreateResult
import app.opentasks.core.data.backup.drive.DriveDownloadReceipt
import app.opentasks.core.data.backup.drive.DriveFileMetadata
import app.opentasks.core.data.backup.drive.DriveTransportException
import app.opentasks.core.data.backup.drive.DriveTransportFailureCategory
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.CreateSmallResult
import app.opentasks.core.domain.DeleteObjectResult
import app.opentasks.core.domain.ImmutableDownloadResult
import app.opentasks.core.domain.ImmutableUploadRequest
import app.opentasks.core.domain.ImmutableUploadResult
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.OwnedRemoteFile
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.domain.RemoteListPage
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.domain.RemoteListedObject
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteObjectLifecycle
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.WriterEpoch
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant

/**
 * Create-only Google Drive implementation of [CreateOnlyBackupObjectStore].
 *
 * Every provider file is named a constant string per [RemoteObjectRoleV1];
 * identity comes from the generated [ProviderObjectId], never the name. App
 * properties carry only a bounded format tag, the role, the opaque lineage
 * (when known to the caller), the public logical object ID, the writer
 * epoch, and the cleanup owner device — never a [app.opentasks.core.model.VaultId],
 * account data, generation, content, or credential material.
 *
 * Drive JSON is never authority: an `AlreadyExists`/`Ambiguous` create
 * outcome, and every uncertain resumable-chunk outcome, is resolved only
 * through an exact-ID download compared against the caller's expected
 * length and SHA-256 — never assumed from provider metadata. Resumable
 * upload state (generated ID, expected length/digest, owning operation,
 * session URI, confirmed offset) is durably persisted through
 * [RemoteBackupTransferStore] before the first network mutation, and a
 * resumable session's URI is cleared only once its bytes are verified.
 */
class CreateOnlyDriveObjectStore(
    private val transport: CreateOnlyDriveTransport,
    private val transferStore: RemoteBackupTransferStore,
    private val stagingRoot: File,
) : CreateOnlyBackupObjectStore {

    override suspend fun generateProviderIds(
        count: Int,
        role: RemoteObjectRoleV1,
    ): List<ProviderObjectId> {
        val bound = if (role in SINGLETON_ROLES) 1 else MAX_GENERATED_IDS_PER_REQUEST
        require(count in 1..bound) {
            "Generated ID count is outside its bound for the requested role"
        }
        return transport.generateAppDataFileIds(count).map(ProviderObjectId::of)
    }

    override suspend fun createSmallIfAbsent(
        providerObjectId: ProviderObjectId,
        metadata: RemoteListedObject,
        bytes: OwnedRemoteBytes,
    ): CreateSmallResult {
        require(metadata.providerObjectId == providerObjectId) {
            "Create metadata provider identity does not match the requested provider identity"
        }
        val role = requireNotNull(metadata.role) { "Create metadata role is required" }
        val driveMetadata = metadataFor(
            providerObjectId = providerObjectId,
            role = role,
            lineageId = null,
            logicalObjectId = metadata.logicalObjectId,
            writerEpoch = metadata.writerEpoch,
            ownerDeviceId = metadata.ownerDeviceId,
        )
        val content = bytes.take()
        return try {
            when (transport.createFileIfAbsent(DriveCreateRequest(driveMetadata, content))) {
                DriveCreateResult.Created -> CreateSmallResult.Created
                DriveCreateResult.AlreadyExists -> CreateSmallResult.AlreadyExists
                DriveCreateResult.Ambiguous -> CreateSmallResult.Ambiguous
            }
        } catch (exception: DriveTransportException) {
            CreateSmallResult.Failed(exception.category.toRemoteFailure())
        } finally {
            content.fill(0)
            bytes.close()
        }
    }

    override suspend fun readSmall(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
    ): ReadSmallResult {
        require(maximumBytes >= 0) { "Maximum bytes is negative" }
        val staged = createStagingFile("read")
        return try {
            transport.downloadFile(providerObjectId.value, staged, maximumBytes)
            ReadSmallResult.Found(StagedRemoteBytes(staged.readBytes()))
        } catch (exception: DriveTransportException) {
            if (exception.category == DriveTransportFailureCategory.MISSING) {
                ReadSmallResult.Missing
            } else {
                ReadSmallResult.Failed(exception.category.toRemoteFailure())
            }
        } finally {
            staged.delete()
        }
    }

    override suspend fun list(request: RemoteListRequest): RemoteListPage {
        require(request.pageSize in 1..MAX_PAGE_SIZE) { "Page size is outside its bound" }
        request.pageToken?.let {
            require(it.length in 1..MAX_PAGE_TOKEN_CHARACTERS) { "Page token is outside its bound" }
        }
        val query = listQuery(request)
        val page = transport.listAppDataFiles(query, request.pageToken, request.pageSize)
        return RemoteListPage(
            objects = page.files.map { file ->
                RemoteListedObject(
                    providerObjectId = ProviderObjectId.of(file.providerFileId),
                    logicalObjectId = file.appProperties[PROPERTY_LOGICAL_OBJECT],
                    role = file.role?.let { roleName ->
                        runCatching { RemoteObjectRoleV1.valueOf(roleName) }.getOrNull()
                    },
                    writerEpoch = file.appProperties[PROPERTY_EPOCH]
                        ?.toLongOrNull()
                        ?.let { runCatching { WriterEpoch(it) }.getOrNull() },
                    ownerDeviceId = file.appProperties[PROPERTY_OWNER_DEVICE]?.let { value ->
                        runCatching { CloudDeviceId.parse(value) }.getOrNull()
                    },
                )
            },
            nextPageToken = page.nextPageToken,
        )
    }

    override suspend fun uploadImmutable(request: ImmutableUploadRequest): ImmutableUploadResult {
        validateUploadRequest(request)
        val existing = transferStore.objectState(request.lineageId, request.logicalObjectId)
        val state = if (existing != null) {
            require(existing.providerObjectId == request.providerObjectId) {
                "Existing object state targets a different provider object"
            }
            require(
                existing.frameSha256 == request.frameSha256 &&
                    existing.frameLength == request.frameLength,
            ) {
                "Existing object state targets different expected bytes"
            }
            existing
        } else {
            val fresh = RemoteBackupObject(
                lineageId = request.lineageId,
                logicalObjectId = request.logicalObjectId,
                providerObjectId = request.providerObjectId,
                role = request.role,
                writerEpoch = request.writerEpoch,
                ownerDeviceId = request.ownerDeviceId,
                operationId = request.operationId,
                firstGeneration = request.firstGeneration,
                lastGeneration = request.lastGeneration,
                frameLength = request.frameLength,
                frameSha256 = request.frameSha256,
                lifecycle = RemoteObjectLifecycle.PLANNED,
                resumableSessionUri = null,
                uploadedBytes = 0,
                createdAt = Instant.now(),
                verifiedAt = null,
            )
            transferStore.insertObject(fresh)
            fresh
        }

        if (state.verifiedAt != null) return ImmutableUploadResult.UploadedAndVerified

        return if (request.frameLength <= MULTIPART_THRESHOLD_BYTES) {
            uploadViaMultipart(request, state)
        } else {
            uploadViaResumable(request, state)
        }
    }

    override suspend fun downloadImmutable(
        providerObjectId: ProviderObjectId,
        maximumBytes: Long,
        expectedSha256: Sha256Digest,
    ): ImmutableDownloadResult {
        require(maximumBytes >= 0) { "Maximum bytes is negative" }
        val staged = createStagingFile("download")
        return try {
            transport.downloadFile(providerObjectId.value, staged, maximumBytes)
            if (digestMatches(staged, expectedSha256)) {
                ImmutableDownloadResult.Downloaded(StagedRemoteFile(staged))
            } else {
                staged.delete()
                ImmutableDownloadResult.Corrupt
            }
        } catch (exception: DriveTransportException) {
            staged.delete()
            if (exception.category == DriveTransportFailureCategory.MISSING) {
                ImmutableDownloadResult.Missing
            } else {
                ImmutableDownloadResult.Failed(exception.category.toRemoteFailure())
            }
        }
    }

    override suspend fun delete(providerObjectId: ProviderObjectId): DeleteObjectResult =
        try {
            if (transport.deleteFile(providerObjectId.value)) {
                DeleteObjectResult.Deleted
            } else {
                DeleteObjectResult.Missing
            }
        } catch (exception: DriveTransportException) {
            DeleteObjectResult.Failed(exception.category.toRemoteFailure())
        }

    // -- Multipart (<= 5 MiB) -------------------------------------------------------------

    private suspend fun uploadViaMultipart(
        request: ImmutableUploadRequest,
        state: RemoteBackupObject,
    ): ImmutableUploadResult {
        val content = request.frame.file.readBytes()
        val result = try {
            transport.createFileIfAbsent(DriveCreateRequest(metadataFor(request), content))
        } catch (exception: DriveTransportException) {
            return ImmutableUploadResult.Failed(exception.category.toRemoteFailure())
        } finally {
            content.fill(0)
        }
        return when (result) {
            DriveCreateResult.Created -> verifyAndFinish(request, state)
            DriveCreateResult.AlreadyExists, DriveCreateResult.Ambiguous ->
                resolveOccupied(request, state)
        }
    }

    // -- Resumable (> 5 MiB) ---------------------------------------------------------------

    private suspend fun uploadViaResumable(
        request: ImmutableUploadRequest,
        initial: RemoteBackupObject,
    ): ImmutableUploadResult {
        var step: SessionStep = if (initial.resumableSessionUri == null) {
            startSession(request, initial)
        } else {
            resumeSession(request, initial)
        }
        while (step is SessionStep.Continue && step.offset < request.frameLength) {
            step = uploadNextChunk(request, step.state, step.offset)
        }
        return when (step) {
            is SessionStep.Done -> step.result
            is SessionStep.Continue -> verifyAndFinish(request, step.state)
        }
    }

    private suspend fun startSession(
        request: ImmutableUploadRequest,
        state: RemoteBackupObject,
    ): SessionStep {
        val sessionUri = try {
            transport.startResumableCreate(metadataFor(request), request.frameLength).sessionUri
        } catch (exception: DriveTransportException) {
            return exception.asSessionFailure()
        }
        val next = state.copy(
            resumableSessionUri = sessionUri,
            uploadedBytes = 0,
            lifecycle = RemoteObjectLifecycle.UPLOADING,
        )
        transferStore.compareAndSetObject(state, next)
        return SessionStep.Continue(next, 0L)
    }

    private suspend fun resumeSession(
        request: ImmutableUploadRequest,
        state: RemoteBackupObject,
    ): SessionStep {
        val sessionUri = checkNotNull(state.resumableSessionUri)
        val queried = try {
            transport.queryResumableUpload(sessionUri, request.frameLength)
        } catch (exception: DriveTransportException) {
            return exception.asSessionFailure()
        }
        return when (queried) {
            is DriveChunkResult.ResumeAt -> confirmOffset(state, queried.nextByte)
            DriveChunkResult.Complete -> confirmOffset(state, request.frameLength)
            DriveChunkResult.Expired -> startSession(request, state)
            DriveChunkResult.Ambiguous -> SessionStep.Done(resolveOccupied(request, state))
        }
    }

    private suspend fun uploadNextChunk(
        request: ImmutableUploadRequest,
        state: RemoteBackupObject,
        offset: Long,
    ): SessionStep {
        val sessionUri = checkNotNull(state.resumableSessionUri)
        val remaining = request.frameLength - offset
        val chunkLength = minOf(remaining, CHUNK_SIZE_BYTES)
        val chunk = ByteArray(chunkLength.toInt())
        RandomAccessFile(request.frame.file, "r").use { source ->
            source.seek(offset)
            source.readFully(chunk)
        }
        val chunkResult = try {
            transport.uploadChunk(sessionUri, offset, request.frameLength, chunk)
        } catch (exception: DriveTransportException) {
            return exception.asSessionFailure()
        } finally {
            chunk.fill(0)
        }
        return when (chunkResult) {
            is DriveChunkResult.ResumeAt -> confirmOffset(state, chunkResult.nextByte)
            DriveChunkResult.Complete -> SessionStep.Continue(state, request.frameLength)
            DriveChunkResult.Expired -> startSession(request, state)
            DriveChunkResult.Ambiguous -> SessionStep.Done(resolveOccupied(request, state))
        }
    }

    private suspend fun confirmOffset(state: RemoteBackupObject, offset: Long): SessionStep {
        if (offset == state.uploadedBytes) return SessionStep.Continue(state, offset)
        val next = state.copy(uploadedBytes = offset)
        transferStore.compareAndSetObject(state, next)
        return SessionStep.Continue(next, offset)
    }

    private fun DriveTransportException.asSessionFailure(): SessionStep =
        SessionStep.Done(ImmutableUploadResult.Failed(category.toRemoteFailure()))

    private sealed interface SessionStep {
        data class Continue(val state: RemoteBackupObject, val offset: Long) : SessionStep
        data class Done(val result: ImmutableUploadResult) : SessionStep
    }

    // -- Exact-ID verification / resolution -------------------------------------------------

    private suspend fun verifyAndFinish(
        request: ImmutableUploadRequest,
        state: RemoteBackupObject,
    ): ImmutableUploadResult {
        val staged = createStagingFile("verify")
        return try {
            val receipt = try {
                transport.downloadFile(request.providerObjectId.value, staged, request.frameLength)
            } catch (exception: DriveTransportException) {
                return if (exception.category == DriveTransportFailureCategory.MISSING) {
                    ImmutableUploadResult.Failed(RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE)
                } else {
                    ImmutableUploadResult.Failed(exception.category.toRemoteFailure())
                }
            }
            if (exactBytesMatch(receipt, staged, request)) {
                markVerified(request, state)
                ImmutableUploadResult.UploadedAndVerified
            } else {
                ImmutableUploadResult.Failed(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
            }
        } finally {
            staged.delete()
        }
    }

    private suspend fun resolveOccupied(
        request: ImmutableUploadRequest,
        state: RemoteBackupObject,
    ): ImmutableUploadResult {
        val staged = createStagingFile("resolve")
        return try {
            val receipt = try {
                transport.downloadFile(request.providerObjectId.value, staged, request.frameLength)
            } catch (exception: DriveTransportException) {
                val category = if (exception.category == DriveTransportFailureCategory.MISSING) {
                    RemoteBackupFailureCategory.RETRYABLE_PROVIDER
                } else {
                    RemoteBackupFailureCategory.AMBIGUOUS_REMOTE_STATE
                }
                return ImmutableUploadResult.Failed(category)
            }
            if (exactBytesMatch(receipt, staged, request)) {
                markVerified(request, state)
                ImmutableUploadResult.OccupiedByExpectedBytes
            } else {
                ImmutableUploadResult.OccupiedByDifferentBytes
            }
        } finally {
            staged.delete()
        }
    }

    private fun exactBytesMatch(
        receipt: DriveDownloadReceipt,
        staged: File,
        request: ImmutableUploadRequest,
    ): Boolean =
        receipt.byteCount == request.frameLength && digestMatches(staged, request.frameSha256)

    private suspend fun markVerified(request: ImmutableUploadRequest, state: RemoteBackupObject) {
        val verified = state.copy(
            lifecycle = RemoteObjectLifecycle.VERIFIED,
            resumableSessionUri = null,
            uploadedBytes = request.frameLength,
            verifiedAt = Instant.now(),
        )
        transferStore.compareAndSetObject(state, verified)
    }

    // -- Shared helpers ----------------------------------------------------------------------

    private fun listQuery(request: RemoteListRequest): String = buildString {
        appendPropertyClause(PROPERTY_ROLE_KEY, request.role.name)
        append(" and ")
        appendPropertyClause(PROPERTY_LINEAGE, request.lineageId.value)
        request.writerEpoch?.let {
            append(" and ")
            appendPropertyClause(PROPERTY_EPOCH, it.value.toString())
        }
        request.ownerDeviceId?.let {
            append(" and ")
            appendPropertyClause(PROPERTY_OWNER_DEVICE, it.value)
        }
    }

    private fun StringBuilder.appendPropertyClause(key: String, value: String) {
        append(APP_PROPERTIES)
        append(" has { key='").append(key)
        append("' and value='").append(value).append("' }")
    }

    private fun metadataFor(request: ImmutableUploadRequest): DriveFileMetadata = metadataFor(
        providerObjectId = request.providerObjectId,
        role = request.role,
        lineageId = request.lineageId,
        logicalObjectId = request.logicalObjectId.value,
        writerEpoch = request.writerEpoch,
        ownerDeviceId = request.ownerDeviceId,
    )

    private fun metadataFor(
        providerObjectId: ProviderObjectId,
        role: RemoteObjectRoleV1,
        lineageId: CloudLineageId?,
        logicalObjectId: String?,
        writerEpoch: WriterEpoch?,
        ownerDeviceId: CloudDeviceId?,
    ): DriveFileMetadata {
        val properties = buildMap {
            put(PROPERTY_FORMAT, FORMAT_V1)
            lineageId?.let { put(PROPERTY_LINEAGE, it.value) }
            logicalObjectId?.let { put(PROPERTY_LOGICAL_OBJECT, it) }
            writerEpoch?.let { put(PROPERTY_EPOCH, it.value.toString()) }
            ownerDeviceId?.let { put(PROPERTY_OWNER_DEVICE, it.value) }
        }
        return DriveFileMetadata(
            providerFileId = providerObjectId.value,
            name = requireNotNull(NAME_BY_ROLE[role]) { "Unrecognized role" },
            role = role.name,
            appProperties = properties,
        )
    }

    private fun createStagingFile(prefix: String): File {
        stagingRoot.mkdirs()
        return File.createTempFile("$prefix-", ".otr", stagingRoot)
    }

    private fun digestMatches(file: File, expected: Sha256Digest): Boolean = MessageDigest.isEqual(
        sha256Hex(file).encodeToByteArray(),
        expected.value.encodeToByteArray(),
    )

    private companion object {
        const val MULTIPART_THRESHOLD_BYTES = 5L * 1024 * 1024
        const val CHUNK_SIZE_BYTES = 256L * 1024
        const val MAX_PAGE_SIZE = 100
        const val MAX_PAGE_TOKEN_CHARACTERS = 1_024
        const val MAX_GENERATED_IDS_PER_REQUEST = 100
        const val FORMAT_V1 = "v1"
        const val APP_PROPERTIES = "appProperties"
        const val PROPERTY_FORMAT = "format"
        const val PROPERTY_ROLE_KEY = "role"
        const val PROPERTY_LINEAGE = "lineageId"
        const val PROPERTY_LOGICAL_OBJECT = "logicalObjectId"
        const val PROPERTY_EPOCH = "epoch"
        const val PROPERTY_OWNER_DEVICE = "ownerDeviceId"
        val SINGLETON_ROLES = setOf(
            RemoteObjectRoleV1.OWNERSHIP_ROOT,
            RemoteObjectRoleV1.OWNERSHIP_CLAIM,
            RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE,
            RemoteObjectRoleV1.PUBLICATION,
            RemoteObjectRoleV1.SNAPSHOT,
        )
        val NAME_BY_ROLE = mapOf(
            RemoteObjectRoleV1.OWNERSHIP_ROOT to "ownership-root",
            RemoteObjectRoleV1.OWNERSHIP_CLAIM to "ownership-claim",
            RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE to "ownership-tombstone",
            RemoteObjectRoleV1.PUBLICATION to "publication",
            RemoteObjectRoleV1.SNAPSHOT to "snapshot",
            RemoteObjectRoleV1.SEGMENT to "segment",
        )
    }
}

private fun validateUploadRequest(request: ImmutableUploadRequest) {
    require(request.frameLength >= 0) { "Frame length is negative" }
    require(request.lastGeneration.value >= request.firstGeneration.value) {
        "Last generation precedes first generation"
    }
}

private fun DriveTransportFailureCategory.toRemoteFailure(): RemoteBackupFailureCategory =
    when (this) {
        DriveTransportFailureCategory.AUTHORIZATION ->
            RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED
        DriveTransportFailureCategory.MISSING ->
            RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE
        DriveTransportFailureCategory.STORAGE_QUOTA ->
            RemoteBackupFailureCategory.PROVIDER_STORAGE
        DriveTransportFailureCategory.RETRYABLE ->
            RemoteBackupFailureCategory.RETRYABLE_PROVIDER
        DriveTransportFailureCategory.CORRUPT_RESPONSE ->
            RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE
        DriveTransportFailureCategory.PROVIDER_REJECTED ->
            RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE
    }

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private class StagedRemoteBytes(bytes: ByteArray) : OwnedRemoteBytes {
    private var owned: ByteArray? = bytes
    override val size: Int = bytes.size

    override fun take(): ByteArray {
        val current = checkNotNull(owned) { "Remote bytes were already taken or closed" }
        owned = null
        return current
    }

    override fun close() {
        owned?.fill(0)
        owned = null
    }
}

private class StagedRemoteFile(candidate: File) : OwnedRemoteFile {
    private var backing: File? = candidate

    override val file: File
        get() = checkNotNull(backing) { "Remote file was already closed" }

    override val length: Long
        get() = file.length()

    override fun close() {
        val current = backing ?: return
        backing = null
        current.delete()
    }
}
