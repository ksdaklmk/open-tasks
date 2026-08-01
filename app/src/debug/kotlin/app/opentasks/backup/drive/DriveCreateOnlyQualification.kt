package app.opentasks.backup.drive

import app.opentasks.core.data.backup.drive.CreateOnlyDriveTransport
import app.opentasks.core.data.backup.drive.DriveCreateRequest
import app.opentasks.core.data.backup.drive.DriveCreateResult
import app.opentasks.core.data.backup.drive.DriveFileMetadata
import app.opentasks.core.data.backup.drive.DriveTransportException
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class QualificationClaim(
    val format: String = "open-tasks-create-only-qualification-v1",
    val predecessorId: String,
    val successorId: String,
    val claimId: String,
    val candidateId: String,
    val baselineId: String,
    val nextSuccessorId: String,
    val epoch: Long,
)

data class QualificationResult(
    val property: String,
    val passed: Boolean,
)

class QualificationCreateFacade(
    private val transport: CreateOnlyDriveTransport,
) {
    suspend fun createAndDeliberatelyDiscardCreatedResult(
        request: DriveCreateRequest,
    ): DriveCreateResult = when (transport.createFileIfAbsent(request)) {
        DriveCreateResult.Created -> DriveCreateResult.Ambiguous
        DriveCreateResult.AlreadyExists -> DriveCreateResult.AlreadyExists
        DriveCreateResult.Ambiguous -> DriveCreateResult.Ambiguous
    }
}

/**
 * Runs only disposable provider checks. Results are bounded property names and booleans;
 * account, token, provider identifiers, frame bytes, and transport details never escape.
 */
internal class DriveCreateOnlyQualification(
    private val transport: CreateOnlyDriveTransport,
    private val directory: File,
    private val keySupplier: () -> ByteArray = {
        ByteArray(HMAC_KEY_BYTES).also(SecureRandom()::nextBytes)
    },
) {
    suspend fun run(): List<QualificationResult> {
        val results = mutableListOf<QualificationResult>()
        val generatedIds = mutableListOf<String>()
        val createdIds = mutableSetOf<String>()
        val key = keySupplier()
        val destination = File(directory, DOWNLOAD_FILE_NAME)
        var stage = "INITIALIZE"
        var cleanupPassed = true
        try {
            if (key.size != HMAC_KEY_BYTES) error("invalid ephemeral key")

            stage = "PERMISSION_LOOKUP"
            val permissionBytes = transport.readCurrentUserPermissionId().encodeToByteArray()
            stage = "PERMISSION_HMAC"
            val permissionDigest = hmac(key, permissionBytes)
            permissionBytes.fill(0)
            permissionDigest.fill(0)
            results += QualificationResult("DRIVE_APPDATA_AUTHORIZATION", true)

            stage = "INTERRUPTED_RUN_CLEANUP"
            cleanupInterruptedRuns()

            repeat(RACE_COUNT) { raceIndex ->
                stage = "GENERATE_RACE_IDS"
                val ids = transport.generateAppDataFileIds(IDS_PER_RACE)
                if (ids.size != IDS_PER_RACE || ids.toSet().size != IDS_PER_RACE) {
                    error("invalid generated ids")
                }
                generatedIds += ids
                stage = "RACE_${raceIndex + 1}"
                runRace(
                    ids = ids,
                    key = key,
                    destination = destination,
                    createdIds = createdIds,
                )
            }
            results += QualificationResult("TEN_CREATE_ONLY_RACES", true)
            results += QualificationResult("THIRTY_LOSER_RETRIES", true)
            results += QualificationResult("UNCHANGED_AUTHENTICATED_WINNERS", true)

            stage = "GENERATE_DISCARDED_SUCCESS_ID"
            val exactId = transport.generateAppDataFileIds(1).single()
            generatedIds += exactId
            val claim = QualificationClaim(
                predecessorId = exactId,
                successorId = exactId,
                claimId = "discarded-success",
                candidateId = exactId,
                baselineId = exactId,
                nextSuccessorId = exactId,
                epoch = 1,
            )
            val frame = encodeFrame(claim, key)
            try {
                stage = "DISCARDED_SUCCESS_CREATE"
                val request = request(exactId, ROLE_SUCCESSOR, claim, frame)
                if (
                    QualificationCreateFacade(transport)
                        .createAndDeliberatelyDiscardCreatedResult(request) != DriveCreateResult.Ambiguous
                ) {
                    error("discarded result was not ambiguous")
                }
                createdIds += exactId
                stage = "DISCARDED_SUCCESS_EXACT_ID"
                if (!downloadAndAuthenticate(exactId, claim, frame, key, destination)) {
                    error("discarded success did not resolve at exact id")
                }
            } finally {
                frame.fill(0)
            }
            results += QualificationResult("DISCARDED_SUCCESS_EXACT_ID", true)
        } catch (throwable: Exception) {
            results += QualificationResult(failureDiagnostic(stage, throwable), false)
        } finally {
            stage = "DISPOSABLE_CLEANUP"
            generatedIds.asReversed().forEach { id ->
                try {
                    val deleted = transport.deleteFile(id)
                    if (id in createdIds && !deleted) cleanupPassed = false
                } catch (_: Exception) {
                    cleanupPassed = false
                }
            }
            destination.delete()
            key.fill(0)
            transport.close()
            results += QualificationResult(stage, cleanupPassed)
        }
        return results
    }

    private suspend fun cleanupInterruptedRuns() {
        var pageToken: String? = null
        repeat(MAX_CLEANUP_PAGES) {
            val page = transport.listAppDataFiles(
                query = "name = '$QUALIFICATION_FILE_NAME' and trashed = false",
                pageToken = pageToken,
                pageSize = CLEANUP_PAGE_SIZE,
            )
            page.files.filter { file ->
                file.name == QUALIFICATION_FILE_NAME &&
                    file.appProperties["format"] == QUALIFICATION_FORMAT
            }.forEach { transport.deleteFile(it.providerFileId) }
            pageToken = page.nextPageToken ?: return
        }
        error("interrupted cleanup page bound exceeded")
    }

    private suspend fun runRace(
        ids: List<String>,
        key: ByteArray,
        destination: File,
        createdIds: MutableSet<String>,
    ) {
        val predecessorId = ids[0]
        val successorId = ids[1]
        val rootBaselineId = ids[2]
        val candidateBaselineIds = listOf(ids[3], ids[4])
        val nextSuccessorIds = listOf(ids[5], ids[6])

        val rootClaim = QualificationClaim(
            predecessorId = rootBaselineId,
            successorId = predecessorId,
            claimId = "root",
            candidateId = rootBaselineId,
            baselineId = rootBaselineId,
            nextSuccessorId = predecessorId,
            epoch = 0,
        )
        createAndAuthenticate(
            rootBaselineId,
            ROLE_ROOT_BASELINE,
            rootClaim,
            key,
            destination,
            createdIds,
        )

        val predecessorClaim = QualificationClaim(
            predecessorId = rootBaselineId,
            successorId = predecessorId,
            claimId = "predecessor",
            candidateId = rootBaselineId,
            baselineId = rootBaselineId,
            nextSuccessorId = successorId,
            epoch = 1,
        )
        createAndAuthenticate(
            predecessorId,
            ROLE_PREDECESSOR,
            predecessorClaim,
            key,
            destination,
            createdIds,
        )

        val baselineClaims = candidateBaselineIds.mapIndexed { index, baselineId ->
            QualificationClaim(
                predecessorId = predecessorId,
                successorId = successorId,
                claimId = "baseline-${index + 1}",
                candidateId = baselineId,
                baselineId = rootBaselineId,
                nextSuccessorId = nextSuccessorIds[index],
                epoch = 2,
            )
        }
        baselineClaims.forEachIndexed { index, claim ->
            createAndAuthenticate(
                candidateBaselineIds[index],
                ROLE_BASELINE,
                claim,
                key,
                destination,
                createdIds,
            )
        }

        val claims = candidateBaselineIds.mapIndexed { index, candidateId ->
            QualificationClaim(
                predecessorId = predecessorId,
                successorId = successorId,
                claimId = "claim-${index + 1}",
                candidateId = candidateId,
                baselineId = rootBaselineId,
                nextSuccessorId = nextSuccessorIds[index],
                epoch = 2,
            )
        }
        val frames = claims.map { encodeFrame(it, key) }
        try {
            val requests = claims.indices.map { index ->
                request(successorId, ROLE_SUCCESSOR, claims[index], frames[index])
            }
            val raceResults = coroutineScope {
                requests.map { request ->
                    async(Dispatchers.IO) { transport.createFileIfAbsent(request) }
                }.map { it.await() }
            }
            if (raceResults.count { it == DriveCreateResult.Created } != 1 ||
                raceResults.count { it == DriveCreateResult.AlreadyExists } != 1
            ) {
                error("create-only race invariant failed")
            }
            createdIds += successorId
            val winnerIndex = raceResults.indexOf(DriveCreateResult.Created)
            val loserIndex = 1 - winnerIndex
            repeat(LOSER_RETRIES_PER_RACE) {
                if (transport.createFileIfAbsent(requests[loserIndex]) != DriveCreateResult.AlreadyExists) {
                    error("loser retry replaced winner")
                }
                if (
                    !downloadAndAuthenticate(
                        successorId,
                        claims[winnerIndex],
                        frames[winnerIndex],
                        key,
                        destination,
                    )
                ) {
                    error("winner changed after loser retry")
                }
            }
        } finally {
            frames.forEach { it.fill(0) }
        }
    }

    private suspend fun createAndAuthenticate(
        id: String,
        role: String,
        claim: QualificationClaim,
        key: ByteArray,
        destination: File,
        createdIds: MutableSet<String>,
    ) {
        val frame = encodeFrame(claim, key)
        try {
            if (transport.createFileIfAbsent(request(id, role, claim, frame)) != DriveCreateResult.Created) {
                error("disposable setup create failed")
            }
            createdIds += id
            if (!downloadAndAuthenticate(id, claim, frame, key, destination)) {
                error("disposable setup authentication failed")
            }
        } finally {
            frame.fill(0)
        }
    }

    private fun request(
        id: String,
        role: String,
        claim: QualificationClaim,
        frame: ByteArray,
    ): DriveCreateRequest = DriveCreateRequest(
        metadata = DriveFileMetadata(
            providerFileId = id,
            name = QUALIFICATION_FILE_NAME,
            role = role,
            appProperties = mapOf(
                "format" to claim.format,
                "epoch" to claim.epoch.toString(),
                "claimId" to claim.claimId,
            ),
        ),
        content = frame,
    )

    private suspend fun downloadAndAuthenticate(
        id: String,
        expectedClaim: QualificationClaim,
        expectedFrame: ByteArray,
        key: ByteArray,
        destination: File,
    ): Boolean {
        val receipt = transport.downloadFile(id, destination, MAX_FRAME_BYTES)
        if (receipt.byteCount != destination.length() || receipt.byteCount > MAX_FRAME_BYTES) return false
        val downloaded = destination.readBytes()
        return try {
            MessageDigest.isEqual(downloaded, expectedFrame) &&
                decodeFrame(downloaded, key) == expectedClaim
        } finally {
            downloaded.fill(0)
            destination.delete()
        }
    }

    private fun encodeFrame(claim: QualificationClaim, key: ByteArray): ByteArray {
        val canonicalJson = json.encodeToString(QualificationClaim.serializer(), claim).encodeToByteArray()
        val authentication = hmac(key, canonicalJson)
        return ByteArray(canonicalJson.size + authentication.size).also { frame ->
            canonicalJson.copyInto(frame)
            authentication.copyInto(frame, canonicalJson.size)
            canonicalJson.fill(0)
            authentication.fill(0)
        }
    }

    private fun decodeFrame(frame: ByteArray, key: ByteArray): QualificationClaim? {
        if (frame.size <= HMAC_BYTES || frame.size > MAX_FRAME_BYTES) return null
        val canonicalJson = frame.copyOfRange(0, frame.size - HMAC_BYTES)
        val authentication = frame.copyOfRange(frame.size - HMAC_BYTES, frame.size)
        val expectedAuthentication = hmac(key, canonicalJson)
        return try {
            if (!MessageDigest.isEqual(authentication, expectedAuthentication)) return null
            val claim = json.decodeFromString(QualificationClaim.serializer(), canonicalJson.decodeToString())
            val encodedAgain = json.encodeToString(QualificationClaim.serializer(), claim).encodeToByteArray()
            try {
                if (MessageDigest.isEqual(encodedAgain, canonicalJson)) claim else null
            } finally {
                encodedAgain.fill(0)
            }
        } catch (_: Exception) {
            null
        } finally {
            canonicalJson.fill(0)
            authentication.fill(0)
            expectedAuthentication.fill(0)
        }
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(input)
        }

    companion object {
        internal fun failureDiagnostic(stage: String, throwable: Throwable): String = when (throwable) {
            is DriveTransportException -> bounded("TRANSPORT_${stage}_${throwable.category.name}")
            else -> bounded(
                "EXCEPTION_${stage}_${throwable.javaClass.simpleName.ifEmpty { "UNKNOWN" }}",
            )
        }

        private fun bounded(value: String): String =
            value.filter { it.isLetterOrDigit() || it == '_' }.take(MAX_PROPERTY_NAME_LENGTH)

        private const val RACE_COUNT = 10
        private const val LOSER_RETRIES_PER_RACE = 3
        private const val IDS_PER_RACE = 7
        private const val HMAC_KEY_BYTES = 32
        private const val HMAC_BYTES = 32
        private const val MAX_FRAME_BYTES = 1L shl 20
        private const val MAX_PROPERTY_NAME_LENGTH = 80
        private const val MAX_CLEANUP_PAGES = 10
        private const val CLEANUP_PAGE_SIZE = 100
        private const val QUALIFICATION_FORMAT = "open-tasks-create-only-qualification-v1"
        private const val QUALIFICATION_FILE_NAME = "stage3-drive-create-only-qualification"
        private const val DOWNLOAD_FILE_NAME = "stage3-drive-create-only-download.bin"
        private const val ROLE_ROOT_BASELINE = "root-baseline"
        private const val ROLE_PREDECESSOR = "predecessor"
        private const val ROLE_BASELINE = "baseline"
        private const val ROLE_SUCCESSOR = "successor"
        private val json = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}
