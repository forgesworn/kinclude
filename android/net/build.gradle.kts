import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Relays over OkHttp WebSockets. Pure JVM, so it is tested here against a
// real relay (test/ws-relay.mjs), not only on a phone.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}
tasks.named<JavaCompile>("compileTestJava") {
    sourceCompatibility = JavaVersion.VERSION_21.toString()
    targetCompatibility = JavaVersion.VERSION_21.toString()
}

dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.secp256k1.jni.jvm)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    systemProperty("kinclude.root", rootProject.file("..").absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
