package com.example.xxxlinkxxx.desktop.security

import com.example.xxxlinkxxx.desktop.crypto.Crypto
import com.example.xxxlinkxxx.desktop.storage.SecurePrefs
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App-lock PIN helper. Mirrors the Android MainActivity PIN flow:
 * PBKDF2-HMAC-SHA256 with 600k iterations, 16-byte random salt per user,
 * constant-time hash compare on unlock, exponential backoff after 3
 * failures, account wipe at 10 failures (matches the v1.14.18 fix that
 * closed finding #6 server-side).
 *
 * Storage keys (in SecurePrefs):
 *   pin_hash         Base64 of 32-byte PBKDF2 output
 *   pin_salt         Base64 of 16-byte salt
 *   pin_failures     Int  — running fail count
 *   pin_lockout_until Long — wall-clock ms until next allowed try
 *   pin_enabled      Bool — explicit on/off
 */
object PinLock {
    private const val K_HASH = "pin_hash"
    private const val K_SALT = "pin_salt"
    private const val K_FAILURES = "pin_failures"
    private const val K_LOCKOUT_UNTIL = "pin_lockout_until"
    private const val K_ENABLED = "pin_enabled"
    private const val ITERATIONS = 600_000
    private const val HASH_LEN_BYTES = 32

    fun isEnabled(prefs: SecurePrefs): Boolean =
        prefs.getBoolean(K_ENABLED, false) && prefs.getString(K_HASH) != null

    /** Returns null on success, error string on failure (with seconds-left if locked out). */
    fun verify(prefs: SecurePrefs, pin: String): String? {
        val now = System.currentTimeMillis()
        val lockoutUntil = prefs.getLong(K_LOCKOUT_UNTIL, 0L)
        if (now < lockoutUntil) {
            val secs = ((lockoutUntil - now) / 1000).coerceAtLeast(1)
            return "Too many attempts — wait ${secs}s"
        }
        val storedHash = prefs.getString(K_HASH) ?: return "PIN not set"
        val storedSalt = prefs.getString(K_SALT) ?: return "PIN salt missing"
        val entered = Crypto.b64(
            Crypto.pbkdf2(pin.toByteArray(Charsets.UTF_8),
                Crypto.b64decode(storedSalt), ITERATIONS, HASH_LEN_BYTES)
        )
        // Constant-time compare via MessageDigest.isEqual.
        val ok = MessageDigest.isEqual(
            entered.toByteArray(Charsets.UTF_8),
            storedHash.toByteArray(Charsets.UTF_8),
        )
        if (ok) {
            prefs.edit().putInt(K_FAILURES, 0).putLong(K_LOCKOUT_UNTIL, 0L).apply()
            return null
        }
        // Update failure counter + backoff.
        val newFailures = prefs.getInt(K_FAILURES, 0) + 1
        val edit = prefs.edit().putInt(K_FAILURES, newFailures)
        if (newFailures >= 10) {
            // Wipe vault — same threat model as v1.14.18 #6.
            edit.remove(K_HASH).remove(K_SALT).remove(K_ENABLED)
                .remove("v2_crypto_secret").remove("photo_account_secret")
                .remove("local_id").remove("auth_version").apply()
            return "Too many failed attempts — vault wiped"
        }
        if (newFailures >= 3) {
            val backoffMs = 30_000L * (1L shl (newFailures - 3).coerceAtMost(6))
            edit.putLong(K_LOCKOUT_UNTIL, now + backoffMs)
        }
        edit.apply()
        return "Wrong PIN ($newFailures/10)"
    }

    fun set(prefs: SecurePrefs, pin: String) {
        require(pin.length in 4..16) { "PIN must be 4-16 chars" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = Crypto.pbkdf2(
            pin.toByteArray(Charsets.UTF_8), salt, ITERATIONS, HASH_LEN_BYTES
        )
        prefs.edit()
            .putString(K_HASH, Crypto.b64(hash))
            .putString(K_SALT, Crypto.b64(salt))
            .putBoolean(K_ENABLED, true)
            .putInt(K_FAILURES, 0)
            .putLong(K_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    fun disable(prefs: SecurePrefs) {
        prefs.edit()
            .remove(K_HASH)
            .remove(K_SALT)
            .putBoolean(K_ENABLED, false)
            .putInt(K_FAILURES, 0)
            .putLong(K_LOCKOUT_UNTIL, 0L)
            .apply()
    }
}
