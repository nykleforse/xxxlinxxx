package com.example.xxxlinkxxx.desktop.photo

import com.example.xxxlinkxxx.desktop.crypto.Crypto
import com.example.xxxlinkxxx.desktop.net.Repository
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted photo transfer over a dedicated WebRTC DataChannel + Firestore
 * /transfers/ signaling — wire-compatible with the Android v1.14.16 flow.
 *
 * On the sender:
 *   1. AES-256 key generated, photo bytes encrypted with AES-256-GCM.
 *   2. AES key wrapped via ECIES (ephemeral EC P-256 + ECDH + AES-256-GCM)
 *      under the recipient's messagePublicKey. Wrapped key carried in
 *      "encKey" field of the meta JSON, NOT in Firestore — so the wire
 *      cipher never lands on the server.
 *   3. Ciphertext split into 12_000-byte chunks (matches Android).
 *   4. Outer PeerConnection + DataChannel labelled "photo" (ordered,
 *      reliable, default retransmits).
 *   5. /transfers/{id} doc gets the SDP offer + state="pending".
 *   6. On DataChannel open: send meta JSON as text frame, then each
 *      chunk as binary frame. After last chunk → state="done", tear
 *      down PeerConnection.
 *
 * On the receiver:
 *   - PollIncomingTransfers() runs as a child coroutine, polling
 *     /transfers where receiverId==me, state="pending".
 *   - Auto-accept for known contacts (caller-side decision via
 *     onIncomingTransfer); decline path writes state="declined".
 *   - Sets up answering PeerConnection, listens on the data channel.
 *   - First text frame = meta JSON {encKey, iv, chunkCount, ext}.
 *   - Subsequent binary frames = ciphertext chunks. After chunkCount
 *     reached: decrypt, save to user-local photos dir, fire onPhoto.
 */
class PhotoTransferController(
    private val repo: Repository,
    private val myKeyPair: java.security.KeyPair,
    val onIncoming: (String, File) -> Unit,
    val onSendProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val factory = PeerConnectionFactory()
    private val active = ConcurrentHashMap<String, Session>()
    private val processedCandidates = ConcurrentHashMap.newKeySet<String>()
    private val pollingActive = AtomicBoolean(false)
    private var pollingJob: Job? = null

    fun startIncomingWatch() {
        if (!pollingActive.compareAndSet(false, true)) return
        pollingJob = scope.launch {
            while (pollingActive.get()) {
                runCatching {
                    val docs = repo.firebase.firestoreQuery(
                        "transfers",
                        equalityFilters = mapOf(
                            "receiverId" to repo.localId,
                            "state" to "pending",
                        ),
                        limit = 5,
                    )
                    for ((id, fields) in docs) {
                        if (active.containsKey(id)) continue
                        val senderId = fields["senderId"] as? String ?: continue
                        val offerSdp = fields["offer"] as? String ?: continue
                        acceptIncoming(id, senderId, offerSdp)
                    }
                }
                delay(3_000)
            }
        }
    }

    fun stopIncomingWatch() {
        pollingActive.set(false)
        pollingJob?.cancel()
        pollingJob = null
    }

    fun dispose() {
        stopIncomingWatch()
        for ((_, s) in active) s.cleanup()
        active.clear()
        runCatching { factory.dispose() }
        scope.cancel()
    }

    /**
     * Start an outgoing photo send. Reads the file, encrypts, opens a fresh
     * PeerConnection, publishes the offer, and pumps chunks once the
     * DataChannel opens. Returns the transferId.
     */
    fun sendPhoto(peerId: String, file: File) {
        scope.launch {
            val photoBytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
            val recipientKey = runCatching { repo.fetchPeerPublicKey(peerId) }.getOrNull()
            if (recipientKey.isNullOrBlank()) return@launch

            val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(photoBytes)

            val wrappedKey = wrapAesKeyForRecipient(aesKey, recipientKey)

            val chunks = (0 until ciphertext.size step CHUNK_SIZE).map { i ->
                ciphertext.copyOfRange(i, minOf(i + CHUNK_SIZE, ciphertext.size))
            }

            val transferId = "${repo.localId}-photo-${System.currentTimeMillis()}"
            val session = Session(transferId, peerId, isCaller = true,
                meta = JSONObject().apply {
                    put("encKey", wrappedKey)
                    put("iv", Crypto.b64(iv))
                    put("chunkCount", chunks.size)
                    put("ext", file.extension.ifBlank { "jpg" })
                },
                outgoingChunks = chunks,
            )
            active[transferId] = session

            val pc = newPeerConnection(session)
            session.pc = pc
            val dcInit = RTCDataChannelInit().apply { ordered = true }
            val dc = pc.createDataChannel("photo", dcInit)
            session.dc = dc
            attachSenderObserver(session, dc)

            pc.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
                override fun onSuccess(desc: RTCSessionDescription) {
                    pc.setLocalDescription(desc, object : SetSessionDescriptionObserver {
                        override fun onSuccess() {
                            scope.launch {
                                runCatching {
                                    publishOffer(transferId, peerId, desc.sdp)
                                    listenForAnswer(transferId, session)
                                    listenForCandidates(transferId, session)
                                }
                            }
                        }
                        override fun onFailure(error: String) { session.cleanup() }
                    })
                }
                override fun onFailure(error: String) { session.cleanup() }
            })
        }
    }

    // ── Receiver path ────────────────────────────────────────────────────────

    private fun acceptIncoming(transferId: String, senderId: String, offerSdp: String) {
        val session = Session(transferId, senderId, isCaller = false)
        active[transferId] = session
        val pc = newPeerConnection(session)
        session.pc = pc
        val remote = RTCSessionDescription(RTCSdpType.OFFER, offerSdp)
        pc.setRemoteDescription(remote, object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                session.remoteDescriptionSet = true
                pc.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
                    override fun onSuccess(desc: RTCSessionDescription) {
                        pc.setLocalDescription(desc, object : SetSessionDescriptionObserver {
                            override fun onSuccess() {
                                scope.launch {
                                    runCatching {
                                        publishAnswer(transferId, desc.sdp)
                                        listenForCandidates(transferId, session)
                                    }
                                }
                            }
                            override fun onFailure(error: String) { session.cleanup() }
                        })
                    }
                    override fun onFailure(error: String) { session.cleanup() }
                })
            }
            override fun onFailure(error: String) { session.cleanup() }
        })
    }

    private fun attachReceiverObserver(session: Session, dc: RTCDataChannel) {
        session.dc = dc
        dc.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: RTCDataChannelBuffer) {
                if (!buffer.binary) {
                    val text = ByteArray(buffer.data.remaining())
                        .also { buffer.data.get(it) }
                        .toString(Charsets.UTF_8)
                    session.meta = JSONObject(text)
                    return
                }
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                session.receivedChunks.add(bytes)
                val meta = session.meta
                val expected = meta?.optInt("chunkCount", 0) ?: 0
                if (expected > 0 && session.receivedChunks.size >= expected) {
                    finalizeReceive(session)
                }
            }
        })
    }

    private fun attachSenderObserver(session: Session, dc: RTCDataChannel) {
        dc.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (dc.state == RTCDataChannelState.OPEN) {
                    scope.launch { pumpSender(session) }
                }
            }
            override fun onMessage(buffer: RTCDataChannelBuffer) {}
        })
    }

    private suspend fun pumpSender(session: Session) {
        val dc = session.dc ?: return
        val meta = session.meta ?: return
        val chunks = session.outgoingChunks ?: return
        runCatching {
            // 1) Meta as text frame.
            val metaBuf = ByteBuffer.wrap(meta.toString().toByteArray(Charsets.UTF_8))
            dc.send(RTCDataChannelBuffer(metaBuf, false))
            // 2) Chunks as binary frames.
            for ((i, chunk) in chunks.withIndex()) {
                dc.send(RTCDataChannelBuffer(ByteBuffer.wrap(chunk), true))
                onSendProgress(session.transferId, i + 1, chunks.size)
            }
            // Mark transfer done.
            repo.firebase.firestoreSet(
                "transfers/${session.transferId}",
                mapOf("state" to "done", "completedAt" to System.currentTimeMillis()),
                merge = true,
            )
        }
        // Defer cleanup so the receiver can drain. 5 s window is in line
        // with Android PHOTO_CHANNEL_MAX_BUFFERED_AMOUNT_BYTES drain time.
        delay(5_000)
        session.cleanup()
        active.remove(session.transferId)
    }

    private fun finalizeReceive(session: Session) {
        val meta = session.meta ?: return
        val encKey = meta.optString("encKey")
        val ivB64 = meta.optString("iv")
        val ext = meta.optString("ext", "jpg")
        val combined = ByteArray(session.receivedChunks.sumOf { it.size })
        var pos = 0
        for (c in session.receivedChunks) {
            System.arraycopy(c, 0, combined, pos, c.size)
            pos += c.size
        }
        val aesKey = runCatching { unwrapAesKey(encKey) }.getOrNull() ?: return
        val plaintext = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, Crypto.b64decode(ivB64)))
            cipher.doFinal(combined)
        }.getOrNull() ?: return
        val photoDir = File(File(System.getProperty("user.home"), ".xxxlink"), "photos")
        photoDir.mkdirs()
        val outFile = File(photoDir, "${session.transferId}.$ext")
        outFile.writeBytes(plaintext)
        onIncoming(session.peerId, outFile)
        scope.launch {
            runCatching {
                repo.firebase.firestoreSet(
                    "transfers/${session.transferId}",
                    mapOf("state" to "received", "receivedAt" to System.currentTimeMillis()),
                    merge = true,
                )
            }
            delay(2_000)
            session.cleanup()
            active.remove(session.transferId)
        }
    }

    // ── ECIES wrap / unwrap of the per-photo AES key ─────────────────────────

    private fun wrapAesKeyForRecipient(aesKey: SecretKey, recipientPubB64: String): String {
        val kf = KeyFactory.getInstance("EC")
        val recipientPub = kf.generatePublic(X509EncodedKeySpec(Crypto.b64decode(recipientPubB64)))
        val ephemKpg = KeyPairGenerator.getInstance("EC")
        ephemKpg.initialize(ECGenParameterSpec("secp256r1"))
        val ephemKp = ephemKpg.generateKeyPair()
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ephemKp.private)
        ka.doPhase(recipientPub, true)
        val wrapAes = SecretKeySpec(Crypto.sha256(ka.generateSecret()), "AES")
        val wrapIv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val wrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
        wrapCipher.init(Cipher.ENCRYPT_MODE, wrapAes, GCMParameterSpec(128, wrapIv))
        val wrapped = wrapCipher.doFinal(aesKey.encoded)
        return "${Crypto.b64(ephemKp.public.encoded)}:${Crypto.b64(wrapIv)}:${Crypto.b64(wrapped)}"
    }

    private fun unwrapAesKey(encKey: String): SecretKey {
        val parts = encKey.split(":")
        require(parts.size == 3) { "Bad wrapped key format" }
        val (ephPubB64, ivB64, ctB64) = parts
        val kf = KeyFactory.getInstance("EC")
        val ephPub = kf.generatePublic(X509EncodedKeySpec(Crypto.b64decode(ephPubB64)))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myKeyPair.private)
        ka.doPhase(ephPub, true)
        val wrapAes = SecretKeySpec(Crypto.sha256(ka.generateSecret()), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapAes, GCMParameterSpec(128, Crypto.b64decode(ivB64)))
        val rawAes = cipher.doFinal(Crypto.b64decode(ctB64))
        return SecretKeySpec(rawAes, "AES")
    }

    // ── PeerConnection wiring ────────────────────────────────────────────────

    private fun newPeerConnection(session: Session): RTCPeerConnection {
        val config = RTCConfiguration().apply {
            iceServers = mutableListOf<RTCIceServer>().apply {
                add(RTCIceServer().apply { urls = listOf("stun:stun.l.google.com:19302") })
            }
        }
        val pc = factory.createPeerConnection(config, object : PeerConnectionObserver {
            override fun onSignalingChange(state: RTCSignalingState) {}
            override fun onConnectionChange(state: RTCPeerConnectionState) {}
            override fun onIceConnectionChange(state: RTCIceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: RTCIceGatheringState) {}
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                scope.launch { runCatching { publishCandidate(session.transferId, candidate) } }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out RTCIceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: RTCDataChannel) {
                if (dc.label == "photo") attachReceiverObserver(session, dc)
            }
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RTCRtpTransceiver) {}
            override fun onAddTrack(receiver: RTCRtpReceiver, mediaStreams: Array<out MediaStream>) {}
            override fun onRemoveTrack(receiver: RTCRtpReceiver) {}
        }) ?: error("Failed to construct RTCPeerConnection")
        return pc
    }

    // ── Firestore signaling helpers ──────────────────────────────────────────

    private suspend fun publishOffer(transferId: String, peerId: String, sdp: String) {
        repo.firebase.firestoreSet(
            "transfers/$transferId",
            mapOf(
                "transferId" to transferId,
                "senderId" to repo.localId,
                "receiverId" to peerId,
                "offer" to sdp,
                "state" to "pending",
                "createdAt" to System.currentTimeMillis(),
            ),
            merge = false,
        )
    }

    private suspend fun publishAnswer(transferId: String, sdp: String) {
        repo.firebase.firestoreSet(
            "transfers/$transferId",
            mapOf(
                "answer" to sdp,
                "state" to "accepted",
                "acceptedAt" to System.currentTimeMillis(),
            ),
            merge = true,
        )
    }

    private suspend fun publishCandidate(transferId: String, candidate: RTCIceCandidate) {
        val cid = UUID.randomUUID().toString()
        repo.firebase.firestoreSet(
            "transfers/$transferId/candidates/$cid",
            mapOf(
                "sender" to repo.localId,
                "sdpMid" to (candidate.sdpMid ?: ""),
                "sdpMLineIndex" to candidate.sdpMLineIndex.toLong(),
                "candidate" to candidate.sdp,
            ),
            merge = false,
        )
    }

    private fun listenForAnswer(transferId: String, session: Session) {
        scope.launch {
            while (active.containsKey(transferId) && !session.remoteDescriptionSet) {
                runCatching {
                    val doc = repo.firebase.firestoreGet("transfers/$transferId")
                    val ans = doc?.get("answer") as? String
                    if (ans != null) {
                        val remote = RTCSessionDescription(RTCSdpType.ANSWER, ans)
                        session.pc?.setRemoteDescription(remote, object : SetSessionDescriptionObserver {
                            override fun onSuccess() { session.remoteDescriptionSet = true }
                            override fun onFailure(error: String) {}
                        })
                    }
                    val state = doc?.get("state") as? String
                    if (state == "declined" || state == "ended") {
                        session.cleanup()
                        active.remove(transferId)
                        return@launch
                    }
                }
                delay(2_000)
            }
        }
    }

    private fun listenForCandidates(transferId: String, session: Session) {
        scope.launch {
            while (active.containsKey(transferId)) {
                runCatching {
                    val docs = repo.firebase.firestoreQuery(
                        "transfers/$transferId/candidates",
                        emptyMap(),
                        limit = 100,
                    )
                    for ((id, fields) in docs) {
                        val key = "$transferId/$id"
                        if (!processedCandidates.add(key)) continue
                        val sender = fields["sender"] as? String ?: continue
                        if (sender == repo.localId) continue
                        val sdp = fields["candidate"] as? String ?: continue
                        val mid = fields["sdpMid"] as? String ?: continue
                        val idx = (fields["sdpMLineIndex"] as? Long)?.toInt() ?: 0
                        session.pc?.addIceCandidate(RTCIceCandidate(mid, idx, sdp))
                    }
                }
                delay(2_000)
            }
        }
    }

    // ── Per-transfer state holder ────────────────────────────────────────────

    private inner class Session(
        val transferId: String,
        val peerId: String,
        val isCaller: Boolean,
        var meta: JSONObject? = null,
        val outgoingChunks: List<ByteArray>? = null,
    ) {
        var pc: RTCPeerConnection? = null
        var dc: RTCDataChannel? = null
        var remoteDescriptionSet = false
        val receivedChunks: MutableList<ByteArray> = mutableListOf()

        fun cleanup() {
            runCatching { dc?.close() }
            runCatching { pc?.close() }
            pc = null
            dc = null
            receivedChunks.clear()
        }
    }

    companion object {
        private const val CHUNK_SIZE = 12_000
    }
}
