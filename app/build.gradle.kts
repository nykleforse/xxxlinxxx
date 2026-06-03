import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.example.xxxlinkxxx"
    compileSdk = 34
    ndkVersion = "26.1.10909125"
    val projectDebugKeystore = file("signing/debug.keystore")

    defaultConfig {
        applicationId = "com.example.xxxlinkxxx"
        minSdk = 23
        targetSdk = 34
        versionCode = 159
        versionName = "1.14.19-beta"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // TURN creds baked from local.properties. WARNING: visible to anyone
        // who decompiles the APK — replace with short-lived backend-issued
        // creds before production release.
        buildConfigField("String", "TURN_USERNAME", "\"${localProps.getProperty("turn.username", "")}\"")
        buildConfigField("String", "TURN_PASSWORD", "\"${localProps.getProperty("turn.password", "")}\"")
    }

    signingConfigs {
        getByName("debug") {
            if (projectDebugKeystore.exists()) {
                storeFile = projectDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("release") {
            // R8 minify + shrinkResources for smaller APK + harder reverse
            // engineering. WebRTC / Codec2 JNI / Firebase / Coroutines / Biometric
            // keep-rules live in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            if (projectDebugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
            if (projectDebugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Подключение CMake для сборки codec2_bridge и codec2
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // ===== WebRTC =====
    implementation("io.github.webrtc-sdk:android:144.7559.01")

    // ===== Firebase Firestore + Auth + Functions =====
    implementation(platform("com.google.firebase:firebase-bom:33.2.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-functions-ktx")

    // ===== Kotlin stdlib =====
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.23")

    // ===== WorkManager =====
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ===== QR (generation + scanning) =====
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }

    // ===== AndroidX UI =====
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ===== Biometric =====
    implementation("androidx.biometric:biometric:1.1.0")

    // ===== Encrypted SharedPreferences (Keystore-wrapped master key) =====
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
