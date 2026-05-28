package com.example.p2pcodec2

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
        const val MODE_3200 = 0
        const val MODE_2400 = 1
        const val MODE_1600 = 2
        const val MODE_1400 = 3
        const val MODE_1300 = 4
        const val MODE_1200 = 5
        const val MODE_450  = 8

        init {
            System.loadLibrary("codec2_bridge")
        }
    }
}
