package com.example.p2pcodec2

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val secretB64 = prefs.getString(KEY_PHOTO_ACCOUNT_SECRET, null)
                ?: return@withContext Result.success() // not logged in — skip silently

            val photoSecret = Base64.decode(secretB64, Base64.NO_WRAP)
            val key = deriveBackupAesKey(photoSecret)

            val plaintext = buildBackupJson(prefs).toByteArray(Charsets.UTF_8)
            val iv = ByteArray(BACKUP_IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plaintext)

            // Write to external files dir (no permissions needed, scoped to app)
            val dir = applicationContext.getExternalFilesDir(null)
                ?: applicationContext.filesDir
            val ts = System.currentTimeMillis()
            val newFile = File(dir, "xlink_backup_$ts.xlinkbak")
            newFile.outputStream().use { out ->
                out.write(BACKUP_MAGIC.toByteArray(Charsets.US_ASCII)) // 8 bytes
                out.write(BACKUP_VERSION)                               // 1 byte
                out.write(iv)                                           // 12 bytes
                out.write(ciphertext)
            }

            // Delete previous backup file
            val prevPath = prefs.getString(KEY_BACKUP_LAST_PATH, null)
            if (prevPath != null) {
                val prev = File(prevPath)
                if (prev.exists()) prev.delete()
            }

            // Save new path
            prefs.edit().putString(KEY_BACKUP_LAST_PATH, newFile.absolutePath).apply()

            Log.i(LOG_TAG, "Auto-backup written: ${newFile.name}")

            // Re-schedule for the next day's 4 AM
            schedule(applicationContext)

            Result.success()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Auto-backup failed", e)
            Result.retry()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers (duplicated from MainActivity to keep Worker self-contained)
    // ──────────────────────────────────────────────────────────────────────

    private fun deriveBackupAesKey(photoSecret: ByteArray): SecretKey {
        val info = "xlink-backup-v1:".toByteArray(Charsets.UTF_8)
        return SecretKeySpec(sha256(info + photoSecret), "AES")
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * Output must match the format MainActivity.restoreFromJson() expects:
     * { v, ts, localId, contacts: [{id, name}], chats: [{id, log}] }
     *
     * Previously this Worker wrote a single flat `contacts[]` array with
     * embedded `log` fields. That format was unreadable by Restore → auto-
     * backups silently couldn't be restored.
     *
     * Also: KEY_CONTACT_IDS is a StringSet in prefs, not a comma-separated
     * String. The old comma-split path returned empty contact lists every run.
     */
    private fun buildBackupJson(prefs: android.content.SharedPreferences): String {
        val contactIds = prefs.getStringSet(KEY_CONTACT_IDS, emptySet()).orEmpty()

        val contactsArr = JSONArray()
        val chatsArr = JSONArray()
        for (cid in contactIds) {
            val name = prefs.getString("$KEY_CONTACT_PREFIX$cid", cid) ?: cid
            contactsArr.put(JSONObject()
                .put("id", cid)
                .put("name", name))
            val log = prefs.getString("$KEY_CHAT_LOG_PREFIX$cid", "") ?: ""
            if (log.isNotEmpty()) {
                chatsArr.put(JSONObject()
                    .put("id", cid)
                    .put("log", log))
            }
        }

        return JSONObject()
            .put("v", BACKUP_VERSION)
            .put("ts", System.currentTimeMillis())
            .put("localId", prefs.getString(KEY_LOCAL_ID, "") ?: "")
            .put("contacts", contactsArr)
            .put("chats", chatsArr)
            .toString()
    }

    // ──────────────────────────────────────────────────────────────────────
    companion object {
        private const val LOG_TAG           = "BackupWorker"
        private const val WORK_NAME         = "daily_backup"

        // Must match MainActivity constants
        const val PREFS_NAME               = "xxxlink_prefs"
        const val KEY_LOCAL_ID             = "local_id"
        const val KEY_PHOTO_ACCOUNT_SECRET = "photo_account_secret"
        const val KEY_CONTACT_IDS          = "contact_ids"
        const val KEY_CONTACT_PREFIX       = "contact_name_"
        const val KEY_CHAT_LOG_PREFIX      = "chat_log_"
        const val KEY_BACKUP_LAST_PATH     = "backup_last_path"
        private const val BACKUP_MAGIC     = "XLINKBAK"
        private const val BACKUP_VERSION   = 2
        private const val BACKUP_IV_BYTES  = 12

        /** Call once from MainActivity.startCore(). Idempotent — WorkManager deduplicates by name. */
        fun schedule(context: Context) {
            val delayMs = millisUntilNextFourAm()

            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build()
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )

            Log.i(LOG_TAG, "Backup scheduled in ${delayMs / 60_000} min")
        }

        /** Milliseconds until the next 04:00 local time (≥ 1 min from now). */
        fun millisUntilNextFourAm(): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 4)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_MONTH, 1) // already past 4 AM today
            }
            val diff = target.timeInMillis - now.timeInMillis
            return diff.coerceAtLeast(60_000L) // never less than 1 min
        }
    }
}
