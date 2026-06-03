package com.example.xxxlinkxxx.desktop.util

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Tiny in-memory ring buffer for runtime events — Firestore poll results,
 * call signaling steps, audio engine state, etc. Drained by the Settings
 * → "View log" UI so users + bug reporters can see what the app is doing
 * without rummaging through stderr.
 *
 * Keeps the last 500 lines so a long-running session doesn't grow
 * unbounded. Thread-safe through the SnapshotStateList contract — the
 * `add` call is locked internally.
 */
object EventLog {
    private const val MAX_LINES = 500
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    val lines: SnapshotStateList<String> = mutableStateListOf()

    fun log(tag: String, msg: String) {
        val ts = LocalTime.now().format(fmt)
        val line = "$ts $tag $msg"
        synchronized(lines) {
            lines.add(line)
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
    }
}
