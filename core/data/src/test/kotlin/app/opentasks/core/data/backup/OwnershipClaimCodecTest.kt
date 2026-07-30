package app.opentasks.core.data.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.WriterEpoch
import app.opentasks.core.sync.CloudBounds
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OwnershipClaimCodecTest {
    private val crypto = TinkVaultCrypto()
    private val authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto)
    private val ownershipCodec = OwnershipClaimCodec(authenticatedCodec)
    private val contentKey = crypto.createKey()
    private val otherKey = crypto.createKey()
    private val verifiedRoot: VerifiedOwnershipClaim by lazy {
        ownershipCodec.verify(ownershipCodec.encode(activeRoot(), contentKey), contentKey)
    }

    @After
    fun closeKeys() {
        contentKey.close()
        otherKey.close()
    }

    @Test
    fun canonicalIdentifiersRejectNonCanonicalValuesAndRedactTheirValue() {
        assertEquals(LINEAGE_ID, CloudLineageId.parse(LINEAGE_ID).value)
        assertEquals(
            CloudLineageId.parse(LINEAGE_ID),
            CloudLineageId.parse(LINEAGE_ID),
        )
        assertNotEquals(
            CloudLineageId.parse(LINEAGE_ID),
            CloudLineageId.parse(OTHER_LINEAGE_ID),
        )
        assertFalse(CloudLineageId.parse(LINEAGE_ID).toString().contains(LINEAGE_ID))
        assertFalse(
            ProviderObjectId.of(ROOT_PROVIDER_ID).toString().contains(ROOT_PROVIDER_ID),
        )
        assertFalse(Sha256Digest.of(DIGEST_A).toString().contains(DIGEST_A))
        assertEquals(36, CloudDeviceId.new().value.length)
        assertEquals(36, OwnershipClaimId.new().value.length)
        assertEquals(36, PublicationId.new().value.length)

        listOf(
            "00000000-0000-4000-8000-0000000000FF",
            LINEAGE_ID.replace("-", ""),
            " $LINEAGE_ID",
            "",
        ).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                CloudLineageId.parse(candidate)
            }
        }
        listOf("", "a".repeat(201), "provider id").forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                ProviderObjectId.of(candidate)
            }
        }
        listOf(DIGEST_A.uppercase(), DIGEST_A.dropLast(1), "").forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                Sha256Digest.of(candidate)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { WriterEpoch(0) }
        assertEquals(1L, WriterEpoch(1).value)
    }

    @Test
    fun encodeWritesLengthPrefixedPublicHeaderBeforeTheAuthenticatedClaimFrame() {
        val encoded = ownershipCodec.encode(activeRoot(), contentKey)

        val headerLength = ByteBuffer.wrap(encoded, 0, Integer.BYTES).int
        val header = ownershipCodec.readPublicHeader(encoded)
        assertEquals(
            TEST_JSON.encodeToString(OwnershipPublicHeaderV1.serializer(), header),
            encoded.copyOfRange(4, 4 + headerLength).toString(Charsets.UTF_8),
        )
        assertEquals("OPEN_TASKS_OWNERSHIP", header.magic)
        assertEquals(1, header.formatVersion)
        assertEquals(1, header.minimumReaderVersion)
        assertEquals(LINEAGE_ID, header.lineageId)
        assertEquals(ROOT_CLAIM_ID, header.claimId)
        assertEquals(1L, header.writerEpoch)
        assertEquals(OwnershipStateV1.ACTIVE, header.state)
        assertEquals(RemoteObjectRoleV1.OWNERSHIP_ROOT, header.role)
        assertEquals(ROOT_PROVIDER_ID, header.providerFileId)
        assertEquals(SUCCESSOR_PROVIDER_ID, header.nextSuccessorProviderFileId)

        val frame = encoded.copyOfRange(4 + headerLength, encoded.size)
        assertEquals(frame.size.toLong(), header.encryptedFrameLength)
        assertEquals(sha256(frame), header.encryptedFrameSha256)

        val verified = ownershipCodec.verify(encoded, contentKey)
        assertEquals(header, verified.header)
        assertEquals(activeRoot(), verified.claim)
        assertEquals(sha256(encoded), verified.completeSha256.value)
    }

    @Test
    fun publicHeaderAndClaimPlaintextKeepStageOneAndTwoByteBounds() {
        assertEquals(
            CloudBounds.MAX_HEADER_BYTES,
            OwnershipClaimCodec.MAX_PUBLIC_HEADER_BYTES,
        )
        assertEquals(1024 * 1024 - 33, OwnershipClaimCodec.MAX_CLAIM_PLAINTEXT_BYTES)

        val oversizedHeader = ByteArray(64).also { source ->
            ByteBuffer.wrap(source, 0, Integer.BYTES)
                .putInt(CloudBounds.MAX_HEADER_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ownershipCodec.readPublicHeader(oversizedHeader)
        }
        val negativeHeader = ByteArray(64).also { source ->
            ByteBuffer.wrap(source, 0, Integer.BYTES).putInt(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ownershipCodec.readPublicHeader(negativeHeader)
        }
    }

    @Test
    fun declaredFrameLengthsMustMatchTheCompleteFileWithoutOverflow() {
        val encoded = ownershipCodec.encode(activeRoot(), contentKey)
        val header = ownershipCodec.readPublicHeader(encoded)
        val frame = frameOf(encoded)

        listOf(
            header.copy(encryptedFrameLength = header.encryptedFrameLength + 1),
            header.copy(encryptedFrameLength = header.encryptedFrameLength - 1),
            header.copy(encryptedFrameLength = 0),
            header.copy(encryptedFrameLength = Long.MAX_VALUE),
            header.copy(encryptedFrameLength = OwnershipClaimCodec.MAX_CLAIM_FRAME_BYTES + 1),
        ).forEach { tampered ->
            assertThrows(IllegalArgumentException::class.java) {
                ownershipCodec.verify(assemble(tampered, frame), contentKey)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ownershipCodec.verify(encoded.copyOfRange(0, encoded.size - 1), contentKey)
        }
    }

    @Test
    fun corruptedFrameFailsTheDeclaredChecksum() {
        val encoded = ownershipCodec.encode(activeRoot(), contentKey)
        val corrupted = encoded.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            ownershipCodec.verify(corrupted, contentKey)
        }
    }

    @Test
    fun frameEncryptedUnderAnotherKeyOrIdentityIsRejected() {
        val plaintext = TEST_JSON
            .encodeToString(OwnershipClaimV1.serializer(), activeRoot())
            .toByteArray()
        val wrongKeyFrame = authenticatedCodec.encrypt(
            identity(LINEAGE_ID, ROOT_CLAIM_ID),
            plaintext,
            otherKey,
        )
        val swappedIdentityFrame = authenticatedCodec.encrypt(
            identity(LINEAGE_ID, SUCCESSOR_CLAIM_ID),
            plaintext,
            contentKey,
        )
        val swappedLineageFrame = authenticatedCodec.encrypt(
            identity(OTHER_LINEAGE_ID, ROOT_CLAIM_ID),
            plaintext,
            contentKey,
        )
        val header = ownershipCodec.readPublicHeader(
            ownershipCodec.encode(activeRoot(), contentKey),
        )

        listOf(wrongKeyFrame, swappedIdentityFrame, swappedLineageFrame).forEach { frame ->
            val file = assemble(
                header.copy(
                    encryptedFrameLength = frame.size.toLong(),
                    encryptedFrameSha256 = sha256(frame),
                ),
                frame,
            )
            assertThrows(IllegalArgumentException::class.java) {
                ownershipCodec.verify(file, contentKey)
            }
        }
    }

    @Test
    fun unknownDuplicateReorderedAndFutureHeaderFieldsAreRejected() {
        val encoded = ownershipCodec.encode(activeRoot(), contentKey)
        val headerJson = headerJsonOf(encoded)
        val frame = frameOf(encoded)

        listOf(
            headerJson.replace(""""formatVersion":1""", """"formatVersion":1,"extra":true"""),
            headerJson.replace(""""formatVersion":1""", """"formatVersion":1,"formatVersion":1"""),
            headerJson.replace(
                """"magic":"OPEN_TASKS_OWNERSHIP","formatVersion":1""",
                """"formatVersion":1,"magic":"OPEN_TASKS_OWNERSHIP"""",
            ),
            headerJson.replace(""""formatVersion":1""", """"formatVersion":2"""),
            headerJson.replace(""""minimumReaderVersion":1""", """"minimumReaderVersion":2"""),
            headerJson.replace(""""OPEN_TASKS_OWNERSHIP"""", """"OPEN_TASKS_PUBLICATION""""),
        ).forEach { tampered ->
            assertThrows(IllegalArgumentException::class.java) {
                ownershipCodec.verify(assembleJson(tampered, frame), contentKey)
            }
        }
    }

    @Test
    fun unknownFieldsAndFutureVersionsInsideTheAuthenticatedClaimAreRejected() {
        val claimJson = TEST_JSON.encodeToString(OwnershipClaimV1.serializer(), activeRoot())

        listOf(
            claimJson.replace(""""formatVersion":1""", """"formatVersion":1,"extra":true"""),
            claimJson.replace(""""formatVersion":1""", """"formatVersion":1,"formatVersion":1"""),
            claimJson.replace(""""formatVersion":1""", """"formatVersion":2"""),
            claimJson.replace(""""minimumReaderVersion":1""", """"minimumReaderVersion":2"""),
        ).forEach { tampered ->
            assertThrows(IllegalArgumentException::class.java) {
                ownershipCodec.verify(fileForClaimJson(tampered), contentKey)
            }
        }
    }

    @Test
    fun publicHeaderMustRepeatTheAuthenticatedClaimIdentity() {
        val encoded = ownershipCodec.encode(activeRoot(), contentKey)
        val header = ownershipCodec.readPublicHeader(encoded)
        val frame = frameOf(encoded)

        listOf(
            header.copy(claimId = SUCCESSOR_CLAIM_ID),
            header.copy(lineageId = OTHER_LINEAGE_ID),
            header.copy(writerEpoch = 2),
            header.copy(providerFileId = "different-id"),
            header.copy(nextSuccessorProviderFileId = "different-id"),
            header.copy(state = OwnershipStateV1.TERMINATED),
            header.copy(role = RemoteObjectRoleV1.OWNERSHIP_CLAIM),
        ).forEach { tampered ->
            assertThrows(IllegalArgumentException::class.java) {
                ownershipCodec.verify(assemble(tampered, frame), contentKey)
            }
        }
    }

    @Test
    fun rootMustBeEpochOneWithoutPredecessorFields() {
        listOf(
            activeRoot().copy(writerEpoch = 2),
            activeRoot().copy(predecessorProviderFileId = ROOT_PROVIDER_ID),
            activeRoot().copy(predecessorClaimId = ROOT_CLAIM_ID),
            activeRoot().copy(predecessorClaimSha256 = DIGEST_A),
            activeRoot().copy(predecessorReservedSuccessorProviderFileId = ROOT_PROVIDER_ID),
            activeRoot().copy(nextSuccessorProviderFileId = ROOT_PROVIDER_ID),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ownershipCodec.encode(invalid, contentKey)
            }
        }

        val root = verifiedRoot
        assertEquals(RemoteObjectRoleV1.OWNERSHIP_ROOT, root.header.role)
        assertNull(root.claim.predecessorProviderFileId)
        assertNull(root.claim.tombstoneId)
    }

    @Test
    fun activeClaimRequiresEveryActiveField() {
        listOf(
            activeRoot().copy(sourceVaultId = null),
            activeRoot().copy(activeDeviceId = null),
            activeRoot().copy(nextSuccessorProviderFileId = null),
            activeRoot().copy(baselinePublicationProviderFileId = null),
            activeRoot().copy(baselinePublicationId = null),
            activeRoot().copy(baselinePublicationSha256 = null),
            activeRoot().copy(recoveryCredentialGeneration = null),
            activeRoot().copy(recoveryCredentialGeneration = -1),
            activeRoot().copy(tombstoneId = TOMBSTONE_ID),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ownershipCodec.encode(invalid, contentKey)
            }
        }
    }

    @Test
    fun terminalClaimCannotCarryRecoveryOrSuccessorState() {
        val invalid = activeClaim().copy(
            state = OwnershipStateV1.TERMINATED,
            tombstoneId = UUID.randomUUID().toString(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ownershipCodec.encode(invalid, contentKey)
        }
    }

    @Test
    fun terminalClaimRequiresATombstoneIdentityAndNoActiveField() {
        assertThrows(IllegalArgumentException::class.java) {
            ownershipCodec.encode(tombstone().copy(tombstoneId = null), contentKey)
        }
        listOf(
            tombstone().copy(sourceVaultId = VAULT_ID),
            tombstone().copy(activeDeviceId = DEVICE_ID),
            tombstone().copy(nextSuccessorProviderFileId = "another-slot"),
            tombstone().copy(baselinePublicationId = BASELINE_PUBLICATION_ID),
            tombstone().copy(baselinePublicationSha256 = DIGEST_A),
            tombstone().copy(recoveryCredentialGeneration = 1),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ownershipCodec.encode(invalid, contentKey)
            }
        }

        val verified = ownershipCodec.verify(
            ownershipCodec.encode(tombstone(), contentKey),
            contentKey,
        )
        assertEquals(RemoteObjectRoleV1.OWNERSHIP_TOMBSTONE, verified.header.role)
        assertEquals(OwnershipStateV1.TERMINATED, verified.header.state)
        assertNull(verified.header.nextSuccessorProviderFileId)
        assertEquals(TOMBSTONE_ID, verified.claim.tombstoneId)
    }

    @Test
    fun successorMustOccupyPredecessorsExactReservedId() {
        assertThrows(IllegalArgumentException::class.java) {
            ownershipCodec.verifySuccessor(
                predecessor = rootClaim(),
                candidate = successorClaim(providerFileId = "different-id"),
                contentKey = contentKey,
            )
        }
    }

    @Test
    fun successorMustIncrementTheWriterEpochByExactlyOne() {
        listOf(3L, 4L, Long.MAX_VALUE).forEach { epoch ->
            assertThrows(IllegalArgumentException::class.java) {
                ownershipCodec.verifySuccessor(
                    predecessor = rootClaim(),
                    candidate = successorClaim(writerEpoch = epoch),
                    contentKey = contentKey,
                )
            }
        }
    }

    @Test
    fun successorMustNameThePredecessorDigestLineageAndIdentity() {
        listOf(
            successorClaim(predecessorClaimSha256 = DIGEST_A),
            successorClaim(predecessorClaimId = TOMBSTONE_CLAIM_ID),
            successorClaim(predecessorProviderFileId = "unknown-predecessor"),
            successorClaim(lineageId = OTHER_LINEAGE_ID),
        ).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                ownershipCodec.verifySuccessor(
                    predecessor = rootClaim(),
                    candidate = candidate,
                    contentKey = contentKey,
                )
            }
        }
    }

    @Test
    fun terminatedPredecessorAcceptsNoSuccessor() {
        val terminated = ownershipCodec.verify(
            ownershipCodec.encode(tombstone(), contentKey),
            contentKey,
        )

        assertThrows(IllegalArgumentException::class.java) {
            ownershipCodec.verifySuccessor(
                predecessor = terminated,
                candidate = successorClaim(),
                contentKey = contentKey,
            )
        }
    }

    @Test
    fun successorAndTombstoneOccupyingTheReservedSlotAreAccepted() {
        val successor = ownershipCodec.verifySuccessor(
            predecessor = rootClaim(),
            candidate = successorClaim(),
            contentKey = contentKey,
        )

        assertEquals(2L, successor.claim.writerEpoch)
        assertEquals(SUCCESSOR_PROVIDER_ID, successor.claim.providerFileId)
        assertEquals(RemoteObjectRoleV1.OWNERSHIP_CLAIM, successor.header.role)
        assertEquals(rootClaim().completeSha256.value, successor.claim.predecessorClaimSha256)

        val terminal = ownershipCodec.verifySuccessor(
            predecessor = successor,
            candidate = ownershipCodec.encode(
                tombstone(
                    predecessorProviderFileId = successor.claim.providerFileId,
                    predecessorClaimId = successor.claim.claimId,
                    predecessorClaimSha256 = successor.completeSha256.value,
                    providerFileId = successor.claim.nextSuccessorProviderFileId!!,
                    writerEpoch = 3,
                ),
                contentKey,
            ),
            contentKey = contentKey,
        )

        assertEquals(OwnershipStateV1.TERMINATED, terminal.claim.state)
        assertEquals(64, terminal.completeSha256.value.length)
    }

    private fun rootClaim(): VerifiedOwnershipClaim = verifiedRoot

    private fun activeRoot(): OwnershipClaimV1 = OwnershipClaimV1(
        lineageId = LINEAGE_ID,
        writerEpoch = 1,
        state = OwnershipStateV1.ACTIVE,
        predecessorProviderFileId = null,
        predecessorClaimId = null,
        predecessorClaimSha256 = null,
        providerFileId = ROOT_PROVIDER_ID,
        claimId = ROOT_CLAIM_ID,
        predecessorReservedSuccessorProviderFileId = null,
        sourceVaultId = VAULT_ID,
        activeDeviceId = DEVICE_ID,
        nextSuccessorProviderFileId = SUCCESSOR_PROVIDER_ID,
        baselinePublicationProviderFileId = BASELINE_PROVIDER_ID,
        baselinePublicationId = BASELINE_PUBLICATION_ID,
        baselinePublicationSha256 = DIGEST_A,
        recoveryCredentialGeneration = 1,
        creationOperationId = ROOT_OPERATION_ID,
        tombstoneId = null,
    )

    private fun activeClaim(): OwnershipClaimV1 = OwnershipClaimV1(
        lineageId = LINEAGE_ID,
        writerEpoch = 2,
        state = OwnershipStateV1.ACTIVE,
        predecessorProviderFileId = ROOT_PROVIDER_ID,
        predecessorClaimId = ROOT_CLAIM_ID,
        predecessorClaimSha256 = rootClaim().completeSha256.value,
        providerFileId = SUCCESSOR_PROVIDER_ID,
        claimId = SUCCESSOR_CLAIM_ID,
        predecessorReservedSuccessorProviderFileId = SUCCESSOR_PROVIDER_ID,
        sourceVaultId = VAULT_ID,
        activeDeviceId = OTHER_DEVICE_ID,
        nextSuccessorProviderFileId = TOMBSTONE_PROVIDER_ID,
        baselinePublicationProviderFileId = NEXT_BASELINE_PROVIDER_ID,
        baselinePublicationId = NEXT_PUBLICATION_ID,
        baselinePublicationSha256 = DIGEST_B,
        recoveryCredentialGeneration = 1,
        creationOperationId = SUCCESSOR_OPERATION_ID,
        tombstoneId = null,
    )

    private fun successorClaim(
        providerFileId: String = SUCCESSOR_PROVIDER_ID,
        writerEpoch: Long = 2,
        lineageId: String = LINEAGE_ID,
        predecessorProviderFileId: String = ROOT_PROVIDER_ID,
        predecessorClaimId: String = ROOT_CLAIM_ID,
        predecessorClaimSha256: String = rootClaim().completeSha256.value,
    ): ByteArray = ownershipCodec.encode(
        activeClaim().copy(
            providerFileId = providerFileId,
            predecessorReservedSuccessorProviderFileId = providerFileId,
            writerEpoch = writerEpoch,
            lineageId = lineageId,
            predecessorProviderFileId = predecessorProviderFileId,
            predecessorClaimId = predecessorClaimId,
            predecessorClaimSha256 = predecessorClaimSha256,
        ),
        contentKey,
    )

    private fun tombstone(
        predecessorProviderFileId: String = ROOT_PROVIDER_ID,
        predecessorClaimId: String = ROOT_CLAIM_ID,
        predecessorClaimSha256: String = rootClaim().completeSha256.value,
        providerFileId: String = SUCCESSOR_PROVIDER_ID,
        writerEpoch: Long = 2,
    ): OwnershipClaimV1 = OwnershipClaimV1(
        lineageId = LINEAGE_ID,
        writerEpoch = writerEpoch,
        state = OwnershipStateV1.TERMINATED,
        predecessorProviderFileId = predecessorProviderFileId,
        predecessorClaimId = predecessorClaimId,
        predecessorClaimSha256 = predecessorClaimSha256,
        providerFileId = providerFileId,
        claimId = TOMBSTONE_CLAIM_ID,
        predecessorReservedSuccessorProviderFileId = providerFileId,
        sourceVaultId = null,
        activeDeviceId = null,
        nextSuccessorProviderFileId = null,
        baselinePublicationProviderFileId = null,
        baselinePublicationId = null,
        baselinePublicationSha256 = null,
        recoveryCredentialGeneration = null,
        creationOperationId = TOMBSTONE_OPERATION_ID,
        tombstoneId = TOMBSTONE_ID,
    )

    private fun identity(
        lineageId: String,
        claimId: String,
    ): CloudHeaderIdentity = CloudHeaderIdentity(
        family = CloudObjectFamily.MANIFEST,
        schemaVersion = 1,
        cryptoVersion = 1,
        minimumReaderVersion = 1,
        vaultId = lineageId,
        objectId = claimId,
    )

    private fun fileForClaimJson(claimJson: String): ByteArray {
        val plaintext = claimJson.toByteArray()
        val frame = authenticatedCodec.encrypt(
            identity(LINEAGE_ID, ROOT_CLAIM_ID),
            plaintext,
            contentKey,
        )
        val header = ownershipCodec
            .readPublicHeader(ownershipCodec.encode(activeRoot(), contentKey))
            .copy(
                encryptedFrameLength = frame.size.toLong(),
                encryptedFrameSha256 = sha256(frame),
            )
        return assemble(header, frame)
    }

    private fun assemble(
        header: OwnershipPublicHeaderV1,
        frame: ByteArray,
    ): ByteArray = assembleJson(
        TEST_JSON.encodeToString(OwnershipPublicHeaderV1.serializer(), header),
        frame,
    )

    private fun assembleJson(
        headerJson: String,
        frame: ByteArray,
    ): ByteArray {
        val headerBytes = headerJson.toByteArray()
        val prefix = ByteArray(Integer.BYTES)
        ByteBuffer.wrap(prefix).putInt(headerBytes.size)
        return prefix + headerBytes + frame
    }

    private fun headerJsonOf(file: ByteArray): String {
        val headerLength = ByteBuffer.wrap(file, 0, Integer.BYTES).int
        return file.copyOfRange(4, 4 + headerLength).toString(Charsets.UTF_8)
    }

    private fun frameOf(file: ByteArray): ByteArray {
        val headerLength = ByteBuffer.wrap(file, 0, Integer.BYTES).int
        return file.copyOfRange(4 + headerLength, file.size)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private companion object {
        const val LINEAGE_ID = "00000000-0000-4000-8000-000000000001"
        const val OTHER_LINEAGE_ID = "00000000-0000-4000-8000-0000000000ff"
        const val ROOT_CLAIM_ID = "00000000-0000-4000-8000-000000000002"
        const val SUCCESSOR_CLAIM_ID = "00000000-0000-4000-8000-000000000003"
        const val TOMBSTONE_CLAIM_ID = "00000000-0000-4000-8000-000000000004"
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000005"
        const val OTHER_DEVICE_ID = "00000000-0000-4000-8000-000000000006"
        const val BASELINE_PUBLICATION_ID = "00000000-0000-4000-8000-000000000007"
        const val NEXT_PUBLICATION_ID = "00000000-0000-4000-8000-000000000008"
        const val VAULT_ID = "00000000-0000-4000-8000-000000000009"
        const val ROOT_OPERATION_ID = "00000000-0000-4000-8000-00000000000a"
        const val SUCCESSOR_OPERATION_ID = "00000000-0000-4000-8000-00000000000b"
        const val TOMBSTONE_OPERATION_ID = "00000000-0000-4000-8000-00000000000c"
        const val TOMBSTONE_ID = "00000000-0000-4000-8000-00000000000d"
        const val ROOT_PROVIDER_ID = "provider-root"
        const val SUCCESSOR_PROVIDER_ID = "provider-successor"
        const val TOMBSTONE_PROVIDER_ID = "provider-tombstone"
        const val BASELINE_PROVIDER_ID = "provider-baseline"
        const val NEXT_BASELINE_PROVIDER_ID = "provider-next-baseline"
        const val DIGEST_A =
            "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        const val DIGEST_B =
            "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
        val TEST_JSON = Json {
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
