plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.blink.feature.payment"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":feature:payment-flow"))
            api(project(":providers:blink:integration:blink"))
            implementation(project(":core:ui"))
            implementation(libs.bitcoin.kmp)
            implementation(libs.kotlinx.coroutines.core)
            api(libs.multiplatform.settings)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.kmp.jni.android)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.lightning.kmp.core)
            implementation(libs.multiplatform.settings.test)
        }
    }
}
