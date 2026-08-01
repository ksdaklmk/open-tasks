package app.opentasks.core.data.backup

import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.data.AuthenticatedCloudObjectCodec
import app.opentasks.core.data.CloudDecodeResult
import app.opentasks.core.domain.BackupCoordinator
import app.opentasks.core.domain.BackupPolicy
import app.opentasks.core.domain.BackupCaptureSource
import app.opentasks.core.model.VaultId
import app.opentasks.core.sync.CloudHeaderIdentity
import app.opentasks.core.sync.CloudObjectFamily
import java.io.FileInputStream
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

fun interface BackupCoordinatorLifecycleBoundary {
    suspend fun beforeOwnershipRelease()
}

internal object NoOpBackupCoordinatorLifecycleBoundary : BackupCoordinatorLifecycleBoundary {
    override suspend fun beforeOwnershipRelease() = Unit
}

/** Testable suspension point after a durable candidate exists, before verification. */
fun interface BackupCandidateLifecycleBoundary {
    suspend fun afterCandidateWritten()
}

internal object NoOpBackupCandidateLifecycleBoundary : BackupCandidateLifecycleBoundary {
    override suspend fun afterCandidateWritten() = Unit
}

class DefaultBackupCoordinator(
    private val vaultId: VaultId,
    private val captureSource: BackupCaptureSource<StructuredBackupCapture>,
    private val stateStore: BackupStateStore,
    private val journalStore: BackupJournalStore,
    private val objectStore: LocalBackupObjectStore,
    private val authenticatedCodec: AuthenticatedCloudObjectCodec,
    private val contentKeyStore: VaultContentKeyStore,
    private val snapshotCodec: BackupSnapshotCodec = BackupSnapshotCodec,
    private val segmentCodec: BackupOperationSegmentCodec = BackupOperationSegmentCodec,
    private val now: () -> Instant = Instant::now,
    private val lifecycleBoundary: BackupCoordinatorLifecycleBoundary =
        NoOpBackupCoordinatorLifecycleBoundary,
    private val candidateLifecycleBoundary: BackupCandidateLifecycleBoundary =
        NoOpBackupCandidateLifecycleBoundary,
) : BackupCoordinator {
    private val mutex = Mutex()
    private var running = false
    private var pending = false
    private var completeSnapshotPending = false
    private var completion: CompletableDeferred<Unit>? = null

    override suspend fun request() = request(completeSnapshot = false)

    override suspend fun requestCompleteSnapshot() = request(completeSnapshot = true)

    private suspend fun request(completeSnapshot: Boolean) {
        var activeCompletion: CompletableDeferred<Unit>? = null
        mutex.withLock {
            if (completeSnapshot) completeSnapshotPending = true
            if (running) {
                pending = true
                activeCompletion = checkNotNull(completion)
            } else {
                running = true
                completion = CompletableDeferred()
            }
        }
        if (activeCompletion != null) {
            // A joined caller must observe owner cancellation/failure and may
            // retry, rather than silently losing its coalesced request.
            activeCompletion.await()
            return
        }
        try {
            while (true) {
                val forceCompleteSnapshot = mutex.withLock {
                    completeSnapshotPending.also { completeSnapshotPending = false }
                }
                val needsFollowUp = produceRequiredObjects(forceCompleteSnapshot)
                lifecycleBoundary.beforeOwnershipRelease()
                var completed: CompletableDeferred<Unit>? = null
                val runAgain = mutex.withLock {
                    if (pending || needsFollowUp || completeSnapshotPending) {
                        pending = false
                        true
                    } else {
                        // Checking for pending work and releasing ownership is
                        // one transition, so a concurrent request either joins
                        // this pass or becomes the next owner.
                        running = false
                        completed = completion
                        completion = null
                        false
                    }
                }
                if (!runAgain) {
                    completed?.complete(Unit)
                    return
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                val completed = mutex.withLock {
                    running = false
                    pending = false
                    completion.also { completion = null }
                }
                completed?.completeExceptionally(failure)
            }
            throw failure
        }
    }

    private suspend fun produceRequiredObjects(forceCompleteSnapshot: Boolean): Boolean {
        val capture = captureSource.capture()
        require(capture.vaultId == vaultId) { "Backup capture belongs to another vault" }
        val state = checkNotNull(stateStore.get(vaultId)) { "Backup state is unavailable" }
        val lastSnapshotGeneration = state.lastVerifiedSnapshotGeneration
        val baseOrAgeRequiresSnapshot = state.currentBaseObjectId == null ||
            lastSnapshotGeneration == null ||
            BackupPolicy.requiresSnapshot(
                operationsSinceBase = 0,
                baseProducedAt = Instant.ofEpochMilli(
                    state.snapshotCreatedAtEpochMillis ?: 0L,
                ),
                now = now(),
            )
        val capturedOperationCount = if (baseOrAgeRequiresSnapshot) {
            0
        } else {
            journalStore.countThrough(
                vaultId = vaultId,
                afterGeneration = lastSnapshotGeneration,
                throughGeneration = capture.generation.value,
                limit = BackupPolicy.SNAPSHOT_OPERATION_INTERVAL,
            )
        }
        val snapshotRequired = forceCompleteSnapshot || baseOrAgeRequiresSnapshot ||
            BackupPolicy.requiresSnapshot(
                operationsSinceBase = capturedOperationCount,
                baseProducedAt = Instant.ofEpochMilli(
                    checkNotNull(state.snapshotCreatedAtEpochMillis),
                ),
                now = now(),
            )
        return if (snapshotRequired) {
            produceSnapshot(capture, state)
        } else {
            val after = state.latestVerifiedSegmentGeneration ?: lastSnapshotGeneration
            val entries = journalStore.between(vaultId, after, capture.generation.value)
            val groups = segmentGroups(entries.filter { it.payloadFormatVersion == 1 })
            if (groups == null) {
                // A generation is the smallest legal segment unit: splitting it
                // would collide with the `segment:first:last` object identity.
                produceSnapshot(capture, state)
            } else {
                produceSegments(capture, state, groups)
            }
        }
    }

    private suspend fun produceSnapshot(
        capture: StructuredBackupCapture,
        state: BackupStateEntity,
    ): Boolean {
        val objectId = "snapshot:${capture.generation.value}"
        val identity = identity(CloudObjectFamily.SNAPSHOT, objectId)
        val key = contentKeyStore.openExisting(vaultId)
        try {
            val plaintext = snapshotCodec.encode(snapshotCodec.fromCapture(capture))
            val frame = try {
                authenticatedCodec.encrypt(identity, plaintext, key)
            } finally {
                plaintext.fill(0)
            }
            try {
                val candidate = objectStore.writeCandidate(objectId, frame)
                try {
                    candidateLifecycleBoundary.afterCandidateWritten()
                    verifySnapshot(candidate, identity, key, capture)
                    objectStore.commitSnapshot(candidate, state.currentBaseObjectId)
                } finally {
                    candidate.file.delete()
                }
            } finally {
                frame.fill(0)
            }
        } finally {
            key.close()
        }
        val updated = checkNotNull(
            stateStore.mutate(
                vaultId,
                BackupStateMutation { latest ->
                    latest.copy(
                        lastVerifiedSnapshotGeneration = capture.generation.value,
                        currentBaseObjectId = objectId,
                        previousBaseObjectId = state.currentBaseObjectId,
                        latestVerifiedSegmentGeneration = capture.generation.value,
                        legacyOutboxCoveredAtGeneration = capture.generation.value,
                        snapshotCreatedAtEpochMillis = now().toEpochMilli(),
                    )
                },
            ),
        ) { "Backup state is unavailable during snapshot checkpoint" }
        objectStore.prune(
            setOfNotNull(updated.currentBaseObjectId, updated.previousBaseObjectId),
        )
        return updated.currentGeneration != capture.generation.value
    }

    private suspend fun produceSegments(
        capture: StructuredBackupCapture,
        initialState: BackupStateEntity,
        groups: List<List<BackupJournalEntity>>,
    ): Boolean {
        var state = initialState
        if (groups.isEmpty()) return state.currentGeneration != capture.generation.value
        groups.forEach { chunk ->
            require(chunk.zipWithNext().all { (left, right) ->
                left.generation < right.generation ||
                    (left.generation == right.generation && left.sequence < right.sequence)
            }) { "Backup journal entries are not ordered" }
            val payload = segmentCodec.fromJournalEntries(vaultId, chunk)
            val objectId = BackupPayloadIdentities.segmentObjectId(
                app.opentasks.core.model.BackupGeneration(payload.firstGeneration),
                app.opentasks.core.model.BackupGeneration(payload.lastGeneration),
            )
            val identity = identity(CloudObjectFamily.OPERATION_SEGMENT, objectId)
            val key = contentKeyStore.openExisting(vaultId)
            try {
                val plaintext = segmentCodec.encode(payload)
                val frame = try {
                    authenticatedCodec.encrypt(identity, plaintext, key)
                } finally {
                    plaintext.fill(0)
                }
                try {
                    val candidate = objectStore.writeCandidate(objectId, frame)
                    try {
                        candidateLifecycleBoundary.afterCandidateWritten()
                        verifySegment(candidate, identity, key, payload)
                        objectStore.commitSegment(candidate)
                    } finally {
                        candidate.file.delete()
                    }
                } finally {
                    frame.fill(0)
                }
            } finally {
                key.close()
            }
            val updated = checkNotNull(
                stateStore.mutate(
                    vaultId,
                    BackupStateMutation { latest ->
                        latest.copy(
                            latestVerifiedSegmentGeneration = payload.lastGeneration,
                        )
                    },
                ),
            ) { "Backup state is unavailable during segment checkpoint" }
            state = updated
        }
        return state.currentGeneration != capture.generation.value
    }

    /** Returns null when one complete generation cannot fit in a segment. */
    private fun segmentGroups(
        entries: List<BackupJournalEntity>,
    ): List<List<BackupJournalEntity>>? {
        if (entries.isEmpty()) return emptyList()
        val generations = entries.groupBy(BackupJournalEntity::generation)
            .toSortedMap()
            .values
        val result = mutableListOf<List<BackupJournalEntity>>()
        var current = emptyList<BackupJournalEntity>()
        generations.forEach { generation ->
            if (generation.size > MAX_SEGMENT_ENTRIES || !fitsSegment(generation)) return null
            val combined = current + generation
            if (combined.size <= MAX_SEGMENT_ENTRIES && fitsSegment(combined)) {
                current = combined
            } else {
                if (current.isEmpty()) return null
                result += current
                current = generation
            }
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun fitsSegment(entries: List<BackupJournalEntity>): Boolean = try {
        val payload = segmentCodec.fromJournalEntries(vaultId, entries)
        segmentCodec.encode(payload).also { it.fill(0) }
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun verifySnapshot(
        candidate: LocalBackupCandidate,
        expectedIdentity: CloudHeaderIdentity,
        key: app.opentasks.core.crypto.VaultKey,
        capture: StructuredBackupCapture,
    ) {
        val decoded = FileInputStream(candidate.file).use {
            authenticatedCodec.decrypt(it, candidate.byteCount, key)
        }
        val value = (decoded as? CloudDecodeResult.Success)?.value
            ?: throw IllegalStateException("Local backup snapshot authentication failed")
        value.use { decrypted ->
            require(decrypted.identity == expectedIdentity) { "Local backup snapshot identity mismatch" }
            val actual = snapshotCodec.decodeOwned(decrypted.takePlaintext())
            val expected = snapshotCodec.fromCapture(capture)
            val expectedCanonical = snapshotCodec.encode(expected)
            val actualCanonical = snapshotCodec.encode(actual)
            try {
                require(expectedCanonical.contentEquals(actualCanonical)) {
                    "Local backup snapshot source mismatch"
                }
            } finally {
                expectedCanonical.fill(0)
                actualCanonical.fill(0)
            }
        }
    }

    private fun verifySegment(
        candidate: LocalBackupCandidate,
        expectedIdentity: CloudHeaderIdentity,
        key: app.opentasks.core.crypto.VaultKey,
        expected: BackupOperationSegmentPayloadV1,
    ) {
        val decoded = FileInputStream(candidate.file).use {
            authenticatedCodec.decrypt(it, candidate.byteCount, key)
        }
        val value = (decoded as? CloudDecodeResult.Success)?.value
            ?: throw IllegalStateException("Local backup segment authentication failed")
        value.use { decrypted ->
            require(decrypted.identity == expectedIdentity) { "Local backup segment identity mismatch" }
            val actual = segmentCodec.decodeOwned(decrypted.takePlaintext())
            val expectedCanonical = segmentCodec.encode(expected)
            val actualCanonical = segmentCodec.encode(actual)
            try {
                require(expectedCanonical.contentEquals(actualCanonical)) {
                    "Local backup segment source mismatch"
                }
            } finally {
                expectedCanonical.fill(0)
                actualCanonical.fill(0)
            }
        }
    }

    private fun identity(family: CloudObjectFamily, objectId: String) = CloudHeaderIdentity(
        family = family,
        schemaVersion = 1,
        cryptoVersion = 1,
        minimumReaderVersion = 1,
        vaultId = vaultId.value,
        objectId = objectId,
    )

    private companion object {
        const val MAX_SEGMENT_ENTRIES = 5_000
    }
}
