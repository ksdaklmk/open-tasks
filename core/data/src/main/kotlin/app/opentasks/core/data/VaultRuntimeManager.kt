package app.opentasks.core.data

import android.content.Context
import app.opentasks.core.crypto.AndroidVaultContentKeyStorage
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.data.db.VaultDatabase
import app.opentasks.core.model.BackupGeneration
import app.opentasks.core.model.VaultId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A staged vault that has already been verified by the recovery pipeline.
 *
 * [recoveredGeneration] is the generation the authenticated payload described.
 * [activationGeneration] is what the staged database actually sits at once
 * verification is finished, which is what activation must trust: constructing a
 * normal repository over a recovered vault runs the local retention purge, and
 * that legitimately advances the staged vault past its recovered payload.
 */
data class VerifiedStagedVault(
    val slot: VaultSlot,
    val vaultId: VaultId,
    val recoveredGeneration: BackupGeneration,
    val activationGeneration: BackupGeneration = recoveredGeneration,
    val retentionPurge: RetentionPurgeAccounting = RetentionPurgeAccounting.NONE,
)

/**
 * The only drift a verified staging slot may show after it has been verified.
 *
 * Everything here is attributed to the local retention purge; any other
 * difference fails verification instead of being reported.
 */
data class RetentionPurgeAccounting(
    val purgedTaskCount: Int,
    val removedRecordCount: Int,
    val journalEntryCount: Int,
) {
    companion object {
        val NONE = RetentionPurgeAccounting(
            purgedTaskCount = 0,
            removedRecordCount = 0,
            journalEntryCount = 0,
        )
    }
}

/** The identity a staging slot proves it holds before it can be activated. */
data class StagedVaultIdentity(
    val vaultId: VaultId,
    val generation: BackupGeneration,
)

sealed interface VaultRuntimeState {
    data object Initializing : VaultRuntimeState

    data object NoVault : VaultRuntimeState

    data class Unreadable(val preservedSlot: VaultSlot) : VaultRuntimeState

    data class Recovering(val operationId: String) : VaultRuntimeState

    data class Active(val runtime: LocalVaultRuntime) : VaultRuntimeState
}

interface VaultRuntimeManager {
    val state: StateFlow<VaultRuntimeState>

    suspend fun initialize()

    suspend fun createNewVault()

    suspend fun beginRecovery(operationId: String): VaultSlot

    suspend fun activate(staged: VerifiedStagedVault)

    /**
     * Discards a staged recovery that must never be published.
     *
     * Losing an ownership race costs the staged slot and nothing else: the
     * active vault, its marker, and every wrapper are left exactly as they
     * were, and a device that held no vault stays holding none.
     */
    suspend fun abandonRecovery(operationId: String)

    fun requireActive(): LocalVaultRuntime
}

/** The slot-scoped storage every runtime state transition works through. */
interface VaultRuntimeFactory {
    fun hasVault(slot: VaultSlot): Boolean

    fun openExisting(slot: VaultSlot): LocalVaultRuntime

    fun createNew(slot: VaultSlot): LocalVaultRuntime

    fun verifyStaging(slot: VaultSlot): StagedVaultIdentity

    fun listStagedSlots(): List<VaultSlot>

    fun discard(slot: VaultSlot)
}

class LocalVaultRuntimeFactory(
    context: Context,
    private val crypto: VaultCrypto,
) : VaultRuntimeFactory {
    private val context = context.applicationContext
    private val keyManager = AndroidVaultKeyManager(this.context)

    override fun hasVault(slot: VaultSlot): Boolean =
        keyManager.hasDatabaseKey(slot) || databaseFile(slot).isFile

    override fun openExisting(slot: VaultSlot): LocalVaultRuntime =
        LocalVaultRepositoryFactory.openRuntime(context, slot, crypto, keyManager)

    override fun createNew(slot: VaultSlot): LocalVaultRuntime =
        LocalVaultRepositoryFactory.createRuntime(context, slot, crypto, keyManager)

    /**
     * Checkpoints, closes, reopens, and reads the staging slot so activation
     * never publishes a slot whose write-ahead log is still outstanding.
     */
    override fun verifyStaging(slot: VaultSlot): StagedVaultIdentity {
        openDatabase(slot).use { database ->
            database.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { cursor -> cursor.moveToFirst() }
        }
        return openDatabase(slot).use { database ->
            val readable = database.openHelper.readableDatabase
            val vaultId = readable.query("SELECT id FROM vaults ORDER BY id LIMIT 1")
                .use { cursor ->
                    check(cursor.moveToFirst()) { "The staged vault holds no identity" }
                    VaultId(cursor.getString(0))
                }
            val generation = readable
                .query(
                    "SELECT currentGeneration FROM backup_state WHERE vaultId = ?",
                    arrayOf(vaultId.value),
                )
                .use { cursor ->
                    check(cursor.moveToFirst()) { "The staged vault holds no backup state" }
                    BackupGeneration(cursor.getLong(0))
                }
            StagedVaultIdentity(vaultId, generation)
        }
    }

    override fun listStagedSlots(): List<VaultSlot> =
        databaseFile(VaultSlot.LEGACY).parentFile?.list().orEmpty()
            .mapNotNull { name ->
                name.removeSurrounding(STAGED_DATABASE_PREFIX, DATABASE_SUFFIX)
                    .takeIf { it != name }
                    ?.let(VaultSlot::parseOrNull)
            }
            .filter { it != VaultSlot.LEGACY }
            .distinct()

    override fun discard(slot: VaultSlot) {
        context.deleteDatabase(databaseName(slot))
        keyManager.deleteDatabaseKey(slot)
        when (val namespace = LocalVaultRepositoryFactory.storageNamespace(slot)) {
            null -> AndroidVaultContentKeyStorage.deleteLegacyStorage(context)
            else -> AndroidVaultContentKeyStorage.deleteNamespace(context, namespace)
        }
    }

    private fun openDatabase(slot: VaultSlot): VaultDatabase {
        val key = keyManager.openExistingDatabaseKey(slot)
        return try {
            VaultDatabase.create(context, databaseName(slot), key)
        } finally {
            key.fill(0)
        }
    }

    private fun databaseFile(slot: VaultSlot): File =
        context.getDatabasePath(databaseName(slot))

    private fun <R> VaultDatabase.use(block: (VaultDatabase) -> R): R = try {
        block(this)
    } finally {
        close()
    }

    companion object {
        fun databaseName(slot: VaultSlot): String =
            if (slot == VaultSlot.LEGACY) {
                LEGACY_DATABASE_NAME
            } else {
                "$STAGED_DATABASE_PREFIX${slot.value}$DATABASE_SUFFIX"
            }

        private const val LEGACY_DATABASE_NAME = "open_tasks.db"
        private const val STAGED_DATABASE_PREFIX = "vault_"
        private const val DATABASE_SUFFIX = ".db"
    }
}

/**
 * Owns the single live vault runtime and the crash-safe slot transitions.
 *
 * Nothing that touches vault data is constructed outside
 * [VaultRuntimeState.Active]; an unreadable slot is preserved exactly as it was
 * found, and a lost registry key costs inactive staging alone.
 */
class DefaultVaultRuntimeManager(
    directory: File,
    fileOperations: VaultRegistryFileOperations,
    secretBoundary: RegistrySecretBoundary,
    private val runtimeFactory: VaultRuntimeFactory,
) : VaultRuntimeManager {
    constructor(context: Context, crypto: VaultCrypto) : this(
        directory = File(context.applicationContext.filesDir, VAULT_RUNTIME_DIRECTORY),
        fileOperations = AtomicFileVaultRegistryOperations(),
        secretBoundary = AndroidKeystoreRegistrySecretBoundary(),
        runtimeFactory = LocalVaultRuntimeFactory(context, crypto),
    )

    private val slotRegistry = VaultSlotRegistry(directory, fileOperations)
    private val recoveryRegistry = RecoveryRegistry(
        file = File(directory, RECOVERY_REGISTRY_NAME),
        fileOperations = fileOperations,
        secretBoundary = secretBoundary,
    )
    private val mutableState = MutableStateFlow<VaultRuntimeState>(VaultRuntimeState.Initializing)
    private val transitions = Mutex()

    @Volatile
    private var quiescer: () -> Unit = {}

    override val state: StateFlow<VaultRuntimeState> = mutableState.asStateFlow()

    /** Registers the hook that stops active app services before a slot moves. */
    fun setActiveServiceQuiescer(quiesce: () -> Unit) {
        quiescer = quiesce
    }

    /**
     * Re-entrant from every state that holds no runtime.
     *
     * The database wrapping key requires an unlocked device, so an open that
     * fails in the background must not latch [VaultRuntimeState.Unreadable] for
     * the life of the process: a later call re-reads the marker and retries.
     */
    override suspend fun initialize() {
        transitions.withLock {
            when (mutableState.value) {
                is VaultRuntimeState.Active, is VaultRuntimeState.Recovering -> return
                else -> initializeLocked()
            }
        }
    }

    private suspend fun initializeLocked() {
        withContext(Dispatchers.IO) {
            val marker = try {
                slotRegistry.read()
            } catch (_: IllegalStateException) {
                mutableState.value = VaultRuntimeState.Unreadable(VaultSlot.LEGACY)
                return@withContext
            }
            val slot = marker
                ?: VaultSlot.LEGACY.takeIf { runtimeFactory.hasVault(it) }
                ?: run {
                    discardInactiveStaging(active = null)
                    mutableState.value = VaultRuntimeState.NoVault
                    return@withContext
                }
            val runtime = try {
                runtimeFactory.openExisting(slot)
            } catch (_: Throwable) {
                if (!restorePriorSlotAfterFailedActivation(failedSlot = slot)) {
                    mutableState.value = VaultRuntimeState.Unreadable(slot)
                }
                return@withContext
            }
            if (marker == null) {
                // Adoption records the slot the vault already lives in; it
                // renames no file and rewraps no key.
                try {
                    slotRegistry.replace(slot)
                } catch (failure: Throwable) {
                    runtime.close()
                    throw failure
                }
            }
            if (!finishInterruptedCleanup(runtime)) return@withContext
            discardInactiveStaging(active = slot)
            mutableState.value = VaultRuntimeState.Active(runtime)
        }
    }

    override suspend fun createNewVault(): Unit = transitions.withLock {
        check(mutableState.value is VaultRuntimeState.NoVault) {
            "A vault runtime already exists"
        }
        withContext(Dispatchers.IO) {
            val runtime = runtimeFactory.createNew(VaultSlot.LEGACY)
            try {
                slotRegistry.replace(VaultSlot.LEGACY)
            } catch (failure: Throwable) {
                runtime.close()
                throw failure
            }
            mutableState.value = VaultRuntimeState.Active(runtime)
        }
    }

    override suspend fun beginRecovery(operationId: String): VaultSlot = transitions.withLock {
        withContext(Dispatchers.IO) {
            val staged = VaultSlot.new()
            recoveryRegistry.write(
                RecoveryRegistryRecord(
                    operationId = operationId,
                    phase = RecoveryPhase.STAGING,
                    priorSlot = activeSlot(),
                    stagedSlot = staged,
                    providerReference = null,
                    claimReference = null,
                    publicationReference = null,
                    claimedEpoch = null,
                    activationState = ActivationState.PENDING,
                    cleanupState = CleanupState.PENDING,
                ),
            )
            if (mutableState.value !is VaultRuntimeState.Active) {
                mutableState.value = VaultRuntimeState.Recovering(operationId)
            }
            staged
        }
    }

    override suspend fun activate(staged: VerifiedStagedVault): Unit = transitions.withLock {
        withContext(Dispatchers.IO) {
            val record = recoveryRegistry.readOrDiscard()
                ?: error("No staged recovery is registered")
            check(record.stagedSlot == staged.slot) {
                "The staged vault is not the registered staging slot"
            }
            val priorRuntime = (mutableState.value as? VaultRuntimeState.Active)?.runtime
            val priorSlot = priorRuntime?.slot ?: record.priorSlot

            quiescer()
            priorRuntime?.close()
            mutableState.value = VaultRuntimeState.Recovering(record.operationId)

            // Every step from here to the published runtime rolls back to the
            // prior slot: a failure must never strand the process in
            // VaultRuntimeState.Recovering with no runtime to return to.
            val runtime = try {
                replaceMarkerWithStagedSlot(staged, record, priorSlot)
            } catch (failure: Throwable) {
                rollbackToPriorSlot(priorSlot)
                throw failure
            }
            mutableState.value = VaultRuntimeState.Active(runtime)

            if (priorSlot != null && priorSlot != staged.slot) {
                runCatching { runtimeFactory.discard(priorSlot) }
            }
            recoveryRegistry.clear()
        }
    }

    override suspend fun abandonRecovery(operationId: String): Unit = transitions.withLock {
        val discarded = withContext(Dispatchers.IO) {
            val record = try {
                recoveryRegistry.readOrDiscard()
            } catch (_: Throwable) {
                null
            }
            if (record == null || record.operationId != operationId) return@withContext false
            recoveryRegistry.clear()
            runCatching { runtimeFactory.discard(record.stagedSlot) }
            true
        }
        // The device is back to exactly whatever it held before the recovery
        // began, which the ordinary marker read is already the authority on.
        if (discarded && mutableState.value is VaultRuntimeState.Recovering) {
            initializeLocked()
        }
    }

    private fun replaceMarkerWithStagedSlot(
        staged: VerifiedStagedVault,
        record: RecoveryRegistryRecord,
        priorSlot: VaultSlot?,
    ): LocalVaultRuntime {
        val identity = runtimeFactory.verifyStaging(staged.slot)
        check(identity.vaultId == staged.vaultId) {
            "The staged vault identity does not match the verified recovery"
        }
        check(identity.generation.value >= staged.recoveredGeneration.value) {
            "The staged vault is behind the verified recovery"
        }

        val activating = record.copy(
            phase = RecoveryPhase.ACTIVATING,
            priorSlot = priorSlot,
            activationState = ActivationState.PENDING,
        )
        recoveryRegistry.write(activating)
        slotRegistry.stageReplacement(staged.slot)
        slotRegistry.commitReplacement()
        recoveryRegistry.write(
            activating.copy(activationState = ActivationState.MARKER_REPLACED),
        )

        val runtime = runtimeFactory.openExisting(staged.slot)
        return try {
            // The database key is not enough: the prior slot is about to be
            // removed, so the staged slot must also prove it can open the
            // content key its records and recovery envelope are sealed under.
            runtime.contentKeyStore.openExisting(runtime.vaultId).close()
            runtime
        } catch (failure: Throwable) {
            runtime.close()
            throw failure
        }
    }

    override fun requireActive(): LocalVaultRuntime {
        activeRuntime()?.let { return it }
        // A caller that arrives before, or after a failed, initialisation gets
        // one more attempt rather than a state latched by a locked device.
        if (mutableState.value !is VaultRuntimeState.Recovering) {
            runBlocking { initialize() }
        }
        return activeRuntime() ?: error("The local vault runtime is not active")
    }

    /** Releases the live runtime; the next call re-reads the active marker. */
    fun close() {
        activeRuntime()?.close()
        mutableState.value = VaultRuntimeState.Initializing
    }

    private fun activeRuntime(): LocalVaultRuntime? =
        (mutableState.value as? VaultRuntimeState.Active)?.runtime

    private fun activeSlot(): VaultSlot? = activeRuntime()?.slot

    /**
     * Restores the unchanged prior marker after a failed staged open, leaving
     * the prior database and every wrapper exactly as they were.
     */
    private fun rollbackToPriorSlot(priorSlot: VaultSlot?) {
        if (priorSlot == null) {
            mutableState.value = VaultRuntimeState.NoVault
            return
        }
        runCatching { slotRegistry.replace(priorSlot) }
        val restored = runCatching { runtimeFactory.openExisting(priorSlot) }.getOrNull()
        mutableState.value = restored?.let(VaultRuntimeState::Active)
            ?: VaultRuntimeState.Unreadable(priorSlot)
    }

    /**
     * Rolls a published marker back when its staged slot will not open.
     *
     * A process that died between the marker replacement and the first normal
     * open leaves the marker naming a slot no runtime can be built from. The
     * registry still records the prior slot, so the guarantee that the first
     * failed normal open restores the unchanged prior marker survives a crash
     * as well as a live activation. The prior slot is proved to open before the
     * marker is moved back to it.
     */
    private fun restorePriorSlotAfterFailedActivation(failedSlot: VaultSlot): Boolean {
        val record = try {
            recoveryRegistry.readOrDiscard()
        } catch (_: Throwable) {
            null
        } ?: return false
        if (record.stagedSlot != failedSlot) return false
        if (record.activationState != ActivationState.MARKER_REPLACED) return false
        val prior = record.priorSlot ?: return false
        val restored = runCatching { runtimeFactory.openExisting(prior) }.getOrNull()
            ?: return false
        return try {
            slotRegistry.replace(prior)
            mutableState.value = VaultRuntimeState.Active(restored)
            true
        } catch (_: Throwable) {
            restored.close()
            false
        }
    }

    /**
     * Completes a slot replacement that died after the marker was published.
     *
     * The marker already names the staged slot, so removing the prior slot is
     * the remaining step of a succeeded activation rather than a new decision.
     * Returns `false` when [runtime] must not be published, having already put
     * the manager into the state that replaces it.
     */
    private fun finishInterruptedCleanup(runtime: LocalVaultRuntime): Boolean {
        val record = try {
            recoveryRegistry.readOrDiscard()
        } catch (_: Throwable) {
            null
        } ?: return true
        if (record.stagedSlot != runtime.slot) return true
        if (record.activationState == ActivationState.PENDING) return true
        val prior = record.priorSlot?.takeIf { it != runtime.slot }
        if (prior != null) {
            // The database key and the content key are wrapped independently,
            // so a staged slot whose database opens can still be unable to open
            // the records it holds. The prior slot is the only vault that could
            // read them, and it is about to be removed.
            val contentKeyOpens = runCatching {
                runtime.contentKeyStore.openExisting(runtime.vaultId).close()
            }.isSuccess
            if (!contentKeyOpens) {
                runtime.close()
                if (!restorePriorSlotAfterFailedActivation(failedSlot = runtime.slot)) {
                    mutableState.value = VaultRuntimeState.Unreadable(runtime.slot)
                }
                return false
            }
            runCatching { runtimeFactory.discard(prior) }
        }
        recoveryRegistry.clear()
        return true
    }

    private fun discardInactiveStaging(active: VaultSlot?) {
        val retained = try {
            recoveryRegistry.readOrDiscard()
                ?.takeIf { it.phase != RecoveryPhase.ACTIVATED }
                ?.stagedSlot
        } catch (_: Throwable) {
            null
        }
        runtimeFactory.listStagedSlots()
            .filter { it != active && it != retained }
            .forEach { slot -> runCatching { runtimeFactory.discard(slot) } }
    }

    private companion object {
        const val RECOVERY_REGISTRY_NAME = "recovery_registry.bin"
    }
}
