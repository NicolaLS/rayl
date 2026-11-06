import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins { id("xyz.lilsus.raylsuite.app.shared") }

kotlin {
    android { namespace = "xyz.lilsus.rayl.shared" }
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.withType<Framework>().configureEach {
            export(project(":providers:nwc:experience"))
            export(project(":providers:blink:experience"))
            export(project(":providers:rayl:experience"))
            export(project(":providers:spark:experience"))
            export(project(":providers:blink:feature:blink-contacts"))
            export(project(":feature:payment-hub"))
            export(project(":feature:payment-ui"))
            export(project(":feature:settings"))
        }
    }
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.multiplatform.settings.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        commonMain.dependencies {
            api(project(":providers:nwc:experience"))
            api(project(":providers:blink:experience"))
            api(project(":providers:rayl:experience"))
            api(project(":providers:spark:experience"))
            api(project(":providers:blink:feature:blink-contacts"))
            implementation(project(":core:camera"))
            implementation(project(":core:settings"))
            implementation(project(":core:model"))
            implementation(project(":core:ui"))
            implementation(project(":feature:theme-settings"))
            implementation(project(":feature:language-settings"))
            implementation(project(":feature:onboarding"))
            implementation(project(":feature:payment-settings"))
            implementation(project(":feature:currency-settings"))
            implementation(project(":integration:exchange-rate"))
            implementation(libs.multiplatform.settings)
            implementation(libs.kotlinx.coroutines.core)
            api(project(":feature:payment-hub"))
            api(project(":feature:payment-ui"))
            api(project(":feature:settings"))
            implementation(libs.compose.runtime)
        }
    }
}
