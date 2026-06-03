import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

group = "com.example.xxxlinkxxx"
version = "1.15.8"

// Repositories live in settings.gradle.kts under FAIL_ON_PROJECT_REPOS.

kotlin {
    // Pin to a Temurin/Adoptium JDK so Foojay auto-downloads a build that
    // ships jpackage — JBR (the JDK bundled with Android Studio) omits
    // jpackage which blocks the Compose Desktop createDistributable /
    // packageMsi / packageExe tasks.
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")

    // Crypto / utility — kept pure-JVM for now, will share with Android in M2.
    implementation("org.json:json:20240303")

    // WebRTC for desktop. devopvoid bundles pre-built native libs for
    // Windows x64 + Linux x64 + macOS arm64/x64 directly in the JAR, so
    // there is no manual libwebrtc compile step for end users.
    // M7 voice-call wiring lives in desktop/.../call/VoiceCallController.kt.
    implementation("dev.onvoid.webrtc:webrtc-java:0.10.0")
    implementation("dev.onvoid.webrtc:webrtc-java:0.10.0:windows-x86_64")
    implementation("dev.onvoid.webrtc:webrtc-java:0.10.0:linux-x86_64")
    implementation("dev.onvoid.webrtc:webrtc-java:0.10.0:macos-x86_64")
}

// Point the Compose Desktop checkRuntime / jpackage tasks at the Adoptium
// JDK that the kotlin toolchain auto-downloaded (JBR omits jpackage).
val adoptiumLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}

compose.desktop {
    application {
        mainClass = "com.example.xxxlinkxxx.desktop.MainKt"
        javaHome = adoptiumLauncher.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "XxxLinkDesktop"
            packageVersion = "1.15.8"
            description = "xxxlinkxxx desktop client"
            vendor = "nykleforse"

            // Per-OS app resource roots. The codec2_bridge.dll under
            // resources/windows-x64/ gets copied into the installer next to
            // the launcher.exe, picked up at runtime via the system
            // property `compose.application.resources.dir`. Codec2Loader
            // checks that path first so voice calls work out of the box
            // without the user installing MinGW.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            windows {
                menuGroup = "XxxLink"
                upgradeUuid = "B6D2C3A4-1F5E-4A9D-B8C7-1234567890AB"
                // Per-user install with no admin prompt — friends-and-family
                // distribution path. They double-click the .exe, click Next,
                // it lands in %LOCALAPPDATA%\XxxLinkDesktop\.
                perUserInstall = true
                shortcut = true
                dirChooser = false
            }
        }
    }
}
