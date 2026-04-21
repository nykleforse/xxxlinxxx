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

// Опционально: если gradle ругается на project repositories,
// можно добавить безопасное управление репозиториями в settings.gradle.kts.
// Здесь ничего больше не нужно.
