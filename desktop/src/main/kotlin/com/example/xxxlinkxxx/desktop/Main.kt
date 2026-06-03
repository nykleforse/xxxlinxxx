package com.example.xxxlinkxxx.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Desktop entry point.
 *
 * Renders the App() Composable defined in App.kt. App.kt owns the screen
 * state machine (login / contact list / add contact / chat) and wires the
 * Firebase REST client, EC ↔ AES-GCM crypto helpers and the on-disk vault
 * together. Voice calling is out of scope for this MVP — milestones M5..M7
 * will add Codec2.dll, javax.sound.sampled audio and WebRTC bindings.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "XxxLink Desktop · v1.15.8 · chat-only MVP",
        state = rememberWindowState(width = 640.dp, height = 560.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            App()
        }
    }
}
