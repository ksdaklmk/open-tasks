package app.opentasks.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.data.backup.RoomRemoteBackupStore
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.RemoteBackupConfiguration
import app.opentasks.core.domain.RemoteBackupObject
import app.opentasks.core.domain.RemoteBackupOperation
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.CloudDeviceId
import app.opentasks.core.model.CloudLineageId
import app.opentasks.core.model.OwnershipClaimId
import app.opentasks.core.model.OwnershipClaimRef
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.PublicationId
import app.opentasks.core.model.PublicationRef
import app.opentasks.core.model.PublicationSequence
import app.opentasks.core.model.RemoteBackupFailureCategory
import app.opentasks.core.model.RemoteBackupLifecycle
import app.opentasks.core.model.RemoteBackupStateVersion
import app.opentasks.core.model.RemoteLogicalObjectId
import app.opentasks.core.model.RemoteObjectLifecycle
import app.opentasks.core.model.RemoteObjectRoleV1
import app.opentasks.core.model.Sha256Digest
import app.opentasks.core.model.VaultId
import app.opentasks.core.model.WriterEpoch
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteBackupDaoInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var databaseName: String
    private lateinit var database: VaultDatabase
    private lateinit var store: RoomRemoteBackupStore

    @Before
    fun setUp() {
        databaseName = "remote-backup-dao-${UUID.randomUUID()}.db"
        database = VaultDatabase.create(
            context,
            databaseName,
            ByteArray(32) { (it + 1).toByte() },
        )
        store = RoomRemoteBackupStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun activeConfigurationRoundTripsClaimAndPublicationThroughOpaqueTypes() = runBlocking {
        withTimeout(5_000) {
            val vaultId = VaultId.new()
            val lineageId = CloudLineageId.new()
            val deviceId = CloudDeviceId.new()
            val claim = ownershipClaim(epoch = 3)
            store.insertConnecting(configuration(lineageId = lineageId, vaultId = vaultId))

            val activated = activeConfiguration(
                lineageId = lineageId,
                vaultId = vaultId,
                activeDeviceId = deviceId,
                writerEpoch = claim.writerEpoch,
                ownershipClaim = claim,
                currentPublication = publication(sequence = 1, generation = 10),
                lastVerifiedGeneration = BackupGeneration(10),
                lastVerifiedAt = Instant.ofEpochMilli(5_000),
                stateVersion = RemoteBackupStateVersion(1),
            )
            assertTrue(
                store.compareAndSet(lineageId, RemoteBackupStateVersion(0), activated),
            )

            val read = store.active(vaultId)
            assertConfigurationsEqual(activated, read)
            assertConfigurationsEqual(activated, store.known(lineageId))
        }
    }

    @Test
    fun anInterruptedConnectingRowIsAdoptedByItsOwnLineage() = runBlocking {
        withTimeout(5_000) {
            val vaultId = VaultId.new()
            val lineageId = CloudLineageId.new()
            // A crash between this row and the durable phase that records it
            // leaves an orphan; the retry reserves fresh provider slots.
            store.insertConnecting(configuration(lineageId = lineageId, vaultId = vaultId))

            store.insertConnecting(
                configuration(lineageId = lineageId, vaultId = vaultId).copy(
                    rootClaimProviderId = ProviderObjectId.of("root-claim-provider-retry"),
                ),
            )

            val adopted = checkNotNull(store.known(lineageId))
            assertEquals(
                ProviderObjectId.of("root-claim-provider-retry"),
                adopted.rootClaimProviderId,
            )
            assertEquals(RemoteBackupLifecycle.CONNECTING, adopted.lifecycle)
            assertEquals(0L, adopted.stateVersion.value)
        }
    }

    @Test
    fun anotherVaultsConnectingRowIsNeverAdoptedAtThisLineage() = runBlocking {
        withTimeout(5_000) {
            val lineageId = CloudLineageId.new()
            val first = VaultId.new()
            store.insertConnecting(configuration(lineageId = lineageId, vaultId = first))

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    store.insertConnecting(
                        configuration(lineageId = lineageId, vaultId = VaultId.new()),
                    )
                }
            }
            assertEquals(first, checkNotNull(store.known(lineageId)).vaultId)
        }
    }

    @Test
    fun anAlreadyActiveLineageIsNeverAdoptedAsConnecting() = runBlocking {
        withTimeout(5_000) {
            val vaultId = VaultId.new()
            val lineageId = CloudLineageId.new()
            store.insertConnecting(configuration(lineageId = lineageId, vaultId = vaultId))
            assertTrue(
                store.compareAndSet(
                    lineageId,
                    RemoteBackupStateVersion(0),
                    activeConfiguration(
                        lineageId = lineageId,
                        vaultId = vaultId,
                        activeDeviceId = CloudDeviceId.new(),
                        writerEpoch = WriterEpoch(1),
                        ownershipClaim = null,
                        currentPublication = publication(sequence = 0, generation = 7),
                        lastVerifiedGeneration = BackupGeneration(7),
                        lastVerifiedAt = Instant.ofEpochMilli(1_000),
                        stateVersion = RemoteBackupStateVersion(1),
                    ),
                ),
            )

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    store.insertConnecting(configuration(lineageId = lineageId, vaultId = vaultId))
                }
            }
            assertEquals(
                RemoteBackupLifecycle.ACTIVE,
                checkNotNull(store.known(lineageId)).lifecycle,
            )
        }
    }

    @Test
    fun staleLocalStateVersionCannotAdvanceRemoteCheckpoint() = runBlocking {
        withTimeout(5_000) {
            val vaultId = VaultId.new()
            val lineageId = CloudLineageId.new()
            store.insertConnecting(
                configuration(
                    lineageId = lineageId,
                    vaultId = vaultId,
                    stateVersion = RemoteBackupStateVersion(1),
                ),
            )

            val next = activeConfiguration(
                lineageId = lineageId,
                vaultId = vaultId,
                activeDeviceId = CloudDeviceId.new(),
                writerEpoch = WriterEpoch(1),
                ownershipClaim = null,
                currentPublication = publication(sequence = 0, generation = 42),
                lastVerifiedGeneration = BackupGeneration(42),
                lastVerifiedAt = Instant.ofEpochMilli(1_000),
                stateVersion = RemoteBackupStateVersion(2),
            )

            assertFalse(
                store.compareAndSet(lineageId, expected = RemoteBackupStateVersion(2), next = next),
            )
            assertEquals(1L, store.known(lineageId)!!.stateVersion.value)
        }
    }

    @Test
    fun onlyOneActiveConfigurationPerVaultIsAllowed() = runBlocking {
        withTimeout(5_000) {
            val vaultId = VaultId.new()
            val firstLineage = CloudLineageId.new()
            val secondLineage = CloudLineageId.new()
            store.insertConnecting(configuration(lineageId = firstLineage, vaultId = vaultId))
            store.insertConnecting(configuration(lineageId = secondLineage, vaultId = vaultId))

            assertTrue(
                store.compareAndSet(
                    firstLineage,
                    RemoteBackupStateVersion(0),
                    minimalActive(firstLineage, vaultId, RemoteBackupStateVersion(1)),
                ),
            )
            assertFalse(
                store.compareAndSet(
                    secondLineage,
                    RemoteBackupStateVersion(0),
                    minimalActive(secondLineage, vaultId, RemoteBackupStateVersion(1)),
                ),
            )
            assertEquals(firstLineage, store.active(vaultId)!!.lineageId)
        }
    }

    @Test
    fun dormantOwnershipLostAndTerminatedConfigsCoexistForOneVault() = runBlocking {
        withTimeout(5_000) {
            val vaultId = VaultId.new()
            val dormant = CloudLineageId.new()
            val ownershipLost = CloudLineageId.new()
            val terminated = CloudLineageId.new()
            listOf(dormant, ownershipLost, terminated).forEach { lineage ->
                store.insertConnecting(configuration(lineageId = lineage, vaultId = vaultId))
            }

            assertTrue(
                store.compareAndSet(
                    dormant,
                    RemoteBackupStateVersion(0),
                    configuration(
                        lineageId = dormant,
                        vaultId = vaultId,
                        lifecycle = RemoteBackupLifecycle.DORMANT,
                        stateVersion = RemoteBackupStateVersion(1),
                    ),
                ),
            )
            assertTrue(
                store.compareAndSet(
                    ownershipLost,
                    RemoteBackupStateVersion(0),
                    configuration(
                        lineageId = ownershipLost,
                        vaultId = vaultId,
                        lifecycle = RemoteBackupLifecycle.OWNERSHIP_LOST,
                        failureCategory = RemoteBackupFailureCategory.OWNERSHIP_LOST,
                        stateVersion = RemoteBackupStateVersion(1),
                    ),
                ),
            )
            assertTrue(
                store.compareAndSet(
                    terminated,
                    RemoteBackupStateVersion(0),
                    configuration(
                        lineageId = terminated,
                        vaultId = vaultId,
                        lifecycle = RemoteBackupLifecycle.TERMINATED,
                        stateVersion = RemoteBackupStateVersion(1),
                    ),
                ),
            )

            assertNull(store.active(vaultId))
            assertEquals(RemoteBackupLifecycle.DORMANT, store.known(dormant)!!.lifecycle)
            assertEquals(
                RemoteBackupLifecycle.OWNERSHIP_LOST,
                store.known(ownershipLost)!!.lifecycle,
            )
            assertEquals(RemoteBackupLifecycle.TERMINATED, store.known(terminated)!!.lifecycle)
        }
    }

    @Test
    fun terminatedConfigurationRejectsTransitionsThatLeaveTerminated() = runBlocking {
        withTimeout(5_000) {
            val vaultId = VaultId.new()
            val lineageId = CloudLineageId.new()
            store.insertConnecting(configuration(lineageId = lineageId, vaultId = vaultId))
            assertTrue(
                store.compareAndSet(
                    lineageId,
                    RemoteBackupStateVersion(0),
                    configuration(
                        lineageId = lineageId,
                        vaultId = vaultId,
                        lifecycle = RemoteBackupLifecycle.TERMINATED,
                        stateVersion = RemoteBackupStateVersion(1),
                    ),
                ),
            )

            assertFalse(
                "TERMINATED must reject leaving the terminal lifecycle",
                store.compareAndSet(
                    lineageId,
                    RemoteBackupStateVersion(1),
                    configuration(
                        lineageId = lineageId,
                        vaultId = vaultId,
                        lifecycle = RemoteBackupLifecycle.DORMANT,
                        stateVersion = RemoteBackupStateVersion(2),
                    ),
                ),
            )

            assertTrue(
                "TERMINATED must accept cleanup-progress updates that stay TERMINATED",
                store.compareAndSet(
                    lineageId,
                    RemoteBackupStateVersion(1),
                    configuration(
                        lineageId = lineageId,
                        vaultId = vaultId,
                        lifecycle = RemoteBackupLifecycle.TERMINATED,
                        failureCategory = RemoteBackupFailureCategory.TERMINATED,
                        stateVersion = RemoteBackupStateVersion(2),
                    ),
                ),
            )
            assertEquals(RemoteBackupLifecycle.TERMINATED, store.known(lineageId)!!.lifecycle)
            assertEquals(2L, store.known(lineageId)!!.stateVersion.value)
        }
    }

    @Test
    fun publicationSequenceAdvancesIndependentlyFromGeneration() = runBlocking {
        withTimeout(5_000) {
            val vaultId = VaultId.new()
            val lineageId = CloudLineageId.new()
            store.insertConnecting(configuration(lineageId = lineageId, vaultId = vaultId))
            val first = activeConfiguration(
                lineageId = lineageId,
                vaultId = vaultId,
                activeDeviceId = CloudDeviceId.new(),
                writerEpoch = WriterEpoch(1),
                ownershipClaim = null,
                currentPublication = publication(sequence = 4, generation = 9),
                lastVerifiedGeneration = BackupGeneration(9),
                lastVerifiedAt = Instant.ofEpochMilli(1_000),
                stateVersion = RemoteBackupStateVersion(1),
            )
            assertTrue(store.compareAndSet(lineageId, RemoteBackupStateVersion(0), first))

            // Same generation, sequence advances by exactly one (a passphrase-only republish).
            val second = first.copy(
                previousPublication = first.currentPublication,
                currentPublication = publication(sequence = 5, generation = 9),
                lastVerifiedGeneration = BackupGeneration(9),
                lastVerifiedAt = Instant.ofEpochMilli(2_000),
                stateVersion = RemoteBackupStateVersion(2),
            )
            assertTrue(store.compareAndSet(lineageId, RemoteBackupStateVersion(1), second))
            val afterSameGeneration = store.known(lineageId)!!
            assertEquals(9L, afterSameGeneration.currentPublication!!.generation.value)
            assertEquals(5L, afterSameGeneration.currentPublication!!.sequence.value)

            // Generation jumps by more than one while sequence still advances by exactly one.
            val third = second.copy(
                previousPublication = second.currentPublication,
                currentPublication = publication(sequence = 6, generation = 42),
                lastVerifiedGeneration = BackupGeneration(42),
                lastVerifiedAt = Instant.ofEpochMilli(3_000),
                stateVersion = RemoteBackupStateVersion(3),
            )
            assertTrue(store.compareAndSet(lineageId, RemoteBackupStateVersion(2), third))
            val afterGenerationJump = store.known(lineageId)!!
            assertEquals(42L, afterGenerationJump.currentPublication!!.generation.value)
            assertEquals(6L, afterGenerationJump.currentPublication!!.sequence.value)
            assertEquals(5L, afterGenerationJump.previousPublication!!.sequence.value)

            // The previous publication's own generation (9) must survive independently of
            // the current publication's generation (42) — it is not derived or fabricated
            // from any other column.
            assertEquals(9L, afterGenerationJump.previousPublication!!.generation.value)
            assertNotEquals(
                afterGenerationJump.currentPublication!!.generation.value,
                afterGenerationJump.previousPublication!!.generation.value,
            )
            assertEquals(second.currentPublication, afterGenerationJump.previousPublication)
        }
    }

    @Test
    fun observeActiveEmitsTheActivatedConfiguration() = runBlocking {
        withTimeout(5_000) {
            val vaultId = VaultId.new()
            val lineageId = CloudLineageId.new()
            assertNull(store.observeActive(vaultId).first())

            store.insertConnecting(configuration(lineageId = lineageId, vaultId = vaultId))
            assertTrue(
                store.compareAndSet(
                    lineageId,
                    RemoteBackupStateVersion(0),
                    minimalActive(lineageId, vaultId, RemoteBackupStateVersion(1)),
                ),
            )

            assertEquals(lineageId, store.observeActive(vaultId).first()!!.lineageId)
        }
    }

    @Test
    fun operationPhaseCompareAndSetRejectsAStalePhase() = runBlocking {
        withTimeout(5_000) {
            val lineageId = CloudLineageId.new()
            val operationId = UUID.randomUUID().toString()
            store.putOperation(
                operation(
                    operationId = operationId,
                    lineageId = lineageId,
                    phase = "PLANNED",
                    stateBytes = byteArrayOf(1),
                ),
            )

            assertFalse(
                store.transitionOperation(
                    operationId,
                    expectedPhase = "RUNNING",
                    next = operation(
                        operationId = operationId,
                        lineageId = lineageId,
                        phase = "RUNNING",
                        stateBytes = byteArrayOf(2),
                    ),
                ),
            )
            assertTrue(
                store.transitionOperation(
                    operationId,
                    expectedPhase = "PLANNED",
                    next = operation(
                        operationId = operationId,
                        lineageId = lineageId,
                        phase = "RUNNING",
                        stateBytes = byteArrayOf(2),
                    ),
                ),
            )
            val persisted = requireNotNull(database.remoteBackupOperationDao().get(operationId))
            assertEquals("RUNNING", persisted.phase)
            assertEquals(listOf<Byte>(2), persisted.stateBytes.toList())
        }
    }

    @Test
    fun cleanupCursorBytesSurviveOperationPhaseTransitions() = runBlocking {
        withTimeout(5_000) {
            val lineageId = CloudLineageId.new()
            val operationId = UUID.randomUUID().toString()
            val cursorV1 = byteArrayOf(1, 1, 1)
            val cursorV2 = byteArrayOf(2, 2, 2, 2)
            val cursorV3 = byteArrayOf(3, 3, 3, 3, 3)
            store.putOperation(
                operation(operationId, lineageId, phase = "PLANNED", stateBytes = cursorV1),
            )
            assertTrue(
                store.transitionOperation(
                    operationId,
                    "PLANNED",
                    operation(operationId, lineageId, phase = "CLEANING_UP", stateBytes = cursorV2),
                ),
            )
            assertTrue(
                store.transitionOperation(
                    operationId,
                    "CLEANING_UP",
                    operation(operationId, lineageId, phase = "CLEANING_UP", stateBytes = cursorV3),
                ),
            )

            val persisted = requireNotNull(database.remoteBackupOperationDao().get(operationId))
            assertEquals(cursorV3.toList(), persisted.stateBytes.toList())
        }
    }

    @Test
    fun accountBindingDigestReadsAreDefensiveCopies() = runBlocking {
        withTimeout(5_000) {
            val vaultId = VaultId.new()
            val lineageId = CloudLineageId.new()
            store.insertConnecting(configuration(lineageId = lineageId, vaultId = vaultId))

            val firstRead = store.known(lineageId)!!.accountBindingDigest
            firstRead[0] = (firstRead[0] + 1).toByte()
            val secondRead = store.known(lineageId)!!.accountBindingDigest

            assertNotEquals(firstRead[0], secondRead[0])
        }
    }

    @Test
    fun insertObjectAndReadItBackByLineageAndLogicalId() = runBlocking {
        withTimeout(5_000) {
            val lineageId = CloudLineageId.new()
            val logicalObjectId = RemoteLogicalObjectId.new()
            val value = remoteObject(lineageId, logicalObjectId)

            store.insertObject(value)

            val read = store.objectState(lineageId, logicalObjectId)
            assertEquals(value, read)
            assertEquals(listOf(value), store.objectsForLineage(lineageId))
        }
    }

    @Test
    fun resumableSessionUriCanOnlyClearAfterVerification() = runBlocking {
        withTimeout(5_000) {
            val lineageId = CloudLineageId.new()
            val logicalObjectId = RemoteLogicalObjectId.new()
            val uploading = remoteObject(
                lineageId,
                logicalObjectId,
                resumableSessionUri = "https://drive.example/resume/session-1",
                uploadedBytes = 100,
                lifecycle = RemoteObjectLifecycle.UPLOADING,
                verifiedAt = null,
            )
            store.insertObject(uploading)

            val prematureClear = uploading.copy(
                resumableSessionUri = null,
                uploadedBytes = 512,
                lifecycle = RemoteObjectLifecycle.UPLOADING,
                verifiedAt = null,
            )
            assertFalse(store.compareAndSetObject(uploading, prematureClear))
            assertEquals(uploading, store.objectState(lineageId, logicalObjectId))

            val verifiedClear = uploading.copy(
                resumableSessionUri = null,
                uploadedBytes = 1_024,
                lifecycle = RemoteObjectLifecycle.VERIFIED,
                verifiedAt = Instant.ofEpochMilli(9_000),
            )
            assertTrue(store.compareAndSetObject(uploading, verifiedClear))
            assertEquals(verifiedClear, store.objectState(lineageId, logicalObjectId))
        }
    }

    @Test
    fun removeObjectStateDeletesExactlyThatObject() = runBlocking {
        withTimeout(5_000) {
            val lineageId = CloudLineageId.new()
            val keep = remoteObject(lineageId, RemoteLogicalObjectId.new())
            val remove = remoteObject(lineageId, RemoteLogicalObjectId.new())
            store.insertObject(keep)
            store.insertObject(remove)

            assertTrue(store.removeObjectState(lineageId, remove.logicalObjectId))
            assertFalse(store.removeObjectState(lineageId, remove.logicalObjectId))

            assertEquals(listOf(keep), store.objectsForLineage(lineageId))
        }
    }

    @Test
    fun insertObjectRejectsNegativeFrameLength() = runBlocking {
        withTimeout(5_000) {
            val value = remoteObject(
                CloudLineageId.new(),
                RemoteLogicalObjectId.new(),
                frameLength = -1,
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.insertObject(value) }
            }
            Unit
        }
    }

    @Test
    fun insertObjectRejectsNegativeUploadedByteOffset() = runBlocking {
        withTimeout(5_000) {
            val value = remoteObject(
                CloudLineageId.new(),
                RemoteLogicalObjectId.new(),
                uploadedBytes = -1,
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.insertObject(value) }
            }
            Unit
        }
    }

    @Test
    fun putOperationRejectsATimeBeforeTheEpoch() = runBlocking {
        withTimeout(5_000) {
            val value = operation(
                UUID.randomUUID().toString(),
                CloudLineageId.new(),
                phase = "PLANNED",
                stateBytes = byteArrayOf(1),
                startedAt = Instant.ofEpochMilli(-1),
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.putOperation(value) }
            }
            Unit
        }
    }

    @Test
    fun compareAndSetRejectsAnOwnershipClaimEpochMismatch() = runBlocking {
        withTimeout(5_000) {
            val vaultId = VaultId.new()
            val lineageId = CloudLineageId.new()
            store.insertConnecting(configuration(lineageId = lineageId, vaultId = vaultId))

            val mismatched = activeConfiguration(
                lineageId = lineageId,
                vaultId = vaultId,
                activeDeviceId = CloudDeviceId.new(),
                writerEpoch = WriterEpoch(2),
                ownershipClaim = ownershipClaim(epoch = 3),
                currentPublication = null,
                lastVerifiedGeneration = null,
                lastVerifiedAt = null,
                stateVersion = RemoteBackupStateVersion(1),
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    store.compareAndSet(lineageId, RemoteBackupStateVersion(0), mismatched)
                }
            }
            Unit
        }
    }

    private fun assertConfigurationsEqual(
        expected: RemoteBackupConfiguration,
        actual: RemoteBackupConfiguration?,
    ) {
        assertTrue(actual != null && expected == actual)
    }

    private fun accountDigest(tag: Int = 1): ByteArray = ByteArray(32) { (it + tag).toByte() }

    private fun sha256(tag: Int): Sha256Digest = Sha256Digest.of(tag.toString().padStart(64, '0'))

    private fun ownershipClaim(epoch: Long, tag: Int = 1): OwnershipClaimRef = OwnershipClaimRef(
        providerId = ProviderObjectId.of("ownership-claim-provider-$tag"),
        logicalId = OwnershipClaimId.new(),
        sha256 = sha256(tag),
        writerEpoch = WriterEpoch(epoch),
    )

    private fun publication(sequence: Long, generation: Long, tag: Int = 1): PublicationRef =
        PublicationRef(
            providerId = ProviderObjectId.of("publication-provider-$tag-$sequence"),
            logicalId = PublicationId.new(),
            sha256 = sha256(tag + 1),
            sequence = PublicationSequence(sequence),
            generation = BackupGeneration(generation),
        )

    private fun configuration(
        lineageId: CloudLineageId,
        vaultId: VaultId,
        lifecycle: RemoteBackupLifecycle = RemoteBackupLifecycle.CONNECTING,
        failureCategory: RemoteBackupFailureCategory? = null,
        stateVersion: RemoteBackupStateVersion = RemoteBackupStateVersion(0),
    ): RemoteBackupConfiguration = RemoteBackupConfiguration(
        lineageId = lineageId,
        vaultId = vaultId,
        rootClaimProviderId = ProviderObjectId.of("root-claim-provider"),
        accountBindingDigest = accountDigest(),
        lifecycle = lifecycle,
        activeDeviceId = null,
        writerEpoch = null,
        ownershipClaim = null,
        nextSuccessorProviderId = null,
        currentPublication = null,
        previousPublication = null,
        lastVerifiedGeneration = null,
        lastVerifiedAt = null,
        recoveryCredentialGeneration = 0,
        failureCategory = failureCategory,
        stateVersion = stateVersion,
    )

    private fun activeConfiguration(
        lineageId: CloudLineageId,
        vaultId: VaultId,
        activeDeviceId: CloudDeviceId,
        writerEpoch: WriterEpoch,
        ownershipClaim: OwnershipClaimRef?,
        currentPublication: PublicationRef?,
        lastVerifiedGeneration: BackupGeneration?,
        lastVerifiedAt: Instant?,
        stateVersion: RemoteBackupStateVersion,
    ): RemoteBackupConfiguration = RemoteBackupConfiguration(
        lineageId = lineageId,
        vaultId = vaultId,
        rootClaimProviderId = ProviderObjectId.of("root-claim-provider"),
        accountBindingDigest = accountDigest(),
        lifecycle = RemoteBackupLifecycle.ACTIVE,
        activeDeviceId = activeDeviceId,
        writerEpoch = writerEpoch,
        ownershipClaim = ownershipClaim,
        nextSuccessorProviderId = null,
        currentPublication = currentPublication,
        previousPublication = null,
        lastVerifiedGeneration = lastVerifiedGeneration,
        lastVerifiedAt = lastVerifiedAt,
        recoveryCredentialGeneration = 0,
        failureCategory = null,
        stateVersion = stateVersion,
    )

    private fun minimalActive(
        lineageId: CloudLineageId,
        vaultId: VaultId,
        stateVersion: RemoteBackupStateVersion,
    ): RemoteBackupConfiguration = activeConfiguration(
        lineageId = lineageId,
        vaultId = vaultId,
        activeDeviceId = CloudDeviceId.new(),
        writerEpoch = WriterEpoch(1),
        ownershipClaim = null,
        currentPublication = null,
        lastVerifiedGeneration = null,
        lastVerifiedAt = null,
        stateVersion = stateVersion,
    )

    private fun operation(
        operationId: String,
        lineageId: CloudLineageId,
        phase: String,
        stateBytes: ByteArray,
        startedAt: Instant = Instant.ofEpochMilli(1_000),
    ): RemoteBackupOperation = RemoteBackupOperation(
        operationId = operationId,
        lineageId = lineageId,
        kind = "PUBLISH",
        phase = phase,
        targetEpoch = WriterEpoch(1),
        targetGeneration = BackupGeneration(1),
        candidateClaimProviderId = null,
        candidatePublicationProviderId = null,
        stateBytes = stateBytes,
        startedAt = startedAt,
        updatedAt = startedAt,
    )

    private fun remoteObject(
        lineageId: CloudLineageId,
        logicalObjectId: RemoteLogicalObjectId,
        role: RemoteObjectRoleV1 = RemoteObjectRoleV1.SNAPSHOT,
        frameLength: Long = 1_024,
        uploadedBytes: Long = 1_024,
        lifecycle: RemoteObjectLifecycle = RemoteObjectLifecycle.VERIFIED,
        resumableSessionUri: String? = null,
        verifiedAt: Instant? = Instant.ofEpochMilli(2_000),
    ): RemoteBackupObject = RemoteBackupObject(
        lineageId = lineageId,
        logicalObjectId = logicalObjectId,
        providerObjectId = ProviderObjectId.of("object-provider-${UUID.randomUUID()}"),
        role = role,
        writerEpoch = WriterEpoch(1),
        ownerDeviceId = CloudDeviceId.new(),
        operationId = UUID.randomUUID().toString(),
        firstGeneration = BackupGeneration(1),
        lastGeneration = BackupGeneration(1),
        frameLength = frameLength,
        frameSha256 = sha256(7),
        lifecycle = lifecycle,
        resumableSessionUri = resumableSessionUri,
        uploadedBytes = uploadedBytes,
        createdAt = Instant.ofEpochMilli(1_000),
        verifiedAt = verifiedAt,
    )
}
