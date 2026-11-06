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
}
