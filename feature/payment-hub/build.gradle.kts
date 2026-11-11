plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.feature.paymenthub"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:payment"))
            api(project(":core:settings"))
            api(project(":core:ui"))
            implementation(project(":core:network"))
            implementation(project(":core:rayl-api"))
            implementation(project(":core:telemetry"))
            implementation(libs.compose.runtime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.multiplatform.settings)
        }
        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.navigation.event.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.multiplatform.settings.test)
        }
    }
}
