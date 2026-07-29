package app.opentasks.backup

import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.backup.BackupSnapshotPayloadV1
import app.opentasks.core.data.backup.BackupStateEntity
import app.opentasks.core.data.backup.BackupStateStore
import app.opentasks.core.data.backup.PortableBootstrapHeaderV1
import app.opentasks.core.data.backup.PortablePackageTooLargeException
import app.opentasks.core.data.backup.PortablePackageCodec
import app.opentasks.core.data.backup.RecoveryEnvelopeCodec
import app.opentasks.core.data.backup.RecoveryEnvelopeStore
import app.opentasks.core.data.backup.StructuredBackupCapture
import app.opentasks.core.data.backup.VerifiedPortableBackup
import app.opentasks.core.domain.BackupCaptureSource
import app.opentasks.core.model.AndroidBackupStatus
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.BackupUnavailableReason
import app.opentasks.core.model.VaultId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableBackupPublisherTest {
    @Test
    fun activeAndroidAtomicWriteReadsDotNewCandidateInsteadOfPriorBase() {
        val directory = Files.createTempDirectory("portable-atomic-candidate").toFile()
        try {
            val base = File(directory, "package.otb").also {
                it.writeBytes("prior".toByteArray())
            }
            File("${base.path}.new").writeBytes("candidate".toByteArray())
            val candidate = AndroidAtomicWriteCandidate(base)

            assertEquals("candidate".length.toLong(), candidate.length())
            assertEquals(
                "candidate",
                candidate.openRead().use { it.readBytes().toString(Charsets.UTF_8) },
            )
            assertFalse(candidate.wasCommitted("candidate".length.toLong()))
            assertTrue(base.delete())
            assertTrue(File("${base.path}.new").renameTo(base))
            assertTrue(candidate.wasCommitted("candidate".length.toLong()))
            File("${base.path}.new").writeBytes("leftover".toByteArray())
            assertFalse(candidate.wasCommitted("candidate".length.toLong()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun initialPreparationCommitsVerifiedFileBeforeEnvelopeAndReadyState() = runBlocking {
        val events = mutableListOf<String>()
        val stateStore = FakeStateStore(state())
        val envelopeStore = FakeEnvelopeStore(events)
        val file = FakeAtomicPackageFile(events = events)
        val publisher = publisher(
            stateStore = stateStore,
            envelopeStore = envelopeStore,
            file = file,
            events = events,
        )
        val passphrase = "correct horse battery staple".toCharArray()

        val status = try {
            publisher.prepare(passphrase)
        } finally {
            passphrase.fill('\u0000')
        }

        assertTrue(status is AndroidBackupStatus.Ready)
        assertArrayEquals("package:7".toByteArray(), file.finalBytes)
        assertEquals(
            listOf(
                "prepare-envelope",
                "capture",
                "encode",
                "start",
                "flush",
                "verify",
                "finish",
                "commit-initial",
            ),
            events,
        )
        assertTrue(envelopeStore.envelope != null)
        assertEquals("READY", stateStore.current.packageState)
        assertEquals(7L, stateStore.current.portablePackageGeneration)
        assertEquals("package:7".length.toLong(), stateStore.current.portablePackageBytes)
        assertTrue(stateStore.current.recoveryEnvelopeReady)
    }

    @Test
    fun refreshReusesStoredEnvelopeAndOlderCaptureCommitsUpdatePending() = runBlocking {
        val events = mutableListOf<String>()
        val stateStore = FakeStateStore(
            state(
                generation = 7,
                packageGeneration = 6,
                packageState = "UPDATE_PENDING",
                envelopeReady = true,
            ),
        )
        val envelopeStore = FakeEnvelopeStore(events, envelope())
        val file = FakeAtomicPackageFile("package:6".toByteArray(), events)
        file.afterFinish = {
            stateStore.current = stateStore.current.copy(currentGeneration = 8)
        }
        val publisher = publisher(
            stateStore = stateStore,
            envelopeStore = envelopeStore,
            file = file,
            events = events,
            failIfEnvelopePrepared = true,
        )

        val status = publisher.refresh()

        assertTrue(status is AndroidBackupStatus.UpdatePending)
        val info = (status as AndroidBackupStatus.UpdatePending).packageInfo
        assertEquals(BackupGeneration(7), info.packageGeneration)
        assertEquals(BackupGeneration(8), info.currentGeneration)
        assertArrayEquals("package:7".toByteArray(), file.finalBytes)
        assertEquals("UPDATE_PENDING", stateStore.current.packageState)
        assertEquals(7L, stateStore.current.portablePackageGeneration)
    }

    @Test
    fun transientEncodeVerificationWriteFlushAndFinishFailuresRetainPriorVerifiedFile() {
        val cases = listOf("encode", "verify", "write", "flush", "finish")
        cases.forEach { failurePoint ->
            val stateStore = FakeStateStore(
                state(
                    generation = 8,
                    packageGeneration = 7,
                    packageState = "UPDATE_PENDING",
                    envelopeReady = true,
                ),
            )
            val envelopeStore = FakeEnvelopeStore(mutableListOf(), envelope())
            val oldBytes = "package:7".toByteArray()
            val file = FakeAtomicPackageFile(oldBytes.copyOf())
            when (failurePoint) {
                "write" -> file.failWrite = true
                "flush" -> file.failFlush = true
                "finish" -> file.failFinish = true
            }
            val codec = FakePortableCodec().also {
                it.failEncode = failurePoint == "encode"
                it.failVerify = failurePoint == "verify"
            }
            val status = runBlocking {
                publisher(
                    stateStore = stateStore,
                    envelopeStore = envelopeStore,
                    file = file,
                    codec = codec,
                    captureGeneration = 8,
                ).refresh()
            }

            assertTrue("$failurePoint status", status is AndroidBackupStatus.UpdatePending)
            assertArrayEquals("$failurePoint file", oldBytes, file.finalBytes)
            assertEquals("UPDATE_PENDING", stateStore.current.packageState)
            assertEquals(7L, stateStore.current.portablePackageGeneration)
            assertTrue(
                stateStore.current.failureCategory in setOf(
                    "ENCODING_OR_CRYPTO",
                    "VERIFICATION_FAILED",
                    "FILE_IO",
                ),
            )
        }
    }

    @Test
    fun transientReconciliationKeyOrReadFailureRetainsPriorVerifiedFile() {
        listOf("key", "read").forEach { failurePoint ->
            val stateStore = FakeStateStore(
                state(
                    generation = 7,
                    packageGeneration = 7,
                    packageState = "READY",
                    envelopeReady = true,
                ),
            )
            val oldBytes = "package:7".toByteArray()
            val file = FakeAtomicPackageFile(oldBytes.copyOf()).also {
                it.failOpenReadAtCall = if (failurePoint == "read") 1 else null
            }
            val codec = FakePortableCodec().also { it.failEncode = true }

            val status = runBlocking {
                publisher(
                    stateStore = stateStore,
                    envelopeStore = FakeEnvelopeStore(mutableListOf(), envelope()),
                    file = file,
                    codec = codec,
                    contentKeyStore = if (failurePoint == "key") {
                        FailingKeyStore()
                    } else {
                        NewKeyStore()
                    },
                ).refresh()
            }

            assertTrue("$failurePoint status", status is AndroidBackupStatus.UpdatePending)
            assertArrayEquals("$failurePoint file", oldBytes, file.finalBytes)
            assertEquals(7L, stateStore.current.portablePackageGeneration)
            assertEquals("UPDATE_PENDING", stateStore.current.packageState)
            assertEquals("ENCODING_OR_CRYPTO", stateStore.current.failureCategory)
        }
    }

    @Test
    fun oversizedOutputWithdrawsEligibleFileAndPersistsOnlyBoundedReason() = runBlocking {
        val stateStore = FakeStateStore(
            state(
                generation = 8,
                packageGeneration = 7,
                packageState = "READY",
                envelopeReady = true,
            ),
        )
        val file = FakeAtomicPackageFile("package:7".toByteArray())
        val codec = FakePortableCodec().also {
            it.failTooLarge = true
        }

        val status = publisher(
            stateStore = stateStore,
            envelopeStore = FakeEnvelopeStore(mutableListOf(), envelope()),
            file = file,
            codec = codec,
            captureGeneration = 8,
        ).refresh()

        assertEquals(
            AndroidBackupStatus.Unavailable(BackupUnavailableReason.PACKAGE_TOO_LARGE),
            status,
        )
        assertNull(file.finalBytes)
        assertEquals("UNAVAILABLE", stateStore.current.packageState)
        assertEquals("PACKAGE_TOO_LARGE", stateStore.current.failureCategory)
        assertNull(stateStore.current.portablePackageGeneration)
        assertNull(stateStore.current.portablePackageBytes)
    }

    @Test
    fun initialDatabaseFailureAfterFileCommitDeletesNewFileAndLeavesSetupUnprepared() {
        val stateStore = FakeStateStore(state())
        val envelopeStore = FakeEnvelopeStore(mutableListOf()).also {
            it.failInitialCommit = true
        }
        val file = FakeAtomicPackageFile()
        val passphrase = "correct horse battery staple".toCharArray()

        val status = runBlocking {
            try {
                publisher(
                    stateStore = stateStore,
                    envelopeStore = envelopeStore,
                    file = file,
                ).prepare(passphrase)
            } finally {
                passphrase.fill('\u0000')
            }
        }

        assertEquals(
            AndroidBackupStatus.Unavailable(BackupUnavailableReason.FILE_IO),
            status,
        )
        assertNull(file.finalBytes)
        assertNull(envelopeStore.envelope)
        assertEquals("NOT_PREPARED", stateStore.current.packageState)
        assertFalse(stateStore.current.recoveryEnvelopeReady)
    }

    @Test
    fun initialStateReadFailureAfterFileCommitDeletesNewFileAndLeavesSetupUnprepared() {
        val stateStore = FakeStateStore(state()).also { it.failGetAtCall = 2 }
        val file = FakeAtomicPackageFile()

        val status = runBlocking {
            publisher(
                stateStore = stateStore,
                envelopeStore = FakeEnvelopeStore(mutableListOf()),
                file = file,
            ).prepare("passphrase".toCharArray())
        }

        assertEquals(
            AndroidBackupStatus.Unavailable(BackupUnavailableReason.FILE_IO),
            status,
        )
        assertNull(file.finalBytes)
        assertEquals("NOT_PREPARED", stateStore.current.packageState)
        assertFalse(stateStore.current.recoveryEnvelopeReady)
    }

    @Test
    fun initialCommitCancellationDeletesNewFileBeforeRethrowing() {
        val stateStore = FakeStateStore(state())
        val envelopeStore = FakeEnvelopeStore(mutableListOf()).also {
            it.cancelInitialCommit = true
        }
        val file = FakeAtomicPackageFile()

        assertThrows(CancellationException::class.java) {
            runBlocking {
                publisher(
                    stateStore = stateStore,
                    envelopeStore = envelopeStore,
                    file = file,
                ).prepare("passphrase".toCharArray())
            }
        }

        assertNull(file.finalBytes)
        assertNull(envelopeStore.envelope)
        assertEquals("NOT_PREPARED", stateStore.current.packageState)
        assertFalse(stateStore.current.recoveryEnvelopeReady)
    }

    @Test
    fun cancellationAfterInitialDatabaseCommitRetainsMatchingCommittedFileAndState() {
        val stateStore = FakeStateStore(state())
        val envelopeStore = FakeEnvelopeStore(mutableListOf()).also {
            it.cancelAfterInitialCommit = true
        }
        val file = FakeAtomicPackageFile()

        assertThrows(CancellationException::class.java) {
            runBlocking {
                publisher(
                    stateStore = stateStore,
                    envelopeStore = envelopeStore,
                    file = file,
                ).prepare("passphrase".toCharArray())
            }
        }

        assertArrayEquals("package:7".toByteArray(), file.finalBytes)
        assertTrue(envelopeStore.envelope != null)
        assertEquals("READY", stateStore.current.packageState)
        assertTrue(stateStore.current.recoveryEnvelopeReady)
    }

    @Test
    fun processDeathPreparingStateReconcilesValidFinalOrAtomicPriorBytes() = runBlocking {
        listOf(false, true).forEach { restorePrior ->
            val stateStore = FakeStateStore(
                state(
                    generation = 7,
                    packageGeneration = 7,
                    packageState = "PREPARING",
                    envelopeReady = true,
                ),
            )
            val file = FakeAtomicPackageFile("package:7".toByteArray())
            if (restorePrior) file.simulateInterruptedWrite("partial".toByteArray())
            val codec = FakePortableCodec()

            val status = publisher(
                stateStore = stateStore,
                envelopeStore = FakeEnvelopeStore(mutableListOf(), envelope()),
                file = file,
                codec = codec,
                failIfCaptured = true,
            ).refresh()

            assertTrue(status is AndroidBackupStatus.Ready)
            assertArrayEquals("package:7".toByteArray(), file.finalBytes)
            assertEquals("READY", stateStore.current.packageState)
            assertNull(stateStore.current.failureCategory)
        }
    }

    @Test
    fun refreshPersistsPreparingBeforeCapturingReplacementPackage() = runBlocking {
        val stateStore = FakeStateStore(
            state(
                generation = 8,
                packageGeneration = 7,
                packageState = "UPDATE_PENDING",
                envelopeReady = true,
            ),
        )
        var observedStateAtCapture: String? = null

        publisher(
            stateStore = stateStore,
            envelopeStore = FakeEnvelopeStore(mutableListOf(), envelope()),
            file = FakeAtomicPackageFile("package:7".toByteArray()),
            captureGeneration = 8,
            onCapture = { observedStateAtCapture = stateStore.current.packageState },
        ).refresh()

        assertEquals("PREPARING", observedStateAtCapture)
        assertEquals("READY", stateStore.current.packageState)
    }

    @Test
    fun processDeathPreparingStateReconcilesNewFinalGenerationOverPriorMetadata() = runBlocking {
        val stateStore = FakeStateStore(
            state(
                generation = 7,
                packageGeneration = 6,
                packageState = "PREPARING",
                envelopeReady = true,
            ),
        )
        val file = FakeAtomicPackageFile("package:7".toByteArray())

        val status = publisher(
            stateStore = stateStore,
            envelopeStore = FakeEnvelopeStore(mutableListOf(), envelope()),
            file = file,
            failIfCaptured = true,
        ).refresh()

        assertTrue(status is AndroidBackupStatus.Ready)
        assertArrayEquals("package:7".toByteArray(), file.finalBytes)
        assertEquals(7L, stateStore.current.portablePackageGeneration)
        assertEquals("READY", stateStore.current.packageState)
    }

    @Test
    fun processDeathPreparingStateWithoutPriorMetadataReconcilesNewFinalGeneration() =
        runBlocking {
            val stateStore = FakeStateStore(
                state(
                    generation = 7,
                    packageGeneration = null,
                    packageState = "PREPARING",
                    envelopeReady = true,
                ),
            )
            val file = FakeAtomicPackageFile("package:7".toByteArray())

            val status = publisher(
                stateStore = stateStore,
                envelopeStore = FakeEnvelopeStore(mutableListOf(), envelope()),
                file = file,
                failIfCaptured = true,
            ).refresh()

            assertTrue(status is AndroidBackupStatus.Ready)
            assertArrayEquals("package:7".toByteArray(), file.finalBytes)
            assertEquals(7L, stateStore.current.portablePackageGeneration)
            assertEquals("READY", stateStore.current.packageState)
        }

    @Test
    fun corruptSelfProducedOutputIsWithdrawnWithoutPersistingPrivateFailureDetails() = runBlocking {
        val stateStore = FakeStateStore(
            state(
                generation = 7,
                packageGeneration = 7,
                packageState = "PREPARING",
                envelopeReady = true,
            ),
        )
        val privateFailure = "task text /private/path checksum=abc ciphertext=deadbeef"
        val codec = FakePortableCodec().also {
            it.verificationFailure = IllegalArgumentException(privateFailure)
        }
        val file = FakeAtomicPackageFile("corrupt".toByteArray())

        val status = publisher(
            stateStore = stateStore,
            envelopeStore = FakeEnvelopeStore(mutableListOf(), envelope()),
            file = file,
            codec = codec,
            captureGeneration = 7,
        ).refresh()

        assertEquals(
            AndroidBackupStatus.Unavailable(BackupUnavailableReason.VERIFICATION_FAILED),
            status,
        )
        assertNull(file.finalBytes)
        val persistent = listOf(
            stateStore.current.packageState,
            stateStore.current.failureCategory,
        ).joinToString()
        assertFalse(persistent.contains("task text"))
        assertFalse(persistent.contains("/private/path"))
        assertFalse(persistent.contains("checksum"))
        assertFalse(persistent.contains("deadbeef"))
    }

    private fun publisher(
        stateStore: FakeStateStore,
        envelopeStore: FakeEnvelopeStore,
        file: FakeAtomicPackageFile,
        codec: FakePortableCodec = FakePortableCodec(),
        events: MutableList<String> = mutableListOf(),
        captureGeneration: Long = 7,
        failIfEnvelopePrepared: Boolean = false,
        failIfCaptured: Boolean = false,
        onCapture: () -> Unit = {},
        contentKeyStore: VaultContentKeyStore = NewKeyStore(),
    ): PortableBackupPublisher = PortableBackupPublisher(
        vaultId = VAULT_ID,
        captureSource = BackupCaptureSource {
            if (failIfCaptured) throw AssertionError("Valid final package must reconcile")
            events += "capture"
            onCapture()
            StructuredBackupCapture(
                vaultId = VAULT_ID,
                generation = BackupGeneration(captureGeneration),
                records = emptyList(),
            )
        },
        stateStore = stateStore,
        envelopeStore = envelopeStore,
        contentKeyStore = contentKeyStore,
        packageFile = file,
        codec = codec.also { it.events = events },
        prepareEnvelope = { _ ->
            if (failIfEnvelopePrepared) {
                throw AssertionError("Refresh must not request a passphrase envelope")
            }
            events += "prepare-envelope"
            val envelope = envelope()
            PreparedRecoveryEnvelope(envelope, RecoveryEnvelopeCodec.encode(envelope))
        },
        now = { Instant.ofEpochMilli(PRODUCED_AT) },
    ).also {
        envelopeStore.onInitialCommit = { stateStore.current = it }
    }

    private fun state(
        generation: Long = 7,
        packageGeneration: Long? = null,
        packageState: String = "NOT_PREPARED",
        envelopeReady: Boolean = false,
    ): BackupStateEntity = BackupStateEntity(
        vaultId = VAULT_ID.value,
        currentGeneration = generation,
        lastVerifiedSnapshotGeneration = null,
        currentBaseObjectId = null,
        previousBaseObjectId = null,
        latestVerifiedSegmentGeneration = null,
        portablePackageGeneration = packageGeneration,
        portablePackageBytes = packageGeneration?.let { "package:$it".length.toLong() },
        portablePackageProducedAtEpochMillis = packageGeneration?.let { PRODUCED_AT },
        packageState = packageState,
        failureCategory = null,
        recoveryEnvelopeReady = envelopeReady,
        legacyOutboxCoveredAtGeneration = null,
        snapshotCreatedAtEpochMillis = null,
    )

    private class FakeStateStore(
        initial: BackupStateEntity,
    ) : BackupStateStore {
        var current = initial
        var failGetAtCall: Int? = null
        private var getCalls = 0

        override fun observe(vaultId: VaultId): Flow<BackupStateEntity> =
            MutableStateFlow(current)

        override suspend fun get(vaultId: VaultId): BackupStateEntity {
            getCalls += 1
            if (getCalls == failGetAtCall) throw IllegalStateException("private database path")
            return current
        }

        override suspend fun compareAndUpdate(
            entity: BackupStateEntity,
            expectedCurrentGeneration: Long,
        ): Int {
            if (current.currentGeneration != expectedCurrentGeneration) return 0
            current = entity
            return 1
        }
    }

    private class FakeEnvelopeStore(
        private val events: MutableList<String>,
        initial: VaultKeyEnvelope? = null,
    ) : RecoveryEnvelopeStore {
        var envelope = initial?.copyEnvelope()
        var failInitialCommit = false
        var cancelInitialCommit = false
        var cancelAfterInitialCommit = false
        var onInitialCommit: (BackupStateEntity) -> Unit = {}

        override suspend fun get(vaultId: VaultId): VaultKeyEnvelope? = envelope?.copyEnvelope()

        override suspend fun upsert(vaultId: VaultId, envelope: VaultKeyEnvelope) {
            this.envelope = envelope.copyEnvelope()
        }

        override suspend fun delete(vaultId: VaultId) {
            envelope?.clear()
            envelope = null
        }

        override suspend fun commitInitial(
            vaultId: VaultId,
            envelope: VaultKeyEnvelope,
            state: BackupStateEntity,
            expectedCurrentGeneration: Long,
        ): Boolean {
            events += "commit-initial"
            if (cancelInitialCommit) throw CancellationException("cancelled")
            if (failInitialCommit) return false
            this.envelope = envelope.copyEnvelope()
            onInitialCommit(state)
            if (cancelAfterInitialCommit) throw CancellationException("cancelled after commit")
            return true
        }
    }

    private class NewKeyStore : VaultContentKeyStore {
        private val crypto = TinkVaultCrypto()

        override fun getOrCreate(vaultId: VaultId): VaultKey =
            throw AssertionError("Publisher must not bootstrap the content key")

        override fun openExisting(vaultId: VaultId): VaultKey = crypto.createKey()

        override fun replace(vaultId: VaultId, key: VaultKey) =
            throw AssertionError("Publisher must not replace the content key")

        override fun delete(vaultId: VaultId) =
            throw AssertionError("Publisher must not delete the content key")
    }

    private class FailingKeyStore : VaultContentKeyStore {
        override fun getOrCreate(vaultId: VaultId): VaultKey =
            throw AssertionError("Publisher must not bootstrap the content key")

        override fun openExisting(vaultId: VaultId): VaultKey =
            throw IllegalStateException("private key-store path")

        override fun replace(vaultId: VaultId, key: VaultKey) =
            throw AssertionError("Publisher must not replace the content key")

        override fun delete(vaultId: VaultId) =
            throw AssertionError("Publisher must not delete the content key")
    }

    private class FakePortableCodec : PortablePackageCodec {
        var events: MutableList<String> = mutableListOf()
        var encodedBytes: ByteArray? = null
        var failEncode = false
        var failTooLarge = false
        var failVerify = false
        var verificationFailure: Throwable? = null

        override fun encode(
            recoveryEnvelope: VaultKeyEnvelope,
            snapshot: BackupSnapshotPayloadV1,
            producedAtEpochMillis: Long,
            key: VaultKey,
        ): ByteArray {
            events += "encode"
            if (failTooLarge) throw PortablePackageTooLargeException()
            if (failEncode) throw IllegalStateException("private encode failure")
            return encodedBytes?.copyOf()
                ?: "package:${snapshot.coveredGeneration}".toByteArray()
        }

        override fun readBootstrap(
            source: InputStream,
            totalLength: Long,
        ): PortableBootstrapHeaderV1 =
            throw AssertionError("Publisher uses complete verification")

        override fun verifyComplete(
            source: InputStream,
            totalLength: Long,
            key: VaultKey,
        ): VerifiedPortableBackup {
            events += "verify"
            verificationFailure?.let { throw it }
            if (failVerify) throw IllegalArgumentException("private verification failure")
            val text = source.readBytes().toString(Charsets.UTF_8)
            val generation = text.substringAfter("package:").toLong()
            return VerifiedPortableBackup(
                vaultId = VAULT_ID.value,
                generation = generation,
                producedAtEpochMillis = PRODUCED_AT,
                recoveryEnvelopeSha256 = envelopeDigest(envelope()),
                totalPackageLength = totalLength,
            )
        }
    }

    private class FakeAtomicPackageFile(
        initial: ByteArray? = null,
        private val events: MutableList<String> = mutableListOf(),
    ) : AtomicPackageFile {
        var finalBytes: ByteArray? = initial
            private set
        var failWrite = false
        var failFlush = false
        var failFinish = false
        var failOpenReadAtCall: Int? = null
        var afterFinish: () -> Unit = {}
        private var prior: ByteArray? = null
        private var active: CapturingOutput? = null
        private var interrupted = false
        private var openReadCalls = 0

        override fun startWrite(): OutputStream {
            events += "start"
            prior = finalBytes?.copyOf()
            interrupted = false
            return CapturingOutput().also { active = it }
        }

        override fun finishWrite(stream: OutputStream) {
            events += "finish"
            if (failFinish) throw IOException("private finish path")
            finalBytes = checkNotNull(active).bytes()
            active = null
            prior = null
            afterFinish()
        }

        override fun failWrite(stream: OutputStream) {
            finalBytes = prior
            active = null
            prior = null
        }

        override fun openRead(): InputStream {
            openReadCalls += 1
            if (openReadCalls == failOpenReadAtCall) throw IOException("private read path")
            if (interrupted) {
                finalBytes = prior
                prior = null
                active = null
                interrupted = false
            }
            val bytes = active?.bytes() ?: finalBytes ?: throw IOException("missing")
            return ByteArrayInputStream(bytes)
        }

        override fun length(): Long = (active?.bytes() ?: finalBytes)?.size?.toLong() ?: 0

        override fun delete(): Boolean {
            finalBytes = null
            active = null
            prior = null
            return true
        }

        fun simulateInterruptedWrite(partial: ByteArray) {
            prior = finalBytes?.copyOf()
            active = CapturingOutput().also { it.write(partial) }
            interrupted = true
        }

        private inner class CapturingOutput : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (failWrite) throw IOException("private write path")
                super.write(bytes, offset, length)
            }

            override fun flush() {
                events += "flush"
                if (failFlush) throw IOException("private flush path")
                super.flush()
            }

            fun bytes(): ByteArray = toByteArray()
        }
    }

    private companion object {
        val VAULT_ID = VaultId("vault-alpha")
        const val PRODUCED_AT = 1_754_000_000_000L

        fun envelope(): VaultKeyEnvelope = VaultKeyEnvelope(
            formatVersion = 1,
            kdf = Argon2Metadata(ByteArray(16) { it.toByte() }),
            nonce = ByteArray(12) { (it + 16).toByte() },
            wrappedKeyset = ByteArray(8) { (it + 28).toByte() },
        )

        fun envelopeDigest(envelope: VaultKeyEnvelope): String {
            val bytes = RecoveryEnvelopeCodec.encode(envelope)
            return try {
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
            } finally {
                bytes.fill(0)
                envelope.clear()
            }
        }

        fun VaultKeyEnvelope.copyEnvelope(): VaultKeyEnvelope = VaultKeyEnvelope(
            formatVersion = formatVersion,
            kdf = kdf.copy(salt = kdf.salt.copyOf()),
            nonce = nonce.copyOf(),
            wrappedKeyset = wrappedKeyset.copyOf(),
        )

        fun VaultKeyEnvelope.clear() {
            kdf.salt.fill(0)
            nonce.fill(0)
            wrappedKeyset.fill(0)
        }
    }
}
