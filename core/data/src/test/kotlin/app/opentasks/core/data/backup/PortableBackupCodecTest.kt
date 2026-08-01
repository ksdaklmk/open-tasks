package app.opentasks.core.data.backup

import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.db.WorkflowStatusEntity
import app.opentasks.core.domain.BackupPolicy
import app.opentasks.core.sync.CloudBounds
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableBackupCodecTest {
    private val crypto = TinkVaultCrypto()
    private val authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto)
    private val codec = PortableBackupCodec(authenticatedCodec)

    @Test
    fun encodeWritesCanonicalBootstrapManifestAndSnapshotFramesInExactOrder() {
        val key = crypto.createKey()
        val envelope = envelope()
        val snapshot = snapshot()
        val packageBytes = try {
            codec.encode(envelope, snapshot, PRODUCED_AT, key)
        } finally {
            key.close()
            clearEnvelope(envelope)
        }

        val headerLength = ByteBuffer.wrap(packageBytes, 0, Integer.BYTES).int
        val header = codec.readBootstrap(
            ByteArrayInputStream(packageBytes),
            packageBytes.size.toLong(),
        )
        val headerText = packageBytes.copyOfRange(4, 4 + headerLength).toString(Charsets.UTF_8)
        assertEquals(TEST_JSON.encodeToString(header), headerText)
        assertEquals("OPEN_TASKS_PORTABLE", header.magic)
        assertEquals("vault-alpha", header.vaultId)
        assertEquals(GENERATION, header.generation)
        assertEquals(PRODUCED_AT, header.producedAtEpochMillis)
        assertEquals(packageBytes.size.toLong(), header.totalPackageLength)

        val manifestStart = 4 + headerLength
        val manifestEnd = manifestStart + header.manifestFrameLength.toInt()
        val manifestFrame = packageBytes.copyOfRange(manifestStart, manifestEnd)
        val snapshotFrame = packageBytes.copyOfRange(manifestEnd, packageBytes.size)
        assertEquals(header.manifestFrameSha256, sha256(manifestFrame))
        assertEquals(header.snapshotFrameSha256, sha256(snapshotFrame))
        assertEquals(header.snapshotFrameLength, snapshotFrame.size.toLong())

        packageBytes.fill(0)
        manifestFrame.fill(0)
        snapshotFrame.fill(0)
    }

    @Test
    fun completeVerificationAuthenticatesExactIdentitiesEnvelopeDigestAndFamilyCounts() {
        val recording = RecordingAuthenticatedCodec(authenticatedCodec)
        val recordingCodec = PortableBackupCodec(recording)
        val key = crypto.createKey()
        val envelope = envelope()
        val packageBytes = try {
            recordingCodec.encode(envelope, snapshot(), PRODUCED_AT, key)
        } finally {
            clearEnvelope(envelope)
        }

        val verified = try {
            recordingCodec.verifyComplete(
                ByteArrayInputStream(packageBytes),
                packageBytes.size.toLong(),
                key,
            )
        } finally {
            key.close()
        }

        assertEquals("vault-alpha", verified.vaultId)
        assertEquals(GENERATION, verified.generation)
        assertEquals(PRODUCED_AT, verified.producedAtEpochMillis)
        assertEquals(packageBytes.size.toLong(), verified.totalPackageLength)
        assertEquals(
            listOf(
                CloudObjectFamily.SNAPSHOT to "snapshot:$GENERATION",
                CloudObjectFamily.MANIFEST to "portable-manifest:$GENERATION",
            ),
            recording.encryptedIdentities.map { it.family to it.objectId },
        )
        assertEquals(
            listOf(
                CloudObjectFamily.MANIFEST to "portable-manifest:$GENERATION",
                CloudObjectFamily.SNAPSHOT to "snapshot:$GENERATION",
            ),
            recording.decryptedIdentities.map { it.family to it.objectId },
        )
        packageBytes.fill(0)
    }

    @Test
    fun bootstrapParsingEnforcesHeaderAndPackageBoundsWithoutReadingDeclaredFrames() {
        val maximum = BackupPolicy.MAX_PORTABLE_PACKAGE_BYTES
        val accepted = bootstrapOnlyPackage(maximum)
        val input = CountingInputStream(accepted)

        val header = codec.readBootstrap(input, maximum)

        assertEquals(maximum, header.totalPackageLength)
        assertEquals(accepted.size.toLong(), input.bytesRead)
        val over = bootstrapOnlyPackage(maximum + 1)
        assertThrows(IllegalArgumentException::class.java) {
            codec.readBootstrap(CountingInputStream(over), maximum + 1)
        }

        val oversizedHeader = ByteBuffer.allocate(4).putInt(16 * 1024 + 1).array()
        val oversizedHeaderInput = CountingInputStream(oversizedHeader)
        assertThrows(IllegalArgumentException::class.java) {
            codec.readBootstrap(oversizedHeaderInput, 6)
        }
        assertEquals(4, oversizedHeaderInput.bytesRead)
        accepted.fill(0)
        over.fill(0)
        oversizedHeader.fill(0)
    }

    @Test
    fun encodeRejectsPortableOversizeDuringBoundedSnapshotSerializationBeforeEncryption() {
        val recording = RecordingAuthenticatedCodec(authenticatedCodec)
        var requestedMaximum: Int? = null
        val boundedSnapshotCodec = object : BackupSnapshotCodec by BackupSnapshotCodec {
            override fun encodeBounded(
                payload: BackupSnapshotPayloadV1,
                maximumBytes: Int,
            ): ByteArray {
                requestedMaximum = maximumBytes
                throw BackupPayloadTooLargeException("snapshot", maximumBytes)
            }
        }
        val codec = PortableBackupCodec(recording, boundedSnapshotCodec)
        val key = crypto.createKey()
        val recoveryEnvelope = envelope()

        try {
            assertThrows(PortablePackageTooLargeException::class.java) {
                codec.encode(recoveryEnvelope, snapshot(), PRODUCED_AT, key)
            }
            assertTrue(checkNotNull(requestedMaximum) < BackupPolicy.MAX_PORTABLE_PACKAGE_BYTES)
            assertTrue(recording.encryptedIdentities.isEmpty())
        } finally {
            recoveryEnvelope.kdf.salt.fill(0)
            recoveryEnvelope.nonce.fill(0)
            recoveryEnvelope.wrappedKeyset.fill(0)
            key.close()
        }
    }

    @Test
    fun encodeAcceptsSnapshotAboveGeneralManifestReservationWhenExactPackageStillFits() {
        val oldConservativeCeiling = (
            BackupPolicy.MAX_PORTABLE_PACKAGE_BYTES -
                4 -
                CloudBounds.MAX_HEADER_BYTES -
                (4 + CloudBounds.MAX_HEADER_BYTES + CloudBounds.MAX_MANIFEST_CIPHERTEXT_BYTES) -
                (
                    4 +
                        CloudBounds.MAX_HEADER_BYTES +
                        CloudBounds.AES_GCM_V1_CIPHERTEXT_OVERHEAD_BYTES
                    )
            ).toInt()
        val desiredSnapshotBytes = oldConservativeCeiling + 1
        var requestedMaximum: Int? = null
        val largeSnapshotCodec = object : BackupSnapshotCodec by BackupSnapshotCodec {
            override fun encodeBounded(
                payload: BackupSnapshotPayloadV1,
                maximumBytes: Int,
            ): ByteArray {
                requestedMaximum = maximumBytes
                if (desiredSnapshotBytes > maximumBytes) {
                    throw BackupPayloadTooLargeException("snapshot", maximumBytes)
                }
                return ByteArray(desiredSnapshotBytes)
            }
        }
        val lengthOnlyAuthenticatedCodec = object : AuthenticatedCloudObjectCodec {
            override fun encrypt(
                identity: CloudHeaderIdentity,
                plaintext: ByteArray,
                key: VaultKey,
            ): ByteArray = ByteArray(plaintext.size + 256)

            override fun decrypt(
                source: InputStream,
                totalLength: Long,
                key: VaultKey,
            ): CloudDecodeResult = throw AssertionError("Encode must not decrypt")
        }
        val codec = PortableBackupCodec(lengthOnlyAuthenticatedCodec, largeSnapshotCodec)
        val key = crypto.createKey()
        val recoveryEnvelope = envelope()

        val packageBytes = try {
            codec.encode(recoveryEnvelope, snapshot(), PRODUCED_AT, key)
        } finally {
            recoveryEnvelope.kdf.salt.fill(0)
            recoveryEnvelope.nonce.fill(0)
            recoveryEnvelope.wrappedKeyset.fill(0)
            key.close()
        }

        assertTrue(checkNotNull(requestedMaximum) > oldConservativeCeiling)
        assertTrue(packageBytes.size.toLong() <= BackupPolicy.MAX_PORTABLE_PACKAGE_BYTES)
        packageBytes.fill(0)
    }

    @Test
    fun invalidLengthsOverflowAndInconsistentTotalAreRejectedBeforeFrameAllocation() {
        val valid = bootstrapOnlyPackage(1_000_000)
        val headerLength = ByteBuffer.wrap(valid, 0, 4).int
        val header = TEST_JSON.decodeFromString<PortableBootstrapHeaderV1>(
            valid.copyOfRange(4, 4 + headerLength).toString(Charsets.UTF_8),
        )
        val invalid = listOf(
            header.copy(manifestFrameLength = -1),
            header.copy(snapshotFrameLength = -1),
            header.copy(
                manifestFrameLength = Long.MAX_VALUE,
                snapshotFrameLength = Long.MAX_VALUE,
                totalPackageLength = Long.MAX_VALUE,
            ),
            header.copy(totalPackageLength = header.totalPackageLength - 1),
        )

        invalid.forEachIndexed { index, candidate ->
            val bytes = canonicalBootstrap(candidate)
            assertThrows("candidate $index", IllegalArgumentException::class.java) {
                codec.readBootstrap(
                    CountingInputStream(bytes),
                    if (index == 3) {
                        header.totalPackageLength
                    } else {
                        candidate.totalPackageLength.coerceAtLeast(bytes.size.toLong())
                    },
                )
            }
            bytes.fill(0)
        }
        valid.fill(0)
    }

    @Test
    fun checksumsFailBeforeAuthenticatedDecryptionAndTruncationNeverAuthenticates() {
        val counting = RecordingAuthenticatedCodec(authenticatedCodec)
        val testCodec = PortableBackupCodec(counting)
        val key = crypto.createKey()
        val envelope = envelope()
        val encoded = try {
            testCodec.encode(envelope, snapshot(), PRODUCED_AT, key)
        } finally {
            clearEnvelope(envelope)
        }
        val decryptsAfterEncode = counting.decryptedIdentities.size
        val headerLength = ByteBuffer.wrap(encoded, 0, 4).int
        val tampered = encoded.copyOf().also { bytes ->
            bytes[4 + headerLength] = (bytes[4 + headerLength].toInt() xor 1).toByte()
        }
        val truncated = encoded.copyOf(encoded.size - 1)

        assertThrows(IllegalArgumentException::class.java) {
            testCodec.verifyComplete(ByteArrayInputStream(tampered), tampered.size.toLong(), key)
        }
        assertEquals(decryptsAfterEncode, counting.decryptedIdentities.size)
        assertThrows(IllegalArgumentException::class.java) {
            testCodec.verifyComplete(
                ByteArrayInputStream(truncated),
                truncated.size.toLong(),
                key,
            )
        }
        assertEquals(decryptsAfterEncode, counting.decryptedIdentities.size)

        key.close()
        encoded.fill(0)
        tampered.fill(0)
        truncated.fill(0)
    }

    @Test
    fun wrongKeySwappedFramesAndHeaderSubstitutionAreRejected() {
        val key = crypto.createKey()
        val envelope = envelope()
        val encoded = try {
            codec.encode(envelope, snapshot(), PRODUCED_AT, key)
        } finally {
            clearEnvelope(envelope)
        }
        val wrongKey = crypto.createKey()
        assertThrows(IllegalArgumentException::class.java) {
            codec.verifyComplete(ByteArrayInputStream(encoded), encoded.size.toLong(), wrongKey)
        }
        wrongKey.close()

        val swapped = swapFrames(encoded)
        assertThrows(IllegalArgumentException::class.java) {
            codec.verifyComplete(ByteArrayInputStream(swapped), swapped.size.toLong(), key)
        }

        val substituted = replaceCanonicalHeader(encoded) {
            it.copy(generation = it.generation + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.verifyComplete(
                ByteArrayInputStream(substituted),
                substituted.size.toLong(),
                key,
            )
        }

        key.close()
        encoded.fill(0)
        swapped.fill(0)
        substituted.fill(0)
    }

    @Test
    fun futureUnknownReorderedDuplicateInvalidUtf8AndRecoveryMetadataAreRejected() {
        val valid = canonicalBootstrap(
            bootstrapHeader(
                totalLength = 1_000_000,
                manifestLength = 100,
                snapshotLength = 200,
            ),
        )
        val text = valid.copyOfRange(4, valid.size).toString(Charsets.UTF_8)
        val sources = listOf(
            text.replace(""""packageVersion":1""", """"packageVersion":2"""),
            text.replace(
                """"packageVersion":1""",
                """"packageVersion":1,"unknown":true""",
            ),
            text.replace(
                """"magic":"OPEN_TASKS_PORTABLE","packageVersion":1""",
                """"packageVersion":1,"magic":"OPEN_TASKS_PORTABLE"""",
            ),
            text.replace(
                """"packageVersion":1""",
                """"packageVersion":1,"packageVersion":1""",
            ),
            text.replace(""""memoryKiB":65536""", """"memoryKiB":32768"""),
        )
        sources.forEach { source ->
            val bytes = lengthPrefixed(source.toByteArray())
            assertThrows(IllegalArgumentException::class.java) {
                codec.readBootstrap(ByteArrayInputStream(bytes), 1_000_000)
            }
            bytes.fill(0)
        }
        val invalidUtf8 = valid.copyOf().also { it[4] = 0xc0.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            codec.readBootstrap(ByteArrayInputStream(invalidUtf8), 1_000_000)
        }
        valid.fill(0)
        invalidUtf8.fill(0)
    }

    @Test
    fun authenticatedManifestSubstitutionGenerationAndCountMismatchAreRejected() {
        val key = crypto.createKey()
        val envelope = envelope()
        val encoded = try {
            codec.encode(envelope, snapshot(), PRODUCED_AT, key)
        } finally {
            clearEnvelope(envelope)
        }
        val manifest = manifestFor(encoded)
        val substitutions = listOf(
            manifest.copy(producedAtEpochMillis = PRODUCED_AT + 1),
            manifest.copy(generation = GENERATION + 1),
            manifest.copy(
                recordCounts = manifest.recordCounts.map {
                    if (it.family == BackupRecordFamily.MEMBER) it.copy(count = 2) else it
                },
            ),
        )

        substitutions.forEach { replacement ->
            val mutated = replaceManifest(
                encoded,
                key,
                TEST_JSON.encodeToString(replacement).toByteArray(),
            )
            assertThrows(IllegalArgumentException::class.java) {
                codec.verifyComplete(ByteArrayInputStream(mutated), mutated.size.toLong(), key)
            }
            mutated.fill(0)
        }

        key.close()
        encoded.fill(0)
    }

    @Test
    fun authenticatedFutureUnknownReorderedDuplicateAndInvalidUtf8ManifestAreRejected() {
        val key = crypto.createKey()
        val envelope = envelope()
        val encoded = try {
            codec.encode(envelope, snapshot(), PRODUCED_AT, key)
        } finally {
            clearEnvelope(envelope)
        }
        val canonical = TEST_JSON.encodeToString(manifestFor(encoded))
        val invalidPlaintexts = listOf(
            canonical.replace(""""packageVersion":1""", """"packageVersion":2""")
                .toByteArray(),
            canonical.replace(
                """"packageVersion":1""",
                """"packageVersion":1,"unknown":true""",
            ).toByteArray(),
            canonical.replace(
                """"packageVersion":1,"minimumReaderVersion":1""",
                """"minimumReaderVersion":1,"packageVersion":1""",
            ).toByteArray(),
            canonical.replace(
                """"packageVersion":1""",
                """"packageVersion":1,"packageVersion":1""",
            ).toByteArray(),
            byteArrayOf(0xc0.toByte()),
        )

        invalidPlaintexts.forEach { plaintext ->
            val mutated = replaceManifest(encoded, key, plaintext)
            assertThrows(IllegalArgumentException::class.java) {
                codec.verifyComplete(ByteArrayInputStream(mutated), mutated.size.toLong(), key)
            }
            plaintext.fill(0)
            mutated.fill(0)
        }

        key.close()
        encoded.fill(0)
    }

    @Test
    fun encodeAndVerifyClearOwnedPlaintextAndFrameBuffersOnSuccessAndFailure() {
        val inspecting = InspectingAuthenticatedCodec(authenticatedCodec)
        val ownedReads = mutableListOf<ByteArray>()
        val testCodec = PortableBackupCodec(
            authenticatedCodec = inspecting,
            ownedFrameReader = { source, size ->
                ByteArray(size).also { bytes ->
                    readExact(source, bytes)
                    ownedReads += bytes
                }
            },
        )
        val key = crypto.createKey()
        val envelope = envelope()
        val encoded = try {
            testCodec.encode(envelope, snapshot(), PRODUCED_AT, key)
        } finally {
            clearEnvelope(envelope)
        }
        assertTrue(inspecting.encryptionPlaintexts.all(::allZero))
        assertTrue(inspecting.encryptedFrames.all(::allZero))

        testCodec.verifyComplete(ByteArrayInputStream(encoded), encoded.size.toLong(), key)
        assertTrue(inspecting.decryptionPlaintexts.all(::allZero))
        assertTrue(ownedReads.all(::allZero))

        val failed = encoded.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            testCodec.verifyComplete(ByteArrayInputStream(failed), failed.size.toLong(), key)
        }
        assertTrue(ownedReads.all(::allZero))

        key.close()
        encoded.fill(0)
        failed.fill(0)
    }


    // -- Recovery decode -----------------------------------------------------------------

    @Test
    fun decodeCompleteAuthenticatesExactlyWhatVerifyCompleteDoes() {
        val recovery = PortableRecoveryCrypto()
        val recoveryCodec = PortableBackupCodec(DefaultAuthenticatedCloudObjectCodec(recovery))
        val file = recovery.writePackage(recoveryCodec)

        val decoded = recoveryCodec.decodeComplete(file, PASSPHRASE.copyOf(), recovery)

        val verified = file.inputStream().use { stream ->
            recoveryCodec.verifyComplete(stream, file.length(), recovery.createKey())
        }
        decoded.use {
            // Decoding canonicalises record order, so the payloads are compared
            // as the canonical bytes both the encoder and the reader agree on.
            val expected = BackupSnapshotCodec.encode(snapshot())
            val actual = BackupSnapshotCodec.encode(decoded.snapshot)
            try {
                assertArrayEquals(expected, actual)
            } finally {
                expected.fill(0)
                actual.fill(0)
            }
            assertEquals(GENERATION, decoded.generation.value)
            assertEquals(verified.vaultId, decoded.snapshot.vaultId)
            assertEquals(verified.generation, decoded.generation.value)
            assertEquals(PRODUCED_AT, verified.producedAtEpochMillis)
            val canonicalEnvelope = RecoveryEnvelopeCodec.encode(decoded.recoveryEnvelope)
            try {
                assertEquals(verified.recoveryEnvelopeSha256, sha256(canonicalEnvelope))
            } finally {
                canonicalEnvelope.fill(0)
            }
            assertEquals(file.length(), verified.totalPackageLength)
        }
        recovery.close()
    }

    @Test
    fun decodeCompleteOwnsItsEnvelopeAndClosesTheKeyItDerived() {
        val recovery = PortableRecoveryCrypto()
        val recoveryCodec = PortableBackupCodec(DefaultAuthenticatedCloudObjectCodec(recovery))
        val file = recovery.writePackage(recoveryCodec)

        val decoded = recoveryCodec.decodeComplete(file, PASSPHRASE.copyOf(), recovery)

        assertEquals(1, recovery.unlockCount)
        // The derived key never escapes: everything the derivation issued is
        // closed by the time the decoded package is returned.
        assertTrue(recovery.issued.isNotEmpty())
        assertTrue(recovery.issued.all(recovery::isClosed))
        // The envelope is this result's own copy, and closing it clears it.
        val envelope = decoded.recoveryEnvelope
        assertTrue(envelope.kdf.salt.any { it != 0.toByte() })
        assertTrue(envelope.wrappedKeyset.any { it != 0.toByte() })
        decoded.close()
        assertTrue(allZero(envelope.kdf.salt))
        assertTrue(allZero(envelope.nonce))
        assertTrue(allZero(envelope.wrappedKeyset))
        // Clearing it changed nothing the package still holds.
        file.inputStream().use { stream ->
            recoveryCodec.verifyComplete(stream, file.length(), recovery.createKey())
        }
        recovery.close()
    }

    @Test
    fun decodeCompleteRejectsAPackageOutsideItsBoundBeforeDeriving() {
        val recovery = PortableRecoveryCrypto()
        val recoveryCodec = PortableBackupCodec(DefaultAuthenticatedCloudObjectCodec(recovery))
        val file = recovery.writePackage(recoveryCodec)
        file.writeBytes(file.readBytes().copyOf(MINIMUM_PACKAGE_BYTES - 1))

        assertThrows(IllegalArgumentException::class.java) {
            recoveryCodec.decodeComplete(file, PASSPHRASE.copyOf(), recovery)
        }

        assertEquals(0, recovery.unlockCount)
        recovery.close()
    }

    @Test
    fun decodeCompleteSeparatesAWrongPassphraseFromACorruptPackage() {
        val recovery = PortableRecoveryCrypto()
        val recoveryCodec = PortableBackupCodec(DefaultAuthenticatedCloudObjectCodec(recovery))
        val file = recovery.writePackage(recoveryCodec)

        assertThrows(RecoveryPassphraseException::class.java) {
            recoveryCodec.decodeComplete(file, "not-the-passphrase".toCharArray(), recovery)
        }

        val corrupted = recovery.writePackage(recoveryCodec, name = "corrupt.otbk")
        corrupted.writeBytes(
            corrupted.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
        )
        val failure = assertThrows(IllegalArgumentException::class.java) {
            recoveryCodec.decodeComplete(corrupted, PASSPHRASE.copyOf(), recovery)
        }
        assertTrue(failure !is RecoveryPassphraseException)
        recovery.close()
    }

    /**
     * Addresses one vault's content under every handle it issues, so a package
     * encoded here decodes under a derivation this test can observe without
     * paying for Argon2id.
     */
    private inner class PortableRecoveryCrypto : app.opentasks.core.crypto.VaultCrypto {
        private val delegate = TinkVaultCrypto()
        private val established: VaultKey = delegate.createKey()
        val issued = mutableListOf<VaultKey>()
        var unlockCount = 0
            private set

        override fun createKey(): VaultKey = delegate.createKey()

        override fun wrapForRecovery(
            unlockedKey: VaultKey,
            passphrase: CharArray,
        ): VaultKeyEnvelope = error("Recovery decode must not wrap an envelope")

        override fun unlock(
            passphrase: CharArray,
            envelope: VaultKeyEnvelope,
        ): VaultKey {
            unlockCount += 1
            require(passphrase.concatToString() == PASSPHRASE.concatToString()) {
                "The recovery passphrase does not unlock this envelope"
            }
            return delegate.createKey().also { issued += it }
        }

        override fun changePassphrase(
            unlockedKey: VaultKey,
            newPassphrase: CharArray,
        ): VaultKeyEnvelope = error("Recovery decode must not rotate a passphrase")

        override fun encryptBytes(
            key: VaultKey,
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray = delegate.encryptBytes(established, plaintext, associatedData)

        override fun decryptBytes(
            key: VaultKey,
            ciphertext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray = delegate.decryptBytes(established, ciphertext, associatedData)

        fun isClosed(key: VaultKey): Boolean =
            runCatching { delegate.encryptBytes(key, ByteArray(1), ByteArray(0)) }.isFailure

        fun writePackage(
            codec: PortableBackupCodec,
            name: String = "package.otbk",
        ): File {
            val key = createKey()
            val envelope = envelope()
            val encoded = try {
                codec.encode(envelope, snapshot(), PRODUCED_AT, key)
            } finally {
                key.close()
                clearEnvelope(envelope)
            }
            val directory = Files.createTempDirectory("portable-recovery-test").toFile()
            temporaryDirectories += directory
            return File(directory, name).also { it.writeBytes(encoded) }
        }

        fun close() {
            established.close()
            temporaryDirectories.forEach { it.deleteRecursively() }
            temporaryDirectories.clear()
        }
    }

    private val temporaryDirectories = mutableListOf<File>()

    private fun bootstrapOnlyPackage(totalLength: Long): ByteArray {
        val manifestLength = 100L
        var headerLength = 0
        var encoded = ByteArray(0)
        repeat(8) {
            val header = bootstrapHeader(
                totalLength = totalLength,
                manifestLength = manifestLength,
                snapshotLength = totalLength - manifestLength - 4 - headerLength,
            )
            encoded = canonicalBootstrap(header)
            val nextHeaderLength = encoded.size - 4
            if (nextHeaderLength == headerLength) return encoded
            headerLength = nextHeaderLength
        }
        return encoded
    }

    private fun bootstrapHeader(
        totalLength: Long,
        manifestLength: Long,
        snapshotLength: Long,
    ): PortableBootstrapHeaderV1 = PortableBootstrapHeaderV1(
        vaultId = "vault-alpha",
        generation = GENERATION,
        producedAtEpochMillis = PRODUCED_AT,
        recoveryEnvelope = RecoveryEnvelopeCodec.toPayload(envelope()),
        manifestFrameLength = manifestLength,
        manifestFrameSha256 = "0".repeat(64),
        snapshotFrameLength = snapshotLength,
        snapshotFrameSha256 = "1".repeat(64),
        totalPackageLength = totalLength,
    )

    private fun canonicalBootstrap(header: PortableBootstrapHeaderV1): ByteArray =
        lengthPrefixed(TEST_JSON.encodeToString(header).toByteArray())

    private fun lengthPrefixed(bytes: ByteArray): ByteArray =
        ByteArray(4 + bytes.size).also {
            ByteBuffer.wrap(it, 0, 4).putInt(bytes.size)
            bytes.copyInto(it, 4)
        }

    private fun replaceCanonicalHeader(
        packageBytes: ByteArray,
        transform: (PortableBootstrapHeaderV1) -> PortableBootstrapHeaderV1,
    ): ByteArray {
        val oldLength = ByteBuffer.wrap(packageBytes, 0, 4).int
        val old = TEST_JSON.decodeFromString<PortableBootstrapHeaderV1>(
            packageBytes.copyOfRange(4, 4 + oldLength).toString(Charsets.UTF_8),
        )
        var candidate = transform(old)
        repeat(8) {
            val headerBytes = TEST_JSON.encodeToString(candidate).toByteArray()
            val total = 4L + headerBytes.size + candidate.manifestFrameLength +
                candidate.snapshotFrameLength
            candidate = candidate.copy(totalPackageLength = total)
        }
        val prefix = canonicalBootstrap(candidate)
        return prefix + packageBytes.copyOfRange(4 + oldLength, packageBytes.size)
    }

    private fun manifestFor(packageBytes: ByteArray): PortableManifestPayloadV1 {
        val headerLength = ByteBuffer.wrap(packageBytes, 0, 4).int
        val header = TEST_JSON.decodeFromString<PortableBootstrapHeaderV1>(
            packageBytes.copyOfRange(4, 4 + headerLength).toString(Charsets.UTF_8),
        )
        val counts = snapshot().records.groupingBy(BackupRecordV1::family).eachCount()
        val envelope = envelope()
        val envelopeBytes = RecoveryEnvelopeCodec.encode(envelope)
        val envelopeSha = try {
            sha256(envelopeBytes)
        } finally {
            envelopeBytes.fill(0)
            clearEnvelope(envelope)
        }
        return PortableManifestPayloadV1(
            vaultId = header.vaultId,
            generation = header.generation,
            producedAtEpochMillis = header.producedAtEpochMillis,
            recoveryEnvelopeSha256 = envelopeSha,
            snapshotObjectId = "snapshot:${header.generation}",
            snapshotFrameLength = header.snapshotFrameLength,
            snapshotFrameSha256 = header.snapshotFrameSha256,
            recordCounts = BackupRecordFamily.entries.map {
                BackupRecordFamilyCountV1(it, counts[it] ?: 0)
            },
        )
    }

    private fun replaceManifest(
        packageBytes: ByteArray,
        key: VaultKey,
        plaintext: ByteArray,
    ): ByteArray {
        val oldHeaderLength = ByteBuffer.wrap(packageBytes, 0, 4).int
        val oldHeader = TEST_JSON.decodeFromString<PortableBootstrapHeaderV1>(
            packageBytes.copyOfRange(4, 4 + oldHeaderLength).toString(Charsets.UTF_8),
        )
        val oldManifestStart = 4 + oldHeaderLength
        val snapshotStart = oldManifestStart + oldHeader.manifestFrameLength.toInt()
        val snapshotFrame = packageBytes.copyOfRange(snapshotStart, packageBytes.size)
        val newManifestFrame = authenticatedCodec.encrypt(
            CloudHeaderIdentity(
                family = CloudObjectFamily.MANIFEST,
                schemaVersion = 1,
                cryptoVersion = 1,
                minimumReaderVersion = 1,
                vaultId = oldHeader.vaultId,
                objectId = "portable-manifest:${oldHeader.generation}",
            ),
            plaintext,
            key,
        )
        var header = oldHeader.copy(
            manifestFrameLength = newManifestFrame.size.toLong(),
            manifestFrameSha256 = sha256(newManifestFrame),
            totalPackageLength = 0,
        )
        repeat(8) {
            val headerLength = TEST_JSON.encodeToString(header).toByteArray().size
            header = header.copy(
                totalPackageLength = 4L + headerLength +
                    newManifestFrame.size + snapshotFrame.size,
            )
        }
        val prefix = canonicalBootstrap(header)
        return try {
            prefix + newManifestFrame + snapshotFrame
        } finally {
            prefix.fill(0)
            newManifestFrame.fill(0)
            snapshotFrame.fill(0)
        }
    }

    private fun swapFrames(packageBytes: ByteArray): ByteArray {
        val headerLength = ByteBuffer.wrap(packageBytes, 0, 4).int
        val header = TEST_JSON.decodeFromString<PortableBootstrapHeaderV1>(
            packageBytes.copyOfRange(4, 4 + headerLength).toString(Charsets.UTF_8),
        )
        val first = 4 + headerLength
        val middle = first + header.manifestFrameLength.toInt()
        val manifest = packageBytes.copyOfRange(first, middle)
        val snapshot = packageBytes.copyOfRange(middle, packageBytes.size)
        val replacement = header.copy(
            manifestFrameLength = snapshot.size.toLong(),
            manifestFrameSha256 = sha256(snapshot),
            snapshotFrameLength = manifest.size.toLong(),
            snapshotFrameSha256 = sha256(manifest),
        )
        val prefix = canonicalBootstrap(replacement)
        manifest.fill(0)
        return prefix + snapshot + packageBytes.copyOfRange(first, middle)
    }

    private fun snapshot(): BackupSnapshotPayloadV1 = BackupSnapshotPayloadV1(
        vaultId = "vault-alpha",
        coveredGeneration = GENERATION,
        records = listOf(
            BackupRecordV1(
                family = BackupRecordFamily.VAULT,
                identity = listOf("vault-alpha"),
                fields = listOf(
                    stringField("id", "vault-alpha"),
                    longField("createdAtEpochMillis", "1"),
                    intField("schemaVersion", "6"),
                    intField("cryptoVersion", "1"),
                    intField("minimumReaderVersion", "1"),
                ),
            ),
            BackupRecordV1(
                family = BackupRecordFamily.WORKSPACE,
                identity = listOf("workspace-1"),
                fields = listOf(
                    stringField("id", "workspace-1"),
                    stringField("vaultId", "vault-alpha"),
                    stringField("ownerId", "member-1"),
                    stringField("name", "Workspace"),
                ),
            ),
            BackupRecordV1(
                family = BackupRecordFamily.MEMBER,
                identity = listOf("member-1"),
                fields = listOf(
                    stringField("id", "member-1"),
                    stringField("displayName", "Member"),
                ),
            ),
        ) + listOf("BACKLOG", "PLANNED", "STARTED", "BLOCKED", "COMPLETED")
            .mapIndexed { index, semantic ->
                WorkflowStatusEntity(
                    id = "status-inbox-${semantic.lowercase()}",
                    projectId = null,
                    name = "Inbox ${semantic.lowercase()}",
                    semanticStatus = semantic,
                    rank = "inbox-$index",
                    archivedAtEpochMillis = null,
                    revisionWallMillis = 1,
                    revisionLogical = index,
                    revisionDeviceId = "device-alpha",
                ).toBackupRecordV1()
            },
    )

    private fun stringField(name: String, value: String) =
        BackupFieldV1(name, BackupFieldType.STRING, value)

    private fun longField(name: String, value: String) =
        BackupFieldV1(name, BackupFieldType.LONG, value)

    private fun intField(name: String, value: String) =
        BackupFieldV1(name, BackupFieldType.INT, value)

    private fun envelope(): VaultKeyEnvelope = VaultKeyEnvelope(
        formatVersion = 1,
        kdf = Argon2Metadata(ByteArray(16) { it.toByte() }),
        nonce = ByteArray(12) { (it + 16).toByte() },
        wrappedKeyset = ByteArray(8) { (it + 28).toByte() },
    )

    private fun clearEnvelope(envelope: VaultKeyEnvelope) {
        envelope.kdf.salt.fill(0)
        envelope.nonce.fill(0)
        envelope.wrappedKeyset.fill(0)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private fun allZero(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

    private fun readExact(source: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = source.read(target, offset, target.size - offset)
            check(count > 0)
            offset += count
        }
    }

    private class CountingInputStream(
        private val bytes: ByteArray,
    ) : InputStream() {
        var bytesRead: Long = 0
            private set
        private var offset = 0

        override fun read(): Int {
            if (offset == bytes.size) return -1
            bytesRead += 1
            return bytes[offset++].toInt() and 0xff
        }

        override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
            if (offset == bytes.size) return -1
            val count = minOf(length, bytes.size - offset)
            bytes.copyInto(target, targetOffset, offset, offset + count)
            offset += count
            bytesRead += count
            return count
        }
    }

    private open class RecordingAuthenticatedCodec(
        private val delegate: AuthenticatedCloudObjectCodec,
    ) : AuthenticatedCloudObjectCodec {
        val encryptedIdentities = mutableListOf<CloudHeaderIdentity>()
        val decryptedIdentities = mutableListOf<CloudHeaderIdentity>()

        override fun encrypt(
            identity: CloudHeaderIdentity,
            plaintext: ByteArray,
            key: VaultKey,
        ): ByteArray {
            encryptedIdentities += identity
            return delegate.encrypt(identity, plaintext, key)
        }

        override fun decrypt(
            source: InputStream,
            totalLength: Long,
            key: VaultKey,
        ): CloudDecodeResult = delegate.decrypt(source, totalLength, key).also { result ->
            if (result is CloudDecodeResult.Success) {
                decryptedIdentities += result.value.identity
            }
        }
    }

    private class InspectingAuthenticatedCodec(
        delegate: AuthenticatedCloudObjectCodec,
    ) : RecordingAuthenticatedCodec(delegate) {
        val encryptionPlaintexts = mutableListOf<ByteArray>()
        val encryptedFrames = mutableListOf<ByteArray>()
        val decryptionPlaintexts = mutableListOf<ByteArray>()

        override fun encrypt(
            identity: CloudHeaderIdentity,
            plaintext: ByteArray,
            key: VaultKey,
        ): ByteArray {
            encryptionPlaintexts += plaintext
            return super.encrypt(identity, plaintext, key).also(encryptedFrames::add)
        }

        override fun decrypt(
            source: InputStream,
            totalLength: Long,
            key: VaultKey,
        ): CloudDecodeResult {
            val result = super.decrypt(source, totalLength, key)
            if (result is CloudDecodeResult.Success) {
                decryptionPlaintexts += result.value.takePlaintext()
                return CloudDecodeResult.Success(
                    app.opentasks.core.data.DecryptedCloudObject(
                        result.value.identity,
                        decryptionPlaintexts.last(),
                    ),
                )
            }
            return result
        }
    }

    private companion object {
        const val MINIMUM_PACKAGE_BYTES = 6
        val PASSPHRASE: CharArray = "correct horse battery".toCharArray()
        const val GENERATION = 7L
        const val PRODUCED_AT = 1_754_000_000_000L
        val TEST_JSON = Json {
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
