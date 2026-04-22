package com.example.p2pcodec2

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
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
import kotlin.math.absoluteValue

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
    private var callTimerJob: Job? = null
    private var callTimeoutJob: Job? = null
    private var appInForeground = false

    private var localId = ""
    private var remoteId = ""
    private var currentCallId: String? = null
    private var pendingIncomingCall: IncomingCall? = null

    @Volatile
    private var recording = false
    private var recordJob: Job? = null
    private var playJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var localWebRtcAudioSource: AudioSource? = null
    private var localWebRtcAudioTrack: org.webrtc.AudioTrack? = null
    private var audioManager: AudioManager? = null
    @Volatile
    private var micMuted = false
    private var speakerEnabled = true
    private var callStartedAtMs = 0L
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
        createNotificationChannel()

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        localId = getOrCreateLocalId()
        ensureMessageKeyPair()
        receivedMessageIds += prefs.getStringSet(KEY_SEEN_MESSAGE_IDS, emptySet()).orEmpty()
        binding.myId.text = "Your ID: $localId"

        initFirebase()
        currentVoiceMode.codec2Mode?.let { codec2 = Codec2Bridge(it) }
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

    override fun onStart() {
        super.onStart()
        appInForeground = true
    }

    override fun onStop() {
        appInForeground = false
        super.onStop()
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
        requestNotificationPermissionIfNeeded()
        publishPublicMessageKey()
        listenIncomingCalls()
        startMessagePolling()
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTIFICATIONS
            )
        }
    }

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
        audioManager = am
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
    }

    private fun configureAudioForCall() {
        val am = audioManager ?: getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager = am
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        applyAudioRoute()
    }

    private fun applyAudioRoute() {
        val am = audioManager ?: return
        am.isSpeakerphoneOn = speakerEnabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (speakerEnabled) {
                val speaker = am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) am.setCommunicationDevice(speaker)
            } else {
                am.clearCommunicationDevice()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val preferredType = if (speakerEnabled) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            findOutputDevice(preferredType)?.let { audioTrack?.preferredDevice = it }
        }
        Log.d(
            LOG_TAG,
            "route speaker=$speakerEnabled mode=${am.mode} speakerphone=${am.isSpeakerphoneOn} trackDevice=${audioTrack?.preferredDevice?.type}"
        )
        updateActiveCallButtons()
    }

    private fun findOutputDevice(type: Int): AudioDeviceInfo? {
        val am = audioManager ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == type }
    }

    private fun restoreAudioAfterCall() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        }
        am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
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
            PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer(),
            meteredTurnServer("turn:global.relay.metered.ca:80"),
            meteredTurnServer("turn:global.relay.metered.ca:80?transport=tcp"),
            meteredTurnServer("turn:global.relay.metered.ca:443"),
            meteredTurnServer("turns:global.relay.metered.ca:443?transport=tcp"),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(servers).apply {
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 4
        }

        peerConnection = peerFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
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
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                updateDebugStatus("ice=$p0")
            }
            override fun onConnectionChange(p0: PeerConnection.PeerConnectionState?) {
                if (p0 == PeerConnection.PeerConnectionState.CONNECTED) {
                    cancelCallTimeout()
                    if (currentVoiceMode.webRtcAudio) startWebRtcAudio()
                    runOnUiThread { showActiveCallScreen() }
                }
                updateDebugStatus("pc=$p0")
            }
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
                (p0?.track() as? org.webrtc.AudioTrack)?.setEnabled(true)
                Log.d(LOG_TAG, "remote audio track added kind=${p0?.track()?.kind()}")
            }
            override fun onRenegotiationNeeded() {}
        })!!

        if (currentVoiceMode.webRtcAudio) {
            attachLocalWebRtcAudio()
        }

        if (createLocalChannels) {
            if (!currentVoiceMode.webRtcAudio) {
                val voiceInit = DataChannel.Init().apply {
                    ordered = true
                    maxRetransmits = 2
                }
                voiceChannel = peerConnection.createDataChannel("voice", voiceInit)
                setupVoiceReceiver(voiceChannel!!)
            }

            val messageInit = DataChannel.Init().apply {
                ordered = true
            }
            messageChannel = peerConnection.createDataChannel("messages", messageInit)
            setupMessageReceiver(messageChannel!!)
        }
    }

    private fun meteredTurnServer(url: String): PeerConnection.IceServer =
        PeerConnection.IceServer.builder(url)
            .setUsername(METERED_TURN_USERNAME)
            .setPassword(METERED_TURN_PASSWORD)
            .createIceServer()

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
        binding.btnCancelOutgoingCall.setOnClickListener { disconnectCall() }
        binding.btnDisconnect.setOnClickListener { disconnectCall() }
        binding.btnEndActiveCall.setOnClickListener { disconnectCall() }
        binding.btnMuteMic.setOnClickListener {
            micMuted = !micMuted
            localWebRtcAudioTrack?.setEnabled(!micMuted)
            Log.d(LOG_TAG, "mic toggled muted=$micMuted recording=$recording sent=$voiceSent recv=$voiceReceived played=$voicePlayed")
            updateDebugStatus(if (micMuted) "mic muted" else "mic on")
            updateActiveCallButtons()
        }
        binding.btnToggleSpeaker.setOnClickListener {
            speakerEnabled = !speakerEnabled
            Log.d(LOG_TAG, "speaker toggled speaker=$speakerEnabled recording=$recording")
            applyAudioRoute()
            updateDebugStatus(if (speakerEnabled) "speaker on" else "phone audio")
        }
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
        showCallControls(false)
        updateActiveCallButtons()
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
        binding.outgoingCallScreen.visibility = View.GONE
        binding.activeCallScreen.visibility = View.GONE
        showCallControls(false)
        renderContacts()
    }

    private fun returnToPostCallScreen() {
        if (remoteId.isNotBlank()) {
            openChat(remoteId)
        } else {
            showContactList()
        }
    }

    private fun openChat(id: String) {
        remoteId = id
        binding.chatTitle.text = contactName(id)
        binding.chatPeerId.text = id
        binding.messages.text = messageLogFor(id).toString()
        binding.contactListScreen.visibility = View.GONE
        binding.chatScreen.visibility = View.VISIBLE
        binding.incomingCallScreen.visibility = View.GONE
        binding.outgoingCallScreen.visibility = View.GONE
        binding.activeCallScreen.visibility = View.GONE
        showCallControls(false)
        updateDebugStatus("chat")
    }

    private fun showCallControls(@Suppress("UNUSED_PARAMETER") visible: Boolean) {
        binding.callControls.visibility = View.GONE
    }

    private fun showActiveCallScreen() {
        if (remoteId.isBlank()) return
        binding.contactListScreen.visibility = View.GONE
        binding.chatScreen.visibility = View.GONE
        binding.incomingCallScreen.visibility = View.GONE
        binding.outgoingCallScreen.visibility = View.GONE
        binding.activeCallScreen.visibility = View.VISIBLE
        binding.activeCallName.text = contactName(remoteId)
        binding.activeCallStatus.text = if (recording) "Connected" else "Connecting"
        if (callStartedAtMs == 0L) binding.callTimer.text = "00:00"
        updateActiveCallButtons()
    }

    private fun showOutgoingCallScreen(secondsLeft: Int = CALL_SETUP_TIMEOUT_SECONDS) {
        if (remoteId.isBlank()) return
        binding.contactListScreen.visibility = View.GONE
        binding.chatScreen.visibility = View.GONE
        binding.incomingCallScreen.visibility = View.GONE
        binding.activeCallScreen.visibility = View.GONE
        binding.outgoingCallScreen.visibility = View.VISIBLE
        binding.outgoingCallName.text = contactName(remoteId)
        binding.outgoingCallCountdown.text = secondsLeft.toString()
    }

    private fun updateOutgoingCallCountdown(secondsLeft: Int) {
        if (!::binding.isInitialized) return
        if (Thread.currentThread() != mainLooper.thread) {
            runOnUiThread { updateOutgoingCallCountdown(secondsLeft) }
            return
        }
        if (binding.outgoingCallScreen.visibility == View.VISIBLE) {
            binding.outgoingCallCountdown.text = secondsLeft.toString()
        }
    }

    private fun updateActiveCallButtons() {
        if (!::binding.isInitialized) return
        if (Thread.currentThread() != mainLooper.thread) {
            runOnUiThread { updateActiveCallButtons() }
            return
        }
        binding.btnMuteMic.text = if (micMuted) "Mic off" else "Mic"
        binding.btnToggleSpeaker.text = if (speakerEnabled) "Speaker" else "Phone"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Messages and calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming messages and calls"
            enableVibration(true)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun notifyIncomingCall(incoming: IncomingCall) {
        if (appInForeground || !hasNotificationPermission()) return
        val callerName = contactName(incoming.callerId)
        val intent = contentIntent()
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Incoming call")
            .setContentText(callerName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(intent)
            .setFullScreenIntent(intent, true)
            .setAutoCancel(true)
            .build()
        notificationManager().notify(NOTIFICATION_CALL_ID, notification)
    }

    private fun notifyIncomingMessage(senderId: String, text: String) {
        if (appInForeground || !hasNotificationPermission()) return
        val senderName = contactName(senderId)
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(senderName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        notificationManager().notify((NOTIFICATION_MESSAGE_ID_BASE + senderId.hashCode()).absoluteValue, notification)
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

        releaseSharedCodec()
        currentVoiceMode = mode
        mode.codec2Mode?.let { codec2 = Codec2Bridge(it) }
        updateModeStatus()
    }

    private fun applyVoiceModeFromRemote(mode: VoiceMode) {
        if (mode == currentVoiceMode) return
        if (recording) return

        releaseSharedCodec()
        currentVoiceMode = mode
        mode.codec2Mode?.let { codec2 = Codec2Bridge(it) }

        updateModeSelectionFromRemote(mode)
    }

    private fun updateModeSelectionFromRemote(mode: VoiceMode) {
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
        runOnUiThread { showOutgoingCallScreen() }
        startCallSetupTimeout(callId, sessionId)
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
        binding.contactListScreen.visibility = View.GONE
        binding.chatScreen.visibility = View.GONE
        binding.activeCallScreen.visibility = View.GONE
        binding.outgoingCallScreen.visibility = View.GONE
        binding.incomingCallScreen.visibility = View.VISIBLE
        binding.status.text = "Incoming call from $callerName"
        binding.chatStatus.text = "Incoming call from $callerName"
        notifyIncomingCall(incoming)
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
        if (incoming.mode != currentVoiceMode && !recording) {
            releaseSharedCodec()
            currentVoiceMode = incoming.mode
            incoming.mode.codec2Mode?.let { codec2 = Codec2Bridge(it) }
        }
        resetPeerConnection(createLocalChannels = false)
        currentCallId = incoming.callId
        currentSessionId = incoming.sessionId
        listenCallEnd()
        listenCandidates()
        runOnUiThread {
            updateModeSelectionFromRemote(incoming.mode)
            rememberContact(incoming.callerId)
            binding.activeCallStatus.text = "Connecting"
            showActiveCallScreen()
        }

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
                                startCallSetupTimeout(incoming.callId, incoming.sessionId)
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
                        runOnUiThread { showActiveCallScreen() }
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
        binding.outgoingCallScreen.visibility = View.GONE
        binding.activeCallScreen.visibility = View.GONE
        returnToPostCallScreen()
        showCallControls(false)
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
            notifyIncomingMessage(senderId, text)
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
                runOnUiThread { notifyIncomingMessage(chatId, text) }
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

    private fun startCallSetupTimeout(callId: String, sessionId: String) {
        cancelCallTimeout()
        callTimeoutJob = ioScope.launch {
            for (secondsLeft in CALL_SETUP_TIMEOUT_SECONDS downTo 1) {
                val stillWaiting = currentCallId == callId &&
                    currentSessionId == sessionId &&
                    !recording &&
                    (!::peerConnection.isInitialized ||
                        peerConnection.connectionState() != PeerConnection.PeerConnectionState.CONNECTED)
                if (!stillWaiting) return@launch
                updateOutgoingCallCountdown(secondsLeft)
                delay(1_000L)
            }

            val timedOut = currentCallId == callId &&
                currentSessionId == sessionId &&
                !recording &&
                (!::peerConnection.isInitialized ||
                    peerConnection.connectionState() != PeerConnection.PeerConnectionState.CONNECTED)
            if (!timedOut) return@launch

            db?.collection("calls")?.document(callId)?.update(
                mapOf(
                    "state" to "ended",
                    "endedBy" to localId,
                    "endedSessionId" to sessionId,
                    "endedReason" to "timeout",
                    "endedAt" to System.currentTimeMillis()
                )
            )

            runOnUiThread {
                resetPeerConnection(createLocalChannels = false)
                returnToPostCallScreen()
                showCallControls(false)
                binding.status.text = "Call timed out"
                if (binding.chatScreen.visibility == View.VISIBLE) {
                    binding.chatStatus.text = "Call timed out"
                }
                updateDebugStatus("call timeout")
            }
        }
    }

    private fun cancelCallTimeout() {
        callTimeoutJob?.cancel()
        callTimeoutJob = null
    }

    private fun startCallTimer() {
        if (callStartedAtMs == 0L) callStartedAtMs = System.currentTimeMillis()
        if (callTimerJob?.isActive == true) return
        callTimerJob = ioScope.launch {
            while (isActive) {
                val elapsedSeconds = ((System.currentTimeMillis() - callStartedAtMs) / 1000L)
                    .coerceAtLeast(0L)
                runOnUiThread {
                    binding.callTimer.text = formatCallDuration(elapsedSeconds)
                    binding.activeCallStatus.text = "Connected"
                }
                delay(1_000L)
            }
        }
    }

    private fun stopCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
        callStartedAtMs = 0L
        if (::binding.isInitialized) {
            runOnUiThread { binding.callTimer.text = "00:00" }
        }
    }

    private fun formatCallDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%02d:%02d".format(Locale.US, minutes, seconds)
        }
    }

    private fun attachLocalWebRtcAudio() {
        if (localWebRtcAudioTrack != null) return
        val constraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            optional.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
        }
        val source = peerFactory.createAudioSource(constraints)
        val track = peerFactory.createAudioTrack("audio-$localId", source).apply {
            setEnabled(!micMuted)
        }
        localWebRtcAudioSource = source
        localWebRtcAudioTrack = track
        peerConnection.addTrack(track, listOf("stream-$localId"))
        Log.d(LOG_TAG, "local WebRTC audio attached mode=${currentVoiceMode.label}")
    }

    private fun startWebRtcAudio() {
        if (recording) return
        cancelCallTimeout()
        recording = true
        configureAudioForCall()
        localWebRtcAudioTrack?.setEnabled(!micMuted)
        updateDebugStatus("webrtc audio ${currentVoiceMode.label}")
        runOnUiThread { showActiveCallScreen() }
        startCallTimer()
    }

    private fun startAudio() {
        if (currentVoiceMode.webRtcAudio) {
            startWebRtcAudio()
            return
        }
        if (recording) return
        if (voiceChannel?.state() != DataChannel.State.OPEN) {
            updateDebugStatus("voice not open")
            return
        }
        cancelCallTimeout()
        recording = true
        configureAudioForCall()

        val samplerate = 8000
        val mode = currentVoiceMode
        val encoder = mode.codec2Mode?.let { Codec2Bridge(it) }
        val decoder = mode.codec2Mode?.let { Codec2Bridge(it) }
        val frameSize = mode.frameSamples(encoder)
        val frameMs = ((frameSize * 1000L) / samplerate).coerceAtLeast(1L)
        val frameBytes = frameSize * 2
        val minRecordBuffer = AudioRecord.getMinBufferSize(
            samplerate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minTrackBuffer = AudioTrack.getMinBufferSize(
            samplerate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recordBufferBytes = maxOf(minRecordBuffer, frameBytes * 6)
        val trackBufferBytes = maxOf(minTrackBuffer, frameBytes * 12)
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, samplerate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufferBytes)
        configureCodec2AudioEffects(record.audioSessionId)
        val track = AudioTrack(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(samplerate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            trackBufferBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val preferredType = if (speakerEnabled) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            findOutputDevice(preferredType)?.let { track.preferredDevice = it }
        }

        record.startRecording()
        track.play()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            updateDebugStatus("mic failed")
            recording = false
            record.release()
            track.release()
            encoder?.release()
            decoder?.release()
            restoreAudioAfterCall()
            return
        }
        updateDebugStatus("audio ${frameSize}sp ${frameMs}ms ${mode.label}")
        audioRecord = record
        audioTrack = track
        runOnUiThread { showActiveCallScreen() }
        startCallTimer()

        recordJob = ioScope.launch {
            try {
                val buf = ShortArray(frameSize)
                val mutedFrame = ShortArray(frameSize)
                Log.d(LOG_TAG, "record loop start muted=$micMuted")
                while (recording) {
                    val read = record.read(buf, 0, frameSize)
                    if (read > 0) {
                        val source = if (micMuted) {
                            mutedFrame
                        } else if (read == frameSize) {
                            buf
                        } else {
                            buf.copyOf(frameSize)
                        }
                        val prepared = if (mode.rawPcm) source else preparePcmForCodec2(source)
                        val encoded = if (mode.rawPcm) {
                            pcmToBytes(prepared)
                        } else {
                            encoder?.encode(prepared) ?: continue
                        }
                        if (voiceChannel?.state() == DataChannel.State.OPEN) {
                            if (voiceChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(makeVoicePacket(encoded)), false)) == true) {
                                voiceSent += 1
                                if (voiceSent % 25 == 0) updateDebugStatus("voice sent")
                            } else {
                                Log.w(LOG_TAG, "voice send returned false state=${voiceChannel?.state()}")
                            }
                        }
                    } else if (read < 0) {
                        Log.w(LOG_TAG, "record read error=$read")
                        delay(frameMs)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "record loop failed", error)
                recording = false
                updateDebugStatus("record error ${error.javaClass.simpleName}")
            } finally {
                Log.d(LOG_TAG, "record loop stop recording=$recording")
                encoder?.release()
                runCatching { record.stop() }
                runCatching { record.release() }
                if (audioRecord === record) audioRecord = null
                if (!recording) restoreAudioAfterCall()
            }
        }

        playJob = ioScope.launch {
            try {
                Log.d(LOG_TAG, "play loop start")
                while (recording) {
                    val encoded = nextVoiceFrame()
                    val pcm = if (encoded != null) {
                        if (mode.rawPcm) {
                            bytesToPcm(encoded, frameSize)
                        } else {
                            decoder?.decode(encoded) ?: ShortArray(frameSize)
                        }
                    } else {
                        ShortArray(frameSize)
                    }
                    val byteBuf = ByteArray(pcm.size * 2)
                    ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
                    val written = track.write(byteBuf, 0, byteBuf.size)
                    if (written < 0) {
                        updateDebugStatus("play write=$written")
                        delay(frameMs)
                    }
                    voicePlayed += 1
                    if (voicePlayed % 25 == 0) updateDebugStatus("voice play")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "play loop failed", error)
                updateDebugStatus("play error ${error.javaClass.simpleName}")
            } finally {
                Log.d(LOG_TAG, "play loop stop recording=$recording")
                decoder?.release()
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
                if (audioTrack === track) audioTrack = null
            }
        }
    }

    private fun configureCodec2AudioEffects(audioSessionId: Int) {
        releaseCodec2AudioEffects()
        noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
        } else {
            null
        }
        acousticEchoCanceler = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
        } else {
            null
        }
        automaticGainControl = if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
        } else {
            null
        }
        Log.d(
            LOG_TAG,
            "codec2 effects ns=${noiseSuppressor?.enabled} aec=${acousticEchoCanceler?.enabled} agc=${automaticGainControl?.enabled}"
        )
    }

    private fun releaseCodec2AudioEffects() {
        noiseSuppressor?.release()
        acousticEchoCanceler?.release()
        automaticGainControl?.release()
        noiseSuppressor = null
        acousticEchoCanceler = null
        automaticGainControl = null
    }

    private fun preparePcmForCodec2(source: ShortArray): ShortArray {
        var sumSquares = 0.0
        for (sample in source) {
            sumSquares += (sample.toDouble() * sample.toDouble())
        }
        val rms = kotlin.math.sqrt(sumSquares / source.size.toDouble())
        val gain = when {
            rms < 1.0 -> 1.0
            else -> (CODEC2_TARGET_RMS / rms).coerceIn(0.65, CODEC2_MAX_GAIN)
        }

        val output = ShortArray(source.size)
        for (i in source.indices) {
            val amplified = source[i].toDouble() * gain
            output[i] = amplified
                .coerceIn(-CODEC2_LIMIT.toDouble(), CODEC2_LIMIT.toDouble())
                .toInt()
                .toShort()
        }
        return output
    }

    private fun makeVoicePacket(encoded: ByteArray): ByteArray {
        val seq = txVoiceSeq++
        return ByteBuffer.allocate(VOICE_HEADER_BYTES + encoded.size)
            .putInt(seq)
            .put(encoded)
            .array()
    }

    private fun pcmToBytes(pcm: ShortArray): ByteArray {
        val bytes = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        return bytes
    }

    private fun bytesToPcm(bytes: ByteArray, frameSize: Int): ShortArray {
        val samples = ShortArray(frameSize)
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        shorts.get(samples, 0, minOf(samples.size, shorts.remaining()))
        return samples
    }

    private fun receiveVoicePacket(packet: ByteArray) {
        val expectedPayloadBytes = currentVoiceMode.expectedPayloadBytes()
        if (packet.size < VOICE_HEADER_BYTES + expectedPayloadBytes) return

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
            val firstBufferedSeq = if (voiceJitterBuffer.isEmpty()) null else voiceJitterBuffer.firstKey()
            if (firstBufferedSeq != null && seq < firstBufferedSeq) {
                rxVoiceSeq = firstBufferedSeq
                Log.d(LOG_TAG, "voice resync old=$seq new=$firstBufferedSeq buffered=${voiceJitterBuffer.size}")
                return voiceJitterBuffer.remove(firstBufferedSeq)
            }

            if (!voiceJitterBuffer.containsKey(seq)) {
                val nextBufferedSeq = firstBufferedSeq
                if (nextBufferedSeq != null && nextBufferedSeq - seq > MAX_MISSING_VOICE_FRAMES) {
                    rxVoiceSeq = nextBufferedSeq + 1
                    Log.d(LOG_TAG, "voice skip gap=${nextBufferedSeq - seq} expected=$seq next=$nextBufferedSeq buffered=${voiceJitterBuffer.size}")
                    return voiceJitterBuffer.remove(nextBufferedSeq)
                }
            }

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
                    Log.d(LOG_TAG, "remote candidate ${candidateType(candidate)} mid=$sdpMid")
                    val cand = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                    peerConnection.addIceCandidate(cand)
                }
        }
        addListener(listener)
    }

    private fun candidateType(candidate: String): String =
        candidate.substringAfter(" typ ", "unknown").substringBefore(' ')

    private fun firestoreOrWarn(): FirebaseFirestore? {
        val firestore = db
        if (firestore == null) {
            runOnUiThread { binding.status.text = "Missing Firebase config" }
        }
        return firestore
    }

    private fun resetPeerConnection(createLocalChannels: Boolean) {
        cancelCallTimeout()
        stopAudio()
        removeListeners()
        voiceChannel?.close()
        messageChannel?.close()
        voiceChannel = null
        messageChannel = null
        localWebRtcAudioTrack?.dispose()
        localWebRtcAudioSource?.dispose()
        localWebRtcAudioTrack = null
        localWebRtcAudioSource = null
        if (::peerConnection.isInitialized) {
            peerConnection.close()
            peerConnection.dispose()
        }
        clearCallState()
        initPeerConnection(createLocalChannels)
        if (!createLocalChannels) {
            runOnUiThread {
                showCallControls(false)
                binding.outgoingCallScreen.visibility = View.GONE
                binding.activeCallScreen.visibility = View.GONE
            }
        }
    }

    private fun disconnectCall() {
        val firestore = db
        val callId = currentCallId
        val sessionId = currentSessionId
        if (firestore == null || callId == null) {
            resetPeerConnection(createLocalChannels = false)
            returnToPostCallScreen()
            showCallControls(false)
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
            returnToPostCallScreen()
            showCallControls(false)
            updateDebugStatus("disconnected")
        }
    }

    private fun stopAudio() {
        recording = false
        stopCallTimer()
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
                track.pause()
                track.flush()
                track.release()
            }
        }
        audioRecord = null
        audioTrack = null
        localWebRtcAudioTrack?.setEnabled(false)
        releaseCodec2AudioEffects()
        restoreAudioAfterCall()
    }

    private fun releaseSharedCodec() {
        if (::codec2.isInitialized) {
            codec2.release()
        }
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
        micMuted = false
        speakerEnabled = true
        currentSessionId = null
        currentCallId = null
        runOnUiThread { updateActiveCallButtons() }
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
        cancelCallTimeout()
        ioScope.cancel()
        releaseSharedCodec()
        if (::peerConnection.isInitialized) {
            peerConnection.close()
            peerConnection.dispose()
        }
        if (::peerFactory.isInitialized) peerFactory.dispose()
    }

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
        private const val REQ_POST_NOTIFICATIONS = 1002
        private const val TEXT_RETRY_COUNT = 6
        private const val TEXT_RETRY_DELAY_MS = 2_000L
        private const val MESSAGE_POLL_MS = 5_000L
        private const val CALL_SETUP_TIMEOUT_SECONDS = 30
        private const val VOICE_HEADER_BYTES = 4
        private const val START_JITTER_FRAMES = 3
        private const val MAX_JITTER_FRAMES = 24
        private const val MAX_MISSING_VOICE_FRAMES = 6
        private const val RAW_PCM_SAMPLES_PER_FRAME = 160
        private const val CODEC2_TARGET_RMS = 6500.0
        private const val CODEC2_MAX_GAIN = 3.0
        private const val CODEC2_LIMIT = 30000
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
        private const val NOTIFICATION_CHANNEL_ID = "xxxlink_messages_calls"
        private const val NOTIFICATION_CALL_ID = 5001
        private const val NOTIFICATION_MESSAGE_ID_BASE = 6000
        private const val METERED_TURN_USERNAME = "adf897ba2cd48862e125b3e7"
        private const val METERED_TURN_PASSWORD = "jMbM3vnWcjOX7Vt2"

        private fun sharedCodecBytesPerFrame(codec2Mode: Int?): Int =
            when (codec2Mode) {
                0 -> 8
                1 -> 6
                2 -> 8
                3 -> 7
                4 -> 7
                5 -> 6
                8 -> 4
                else -> RAW_PCM_SAMPLES_PER_FRAME * 2
            }
    }

    private enum class VoiceMode(
        val label: String,
        val codec2Mode: Int?,
        val rawPcm: Boolean = false,
        val webRtcAudio: Boolean = false,
        private val rawSamplesPerFrame: Int = RAW_PCM_SAMPLES_PER_FRAME
    ) {
        COMFY("comfy", null, webRtcAudio = true),
        BASE("base", 0),
        XTREAM("Xtream", 1);

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

        fun frameSamples(codec: Codec2Bridge?): Int =
            if (rawPcm) rawSamplesPerFrame else codec?.samplesPerFrame() ?: RAW_PCM_SAMPLES_PER_FRAME

        fun expectedPayloadBytes(): Int =
            if (rawPcm) rawSamplesPerFrame * 2 else sharedCodecBytesPerFrame(codec2Mode)
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
