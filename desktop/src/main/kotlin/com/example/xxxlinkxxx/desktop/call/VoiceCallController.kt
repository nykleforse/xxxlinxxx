package com.example.xxxlinkxxx.desktop.call

import com.example.p2pcodec2.Codec2Bridge
import com.example.xxxlinkxxx.desktop.audio.Codec2Loader
import com.example.xxxlinkxxx.desktop.audio.JvmAudioEngine
import com.example.xxxlinkxxx.desktop.net.Repository
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
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop voice-call controller. Mirrors the relevant slice of
 * MainActivity (createPeerConnection, listenAnswer, listenCandidates,
 * setupVoiceReceiver, audio start/stop) for the JVM, using dev.onvoid
 * webrtc-java for the PeerConnection / DataChannel surface and the
 * Repository's Firestore client for signaling.
 *
 * Voice modes:
 *  - BASE (Codec2 mode 0, 8 bytes per frame) — encoded frames pumped
 *    through the "voice" DataChannel with maxRetransmits=2.
 *  - XTREAM (Codec2 mode 1, 6 bytes per frame) — same transport, lower
 *    bitrate.
 *  - COMFY (native WebRTC audio track) — currently not implemented on
 *    desktop; falls back to BASE if requested.
 *
 * Requires:
 *  - dev.onvoid.webrtc native libs (bundled via classifier in
 *    desktop/build.gradle.kts).
 *  - codec2_bridge.{dll,so,dylib} loadable via Codec2Loader for
 *    BASE / XTREAM. If unavailable, callerWillFallToTextOnly()
 *    returns true.
 *
 * Lifecycle: construct once per logged-in session, dispose() on logout.
 * Per-call state lives between startOutgoing()/acceptIncoming() and
 * hangUp(). PollIncomingCalls() runs as a child coroutine on the
 * controller's scope and emits OnIncomingCall events through onIncoming.
 */
class VoiceCallController(
    private val repo: Repository,
    private val turnUsername: String? = null,
    private val turnPassword: String? = null,
    val onState: (CallStateChange) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val factory: PeerConnectionFactory = PeerConnectionFactory()
    private var pc: RTCPeerConnection? = null
    private var voiceChannel: RTCDataChannel? = null
    private var messageChannel: RTCDataChannel? = null

    private var codec2: Codec2Bridge? = null
    private var audio: JvmAudioEngine? = null
    @Volatile private var callId: String? = null
    @Volatile private var peerId: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<RTCIceCandidate>()
    private val processedCandidates = ConcurrentHashMap.newKeySet<String>()
    private val pollingActive = AtomicBoolean(false)
    private var pollingJob: Job? = null
    @Volatile private var txSeq = 0

    fun callerWillFallToTextOnly(): Boolean = !Codec2Loader.isAvailable()

    /**
     * Start an outgoing call to peerId. The function returns immediately;
     * the actual state transitions arrive through onState. Throws if a
     * call is already in flight.
     */
    fun startOutgoing(peerId: String) {
        if (pc != null) error("Call already active — hang up first")
        val callId = pairCallId(repo.localId, peerId)
        val sid = UUID.randomUUID().toString()
        this.callId = callId
        this.peerId = peerId
        this.sessionId = sid
        onState(CallStateChange.Outgoing(peerId, callId))
        val connection = newPeerConnection(isCaller = true)
        pc = connection
        connection.createOffer(RTCOfferOptions(), object : dev.onvoid.webrtc.CreateSessionDescriptionObserver {
            override fun onSuccess(desc: RTCSessionDescription) {
                connection.setLocalDescription(desc, object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        scope.launch {
                            runCatching {
                                publishOffer(callId, peerId, sid, desc.sdp)
                                listenForAnswer(callId)
                                listenForCandidates(callId, sid)
                            }
                        }
                    }
                    override fun onFailure(error: String) {
                        onState(CallStateChange.Failed("setLocal: $error"))
                    }
                })
            }
            override fun onFailure(error: String) {
                onState(CallStateChange.Failed("createOffer: $error"))
            }
        })
    }

    /**
     * Accept an incoming call described by the given Firestore call doc.
     * Fetches the offer, generates an answer, writes it back, and starts
     * the ICE candidate listener.
     */
    fun acceptIncoming(callId: String, peerId: String, sid: String, offerSdp: String) {
        if (pc != null) error("Call already active — hang up first")
        this.callId = callId
        this.peerId = peerId
        this.sessionId = sid
        onState(CallStateChange.AcceptingIncoming(peerId, callId))
        val connection = newPeerConnection(isCaller = false)
        pc = connection
        val remote = RTCSessionDescription(RTCSdpType.OFFER, offerSdp)
        connection.setRemoteDescription(remote, object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                remoteDescriptionSet = true
                flushPendingCandidates()
                connection.createAnswer(RTCAnswerOptions(), object : dev.onvoid.webrtc.CreateSessionDescriptionObserver {
                    override fun onSuccess(desc: RTCSessionDescription) {
                        connection.setLocalDescription(desc, object : SetSessionDescriptionObserver {
                            override fun onSuccess() {
                                scope.launch {
                                    runCatching {
                                        publishAnswer(callId, sid, desc.sdp)
                                        listenForCandidates(callId, sid)
                                    }
                                }
                            }
                            override fun onFailure(error: String) {
                                onState(CallStateChange.Failed("setLocal: $error"))
                            }
                        })
                    }
                    override fun onFailure(error: String) {
                        onState(CallStateChange.Failed("createAnswer: $error"))
                    }
                })
            }
            override fun onFailure(error: String) {
                onState(CallStateChange.Failed("setRemote: $error"))
            }
        })
    }

    fun hangUp() {
        val id = callId
        scope.launch {
            runCatching {
                if (id != null) {
                    repo.firebase.firestoreSet(
                        "calls/$id",
                        mapOf(
                            "state" to "ended",
                            "endedBy" to repo.localId,
                            "endedAt" to System.currentTimeMillis(),
                        ),
                        merge = true,
                    )
                }
            }
        }
        cleanupCall()
        onState(CallStateChange.Ended)
    }

    fun toggleMute(muted: Boolean) {
        audio?.micMuted = muted
        onState(CallStateChange.MuteChanged(muted))
    }

    fun dispose() {
        pollingJob?.cancel()
        pollingJob = null
        cleanupCall()
        runCatching { factory.dispose() }
        scope.cancel()
    }

    /** Begin watching for incoming calls. Caller wires this once per session. */
    fun startIncomingWatch(onIncoming: (IncomingCallEvent) -> Unit) {
        if (!pollingActive.compareAndSet(false, true)) return
        pollingJob = scope.launch {
            while (pollingActive.get()) {
                runCatching {
                    val docs = repo.firebase.firestoreQuery(
                        "calls",
                        equalityFilters = mapOf(
                            "calleeId" to repo.localId,
                            "state" to "ringing",
                        ),
                        limit = 5,
                    )
                    for ((id, fields) in docs) {
                        val callerId = fields["callerId"] as? String ?: continue
                        val sid = fields["sessionId"] as? String ?: continue
                        val offerSdp = (fields["offer"] as? Map<*, *>)?.get("sdp") as? String
                            ?: fields["offer"] as? String ?: continue
                        onIncoming(IncomingCallEvent(id, callerId, sid, offerSdp))
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

    // ── Internals ────────────────────────────────────────────────────────────

    private fun newPeerConnection(isCaller: Boolean): RTCPeerConnection {
        val config = RTCConfiguration().apply {
            iceServers = mutableListOf<RTCIceServer>().apply {
                add(RTCIceServer().apply { urls = listOf("stun:stun.l.google.com:19302") })
                if (!turnUsername.isNullOrEmpty() && !turnPassword.isNullOrEmpty()) {
                    add(RTCIceServer().apply {
                        urls = listOf(
                            "turn:openrelay.metered.ca:80?transport=udp",
                            "turn:openrelay.metered.ca:443?transport=tcp",
                        )
                        username = turnUsername
                        password = turnPassword
                    })
                }
            }
        }
        val connection = factory.createPeerConnection(config, object : PeerConnectionObserver {
            override fun onSignalingChange(state: RTCSignalingState) {}
            override fun onConnectionChange(state: RTCPeerConnectionState) {
                if (state == RTCPeerConnectionState.CONNECTED) {
                    onState(CallStateChange.Connected)
                    startAudioPump()
                } else if (state == RTCPeerConnectionState.FAILED ||
                    state == RTCPeerConnectionState.CLOSED ||
                    state == RTCPeerConnectionState.DISCONNECTED) {
                    onState(CallStateChange.Ended)
                }
            }
            override fun onIceConnectionChange(state: RTCIceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: RTCIceGatheringState) {}
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                scope.launch { runCatching { publishCandidate(candidate) } }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out RTCIceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: RTCDataChannel) {
                when (channel.label) {
                    "voice" -> attachVoiceReceiver(channel)
                    "messages" -> { messageChannel = channel }
                }
            }
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RTCRtpTransceiver) {}
            override fun onAddTrack(receiver: RTCRtpReceiver, mediaStreams: Array<out MediaStream>) {}
            override fun onRemoveTrack(receiver: RTCRtpReceiver) {}
        }) ?: error("Failed to construct RTCPeerConnection")

        if (isCaller) {
            val voiceInit = RTCDataChannelInit().apply {
                ordered = true
                maxRetransmits = 2
            }
            val messagesInit = RTCDataChannelInit().apply { ordered = true }
            voiceChannel = connection.createDataChannel("voice", voiceInit)
                .also { attachVoiceReceiver(it) }
            messageChannel = connection.createDataChannel("messages", messagesInit)
        }
        return connection
    }

    private fun attachVoiceReceiver(channel: RTCDataChannel) {
        voiceChannel = channel
        channel.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (channel.state == RTCDataChannelState.OPEN) {
                    startAudioPump()
                }
            }
            override fun onMessage(buffer: RTCDataChannelBuffer) {
                if (!buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                audio?.playEncodedFrame(bytes)
            }
        })
    }

    private fun startAudioPump() {
        if (audio != null) return
        if (!Codec2Loader.isAvailable()) {
            onState(CallStateChange.VoiceUnavailable(
                "codec2 library not found — text-only on this device"
            ))
            return
        }
        val c2 = runCatching { Codec2Bridge(Codec2Bridge.MODE_1600) }
            .getOrElse {
                onState(CallStateChange.VoiceUnavailable("Codec2 init failed: ${it.message}"))
                return
            }
        codec2 = c2
        val engine = JvmAudioEngine(c2) { frame ->
            val channel = voiceChannel ?: return@JvmAudioEngine
            if (channel.state != RTCDataChannelState.OPEN) return@JvmAudioEngine
            val seq = ++txSeq
            val wrapped = ByteBuffer.allocate(4 + frame.size).apply {
                putInt(seq)
                put(frame)
                flip()
            }
            try {
                channel.send(RTCDataChannelBuffer(wrapped, true))
            } catch (_: Throwable) { /* channel closed */ }
        }
        runCatching { engine.start() }.onFailure {
            onState(CallStateChange.VoiceUnavailable("Audio device init failed: ${it.message}"))
            engine.stop()
            return
        }
        audio = engine
    }

    private fun cleanupCall() {
        runCatching { voiceChannel?.close() }
        runCatching { messageChannel?.close() }
        voiceChannel = null
        messageChannel = null
        runCatching { pc?.close() }
        pc = null
        runCatching { audio?.stop() }
        audio = null
        runCatching { codec2?.release() }
        codec2 = null
        callId = null
        peerId = null
        sessionId = null
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        processedCandidates.clear()
        txSeq = 0
    }

    // ── Firestore signaling ──────────────────────────────────────────────────

    private suspend fun publishOffer(callId: String, calleeId: String, sid: String, sdp: String) {
        repo.firebase.firestoreSet(
            "calls/$callId",
            mapOf(
                "callerId" to repo.localId,
                "calleeId" to calleeId,
                "sessionId" to sid,
                "state" to "ringing",
                "createdAt" to System.currentTimeMillis(),
                "offer" to mapOf("type" to "offer", "sdp" to sdp),
                "mode" to "base",
            ),
            merge = false,
        )
    }

    private suspend fun publishAnswer(callId: String, sid: String, sdp: String) {
        repo.firebase.firestoreSet(
            "calls/$callId",
            mapOf(
                "state" to "answered",
                "answerSessionId" to sid,
                "answeredAt" to System.currentTimeMillis(),
                "answer" to mapOf("type" to "answer", "sdp" to sdp),
            ),
            merge = true,
        )
    }

    private suspend fun publishCandidate(candidate: RTCIceCandidate) {
        val callId = callId ?: return
        val sid = sessionId ?: return
        val candId = UUID.randomUUID().toString()
        repo.firebase.firestoreSet(
            "calls/$callId/candidates/$candId",
            mapOf(
                "sender" to repo.localId,
                "sessionId" to sid,
                "sdpMid" to (candidate.sdpMid ?: ""),
                "sdpMLineIndex" to candidate.sdpMLineIndex.toLong(),
                "candidate" to candidate.sdp,
            ),
            merge = false,
        )
    }

    private fun listenForAnswer(callId: String) {
        scope.launch {
            while (callId == this@VoiceCallController.callId) {
                runCatching {
                    val doc = repo.firebase.firestoreGet("calls/$callId")
                    val answer = (doc?.get("answer") as? Map<*, *>)?.get("sdp") as? String
                    if (answer != null && !remoteDescriptionSet) {
                        val remote = RTCSessionDescription(RTCSdpType.ANSWER, answer)
                        pc?.setRemoteDescription(remote, object : SetSessionDescriptionObserver {
                            override fun onSuccess() {
                                remoteDescriptionSet = true
                                flushPendingCandidates()
                            }
                            override fun onFailure(error: String) {
                                onState(CallStateChange.Failed("setRemote (answer): $error"))
                            }
                        })
                        return@launch
                    }
                    val state = doc?.get("state") as? String
                    if (state == "ended" || state == "declined") {
                        onState(CallStateChange.Ended)
                        cleanupCall()
                        return@launch
                    }
                }
                delay(2_000)
            }
        }
    }

    private fun listenForCandidates(callId: String, sid: String) {
        scope.launch {
            while (callId == this@VoiceCallController.callId) {
                runCatching {
                    val docs = repo.firebase.firestoreQuery(
                        "calls/$callId/candidates",
                        emptyMap(),
                        limit = 100,
                    )
                    for ((id, fields) in docs) {
                        if (!processedCandidates.add(id)) continue
                        val sender = fields["sender"] as? String ?: continue
                        if (sender == repo.localId) continue
                        val sdp = fields["candidate"] as? String ?: continue
                        val mid = fields["sdpMid"] as? String ?: continue
                        val idx = (fields["sdpMLineIndex"] as? Long)?.toInt() ?: 0
                        val cand = RTCIceCandidate(mid, idx, sdp)
                        if (!remoteDescriptionSet) {
                            pendingRemoteCandidates.add(cand)
                        } else {
                            pc?.addIceCandidate(cand)
                        }
                    }
                }
                delay(2_000)
            }
        }
    }

    private fun flushPendingCandidates() {
        for (c in pendingRemoteCandidates) pc?.addIceCandidate(c)
        pendingRemoteCandidates.clear()
    }

    private fun pairCallId(a: String, b: String): String =
        listOf(a, b).sorted().joinToString("_")

    // ── Events / state ───────────────────────────────────────────────────────

    sealed interface CallStateChange {
        data class Outgoing(val peerId: String, val callId: String) : CallStateChange
        data class AcceptingIncoming(val peerId: String, val callId: String) : CallStateChange
        object Connected : CallStateChange
        object Ended : CallStateChange
        data class Failed(val reason: String) : CallStateChange
        data class MuteChanged(val muted: Boolean) : CallStateChange
        data class VoiceUnavailable(val reason: String) : CallStateChange
    }

    data class IncomingCallEvent(
        val callId: String,
        val callerId: String,
        val sessionId: String,
        val offerSdp: String,
    )
}
