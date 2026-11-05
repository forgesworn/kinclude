import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// As in :kithmoot-protocol: the JNI secp256k1 test runtime is JVM 21 only.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}
tasks.named<JavaCompile>("compileTestJava") {
    sourceCompatibility = JavaVersion.VERSION_21.toString()
    targetCompatibility = JavaVersion.VERSION_21.toString()
}

dependencies {
    api(project(":kithmoot-protocol"))
    implementation(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testRuntimeOnly(libs.secp256k1.jni.jvm)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    // The TypeScript library's wire vectors, the cross-implementation check.
    systemProperty("kinclude.vectors", rootProject.file("../vectors/kinclude-vectors.json").absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
