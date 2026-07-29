package com.example.p2pcodec2

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.example.xxxlinkxxx.BuildConfig
import com.example.xxxlinkxxx.R
import com.example.xxxlinkxxx.databinding.ActivityMainBinding
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.spec.ECGenParameterSpec
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.text.InputType
import android.provider.Settings
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlin.math.absoluteValue
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt

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
    private var unreadNotificationCount = 0
    private var pendingMicAction: PendingMicAction? = null

    private var localId = ""
    private var remoteId = ""
    @Volatile private var authUid: String? = null
    @Volatile private var currentCallId: String? = null
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
    private enum class MsgStatus { SENT, DELIVERED, READ }
    private enum class PhotoState { OK, PENDING, FAILED }

    // ConcurrentHashMap: these maps are mutated from main thread (UI events,
    // sendMessage callbacks), signaling thread (DataChannel handleMessagePacket),
    // and Firestore listener thread (processReceiptDoc). Plain mutableMapOf would
    // throw ConcurrentModificationException during renderChatMessages iteration.
    private val messageLogs = java.util.concurrent.ConcurrentHashMap<String, StringBuilder>()
    private val pendingMessages = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var chatDividerLineIndex = -1   // index where "new messages" start; -1 = no divider
    private val messageStatuses = java.util.concurrent.ConcurrentHashMap<String, MsgStatus>()
    private val incomingMsgIds  = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    private val pendingCloudReadReceipts = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val receivedMessageIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val messageSeqCounter = AtomicInteger(0)
    private var coreStarted = false
    private var currentVoiceMode = VoiceMode.COMFY

    // ── Reply-to state ────────────────────────────────────────────────────────
    /** msgId of the message currently being replied to, or null. */
    private var replyingToMsgId: String? = null

    // ── Chat list filter ──────────────────────────────────────────────────────
    /** Search query for the chat list — empty string disables filtering. */
    private var chatListQuery: String = ""

    // ── In-app incoming-call ringer ───────────────────────────────────────────
    // When app is foreground the FCM channel notification is suppressed, so the
    // OS does not play the channel sound. Play the ringtone ourselves on the
    // RINGTONE audio stream and vibrate. Stopped on accept/decline/cancel.
    private var incomingRingPlayer: MediaPlayer? = null
    private var incomingVibrator: android.os.Vibrator? = null

    // ── App lock ──────────────────────────────────────────────────────────────
    private var appUnlocked = false

    // ── Chat search ───────────────────────────────────────────────────────────
    private var chatSearchQuery = ""

    @Volatile private var answerProcessed = false
    @Volatile private var offerProcessed = false
    @Volatile private var myEcKeyPair: KeyPair? = null
    private val processedCandidateIds: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    private var voiceSent = 0
    private var voiceReceived = 0
    private var voicePlayed = 0
    @Volatile private var currentSessionId: String? = null
    private val dismissedIncomingSessions = mutableSetOf<String>()
    private val photoAuthPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { authenticateWithPhoto(it) }
    }

    // ── Photo transfer ────────────────────────────────────────────────────────
    // Mutated from main (UI), Firestore listener thread, and WebRTC signaling
    // thread. @Volatile fixes memory-visibility races on multi-core ARM.
    @Volatile private var photoTransferPc: PeerConnection? = null
    @Volatile private var photoTransferDc: DataChannel? = null
    @Volatile private var outgoingPhotoTransferId: String? = null
    @Volatile private var outgoingPhotoChatId: String? = null
    private var incomingTransferListener: ListenerRegistration? = null
    // Lock for cleanupPhotoTransfer to prevent concurrent double-dispose
    // from main (PHO_END), IO scope (send catch), and WebRTC signaling threads.
    private val photoTransferLock = Any()
    // Listeners for the active photo transfer — kept separate from `listeners` so
    // removeListeners() (called on call setup/teardown) cannot kill a mid-transfer.
    private val photoTransferListeners = mutableListOf<ListenerRegistration>()
    // Guard against double-send (observer + polling both triggering sendPhotoOverChannel)
    @Volatile private var photoSendInProgress = false
    @Volatile private var outgoingPhotoCompletionHandled = false

    // Incoming assembly
    private var assemblingTransferId: String? = null
    private var assemblingChatId: String? = null
    private var assemblingChunks: Array<ByteArray?>? = null
    private var assemblingExpected: Int = 0
    private var assemblingKey: ByteArray? = null
    private var assemblingIv: ByteArray? = null
    // Watchdog: aborts a stalled receive after PHOTO_RECEIVE_TIMEOUT_MS of inactivity
    // (no PHO_CHUNK / PHO_END seen). Resets the receiver back to idle so subsequent
    // incoming transfers can be accepted.
    @Volatile private var assemblingWatchdog: Runnable? = null
    /** Snapshot of when this session started; rejects stale `pending` offers older than this. */
    private val appSessionStartMs: Long = System.currentTimeMillis()
    private var assemblingReceived: Int = 0

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        Log.d(TAG, "XLINK_PHOTO picker returned uri=$uri")
        uri?.let { ioScope.launch { prepareAndSendPhoto(it) } }
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents ?: return@registerForActivityResult
        val scannedId = content.trim().uppercase(Locale.US)
        if (scannedId.matches(Regex("[A-Z0-9]{4,32}"))) {
            binding.addContactIdInput.setText(scannedId)
            showAddContactScreen()
        } else {
            Toast.makeText(this, "Invalid QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingBackupPhotoSecret: ByteArray? = null
    private var pendingRestoreUri: android.net.Uri? = null

    private val backupFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { fileUri ->
            pendingBackupPhotoSecret?.let { secret ->
                doExportBackup(fileUri, secret)
                pendingBackupPhotoSecret = null
            }
        }
    }

    private val restoreFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            pendingRestoreUri = it
            promptRestoreCredential()
        }
    }

    private val backupRestorePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { photoUri ->
            val restoreUri = pendingRestoreUri ?: return@let
            pendingRestoreUri = null
            ioScope.launch {
                val material = runCatching { photoAuthMaterial(photoUri) }.getOrElse {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Photo read failed", Toast.LENGTH_LONG).show() }
                    return@launch
                }
                doImportBackup(restoreUri, material.secret)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        createNotificationChannel()

        // Hardware/gesture back: navigate up within app instead of exiting
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.drawerLayout.isDrawerOpen(GravityCompat.START) ->
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    binding.chatScreen.visibility == View.VISIBLE ->
                        showContactList()
                    binding.addContactScreen.visibility == View.VISIBLE ->
                        showContactList()
                    else -> finish()
                }
            }
        })

        // EncryptedSharedPreferences (Keystore-wrapped AES-256-GCM master key) for
        // all on-disk state. Migrates legacy plain prefs once at first launch.
        prefs = com.example.p2pcodec2.SecurePrefs.get(this)
        initFirebase()
        currentVoiceMode.codec2Mode?.let { codec2 = Codec2Bridge(it) }
        binding.btnChooseAuthPhoto.setOnClickListener {
            photoAuthPicker.launch("image/*")
        }
        binding.btnLoginWithPassword.setOnClickListener { loginWithPhotoAndPassword() }
        binding.authPasswordInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateLoginButtonEnabled() }
        })

        val savedId = prefs.getString(KEY_LOCAL_ID, null)
        val savedV1Secret = prefs.getString(KEY_PHOTO_ACCOUNT_SECRET, null)
        val savedV2Secret = prefs.getString(KEY_V2_CRYPTO_SECRET, null)
        val authVersion = prefs.getInt(KEY_AUTH_VERSION, 1)

        when {
            // Already on v2 — go straight in.
            !savedId.isNullOrBlank() && authVersion >= 2 && !savedV2Secret.isNullOrBlank() -> {
                continueWithLocalIdentity(savedId)
            }
            // v1 user — force migration via the password screen.
            !savedId.isNullOrBlank() && !savedV1Secret.isNullOrBlank() -> {
                migratingV1 = true
                showPhotoAuthScreen()
            }
            // Brand-new install.
            else -> {
                migratingV1 = false
                showPhotoAuthScreen()
            }
        }
    }

    private fun continueWithLocalIdentity(id: String) {
        localId = id
        prefs.edit().putString(KEY_LOCAL_ID, localId).apply()
        receivedMessageIds += prefs.getStringSet(KEY_SEEN_MESSAGE_IDS, emptySet()).orEmpty()
        binding.myId.text = localId
        binding.photoAuthScreen.visibility = View.GONE
        binding.contactListScreen.visibility = View.VISIBLE

        val authVersion = prefs.getInt(KEY_AUTH_VERSION, 1)
        val v2SecretB64 = prefs.getString(KEY_V2_CRYPTO_SECRET, null)
        val v1SecretB64 = prefs.getString(KEY_PHOTO_ACCOUNT_SECRET, null)

        // Derive EC keypair off main thread; startCore only after keypair is ready.
        // v2 path: PBKDF2-derived crypto_secret seeds the keypair.
        // v1 path: legacy SHA256(salt+photo) secret seeds the keypair (kept for
        // backward compat until user goes through the migration screen).
        if (authVersion >= 2 && v2SecretB64 != null) {
            ioScope.launch {
                myEcKeyPair = deriveEcKeyPair(b64decode(v2SecretB64))
                withContext(Dispatchers.Main) { startCore() }
            }
        } else if (v1SecretB64 != null) {
            ioScope.launch {
                myEcKeyPair = deriveEcKeyPair(b64decode(v1SecretB64))
                withContext(Dispatchers.Main) { startCore() }
            }
        } else {
            showPhotoAuthScreen()
            return
        }
    }

    private fun showPhotoAuthScreen() {
        binding.photoAuthScreen.visibility = View.VISIBLE
        binding.contactListScreen.visibility = View.GONE
        binding.addContactScreen.visibility = View.GONE
        binding.chatScreen.visibility = View.GONE
        binding.incomingCallScreen.visibility = View.GONE
        binding.outgoingCallScreen.visibility = View.GONE
        binding.activeCallScreen.visibility = View.GONE
        if (migratingV1) {
            binding.photoAuthStatus.text = "Set an account password to continue\n(your existing account stays the same)"
            binding.btnChooseAuthPhoto.visibility = View.GONE
            binding.authPhotoChosen.visibility = View.GONE
        } else {
            binding.photoAuthStatus.text = "Select photo, then enter password"
            binding.btnChooseAuthPhoto.visibility = View.VISIBLE
            binding.btnChooseAuthPhoto.isEnabled = db != null
            binding.authPhotoChosen.visibility = View.GONE
        }
        updateLoginButtonEnabled()
    }

    private fun doLogout() {
        // Stop ongoing background work
        messagePollJob?.cancel()
        messagePollJob = null
        cancelCallTimeout()
        removeListeners()
        incomingListener?.remove()
        incomingListener = null
        incomingTransferListener?.remove()
        incomingTransferListener = null
        cleanupPhotoTransfer()

        // Wipe all local SharedPreferences
        prefs.edit().clear().apply()

        // Delete saved photos from internal storage
        runCatching { java.io.File(filesDir, "photos").deleteRecursively() }

        // Restart Activity — onCreate will find no saved ID and show auth screen
        recreate()
    }

    override fun onStart() {
        super.onStart()
        appInForeground = true
        prefs.edit().putBoolean(KEY_APP_IN_FOREGROUND, true).apply()
        clearNotificationBadges()
        checkAndShowLockScreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == CallForegroundService.ACTION_END_CALL) {
            disconnectCall()
            return
        }
        // Notification tap → open the chat with the sender (or group). For new
        // senders not yet in saved contacts, rememberContact() auto-adds them
        // so the card materialises in the chat list.
        intent.getStringExtra("openChatId")?.takeIf { it.isNotBlank() }?.let { id ->
            if (!isGroup(id) && id !in savedContactIds()) rememberContact(id)
            openChat(id)
        }
    }

    override fun onStop() {
        appInForeground = false
        prefs.edit().putBoolean(KEY_APP_IN_FOREGROUND, false).apply()
        // Lock app when going to background (require PIN/biometric on next foreground)
        if (prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)) {
            appUnlocked = false
        }
        // Stop in-app ringtone — backgrounded app means FCM channel takes over
        // and would otherwise stack on top of our MediaPlayer.
        stopIncomingRing()
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
        signInAnonymouslyIfNeeded()
    }

    /**
     * Ensure the client has a Firebase Auth uid. Anonymous sign-in must be enabled
     * in Firebase Console → Authentication → Sign-in method. The uid persists in
     * Firebase Auth state across app launches; we only call signIn when there's
     * no current user. After sign-in, [authUid] is set and subsequent Firestore
     * writes carry the auth context (used by the v2 security rules).
     */
    private fun signInAnonymouslyIfNeeded() {
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        val existing = auth.currentUser
        if (existing != null) {
            authUid = existing.uid
            if (BuildConfig.DEBUG) Log.d(TAG, "Firebase Auth: existing uid=$authUid")
            onAuthReady()
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                authUid = result.user?.uid
                if (BuildConfig.DEBUG) Log.d(TAG, "Firebase Auth: signed in anonymously uid=$authUid")
                onAuthReady()
            }
            .addOnFailureListener { e ->
                // Most likely Anonymous sign-in is disabled in Firebase Console.
                // Old rules still allow unauthenticated writes during the grace
                // window, so we keep working — security upgrade just stalls.
                Log.w(TAG, "Firebase Auth signIn failed: ${e.message}")
            }
    }

    /**
     * Installs a default uncaught-exception handler that appends the crash
     * stack trace to the beta log file before delegating to the system handler
     * (which kills the process). Idempotent across calls — chains to whatever
     * was installed before.
     */
    private fun installCrashLogger() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val file = com.example.p2pcodec2.BetaLogger.currentLogFile(this)
                file.parentFile?.mkdirs()
                java.io.FileOutputStream(file, true).use { out ->
                    val ts = System.currentTimeMillis()
                    out.write("\n=== UNCAUGHT EXCEPTION $ts thread=${t.name} ===\n".toByteArray())
                    val sw = java.io.StringWriter()
                    e.printStackTrace(java.io.PrintWriter(sw))
                    out.write(sw.toString().toByteArray())
                    out.write("\n".toByteArray())
                }
            }
            prior?.uncaughtException(t, e)
        }
    }

    private fun shareBetaLog() {
        ioScope.launch {
            val file = com.example.p2pcodec2.BetaLogger.snapshot(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (!file.exists() || file.length() == 0L) {
                    Toast.makeText(this@MainActivity, "No beta logs yet", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "X-link beta log ${BuildConfig.VERSION_NAME}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share beta log"))
            }
        }
    }

    /** Called once auth is established. Triggers bindLocalId if we have an identity. */
    private fun onAuthReady() {
        // Defer until both authUid and EC keypair + localId are ready.
        if (authUid == null || localId.isBlank() || myEcKeyPair == null) return
        bindLocalIdOnce()
    }

    @Volatile private var bindingInFlight = false
    @Volatile private var localIdBound = false

    private fun bindLocalIdOnce() {
        if (bindingInFlight || localIdBound) return
        val uid = authUid ?: return
        val kp = myEcKeyPair ?: return
        val id = localId.takeIf { it.isNotBlank() } ?: return
        bindingInFlight = true
        ioScope.launch {
            runCatching {
                val pubkey = b64(kp.public.encoded)
                // ts is included in the signed blob so the server can enforce a
                // freshness window (±60s). Closes the bindLocalId replay-window
                // gap and is mandatory in the server fix that re-introduces
                // stored-pubkey verification for key rotation.
                val ts = System.currentTimeMillis()
                val message = "$id\n$uid\n$ts".toByteArray(Charsets.UTF_8)
                val sig = java.security.Signature.getInstance("SHA256withECDSA").apply {
                    initSign(kp.private)
                    update(message)
                }
                val signature = b64(sig.sign())
                val functions = com.google.firebase.functions.FirebaseFunctions.getInstance()
                val data = hashMapOf(
                    "localId" to id,
                    "pubkey" to pubkey,
                    "signature" to signature,
                    "ts" to ts
                )
                val result = functions.getHttpsCallable("bindLocalId")
                    .call(data).await()
                Log.d(TAG, "bindLocalId ok: ${result.data}")
                // Refresh ID token so the new custom claim {localId} is picked up
                // by subsequent Firestore requests.
                com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    ?.getIdToken(true)?.await()
                localIdBound = true
            }.onFailure { e ->
                Log.w(TAG, "bindLocalId failed: ${e.message}")
            }
            bindingInFlight = false
        }
    }

    private fun startCore() {
        if (coreStarted) return
        coreStarted = true
        // Beta-only diagnostics: capture this app's logcat output to a rotating
        // file under filesDir/beta_logs/log.txt + install a crash hook that
        // appends the stack trace to the same file before the process dies.
        if (BuildConfig.VERSION_NAME.contains("beta", ignoreCase = true)) {
            com.example.p2pcodec2.BetaLogger.start(this, ioScope)
            installCrashLogger()
        }
        // Bind localId to current Firebase Auth uid (idempotent for same uid,
        // also re-binds to a new uid when the user logs in on a fresh install).
        onAuthReady()
        initAudio()
        initPeerFactory()
        resetPeerConnection(createLocalChannels = false)
        bindUI()
        requestNotificationPermissionIfNeeded()
        publishPublicMessageKey()
        syncFcmRegistrationToken()
        listenIncomingCalls()
        listenIncomingPhotoTransfers()
        startMessagePolling()
        resumeAllGroupListeners()
        BackupWorker.schedule(this)
        // Notification cold-launch: if the user tapped a chat notification
        // before MainActivity existed, the launching Intent carries openChatId.
        intent?.getStringExtra("openChatId")?.takeIf { it.isNotBlank() }?.let { id ->
            if (!isGroup(id) && id !in savedContactIds()) rememberContact(id)
            openChat(id)
            intent.removeExtra("openChatId")
        }
        // Silent background update check — shows dialog only if update found
        ioScope.launch {
            kotlinx.coroutines.delay(8_000)
            runCatching {
                val info = fetchLatestRelease() ?: return@launch
                if (isNewerVersion(info.tagName, BuildConfig.VERSION_NAME)) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        showUpdateDialog(info, BuildConfig.VERSION_NAME)
                    }
                }
            }
        }
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

    private fun requestRecordAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_RECORD_AUDIO
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_RECORD_AUDIO -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                val action = pendingMicAction
                pendingMicAction = null
                if (!granted) {
                    binding.status.text = "Microphone permission is required for calls"
                    return
                }
                when (action) {
                    PendingMicAction.OUTGOING_CALL -> call()
                    PendingMicAction.ACCEPT_INCOMING -> acceptPendingIncoming()
                    null -> {}
                }
            }
            REQ_POST_NOTIFICATIONS -> {
                // Result is silently OK — we just continue running. If denied,
                // notification-based features (incoming-call alerts, message badges)
                // are degraded but the app still works.
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    Log.i(TAG, "POST_NOTIFICATIONS denied — notifications disabled")
                }
            }
        }
    }

    private fun initAudio() {
        // Only cache the AudioManager reference — do NOT change mode or speakerphone here.
        // Audio mode is set in configureAudioForCall() when a call actually begins,
        // and restored in restoreAudioAfterCall() when it ends.
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
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

    private fun stunIceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer()
    )

    private fun meteredTurnServers(vararg urls: String): List<PeerConnection.IceServer> {
        if (BuildConfig.TURN_USERNAME.isBlank() || BuildConfig.TURN_PASSWORD.isBlank()) {
            return emptyList()
        }
        return urls.map { meteredTurnServer(it) }
    }

    private fun defaultIceServers(): List<PeerConnection.IceServer> =
        listOf(PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer()) +
            meteredTurnServers(
                "turn:global.relay.metered.ca:80",
                "turn:global.relay.metered.ca:80?transport=tcp",
                "turn:global.relay.metered.ca:443",
                "turns:global.relay.metered.ca:443?transport=tcp"
            ) +
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer()
            )

    private fun photoIceServers(): List<PeerConnection.IceServer> =
        listOf(PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer()) +
            meteredTurnServers(
                "turn:global.relay.metered.ca:80",
                "turn:global.relay.metered.ca:443"
            ) +
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())

    private fun initPeerConnection(createLocalChannels: Boolean) {
        val firestore = db
        val rtcConfig = PeerConnection.RTCConfiguration(defaultIceServers()).apply {
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 4
        }

        val pc = peerFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
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
        }) ?: run {
            Log.e(TAG, "initPeerConnection: createPeerConnection returned null")
            runOnUiThread { Toast.makeText(this, "WebRTC init failed", Toast.LENGTH_SHORT).show() }
            return
        }
        peerConnection = pc

        if (currentVoiceMode.webRtcAudio) {
            attachLocalWebRtcAudio()
        }

        if (createLocalChannels) {
            if (!currentVoiceMode.webRtcAudio) {
                val voiceInit = DataChannel.Init().apply {
                    ordered = true
                    maxRetransmits = 2
                }
                val vc = peerConnection.createDataChannel("voice", voiceInit)
                if (vc == null) {
                    Log.e(TAG, "createDataChannel(voice) returned null — aborting setup")
                    runOnUiThread { Toast.makeText(this, "Voice channel init failed", Toast.LENGTH_SHORT).show() }
                    return
                }
                voiceChannel = vc
                setupVoiceReceiver(vc)
            }

            val messageInit = DataChannel.Init().apply {
                ordered = true
            }
            val mc = peerConnection.createDataChannel("messages", messageInit)
            if (mc == null) {
                Log.e(TAG, "createDataChannel(messages) returned null — aborting setup")
                runOnUiThread { Toast.makeText(this, "Message channel init failed", Toast.LENGTH_SHORT).show() }
                return
            }
            messageChannel = mc
            setupMessageReceiver(mc)
        }
    }

    private fun meteredTurnServer(url: String): PeerConnection.IceServer =
        PeerConnection.IceServer.builder(url)
            .setUsername(BuildConfig.TURN_USERNAME)
            .setPassword(BuildConfig.TURN_PASSWORD)
            .createIceServer()

    private fun createPhotoPeerConnection(
        label: String,
        observer: PeerConnection.Observer
    ): PeerConnection? {
        val primaryConfig = PeerConnection.RTCConfiguration(photoIceServers()).apply {
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val primary = runCatching {
            peerFactory.createPeerConnection(primaryConfig, observer)
        }.getOrElse { e ->
            Log.w(TAG, "$label: primary photo PeerConnection threw: ${e.message}")
            null
        }
        if (primary != null) return primary

        Log.w(TAG, "$label: primary photo PeerConnection returned null, retrying with STUN-only config")
        val fallbackConfig = PeerConnection.RTCConfiguration(stunIceServers()).apply {
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return runCatching {
            peerFactory.createPeerConnection(fallbackConfig, observer)
        }.getOrElse { e ->
            Log.w(TAG, "$label: fallback photo PeerConnection threw: ${e.message}")
            null
        }
    }

    private fun showMyQrCode() {
        if (localId.isBlank()) return
        val bitmap = runCatching { generateQrBitmap(localId) }.getOrElse {
            Toast.makeText(this, "QR generation failed", Toast.LENGTH_SHORT).show()
            return
        }
        val dp = resources.displayMetrics.density
        val sizePx = (260 * dp).toInt()
        val iv = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val tv = android.widget.TextView(this).apply {
            text = localId
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(0xFF000000.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
            addView(iv, android.widget.LinearLayout.LayoutParams(sizePx, sizePx))
            addView(tv)
        }
        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun generateQrBitmap(content: String, sizePx: Int = 600): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun bindUI() {
        binding.btnBack.setOnClickListener { showContactList() }
        binding.btnBackFromAddContact.setOnClickListener { showContactList() }

        // Drawer
        binding.btnMenu.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.btnDrawerClose.setOnClickListener { binding.drawerLayout.closeDrawer(GravityCompat.START) }
        binding.btnCopyMyId.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            copyLocalId()
        }
        binding.btnDrawerShowQr.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showMyQrCode()
        }
        binding.btnDrawerScanQr.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            qrScanLauncher.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                }
            )
        }
        binding.btnNewGroup.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showNewGroupDialog()
        }
        // Live chat-list filter.
        binding.chatListSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                chatListQuery = s?.toString().orEmpty()
                renderContacts()
            }
        })
        // Chat title row:
        // - group: open group info / management.
        // - 1:1: toggle peer ID visibility (so user can tap the name to read
        //   the underlying localId for QR sharing / verification).
        binding.chatTitle.setOnClickListener {
            if (isGroup(remoteId)) {
                showGroupInfo(remoteId)
            } else if (remoteId.isNotBlank()) {
                togglePeerIdVisibility()
            }
        }

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
        binding.btnCancelReply.setOnClickListener { cancelReply() }
        binding.btnAttachPhoto.setOnClickListener {
            val targetId = selectedContactId()
            Log.d(TAG, "XLINK_PHOTO btnAttachPhoto tapped targetId='$targetId' localId='$localId' bound=$localIdBound")
            if (targetId.isBlank()) {
                Toast.makeText(this, "Open a chat first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            photoPickerLauncher.launch("image/*")
        }
        binding.btnBannerAddContact.setOnClickListener { showAddContactFromBannerDialog(remoteId) }
        binding.btnAddContact.setOnClickListener { saveCurrentContact(openAfterSave = true) }
        binding.btnExportBackup.setOnClickListener { exportBackupWithPhotoKey() }
        binding.btnImportBackup.setOnClickListener {
            restoreFileLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }

        // Search in chat
        binding.btnSearch.setOnClickListener {
            if (binding.chatSearchBar.visibility == View.VISIBLE) {
                binding.chatSearchBar.visibility = View.GONE
                chatSearchQuery = ""
                binding.chatSearchInput.text?.clear()
                refreshChatDisplay(remoteId)
            } else {
                binding.chatSearchBar.visibility = View.VISIBLE
                binding.chatSearchInput.requestFocus()
            }
        }
        binding.btnClearSearch.setOnClickListener {
            binding.chatSearchInput.text?.clear()
            chatSearchQuery = ""
            refreshChatDisplay(remoteId)
        }
        binding.chatSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                chatSearchQuery = s?.toString() ?: ""
                refreshChatDisplay(remoteId)
            }
        })

        // App lock button
        binding.btnAppLock.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showAppLockSettings()
        }
        updateAppLockButton()

        // Lock screen
        binding.btnUnlock.setOnClickListener {
            val pin = binding.pinInput.text.toString()
            attemptPinUnlock(pin)
        }
        binding.btnUseBiometric.setOnClickListener {
            showBiometricPrompt()
        }
        binding.btnCheckUpdates.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            checkForUpdates(fromUser = true)
        }
        binding.btnCheckBeta.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            checkForBeta()
        }
        binding.btnShareBetaLog.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            shareBetaLog()
        }
        binding.btnLogout.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            AlertDialog.Builder(this)
                .setTitle("Log out?")
                .setMessage("All local data (chats, contacts, account key) will be erased from this device. Export a backup first if you want to restore later.")
                .setPositiveButton("Log out") { _, _ -> doLogout() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        renderContacts()
        showContactList()
        showCallControls(false)
        updateActiveCallButtons()
        updateModeStatus()
        updateFirebaseControls()
    }

    /** Show/hide the inline "+ Add" pill in the chat top bar for unsaved contacts. */
    private fun updateAddContactBanner(id: String) {
        // Show only when the user has NOT explicitly added this contact.
        // rememberContact() adds IDs to savedContactIds without setting contactNameKey —
        // we distinguish by checking whether contactNameKey was explicitly written.
        val explicitlySaved = prefs.contains(contactNameKey(id))
        binding.btnBannerAddContact.visibility = if (explicitlySaved) View.GONE else View.VISIBLE
    }

    private fun showAddContactFromBannerDialog(id: String) {
        if (id.isBlank()) return
        val input = EditText(this).apply {
            hint = "Name (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(
                (24 * resources.displayMetrics.density).toInt(), 0,
                (24 * resources.displayMetrics.density).toInt(), 0
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Add contact")
            .setMessage("ID: $id")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                prefs.edit()
                    .putString(contactNameKey(id), name)
                    .putStringSet(KEY_CONTACT_IDS, savedContactIds() + id)
                    .apply()
                if (name.isNotBlank()) binding.chatTitle.text = name
                updateAddContactBanner(id)
                renderContacts()
                Toast.makeText(this, "Contact saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyLocalId() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Your ID", localId))
        binding.status.text = "ID copied"
    }

    /**
     * NEW USER FLOW: photo picked from device, password typed.
     * Both required. Cached in memory until [btnLoginWithPassword] is tapped.
     * For an existing v1 user, [chosenPhotoBytes] stays null because we already
     * have v1 secret in prefs — migration only needs the password.
     */
    @Volatile private var chosenPhotoBytes: ByteArray? = null
    /** True iff prefs hold v1 secret but no v2 — user must migrate by adding a password. */
    private var migratingV1: Boolean = false

    private fun authenticateWithPhoto(uri: Uri) {
        binding.btnChooseAuthPhoto.isEnabled = false
        binding.photoAuthStatus.text = "Reading photo"
        ioScope.launch {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("empty photo")
            }.getOrElse { _ ->
                withContext(Dispatchers.Main) {
                    binding.photoAuthStatus.text = "Photo read failed"
                    binding.btnChooseAuthPhoto.isEnabled = true
                }
                return@launch
            }
            chosenPhotoBytes = bytes
            withContext(Dispatchers.Main) {
                binding.photoAuthStatus.text = "Now enter your password"
                binding.authPhotoChosen.text = "✓ Photo loaded (${bytes.size / 1024} KB)"
                binding.authPhotoChosen.visibility = View.VISIBLE
                binding.btnChooseAuthPhoto.isEnabled = true
                updateLoginButtonEnabled()
            }
        }
    }

    private fun updateLoginButtonEnabled() {
        val passwordOk = binding.authPasswordInput.text?.toString()?.isNotEmpty() == true
        val photoOk = chosenPhotoBytes != null || migratingV1
        binding.btnLoginWithPassword.isEnabled = passwordOk && photoOk
    }

    /**
     * Final step after photo + password are both provided. Derives v2 master
     * via PBKDF2 (password as key, photo bytes as salt) — never sends the
     * password or photo to the server. Saves v2 secret to prefs and continues.
     */
    private fun loginWithPhotoAndPassword() {
        val password = binding.authPasswordInput.text?.toString().orEmpty()
        if (password.isEmpty()) return
        binding.btnLoginWithPassword.isEnabled = false
        binding.photoAuthStatus.text = "Deriving keys (this takes a moment)..."

        ioScope.launch {
            val (localIdNew, cryptoSecret) = if (migratingV1) {
                // Migration: salt = existing v1 secret. localId stays unchanged.
                val v1SecretB64 = prefs.getString(KEY_PHOTO_ACCOUNT_SECRET, null)
                    ?: return@launch failLogin("Missing legacy secret")
                val v1Secret = b64decode(v1SecretB64)
                val master = pbkdf2(password.toByteArray(Charsets.UTF_8), v1Secret, V2_PBKDF2_ITERS, 32)
                val crypto = sha256("crypto-v2:".toByteArray(Charsets.UTF_8) + master)
                // Keep existing localId; only crypto material changes.
                val existingId = prefs.getString(KEY_LOCAL_ID, null) ?: return@launch failLogin("Missing local id")
                existingId to crypto
            } else {
                val photoBytes = chosenPhotoBytes ?: return@launch failLogin("Pick a photo first")
                val master = pbkdf2(password.toByteArray(Charsets.UTF_8), photoBytes, V2_PBKDF2_ITERS, 32)
                val localBytes = sha256("localid-v2:".toByteArray(Charsets.UTF_8) + master)
                val id = localBytes.take(4).joinToString("") { "%02X".format(it) }
                val crypto = sha256("crypto-v2:".toByteArray(Charsets.UTF_8) + master)
                id to crypto
            }
            // Save v2 secret. Keep v1 secret if it existed (for decrypting legacy
            // cloud messages encrypted with the v1 key while migration in flight).
            prefs.edit()
                .putString(KEY_LOCAL_ID, localIdNew)
                .putString(KEY_V2_CRYPTO_SECRET, b64(cryptoSecret))
                .putInt(KEY_AUTH_VERSION, 2)
                .apply()
            // Wipe in-memory password and photo bytes ASAP.
            chosenPhotoBytes = null
            withContext(Dispatchers.Main) {
                binding.authPasswordInput.text?.clear()
                continueWithLocalIdentity(localIdNew)
            }
        }
    }

    private suspend fun failLogin(msg: String) {
        withContext(Dispatchers.Main) {
            binding.photoAuthStatus.text = msg
            binding.btnLoginWithPassword.isEnabled = true
        }
    }

    /** PBKDF2-HMAC-SHA256. password = key material, salt = personalization. */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iters: Int, length: Int): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(
            password.map { it.toInt().toChar() }.toCharArray(),
            salt, iters, length * 8
        )
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun savePhotoAccount(id: String, secret: ByteArray) {
        prefs.edit()
            .putString(KEY_LOCAL_ID, id)
            .putString(KEY_PHOTO_ACCOUNT_SECRET, b64(secret))
            .apply()
    }

    private fun photoAuthMaterial(uri: Uri): PhotoAuthMaterial {
        val photoBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("empty photo")
        val photoHash = sha256(photoBytes)
        val secret = sha256("$PHOTO_ACCOUNT_KEY_SALT:${hex(photoHash)}".toByteArray(Charsets.UTF_8))
        return PhotoAuthMaterial(secret)
    }

    /**
     * Visual fingerprint of a peer's public key. Renders SHA-256 of the X.509 DER
     * pubkey as 8 emoji from a fixed alphabet — users can compare them out-of-band
     * (call/photo) to detect MITM key substitution. Equivalent to Signal "safety
     * numbers" but compact enough to fit in a chat title row.
     *
     * Returns empty string if the pubkey hasn't been fetched yet for this contact.
     */
    /**
     * Fetches peer's pubkey (cached) and shows the fingerprint emoji row in the
     * chat header. Hidden until the lookup resolves.
     */
    /**
     * Reveal the peer's localId in the chat header for 5 seconds (then revert to
     * the fingerprint row), and copy it to the clipboard so the user can paste
     * it elsewhere. Triggered by tapping the contact name in a 1:1 chat.
     */
    private fun togglePeerIdVisibility() {
        val id = remoteId.takeIf { it.isNotBlank() } ?: return
        val previous = binding.chatPeerId.text?.toString().orEmpty()
        val previouslyVisible = binding.chatPeerId.visibility == View.VISIBLE
        binding.chatPeerId.text = id
        binding.chatPeerId.visibility = View.VISIBLE
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("contact id", id))
        }
        Toast.makeText(this, "ID скопирован", Toast.LENGTH_SHORT).show()
        binding.chatPeerId.postDelayed({
            // Don't stomp on a newer chat or a group switch.
            if (remoteId == id && !isGroup(id)) {
                binding.chatPeerId.text = previous
                binding.chatPeerId.visibility =
                    if (previouslyVisible && previous.isNotBlank()) View.VISIBLE else View.GONE
            }
        }, 5_000)
    }

    private fun loadPeerFingerprint(id: String) {
        binding.chatPeerId.visibility = View.GONE
        ioScope.launch {
            val pubkey = runCatching {
                db?.collection("users")?.document(id)?.get()?.await()
                    ?.getString("messagePublicKey")
            }.getOrNull()
            val print = pubkeyFingerprint(pubkey)
            withContext(Dispatchers.Main) {
                if (remoteId == id && print.isNotEmpty()) {
                    binding.chatPeerId.text = print
                    binding.chatPeerId.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun pubkeyFingerprint(pubkeyB64: String?): String {
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

    /** 8-char uppercase hex ID, derived deterministically from photo secret. */
    private fun deriveLocalId(photoSecret: ByteArray): String =
        sha256("xlink-id-v1:".toByteArray(Charsets.UTF_8) + photoSecret)
            .take(4)
            .joinToString("") { "%02X".format(it) }

    /**
     * Derives an EC P-256 keypair deterministically from photo secret.
     * Same photo → same keypair, on any device, with no server involvement.
     * 64 bytes of deterministic material cover any provider's internal retry loop.
     */
    private fun deriveEcKeyPair(photoSecret: ByteArray): KeyPair {
        val seed = sha256("xlink-ec-key-v1:".toByteArray(Charsets.UTF_8) + photoSecret)
        val material = seed + sha256(seed) // 64 bytes
        var pos = 0
        val det = object : SecureRandom() {
            override fun nextBytes(out: ByteArray) {
                for (i in out.indices) { out[i] = material.getOrElse(pos++) { 0 } }
            }
            override fun generateSeed(n: Int): ByteArray = ByteArray(n)
        }
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), det)
        return kpg.generateKeyPair()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(it) }


    private fun publishPublicMessageKey() {
        val firestore = db ?: return
        val kp = myEcKeyPair ?: return
        val publicKeyB64 = b64(kp.public.encoded)
        val userData = mutableMapOf<String, Any>(
            "id" to localId,
            "messagePublicKey" to publicKeyB64,
            "keyAlgorithm" to EC_KEY_ALGORITHM,
            "updatedAt" to System.currentTimeMillis()
        )
        prefs.getString(KEY_FCM_TOKEN, null)?.takeIf { it.isNotBlank() }?.let {
            userData["fcmToken"] = it
            userData["fcmUpdatedAt"] = System.currentTimeMillis()
        }
        firestore.collection("users").document(localId).set(userData, SetOptions.merge())
            .addOnFailureListener { error ->
                updateDebugStatus("key publish failed: ${error.message}")
            }
    }


    private fun syncFcmRegistrationToken() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
                db?.collection("users")?.document(localId)?.set(
                    mapOf(
                        "id" to localId,
                        "fcmToken" to token,
                        "fcmUpdatedAt" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                )?.addOnFailureListener { error ->
                    updateDebugStatus("fcm token save failed: ${error.message}")
                }
            }
            .addOnFailureListener { error ->
                updateDebugStatus("fcm token failed: ${error.message}")
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

    private fun shouldAutoAcceptPhotoFrom(senderId: String): Boolean =
        prefs.contains(contactNameKey(senderId)) ||
            prefs.contains(chatLogKey(senderId)) ||
            remoteId == senderId

    /**
     * Timestamp of the most recent message in the chat log for [id], or 0
     * if the chat is empty / has no entries with timestamps. Used to sort
     * the contacts list so the latest-activity chat appears on top.
     */
    private fun lastChatActivity(id: String): Long {
        val lines = splitLogLines(messageLogFor(id).toString())
        if (lines.isEmpty()) return 0L
        return lineTimestamp(lines.last())
    }

    private fun messageLogFor(id: String): StringBuilder =
        messageLogs.getOrPut(id) {
            val raw = prefs.getString(chatLogKey(id), "").orEmpty()
            // Two-stage migration:
            //   1. trim leading whitespace from each entry (Codex v1.13.x
            //      corruption — caused outgoing msgs to render as incoming);
            //   2. drop entries whose displayable text is blank.
            val cleaned = stripBlankEntries(trimEntryWhitespace(raw))
            if (cleaned != raw) {
                prefs.edit().putString(chatLogKey(id), cleaned).apply()
                val readKey = "$KEY_CHAT_READ_PREFIX$id"
                val storedCount = prefs.getInt(readKey, 0)
                val newSize = splitLogLines(cleaned).size
                if (storedCount > newSize) {
                    prefs.edit().putInt(readKey, newSize).apply()
                }
            }
            StringBuilder(cleaned)
        }

    /**
     * Rewrites a raw log trimming leading whitespace from each entry.
     * Preserves the structural `\t{ts}\n` boundary that splitLogLines uses.
     * Fixes the Codex-build corruption where entries were stored as
     * "    Me|{id}: text\t{ts}\n" — the four leading spaces broke
     * parseLineAuthorText so all such entries rendered as incoming.
     */
    private fun trimEntryWhitespace(log: String): String {
        if (log.isEmpty()) return log
        val sb = StringBuilder(log.length)
        val re = Regex("""\t\d{10,}\n""")
        var pos = 0
        for (m in re.findAll(log)) {
            val end = m.range.last + 1
            val entry = log.substring(pos, end).trimEnd('\n')
            // Trim only leading whitespace (spaces, tabs other than the
            // boundary one before timestamp, and any zero-width junk).
            val trimmed = entry.trimStart()
            if (trimmed.isNotEmpty()) {
                sb.append(trimmed).append('\n')
            }
            pos = end
        }
        if (pos < log.length) {
            log.substring(pos).split('\n').forEach { line ->
                val trimmed = line.trimStart()
                if (trimmed.isNotEmpty()) sb.append(trimmed).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * Rewrites a raw log dropping entries whose displayable text is blank.
     * Preserves the structural `\t{ts}\n` boundary that splitLogLines relies on.
     */
    private fun stripBlankEntries(log: String): String {
        if (log.isEmpty()) return log
        val sb = StringBuilder(log.length)
        val re = Regex("""\t\d{10,}\n""")
        var pos = 0
        for (m in re.findAll(log)) {
            val end = m.range.last + 1
            // entry = "Me|abc: text\t1733262000000" (boundary tab+ts kept, trailing \n trimmed)
            val entry = log.substring(pos, end).trimEnd('\n')
            if (entry.isNotEmpty() && !isBlankEntry(entry)) {
                sb.append(entry).append('\n')
            }
            pos = end
        }
        // Legacy tail (no timestamp boundary) — keep non-blank lines as-is
        if (pos < log.length) {
            log.substring(pos).split('\n').forEach { line ->
                if (line.isNotEmpty() && !isBlankEntry(line)) {
                    sb.append(line).append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun newCallId(sessionId: String): String =
        "call_$sessionId"

    private fun savedContactIds(): Set<String> =
        prefs.getStringSet(KEY_CONTACT_IDS, emptySet()).orEmpty()

    /** Count contacts with unread messages and update the badge next to "Chats". */
    private fun updateUnreadBadge() {
        if (!::binding.isInitialized) return
        val count = savedContactIds().count { id ->
            val lines = splitLogLines(messageLogFor(id).toString()).size
            val read  = prefs.getInt("$KEY_CHAT_READ_PREFIX$id", 0)
            lines > read
        }
        if (count > 0) {
            binding.tvUnreadCount.text = count.toString()
            binding.tvUnreadCount.visibility = View.VISIBLE
        } else {
            binding.tvUnreadCount.visibility = View.GONE
        }
    }

    /**
     * Card displayed above the chat-list filter results when the query looks
     * like a contact ID we don't have yet. Tap → open the inline add-contact
     * banner dialog so the user can give the new contact a local name.
     */
    private fun addAddNewContactRow(id: String) {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14 * dp
                setColor(0xFF1A5276.toInt())
            }
            val padH = (16 * dp).toInt()
            val padV = (14 * dp).toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
            addView(TextView(this@MainActivity).apply {
                text = "+ Add new contact"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 14f
            })
            addView(TextView(this@MainActivity).apply {
                text = id
                setTextColor(0xFFAACCEE.toInt())
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
            setOnClickListener { showAddContactFromBannerDialog(id) }
        }
        binding.contactsList.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * dp).toInt() })
    }

    private fun renderContacts() {
        updateUnreadBadge()
        binding.contactsList.removeAllViews()
        // Merge contacts + groups into one list, sort by last activity.
        val query = chatListQuery.trim()
        val queryLower = query.lowercase(Locale.US)
        val all = (savedContactIds() + savedGroupIds())
            .sortedWith(
                compareByDescending<String> { lastChatActivity(it) }
                    .thenBy { (if (isGroup(it)) groupName(it) else contactName(it)).lowercase(Locale.US) }
                    .thenBy { it }
            )
        // Filter by query: matches name, raw id, or last-message preview.
        val contacts = if (query.isEmpty()) all else all.filter { id ->
            val name = (if (isGroup(id)) groupName(id) else contactName(id)).lowercase(Locale.US)
            name.contains(queryLower) ||
                id.lowercase(Locale.US).contains(queryLower)
        }
        // CTA row: query looks like an ID we don't have yet → offer to add it.
        if (query.isNotEmpty() && Regex("^[A-Z0-9]{4,32}$").matches(query.uppercase(Locale.US))) {
            val normalised = query.uppercase(Locale.US)
            if (normalised !in savedContactIds() && normalised != localId) {
                addAddNewContactRow(normalised)
            }
        }
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

        val dp = resources.displayMetrics.density
        contacts.forEach { id ->
            val hasUnread = run {
                val lines = splitLogLines(messageLogFor(id).toString()).size
                lines > prefs.getInt("$KEY_CHAT_READ_PREFIX$id", 0)
            }

            // Last message preview (first 60 chars of last line, stripped of author prefix).
            // Also expose the status of the last message IF it's outgoing — so the
            // contacts list shows ✓ / ✓✓ / ✓✓ (blue) without opening the chat.
            val lastLine: String? = run {
                val lines = splitLogLines(messageLogFor(id).toString())
                if (lines.isEmpty()) null else lines.last()
            }
            val lastMsg = if (lastLine == null) "" else {
                val (_, body) = parseLineAuthorText(lastLine)
                when {
                    body.startsWith("[PHOTO:") && body.endsWith("]") -> "📷 Photo"
                    body.startsWith("[PHOTO_PENDING:") && body.endsWith("]") -> "⏳ Sending photo"
                    body.startsWith("[PHOTO_FAILED:") && body.endsWith("]") -> "⚠️ Photo not delivered"
                    else -> body.take(60)
                }
            }
            val lastOutgoingStatus: MsgStatus? = lastLine?.let { line ->
                val (author, _) = parseLineAuthorText(line)
                if (author != "Me") return@let null
                val msgId = lineMsgId(line) ?: return@let MsgStatus.SENT
                getMessageStatus(msgId) ?: MsgStatus.SENT
            }

            // Name row: name + blue dot. Prefix group cards with 👥.
            val displayName = if (isGroup(id)) "👥 ${groupName(id)}" else contactName(id)
            val nameView = android.widget.TextView(this).apply {
                text = displayName
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val dotView = android.view.View(this).apply {
                val size = (9 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    marginStart = (8 * dp).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFF2196F3.toInt())
                }
                visibility = if (hasUnread) View.VISIBLE else View.GONE
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(nameView)
                addView(dotView)
            }

            // Preview row: optional ✓/✓✓ status indicator (if last msg is outgoing)
            // + preview text. Hidden entirely if there's no last message.
            val previewRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * dp).toInt() }
                visibility = if (lastMsg.isNotEmpty()) View.VISIBLE else View.GONE

                if (lastOutgoingStatus != null) {
                    val gray = 0xFF888888.toInt()
                    val blue = 0xFF4FC3F7.toInt()
                    val (mark, color) = when (lastOutgoingStatus) {
                        MsgStatus.SENT      -> "✓"  to gray
                        MsgStatus.DELIVERED -> "✓✓" to gray
                        MsgStatus.READ      -> "✓✓" to blue
                    }
                    addView(android.widget.TextView(this@MainActivity).apply {
                        text = mark
                        textSize = 12f
                        setTextColor(color)
                        includeFontPadding = false
                        letterSpacing = -0.18f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { rightMargin = (6 * dp).toInt() }
                    })
                }
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = lastMsg
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                    textSize = 13f
                    setSingleLine(true)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                })
            }

            // Card
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 14 * dp
                    setColor(0xFF1E1E1E.toInt())
                }
                val padH = (16 * dp).toInt()
                val padV = (14 * dp).toInt()
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                foreground = ContextCompat.getDrawable(
                    this@MainActivity,
                    android.R.attr.selectableItemBackground.let { attr ->
                        val ta = obtainStyledAttributes(intArrayOf(attr))
                        val res = ta.getResourceId(0, 0)
                        ta.recycle()
                        res
                    }
                )
                addView(topRow)
                addView(previewRow)
                setOnClickListener { openChat(id) }
                setOnLongClickListener { showContactContextMenu(id); true }
            }

            binding.contactsList.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() })
        }
    }

    private fun showContactList() {
        binding.contactListScreen.visibility = View.VISIBLE
        binding.addContactScreen.visibility = View.GONE
        binding.chatScreen.visibility = View.GONE
        binding.incomingCallScreen.visibility = View.GONE
        binding.outgoingCallScreen.visibility = View.GONE
        binding.activeCallScreen.visibility = View.GONE
        showCallControls(false)
        renderContacts()   // also calls updateUnreadBadge()
    }

    private fun showAddContactScreen() {
        binding.contactListScreen.visibility = View.GONE
        binding.addContactScreen.visibility = View.VISIBLE
        binding.chatScreen.visibility = View.GONE
        binding.incomingCallScreen.visibility = View.GONE
        binding.outgoingCallScreen.visibility = View.GONE
        binding.activeCallScreen.visibility = View.GONE
        showCallControls(false)
        binding.addContactIdInput.requestFocus()
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
        if (isGroup(id)) {
            val members = groupMembers(id)
            binding.chatTitle.text = "👥 ${groupName(id)}"
            binding.chatPeerId.text = "${members.size} members"
            binding.chatPeerId.visibility = View.VISIBLE
            // Hide add-contact pill + call button for groups (no 1:1 call).
            binding.btnBannerAddContact.visibility = View.GONE
            binding.btnCall.visibility = View.GONE
        } else {
            binding.chatTitle.text = contactName(id)
            binding.btnCall.visibility = View.VISIBLE
            updateAddContactBanner(id)
            loadPeerFingerprint(id)
        }
        cancelReply()
        // Reset search when switching chats
        chatSearchQuery = ""
        binding.chatSearchInput.text?.clear()
        binding.chatSearchBar.visibility = View.GONE
        // Dismiss any notification for this contact immediately
        notificationManager().cancel((NOTIFICATION_MESSAGE_ID_BASE + id.hashCode()).absoluteValue)

        val allText = messageLogFor(id).toString()
        val lines = splitLogLines(allText)
        val savedCount = prefs.getInt("$KEY_CHAT_READ_PREFIX$id", 0)
        val newCount = (lines.size - savedCount).coerceAtLeast(0)

        // Mark all current messages as read
        prefs.edit().putInt("$KEY_CHAT_READ_PREFIX$id", lines.size).apply()

        val dividerAt = if (newCount > 0 && savedCount > 0) savedCount else -1
        chatDividerLineIndex = dividerAt
        renderChatMessages(id, lines, dividerAt)
        if (dividerAt >= 0) {
            binding.messagesScroll.post {
                chatDividerView?.let { dv ->
                    binding.messagesScroll.smoothScrollTo(0, dv.top)
                } ?: binding.messagesScroll.fullScroll(android.view.View.FOCUS_DOWN)
            }
        } else {
            binding.messagesScroll.post { binding.messagesScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }

        // Send READ receipts for DataChannel messages received before chat was opened
        incomingMsgIds[id]?.toList()?.forEach { msgId -> sendRead(msgId) }
        incomingMsgIds.remove(id)
        // Send READ receipts for cloud (Firestore) messages
        sendCloudReadReceipts(id)

        binding.contactListScreen.visibility = View.GONE
        binding.addContactScreen.visibility = View.GONE
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
        binding.addContactScreen.visibility = View.GONE
        binding.chatScreen.visibility = View.GONE
        binding.incomingCallScreen.visibility = View.GONE
        binding.outgoingCallScreen.visibility = View.GONE
        binding.activeCallScreen.visibility = View.VISIBLE
        binding.activeCallName.text = contactName(remoteId)
        binding.activeCallStatus.text = if (recording) "Connected" else "Connecting"
        if (callStartedAtMs == 0L) binding.callTimer.text = "00:00"
        updateActiveCallButtons()
        // Foreground service keeps the call alive when app is backgrounded / screen locked.
        // Android 14: starting a type=microphone FGS without RECORD_AUDIO permission
        // throws SecurityException. Skip if we don't hold it.
        if (hasRecordAudioPermission()) {
            CallForegroundService.start(this, contactName(remoteId))
        } else {
            Log.w(TAG, "Skipping CallForegroundService.start — RECORD_AUDIO not granted")
        }
    }

    private fun showOutgoingCallScreen(secondsLeft: Int = CALL_SETUP_TIMEOUT_SECONDS) {
        if (remoteId.isBlank()) return
        binding.contactListScreen.visibility = View.GONE
        binding.addContactScreen.visibility = View.GONE
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
        // Clean up dead v3 channels (cached broken sound URIs after the raw
        // resource rename incomming_* → incoming_* in v1.14.18).
        runCatching {
            notificationManager().deleteNotificationChannel(LEGACY_CALLS_CHANNEL_ID)
            notificationManager().deleteNotificationChannel(LEGACY_MESSAGES_CHANNEL_ID)
        }
        val callSound = notificationSound(R.raw.incoming_call)
        val messageSound = notificationSound(R.raw.incoming_message)
        val callAudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val messageAudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val callsChannel = NotificationChannel(
            NOTIFICATION_CALLS_CHANNEL_ID,
            "Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming call alerts"
            enableVibration(true)
            setSound(callSound, callAudioAttributes)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val messagesChannel = NotificationChannel(
            NOTIFICATION_MESSAGES_CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming message alerts"
            enableVibration(true)
            setSound(messageSound, messageAudioAttributes)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        notificationManager().createNotificationChannels(listOf(callsChannel, messagesChannel))
    }

    private fun notificationSound(resId: Int): Uri =
        Uri.parse("android.resource://$packageName/$resId")

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
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CALLS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Incoming call")
            .setContentText(callerName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setSound(notificationSound(R.raw.incoming_call))
            .setVibrate(longArrayOf(0L, 250L, 150L, 250L))
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setNumber(1)
            .setContentIntent(intent)
            .setFullScreenIntent(intent, true)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()
        notificationManager().notify(NOTIFICATION_CALL_ID, notification)
    }

    private fun notifyIncomingMessage(senderId: String, text: String) {
        if (appInForeground || !hasNotificationPermission()) return
        // Single source of truth: SharedPreferences. The in-memory mirror
        // diverged from the FCM-service-incremented value, causing wrong
        // badge numbers on notifications.
        val count = prefs.getInt(KEY_UNREAD_NOTIFICATION_COUNT, 0) + 1
        prefs.edit().putInt(KEY_UNREAD_NOTIFICATION_COUNT, count).apply()
        unreadNotificationCount = count
        val senderName = contactName(senderId)
        val notification = NotificationCompat.Builder(this, NOTIFICATION_MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(senderName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setSound(notificationSound(R.raw.incoming_message))
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setNumber(count)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        notificationManager().notify((NOTIFICATION_MESSAGE_ID_BASE + senderId.hashCode()).absoluteValue, notification)
    }

    private fun clearNotificationBadges() {
        unreadNotificationCount = 0
        prefs.edit().putInt(KEY_UNREAD_NOTIFICATION_COUNT, 0).apply()
        cancelIncomingCallNotification()
        notificationManager().cancelAll()
    }

    private fun cancelIncomingCallNotification() {
        notificationManager().cancel(NOTIFICATION_CALL_ID)
        stopIncomingRing()
    }

    private fun startIncomingRing() {
        // Already ringing — don't double-start (parallel listener fires).
        if (incomingRingPlayer != null) return
        runCatching {
            val uri = Uri.parse("android.resource://$packageName/${R.raw.incoming_call}")
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@MainActivity, uri)
                isLooping = true
                prepare()
                start()
            }
            incomingRingPlayer = player
        }.onFailure { Log.w("XLINK_RING", "ring start failed: ${it.message}") }

        // Vibrate in parallel — respects user's vibrate-on-ring preference because
        // we use USAGE_NOTIFICATION_RINGTONE; if ringer is silent, OS skips audio
        // but vibration still alerts.
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (vib.hasVibrator()) {
                val pattern = longArrayOf(0L, 600L, 400L, 600L, 400L, 600L, 1000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(pattern, 0)
                }
                incomingVibrator = vib
            }
        }.onFailure { Log.w("XLINK_RING", "vibrate failed: ${it.message}") }
    }

    private fun stopIncomingRing() {
        incomingRingPlayer?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        incomingRingPlayer = null
        incomingVibrator?.let { runCatching { it.cancel() } }
        incomingVibrator = null
    }

    private fun updateFirebaseControls() {
        val firebaseReady = db != null
        binding.btnCall.isEnabled = firebaseReady
        binding.btnAccept.isEnabled = firebaseReady
        if (!firebaseReady) {
            binding.status.text = "Missing Firebase config"
        }
    }

    private fun updateModeSelectionFromRemote(mode: VoiceMode) {
        binding.status.text = "Incoming mode: ${mode.label}"
    }

    private fun updateModeStatus() {
        updateDebugStatus("mode=${currentVoiceMode.label}")
    }

    private fun call() = ioScope.launch {
        if (!hasRecordAudioPermission()) {
            pendingMicAction = PendingMicAction.OUTGOING_CALL
            runOnUiThread {
                binding.status.text = "Allow microphone to start a call"
                requestRecordAudioPermission()
            }
            return@launch
        }
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
        val sessionId = "$localId-${System.currentTimeMillis()}"
        val callId = newCallId(sessionId)
        withContext(Dispatchers.Main) { resetPeerConnection(createLocalChannels = true) }
        currentCallId = callId
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
        if (!hasRecordAudioPermission()) {
            pendingMicAction = PendingMicAction.ACCEPT_INCOMING
            runOnUiThread {
                binding.status.text = "Allow microphone to answer the call"
                requestRecordAudioPermission()
            }
            return@launch
        }
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
                        val callerId = pendingIncomingCall?.callerId
                        pendingIncomingCall = null
                        runOnUiThread {
                            cancelIncomingCallNotification()
                            if (!callerId.isNullOrBlank()) remoteId = callerId
                            binding.addContactScreen.visibility = View.GONE
                            binding.incomingCallScreen.visibility = View.GONE
                            binding.outgoingCallScreen.visibility = View.GONE
                            binding.activeCallScreen.visibility = View.GONE
                            returnToPostCallScreen()
                            binding.status.text = "Incoming call ended"
                            if (binding.chatScreen.visibility == View.VISIBLE) {
                                binding.chatStatus.text = "Incoming call ended"
                            }
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
        remoteId = incoming.callerId
        val callerName = contactName(incoming.callerId)
        binding.incomingCallerName.text = callerName
        binding.incomingCallerId.text = incoming.callerId
        binding.contactListScreen.visibility = View.GONE
        binding.addContactScreen.visibility = View.GONE
        binding.chatScreen.visibility = View.GONE
        binding.activeCallScreen.visibility = View.GONE
        binding.outgoingCallScreen.visibility = View.GONE
        binding.incomingCallScreen.visibility = View.VISIBLE
        binding.status.text = "Incoming call from $callerName"
        binding.chatStatus.text = "Incoming call from $callerName"
        notifyIncomingCall(incoming)
        // notifyIncomingCall early-returns when appInForeground, so the channel
        // sound never plays. Drive our own ringtone+vibrate while the incoming
        // screen is visible. stopIncomingRing() runs via cancelIncomingCallNotification.
        startIncomingRing()
    }

    private fun acceptPendingIncoming() {
        if (!hasRecordAudioPermission()) {
            pendingMicAction = PendingMicAction.ACCEPT_INCOMING
            binding.status.text = "Allow microphone to answer the call"
            requestRecordAudioPermission()
            return
        }
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
        cancelIncomingCallNotification()
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
                                        "state" to "answered",
                                        "answeredAt" to System.currentTimeMillis()
                                    )
                                ).addOnFailureListener { error ->
                                    updateDebugStatus("answer write failed: ${error.message}")
                                }
                                startCallSetupTimeout(incoming.callId, incoming.sessionId)
                                updateDebugStatus("accepted ${contactName(incoming.callerId)}")
                            }

                            override fun onSetFailure(error: String?) {
                                updateDebugStatus("answer set failed: $error")
                            }
                        }, desc)
                    }

                    override fun onCreateFailure(error: String?) {
                        updateDebugStatus("create answer failed: $error")
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
            cancelIncomingCallNotification()
            returnToPostCallScreen()
            return
        }
        cancelIncomingCallNotification()
        pendingIncomingCall = null
        dismissedIncomingSessions += incoming.sessionId
        db?.collection("calls")?.document(incoming.callId)?.update(
            mapOf(
                "state" to "declined",
                "declinedBy" to localId,
                "declinedSessionId" to incoming.sessionId
            )
        )
        remoteId = incoming.callerId
        finishCallAndReturn("Declined ${contactName(incoming.callerId)}")
    }

    private fun rememberContact(id: String) {
        if (savedContactIds().contains(id)) return
        prefs.edit()
            .putStringSet(KEY_CONTACT_IDS, savedContactIds() + id)
            .apply()
        renderContacts()
        // Don't touch the banner here — updateAddContactBanner checks for a name, not just ID membership
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
                    val declinedSessionId = snap.getString("declinedSessionId")
                    if (declinedSessionId != null && declinedSessionId != currentSessionId) return@addSnapshotListener
                    runOnUiThread { finishCallAndReturn("Declined by ${contactName(remoteId)}") }
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
                val state = snap?.getString("state") ?: return@addSnapshotListener
                if (state != "ended" && state != "declined") return@addSnapshotListener
                if (snap.getString("endedBy") == localId || snap.getString("declinedBy") == localId) return@addSnapshotListener
                val endedSessionId = snap.getString("endedSessionId")
                if (endedSessionId != null && sessionId != null && endedSessionId != sessionId) return@addSnapshotListener
                val declinedSessionId = snap.getString("declinedSessionId")
                if (declinedSessionId != null && sessionId != null && declinedSessionId != sessionId) return@addSnapshotListener
                runOnUiThread {
                    if (state == "declined") {
                        finishCallAndReturn("Call declined")
                    } else {
                        finishCallAndReturn("Call ended")
                    }
                }
            }
        addListener(listener)
    }

    private fun endRemoteCall() {
        finishCallAndReturn("Call ended")
    }

    private fun finishCallAndReturn(message: String) {
        cancelIncomingCallNotification()
        resetPeerConnection(createLocalChannels = false)
        binding.addContactScreen.visibility = View.GONE
        binding.incomingCallScreen.visibility = View.GONE
        binding.outgoingCallScreen.visibility = View.GONE
        binding.activeCallScreen.visibility = View.GONE
        returnToPostCallScreen()
        showCallControls(false)
        binding.status.text = message
        if (binding.chatScreen.visibility == View.VISIBLE) {
            binding.chatStatus.text = message
        }
        updateDebugStatus(message.lowercase(Locale.US))
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

        // Group fan-out: encrypt + write one /messages doc per member (excluding self).
        if (isGroup(targetId)) {
            sendGroupMessage(targetId, text)
            return
        }

        if (targetId == localId) {
            binding.status.text = "This is your own ID"
            return
        }

        val firestore = firestoreOrWarn() ?: return
        val id = nextMessageId()
        val replyTo = replyingToMsgId
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

                val message = mutableMapOf<String, Any>(
                    "from" to localId,
                    "to" to targetId,
                    "encryptedKey" to encrypted.encryptedKey,
                    "iv" to encrypted.iv,
                    "cipherText" to encrypted.cipherText,
                    "messageAlgorithm" to AES_MESSAGE_ALGORITHM,
                    "keyAlgorithm" to encrypted.keyAlgorithm,
                    "createdAt" to System.currentTimeMillis()
                )
                if (replyTo != null) message["replyTo"] = replyTo

                firestore.collection("messages").document(id).set(message)
                    .addOnSuccessListener {
                        markMessageSeen(id)
                        saveMessageStatus(id, MsgStatus.SENT)
                        binding.messageInput.text?.clear()
                        appendMessage(targetId, "Me", text, id, replyToMsgId = replyTo)
                        cancelReply()
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
            var tick = 0
            while (isActive) {
                delay(MESSAGE_POLL_MS)
                pollCloudMessages()
                pollReceipts()
                // Prune stale DELIVERED-only receipts once per hour (720 ticks @ 5s)
                if (++tick % 720 == 0) pruneOldReceipts()
            }
        }
    }

    private fun pollCloudMessages() {
        val firestore = db ?: return
        firestore.collection("messages")
            .whereEqualTo("to", localId)
            .limit(50)
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
        val text = decryptCloudMessage(document)?.takeIf { it.isNotBlank() } ?: return

        val isNew = synchronized(receivedMessageIds) {
            receivedMessageIds.add(id)
        }
        if (!isNew) {
            deleteCloudMessage(document)
            return
        }

        markMessageSeen(id)

        // Write delivery receipt to Firestore so sender gets ✓✓
        db?.collection("receipts")?.document(id)?.set(
            mapOf("from" to localId, "to" to senderId,
                  "delivered" to true, "read" to false,
                  "createdAt" to System.currentTimeMillis())
        )?.addOnFailureListener { e ->
            Log.w(TAG, "delivery receipt write failed msgId=$id err=${e.message}")
        }

        // Optional group routing + reply linking.
        val groupId = document.getString("groupId")?.takeIf { it.isNotBlank() }
        val replyTo = document.getString("replyTo")?.takeIf { it.isNotBlank() }
        // Auto-discover groups: if the message refers to a group we don't know
        // yet, pull /groups/{rawId} from Firestore and add it locally so the
        // chat shows up in the list with the right name + member count.
        if (groupId != null && groupId !in savedGroupIds()) discoverGroup(groupId)
        // The chatId for the local log: group conversations use the groupId so
        // every member's view of the same group is keyed identically; 1:1
        // conversations key on the sender's localId as before. The original
        // msgId of the fan-out is "{baseMsgId}-{recipient}" so strip the
        // recipient suffix to get the canonical sender-issued id when in group
        // context — keeps reply lookups and dedup consistent across recipients.
        val canonicalMsgId = if (groupId != null) {
            id.removeSuffix("-$localId")
        } else id
        val chatId = groupId ?: senderId

        runOnUiThread {
            if (groupId == null) rememberContact(senderId)
            // Persist the reply link locally so the bubble can render its preview.
            if (replyTo != null) {
                prefs.edit().putString("$KEY_REPLY_PREFIX$canonicalMsgId", replyTo).apply()
            }
            val author = if (groupId != null) contactName(senderId) else contactName(senderId)
            appendMessage(chatId, author, text, canonicalMsgId, replyToMsgId = replyTo)
            notifyIncomingMessage(chatId, text)
            binding.status.text = "Message from ${contactName(senderId)}"
            if (remoteId == chatId && binding.chatScreen.visibility == View.VISIBLE) {
                binding.chatStatus.text = "Message from ${contactName(senderId)}"
                sendCloudReadReceipt(id, senderId)
            } else {
                pendingCloudReadReceipts.getOrPut(senderId) { mutableSetOf() }.add(id)
            }
            deleteCloudMessage(document)
        }
    }

    /** Mark a single cloud message as read in Firestore. */
    private fun sendCloudReadReceipt(msgId: String, contactId: String) {
        db?.collection("receipts")?.document(msgId)
            ?.update("read", true)
            ?.addOnFailureListener { e -> Log.w(TAG, "read receipt update failed: ${e.message}") }
    }

    /** Send read receipts for all queued cloud messages from [contactId]. */
    private fun sendCloudReadReceipts(contactId: String) {
        val pending = pendingCloudReadReceipts.remove(contactId) ?: return
        pending.forEach { msgId -> sendCloudReadReceipt(msgId, contactId) }
    }

    private fun pollReceipts() {
        val firestore = db ?: return
        firestore.collection("receipts")
            .whereEqualTo("to", localId)
            .limit(50)
            .get()
            .addOnSuccessListener { snap -> snap.documents.forEach { processReceiptDoc(it) } }
            .addOnFailureListener { e -> Log.w(TAG, "pollReceipts failed: ${e.message}") }
    }

    private fun processReceiptDoc(doc: DocumentSnapshot) {
        val msgId = doc.id
        val fromId = doc.getString("from") ?: return
        val isRead = doc.getBoolean("read") ?: false
        val isDelivered = doc.getBoolean("delivered") ?: false

        val newStatus = when {
            isRead -> MsgStatus.READ
            isDelivered -> MsgStatus.DELIVERED
            else -> return
        }
        // Only upgrade status, never downgrade
        if (getMessageStatus(msgId) != MsgStatus.READ) {
            saveMessageStatus(msgId, newStatus)
            runOnUiThread { refreshChatDisplay(fromId) }
        }
        // Delete receipt ONLY when READ is confirmed. Deleting on DELIVERED would
        // prevent the receiver from later .update("read", true) — the update would
        // hit a deleted doc and READ status would never propagate. Sender prunes
        // stale DELIVERED-only receipts via a periodic GC pass (see pruneOldReceipts).
        if (isRead) {
            doc.reference.delete()
        }
    }

    /**
     * Periodic GC: delete receipts older than 7 days whose READ has not been
     * confirmed. Prevents the 50-doc poll limit from filling up with stale
     * DELIVERED-only receipts when the peer never opens the chat.
     */
    private fun pruneOldReceipts() {
        val firestore = db ?: return
        val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        firestore.collection("receipts")
            .whereEqualTo("to", localId)
            .whereLessThan("createdAt", cutoff)
            .limit(50)
            .get()
            .addOnSuccessListener { snap ->
                snap.documents.forEach { it.reference.delete() }
            }
            .addOnFailureListener { e -> Log.w(TAG, "pruneOldReceipts failed: ${e.message}") }
    }

    private fun decryptCloudMessage(document: DocumentSnapshot): String? {
        document.getString("text")?.let { return it }

        val encryptedKey = document.getString("encryptedKey") ?: return null
        val iv = document.getString("iv") ?: return null
        val cipherText = document.getString("cipherText") ?: return null
        val keyAlgorithm = document.getString("keyAlgorithm") ?: EC_KEY_ALGORITHM

        return runCatching {
            decryptMessage(encryptedKey, iv, cipherText, keyAlgorithm)
        }.getOrElse { error ->
            updateDebugStatus("decrypt failed: ${error.message}")
            // Don't delete if keypair isn't loaded yet — transient, message still recoverable
            if (error.message != "EC keypair not loaded") {
                deleteCloudMessage(document)
            }
            null
        }
    }

    /**
     * ECIES: ephemeral ECDH key agreement + AES-256-GCM.
     * encryptedKey field carries the ephemeral EC public key (X.509 DER, base64).
     */
    private fun encryptMessageFor(text: String, recipientPublicKeyB64: String): EncryptedMessage {
        val kf = KeyFactory.getInstance("EC")
        val recipientPublicKey = kf.generatePublic(X509EncodedKeySpec(b64decode(recipientPublicKeyB64)))

        // Ephemeral keypair — fresh random per message
        val ephemKpg = KeyPairGenerator.getInstance("EC")
        ephemKpg.initialize(ECGenParameterSpec("secp256r1"))
        val ephemKp = ephemKpg.generateKeyPair()

        // ECDH shared secret
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ephemKp.private)
        ka.doPhase(recipientPublicKey, true)
        val aesKey = SecretKeySpec(sha256(ka.generateSecret()), "AES")

        val iv = ByteArray(AES_GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_MESSAGE_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        val cipherText = cipher.doFinal(text.toByteArray(Charsets.UTF_8))

        return EncryptedMessage(
            encryptedKey = b64(ephemKp.public.encoded), // ephemeral EC public key
            iv = b64(iv),
            cipherText = b64(cipherText),
            keyAlgorithm = EC_KEY_ALGORITHM
        )
    }

    /** ECIES decryption: ECDH with ephemeral public key from sender + AES-256-GCM. */
    private fun decryptMessage(
        encryptedKeyB64: String, // ephemeral EC public key (X.509 DER, base64)
        ivB64: String,
        cipherTextB64: String,
        @Suppress("UNUSED_PARAMETER") keyAlgorithm: String
    ): String {
        val kp = myEcKeyPair ?: error("EC keypair not loaded")
        val kf = KeyFactory.getInstance("EC")
        val ephemPublicKey = kf.generatePublic(X509EncodedKeySpec(b64decode(encryptedKeyB64)))

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(kp.private)
        ka.doPhase(ephemPublicKey, true)
        val aesKey = SecretKeySpec(sha256(ka.generateSecret()), "AES")

        val cipher = Cipher.getInstance(AES_MESSAGE_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(AES_GCM_TAG_BITS, b64decode(ivB64)))
        return String(cipher.doFinal(b64decode(cipherTextB64)), Charsets.UTF_8)
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
            if (receivedMessageIds.size > 500) {
                val excess = receivedMessageIds.size - 500
                val toRemove = receivedMessageIds.take(excess)
                receivedMessageIds.removeAll(toRemove.toSet())
            }
            prefs.edit().putStringSet(KEY_SEEN_MESSAGE_IDS, receivedMessageIds.toSet()).apply()
        }
    }

    private fun handleMessagePacket(packet: String) {
        val parts = packet.split("|", limit = 3)
        when (parts.firstOrNull()) {
            "ACK" -> {
                val id = parts.getOrNull(1) ?: return
                synchronized(pendingMessages) { pendingMessages.remove(id) }
                saveMessageStatus(id, MsgStatus.DELIVERED)
                runOnUiThread { refreshChatDisplay(remoteId) }
            }
            "READ" -> {
                val id = parts.getOrNull(1) ?: return
                saveMessageStatus(id, MsgStatus.READ)
                runOnUiThread { refreshChatDisplay(remoteId) }
            }
            "TXT" -> {
                val id = parts.getOrNull(1) ?: return
                val payload = parts.getOrNull(2) ?: return
                sendAck(id)

                // Replay protection: reject any (ts, seq) ≤ max seen for this peer.
                // msgId format is "peerId-ts-seq" set by nextMessageId(). Tracks
                // per-peer monotonic order across sessions; survives restart via
                // encrypted prefs. Combined with the in-memory receivedMessageIds
                // Set, this closes the finite-LRU window in #5.
                if (!acceptByReplayCounter(id)) {
                    Log.d(TAG, "TXT replay rejected: $id")
                    return
                }

                val isNew = synchronized(receivedMessageIds) { receivedMessageIds.add(id) }
                if (!isNew) return

                val text = String(Base64.decode(payload, Base64.NO_WRAP), Charsets.UTF_8)
                // Skip blank/whitespace-only packets — they render as empty bubbles
                // and inflate the unread-count badge.
                if (text.isBlank()) return
                val chatId = remoteId.takeIf { it.isNotBlank() } ?: "unknown"
                incomingMsgIds.getOrPut(chatId) { mutableListOf() }.add(id)
                appendMessage(chatId, contactName(chatId), text)
                runOnUiThread {
                    notifyIncomingMessage(chatId, text)
                    // If user is looking at this chat, send READ immediately
                    if (binding.chatScreen.visibility == View.VISIBLE && chatId == remoteId) {
                        sendRead(id)
                        incomingMsgIds[chatId]?.remove(id)
                    }
                }
            }
        }
    }

    private fun sendTextPacket(id: String, text: String) {
        val payload = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendMessagePacket("TXT|$id|$payload")
    }

    /**
     * Replay-protection check. msgId format is `<peerId>-<tsMs>-<seq>` set by
     * [nextMessageId]. Per-peer max (ts, seq) is persisted in encrypted prefs.
     *
     * Accepts iff `(newTs, newSeq) > (storedTs, storedSeq)` lexicographically.
     * On accept, updates the stored max. On reject, does not.
     *
     * Returns true if the message is fresh and should be processed.
     */
    private fun acceptByReplayCounter(msgId: String): Boolean {
        val msgParts = msgId.split("-")
        if (msgParts.size < 3) return true  // malformed msgId — fall through to dedup set
        val peer = msgParts[0]
        val ts = msgParts[1].toLongOrNull() ?: return true
        val seq = msgParts[2].toIntOrNull() ?: return true

        val key = "replay_max_$peer"
        val stored = prefs.getString(key, null)
        val (storedTs, storedSeq) = if (stored != null) {
            val sp = stored.split(":")
            (sp.getOrNull(0)?.toLongOrNull() ?: 0L) to (sp.getOrNull(1)?.toIntOrNull() ?: 0)
        } else 0L to 0

        val isNewer = ts > storedTs || (ts == storedTs && seq > storedSeq)
        if (!isNewer) return false

        prefs.edit().putString(key, "$ts:$seq").apply()
        return true
    }

    private fun sendAck(id: String) {
        sendMessagePacket("ACK|$id|")
    }

    private fun sendRead(id: String) {
        sendMessagePacket("READ|$id|")
    }

    /** Rebuild chat bubbles — debounced 150ms to batch rapid status-tick updates. */
    private fun refreshChatDisplay(chatId: String) {
        // If chat is not open we still want to refresh the contacts list so the
        // ✓/✓✓ indicator next to the last message preview updates live.
        if (binding.chatScreen.visibility != View.VISIBLE || chatId != remoteId) {
            if (binding.contactListScreen.visibility == View.VISIBLE) {
                runOnUiThread { renderContacts() }
            }
            return
        }
        pendingRefreshJob?.cancel()
        pendingRefreshJob = ioScope.launch {
            delay(150)
            withContext(Dispatchers.Main) {
                if (binding.chatScreen.visibility != View.VISIBLE || chatId != remoteId) return@withContext
                val allLines = splitLogLines(messageLogFor(chatId).toString())
                val query = chatSearchQuery
                val lines = if (query.isBlank()) allLines
                            else allLines.filter { line ->
                                val (_, text) = parseLineAuthorText(line)
                                text.contains(query, ignoreCase = true)
                            }
                renderChatMessages(chatId, lines, if (query.isBlank()) chatDividerLineIndex else -1)
            }
        }
    }

    private fun sendMessagePacket(packet: String): Boolean {
        val channel = messageChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false

        val bytes = packet.toByteArray(Charsets.UTF_8)
        if (bytes.size > 16 * 1024) {
            Log.e(TAG, "sendMessagePacket: packet too large (${bytes.size} bytes), dropping")
            return false
        }
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
        val seq = messageSeqCounter.incrementAndGet()
        return "$localId-${System.currentTimeMillis()}-$seq"
    }

    private fun appendMessage(chatId: String, author: String, text: String, msgId: String? = null,
                                replyToMsgId: String? = null) {
        // Last-line defence: blank/whitespace text must never reach the chat log.
        // Photo bubbles arrive as "[PHOTO:path]" which is not blank, so this is safe.
        if (text.isBlank()) return
        // Persist reply link separately from the log line so log format stays
        // simple. Looked up by render via getReplyTarget(msgId).
        if (msgId != null && replyToMsgId != null) {
            prefs.edit().putString("$KEY_REPLY_PREFIX$msgId", replyToMsgId).apply()
        }
        val update = {
            val log = messageLogFor(chatId)
            // Embed msgId in author field for all messages so reply-to + status
            // lookups work for incoming too: "Me|{msgId}: text\t{ts}" outgoing,
            // "{contactName}|{msgId}: text\t{ts}" incoming. Legacy entries
            // (msgId == null) keep the old "{author}: text" form for compat.
            val authorField = if (msgId != null) "$author|$msgId" else author
            // Escape real newlines in text so multi-line messages occupy exactly one log entry.
            //   (Unicode Line Separator) is visually invisible in normal text but safe here.
            val safeText = text.replace('\n', ' ')
            log.append(authorField).append(": ").append(safeText).append('\t').append(System.currentTimeMillis()).append('\n')
            prefs.edit().putString(chatLogKey(chatId), log.toString()).apply()
            if (binding.chatScreen.visibility == View.VISIBLE && chatId == remoteId) {
                val lines = splitLogLines(log.toString())
                prefs.edit().putInt("$KEY_CHAT_READ_PREFIX$chatId", lines.size).apply()
                renderChatMessages(chatId, lines, chatDividerLineIndex)
                binding.messagesScroll.post { binding.messagesScroll.fullScroll(View.FOCUS_DOWN) }
            }
            updateUnreadBadge()
            // If user is on the contacts list, refresh it live so blue dots update immediately
            if (binding.contactListScreen.visibility == View.VISIBLE) {
                renderContacts()
            }
        }
        if (Thread.currentThread() == mainLooper.thread) {
            update()
        } else {
            runOnUiThread(update)
        }
    }

    // ─── Chat bubble rendering ────────────────────────────────────────────────

    private var chatDividerView: View? = null
    private var pendingRefreshJob: Job? = null

    /**
     * Split a raw log string into individual message entries.
     * Uses the structural boundary "\t{digits}\n" instead of bare "\n" so that
     * multi-line message texts (containing real newlines) are never split into
     * separate entries — which would cause the continuation parts to render on
     * the wrong (incoming) side.
     * Falls back to bare-newline split for legacy entries that lack a timestamp.
     */
    private fun splitLogLines(log: String): List<String> {
        if (log.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        val re = Regex("""\t\d{10,}\n""")
        var pos = 0
        for (m in re.findAll(log)) {
            val end = m.range.last + 1        // include the trailing \n
            val entry = log.substring(pos, end).trimEnd('\n')
            if (entry.isNotEmpty() && !isBlankEntry(entry)) result.add(entry)
            pos = end
        }
        // Legacy entries without timestamp that might remain after pos
        if (pos < log.length) {
            log.substring(pos).split('\n')
                .filter { it.isNotEmpty() && !isBlankEntry(it) }
                .forEach { result.add(it) }
        }
        return result
    }

    /**
     * True if a raw log entry has no displayable message body (whitespace only
     * after the author marker). Filters out junk that historically polluted the
     * unread-count badge and rendered as empty bubbles below the "New messages"
     * divider.
     */
    private fun isBlankEntry(entry: String): Boolean {
        val (_, text) = parseLineAuthorText(entry)
        return text.isBlank()
    }

    private fun lineText(line: String): String {
        val tab = line.lastIndexOf('\t')
        return if (tab >= 0) line.substring(0, tab) else line
    }

    private fun lineTimestamp(line: String): Long {
        val tab = line.lastIndexOf('\t')
        if (tab < 0) return 0L
        return line.substring(tab + 1).toLongOrNull() ?: 0L
    }

    /**
     * Extract msgId embedded in log lines.
     *
     * New format (v1.14.20+): `"{author}|{msgId}: text"` — works for any author.
     * Legacy outgoing-only format: `"Me|{msgId}: text"` (still covered by the
     * same logic since "Me" has no embedded "|").
     *
     * Returns null for entries that pre-date the embedded-msgId format.
     */
    private fun lineMsgId(line: String): String? {
        // trimStart() — same Codex v1.13.x leading-whitespace corruption fix
        // as in parseLineAuthorText().
        val text = lineText(line).trimStart()
        val sep = text.indexOf(": ")
        if (sep < 0) return null
        val authorField = text.substring(0, sep)
        val pipe = authorField.indexOf('|')
        if (pipe < 0) return null
        val msgId = authorField.substring(pipe + 1)
        return msgId.takeIf { it.isNotBlank() }
    }

    /**
     * Persist msgId→status to SharedPreferences AND update in-memory map.
     * Use this instead of direct messageStatuses[id] = ... assignment.
     */
    private fun saveMessageStatus(msgId: String, status: MsgStatus) {
        messageStatuses[msgId] = status
        prefs.edit().putString("mst_$msgId", status.name).apply()
    }

    /**
     * Look up status for msgId: in-memory first, then SharedPreferences (lazy load).
     * Returns null only if msgId has no status recorded anywhere.
     */
    private fun getMessageStatus(msgId: String): MsgStatus? {
        messageStatuses[msgId]?.let { return it }
        val stored = prefs.getString("mst_$msgId", null) ?: return null
        val status = runCatching { MsgStatus.valueOf(stored) }.getOrNull() ?: return null
        messageStatuses[msgId] = status  // cache for this session
        return status
    }

    private fun parseLineAuthorText(line: String): Pair<String, String> {
        val text = lineText(line).trimStart()
        // Generic "{author}|{msgId}: body" — author = part before "|".
        // Covers v1.14.20+ embedded-msgId-for-all-authors format.
        val ci = text.indexOf(": ")
        if (ci < 0) return "" to text
        val authorField = text.substring(0, ci)
        val body = text.substring(ci + 2)
        val pipe = authorField.indexOf('|')
        val author = if (pipe >= 0) authorField.substring(0, pipe) else authorField
        return author to body
    }

    /**
     * Fan-out group send: encrypt the same plaintext under each member's pubkey
     * (sender excluded), write a /messages doc per recipient with a shared
     * `groupId` field. Receivers route the message into the group chat instead
     * of the 1:1 chat by looking at `groupId`.
     *
     * Same outgoing msgId is reused across the fan-out so the sender sees only
     * one bubble in their own chat log.
     */
    private fun sendGroupMessage(groupId: String, text: String) {
        val firestore = firestoreOrWarn() ?: return
        val members = groupMembers(groupId).filter { it != localId }
        if (members.isEmpty()) {
            Toast.makeText(this, "Group has no other members", Toast.LENGTH_SHORT).show()
            return
        }
        val msgId = nextMessageId()
        val replyTo = replyingToMsgId
        binding.btnSend.isEnabled = false

        // Save sender's local bubble first so the user sees instant feedback.
        appendMessage(groupId, "Me", text, msgId, replyToMsgId = replyTo)
        cancelReply()
        binding.messageInput.text?.clear()
        saveMessageStatus(msgId, MsgStatus.SENT)

        val pending = java.util.concurrent.atomic.AtomicInteger(members.size)
        members.forEach { memberId ->
            firestore.collection("users").document(memberId).get()
                .addOnSuccessListener { userDoc ->
                    val publicKeyB64 = userDoc.getString("messagePublicKey")
                    if (publicKeyB64.isNullOrBlank()) {
                        Log.w(TAG, "group send: $memberId missing pubkey")
                        if (pending.decrementAndGet() == 0) runOnUiThread { binding.btnSend.isEnabled = true }
                        return@addOnSuccessListener
                    }
                    val encrypted = runCatching { encryptMessageFor(text, publicKeyB64) }.getOrNull()
                    if (encrypted == null) {
                        if (pending.decrementAndGet() == 0) runOnUiThread { binding.btnSend.isEnabled = true }
                        return@addOnSuccessListener
                    }
                    val docId = "$msgId-$memberId"
                    val message = mutableMapOf<String, Any>(
                        "from" to localId,
                        "to" to memberId,
                        "groupId" to groupId,
                        "encryptedKey" to encrypted.encryptedKey,
                        "iv" to encrypted.iv,
                        "cipherText" to encrypted.cipherText,
                        "messageAlgorithm" to AES_MESSAGE_ALGORITHM,
                        "keyAlgorithm" to encrypted.keyAlgorithm,
                        "createdAt" to System.currentTimeMillis()
                    )
                    if (replyTo != null) message["replyTo"] = replyTo
                    firestore.collection("messages").document(docId).set(message)
                        .addOnFailureListener { e -> Log.w(TAG, "group send to $memberId failed: ${e.message}") }
                        .addOnCompleteListener {
                            if (pending.decrementAndGet() == 0) runOnUiThread {
                                binding.btnSend.isEnabled = true
                                binding.chatStatus.text = "Sent to ${members.size} members"
                            }
                        }
                }
                .addOnFailureListener {
                    if (pending.decrementAndGet() == 0) runOnUiThread { binding.btnSend.isEnabled = true }
                }
        }
    }

    /** Look up the msgId this message is a reply to, or null. */
    private fun getReplyTarget(msgId: String?): String? {
        if (msgId.isNullOrBlank()) return null
        return prefs.getString("$KEY_REPLY_PREFIX$msgId", null)
    }

    /**
     * Find the rendered body of a previously sent/received message by its msgId
     * inside the given chat's log. Used to build the small preview shown above
     * a reply bubble.
     */
    private fun lookupMessagePreview(chatId: String, targetMsgId: String): String? {
        val lines = splitLogLines(messageLogFor(chatId).toString())
        for (line in lines) {
            if (lineMsgId(line) == targetMsgId) {
                val (_, body) = parseLineAuthorText(line)
                return when {
                    body.startsWith("[PHOTO:") -> "📷 Photo"
                    body.startsWith("[PHOTO_PENDING:") -> "📷 Photo"
                    body.startsWith("[PHOTO_FAILED:") -> "📷 Photo"
                    else -> body.take(60)
                }
            }
        }
        return null
    }

    // ─── Group chats ──────────────────────────────────────────────────────────

    private fun savedGroupIds(): Set<String> =
        prefs.getStringSet(KEY_GROUP_IDS, emptySet()).orEmpty()

    private fun isGroup(id: String): Boolean = id.startsWith(GROUP_ID_PREFIX)

    private fun groupName(id: String): String =
        prefs.getString("$KEY_GROUP_NAME_PREFIX$id", null)?.takeIf { it.isNotBlank() } ?: id

    private fun groupMembers(id: String): List<String> {
        val csv = prefs.getString("$KEY_GROUP_MEMBERS_PREFIX$id", null) ?: return emptyList()
        return csv.split(",").filter { it.isNotBlank() }
    }

    private fun groupAdmin(id: String): String? =
        prefs.getString("$KEY_GROUP_ADMIN_PREFIX$id", null)

    /**
     * Show a dialog: group name + checkbox list of saved contacts.
     * Creator becomes the admin and is auto-added to members.
     */
    private fun showNewGroupDialog() {
        val contacts = savedContactIds().sortedBy { contactName(it).lowercase(Locale.US) }
        if (contacts.isEmpty()) {
            Toast.makeText(this, "Add some contacts first", Toast.LENGTH_SHORT).show()
            return
        }
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Group name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1
        }
        container.addView(nameInput)
        container.addView(android.widget.TextView(this).apply {
            text = "Members"
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
        })
        val checkboxes = contacts.map { id ->
            android.widget.CheckBox(this).apply {
                text = contactName(id)
                tag = id
            }
        }
        checkboxes.forEach { container.addView(it) }

        AlertDialog.Builder(this)
            .setTitle("New group")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim().ifBlank { "Untitled" }
                val selected = checkboxes.filter { it.isChecked }.map { it.tag as String }
                if (selected.isEmpty()) {
                    Toast.makeText(this, "Pick at least one member", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val rawId = java.util.UUID.randomUUID().toString().take(12).uppercase(Locale.US)
                val groupId = "$GROUP_ID_PREFIX$rawId"
                val members = (selected + localId).distinct()
                saveGroup(groupId, name, members, localId)
                renderContacts()
                openChat(groupId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Pull group metadata from /groups/{rawId} and persist locally, then start
     * a snapshot listener so future admin edits (rename, member add/remove)
     * propagate to this device.
     */
    private fun discoverGroup(groupId: String) {
        val rawId = groupId.removePrefix(GROUP_ID_PREFIX)
        db?.collection("groups")?.document(rawId)?.get()
            ?.addOnSuccessListener { snap ->
                val name = snap.getString("name") ?: return@addOnSuccessListener
                @Suppress("UNCHECKED_CAST")
                val members = (snap.get("members") as? List<String>) ?: return@addOnSuccessListener
                val adminId = snap.getString("adminId") ?: return@addOnSuccessListener
                // Persist locally without re-writing to /groups (the doc already exists).
                prefs.edit()
                    .putStringSet(KEY_GROUP_IDS, savedGroupIds() + groupId)
                    .putString("$KEY_GROUP_NAME_PREFIX$groupId", name)
                    .putString("$KEY_GROUP_MEMBERS_PREFIX$groupId", members.joinToString(","))
                    .putString("$KEY_GROUP_ADMIN_PREFIX$groupId", adminId)
                    .apply()
                listenToGroup(groupId)
                runOnUiThread { if (binding.contactListScreen.visibility == View.VISIBLE) renderContacts() }
            }
            ?.addOnFailureListener { e -> Log.w(TAG, "discoverGroup($groupId) failed: ${e.message}") }
    }

    private val groupListeners = mutableMapOf<String, ListenerRegistration>()

    /** Subscribe to admin-driven edits on /groups/{rawId}. Idempotent. */
    private fun listenToGroup(groupId: String) {
        if (groupListeners.containsKey(groupId)) return
        val rawId = groupId.removePrefix(GROUP_ID_PREFIX)
        val reg = db?.collection("groups")?.document(rawId)
            ?.addSnapshotListener { snap, _ ->
                snap ?: return@addSnapshotListener
                if (!snap.exists()) {
                    // Group was deleted by admin — drop it locally.
                    leaveGroupLocally(groupId)
                    return@addSnapshotListener
                }
                val name = snap.getString("name") ?: return@addSnapshotListener
                @Suppress("UNCHECKED_CAST")
                val members = (snap.get("members") as? List<String>) ?: return@addSnapshotListener
                val adminId = snap.getString("adminId") ?: return@addSnapshotListener
                // If we were removed by admin, treat as forced leave.
                if (localId !in members) {
                    leaveGroupLocally(groupId)
                    return@addSnapshotListener
                }
                prefs.edit()
                    .putString("$KEY_GROUP_NAME_PREFIX$groupId", name)
                    .putString("$KEY_GROUP_MEMBERS_PREFIX$groupId", members.joinToString(","))
                    .putString("$KEY_GROUP_ADMIN_PREFIX$groupId", adminId)
                    .apply()
                runOnUiThread {
                    if (remoteId == groupId) {
                        binding.chatTitle.text = "👥 $name"
                        binding.chatPeerId.text = "${members.size} members"
                    }
                    if (binding.contactListScreen.visibility == View.VISIBLE) renderContacts()
                }
            } ?: return
        groupListeners[groupId] = reg
    }

    /** Drop a group from local storage (member kicked, admin deleted, or self-leave). */
    private fun leaveGroupLocally(groupId: String) {
        groupListeners.remove(groupId)?.remove()
        prefs.edit()
            .putStringSet(KEY_GROUP_IDS, savedGroupIds() - groupId)
            .remove("$KEY_GROUP_NAME_PREFIX$groupId")
            .remove("$KEY_GROUP_MEMBERS_PREFIX$groupId")
            .remove("$KEY_GROUP_ADMIN_PREFIX$groupId")
            .remove(chatLogKey(groupId))
            .remove("$KEY_CHAT_READ_PREFIX$groupId")
            .apply()
        messageLogs.remove(groupId)
        runOnUiThread {
            if (remoteId == groupId) {
                remoteId = ""
                showContactList()
            } else {
                renderContacts()
            }
        }
    }

    /** Admin-only: write updated members list back to /groups/{rawId}. */
    private fun updateGroupMembers(groupId: String, newMembers: List<String>, newName: String? = null) {
        val rawId = groupId.removePrefix(GROUP_ID_PREFIX)
        val patch = mutableMapOf<String, Any>("members" to newMembers)
        if (newName != null) patch["name"] = newName
        db?.collection("groups")?.document(rawId)?.update(patch)
            ?.addOnFailureListener { e ->
                runOnUiThread { Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
    }

    /** Self-leave: remove self from group's member list, then drop locally. */
    private fun leaveGroup(groupId: String) {
        val rawId = groupId.removePrefix(GROUP_ID_PREFIX)
        val currentMembers = groupMembers(groupId)
        val newMembers = currentMembers.filter { it != localId }
        val adminId = groupAdmin(groupId)
        if (adminId == localId) {
            // Admin can't leave — must delete or transfer first. Force delete on leave.
            db?.collection("groups")?.document(rawId)?.delete()
                ?.addOnFailureListener { e -> Log.w(TAG, "group delete failed: ${e.message}") }
        } else {
            // Non-admin leave: only admin can rewrite members in v1.14.21 rules.
            // Work around: ask the admin via a /messages text packet. For MVP,
            // just delete locally — server will keep stale member list until
            // admin removes us. Future: server-side "request leave" function.
            Log.d(TAG, "Self-leave: ${groupId}; admin will see stale entry until they remove us")
        }
        leaveGroupLocally(groupId)
    }

    private fun showGroupInfo(groupId: String) {
        val name = groupName(groupId)
        val members = groupMembers(groupId)
        val adminId = groupAdmin(groupId)
        val isAdmin = adminId == localId

        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        container.addView(android.widget.TextView(this).apply {
            text = "Members (${members.size})"
            setPadding(0, 0, 0, (8 * dp).toInt())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
        })
        members.forEach { mid ->
            val label = when {
                mid == localId -> "${contactName(mid)} (you)"
                mid == adminId -> "${contactName(mid)} (admin)"
                else -> contactName(mid)
            }
            container.addView(android.widget.TextView(this).apply {
                text = "• $label"
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            })
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("👥 $name")
            .setView(container)
        if (isAdmin) {
            builder.setPositiveButton("Manage") { _, _ -> showGroupManageDialog(groupId) }
        }
        builder.setNeutralButton(if (isAdmin) "Delete group" else "Leave group") { _, _ ->
            AlertDialog.Builder(this)
                .setMessage(if (isAdmin) "Delete \"$name\" for everyone?" else "Leave \"$name\"?")
                .setPositiveButton(if (isAdmin) "Delete" else "Leave") { _, _ -> leaveGroup(groupId) }
                .setNegativeButton("Cancel", null)
                .show()
        }
        builder.setNegativeButton("Close", null)
        builder.show()
    }

    /** Admin-only: rename + manage member roster. */
    private fun showGroupManageDialog(groupId: String) {
        val currentMembers = groupMembers(groupId)
        val candidates = savedContactIds().sortedBy { contactName(it).lowercase(Locale.US) }
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val nameInput = EditText(this).apply {
            setText(groupName(groupId))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1
        }
        container.addView(nameInput)
        container.addView(android.widget.TextView(this).apply {
            text = "Members"
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
        })
        val checkboxes = candidates.map { id ->
            android.widget.CheckBox(this).apply {
                text = contactName(id)
                tag = id
                isChecked = id in currentMembers
            }
        }
        checkboxes.forEach { container.addView(it) }

        AlertDialog.Builder(this)
            .setTitle("Manage group")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim().ifBlank { groupName(groupId) }
                val selected = checkboxes.filter { it.isChecked }.map { it.tag as String }
                val members = (selected + localId).distinct()
                updateGroupMembers(groupId, members, newName = name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Persist a newly-created group locally. Also writes the /groups Firestore doc. */
    private fun saveGroup(id: String, name: String, members: List<String>, adminId: String) {
        prefs.edit()
            .putStringSet(KEY_GROUP_IDS, savedGroupIds() + id)
            .putString("$KEY_GROUP_NAME_PREFIX$id", name)
            .putString("$KEY_GROUP_MEMBERS_PREFIX$id", members.joinToString(","))
            .putString("$KEY_GROUP_ADMIN_PREFIX$id", adminId)
            .apply()
        val rawId = id.removePrefix(GROUP_ID_PREFIX)
        db?.collection("groups")?.document(rawId)?.set(
            mapOf(
                "name" to name,
                "members" to members,
                "adminId" to adminId,
                "createdAt" to System.currentTimeMillis()
            )
        )?.addOnFailureListener { e -> Log.w(TAG, "group write failed: ${e.message}") }
        listenToGroup(id)
    }

    /** Bring up all snapshot listeners for groups we already know about. */
    private fun resumeAllGroupListeners() {
        savedGroupIds().forEach { gid -> listenToGroup(gid) }
    }

    private fun formatMessageTime(epochMs: Long): String {
        if (epochMs == 0L) return ""
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMs
        return "%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }

    private fun renderChatMessages(chatId: String, lines: List<String>, dividerAt: Int) {
        binding.messagesContainer.removeAllViews()
        chatDividerView = null
        if (lines.isEmpty()) return

        val timestamps = lines.map { lineTimestamp(it) }
        // Determine which lines show timestamp: last in each consecutive same-minute group
        val showTime = Array<String?>(lines.size) { null }
        for (i in lines.indices) {
            val ts = timestamps[i]
            if (ts == 0L) continue
            val nextTs = timestamps.getOrElse(i + 1) { 0L }
            val thisMin = ts / 60_000
            val nextMin = if (nextTs != 0L) nextTs / 60_000 else -1L
            if (thisMin != nextMin) showTime[i] = formatMessageTime(ts)
        }

        lines.forEachIndexed { i, line ->
            if (i == dividerAt && dividerAt >= 0) {
                val dv = createMsgDividerView()
                chatDividerView = dv
                binding.messagesContainer.addView(dv)
            }
            val (author, text) = parseLineAuthorText(line)
            val isOutgoing = author == "Me"
            val ts = showTime[i]
            val status: MsgStatus? = if (isOutgoing) {
                // Prefer msgId embedded in log line (new format: "Me|{msgId}: ...")
                val embeddedId = lineMsgId(line)
                if (embeddedId != null) {
                    getMessageStatus(embeddedId) ?: MsgStatus.SENT  // at minimum show ✓
                } else {
                    // Legacy log line without embedded msgId — no status tracking possible
                    MsgStatus.SENT
                }
            } else null

            val lineTs = lineTimestamp(line)
            val lineMsgId = lineMsgId(line)
            val onLongClick: () -> Unit = { showMessageContextMenu(chatId, lineTs, text, isOutgoing, lineMsgId) }
            val replyPreview = lineMsgId?.let { getReplyTarget(it) }?.let { lookupMessagePreview(chatId, it) }
            // Group chats: show sender name above incoming bubbles so readers
            // can tell members apart.
            val senderLabel = if (!isOutgoing && isGroup(chatId)) author.takeIf { it.isNotBlank() } else null
            binding.messagesContainer.addView(
                when {
                    text.startsWith("[PHOTO:") && text.endsWith("]") -> {
                        createPhotoBubble(text.removePrefix("[PHOTO:").removeSuffix("]"), isOutgoing, ts, onLongClick)
                    }
                    text.startsWith("[PHOTO_PENDING:") && text.endsWith("]") -> {
                        // path|tid format — drop the |tid suffix for rendering
                        val body = text.removePrefix("[PHOTO_PENDING:").removeSuffix("]")
                        val path = body.substringBefore('|')
                        createPhotoBubble(path, isOutgoing, ts, onLongClick, photoState = PhotoState.PENDING)
                    }
                    text.startsWith("[PHOTO_FAILED:") && text.endsWith("]") -> {
                        val path = text.removePrefix("[PHOTO_FAILED:").removeSuffix("]")
                        createPhotoBubble(path, isOutgoing, ts, onLongClick, photoState = PhotoState.FAILED)
                    }
                    else -> {
                        createTextBubble(text, isOutgoing, ts, status, onLongClick,
                            replyPreview = replyPreview, senderLabel = senderLabel)
                    }
                }
            )
        }
    }

    private fun createTextBubble(text: String, isOutgoing: Boolean, time: String?, status: MsgStatus?,
                                  onLongClick: (() -> Unit)? = null,
                                  replyPreview: String? = null,
                                  senderLabel: String? = null): android.view.View {
        val dp = resources.displayMetrics.density

        val bubbleLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (isOutgoing) R.drawable.bg_bubble_out else R.drawable.bg_bubble_in
            )
            val pad = (12 * dp).toInt()
            val padV = (8 * dp).toInt()
            setPadding(pad, padV, pad, padV)

            // Group chats: show sender's name above the message for incoming bubbles.
            if (senderLabel != null) {
                addView(android.widget.TextView(this@MainActivity).apply {
                    this.text = senderLabel
                    textSize = 11f
                    setTextColor(0xFF4FC3F7.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, (2 * dp).toInt())
                })
            }

            // Reply preview: tiny quoted block above message body.
            if (replyPreview != null) {
                addView(android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 6 * dp
                        setColor(0x33FFFFFF)
                    }
                    setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4 * dp).toInt() }

                    addView(android.view.View(this@MainActivity).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams((3 * dp).toInt(), android.widget.LinearLayout.LayoutParams.MATCH_PARENT)
                            .apply { rightMargin = (6 * dp).toInt() }
                        setBackgroundColor(0xFF4FC3F7.toInt())
                    })
                    addView(android.widget.TextView(this@MainActivity).apply {
                        this.text = replyPreview
                        textSize = 12f
                        setTextColor(0xFFCCCCCC.toInt())
                        maxLines = 2
                    })
                })
            }

            addView(android.widget.TextView(this@MainActivity).apply {
                this.text = text
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            })

            // Time + status on a compact bottom-right row (Telegram-style).
            if (time != null || status != null) {
                addView(android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (2 * dp).toInt() }

                    if (time != null) {
                        addView(android.widget.TextView(this@MainActivity).apply {
                            this.text = time
                            textSize = 10f
                            setTextColor(0xFF888888.toInt())
                            includeFontPadding = false
                        })
                    }
                    if (status != null) {
                        val gray = 0xFF888888.toInt()
                        val blue = 0xFF4FC3F7.toInt()
                        val (mark, color) = when (status) {
                            MsgStatus.SENT      -> "✓"  to gray
                            MsgStatus.DELIVERED -> "✓✓" to gray
                            MsgStatus.READ      -> "✓✓" to blue
                        }
                        addView(android.widget.TextView(this@MainActivity).apply {
                            this.text = mark
                            textSize = 11f
                            setTextColor(color)
                            includeFontPadding = false
                            // Compact double-tick via negative letter-spacing (overlap effect).
                            letterSpacing = -0.18f
                            setPadding((4 * dp).toInt(), 0, 0, 0)
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        })
                    }
                })
            }
            if (onLongClick != null) {
                isLongClickable = true
                setOnLongClickListener { onLongClick(); true }
            }
        }

        return android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (3 * dp).toInt()
                bottomMargin = (3 * dp).toInt()
            }
            addView(bubbleLayout, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isOutgoing) android.view.Gravity.END else android.view.Gravity.START
                leftMargin = if (!isOutgoing) (12 * dp).toInt() else (52 * dp).toInt()
                rightMargin = if (isOutgoing) (12 * dp).toInt() else (52 * dp).toInt()
            })
        }
    }

    private fun createPhotoBubble(path: String, isOutgoing: Boolean, time: String?,
                                   onLongClick: (() -> Unit)? = null,
                                   photoState: PhotoState = PhotoState.OK): android.view.View {
        val dp = resources.displayMetrics.density
        val maxPx = (220 * dp).toInt()
        val file = java.io.File(path)

        // File missing — show placeholder text bubble instead of empty photo bubble
        if (!file.exists()) {
            val placeholder = when (photoState) {
                PhotoState.PENDING -> "⏳ Sending photo..."
                PhotoState.FAILED  -> "⚠️ Photo not delivered"
                PhotoState.OK      -> "📷 Photo unavailable"
            }
            return createTextBubble(placeholder, isOutgoing, time, null, onLongClick)
        }

        val imgView = android.widget.ImageView(this).apply {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, opts)
            val sample = maxOf(1, opts.outWidth / maxPx)
            val bmp = android.graphics.BitmapFactory.decodeFile(
                path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            )
            setImageBitmap(bmp)
            scaleType = android.widget.ImageView.ScaleType.FIT_START
            adjustViewBounds = true
            maxWidth = maxPx
            setOnClickListener { showFullScreenPhoto(path) }
            // Dim image while transfer is in-flight or after failure.
            when (photoState) {
                PhotoState.PENDING -> alpha = 0.5f
                PhotoState.FAILED  -> alpha = 0.4f
                PhotoState.OK      -> { /* default */ }
            }
        }

        val bubbleLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (isOutgoing) R.drawable.bg_bubble_out else R.drawable.bg_bubble_in
            )
            val pad = (6 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            addView(imgView)
            // Status overlay below the image for non-OK states.
            if (photoState != PhotoState.OK) {
                addView(android.widget.TextView(this@MainActivity).apply {
                    this.text = when (photoState) {
                        PhotoState.PENDING -> "⏳ Sending..."
                        PhotoState.FAILED  -> "⚠️ Not delivered"
                        PhotoState.OK      -> ""
                    }
                    textSize = 11f
                    setTextColor(if (photoState == PhotoState.FAILED) 0xFFE57373.toInt() else 0xFFAAAAAA.toInt())
                    gravity = android.view.Gravity.END
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            }
            if (time != null) {
                addView(android.widget.TextView(this@MainActivity).apply {
                    this.text = time
                    textSize = 10f
                    setTextColor(0xFF666666.toInt())
                    gravity = android.view.Gravity.END
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            }
            if (onLongClick != null) {
                isLongClickable = true
                setOnLongClickListener { onLongClick(); true }
            }
        }

        return android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (3 * dp).toInt()
                bottomMargin = (3 * dp).toInt()
            }
            addView(bubbleLayout, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isOutgoing) android.view.Gravity.END else android.view.Gravity.START
                leftMargin = if (!isOutgoing) (12 * dp).toInt() else (52 * dp).toInt()
                rightMargin = if (isOutgoing) (12 * dp).toInt() else (52 * dp).toInt()
            })
        }
    }

    private fun createMsgDividerView(): android.view.View {
        val dp = resources.displayMetrics.density
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * dp).toInt()
                bottomMargin = (10 * dp).toInt()
            }
            addView(android.view.View(this@MainActivity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, (1 * dp).toInt(), 1f).apply {
                    rightMargin = (8 * dp).toInt()
                }
                setBackgroundColor(0xFF555555.toInt())
                alpha = 0.5f
            })
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "New messages"
                textSize = 11f
                setTextColor(0xFF888888.toInt())
            })
            addView(android.view.View(this@MainActivity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, (1 * dp).toInt(), 1f).apply {
                    leftMargin = (8 * dp).toInt()
                }
                setBackgroundColor(0xFF555555.toInt())
                alpha = 0.5f
            })
        }
    }

    // ─── End Chat bubble rendering ────────────────────────────────────────────

    // ─── Contact context menu (long-press) ───────────────────────────────────

    private fun showContactContextMenu(id: String) {
        if (isGroup(id)) {
            showGroupInfo(id)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(contactName(id))
            .setItems(arrayOf("Edit name", "Delete contact")) { _, which ->
                when (which) {
                    0 -> showRenameContactDialog(id)
                    1 -> showDeleteContactDialog(id)
                }
            }
            .show()
    }

    private fun showRenameContactDialog(id: String) {
        val input = EditText(this).apply {
            setText(contactName(id))
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(
                (24 * resources.displayMetrics.density).toInt(), 0,
                (24 * resources.displayMetrics.density).toInt(), 0
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Rename contact")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    prefs.edit().putString("$KEY_CONTACT_PREFIX$id", newName).apply()
                    renderContacts()
                    if (remoteId == id) binding.chatTitle.text = newName
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.post {
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showDeleteContactDialog(id: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete contact")
            .setMessage("Remove \"${contactName(id)}\" and all messages?")
            .setPositiveButton("Delete") { _, _ -> deleteContact(id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteContact(id: String) {
        val newSet = savedContactIds().toMutableSet().apply { remove(id) }
        prefs.edit()
            .putStringSet(KEY_CONTACT_IDS, newSet)
            .remove("$KEY_CONTACT_PREFIX$id")
            .remove("$KEY_CHAT_LOG_PREFIX$id")
            .remove("$KEY_CHAT_READ_PREFIX$id")
            .apply()
        messageLogs.remove(id)
        if (remoteId == id) {
            remoteId = ""
            showContactList()
        } else {
            renderContacts()
        }
    }

    // ─── Message context menu (long-press) ───────────────────────────────────

    private fun showMessageContextMenu(chatId: String, lineTs: Long, text: String, @Suppress("UNUSED_PARAMETER") isOutgoing: Boolean, msgId: String? = null) {
        // When search is active, show only Copy + Reply (delete index is ambiguous with filtered view)
        val canReply = msgId != null
        val items = mutableListOf("Copy text")
        if (canReply) items.add("Reply")
        if (chatSearchQuery.isBlank()) items.add("Delete message")
        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which ->
                val label = items[which]
                when (label) {
                    "Copy text" -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    "Reply" -> {
                        msgId?.let { startReplyingTo(it) }
                    }
                    "Delete message" -> AlertDialog.Builder(this)
                        .setMessage("Delete this message?")
                        .setPositiveButton("Delete") { _, _ -> deleteMessageAt(chatId, lineTs) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    /** Enters reply-mode: shows the preview bar above the input + focuses input. */
    private fun startReplyingTo(msgId: String) {
        replyingToMsgId = msgId
        val preview = lookupMessagePreview(remoteId, msgId) ?: "(unknown message)"
        binding.replyPreviewText.text = preview
        binding.replyPreviewBar.visibility = View.VISIBLE
        binding.messageInput.requestFocus()
    }

    private fun cancelReply() {
        replyingToMsgId = null
        binding.replyPreviewBar.visibility = View.GONE
    }

    private fun deleteMessageAt(chatId: String, lineTs: Long) {
        val allLines = splitLogLines(messageLogFor(chatId).toString()).toMutableList()
        val idx = allLines.indexOfFirst { lineTimestamp(it) == lineTs }
        if (idx < 0) return
        allLines.removeAt(idx)
        val newLog = allLines.joinToString("") { "$it\n" }
        messageLogs[chatId] = StringBuilder(newLog)
        prefs.edit()
            .putString(chatLogKey(chatId), newLog)
            .putInt("$KEY_CHAT_READ_PREFIX$chatId", allLines.size)
            .apply()
        refreshChatDisplay(chatId)
    }

    // ─── App lock (PIN / biometric) ──────────────────────────────────────────

    private fun checkAndShowLockScreen() {
        val lockEnabled = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        val pinSet = prefs.getString(KEY_APP_PIN_HASH, null) != null
        val loggedIn = localId.isNotBlank()
        // Lock applies during active calls too — previously this carveout
        // let an attacker who triggered a fake incoming call bypass the lock.
        // The call screen survives the lock overlay (FGS notification still
        // works) so user can still End call from the lock screen via Back.
        if (lockEnabled && pinSet && loggedIn && !appUnlocked) {
            showLockScreen()
        }
    }

    private fun showLockScreen() {
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.lockScreen.visibility = View.VISIBLE
        binding.lockStatus.text = "Enter PIN to unlock"
        binding.pinInput.text?.clear()
        binding.pinInput.requestFocus()
        // Show biometric button only if hardware available
        val bm = BiometricManager.from(this)
        val canUseBiometric = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
        binding.btnUseBiometric.visibility = if (canUseBiometric) View.VISIBLE else View.GONE
        // Auto-trigger biometric prompt if available
        if (canUseBiometric) showBiometricPrompt()
    }

    private fun hideLockScreen() {
        appUnlocked = true
        binding.lockScreen.visibility = View.GONE
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    private fun attemptPinUnlock(pin: String) {
        if (pin.isBlank()) {
            binding.lockStatus.text = "Enter your PIN"
            return
        }
        // Rate-limit: backoff after repeated failures so brute-force is impractical.
        val failures = prefs.getInt(KEY_PIN_FAILURES, 0)
        val lockoutUntil = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
        val nowMs = System.currentTimeMillis()
        if (nowMs < lockoutUntil) {
            val secsLeft = ((lockoutUntil - nowMs) / 1000).coerceAtLeast(1)
            binding.lockStatus.text = "Too many attempts — wait ${secsLeft}s"
            return
        }

        val storedHash = prefs.getString(KEY_APP_PIN_HASH, null)
        val storedSalt = prefs.getString(KEY_APP_PIN_SALT, null)
        if (storedHash == null || storedSalt == null) { hideLockScreen(); return }

        val enteredHash = hashPin(pin, b64decode(storedSalt))
        // Constant-time compare: String == leaks timing on per-character mismatch
        // position, letting an attacker grind toward the right prefix.
        if (java.security.MessageDigest.isEqual(
                enteredHash.toByteArray(Charsets.UTF_8),
                storedHash.toByteArray(Charsets.UTF_8))) {
            prefs.edit()
                .putInt(KEY_PIN_FAILURES, 0)
                .putLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
                .apply()
            hideLockScreen()
        } else {
            val newFailures = failures + 1
            val edit = prefs.edit().putInt(KEY_PIN_FAILURES, newFailures)
            // Exponential backoff after 3 failures: 30s, 60s, 120s, 240s, ...
            // After 10 failures total: WIPE all crypto material + chat logs + contacts.
            // Previously only PIN was cleared, leaving the attacker with full account
            // access by simply reinstalling. Wipe is the only sane response to N PIN
            // failures on a device the attacker physically holds.
            if (newFailures >= 10) {
                Log.w(TAG, "PIN brute-force threshold reached — wiping local account")
                Toast.makeText(this,
                    "Too many failed attempts — local account wiped",
                    Toast.LENGTH_LONG).show()
                // doLogout() handles: cancel jobs/listeners, clear all prefs, delete
                // photo files, recreate() into the photo-auth screen. Same teardown
                // as voluntary logout — but here it's forced by N PIN failures.
                doLogout()
                return
            }
            if (newFailures >= 3) {
                val backoffMs = 30_000L * (1L shl (newFailures - 3).coerceAtMost(6))
                edit.putLong(KEY_PIN_LOCKOUT_UNTIL, nowMs + backoffMs)
            }
            edit.apply()
            binding.lockStatus.text = "Wrong PIN ($newFailures/10)"
            binding.pinInput.text?.clear()
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                runOnUiThread { hideLockScreen() }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                runOnUiThread { binding.lockStatus.text = "Biometric error — use PIN" }
            }
            override fun onAuthenticationFailed() {
                runOnUiThread { binding.lockStatus.text = "Not recognized — try again" }
            }
        }
        val prompt = BiometricPrompt(this, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("X-link")
            .setSubtitle("Unlock app")
            .setNegativeButtonText("Use PIN")
            .build()
        prompt.authenticate(info)
    }

    private fun showAppLockSettings() {
        val lockEnabled = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        if (!lockEnabled) {
            // Enable lock: prompt to set PIN first
            showSetPinDialog { pin ->
                val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                prefs.edit()
                    .putString(KEY_APP_PIN_HASH, hashPin(pin, salt))
                    .putString(KEY_APP_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putBoolean(KEY_APP_LOCK_ENABLED, true)
                    .putInt(KEY_PIN_FAILURES, 0)
                    .putLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
                    .apply()
                appUnlocked = true
                updateAppLockButton()
                Toast.makeText(this, "App lock enabled", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Already enabled — offer to disable or change PIN
            val items = arrayOf("Change PIN", "Disable app lock")
            AlertDialog.Builder(this)
                .setTitle("App lock")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> showSetPinDialog { pin ->
                            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                            prefs.edit()
                                .putString(KEY_APP_PIN_HASH, hashPin(pin, salt))
                                .putString(KEY_APP_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                                .putInt(KEY_PIN_FAILURES, 0)
                                .putLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
                                .apply()
                            Toast.makeText(this, "PIN updated", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            prefs.edit()
                                .putBoolean(KEY_APP_LOCK_ENABLED, false)
                                .remove(KEY_APP_PIN_HASH)
                                .remove(KEY_APP_PIN_SALT)
                                .remove(KEY_PIN_FAILURES)
                                .remove(KEY_PIN_LOCKOUT_UNTIL)
                                .apply()
                            appUnlocked = false
                            updateAppLockButton()
                            Toast.makeText(this, "App lock disabled", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        }
    }

    private fun showSetPinDialog(onPinConfirmed: (String) -> Unit) {
        val dp = resources.displayMetrics.density
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        val pin1 = EditText(this).apply {
            hint = "Enter new PIN (4–8 digits)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }
        val pin2 = EditText(this).apply {
            hint = "Confirm PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dp).toInt() }
        }
        container.addView(pin1)
        container.addView(pin2)
        AlertDialog.Builder(this)
            .setTitle("Set PIN")
            .setView(container)
            .setPositiveButton("Set") { _, _ ->
                val p1 = pin1.text.toString()
                val p2 = pin2.text.toString()
                when {
                    p1.length < 4 -> Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                    p1 != p2 -> Toast.makeText(this, "PINs don't match", Toast.LENGTH_SHORT).show()
                    else -> onPinConfirmed(p1)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateAppLockButton() {
        val lockEnabled = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        binding.btnAppLockText.text = if (lockEnabled) "App lock: ON" else "App lock: OFF"
    }

    /**
     * PBKDF2-SHA256 with 600,000 iterations and a per-user random 16-byte salt.
     * OWASP 2023 recommendation. Replaces the previous single-SHA256 hash with
     * a hardcoded "salt" constant — that was crackable in <1 second on a GPU.
     */
    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = javax.crypto.spec.PBEKeySpec(
            pin.toCharArray(), salt, 600_000, 256
        )
        val skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = skf.generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // ─── Backup / Restore ────────────────────────────────────────────────────

    /**
     * Legacy v2 derivation (single SHA-256). Kept for restoring older backup files.
     * v3 backups use PBKDF2 with a per-backup random salt instead.
     */
    private fun deriveBackupAesKeyV2(photoSecret: ByteArray): SecretKey {
        val info = "xlink-backup-v1:".toByteArray(Charsets.UTF_8)
        return SecretKeySpec(sha256(info + photoSecret), "AES")
    }

    /**
     * v3 derivation: PBKDF2-HMAC-SHA256 over the photo secret with a random 16-byte
     * salt + 600k iterations. Per-backup salt means two backups from the same photo
     * yield different ciphertexts; PBKDF2 iterations defeat GPU brute-force.
     */
    private fun deriveBackupAesKeyV3(photoSecret: ByteArray, salt: ByteArray): SecretKey {
        val derived = pbkdf2(photoSecret, salt, BACKUP_PBKDF2_ITERS, 32)
        return SecretKeySpec(derived, "AES")
    }

    private fun exportBackupWithPhotoKey() {
        val secretB64 = prefs.getString(KEY_PHOTO_ACCOUNT_SECRET, null)
        if (secretB64 != null) {
            // Have the photo-derived secret — use it (canonical path).
            pendingBackupPhotoSecret = b64decode(secretB64)
            backupFileLauncher.launch("xlink_backup_${System.currentTimeMillis()}.xlinkbak")
            return
        }
        // Fallback: derive a backup key from a user-supplied password so the
        // user doesn't have to re-do the photo+password login flow just to
        // export. On restore, the same password is required.
        promptBackupPasswordAndExport()
    }

    private fun promptRestoreCredential() {
        AlertDialog.Builder(this)
            .setTitle("Restore backup")
            .setMessage("Was this backup exported with a photo login or with a backup password?")
            .setPositiveButton("Password") { _, _ -> promptRestorePasswordAndImport() }
            .setNegativeButton("Photo") { _, _ ->
                Toast.makeText(this, "Now select your login photo", Toast.LENGTH_LONG).show()
                backupRestorePhotoLauncher.launch("image/*")
            }
            .setNeutralButton("Cancel") { _, _ -> pendingRestoreUri = null }
            .setCancelable(false)
            .show()
    }

    private fun promptRestorePasswordAndImport() {
        val restoreUri = pendingRestoreUri ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Backup password"
        }
        AlertDialog.Builder(this)
            .setTitle("Backup password")
            .setView(input)
            .setPositiveButton("Restore") { _, _ ->
                val pwd = input.text.toString()
                if (pwd.isEmpty()) {
                    Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingRestoreUri = null
                ioScope.launch {
                    val secret = derivePasswordBackupSecret(pwd)
                    doImportBackup(restoreUri, secret)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> pendingRestoreUri = null }
            .show()
    }

    private fun promptBackupPasswordAndExport() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Backup password (remember it!)"
        }
        AlertDialog.Builder(this)
            .setTitle("Backup password")
            .setMessage("This password protects the backup file. You will need to enter it on restore. Photo login is not required.")
            .setView(input)
            .setPositiveButton("Export") { _, _ ->
                val pwd = input.text.toString()
                if (pwd.length < 6) {
                    Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                ioScope.launch {
                    val secret = derivePasswordBackupSecret(pwd)
                    runOnUiThread {
                        pendingBackupPhotoSecret = secret
                        backupFileLauncher.launch("xlink_backup_${System.currentTimeMillis()}.xlinkbak")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * PBKDF2-derive a 32-byte backup key from a user-typed password salted by
     * the device's localId. Same KDF profile as the photo-account secret so a
     * password-derived backup is interchangeable with a photo-derived one on
     * the import path (which already accepts any 32-byte secret).
     */
    private fun derivePasswordBackupSecret(password: String): ByteArray {
        val salt = "$PHOTO_ACCOUNT_KEY_SALT:$localId".toByteArray(Charsets.UTF_8)
        val spec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            salt,
            V2_PBKDF2_ITERS,
            256
        )
        val skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return skf.generateSecret(spec).encoded
    }

    private fun buildBackupJson(): String {
        val contacts = savedContactIds()
        val jsonContacts = JSONArray()
        val jsonChats = JSONArray()
        contacts.forEach { id ->
            jsonContacts.put(JSONObject().put("id", id).put("name", contactName(id)))
            val log = prefs.getString("$KEY_CHAT_LOG_PREFIX$id", "")
            if (!log.isNullOrEmpty()) {
                jsonChats.put(JSONObject().put("id", id).put("log", log))
            }
        }
        return JSONObject()
            .put("v", BACKUP_VERSION)
            .put("ts", System.currentTimeMillis())
            .put("localId", localId)
            .put("contacts", jsonContacts)
            .put("chats", jsonChats)
            .toString()
    }

    private fun restoreFromJson(json: String) {
        val root = JSONObject(json)
        val contacts = root.getJSONArray("contacts")
        val newIds = mutableSetOf<String>()
        val edit = prefs.edit()
        for (i in 0 until contacts.length()) {
            val c = contacts.getJSONObject(i)
            val id = c.getString("id")
            newIds.add(id)
            edit.putString("$KEY_CONTACT_PREFIX$id", c.getString("name"))
        }
        edit.putStringSet(KEY_CONTACT_IDS, savedContactIds() + newIds)

        val chats = root.getJSONArray("chats")
        for (i in 0 until chats.length()) {
            val c = chats.getJSONObject(i)
            val id = c.getString("id")
            val log = c.getString("log")
            edit.putString("$KEY_CHAT_LOG_PREFIX$id", log)
            messageLogs[id] = StringBuilder(log)
        }
        edit.apply()
    }

    private fun doExportBackup(uri: android.net.Uri, photoSecret: ByteArray) {
        ioScope.launch {
            try {
                val plaintext = buildBackupJson().toByteArray(Charsets.UTF_8)
                val iv = ByteArray(BACKUP_IV_BYTES).also { SecureRandom().nextBytes(it) }
                val salt = ByteArray(BACKUP_SALT_BYTES).also { SecureRandom().nextBytes(it) }
                val key = deriveBackupAesKeyV3(photoSecret, salt)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                val ciphertext = cipher.doFinal(plaintext)

                // File format v3: [magic:8][version:1=3][salt:16][iv:12][ciphertext]
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(BACKUP_MAGIC.toByteArray(Charsets.US_ASCII)) // 8 bytes
                    out.write(BACKUP_VERSION)                               // 1 byte (= 3)
                    out.write(salt)                                         // 16 bytes
                    out.write(iv)                                           // 12 bytes
                    out.write(ciphertext)
                }
                runOnUiThread { Toast.makeText(this@MainActivity, "Backup saved", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Log.e(TAG, "Export backup failed: ${e.message}", e)
                runOnUiThread { Toast.makeText(this@MainActivity, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun doImportBackup(uri: android.net.Uri, photoSecret: ByteArray) {
        ioScope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("Cannot open file")

                if (bytes.size < 21) throw Exception("File too small")
                val magic = String(bytes, 0, 8, Charsets.US_ASCII)
                if (magic != BACKUP_MAGIC) throw Exception("Not an xlink backup file")
                val version = bytes[8].toInt() and 0xFF

                val (key, iv, ciphertext) = when (version) {
                    2 -> {
                        // v2: [magic:8][version:1][iv:12][ciphertext]
                        val iv = bytes.copyOfRange(9, 9 + BACKUP_IV_BYTES)
                        val ciphertext = bytes.copyOfRange(9 + BACKUP_IV_BYTES, bytes.size)
                        Triple(deriveBackupAesKeyV2(photoSecret), iv, ciphertext)
                    }
                    3 -> {
                        // v3: [magic:8][version:1][salt:16][iv:12][ciphertext]
                        if (bytes.size < 9 + BACKUP_SALT_BYTES + BACKUP_IV_BYTES) throw Exception("v3 header truncated")
                        val salt = bytes.copyOfRange(9, 9 + BACKUP_SALT_BYTES)
                        val ivOffset = 9 + BACKUP_SALT_BYTES
                        val iv = bytes.copyOfRange(ivOffset, ivOffset + BACKUP_IV_BYTES)
                        val ciphertext = bytes.copyOfRange(ivOffset + BACKUP_IV_BYTES, bytes.size)
                        Triple(deriveBackupAesKeyV3(photoSecret, salt), iv, ciphertext)
                    }
                    else -> throw Exception("Unsupported backup version $version")
                }

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                val plaintext = cipher.doFinal(ciphertext) // AEADBadTagException = wrong photo

                runOnUiThread {
                    restoreFromJson(plaintext.toString(Charsets.UTF_8))
                    renderContacts()
                    Toast.makeText(this@MainActivity, "Chats restored successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: AEADBadTagException) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Wrong photo — backup not decrypted", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                Log.e(TAG, "Import backup failed: ${e.message}", e)
                runOnUiThread { Toast.makeText(this@MainActivity, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ─── End Backup / Restore ────────────────────────────────────────────────

    // ─── In-app update ───────────────────────────────────────────────────────

    private data class ReleaseInfo(
        val tagName: String,
        val apkUrl: String,
        val releaseNotes: String
    )

    /** Fetch latest stable release. Returns null if repo not configured. */
    private fun fetchLatestRelease(): ReleaseInfo? {
        if (GITHUB_OWNER.isBlank() || GITHUB_REPO.isBlank()) return null
        val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
        return try {
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "XxxLink-UpdateChecker")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseRelease(org.json.JSONObject(body))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Fetch latest prerelease (beta) from the releases list.
     * Returns null if none found or repo not configured.
     */
    private fun fetchLatestPrerelease(): ReleaseInfo? {
        if (GITHUB_OWNER.isBlank() || GITHUB_REPO.isBlank()) return null
        val listUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=20"
        val conn = URL(listUrl).openConnection() as HttpURLConnection
        return try {
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "XxxLink-UpdateChecker")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = org.json.JSONArray(body)
            // "Check beta" returns whichever release has the highest semantic
            // version, prerelease or stable. Without including stables, a beta
            // user installed on 1.14.23-beta would never see the 1.15.x stable
            // line via the beta button. The "Check for updates" stable button
            // still uses /releases/latest which excludes prereleases — so the
            // two buttons remain semantically distinct.
            var best: ReleaseInfo? = null
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optBoolean("draft", false)) continue
                val candidate = runCatching { parseRelease(obj) }.getOrNull() ?: continue
                if (best == null || isNewerVersion(candidate.tagName, best.tagName)) {
                    best = candidate
                }
            }
            best
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRelease(json: org.json.JSONObject): ReleaseInfo {
        val tag    = json.getString("tag_name")
        val notes  = json.optString("body", "").take(300)
        val assets = json.getJSONArray("assets")
        var apkUrl = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.getString("browser_download_url"); break
            }
        }
        if (apkUrl.isBlank()) error("No APK asset in release $tag")
        return ReleaseInfo(tag, apkUrl, notes)
    }

    /** Returns true if `latest` version string is newer than `current`. */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        // Split on both "." and "-" so "v1.3-beta.1" → [1,3,1] instead of [1,1] (which "3-beta" skips)
        val normalize = { v: String -> v.trimStart('v').split(Regex("[.\\-]")).mapNotNull { it.toIntOrNull() } }
        val l = normalize(latest);  val c = normalize(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 };  val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }

    /** Check GitHub for a newer release; show dialog if found. */
    private fun checkForUpdates(fromUser: Boolean = true) {
        if (GITHUB_OWNER.isBlank() || GITHUB_REPO.isBlank()) {
            if (fromUser) Toast.makeText(this,
                "GitHub repo not configured (GITHUB_OWNER / GITHUB_REPO)", Toast.LENGTH_LONG).show()
            return
        }
        ioScope.launch {
            val info = runCatching { fetchLatestRelease() }.getOrElse { e ->
                if (fromUser) runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "Update check failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                return@launch
            } ?: return@launch
            val current = BuildConfig.VERSION_NAME
            if (!isNewerVersion(info.tagName, current)) {
                if (fromUser) runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "Up to date (v$current)", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            runOnUiThread { showUpdateDialog(info, current) }
        }
    }

    /** Check GitHub for a newer prerelease (beta); show dialog if found. */
    private fun checkForBeta() {
        if (GITHUB_OWNER.isBlank() || GITHUB_REPO.isBlank()) {
            Toast.makeText(this,
                "GitHub repo not configured (GITHUB_OWNER / GITHUB_REPO)", Toast.LENGTH_LONG).show()
            return
        }
        ioScope.launch {
            val info = runCatching { fetchLatestPrerelease() }.getOrElse { e ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "Beta check failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (info == null) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "No beta releases found", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val current = BuildConfig.VERSION_NAME
            if (!isNewerVersion(info.tagName, current)) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "Already on latest beta or newer (v$current)", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            runOnUiThread { showBetaDialog(info, current) }
        }
    }

    private fun showBetaDialog(info: ReleaseInfo, current: String) {
        val msg = "Beta: ${info.tagName}  /  Current: v$current" +
            if (info.releaseNotes.isNotBlank()) "\n\n${info.releaseNotes}" else ""
        AlertDialog.Builder(this)
            .setTitle("Beta update available")
            .setMessage(msg)
            .setPositiveButton("Download & Install") { _, _ -> downloadAndInstall(info) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showUpdateDialog(info: ReleaseInfo, current: String) {
        val msg = "New: ${info.tagName}  /  Current: v$current" +
            if (info.releaseNotes.isNotBlank()) "\n\n${info.releaseNotes}" else ""
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage(msg)
            .setPositiveButton("Download & Install") { _, _ -> downloadAndInstall(info) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(info: ReleaseInfo) {
        // Check install-unknown-apps permission (API 26+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this)
                    .setTitle("Permission required")
                    .setMessage("Allow installing apps from unknown sources to apply updates.")
                    .setPositiveButton("Open settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName"))
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }

        // Build progress dialog
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            setPadding(60, 20, 60, 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Downloading ${info.tagName}…")
            .setView(progress)
            .setCancelable(false)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()

        val job = ioScope.launch {
            try {
                val dir = (getExternalFilesDir(null)?.let { File(it, "updates") }
                    ?: File(filesDir, "updates")).also { it.mkdirs() }
                val dest = File(dir, "update_${info.tagName}.apk")

                downloadApk(info.apkUrl, dest, isCancelled = { !isActive }) { pct ->
                    runOnUiThread {
                        if (pct >= 0) {
                            progress.isIndeterminate = false
                            progress.max = 100
                            progress.progress = pct
                            dialog.setTitle("Downloading ${info.tagName}… $pct%")
                        }
                    }
                }

                if (!isActive) return@launch
                runOnUiThread {
                    dialog.dismiss()
                    triggerInstall(dest)
                }
            } catch (e: CancellationException) {
                runOnUiThread { dialog.dismiss() }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Update download failed: ${e.message}", e)
                runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity,
                        "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        // Cancel button must actually cancel the download. Previously it just
        // dismissed the dialog while the launch coroutine kept running and
        // then triggered the installer after the user said "Cancel".
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel") { _, _ ->
            job.cancel()
            dialog.dismiss()
            Toast.makeText(this, "Update cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Downloads [apkUrl] to [dest], following HTTP redirects manually.
     * [onProgress] called with 0-100 when Content-Length is known, -1 otherwise.
     */
    private fun downloadApk(
        apkUrl: String,
        dest: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (Int) -> Unit
    ) {
        var targetUrl = apkUrl
        repeat(8) { // max redirects
            val conn = URL(targetUrl).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout    = 60_000
                conn.connect()
                val code = conn.responseCode
                if (code in 301..308) {
                    targetUrl = conn.getHeaderField("Location")
                        ?: error("Redirect with no Location header")
                    return@repeat
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        var downloaded = 0L
                        val buf = ByteArray(16_384)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            // Honour cancellation — long downloads must
                            // terminate when the user hits Cancel.
                            if (isCancelled()) {
                                dest.delete()
                                throw CancellationException("Download cancelled")
                            }
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress(if (total > 0) ((downloaded * 100) / total).toInt() else -1)
                        }
                    }
                }
                return // success
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        error("Too many redirects for $apkUrl")
    }

    private fun triggerInstall(apkFile: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ─── End In-app update ───────────────────────────────────────────────────

    // ─── Photo Transfer ───────────────────────────────────────────────────────

    private suspend fun prepareAndSendPhoto(uri: android.net.Uri) {
        Log.d(TAG, "XLINK_PHOTO prepareAndSendPhoto start uri=$uri remoteId='$remoteId'")
        val chatId = remoteId.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "XLINK_PHOTO abort: no active chat")
            runOnUiThread { Toast.makeText(this, "No active chat", Toast.LENGTH_SHORT).show() }
            return
        }
        runOnUiThread { Toast.makeText(this, "Preparing photo…", Toast.LENGTH_SHORT).show() }

        val original = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        if (original == null) {
            Log.w(TAG, "XLINK_PHOTO abort: BitmapFactory returned null for uri=$uri")
            runOnUiThread { Toast.makeText(this, "Cannot read or decode photo", Toast.LENGTH_SHORT).show() }
            return
        }
        Log.d(TAG, "XLINK_PHOTO decoded original=${original.width}x${original.height}")
        val maxSide = 1200
        val scaled = if (original.width > maxSide || original.height > maxSide) {
            val ratio = minOf(maxSide.toFloat() / original.width, maxSide.toFloat() / original.height)
            Bitmap.createScaledBitmap(original, (original.width * ratio).toInt(), (original.height * ratio).toInt(), true)
        } else original
        val baos = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, baos)
        val photoBytes = baos.toByteArray()
        // Recycle bitmaps now that raw bytes are captured
        if (scaled !== original) scaled.recycle()
        original.recycle()

        val recipientKeyB64 = runCatching {
            db?.collection("users")?.document(chatId)?.get()?.await()
                ?.getString("messagePublicKey")
        }.getOrNull()

        if (recipientKeyB64.isNullOrBlank()) {
            Log.w(TAG, "XLINK_PHOTO abort: no messagePublicKey for chatId=$chatId")
            runOnUiThread { Toast.makeText(this, "Cannot get contact's encryption key", Toast.LENGTH_SHORT).show() }
            return
        }
        Log.d(TAG, "XLINK_PHOTO recipient key fetched (${recipientKeyB64.length} chars)")

        val aesKey = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, aesKey, javax.crypto.spec.GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(photoBytes)

        val wrappedKey = runCatching {
            val kf = java.security.KeyFactory.getInstance("EC")
            val recipientPub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(b64decode(recipientKeyB64)))
            val ephemKpg = java.security.KeyPairGenerator.getInstance("EC")
            ephemKpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            val ephemKp = ephemKpg.generateKeyPair()
            val ka = javax.crypto.KeyAgreement.getInstance("ECDH")
            ka.init(ephemKp.private)
            ka.doPhase(recipientPub, true)
            val wrapAes = javax.crypto.spec.SecretKeySpec(sha256(ka.generateSecret()), "AES")
            val wrapIv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val wrapCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            wrapCipher.init(javax.crypto.Cipher.ENCRYPT_MODE, wrapAes, javax.crypto.spec.GCMParameterSpec(128, wrapIv))
            val wrappedKeyBytes = wrapCipher.doFinal(aesKey.encoded)
            "${b64(ephemKp.public.encoded)}:${b64(wrapIv)}:${b64(wrappedKeyBytes)}"
        }.getOrElse { e ->
            runOnUiThread { Toast.makeText(this, "Encryption failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            return
        }

        val encKeyB64 = wrappedKey
        val ivB64 = b64(iv)

        val chunkSize = 12_000
        val chunks = (0 until encrypted.size step chunkSize).map { i ->
            encrypted.copyOfRange(i, minOf(i + chunkSize, encrypted.size))
        }

        if (photoTransferPc != null || photoTransferDc != null) {
            Log.w(TAG, "XLINK_PHOTO clearing stale photo peer before starting a new transfer")
            cleanupPhotoTransfer()
        }

        val transferId = "$localId-photo-${System.currentTimeMillis()}"
        outgoingPhotoTransferId = transferId
        outgoingPhotoChatId = chatId
        outgoingPhotoCompletionHandled = false

        // Save photo locally for sender's chat view. Render as PENDING — the
        // bubble is rewritten to [PHOTO:..] on success or [PHOTO_FAILED:..] on abort.
        val senderPhotoDir = File(filesDir, "photos/$chatId").also { it.mkdirs() }
        val senderPhotoFile = File(senderPhotoDir, "$transferId.jpg")
        senderPhotoFile.writeBytes(photoBytes)
        runOnUiThread {
            appendMessage(chatId, "Me", "[PHOTO_PENDING:${senderPhotoFile.absolutePath}|$transferId]")
        }

        runOnUiThread { Toast.makeText(this, "Connecting to send photo…", Toast.LENGTH_SHORT).show() }

        val firestore = db ?: run {
            runOnUiThread { Toast.makeText(this, "Not connected to server", Toast.LENGTH_SHORT).show() }
            return
        }

        val setupOk = withContext(Dispatchers.Main) { setupPhotoTransferPc(chatId) }
        if (!setupOk) {
            runOnUiThread { rewriteChatLogPhotoEntry(chatId, transferId, success = false) }
            return
        }
        android.os.Handler(mainLooper).postDelayed({
            photoTransferPc?.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) return
                    photoTransferPc?.setLocalDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            Log.d(TAG, "XLINK_PHOTO writing transfer doc tid=$transferId offerLen=${desc.description.length}")
                            firestore.collection("transfers").document(transferId).set(
                                mapOf(
                                    "transferId" to transferId,
                                    "senderId" to localId,
                                    "receiverId" to chatId,
                                    "offer" to desc.description,
                                    "state" to "pending",
                                    "createdAt" to System.currentTimeMillis()
                                )
                            )
                                .addOnSuccessListener {
                                    Log.d(TAG, "XLINK_PHOTO transfer doc write OK tid=$transferId")
                                    listenPhotoTransferAnswer(transferId, chatId, chunks, encKeyB64, ivB64)
                                }
                                .addOnFailureListener { e ->
                                    Log.w(TAG, "XLINK_PHOTO transfer offer write failed: ${e.message}")
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, "Photo transfer setup failed", Toast.LENGTH_SHORT).show()
                                    }
                                    cleanupPhotoTransfer()
                                }
                        }
                        override fun onSetFailure(error: String?) {
                            runOnUiThread { Toast.makeText(this@MainActivity, "Photo transfer setup failed: $error", Toast.LENGTH_SHORT).show() }
                            cleanupPhotoTransfer()
                        }
                    }, desc)
                }
                override fun onCreateFailure(error: String?) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Photo offer failed: $error", Toast.LENGTH_SHORT).show() }
                }
            }, MediaConstraints())
        }, 200)
    }

    private fun setupPhotoTransferPc(chatId: String): Boolean {
        val observer = object : PeerConnection.Observer {
            override fun onDataChannel(dc: DataChannel) {
                photoTransferDc = dc
                setupPhotoReceiveChannel(dc)
            }
            override fun onIceCandidate(cand: IceCandidate) {
                val tid = outgoingPhotoTransferId ?: return
                db?.collection("transfers")?.document(tid)
                    ?.collection("candidates")
                    ?.add(mapOf(
                        "sender" to localId,
                        "sdpMid" to cand.sdpMid,
                        "sdpMLineIndex" to cand.sdpMLineIndex,
                        "candidate" to cand.sdp
                    ))
                    ?.addOnFailureListener { e -> Log.w(TAG, "photo candidate write failed: ${e.message}") }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                if (state == PeerConnection.PeerConnectionState.FAILED ||
                    state == PeerConnection.PeerConnectionState.CLOSED) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Photo transfer connection lost", Toast.LENGTH_SHORT).show()
                        cleanupPhotoTransfer()
                    }
                }
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onRenegotiationNeeded() {}
        }
        photoTransferPc = createPhotoPeerConnection("setupPhotoTransferPc", observer) ?: run {
            Log.e(TAG, "setupPhotoTransferPc: createPeerConnection returned null")
            runOnUiThread { Toast.makeText(this, "Photo connection setup failed", Toast.LENGTH_SHORT).show() }
            return false
        }

        val dcInit = DataChannel.Init().apply { ordered = true }
        val safePc = photoTransferPc ?: return false  // already null-guarded above, but avoid !!
        photoTransferDc = safePc.createDataChannel("photo", dcInit) ?: run {
            Log.e(TAG, "setupPhotoTransferPc: createDataChannel(photo) returned null")
            runOnUiThread { Toast.makeText(this, "Photo channel setup failed", Toast.LENGTH_SHORT).show() }
            cleanupPhotoTransfer()
            return false
        }
        return true
    }

    private fun listenPhotoTransferAnswer(
        transferId: String,
        chatId: String,
        chunks: List<ByteArray>,
        encKeyB64: String,
        ivB64: String
    ) {
        val firestore = db ?: return
        // Sender-side answer timeout: if the receiver never moves the doc out
        // of 'pending' within PHOTO_ANSWER_TIMEOUT_MS (peer offline / app
        // closed / busy on stale transfer), bail and mark the doc 'expired'
        // so the local PC is torn down. Without this, photoTransferPc stays
        // alive indefinitely and every subsequent incoming offer from the
        // same peer is rejected as busy.
        val timeoutHandler = android.os.Handler(mainLooper)
        val timeoutTask = Runnable {
            if (outgoingPhotoTransferId != transferId) return@Runnable
            Log.w(TAG, "XLINK_PHOTO answer timeout tid=$transferId — no reply within ${PHOTO_ANSWER_TIMEOUT_MS}ms")
            firestore.collection("transfers").document(transferId)
                .update("state", "expired")
                .addOnFailureListener { e -> Log.w(TAG, "XLINK_PHOTO expired write failed: ${e.message}") }
            runOnUiThread {
                Toast.makeText(this, "Photo not delivered (recipient offline)", Toast.LENGTH_SHORT).show()
                rewriteChatLogPhotoEntry(chatId, transferId, success = false)
            }
            cleanupPhotoTransfer()
        }
        timeoutHandler.postDelayed(timeoutTask, PHOTO_ANSWER_TIMEOUT_MS)
        val listener = firestore.collection("transfers").document(transferId)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.w(TAG, "photo transfer answer listener failed: ${error.message}")
                    return@addSnapshotListener
                }
                val state = snap?.getString("state") ?: return@addSnapshotListener
                when (state) {
                    "rejected" -> {
                        timeoutHandler.removeCallbacks(timeoutTask)
                        runOnUiThread {
                            Toast.makeText(this, "Recipient declined the photo", Toast.LENGTH_SHORT).show()
                            rewriteChatLogPhotoEntry(chatId, transferId, success = false)
                        }
                        cleanupPhotoTransfer()
                        return@addSnapshotListener
                    }
                    "rejected_busy" -> {
                        timeoutHandler.removeCallbacks(timeoutTask)
                        runOnUiThread {
                            Toast.makeText(this, "Recipient is busy with another transfer", Toast.LENGTH_SHORT).show()
                            rewriteChatLogPhotoEntry(chatId, transferId, success = false)
                        }
                        cleanupPhotoTransfer()
                        return@addSnapshotListener
                    }
                    "expired", "failed" -> {
                        timeoutHandler.removeCallbacks(timeoutTask)
                        runOnUiThread {
                            Toast.makeText(this, "Photo transfer was aborted", Toast.LENGTH_SHORT).show()
                            rewriteChatLogPhotoEntry(chatId, transferId, success = false)
                        }
                        cleanupPhotoTransfer()
                        return@addSnapshotListener
                    }
                    "accepted" -> { timeoutHandler.removeCallbacks(timeoutTask) }
                    else -> return@addSnapshotListener
                }
                val answer = snap.getString("answer") ?: return@addSnapshotListener
                if (photoTransferPc == null) return@addSnapshotListener

                photoTransferPc?.setRemoteDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        listenPhotoTransferCandidates(transferId)
                        waitForPhotoChannelAndSend(transferId, chatId, chunks, encKeyB64, ivB64)
                    }
                    override fun onSetFailure(error: String?) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Photo answer rejected: $error", Toast.LENGTH_SHORT).show() }
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, answer))
            }
        // Use separate list — not shared with call listeners (removeListeners() would kill this)
        photoTransferListeners.add(listener)
    }

    private fun listenPhotoTransferCandidates(transferId: String) {
        val firestore = db ?: return
        // Both sides exclude their OWN candidates and add only the REMOTE peer's.
        val excludeSender = localId
        val listener = firestore.collection("transfers").document(transferId)
            .collection("candidates")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.w(TAG, "photo candidates listener failed: ${error.message}")
                    return@addSnapshotListener
                }
                snap?.documentChanges?.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val doc = change.document
                        if (doc.getString("sender") == excludeSender) return@forEach
                        val cand = IceCandidate(
                            doc.getString("sdpMid") ?: return@forEach,
                            doc.getLong("sdpMLineIndex")?.toInt() ?: return@forEach,
                            doc.getString("candidate") ?: return@forEach
                        )
                        photoTransferPc?.addIceCandidate(cand)
                    }
                }
            }
        photoTransferListeners.add(listener)
    }

    private fun waitForPhotoChannelAndSend(
        transferId: String,
        chatId: String,
        chunks: List<ByteArray>,
        encKeyB64: String,
        ivB64: String
    ) {
        val dc = photoTransferDc ?: return
        val startTime = System.currentTimeMillis()

        fun tryOpen() {
            if (dc.state() == DataChannel.State.OPEN) {
                if (!photoSendInProgress) {
                    photoSendInProgress = true
                    sendPhotoOverChannel(dc, transferId, chatId, chunks, encKeyB64, ivB64)
                }
                return
            }
            if (System.currentTimeMillis() - startTime > 30_000) {
                runOnUiThread { Toast.makeText(this, "Photo transfer timed out — contact may be offline", Toast.LENGTH_LONG).show() }
                cleanupPhotoTransfer()
                return
            }
            android.os.Handler(mainLooper).postDelayed({ tryOpen() }, 500)
        }

        dc.registerObserver(object : DataChannel.Observer {
            override fun onStateChange() {
                if (dc.state() == DataChannel.State.OPEN) {
                    runOnUiThread {
                        if (!photoSendInProgress) {
                            photoSendInProgress = true
                            sendPhotoOverChannel(dc, transferId, chatId, chunks, encKeyB64, ivB64)
                        }
                    }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                val packet = bytes.toString(Charsets.UTF_8)
                runOnUiThread { handleOutgoingPhotoControlPacket(packet, transferId, chatId) }
            }
            override fun onBufferedAmountChange(p0: Long) {}
        })
        android.os.Handler(mainLooper).postDelayed({ tryOpen() }, 1000)
    }

    private fun handleOutgoingPhotoControlPacket(packet: String, transferId: String, chatId: String) {
        when {
            packet == "PHO_ACK|$transferId" -> {
                completeOutgoingPhotoTransfer(
                    chatId = chatId,
                    transferId = transferId,
                    success = true,
                    toast = "Photo received"
                )
            }
            packet.startsWith("PHO_NACK|$transferId") -> {
                completeOutgoingPhotoTransfer(
                    chatId = chatId,
                    transferId = transferId,
                    success = false,
                    toast = "Photo was not received"
                )
            }
        }
    }

    private fun completeOutgoingPhotoTransfer(
        chatId: String,
        transferId: String,
        success: Boolean,
        toast: String
    ) {
        val shouldComplete = synchronized(photoTransferLock) {
            if (outgoingPhotoTransferId != transferId || outgoingPhotoCompletionHandled) {
                false
            } else {
                outgoingPhotoCompletionHandled = true
                true
            }
        }
        if (!shouldComplete) return

        runOnUiThread {
            Toast.makeText(this@MainActivity, toast, Toast.LENGTH_SHORT).show()
            rewriteChatLogPhotoEntry(chatId, transferId, success)
        }

        db?.collection("transfers")?.document(transferId)?.update(
            "state",
            if (success) "done" else "failed"
        )?.addOnFailureListener { e -> Log.w(TAG, "photo transfer final state update failed: ${e.message}") }

        cleanupPhotoTransfer()
    }

    private fun sendPhotoOverChannel(
        dc: DataChannel,
        transferId: String,
        chatId: String,
        chunks: List<ByteArray>,
        encKeyB64: String,
        ivB64: String
    ) {
        ioScope.launch {
            try {
                // Protocol-clean PHO_START: 5 fields, no chatId (receiver knows senderId
                // from Firestore doc + sets outgoingPhotoChatId in proceedWithPhotoOffer).
                // Old receivers still accept the 6-field variant; new receivers handle both.
                val startPacket = "PHO_START|$transferId|${chunks.size}|$encKeyB64|$ivB64"
                sendPhotoPacket(dc, startPacket)
                chunks.forEachIndexed { index, chunk ->
                    val chunkPacket = "PHO_CHUNK|$transferId|$index|${b64(chunk)}"
                    sendPhotoPacket(dc, chunkPacket)
                    delay(10)
                }
                sendPhotoPacket(dc, "PHO_END|$transferId")

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Photo sent, waiting for receipt", Toast.LENGTH_SHORT).show()
                }

                android.os.Handler(mainLooper).postDelayed({
                    completeOutgoingPhotoTransfer(
                        chatId = chatId,
                        transferId = transferId,
                        success = false,
                        toast = "Photo was not confirmed"
                    )
                }, PHOTO_DELIVERY_ACK_TIMEOUT_MS)

            } catch (e: Exception) {
                // Send an explicit abort packet so receiver tears down immediately
                // instead of relying on the watchdog timeout.
                runCatching { dc.send(DataChannel.Buffer(
                    java.nio.ByteBuffer.wrap("PHO_ABORT|$transferId".toByteArray(Charsets.UTF_8)),
                    false
                )) }
                completeOutgoingPhotoTransfer(
                    chatId = chatId,
                    transferId = transferId,
                    success = false,
                    toast = "Photo send error: ${e.message}"
                )
            }
        }
    }

    private suspend fun sendPhotoPacket(dc: DataChannel, packet: String) {
        var waitedMs = 0L
        while (dc.bufferedAmount() > PHOTO_CHANNEL_MAX_BUFFERED_AMOUNT_BYTES) {
            if (dc.state() != DataChannel.State.OPEN) {
                error("photo channel closed")
            }
            if (waitedMs >= PHOTO_CHANNEL_BUFFER_TIMEOUT_MS) {
                error("photo channel buffer timeout")
            }
            delay(PHOTO_CHANNEL_BUFFER_POLL_MS)
            waitedMs += PHOTO_CHANNEL_BUFFER_POLL_MS
        }
        if (dc.state() != DataChannel.State.OPEN) {
            error("photo channel closed")
        }
        val bytes = packet.toByteArray(Charsets.UTF_8)
        if (!dc.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), false))) {
            error("photo channel send failed")
        }
    }

    private fun listenIncomingPhotoTransfers() {
        val firestore = db ?: return
        incomingTransferListener?.remove()
        incomingTransferListener = firestore.collection("transfers")
            .whereEqualTo("receiverId", localId)
            .whereEqualTo("state", "pending")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.w(TAG, "incoming photo transfer listener failed: ${error.message}")
                    return@addSnapshotListener
                }
                snap?.documentChanges?.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val doc = change.document
                        val offer = doc.getString("offer") ?: return@forEach
                        val transferId = doc.getString("transferId") ?: doc.id
                        val senderId = doc.getString("senderId") ?: return@forEach
                        // Skip stale offers by absolute age. A valid offer can be created
                        // before the receiver opens the app, so app start time is not safe here.
                        val createdAt = doc.getLong("createdAt") ?: 0L
                        val ageMs = System.currentTimeMillis() - createdAt
                        if (createdAt <= 0L || ageMs > PHOTO_OFFER_TTL_MS) {
                            Log.d(TAG, "Skipping stale photo offer tid=$transferId createdAt=$createdAt")
                            // Best-effort cleanup so it doesn't keep replaying on every start.
                            firestore.collection("transfers").document(doc.id)
                                .update("state", "expired")
                                .addOnFailureListener { _ -> /* may have no perms; ignore */ }
                            return@forEach
                        }
                        acceptIncomingPhotoTransfer(doc.id, transferId, senderId, offer)
                    }
                }
            }
    }

    private fun acceptIncomingPhotoTransfer(docId: String, transferId: String, senderId: String, offer: String) {
        val firestore = db ?: return
        val chatId = senderId

        // Reject-busy: don't silently drop the offer when we're already in another
        // transfer. Mark the doc rejected so the sender gets immediate feedback.
        if (photoTransferPc != null) {
            firestore.collection("transfers").document(docId)
                .update("state", "rejected_busy")
                .addOnFailureListener { e -> Log.w(TAG, "rejected_busy write failed: ${e.message}") }
            Log.d(TAG, "Photo transfer $transferId from $senderId rejected: busy with another transfer")
            return
        }

        // Whitelist: saved contacts and existing chats auto-accept; unknown senders prompt for consent.
        if (!shouldAutoAcceptPhotoFrom(senderId)) {
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Photo from unknown sender")
                    .setMessage("Receive a photo from $senderId?")
                    .setPositiveButton("Accept") { _, _ ->
                        proceedWithPhotoOffer(firestore, docId, transferId, senderId, offer)
                    }
                    .setNegativeButton("Reject") { _, _ ->
                        firestore.collection("transfers").document(docId)
                            .update("state", "rejected")
                            .addOnFailureListener { e -> Log.w(TAG, "rejected write failed: ${e.message}") }
                    }
                    .setCancelable(false)
                    .show()
            }
            return
        }

        proceedWithPhotoOffer(firestore, docId, transferId, senderId, offer)
    }

    private fun proceedWithPhotoOffer(
        firestore: FirebaseFirestore,
        docId: String,
        transferId: String,
        senderId: String,
        offer: String
    ) {
        val chatId = senderId
        outgoingPhotoTransferId = transferId
        outgoingPhotoChatId = chatId
        // Arm the inactivity watchdog right after we commit to receive — covers
        // every path: peer drops before sending data, mid-transfer freeze, etc.
        armReceiveWatchdog()

        runOnUiThread {
            if (!setupPhotoTransferPcReceiver(transferId)) {
                firestore.collection("transfers").document(docId)
                    .update("state", "failed")
                    .addOnFailureListener { e -> Log.w(TAG, "photo receiver setup failed state write failed: ${e.message}") }
                cleanupPhotoTransfer()
                return@runOnUiThread
            }
            photoTransferPc?.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    photoTransferPc?.createAnswer(object : SdpObserverAdapter() {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            if (desc == null) return
                            photoTransferPc?.setLocalDescription(object : SdpObserverAdapter() {
                                override fun onSetSuccess() {
                                    firestore.collection("transfers").document(docId).update(
                                        mapOf("answer" to desc.description, "state" to "accepted")
                                    )
                                        .addOnSuccessListener { listenPhotoTransferCandidates(docId) }
                                        .addOnFailureListener { e ->
                                            Log.w(TAG, "photo transfer answer write failed: ${e.message}")
                                            cleanupPhotoTransfer()
                                        }
                                }
                                override fun onSetFailure(error: String?) {
                                    Log.w(TAG, "photo answer local description failed: $error")
                                    cleanupPhotoTransfer()
                                }
                            }, desc)
                        }
                        override fun onCreateFailure(error: String?) {
                            Log.w(TAG, "photo answer create failed: $error")
                            cleanupPhotoTransfer()
                        }
                    }, MediaConstraints())
                }
                override fun onSetFailure(error: String?) {
                    Log.w(TAG, "photo offer remote description failed: $error")
                    cleanupPhotoTransfer()
                }
            }, SessionDescription(SessionDescription.Type.OFFER, offer))
        }
    }

    private fun setupPhotoTransferPcReceiver(transferId: String): Boolean {
        val observer = object : PeerConnection.Observer {
            override fun onDataChannel(dc: DataChannel) {
                photoTransferDc = dc
                setupPhotoReceiveChannel(dc)
            }
            override fun onIceCandidate(cand: IceCandidate) {
                db?.collection("transfers")?.document(transferId)
                    ?.collection("candidates")
                    ?.add(mapOf(
                        "sender" to localId,
                        "sdpMid" to cand.sdpMid,
                        "sdpMLineIndex" to cand.sdpMLineIndex,
                        "candidate" to cand.sdp
                    ))
                    ?.addOnFailureListener { e -> Log.w(TAG, "photo receiver candidate write failed: ${e.message}") }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                if (state == PeerConnection.PeerConnectionState.FAILED ||
                    state == PeerConnection.PeerConnectionState.CLOSED) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Photo transfer connection lost", Toast.LENGTH_SHORT).show()
                        cleanupPhotoTransfer()
                    }
                }
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onRenegotiationNeeded() {}
        }
        photoTransferPc = createPhotoPeerConnection("setupPhotoTransferPcReceiver", observer) ?: run {
            Log.e(TAG, "setupPhotoTransferPcReceiver: createPeerConnection returned null")
            runOnUiThread { Toast.makeText(this, "Photo connection setup failed", Toast.LENGTH_SHORT).show() }
            return false
        }
        return true
    }

    private fun setupPhotoReceiveChannel(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                // Copy data here (ByteBuffer becomes invalid after callback returns)
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                val packet = bytes.toString(Charsets.UTF_8)
                // Dispatch to main: keeps all assembly state and cleanupPhotoTransfer on main thread
                // (dispose() from WebRTC callback thread = deadlock risk)
                runOnUiThread { handlePhotoPacket(packet) }
            }
            override fun onStateChange() {}
            override fun onBufferedAmountChange(p0: Long) {}
        })
    }

    private fun sendPhotoControlPacket(packet: String) {
        val dc = photoTransferDc ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        runCatching {
            dc.send(
                DataChannel.Buffer(
                    java.nio.ByteBuffer.wrap(packet.toByteArray(Charsets.UTF_8)),
                    false
                )
            )
        }.onFailure { e ->
            Log.w(TAG, "photo control packet send failed: ${e.message}")
        }
    }

    private fun handlePhotoPacket(packet: String) {
        when {
            packet.startsWith("PHO_START|") -> {
                val parts = packet.split("|")
                // Accept both v1 (6 fields with chatId) and v2 (5 fields, no chatId).
                // chatId is unused — we trust outgoingPhotoChatId set by acceptIncoming.
                if (parts.size < 5) return
                val transferId = parts[1]
                val totalChunks = parts[2].toIntOrNull() ?: return
                val encKeyB64 = parts[3]
                val ivB64 = parts[4]
                val chatId = parts.getOrNull(5) ?: ""

                // Guard against attacker-controlled OOM: cap chunks at a sane upper bound.
                // 4096 chunks × ~12KB ≈ 48 MB max photo. Photos larger than this are rejected.
                if (totalChunks <= 0 || totalChunks > 4096) {
                    Log.w(TAG, "PHO_START rejected — invalid totalChunks=$totalChunks")
                    return
                }
                // Guard against PHO_START clobbering an in-flight assembly. Receiver-side
                // photoTransferPc gate (setupPhotoTransferPcReceiver) usually prevents this,
                // but defend in depth.
                if (assemblingTransferId != null && assemblingTransferId != transferId) {
                    Log.w(TAG, "PHO_START while assembling ${assemblingTransferId} — ignoring new transfer $transferId")
                    return
                }

                assemblingTransferId = transferId
                assemblingChatId = chatId
                assemblingExpected = totalChunks
                assemblingChunks = arrayOfNulls(totalChunks)
                assemblingReceived = 0
                // Reset watchdog from "arm" state (set in proceedWithPhotoOffer)
                // to "active": next 90s of silence aborts.
                armReceiveWatchdog()
                val keyParts = encKeyB64.split(":")
                if (keyParts.size != 3) return
                val aesKeyBytes = runCatching {
                    val kp = myEcKeyPair ?: return
                    val kf = java.security.KeyFactory.getInstance("EC")
                    val ephemPub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(b64decode(keyParts[0])))
                    val ka = javax.crypto.KeyAgreement.getInstance("ECDH")
                    ka.init(kp.private)
                    ka.doPhase(ephemPub, true)
                    val wrapAes = javax.crypto.spec.SecretKeySpec(sha256(ka.generateSecret()), "AES")
                    val wrapIv = b64decode(keyParts[1])
                    val wrapCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    wrapCipher.init(javax.crypto.Cipher.DECRYPT_MODE, wrapAes, javax.crypto.spec.GCMParameterSpec(128, wrapIv))
                    wrapCipher.doFinal(b64decode(keyParts[2]))
                }.getOrNull() ?: return

                assemblingKey = aesKeyBytes
                assemblingIv = b64decode(ivB64)
            }

            packet.startsWith("PHO_CHUNK|") -> {
                val parts = packet.split("|")
                if (parts.size < 4) return
                val transferId = parts[1]
                if (transferId != assemblingTransferId) return
                val index = parts[2].toIntOrNull() ?: return
                val data = runCatching { b64decode(parts[3]) }.getOrNull() ?: return
                val arr = assemblingChunks ?: return
                // Bounds check + dedup: duplicate index would double-count assemblingReceived
                // and falsely satisfy assemblingReceived == assemblingExpected with a hole.
                if (index in 0 until arr.size && arr[index] == null) {
                    arr[index] = data
                    assemblingReceived++
                }
                // Reset the watchdog — peer is alive.
                armReceiveWatchdog()
            }

            packet.startsWith("PHO_ABORT|") -> {
                val parts = packet.split("|")
                if (parts.size < 2) return
                val transferId = parts[1]
                if (transferId != assemblingTransferId) return
                Log.d(TAG, "PHO_ABORT received tid=$transferId")
                runOnUiThread {
                    Toast.makeText(this, "Sender aborted the transfer", Toast.LENGTH_SHORT).show()
                }
                cleanupPhotoTransfer()
            }

            packet.startsWith("PHO_END|") -> {
                val parts = packet.split("|")
                if (parts.size < 2) return
                val transferId = parts[1]
                if (transferId != assemblingTransferId) return

                val chunks = assemblingChunks ?: return
                val key = assemblingKey ?: return
                val iv = assemblingIv ?: return
                // Use outgoingPhotoChatId (= senderId, set by acceptIncomingPhotoTransfer).
                // assemblingChatId contains the sender's remoteId which equals our own localId —
                // wrong key for the local chat log and photo directory.
                val chatId = outgoingPhotoChatId ?: assemblingChatId ?: return

                // C5: verify all chunks arrived before attempting decryption
                if (assemblingReceived != assemblingExpected) {
                    runOnUiThread {
                        Toast.makeText(this,
                            "Photo transfer incomplete ($assemblingReceived/${assemblingExpected} chunks)",
                            Toast.LENGTH_SHORT).show()
                    }
                    sendPhotoControlPacket("PHO_NACK|$transferId|incomplete")
                    cleanupPhotoTransfer()
                    resetAssembly()
                    return
                }

                // Concatenate chunks via ByteArrayOutputStream. The previous
                // `fold(ByteArray(0)) { acc, b -> acc + b }` was O(N²) — for a
                // 1 MB photo it allocated ~50 MB of intermediate arrays and OOM'd
                // on low-memory devices.
                val totalSize = chunks.sumOf { it?.size ?: 0 }
                val baos = java.io.ByteArrayOutputStream(totalSize)
                chunks.forEach { it?.let(baos::write) }
                val encryptedBytes = baos.toByteArray()

                val photoBytes = runCatching {
                    val aesKey = javax.crypto.spec.SecretKeySpec(key, "AES")
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, aesKey, javax.crypto.spec.GCMParameterSpec(128, iv))
                    cipher.doFinal(encryptedBytes)
                }.getOrElse { e ->
                    runOnUiThread { Toast.makeText(this, "Photo decrypt failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                    sendPhotoControlPacket("PHO_NACK|$transferId|decrypt_failed")
                    cleanupPhotoTransfer()
                    resetAssembly()
                    return
                }

                ioScope.launch {
                    try {
                    val photoDir = File(filesDir, "photos/$chatId").also { it.mkdirs() }
                    val dest = File(photoDir, "$transferId.jpg")
                    dest.writeBytes(photoBytes)
                    val path = dest.absolutePath
                    sendPhotoControlPacket("PHO_ACK|$transferId")
                    runOnUiThread {
                        appendMessage(chatId, contactName(chatId), "[PHOTO:$path]")
                        notifyIncomingMessage(chatId, "📷 Photo")
                        Toast.makeText(this@MainActivity, "Photo received", Toast.LENGTH_SHORT).show()
                    }
                    } catch (e: Exception) {
                        Log.w(TAG, "photo save failed: ${e.message}")
                        sendPhotoControlPacket("PHO_NACK|$transferId|save_failed")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Photo save failed", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        runOnUiThread {
                            cleanupPhotoTransfer()
                            resetAssembly()
                        }
                    }
                }

                return
            }
        }
    }

    private fun resetAssembly() {
        assemblingTransferId = null
        assemblingChatId = null
        assemblingChunks = null
        assemblingExpected = 0
        assemblingKey = null
        assemblingIv = null
        assemblingReceived = 0
        cancelReceiveWatchdog()
    }

    private val receiveWatchdogHandler by lazy { android.os.Handler(mainLooper) }

    private fun armReceiveWatchdog() {
        cancelReceiveWatchdog()
        val task = Runnable {
            val tid = assemblingTransferId
            if (tid != null) {
                Log.w(TAG, "Photo receive watchdog fired: tid=$tid, dropping stale assembly")
                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "Photo receive timed out — sender unreachable",
                        Toast.LENGTH_SHORT).show()
                }
                cleanupPhotoTransfer()
            }
        }
        assemblingWatchdog = task
        receiveWatchdogHandler.postDelayed(task, PHOTO_RECEIVE_TIMEOUT_MS)
    }

    private fun cancelReceiveWatchdog() {
        assemblingWatchdog?.let { receiveWatchdogHandler.removeCallbacks(it) }
        assemblingWatchdog = null
    }

    /**
     * Rewrites a `[PHOTO_PENDING:path|tid]` chat log entry to either `[PHOTO:path]`
     * (success) or `[PHOTO_FAILED:path]` (failure). Triggered when the outgoing
     * transfer completes or aborts. Called on main thread.
     */
    private fun rewriteChatLogPhotoEntry(chatId: String, transferId: String, success: Boolean) {
        val log = messageLogFor(chatId).toString()
        val pendingMarker = "[PHOTO_PENDING:"
        val tidTag = "|$transferId]"
        val rewritten = buildString(log.length) {
            var i = 0
            while (i < log.length) {
                val start = log.indexOf(pendingMarker, i)
                if (start < 0) {
                    append(log, i, log.length)
                    break
                }
                append(log, i, start)
                val end = log.indexOf("]", start)
                if (end < 0) {
                    append(log, start, log.length)
                    break
                }
                val full = log.substring(start, end + 1)
                if (full.endsWith(tidTag)) {
                    val path = full.removePrefix(pendingMarker).removeSuffix(tidTag)
                    append(if (success) "[PHOTO:$path]" else "[PHOTO_FAILED:$path]")
                } else {
                    append(full)
                }
                i = end + 1
            }
        }
        if (rewritten != log) {
            messageLogs[chatId] = StringBuilder(rewritten)
            prefs.edit().putString(chatLogKey(chatId), rewritten).apply()
            if (binding.chatScreen.visibility == View.VISIBLE && chatId == remoteId) {
                refreshChatDisplay(chatId)
            }
        }
    }

    private fun cleanupPhotoTransfer() {
        // Called from main (PHO_END), IO scope (sendPhotoOverChannel catch), and
        // WebRTC signaling threads (onConnectionChange FAILED). Without sync,
        // concurrent `photoTransferPc?.dispose()` → native double-free crash.
        synchronized(photoTransferLock) {
            photoTransferListeners.forEach { runCatching { it.remove() } }
            photoTransferListeners.clear()
            runCatching { photoTransferDc?.close() }
            photoTransferDc = null
            runCatching { photoTransferPc?.dispose() }
            photoTransferPc = null
            outgoingPhotoTransferId = null
            outgoingPhotoChatId = null
            photoSendInProgress = false
            outgoingPhotoCompletionHandled = false
            resetAssembly()
        }
    }

    /**
     * Full-screen photo viewer. Matches the drawer dark theme: black background,
     * pill-shaped action buttons (Save · Share · Close) in a row at the bottom.
     * The image supports pinch-to-zoom, double-tap zoom, and one-finger pan
     * via matrix transforms on a custom ZoomableImageView. Uses a plain Dialog
     * (not AlertDialog) so the system insets don't push the image up.
     */
    private fun showFullScreenPhoto(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Photo file not found", Toast.LENGTH_SHORT).show()
            return
        }
        val bmp = BitmapFactory.decodeFile(path) ?: return
        val dp = resources.displayMetrics.density

        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val image = ZoomableImageView(this).apply {
            setImageBitmap(bmp)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(image)

        val actionBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            val pad = (12 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            )
            setBackgroundColor(0xCC000000.toInt())
        }

        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        fun pillButton(label: String, primary: Boolean, onClick: () -> Unit): android.widget.TextView {
            return android.widget.TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (primary) R.drawable.bg_action_primary else R.drawable.bg_action_neutral
                )
                val padH = (18 * dp).toInt()
                val padV = (10 * dp).toInt()
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

        val saveBtn = pillButton("💾 Save", primary = true) {
            savePhotoToGallery(file)
        }
        val shareBtn = pillButton("↗ Share", primary = false) {
            sharePhotoFile(file)
        }
        val closeBtn = pillButton("✕ Close", primary = false) {
            dialog.dismiss()
        }
        val gap = (8 * dp).toInt()
        actionBar.addView(saveBtn, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = gap })
        actionBar.addView(shareBtn, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = gap })
        actionBar.addView(closeBtn, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionBar)

        // Single tap (without zoom gesture) -> toggle action bar. The
        // ZoomableImageView forwards single-tap-confirmed up through GestureDetector.
        image.onSingleTapUp = {
            actionBar.visibility =
                if (actionBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        image.onLongPress = {
            AlertDialog.Builder(this)
                .setItems(arrayOf("Save to gallery", "Share", "Close")) { _, i ->
                    when (i) {
                        0 -> savePhotoToGallery(file)
                        1 -> sharePhotoFile(file)
                        2 -> dialog.dismiss()
                    }
                }
                .show()
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF000000.toInt()))
            setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        dialog.show()
    }

    /**
     * ImageView subclass that supports pinch-to-zoom, double-tap zoom toggle
     * and one-finger pan via Matrix transforms. Initial fit is FIT_CENTER
     * (handled by computeBaseMatrix on the first layout pass), so the image
     * appears centered in the viewport — unlike AlertDialog's default which
     * pushes content to the top of the dialog frame.
     */
    private inner class ZoomableImageView(ctx: Context) : ImageView(ctx) {
        private val MODE_NONE = 0
        private val MODE_DRAG = 1
        private val MODE_ZOOM = 2

        private val matrixCurr = android.graphics.Matrix()
        private val matrixSaved = android.graphics.Matrix()
        private var scaleFactor = 1f
        private val minScale = 1f
        private val maxScale = 6f
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var mode = MODE_NONE
        private var baseConfigured = false

        var onSingleTapUp: (() -> Unit)? = null
        var onLongPress: (() -> Unit)? = null

        private val scaleDetector = android.view.ScaleGestureDetector(ctx,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val newScale = (scaleFactor * detector.scaleFactor)
                        .coerceIn(minScale, maxScale)
                    val factor = newScale / scaleFactor
                    scaleFactor = newScale
                    matrixCurr.postScale(factor, factor, detector.focusX, detector.focusY)
                    clampMatrix()
                    imageMatrix = matrixCurr
                    return true
                }
            })

        private val gestureDetector = android.view.GestureDetector(ctx,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    onSingleTapUp?.invoke()
                    return true
                }
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    val targetScale = if (scaleFactor > 1.05f) minScale else 2.5f
                    val factor = targetScale / scaleFactor
                    scaleFactor = targetScale
                    matrixCurr.postScale(factor, factor, e.x, e.y)
                    clampMatrix()
                    imageMatrix = matrixCurr
                    return true
                }
                override fun onLongPress(e: android.view.MotionEvent) {
                    onLongPress?.invoke()
                }
            })

        init {
            scaleType = ScaleType.MATRIX
            isClickable = true
            isFocusable = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            baseConfigured = false
            configureBaseMatrix()
        }

        override fun setImageBitmap(bm: android.graphics.Bitmap?) {
            super.setImageBitmap(bm)
            baseConfigured = false
            if (width > 0 && height > 0) configureBaseMatrix()
        }

        private fun configureBaseMatrix() {
            val drawable = drawable ?: return
            val dW = drawable.intrinsicWidth.toFloat()
            val dH = drawable.intrinsicHeight.toFloat()
            if (dW <= 0f || dH <= 0f || width <= 0 || height <= 0) return
            val scale = minOf(width / dW, height / dH)
            val tx = (width - dW * scale) / 2f
            val ty = (height - dH * scale) / 2f
            matrixCurr.reset()
            matrixCurr.postScale(scale, scale)
            matrixCurr.postTranslate(tx, ty)
            scaleFactor = 1f
            imageMatrix = matrixCurr
            baseConfigured = true
        }

        private fun clampMatrix() {
            // Re-center single axis if image is smaller than viewport on that axis.
            val vals = FloatArray(9)
            matrixCurr.getValues(vals)
            val curScale = vals[android.graphics.Matrix.MSCALE_X]
            val drawable = drawable ?: return
            val w = drawable.intrinsicWidth * curScale
            val h = drawable.intrinsicHeight * curScale
            var dx = 0f
            var dy = 0f
            val tx = vals[android.graphics.Matrix.MTRANS_X]
            val ty = vals[android.graphics.Matrix.MTRANS_Y]
            if (w < width) {
                dx = (width - w) / 2f - tx
            } else {
                if (tx > 0f) dx = -tx
                else if (tx + w < width) dx = width - (tx + w)
            }
            if (h < height) {
                dy = (height - h) / 2f - ty
            } else {
                if (ty > 0f) dy = -ty
                else if (ty + h < height) dy = height - (ty + h)
            }
            if (dx != 0f || dy != 0f) matrixCurr.postTranslate(dx, dy)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    matrixSaved.set(matrixCurr)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    mode = MODE_DRAG
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    mode = MODE_ZOOM
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (mode == MODE_DRAG && !scaleDetector.isInProgress) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        matrixCurr.postTranslate(dx, dy)
                        clampMatrix()
                        imageMatrix = matrixCurr
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    mode = MODE_NONE
                }
            }
            return true
        }
    }

    /**
     * Drop the photo into the device's Pictures/X-link bucket so it shows up
     * in the gallery. Uses MediaStore on API 29+ (no permission needed under
     * scoped storage); falls back to public ExternalStorage on pre-Q which is
     * gated by the maxSdkVersion=28 WRITE_EXTERNAL_STORAGE permission in the
     * manifest.
     */
    private fun savePhotoToGallery(src: File) {
        val name = "xlink_${System.currentTimeMillis()}.jpg"
        ioScope.launch {
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                            "${android.os.Environment.DIRECTORY_PICTURES}/X-link")
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = contentResolver
                    val uri = resolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: error("MediaStore insert returned null")
                    resolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    } ?: error("Cannot open output stream")
                    values.clear()
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES
                        ),
                        "X-link"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    val dst = File(dir, name)
                    src.copyTo(dst, overwrite = false)
                    // Notify gallery
                    sendBroadcast(android.content.Intent(
                        android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        android.net.Uri.fromFile(dst)
                    ))
                }
            }.isSuccess
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "Saved to gallery" else "Save failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun sharePhotoFile(src: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", src)
        }.getOrNull() ?: run {
            Toast.makeText(this, "Cannot share this file", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Share photo"))
    }

    // ─── End Photo Transfer ───────────────────────────────────────────────────

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
                cancelIncomingCallNotification()
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
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
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
                        val prepared = if (mode.rawPcm) source else preparePcmForCodec2(source, mode)
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
                    } else {
                        // read == 0: transient mic stall — yield instead of busy-looping CPU.
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

    private fun preparePcmForCodec2(source: ShortArray, mode: VoiceMode): ShortArray {
        var sumSquares = 0.0
        for (sample in source) {
            sumSquares += (sample.toDouble() * sample.toDouble())
        }
        val rms = kotlin.math.sqrt(sumSquares / source.size.toDouble())
        val targetRms = when (mode) {
            VoiceMode.BASE -> CODEC2_BASE_TARGET_RMS
            else -> CODEC2_TARGET_RMS
        }
        val maxGain = when (mode) {
            VoiceMode.BASE -> CODEC2_BASE_MAX_GAIN
            else -> CODEC2_MAX_GAIN
        }
        val gain = when {
            rms < 1.0 -> 1.0
            else -> (targetRms / rms).coerceIn(0.65, maxGain)
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
                // Resync: advance rxVoiceSeq PAST the frame we're about to return.
                // Previous code set `rxVoiceSeq = firstBufferedSeq` and then removed
                // that key — the next call would re-enter the gap branch and skip
                // the legitimate next frame, causing audible clicks every jitter.
                rxVoiceSeq = firstBufferedSeq + 1
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
                    if (::peerConnection.isInitialized) peerConnection.addIceCandidate(cand)
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
        // Stop the call foreground service / notification on every teardown path
        // (remote ended, finishCallAndReturn, etc.) — no-op when not running.
        CallForegroundService.stop(this)
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
        runCatching {
            if (::peerConnection.isInitialized) {
                peerConnection.close()
                peerConnection.dispose()
            }
        }.onFailure { Log.w(TAG, "resetPeerConnection dispose error: ${it.message}") }
        clearCallState()
        initPeerConnection(createLocalChannels)
        if (!createLocalChannels) {
            runOnUiThread {
                showCallControls(false)
                binding.addContactScreen.visibility = View.GONE
                binding.outgoingCallScreen.visibility = View.GONE
                binding.activeCallScreen.visibility = View.GONE
            }
        }
    }

    private fun disconnectCall() {
        // Stop the call foreground service / notification regardless of path.
        CallForegroundService.stop(this)

        val firestore = db
        val callId = currentCallId
        val sessionId = currentSessionId
        if (firestore == null || callId == null) {
            cancelIncomingCallNotification()
            resetPeerConnection(createLocalChannels = false)
            returnToPostCallScreen()
            showCallControls(false)
            updateDebugStatus("disconnected")
            return
        }

        val callRef = firestore.collection("calls").document(callId)
        callRef.update(
            mapOf(
                "state" to "ended",
                "endedBy" to localId,
                "endedSessionId" to sessionId,
                "endedAt" to System.currentTimeMillis()
            )
        ).addOnCompleteListener {
            cancelIncomingCallNotification()
            resetPeerConnection(createLocalChannels = false)
            returnToPostCallScreen()
            showCallControls(false)
            updateDebugStatus("disconnected")
            // Clean up Firestore call document so stale data doesn't accumulate
            callRef.delete().addOnFailureListener { e ->
                Log.w(TAG, "clearCallDocument failed: ${e.message}")
            }
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
        processedCandidateIds.clear()
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
        // Cancel coroutines first — stops any in-flight IO that touches native objects.
        ioScope.cancel()
        messagePollJob = null
        cancelCallTimeout()
        stopIncomingRing()
        stopAudio()
        removeListeners()
        incomingListener?.remove()
        incomingListener = null
        incomingTransferListener?.remove()
        incomingTransferListener = null
        photoTransferListeners.forEach { it.remove() }
        photoTransferListeners.clear()
        cleanupPhotoTransfer()
        releaseSharedCodec()
        // Wrap WebRTC dispose in try-catch: callbacks on other threads may still fire briefly.
        runCatching {
            if (::peerConnection.isInitialized) {
                peerConnection.close()
                peerConnection.dispose()
            }
        }.onFailure { Log.w(TAG, "peerConnection dispose error: ${it.message}") }
        runCatching {
            if (::peerFactory.isInitialized) peerFactory.dispose()
        }.onFailure { Log.w(TAG, "peerFactory dispose error: ${it.message}") }
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
        private const val CODEC2_BASE_TARGET_RMS = 8500.0
        private const val CODEC2_BASE_MAX_GAIN = 4.0
        private const val CODEC2_LIMIT = 30000
        private const val AES_GCM_IV_BYTES = 12
        private const val AES_GCM_TAG_BITS = 128
        private const val EC_KEY_ALGORITHM = "ECDH/P-256"
        private const val AES_MESSAGE_ALGORITHM = "AES/GCM/NoPadding"
        private const val LOG_TAG = "XxxLinkCall"
        private const val TAG = LOG_TAG
        private const val PREFS_NAME = "xxxlink_prefs"
        private const val KEY_LOCAL_ID = "local_id"
        private const val KEY_PHOTO_ACCOUNT_SECRET = "photo_account_secret"
        private const val KEY_CONTACT_IDS = "contact_ids"
        private const val KEY_CONTACT_PREFIX = "contact_name_"
        private const val KEY_CHAT_LOG_PREFIX = "chat_log_"
        private const val KEY_CHAT_READ_PREFIX = "chat_read_count_"
        private const val KEY_APP_IN_FOREGROUND = "app_in_foreground"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_APP_PIN_HASH = "app_pin_hash"
        // ── v2 auth (photo + password) ─────────────────────────────────
        private const val KEY_AUTH_VERSION = "auth_version"
        private const val KEY_V2_CRYPTO_SECRET = "v2_crypto_secret"
        private const val V2_PBKDF2_ITERS = 600_000
        // ── Photo transfer hardening (v1.14.16) ──────────────────────────
        /** Stale-offer cutoff: ignore pending transfers older than this when listening. */
        private const val PHOTO_OFFER_TTL_MS = 10 * 60_000L
        // Sender's wait for the receiver to move /transfers/{tid}.state out of
        // 'pending'. After this, the sender gives up so its photoTransferPc
        // doesn't deadlock subsequent incoming offers as busy.
        private const val PHOTO_ANSWER_TIMEOUT_MS = 60_000L
        private const val PHOTO_DELIVERY_ACK_TIMEOUT_MS = 60_000L
        /** Receive-side inactivity timeout. Resets on every PHO_CHUNK. */
        private const val PHOTO_RECEIVE_TIMEOUT_MS = 90_000L
        private const val KEY_APP_PIN_SALT = "app_pin_salt"
        private const val KEY_PIN_FAILURES = "app_pin_failures"
        private const val KEY_PIN_LOCKOUT_UNTIL = "app_pin_lockout_until"
        private const val BACKUP_MAGIC = "XLINKBAK"
        // v3 = PBKDF2 + random 16-byte salt (defeats GPU brute force).
        // v2 = legacy SHA-256 derivation; still supported on import.
        private const val BACKUP_VERSION = 3
        private const val BACKUP_SALT_BYTES = 16
        private const val BACKUP_PBKDF2_ITERS = 600_000
        // ── Reply-to / Groups (v1.14.20) ─────────────────────────────────
        private const val KEY_REPLY_PREFIX = "reply_"          // reply_{msgId} → original msgId
        private const val KEY_GROUP_IDS = "group_ids"          // Set<groupId>
        private const val KEY_GROUP_NAME_PREFIX = "group_name_"
        private const val KEY_GROUP_MEMBERS_PREFIX = "group_members_"
        private const val KEY_GROUP_ADMIN_PREFIX = "group_admin_"
        private const val GROUP_ID_PREFIX = "g:"

        /** 64-emoji alphabet (power of 2 so byte → index is a simple AND mask). */
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
        private const val BACKUP_IV_BYTES = 12
        private const val KEY_BACKUP_LAST_PATH = "backup_last_path"
        private const val KEY_SEEN_MESSAGE_IDS = "seen_message_ids"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_UNREAD_NOTIFICATION_COUNT = "unread_notification_count"
        private const val PHOTO_ACCOUNT_KEY_SALT = "x-link-photo-key-v1"
        // ── GitHub update ──────────────────────────────────────────────────────
        // Set these after creating your GitHub repository.
        // Releases must have an .apk file as a release asset.
        private const val GITHUB_OWNER = "nykleforse"
        private const val GITHUB_REPO  = "xlink-android"
        private const val GITHUB_API   =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        // v4: bumped from v3 because sound URIs changed when the raw resources
        // were renamed (incomming_* → incoming_*). Android caches the sound at
        // channel-creation time on API 26+; the only way to apply the new URI
        // is to register a fresh channel ID.
        private const val NOTIFICATION_CALLS_CHANNEL_ID = "xxxlink_calls_v4"
        private const val NOTIFICATION_MESSAGES_CHANNEL_ID = "xxxlink_messages_v4"
        private const val LEGACY_CALLS_CHANNEL_ID = "xxxlink_calls_v3"
        private const val LEGACY_MESSAGES_CHANNEL_ID = "xxxlink_messages_v3"
        private const val NOTIFICATION_CALL_ID = 5001
        private const val NOTIFICATION_MESSAGE_ID_BASE = 6000
        private const val PHOTO_CHANNEL_MAX_BUFFERED_AMOUNT_BYTES = 256L * 1024L
        private const val PHOTO_CHANNEL_BUFFER_POLL_MS = 25L
        private const val PHOTO_CHANNEL_BUFFER_TIMEOUT_MS = 30_000L
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

        companion object {
            fun fromLabel(label: String?): VoiceMode =
                entries.firstOrNull { it.label == label } ?: COMFY
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

    private data class PhotoAuthMaterial(val secret: ByteArray)

    private enum class PendingMicAction {
        OUTGOING_CALL,
        ACCEPT_INCOMING
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onSetFailure(p0: String?) {}
    override fun onSetSuccess() {}
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onCreateFailure(p0: String?) {}
}
