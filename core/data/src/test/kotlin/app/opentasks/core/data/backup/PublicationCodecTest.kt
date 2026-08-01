package app.opentasks.core.data.backup

import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.model.OwnershipStateV1
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.sync.CloudBounds
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationCodecTest {
    private val crypto = TinkVaultCrypto()
    private val authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto)
    private val publicationCodec = PublicationCodec(authenticatedCodec)
    private val ownershipCodec = OwnershipClaimCodec(authenticatedCodec)
    private val contentKey = crypto.createKey()
    private val envelope = fixtureEnvelope()
    private val baseline: VerifiedPublication by lazy {
        publicationCodec.verify(
            publicationCodec.encode(baselineManifest(), envelope, contentKey),
            contentKey,
        )
    }
    private val ownership: VerifiedOwnershipClaim by lazy {
        ownershipCodec.verify(
            ownershipCodec.encode(
                activeRoot(baselinePublicationSha256 = baseline.completeSha256.value),
                contentKey,
            ),
            contentKey,
        )
    }
    private val current: VerifiedPublication by lazy {
        publicationCodec.verify(
            publicationCodec.encode(
                manifest(sequence = 1, generation = 42),
                envelope,
                contentKey,
            ),
            contentKey,
        )
    }

    @After
    fun releaseResources() {
        contentKey.close()
        envelope.kdf.salt.fill(0)
        envelope.nonce.fill(0)
        envelope.wrappedKeyset.fill(0)
    }

    @Test
    fun encodeWritesLengthPrefixedBootstrapBeforeTheAuthenticatedManifestFrame() {
        val encoded = publicationCodec.encode(
            manifest(sequence = 1, generation = 42),
            envelope,
            contentKey,
        )

        val bootstrapLength = ByteBuffer.wrap(encoded, 0, Integer.BYTES).int
        val bootstrap = publicationCodec.readBootstrap(encoded)
        assertEquals(
            TEST_JSON.encodeToString(PublicationBootstrapV1.serializer(), bootstrap),
            encoded.copyOfRange(4, 4 + bootstrapLength).toString(Charsets.UTF_8),
        )
        assertEquals("OPEN_TASKS_PUBLICATION", bootstrap.magic)
        assertEquals(1, bootstrap.formatVersion)
        assertEquals(1, bootstrap.minimumReaderVersion)
        assertEquals(LINEAGE_ID, bootstrap.lineageId)
        assertEquals(1L, bootstrap.writerEpoch)
        assertNull(bootstrap.plannedClaimProviderFileId)
        assertEquals(RECOVERY_CREDENTIAL_GENERATION, bootstrap.recoveryCredentialGeneration)
        assertEquals("ARGON2ID", bootstrap.recoveryEnvelope.kdfAlgorithm)

        val frame = encoded.copyOfRange(4 + bootstrapLength, encoded.size)
        assertEquals(frame.size.toLong(), bootstrap.encryptedFrameLength)
        assertEquals(sha256(frame), bootstrap.encryptedFrameSha256)

        val verified = publicationCodec.verify(encoded, contentKey)
        assertEquals(bootstrap, verified.bootstrap)
        assertEquals(manifest(sequence = 1, generation = 42), verified.manifest)
        assertEquals(sha256(encoded), verified.completeSha256.value)
    }

    @Test
    fun baselineBindsPlannedClaimWithoutDigestCycle() {
        val encoded = publicationCodec.encode(
            baselineManifest(plannedClaimProviderFileId = "claim-provider-a"),
            envelope,
            contentKey,
        )
        val verified = publicationCodec.verify(encoded, contentKey)

        assertEquals(0L, verified.manifest.publicationSequence)
        assertEquals("claim-provider-a", verified.manifest.plannedClaimProviderFileId)
        assertNull(verified.manifest.ownershipClaimSha256)
    }

    @Test
    fun passphraseRotationMayAdvanceSequenceAtSameGeneration() {
        publicationCodec.requireSuccessor(
            previous = manifest(sequence = 7, generation = 42),
            current = manifest(sequence = 8, generation = 42).copy(
                recoveryCredentialGeneration = RECOVERY_CREDENTIAL_GENERATION + 1,
            ),
        )
    }

    @Test
    fun unchangedGenerationRequiresANewerRecoveryCredential() {
        assertThrows(IllegalArgumentException::class.java) {
            publicationCodec.requireSuccessor(
                previous = manifest(sequence = 7, generation = 42),
                current = manifest(sequence = 8, generation = 42),
            )
        }

        publicationCodec.requireSuccessor(
            previous = manifest(sequence = 7, generation = 42),
            current = manifest(sequence = 8, generation = 43),
        )
    }

    @Test
    fun bootstrapAndManifestBoundsMatchStageOneAndTwoLimits() {
        assertEquals(CloudBounds.MAX_HEADER_BYTES, PublicationCodec.MAX_BOOTSTRAP_BYTES)
        assertEquals(1024 * 1024 - 33, PublicationCodec.MAX_MANIFEST_PLAINTEXT_BYTES)
        assertEquals(16 * 1024, RecoveryEnvelopeCodec.MAX_CANONICAL_BYTES)
        assertEquals(
            CloudBounds.MAX_MANIFEST_INVENTORY_ENTRIES,
            PublicationCodec.MAX_INVENTORY_ENTRIES,
        )

        val oversized = ByteArray(64).also { source ->
            ByteBuffer.wrap(source, 0, Integer.BYTES)
                .putInt(CloudBounds.MAX_HEADER_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            publicationCodec.readBootstrap(oversized)
        }
        val encoded = publicationCodec.encode(
            manifest(sequence = 1, generation = 42),
            envelope,
            contentKey,
        )
        val bootstrap = publicationCodec.readBootstrap(encoded)
        listOf(
            bootstrap.copy(encryptedFrameLength = Long.MAX_VALUE),
            bootstrap.copy(encryptedFrameLength = PublicationCodec.MAX_FRAME_BYTES + 1),
            bootstrap.copy(encryptedFrameLength = 0),
        ).forEach { tampered ->
            assertThrows(IllegalArgumentException::class.java) {
                publicationCodec.readBootstrap(assemble(tampered, frameOf(encoded)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            publicationCodec.verify(encoded.copyOfRange(0, encoded.size - 1), contentKey)
        }
    }

    @Test
    fun weakenedRecoveryKdfIsRejectedBeforeAnyDerivation() {
        val encoded = publicationCodec.encode(
            manifest(sequence = 1, generation = 42),
            envelope,
            contentKey,
        )
        val bootstrapJson = bootstrapJsonOf(encoded)
        val frame = frameOf(encoded)

        listOf(
            """"memoryKiB":65536""" to """"memoryKiB":8""",
            """"iterations":3""" to """"iterations":1""",
            """"parallelism":1""" to """"parallelism":64""",
            """"kdfAlgorithm":"ARGON2ID"""" to """"kdfAlgorithm":"PBKDF2"""",
        ).forEach { (from, to) ->
            val tampered = assembleJson(bootstrapJson.replace(from, to), frame)
            assertThrows(IllegalArgumentException::class.java) {
                publicationCodec.readBootstrap(tampered)
            }
            assertThrows(IllegalArgumentException::class.java) {
                publicationCodec.verify(tampered, contentKey)
            }
        }
    }

    @Test
    fun manifestMustCarryTheExactBootstrapDigest() {
        val draft = manifest(sequence = 1, generation = 42)
        assertThrows(IllegalArgumentException::class.java) {
            publicationCodec.encode(
                draft.copy(bootstrapSha256 = ZERO_SHA256),
                envelope,
                contentKey,
            )
        }

        val encoded = publicationCodec.encode(draft, envelope, contentKey)
        val tampered = assembleJson(
            bootstrapJsonOf(encoded).replace(
                """"saltBase64":"AAECAwQFBgcICQoLDA0ODw"""",
                """"saltBase64":"DwECAwQFBgcICQoLDA0ODw"""",
            ),
            frameOf(encoded),
        )
        assertThrows(IllegalArgumentException::class.java) {
            publicationCodec.verify(tampered, contentKey)
        }
    }

    @Test
    fun bootstrapMustAgreeWithTheAuthenticatedManifest() {
        val encoded = publicationCodec.encode(
            manifest(sequence = 1, generation = 42),
            envelope,
            contentKey,
        )
        val bootstrap = publicationCodec.readBootstrap(encoded)
        val frame = frameOf(encoded)

        listOf(
            bootstrap.copy(lineageId = OTHER_LINEAGE_ID),
            bootstrap.copy(writerEpoch = 2),
            bootstrap.copy(recoveryCredentialGeneration = RECOVERY_CREDENTIAL_GENERATION + 1),
            bootstrap.copy(plannedClaimProviderFileId = "claim-provider-a"),
        ).forEach { tampered ->
            assertThrows(IllegalArgumentException::class.java) {
                publicationCodec.verify(assemble(tampered, frame), contentKey)
            }
        }
    }

    @Test
    fun unknownDuplicateReorderedAndFutureFieldsAreRejected() {
        val encoded = publicationCodec.encode(
            manifest(sequence = 1, generation = 42),
            envelope,
            contentKey,
        )
        val bootstrapJson = bootstrapJsonOf(encoded)
        val frame = frameOf(encoded)

        listOf(
            bootstrapJson.replace(""""formatVersion":1""", """"formatVersion":1,"extra":true"""),
            bootstrapJson.replace(
                """"formatVersion":1""",
                """"formatVersion":1,"formatVersion":1""",
            ),
            bootstrapJson.replace(
                """"magic":"OPEN_TASKS_PUBLICATION","formatVersion":1""",
                """"formatVersion":1,"magic":"OPEN_TASKS_PUBLICATION"""",
            ),
            bootstrapJson.replace(""""formatVersion":1""", """"formatVersion":2"""),
            bootstrapJson.replace(""""minimumReaderVersion":1""", """"minimumReaderVersion":2"""),
            bootstrapJson.replace(
                """"magic":"OPEN_TASKS_PUBLICATION"""",
                """"magic":"OPEN_TASKS_OWNERSHIP"""",
            ),
        ).forEach { tampered ->
            assertThrows(IllegalArgumentException::class.java) {
                publicationCodec.verify(assembleJson(tampered, frame), contentKey)
            }
        }

        val manifestJson = TEST_JSON.encodeToString(
            PublicationManifestV1.serializer(),
            manifest(sequence = 1, generation = 42),
        )
        listOf(
            manifestJson.replace(""""formatVersion":1""", """"formatVersion":1,"extra":true"""),
            manifestJson.replace(""""formatVersion":1""", """"formatVersion":2"""),
            manifestJson.replace(""""minimumReaderVersion":1""", """"minimumReaderVersion":2"""),
        ).forEach { tampered ->
            assertThrows(IllegalArgumentException::class.java) {
                publicationCodec.verify(fileForManifestJson(tampered), contentKey)
            }
        }
    }

    @Test
    fun frameEncryptedUnderAnotherIdentityOrKeyIsRejected() {
        val otherKey = crypto.createKey()
        val plaintext = TEST_JSON.encodeToString(
            PublicationManifestV1.serializer(),
            manifest(sequence = 1, generation = 42),
        ).toByteArray()
        val frames = try {
            listOf(
                authenticatedCodec.encrypt(
                    identity(LINEAGE_ID, publicationIdFor(1)),
                    plaintext,
                    otherKey,
                ),
                authenticatedCodec.encrypt(
                    identity(LINEAGE_ID, publicationIdFor(2)),
                    plaintext,
                    contentKey,
                ),
                authenticatedCodec.encrypt(
                    identity(OTHER_LINEAGE_ID, publicationIdFor(1)),
                    plaintext,
                    contentKey,
                ),
            )
        } finally {
            otherKey.close()
        }
        val bootstrap = publicationCodec.readBootstrap(
            publicationCodec.encode(manifest(sequence = 1, generation = 42), envelope, contentKey),
        )

        frames.forEach { frame ->
            val file = assemble(
                bootstrap.copy(
                    encryptedFrameLength = frame.size.toLong(),
                    encryptedFrameSha256 = sha256(frame),
                ),
                frame,
            )
            assertThrows(IllegalArgumentException::class.java) {
                publicationCodec.verify(file, contentKey)
            }
        }
    }

    @Test
    fun baselineAndNormalPublicationFieldsAreMutuallyExclusive() {
        listOf(
            baselineManifest().copy(publicationSequence = 1),
            baselineManifest().copy(ownershipClaimProviderFileId = ROOT_PROVIDER_ID),
            baselineManifest().copy(ownershipClaimId = ROOT_CLAIM_ID),
            baselineManifest().copy(ownershipClaimSha256 = DIGEST_A),
            baselineManifest().copy(plannedClaimProviderFileId = null),
            baselineManifest().copy(plannedClaimId = null),
            baselineManifest().copy(predecessorPublicationId = publicationIdFor(0)),
            baselineManifest().copy(predecessorClaimProviderFileId = ROOT_PROVIDER_ID),
            manifest(sequence = 1, generation = 42).copy(baseline = true),
            manifest(sequence = 1, generation = 42).copy(publicationSequence = 0),
            manifest(sequence = 1, generation = 42).copy(plannedClaimId = ROOT_CLAIM_ID),
            manifest(sequence = 1, generation = 42).copy(ownershipClaimSha256 = null),
            manifest(sequence = 1, generation = 42).copy(predecessorPublicationSha256 = null),
            manifest(sequence = 1, generation = 42).copy(predecessorClaimId = ROOT_CLAIM_ID),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                publicationCodec.encode(invalid, envelope, contentKey)
            }
        }
    }

    @Test
    fun inventoryMustBeUniqueSortedAndBounded() {
        val valid = manifest(sequence = 1, generation = 42)
        val duplicate = valid.inventory[0].copy()
        listOf(
            valid.copy(inventory = valid.inventory.reversed()),
            valid.copy(inventory = valid.inventory + duplicate),
            valid.copy(
                inventory = valid.inventory +
                    duplicate.copy(logicalObjectId = "zzz-duplicate-provider"),
            ),
            valid.copy(inventory = emptyList()),
            valid.copy(
                inventory = valid.inventory.map { it.copy(frameSha256 = DIGEST_A.uppercase()) },
            ),
            valid.copy(inventory = valid.inventory.map { it.copy(frameLength = 0) }),
            valid.copy(
                inventory = valid.inventory.map {
                    it.copy(role = RemoteObjectRoleV1.PUBLICATION)
                },
            ),
            valid.copy(
                inventory = valid.inventory.map {
                    it.copy(firstGeneration = it.lastGeneration + 1)
                },
            ),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                publicationCodec.encode(invalid, envelope, contentKey)
            }
        }
    }

    @Test
    fun currentAndFallbackBasesMustBeDistinctSnapshotsInsideTheInventory() {
        val valid = manifest(sequence = 1, generation = 42)
        listOf(
            valid.copy(fallbackBaseObjectId = valid.currentBaseObjectId),
            valid.copy(currentBaseObjectId = "missing-base"),
            valid.copy(fallbackBaseObjectId = "missing-base"),
            valid.copy(currentBaseObjectId = SEGMENT_ONE_ID),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                publicationCodec.encode(invalid, envelope, contentKey)
            }
        }
    }

    @Test
    fun segmentsMustCoverEveryGenerationAfterEitherBase() {
        val valid = manifest(sequence = 1, generation = 42)
        val withoutFirstSegment = valid.copy(
            inventory = valid.inventory.filterNot { it.logicalObjectId == SEGMENT_ONE_ID },
        )
        val withoutLastSegment = valid.copy(
            inventory = valid.inventory.filterNot { it.logicalObjectId == SEGMENT_TWO_ID },
        )

        listOf(withoutFirstSegment, withoutLastSegment).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                publicationCodec.encode(invalid, envelope, contentKey)
            }
        }
        publicationCodec.encode(valid, envelope, contentKey)
    }

    @Test
    fun sequenceMustIncreaseByExactlyOneWithoutForkOrGap() {
        val previous = manifest(sequence = 7, generation = 42)
        listOf(
            manifest(sequence = 7, generation = 42),
            manifest(sequence = 9, generation = 42),
            manifest(sequence = 6, generation = 42),
            manifest(sequence = 8, generation = 42).copy(
                predecessorPublicationId = publicationIdFor(3),
            ),
            manifest(sequence = 8, generation = 42).copy(
                predecessorPublicationProviderFileId = "provider-publication-3",
            ),
            manifest(sequence = 8, generation = 41),
            manifest(sequence = 8, generation = 42).copy(activeDeviceId = OTHER_DEVICE_ID),
            manifest(sequence = 8, generation = 42).copy(writerEpoch = 2),
            manifest(sequence = 8, generation = 42).copy(lineageId = OTHER_LINEAGE_ID),
        ).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                publicationCodec.requireSuccessor(previous = previous, current = candidate)
            }
        }
    }

    @Test
    fun retainedPairRequiresTheExactPredecessorDigestAndOwnershipBinding() {
        publicationCodec.requireRetainedPair(
            current = current,
            previous = baseline,
            ownership = ownership,
        )
        publicationCodec.requireRetainedPair(
            current = baseline,
            previous = null,
            ownership = ownership,
        )

        val forgedDigest = publicationCodec.verify(
            publicationCodec.encode(
                manifest(sequence = 1, generation = 42).copy(
                    predecessorPublicationSha256 = DIGEST_A,
                ),
                envelope,
                contentKey,
            ),
            contentKey,
        )
        assertThrows(IllegalArgumentException::class.java) {
            publicationCodec.requireRetainedPair(
                current = forgedDigest,
                previous = baseline,
                ownership = ownership,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            publicationCodec.requireRetainedPair(
                current = current,
                previous = null,
                ownership = ownership,
            )
        }
    }

    @Test
    fun retainedPairMustBindTheOwnershipClaimEpochAndDevice() {
        val otherOwnership = ownershipCodec.verify(
            ownershipCodec.encode(
                activeRoot(baselinePublicationSha256 = baseline.completeSha256.value).copy(
                    activeDeviceId = OTHER_DEVICE_ID,
                ),
                contentKey,
            ),
            contentKey,
        )
        assertThrows(IllegalArgumentException::class.java) {
            publicationCodec.requireRetainedPair(
                current = current,
                previous = baseline,
                ownership = otherOwnership,
            )
        }

        val unboundBaselineOwnership = ownershipCodec.verify(
            ownershipCodec.encode(
                activeRoot(baselinePublicationSha256 = DIGEST_A),
                contentKey,
            ),
            contentKey,
        )
        assertThrows(IllegalArgumentException::class.java) {
            publicationCodec.requireRetainedPair(
                current = baseline,
                previous = null,
                ownership = unboundBaselineOwnership,
            )
        }

        val terminated = ownershipCodec.verify(
            ownershipCodec.encode(tombstone(), contentKey),
            contentKey,
        )
        assertThrows(IllegalArgumentException::class.java) {
            publicationCodec.requireRetainedPair(
                current = current,
                previous = baseline,
                ownership = terminated,
            )
        }
    }

    @Test
    fun encodeClearsItsOwnedManifestPlaintext() {
        val inspecting = InspectingAuthenticatedCodec(authenticatedCodec)
        val inspectingCodec = PublicationCodec(inspecting)

        val encoded = inspectingCodec.encode(
            manifest(sequence = 1, generation = 42),
            envelope,
            contentKey,
        )

        assertEquals(1, inspecting.encryptionPlaintexts.size)
        assertTrue(inspecting.encryptionPlaintexts.single().all { it == 0.toByte() })
        assertEquals(
            listOf(CloudObjectFamily.MANIFEST to publicationIdFor(1)),
            inspecting.encryptedIdentities.map { it.family to it.objectId },
        )
        encoded.fill(0)
    }

    private fun baselineManifest(
        plannedClaimProviderFileId: String = ROOT_PROVIDER_ID,
    ): PublicationManifestV1 = draft(
        PublicationManifestV1(
            bootstrapSha256 = ZERO_SHA256,
            lineageId = LINEAGE_ID,
            sourceVaultId = VAULT_ID,
            writerEpoch = 1,
            activeDeviceId = DEVICE_ID,
            publicationProviderFileId = "provider-publication-0",
            publicationId = publicationIdFor(0),
            publicationSequence = 0,
            predecessorPublicationProviderFileId = null,
            predecessorPublicationId = null,
            predecessorPublicationSha256 = null,
            baseline = true,
            plannedClaimProviderFileId = plannedClaimProviderFileId,
            plannedClaimId = ROOT_CLAIM_ID,
            predecessorClaimProviderFileId = null,
            predecessorClaimId = null,
            predecessorClaimSha256 = null,
            ownershipClaimProviderFileId = null,
            ownershipClaimId = null,
            ownershipClaimSha256 = null,
            localGeneration = 40,
            publicationOperationId = OPERATION_ID,
            currentBaseObjectId = CURRENT_BASE_ID,
            fallbackBaseObjectId = FALLBACK_BASE_ID,
            inventory = listOf(
                inventoryItem(CURRENT_BASE_ID, RemoteObjectRoleV1.SNAPSHOT, 40, 40),
                inventoryItem(FALLBACK_BASE_ID, RemoteObjectRoleV1.SNAPSHOT, 40, 40),
            ),
            recoveryCredentialGeneration = RECOVERY_CREDENTIAL_GENERATION,
        ),
    )

    private fun manifest(
        sequence: Long,
        generation: Long,
    ): PublicationManifestV1 = draft(
        PublicationManifestV1(
            bootstrapSha256 = ZERO_SHA256,
            lineageId = LINEAGE_ID,
            sourceVaultId = VAULT_ID,
            writerEpoch = 1,
            activeDeviceId = DEVICE_ID,
            publicationProviderFileId = "provider-publication-$sequence",
            publicationId = publicationIdFor(sequence),
            publicationSequence = sequence,
            predecessorPublicationProviderFileId = "provider-publication-${sequence - 1}",
            predecessorPublicationId = publicationIdFor(sequence - 1),
            predecessorPublicationSha256 = if (sequence == 1L) {
                baseline.completeSha256.value
            } else {
                DIGEST_B
            },
            baseline = false,
            plannedClaimProviderFileId = null,
            plannedClaimId = null,
            predecessorClaimProviderFileId = null,
            predecessorClaimId = null,
            predecessorClaimSha256 = null,
            ownershipClaimProviderFileId = ROOT_PROVIDER_ID,
            ownershipClaimId = ROOT_CLAIM_ID,
            ownershipClaimSha256 = ownership.completeSha256.value,
            localGeneration = generation,
            publicationOperationId = OPERATION_ID,
            currentBaseObjectId = CURRENT_BASE_ID,
            fallbackBaseObjectId = FALLBACK_BASE_ID,
            inventory = listOf(
                inventoryItem(CURRENT_BASE_ID, RemoteObjectRoleV1.SNAPSHOT, 40, 40),
                inventoryItem(FALLBACK_BASE_ID, RemoteObjectRoleV1.SNAPSHOT, 40, 40),
            ) + (BASE_GENERATION + 1..generation).map { covered ->
                inventoryItem(
                    logicalObjectId = "segment-$covered",
                    role = RemoteObjectRoleV1.SEGMENT,
                    firstGeneration = covered,
                    lastGeneration = covered,
                )
            },
            recoveryCredentialGeneration = RECOVERY_CREDENTIAL_GENERATION,
        ),
    )

    private fun draft(manifest: PublicationManifestV1): PublicationManifestV1 =
        manifest.copy(
            bootstrapSha256 = publicationCodec.bootstrapSha256(manifest, envelope),
        )

    private fun inventoryItem(
        logicalObjectId: String,
        role: RemoteObjectRoleV1,
        firstGeneration: Long,
        lastGeneration: Long,
    ): RemoteInventoryItemV1 = RemoteInventoryItemV1(
        logicalObjectId = logicalObjectId,
        providerFileId = "provider-$logicalObjectId",
        role = role,
        firstGeneration = firstGeneration,
        lastGeneration = lastGeneration,
        frameLength = 4_096,
        frameSha256 = DIGEST_A,
    )

    private fun activeRoot(baselinePublicationSha256: String): OwnershipClaimV1 =
        OwnershipClaimV1(
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
            nextSuccessorProviderFileId = "provider-successor",
            baselinePublicationProviderFileId = "provider-publication-0",
            baselinePublicationId = publicationIdFor(0),
            baselinePublicationSha256 = baselinePublicationSha256,
            recoveryCredentialGeneration = RECOVERY_CREDENTIAL_GENERATION,
            creationOperationId = OPERATION_ID,
            tombstoneId = null,
        )

    private fun tombstone(): OwnershipClaimV1 = OwnershipClaimV1(
        lineageId = LINEAGE_ID,
        writerEpoch = 2,
        state = OwnershipStateV1.TERMINATED,
        predecessorProviderFileId = ROOT_PROVIDER_ID,
        predecessorClaimId = ROOT_CLAIM_ID,
        predecessorClaimSha256 = ownership.completeSha256.value,
        providerFileId = "provider-successor",
        claimId = TOMBSTONE_CLAIM_ID,
        predecessorReservedSuccessorProviderFileId = "provider-successor",
        sourceVaultId = null,
        activeDeviceId = null,
        nextSuccessorProviderFileId = null,
        baselinePublicationProviderFileId = null,
        baselinePublicationId = null,
        baselinePublicationSha256 = null,
        recoveryCredentialGeneration = null,
        creationOperationId = OPERATION_ID,
        tombstoneId = TOMBSTONE_ID,
    )

    private fun identity(
        lineageId: String,
        publicationId: String,
    ): CloudHeaderIdentity = CloudHeaderIdentity(
        family = CloudObjectFamily.MANIFEST,
        schemaVersion = 1,
        cryptoVersion = 1,
        minimumReaderVersion = 1,
        vaultId = lineageId,
        objectId = publicationId,
    )

    private fun fileForManifestJson(manifestJson: String): ByteArray {
        val plaintext = manifestJson.toByteArray()
        val frame = authenticatedCodec.encrypt(
            identity(LINEAGE_ID, publicationIdFor(1)),
            plaintext,
            contentKey,
        )
        val bootstrap = publicationCodec
            .readBootstrap(
                publicationCodec.encode(
                    manifest(sequence = 1, generation = 42),
                    envelope,
                    contentKey,
                ),
            )
            .copy(
                encryptedFrameLength = frame.size.toLong(),
                encryptedFrameSha256 = sha256(frame),
            )
        return assemble(bootstrap, frame)
    }

    private fun assemble(
        bootstrap: PublicationBootstrapV1,
        frame: ByteArray,
    ): ByteArray = assembleJson(
        TEST_JSON.encodeToString(PublicationBootstrapV1.serializer(), bootstrap),
        frame,
    )

    private fun assembleJson(
        bootstrapJson: String,
        frame: ByteArray,
    ): ByteArray {
        val bootstrapBytes = bootstrapJson.toByteArray()
        val prefix = ByteArray(Integer.BYTES)
        ByteBuffer.wrap(prefix).putInt(bootstrapBytes.size)
        return prefix + bootstrapBytes + frame
    }

    private fun bootstrapJsonOf(file: ByteArray): String {
        val bootstrapLength = ByteBuffer.wrap(file, 0, Integer.BYTES).int
        return file.copyOfRange(4, 4 + bootstrapLength).toString(Charsets.UTF_8)
    }

    private fun frameOf(file: ByteArray): ByteArray {
        val bootstrapLength = ByteBuffer.wrap(file, 0, Integer.BYTES).int
        return file.copyOfRange(4 + bootstrapLength, file.size)
    }

    private fun publicationIdFor(sequence: Long): String =
        "00000000-0000-4000-8000-%012d".format(sequence)

    private fun fixtureEnvelope(): VaultKeyEnvelope = VaultKeyEnvelope(
        formatVersion = 1,
        kdf = Argon2Metadata(ByteArray(16) { it.toByte() }),
        nonce = ByteArray(12) { (it + 16).toByte() },
        wrappedKeyset = ByteArray(8) { (it + 28).toByte() },
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private class InspectingAuthenticatedCodec(
        private val delegate: AuthenticatedCloudObjectCodec,
    ) : AuthenticatedCloudObjectCodec {
        val encryptedIdentities = mutableListOf<CloudHeaderIdentity>()
        val encryptionPlaintexts = mutableListOf<ByteArray>()

        override fun encrypt(
            identity: CloudHeaderIdentity,
            plaintext: ByteArray,
            key: VaultKey,
        ): ByteArray {
            encryptedIdentities += identity
            encryptionPlaintexts += plaintext
            return delegate.encrypt(identity, plaintext, key)
        }

        override fun decrypt(
            source: InputStream,
            totalLength: Long,
            key: VaultKey,
        ): CloudDecodeResult = delegate.decrypt(source, totalLength, key)
    }

    private companion object {
        const val LINEAGE_ID = "00000000-0000-4000-8000-000000000001"
        const val OTHER_LINEAGE_ID = "00000000-0000-4000-8000-0000000000ff"
        const val ROOT_CLAIM_ID = "00000000-0000-4000-8000-000000000002"
        const val TOMBSTONE_CLAIM_ID = "00000000-0000-4000-8000-000000000004"
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000005"
        const val OTHER_DEVICE_ID = "00000000-0000-4000-8000-000000000006"
        const val VAULT_ID = "00000000-0000-4000-8000-000000000009"
        const val OPERATION_ID = "00000000-0000-4000-8000-00000000000a"
        const val TOMBSTONE_ID = "00000000-0000-4000-8000-00000000000d"
        const val ROOT_PROVIDER_ID = "provider-root"
        const val CURRENT_BASE_ID = "base-current"
        const val FALLBACK_BASE_ID = "base-fallback"
        const val BASE_GENERATION = 40L
        const val SEGMENT_ONE_ID = "segment-41"
        const val SEGMENT_TWO_ID = "segment-42"
        const val RECOVERY_CREDENTIAL_GENERATION = 3L
        const val DIGEST_A =
            "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        const val DIGEST_B =
            "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
        const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
        val TEST_JSON = Json {
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
