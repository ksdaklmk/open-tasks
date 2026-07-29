package app.opentasks.backup

import app.opentasks.core.crypto.CryptoContext
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.backup.RecoveryEnvelopeCodec
import app.opentasks.core.model.VaultId
import java.security.MessageDigest
import java.security.SecureRandom

class PreparedRecoveryEnvelope internal constructor(
    val envelope: VaultKeyEnvelope,
    val canonicalBytes: ByteArray,
) : AutoCloseable {
    override fun close() {
        envelope.kdf.salt.fill(0)
        envelope.nonce.fill(0)
        envelope.wrappedKeyset.fill(0)
        canonicalBytes.fill(0)
    }
}

class RecoveryEnvelopePreparer(
    private val vaultId: VaultId,
    private val keyStore: VaultContentKeyStore,
    private val crypto: VaultCrypto,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun prepare(passphrase: CharArray): PreparedRecoveryEnvelope {
        var envelope: VaultKeyEnvelope? = null
        var canonicalBytes: ByteArray? = null
        var challenge: ByteArray? = null
        var associatedData: ByteArray? = null
        var ciphertext: ByteArray? = null
        var candidatePlaintext: ByteArray? = null
        val establishedKey = keyStore.openExisting(vaultId)
        var candidateKey: VaultKey? = null
        var ownershipTransferred = false
        try {
            envelope = crypto.wrapForRecovery(establishedKey, passphrase)
            candidateKey = crypto.unlock(passphrase, envelope)
            challenge = ByteArray(CHALLENGE_BYTES).also(secureRandom::nextBytes)
            associatedData = CryptoContext(
                vaultId = vaultId,
                objectId = CHALLENGE_OBJECT_ID,
                formatVersion = FORMAT_VERSION,
            ).associatedData()
            ciphertext = crypto.encryptBytes(
                key = establishedKey,
                plaintext = challenge,
                associatedData = associatedData,
            )
            candidatePlaintext = crypto.decryptBytes(
                key = candidateKey,
                ciphertext = ciphertext,
                associatedData = associatedData,
            )
            check(MessageDigest.isEqual(challenge, candidatePlaintext)) {
                "Recovery envelope did not reproduce the established content key"
            }
            canonicalBytes = RecoveryEnvelopeCodec.encode(envelope)
            return PreparedRecoveryEnvelope(
                envelope = envelope,
                canonicalBytes = canonicalBytes,
            ).also {
                ownershipTransferred = true
            }
        } finally {
            candidatePlaintext?.fill(0)
            ciphertext?.fill(0)
            associatedData?.fill(0)
            challenge?.fill(0)
            if (!ownershipTransferred) {
                canonicalBytes?.fill(0)
                envelope?.kdf?.salt?.fill(0)
                envelope?.nonce?.fill(0)
                envelope?.wrappedKeyset?.fill(0)
            }
            try {
                candidateKey?.close()
            } finally {
                establishedKey.close()
            }
        }
    }

    private companion object {
        const val CHALLENGE_BYTES = 32
        const val CHALLENGE_OBJECT_ID = "recovery-envelope-verification"
        const val FORMAT_VERSION = 1
    }
}
