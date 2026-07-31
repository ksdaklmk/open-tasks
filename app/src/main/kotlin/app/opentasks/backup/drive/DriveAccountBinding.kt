package app.opentasks.backup.drive

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Supplies the non-exportable HMAC-SHA-256 key backing [DriveAccountBinding].
 *
 * The production boundary never returns raw key material: [SecretKey] handles
 * obtained from the Android Keystore only ever authorize a [Mac] operation.
 */
internal fun interface DriveAccountBindingKeyBoundary {
    fun getOrCreateKey(): SecretKey
}

internal class AndroidKeystoreDriveAccountBindingKeyBoundary : DriveAccountBindingKeyBoundary {
    override fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(DriveAccountBinding.KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEYSTORE,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    DriveAccountBinding.KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_SIGN,
                ).build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * Binds a Drive `about.get` permission ID to a per-install HMAC-SHA-256 digest.
 *
 * The permission ID is the only account-identifying input this class ever
 * touches, and it never leaves [digest]: the value is encoded as strict
 * UTF-8, authenticated with a non-exportable Android Keystore key, and the
 * owned encoded buffer is cleared before returning. Only the resulting
 * 32-byte digest — never the raw permission ID, email, or profile — is fit
 * to persist as Task 3's `accountBindingDigest`.
 */
class DriveAccountBinding internal constructor(
    private val keyBoundary: DriveAccountBindingKeyBoundary,
) {
    constructor() : this(AndroidKeystoreDriveAccountBindingKeyBoundary())

    fun digest(permissionId: String): ByteArray {
        val encoded = strictUtf8(permissionId)
        return try {
            val mac = Mac.getInstance(HMAC_ALGORITHM).apply { init(keyBoundary.getOrCreateKey()) }
            val computed = mac.doFinal(encoded)
            check(computed.size == DIGEST_BYTES) {
                "The Drive account-binding digest has an unexpected length"
            }
            computed.copyOf()
        } finally {
            encoded.fill(0)
        }
    }

    private fun strictUtf8(value: String): ByteArray {
        val buffer = StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    companion object {
        const val KEYSTORE_ALIAS = "open_tasks_drive_account_binding_hmac_v1"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val DIGEST_BYTES = 32
    }
}
