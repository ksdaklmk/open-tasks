package app.opentasks.core.data.backup

import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.data.DefaultAuthenticatedCloudObjectCodec
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeFailure
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.data.DecryptedCloudObject
import app.opentasks.core.data.db.SavedViewEntity
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.VaultId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.io.InputStream

class DefaultBackupCoordinatorTest {
    @Test
    fun firstRequestProducesAndVerifiesACompleteBaselineBeforeCheckpointing() = runBlocking {
        val root = Files.createTempDirectory("backup-coordinator-test").toFile()
        val fixture = BackupPayloadTestFixtures.snapshot()
        val capture = StructuredBackupCapture(
            vaultId = VaultId(fixture.vaultId),
            generation = BackupGeneration(fixture.coveredGeneration),
            records = fixture.records,
        )
        val state = InMemoryBackupStateStore(
            BackupStateEntity(
                vaultId = capture.vaultId.value,
                currentGeneration = fixture.coveredGeneration,
                lastVerifiedSnapshotGeneration = null,
                currentBaseObjectId = null,
                previousBaseObjectId = null,
                latestVerifiedSegmentGeneration = null,
                portablePackageGeneration = null,
                portablePackageBytes = null,
                portablePackageProducedAtEpochMillis = null,
                packageState = "IDLE",
                failureCategory = null,
                recoveryEnvelopeReady = false,
                legacyOutboxCoveredAtGeneration = null,
                snapshotCreatedAtEpochMillis = null,
            ),
        )
        val crypto = TinkVaultCrypto()
        val keyStore = InMemoryVaultContentKeyStore(crypto)
        try {
            val coordinator = DefaultBackupCoordinator(
                vaultId = capture.vaultId,
                captureSource = { capture },
                stateStore = state,
                journalStore = InMemoryBackupJournalStore(emptyList()),
                objectStore = DefaultLocalBackupObjectStore(root),
                authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
                contentKeyStore = keyStore,
                now = { Instant.parse("2026-07-29T00:00:00Z") },
            )

            coordinator.request()

            assertEquals(53L, state.value.lastVerifiedSnapshotGeneration)
            assertEquals("snapshot:53", state.value.currentBaseObjectId)
            assertEquals(53L, state.value.legacyOutboxCoveredAtGeneration)
            assertTrue(root.resolve("current/snapshot-53.otf").isFile)
        } finally {
            keyStore.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun localCheckpointPreservesRestoredPackageClassification() = runBlocking {
        val root = Files.createTempDirectory("backup-coordinator-restored-state").toFile()
        val fixture = BackupPayloadTestFixtures.snapshot()
        val capture = StructuredBackupCapture(
            vaultId = VaultId(fixture.vaultId),
            generation = BackupGeneration(fixture.coveredGeneration),
            records = fixture.records,
        )
        val state = InMemoryBackupStateStore(
            defaultState(capture).copy(
                packageState = "RESTORED_PACKAGE_DETECTED",
                failureCategory = "PRESERVED",
            ),
        )
        val crypto = TinkVaultCrypto()
        val keyStore = InMemoryVaultContentKeyStore(crypto)
        try {
            DefaultBackupCoordinator(
                vaultId = capture.vaultId,
                captureSource = { capture },
                stateStore = state,
                journalStore = InMemoryBackupJournalStore(emptyList()),
                objectStore = DefaultLocalBackupObjectStore(root),
                authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
                contentKeyStore = keyStore,
            ).request()

            assertEquals("RESTORED_PACKAGE_DETECTED", state.value.packageState)
            assertEquals("PRESERVED", state.value.failureCategory)
        } finally {
            keyStore.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun coordinatorSelectsOnlyTheCompleteRangeUpToItsCapturedGeneration() = runBlocking {
        val fixture = BackupPayloadTestFixtures.snapshot()
        val capture = StructuredBackupCapture(
            VaultId(fixture.vaultId),
            BackupGeneration(fixture.coveredGeneration),
            fixture.records,
        )
        val state = InMemoryBackupStateStore(defaultState(capture).copy(
            currentBaseObjectId = "snapshot:53",
            lastVerifiedSnapshotGeneration = 53,
            latestVerifiedSegmentGeneration = 53,
            snapshotCreatedAtEpochMillis = Instant.parse("2026-07-29T00:00:00Z").toEpochMilli(),
        ))
        val journal = CaptureBoundJournalStore()
        val crypto = TinkVaultCrypto()
        val keys = InMemoryVaultContentKeyStore(crypto)
        val root = Files.createTempDirectory("backup-coordinator-test").toFile()
        try {
            DefaultBackupCoordinator(
                vaultId = capture.vaultId,
                captureSource = { capture },
                stateStore = state,
                journalStore = journal,
                objectStore = DefaultLocalBackupObjectStore(root),
                authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
                contentKeyStore = keys,
                now = { Instant.parse("2026-07-29T00:00:00Z") },
            ).request()

            assertTrue(journal.betweenCalled)
        } finally {
            keys.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun laterRowsProduceOneContiguousSegmentAndExcludeLegacyFormatZeroRows() = runBlocking {
        val fixture = BackupPayloadTestFixtures.snapshot()
        val capture = StructuredBackupCapture(VaultId(fixture.vaultId), BackupGeneration(55), fixture.records)
        val state = InMemoryBackupStateStore(defaultState(capture).copy(
            currentBaseObjectId = "snapshot:53",
            lastVerifiedSnapshotGeneration = 53,
            latestVerifiedSegmentGeneration = 53,
            snapshotCreatedAtEpochMillis = Instant.parse("2026-07-29T00:00:00Z").toEpochMilli(),
        ))
        val entries = listOf(
            journalEntry("legacy", 54, 0).copy(payloadFormatVersion = 0),
            journalEntry("op-54", 54, 1),
            journalEntry("op-55", 55, 0),
        )
        val root = Files.createTempDirectory("backup-coordinator-test").toFile()
        val crypto = TinkVaultCrypto()
        val keys = InMemoryVaultContentKeyStore(crypto)
        try {
            DefaultBackupCoordinator(
                vaultId = capture.vaultId,
                captureSource = { capture },
                stateStore = state,
                journalStore = InMemoryBackupJournalStore(entries),
                objectStore = DefaultLocalBackupObjectStore(root),
                authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
                contentKeyStore = keys,
                now = { Instant.parse("2026-07-29T00:00:00Z") },
            ).request()

            assertTrue(root.resolve("segments/segment-54-55.otf").isFile)
            assertEquals(55L, state.value.latestVerifiedSegmentGeneration)
        } finally {
            keys.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedSingleGenerationRotatesToSnapshotInsteadOfCollidingSegmentRange() = runBlocking<Unit> {
        val fixture = BackupPayloadTestFixtures.snapshot()
        val capture = StructuredBackupCapture(VaultId(fixture.vaultId), BackupGeneration(55), fixture.records)
        val state = InMemoryBackupStateStore(defaultState(capture).copy(
            currentBaseObjectId = "snapshot:53",
            lastVerifiedSnapshotGeneration = 53,
            latestVerifiedSegmentGeneration = 53,
            snapshotCreatedAtEpochMillis = Instant.parse("2026-07-29T00:00:00Z").toEpochMilli(),
        ))
        val entries = (0 until 7).map { sequence ->
            largeJournalEntry("large-$sequence", generation = 54, sequence = sequence)
        }
        val root = Files.createTempDirectory("backup-coordinator-test").toFile()
        val crypto = TinkVaultCrypto()
        val keys = InMemoryVaultContentKeyStore(crypto)
        try {
            DefaultBackupCoordinator(
                vaultId = capture.vaultId,
                captureSource = { capture },
                stateStore = state,
                journalStore = InMemoryBackupJournalStore(entries),
                objectStore = DefaultLocalBackupObjectStore(root),
                authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
                contentKeyStore = keys,
                now = { Instant.parse("2026-07-29T00:00:00Z") },
            ).request()

            assertTrue(root.resolve("current/snapshot-55.otf").isFile)
            assertFalse(root.resolve("segments/segment-54-54.otf").exists())
        } finally {
            keys.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun requestAtCompletionBoundaryCoalescesIntoOneFollowUpPass() = runBlocking {
        val fixture = BackupPayloadTestFixtures.snapshot()
        val capture = StructuredBackupCapture(VaultId(fixture.vaultId), BackupGeneration(53), fixture.records)
        val state = InMemoryBackupStateStore(defaultState(capture))
        val root = Files.createTempDirectory("backup-coordinator-test").toFile()
        val crypto = TinkVaultCrypto()
        val keys = InMemoryVaultContentKeyStore(crypto)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var captures = 0
        var boundaries = 0
        try {
            val coordinator = DefaultBackupCoordinator(
                vaultId = capture.vaultId,
                captureSource = {
                    captures += 1
                    capture
                },
                stateStore = state,
                journalStore = InMemoryBackupJournalStore(emptyList()),
                objectStore = DefaultLocalBackupObjectStore(root),
                authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
                contentKeyStore = keys,
                now = { Instant.parse("2026-07-29T00:00:00Z") },
                lifecycleBoundary = BackupCoordinatorLifecycleBoundary {
                    boundaries += 1
                    if (boundaries == 1) {
                        started.complete(Unit)
                        release.await()
                    }
                },
            )
            val first = launch { coordinator.request() }
            started.await()
            val second = launch { coordinator.request() }
            release.complete(Unit)
            first.join()
            second.join()

            assertEquals(2, captures)
        } finally {
            keys.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun coalescedRequestObservesOwnerCancellationAndCanBeRetried() = runBlocking {
        val fixture = BackupPayloadTestFixtures.snapshot()
        val capture = StructuredBackupCapture(VaultId(fixture.vaultId), BackupGeneration(53), fixture.records)
        val state = InMemoryBackupStateStore(defaultState(capture))
        val root = Files.createTempDirectory("backup-coordinator-test").toFile()
        val crypto = TinkVaultCrypto()
        val keys = InMemoryVaultContentKeyStore(crypto)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelOwner = true
        try {
            val coordinator = DefaultBackupCoordinator(
                vaultId = capture.vaultId,
                captureSource = { capture },
                stateStore = state,
                journalStore = InMemoryBackupJournalStore(emptyList()),
                objectStore = DefaultLocalBackupObjectStore(root),
                authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
                contentKeyStore = keys,
                now = { Instant.parse("2026-07-29T00:00:00Z") },
                lifecycleBoundary = BackupCoordinatorLifecycleBoundary {
                    if (cancelOwner) {
                        started.complete(Unit)
                        release.await()
                        throw kotlinx.coroutines.CancellationException("injected owner cancellation")
                    }
                },
            )
            val owner = async { coordinator.request() }
            started.await()
            val joined = async { coordinator.request() }
            release.complete(Unit)
            owner.join()
            joined.join()

            assertTrue(owner.isCancelled)
            assertTrue(joined.isCancelled)
            cancelOwner = false
            coordinator.request()
        } finally {
            keys.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun fiveThousandCapturedOperationsRotateACompleteSnapshot() = runBlocking {
        val fixture = BackupPayloadTestFixtures.snapshot()
        val capture = StructuredBackupCapture(VaultId(fixture.vaultId), BackupGeneration(54), fixture.records)
        val state = InMemoryBackupStateStore(defaultState(capture).copy(
            currentBaseObjectId = "snapshot:53",
            lastVerifiedSnapshotGeneration = 53,
            latestVerifiedSegmentGeneration = 53,
            snapshotCreatedAtEpochMillis = Instant.parse("2026-07-29T00:00:00Z").toEpochMilli(),
        ))
        val entries = (0 until 5_000).map { journalEntry("op-$it", 54, it) }
        val root = Files.createTempDirectory("backup-coordinator-test").toFile()
        val crypto = TinkVaultCrypto()
        val keys = InMemoryVaultContentKeyStore(crypto)
        try {
            DefaultBackupCoordinator(
                vaultId = capture.vaultId,
                captureSource = { capture },
                stateStore = state,
                journalStore = InMemoryBackupJournalStore(entries),
                objectStore = DefaultLocalBackupObjectStore(root),
                authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
                contentKeyStore = keys,
                now = { Instant.parse("2026-07-29T00:00:00Z") },
            ).request()

            assertTrue(root.resolve("current/snapshot-54.otf").isFile)
            assertEquals(54L, state.value.lastVerifiedSnapshotGeneration)
        } finally {
            keys.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun sevenDayOldBaseRotatesACompleteSnapshotWithoutNewJournalRows() = runBlocking {
        val fixture = BackupPayloadTestFixtures.snapshot()
        val capture = StructuredBackupCapture(VaultId(fixture.vaultId), BackupGeneration(54), fixture.records)
        val state = InMemoryBackupStateStore(defaultState(capture).copy(
            currentBaseObjectId = "snapshot:53",
            lastVerifiedSnapshotGeneration = 53,
            latestVerifiedSegmentGeneration = 53,
            snapshotCreatedAtEpochMillis = Instant.parse("2026-07-21T00:00:00Z").toEpochMilli(),
        ))
        val root = Files.createTempDirectory("backup-coordinator-test").toFile()
        val crypto = TinkVaultCrypto()
        val keys = InMemoryVaultContentKeyStore(crypto)
        try {
            DefaultBackupCoordinator(
                vaultId = capture.vaultId,
                captureSource = { capture },
                stateStore = state,
                journalStore = InMemoryBackupJournalStore(emptyList()),
                objectStore = DefaultLocalBackupObjectStore(root),
                authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
                contentKeyStore = keys,
                now = { Instant.parse("2026-07-29T00:00:00Z") },
            ).request()

            assertTrue(root.resolve("current/snapshot-54.otf").isFile)
            assertEquals(54L, state.value.lastVerifiedSnapshotGeneration)
        } finally {
            keys.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun stateAdvanceDuringCaptureSchedulesASecondPassAfterCommittingCaptureGeneration() = runBlocking {
        val fixture = BackupPayloadTestFixtures.snapshot()
        val first = StructuredBackupCapture(VaultId(fixture.vaultId), BackupGeneration(53), fixture.records)
        val second = first.copy(generation = BackupGeneration(54))
        val state = InMemoryBackupStateStore(defaultState(first))
        val root = Files.createTempDirectory("backup-coordinator-test").toFile()
        val crypto = TinkVaultCrypto()
        val keys = InMemoryVaultContentKeyStore(crypto)
        var captures = 0
        try {
            DefaultBackupCoordinator(
                vaultId = first.vaultId,
                captureSource = {
                    captures += 1
                    if (captures == 1) {
                        state.replace(state.value.copy(currentGeneration = 54))
                        first
                    } else {
                        second
                    }
                },
                stateStore = state,
                journalStore = InMemoryBackupJournalStore(emptyList()),
                objectStore = DefaultLocalBackupObjectStore(root),
                authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
                contentKeyStore = keys,
                now = { Instant.parse("2026-07-29T00:00:00Z") },
            ).request()

            assertEquals(2, captures)
            assertEquals(53L, state.value.lastVerifiedSnapshotGeneration)
            assertEquals(54L, state.value.currentGeneration)
        } finally {
            keys.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun encodeFailureLeavesCheckpointAndVisibleObjectsUnchanged() = runBlocking {
        val fixture = BackupPayloadTestFixtures.snapshot()
        val capture = StructuredBackupCapture(VaultId(fixture.vaultId), BackupGeneration(53), fixture.records)
        val state = InMemoryBackupStateStore(defaultState(capture))
        val root = Files.createTempDirectory("backup-coordinator-test").toFile()
        val crypto = TinkVaultCrypto()
        val keys = InMemoryVaultContentKeyStore(crypto)
        val failingCodec = object : BackupSnapshotCodec by BackupSnapshotCodec {
            override fun encode(payload: BackupSnapshotPayloadV1): ByteArray =
                throw IllegalStateException("injected encode failure")
        }
        try {
            val coordinator = DefaultBackupCoordinator(
                vaultId = capture.vaultId,
                captureSource = { capture },
                stateStore = state,
                journalStore = InMemoryBackupJournalStore(emptyList()),
                objectStore = DefaultLocalBackupObjectStore(root),
                authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
                contentKeyStore = keys,
                snapshotCodec = failingCodec,
                now = { Instant.parse("2026-07-29T00:00:00Z") },
            )

            assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.request() }
            }

            assertEquals(null, state.value.lastVerifiedSnapshotGeneration)
            assertFalse(root.resolve("current/snapshot-53.otf").exists())
        } finally {
            keys.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun failureMatrixLeavesCheckpointAndJournalUntouched() {
        val scenarios = listOf(
            "encrypt" to { real: AuthenticatedCloudObjectCodec ->
                object : AuthenticatedCloudObjectCodec by real {
                    override fun encrypt(identity: app.opentasks.core.sync.CloudHeaderIdentity, plaintext: ByteArray, key: VaultKey): ByteArray =
                        throw IllegalStateException("injected encrypt failure")
                }
            },
            "checksum" to { real: AuthenticatedCloudObjectCodec -> failureDecryptCodec(real, CloudDecodeFailure.CHECKSUM_MISMATCH) },
            "authentication" to { real: AuthenticatedCloudObjectCodec -> failureDecryptCodec(real, CloudDecodeFailure.AUTHENTICATION_FAILED) },
            "identity" to { real: AuthenticatedCloudObjectCodec -> identityMismatchCodec(real) },
        )
        scenarios.forEach { (label, codecFactory) ->
            assertSnapshotFailurePreservesStateAndJournal(label, authenticatedCodecFactory = codecFactory)
        }
        assertSnapshotFailurePreservesStateAndJournal(
            "write",
            objectStoreFactory = { delegate -> FailingWriteObjectStore(delegate) },
        )
    }

    @Test
    fun strictDecodeAndSourceComparisonFailuresLeaveCheckpointAndJournalUntouched() {
        assertSnapshotFailurePreservesStateAndJournal(
            "strict-decode",
            snapshotCodec = object : BackupSnapshotCodec by BackupSnapshotCodec {
                override fun decodeOwned(source: ByteArray): BackupSnapshotPayloadV1 {
                    source.fill(0)
                    throw IllegalArgumentException("injected strict decode failure")
                }
            },
        )
        assertSnapshotFailurePreservesStateAndJournal(
            "source-compare",
            snapshotCodec = object : BackupSnapshotCodec by BackupSnapshotCodec {
                override fun decodeOwned(source: ByteArray): BackupSnapshotPayloadV1 =
                    BackupSnapshotCodec.decodeOwned(source).copy(coveredGeneration = 999)
            },
        )
    }

    @Test
    fun segmentFailureMatrixLeavesCheckpointJournalAndBasesUntouched() {
        val scenarios = listOf(
            "encrypt" to { real: AuthenticatedCloudObjectCodec ->
                object : AuthenticatedCloudObjectCodec by real {
                    override fun encrypt(identity: app.opentasks.core.sync.CloudHeaderIdentity, plaintext: ByteArray, key: VaultKey): ByteArray =
                        throw IllegalStateException("injected encrypt failure")
                }
            },
            "checksum" to { real: AuthenticatedCloudObjectCodec -> failureDecryptCodec(real, CloudDecodeFailure.CHECKSUM_MISMATCH) },
            "authentication" to { real: AuthenticatedCloudObjectCodec -> failureDecryptCodec(real, CloudDecodeFailure.AUTHENTICATION_FAILED) },
            "identity" to { real: AuthenticatedCloudObjectCodec -> identityMismatchCodec(real) },
        )
        scenarios.forEach { (label, codecFactory) ->
            assertSegmentFailurePreservesStateAndJournal(label, authenticatedCodecFactory = codecFactory)
        }
        assertSegmentFailurePreservesStateAndJournal(
            "write",
            objectStoreFactory = { delegate -> FailingWriteObjectStore(delegate) },
        )
        assertSegmentFailurePreservesStateAndJournal(
            "strict-decode",
            segmentCodec = object : BackupOperationSegmentCodec by BackupOperationSegmentCodec {
                override fun decodeOwned(source: ByteArray): BackupOperationSegmentPayloadV1 {
                    source.fill(0)
                    throw IllegalArgumentException("injected strict decode failure")
                }
            },
        )
        assertSegmentFailurePreservesStateAndJournal(
            "source-compare",
            segmentCodec = object : BackupOperationSegmentCodec by BackupOperationSegmentCodec {
                override fun decodeOwned(source: ByteArray): BackupOperationSegmentPayloadV1 =
                    BackupOperationSegmentCodec.decodeOwned(source).copy(lastGeneration = 999)
            },
        )
    }

    @Test
    fun cancellationZeroesOwnedPlaintextAndFrameAndReleasesCoordinatorOwnership() = runBlocking {
        val fixture = BackupPayloadTestFixtures.snapshot()
        val capture = StructuredBackupCapture(VaultId(fixture.vaultId), BackupGeneration(53), fixture.records)
        val state = InMemoryBackupStateStore(defaultState(capture))
        val root = Files.createTempDirectory("backup-coordinator-test").toFile()
        val crypto = TinkVaultCrypto()
        val real = DefaultAuthenticatedCloudObjectCodec(crypto)
        val codec = CapturingAuthenticatedCodec(real)
        val store = CapturingObjectStore(DefaultLocalBackupObjectStore(root))
        val keys = InMemoryVaultContentKeyStore(crypto)
        val candidateWritten = CompletableDeferred<Unit>()
        val continueAfterCandidate = CompletableDeferred<Unit>()
        var blockAfterCandidate = true
        try {
            val coordinator = DefaultBackupCoordinator(
                vaultId = capture.vaultId,
                captureSource = { capture },
                stateStore = state,
                journalStore = InMemoryBackupJournalStore(emptyList()),
                objectStore = store,
                authenticatedCodec = codec,
                contentKeyStore = keys,
                now = { Instant.parse("2026-07-29T00:00:00Z") },
                candidateLifecycleBoundary = BackupCandidateLifecycleBoundary {
                    if (blockAfterCandidate) {
                        candidateWritten.complete(Unit)
                        continueAfterCandidate.await()
                    }
                },
            )

            val request = launch { coordinator.request() }
            candidateWritten.await()
            request.cancel()
            request.join()

            assertTrue(request.isCancelled)
            assertTrue(checkNotNull(codec.plaintext).all { it == 0.toByte() })
            assertTrue(checkNotNull(store.frame).all { it == 0.toByte() })
            assertEquals(null, state.value.currentBaseObjectId)
            assertTrue(root.resolve("staging").listFiles().isNullOrEmpty())
            blockAfterCandidate = false
            coordinator.request()
            assertEquals("snapshot:53", state.value.currentBaseObjectId)
        } finally {
            keys.close()
            root.deleteRecursively()
        }
    }
}

private class CapturingAuthenticatedCodec(
    private val delegate: AuthenticatedCloudObjectCodec,
) : AuthenticatedCloudObjectCodec by delegate {
    var plaintext: ByteArray? = null

    override fun encrypt(
        identity: app.opentasks.core.sync.CloudHeaderIdentity,
        plaintext: ByteArray,
        key: VaultKey,
    ): ByteArray {
        this.plaintext = plaintext
        return delegate.encrypt(identity, plaintext, key)
    }
}

private class CapturingObjectStore(
    private val delegate: LocalBackupObjectStore,
) : LocalBackupObjectStore by delegate {
    var frame: ByteArray? = null

    override fun writeCandidate(objectId: String, frame: ByteArray): LocalBackupCandidate {
        this.frame = frame
        return delegate.writeCandidate(objectId, frame)
    }
}

private class FailingWriteObjectStore(
    private val delegate: LocalBackupObjectStore,
) : LocalBackupObjectStore by delegate {
    override fun writeCandidate(objectId: String, frame: ByteArray): LocalBackupCandidate =
        throw IllegalStateException("injected write failure")
}

private fun failureDecryptCodec(
    delegate: AuthenticatedCloudObjectCodec,
    reason: CloudDecodeFailure,
): AuthenticatedCloudObjectCodec = object : AuthenticatedCloudObjectCodec by delegate {
    override fun decrypt(source: InputStream, totalLength: Long, key: VaultKey): CloudDecodeResult =
        CloudDecodeResult.Failure(reason)
}

private fun identityMismatchCodec(delegate: AuthenticatedCloudObjectCodec): AuthenticatedCloudObjectCodec =
    object : AuthenticatedCloudObjectCodec by delegate {
        override fun decrypt(source: InputStream, totalLength: Long, key: VaultKey): CloudDecodeResult {
            val result = delegate.decrypt(source, totalLength, key)
            val decrypted = (result as? CloudDecodeResult.Success)?.value ?: return result
            return DecryptedCloudObject(
                identity = decrypted.identity.copy(objectId = "snapshot:identity-mismatch"),
                plaintext = decrypted.takePlaintext(),
            ).let { CloudDecodeResult.Success(it) }
        }
    }

private fun assertSnapshotFailurePreservesStateAndJournal(
    label: String,
    authenticatedCodecFactory: (AuthenticatedCloudObjectCodec) -> AuthenticatedCloudObjectCodec = { it },
    snapshotCodec: BackupSnapshotCodec = BackupSnapshotCodec,
    objectStoreFactory: (LocalBackupObjectStore) -> LocalBackupObjectStore = { it },
) = runBlocking {
    val fixture = BackupPayloadTestFixtures.snapshot()
    val capture = StructuredBackupCapture(VaultId(fixture.vaultId), BackupGeneration(53), fixture.records)
    val state = InMemoryBackupStateStore(defaultState(capture).copy(
        currentBaseObjectId = "snapshot:53",
        previousBaseObjectId = "snapshot:52",
        lastVerifiedSnapshotGeneration = 53,
        latestVerifiedSegmentGeneration = 53,
        snapshotCreatedAtEpochMillis = 0,
    ))
    val journalEntries = listOf(journalEntry("unchanged-$label", 53, 0))
    val journal = InMemoryBackupJournalStore(journalEntries)
    val root = Files.createTempDirectory("backup-coordinator-test").toFile()
    val crypto = TinkVaultCrypto()
    val keys = InMemoryVaultContentKeyStore(crypto)
    val store = DefaultLocalBackupObjectStore(root)
    try {
        store.commitSnapshot(store.writeCandidate("snapshot:52", "previous".toByteArray()), null)
        store.commitSnapshot(store.writeCandidate("snapshot:53", "current".toByteArray()), "snapshot:52")
        val coordinator = DefaultBackupCoordinator(
            vaultId = capture.vaultId,
            captureSource = { capture },
            stateStore = state,
            journalStore = journal,
            objectStore = objectStoreFactory(store),
            authenticatedCodec = authenticatedCodecFactory(DefaultAuthenticatedCloudObjectCodec(crypto)),
            contentKeyStore = keys,
            snapshotCodec = snapshotCodec,
            now = { Instant.parse("2026-07-29T00:00:00Z") },
        )

        assertThrows(RuntimeException::class.java) { runBlocking { coordinator.request() } }

        assertEquals(label, 53L, state.value.lastVerifiedSnapshotGeneration)
        assertEquals(label, "snapshot:53", state.value.currentBaseObjectId)
        assertEquals(label, "snapshot:52", state.value.previousBaseObjectId)
        assertEquals(label, "current", store.open("snapshot:53").readBytes().decodeToString())
        assertEquals(label, "previous", store.open("snapshot:52").readBytes().decodeToString())
        assertEquals(label, journalEntries, journal.entries())
        assertTrue(label, root.resolve("staging").listFiles().isNullOrEmpty())
    } finally {
        keys.close()
        root.deleteRecursively()
    }
}

private fun assertSegmentFailurePreservesStateAndJournal(
    label: String,
    authenticatedCodecFactory: (AuthenticatedCloudObjectCodec) -> AuthenticatedCloudObjectCodec = { it },
    segmentCodec: BackupOperationSegmentCodec = BackupOperationSegmentCodec,
    objectStoreFactory: (LocalBackupObjectStore) -> LocalBackupObjectStore = { it },
) = runBlocking {
    val fixture = BackupPayloadTestFixtures.snapshot()
    val capture = StructuredBackupCapture(VaultId(fixture.vaultId), BackupGeneration(55), fixture.records)
    val state = InMemoryBackupStateStore(defaultState(capture).copy(
        currentBaseObjectId = "snapshot:53",
        previousBaseObjectId = "snapshot:52",
        lastVerifiedSnapshotGeneration = 53,
        latestVerifiedSegmentGeneration = 53,
        snapshotCreatedAtEpochMillis = Instant.parse("2026-07-29T00:00:00Z").toEpochMilli(),
    ))
    val journalEntries = listOf(
        journalEntry("unchanged-$label-54", 54, 0),
        journalEntry("unchanged-$label-55", 55, 0),
    )
    val journal = InMemoryBackupJournalStore(journalEntries)
    val root = Files.createTempDirectory("backup-coordinator-test").toFile()
    val crypto = TinkVaultCrypto()
    val keys = InMemoryVaultContentKeyStore(crypto)
    val store = DefaultLocalBackupObjectStore(root)
    try {
        store.commitSnapshot(store.writeCandidate("snapshot:52", "previous".toByteArray()), null)
        store.commitSnapshot(store.writeCandidate("snapshot:53", "current".toByteArray()), "snapshot:52")
        val coordinator = DefaultBackupCoordinator(
            vaultId = capture.vaultId,
            captureSource = { capture },
            stateStore = state,
            journalStore = journal,
            objectStore = objectStoreFactory(store),
            authenticatedCodec = authenticatedCodecFactory(DefaultAuthenticatedCloudObjectCodec(crypto)),
            contentKeyStore = keys,
            segmentCodec = segmentCodec,
            now = { Instant.parse("2026-07-29T00:00:00Z") },
        )

        assertThrows(RuntimeException::class.java) { runBlocking { coordinator.request() } }

        assertEquals(label, 53L, state.value.lastVerifiedSnapshotGeneration)
        assertEquals(label, 53L, state.value.latestVerifiedSegmentGeneration)
        assertEquals(label, "snapshot:53", state.value.currentBaseObjectId)
        assertEquals(label, "snapshot:52", state.value.previousBaseObjectId)
        assertEquals(label, "current", store.open("snapshot:53").readBytes().decodeToString())
        assertEquals(label, "previous", store.open("snapshot:52").readBytes().decodeToString())
        assertFalse(label, root.resolve("segments/segment-54-55.otf").exists())
        assertEquals(label, journalEntries, journal.entries())
        assertTrue(label, root.resolve("staging").listFiles().isNullOrEmpty())
    } finally {
        keys.close()
        root.deleteRecursively()
    }
}

private class InMemoryBackupStateStore(initial: BackupStateEntity) : BackupStateStore {
    private val flow = MutableStateFlow(initial)

    val value: BackupStateEntity
        get() = flow.value

    override fun observe(vaultId: VaultId): Flow<BackupStateEntity> = flow

    override suspend fun get(vaultId: VaultId): BackupStateEntity? =
        flow.value.takeIf { it.vaultId == vaultId.value }

    override suspend fun compareAndUpdate(
        entity: BackupStateEntity,
        expectedCurrentGeneration: Long,
    ): Int = if (flow.value.currentGeneration == expectedCurrentGeneration) {
        flow.value = entity
        1
    } else {
        0
    }

    fun replace(entity: BackupStateEntity) {
        flow.value = entity
    }
}

private fun defaultState(capture: StructuredBackupCapture) = BackupStateEntity(
    vaultId = capture.vaultId.value,
    currentGeneration = capture.generation.value,
    lastVerifiedSnapshotGeneration = null,
    currentBaseObjectId = null,
    previousBaseObjectId = null,
    latestVerifiedSegmentGeneration = null,
    portablePackageGeneration = null,
    portablePackageBytes = null,
    portablePackageProducedAtEpochMillis = null,
    packageState = "IDLE",
    failureCategory = null,
    recoveryEnvelopeReady = false,
    legacyOutboxCoveredAtGeneration = null,
    snapshotCreatedAtEpochMillis = null,
)

private fun journalEntry(
    operationId: String,
    generation: Long,
    sequence: Int,
): BackupJournalEntity = BackupJournalEntity(
    operationId = operationId,
    vaultId = "vault-alpha",
    generation = generation,
    sequence = sequence,
    payloadFormatVersion = 1,
    mutationKind = "UPSERT",
    objectId = "tag-$operationId",
    objectType = "TAG",
    payload = BackupPayloadTestFixtures.tagMutation("tag-$operationId"),
    revisionWallMillis = 1,
    revisionLogical = sequence,
    sourceDeviceId = "device-alpha",
)

private fun largeJournalEntry(
    operationId: String,
    generation: Long,
    sequence: Int,
): BackupJournalEntity = BackupJournalEntity(
    operationId = operationId,
    vaultId = "vault-alpha",
    generation = generation,
    sequence = sequence,
    payloadFormatVersion = 1,
    mutationKind = "UPSERT",
    objectId = "view-$operationId",
    objectType = "SAVED_VIEW",
    payload = BackupMutationCodec.encode(
        BackupMutationPayloadV1(
            mutationKind = BackupMutationKind.UPSERT,
            record = SavedViewEntity(
                id = "view-$operationId",
                workspaceId = "workspace-1",
                name = "View",
                encryptedQuery = ByteArray(2 * 1024 * 1024) { 7 },
            ).toBackupRecordV1(),
            deletedFamily = null,
            deletedIdentity = null,
        ),
    ),
    revisionWallMillis = 1,
    revisionLogical = sequence,
    sourceDeviceId = "device-alpha",
)

private class InMemoryBackupJournalStore(
    private val entries: List<BackupJournalEntity>,
) : BackupJournalStore {
    fun entries(): List<BackupJournalEntity> = entries
    override suspend fun after(
        vaultId: VaultId,
        generation: Long,
        limit: Int,
    ): List<BackupJournalEntity> = entries.filter {
        it.vaultId == vaultId.value && it.generation > generation
    }.sortedWith(compareBy<BackupJournalEntity> { it.generation }.thenBy { it.sequence })
        .take(limit)

    override suspend fun countAfter(vaultId: VaultId, generation: Long): Int =
        entries.count { it.vaultId == vaultId.value && it.generation > generation }

    override suspend fun between(
        vaultId: VaultId,
        afterGeneration: Long,
        throughGeneration: Long,
    ): List<BackupJournalEntity> = entries.filter {
        it.vaultId == vaultId.value &&
            it.generation > afterGeneration &&
            it.generation <= throughGeneration
    }.sortedWith(compareBy<BackupJournalEntity> { it.generation }.thenBy { it.sequence })
}

private class CaptureBoundJournalStore : BackupJournalStore {
    var betweenCalled = false

    override suspend fun after(vaultId: VaultId, generation: Long, limit: Int): List<BackupJournalEntity> =
        error("Coordinator must select the capture-bounded range")

    override suspend fun countAfter(vaultId: VaultId, generation: Long): Int =
        error("Coordinator must count only the capture-bounded range")

    override suspend fun between(
        vaultId: VaultId,
        afterGeneration: Long,
        throughGeneration: Long,
    ): List<BackupJournalEntity> {
        betweenCalled = true
        return emptyList()
    }
}

private class InMemoryVaultContentKeyStore(
    private val crypto: TinkVaultCrypto,
) : VaultContentKeyStore {
    private val issued = mutableListOf<VaultKey>()

    override fun getOrCreate(vaultId: VaultId): VaultKey = crypto.createKey().also(issued::add)

    override fun openExisting(vaultId: VaultId): VaultKey =
        error("Backup coordinator tests bootstrap through getOrCreate")

    override fun replace(vaultId: VaultId, key: VaultKey) {
        issued += key
    }

    override fun delete(vaultId: VaultId) {
        Unit
    }

    fun close() {
        issued.forEach(VaultKey::close)
        issued.clear()
    }
}
