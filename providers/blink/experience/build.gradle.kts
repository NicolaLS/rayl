plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.blink.experience"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":providers:blink:feature:onboarding"))
            implementation(project(":providers:blink:feature:payment"))
            implementation(project(":providers:blink:feature:wallet-connection"))
            api(project(":providers:blink:feature:blink-contacts"))
            implementation(project(":providers:blink:feature:wallet-settings"))
            implementation(project(":providers:blink:integration:blink"))
            implementation(project(":providers:blink:ui"))
            implementation(project(":core:network"))
            implementation(project(":core:settings"))
            api(project(":feature:wallet-management"))
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
            implementation(libs.multiplatform.settings)
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(project(":providers:blink:feature:onboarding"))
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation.compose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.multiplatform.settings.test)
        }
    }
}
