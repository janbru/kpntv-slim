package nl.kpn.slim

import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/** Who this install is: the KPN account number and the deviceId that was registered to it at sign-in. */
data class Identity(val account: String, val deviceId: String)

/**
 * Keeps the [Identity] in the app's private preferences. The pincode is never stored: it is only sent
 * to KPN once, at sign-in, and from then on sessions are minted from the deviceId alone.
 */
object IdentityStore {
    private const val KEY_ACCOUNT = "account"
    private const val KEY_DEVICE = "deviceId"

    fun load(prefs: SharedPreferences): Identity? {
        val account = prefs.getString(KEY_ACCOUNT, null).orEmpty()
        val device = prefs.getString(KEY_DEVICE, null).orEmpty()
        if (account.isNotEmpty() && device.isNotEmpty()) return Identity(account, device)
        // Optional developer seed from gradle.properties (an identity that is already registered).
        if (BuildConfig.KPN_ACCOUNT.isNotEmpty() && BuildConfig.KPN_DEVICE_ID.isNotEmpty()) {
            return Identity(BuildConfig.KPN_ACCOUNT, BuildConfig.KPN_DEVICE_ID).also { save(prefs, it) }
        }
        return null
    }

    fun save(prefs: SharedPreferences, id: Identity) {
        prefs.edit().putString(KEY_ACCOUNT, id.account).putString(KEY_DEVICE, id.deviceId).apply()
    }

    /**
     * A fresh random deviceId: 64 hex characters (SHA-256), the same shape the official clients send.
     * The web player also makes its own up (a hash over random input); the server registers whatever id
     * the first sign-in presents.
     */
    fun newDeviceId(): String {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return MessageDigest.getInstance("SHA-256").digest(seed).joinToString("") { "%02X".format(it) }
    }
}
