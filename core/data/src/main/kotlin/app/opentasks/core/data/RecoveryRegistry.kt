package app.opentasks.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class RecoveryPhase {
    STAGING,
    VERIFIED,
    ACTIVATING,
    ACTIVATED,
}

enum class ActivationState {
    PENDING,
    MARKER_REPLACED,
    PUBLISHED,
}

enum class CleanupState {
    PENDING,
    PRIOR_REMOVED,
    COMPLETE,
}

/**
 * The staged-recovery bookkeeping that must survive a crash.
 *
 * Every field is bounded, and the record renders redacted so a log, a crash
 * report, or an assertion message can never carry a slot, a provider file
 * reference, or the progress of a recovery.
 */
data class RecoveryRegistryRecord(
    val operationId: String,
    val phase: RecoveryPhase,
    val priorSlot: VaultSlot?,
    val stagedSlot: VaultSlot,
    val providerReference: String?,
    val claimReference: String?,
    val publicationReference: String?,
    val claimedEpoch: Long?,
    val activationState: ActivationState,
    val cleanupState: CleanupState,
) {
    init {
        require(operationId.length in 1..MAX_OPERATION_ID_LENGTH && operationId.isPrintable()) {
            "The recovery operation identifier is not bounded"
        }
        listOf(providerReference, claimReference, publicationReference).forEach { reference ->
            require(
                (reference?.length ?: 0) <= MAX_REFERENCE_LENGTH &&
                    reference?.isPrintable() != false,
            ) {
                "A recovery registry reference is not bounded"
            }
        }
        require((claimedEpoch ?: 0) >= 0) { "The claimed epoch cannot be negative" }
    }

    override fun toString(): String = "RecoveryRegistryRecord([redacted])"

    private companion object {
        const val MAX_OPERATION_ID_LENGTH = 128
        const val MAX_REFERENCE_LENGTH = 256

        /** Registry references are opaque provider identifiers, never text. */
        fun String.isPrintable(): Boolean = all { it.code in 0x20..0x7e }
    }
}

/**
 * Authenticated encryption for the recovery registry.
 *
 * The registry key is deliberately separate from every database and content
 * key: losing it discards inactive staging and never the active vault.
 */
interface RegistrySecretBoundary {
    fun seal(plaintext: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

class AndroidKeystoreRegistrySecretBoundary(
    private val alias: String = DEFAULT_ALIAS,
) : RegistrySecretBoundary {
    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey(requireExisting = false))
            updateAAD(ASSOCIATED_DATA)
        }
        val nonce = cipher.iv.copyOf()
        check(nonce.size == GCM_NONCE_BYTES) { "The registry nonce is invalid" }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArray(1 + nonce.size + ciphertext.size).also { sealed ->
            sealed[0] = SEALED_FORMAT_VERSION
            nonce.copyInto(sealed, destinationOffset = 1)
            ciphertext.copyInto(sealed, destinationOffset = 1 + nonce.size)
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }

    override fun open(sealed: ByteArray): ByteArray {
        check(sealed.size > 1 + GCM_NONCE_BYTES) { "The registry envelope is invalid" }
        check(sealed[0] == SEALED_FORMAT_VERSION) { "The registry envelope is invalid" }
        val nonce = sealed.copyOfRange(1, 1 + GCM_NONCE_BYTES)
        return Cipher.getInstance(AES_GCM).run {
            init(
                Cipher.DECRYPT_MODE,
                wrappingKey(requireExisting = true),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            updateAAD(ASSOCIATED_DATA)
            doFinal(sealed, 1 + GCM_NONCE_BYTES, sealed.size - 1 - GCM_NONCE_BYTES)
        }
    }

    private fun wrappingKey(requireExisting: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        check(!requireExisting) { "The registry key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(REGISTRY_KEY_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .setUnlockedDeviceRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val DEFAULT_ALIAS = "open_tasks_vault_recovery_registry_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val REGISTRY_KEY_BITS = 256
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val SEALED_FORMAT_VERSION: Byte = 1
        val ASSOCIATED_DATA =
            "open-tasks:vault-recovery-registry:v1".toByteArray(Charsets.UTF_8)
    }
}

/**
 * The encrypted staged-recovery registry.
 *
 * Reads never explain what failed: a caller learns only that the registry is
 * unreadable, and [readOrDiscard] converts that into discarded staging.
 */
class RecoveryRegistry(
    private val file: File,
    private val fileOperations: VaultRegistryFileOperations,
    private val secretBoundary: RegistrySecretBoundary,
) {
    private var directoryPrepared = false

    constructor(context: Context) : this(
        file = File(
            File(context.applicationContext.filesDir, VAULT_RUNTIME_DIRECTORY),
            REGISTRY_NAME,
        ),
        fileOperations = AtomicFileVaultRegistryOperations(),
        secretBoundary = AndroidKeystoreRegistrySecretBoundary(),
    )

    fun read(): RecoveryRegistryRecord? {
        val sealed = fileOperations.readBytes(file) ?: return null
        var plaintext: ByteArray? = null
        return try {
            require(sealed.size <= REGISTRY_MAX_BYTES) { "bounded" }
            plaintext = secretBoundary.open(sealed)
            decode(CanonicalJson.decode(plaintext))
        } catch (_: Throwable) {
            throw IllegalStateException("The vault recovery registry is unreadable")
        } finally {
            plaintext?.fill(0)
        }
    }

    /**
     * Reads the registry, discarding it when it can no longer be opened.
     *
     * Only inactive staging depends on this registry, so a lost registry key
     * costs a staged recovery and never the active vault.
     */
    fun readOrDiscard(): RecoveryRegistryRecord? = try {
        read()
    } catch (_: IllegalStateException) {
        clear()
        null
    }

    fun write(record: RecoveryRegistryRecord) {
        if (!directoryPrepared) {
            file.parentFile?.let(fileOperations::ensureDirectory)
            directoryPrepared = true
        }
        val plaintext = CanonicalJson.encode(encode(record))
        val sealed = try {
            secretBoundary.seal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        check(sealed.size <= REGISTRY_MAX_BYTES) { "The vault recovery registry is unwritable" }
        fileOperations.stageWrite(file, sealed)
        fileOperations.commitWrite(file)
    }

    fun clear() {
        fileOperations.delete(file)
    }

    private fun encode(record: RecoveryRegistryRecord): Map<String, Any?> = mapOf(
        FORMAT_VERSION to RECORD_FORMAT_VERSION,
        OPERATION_ID to record.operationId,
        PHASE to record.phase.name,
        PRIOR_SLOT to record.priorSlot?.value,
        STAGED_SLOT to record.stagedSlot.value,
        PROVIDER_REFERENCE to record.providerReference,
        CLAIM_REFERENCE to record.claimReference,
        PUBLICATION_REFERENCE to record.publicationReference,
        CLAIMED_EPOCH to record.claimedEpoch,
        ACTIVATION_STATE to record.activationState.name,
        CLEANUP_STATE to record.cleanupState.name,
    )

    private fun decode(fields: Map<String, Any?>): RecoveryRegistryRecord {
        require(fields.keys == RECORD_FIELDS) { "fields" }
        require(fields[FORMAT_VERSION] == RECORD_FORMAT_VERSION) { "version" }
        return RecoveryRegistryRecord(
            operationId = fields.text(OPERATION_ID),
            phase = RecoveryPhase.valueOf(fields.text(PHASE)),
            priorSlot = fields.optionalText(PRIOR_SLOT)?.let(VaultSlot::parse),
            stagedSlot = VaultSlot.parse(fields.text(STAGED_SLOT)),
            providerReference = fields.optionalText(PROVIDER_REFERENCE),
            claimReference = fields.optionalText(CLAIM_REFERENCE),
            publicationReference = fields.optionalText(PUBLICATION_REFERENCE),
            claimedEpoch = fields[CLAIMED_EPOCH]?.let { value ->
                value as? Long ?: throw IllegalArgumentException("epoch")
            },
            activationState = ActivationState.valueOf(fields.text(ACTIVATION_STATE)),
            cleanupState = CleanupState.valueOf(fields.text(CLEANUP_STATE)),
        )
    }

    private fun Map<String, Any?>.text(key: String): String =
        this[key] as? String ?: throw IllegalArgumentException("field")

    private fun Map<String, Any?>.optionalText(key: String): String? =
        this[key]?.let { value -> value as? String ?: throw IllegalArgumentException("field") }

    private companion object {
        const val REGISTRY_NAME = "recovery_registry.bin"
        const val RECORD_FORMAT_VERSION = 1L
        const val FORMAT_VERSION = "formatVersion"
        const val OPERATION_ID = "operationId"
        const val PHASE = "phase"
        const val PRIOR_SLOT = "priorSlot"
        const val STAGED_SLOT = "stagedSlot"
        const val PROVIDER_REFERENCE = "providerReference"
        const val CLAIM_REFERENCE = "claimReference"
        const val PUBLICATION_REFERENCE = "publicationReference"
        const val CLAIMED_EPOCH = "claimedEpoch"
        const val ACTIVATION_STATE = "activationState"
        const val CLEANUP_STATE = "cleanupState"
        val RECORD_FIELDS = setOf(
            FORMAT_VERSION,
            OPERATION_ID,
            PHASE,
            PRIOR_SLOT,
            STAGED_SLOT,
            PROVIDER_REFERENCE,
            CLAIM_REFERENCE,
            PUBLICATION_REFERENCE,
            CLAIMED_EPOCH,
            ACTIVATION_STATE,
            CLEANUP_STATE,
        )
    }
}

internal const val VAULT_RUNTIME_DIRECTORY = "vault_runtime"
