package com.example.xxxlinkxxx

class Codec2Bridge(mode: Int) {

    companion object {
        init {
            System.loadLibrary("native-lib")
        }

        // Часто используют 3200, 2400, 1600.
        // Конкретные int-константы зависят от codec2.h.
        const val MODE_3200 = 0
        const val MODE_2400 = 1
        const val MODE_1600 = 2
    }

    private var nativePtr: Long = nativeCreate(mode)

    fun samplesPerFrame(): Int = nativeSamplesPerFrame(nativePtr)
    fun bitsPerFrame(): Int = nativeBitsPerFrame(nativePtr)

    fun encode(pcm: ShortArray): ByteArray {
        return nativeEncode(nativePtr, pcm) ?: ByteArray(0)
    }

    fun decode(data: ByteArray): ShortArray {
        return nativeDecode(nativePtr, data) ?: ShortArray(0)
    }

    fun release() {
        if (nativePtr != 0L) {
            nativeDestroy(nativePtr)
            nativePtr = 0L
        }
    }

    private external fun nativeCreate(mode: Int): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeSamplesPerFrame(ptr: Long): Int
    private external fun nativeBitsPerFrame(ptr: Long): Int
    private external fun nativeEncode(ptr: Long, pcm: ShortArray): ByteArray?
    private external fun nativeDecode(ptr: Long, data: ByteArray): ShortArray?
}