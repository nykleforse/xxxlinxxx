package com.example.xxxlinkxxx.desktop.security

import com.example.xxxlinkxxx.desktop.crypto.Crypto
import com.example.xxxlinkxxx.desktop.storage.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup file codec — wire-compatible with the Android v1.14.18 v3 format:
 *
 *   [magic:8 = "XLINKBAK"][version:1 = 3][salt:16][iv:12][AES-256-GCM ct]
 *
 * Key derivation:
 *   AES-256 key = PBKDF2-HMAC-SHA256(photoSecret, salt, 600k iter, 32 B)
 *
 * Payload (plaintext JSON before AES):
 *   {
 *     "v": 3,
 *     "ts": <millis>,
 *     "localId": "...",
 *     "contacts": [{"id":"...","name":"..."}, ...],
 *     "chats":    [{"id":"...","log":"..."}, ...]
 *   }
 *
 * The same .xlinkbak file written from the desktop restores on Android
 * and vice-versa as long as the photo secret matches — i.e. the user
 * picks the same identity-photo on both ends.
 */
object BackupCodec {

    private val MAGIC = "XLINKBAK".toByteArray(Charsets.US_ASCII)
    private const val VERSION_V3: Byte = 3
    private const val PBKDF2_ITERS = 600_000
    private const val KEY_LEN_BYTES = 32
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    fun export(prefs: SecurePrefs, photoSecret: ByteArray, outFile: File) {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = SecretKeySpec(
            Crypto.pbkdf2(photoSecret, salt, PBKDF2_ITERS, KEY_LEN_BYTES),
            "AES"
        )
        val plaintext = buildBackupJson(prefs).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext)
        outFile.parentFile?.mkdirs()
        outFile.outputStream().use { out ->
            out.write(MAGIC)
            out.write(VERSION_V3.toInt())
            out.write(salt)
            out.write(iv)
            out.write(ct)
        }
    }

    /** Restores into the open vault. Throws if magic/version/decrypt fail. */
    fun restore(prefs: SecurePrefs, photoSecret: ByteArray, inFile: File): RestoreResult {
        val raw = inFile.readBytes()
        require(raw.size > MAGIC.size + 1 + SALT_LEN + IV_LEN) { "Backup file too small" }
        val magic = raw.copyOfRange(0, MAGIC.size)
        require(magic.contentEquals(MAGIC)) { "Not an XLINKBAK file" }
        val version = raw[MAGIC.size]
        require(version == VERSION_V3) { "Unsupported backup version $version (only v3)" }
        val salt = raw.copyOfRange(MAGIC.size + 1, MAGIC.size + 1 + SALT_LEN)
        val iv = raw.copyOfRange(
            MAGIC.size + 1 + SALT_LEN,
            MAGIC.size + 1 + SALT_LEN + IV_LEN
        )
        val ct = raw.copyOfRange(MAGIC.size + 1 + SALT_LEN + IV_LEN, raw.size)

        val key = SecretKeySpec(
            Crypto.pbkdf2(photoSecret, salt, PBKDF2_ITERS, KEY_LEN_BYTES),
            "AES"
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(ct)
        val root = JSONObject(String(plaintext, Charsets.UTF_8))

        val contacts = root.optJSONArray("contacts") ?: JSONArray()
        val chats = root.optJSONArray("chats") ?: JSONArray()
        val contactIds = mutableSetOf<String>()
        val edit = prefs.edit()
        for (i in 0 until contacts.length()) {
            val c = contacts.getJSONObject(i)
            val id = c.getString("id")
            contactIds.add(id)
            edit.putString("contact_name_$id", c.optString("name", id))
        }
        for (i in 0 until chats.length()) {
            val c = chats.getJSONObject(i)
            val id = c.getString("id")
            val log = c.optString("log", "")
            if (log.isNotEmpty()) edit.putString("chat_log_$id", log)
        }
        edit.putStringSet("contact_ids", contactIds).apply()

        return RestoreResult(
            localId = root.optString("localId"),
            contactCount = contacts.length(),
            chatCount = chats.length(),
        )
    }

    private fun buildBackupJson(prefs: SecurePrefs): JSONObject {
        val contactIds = prefs.getStringSet("contact_ids", emptySet())
        val contactsArr = JSONArray()
        val chatsArr = JSONArray()
        for (cid in contactIds) {
            val name = prefs.getString("contact_name_$cid", cid) ?: cid
            contactsArr.put(JSONObject().put("id", cid).put("name", name))
            val log = prefs.getString("chat_log_$cid", "") ?: ""
            if (log.isNotEmpty()) {
                chatsArr.put(JSONObject().put("id", cid).put("log", log))
            }
        }
        return JSONObject()
            .put("v", 3)
            .put("ts", System.currentTimeMillis())
            .put("localId", prefs.getString("local_id", "") ?: "")
            .put("contacts", contactsArr)
            .put("chats", chatsArr)
    }

    data class RestoreResult(val localId: String, val contactCount: Int, val chatCount: Int)
}
