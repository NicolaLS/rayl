plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
    id("xyz.lilsus.raylsuite.wallet.integration")
    alias(libs.plugins.breezSpark)
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.spark.integration.wallet"
        // The credential vault on a real Android Keystore (emulator or device): QA I3.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":providers:spark:application"))
            implementation(project(":core:settings"))
            implementation(libs.breez.sdk.spark)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidHostTest.dependencies {
            implementation(project(":tools:regtest-bank"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}
