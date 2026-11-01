plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
    alias(libs.plugins.breezSpark)
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.spark.integration.wallet"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":providers:spark:application"))
            implementation(project(":core:settings"))
            implementation(libs.breez.sdk.spark)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
