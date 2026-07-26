package app.opentasks.core.data

import android.content.Context
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.domain.VaultRepository

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

    private const val DATABASE_NAME = "open_tasks.db"
}
