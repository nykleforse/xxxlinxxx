package com.example.xxxlinkxxx.desktop.call

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Bg = Color(0xEE000000)
private val Surface = Color(0xFF101010)
private val Line = Color(0xFF333333)
private val TextPrimary = Color(0xFFB8B8B8)
private val TextSecondary = Color(0xFF888888)
private val TextMuted = Color(0xFF5F5F5F)
private val AccentRed = Color(0xFFFF4040)
private val AccentGreen = Color(0xFF40FF80)

private val Mono = TextStyle(
    color = TextPrimary,
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
)

/**
 * Active-call full-screen overlay. Shown while CallUiState != Idle. The
 * three sub-states (Outgoing / Incoming / Active) render different
 * button layouts but share the same dark surface + timer / status row.
 */
sealed interface CallUiState {
    object Idle : CallUiState
    data class Outgoing(val peerId: String) : CallUiState
    data class Incoming(val callerId: String, val callId: String, val sessionId: String, val offerSdp: String) : CallUiState
    data class Active(val peerId: String, val startedAtMs: Long) : CallUiState
}

@Composable
fun CallOverlay(
    state: CallUiState,
    muted: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
) {
    if (state is CallUiState.Idle) return
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(420.dp)
                .background(Surface, RoundedCornerShape(10.dp))
                .border(1.dp, Line, RoundedCornerShape(10.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                CallUiState.Idle -> Unit
                is CallUiState.Outgoing -> {
                    Text("Outgoing call", color = TextSecondary, style = Mono.copy(fontSize = 12.sp))
                    Spacer(Modifier.height(12.dp))
                    Text(state.peerId, color = TextPrimary, style = Mono.copy(fontSize = 22.sp))
                    Spacer(Modifier.height(20.dp))
                    Text("Ringing...", color = TextMuted, style = Mono.copy(fontSize = 12.sp))
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.Center) {
                        PillButton("Hang up", AccentRed, onClick = onHangUp)
                    }
                }
                is CallUiState.Incoming -> {
                    Text("Incoming call", color = TextSecondary, style = Mono.copy(fontSize = 12.sp))
                    Spacer(Modifier.height(12.dp))
                    Text(state.callerId, color = TextPrimary, style = Mono.copy(fontSize = 22.sp))
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Accept", AccentGreen, onClick = onAccept)
                        PillButton("Decline", AccentRed, onClick = onDecline)
                    }
                }
                is CallUiState.Active -> {
                    var elapsedSecs by remember { mutableStateOf(0L) }
                    LaunchedEffect(state.startedAtMs) {
                        while (true) {
                            elapsedSecs = (System.currentTimeMillis() - state.startedAtMs) / 1000
                            delay(500)
                        }
                    }
                    Text("In call", color = TextSecondary, style = Mono.copy(fontSize = 12.sp))
                    Spacer(Modifier.height(8.dp))
                    Text(state.peerId, color = TextPrimary, style = Mono.copy(fontSize = 22.sp))
                    Spacer(Modifier.height(8.dp))
                    Text(formatTimer(elapsedSecs), color = TextSecondary, style = Mono.copy(fontSize = 14.sp))
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton(if (muted) "Unmute" else "Mute", TextPrimary, onClick = onToggleMute)
                        PillButton("Hang up", AccentRed, onClick = onHangUp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Surface, RoundedCornerShape(20.dp))
            .border(1.dp, accent, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, color = accent, style = Mono.copy(fontSize = 13.sp))
    }
}

private fun formatTimer(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
