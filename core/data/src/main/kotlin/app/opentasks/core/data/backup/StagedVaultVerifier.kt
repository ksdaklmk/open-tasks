package app.opentasks.core.data.backup

import android.content.Context
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.data.AndroidVaultKeyManager
import app.opentasks.core.data.LocalVaultRepositoryFactory
import app.opentasks.core.data.LocalVaultRuntime
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.data.VerifiedStagedVault
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.VaultId
import kotlinx.coroutines.withTimeout

/**
 * Proves an inactive staging slot before anything may activate it.
 *
 * Activation from [app.opentasks.core.data.VaultRuntimeState.NoVault] has no
 * prior slot to fall back to and no prior content key to compare against, so
 * this verifier — not the interrupted-cleanup path — is what stands between a
 * partially rebuilt staging database and a published vault.
 */
internal interface StagedVaultVerifier {
    suspend fun verify(
        slot: VaultSlot,
        expectedVaultId: VaultId,
        expectedGeneration: BackupGeneration,
        expectedCapture: StructuredBackupCapture,
    ): VerifiedStagedVault
}

internal class DefaultStagedVaultVerifier(
    context: Context,
    private val crypto: VaultCrypto,
    private val keyManager: AndroidVaultKeyManager = AndroidVaultKeyManager(context),
) : StagedVaultVerifier {
    private val context = context.applicationContext

    override suspend fun verify(
        slot: VaultSlot,
        expectedVaultId: VaultId,
        expectedGeneration: BackupGeneration,
        expectedCapture: StructuredBackupCapture,
    ): VerifiedStagedVault {
        require(expectedCapture.vaultId == expectedVaultId) {
            "The expected capture describes another vault"
        }
        require(expectedCapture.generation == expectedGeneration) {
            "The expected capture describes another generation"
        }

        val database = LocalVaultRepositoryFactory.openStagingDatabase(context, slot, keyManager)
        try {
            verifyStorageIntegrity(database)
            verifyFreshOperationalState(database, expectedVaultId, expectedGeneration)
            verifyRecords(database, expectedVaultId, expectedCapture)
            checkpoint(database)
        } finally {
            database.close()
        }

        val runtime = LocalVaultRepositoryFactory.openRuntime(context, slot, crypto, keyManager)
        try {
            check(runtime.vaultId == expectedVaultId) {
                "The reopened staging slot holds another vault"
            }
            // The database key and the content key are wrapped independently, so
            // a staging database that opens can still be unable to read the
            // records it holds.
            runtime.contentKeyStore.openExisting(expectedVaultId).close()
            verifyReopenedWorkspace(runtime, expectedCapture)
        } finally {
            runtime.close()
        }

        return VerifiedStagedVault(
            slot = slot,
            vaultId = expectedVaultId,
            recoveredGeneration = expectedGeneration,
        )
    }

    private fun verifyStorageIntegrity(database: VaultDatabase) {
        val readable = database.openHelper.readableDatabase
        readable.query("PRAGMA foreign_key_check").use { cursor ->
            check(cursor.count == 0) { "The staged vault violates a declared foreign key" }
        }
        readable.query("PRAGMA integrity_check").use { cursor ->
            check(cursor.moveToFirst()) { "The staged vault reported no integrity result" }
            check(cursor.getString(0) == INTEGRITY_OK) {
                "The staged vault failed its integrity check"
            }
            check(!cursor.moveToNext()) { "The staged vault failed its integrity check" }
        }
    }

    /**
     * Requires the recovered generation and nothing the source device owned.
     *
     * A recovered vault carries no journal, no legacy outbox, no remote binding,
     * and no former publication checkpoint, so its next remote publication has
     * to start from a fresh complete Stage 2 baseline.
     */
    private suspend fun verifyFreshOperationalState(
        database: VaultDatabase,
        expectedVaultId: VaultId,
        expectedGeneration: BackupGeneration,
    ) {
        val importDao = database.recoveryImportDao()
        check(importDao.operationalRecordCount() == 0) {
            "The staged vault holds operational rows"
        }
        check(importDao.danglingReferenceCount() == 0) {
            "The staged vault holds an unresolved reference"
        }
        check(importDao.localBackupStateCount() == 2) {
            "The staged vault does not hold exactly one backup state and envelope"
        }

        val state = database.backupStateDao().get(expectedVaultId.value)
        checkNotNull(state) { "The staged vault holds no backup state" }
        check(state.currentGeneration == expectedGeneration.value) {
            "The staged vault is not at the recovered generation"
        }
        check(
            state.lastVerifiedSnapshotGeneration == null &&
                state.currentBaseObjectId == null &&
                state.previousBaseObjectId == null &&
                state.latestVerifiedSegmentGeneration == null &&
                state.portablePackageGeneration == null &&
                state.portablePackageBytes == null &&
                state.portablePackageProducedAtEpochMillis == null &&
                state.failureCategory == null &&
                state.legacyOutboxCoveredAtGeneration == null &&
                state.snapshotCreatedAtEpochMillis == null,
        ) {
            "The staged vault inherited former backup checkpoints"
        }
        check(state.packageState == NOT_PREPARED_PACKAGE_STATE) {
            "The staged vault does not require a fresh baseline"
        }

        val envelope = RoomRecoveryEnvelopeStore(database).get(expectedVaultId)
        try {
            checkNotNull(envelope) { "The staged vault holds no recovery envelope" }
        } finally {
            envelope?.kdf?.salt?.fill(0)
            envelope?.nonce?.fill(0)
            envelope?.wrappedKeyset?.fill(0)
        }
    }

    private suspend fun verifyRecords(
        database: VaultDatabase,
        expectedVaultId: VaultId,
        expectedCapture: StructuredBackupCapture,
    ) {
        val actual = database.stagedRecords(expectedVaultId)
        check(actual.size == expectedCapture.records.size) {
            "The staged vault holds a different number of records"
        }
        check(
            actual.associateBy { BackupRecordKey(it) } ==
                expectedCapture.records.associateBy { BackupRecordKey(it) },
        ) {
            "The staged vault does not match the authenticated recovery"
        }
    }

    /**
     * Reads the staged vault with Stage 2 capture semantics.
     *
     * Capture attributes relationless activity entries and tombstones through
     * `backup_journal` evidence, which a recovered vault deliberately does not
     * carry; those two families are therefore read directly, and the staging
     * database is separately proved to hold exactly one vault.
     */
    private suspend fun VaultDatabase.stagedRecords(
        vaultId: VaultId,
    ): List<BackupRecordV1> {
        val capture = backupCaptureDao()
        val importDao = recoveryImportDao()
        val id = vaultId.value
        check(capture.crossVaultTaskDependencyCount(id) == 0) {
            "A staged task dependency crosses vaults"
        }
        check(capture.crossVaultTaskTagCount(id) == 0) { "A staged task tag crosses vaults" }
        check(capture.ambiguousInboxWorkflowStatusCount(id) == 0) {
            "The staged inbox workflow cannot be assigned to one workspace"
        }
        return buildList {
            capture.vaults(id).mapTo(this) { it.toBackupRecordV1() }
            capture.workspaces(id).mapTo(this) { it.toBackupRecordV1() }
            capture.members(id).mapTo(this) { it.toBackupRecordV1() }
            capture.projects(id).mapTo(this) { it.toBackupRecordV1() }
            capture.workflowStatuses(id).mapTo(this) { it.toBackupRecordV1() }
            capture.milestones(id).mapTo(this) { it.toBackupRecordV1() }
            capture.tasks(id).mapTo(this) { it.toBackupRecordV1() }
            capture.checklistItems(id).mapTo(this) { it.toBackupRecordV1() }
            capture.taskDependencies(id).mapTo(this) { it.toBackupRecordV1() }
            capture.tags(id).mapTo(this) { it.toBackupRecordV1() }
            capture.taskTags(id).mapTo(this) { it.toBackupRecordV1() }
            capture.reminders(id).mapTo(this) { it.toBackupRecordV1() }
            capture.attachments(id).mapTo(this) { it.toBackupRecordV1() }
            importDao.allActivityEntries().mapTo(this) { it.toBackupRecordV1() }
            capture.timeEntries(id).mapTo(this) { it.toBackupRecordV1() }
            capture.templates(id).mapTo(this) { it.toBackupRecordV1() }
            capture.savedViews(id).mapTo(this) { it.toBackupRecordV1() }
            importDao.allTombstones().mapTo(this) { it.toBackupRecordV1() }
        }
    }

    /**
     * Proves the reopened slot serves the recovered vault and nothing else.
     *
     * A repository built over an empty vault seeds the shipped fixture, so a
     * workspace holding anything the recovery did not authenticate means the
     * slot was seeded rather than recovered.
     */
    private suspend fun verifyReopenedWorkspace(
        runtime: LocalVaultRuntime,
        expectedCapture: StructuredBackupCapture,
    ) {
        val workspace = withTimeout(REOPEN_TIMEOUT_MILLIS) {
            runtime.repository.currentWorkspace()
        }
        val recoveredTasks = expectedCapture.identitiesOf(BackupRecordFamily.TASK)
        val recoveredProjects = expectedCapture.identitiesOf(BackupRecordFamily.PROJECT)
        check(workspace.tasks.all { it.id.value in recoveredTasks }) {
            "The reopened staging slot was seeded instead of recovered"
        }
        check(workspace.projects.all { it.id.value in recoveredProjects }) {
            "The reopened staging slot was seeded instead of recovered"
        }
    }

    private fun checkpoint(database: VaultDatabase) {
        database.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { cursor -> cursor.moveToFirst() }
    }

    private fun StructuredBackupCapture.identitiesOf(
        family: BackupRecordFamily,
    ): Set<String> = records.asSequence()
        .filter { it.family == family }
        .map { it.identity.single() }
        .toSet()

    private companion object {
        const val INTEGRITY_OK = "ok"
        const val REOPEN_TIMEOUT_MILLIS = 5_000L
    }
}
