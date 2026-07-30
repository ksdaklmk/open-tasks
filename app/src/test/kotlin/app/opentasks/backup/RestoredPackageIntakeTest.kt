package app.opentasks.backup

import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.backup.BackupStateEntity
import app.opentasks.core.data.backup.BackupStateMutation
import app.opentasks.core.data.backup.BackupStateStore
import app.opentasks.core.data.backup.PortableBootstrapHeaderV1
import app.opentasks.core.data.backup.PortablePackageCodec
import app.opentasks.core.data.backup.RecoveryEnvelopeCodec
import app.opentasks.core.data.backup.RecoveryEnvelopePayloadV1
import app.opentasks.core.data.backup.RecoveryEnvelopeStore
import app.opentasks.core.data.backup.VerifiedPortableBackup
import app.opentasks.core.model.RestoredPackageCondition
import app.opentasks.core.model.VaultId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoredPackageIntakeTest {
    @Test
    fun noEligibleFileReturnsNoPackageWithoutOpeningContentKey() = runBlocking {
        val fixture = fixture(fileBytes = null)

        assertEquals(RestoredPackageIntakeResult.NoPackage, fixture.intake.inspect())
        assertEquals(0, fixture.keyStore.openCount)
        assertEquals(0, fixture.codec.completeVerificationCount)
    }

    @Test
    fun restartClearsAbandonedInitialPreparingStateWhenNoFinalFileExists() = runBlocking {
        val fixture = fixture(
            fileBytes = null,
            state = state(
                packageGeneration = null,
                packageState = "PREPARING",
                envelopeReady = false,
            ),
            storedEnvelope = null,
        )

        assertEquals(RestoredPackageIntakeResult.NoPackage, fixture.intake.inspect())
        assertEquals("NOT_PREPARED", fixture.stateStore.current.packageState)
        assertEquals(null, fixture.stateStore.current.failureCategory)
        assertFalse(fixture.stateStore.current.recoveryEnvelopeReady)
    }

    @Test
    fun durableInboxIsStartupTruthAfterMoveBeforeStatusPersistenceCrash() = runBlocking {
        val fixture = fixture(fileBytes = null)
        checkNotNull(fixture.inbox.parentFile).mkdirs()
        val restoredBytes = "durable-restored-input".toByteArray()
        fixture.inbox.writeBytes(restoredBytes)

        assertEquals(
            RestoredPackageIntakeResult.Preserved(RestoredPackageCondition.PRESERVED),
            fixture.intake.inspect(),
        )
        assertArrayEquals(restoredBytes, fixture.inbox.readBytes())
        assertFalse(fixture.eligible.exists())
        assertEquals(0, fixture.keyStore.openCount)
        assertEquals(0, fixture.codec.completeVerificationCount)
    }

    @Test
    fun currentVerifiedSelfProducedPackageKeepsStatusAndFile() = runBlocking {
        val fixture = fixture()
        val original = fixture.eligible.readBytes()

        assertEquals(
            RestoredPackageIntakeResult.CurrentSelfProduced,
            fixture.intake.inspect(),
        )
        assertArrayEquals(original, fixture.eligible.readBytes())
        assertEquals(1, fixture.codec.completeVerificationCount)
        assertEquals(0, fixture.stateStore.updateCount)
    }

    @Test
    fun crashAfterPublishPackageIsFullyVerifiedBeforeStateReconciliation() = runBlocking {
        val fixture = fixture(
            state = state(packageGeneration = null, packageState = "PREPARING"),
        )

        assertEquals(
            RestoredPackageIntakeResult.ReconciledSelfProduced,
            fixture.intake.inspect(),
        )
        assertEquals(1, fixture.codec.completeVerificationCount)
        assertEquals(1, fixture.stateStore.updateCount)
        assertEquals(7L, fixture.stateStore.current.portablePackageGeneration)
        assertEquals("READY", fixture.stateStore.current.packageState)
        assertEquals(null, fixture.stateStore.current.failureCategory)
    }

    @Test
    fun restartReconcilesInitialLocalFileCommitBeforeEnvelopeDatabaseCommit() = runBlocking {
        val fixture = fixture(
            state = state(
                packageGeneration = null,
                packageState = "NOT_PREPARED",
                envelopeReady = false,
            ),
            storedEnvelope = null,
        )
        val original = fixture.eligible.readBytes()

        assertEquals(
            RestoredPackageIntakeResult.ReconciledSelfProduced,
            fixture.intake.inspect(),
        )
        assertArrayEquals(original, fixture.eligible.readBytes())
        assertFalse(fixture.inbox.exists())
        assertEquals(1, fixture.keyStore.openCount)
        assertEquals(1, fixture.codec.completeVerificationCount)
        assertEquals(7L, fixture.stateStore.current.portablePackageGeneration)
        assertEquals("READY", fixture.stateStore.current.packageState)
        assertTrue(fixture.stateStore.current.recoveryEnvelopeReady)
        assertTrue(fixture.envelopeStore.hasEnvelope)
    }

    @Test
    fun corruptLinkedSelfProducedPackageIsWithdrawnForRegeneration() = runBlocking {
        val fixture = fixture().also { it.codec.completeFailure = IllegalArgumentException() }

        assertEquals(RestoredPackageIntakeResult.NoPackage, fixture.intake.inspect())
        assertFalse(fixture.eligible.exists())
        assertFalse(fixture.inbox.exists())
        assertEquals(1, fixture.codec.completeVerificationCount)
    }

    @Test
    fun unprovenInitialCandidateAuthenticationFailureIsPreservedAsRestoredInput() = runBlocking {
        val fixture = fixture(
            state = state(
                packageGeneration = null,
                packageState = "PREPARING",
                envelopeReady = false,
            ),
            storedEnvelope = null,
        ).also {
            it.codec.completeFailure = IllegalArgumentException("authentication mismatch")
        }
        val original = fixture.eligible.readBytes()

        assertEquals(
            RestoredPackageIntakeResult.Preserved(
                RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT,
            ),
            fixture.intake.inspect(),
        )
        assertFalse(fixture.eligible.exists())
        assertArrayEquals(original, fixture.inbox.readBytes())
    }

    @Test
    fun transientBootstrapAndCompleteReadIoRetainEligiblePackageForRetry() = runBlocking {
        val cases = listOf(
            "bootstrap" to fixture().also {
                it.codec.bootstrapFailure = java.io.IOException("transient bootstrap read")
            },
            "complete" to fixture().also {
                it.codec.completeFailure = java.io.IOException("transient complete read")
            },
        )

        cases.forEach { (label, fixture) ->
            val original = fixture.eligible.readBytes()
            val result = fixture.intake.inspect()

            assertFalse("$label must not report no package", result is RestoredPackageIntakeResult.NoPackage)
            assertFalse("$label must not quarantine as restored", result is RestoredPackageIntakeResult.Preserved)
            assertArrayEquals("$label eligible bytes", original, fixture.eligible.readBytes())
            assertFalse("$label inbox", fixture.inbox.exists())
        }
    }

    @Test
    fun missingExistingContentKeyKeepsLinkedEligiblePackageAndFailsClosed() = runBlocking {
        val fixture = fixture()
        val original = fixture.eligible.readBytes()
        fixture.keyStore.openFailure = IllegalStateException("local key alias is unavailable")

        assertEquals(
            RestoredPackageIntakeResult.PreservationBlocked,
            fixture.intake.inspect(),
        )
        assertArrayEquals(original, fixture.eligible.readBytes())
        assertFalse(fixture.inbox.exists())
        assertEquals(1, fixture.keyStore.openCount)
        assertEquals(0, fixture.codec.completeVerificationCount)
    }

    @Test
    fun unknownRestoredInputsMoveInertlyWithoutKeyOrCompleteVerification() {
        val cases = listOf(
            "missing-state" to fixture(state = null),
            "different-vault" to fixture(header = header(vaultId = "another-vault")),
            "different-envelope" to fixture(header = header(envelope = envelopePayload(9))),
            "future-generation" to fixture(header = header(generation = 8)),
            "incompatible-bootstrap" to fixture().also {
                it.codec.bootstrapFailure = IllegalArgumentException()
            },
            "invalid-envelope-bootstrap" to fixture(
                header = header(
                    envelope = envelopePayload(1).copy(saltBase64 = "AA"),
                ),
            ),
            "malformed-input" to fixture(fileBytes = byteArrayOf(1, 2, 3)).also {
                it.codec.bootstrapFailure = IllegalArgumentException()
            },
        )

        cases.forEach { (label, fixture) ->
            val original = fixture.eligible.readBytes()
            val result = runBlocking { fixture.intake.inspect() }

            assertTrue(label, result is RestoredPackageIntakeResult.Preserved)
            val condition = (result as RestoredPackageIntakeResult.Preserved).condition
            if (
                label == "incompatible-bootstrap" ||
                label == "invalid-envelope-bootstrap" ||
                label == "malformed-input"
            ) {
                assertEquals(label, RestoredPackageCondition.INCOMPATIBLE_OR_CORRUPT, condition)
            } else {
                assertEquals(label, RestoredPackageCondition.PRESERVED, condition)
            }
            assertFalse(label, fixture.eligible.exists())
            assertArrayEquals(label, original, fixture.inbox.readBytes())
            assertEquals(label, 0, fixture.keyStore.openCount)
            assertEquals(label, 0, fixture.codec.completeVerificationCount)
        }
    }

    @Test
    fun existingInboxIsDurablePreservedTruthAndLeavesBothInputsUntouched() = runBlocking {
        val fixture = fixture(header = header(vaultId = "another-vault"))
        val eligible = fixture.eligible.readBytes()
        checkNotNull(fixture.inbox.parentFile).mkdirs()
        fixture.inbox.writeBytes("existing-restored-input".toByteArray())
        val inbox = fixture.inbox.readBytes()

        assertEquals(
            RestoredPackageIntakeResult.Preserved(RestoredPackageCondition.PRESERVED),
            fixture.intake.inspect(),
        )
        assertArrayEquals(eligible, fixture.eligible.readBytes())
        assertArrayEquals(inbox, fixture.inbox.readBytes())
        assertEquals(0, fixture.keyStore.openCount)
    }

    @Test
    fun failedAtomicMoveBlocksPublicationAndLeavesEligibleInputUntouched() = runBlocking {
        val fixture = fixture(
            header = header(vaultId = "another-vault"),
            moveAtomicallyNoReplace = { _, _ -> false },
        )
        val original = fixture.eligible.readBytes()

        assertEquals(
            RestoredPackageIntakeResult.PreservationBlocked,
            fixture.intake.inspect(),
        )
        assertArrayEquals(original, fixture.eligible.readBytes())
        assertFalse(fixture.inbox.exists())
        assertEquals(0, fixture.keyStore.openCount)
        assertEquals(0, fixture.codec.completeVerificationCount)
    }

    @Test
    fun transientInitialCrashDatabaseCommitFailureRetainsEligiblePackageForRetry() = runBlocking {
        val fixture = fixture(
            state = state(
                packageGeneration = null,
                packageState = "PREPARING",
                envelopeReady = false,
            ),
            storedEnvelope = null,
        ).also {
            it.envelopeStore.initialCommitFailure =
                java.io.IOException("transient database commit")
        }
        val original = fixture.eligible.readBytes()

        assertEquals(
            RestoredPackageIntakeResult.RetryableFailure,
            fixture.intake.inspect(),
        )
        assertArrayEquals(original, fixture.eligible.readBytes())
        assertFalse(fixture.inbox.exists())
        assertEquals("PREPARING", fixture.stateStore.current.packageState)
        assertFalse(fixture.stateStore.current.recoveryEnvelopeReady)
    }

    @Test
    fun concurrentNoReplacePreservationNeverOverwritesInbox() {
        val directory = Files.createTempDirectory("restored-intake-race").toFile()
        val first = File(directory, "first.otb").also { it.writeText("first") }
        val second = File(directory, "second.otb").also { it.writeText("second") }
        val inbox = File(directory, "incoming.otb")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())

        listOf(first, second).forEach { source ->
            Thread {
                ready.countDown()
                start.await()
                results += SameFileSystemNoReplaceMover.move(source, inbox)
                done.countDown()
            }.start()
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))

        assertEquals(1, results.count { it })
        assertTrue(inbox.readText() in setOf("first", "second"))
        val remaining = listOf(first, second).filter(File::exists)
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().readText() in setOf("first", "second"))
        assertTrue(remaining.single().readText() != inbox.readText())
    }

    @Test
    fun failedSourceDeleteAfterAtomicNoReplaceCreatePreservesEligibleSource() {
        val directory = Files.createTempDirectory("restored-intake-delete").toFile()
        val source = File(directory, "eligible.otb").also { it.writeText("eligible") }
        val inbox = File(directory, "incoming.otb")
        val mover = SameFileSystemNoReplaceMover(
            deleteSource = { throw java.io.IOException("delete failed") },
        )

        assertFalse(mover.moveNoReplace(source, inbox))
        assertEquals("eligible", source.readText())
        assertEquals("eligible", inbox.readText())
    }

    private fun fixture(
        fileBytes: ByteArray? = "eligible-package".toByteArray(),
        state: BackupStateEntity? = state(),
        header: PortableBootstrapHeaderV1 = header(),
        storedEnvelope: VaultKeyEnvelope? = envelope(),
        moveAtomicallyNoReplace: ((File, File) -> Boolean)? = null,
    ): IntakeFixture {
        val directory = Files.createTempDirectory("restored-intake").toFile()
        val eligible = File(directory, "files/android_backup/open_tasks_portable_v1.otb")
        if (fileBytes != null) {
            checkNotNull(eligible.parentFile).mkdirs()
            eligible.writeBytes(fileBytes)
        }
        val inbox = File(directory, "no_backup/recovery/incoming_android_v1.otb")
        val stateStore = FakeStateStore(state)
        val envelopeStore = FakeEnvelopeStore(storedEnvelope, stateStore)
        val keyStore = RecordingKeyStore()
        val codec = FakePortableCodec(header)
        return IntakeFixture(
            eligible = eligible,
            inbox = inbox,
            stateStore = stateStore,
            envelopeStore = envelopeStore,
            keyStore = keyStore,
            codec = codec,
            intake = RestoredPackageIntake(
                vaultId = VAULT_ID,
                eligiblePackage = eligible,
                recoveryInbox = inbox,
                packageFile = TestPackageFile(eligible),
                stateStore = stateStore,
                envelopeStore = envelopeStore,
                contentKeyStore = keyStore,
                codec = codec,
                moveAtomicallyNoReplace = moveAtomicallyNoReplace
                    ?: SameFileSystemNoReplaceMover::move,
            ),
        )
    }

    private data class IntakeFixture(
        val eligible: File,
        val inbox: File,
        val stateStore: FakeStateStore,
        val envelopeStore: FakeEnvelopeStore,
        val keyStore: RecordingKeyStore,
        val codec: FakePortableCodec,
        val intake: RestoredPackageIntake,
    )

    private class FakeStateStore(initial: BackupStateEntity?) : BackupStateStore {
        private val flow = MutableStateFlow(initial ?: state())
        var current = initial ?: state()
        var available = initial != null
        var updateCount = 0

        override fun observe(vaultId: VaultId): Flow<BackupStateEntity> = flow

        override suspend fun get(vaultId: VaultId): BackupStateEntity? =
            current.takeIf { available }

        override suspend fun mutate(
            vaultId: VaultId,
            mutation: BackupStateMutation,
        ): BackupStateEntity? {
            if (!available || current.vaultId != vaultId.value) return null
            val updated = mutation.apply(current) ?: return null
            current = updated
            flow.value = updated
            updateCount += 1
            return updated
        }
    }

    private class FakeEnvelopeStore(
        initial: VaultKeyEnvelope?,
        private val stateStore: FakeStateStore,
    ) : RecoveryEnvelopeStore {
        private var value = initial
        var initialCommitFailure: Throwable? = null
        val hasEnvelope: Boolean
            get() = value != null

        override suspend fun get(vaultId: VaultId): VaultKeyEnvelope? = value?.copy(
            kdf = checkNotNull(value).kdf.copy(salt = checkNotNull(value).kdf.salt.copyOf()),
            nonce = checkNotNull(value).nonce.copyOf(),
            wrappedKeyset = checkNotNull(value).wrappedKeyset.copyOf(),
        )

        override suspend fun upsert(vaultId: VaultId, envelope: VaultKeyEnvelope) {
            value = envelope.copy(
                kdf = envelope.kdf.copy(salt = envelope.kdf.salt.copyOf()),
                nonce = envelope.nonce.copyOf(),
                wrappedKeyset = envelope.wrappedKeyset.copyOf(),
            )
        }

        override suspend fun delete(vaultId: VaultId) {
            value = null
        }

        override suspend fun commitInitial(
            vaultId: VaultId,
            envelope: VaultKeyEnvelope,
            published: VerifiedPortableBackup,
        ): BackupStateEntity? {
            initialCommitFailure?.let { throw it }
            if (value != null) return null
            val updated = stateStore.mutate(
                vaultId,
                BackupStateMutation { current ->
                    if (published.generation > current.currentGeneration) {
                        null
                    } else {
                        current.copy(
                            portablePackageGeneration = published.generation,
                            portablePackageBytes = published.totalPackageLength,
                            portablePackageProducedAtEpochMillis =
                                published.producedAtEpochMillis,
                            packageState = if (
                                published.generation == current.currentGeneration
                            ) {
                                "READY"
                            } else {
                                "UPDATE_PENDING"
                            },
                            failureCategory = null,
                            recoveryEnvelopeReady = true,
                        )
                    }
                },
            ) ?: return null
            upsert(vaultId, envelope)
            return updated
        }
    }

    private class RecordingKeyStore : VaultContentKeyStore {
        var openCount = 0
        var openFailure: Throwable? = null

        override fun getOrCreate(vaultId: VaultId): VaultKey = key()

        override fun openExisting(vaultId: VaultId): VaultKey {
            openCount += 1
            openFailure?.let { throw it }
            return key()
        }

        override fun replace(vaultId: VaultId, key: VaultKey) = Unit
        override fun delete(vaultId: VaultId) = Unit
    }

    private class FakePortableCodec(
        var header: PortableBootstrapHeaderV1,
    ) : PortablePackageCodec {
        var bootstrapFailure: Throwable? = null
        var completeFailure: Throwable? = null
        var completeVerificationCount = 0

        override fun encode(
            recoveryEnvelope: VaultKeyEnvelope,
            snapshot: app.opentasks.core.data.backup.BackupSnapshotPayloadV1,
            producedAtEpochMillis: Long,
            key: VaultKey,
        ): ByteArray = error("Intake never encodes")

        override fun readBootstrap(
            source: InputStream,
            totalLength: Long,
        ): PortableBootstrapHeaderV1 {
            bootstrapFailure?.let { throw it }
            return header
        }

        override fun verifyComplete(
            source: InputStream,
            totalLength: Long,
            key: VaultKey,
        ): VerifiedPortableBackup {
            completeVerificationCount += 1
            completeFailure?.let { throw it }
            return VerifiedPortableBackup(
                vaultId = header.vaultId,
                generation = header.generation,
                producedAtEpochMillis = header.producedAtEpochMillis,
                recoveryEnvelopeSha256 = envelopeDigest(header.recoveryEnvelope),
                totalPackageLength = totalLength,
            )
        }
    }

    private class TestPackageFile(private val file: File) : AtomicPackageFile {
        override fun startWrite(): OutputStream = ByteArrayOutputStream()
        override fun finishWrite(stream: OutputStream) = Unit
        override fun failWrite(stream: OutputStream) = Unit
        override fun openRead(): InputStream = ByteArrayInputStream(file.readBytes())
        override fun length(): Long = if (file.isFile) file.length() else 0
        override fun delete(): Boolean = !file.exists() || file.delete()
    }

    private companion object {
        val VAULT_ID = VaultId("vault-primary")

        fun state(
            packageGeneration: Long? = 7,
            packageState: String = "READY",
            envelopeReady: Boolean = true,
        ) = BackupStateEntity(
            vaultId = VAULT_ID.value,
            currentGeneration = 7,
            lastVerifiedSnapshotGeneration = 7,
            currentBaseObjectId = "snapshot:7",
            previousBaseObjectId = null,
            latestVerifiedSegmentGeneration = 7,
            portablePackageGeneration = packageGeneration,
            portablePackageBytes = if (packageGeneration == null) null else 16,
            portablePackageProducedAtEpochMillis = if (packageGeneration == null) null else 1234,
            packageState = packageState,
            failureCategory = null,
            recoveryEnvelopeReady = envelopeReady,
            legacyOutboxCoveredAtGeneration = 7,
            snapshotCreatedAtEpochMillis = 1234,
        )

        fun header(
            vaultId: String = VAULT_ID.value,
            generation: Long = 7,
            envelope: RecoveryEnvelopePayloadV1 = envelopePayload(1),
        ) = PortableBootstrapHeaderV1(
            vaultId = vaultId,
            generation = generation,
            producedAtEpochMillis = 1234,
            recoveryEnvelope = envelope,
            manifestFrameLength = 1,
            manifestFrameSha256 = "00".repeat(32),
            snapshotFrameLength = 1,
            snapshotFrameSha256 = "11".repeat(32),
            totalPackageLength = 16,
        )

        fun envelopePayload(seed: Int): RecoveryEnvelopePayloadV1 =
            RecoveryEnvelopeCodec.toPayload(envelope(seed))

        fun envelope(seed: Int = 1) = VaultKeyEnvelope(
            formatVersion = 1,
            kdf = Argon2Metadata(
                memoryKiB = 65_536,
                iterations = 3,
                parallelism = 1,
                salt = ByteArray(16) { seed.toByte() },
            ),
            nonce = ByteArray(12) { (seed + 1).toByte() },
            wrappedKeyset = ByteArray(32) { (seed + 2).toByte() },
        )

        fun key() = TinkVaultCrypto().createKey()

        fun envelopeDigest(payload: RecoveryEnvelopePayloadV1): String {
            val envelope = RecoveryEnvelopeCodec.fromPayload(payload)
            return try {
                val bytes = RecoveryEnvelopeCodec.encode(envelope)
                try {
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                } finally {
                    bytes.fill(0)
                }
            } finally {
                envelope.kdf.salt.fill(0)
                envelope.nonce.fill(0)
                envelope.wrappedKeyset.fill(0)
            }
        }
    }
}
