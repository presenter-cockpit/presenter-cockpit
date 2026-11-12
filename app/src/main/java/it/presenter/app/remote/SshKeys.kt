package it.presenter.app.remote

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import com.jcraft.jsch.Identity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The app's SSH key: ECDSA P-256 (`ecdsa-sha2-nistp256`) generated in the Android Keystore, so the
 * private part never leaves the tablet. Signing needs the fingerprint (or PIN), valid for
 * [AUTH_VALIDITY_S] seconds: during a presentation automatic reconnections do not ask for it again
 * (user's choice: once, then 30 minutes).
 */
object SshKeys {
    private const val ALIAS = "presenter-ssh"
    private const val KEY_TYPE = "ecdsa-sha2-nistp256"
    private const val CURVE = "nistp256"
    const val AUTH_VALIDITY_S = 30 * 60

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(): Boolean = runCatching { store().containsAlias(ALIAS) }.getOrDefault(false)

    /**
     * Generates (or regenerates) the key. The public part must then be copied to the server's `~/.ssh/authorized_keys`.
     */
    fun generate() {
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                AUTH_VALIDITY_S,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_S)
        }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(builder.build())
            generateKeyPair()
        }
    }

    fun delete() {
        runCatching { store().deleteEntry(ALIAS) }
    }

    private fun publicKey(): ECPublicKey? = runCatching { store().getCertificate(ALIAS)?.publicKey as? ECPublicKey }.getOrNull()

    private fun privateKey(): PrivateKey? = runCatching { store().getKey(ALIAS, null) as? PrivateKey }.getOrNull()

    /** Public key as an `authorized_keys` line. */
    fun publicKeyLine(comment: String = "presenter@tablet"): String? {
        val blob = publicBlob() ?: return null
        return "$KEY_TYPE ${Base64.encodeToString(blob, Base64.NO_WRAP)} $comment"
    }

    /** Public key in SSH protocol format (type, curve, uncompressed point). */
    fun publicBlob(): ByteArray? {
        val key = publicKey() ?: return null
        val q = ByteArray(65)
        q[0] = 4
        fixed(key.w.affineX).copyInto(q, 1)
        fixed(key.w.affineY).copyInto(q, 33)
        return wire { string(KEY_TYPE.toByteArray()); string(CURVE.toByteArray()); string(q) }
    }

    /**
     * State of the key: usable without asking for the fingerprint (unlocked less than [AUTH_VALIDITY_S]
     * ago), to unlock, or no longer valid.
     */
    enum class KeyState { UNLOCKED, LOCKED, INVALID }

    /**
     * State of the key, found by trying to sign: unlocked, to unlock with the fingerprint, or no longer
     * valid (screen lock removed or changed: it must be regenerated, the fingerprint is not enough).
     */
    fun state(): KeyState = try {
        sign(byteArrayOf(0))
        KeyState.UNLOCKED
    } catch (e: Throwable) {
        if (generateSequence(e) { it.cause }.any { it is KeyPermanentlyInvalidatedException }) KeyState.INVALID
        else KeyState.LOCKED
    }

    fun isUnlocked(): Boolean = state() == KeyState.UNLOCKED

    /** Signing failed because the fingerprint is needed. */
    fun needsAuth(e: Throwable?): Boolean = generateSequence(e) { it.cause }.any { it is UserNotAuthenticatedException }

    /** SSH signature (blob with type and the r, s pair) of [data]. */
    fun sign(data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey() ?: error("no key"))
            update(data)
            sign()
        }
        val (r, s) = derToRs(der)
        val inner = wire { mpint(r); mpint(s) }
        return wire { string(KEY_TYPE.toByteArray()); string(inner) }
    }

    /** Identity for JSch: the Keystore does the signing. */
    val identity: Identity = object : Identity {
        override fun setPassphrase(passphrase: ByteArray?) = true
        override fun getPublicKeyBlob(): ByteArray? = publicBlob()
        override fun getSignature(data: ByteArray): ByteArray? = runCatching { sign(data) }.getOrNull()
        override fun getAlgName() = KEY_TYPE
        override fun getName() = ALIAS
        override fun isEncrypted() = false
        override fun clear() = Unit
    }

    // ------------------------------------------------------------------ encoding

    private fun fixed(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    /** DER signature (SEQUENCE of two INTEGERs) → r, s. */
    internal fun derToRs(der: ByteArray): Pair<BigInteger, BigInteger> {
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i = 2 + (der[1].toInt() and 0x7F)
        fun int(): BigInteger {
            require(der[i].toInt() == 0x02)
            val len = der[i + 1].toInt() and 0xFF
            val v = BigInteger(der.copyOfRange(i + 2, i + 2 + len))
            i += 2 + len
            return v
        }
        val r = int()
        val s = int()
        return r to s
    }

    private class Wire(val d: DataOutputStream) {
        fun string(b: ByteArray) {
            d.writeInt(b.size)
            d.write(b)
        }

        fun mpint(v: BigInteger) = string(v.toByteArray())
    }

    private fun wire(block: Wire.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { Wire(it).block() }
        return out.toByteArray()
    }
}
