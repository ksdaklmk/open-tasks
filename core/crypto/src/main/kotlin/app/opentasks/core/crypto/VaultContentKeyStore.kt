package app.opentasks.core.crypto

import app.opentasks.core.model.VaultId

interface VaultContentKeyStore {
    fun getOrCreate(vaultId: VaultId): VaultKey

    fun replace(vaultId: VaultId, key: VaultKey)

    fun delete(vaultId: VaultId)
}
