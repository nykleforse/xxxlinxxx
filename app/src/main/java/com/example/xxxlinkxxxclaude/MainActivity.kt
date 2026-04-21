package com.example.p2pcodec2

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.media.AudioTrack
import android.os.Bundle
import android.util.Base64
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.example.p2pcodec2.databinding.ActivityMainBinding
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.TreeMap

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val db = FirebaseFirestore.getInstance()
    private lateinit var peerConnection: PeerConnection
    private var voiceChannel: DataChannel? = null
    private var messageChannel: DataChannel? = null
    private lateinit var codec2: Codec2Bridge

    private val CALL_ID = "0000_0001"
    private val LOCAL_ID = if (android.os.Build.MODEL.contains("Redmi")) "0001" else "0000"
    private val REMOTE_ID = if (LOCAL_ID == "0000") "0001" else "0000"

    private var recording = false
    private lateinit var recordJob: Job
    private lateinit var playJob: Job
    private val voiceJitterBuffer = TreeMap<Int, ByteArray>()
    private var txVoiceSeq = 0
    private var rxVoiceSeq: Int? = null
    private val messageLog = StringBuilder()
    private val pendingMessages = mutableMapOf<String, String>()
    private val receivedMessageIds = mutableSetOf<String>()
    private var messageSeq = 0
    private var coreStarted = false
    private var currentVoiceMode = VoiceMode.BASE
    private var applyingRemoteMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        codec2 = Codec2Bridge(currentVoiceMode.codec2Mode)
        if (hasRecordAudioPermission()) {
            startCore()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_RECORD_AUDIO
            )
        }
    }

    private fun startCore() {
        if (coreStarted) return
        coreStarted = true
        initAudio()
        initPeerConnection()
        bindUI()
        listenCandidates()
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startCore()
        } else {
            binding.status.text = "Microphone permission is required"
        }
    }

    private fun initAudio() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
    }

    private fun initPeerConnection() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )

        val factory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        peerConnection = factory.createPeerConnection(servers, object : PeerConnection.Observer {
            override fun onDataChannel(dc: DataChannel) {
                when (dc.label()) {
                    "voice" -> {
                        voiceChannel = dc
                        setupVoiceReceiver(dc)
                    }
                    "messages" -> {
                        messageChannel = dc
                        setupMessageReceiver(dc)
                    }
                }
            }
            override fun onIceCandidate(cand: IceCandidate) {
                db.collection("calls").document(CALL_ID)
                    .collection("candidates")
                    .add(mapOf("sdpMid" to cand.sdpMid, "sdpMLineIndex" to cand.sdpMLineIndex, "candidate" to cand.sdp))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onConnectionChange(p0: PeerConnection.PeerConnectionState?) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onRenegotiationNeeded() {}
        })!!

        val voiceInit = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        }
        voiceChannel = peerConnection.createDataChannel("voice", voiceInit)
        setupVoiceReceiver(voiceChannel!!)

        val messageInit = DataChannel.Init().apply {
            ordered = true
        }
        messageChannel = peerConnection.createDataChannel("messages", messageInit)
        setupMessageReceiver(messageChannel!!)
    }

    private fun bindUI() {
        binding.btnCall.setOnClickListener { call() }
        binding.btnAccept.setOnClickListener { accept() }
        binding.btnDisconnect.setOnClickListener { peerConnection.close() }
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = when (checkedId) {
                binding.modeComfy.id -> VoiceMode.COMFY
                binding.modeXtream.id -> VoiceMode.XTREAM
                else -> VoiceMode.BASE
            }
            setVoiceMode(selectedMode)
        }
        updateModeStatus()
    }

    private fun setVoiceMode(mode: VoiceMode) {
        if (applyingRemoteMode) return
        if (mode == currentVoiceMode) return
        if (recording) {
            binding.status.text = "Disconnect before changing mode"
            binding.modeGroup.check(currentVoiceMode.buttonId(binding))
            return
        }

        codec2.release()
        currentVoiceMode = mode
        codec2 = Codec2Bridge(mode.codec2Mode)
        updateModeStatus()
    }

    private fun applyVoiceModeFromRemote(mode: VoiceMode) {
        if (mode == currentVoiceMode) return
        if (recording) return

        codec2.release()
        currentVoiceMode = mode
        codec2 = Codec2Bridge(mode.codec2Mode)

        applyingRemoteMode = true
        binding.modeGroup.check(mode.buttonId(binding))
        applyingRemoteMode = false
        binding.status.text = "Incoming mode: ${mode.label}"
    }

    private fun updateModeStatus() {
        binding.status.text = "Mode: ${currentVoiceMode.label}"
    }

    private fun call() = CoroutineScope(Dispatchers.IO).launch {
        db.collection("calls").document(CALL_ID).delete()
        peerConnection.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection.setLocalDescription(this, desc)
                db.collection("calls").document(CALL_ID).set(
                    mapOf(
                        "offer" to desc.description,
                        "mode" to currentVoiceMode.label
                    )
                )
                runOnUiThread { binding.status.text = "Offer sent" }
                listenAnswer()
            }
        }, MediaConstraints())
    }

    private fun accept() = CoroutineScope(Dispatchers.IO).launch {
        db.collection("calls").document(CALL_ID).addSnapshotListener { snap, _ ->
            val offer = snap?.getString("offer") ?: return@addSnapshotListener
            val remoteMode = VoiceMode.fromLabel(snap.getString("mode"))
            runOnUiThread { applyVoiceModeFromRemote(remoteMode) }
            peerConnection.setRemoteDescription(SdpObserverAdapter(), SessionDescription(SessionDescription.Type.OFFER, offer))
            peerConnection.createAnswer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) return
                    peerConnection.setLocalDescription(this, desc)
                    db.collection("calls").document(CALL_ID).update(
                        mapOf(
                            "answer" to desc.description,
                            "answerMode" to currentVoiceMode.label
                        )
                    )
                    binding.status.text = "Answer sent"
                }
            }, MediaConstraints())
        }
    }

    private fun listenAnswer() {
        db.collection("calls").document(CALL_ID)
            .addSnapshotListener { snap, _ ->
                val ans = snap?.getString("answer") ?: return@addSnapshotListener
                peerConnection.setRemoteDescription(SdpObserverAdapter(), SessionDescription(SessionDescription.Type.ANSWER, ans))
                startAudio()
            }
    }

    private fun setupVoiceReceiver(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                receiveVoicePacket(bytes)
            }
            override fun onStateChange() {}
            override fun onBufferedAmountChange(p0: Long) {}
        })
        startAudio()
    }

    private fun setupMessageReceiver(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handleMessagePacket(bytes.toString(Charsets.UTF_8))
            }
            override fun onStateChange() {
                runOnUiThread { binding.status.text = "Messages: ${dc.state()}" }
            }
            override fun onBufferedAmountChange(p0: Long) {}
        })
    }

    private fun sendMessage() {
        val text = binding.messageInput.text.toString().trim()
        if (text.isEmpty()) return

        if (messageChannel?.state() != DataChannel.State.OPEN) {
            binding.status.text = "Message channel is not open"
            return
        }

        val id = nextMessageId()
        synchronized(pendingMessages) {
            pendingMessages[id] = text
        }
        sendTextPacket(id, text)
        retryUntilAck(id)
        binding.messageInput.text?.clear()
        appendMessage("Me", text)
    }

    private fun handleMessagePacket(packet: String) {
        val parts = packet.split("|", limit = 3)
        when (parts.firstOrNull()) {
            "ACK" -> {
                val id = parts.getOrNull(1) ?: return
                synchronized(pendingMessages) {
                    pendingMessages.remove(id)
                }
            }
            "TXT" -> {
                val id = parts.getOrNull(1) ?: return
                val payload = parts.getOrNull(2) ?: return
                sendAck(id)

                val isNew = synchronized(receivedMessageIds) {
                    receivedMessageIds.add(id)
                }
                if (!isNew) return

                val text = String(Base64.decode(payload, Base64.NO_WRAP), Charsets.UTF_8)
                appendMessage("Peer", text)
            }
        }
    }

    private fun sendTextPacket(id: String, text: String) {
        val payload = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendMessagePacket("TXT|$id|$payload")
    }

    private fun sendAck(id: String) {
        sendMessagePacket("ACK|$id|")
    }

    private fun sendMessagePacket(packet: String): Boolean {
        val channel = messageChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false

        val bytes = packet.toByteArray(Charsets.UTF_8)
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    private fun retryUntilAck(id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            repeat(TEXT_RETRY_COUNT) {
                delay(TEXT_RETRY_DELAY_MS)
                val text = synchronized(pendingMessages) {
                    pendingMessages[id]
                } ?: return@launch
                sendTextPacket(id, text)
            }

            val stillPending = synchronized(pendingMessages) {
                pendingMessages.containsKey(id)
            }
            if (stillPending) {
                runOnUiThread { binding.status.text = "Message pending: weak connection" }
            }
        }
    }

    private fun nextMessageId(): String {
        messageSeq += 1
        return "$LOCAL_ID-${System.currentTimeMillis()}-$messageSeq"
    }

    private fun appendMessage(author: String, text: String) {
        runOnUiThread {
            messageLog.append(author).append(": ").append(text).append('\n')
            binding.messages.text = messageLog.toString()
        }
    }

    private fun startAudio() {
        if (recording) return
        recording = true

        val samplerate = 8000
        val frameSize = codec2.samplesPerFrame()
        val minBuf = AudioRecord.getMinBufferSize(samplerate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, samplerate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
        val track = AudioTrack(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(samplerate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            minBuf, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)

        record.startRecording()
        track.play()

        recordJob = CoroutineScope(Dispatchers.IO).launch {
            val buf = ShortArray(frameSize)
            while (recording) {
                val read = record.read(buf, 0, frameSize)
                if (read > 0) {
                    val encoded = if (read == frameSize) {
                        codec2.encode(buf)
                    } else {
                        codec2.encode(buf.copyOf(frameSize))
                    }
                    if (voiceChannel?.state() == DataChannel.State.OPEN) {
                        voiceChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(makeVoicePacket(encoded)), false))
                    }
                }
            }
        }

        playJob = CoroutineScope(Dispatchers.IO).launch {
            while (recording) {
                val encoded = nextVoiceFrame()
                val pcm = if (encoded != null) {
                    codec2.decode(encoded)
                } else {
                    ShortArray(frameSize)
                }
                val byteBuf = ByteArray(pcm.size * 2)
                ByteBuffer.wrap(byteBuf).asShortBuffer().put(pcm)
                track.write(byteBuf, 0, byteBuf.size)
                delay(VOICE_FRAME_MS)
            }
        }
    }

    private fun makeVoicePacket(encoded: ByteArray): ByteArray {
        val seq = txVoiceSeq++
        return ByteBuffer.allocate(VOICE_HEADER_BYTES + encoded.size)
            .putInt(seq)
            .put(encoded)
            .array()
    }

    private fun receiveVoicePacket(packet: ByteArray) {
        if (packet.size < VOICE_HEADER_BYTES + codec2.bytesPerFrame()) return

        val buffer = ByteBuffer.wrap(packet)
        val seq = buffer.int
        val encoded = ByteArray(packet.size - VOICE_HEADER_BYTES)
        buffer.get(encoded)

        synchronized(voiceJitterBuffer) {
            voiceJitterBuffer[seq] = encoded
            while (voiceJitterBuffer.size > MAX_JITTER_FRAMES) {
                voiceJitterBuffer.pollFirstEntry()
            }
        }
    }

    private fun nextVoiceFrame(): ByteArray? {
        synchronized(voiceJitterBuffer) {
            if (rxVoiceSeq == null) {
                if (voiceJitterBuffer.size < START_JITTER_FRAMES) return null
                rxVoiceSeq = voiceJitterBuffer.firstKey()
            }

            val seq = rxVoiceSeq ?: return null
            rxVoiceSeq = seq + 1
            voiceJitterBuffer.headMap(seq).clear()
            return voiceJitterBuffer.remove(seq)
        }
    }

    private fun listenCandidates() {
        db.collection("calls").document(CALL_ID)
            .collection("candidates")
            .addSnapshotListener { snap, _ ->
                snap?.documents?.forEach {
                    val map = it.data ?: return@forEach
                    val cand = IceCandidate(map["sdpMid"] as String?, (map["sdpMLineIndex"] as Long).toInt(), map["candidate"] as String?)
                    peerConnection.addIceCandidate(cand)
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        recording = false
        codec2.release()
        if (::peerConnection.isInitialized) peerConnection.close()
    }

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
        private const val TEXT_RETRY_COUNT = 6
        private const val TEXT_RETRY_DELAY_MS = 2_000L
        private const val VOICE_HEADER_BYTES = 4
        private const val VOICE_FRAME_MS = 20L
        private const val START_JITTER_FRAMES = 3
        private const val MAX_JITTER_FRAMES = 12
    }

    private enum class VoiceMode(val label: String, val codec2Mode: Int) {
        COMFY("comfy", 3200),
        BASE("base", 1300),
        XTREAM("Xtream", 1200);

        fun buttonId(binding: ActivityMainBinding): Int =
            when (this) {
                COMFY -> binding.modeComfy.id
                BASE -> binding.modeBase.id
                XTREAM -> binding.modeXtream.id
            }

        companion object {
            fun fromLabel(label: String?): VoiceMode =
                entries.firstOrNull { it.label == label } ?: BASE
        }
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onSetFailure(p0: String?) {}
    override fun onSetSuccess() {}
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onCreateFailure(p0: String?) {}
}
