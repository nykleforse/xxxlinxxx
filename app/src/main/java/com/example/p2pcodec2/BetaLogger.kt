package com.example.p2pcodec2

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * Captures the app's own logcat output to a rotating file in internal storage,
 * so beta testers can ship the developer a single .txt file of everything that
 * happened during the session.
 *
 * Notes:
 *  - Since Android 4.1 (API 16), `logcat` from within an app only sees that
 *    app's own log entries — no permission is needed and no other app's logs
 *    are exposed.
 *  - Capture only runs in beta builds (caller decides). Production should not
 *    spend cycles on this.
 *  - Rotation: when the current file exceeds [MAX_FILE_BYTES], it's renamed
 *    to `log.1.txt` (overwriting any existing `log.1.txt`) and a fresh
 *    `log.txt` is created. Only one rotated file is kept.
 */
object BetaLogger {

    private const val TAG = "BetaLogger"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L  // 2 MB
    private const val LOG_DIR = "beta_logs"
    private const val CURRENT_FILE = "log.txt"
    private const val ROTATED_FILE = "log.1.txt"

    @Volatile private var captureJob: Job? = null
    @Volatile private var logcatProcess: Process? = null

    /** Returns the directory under filesDir where logs are persisted. */
    fun logsDir(context: Context): File =
        File(context.filesDir, LOG_DIR).apply { mkdirs() }

    /** Current (un-rotated) log file. May not exist yet if capture has not started. */
    fun currentLogFile(context: Context): File =
        File(logsDir(context), CURRENT_FILE)

    fun rotatedLogFile(context: Context): File =
        File(logsDir(context), ROTATED_FILE)

    /**
     * Begin capturing the app's logcat stream into [currentLogFile]. Safe to call
     * multiple times — second call is a no-op while an existing capture is alive.
     *
     * @param scope Coroutine scope that controls the capture lifetime. Capture
     *              stops when the scope is cancelled.
     */
    fun start(context: Context, scope: CoroutineScope) {
        if (captureJob?.isActive == true) return
        val dir = logsDir(context)
        val current = File(dir, CURRENT_FILE)
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                // -v threadtime: timestamp + tid info, easy to diff later.
                // -T 1: start from the most recent existing line (so we don't
                //       re-capture the whole ring buffer every launch).
                val proc = ProcessBuilder("logcat", "-v", "threadtime", "-T", "1")
                    .redirectErrorStream(true)
                    .start()
                logcatProcess = proc
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                appendSessionBanner(current)
                var written = current.length()
                reader.useLines { lines ->
                    for (line in lines) {
                        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                        FileOutputStream(current, true).use { it.write(bytes) }
                        written += bytes.size
                        if (written > MAX_FILE_BYTES) {
                            rotate(dir)
                            written = 0
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "logcat capture stopped: ${e.message}")
            } finally {
                logcatProcess?.destroy()
                logcatProcess = null
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        logcatProcess?.destroy()
        logcatProcess = null
    }

    /** Resets the current log file and any rotated copy. Useful for "Clear logs". */
    suspend fun clear(context: Context) {
        withContext(Dispatchers.IO) {
            currentLogFile(context).delete()
            rotatedLogFile(context).delete()
        }
    }

    /**
     * Returns a single concatenated log spanning the rotated + current files.
     * The caller can hand this to a share Intent without worrying about ordering.
     */
    suspend fun snapshot(context: Context): File = withContext(Dispatchers.IO) {
        val combined = File(logsDir(context), "snapshot-${System.currentTimeMillis()}.txt")
        FileOutputStream(combined).use { out ->
            // Older entries first
            rotatedLogFile(context).takeIf { it.exists() }?.inputStream()?.use { it.copyTo(out) }
            currentLogFile(context).takeIf { it.exists() }?.inputStream()?.use { it.copyTo(out) }
        }
        combined
    }

    private fun rotate(dir: File) {
        val current = File(dir, CURRENT_FILE)
        val rotated = File(dir, ROTATED_FILE)
        if (rotated.exists()) rotated.delete()
        if (current.exists()) current.renameTo(rotated)
    }

    private fun appendSessionBanner(file: File) {
        val banner = "\n=== beta-log session start ${System.currentTimeMillis()} ===\n"
        FileOutputStream(file, true).use { it.write(banner.toByteArray(Charsets.UTF_8)) }
    }
}
