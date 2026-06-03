package com.example.xxxlinkxxx.desktop.security

import com.example.xxxlinkxxx.desktop.crypto.Crypto
import com.example.xxxlinkxxx.desktop.util.EventLog
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Device-bound auto-login store. Persists the photo secret on disk
 * encrypted under a key derived from machine + OS user identity, so
 * relaunching the desktop client does not require the user to re-pick
 * the photo + retype the password each time. PIN lock (if enabled)
 * still gates access on top.
 *
 * Threat model trade-off, documented:
 *   - Anyone who can read the same Windows user account on the same
 *     machine where the file lives can decrypt it. That matches the
 *     existing "logged-in Windows user trusts their own machine" model.
 *   - File lifted to a different machine or copied to another user
 *     account on the same machine → MachineGuid + os.user mismatch →
 *     decryption fails closed.
 *   - Recommended companion: enable PinLock so a stolen unlocked
 *     screen still hits a PIN prompt before vault contents render.
 *
 * Location: %APPDATA%/.xxxlink/quickstart.dat (Windows),
 *           $XDG_DATA_HOME/.xxxlink/quickstart.dat otherwise.
 * Format:   [magic:8 = "XLINKQS1"][iv:12][AES-256-GCM ciphertext]
 *           Plaintext = the photo secret bytes (32 bytes typical).
 */
object QuickStart {
    private val MAGIC = "XLINKQS1".toByteArray(Charsets.US_ASCII)
    private const val IV_LEN = 12

    private val file: File by lazy {
        val base = System.getenv("APPDATA")
            ?: System.getenv("XDG_DATA_HOME")
            ?: (System.getProperty("user.home") + File.separator + ".local" + File.separator + "share")
        File(File(base, ".xxxlink"), "quickstart.dat")
    }

    fun isPresent(): Boolean = file.exists()

    fun save(photoSecret: ByteArray) {
        val key = deviceKey()
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(photoSecret)
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            out.write(MAGIC)
            out.write(iv)
            out.write(ct)
        }
        EventLog.log("QSTART", "saved")
    }

    /** Returns the photo secret bytes if the on-disk blob decrypts cleanly. */
    fun tryLoad(): ByteArray? {
        if (!file.exists()) return null
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (raw.size < MAGIC.size + IV_LEN + 16) {
            EventLog.log("QSTART", "file too small (${raw.size} bytes)")
            return null
        }
        if (!raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            EventLog.log("QSTART", "magic mismatch")
            return null
        }
        val iv = raw.copyOfRange(MAGIC.size, MAGIC.size + IV_LEN)
        val ct = raw.copyOfRange(MAGIC.size + IV_LEN, raw.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ct)
        }.onFailure { EventLog.log("QSTART", "decrypt failed: ${it.message}") }
            .onSuccess { EventLog.log("QSTART", "loaded ok") }
            .getOrNull()
    }

    fun clear() {
        runCatching { file.delete() }
        EventLog.log("QSTART", "cleared")
    }

    /**
     * Device-bound AES-256 key. Combines:
     *   - Windows MachineGuid (HKLM\\SOFTWARE\\Microsoft\\Cryptography),
     *     stable per OS install. Falls back to os.name on non-Windows.
     *   - System property user.name so a second OS user on the same
     *     machine cannot read the file.
     *   - Static personalization string so the same secret on another
     *     app does not collide.
     */
    private fun deviceKey(): SecretKey {
        val machineGuid = readMachineGuid().ifBlank { System.getProperty("os.name", "unknown") }
        val userName = System.getProperty("user.name", "unknown")
        val seed = "xlink-quickstart-v1:$machineGuid:$userName"
        return SecretKeySpec(Crypto.sha256(seed.toByteArray(Charsets.UTF_8)), "AES")
    }

    private fun readMachineGuid(): String {
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("win")) return ""
        return runCatching {
            val proc = ProcessBuilder(
                "reg", "query",
                "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                "/v", "MachineGuid"
            ).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            text.lineSequence()
                .firstOrNull { it.contains("MachineGuid", ignoreCase = true) }
                ?.substringAfterLast(" ")?.trim()
                .orEmpty()
        }.getOrDefault("")
    }
}
