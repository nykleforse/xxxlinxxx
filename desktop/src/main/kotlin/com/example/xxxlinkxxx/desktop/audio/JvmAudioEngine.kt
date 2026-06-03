package com.example.xxxlinkxxx.desktop.audio

import com.example.p2pcodec2.Codec2Bridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Desktop counterpart to app/src/main/java/com/example/xxxlinkxxxclaude/
 * PcmAudioEngine.kt. Reads 8 kHz mono PCM16 from the default input device
 * via javax.sound.sampled.TargetDataLine, encodes through Codec2, and
 * pumps the encoded frames out via the onEncodedFrame callback. Decoding
 * incoming frames writes PCM into a SourceDataLine fed from the default
 * output device.
 *
 * No hardware AEC / NoiseSuppressor / AGC on desktop — Java Sound has no
 * equivalent. WebRTC's own APM handles echo cancel + AGC + noise suppress
 * for the WebRTC audio track variant; this engine is used only with the
 * Codec2 / DataChannel voice modes (BASE, XTREAM). The COMFY mode runs
 * straight through libwebrtc's audio capture and bypasses this engine
 * entirely.
 */
class JvmAudioEngine(
    private val codec2: Codec2Bridge,
    private val onEncodedFrame: (ByteArray) -> Unit,
) {
    private var capture: TargetDataLine? = null
    private var playback: SourceDataLine? = null
    private var recordThread: Thread? = null

    @Volatile private var running = false
    @Volatile var micMuted: Boolean = false

    fun start() {
        if (running) return
        running = true

        val sampleRate = 8000
        val frameSize = codec2.samplesPerFrame().coerceAtLeast(160)
        val frameBytes = frameSize * 2

        val format = AudioFormat(
            sampleRate.toFloat(),
            16,
            1,
            true,  // signed
            false  // little-endian (matches Android PCM16)
        )

        val captureInfo = DataLine.Info(TargetDataLine::class.java, format)
        val playbackInfo = DataLine.Info(SourceDataLine::class.java, format)

        if (!AudioSystem.isLineSupported(captureInfo)) {
            error("No microphone capable of 8 kHz mono PCM16")
        }
        if (!AudioSystem.isLineSupported(playbackInfo)) {
            error("No speaker capable of 8 kHz mono PCM16")
        }

        val cap = AudioSystem.getLine(captureInfo) as TargetDataLine
        val play = AudioSystem.getLine(playbackInfo) as SourceDataLine

        cap.open(format, frameBytes * 4)
        play.open(format, frameBytes * 8)
        cap.start()
        play.start()

        capture = cap
        playback = play

        recordThread = Thread({
            val raw = ByteArray(frameBytes)
            val shorts = ShortArray(frameSize)
            val muted = ShortArray(frameSize)
            val mutedBytes = codec2.encode(muted) ?: ByteArray(0)
            while (running) {
                val read = try { cap.read(raw, 0, frameBytes) } catch (_: Throwable) { -1 }
                if (read != frameBytes) {
                    try { Thread.sleep(5) } catch (_: InterruptedException) { /* loop exits */ }
                    continue
                }
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                bb.get(shorts)
                if (micMuted) {
                    if (mutedBytes.isNotEmpty()) onEncodedFrame(mutedBytes)
                    continue
                }
                val encoded = try { codec2.encode(shorts) } catch (_: Throwable) { null }
                if (encoded != null) onEncodedFrame(encoded)
            }
        }, "codec2-record").apply { isDaemon = true; start() }
    }

    fun playEncodedFrame(bytes: ByteArray) {
        val play = playback ?: return
        val pcm = try { codec2.decode(bytes) } catch (_: Throwable) { null } ?: return
        val out = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        try { play.write(out, 0, out.size) } catch (_: Throwable) { /* line closed */ }
    }

    fun stop() {
        running = false
        recordThread?.interrupt()
        recordThread = null
        runCatching { capture?.stop(); capture?.close() }
        runCatching { playback?.drain(); playback?.stop(); playback?.close() }
        capture = null
        playback = null
    }
}
