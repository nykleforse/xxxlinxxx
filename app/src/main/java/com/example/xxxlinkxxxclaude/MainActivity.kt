package com.example.p2pcodec2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.AudioTrack
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.example.xxxlinkxxx.R
import com.example.xxxlinkxxx.databinding.ActivityMainBinding
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.TreeMap
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var db: FirebaseFirestore? = null
    private lateinit var peerConnection: PeerConnection
    private lateinit var peerFactory: PeerConnectionFactory
    private var voiceChannel: DataChannel? = null
    private var messageChannel: DataChannel? = null
    private lateinit var codec2: Codec2Bridge
    private lateinit var prefs: android.content.SharedPreferences
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableListOf<ListenerRegistration>()
    private var incomingListener: ListenerRegistration? = null
    private var messagePollJob: Job? = null

    private var localId = ""
    private var remoteId = ""
    private var currentCallId: String? = null
    private var pendingIncomingCall: IncomingCall? = null

    private var recording = false
    private var recordJob: Job? = null
    private var playJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val voiceJitterBuffer = TreeMap<Int, ByteArray>()
    private var txVoiceSeq = 0
    private var rxVoiceSeq: Int? = null
    private val messageLogs = mutableMapOf<String, StringBuilder>()
    private val pendingMessages = mutableMapOf<String, String>()
    private val receivedMessageIds = mutableSetOf<String>()
    private var messageSeq = 0
    private var coreStarted = false
    private var currentVoiceMode = VoiceMode.BASE
    private var applyingRemoteMode = false
    private var answerProcessed = false
    private var offerProcessed = false
    private val processedCandidateIds = mutableSetOf<String>()
    private var voiceSent = 0
    private var voiceReceived = 0
    private var voicePlayed = 0
    private var currentSessionId: String? = null
    private val dismissedIncomingSessions = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        localId = getOrCreateLocalId()
        ensureMessageKeyPair()
        receivedMessageIds += prefs.getStringSet(KEY_SEEN_MESSAGE_IDS, emptySet()).orEmpty()
        binding.myId.text = "Your ID: $localId"

        initFirebase()
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

    private fun initFirebase() {
        val app = FirebaseApp.initializeApp(this)
        if (app == null) {
            binding.status.text = "Missing Firebase config"
            return
        }
        db = FirebaseFirestore.getInstance(app).apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(false)
                .build()
        }
    }

    private fun startCore() {
        if (coreStarted) return
        coreStarted = true
        initAudio()
        initPeerFactory()
        resetPeerConnection(createLocalChannels = false)
        bindUI()
        publishPublicMessageKey()
        listenIncomingCalls()
        startMessagePolling()
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

    private fun initPeerFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )

        peerFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()
    }

    private fun initPeerConnection(createLocalChannels: Boolean) {
        val firestore = db
        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        peerConnection = peerFactory.createPeerConnection(servers, object : PeerConnection.Observer {
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
                val sessionId = currentSessionId ?: return
                val callId = currentCallId ?: return
                firestore?.collection("calls")?.document(callId)
                    ?.collection("candidates")
                    ?.add(
                        mapOf(
                            "sessionId" to sessionId,
                            "sender" to localId,
                            "sdpMid" to cand.sdpMid,
                            "sdpMLineIndex" to cand.sdpMLineIndex,
                            "candidate" to cand.sdp
                        )
                    )
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onConnectionChange(p0: PeerConnection.PeerConnectionState?) {
                updateDebugStatus("pc=$p0")
            }
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onRenegotiationNeeded() {}
        })!!

        if (createLocalChannels) {
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
    }

    private fun bindUI() {
        binding.btnBack.setOnClickListener { showContactList() }
        binding.btnCall.setOnClickListener {
            call()
        }
        binding.btnAccept.setOnClickListener {
            accept()
        }
        binding.btnAcceptIncoming.setOnClickListener {
            acceptPendingIncoming()
        }
        binding.btnDeclineIncoming.setOnClickListener {
            declinePendingIncoming()
        }
        binding.btnDisconnect.setOnClickListener { disconnectCall() }
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnAddContact.setOnClickListener { saveCurrentContact(openAfterSave = true) }
        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = when (checkedId) {
                binding.modeComfy.id -> VoiceMode.COMFY
                binding.modeXtream.id -> VoiceMode.XTREAM
                else -> VoiceMode.BASE
            }
            setVoiceMode(selectedMode)
        }
        renderContacts()
        showContactList()
        updateModeStatus()
        updateFirebaseControls()
    }

    private fun getOrCreateLocalId(): String {
        prefs.getString(KEY_LOCAL_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
            .replace("-", "")
            .take(8)
            .uppercase(Locale.US)
        prefs.edit().putString(KEY_LOCAL_ID, generated).apply()
        return generated
    }

    private fun ensureMessageKeyPair() {
        if (prefs.contains(KEY_MESSAGE_PUBLIC_KEY) && prefs.contains(KEY_MESSAGE_PRIVATE_KEY)) return

        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSA_KEY_BITS, SecureRandom())
        val pair = generator.generateKeyPair()
        prefs.edit()
            .putString(KEY_MESSAGE_PUBLIC_KEY, b64(pair.public.encoded))
            .putString(KEY_MESSAGE_PRIVATE_KEY, b64(pair.private.encoded))
            .apply()
    }

    private fun localMessageKeyPair(): KeyPair {
        ensureMessageKeyPair()
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicBytes = b64decode(prefs.getString(KEY_MESSAGE_PUBLIC_KEY, "").orEmpty())
        val privateBytes = b64decode(prefs.getString(KEY_MESSAGE_PRIVATE_KEY, "").orEmpty())
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicBytes))
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
        return KeyPair(publicKey, privateKey)
    }

    private fun publishPublicMessageKey() {
        val firestore = db ?: return
        val publicKey = prefs.getString(KEY_MESSAGE_PUBLIC_KEY, null) ?: return
        firestore.collection("users").document(localId).set(
            mapOf(
                "id" to localId,
                "messagePublicKey" to publicKey,
                "keyAlgorithm" to RSA_KEY_ALGORITHM,
                "updatedAt" to System.currentTimeMillis()
            )
        ).addOnFailureListener { error ->
            updateDebugStatus("key publish failed: ${error.message}")
        }
    }

    private fun normalizeId(value: String): String =
        value.trim().replace(" ", "").uppercase(Locale.US)

    private fun selectedContactId(): String =
        remoteId

    private fun contactName(id: String): String =
        prefs.getString(contactNameKey(id), null)?.takeIf { it.isNotBlank() } ?: id

    private fun saveCurrentContact(openAfterSave: Boolean): Boolean {
        val id = normalizeId(binding.addContactIdInput.text?.toString().orEmpty())
        if (id.isBlank()) {
            binding.status.text = "Enter contact ID"
            return false
        }
        if (id == localId) {
            binding.status.text = "This is your own ID"
            return false
        }
        val name = binding.addContactNameInput.text?.toString()?.trim().orEmpty()
        val contacts = savedContactIds().toMutableSet()
        contacts += id
        prefs.edit()
            .putString(contactNameKey(id), name)
            .putStringSet(KEY_CONTACT_IDS, contacts)
            .apply()
        binding.addContactIdInput.text?.clear()
        binding.addContactNameInput.text?.clear()
        renderContacts()
        binding.status.text = "Saved: ${contactName(id)}"
        if (openAfterSave) openChat(id)
        return true
    }

    private fun contactNameKey(id: String): String = "$KEY_CONTACT_PREFIX$id"

    private fun chatLogKey(id: String): String = "$KEY_CHAT_LOG_PREFIX$id"

    private fun messageLogFor(id: String): StringBuilder =
        messageLogs.getOrPut(id) {
            StringBuilder(prefs.getString(chatLogKey(id), "").orEmpty())
        }

    private fun callIdFor(first: String, second: String): String =
        listOf(first, second).sorted().joinToString("_")

    private fun savedContactIds(): Set<String> =
        prefs.getStringSet(KEY_CONTACT_IDS, emptySet()).orEmpty()

    private fun renderContacts() {
        binding.contactsList.removeAllViews()
        val contacts = savedContactIds()
            .sortedWith(compareBy<String> { contactName(it).lowercase(Locale.US) }.thenBy { it })
        if (contacts.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No contacts yet"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                textSize = 15f
                setPadding(0, 18, 0, 18)
            }
            binding.contactsList.addView(empty)
            return
        }

        contacts.forEach { id ->
            val item = TextView(this).apply {
                text = "${contactName(id)}\n$id"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 16f
                setPadding(18, 16, 18, 16)
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.control))
                setOnClickListener { openChat(id) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
            binding.contactsList.addView(item, params)
        }
    }

    private fun showContactList() {
        binding.contactListScreen.visibility = View.VISIBLE
        binding.chatScreen.visibility = View.GONE
        binding.incomingCallScreen.visibility = View.GONE
        renderContacts()
    }

    private fun openChat(id: String) {
        remoteId = id
        binding.chatTitle.text = contactName(id)
        binding.chatPeerId.text = id
        binding.messages.text = messageLogFor(id).toString()
        binding.contactListScreen.visibility = View.GONE
        binding.chatScreen.visibility = View.VISIBLE
        binding.incomingCallScreen.visibility = View.GONE
        updateDebugStatus("chat")
    }

    private fun updateFirebaseControls() {
        val firebaseReady = db != null
        binding.btnCall.isEnabled = firebaseReady
        binding.btnAccept.isEnabled = firebaseReady
        if (!firebaseReady) {
            binding.status.text = "Missing Firebase config"
        }
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
        updateDebugStatus("mode=${currentVoiceMode.label}")
    }

    private fun call() = ioScope.launch {
        val firestore = firestoreOrWarn() ?: return@launch
        val targetId = selectedContactId()
        if (targetId.isBlank()) {
            runOnUiThread { binding.status.text = "Enter contact ID" }
            return@launch
        }
        if (targetId == localId) {
            runOnUiThread { binding.status.text = "This is your own ID" }
            return@launch
        }

        remoteId = targetId
        val callId = callIdFor(localId, remoteId)
        resetPeerConnection(createLocalChannels = true)
        currentCallId = callId
        val sessionId = "$localId-${System.currentTimeMillis()}"
        currentSessionId = sessionId
        listenCallEnd()
        listenCandidates()
        peerConnection.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        firestore.collection("calls").document(callId).set(
                            mapOf(
                                "sessionId" to sessionId,
                                "callerId" to localId,
                                "calleeId" to remoteId,
                                "offer" to desc.description,
                                "mode" to currentVoiceMode.label,
                                "state" to "ringing",
                                "createdAt" to System.currentTimeMillis()
                            )
                        ).addOnSuccessListener {
                            updateDebugStatus("offer saved")
                        }.addOnFailureListener { error ->
                            updateDebugStatus("offer write failed: ${error.message}")
                        }
                        updateDebugStatus("calling ${contactName(remoteId)}")
                        listenAnswer()
                    }

                    override fun onSetFailure(error: String?) {
                        updateDebugStatus("offer failed: $error")
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                updateDebugStatus("create offer failed: $error")
            }
        }, MediaConstraints())
    }

    private fun accept() = ioScope.launch {
        pendingIncomingCall?.let {
            acceptPendingIncoming()
            return@launch
        }
        val firestore = firestoreOrWarn() ?: return@launch
        val selectedId = selectedContactId()
        val query = firestore.collection("calls").whereEqualTo("calleeId", localId)
        val listener = query.addSnapshotListener { snap, error ->
            if (error != null) {
                updateDebugStatus("offer listen failed: ${error.message}")
                return@addSnapshotListener
            }
            val document = snap?.documents
                ?.firstOrNull { doc ->
                    doc.getString("state") == "ringing" &&
                    doc.getString("offer") != null &&
                        doc.getString("calleeId") == localId &&
                        (selectedId.isBlank() || doc.getString("callerId") == selectedId)
                } ?: return@addSnapshotListener
            acceptIncomingDocument(document)
        }
        addListener(listener)
    }

    private fun listenIncomingCalls() {
        val firestore = db ?: return
        incomingListener?.remove()
        incomingListener = firestore.collection("calls")
            .whereEqualTo("calleeId", localId)
            .whereEqualTo("state", "ringing")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    updateDebugStatus("incoming listen failed: ${error.message}")
                    return@addSnapshotListener
                }

                val document = snap?.documents
                    ?.firstOrNull { doc ->
                        val sessionId = doc.getString("sessionId")
                        doc.getString("offer") != null &&
                            doc.getString("callerId") != localId &&
                            sessionId != null &&
                            sessionId != currentSessionId &&
                            !dismissedIncomingSessions.contains(sessionId)
                    }

                if (document == null) {
                    if (pendingIncomingCall != null) {
                        pendingIncomingCall = null
                        runOnUiThread {
                            binding.incomingCallScreen.visibility = View.GONE
                            binding.status.text = "Incoming call ended"
                            binding.chatStatus.text = "Incoming call ended"
                        }
                    }
                    return@addSnapshotListener
                }

                val incoming = IncomingCall(
                    callId = document.id,
                    sessionId = document.getString("sessionId") ?: return@addSnapshotListener,
                    callerId = document.getString("callerId") ?: return@addSnapshotListener,
                    offer = document.getString("offer") ?: return@addSnapshotListener,
                    mode = VoiceMode.fromLabel(document.getString("mode"))
                )
                pendingIncomingCall = incoming
                runOnUiThread { showIncomingCall(incoming) }
            }
    }

    private fun showIncomingCall(incoming: IncomingCall) {
        val callerName = contactName(incoming.callerId)
        binding.incomingCallerName.text = callerName
        binding.incomingCallerId.text = incoming.callerId
        binding.incomingCallScreen.visibility = View.VISIBLE
        binding.status.text = "Incoming call from $callerName"
        binding.chatStatus.text = "Incoming call from $callerName"
    }

    private fun acceptPendingIncoming() {
        val incoming = pendingIncomingCall ?: run {
            binding.status.text = "No incoming call"
            return
        }
        pendingIncomingCall = null
        acceptIncoming(incoming)
    }

    private fun acceptIncomingDocument(document: DocumentSnapshot) {
        val incoming = IncomingCall(
            callId = document.id,
            sessionId = document.getString("sessionId") ?: return,
            callerId = document.getString("callerId") ?: return,
            offer = document.getString("offer") ?: return,
            mode = VoiceMode.fromLabel(document.getString("mode"))
        )
        pendingIncomingCall = null
        acceptIncoming(incoming)
    }

    private fun acceptIncoming(incoming: IncomingCall) {
        val firestore = firestoreOrWarn() ?: return
        if (offerProcessed) return
        offerProcessed = true
        dismissedIncomingSessions += incoming.sessionId
        removeListeners()
        remoteId = incoming.callerId
        runOnUiThread {
            rememberContact(incoming.callerId)
            openChat(incoming.callerId)
        }
        resetPeerConnection(createLocalChannels = false)
        currentCallId = incoming.callId
        currentSessionId = incoming.sessionId
        listenCallEnd()
        listenCandidates()
        runOnUiThread { applyVoiceModeFromRemote(incoming.mode) }

        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, incoming.offer)
        peerConnection.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                peerConnection.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
                        peerConnection.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                firestore.collection("calls").document(incoming.callId).update(
                                    mapOf(
                                        "answerSessionId" to incoming.sessionId,
                                        "answer" to desc.description,
                                        "answerMode" to currentVoiceMode.label,
                                        "state" to "accepted"
                                    )
                                ).addOnFailureListener { error ->
                                    updateDebugStatus("answer write failed: ${error.message}")
                                }
                                updateDebugStatus("accepted ${contactName(incoming.callerId)}")
                            }

                            override fun onSetFailure(error: String?) {
                                updateDebugStatus("answer failed: $error")
                            }
                        }, desc)
                    }
                }, MediaConstraints())
            }

            override fun onSetFailure(error: String?) {
                offerProcessed = false
                updateDebugStatus("offer rejected: $error")
            }
        }, remoteOffer)
    }

    private fun declinePendingIncoming() {
        val incoming = pendingIncomingCall ?: run {
            binding.incomingCallScreen.visibility = View.GONE
            return
        }
        pendingIncomingCall = null
        dismissedIncomingSessions += incoming.sessionId
        db?.collection("calls")?.document(incoming.callId)?.update(
            mapOf(
                "state" to "declined",
                "declinedBy" to localId,
                "declinedSessionId" to incoming.sessionId
            )
        )
        binding.incomingCallScreen.visibility = View.GONE
        binding.status.text = "Declined ${contactName(incoming.callerId)}"
        binding.chatStatus.text = "Declined ${contactName(incoming.callerId)}"
    }

    private fun rememberContact(id: String) {
        if (savedContactIds().contains(id)) return
        prefs.edit()
            .putStringSet(KEY_CONTACT_IDS, savedContactIds() + id)
            .apply()
        renderContacts()
    }

    private fun listenAnswer() {
        val firestore = firestoreOrWarn() ?: return
        val callId = currentCallId ?: return
        val listener = firestore.collection("calls").document(callId)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    updateDebugStatus("answer listen failed: ${error.message}")
                    return@addSnapshotListener
                }
                val state = snap?.getString("state") ?: return@addSnapshotListener
                if (state == "declined") {
                    updateDebugStatus("declined by ${contactName(remoteId)}")
                    return@addSnapshotListener
                }
                if (state == "ended" && snap.getString("endedBy") != localId) {
                    runOnUiThread { endRemoteCall() }
                    return@addSnapshotListener
                }
                val ans = snap.getString("answer") ?: return@addSnapshotListener
                val answerSessionId = snap.getString("answerSessionId") ?: return@addSnapshotListener
                if (answerSessionId != currentSessionId) return@addSnapshotListener
                if (answerProcessed) return@addSnapshotListener
                answerProcessed = true
                peerConnection.setRemoteDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        updateDebugStatus("remote answer set")
                        startAudio()
                    }

                    override fun onSetFailure(error: String?) {
                        answerProcessed = false
                        updateDebugStatus("answer rejected: $error")
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, ans))
            }
        addListener(listener)
    }

    private fun listenCallEnd() {
        val firestore = firestoreOrWarn() ?: return
        val callId = currentCallId ?: return
        val sessionId = currentSessionId
        val listener = firestore.collection("calls").document(callId)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    updateDebugStatus("call end listen failed: ${error.message}")
                    return@addSnapshotListener
                }
                if (snap?.getString("state") != "ended") return@addSnapshotListener
                if (snap.getString("endedBy") == localId) return@addSnapshotListener
                val endedSessionId = snap.getString("endedSessionId")
                if (endedSessionId != null && sessionId != null && endedSessionId != sessionId) return@addSnapshotListener
                runOnUiThread { endRemoteCall() }
            }
        addListener(listener)
    }

    private fun endRemoteCall() {
        resetPeerConnection(createLocalChannels = false)
        binding.incomingCallScreen.visibility = View.GONE
        updateDebugStatus("call ended")
    }

    private fun setupVoiceReceiver(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                receiveVoicePacket(bytes)
            }
            override fun onStateChange() {
                if (dc.state() == DataChannel.State.OPEN) {
                    runOnUiThread {
                        updateDebugStatus("voice=${dc.state()}")
                        startAudio()
                    }
                } else {
                    updateDebugStatus("voice=${dc.state()}")
                }
            }
            override fun onBufferedAmountChange(p0: Long) {}
        })
    }

    private fun setupMessageReceiver(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handleMessagePacket(bytes.toString(Charsets.UTF_8))
            }
            override fun onStateChange() {
                updateDebugStatus("msg=${dc.state()}")
            }
            override fun onBufferedAmountChange(p0: Long) {}
        })
    }

    private fun sendMessage() {
        val text = binding.messageInput.text.toString().trim()
        if (text.isEmpty()) return

        val targetId = selectedContactId()
        if (targetId.isBlank()) {
            binding.status.text = "Choose contact"
            return
        }
        if (targetId == localId) {
            binding.status.text = "This is your own ID"
            return
        }

        val firestore = firestoreOrWarn() ?: return
        val id = nextMessageId()
        binding.btnSend.isEnabled = false

        firestore.collection("users").document(targetId).get()
            .addOnSuccessListener { userDoc ->
                val publicKeyB64 = userDoc.getString("messagePublicKey")
                if (publicKeyB64.isNullOrBlank()) {
                    runOnUiThread {
                        binding.btnSend.isEnabled = true
                        binding.status.text = "Contact encryption key not found"
                        binding.chatStatus.text = "Contact encryption key not found"
                    }
                    return@addOnSuccessListener
                }

                val encrypted = runCatching {
                    encryptMessageFor(text, publicKeyB64)
                }.getOrElse { error ->
                    runOnUiThread {
                        binding.btnSend.isEnabled = true
                        binding.status.text = "Encrypt failed: ${error.message}"
                        binding.chatStatus.text = "Encrypt failed: ${error.message}"
                    }
                    return@addOnSuccessListener
                }

                val message = mapOf(
                    "from" to localId,
                    "to" to targetId,
                    "encryptedKey" to encrypted.encryptedKey,
                    "iv" to encrypted.iv,
                    "cipherText" to encrypted.cipherText,
                    "messageAlgorithm" to AES_MESSAGE_ALGORITHM,
                    "keyAlgorithm" to encrypted.keyAlgorithm,
                    "createdAt" to System.currentTimeMillis()
                )

                firestore.collection("messages").document(id).set(message)
                    .addOnSuccessListener {
                        markMessageSeen(id)
                        binding.messageInput.text?.clear()
                        appendMessage(targetId, "Me", text)
                        runOnUiThread {
                            binding.btnSend.isEnabled = true
                            binding.status.text = "Encrypted sent to ${contactName(targetId)}"
                            binding.chatStatus.text = "Encrypted sent to ${contactName(targetId)}"
                        }
                    }
                    .addOnFailureListener { error ->
                        runOnUiThread {
                            binding.btnSend.isEnabled = true
                            binding.status.text = "Send failed: ${error.message}"
                            binding.chatStatus.text = "Send failed: ${error.message}"
                        }
                    }
            }.addOnFailureListener { error ->
                runOnUiThread {
                    binding.btnSend.isEnabled = true
                    binding.status.text = "Key fetch failed: ${error.message}"
                    binding.chatStatus.text = "Key fetch failed: ${error.message}"
                }
            }
    }

    private fun startMessagePolling() {
        messagePollJob?.cancel()
        messagePollJob = ioScope.launch {
            while (isActive) {
                delay(MESSAGE_POLL_MS)
                pollCloudMessages()
            }
        }
    }

    private fun pollCloudMessages() {
        val firestore = db ?: return
        firestore.collection("messages")
            .whereEqualTo("to", localId)
            .get()
            .addOnSuccessListener { snap ->
                snap.documents.forEach { processCloudMessage(it) }
            }
            .addOnFailureListener { error ->
                updateDebugStatus("message poll failed: ${error.message}")
            }
    }

    private fun processCloudMessage(document: DocumentSnapshot) {
        val id = document.id
        val senderId = document.getString("from") ?: return
        if (senderId == localId) return
        val text = decryptCloudMessage(document) ?: return

        val isNew = synchronized(receivedMessageIds) {
            receivedMessageIds.add(id)
        }
        if (!isNew) {
            deleteCloudMessage(document)
            return
        }

        markMessageSeen(id)
        runOnUiThread {
            rememberContact(senderId)
            appendMessage(senderId, contactName(senderId), text)
            binding.status.text = "Message from ${contactName(senderId)}"
            if (remoteId == senderId && binding.chatScreen.visibility == View.VISIBLE) {
                binding.chatStatus.text = "Message from ${contactName(senderId)}"
            }
            deleteCloudMessage(document)
        }
    }

    private fun decryptCloudMessage(document: DocumentSnapshot): String? {
        document.getString("text")?.let { return it }

        val encryptedKey = document.getString("encryptedKey") ?: return null
        val iv = document.getString("iv") ?: return null
        val cipherText = document.getString("cipherText") ?: return null
        val keyAlgorithm = document.getString("keyAlgorithm") ?: RSA_OAEP_SHA256

        return runCatching {
            decryptMessage(encryptedKey, iv, cipherText, keyAlgorithm)
        }.getOrElse { error ->
            updateDebugStatus("decrypt failed: ${error.message}")
            null
        }
    }

    private fun encryptMessageFor(text: String, publicKeyB64: String): EncryptedMessage {
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(b64decode(publicKeyB64)))

        val aesGenerator = KeyGenerator.getInstance("AES")
        aesGenerator.init(AES_KEY_BITS)
        val aesKey = aesGenerator.generateKey()
        val iv = ByteArray(AES_GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }

        val aesCipher = Cipher.getInstance(AES_MESSAGE_ALGORITHM)
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        val cipherText = aesCipher.doFinal(text.toByteArray(Charsets.UTF_8))

        val encryptedKey = rsaEncrypt(aesKey.encoded, publicKey)
        return EncryptedMessage(
            encryptedKey = b64(encryptedKey.bytes),
            iv = b64(iv),
            cipherText = b64(cipherText),
            keyAlgorithm = encryptedKey.algorithm
        )
    }

    private fun decryptMessage(
        encryptedKeyB64: String,
        ivB64: String,
        cipherTextB64: String,
        keyAlgorithm: String
    ): String {
        val privateKey = localMessageKeyPair().private
        val aesKeyBytes = rsaDecrypt(b64decode(encryptedKeyB64), privateKey, keyAlgorithm)
        val aesKey = SecretKeySpec(aesKeyBytes, "AES")
        val aesCipher = Cipher.getInstance(AES_MESSAGE_ALGORITHM)
        aesCipher.init(
            Cipher.DECRYPT_MODE,
            aesKey,
            GCMParameterSpec(AES_GCM_TAG_BITS, b64decode(ivB64))
        )
        return String(aesCipher.doFinal(b64decode(cipherTextB64)), Charsets.UTF_8)
    }

    private fun rsaEncrypt(bytes: ByteArray, publicKey: PublicKey): RsaCipherBytes {
        val algorithms = listOf(RSA_OAEP_SHA256, RSA_OAEP_SHA1)
        algorithms.forEach { algorithm ->
            runCatching {
                val cipher = Cipher.getInstance(algorithm)
                cipher.init(Cipher.ENCRYPT_MODE, publicKey)
                return RsaCipherBytes(cipher.doFinal(bytes), algorithm)
            }
        }
        error("No RSA encryption provider")
    }

    private fun rsaDecrypt(bytes: ByteArray, privateKey: PrivateKey, keyAlgorithm: String): ByteArray {
        val algorithms = listOf(keyAlgorithm, RSA_OAEP_SHA256, RSA_OAEP_SHA1).distinct()
        algorithms.forEach { algorithm ->
            runCatching {
                val cipher = Cipher.getInstance(algorithm)
                cipher.init(Cipher.DECRYPT_MODE, privateKey)
                return cipher.doFinal(bytes)
            }
        }
        error("No RSA decryption provider")
    }

    private fun deleteCloudMessage(document: DocumentSnapshot) {
        document.reference.delete()
            .addOnFailureListener { error ->
                updateDebugStatus("message delete failed: ${error.message}")
            }
    }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun b64decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun markMessageSeen(id: String) {
        synchronized(receivedMessageIds) {
            receivedMessageIds.add(id)
            prefs.edit().putStringSet(KEY_SEEN_MESSAGE_IDS, receivedMessageIds.toSet()).apply()
        }
    }

    private fun sendChannelMessage() {
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
        appendMessage(remoteId, "Me", text)
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
                val chatId = remoteId.takeIf { it.isNotBlank() } ?: "unknown"
                appendMessage(chatId, contactName(chatId), text)
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
        ioScope.launch {
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
        return "$localId-${System.currentTimeMillis()}-$messageSeq"
    }

    private fun appendMessage(chatId: String, author: String, text: String) {
        val update = {
            val log = messageLogFor(chatId)
            log.append(author).append(": ").append(text).append('\n')
            prefs.edit().putString(chatLogKey(chatId), log.toString()).apply()
            if (binding.chatScreen.visibility == View.VISIBLE && chatId == remoteId) {
                binding.messages.text = log.toString()
            }
        }
        if (Thread.currentThread() == mainLooper.thread) {
            update()
        } else {
            runOnUiThread(update)
        }
    }

    private fun startAudio() {
        if (recording) return
        if (voiceChannel?.state() != DataChannel.State.OPEN) {
            updateDebugStatus("voice not open")
            return
        }
        recording = true

        val samplerate = 8000
        val frameSize = codec2.samplesPerFrame()
        val frameMs = ((frameSize * 1000L) / samplerate).coerceAtLeast(1L)
        val minBuf = AudioRecord.getMinBufferSize(samplerate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioBufferBytes = maxOf(minBuf, frameSize * 2 * 4)
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, samplerate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, audioBufferBytes)
        val track = AudioTrack(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(samplerate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            audioBufferBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)

        record.startRecording()
        track.play()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            updateDebugStatus("mic failed")
            recording = false
            record.release()
            track.release()
            return
        }
        updateDebugStatus("audio ${frameSize}sp ${frameMs}ms")
        audioRecord = record
        audioTrack = track

        recordJob = ioScope.launch {
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
                        if (voiceChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(makeVoicePacket(encoded)), false)) == true) {
                            voiceSent += 1
                            if (voiceSent % 25 == 0) updateDebugStatus("voice sent")
                        }
                    }
                }
            }
        }

        playJob = ioScope.launch {
            while (recording) {
                val encoded = nextVoiceFrame()
                val pcm = if (encoded != null) {
                    codec2.decode(encoded)
                } else {
                    ShortArray(frameSize)
                }
                val byteBuf = ByteArray(pcm.size * 2)
                ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
                track.write(byteBuf, 0, byteBuf.size)
                voicePlayed += 1
                if (voicePlayed % 25 == 0) updateDebugStatus("voice play")
                delay(frameMs)
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
            voiceReceived += 1
            if (voiceReceived % 25 == 0) updateDebugStatus("voice recv")
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
        val firestore = db ?: return
        val callId = currentCallId ?: return
        val listener = firestore.collection("calls").document(callId)
            .collection("candidates")
            .addSnapshotListener { snap, _ ->
                snap?.documentChanges?.forEach {
                    val document = it.document
                    val map = document.data
                    val sessionId = map["sessionId"] as? String
                    if (sessionId != currentSessionId) return@forEach
                    if (!processedCandidateIds.add(document.id)) return@forEach
                    if (map["sender"] == localId) return@forEach
                    val sdpMid = map["sdpMid"] as String?
                    val sdpMLineIndex = (map["sdpMLineIndex"] as? Long)?.toInt() ?: return@forEach
                    val candidate = map["candidate"] as String? ?: return@forEach
                    val cand = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                    peerConnection.addIceCandidate(cand)
                }
        }
        addListener(listener)
    }

    private fun firestoreOrWarn(): FirebaseFirestore? {
        val firestore = db
        if (firestore == null) {
            runOnUiThread { binding.status.text = "Missing Firebase config" }
        }
        return firestore
    }

    private fun resetPeerConnection(createLocalChannels: Boolean) {
        stopAudio()
        removeListeners()
        voiceChannel?.close()
        messageChannel?.close()
        voiceChannel = null
        messageChannel = null
        if (::peerConnection.isInitialized) {
            peerConnection.close()
            peerConnection.dispose()
        }
        clearCallState()
        initPeerConnection(createLocalChannels)
    }

    private fun disconnectCall() {
        val firestore = db
        val callId = currentCallId
        val sessionId = currentSessionId
        if (firestore == null || callId == null) {
            resetPeerConnection(createLocalChannels = false)
            updateDebugStatus("disconnected")
            return
        }

        firestore.collection("calls").document(callId).update(
            mapOf(
                "state" to "ended",
                "endedBy" to localId,
                "endedSessionId" to sessionId,
                "endedAt" to System.currentTimeMillis()
            )
        ).addOnCompleteListener {
            resetPeerConnection(createLocalChannels = false)
            updateDebugStatus("disconnected")
        }
    }

    private fun stopAudio() {
        recording = false
        recordJob?.cancel()
        playJob?.cancel()
        recordJob = null
        playJob = null

        audioRecord?.let { record ->
            runCatching {
                record.stop()
                record.release()
            }
        }
        audioTrack?.let { track ->
            runCatching {
                track.stop()
                track.release()
            }
        }
        audioRecord = null
        audioTrack = null
    }

    private fun clearCallState() {
        synchronized(voiceJitterBuffer) {
            voiceJitterBuffer.clear()
        }
        synchronized(pendingMessages) {
            pendingMessages.clear()
        }
        synchronized(processedCandidateIds) {
            processedCandidateIds.clear()
        }
        txVoiceSeq = 0
        rxVoiceSeq = null
        answerProcessed = false
        offerProcessed = false
        voiceSent = 0
        voiceReceived = 0
        voicePlayed = 0
        currentSessionId = null
        currentCallId = null
    }

    private fun updateDebugStatus(event: String) {
        val pcState = if (::peerConnection.isInitialized) peerConnection.connectionState().toString() else "none"
        val peer = remoteId.takeIf { it.isNotBlank() }?.let { contactName(it) } ?: "no contact"
        val status = "$localId -> $peer | $event | v $voiceSent/$voiceReceived/$voicePlayed"
        Log.d(
            LOG_TAG,
            "$status pc=$pcState voice=${voiceChannel?.state()} msg=${messageChannel?.state()}"
        )
        runOnUiThread {
            binding.status.text = status
            binding.chatStatus.text = status
        }
    }

    private fun addListener(listener: ListenerRegistration) {
        synchronized(listeners) {
            listeners += listener
        }
    }

    private fun removeListeners() {
        synchronized(listeners) {
            listeners.forEach { it.remove() }
            listeners.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
        removeListeners()
        incomingListener?.remove()
        incomingListener = null
        messagePollJob?.cancel()
        messagePollJob = null
        ioScope.cancel()
        codec2.release()
        if (::peerConnection.isInitialized) {
            peerConnection.close()
            peerConnection.dispose()
        }
        if (::peerFactory.isInitialized) peerFactory.dispose()
    }

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
        private const val TEXT_RETRY_COUNT = 6
        private const val TEXT_RETRY_DELAY_MS = 2_000L
        private const val MESSAGE_POLL_MS = 5_000L
        private const val VOICE_HEADER_BYTES = 4
        private const val START_JITTER_FRAMES = 3
        private const val MAX_JITTER_FRAMES = 12
        private const val RSA_KEY_BITS = 2048
        private const val AES_KEY_BITS = 256
        private const val AES_GCM_IV_BYTES = 12
        private const val AES_GCM_TAG_BITS = 128
        private const val RSA_KEY_ALGORITHM = "RSA"
        private const val AES_MESSAGE_ALGORITHM = "AES/GCM/NoPadding"
        private const val RSA_OAEP_SHA256 = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private const val RSA_OAEP_SHA1 = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
        private const val LOG_TAG = "XxxLinkCall"
        private const val PREFS_NAME = "xxxlink_prefs"
        private const val KEY_LOCAL_ID = "local_id"
        private const val KEY_MESSAGE_PUBLIC_KEY = "message_public_key"
        private const val KEY_MESSAGE_PRIVATE_KEY = "message_private_key"
        private const val KEY_CONTACT_IDS = "contact_ids"
        private const val KEY_CONTACT_PREFIX = "contact_name_"
        private const val KEY_CHAT_LOG_PREFIX = "chat_log_"
        private const val KEY_SEEN_MESSAGE_IDS = "seen_message_ids"
    }

    private enum class VoiceMode(val label: String, val codec2Mode: Int) {
        COMFY("comfy", 0),
        BASE("base", 4),
        XTREAM("Xtream", 5);

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

    private data class IncomingCall(
        val callId: String,
        val sessionId: String,
        val callerId: String,
        val offer: String,
        val mode: VoiceMode
    )

    private data class EncryptedMessage(
        val encryptedKey: String,
        val iv: String,
        val cipherText: String,
        val keyAlgorithm: String
    )

    private data class RsaCipherBytes(
        val bytes: ByteArray,
        val algorithm: String
    )
}

open class SdpObserverAdapter : SdpObserver {
    override fun onSetFailure(p0: String?) {}
    override fun onSetSuccess() {}
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onCreateFailure(p0: String?) {}
}
