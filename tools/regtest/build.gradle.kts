plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.ktlint)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    applicationName = "regtest"
    mainClass.set("xyz.lilsus.rayl.tools.regtest.MainKt")
    // The Breez SDK loads its native library through JNA.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(libs.breez.sdk.spark.jvm)
    implementation(libs.bitcoin.kmp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.zxing.core)
    testImplementation(libs.kotlin.test)
}

// Contract tests against the real Breez SDK on the hosted Regtest (qa/stub-assumptions.md). They
// need the network and a funded bank, so they are not part of `test` or the gate.
tasks.named<Test>("test") { enabled = false }
tasks.register<Test>("regtestContractTest") {
    group = "verification"
    description = "Checks the Breez SDK behaviour Rayl's stubs assume, on the hosted Regtest."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("rayl.root", rootDir.absolutePath)
    outputs.upToDateWhen { false }
}
