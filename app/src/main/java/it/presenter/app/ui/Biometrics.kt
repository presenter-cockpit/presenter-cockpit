package it.presenter.app.ui

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import it.presenter.app.R
import javax.crypto.Cipher

/** Fingerprint prompts of the remote connections: the SSH key in the Keystore and the saved passwords. */
object Biometrics {
    /** A strong fingerprint is enrolled: it can unlock the cipher of the saved passwords. */
    fun canUnlockCiphers(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Fingerprint (or PIN) that unlocks the Keystore for the SSH key; then [onOk]. */
    fun unlockKey(activity: FragmentActivity, onOk: () -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.remote_unlock_title))
            .setSubtitle(activity.getString(R.string.remote_unlock_subtitle))
            .apply {
                // Android 10 does not accept "strong" fingerprint together with the PIN: the old option is used there.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                }
            }
            .build()
        prompt(activity) { onOk() }.authenticate(info)
    }

    /** Fingerprint only, no PIN (user's choice), that unlocks [cipher]; [onOk] receives it unlocked. */
    fun unlockCipher(activity: FragmentActivity, cipher: Cipher, subtitle: String, onOk: (Cipher) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.remote_password_prompt))
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText(activity.getString(R.string.action_cancel))
            .build()
        prompt(activity) { result -> result.cryptoObject?.cipher?.let(onOk) }
            .authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun prompt(activity: FragmentActivity, onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit) =
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess(result)
            },
        )
}
