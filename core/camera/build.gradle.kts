plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
}

kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.core.camera"

        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-rules.pro")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The Compose compiler plugin runs for this module's Android renderers.
            implementation(libs.compose.runtime)
            implementation(project(":core:telemetry"))
        }
        androidMain.dependencies {
            implementation(libs.compose.ui)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.mlkit.barcode.scanning)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
