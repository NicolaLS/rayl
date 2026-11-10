plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.rayl.experience"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":providers:rayl:feature:payment"))
            implementation(project(":providers:rayl:integration:pocket"))
            implementation(project(":core:model"))
            implementation(project(":core:network"))
            implementation(project(":core:settings"))
            implementation(project(":core:telemetry"))
            implementation(project(":core:ui"))
            implementation(project(":feature:app-shell"))
            implementation(project(":feature:currency-settings"))
            implementation(project(":feature:language-settings"))
            implementation(project(":feature:onboarding"))
            implementation(project(":feature:payment-settings"))
            api(project(":feature:payment-hub"))
            api(project(":feature:payment-ui"))
            api(project(":feature:settings"))
            implementation(project(":feature:theme-settings"))
            implementation(project(":integration:exchange-rate"))
            implementation(project(":integration:lnurl"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.multiplatform.settings)
            implementation(libs.compose.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
        }
        // The iOS host passes the Swift passkey bridge, an interface of the pocket module.
        iosMain.dependencies {
            api(project(":providers:rayl:integration:pocket"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.navigation.event.compose)
            implementation(libs.qrose)
        }
    }
}
