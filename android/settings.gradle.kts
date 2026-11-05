// Google's repository serves the Android plugin and AndroidX, and only the
// Android app needs it; without an SDK it is left out, so a machine that
// cannot reach it still builds the JVM modules.
fun androidSdk(): String? = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
val withAndroid = androidSdk()?.let { file(it).exists() } == true

pluginManagement {
    repositories {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: file("local.properties").takeIf { it.exists() }?.readLines()
                ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
        if (sdk != null && file(sdk).exists()) google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (withAndroid) google()
        mavenCentral()
    }
}

rootProject.name = "kinclude-android"

// KithMoot's protocol in Kotlin, from the pinned kithmoot-android submodule:
// rooms, epochs, rekeys, group invitations, credentials, NIP-44, call
// signalling. Pure JVM, with its own interop vector tests.
include(":kithmoot-protocol")
project(":kithmoot-protocol").projectDir = file("vendor/kithmoot-android/protocol")

// Kinclude's family layer on top of it: statements, tiers, approvals, room
// state and rules, the room engine. Pure JVM, checked against the
// TypeScript library's vectors.
include(":core")

// Relays over OkHttp: pure JVM, tested against a real WebSocket relay.
include(":net")

// The Android app needs the Android SDK. Leave it out where there is none,
// so the JVM modules still build and test.
if (withAndroid) include(":app")
