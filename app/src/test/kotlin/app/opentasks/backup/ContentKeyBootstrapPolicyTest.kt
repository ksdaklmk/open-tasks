package app.opentasks.backup

import app.opentasks.core.data.backup.BackupStateEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentKeyBootstrapPolicyTest {
    @Test
    fun pristineStateIsTheOnlyStateAllowedToCreateAContentKey() {
        assertFalse(
            requiresEstablishedContentKey(
                state = state(),
                recoveryEnvelopePresent = false,
                eligiblePackagePresent = false,
                recoveryInboxPresent = false,
                localBackupObjectPresent = false,
            ),
        )
        assertFalse(
            requiresEstablishedContentKey(
                state = state().copy(currentGeneration = 7),
                recoveryEnvelopePresent = false,
                eligiblePackagePresent = false,
                recoveryInboxPresent = false,
                localBackupObjectPresent = false,
            ),
        )
    }

    @Test
    fun everyDurableEncryptedBackupSignalRequiresOpeningTheEstablishedKey() {
        val stateSignals = listOf(
            state().copy(lastVerifiedSnapshotGeneration = 1),
            state().copy(currentBaseObjectId = "snapshot:1"),
            state().copy(previousBaseObjectId = "snapshot:0"),
            state().copy(latestVerifiedSegmentGeneration = 1),
            state().copy(portablePackageGeneration = 1),
            state().copy(portablePackageBytes = 4_096),
            state().copy(portablePackageProducedAtEpochMillis = 1_234),
            state().copy(packageState = "PREPARING"),
            state().copy(recoveryEnvelopeReady = true),
            state().copy(legacyOutboxCoveredAtGeneration = 1),
            state().copy(snapshotCreatedAtEpochMillis = 1_234),
        )

        stateSignals.forEach { signalled ->
            assertTrue(
                requiresEstablishedContentKey(
                    state = signalled,
                    recoveryEnvelopePresent = false,
                    eligiblePackagePresent = false,
                    recoveryInboxPresent = false,
                    localBackupObjectPresent = false,
                ),
            )
        }
        assertTrue(
            requiresEstablishedContentKey(
                state = state(),
                recoveryEnvelopePresent = true,
                eligiblePackagePresent = false,
                recoveryInboxPresent = false,
                localBackupObjectPresent = false,
            ),
        )
        assertTrue(
            requiresEstablishedContentKey(
                state = state(),
                recoveryEnvelopePresent = false,
                eligiblePackagePresent = true,
                recoveryInboxPresent = false,
                localBackupObjectPresent = false,
            ),
        )
        assertTrue(
            requiresEstablishedContentKey(
                state = state(),
                recoveryEnvelopePresent = false,
                eligiblePackagePresent = false,
                recoveryInboxPresent = false,
                localBackupObjectPresent = true,
            ),
        )
        assertTrue(
            requiresEstablishedContentKey(
                state = state(),
                recoveryEnvelopePresent = false,
                eligiblePackagePresent = false,
                recoveryInboxPresent = true,
                localBackupObjectPresent = false,
            ),
        )
    }

    private fun state() = BackupStateEntity(
        vaultId = "vault-primary",
        currentGeneration = 0,
        lastVerifiedSnapshotGeneration = null,
        currentBaseObjectId = null,
        previousBaseObjectId = null,
        latestVerifiedSegmentGeneration = null,
        portablePackageGeneration = null,
        portablePackageBytes = null,
        portablePackageProducedAtEpochMillis = null,
        packageState = "NOT_PREPARED",
        failureCategory = null,
        recoveryEnvelopeReady = false,
        legacyOutboxCoveredAtGeneration = null,
        snapshotCreatedAtEpochMillis = null,
    )
}
