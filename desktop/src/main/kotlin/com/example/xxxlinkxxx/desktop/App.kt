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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xxxlinkxxx.desktop.crypto.Crypto
import com.example.xxxlinkxxx.desktop.net.FirebaseClient
import com.example.xxxlinkxxx.desktop.net.Repository
import com.example.xxxlinkxxx.desktop.storage.SecurePrefs
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

// ── App state machine ────────────────────────────────────────────────────────

private sealed interface AppScreen {
    object Login : AppScreen
    data class Authenticated(val repo: Repository, val prefs: SecurePrefs) : AppScreen
}

private sealed interface AuthedSub {
    object ContactList : AuthedSub
    object AddContact : AuthedSub
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
                onAuthenticated = { repo, prefs ->
                    screen = AppScreen.Authenticated(repo, prefs)
                }
            )
            is AppScreen.Authenticated -> AuthedRoot(
                repo = s.repo,
                prefs = s.prefs,
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
    onAuthenticated: (Repository, SecurePrefs) -> Unit,
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
                    }.onSuccess { (repo, prefs) ->
                        busy = false
                        onAuthenticated(repo, prefs)
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
): Pair<Repository, SecurePrefs> = withContext(Dispatchers.IO) {
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
    repo to prefs
}

// ── Authenticated root: contacts list / chat / add-contact ───────────────────

@Composable
private fun AuthedRoot(
    repo: Repository,
    prefs: SecurePrefs,
    onLogout: () -> Unit,
) {
    var sub: AuthedSub by remember { mutableStateOf<AuthedSub>(AuthedSub.ContactList) }
    val contacts = remember {
        mutableStateListOf<String>().also { it.addAll(prefs.getStringSet("contact_ids")) }
    }
    val chatLogs = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repo.localId) {
        while (true) {
            runCatching {
                val msgs = repo.pollInbox()
                for (m in msgs) {
                    val prev = chatLogs[m.from] ?: ""
                    val line = "${m.from}: ${m.text}"
                    chatLogs[m.from] = if (prev.isEmpty()) line else "$prev\n$line"
                    if (m.from !in contacts) {
                        contacts.add(m.from)
                        prefs.edit().putStringSet("contact_ids", contacts.toSet()).apply()
                    }
                    runCatching { repo.writeReceipt(m.id, m.from) }
                }
            }
            delay(5_000)
        }
    }

    when (val s = sub) {
        AuthedSub.ContactList -> ContactListScreen(
            localId = repo.localId,
            contacts = contacts,
            onAdd = { sub = AuthedSub.AddContact },
            onPick = { sub = AuthedSub.Chat(it) },
            onLogout = onLogout,
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
        is AuthedSub.Chat -> ChatScreen(
            peerId = s.peerId,
            log = chatLogs[s.peerId] ?: "",
            repo = repo,
            onSend = { text, pk ->
                scope.launch {
                    runCatching { repo.sendEncryptedMessage(s.peerId, text, pk) }
                        .onSuccess {
                            val prev = chatLogs[s.peerId] ?: ""
                            val line = "Me: $text"
                            chatLogs[s.peerId] = if (prev.isEmpty()) line else "$prev\n$line"
                        }
                }
            },
            onBack = { sub = AuthedSub.ContactList },
        )
    }
}

// ── Contact list ─────────────────────────────────────────────────────────────

@Composable
private fun ContactListScreen(
    localId: String,
    contacts: List<String>,
    onAdd: () -> Unit,
    onPick: (String) -> Unit,
    onLogout: () -> Unit,
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
            DarkButton(label = "Log out", onClick = onLogout)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.height(1.dp).fillMaxWidth().background(Line))
        Spacer(Modifier.height(8.dp))

        if (contacts.isEmpty()) {
            Text(
                "No contacts yet. Press + Add and enter their 8-char ID.",
                color = TextMuted,
                style = Mono.copy(fontSize = 12.sp),
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
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
    log: String,
    repo: Repository,
    onSend: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var peerPubkey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(peerId) {
        val pk = runCatching { repo.fetchPeerPublicKey(peerId) }.getOrNull()
        peerPubkey = pk
        fingerprint = Crypto.pubkeyFingerprint(pk)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DarkButton(label = "Back", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(peerId, color = TextPrimary, style = Mono)
            Spacer(Modifier.width(12.dp))
            if (fingerprint.isNotEmpty()) {
                Text(fingerprint, color = TextSecondary, style = Mono.copy(fontSize = 14.sp))
            } else {
                Text("(no key yet)", color = TextMuted, style = Mono.copy(fontSize = 11.sp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.height(1.dp).fillMaxWidth().background(Line))
        Spacer(Modifier.height(8.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(Surface, RoundedCornerShape(4.dp))
                .padding(8.dp),
        ) {
            val scroll = rememberScrollState()
            Text(
                text = log.ifEmpty { "(empty)" },
                color = TextPrimary,
                style = Mono.copy(fontSize = 12.sp),
                modifier = Modifier.fillMaxSize().verticalScroll(scroll),
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldBox(width = 440.dp) {
                TextInputRaw(
                    value = input,
                    onChange = { input = it },
                    placeholder = "Type message...",
                )
            }
            Spacer(Modifier.width(8.dp))
            DarkButton(
                label = "Send",
                onClick = {
                    val text = input.trim()
                    val pk = peerPubkey ?: return@DarkButton
                    if (text.isEmpty()) return@DarkButton
                    onSend(text, pk)
                    input = ""
                },
                enabled = peerPubkey != null,
            )
        }
    }
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
