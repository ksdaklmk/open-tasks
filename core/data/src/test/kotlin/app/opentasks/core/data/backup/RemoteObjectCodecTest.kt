package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.VaultId
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteObjectCodecTest {

    @Test
    fun snapshotObjectsCarryTheirRoleGenerationsLengthAndDigest() = runCodecTest { fixture ->
        fixture.seedSnapshot()

        val remote = fixture.codec.reauthenticateLocalObject(
            localObjectId = SNAPSHOT_OBJECT_ID,
            vaultId = VAULT_ID,
            lineageId = LINEAGE,
            logicalObjectId = BASE_A,
            contentKey = fixture.contentKey,
        )

        try {
            assertEquals(RemoteObjectRoleV1.SNAPSHOT, remote.role)
            assertEquals(BackupGeneration(SNAPSHOT_GENERATION), remote.firstGeneration)
            assertEquals(BackupGeneration(SNAPSHOT_GENERATION), remote.lastGeneration)
            assertEquals(BASE_A, remote.logicalObjectId)
            assertEquals(remote.file.length(), remote.frameLength)
            assertEquals(hexDigestOf(remote.file.readBytes()), remote.frameSha256.value)
        } finally {
            remote.close()
        }
    }

    @Test
    fun theExplicitRemoteLogicalIdBindsTheReauthenticatedFrameIdentity() = runCodecTest { fixture ->
        val payload = fixture.seedSnapshot()

        val remote = fixture.codec.reauthenticateLocalObject(
            localObjectId = SNAPSHOT_OBJECT_ID,
            vaultId = VAULT_ID,
            lineageId = LINEAGE,
            logicalObjectId = BASE_A,
            contentKey = fixture.contentKey,
        )

        try {
            val decoded = fixture.decodeRemote(remote.file, CloudObjectFamily.SNAPSHOT, BASE_A)
            assertArrayEquals(
                BackupSnapshotCodec.encode(payload),
                BackupSnapshotCodec.encode(decoded),
            )
        } finally {
            remote.close()
        }
    }

    @Test
    fun twoCopiesOfOneGenerationUseIndependentIdentitiesNoncesAndCiphertext() = runCodecTest { fixture ->
        fixture.seedSnapshot()

        val first = fixture.codec.reauthenticateLocalObject(
            localObjectId = SNAPSHOT_OBJECT_ID,
            vaultId = VAULT_ID,
            lineageId = LINEAGE,
            logicalObjectId = BASE_A,
            contentKey = fixture.contentKey,
        )
        val second = fixture.codec.reauthenticateLocalObject(
            localObjectId = SNAPSHOT_OBJECT_ID,
            vaultId = VAULT_ID,
            lineageId = LINEAGE,
            logicalObjectId = BASE_B,
            contentKey = fixture.contentKey,
        )

        try {
            assertNotEquals(first.logicalObjectId, second.logicalObjectId)
            assertNotEquals(first.frameSha256, second.frameSha256)
            assertFalse(first.file.readBytes().contentEquals(second.file.readBytes()))
            assertNotEquals(first.file.absolutePath, second.file.absolutePath)
            // Both remain independently decodable to the same canonical payload.
            assertArrayEquals(
                BackupSnapshotCodec.encode(
                    fixture.decodeRemote(first.file, CloudObjectFamily.SNAPSHOT, BASE_A),
                ),
                BackupSnapshotCodec.encode(
                    fixture.decodeRemote(second.file, CloudObjectFamily.SNAPSHOT, BASE_B),
                ),
            )
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun aLocalFrameThatFailsAuthenticationLeavesNoRemoteOutput() = runCodecTest { fixture ->
        fixture.seedSnapshot(key = RemoteBackupTestFixtures.otherKey)

        assertThrows(IllegalArgumentException::class.java) {
            fixture.codec.reauthenticateLocalObject(
                localObjectId = SNAPSHOT_OBJECT_ID,
                vaultId = VAULT_ID,
                lineageId = LINEAGE,
                logicalObjectId = BASE_A,
                contentKey = fixture.contentKey,
            )
        }

        assertTrue(fixture.stagedFiles().isEmpty())
    }

    @Test
    fun aLocalFrameCarryingAnotherObjectIdentityIsRejectedBeforeEncoding() = runCodecTest { fixture ->
        fixture.seedSnapshot(identityObjectId = "snapshot:99")

        assertThrows(IllegalArgumentException::class.java) {
            fixture.codec.reauthenticateLocalObject(
                localObjectId = SNAPSHOT_OBJECT_ID,
                vaultId = VAULT_ID,
                lineageId = LINEAGE,
                logicalObjectId = BASE_A,
                contentKey = fixture.contentKey,
            )
        }

        assertTrue(fixture.stagedFiles().isEmpty())
    }

    @Test
    fun aDecodedPayloadCoveringAnotherGenerationIsRejectedBeforeEncoding() = runCodecTest { fixture ->
        fixture.seedSnapshot(localObjectId = "snapshot:52")

        assertThrows(IllegalArgumentException::class.java) {
            fixture.codec.reauthenticateLocalObject(
                localObjectId = "snapshot:52",
                vaultId = VAULT_ID,
                lineageId = LINEAGE,
                logicalObjectId = BASE_A,
                contentKey = fixture.contentKey,
            )
        }

        assertTrue(fixture.stagedFiles().isEmpty())
    }

    @Test
    fun segmentObjectsAreReauthenticatedUnderTheirOwnFamilyAndRange() = runCodecTest { fixture ->
        val payload = fixture.seedSegment()

        val remote = fixture.codec.reauthenticateLocalObject(
            localObjectId = SEGMENT_OBJECT_ID,
            vaultId = VAULT_ID,
            lineageId = LINEAGE,
            logicalObjectId = SEGMENT_LOGICAL_ID,
            contentKey = fixture.contentKey,
        )

        try {
            assertEquals(RemoteObjectRoleV1.SEGMENT, remote.role)
            assertEquals(BackupGeneration(SEGMENT_GENERATION), remote.firstGeneration)
            assertEquals(BackupGeneration(SEGMENT_GENERATION), remote.lastGeneration)
            val decoded = fixture.decodeSegment(remote.file)
            assertArrayEquals(
                BackupOperationSegmentCodec.encode(payload),
                BackupOperationSegmentCodec.encode(decoded),
            )
        } finally {
            remote.close()
        }
    }

    @Test
    fun closingAReauthenticatedObjectRemovesItsPrivateStagedFile() = runCodecTest { fixture ->
        fixture.seedSnapshot()

        val remote = fixture.codec.reauthenticateLocalObject(
            localObjectId = SNAPSHOT_OBJECT_ID,
            vaultId = VAULT_ID,
            lineageId = LINEAGE,
            logicalObjectId = BASE_A,
            contentKey = fixture.contentKey,
        )
        val staged = remote.file
        remote.close()

        assertFalse(staged.exists())
        assertTrue(fixture.stagedFiles().isEmpty())
    }

    private fun runCodecTest(block: (RemoteObjectCodecFixture) -> Unit) {
        val root = Files.createTempDirectory("remote-object-codec-test").toFile()
        try {
            block(RemoteObjectCodecFixture(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        val VAULT_ID = VaultId(RemoteBackupTestFixtures.VAULT_ID)
        val LINEAGE: CloudLineageId = CloudLineageId.parse(RemoteBackupTestFixtures.LINEAGE_ID)
        val BASE_A: RemoteLogicalObjectId =
            RemoteLogicalObjectId.of(RemoteBackupTestFixtures.BASE_A_LOGICAL_ID)
        val BASE_B: RemoteLogicalObjectId =
            RemoteLogicalObjectId.of(RemoteBackupTestFixtures.BASE_B_LOGICAL_ID)
        val SEGMENT_LOGICAL_ID: RemoteLogicalObjectId =
            RemoteLogicalObjectId.of("00000000-0000-4000-8000-0000000000c3")
        const val SNAPSHOT_GENERATION = 53L
        const val SEGMENT_GENERATION = 41L
        const val SNAPSHOT_OBJECT_ID = "snapshot:53"
        const val SEGMENT_OBJECT_ID = "segment:41:41"
    }
}

private class RemoteObjectCodecFixture(root: File) {
    private val localRoot = File(root, "local").also { it.mkdirs() }
    private val stagingRoot = File(root, "staging").also { it.mkdirs() }
    private val authenticatedCodec =
        DefaultAuthenticatedCloudObjectCodec(RemoteBackupTestFixtures.crypto)
    val localObjectStore: LocalBackupObjectStore = DefaultLocalBackupObjectStore(localRoot)
    val contentKey: VaultKey = RemoteBackupTestFixtures.contentKey
    val codec = RemoteObjectCodec(
        authenticatedCodec = authenticatedCodec,
        localObjectStore = localObjectStore,
        stagingRoot = stagingRoot,
    )

    fun stagedFiles(): List<File> = stagingRoot.listFiles()?.toList().orEmpty()

    fun seedSnapshot(
        key: VaultKey = contentKey,
        localObjectId: String = "snapshot:53",
        identityObjectId: String = localObjectId,
    ): BackupSnapshotPayloadV1 {
        val payload = BackupPayloadTestFixtures.snapshot()
        val plaintext = BackupSnapshotCodec.encode(payload)
        val frame = try {
            authenticatedCodec.encrypt(
                identity(CloudObjectFamily.SNAPSHOT, identityObjectId),
                plaintext,
                key,
            )
        } finally {
            plaintext.fill(0)
        }
        val candidate = localObjectStore.writeCandidate(localObjectId, frame)
        localObjectStore.commitSnapshot(candidate, null)
        candidate.file.delete()
        return payload
    }

    fun seedSegment(): BackupOperationSegmentPayloadV1 {
        val payload = BackupOperationSegmentCodec.fromJournalEntries(
            VaultId(RemoteBackupTestFixtures.VAULT_ID),
            listOf(
                BackupJournalEntity(
                    operationId = "operation-1",
                    vaultId = RemoteBackupTestFixtures.VAULT_ID,
                    generation = 41,
                    sequence = 0,
                    payloadFormatVersion = 1,
                    mutationKind = "UPSERT",
                    objectId = "tag-1",
                    objectType = "TAG",
                    payload = BackupPayloadTestFixtures.tagMutation("tag-1"),
                    revisionWallMillis = 9,
                    revisionLogical = 2,
                    sourceDeviceId = "device-alpha",
                ),
            ),
        )
        val plaintext = BackupOperationSegmentCodec.encode(payload)
        val frame = try {
            authenticatedCodec.encrypt(
                identity(CloudObjectFamily.OPERATION_SEGMENT, "segment:41:41"),
                plaintext,
                contentKey,
            )
        } finally {
            plaintext.fill(0)
        }
        val candidate = localObjectStore.writeCandidate("segment:41:41", frame)
        localObjectStore.commitSegment(candidate)
        candidate.file.delete()
        return payload
    }

    fun decodeRemote(
        file: File,
        family: CloudObjectFamily,
        logicalObjectId: RemoteLogicalObjectId,
    ): BackupSnapshotPayloadV1 {
        val decoded = FileInputStream(file).use {
            authenticatedCodec.decrypt(it, file.length(), contentKey)
        }
        val success = decoded as CloudDecodeResult.Success
        return success.value.use { value ->
            check(
                value.identity == CloudHeaderIdentity(
                    family = family,
                    schemaVersion = 1,
                    cryptoVersion = 1,
                    minimumReaderVersion = 1,
                    vaultId = RemoteBackupTestFixtures.LINEAGE_ID,
                    objectId = logicalObjectId.value,
                ),
            ) { "Remote frame identity is not the lineage plus its remote logical object" }
            BackupSnapshotCodec.decodeOwned(value.takePlaintext())
        }
    }

    fun decodeSegment(file: File): BackupOperationSegmentPayloadV1 {
        val decoded = FileInputStream(file).use {
            authenticatedCodec.decrypt(it, file.length(), contentKey)
        }
        val success = decoded as CloudDecodeResult.Success
        return success.value.use { value ->
            check(value.identity.family == CloudObjectFamily.OPERATION_SEGMENT)
            check(value.identity.vaultId == RemoteBackupTestFixtures.LINEAGE_ID)
            BackupOperationSegmentCodec.decodeOwned(value.takePlaintext())
        }
    }

    private fun identity(family: CloudObjectFamily, objectId: String) = CloudHeaderIdentity(
        family = family,
        schemaVersion = 1,
        cryptoVersion = 1,
        minimumReaderVersion = 1,
        vaultId = RemoteBackupTestFixtures.VAULT_ID,
        objectId = objectId,
    )
}
