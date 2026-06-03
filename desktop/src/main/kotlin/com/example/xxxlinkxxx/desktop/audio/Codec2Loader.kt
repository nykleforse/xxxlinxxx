package com.example.xxxlinkxxx.desktop.audio

import java.io.File

/**
 * Locates and loads the codec2_bridge native library at startup.
 *
 * The native side is identical to the Android JNI shim
 * (app/src/main/cpp/codec2_bridge.cpp) — same JNI symbol names
 * (Java_com_example_p2pcodec2_Codec2Bridge_native*), same mode integers,
 * same encode/decode contract. The Android shim is built into the APK
 * by CMake. For desktop we need an equivalent libcodec2_bridge.dll
 * (Windows x64) or .so / .dylib for Linux / macOS — built separately
 * via M5 (CMake cross-build with MinGW-w64 or MSVC).
 *
 * Loading order:
 *  1. {user.home}/.xxxlink/native/{os}-{arch}/libcodec2_bridge.{ext}
 *     (M8 packaging will drop the right file there at install time).
 *  2. ./native/{os}-{arch}/ relative to the launching JAR.
 *  3. Fall back to System.loadLibrary("codec2_bridge") which honours
 *     java.library.path.
 *
 * If no library is found, encode/decode calls raise UnsatisfiedLinkError.
 * Voice-call UI must check Codec2Loader.isAvailable() and degrade
 * gracefully (text-only mode) instead of crashing.
 */
object Codec2Loader {
    const val MODE_3200 = 0
    const val MODE_2400 = 1
    const val MODE_1600 = 2
    const val MODE_1400 = 3
    const val MODE_1300 = 4
    const val MODE_1200 = 5
    const val MODE_450 = 8

    @Volatile private var loaded = false
    @Volatile private var loadError: Throwable? = null

    fun isAvailable(): Boolean {
        ensureLoaded()
        return loaded
    }

    fun loadError(): Throwable? = loadError

    @Synchronized
    private fun ensureLoaded() {
        if (loaded || loadError != null) return
        for (file in nativeSearchPath()) {
            if (!file.exists()) continue
            try {
                System.load(file.absolutePath)
                loaded = true
                return
            } catch (t: Throwable) {
                loadError = t
            }
        }
        try {
            System.loadLibrary("codec2_bridge")
            loaded = true
            loadError = null
        } catch (t: Throwable) {
            loadError = t
        }
    }

    private fun nativeSearchPath(): List<File> {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val osTag = when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            else -> "linux"
        }
        val archTag = when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            else -> "x86_64"
        }
        val libName = when (osTag) {
            "windows" -> "codec2_bridge.dll"
            "macos" -> "libcodec2_bridge.dylib"
            else -> "libcodec2_bridge.so"
        }
        val userHome = System.getProperty("user.home")
        val workDir = System.getProperty("user.dir")
        return listOf(
            File(File(userHome, ".xxxlink"), "native/$osTag-$archTag/$libName"),
            File(File(workDir, "native"), "$osTag-$archTag/$libName"),
            File("native/$osTag-$archTag/$libName"),
        )
    }
}
