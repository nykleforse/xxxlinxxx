// Корневой build.gradle.kts — в нём объявляются плагины и репозитории для всех модулей

buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.3.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.23")
        classpath("com.google.gms:google-services:4.4.1")
    }
}

// Compose Multiplatform plugin classpath for the :desktop module.
// Declared with apply false so :app doesn't pull it in. Version pinned
// to a build compatible with Kotlin 1.9.23.
plugins {
    id("org.jetbrains.compose") version "1.6.11" apply false
}

// Опционально: если gradle ругается на project repositories,
// можно добавить безопасное управление репозиториями в settings.gradle.kts.
// Здесь ничего больше не нужно.
