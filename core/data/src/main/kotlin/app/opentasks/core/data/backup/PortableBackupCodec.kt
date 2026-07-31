package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.DecryptedCloudObject
import app.opentasks.core.domain.BackupPolicy
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.sync.CloudBounds
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import app.opentasks.core.sync.CloudObjectHeader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class PortableBootstrapHeaderV1(
    val magic: String = "OPEN_TASKS_PORTABLE",
    val packageVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val vaultId: String,
    val generation: Long,
    val producedAtEpochMillis: Long,
    val recoveryEnvelope: RecoveryEnvelopePayloadV1,
    val manifestFrameLength: Long,
    val manifestFrameSha256: String,
    val snapshotFrameLength: Long,
    val snapshotFrameSha256: String,
    val totalPackageLength: Long,
)

@Serializable
data class PortableManifestPayloadV1(
    val packageVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val vaultId: String,
    val generation: Long,
    val producedAtEpochMillis: Long,
    val recoveryEnvelopeSha256: String,
    val snapshotObjectId: String,
    val snapshotFrameLength: Long,
    val snapshotFrameSha256: String,
    val recordCounts: List<BackupRecordFamilyCountV1>,
)

@Serializable
data class BackupRecordFamilyCountV1(
    val family: BackupRecordFamily,
    val count: Int,
)

data class VerifiedPortableBackup(
    val vaultId: String,
    val generation: Long,
    val producedAtEpochMillis: Long,
    val recoveryEnvelopeSha256: String,
    val totalPackageLength: Long,
)

class PortablePackageTooLargeException :
    IllegalArgumentException("Portable package exceeds its byte bound")

/**
 * The recovery envelope did not unlock under the passphrase offered.
 *
 * Distinguished from every other decode failure so a caller can tell a user to
 * try another passphrase rather than that the backup is unreadable. It carries
 * no detail of the envelope, the derivation, or the package.
 */
class RecoveryPassphraseException :
    IllegalArgumentException("The recovery passphrase does not unlock this backup")

interface PortablePackageCodec {
    fun encode(
        recoveryEnvelope: VaultKeyEnvelope,
        snapshot: BackupSnapshotPayloadV1,
        producedAtEpochMillis: Long,
        key: VaultKey,
    ): ByteArray

    fun readBootstrap(
        source: InputStream,
        totalLength: Long,
    ): PortableBootstrapHeaderV1

    fun verifyComplete(
        source: InputStream,
        totalLength: Long,
        key: VaultKey,
    ): VerifiedPortableBackup
}

class PortableBackupCodec(
    private val authenticatedCodec: AuthenticatedCloudObjectCodec,
    private val snapshotCodec: BackupSnapshotCodec = BackupSnapshotCodec,
    internal val ownedFrameReader: (InputStream, Int) -> ByteArray = ::readOwnedBytes,
) : PortablePackageCodec {
    override fun encode(
        recoveryEnvelope: VaultKeyEnvelope,
        snapshot: BackupSnapshotPayloadV1,
        producedAtEpochMillis: Long,
        key: VaultKey,
    ): ByteArray {
        require(producedAtEpochMillis >= 0) { "Portable package production time is negative" }
        val canonicalEnvelope = RecoveryEnvelopeCodec.encode(recoveryEnvelope)
        var snapshotPlaintext: ByteArray? = null
        var snapshotFrame: ByteArray? = null
        var manifestPlaintext: ByteArray? = null
        var manifestFrame: ByteArray? = null
        var headerBytes: ByteArray? = null
        try {
            snapshotPlaintext = try {
                snapshotCodec.encodeBounded(
                    snapshot,
                    MAX_PORTABLE_SNAPSHOT_PLAINTEXT_BYTES,
                )
            } catch (_: BackupPayloadTooLargeException) {
                throw PortablePackageTooLargeException()
            }
            preflightPackageLength(
                recoveryEnvelope = recoveryEnvelope,
                canonicalEnvelope = canonicalEnvelope,
                snapshot = snapshot,
                snapshotPlaintextLength = snapshotPlaintext.size,
                producedAtEpochMillis = producedAtEpochMillis,
            )
            snapshotFrame = authenticatedCodec.encrypt(
                identity = identity(
                    family = CloudObjectFamily.SNAPSHOT,
                    vaultId = snapshot.vaultId,
                    objectId = snapshotObjectId(snapshot.coveredGeneration),
                ),
                plaintext = snapshotPlaintext,
                key = key,
            )
            validateFrameLength(
                family = CloudObjectFamily.SNAPSHOT,
                length = snapshotFrame.size.toLong(),
            )
            val manifest = PortableManifestPayloadV1(
                vaultId = snapshot.vaultId,
                generation = snapshot.coveredGeneration,
                producedAtEpochMillis = producedAtEpochMillis,
                recoveryEnvelopeSha256 = sha256(canonicalEnvelope),
                snapshotObjectId = snapshotObjectId(snapshot.coveredGeneration),
                snapshotFrameLength = snapshotFrame.size.toLong(),
                snapshotFrameSha256 = sha256(snapshotFrame),
                recordCounts = recordCounts(snapshot),
            )
            manifestPlaintext = PortableManifestCodec.encode(manifest)
            manifestFrame = authenticatedCodec.encrypt(
                identity = identity(
                    family = CloudObjectFamily.MANIFEST,
                    vaultId = snapshot.vaultId,
                    objectId = manifestObjectId(snapshot.coveredGeneration),
                ),
                plaintext = manifestPlaintext,
                key = key,
            )
            validateFrameLength(
                family = CloudObjectFamily.MANIFEST,
                length = manifestFrame.size.toLong(),
            )

            var header = PortableBootstrapHeaderV1(
                vaultId = snapshot.vaultId,
                generation = snapshot.coveredGeneration,
                producedAtEpochMillis = producedAtEpochMillis,
                recoveryEnvelope = RecoveryEnvelopeCodec.toPayload(recoveryEnvelope),
                manifestFrameLength = manifestFrame.size.toLong(),
                manifestFrameSha256 = sha256(manifestFrame),
                snapshotFrameLength = snapshotFrame.size.toLong(),
                snapshotFrameSha256 = sha256(snapshotFrame),
                totalPackageLength = 0,
            )
            repeat(MAX_HEADER_STABILISATION_PASSES) {
                headerBytes?.fill(0)
                headerBytes = canonicalHeaderBytes(header)
                val totalLength = checkedPackageLength(
                    headerBytes.size,
                    header.manifestFrameLength,
                    header.snapshotFrameLength,
                )
                header = header.copy(totalPackageLength = totalLength)
            }
            headerBytes?.fill(0)
            headerBytes = canonicalHeaderBytes(header)
            val totalLength = checkedPackageLength(
                headerBytes.size,
                header.manifestFrameLength,
                header.snapshotFrameLength,
            )
            require(header.totalPackageLength == totalLength) {
                "Portable bootstrap length did not stabilise"
            }
            validateHeader(header, headerBytes.size, totalLength)
            return ByteArray(totalLength.toInt()).also { output ->
                ByteBuffer.wrap(output, 0, LENGTH_PREFIX_BYTES).putInt(headerBytes.size)
                var offset = LENGTH_PREFIX_BYTES
                headerBytes.copyInto(output, offset)
                offset += headerBytes.size
                manifestFrame.copyInto(output, offset)
                offset += manifestFrame.size
                snapshotFrame.copyInto(output, offset)
            }
        } finally {
            headerBytes?.fill(0)
            manifestFrame?.fill(0)
            manifestPlaintext?.fill(0)
            snapshotFrame?.fill(0)
            snapshotPlaintext?.fill(0)
            canonicalEnvelope.fill(0)
        }
    }

    override fun readBootstrap(
        source: InputStream,
        totalLength: Long,
    ): PortableBootstrapHeaderV1 {
        require(totalLength in MINIMUM_PACKAGE_BYTES..BackupPolicy.MAX_PORTABLE_PACKAGE_BYTES) {
            "Portable package length is outside its bound"
        }
        val prefix = readOwnedBytes(source, LENGTH_PREFIX_BYTES)
        val headerLength = try {
            ByteBuffer.wrap(prefix).int
        } finally {
            prefix.fill(0)
        }
        require(headerLength in 1..CloudBounds.MAX_HEADER_BYTES) {
            "Portable bootstrap header length is outside its bound"
        }
        val headerBytes = readOwnedBytes(source, headerLength)
        try {
            val header = decodeCanonicalHeader(headerBytes)
            validateHeader(header, headerLength, totalLength)
            return header
        } finally {
            headerBytes.fill(0)
        }
    }

    override fun verifyComplete(
        source: InputStream,
        totalLength: Long,
        key: VaultKey,
    ): VerifiedPortableBackup = decodeVerified(source, totalLength, key).verified

    /**
     * Authenticates the complete package exactly as [verifyComplete] does and
     * keeps the snapshot it already decoded.
     *
     * Verification has to decode the snapshot to check it against the manifest
     * and the bootstrap, so recovery would otherwise authenticate the same
     * bytes twice. Every byte read, every bound, and every agreement check is
     * the one [verifyComplete] performs; only the decoded payload survives.
     */
    internal fun decodeVerified(
        source: InputStream,
        totalLength: Long,
        key: VaultKey,
    ): DecodedPortablePackage {
        val header = readBootstrap(source, totalLength)
        var manifestFrame: ByteArray? = null
        var snapshotFrame: ByteArray? = null
        try {
            manifestFrame = ownedFrameReader(source, header.manifestFrameLength.toInt())
            snapshotFrame = ownedFrameReader(source, header.snapshotFrameLength.toInt())
            require(source.read() == -1) { "Portable package contains trailing bytes" }
            require(sha256(manifestFrame) == header.manifestFrameSha256) {
                "Portable manifest frame checksum mismatch"
            }
            require(sha256(snapshotFrame) == header.snapshotFrameSha256) {
                "Portable snapshot frame checksum mismatch"
            }

            val manifestObject = decrypt(
                frame = manifestFrame,
                key = key,
                expectedIdentity = identity(
                    CloudObjectFamily.MANIFEST,
                    header.vaultId,
                    manifestObjectId(header.generation),
                ),
            )
            val manifest = manifestObject.usePlaintext(PortableManifestCodec::decodeOwned)
            validateManifestAgreement(header, manifest)

            val snapshotObject = decrypt(
                frame = snapshotFrame,
                key = key,
                expectedIdentity = identity(
                    CloudObjectFamily.SNAPSHOT,
                    header.vaultId,
                    snapshotObjectId(header.generation),
                ),
            )
            val snapshot = snapshotObject.usePlaintext(snapshotCodec::decodeOwned)
            validateSnapshotAgreement(header, manifest, snapshot)
            return DecodedPortablePackage(
                verified = VerifiedPortableBackup(
                    vaultId = header.vaultId,
                    generation = header.generation,
                    producedAtEpochMillis = header.producedAtEpochMillis,
                    recoveryEnvelopeSha256 = manifest.recoveryEnvelopeSha256,
                    totalPackageLength = header.totalPackageLength,
                ),
                snapshot = snapshot,
            )
        } finally {
            snapshotFrame?.fill(0)
            manifestFrame?.fill(0)
        }
    }

    private fun decrypt(
        frame: ByteArray,
        key: VaultKey,
        expectedIdentity: CloudHeaderIdentity,
    ): DecryptedCloudObject {
        val result = authenticatedCodec.decrypt(
            source = ByteArrayInputStream(frame),
            totalLength = frame.size.toLong(),
            key = key,
        )
        require(result is CloudDecodeResult.Success) {
            "Portable frame authentication failed"
        }
        return result.value.also { decrypted ->
            if (decrypted.identity != expectedIdentity) {
                decrypted.close()
                throw IllegalArgumentException("Portable frame identity mismatch")
            }
        }
    }

    private fun validateManifestAgreement(
        header: PortableBootstrapHeaderV1,
        manifest: PortableManifestPayloadV1,
    ) {
        require(manifest.vaultId == header.vaultId) { "Portable manifest vault mismatch" }
        require(manifest.generation == header.generation) {
            "Portable manifest generation mismatch"
        }
        require(manifest.producedAtEpochMillis == header.producedAtEpochMillis) {
            "Portable manifest production time mismatch"
        }
        require(manifest.snapshotObjectId == snapshotObjectId(header.generation)) {
            "Portable snapshot object identity mismatch"
        }
        require(manifest.snapshotFrameLength == header.snapshotFrameLength) {
            "Portable snapshot frame length mismatch"
        }
        require(manifest.snapshotFrameSha256 == header.snapshotFrameSha256) {
            "Portable snapshot frame checksum declaration mismatch"
        }
        val envelope = RecoveryEnvelopeCodec.fromPayload(header.recoveryEnvelope)
        val envelopeBytes = try {
            RecoveryEnvelopeCodec.encode(envelope)
        } finally {
            envelope.clear()
        }
        try {
            require(sha256(envelopeBytes) == manifest.recoveryEnvelopeSha256) {
                "Portable recovery envelope digest mismatch"
            }
        } finally {
            envelopeBytes.fill(0)
        }
    }

    private fun validateSnapshotAgreement(
        header: PortableBootstrapHeaderV1,
        manifest: PortableManifestPayloadV1,
        snapshot: BackupSnapshotPayloadV1,
    ) {
        require(snapshot.vaultId == header.vaultId) { "Portable snapshot vault mismatch" }
        require(snapshot.coveredGeneration == header.generation) {
            "Portable snapshot generation mismatch"
        }
        require(recordCounts(snapshot) == manifest.recordCounts) {
            "Portable snapshot record counts mismatch"
        }
    }

    private fun decodeCanonicalHeader(source: ByteArray): PortableBootstrapHeaderV1 {
        val text = strictUtf8(source, "Portable bootstrap header")
        val header = try {
            StrictPortableJson.json.decodeFromString<PortableBootstrapHeaderV1>(text)
        } catch (failure: SerializationException) {
            throw IllegalArgumentException("Invalid portable bootstrap header", failure)
        }
        val canonical = canonicalHeaderBytes(header)
        try {
            require(source.contentEquals(canonical)) {
                "Portable bootstrap header is not canonical"
            }
        } finally {
            canonical.fill(0)
        }
        return header
    }

    private fun validateHeader(
        header: PortableBootstrapHeaderV1,
        headerLength: Int,
        actualTotalLength: Long,
    ) {
        require(header.magic == MAGIC) { "Unsupported portable package magic" }
        require(header.packageVersion == FORMAT_VERSION) {
            "Unsupported portable package version ${header.packageVersion}"
        }
        require(header.minimumReaderVersion == MINIMUM_READER_VERSION) {
            "Unsupported portable minimum reader ${header.minimumReaderVersion}"
        }
        validateIdentifier(header.vaultId, "Portable vault")
        require(header.generation >= 0) { "Portable generation is negative" }
        require(header.producedAtEpochMillis >= 0) { "Portable production time is negative" }
        val envelope = RecoveryEnvelopeCodec.fromPayload(header.recoveryEnvelope)
        envelope.clear()
        validateFrameLength(CloudObjectFamily.MANIFEST, header.manifestFrameLength)
        validateFrameLength(CloudObjectFamily.SNAPSHOT, header.snapshotFrameLength)
        require(LOWERCASE_SHA256.matches(header.manifestFrameSha256)) {
            "Portable manifest checksum is malformed"
        }
        require(LOWERCASE_SHA256.matches(header.snapshotFrameSha256)) {
            "Portable snapshot checksum is malformed"
        }
        val declared = checkedPackageLength(
            headerLength,
            header.manifestFrameLength,
            header.snapshotFrameLength,
        )
        require(header.totalPackageLength == declared) {
            "Portable total length is inconsistent"
        }
        require(header.totalPackageLength == actualTotalLength) {
            "Portable total length does not match input"
        }
    }

    private fun validateFrameLength(
        family: CloudObjectFamily,
        length: Long,
    ) {
        require(length > 0) { "Portable frame length must be positive" }
        val maximum = when (family) {
            CloudObjectFamily.MANIFEST ->
                LENGTH_PREFIX_BYTES + CloudBounds.MAX_HEADER_BYTES +
                    CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES
            CloudObjectFamily.SNAPSHOT ->
                BackupPolicy.MAX_PORTABLE_PACKAGE_BYTES
            else -> throw IllegalArgumentException("Unsupported portable frame family")
        }
        require(length <= maximum) { "Portable $family frame exceeds its bound" }
        require(length <= Int.MAX_VALUE) { "Portable frame cannot be allocated" }
    }

    private fun checkedPackageLength(
        headerLength: Int,
        manifestLength: Long,
        snapshotLength: Long,
    ): Long {
        require(headerLength in 1..CloudBounds.MAX_HEADER_BYTES) {
            "Portable bootstrap header length is outside its bound"
        }
        val total = try {
            Math.addExact(
                Math.addExact(
                    Math.addExact(LENGTH_PREFIX_BYTES.toLong(), headerLength.toLong()),
                    manifestLength,
                ),
                snapshotLength,
            )
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("Portable package length overflows", failure)
        }
        if (total > BackupPolicy.MAX_PORTABLE_PACKAGE_BYTES) {
            throw PortablePackageTooLargeException()
        }
        require(total <= Int.MAX_VALUE) { "Portable package cannot be allocated" }
        return total
    }

    private fun preflightPackageLength(
        recoveryEnvelope: VaultKeyEnvelope,
        canonicalEnvelope: ByteArray,
        snapshot: BackupSnapshotPayloadV1,
        snapshotPlaintextLength: Int,
        producedAtEpochMillis: Long,
    ) {
        val snapshotFrameLength = predictedFrameLength(
            identity = identity(
                CloudObjectFamily.SNAPSHOT,
                snapshot.vaultId,
                snapshotObjectId(snapshot.coveredGeneration),
            ),
            plaintextLength = snapshotPlaintextLength,
        )
        val manifest = PortableManifestPayloadV1(
            vaultId = snapshot.vaultId,
            generation = snapshot.coveredGeneration,
            producedAtEpochMillis = producedAtEpochMillis,
            recoveryEnvelopeSha256 = sha256(canonicalEnvelope),
            snapshotObjectId = snapshotObjectId(snapshot.coveredGeneration),
            snapshotFrameLength = snapshotFrameLength,
            snapshotFrameSha256 = ZERO_SHA256,
            recordCounts = recordCounts(snapshot),
        )
        val manifestPlaintext = PortableManifestCodec.encode(manifest)
        val manifestFrameLength = try {
            predictedFrameLength(
                identity = identity(
                    CloudObjectFamily.MANIFEST,
                    snapshot.vaultId,
                    manifestObjectId(snapshot.coveredGeneration),
                ),
                plaintextLength = manifestPlaintext.size,
            )
        } finally {
            manifestPlaintext.fill(0)
        }
        var header = PortableBootstrapHeaderV1(
            vaultId = snapshot.vaultId,
            generation = snapshot.coveredGeneration,
            producedAtEpochMillis = producedAtEpochMillis,
            recoveryEnvelope = RecoveryEnvelopeCodec.toPayload(recoveryEnvelope),
            manifestFrameLength = manifestFrameLength,
            manifestFrameSha256 = ZERO_SHA256,
            snapshotFrameLength = snapshotFrameLength,
            snapshotFrameSha256 = ZERO_SHA256,
            totalPackageLength = 0,
        )
        repeat(MAX_HEADER_STABILISATION_PASSES) {
            val headerBytes = canonicalHeaderBytes(header)
            val totalLength = try {
                checkedPackageLength(
                    headerBytes.size,
                    manifestFrameLength,
                    snapshotFrameLength,
                )
            } finally {
                headerBytes.fill(0)
            }
            header = header.copy(totalPackageLength = totalLength)
        }
        val finalHeader = canonicalHeaderBytes(header)
        try {
            require(
                header.totalPackageLength == checkedPackageLength(
                    finalHeader.size,
                    manifestFrameLength,
                    snapshotFrameLength,
                ),
            ) {
                "Portable preflight bootstrap length did not stabilise"
            }
        } finally {
            finalHeader.fill(0)
        }
    }

    private fun predictedFrameLength(
        identity: CloudHeaderIdentity,
        plaintextLength: Int,
    ): Long {
        val ciphertextLength = try {
            Math.addExact(
                plaintextLength.toLong(),
                CloudBounds.AES_GCM_V1_CIPHERTEXT_OVERHEAD_BYTES,
            )
        } catch (failure: ArithmeticException) {
            throw PortablePackageTooLargeException()
        }
        val header = CloudObjectHeader(
            family = identity.family,
            schemaVersion = identity.schemaVersion,
            cryptoVersion = identity.cryptoVersion,
            minimumReaderVersion = identity.minimumReaderVersion,
            vaultId = identity.vaultId,
            objectId = identity.objectId,
            ciphertextLength = ciphertextLength,
            ciphertextSha256 = ZERO_SHA256,
            chunkIndex = identity.chunkIndex,
            chunkCount = identity.chunkCount,
        )
        val headerBytes = StrictPortableJson.json
            .encodeToString(CloudObjectHeader.serializer(), header)
            .toByteArray(Charsets.UTF_8)
        try {
            require(headerBytes.size <= CloudBounds.MAX_HEADER_BYTES) {
                "Portable cloud frame header exceeds its byte bound"
            }
            return try {
                Math.addExact(
                    Math.addExact(LENGTH_PREFIX_BYTES.toLong(), headerBytes.size.toLong()),
                    ciphertextLength,
                )
            } catch (failure: ArithmeticException) {
                throw PortablePackageTooLargeException()
            }
        } finally {
            headerBytes.fill(0)
        }
    }

    private fun canonicalHeaderBytes(header: PortableBootstrapHeaderV1): ByteArray =
        StrictPortableJson.json.encodeToString(PortableBootstrapHeaderV1.serializer(), header)
            .toByteArray(Charsets.UTF_8)
            .also {
                require(it.size <= CloudBounds.MAX_HEADER_BYTES) {
                    "Portable bootstrap header exceeds ${CloudBounds.MAX_HEADER_BYTES} bytes"
                }
            }

    private fun recordCounts(snapshot: BackupSnapshotPayloadV1): List<BackupRecordFamilyCountV1> {
        val counts = snapshot.records.groupingBy(BackupRecordV1::family).eachCount()
        return BackupRecordFamily.entries.map { family ->
            BackupRecordFamilyCountV1(family, counts[family] ?: 0)
        }
    }

    private fun identity(
        family: CloudObjectFamily,
        vaultId: String,
        objectId: String,
    ): CloudHeaderIdentity = CloudHeaderIdentity(
        family = family,
        schemaVersion = FORMAT_VERSION,
        cryptoVersion = FORMAT_VERSION,
        minimumReaderVersion = MINIMUM_READER_VERSION,
        vaultId = vaultId,
        objectId = objectId,
    )

    private fun manifestObjectId(generation: Long): String = "portable-manifest:$generation"

    private fun snapshotObjectId(generation: Long): String = "snapshot:$generation"
}

/** The authenticated package, still holding the snapshot verification decoded. */
internal class DecodedPortablePackage(
    val verified: VerifiedPortableBackup,
    val snapshot: BackupSnapshotPayloadV1,
)

/**
 * One authenticated portable package, ready to rebuild a staging vault.
 *
 * The recovery envelope is a fresh copy this result owns: nothing else holds
 * those buffers, and [close] is what clears them. The unlocked content key is
 * deliberately absent — it is derived, used to authenticate, and closed inside
 * [decodeComplete], so no caller can hold a passphrase-derived key by
 * accident.
 */
data class DecodedPortableBackup(
    val snapshot: BackupSnapshotPayloadV1,
    val recoveryEnvelope: VaultKeyEnvelope,
    val generation: BackupGeneration,
) : AutoCloseable {
    override fun close() {
        recoveryEnvelope.clear()
    }
}

/**
 * Reads the public bootstrap, derives the recovery key from [passphrase], and
 * authenticates the whole package before anything is decoded for use.
 *
 * The bootstrap's KDF parameters and every declared length are validated by
 * [PortableBackupCodec.readBootstrap] first, so no oversized buffer is
 * allocated and no key is derived from a weakened envelope. A wrong passphrase
 * surfaces as the failure [VaultCrypto.unlock] raises; nothing partially
 * decoded escapes.
 *
 * [crypto] is a parameter rather than a codec dependency because the codec is
 * also constructed where no recovery derivation may happen at all.
 */
fun PortableBackupCodec.decodeComplete(
    source: File,
    passphrase: CharArray,
    crypto: VaultCrypto,
): DecodedPortableBackup {
    val totalLength = source.length()
    val header = source.inputStream().use { stream -> readBootstrap(stream, totalLength) }
    val envelope = RecoveryEnvelopeCodec.fromPayload(header.recoveryEnvelope)
    var key: VaultKey? = null
    try {
        val unlocked = try {
            crypto.unlock(passphrase, envelope)
        } catch (_: Exception) {
            // The provider's own failure is never propagated: it would say how
            // the derivation failed, and a caller only needs that it did.
            throw RecoveryPassphraseException()
        }
        key = unlocked
        val decoded = source.inputStream().use { stream ->
            decodeVerified(stream, totalLength, unlocked)
        }
        return DecodedPortableBackup(
            snapshot = decoded.snapshot,
            recoveryEnvelope = envelope,
            generation = BackupGeneration(decoded.verified.generation),
        )
    } catch (failure: Throwable) {
        envelope.clear()
        throw failure
    } finally {
        key?.close()
    }
}

private object PortableManifestCodec {
    private const val MAX_PLAINTEXT_BYTES =
        (CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES -
            CloudBounds.AES_GCM_V1_CIPHERTEXT_OVERHEAD_BYTES).toInt()

    fun encode(payload: PortableManifestPayloadV1): ByteArray {
        validate(payload)
        return StrictPortableJson.json
            .encodeToString(PortableManifestPayloadV1.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
            .also {
                require(it.size <= MAX_PLAINTEXT_BYTES) {
                    "Portable manifest exceeds $MAX_PLAINTEXT_BYTES bytes"
                }
            }
    }

    fun decodeOwned(source: ByteArray): PortableManifestPayloadV1 {
        try {
            require(source.isNotEmpty()) { "Portable manifest is empty" }
            require(source.size <= MAX_PLAINTEXT_BYTES) {
                "Portable manifest exceeds $MAX_PLAINTEXT_BYTES bytes"
            }
            val payload = try {
                StrictPortableJson.json.decodeFromString<PortableManifestPayloadV1>(
                    strictUtf8(source, "Portable manifest"),
                )
            } catch (failure: SerializationException) {
                throw IllegalArgumentException("Invalid portable manifest", failure)
            }
            validate(payload)
            val canonical = encode(payload)
            try {
                require(source.contentEquals(canonical)) {
                    "Portable manifest is not canonical"
                }
            } finally {
                canonical.fill(0)
            }
            return payload
        } finally {
            source.fill(0)
        }
    }

    private fun validate(payload: PortableManifestPayloadV1) {
        require(payload.packageVersion == FORMAT_VERSION) {
            "Unsupported portable manifest version ${payload.packageVersion}"
        }
        require(payload.minimumReaderVersion == MINIMUM_READER_VERSION) {
            "Unsupported portable manifest minimum reader ${payload.minimumReaderVersion}"
        }
        validateIdentifier(payload.vaultId, "Portable manifest vault")
        require(payload.generation >= 0) { "Portable manifest generation is negative" }
        require(payload.producedAtEpochMillis >= 0) {
            "Portable manifest production time is negative"
        }
        require(LOWERCASE_SHA256.matches(payload.recoveryEnvelopeSha256)) {
            "Portable recovery envelope digest is malformed"
        }
        require(payload.snapshotObjectId == "snapshot:${payload.generation}") {
            "Portable manifest snapshot identity mismatch"
        }
        require(payload.snapshotFrameLength > 0) {
            "Portable manifest snapshot length must be positive"
        }
        require(payload.snapshotFrameLength <= BackupPolicy.MAX_PORTABLE_PACKAGE_BYTES) {
            "Portable manifest snapshot length exceeds its bound"
        }
        require(LOWERCASE_SHA256.matches(payload.snapshotFrameSha256)) {
            "Portable manifest snapshot checksum is malformed"
        }
        require(
            payload.recordCounts.map(BackupRecordFamilyCountV1::family) ==
                BackupRecordFamily.entries,
        ) {
            "Portable manifest record families are not canonical"
        }
        require(payload.recordCounts.all { it.count >= 0 }) {
            "Portable manifest contains a negative record count"
        }
        val totalRecords = payload.recordCounts.fold(0L) { total, item ->
            try {
                Math.addExact(total, item.count.toLong())
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("Portable record count overflows", failure)
            }
        }
        require(totalRecords <= CloudBounds.MAX_RECORDS_PER_SNAPSHOT) {
            "Portable manifest record count exceeds its bound"
        }
    }
}

private fun <T> DecryptedCloudObject.usePlaintext(block: (ByteArray) -> T): T {
    val plaintext = takePlaintext()
    return try {
        block(plaintext)
    } finally {
        plaintext.fill(0)
        close()
    }
}

private fun VaultKeyEnvelope.clear() {
    kdf.salt.fill(0)
    nonce.fill(0)
    wrappedKeyset.fill(0)
}

private fun validateIdentifier(
    value: String,
    label: String,
) {
    require(value.isNotEmpty() && value.length <= MAX_IDENTIFIER_LENGTH) {
        "$label identifier is outside its bound"
    }
    require(value.none(Char::isISOControl)) { "$label identifier contains control characters" }
}

private fun strictUtf8(
    source: ByteArray,
    label: String,
): String = try {
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(source))
        .toString()
} catch (failure: Exception) {
    throw IllegalArgumentException("$label is not valid UTF-8", failure)
}

private fun readOwnedBytes(
    source: InputStream,
    size: Int,
): ByteArray {
    require(size >= 0) { "Portable read size is negative" }
    val owned = ByteArray(size)
    val scratch = ByteArray(minOf(size, READ_BUFFER_BYTES))
    var offset = 0
    var complete = false
    return try {
        while (offset < size) {
            val requested = minOf(scratch.size, size - offset)
            val count = source.read(scratch, 0, requested)
            if (count < 0) throw IllegalArgumentException("Portable package is truncated")
            if (count == 0) {
                val next = source.read()
                if (next < 0) throw IllegalArgumentException("Portable package is truncated")
                owned[offset++] = next.toByte()
            } else {
                scratch.copyInto(owned, offset, 0, count)
                offset += count
            }
        }
        complete = true
        owned
    } finally {
        scratch.fill(0)
        if (!complete) owned.fill(0)
    }
}

private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return try {
        buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_ALPHABET[value ushr 4])
                append(HEX_ALPHABET[value and 0x0f])
            }
        }
    } finally {
        digest.fill(0)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private object StrictPortableJson {
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowTrailingComma = false
    }
}

private const val MAGIC = "OPEN_TASKS_PORTABLE"
private const val FORMAT_VERSION = 1
private const val MINIMUM_READER_VERSION = 1
private const val LENGTH_PREFIX_BYTES = 4
private const val MINIMUM_PACKAGE_BYTES = 6L
private const val MAX_IDENTIFIER_LENGTH = 200
private const val MAX_HEADER_STABILISATION_PASSES = 8
private const val READ_BUFFER_BYTES = 8 * 1024
private const val HEX_ALPHABET = "0123456789abcdef"
private const val ZERO_SHA256 =
    "0000000000000000000000000000000000000000000000000000000000000000"
private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
private val MAX_PORTABLE_SNAPSHOT_PLAINTEXT_BYTES = (
    BackupPolicy.MAX_PORTABLE_PACKAGE_BYTES -
        CloudBounds.AES_GCM_V1_CIPHERTEXT_OVERHEAD_BYTES
    ).toInt()
