package com.example.p2pcodec2

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Drop-in replacement for `context.getSharedPreferences("xxxlink_prefs", MODE_PRIVATE)`
 * that returns an [EncryptedSharedPreferences] instance backed by a Keystore-wrapped
 * AES-256 master key.
 *
 * On the very first call after upgrading from v1.14.18 or earlier, any existing
 * plaintext prefs in the legacy file are copied across, then the legacy file is
 * cleared so secrets never sit on disk in cleartext after the upgrade.
 *
 * Fallback: if Keystore is unavailable (corrupted, OEM bug, factory-reset edge),
 * falls back to the plain SharedPreferences so the app can still launch; the
 * caller logs a warning but does not crash.
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val ENCRYPTED_NAME = "xxxlink_prefs_secure"
    private const val LEGACY_NAME = "xxxlink_prefs"
    private const val MIGRATION_MARKER = "_secure_migration_done"

    @Volatile private var cached: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = openEncrypted(context.applicationContext) ?: openLegacy(context.applicationContext)
            migrateIfNeeded(context.applicationContext, prefs)
            cached = prefs
            return prefs
        }
    }

    private fun openEncrypted(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences init failed; falling back to plain prefs: ${e.message}")
        null
    }

    private fun openLegacy(context: Context): SharedPreferences =
        context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)

    /**
     * Copies all key/value pairs from the legacy plaintext file into the
     * encrypted file (one-time), then clears the legacy file so no secret
     * remains on disk in cleartext. Idempotent: tracked via [MIGRATION_MARKER].
     */
    private fun migrateIfNeeded(context: Context, target: SharedPreferences) {
        if (target.getBoolean(MIGRATION_MARKER, false)) return
        val legacy = context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
        // If the legacy file is also our target (encrypted init failed), no-op.
        if (legacy === target) return
        val all = legacy.all
        if (all.isEmpty()) {
            target.edit().putBoolean(MIGRATION_MARKER, true).apply()
            return
        }
        val edit = target.edit()
        all.forEach { (k, v) ->
            when (v) {
                is String -> edit.putString(k, v)
                is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v)
                is Boolean -> edit.putBoolean(k, v)
                is Float -> edit.putFloat(k, v)
                is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(k, v as Set<String>)
            }
        }
        edit.putBoolean(MIGRATION_MARKER, true)
        if (edit.commit()) {
            legacy.edit().clear().apply()
            Log.d(TAG, "Migrated ${all.size} entries from legacy prefs to EncryptedSharedPreferences")
        } else {
            Log.w(TAG, "Migration commit failed — leaving legacy file in place")
        }
    }
}
