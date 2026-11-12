plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.nwc.experience"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":providers:nwc:feature:onboarding"))
            implementation(project(":providers:nwc:feature:payment"))
            implementation(project(":providers:nwc:integration:nwc"))
            implementation(project(":core:model"))
            implementation(project(":core:network"))
            implementation(project(":core:settings"))
            implementation(project(":core:ui"))
            implementation(project(":feature:currency-settings"))
            implementation(project(":feature:language-settings"))
            implementation(project(":feature:onboarding"))
            implementation(project(":feature:payment-settings"))
            api(project(":feature:payment-ui"))
            implementation(project(":feature:app-shell"))
            api(project(":feature:payment-hub"))
            api(project(":feature:settings"))
            implementation(project(":feature:theme-settings"))
            implementation(project(":integration:exchange-rate"))
            implementation(project(":integration:lnurl"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatform.settings)
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            implementation(libs.compose.foundation)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3)
            implementation(project(":providers:nwc:feature:wallet-details"))
            api(project(":feature:wallet-management"))
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation.compose)
            implementation(libs.navigation.event.compose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
        }
        iosMain.dependencies {
            implementation(project(":core:camera"))
            api(project(":feature:wallet-management"))
            implementation(project(":providers:nwc:feature:wallet-details"))
            implementation(project(":providers:nwc:feature:wallet-connection"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.multiplatform.settings.test)
        }
    }
}
