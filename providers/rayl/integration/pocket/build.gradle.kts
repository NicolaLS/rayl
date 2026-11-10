plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
    id("xyz.lilsus.raylsuite.wallet.integration")
    alias(libs.plugins.breezSpark)
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.rayl.integration.pocket"
        // The key store on a real Android Keystore (emulator or device): QA I3.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:settings"))
            implementation(project(":core:telemetry"))
            implementation(libs.bitcoin.kmp)
            implementation(libs.breez.sdk.spark)
            api(libs.kotlinx.coroutines.core)
        }
        androidHostTest.dependencies {
            implementation(project(":tools:regtest-bank"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}
