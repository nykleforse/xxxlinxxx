package com.example.xxxlinkxxx.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xxxlinkxxx.desktop.call.CallOverlay
import com.example.xxxlinkxxx.desktop.call.CallUiState
import com.example.xxxlinkxxx.desktop.call.VoiceCallController
import com.example.xxxlinkxxx.desktop.crypto.Crypto
import com.example.xxxlinkxxx.desktop.groups.GroupRepository
import com.example.xxxlinkxxx.desktop.net.FirebaseClient
import com.example.xxxlinkxxx.desktop.net.Repository
import com.example.xxxlinkxxx.desktop.photo.PhotoTransferController
import com.example.xxxlinkxxx.desktop.security.BackupCodec
import com.example.xxxlinkxxx.desktop.security.PinLock
import com.example.xxxlinkxxx.desktop.storage.SecurePrefs
import com.example.xxxlinkxxx.desktop.update.UpdateChecker
import java.awt.Desktop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// ── Design tokens (mirror Android colors.xml) ────────────────────────────────

private val Bg = Color(0xFF000000)
private val Surface = Color(0xFF101010)
private val Line = Color(0xFF333333)
private val TextPrimary = Color(0xFFB8B8B8)
private val TextSecondary = Color(0xFF888888)
private val TextMuted = Color(0xFF5F5F5F)
private val Accent = Color(0xFF4FC3F7)

private val Mono = TextStyle(
    color = TextPrimary,
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
)

// ── Firebase project constants (mirror app/google-services.json) ─────────────

private const val FB_API_KEY = "AIzaSyAVJQ8ULFuZ6XMA81UoMwuiiXmJCd201tw"
private const val FB_PROJECT_ID = "xxxlinkxxx-81cbf"

// ── Chat message model + status enum ─────────────────────────────────────────

enum class MsgStatus { UNKNOWN, SENT, DELIVERED, READ }

data class ChatMessage(
    val id: String,
    val author: String,         // "Me" or peer localId
    val text: String,
    val ts: Long,
    val replyTo: String? = null,
    var status: MsgStatus = MsgStatus.UNKNOWN,
)

// ── App state machine ────────────────────────────────────────────────────────

private sealed interface AppScreen {
    object Login : AppScreen
    data class Authenticated(
        val repo: Repository,
        val prefs: SecurePrefs,
        val photoSecret: ByteArray,
    ) : AppScreen
}

private sealed interface AuthedSub {
    object ContactList : AuthedSub
    object AddContact : AuthedSub
    object NewGroup : AuthedSub
    data class Chat(val peerId: String) : AuthedSub
}

@Composable
fun App() {
    var screen: AppScreen by remember { mutableStateOf<AppScreen>(AppScreen.Login) }
    var status by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (val s = screen) {
            AppScreen.Login -> LoginScreen(
                status = status,
                onStatus = { status = it },
                onAuthenticated = { repo, prefs, photoSecret ->
                    screen = AppScreen.Authenticated(repo, prefs, photoSecret)
                }
            )
            is AppScreen.Authenticated -> AuthedRoot(
                repo = s.repo,
                prefs = s.prefs,
                photoSecret = s.photoSecret,
                onLogout = {
                    screen = AppScreen.Login
                    status = "Logged out"
                }
            )
        }
    }
}

// ── Login (photo + password → derive identity → bind → publish pubkey) ───────

@Composable
private fun LoginScreen(
    status: String,
    onStatus: (String) -> Unit,
    onAuthenticated: (Repository, SecurePrefs, ByteArray) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var photoFile by remember { mutableStateOf<File?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("XxxLink Desktop", color = TextPrimary, style = Mono.copy(fontSize = 18.sp))
        Spacer(Modifier.height(4.dp))
        Text("v1.15.8 · chat-only MVP", color = TextMuted, style = Mono.copy(fontSize = 11.sp))
        Spacer(Modifier.height(24.dp))

        DarkButton(
            label = photoFile?.name?.let { "Photo: $it" } ?: "Pick photo",
            onClick = { photoFile = openPhotoDialog() },
            enabled = !busy,
        )
        Spacer(Modifier.height(12.dp))

        FieldBox(width = 320.dp) {
            TextInputRaw(
                value = password,
                onChange = { password = it },
                placeholder = "Password (same as on phone)",
                enabled = !busy,
                mask = true,
            )
        }
        Spacer(Modifier.height(12.dp))

        DarkButton(
            label = if (busy) "Working..." else "Log in",
            onClick = {
                val photo = photoFile
                if (photo == null) {
                    onStatus("Pick photo first")
                    return@DarkButton
                }
                if (password.isEmpty()) {
                    onStatus("Enter password")
                    return@DarkButton
                }
                busy = true
                scope.launch {
                    runCatching {
                        doLogin(photo, password, onStatus)
                    }.onSuccess { triple ->
                        busy = false
                        onAuthenticated(triple.first, triple.second, triple.third)
                    }.onFailure { e ->
                        busy = false
                        onStatus("Login failed: ${e.message}")
                    }
                }
            },
            enabled = !busy,
        )
        Spacer(Modifier.height(16.dp))
        Text(status, color = TextSecondary, style = Mono.copy(fontSize = 11.sp))
    }
}

private suspend fun doLogin(
    photo: File,
    password: String,
    onStatus: (String) -> Unit,
): Triple<Repository, SecurePrefs, ByteArray> = withContext(Dispatchers.IO) {
    onStatus("Reading photo...")
    val photoBytes = photo.readBytes()

    onStatus("Deriving keys (PBKDF2 600k iter, takes ~1-2s)...")
    val (localId, cryptoSecret) = Crypto.deriveV2(photoBytes, password)
    val photoSecret = Crypto.photoAuthSecret(photoBytes)
    val keyPair = Crypto.deriveEcKeyPair(cryptoSecret)

    onStatus("Opening secure vault...")
    val prefs = SecurePrefs.open(photoSecret)
    prefs.edit()
        .putString("local_id", localId)
        .putString("v2_crypto_secret", Crypto.b64(cryptoSecret))
        .putInt("auth_version", 2)
        .apply()

    onStatus("Signing in to Firebase...")
    val fb = FirebaseClient(FB_API_KEY, FB_PROJECT_ID)
    fb.signInAnonymously()

    val repo = Repository(fb, keyPair, localId)

    onStatus("Binding localId on server...")
    runCatching { repo.bindLocalIdOnServer() }
        .onFailure { onStatus("bindLocalId warning: ${it.message}") }

    onStatus("Publishing public key...")
    repo.publishPublicMessageKey()

    onStatus("Ready as $localId")
    Triple(repo, prefs, photoSecret)
}

// ── Authenticated root: contacts list / chat / add-contact ───────────────────

@Composable
private fun AuthedRoot(
    repo: Repository,
    prefs: SecurePrefs,
    photoSecret: ByteArray,
    onLogout: () -> Unit,
) {
    var locked by remember { mutableStateOf(PinLock.isEnabled(prefs)) }
    var lockError by remember { mutableStateOf("") }
    var pinAttempt by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsMsg by remember { mutableStateOf("") }
    val groupRepo = remember(repo.localId) { GroupRepository(repo, prefs) }
    val groups = remember {
        mutableStateListOf<String>().also { it.addAll(groupRepo.savedGroupIds()) }
    }
    var sub: AuthedSub by remember { mutableStateOf<AuthedSub>(AuthedSub.ContactList) }
    val contacts = remember {
        mutableStateListOf<String>().also { it.addAll(prefs.getStringSet("contact_ids")) }
    }
    val chatLogs = remember { mutableStateMapOf<String, SnapshotStateList<ChatMessage>>() }
    val scope = rememberCoroutineScope()

    val photoTransfer = remember(repo.localId) {
        PhotoTransferController(
            repo = repo,
            myKeyPair = keyPairFromPrefs(prefs, photoSecret),
            onIncoming = { from, file ->
                appendMessage(
                    chatLogs, from,
                    "photo-${System.currentTimeMillis()}",
                    from, "[PHOTO:${file.absolutePath}]",
                    System.currentTimeMillis(),
                )
            },
        )
    }
    DisposableEffect(photoTransfer) {
        photoTransfer.startIncomingWatch()
        onDispose { photoTransfer.dispose() }
    }

    // VoiceCallController lifetime = AuthedRoot lifetime. Disposed on logout
    // so PeerConnectionFactory + Firestore polling shut down cleanly.
    var callState: CallUiState by remember { mutableStateOf<CallUiState>(CallUiState.Idle) }
    var callMuted by remember { mutableStateOf(false) }
    val callController = remember(repo.localId) {
        VoiceCallController(repo, turnUsername = null, turnPassword = null) { change ->
            when (change) {
                is VoiceCallController.CallStateChange.Outgoing ->
                    callState = CallUiState.Outgoing(change.peerId)
                is VoiceCallController.CallStateChange.AcceptingIncoming ->
                    callState = CallUiState.Outgoing(change.peerId)
                VoiceCallController.CallStateChange.Connected -> {
                    val peer = (callState as? CallUiState.Outgoing)?.peerId
                        ?: (callState as? CallUiState.Active)?.peerId
                        ?: return@VoiceCallController
                    callState = CallUiState.Active(peer, System.currentTimeMillis())
                }
                VoiceCallController.CallStateChange.Ended -> {
                    callState = CallUiState.Idle
                    callMuted = false
                }
                is VoiceCallController.CallStateChange.Failed -> {
                    callState = CallUiState.Idle
                    callMuted = false
                }
                is VoiceCallController.CallStateChange.MuteChanged -> callMuted = change.muted
                is VoiceCallController.CallStateChange.VoiceUnavailable -> Unit
            }
        }
    }
    DisposableEffect(callController) {
        callController.startIncomingWatch { evt ->
            // Only surface the incoming call if we're not already in one.
            if (callState is CallUiState.Idle) {
                callState = CallUiState.Incoming(evt.callerId, evt.callId, evt.sessionId, evt.offerSdp)
            }
        }
        onDispose {
            callController.stopIncomingWatch()
            callController.dispose()
        }
    }

    LaunchedEffect(repo.localId) {
        while (true) {
            runCatching {
                val msgs = repo.pollInbox()
                for (m in msgs) {
                    val gid = m.groupId
                    if (gid != null) {
                        if (gid !in groups) {
                            val ok = runCatching { groupRepo.discoverGroup(gid) }.getOrDefault(false)
                            if (ok) groups.add(gid)
                            else continue
                        }
                        appendMessage(chatLogs, gid, m.id, m.from, m.text, m.ts, m.replyTo)
                    } else {
                        appendMessage(chatLogs, m.from, m.id, m.from, m.text, m.ts, m.replyTo)
                        if (m.from !in contacts) {
                            contacts.add(m.from)
                            prefs.edit().putStringSet("contact_ids", contacts.toSet()).apply()
                        }
                    }
                    runCatching { repo.writeReceipt(m.id, m.from) }
                }
            }
            delay(5_000)
        }
    }

    // Receipt poller: drives ✓ / ✓✓ ticks on outgoing messages by reading
    // back our own /receipts/{msgId} writes from the recipients.
    LaunchedEffect(repo.localId) {
        while (true) {
            runCatching {
                val receipts = repo.pollReceipts()
                if (receipts.isNotEmpty()) {
                    for ((_, list) in chatLogs) {
                        for (msg in list) {
                            if (msg.author != "Me") continue
                            // 1:1 receipt id == msgId. Group fan-out IDs look like
                            // "${msgId}-${memberId}" — match on prefix.
                            val match = receipts.firstOrNull {
                                it.msgId == msg.id || it.msgId.startsWith("${msg.id}-")
                            } ?: continue
                            val newStatus = when {
                                match.read -> MsgStatus.READ
                                match.delivered -> MsgStatus.DELIVERED
                                else -> MsgStatus.SENT
                            }
                            if (msg.status != newStatus) msg.status = newStatus
                        }
                    }
                }
            }
            delay(5_000)
        }
    }

    when (val s = sub) {
        AuthedSub.ContactList -> ContactListScreen(
            localId = repo.localId,
            contacts = contacts,
            groups = groups,
            groupName = { id -> groupRepo.groupName(id) },
            onAdd = { sub = AuthedSub.AddContact },
            onNewGroup = { sub = AuthedSub.NewGroup },
            onPick = { sub = AuthedSub.Chat(it) },
            onLogout = onLogout,
            onSettings = { settingsOpen = true },
        )
        AuthedSub.AddContact -> AddContactScreen(
            onAdd = { id ->
                if (id.isNotBlank() && id !in contacts) {
                    contacts.add(id)
                    prefs.edit().putStringSet("contact_ids", contacts.toSet()).apply()
                }
                sub = AuthedSub.ContactList
            },
            onCancel = { sub = AuthedSub.ContactList },
        )
        AuthedSub.NewGroup -> NewGroupScreen(
            contacts = contacts,
            onCreate = { name, selected ->
                scope.launch {
                    runCatching { groupRepo.createGroup(name, selected) }
                        .onSuccess { gid ->
                            if (gid !in groups) groups.add(gid)
                            sub = AuthedSub.Chat(gid)
                        }
                }
            },
            onCancel = { sub = AuthedSub.ContactList },
        )
        is AuthedSub.Chat -> ChatScreen(
            peerId = s.peerId,
            isGroup = groupRepo.isGroupId(s.peerId),
            displayName = if (groupRepo.isGroupId(s.peerId))
                "👥 ${groupRepo.groupName(s.peerId)}" else s.peerId,
            messages = chatLogs[s.peerId] ?: mutableStateListOf(),
            repo = repo,
            onSendPhoto = if (!groupRepo.isGroupId(s.peerId)) {
                {
                    val file = openImageDialog()
                    if (file != null) {
                        photoTransfer.sendPhoto(s.peerId, file)
                        appendMessage(
                            chatLogs, s.peerId,
                            "photo-${System.currentTimeMillis()}",
                            "Me", "[PHOTO:${file.absolutePath}]",
                            System.currentTimeMillis(),
                            initialStatus = MsgStatus.SENT,
                        )
                    }
                }
            } else null,
            onSend = { text, pk, replyTo ->
                scope.launch {
                    if (groupRepo.isGroupId(s.peerId)) {
                        runCatching { groupRepo.sendGroupMessage(s.peerId, text, replyTo) }
                            .onSuccess { msgId ->
                                if (msgId != null) {
                                    appendMessage(
                                        chatLogs, s.peerId, msgId, "Me", text,
                                        System.currentTimeMillis(), replyTo,
                                        initialStatus = MsgStatus.SENT,
                                    )
                                }
                            }
                    } else {
                        runCatching { repo.sendEncryptedMessage(s.peerId, text, pk, replyTo) }
                            .onSuccess { msgId ->
                                appendMessage(
                                    chatLogs, s.peerId, msgId, "Me", text,
                                    System.currentTimeMillis(), replyTo,
                                    initialStatus = MsgStatus.SENT,
                                )
                            }
                    }
                }
            },
            onBack = { sub = AuthedSub.ContactList },
            onCall = {
                if (!groupRepo.isGroupId(s.peerId)) {
                    runCatching { callController.startOutgoing(s.peerId) }
                }
            },
        )
    }

    if (settingsOpen) {
        SettingsOverlay(
            pinEnabled = PinLock.isEnabled(prefs),
            statusMessage = settingsMsg,
            onSetPin = { newPin ->
                runCatching { PinLock.set(prefs, newPin) }
                    .onSuccess { settingsMsg = "PIN set" }
                    .onFailure { settingsMsg = "PIN error: ${it.message}" }
            },
            onDisablePin = {
                PinLock.disable(prefs)
                settingsMsg = "PIN disabled"
            },
            onExportBackup = {
                val target = saveBackupDialog() ?: return@SettingsOverlay
                runCatching { BackupCodec.export(prefs, photoSecret, target) }
                    .onSuccess { settingsMsg = "Backup written: ${target.name}" }
                    .onFailure { settingsMsg = "Backup error: ${it.message}" }
            },
            onImportBackup = {
                val src = openBackupDialog() ?: return@SettingsOverlay
                runCatching { BackupCodec.restore(prefs, photoSecret, src) }
                    .onSuccess { r ->
                        contacts.clear()
                        contacts.addAll(prefs.getStringSet("contact_ids"))
                        settingsMsg = "Restored ${r.contactCount} contacts, ${r.chatCount} chats"
                    }
                    .onFailure { settingsMsg = "Restore error: ${it.message}" }
            },
            onCheckForUpdates = {
                settingsMsg = "Checking..."
                scope.launch {
                    val info = runCatching { UpdateChecker.checkLatest() }.getOrNull()
                    if (info == null) {
                        settingsMsg = "You're on the latest (${UpdateChecker.CURRENT_VERSION})"
                        return@launch
                    }
                    settingsMsg = "Downloading ${info.version}..."
                    runCatching {
                        val file = UpdateChecker.downloadMsi(info) { got, total ->
                            if (total > 0) {
                                settingsMsg = "Downloading ${info.version} " +
                                    "${(got * 100 / total)}%"
                            }
                        }
                        settingsMsg = "Launching installer: ${file.name}"
                        runCatching { Desktop.getDesktop().open(file) }
                    }.onFailure { settingsMsg = "Update failed: ${it.message}" }
                }
            },
            onClose = {
                settingsOpen = false
                settingsMsg = ""
            },
        )
    }

    if (locked) {
        LockOverlay(
            pin = pinAttempt,
            onPinChange = { pinAttempt = it.filter { c -> c.isDigit() }.take(16) },
            error = lockError,
            onUnlock = {
                val err = PinLock.verify(prefs, pinAttempt)
                if (err == null) {
                    locked = false
                    lockError = ""
                    pinAttempt = ""
                } else {
                    lockError = err
                    pinAttempt = ""
                    if (err.contains("wiped", ignoreCase = true)) {
                        onLogout()
                    }
                }
            },
        )
    }

    CallOverlay(
        state = callState,
        muted = callMuted,
        onAccept = {
            val inc = callState as? CallUiState.Incoming ?: return@CallOverlay
            callController.acceptIncoming(inc.callId, inc.callerId, inc.sessionId, inc.offerSdp)
        },
        onDecline = {
            val inc = callState as? CallUiState.Incoming ?: return@CallOverlay
            scope.launch {
                runCatching {
                    repo.firebase.firestoreSet(
                        "calls/${inc.callId}",
                        mapOf(
                            "state" to "declined",
                            "declinedBy" to repo.localId,
                            "declinedAt" to System.currentTimeMillis(),
                        ),
                        merge = true,
                    )
                }
            }
            callState = CallUiState.Idle
        },
        onHangUp = { callController.hangUp() },
        onToggleMute = { callController.toggleMute(!callMuted) },
    )
}

// ── Contact list ─────────────────────────────────────────────────────────────

@Composable
private fun ContactListScreen(
    localId: String,
    contacts: List<String>,
    groups: List<String>,
    groupName: (String) -> String,
    onAdd: () -> Unit,
    onNewGroup: () -> Unit,
    onPick: (String) -> Unit,
    onLogout: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", color = TextPrimary, style = Mono.copy(fontSize = 16.sp))
            Spacer(Modifier.width(12.dp))
            Text("My ID: $localId", color = TextMuted, style = Mono.copy(fontSize = 11.sp))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            DarkButton(label = "+ Add", onClick = onAdd)
            Spacer(Modifier.width(8.dp))
            DarkButton(label = "+ Group", onClick = onNewGroup)
            Spacer(Modifier.width(8.dp))
            DarkButton(label = "Settings", onClick = onSettings)
            Spacer(Modifier.width(8.dp))
            DarkButton(label = "Log out", onClick = onLogout)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.height(1.dp).fillMaxWidth().background(Line))
        Spacer(Modifier.height(8.dp))

        if (contacts.isEmpty() && groups.isEmpty()) {
            Text(
                "No contacts or groups yet. Press + Add (peer ID) or + Group.",
                color = TextMuted,
                style = Mono.copy(fontSize = 12.sp),
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups) { gid ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(gid) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text("👥 ${groupName(gid)}", color = TextPrimary, style = Mono)
                    }
                }
                items(contacts) { id ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(id) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(id, color = TextPrimary, style = Mono)
                    }
                }
            }
        }
    }
}

// ── New group ────────────────────────────────────────────────────────────────

@Composable
private fun NewGroupScreen(
    contacts: List<String>,
    onCreate: (String, List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("New group", color = TextPrimary, style = Mono.copy(fontSize = 16.sp))
        Spacer(Modifier.height(16.dp))
        FieldBox(width = 320.dp) {
            TextInputRaw(value = name, onChange = { name = it.take(40) }, placeholder = "Group name")
        }
        Spacer(Modifier.height(12.dp))
        Text("Members", color = TextSecondary, style = Mono.copy(fontSize = 11.sp))
        Spacer(Modifier.height(6.dp))
        if (contacts.isEmpty()) {
            Text(
                "Add a contact first.",
                color = TextMuted,
                style = Mono.copy(fontSize = 12.sp),
            )
        } else {
            Box(Modifier.height(220.dp).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(contacts) { id ->
                        val isSel = id in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSel) selected.remove(id) else selected.add(id)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (isSel) "[x] " else "[ ] ", color = TextPrimary, style = Mono)
                            Text(id, color = TextPrimary, style = Mono)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row {
            DarkButton(
                label = "Create",
                onClick = {
                    val n = name.trim().ifBlank { "Untitled" }
                    if (selected.isNotEmpty()) onCreate(n, selected.toList())
                },
                enabled = selected.isNotEmpty(),
            )
            Spacer(Modifier.width(8.dp))
            DarkButton(label = "Cancel", onClick = onCancel)
        }
    }
}

// ── Add contact ──────────────────────────────────────────────────────────────

@Composable
private fun AddContactScreen(onAdd: (String) -> Unit, onCancel: () -> Unit) {
    var id by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Add contact", color = TextPrimary, style = Mono.copy(fontSize = 16.sp))
        Spacer(Modifier.height(16.dp))
        FieldBox(width = 320.dp) {
            TextInputRaw(
                value = id,
                onChange = { id = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(32) },
                placeholder = "8-char ID (e.g. A3F2B910)",
            )
        }
        Spacer(Modifier.height(16.dp))
        Row {
            DarkButton(label = "Add", onClick = { onAdd(id) })
            Spacer(Modifier.width(8.dp))
            DarkButton(label = "Cancel", onClick = onCancel)
        }
    }
}

// ── Chat ─────────────────────────────────────────────────────────────────────

@Composable
private fun ChatScreen(
    peerId: String,
    isGroup: Boolean,
    displayName: String,
    messages: SnapshotStateList<ChatMessage>,
    repo: Repository,
    onSend: (String, String, String?) -> Unit,
    onSendPhoto: (() -> Unit)? = null,
    onBack: () -> Unit,
    onCall: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var peerPubkey by remember { mutableStateOf<String?>(null) }
    var replyTarget by remember { mutableStateOf<ChatMessage?>(null) }

    LaunchedEffect(peerId) {
        if (isGroup) {
            peerPubkey = "group"
            fingerprint = ""
        } else {
            val pk = runCatching { repo.fetchPeerPublicKey(peerId) }.getOrNull()
            peerPubkey = pk
            fingerprint = Crypto.pubkeyFingerprint(pk)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DarkButton(label = "Back", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(displayName, color = TextPrimary, style = Mono)
            Spacer(Modifier.width(12.dp))
            if (isGroup) {
                Text("(group)", color = TextMuted, style = Mono.copy(fontSize = 11.sp))
            } else if (fingerprint.isNotEmpty()) {
                Text(fingerprint, color = TextSecondary, style = Mono.copy(fontSize = 14.sp))
            } else {
                Text("(no key yet)", color = TextMuted, style = Mono.copy(fontSize = 11.sp))
            }
            if (!isGroup) {
                Spacer(Modifier.width(12.dp))
                DarkButton(label = "Call", onClick = onCall)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.height(1.dp).fillMaxWidth().background(Line))
        Spacer(Modifier.height(8.dp))

        // Message list.
        Box(
            Modifier
                .fillMaxWidth()
                .height(340.dp)
                .background(Surface, RoundedCornerShape(4.dp))
                .padding(8.dp),
        ) {
            if (messages.isEmpty()) {
                Text(
                    "(empty)",
                    color = TextMuted,
                    style = Mono.copy(fontSize = 12.sp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(messages.size) { idx ->
                        val msg = messages[idx]
                        val replyPreview: String? = msg.replyTo?.let { rid ->
                            val target: ChatMessage? = messages.firstOrNull { m -> m.id == rid }
                            target?.let { t -> "${t.author}: ${t.text.take(60)}" }
                        }
                        MessageRow(
                            msg = msg,
                            replyPreview = replyPreview,
                            onReply = { replyTarget = msg },
                        )
                    }
                }
            }
        }

        // Reply banner.
        replyTarget?.let { rt ->
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF181818), RoundedCornerShape(4.dp))
                    .border(1.dp, Line, RoundedCornerShape(4.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "↩ ${rt.author}: ${rt.text.take(60)}",
                    color = TextSecondary,
                    style = Mono.copy(fontSize = 11.sp),
                    modifier = Modifier.padding(end = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                DarkButton(label = "x", onClick = { replyTarget = null })
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldBox(width = 380.dp) {
                TextInputRaw(
                    value = input,
                    onChange = { input = it },
                    placeholder = "Type message...",
                )
            }
            Spacer(Modifier.width(8.dp))
            if (onSendPhoto != null) {
                DarkButton(label = "📷", onClick = onSendPhoto)
                Spacer(Modifier.width(8.dp))
            }
            DarkButton(
                label = "Send",
                onClick = {
                    val text = input.trim()
                    val pk = peerPubkey ?: return@DarkButton
                    if (text.isEmpty()) return@DarkButton
                    onSend(text, pk, replyTarget?.id)
                    input = ""
                    replyTarget = null
                },
                enabled = peerPubkey != null,
            )
        }
    }
}

/**
 * One message row. Click anywhere on a non-Me message → set reply target.
 * Ticks (✓ / ✓✓) render to the right of "Me" bubbles only.
 */
@Composable
private fun MessageRow(msg: ChatMessage, replyPreview: String?, onReply: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onReply)
            .padding(vertical = 4.dp),
    ) {
        if (replyPreview != null) {
            Text(
                "↳ $replyPreview",
                color = TextMuted,
                style = Mono.copy(fontSize = 10.sp),
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        Row {
            val authorColor = if (msg.author == "Me") Accent else TextSecondary
            Text(
                "${msg.author}: ",
                color = authorColor,
                style = Mono.copy(fontSize = 12.sp),
            )
            val photoPath = parsePhotoPath(msg.text)
            if (photoPath != null) {
                Text(
                    "📷 ${File(photoPath).name}",
                    color = Accent,
                    style = Mono.copy(fontSize = 12.sp),
                    modifier = Modifier.clickable {
                        runCatching { Desktop.getDesktop().open(File(photoPath)) }
                    },
                )
            } else {
                Text(msg.text, color = TextPrimary, style = Mono.copy(fontSize = 12.sp))
            }
            if (msg.author == "Me") {
                Spacer(Modifier.width(6.dp))
                Text(
                    statusTick(msg.status),
                    color = if (msg.status == MsgStatus.READ) Accent else TextMuted,
                    style = Mono.copy(fontSize = 11.sp),
                )
            }
        }
    }
}

private fun parsePhotoPath(text: String): String? {
    if (!text.startsWith("[PHOTO:")) return null
    val end = text.indexOf(']')
    if (end <= 7) return null
    return text.substring(7, end)
}

private fun statusTick(status: MsgStatus): String = when (status) {
    MsgStatus.UNKNOWN -> "·"
    MsgStatus.SENT -> "✓"
    MsgStatus.DELIVERED -> "✓✓"
    MsgStatus.READ -> "✓✓"
}

/** Append a chat message (creates the per-chat list lazily). */
private fun appendMessage(
    chatLogs: MutableMap<String, SnapshotStateList<ChatMessage>>,
    chatId: String,
    msgId: String,
    author: String,
    text: String,
    ts: Long,
    replyTo: String? = null,
    initialStatus: MsgStatus = MsgStatus.UNKNOWN,
) {
    val list = chatLogs.getOrPut(chatId) { mutableStateListOf() }
    // Deduplicate on msgId so the poller doesn't double-append after a
    // server-side retry.
    if (list.any { it.id == msgId }) return
    list.add(ChatMessage(msgId, author, text, ts, replyTo, initialStatus))
}

// ── Reusable widgets ─────────────────────────────────────────────────────────

@Composable
private fun DarkButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .background(if (enabled) Surface else Color(0xFF080808), RoundedCornerShape(6.dp))
            .border(1.dp, Line, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (enabled) TextPrimary else TextMuted, style = Mono.copy(fontSize = 12.sp))
    }
}

@Composable
private fun FieldBox(width: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(
        Modifier
            .width(width)
            .background(Surface, RoundedCornerShape(4.dp))
            .border(1.dp, Line, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        content()
    }
}

@Composable
private fun TextInputRaw(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    mask: Boolean = false,
) {
    if (value.isEmpty()) {
        Text(placeholder, color = TextMuted, style = Mono.copy(fontSize = 12.sp))
    }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        cursorBrush = SolidColor(Accent),
        textStyle = Mono.copy(fontSize = 13.sp, color = TextPrimary),
        visualTransformation = if (mask) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── AWT file dialog (no Compose file picker in 1.6.x stable) ─────────────────

private fun openPhotoDialog(): File? {
    val fd = FileDialog(null as Frame?, "Pick photo", FileDialog.LOAD).apply {
        setFilenameFilter { _, name ->
            name.endsWith(".jpg", true) ||
                name.endsWith(".jpeg", true) ||
                name.endsWith(".png", true) ||
                name.endsWith(".webp", true) ||
                name.endsWith(".heic", true)
        }
        isVisible = true
    }
    val name = fd.file ?: return null
    val dir = fd.directory ?: return null
    return File(dir, name)
}

private fun saveBackupDialog(): File? {
    val fd = FileDialog(null as Frame?, "Save backup", FileDialog.SAVE).apply {
        file = "xlink_backup_${System.currentTimeMillis()}.xlinkbak"
        isVisible = true
    }
    val name = fd.file ?: return null
    val dir = fd.directory ?: return null
    return File(dir, if (name.endsWith(".xlinkbak", true)) name else "$name.xlinkbak")
}

private fun openBackupDialog(): File? {
    val fd = FileDialog(null as Frame?, "Open backup", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.endsWith(".xlinkbak", true) }
        isVisible = true
    }
    val name = fd.file ?: return null
    val dir = fd.directory ?: return null
    return File(dir, name)
}

private fun openImageDialog(): File? {
    val fd = FileDialog(null as Frame?, "Send photo", FileDialog.LOAD).apply {
        setFilenameFilter { _, name ->
            name.endsWith(".jpg", true) ||
                name.endsWith(".jpeg", true) ||
                name.endsWith(".png", true) ||
                name.endsWith(".webp", true) ||
                name.endsWith(".heic", true)
        }
        isVisible = true
    }
    val name = fd.file ?: return null
    val dir = fd.directory ?: return null
    return File(dir, name)
}

/**
 * Re-derive the EC keypair from the photo+v2 secret cache. Repository
 * already holds it as a private field; we'd normally lift it out, but
 * PhotoTransferController takes a fresh KeyPair so it can decrypt the
 * wrapped AES key on the incoming side without touching Repository
 * internals.
 */
private fun keyPairFromPrefs(prefs: SecurePrefs, photoSecret: ByteArray): java.security.KeyPair {
    val v2 = prefs.getString("v2_crypto_secret")
    val secret = if (v2 != null) Crypto.b64decode(v2) else photoSecret
    return Crypto.deriveEcKeyPair(secret)
}

// ── Lock + Settings overlays ─────────────────────────────────────────────────

@Composable
private fun LockOverlay(
    pin: String,
    onPinChange: (String) -> Unit,
    error: String,
    onUnlock: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xEE000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(360.dp)
                .background(Surface, RoundedCornerShape(10.dp))
                .border(1.dp, Line, RoundedCornerShape(10.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Locked", color = TextPrimary, style = Mono.copy(fontSize = 16.sp))
            Spacer(Modifier.height(16.dp))
            FieldBox(width = 280.dp) {
                TextInputRaw(
                    value = pin,
                    onChange = onPinChange,
                    placeholder = "PIN",
                    mask = true,
                )
            }
            Spacer(Modifier.height(12.dp))
            DarkButton(label = "Unlock", onClick = onUnlock, enabled = pin.length >= 4)
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = Color(0xFFFF4040), style = Mono.copy(fontSize = 11.sp))
            }
        }
    }
}

@Composable
private fun SettingsOverlay(
    pinEnabled: Boolean,
    statusMessage: String,
    onSetPin: (String) -> Unit,
    onDisablePin: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onClose: () -> Unit,
) {
    var newPin by remember { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().background(Color(0xEE000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(420.dp)
                .background(Surface, RoundedCornerShape(10.dp))
                .border(1.dp, Line, RoundedCornerShape(10.dp))
                .padding(24.dp),
        ) {
            Text("Settings", color = TextPrimary, style = Mono.copy(fontSize = 16.sp))
            Spacer(Modifier.height(16.dp))
            Text("App lock", color = TextSecondary, style = Mono.copy(fontSize = 12.sp))
            Spacer(Modifier.height(6.dp))
            FieldBox(width = 320.dp) {
                TextInputRaw(
                    value = newPin,
                    onChange = { newPin = it.filter { c -> c.isDigit() }.take(16) },
                    placeholder = "New PIN (4-16 digits)",
                    mask = true,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                DarkButton(
                    label = if (pinEnabled) "Change PIN" else "Set PIN",
                    onClick = {
                        if (newPin.length >= 4) {
                            onSetPin(newPin)
                            newPin = ""
                        }
                    },
                    enabled = newPin.length >= 4,
                )
                if (pinEnabled) {
                    Spacer(Modifier.width(8.dp))
                    DarkButton(label = "Disable PIN", onClick = onDisablePin)
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.height(1.dp).fillMaxWidth().background(Line))
            Spacer(Modifier.height(16.dp))
            Text("Backup", color = TextSecondary, style = Mono.copy(fontSize = 12.sp))
            Spacer(Modifier.height(8.dp))
            Row {
                DarkButton(label = "Export...", onClick = onExportBackup)
                Spacer(Modifier.width(8.dp))
                DarkButton(label = "Import...", onClick = onImportBackup)
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.height(1.dp).fillMaxWidth().background(Line))
            Spacer(Modifier.height(12.dp))
            Text("Updates", color = TextSecondary, style = Mono.copy(fontSize = 12.sp))
            Spacer(Modifier.height(6.dp))
            Text(
                "Current: v${UpdateChecker.CURRENT_VERSION}",
                color = TextMuted, style = Mono.copy(fontSize = 11.sp),
            )
            Spacer(Modifier.height(8.dp))
            DarkButton(label = "Check for updates", onClick = onCheckForUpdates)
            if (statusMessage.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(statusMessage, color = TextSecondary, style = Mono.copy(fontSize = 11.sp))
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.height(1.dp).fillMaxWidth().background(Line))
            Spacer(Modifier.height(12.dp))
            DarkButton(label = "Close", onClick = onClose)
        }
    }
}
