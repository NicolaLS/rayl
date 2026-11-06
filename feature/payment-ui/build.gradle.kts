plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
    id("xyz.lilsus.raylsuite.robolectric")
}

kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.feature.paymentui"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(project(":core:camera"))
            implementation(project(":core:ui"))
            api(project(":feature:payment-hub"))
            implementation(libs.compose.runtime)
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation.event.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // Compose screen tests on Robolectric (qa/test-plan.md §6.8).
        getByName("androidHostTest").dependencies {
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
        }
    }
}
