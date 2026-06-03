package com.example.xxxlinkxxx.desktop.storage

import com.example.xxxlinkxxx.desktop.crypto.Crypto
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

/**
 * Encrypted key-value store for the desktop client. Matches the Android
 * EncryptedSharedPreferences threat model: nothing on disk is readable
 * without the photo+password-derived secret.
 *
 * Layout: vault.enc under %APPDATA%/XxxLinkDesktop (Windows) or
 * $XDG_DATA_HOME/XxxLinkDesktop (Linux/macOS). File format:
 *   [magic:8="XLINKVLT"] [version:1=1] [iv:12] [ciphertext:rest]
 *
 * Master AES-256 key = SHA-256("prefs-v1:" + photoSecret). The key never
 * touches disk — only photoSecret (after photo auth) reconstructs it.
 * Lose the photo → vault is forever opaque, same as on Android.
 */
class SecurePrefs private constructor(
    private val file: File,
    private var masterKey: SecretKeySpec,
    private var json: JSONObject,
) {

    @Synchronized
    fun getString(key: String, default: String? = null): String? =
        if (json.has(key) && !json.isNull(key)) json.getString(key) else default

    @Synchronized
    fun getInt(key: String, default: Int = 0): Int =
        if (json.has(key)) json.getInt(key) else default

    @Synchronized
    fun getLong(key: String, default: Long = 0L): Long =
        if (json.has(key)) json.getLong(key) else default

    @Synchronized
    fun getBoolean(key: String, default: Boolean = false): Boolean =
        if (json.has(key)) json.getBoolean(key) else default

    @Synchronized
    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> {
        if (!json.has(key)) return default
        val arr = json.getJSONArray(key)
        return (0 until arr.length()).mapTo(mutableSetOf()) { arr.getString(it) }
    }

    @Synchronized
    fun edit(): Editor = Editor(this)

    @Synchronized
    fun all(): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        json.keys().forEach { out[it] = json.get(it) }
        return out
    }

    @Synchronized
    internal fun applyEdits(edits: Map<String, Any?>, removes: Set<String>) {
        edits.forEach { (k, v) ->
            when (v) {
                null -> json.put(k, JSONObject.NULL)
                is Set<*> -> {
                    val arr = org.json.JSONArray()
                    v.forEach { arr.put(it.toString()) }
                    json.put(k, arr)
                }
                else -> json.put(k, v)
            }
        }
        removes.forEach { json.remove(it) }
        flush()
    }

    private fun flush() {
        val plaintext = json.toString().toByteArray(Charsets.UTF_8)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)

        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(MAGIC)
            out.write(VERSION.toInt())
            out.write(iv)
            out.write(ciphertext)
        }
        // Atomic-ish replace
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) error("Could not finalize vault write")
    }

    class Editor internal constructor(private val parent: SecurePrefs) {
        private val edits = mutableMapOf<String, Any?>()
        private val removes = mutableSetOf<String>()

        fun putString(key: String, value: String?): Editor = apply {
            edits[key] = value
            removes.remove(key)
        }

        fun putInt(key: String, value: Int): Editor = apply {
            edits[key] = value
            removes.remove(key)
        }

        fun putLong(key: String, value: Long): Editor = apply {
            edits[key] = value
            removes.remove(key)
        }

        fun putBoolean(key: String, value: Boolean): Editor = apply {
            edits[key] = value
            removes.remove(key)
        }

        fun putStringSet(key: String, value: Set<String>): Editor = apply {
            edits[key] = value
            removes.remove(key)
        }

        fun remove(key: String): Editor = apply {
            removes.add(key)
            edits.remove(key)
        }

        fun apply() {
            parent.applyEdits(edits, removes)
        }
    }

    companion object {
        private val MAGIC = "XLINKVLT".toByteArray(Charsets.US_ASCII) // 8 bytes
        private const val VERSION: Byte = 1

        /**
         * Open (or create) the vault. Throws if file exists but the secret is
         * wrong — caller must catch and re-prompt for photo+password.
         */
        fun open(photoSecret: ByteArray, customFile: File? = null): SecurePrefs {
            val file = customFile ?: defaultVaultFile()
            file.parentFile?.let { Path(it.absolutePath).createDirectories() }
            val masterKey = SecretKeySpec(
                Crypto.sha256("prefs-v1:".toByteArray(Charsets.UTF_8) + photoSecret),
                "AES"
            )
            val json = if (file.exists() && file.length() > MAGIC.size + 1 + 12) {
                decryptVault(file, masterKey)
            } else {
                JSONObject()
            }
            return SecurePrefs(file, masterKey, json)
        }

        private fun decryptVault(file: File, masterKey: SecretKeySpec): JSONObject {
            val raw = file.readBytes()
            val magic = raw.copyOfRange(0, MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Vault magic mismatch" }
            val version = raw[MAGIC.size]
            require(version == VERSION) { "Vault version $version unsupported" }
            val iv = raw.copyOfRange(MAGIC.size + 1, MAGIC.size + 1 + 12)
            val ct = raw.copyOfRange(MAGIC.size + 1 + 12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(ct)
            return JSONObject(String(plaintext, Charsets.UTF_8))
        }

        private fun defaultVaultFile(): File {
            val base = System.getenv("APPDATA")
                ?: System.getenv("XDG_DATA_HOME")
                ?: (System.getProperty("user.home") + File.separator + ".local" + File.separator + "share")
            return File(File(base), "XxxLinkDesktop" + File.separator + "vault.enc")
        }
    }
}
