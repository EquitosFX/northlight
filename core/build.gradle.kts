import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin, no Android: playlist and guide parsing, channel grouping and guide matching.
// Keeping this separate means it can be compiled and tested on any machine.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
