import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
    java
}

group = "dev.tester"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    // Kotlin & Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // HTTP & HTML Parsing
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    implementation("org.jsoup:jsoup:1.18.3")

    // Aniyomi Ecosystem
    implementation(files("libs/anizen-source-api.jar"))
    implementation(files("libs/anizen-core-common.jar"))
    implementation(files("libs/androidx-preference.jar"))
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("com.github.null2264.injekt:injekt-core:4135455a2a")

    // Android framework classes for pure JVM
    implementation("org.robolectric:android-all:14-robolectric-10818077")

    // CLI & Terminal formatting
    implementation("com.github.ajalt.clikt:clikt:5.0.2")

    // Mocking & Android bytecode stubs
    implementation("io.mockk:mockk:1.13.13")
}

application {
    mainClass.set("dev.tester.MainKt")
}

