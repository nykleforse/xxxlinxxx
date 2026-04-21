package com.example.xxxlinkxxx

import android.media.*
import java.util.concurrent.atomic.AtomicBoolean

class PcmAudioEngine(
    private val codec2: Codec2Bridge,
    private val onEncodedFrame: (ByteArray) -> Unit
) {
    private val sampleRate = 8000
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null

    private val recording = AtomicBoolean(false)

    fun start() {
        val minRecBuf = AudioRecord.getMinBufferSize(sampleRate, channelIn, audioFormat)
        val minPlayBuf = AudioTrack.getMinBufferSize(sampleRate, channelOut, audioFormat)

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelIn,
            audioFormat,
            maxOf(minRecBuf, 4096)
        )

        player = AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            sampleRate,
            channelOut,
            audioFormat,
            maxOf(minPlayBuf, 4096),
            AudioTrack.MODE_STREAM
        )

        player?.play()
        recorder?.startRecording()

        recording.set(true)

        Thread {
            val samplesPerFrame = codec2.samplesPerFrame()
            val frame = ShortArray(samplesPerFrame)

            while (recording.get()) {
                val read = recorder?.read(frame, 0, frame.size) ?: 0
                if (read == frame.size) {
                    val encoded = codec2.encode(frame)
                    if (encoded.isNotEmpty()) {
                        onEncodedFrame(encoded)
                    }
                }
            }
        }.start()
    }

    fun playEncodedFrame(data: ByteArray) {
        val pcm = codec2.decode(data)
        if (pcm.isNotEmpty()) {
            player?.write(pcm, 0, pcm.size)
        }
    }

    fun stop() {
        recording.set(false)

        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null

        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
    }
}