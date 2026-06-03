/**
 * Desktop counterpart to app/src/main/java/com/example/p2pcodec2/Codec2Bridge.kt.
 *
 * Identical package + class + external method names so the same JNI
 * symbol set works on both Android and desktop without recompiling
 * the native side — the .dll just has to exist on the desktop loader
 * search path (see Codec2Loader).
 *
 * Throws UnsatisfiedLinkError on any call if Codec2Loader.isAvailable()
 * returned false at startup. Guard at the call site.
 */
package com.example.p2pcodec2

import com.example.xxxlinkxxx.desktop.audio.Codec2Loader

class Codec2Bridge(mode: Int = MODE_3200) {
    private var handle: Long = nativeCreate(mode)

    fun encode(shorts: ShortArray): ByteArray? = nativeEncode(handle, shorts)
    fun decode(bytes: ByteArray): ShortArray? = nativeDecode(handle, bytes)
    fun samplesPerFrame(): Int = nativeSamplesPerFrame(handle)
    fun bytesPerFrame(): Int = nativeBytesPerFrame(handle)

    fun release() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0L
    }

    private external fun nativeCreate(mode: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSamplesPerFrame(handle: Long): Int
    private external fun nativeBytesPerFrame(handle: Long): Int
    private external fun nativeEncode(handle: Long, pcm: ShortArray): ByteArray?
    private external fun nativeDecode(handle: Long, encoded: ByteArray): ShortArray?

    companion object {
        const val MODE_3200 = Codec2Loader.MODE_3200
        const val MODE_2400 = Codec2Loader.MODE_2400
        const val MODE_1600 = Codec2Loader.MODE_1600
        const val MODE_1400 = Codec2Loader.MODE_1400
        const val MODE_1300 = Codec2Loader.MODE_1300
        const val MODE_1200 = Codec2Loader.MODE_1200
        const val MODE_450 = Codec2Loader.MODE_450

        init {
            // Triggers Codec2Loader to attempt the System.load on whatever
            // bundled .dll/.so/.dylib is reachable. If nothing is found
            // we still allow construction; the native* calls will throw
            // and callers can fall back to text-only mode.
            Codec2Loader.isAvailable()
        }
    }
}
