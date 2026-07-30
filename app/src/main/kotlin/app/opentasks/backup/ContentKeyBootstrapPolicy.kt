package app.opentasks.backup

import app.opentasks.core.data.backup.BackupStateEntity

internal fun requiresEstablishedContentKey(
    state: BackupStateEntity?,
    recoveryEnvelopePresent: Boolean,
    eligiblePackagePresent: Boolean,
    recoveryInboxPresent: Boolean,
    localBackupObjectPresent: Boolean,
): Boolean =
    recoveryEnvelopePresent ||
        eligiblePackagePresent ||
        recoveryInboxPresent ||
        localBackupObjectPresent ||
        state?.let { current ->
            current.recoveryEnvelopeReady ||
                current.lastVerifiedSnapshotGeneration != null ||
                current.currentBaseObjectId != null ||
                current.previousBaseObjectId != null ||
                current.latestVerifiedSegmentGeneration != null ||
                current.portablePackageGeneration != null ||
                current.portablePackageBytes != null ||
                current.portablePackageProducedAtEpochMillis != null ||
                current.packageState in ESTABLISHED_KEY_PACKAGE_STATES ||
                current.legacyOutboxCoveredAtGeneration != null ||
                current.snapshotCreatedAtEpochMillis != null
        } == true

private val ESTABLISHED_KEY_PACKAGE_STATES = setOf(
    "PREPARING",
    "READY",
    "UPDATE_PENDING",
)
