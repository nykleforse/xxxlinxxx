package com.example.xxxlinkxxx.desktop.net

import com.example.xxxlinkxxx.desktop.crypto.Crypto
import java.security.KeyPair
import java.security.Signature

/**
 * High-level repository tying the FirebaseClient + Crypto + the on-disk
 * vault together. Mirrors the subset of MainActivity behaviour that the
 * desktop client needs for chat-only flows: bindLocalId, publish pubkey,
 * encrypt + send messages, poll inbox, write receipts, contact lookup.
 *
 * Voice calling is intentionally out of scope here — that's milestones
 * M5-M7. Photo transfer is out of scope for the v1 MVP.
 */
class Repository(
    val firebase: FirebaseClient,
    private val keyPair: KeyPair,
    val localId: String,
) {
    private val seenIds = mutableSetOf<String>()

    /**
     * Calls the bindLocalId Cloud Function with the same payload the
     * Android client uses: localId, X.509 pubkey, signature over
     * "id\nuid\nts". After success the Firebase token carries the
     * custom claim {localId: id} on its next refresh.
     */
    suspend fun bindLocalIdOnServer() {
        val uid = firebase.currentUid ?: error("Sign in first")
        val pubkey = Crypto.b64(keyPair.public.encoded)
        val ts = System.currentTimeMillis()
        val message = "$localId\n$uid\n$ts".toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(message)
        }.sign()
        val data = mapOf(
            "localId" to localId,
            "pubkey" to pubkey,
            "signature" to Crypto.b64(sig),
            "ts" to ts,
        )
        firebase.callFunction("bindLocalId", data)
    }

    /** Publish my X.509 EC public key under /users/{localId}. */
    suspend fun publishPublicMessageKey() {
        val pubkey = Crypto.b64(keyPair.public.encoded)
        firebase.firestoreSet(
            "users/$localId",
            mapOf(
                "id" to localId,
                "messagePublicKey" to pubkey,
                "keyAlgorithm" to Crypto.EC_KEY_ALGORITHM,
                "updatedAt" to System.currentTimeMillis(),
            ),
            merge = true,
        )
    }

    /** Fetches another user's pubkey for encryption. Returns null if unknown. */
    suspend fun fetchPeerPublicKey(peerId: String): String? {
        val doc = firebase.firestoreGet("users/$peerId") ?: return null
        return doc["messagePublicKey"] as? String
    }

    /**
     * Encrypt + write a message document. Caller supplies the plaintext
     * and recipient localId. Returns the generated messageId on success.
     * Optional replyTo carries the msgId being replied to (Android v1.14.20
     * wire format — the receiver renders a quoted preview above the bubble).
     */
    suspend fun sendEncryptedMessage(
        peerId: String,
        text: String,
        peerPubkey: String,
        replyTo: String? = null,
    ): String {
        val enc = Crypto.encryptMessageFor(text, peerPubkey)
        val ts = System.currentTimeMillis()
        val seq = nextSeq()
        val messageId = "$localId-$ts-$seq"
        val payload = mutableMapOf<String, Any?>(
            "from" to localId,
            "to" to peerId,
            "encryptedKey" to enc.encryptedKey,
            "iv" to enc.iv,
            "cipherText" to enc.cipherText,
            "messageAlgorithm" to enc.messageAlgorithm,
            "keyAlgorithm" to enc.keyAlgorithm,
            "createdAt" to ts,
        )
        if (replyTo != null) payload["replyTo"] = replyTo
        firebase.firestoreSet("messages/$messageId", payload, merge = false)
        return messageId
    }

    /** Poll receipts/{msgId} docs addressed to us → drives ✓/✓✓ UI. */
    suspend fun pollReceipts(): List<Receipt> {
        val docs = firebase.firestoreQuery(
            "receipts",
            equalityFilters = mapOf("from" to localId),
            limit = 100,
        )
        val out = mutableListOf<Receipt>()
        for ((id, fields) in docs) {
            val delivered = (fields["delivered"] as? Boolean) ?: false
            val read = (fields["read"] as? Boolean) ?: false
            out.add(Receipt(id, delivered, read))
        }
        return out
    }

    /**
     * Poll one batch of inbound messages and decrypt them. Caller invokes
     * this on a timer (5 s mirrors the Android MESSAGE_POLL_MS). Already-
     * seen ids are filtered with the in-memory set.
     */
    suspend fun pollInbox(): List<InboundMessage> {
        val docs = firebase.firestoreQuery(
            "messages",
            equalityFilters = mapOf("to" to localId),
            limit = 50,
        )
        val out = mutableListOf<InboundMessage>()
        for ((id, fields) in docs) {
            if (id in seenIds) continue
            seenIds.add(id)
            val from = fields["from"] as? String ?: continue
            val encKey = fields["encryptedKey"] as? String ?: continue
            val iv = fields["iv"] as? String ?: continue
            val ct = fields["cipherText"] as? String ?: continue
            val text = runCatching {
                Crypto.decryptMessage(keyPair, encKey, iv, ct)
            }.getOrNull() ?: continue
            val ts = (fields["createdAt"] as? Long) ?: 0L
            val groupId = (fields["groupId"] as? String)?.takeIf { it.isNotBlank() }
            val replyTo = (fields["replyTo"] as? String)?.takeIf { it.isNotBlank() }
            out.add(InboundMessage(id, from, text, ts, groupId, replyTo))
            // best-effort delete (matches MainActivity behaviour after receipt)
            runCatching { firebase.firestoreDelete("messages/$id") }
        }
        return out
    }

    /** Write a delivery receipt for a message we just decoded. */
    suspend fun writeReceipt(messageId: String, peerId: String, read: Boolean = false) {
        firebase.firestoreSet(
            "receipts/$messageId",
            mapOf(
                "from" to localId,
                "to" to peerId,
                "delivered" to true,
                "read" to read,
                "ts" to System.currentTimeMillis(),
            ),
            merge = true,
        )
    }

    private var seqCounter = 0
    private fun nextSeq(): Int {
        seqCounter += 1
        return seqCounter
    }

    data class InboundMessage(
        val id: String,
        val from: String,
        val text: String,
        val ts: Long,
        /** Group fan-out doc had a non-blank groupId — route to group chat. */
        val groupId: String? = null,
        /** msgId this message replies to, or null. */
        val replyTo: String? = null,
    )

    /** Per-msgId delivery state observed on the /receipts/ collection. */
    data class Receipt(val msgId: String, val delivered: Boolean, val read: Boolean)
}
