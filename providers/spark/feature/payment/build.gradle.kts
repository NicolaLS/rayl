plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.spark.feature.payment"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":feature:payment-flow"))
            api(project(":providers:spark:application"))
            implementation(project(":core:ui"))
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
