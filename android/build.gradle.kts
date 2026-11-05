// The Android Gradle plugin goes on the build classpath only where there is
// an Android SDK (see settings.gradle.kts), so the pure JVM modules build and
// test on a machine without one. It must sit here, beside the Kotlin plugin,
// for the two to see each other.
buildscript {
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootDir.resolve("local.properties").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
    if (sdk != null && File(sdk).exists()) {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools.build:gradle:${libs.versions.agp.get()}") }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}
