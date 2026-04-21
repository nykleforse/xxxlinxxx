package com.example.p2pcodec2

class Codec2Bridge(mode: Int = 3200) {
    private var handle: Long = nativeCreate(mode)

    fun encode(shorts: ShortArray): ByteArray = nativeEncode(handle, shorts)
    fun decode(bytes: ByteArray): ShortArray = nativeDecode(handle, bytes)
    fun samplesPerFrame(): Int = nativeSamplesPerFrame(handle)
    fun bytesPerFrame(): Int = nativeBytesPerFrame(handle)

    fun release() = nativeDestroy(handle)

    private external fun nativeCreate(mode: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSamplesPerFrame(handle: Long): Int
    private external fun nativeBytesPerFrame(handle: Long): Int
    private external fun nativeEncode(handle: Long, pcm: ShortArray): ByteArray
    private external fun nativeDecode(handle: Long, encoded: ByteArray): ShortArray

    companion object {
        init {
            System.loadLibrary("codec2_bridge")
        }
    }
}
