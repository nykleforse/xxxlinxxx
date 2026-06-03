package com.example.xxxlinkxxx.desktop.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM crypto helpers, byte-for-byte compatible with the Android client.
 *
 * Same SHA-256 personalization strings, same PBKDF2 iteration count, same
 * ECIES (ephemeral EC P-256 + ECDH + AES-256-GCM) scheme, same X.509 DER
 * pubkey serialization. A message encrypted by the Android client decrypts
 * correctly here, and vice versa.
 *
 * Ported from app/src/main/java/com/example/xxxlinkxxxclaude/MainActivity.kt
 * sections (1206..1302, 2685..2749). Keep symbols in sync with the Android
 * source — any drift breaks interop with phones on the same account.
 */
object Crypto {

    const val EC_KEY_ALGORITHM = "ECDH/P-256"
    const val AES_MESSAGE_ALGORITHM = "AES/GCM/NoPadding"
    const val V2_PBKDF2_ITERS = 600_000
    private const val AES_GCM_IV_BYTES = 12
    private const val AES_GCM_TAG_BITS = 128
    private const val PHOTO_ACCOUNT_KEY_SALT = "x-link-photo-key-v1"

    private val secureRandom by lazy { SecureRandom() }

    // ── Hash + encoding helpers ──────────────────────────────────────────────

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(it) }

    fun b64(bytes: ByteArray): String =
        Base64.getEncoder().withoutPadding().encodeToString(bytes)

    fun b64decode(value: String): ByteArray =
        Base64.getDecoder().decode(value.trimEnd('='))

    // ── PBKDF2 (v2 auth: photo+password → master) ────────────────────────────

    /** PBKDF2-HMAC-SHA256. password = key material, salt = personalization. */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iters: Int, length: Int): ByteArray {
        val spec = PBEKeySpec(
            password.map { it.toInt().toChar() }.toCharArray(),
            salt,
            iters,
            length * 8
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    // ── Photo auth ───────────────────────────────────────────────────────────

    /**
     * v1 path: SHA-256 of photo, then domain-separated SHA-256 of hex(hash).
     * Returns the secret bytes used to derive localId + EC keypair.
     */
    fun photoAuthSecret(photoBytes: ByteArray): ByteArray {
        val photoHash = sha256(photoBytes)
        return sha256(
            "$PHOTO_ACCOUNT_KEY_SALT:${hex(photoHash)}".toByteArray(Charsets.UTF_8)
        )
    }

    /** 8-char uppercase hex ID, derived deterministically from photo secret. */
    fun deriveLocalId(photoSecret: ByteArray): String =
        sha256("xlink-id-v1:".toByteArray(Charsets.UTF_8) + photoSecret)
            .take(4)
            .joinToString("") { "%02X".format(it) }

    /**
     * v2 path: PBKDF2(password, photoBytes, 600k iter) → 32-byte master.
     * Returns (localId, cryptoSecret).
     */
    fun deriveV2(photoBytes: ByteArray, password: String): Pair<String, ByteArray> {
        val master = pbkdf2(
            password.toByteArray(Charsets.UTF_8),
            photoBytes,
            V2_PBKDF2_ITERS,
            32
        )
        val localBytes = sha256("localid-v2:".toByteArray(Charsets.UTF_8) + master)
        val id = localBytes.take(4).joinToString("") { "%02X".format(it) }
        val crypto = sha256("crypto-v2:".toByteArray(Charsets.UTF_8) + master)
        return id to crypto
    }

    /**
     * v1 → v2 migration: salt = existing v1 secret. localId stays unchanged.
     */
    fun migrateV2(v1Secret: ByteArray, password: String): ByteArray {
        val master = pbkdf2(
            password.toByteArray(Charsets.UTF_8),
            v1Secret,
            V2_PBKDF2_ITERS,
            32
        )
        return sha256("crypto-v2:".toByteArray(Charsets.UTF_8) + master)
    }

    // ── EC keypair (deterministic from photo secret) ─────────────────────────

    /**
     * Derives an EC P-256 keypair deterministically from photo secret.
     * Same photo → same keypair, on any device, with no server involvement.
     * 64 bytes of deterministic material cover any provider's internal retry
     * loop inside JCE.
     */
    fun deriveEcKeyPair(photoSecret: ByteArray): KeyPair {
        val seed = sha256("xlink-ec-key-v1:".toByteArray(Charsets.UTF_8) + photoSecret)
        val material = seed + sha256(seed) // 64 bytes total
        var pos = 0
        val det = object : SecureRandom() {
            override fun nextBytes(out: ByteArray) {
                for (i in out.indices) {
                    out[i] = if (pos < material.size) material[pos++] else 0
                }
            }
            override fun generateSeed(n: Int): ByteArray = ByteArray(n)
        }
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), det)
        return kpg.generateKeyPair()
    }

    // ── ECIES message encryption / decryption ────────────────────────────────

    data class EncryptedMessage(
        val encryptedKey: String, // ephemeral EC public key, X.509 DER, base64
        val iv: String,           // AES-GCM IV, 12 bytes, base64
        val cipherText: String,   // ciphertext + GCM tag, base64
        val keyAlgorithm: String = EC_KEY_ALGORITHM,
        val messageAlgorithm: String = AES_MESSAGE_ALGORITHM,
    )

    fun encryptMessageFor(text: String, recipientPublicKeyB64: String): EncryptedMessage {
        val kf = KeyFactory.getInstance("EC")
        val recipientPublicKey = kf.generatePublic(
            X509EncodedKeySpec(b64decode(recipientPublicKeyB64))
        )

        val ephemKpg = KeyPairGenerator.getInstance("EC")
        ephemKpg.initialize(ECGenParameterSpec("secp256r1"))
        val ephemKp = ephemKpg.generateKeyPair()

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ephemKp.private)
        ka.doPhase(recipientPublicKey, true)
        val aesKey = SecretKeySpec(sha256(ka.generateSecret()), "AES")

        val iv = ByteArray(AES_GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_MESSAGE_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        val cipherText = cipher.doFinal(text.toByteArray(Charsets.UTF_8))

        return EncryptedMessage(
            encryptedKey = b64(ephemKp.public.encoded),
            iv = b64(iv),
            cipherText = b64(cipherText),
        )
    }

    fun decryptMessage(
        myKeyPair: KeyPair,
        encryptedKeyB64: String,
        ivB64: String,
        cipherTextB64: String,
    ): String {
        val kf = KeyFactory.getInstance("EC")
        val ephemPublicKey = kf.generatePublic(X509EncodedKeySpec(b64decode(encryptedKeyB64)))

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myKeyPair.private)
        ka.doPhase(ephemPublicKey, true)
        val aesKey = SecretKeySpec(sha256(ka.generateSecret()), "AES")

        val cipher = Cipher.getInstance(AES_MESSAGE_ALGORITHM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            aesKey,
            GCMParameterSpec(AES_GCM_TAG_BITS, b64decode(ivB64))
        )
        return String(cipher.doFinal(b64decode(cipherTextB64)), Charsets.UTF_8)
    }

    // ── Visual fingerprint (8-emoji row, Signal-safety-numbers analog) ───────

    private val FINGERPRINT_ALPHABET = arrayOf(
        "🍎","🍊","🍋","🍉","🍇","🍓","🍒","🍑",
        "🥑","🥕","🌽","🍔","🍕","🍩","🍪","🍫",
        "🐱","🐶","🐭","🐹","🐰","🦊","🐻","🐼",
        "🦁","🐯","🐮","🐷","🐸","🐵","🐔","🦉",
        "🌸","🌺","🌻","🌷","🌹","🍀","🌳","🌵",
        "🚀","✈️","🚂","🚗","⛵","🏎️","🛸","🚲",
        "⚽","🏀","🏈","🎾","🎸","🎺","🎷","🎹",
        "🌙","⭐","☀️","🌈","❄️","🔥","💧","🌊"
    )

    fun pubkeyFingerprint(pubkeyB64: String?): String {
        if (pubkeyB64.isNullOrBlank()) return ""
        val bytes = runCatching { b64decode(pubkeyB64) }.getOrNull() ?: return ""
        val hash = sha256(bytes)
        val sb = StringBuilder(8)
        for (i in 0 until 8) {
            val idx = hash[i].toInt() and (FINGERPRINT_ALPHABET.size - 1)
            sb.append(FINGERPRINT_ALPHABET[idx])
        }
        return sb.toString()
    }
}
