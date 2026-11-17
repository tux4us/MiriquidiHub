// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Hält das Kotlin-Gradle-Plugin im Classpath (JvmTarget-Import in app/build.gradle.kts).
    alias(libs.plugins.kotlin.compose) apply false
}