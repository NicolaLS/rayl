plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
}

kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.feature.walletmanagement"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:ui"))
            api(project(":feature:settings"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation.event.compose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
        }
    }
}
