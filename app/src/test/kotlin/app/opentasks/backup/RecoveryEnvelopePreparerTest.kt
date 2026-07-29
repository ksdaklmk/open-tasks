package app.opentasks.backup

import app.opentasks.core.crypto.Argon2Metadata
import app.opentasks.core.crypto.InvalidRecoveryPassphraseException
import app.opentasks.core.crypto.TinkVaultCrypto
import app.opentasks.core.crypto.VaultContentKeyStore
import app.opentasks.core.crypto.VaultCrypto
import app.opentasks.core.crypto.VaultKey
import app.opentasks.core.crypto.VaultKeyEnvelope
import app.opentasks.core.data.backup.RecoveryEnvelopeCodec
import app.opentasks.core.model.VaultId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.SecureRandom

class RecoveryEnvelopePreparerTest {
    private val vaultId = VaultId("vault-1")

    @Test
    fun prepareOpensEstablishedKeyAndReturnsVerifiedCanonicalEnvelope() {
        val delegate = TinkVaultCrypto()
        val established = delegate.createKey()
        val crypto = InspectingVaultCrypto(delegate)
        val random = CapturingSecureRandom()
        val preparer = RecoveryEnvelopePreparer(
            vaultId = vaultId,
            keyStore = ExistingOnlyKeyStore(established),
            crypto = crypto,
            secureRandom = random,
        )
        val passphrase = "correct horse battery staple".toCharArray()

        val prepared = try {
            preparer.prepare(passphrase)
        } finally {
            passphrase.fill('\u0000')
        }

        assertArrayEquals(CharArray(passphrase.size), passphrase)
        val reencoded = RecoveryEnvelopeCodec.encode(prepared.envelope)
        assertArrayEquals(reencoded, prepared.canonicalBytes)
        reencoded.fill(0)
        assertNotNull(crypto.unlockedCandidate)
        assertEquals(32, checkNotNull(random.challenge).size)
        assertAllZero(checkNotNull(random.challenge))
        assertAllZero(checkNotNull(crypto.encryptAssociatedData))
        assertAllZero(checkNotNull(crypto.decryptAssociatedData))
        assertAllZero(checkNotNull(crypto.challengeCiphertext))
        assertAllZero(checkNotNull(crypto.decryptedChallenge))
        assertKeyClosed(established, delegate)
        assertKeyClosed(checkNotNull(crypto.unlockedCandidate), delegate)

        val canonical = prepared.canonicalBytes
        val salt = prepared.envelope.kdf.salt
        val nonce = prepared.envelope.nonce
        val wrapped = prepared.envelope.wrappedKeyset
        prepared.close()
        assertAllZero(canonical)
        assertAllZero(salt)
        assertAllZero(nonce)
        assertAllZero(wrapped)
    }

    @Test
    fun prepareNeverBootstrapsOrCreatesAContentKey() {
        val keyFactory = TinkVaultCrypto()
        val established = keyFactory.createKey()
        val crypto = object : VaultCrypto by keyFactory {
            override fun createKey(): VaultKey =
                throw AssertionError("Recovery preparation must not create a content key")
        }
        val passphrase = "a sufficiently long passphrase".toCharArray()

        val prepared = try {
            RecoveryEnvelopePreparer(
                vaultId = vaultId,
                keyStore = ExistingOnlyKeyStore(established),
                crypto = crypto,
                secureRandom = CapturingSecureRandom(),
            ).prepare(passphrase)
        } finally {
            passphrase.fill('\u0000')
        }

        prepared.close()
    }

    @Test
    fun wrongPassphraseVerificationFailsAndClearsOwnedCandidateBuffers() {
        val delegate = TinkVaultCrypto()
        val established = delegate.createKey()
        val crypto = WrongWrappingPassphraseVaultCrypto(delegate)
        val passphrase = "the submitted passphrase".toCharArray()

        assertThrows(InvalidRecoveryPassphraseException::class.java) {
            try {
                RecoveryEnvelopePreparer(
                    vaultId = vaultId,
                    keyStore = ExistingOnlyKeyStore(established),
                    crypto = crypto,
                    secureRandom = CapturingSecureRandom(),
                ).prepare(passphrase)
            } finally {
                passphrase.fill('\u0000')
            }
        }

        assertArrayEquals(CharArray(passphrase.size), passphrase)
        assertAllZero(checkNotNull(crypto.envelope).kdf.salt)
        assertAllZero(checkNotNull(crypto.envelope).nonce)
        assertAllZero(checkNotNull(crypto.envelope).wrappedKeyset)
        assertKeyClosed(established, delegate)
    }

    @Test
    fun tamperedEnvelopeVerificationFailsBeforeChallengeAndClearsEnvelope() {
        val delegate = TinkVaultCrypto()
        val established = delegate.createKey()
        val crypto = TamperingEnvelopeVaultCrypto(delegate)
        val random = CapturingSecureRandom()
        val passphrase = "the submitted passphrase".toCharArray()

        assertThrows(InvalidRecoveryPassphraseException::class.java) {
            try {
                RecoveryEnvelopePreparer(
                    vaultId = vaultId,
                    keyStore = ExistingOnlyKeyStore(established),
                    crypto = crypto,
                    secureRandom = random,
                ).prepare(passphrase)
            } finally {
                passphrase.fill('\u0000')
            }
        }

        assertEquals(null, random.challenge)
        assertAllZero(checkNotNull(crypto.envelope).kdf.salt)
        assertAllZero(checkNotNull(crypto.envelope).nonce)
        assertAllZero(checkNotNull(crypto.envelope).wrappedKeyset)
        assertKeyClosed(established, delegate)
    }

    @Test
    fun candidateForAnotherKeyFailsRandomChallengeProofAndIsClosed() {
        val delegate = TinkVaultCrypto()
        val established = delegate.createKey()
        val crypto = DifferentCandidateVaultCrypto(delegate)
        val random = CapturingSecureRandom()
        val passphrase = "the submitted passphrase".toCharArray()

        assertThrows(GeneralSecurityException::class.java) {
            try {
                RecoveryEnvelopePreparer(
                    vaultId = vaultId,
                    keyStore = ExistingOnlyKeyStore(established),
                    crypto = crypto,
                    secureRandom = random,
                ).prepare(passphrase)
            } finally {
                passphrase.fill('\u0000')
            }
        }

        assertAllZero(checkNotNull(random.challenge))
        assertAllZero(checkNotNull(crypto.encryptAssociatedData))
        assertAllZero(checkNotNull(crypto.decryptAssociatedData))
        assertAllZero(checkNotNull(crypto.challengeCiphertext))
        assertKeyClosed(established, delegate)
        assertKeyClosed(checkNotNull(crypto.candidate), delegate)
        assertAllZero(checkNotNull(crypto.envelope).kdf.salt)
        assertAllZero(checkNotNull(crypto.envelope).nonce)
        assertAllZero(checkNotNull(crypto.envelope).wrappedKeyset)
    }

    @Test
    fun unequalChallengePlaintextFailsConstantTimeProofAndClearsPlaintext() {
        val delegate = TinkVaultCrypto()
        val established = delegate.createKey()
        val crypto = AlteredPlaintextVaultCrypto(delegate)
        val random = CapturingSecureRandom()
        val passphrase = "the submitted passphrase".toCharArray()

        assertThrows(IllegalStateException::class.java) {
            try {
                RecoveryEnvelopePreparer(
                    vaultId = vaultId,
                    keyStore = ExistingOnlyKeyStore(established),
                    crypto = crypto,
                    secureRandom = random,
                ).prepare(passphrase)
            } finally {
                passphrase.fill('\u0000')
            }
        }

        assertAllZero(checkNotNull(random.challenge))
        assertAllZero(checkNotNull(crypto.alteredPlaintext))
        assertAllZero(checkNotNull(crypto.challengeCiphertext))
        assertAllZero(checkNotNull(crypto.encryptAssociatedData))
        assertAllZero(checkNotNull(crypto.decryptAssociatedData))
        assertAllZero(checkNotNull(crypto.envelope).kdf.salt)
        assertAllZero(checkNotNull(crypto.envelope).nonce)
        assertAllZero(checkNotNull(crypto.envelope).wrappedKeyset)
        assertKeyClosed(established, delegate)
        assertKeyClosed(checkNotNull(crypto.candidate), delegate)
    }

    private fun assertKeyClosed(
        key: VaultKey,
        crypto: VaultCrypto,
    ) {
        assertThrows(IllegalStateException::class.java) {
            crypto.encryptBytes(key, byteArrayOf(1), byteArrayOf(2))
        }
    }

    private fun assertAllZero(bytes: ByteArray) {
        assertTrue(bytes.all { it == 0.toByte() })
    }

    private class ExistingOnlyKeyStore(
        private val established: VaultKey,
    ) : VaultContentKeyStore {
        override fun getOrCreate(vaultId: VaultId): VaultKey =
            throw AssertionError("Recovery preparation must not bootstrap a content key")

        override fun openExisting(vaultId: VaultId): VaultKey = established

        override fun replace(vaultId: VaultId, key: VaultKey) =
            throw AssertionError("Recovery preparation must not replace the content key")

        override fun delete(vaultId: VaultId) =
            throw AssertionError("Recovery preparation must not delete the content key")
    }

    private class CapturingSecureRandom : SecureRandom() {
        var challenge: ByteArray? = null
            private set

        override fun nextBytes(bytes: ByteArray) {
            challenge = bytes
            bytes.indices.forEach { index -> bytes[index] = (index + 1).toByte() }
        }
    }

    private open class InspectingVaultCrypto(
        protected val delegate: VaultCrypto,
    ) : VaultCrypto by delegate {
        var unlockedCandidate: VaultKey? = null
            protected set
        var encryptAssociatedData: ByteArray? = null
            protected set
        var decryptAssociatedData: ByteArray? = null
            protected set
        var challengeCiphertext: ByteArray? = null
            protected set
        var decryptedChallenge: ByteArray? = null
            protected set

        override fun unlock(
            passphrase: CharArray,
            envelope: VaultKeyEnvelope,
        ): VaultKey = delegate.unlock(passphrase, envelope).also { unlockedCandidate = it }

        override fun encryptBytes(
            key: VaultKey,
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray {
            encryptAssociatedData = associatedData
            return delegate.encryptBytes(key, plaintext, associatedData)
                .also { challengeCiphertext = it }
        }

        override fun decryptBytes(
            key: VaultKey,
            ciphertext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray {
            decryptAssociatedData = associatedData
            return delegate.decryptBytes(key, ciphertext, associatedData)
                .also { decryptedChallenge = it }
        }
    }

    private class WrongWrappingPassphraseVaultCrypto(
        delegate: VaultCrypto,
    ) : InspectingVaultCrypto(delegate) {
        var envelope: VaultKeyEnvelope? = null
            private set

        override fun wrapForRecovery(
            unlockedKey: VaultKey,
            passphrase: CharArray,
        ): VaultKeyEnvelope {
            val wrong = "a different passphrase".toCharArray()
            return try {
                delegate.wrapForRecovery(unlockedKey, wrong).also { envelope = it }
            } finally {
                wrong.fill('\u0000')
            }
        }
    }

    private class TamperingEnvelopeVaultCrypto(
        delegate: VaultCrypto,
    ) : InspectingVaultCrypto(delegate) {
        var envelope: VaultKeyEnvelope? = null
            private set

        override fun wrapForRecovery(
            unlockedKey: VaultKey,
            passphrase: CharArray,
        ): VaultKeyEnvelope =
            delegate.wrapForRecovery(unlockedKey, passphrase).also {
                it.wrappedKeyset[it.wrappedKeyset.lastIndex] =
                    (it.wrappedKeyset.last().toInt() xor 0x01).toByte()
                envelope = it
            }
    }

    private class DifferentCandidateVaultCrypto(
        delegate: VaultCrypto,
    ) : InspectingVaultCrypto(delegate) {
        var candidate: VaultKey? = null
            private set
        var envelope: VaultKeyEnvelope? = null
            private set

        override fun wrapForRecovery(
            unlockedKey: VaultKey,
            passphrase: CharArray,
        ): VaultKeyEnvelope =
            delegate.wrapForRecovery(unlockedKey, passphrase).also { envelope = it }

        override fun unlock(
            passphrase: CharArray,
            envelope: VaultKeyEnvelope,
        ): VaultKey = delegate.createKey().also {
            candidate = it
            unlockedCandidate = it
        }
    }

    private class AlteredPlaintextVaultCrypto(
        delegate: VaultCrypto,
    ) : InspectingVaultCrypto(delegate) {
        var candidate: VaultKey? = null
            private set
        var envelope: VaultKeyEnvelope? = null
            private set
        var alteredPlaintext: ByteArray? = null
            private set

        override fun wrapForRecovery(
            unlockedKey: VaultKey,
            passphrase: CharArray,
        ): VaultKeyEnvelope =
            delegate.wrapForRecovery(unlockedKey, passphrase).also { envelope = it }

        override fun unlock(
            passphrase: CharArray,
            envelope: VaultKeyEnvelope,
        ): VaultKey = super.unlock(passphrase, envelope).also { candidate = it }

        override fun decryptBytes(
            key: VaultKey,
            ciphertext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray {
            decryptAssociatedData = associatedData
            return delegate.decryptBytes(key, ciphertext, associatedData)
                .also {
                    it[0] = (it[0].toInt() xor 0x01).toByte()
                    alteredPlaintext = it
                }
        }
    }
}
