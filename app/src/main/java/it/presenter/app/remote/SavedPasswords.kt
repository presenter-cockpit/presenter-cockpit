package it.presenter.app.remote

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Server passwords remembered across launches, **only with biometrics** (user's choice): encrypted
 * with an AES key of the Android Keystore usable once per fingerprint (BiometricPrompt with the cipher,
 * no PIN). Only the ciphertext stays in the app's files. Enrolling a new fingerprint invalidates the
 * key: the passwords are forgotten.
 *
 * Once decrypted, the password stays in memory in the connection ([SshLink.password]) for automatic
 * reconnections: the fingerprint is needed once per server at each app launch.
 */
object SavedPasswords {
    private const val ALIAS = "presenter-passwords"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("saved_passwords", Context.MODE_PRIVATE)
    }

    fun has(serverId: String): Boolean = prefs.contains(serverId)

    fun put(serverId: String, blob: String) = prefs.edit().putString(serverId, blob).apply()

    fun remove(serverId: String) = prefs.edit().remove(serverId).apply()

    private fun clearAll() {
        prefs.edit().clear().apply()
        runCatching { store().deleteEntry(ALIAS) }
    }

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (store().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        // Authentication on every use and biometric only (on Android 10 this is already the case without a duration).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(builder.build())
            generateKey()
        }
    }

    /** Cipher to save a password, to unlock with the fingerprint. An invalidated key is recreated. */
    fun encryptCipher(): Cipher = try {
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
    } catch (e: KeyPermanentlyInvalidatedException) {
        clearAll()
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
    }

    /** Text to save with [put]: initialization vector and encrypted password. [cipher] unlocked by the fingerprint. */
    fun encrypt(cipher: Cipher, password: String): String {
        val data = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /**
     * Cipher to read the password of [serverId], to unlock with the fingerprint; null if there is none or
     * it can no longer be read (new fingerprint enrolled, data from another device): then it is forgotten
     * and the password must be entered again.
     */
    fun decryptCipher(serverId: String): Cipher? {
        val blob = prefs.getString(serverId, null) ?: return null
        return try {
            val iv = Base64.decode(blob.substringBefore(':'), Base64.NO_WRAP)
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            clearAll()
            null
        } catch (e: Exception) {
            remove(serverId)
            null
        }
    }

    /**
     * Password of [serverId] with [cipher] unlocked by the fingerprint; null if it cannot be decrypted (and it is
     * forgotten).
     */
    fun decrypt(cipher: Cipher, serverId: String): String? {
        val blob = prefs.getString(serverId, null) ?: return null
        return runCatching { String(cipher.doFinal(Base64.decode(blob.substringAfter(':'), Base64.NO_WRAP)), Charsets.UTF_8) }
            .onFailure { remove(serverId) }
            .getOrNull()
    }
}
