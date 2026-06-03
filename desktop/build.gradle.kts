import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

group = "com.example.xxxlinkxxx"
version = "1.15.8"

// Repositories live in settings.gradle.kts under FAIL_ON_PROJECT_REPOS.

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")

    // Crypto / utility — kept pure-JVM for now, will share with Android in M2.
    implementation("org.json:json:20240303")
}

compose.desktop {
    application {
        mainClass = "com.example.xxxlinkxxx.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "XxxLinkDesktop"
            packageVersion = "1.15.8"
            description = "xxxlinkxxx desktop client"
            vendor = "nykleforse"

            windows {
                menuGroup = "XxxLink"
                upgradeUuid = "B6D2C3A4-1F5E-4A9D-B8C7-1234567890AB"
            }
        }
    }
}
