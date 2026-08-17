package app.opentasks.core.data.backup

import android.content.Context
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.data.AndroidVaultKeyManager
import app.opentasks.core.data.LocalVaultRepositoryFactory
import app.opentasks.core.data.LocalVaultRuntime
import app.opentasks.core.data.RetentionPurgeAccounting
import app.opentasks.core.data.VaultSlot
import app.opentasks.core.data.VerifiedStagedVault
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.TrashPolicy
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.VaultId
import java.time.Instant
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
    private val now: () -> Instant = Instant::now,
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

        // That repository purged expired trash as it opened, so the staged vault
        // above is no longer necessarily the staged vault below. Activation gets
        // what the slot actually holds now, and only after every difference has
        // been attributed to that purge.
        val settled = LocalVaultRepositoryFactory.openStagingDatabase(context, slot, keyManager)
        val outcome = try {
            verifyStorageIntegrity(settled)
            verifySettledState(settled, expectedVaultId, expectedGeneration, expectedCapture)
                .also { checkpoint(settled) }
        } finally {
            settled.close()
        }

        return VerifiedStagedVault(
            slot = slot,
            vaultId = expectedVaultId,
            recoveredGeneration = expectedGeneration,
            activationGeneration = outcome.generation,
            retentionPurge = outcome.retentionPurge,
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
        // Those reads are vault-scoped, so a row bound to another vault is
        // invisible to them and to every relation they resolve; an unscoped
        // total is the only thing that can exclude one.
        check(database.recoveryImportDao().structuredRecordCount() == actual.size) {
            "The staged vault holds a record outside the recovered vault"
        }
        check(actual.size == expectedCapture.records.size) {
            "The staged vault holds a different number of records"
        }
        check(
            actual.associateBy { BackupRecordKey(it) } ==
                expectedCapture.records.associateBy { BackupRecordKey(it) },
        ) {
            "The staged vault does not match the authenticated recovery"
        }
        verifyVaultStateRules(expectedVaultId, expectedCapture.generation, actual)
    }

    /**
     * Proves the staged records form a vault state Stage 2 would accept.
     *
     * Matching the authenticated recovery record for record still allows a set
     * no live vault could hold — an owner-less workspace, a workflow missing an
     * active semantic status, a task whose status belongs to another workflow.
     * The exhaustive rule set already lives in the snapshot codec, so this runs
     * that codec over the staged records rather than restating any rule here.
     */
    private fun verifyVaultStateRules(
        vaultId: VaultId,
        generation: BackupGeneration,
        records: List<BackupRecordV1>,
    ) {
        try {
            BackupSnapshotCodec.encode(
                BackupSnapshotPayloadV1(
                    vaultId = vaultId.value,
                    coveredGeneration = generation.value,
                    records = records,
                ),
            ).fill(0)
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException("The staged vault is not a valid vault state", failure)
        }
    }

    /**
     * Re-reads the staged vault once the runtime smoke check has released it.
     *
     * `RoomVaultRepository` purges retention-expired trash as it initialises,
     * which deletes records, appends journal entries, and advances the stored
     * generation — correct local-authority behaviour that a legitimately old
     * recovery must survive. Every difference from the verified capture is
     * therefore attributed to that purge, and anything else fails closed.
     */
    private suspend fun verifySettledState(
        database: VaultDatabase,
        expectedVaultId: VaultId,
        recoveredGeneration: BackupGeneration,
        expectedCapture: StructuredBackupCapture,
    ): SettledStagedVault {
        val importDao = database.recoveryImportDao()
        val actual = database.stagedRecords(expectedVaultId)
        check(importDao.structuredRecordCount() == actual.size) {
            "The staged vault holds a record outside the recovered vault"
        }
        check(importDao.localBackupStateCount() == 2) {
            "The staged vault no longer holds exactly one backup state and envelope"
        }
        val journalEntryCount = importDao.journalEntryCount()
        check(importDao.operationalRecordCount() == journalEntryCount) {
            "The staged vault holds operational rows no local purge could write"
        }

        val retentionPurge = accountForRetentionPurge(
            verified = expectedCapture.records,
            actual = actual,
            journalEntryCount = journalEntryCount,
            now = now,
        )
        val state = database.backupStateDao().get(expectedVaultId.value)
        checkNotNull(state) { "The staged vault holds no backup state" }
        val expectedGeneration = if (retentionPurge.purgedTaskCount == 0) {
            recoveredGeneration.value
        } else {
            // The purge runs as one transaction, so it allocates one generation.
            Math.addExact(recoveredGeneration.value, 1L)
        }
        check(state.currentGeneration == expectedGeneration) {
            "The staged vault advanced past its retention purge"
        }
        return SettledStagedVault(
            generation = BackupGeneration(state.currentGeneration),
            retentionPurge = retentionPurge,
        )
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
            capture.notes(id).mapTo(this) { it.toBackupRecordV1() }
            importDao.allRetiredBlobSets().mapTo(this) { it.toBackupRecordV1() }
            importDao.allTombstones().mapTo(this) { it.toBackupRecordV1() }
            capture.automationRules(id).mapTo(this) { it.toBackupRecordV1() }
            capture.myDayEntries(id).mapTo(this) { it.toBackupRecordV1() }
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

    /** The staged vault as it stands after the runtime smoke check. */
    private data class SettledStagedVault(
        val generation: BackupGeneration,
        val retentionPurge: RetentionPurgeAccounting,
    )

    private companion object {
        const val INTEGRITY_OK = "ok"
        const val REOPEN_TIMEOUT_MILLIS = 5_000L
    }
}

private const val TASK_OBJECT_TYPE = "task"

/**
 * Attributes every difference to expired trash, or fails closed.
 *
 * A record may only disappear as part of purging a task that was in the Bin
 * past retention, a record may only appear as the tombstone that purge
 * writes, as a surviving child that purge detached, or as the retired blob
 * set of a blob-bearing attachment that purge just removed, and journal
 * entries are only allowed to exist when they match.
 *
 * Pulled out of [DefaultStagedVaultVerifier] as a pure function of its
 * inputs (no Room, no SQLCipher, no Android API) so this accounting rule is
 * unit-testable on its own.
 */
internal fun accountForRetentionPurge(
    verified: List<BackupRecordV1>,
    actual: List<BackupRecordV1>,
    journalEntryCount: Int,
    now: () -> Instant,
): RetentionPurgeAccounting {
    val verifiedByKey = verified.associateBy { BackupRecordKey(it) }
    val actualByKey = actual.associateBy { BackupRecordKey(it) }
    check(actualByKey.size == actual.size) {
        "The staged vault holds a duplicate record identity"
    }

    val removed = verifiedByKey.keys - actualByKey.keys
    val written = actualByKey.keys.filterTo(mutableSetOf()) { key ->
        actualByKey.getValue(key) != verifiedByKey[key]
    }
    if (removed.isEmpty() && written.isEmpty()) {
        check(journalEntryCount == 0) {
            "The staged vault journalled a change it did not make"
        }
        return RetentionPurgeAccounting.NONE
    }

    val purgedTasks = removed
        .filter { it.family == BackupRecordFamily.TASK }
        .associate { key -> key.identity.single() to verifiedByKey.getValue(key) }
    purgedTasks.values.forEach { task ->
        val deletedAt = BackupRecordFields.of(task).nullableLong("deletedAtEpochMillis")
        check(
            deletedAt != null &&
                TrashPolicy.isEligibleForPurge(Instant.ofEpochMilli(deletedAt), now()),
        ) {
            "The staged vault lost a task no retention purge could remove"
        }
    }
    check(removed == retentionPurgeRemovals(verified, purgedTasks.keys)) {
        "The staged vault lost records beyond its retention purge"
    }

    val detachedChildren = verified.asSequence()
        .filter { it.family == BackupRecordFamily.TASK }
        .filter { it.identity.single() !in purgedTasks.keys }
        .filter {
            BackupRecordFields.of(it).nullableString("parentTaskId") in purgedTasks.keys
        }
        .associateBy { BackupRecordKey(it) }

    val tombstones = purgedTasks.keys.mapTo(mutableSetOf()) { taskId ->
        BackupRecordKey(BackupRecordFamily.TOMBSTONE, listOf(taskId, TASK_OBJECT_TYPE))
    }
    val purgedBlobSets = purgedAttachmentBlobSets(verified, purgedTasks.keys)
    val retiredBlobSets = purgedBlobSets.mapTo(mutableSetOf()) { it.key }
    check(
        written.all { it in tombstones || it in detachedChildren || it in retiredBlobSets },
    ) {
        "The staged vault gained a record its retention purge did not write"
    }
    tombstones.forEach { key ->
        val tombstone = actualByKey[key]
        checkNotNull(tombstone) { "The retention purge left a purged task untombstoned" }
        check(
            BackupRecordFields.of(tombstone).long("deletedAtEpochMillis") ==
                BackupRecordFields.of(purgedTasks.getValue(key.identity.first()))
                    .nullableLong("deletedAtEpochMillis"),
        ) {
            "A retention tombstone does not describe the task it replaced"
        }
    }
    detachedChildren.forEach { (key, before) ->
        val after = checkNotNull(actualByKey[key]) {
            "The retention purge removed a surviving direct child"
        }
        checkDetachedChild(before, after)
    }
    checkRetiredBlobSets(purgedBlobSets, actualByKey, purgedTasks, now)
    check(journalEntryCount == removed.size + written.size) {
        "The staged vault journalled changes beyond its retention purge"
    }
    return RetentionPurgeAccounting(
        purgedTaskCount = purgedTasks.size,
        removedRecordCount = removed.size,
        journalEntryCount = journalEntryCount,
    )
}

private fun checkDetachedChild(before: BackupRecordV1, after: BackupRecordV1) {
    check(before.family == BackupRecordFamily.TASK && after.family == before.family)
    check(after.identity == before.identity) {
        "The retention purge changed a surviving child identity"
    }
    val mutable = setOf(
        "parentTaskId",
        "revisionWallMillis",
        "revisionLogical",
        "revisionDeviceId",
    )
    check(
        before.fields.filterNot { it.name in mutable } ==
            after.fields.filterNot { it.name in mutable },
    ) {
        "The retention purge changed a surviving child beyond detaching it"
    }
    val previous = BackupRecordFields.of(before)
    val current = BackupRecordFields.of(after)
    check(current.nullableString("parentTaskId") == null) {
        "The retention purge left a surviving child attached"
    }
    check(current.long("revisionWallMillis") > previous.long("revisionWallMillis")) {
        "The retention purge did not advance a surviving child wall revision"
    }
    check(current.int("revisionLogical") == previous.int("revisionLogical") + 1) {
        "The retention purge did not advance a surviving child logical revision"
    }
    check(current.string("revisionDeviceId").isNotBlank()) {
        "The retention purge wrote an invalid surviving child device revision"
    }
}

/** The rows `VaultDatabase.purgeTask` removes for each expired task. */
private fun retentionPurgeRemovals(
    verified: List<BackupRecordV1>,
    taskIds: Set<String>,
): Set<BackupRecordKey> = verified.asSequence()
    .filter { record ->
        when (record.family) {
            BackupRecordFamily.TASK -> record.identity.single() in taskIds
            BackupRecordFamily.CHECKLIST_ITEM,
            BackupRecordFamily.REMINDER,
            BackupRecordFamily.ATTACHMENT,
            BackupRecordFamily.TIME_ENTRY,
            BackupRecordFamily.MY_DAY,
            -> BackupRecordFields.of(record).string("taskId") in taskIds
            BackupRecordFamily.ACTIVITY_ENTRY ->
                BackupRecordFields.of(record).nullableString("taskId")
                    ?.let { it in taskIds } == true
            BackupRecordFamily.NOTE ->
                BackupRecordFields.of(record).nullableString("taskId")
                    ?.let { it in taskIds } == true
            BackupRecordFamily.TASK_TAG -> record.identity.first() in taskIds
            BackupRecordFamily.TASK_DEPENDENCY -> record.identity.any { it in taskIds }
            else -> false
        }
    }
    .map { BackupRecordKey(it) }
    .toSet()

/** One blob-bearing attachment a retention purge removed, and the identity of the retired row it must leave behind. */
private data class PurgedAttachmentBlobSet(
    val key: BackupRecordKey,
    val attachment: BackupRecordV1,
    val ownerTaskId: String,
)

/**
 * The retired-blob-set identity a purge writes for each blob-bearing
 * attachment it removes.
 *
 * Derived from the purged attachments' own `blobSetId`, not reused from
 * [retentionPurgeRemovals]: a blob set is retired by that identity alone, so
 * this is the only fact that may license a new `RETIRED_BLOB_SET` row.
 */
private fun purgedAttachmentBlobSets(
    verified: List<BackupRecordV1>,
    purgedTaskIds: Set<String>,
): List<PurgedAttachmentBlobSet> = verified.asSequence()
    .filter { it.family == BackupRecordFamily.ATTACHMENT }
    .mapNotNull { attachment ->
        val fields = BackupRecordFields.of(attachment)
        val taskId = fields.string("taskId")
        val blobSetId = fields.nullableString("blobSetId")
        if (taskId !in purgedTaskIds || blobSetId == null) {
            null
        } else {
            PurgedAttachmentBlobSet(
                key = BackupRecordKey(BackupRecordFamily.RETIRED_BLOB_SET, listOf(blobSetId)),
                attachment = attachment,
                ownerTaskId = taskId,
            )
        }
    }
    .toList()

/**
 * Proves each retired blob set the purge is allowed to write actually
 * describes the attachment it replaced.
 *
 * The purge's exact wall-clock instant is not knowable from here — it runs
 * on the activating device's clock, not the verifier's — so this checks only
 * what is deterministic: the retired chunk count matches the purged
 * attachment's, the row carries no revision history (a retired blob set is
 * created once, by construction), its own two timestamp fields agree with
 * each other, it is not from the future relative to [now], it is late enough
 * that its owning task really was eligible for purge by then, and every
 * blob set retired by the one purge transaction shares one instant.
 */
private fun checkRetiredBlobSets(
    purgedBlobSets: List<PurgedAttachmentBlobSet>,
    actualByKey: Map<BackupRecordKey, BackupRecordV1>,
    purgedTasks: Map<String, BackupRecordV1>,
    now: () -> Instant,
) {
    val retiredInstants = mutableSetOf<Long>()
    purgedBlobSets.forEach { purged ->
        val retired = checkNotNull(actualByKey[purged.key]) {
            "The retention purge removed an attachment without retiring its blob set"
        }
        val attachmentFields = BackupRecordFields.of(purged.attachment)
        val retiredFields = BackupRecordFields.of(retired)
        check(retiredFields.int("chunkCount") == attachmentFields.int("chunkCount")) {
            "A retired blob set does not describe the attachment it replaced"
        }
        check(retiredFields.int("revisionLogical") == 0) {
            "A retention purge retired a blob set with a revised history"
        }
        val retiredAt = retiredFields.long("retiredAtEpochMillis")
        check(retiredAt == retiredFields.long("revisionWallMillis")) {
            "A retired blob set's retirement instant does not match its own revision"
        }
        check(retiredAt <= now().toEpochMilli()) {
            "A retired blob set's retirement instant is in the future"
        }
        val ownerDeletedAt = BackupRecordFields.of(purgedTasks.getValue(purged.ownerTaskId))
            .nullableLong("deletedAtEpochMillis")
        check(
            ownerDeletedAt != null &&
                TrashPolicy.isEligibleForPurge(
                    Instant.ofEpochMilli(ownerDeletedAt),
                    Instant.ofEpochMilli(retiredAt),
                ),
        ) {
            "A retired blob set predates its owning task's retention eligibility"
        }
        retiredInstants += retiredAt
    }
    check(retiredInstants.size <= 1) {
        "A single retention purge retired blob sets at different instants"
    }
}
