plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
    id("xyz.lilsus.raylsuite.robolectric")
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.spark.feature.walletconnection"
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        commonMain.dependencies {
            api(project(":providers:spark:application"))
            implementation(project(":core:ui"))
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
        }
        // Compose screen tests on Robolectric (qa/test-plan.md §6.8).
        getByName("androidHostTest").dependencies {
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
        }
    }
}
