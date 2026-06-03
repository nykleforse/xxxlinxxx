package com.example.xxxlinkxxx.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Desktop entry point — M1 milestone bootstrap.
 *
 * Currently a placeholder Compose Desktop window so we can verify the
 * Gradle + Compose toolchain end-to-end on Windows before porting any
 * real screens. See .agents/ workflow + desktop-port milestone plan
 * in MEMORY for the M2–M8 follow-ups (crypto, Firebase JVM, real UI,
 * Codec2 dll, WebRTC bindings, MSI packaging).
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "XxxLink Desktop · v1.15.8 · M1 skeleton",
        state = rememberWindowState(width = 480.dp, height = 320.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            DesktopPlaceholder()
        }
    }
}

@Composable
private fun DesktopPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "XxxLink Desktop",
                color = Color(0xFFB8B8B8),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(4.dp),
            )
            Text(
                text = "M1: Compose Desktop bootstrap",
                color = Color(0xFF888888),
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "Next: crypto + Firestore (M2/M3)",
                color = Color(0xFF5F5F5F),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
