package os.retinue.ara.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import os.retinue.ara.net.BasicCredentials

/**
 * Seals an endpoint's password for the preferences file with a key that never
 * leaves the Android Keystore (AES-256-GCM, a fresh nonce per sealing). The
 * sealed form is `base64(nonce || ciphertext)`; only this app, on this phone,
 * can open it — a backup restored to another device yields nothing, and the
 * shell then asks for the password again. Nothing here is user-bound: the key
 * works with the phone locked, as the health upload may run then.
 */
object CredentialVault {

    private const val TAG = "CredentialVault"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "retinue-endpoint-secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /** The sealed form of [plain]. */
    @Synchronized
    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val nonce = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(nonce + sealed, Base64.NO_WRAP)
    }

    /** The clear text of [sealed], or null when it cannot be opened (another device's key, a torn value). */
    @Synchronized
    fun open(sealed: String?): String? {
        if (sealed.isNullOrEmpty()) return null
        return try {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            if (bytes.size <= NONCE_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
            String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "stored password could not be opened", e)
            null
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}

/**
 * The user name and password stored for this endpoint, opened for use, or null
 * when none is stored or it cannot be opened on this phone.
 */
fun Endpoint.credentials(): BasicCredentials? {
    val user = username?.takeIf { it.isNotBlank() } ?: return null
    val password = CredentialVault.open(secret) ?: return null
    return BasicCredentials(user, password)
}
