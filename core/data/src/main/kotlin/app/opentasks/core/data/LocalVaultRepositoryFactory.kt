package app.opentasks.core.data

import android.content.Context
import app.opentasks.core.crypto.AndroidVaultContentKeyStore
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.data.backup.DefaultBackupCoordinator
import app.opentasks.core.data.backup.DefaultLocalBackupObjectStore
import app.opentasks.core.data.backup.RoomBackupCaptureSource
import app.opentasks.core.data.backup.RoomBackupJournalStore
import app.opentasks.core.data.backup.RoomBackupStateStore
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.BackupCoordinator
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.VaultId
import java.io.File

/**
 * Constructed local services. Task 8 owns lifecycle, debounce, and requests;
 * this bundle intentionally does not start backup work.
 */
data class LocalVaultRuntime(
    val repository: VaultRepository,
    val backupCoordinator: BackupCoordinator,
)

object LocalVaultRepositoryFactory {
    fun create(context: Context): VaultRepository {
        val applicationContext = context.applicationContext
        val keyManager = AndroidVaultKeyManager(applicationContext)
        val databaseKey = keyManager.getOrCreateDatabaseKey()
        val database = try {
            VaultDatabase.create(applicationContext, DATABASE_NAME, databaseKey)
        } finally {
            databaseKey.fill(0)
        }
        return RoomVaultRepository(
            database = database,
            deviceId = keyManager.getOrCreateDeviceId(),
        )
    }

    fun createRuntime(context: Context): LocalVaultRuntime {
        val applicationContext = context.applicationContext
        val keyManager = AndroidVaultKeyManager(applicationContext)
        val databaseKey = keyManager.getOrCreateDatabaseKey()
        val database = try {
            VaultDatabase.create(applicationContext, DATABASE_NAME, databaseKey)
        } finally {
            databaseKey.fill(0)
        }
        val repository = RoomVaultRepository(
            database = database,
            deviceId = keyManager.getOrCreateDeviceId(),
        )
        val vaultId = VaultId("vault-primary")
        val crypto = TinkVaultCrypto()
        return LocalVaultRuntime(
            repository = repository,
            backupCoordinator = DefaultBackupCoordinator(
                vaultId = vaultId,
                captureSource = RoomBackupCaptureSource(database, vaultId),
                stateStore = RoomBackupStateStore(database.backupStateDao()),
                journalStore = RoomBackupJournalStore(database.backupJournalDao()),
                objectStore = DefaultLocalBackupObjectStore(
                    File(applicationContext.noBackupFilesDir, "backup/v1"),
                ),
                authenticatedCodec = DefaultAuthenticatedCloudObjectCodec(crypto),
                contentKeyStore = AndroidVaultContentKeyStore(applicationContext, crypto),
            ),
        )
    }

    private const val DATABASE_NAME = "open_tasks.db"
}
