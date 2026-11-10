plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
    id("xyz.lilsus.raylsuite.robolectric")
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.rayl.feature.payment"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":feature:payment-flow"))
            api(project(":providers:rayl:integration:pocket"))
            implementation(project(":core:ui"))
            implementation(project(":core:telemetry"))
            implementation(libs.bitcoin.kmp)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.kmp.jni.android)
        }
        androidHostTest.dependencies {
            implementation(libs.secp256k1.kmp.jni.jvm)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.lightning.kmp.core)
        }
    }
}
