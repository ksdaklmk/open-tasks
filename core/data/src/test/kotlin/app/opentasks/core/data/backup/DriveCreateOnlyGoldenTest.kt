package app.opentasks.core.data.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.RemoteObjectRoleV1
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveCreateOnlyGoldenTest {
    private val crypto = TinkVaultCrypto()
    private val contentKey = crypto.createKey()

    @After
    fun closeKey() {
        contentKey.close()
    }

    @Test
    fun independentOwnershipFixturesMatchExactKotlinBytesAndChainAuthority() {
        val rootFixture = loadOwnership("ownership-root")
        val successorFixture = loadOwnership("ownership-successor")
        val terminatedFixture = loadOwnership("ownership-terminated")

        val root = encodeAndVerify(rootFixture)
        val successorBytes = rootFixture.claimBytesOf(successorFixture)
        val terminatedBytes = rootFixture.claimBytesOf(terminatedFixture)

        assertEquals(RemoteObjectRoleV1.OWNERSHIP_ROOT, root.header.role)
        assertEquals(1L, root.claim.writerEpoch)
        assertNull(root.claim.predecessorProviderFileId)

        val successor = codecFor(successorFixture).verifySuccessor(
            predecessor = root,
            candidate = successorBytes,
            contentKey = contentKey,
        )
        assertEquals(successorFixture.fileSha256, successor.completeSha256.value)
        assertEquals(2L, successor.claim.writerEpoch)
        assertEquals(RemoteObjectRoleV1.OWNERSHIP_CLAIM, successor.header.role)
        assertEquals(root.completeSha256.value, successor.claim.predecessorClaimSha256)
        assertEquals(
            root.claim.nextSuccessorProviderFileId,
            successor.claim.providerFileId,
        )

        val terminated = codecFor(terminatedFixture).verifySuccessor(
            predecessor = successor,
            candidate = terminatedBytes,
            contentKey = contentKey,
        )
        assertEquals(terminatedFixture.fileSha256, terminated.completeSha256.value)
        assertEquals(OwnershipStateV1.TERMINATED, terminated.claim.state)
        assertEquals(RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE, terminated.header.role)
        assertNull(terminated.header.nextSuccessorProviderFileId)
        assertNull(terminated.claim.baselinePublicationSha256)
        assertNull(terminated.claim.recoveryCredentialGeneration)
        assertTrue(terminated.claim.tombstoneId != null)
    }

    @Test
    fun independentPublicationFixturesMatchExactKotlinBytesAndRetainedPairAuthority() {
        val rootFixture = loadOwnership("ownership-root")
        val baselineFixture = loadPublication("publication-baseline")
        val currentFixture = loadPublication("publication-successor")

        val baseline = encodeAndVerify(baselineFixture)
        val current = encodeAndVerify(currentFixture)
        val root = encodeAndVerify(rootFixture)

        assertEquals(0L, baseline.manifest.publicationSequence)
        assertTrue(baseline.manifest.baseline)
        assertNull(baseline.manifest.ownershipClaimSha256)
        assertEquals(
            root.claim.providerFileId,
            baseline.manifest.plannedClaimProviderFileId,
        )
        assertEquals(baselineFixture.bootstrapSha256, baseline.manifest.bootstrapSha256)
        assertEquals(
            root.claim.baselinePublicationSha256,
            baseline.completeSha256.value,
        )

        assertEquals(1L, current.manifest.publicationSequence)
        assertEquals(
            baseline.completeSha256.value,
            current.manifest.predecessorPublicationSha256,
        )
        assertEquals(root.completeSha256.value, current.manifest.ownershipClaimSha256)
        assertNull(current.manifest.plannedClaimProviderFileId)

        val codec = PublicationCodec(
            DefaultAuthenticatedCloudObjectCodec(
                DeterministicFixtureCrypto(currentFixture.keyHex, currentFixture.nonceHex),
            ),
        )
        codec.requireSuccessor(
            previous = baseline.manifest,
            current = current.manifest,
        )
        codec.requireRetainedPair(current = current, previous = baseline, ownership = root)
        codec.requireRetainedPair(current = baseline, previous = null, ownership = root)
    }

    private fun encodeAndVerify(fixture: OwnershipFixture): VerifiedOwnershipClaim {
        val codec = codecFor(fixture)
        val claim = STRICT_JSON.decodeFromString(
            OwnershipClaimV1.serializer(),
            fixture.claimJson,
        )
        val encoded = codec.encode(claim, contentKey)
        assertArrayEquals(fixture.fileHex.hexToBytes(), encoded)
        assertEquals(fixture.fileSha256, sha256(encoded))
        assertEquals(
            fixture.headerJson,
            encoded.copyOfRange(4, 4 + fixture.headerJson.toByteArray().size)
                .toString(Charsets.UTF_8),
        )
        assertArrayEquals(
            fixture.frameHex.hexToBytes(),
            encoded.copyOfRange(4 + fixture.headerJson.toByteArray().size, encoded.size),
        )

        val verified = codec.verify(encoded, contentKey)
        assertEquals(fixture.fileSha256, verified.completeSha256.value)
        assertEquals(claim, verified.claim)
        assertEquals(fixture.frameSha256, verified.header.encryptedFrameSha256)
        return verified
    }

    private fun encodeAndVerify(fixture: PublicationFixture): VerifiedPublication {
        val codec = PublicationCodec(
            DefaultAuthenticatedCloudObjectCodec(
                DeterministicFixtureCrypto(fixture.keyHex, fixture.nonceHex),
            ),
        )
        val manifest = STRICT_JSON.decodeFromString(
            PublicationManifestV1.serializer(),
            fixture.manifestJson,
        )
        val envelope = RecoveryEnvelopeCodec.decode(
            fixture.recoveryEnvelopeJson.toByteArray(),
        )
        val encoded = try {
            assertEquals(fixture.bootstrapSha256, codec.bootstrapSha256(manifest, envelope))
            codec.encode(manifest, envelope, contentKey)
        } finally {
            envelope.kdf.salt.fill(0)
            envelope.nonce.fill(0)
            envelope.wrappedKeyset.fill(0)
        }
        assertArrayEquals(fixture.fileHex.hexToBytes(), encoded)
        assertEquals(fixture.fileSha256, sha256(encoded))
        assertEquals(
            fixture.bootstrapJson,
            encoded.copyOfRange(4, 4 + fixture.bootstrapJson.toByteArray().size)
                .toString(Charsets.UTF_8),
        )
        assertArrayEquals(
            fixture.frameHex.hexToBytes(),
            encoded.copyOfRange(
                4 + fixture.bootstrapJson.toByteArray().size,
                encoded.size,
            ),
        )

        val verified = codec.verify(encoded, contentKey)
        assertEquals(fixture.fileSha256, verified.completeSha256.value)
        assertEquals(manifest, verified.manifest)
        assertEquals(fixture.frameSha256, verified.bootstrap.encryptedFrameSha256)
        return verified
    }

    private fun codecFor(fixture: OwnershipFixture): OwnershipClaimCodec =
        OwnershipClaimCodec(
            DefaultAuthenticatedCloudObjectCodec(
                DeterministicFixtureCrypto(fixture.keyHex, fixture.nonceHex),
            ),
        )

    private fun OwnershipFixture.claimBytesOf(other: OwnershipFixture): ByteArray {
        val codec = codecFor(other)
        val claim = STRICT_JSON.decodeFromString(
            OwnershipClaimV1.serializer(),
            other.claimJson,
        )
        return codec.encode(claim, contentKey).also {
            assertArrayEquals(other.fileHex.hexToBytes(), it)
        }
    }

    private fun loadOwnership(name: String): OwnershipFixture =
        STRICT_JSON.decodeFromString(OwnershipFixture.serializer(), readFixture(name))

    private fun loadPublication(name: String): PublicationFixture =
        STRICT_JSON.decodeFromString(PublicationFixture.serializer(), readFixture(name))

    private fun readFixture(name: String): String {
        val path = "/backup-format/drive-create-only-v1/$name.json"
        return requireNotNull(javaClass.getResourceAsStream(path)) {
            "Missing create-only fixture $path"
        }.bufferedReader().use { it.readText() }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private companion object {
        val STRICT_JSON = Json {
            encodeDefaults = true
            explicitNulls = true
        }
    }
}

@Serializable
private data class OwnershipFixture(
    val keyHex: String,
    val nonceHex: String,
    val headerJson: String,
    val claimJson: String,
    val frameSha256: String,
    val fileSha256: String,
    val frameHex: String,
    val fileHex: String,
)

@Serializable
private data class PublicationFixture(
    val keyHex: String,
    val nonceHex: String,
    val bootstrapJson: String,
    val manifestJson: String,
    val recoveryEnvelopeJson: String,
    val bootstrapSha256: String,
    val frameSha256: String,
    val fileSha256: String,
    val frameHex: String,
    val fileHex: String,
)

/**
 * Encrypts with the fixture's exact key and nonce so the Kotlin codecs produce
 * the independently generated bytes byte for byte.
 */
private class DeterministicFixtureCrypto(
    private val keyHex: String,
    private val nonceHex: String,
    private val delegate: VaultCrypto = TinkVaultCrypto(),
) : VaultCrypto by delegate {
    override fun encryptBytes(
        key: VaultKey,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val rawKey = keyHex.hexToBytes()
        val nonce = nonceHex.hexToBytes()
        return try {
            val encrypted = Cipher.getInstance(AES_GCM).run {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(rawKey, AES),
                    GCMParameterSpec(GCM_TAG_BITS, nonce),
                )
                updateAAD(associatedData)
                doFinal(plaintext)
            }
            TINK_PREFIX + nonce + encrypted
        } finally {
            rawKey.fill(0)
            nonce.fill(0)
        }
    }

    override fun decryptBytes(
        key: VaultKey,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(ciphertext.size > TINK_PREFIX.size + NONCE_BYTES + TAG_BYTES)
        require(ciphertext.copyOfRange(0, TINK_PREFIX.size).contentEquals(TINK_PREFIX))
        val rawKey = keyHex.hexToBytes()
        val nonce = ciphertext.copyOfRange(TINK_PREFIX.size, TINK_PREFIX.size + NONCE_BYTES)
        try {
            return Cipher.getInstance(AES_GCM).run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(rawKey, AES),
                    GCMParameterSpec(GCM_TAG_BITS, nonce),
                )
                updateAAD(associatedData)
                doFinal(
                    ciphertext,
                    TINK_PREFIX.size + NONCE_BYTES,
                    ciphertext.size - TINK_PREFIX.size - NONCE_BYTES,
                )
            }
        } finally {
            nonce.fill(0)
            rawKey.fill(0)
        }
    }

    private companion object {
        const val AES = "AES"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        val TINK_PREFIX = "010000002a".hexToBytes()
    }
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
