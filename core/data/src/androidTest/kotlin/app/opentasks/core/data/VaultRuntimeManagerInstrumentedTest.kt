package app.opentasks.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.crypto.CryptoContext
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.VaultId
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultRuntimeManagerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var registryDirectory: File
    private val crypto: VaultCrypto = TinkVaultCrypto()
    private val managers = mutableListOf<DefaultVaultRuntimeManager>()
    private val runtimes = mutableListOf<LocalVaultRuntime>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registryDirectory = File(context.filesDir, "vault_runtime_test")
        clearVaultState()
    }

    @After
    fun tearDown() {
        managers.forEach { runCatching { it.close() } }
        managers.clear()
        runtimes.forEach { runCatching { it.close() } }
        runtimes.clear()
        clearVaultState()
    }

    @Test
    fun legacyVaultIsAdoptedWithoutRenamingItsDatabaseOrKeys() = runBlocking {
        seedLegacyVault()
        val before = storageSnapshot()

        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }

        val state = manager.state.value
        assertTrue(state is VaultRuntimeState.Active)
        assertEquals(VaultSlot.LEGACY, (state as VaultRuntimeState.Active).runtime.slot)
        assertTrue(databaseFile("open_tasks.db").isFile)
        assertEquals(before.aliases, storageSnapshot().aliases)
        assertEquals(before.preferenceKeys, storageSnapshot().preferenceKeys)
        assertTrue(storageSnapshot().databaseNames.contains("open_tasks.db"))
        assertTrue(storageSnapshot().databaseNames.none { it.startsWith("vault_") })
    }

    @Test
    fun initializationWithoutAVaultCreatesNoKeysOrDatabases() = runBlocking {
        val before = storageSnapshot()

        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }

        assertEquals(VaultRuntimeState.NoVault, manager.state.value)
        assertEquals(before.aliases, storageSnapshot().aliases)
        assertEquals(0, storageSnapshot().aliases.size)
        assertFalse(databaseFile("open_tasks.db").exists())
    }

    @Test
    fun explicitVaultCreationEstablishesTheLegacySlot() = runBlocking {
        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }
        assertEquals(VaultRuntimeState.NoVault, manager.state.value)

        withTimeout(TIMEOUT_MILLIS) { manager.createNewVault() }

        val state = manager.state.value
        assertTrue(state is VaultRuntimeState.Active)
        assertEquals(VaultSlot.LEGACY, (state as VaultRuntimeState.Active).runtime.slot)
        assertTrue(databaseFile("open_tasks.db").isFile)
        assertTrue(keyStore().containsAlias(LEGACY_DATABASE_ALIAS))
        assertTrue(storageSnapshot().preferenceKeys.contains("vault_keys/$LEGACY_CIPHERTEXT_KEY"))
    }

    @Test
    fun activeRuntimeReportsTheVaultIdentityStoredInRoom() = runBlocking {
        seedLegacyVault()
        rewriteLegacyVaultIdentity("vault-recovered")

        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }

        val state = manager.state.value as VaultRuntimeState.Active
        assertEquals(VaultId("vault-recovered"), state.runtime.vaultId)
    }

    @Test
    fun unreadableLegacyVaultIsPreservedWithoutReplacementKeys() = runBlocking {
        seedLegacyVault()
        keyStore().deleteEntry(LEGACY_DATABASE_ALIAS)
        val before = storageSnapshot()

        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }

        assertTrue(manager.state.value is VaultRuntimeState.Unreadable)
        assertEquals(
            VaultSlot.LEGACY,
            (manager.state.value as VaultRuntimeState.Unreadable).preservedSlot,
        )
        assertEquals(before, storageSnapshot())
        assertEquals(0, storageSnapshot().aliases.size)
    }

    @Test
    fun stagedSlotsUseRandomNamesAndIndependentDatabaseKeys() = runBlocking {
        seedLegacyVault()
        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }

        val first = withTimeout(TIMEOUT_MILLIS) { manager.beginRecovery("operation-1") }
        val second = withTimeout(TIMEOUT_MILLIS) { manager.beginRecovery("operation-2") }

        assertNotEquals(first, second)
        assertNotEquals(VaultSlot.LEGACY, first)
        val keyManager = AndroidVaultKeyManager(context)
        val firstKey = keyManager.createDatabaseKey(first)
        val secondKey = keyManager.createDatabaseKey(second)
        assertFalse(firstKey.contentEquals(secondKey))
        assertFalse(firstKey.contentEquals(keyManager.openExistingDatabaseKey(VaultSlot.LEGACY)))
        firstKey.fill(0)
        secondKey.fill(0)
    }

    @Test
    fun equalVaultIdentitiesInTwoSlotsKeepIndependentContentKeyWrappers() {
        val legacy = openedRuntime(VaultSlot.LEGACY, create = true)
        val staged = VaultSlot.new()
        val stagedRuntime = openedRuntime(staged, create = true)

        val legacyKey = legacy.contentKeyStore.getOrCreate(legacy.vaultId)
        val stagedKey = stagedRuntime.contentKeyStore.getOrCreate(stagedRuntime.vaultId)
        val context = CryptoContext(legacy.vaultId, "record", 1)
        val sealed = crypto.encryptRecord(legacyKey, context, SECRET)

        assertEquals(legacy.vaultId, stagedRuntime.vaultId)
        assertThrows(GeneralSecurityException::class.java) {
            crypto.decryptRecord(stagedKey, context, sealed)
        }

        val replacement = crypto.createKey()
        stagedRuntime.contentKeyStore.replace(stagedRuntime.vaultId, replacement)
        val reopenedLegacy = legacy.contentKeyStore.openExisting(legacy.vaultId)

        assertArrayEquals(SECRET, crypto.decryptRecord(reopenedLegacy, context, sealed))
        legacyKey.close()
        stagedKey.close()
        replacement.close()
        reopenedLegacy.close()
    }

    @Test
    fun deathBeforeMarkerReplacementKeepsThePriorSlotActive() = runBlocking {
        seedLegacyVault()
        val staged = VaultSlot.new()
        seedSlotVault(staged)
        VaultSlotRegistry(registryDirectory, AtomicFileVaultRegistryOperations())
            .replace(VaultSlot.LEGACY)

        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }

        val state = manager.state.value as VaultRuntimeState.Active
        assertEquals(VaultSlot.LEGACY, state.runtime.slot)
        assertFalse(databaseFile(slotDatabaseName(staged)).exists())
        assertTrue(databaseFile("open_tasks.db").isFile)
    }

    @Test
    fun deathAfterMarkerReplacementActivatesTheStagedSlot() = runBlocking {
        seedLegacyVault()
        val staged = VaultSlot.new()
        seedSlotVault(staged)
        VaultSlotRegistry(registryDirectory, AtomicFileVaultRegistryOperations()).replace(staged)

        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }

        val state = manager.state.value as VaultRuntimeState.Active
        assertEquals(staged, state.runtime.slot)
        assertTrue(databaseFile(slotDatabaseName(staged)).isFile)
    }

    @Test
    fun deathAfterMarkerReplacementFinishesThePriorSlotRemoval() = runBlocking {
        seedLegacyVault()
        val staged = VaultSlot.new()
        seedSlotVault(staged)
        val operations = AtomicFileVaultRegistryOperations()
        VaultSlotRegistry(registryDirectory, operations).replace(staged)
        RecoveryRegistry(
            file = File(registryDirectory, "recovery_registry.bin"),
            fileOperations = operations,
            secretBoundary = AndroidKeystoreRegistrySecretBoundary(REGISTRY_TEST_ALIAS),
        ).write(
            RecoveryRegistryRecord(
                operationId = "operation-1",
                phase = RecoveryPhase.ACTIVATING,
                priorSlot = VaultSlot.LEGACY,
                stagedSlot = staged,
                providerReference = null,
                claimReference = null,
                publicationReference = null,
                claimedEpoch = null,
                activationState = ActivationState.MARKER_REPLACED,
                cleanupState = CleanupState.PENDING,
            ),
        )

        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }

        val state = manager.state.value as VaultRuntimeState.Active
        assertEquals(staged, state.runtime.slot)
        assertFalse(databaseFile("open_tasks.db").exists())
        assertFalse(keyStore().containsAlias(LEGACY_DATABASE_ALIAS))
        assertTrue(databaseFile(slotDatabaseName(staged)).isFile)
    }

    @Test
    fun failedStagedOpenRestoresThePriorMarkerAndPriorVault() = runBlocking {
        seedLegacyVault()
        val failing = FailingOpenRuntimeFactory(LocalVaultRuntimeFactory(context, crypto))
        val manager = manager(runtimeFactory = failing)
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }
        val staged = withTimeout(TIMEOUT_MILLIS) { manager.beginRecovery("operation-1") }
        seedSlotVault(staged)
        failing.failFor = staged
        val before = storageSnapshot()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                manager.activate(
                    VerifiedStagedVault(
                        slot = staged,
                        vaultId = VaultId("vault-primary"),
                        recoveredGeneration = BackupGeneration(0),
                    ),
                )
            }
        }

        assertEquals(
            VaultSlot.LEGACY,
            VaultSlotRegistry(registryDirectory, AtomicFileVaultRegistryOperations()).read(),
        )
        assertTrue(databaseFile("open_tasks.db").isFile)
        assertEquals(before.aliases, storageSnapshot().aliases)
        assertTrue(manager.state.value is VaultRuntimeState.Active)
        assertEquals(
            VaultSlot.LEGACY,
            (manager.state.value as VaultRuntimeState.Active).runtime.slot,
        )
    }

    @Test
    fun activationQuiescesServicesAndClosesTheActiveRoomBeforeTheMarkerMoves() = runBlocking {
        seedLegacyVault()
        val manager = manager()
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }
        val prior = (manager.state.value as VaultRuntimeState.Active).runtime
        val observedAtQuiesce = mutableListOf<VaultSlot?>()
        manager.setActiveServiceQuiescer {
            observedAtQuiesce += VaultSlotRegistry(
                registryDirectory,
                AtomicFileVaultRegistryOperations(),
            ).read()
        }
        val staged = withTimeout(TIMEOUT_MILLIS) { manager.beginRecovery("operation-1") }
        seedSlotVault(staged)

        withTimeout(ACTIVATION_TIMEOUT_MILLIS) {
            manager.activate(
                VerifiedStagedVault(
                    slot = staged,
                    vaultId = VaultId("vault-primary"),
                    recoveredGeneration = BackupGeneration(0),
                ),
            )
        }

        assertEquals(listOf<VaultSlot?>(VaultSlot.LEGACY), observedAtQuiesce)
        val state = manager.state.value as VaultRuntimeState.Active
        assertEquals(staged, state.runtime.slot)
        assertFalse(state.runtime === prior)
        assertFalse(databaseFile("open_tasks.db").exists())
        assertFalse(keyStore().containsAlias(LEGACY_DATABASE_ALIAS))
    }

    @Test
    fun registryKeyLossDiscardsOnlyInactiveStaging() = runBlocking {
        seedLegacyVault()
        val staged = VaultSlot.new()
        seedSlotVault(staged)
        val writable = manager()
        withTimeout(TIMEOUT_MILLIS) { writable.initialize() }
        withTimeout(TIMEOUT_MILLIS) { writable.beginRecovery("operation-1") }
        writable.close()
        managers.remove(writable)
        val legacyDatabaseLength = databaseFile("open_tasks.db").length()

        val manager = manager(secretBoundary = UnavailableSecretBoundary())
        withTimeout(TIMEOUT_MILLIS) { manager.initialize() }

        val state = manager.state.value as VaultRuntimeState.Active
        assertEquals(VaultSlot.LEGACY, state.runtime.slot)
        assertFalse(databaseFile(slotDatabaseName(staged)).exists())
        assertEquals(legacyDatabaseLength, databaseFile("open_tasks.db").length())
        assertTrue(keyStore().containsAlias(LEGACY_DATABASE_ALIAS))
    }

    @Test
    fun requireActiveFailsOutsideAnActiveRuntime() = runBlocking {
        val empty = manager()
        withTimeout(TIMEOUT_MILLIS) { empty.initialize() }

        assertThrows(IllegalStateException::class.java) { empty.requireActive() }

        seedLegacyVault()
        keyStore().deleteEntry(LEGACY_DATABASE_ALIAS)
        val unreadable = manager()
        withTimeout(TIMEOUT_MILLIS) { unreadable.initialize() }

        assertTrue(unreadable.state.value is VaultRuntimeState.Unreadable)
        assertThrows(IllegalStateException::class.java) { unreadable.requireActive() }
        assertTrue(databaseFile("open_tasks.db").isFile)
    }

    private fun manager(
        runtimeFactory: VaultRuntimeFactory = LocalVaultRuntimeFactory(context, crypto),
        secretBoundary: RegistrySecretBoundary = AndroidKeystoreRegistrySecretBoundary(
            REGISTRY_TEST_ALIAS,
        ),
    ): DefaultVaultRuntimeManager = DefaultVaultRuntimeManager(
        directory = registryDirectory,
        fileOperations = AtomicFileVaultRegistryOperations(),
        secretBoundary = secretBoundary,
        runtimeFactory = runtimeFactory,
    ).also(managers::add)

    private fun openedRuntime(slot: VaultSlot, create: Boolean): LocalVaultRuntime {
        val factory = LocalVaultRuntimeFactory(context, crypto)
        val runtime = if (create) factory.createNew(slot) else factory.openExisting(slot)
        runtimes += runtime
        runBlocking { withTimeout(TIMEOUT_MILLIS) { runtime.repository.currentWorkspace() } }
        return runtime
    }

    private fun seedLegacyVault() {
        val runtime = openedRuntime(VaultSlot.LEGACY, create = true)
        runtime.close()
        runtimes.remove(runtime)
    }

    private fun seedSlotVault(slot: VaultSlot) {
        val runtime = openedRuntime(slot, create = true)
        runtime.close()
        runtimes.remove(runtime)
    }

    private fun rewriteLegacyVaultIdentity(vaultId: String) {
        val key = AndroidVaultKeyManager(context).openExistingDatabaseKey(VaultSlot.LEGACY)
        val database = try {
            VaultDatabase.create(context, "open_tasks.db", key)
        } finally {
            key.fill(0)
        }
        try {
            database.openHelper.writableDatabase.run {
                execSQL("PRAGMA foreign_keys=OFF")
                execSQL("UPDATE workspaces SET vaultId = '$vaultId'")
                execSQL("UPDATE backup_state SET vaultId = '$vaultId'")
                execSQL("UPDATE vaults SET id = '$vaultId'")
            }
        } finally {
            database.close()
        }
    }

    private fun storageSnapshot(): StorageSnapshot = StorageSnapshot(
        databaseNames = databaseDirectory().list().orEmpty()
            .filter { it == "open_tasks.db" || it.startsWith("vault_") }
            .sorted(),
        preferenceKeys = listOf("vault_keys", "vault_content_keys_v1")
            .flatMap { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all.keys
                    .map { "$name/$it" }
            }
            .sorted(),
        aliases = keyStore().aliases().toList()
            .filter { alias ->
                alias.startsWith("open_tasks_local_vault_wrapper_v1") ||
                    alias.startsWith("open_tasks_vault_content_wrapper_v1")
            }
            .sorted(),
    )

    private fun clearVaultState() {
        managers.forEach { runCatching { it.close() } }
        runtimes.forEach { runCatching { it.close() } }
        databaseDirectory().list().orEmpty()
            .filter { it.startsWith("open_tasks.db") || it.startsWith("vault_") }
            .forEach { name -> File(databaseDirectory(), name).delete() }
        context.deleteSharedPreferences("vault_keys")
        context.deleteSharedPreferences("vault_content_keys_v1")
        (context.filesDir.parentFile?.let { File(it, "shared_prefs") }?.list().orEmpty())
            .filter { it.startsWith("vault_content_keys_v1_") }
            .forEach { name -> context.deleteSharedPreferences(name.removeSuffix(".xml")) }
        val keyStore = keyStore()
        keyStore.aliases().toList()
            .filter { alias ->
                alias.startsWith("open_tasks_local_vault_wrapper_v1") ||
                    alias.startsWith("open_tasks_vault_content_wrapper_v1") ||
                    alias == REGISTRY_TEST_ALIAS
            }
            .forEach(keyStore::deleteEntry)
        registryDirectory.deleteRecursively()
    }

    private fun databaseDirectory(): File =
        checkNotNull(context.getDatabasePath("open_tasks.db").parentFile)

    private fun databaseFile(name: String): File = File(databaseDirectory(), name)

    private fun slotDatabaseName(slot: VaultSlot): String =
        LocalVaultRuntimeFactory.databaseName(slot)

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private data class StorageSnapshot(
        val databaseNames: List<String>,
        val preferenceKeys: List<String>,
        val aliases: List<String>,
    )

    private class FailingOpenRuntimeFactory(
        private val delegate: VaultRuntimeFactory,
    ) : VaultRuntimeFactory by delegate {
        var failFor: VaultSlot? = null

        override fun openExisting(slot: VaultSlot): LocalVaultRuntime {
            if (slot == failFor) error("The staged vault runtime cannot be opened")
            return delegate.openExisting(slot)
        }
    }

    private class UnavailableSecretBoundary : RegistrySecretBoundary {
        override fun seal(plaintext: ByteArray): ByteArray =
            error("The registry secret is unavailable")

        override fun open(sealed: ByteArray): ByteArray =
            error("The registry secret is unavailable")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val ACTIVATION_TIMEOUT_MILLIS = 5_000L
        const val LEGACY_DATABASE_ALIAS = "open_tasks_local_vault_wrapper_v1"
        const val LEGACY_CIPHERTEXT_KEY = "database_key_ciphertext_v1"
        const val REGISTRY_TEST_ALIAS = "open_tasks_vault_recovery_registry_test_v1"
        val SECRET = "slot-scoped record".toByteArray(Charsets.UTF_8)
    }
}
