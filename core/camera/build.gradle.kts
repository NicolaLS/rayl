plugins {
    id("xyz.lilsus.raylsuite.kmp.compose")
}

val androidScanner =
    providers.gradleProperty("rayl.scanner").orElse("mlkit").get().lowercase()
require(androidScanner in setOf("mlkit", "zxing")) {
    "Unsupported rayl.scanner='$androidScanner'. Use 'mlkit' or 'zxing'."
}
val useZxingScanner = androidScanner == "zxing"

kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.core.camera"

        if (!useZxingScanner) {
            optimization {
                consumerKeepRules.apply {
                    publish = true
                    file("consumer-rules.pro")
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The Compose compiler plugin runs for this module's Android renderers.
            implementation(libs.compose.runtime)
            implementation(project(":core:telemetry"))
        }
        androidMain {
            kotlin.srcDir(
                if (useZxingScanner) "src/androidZxing/kotlin" else "src/androidMlkit/kotlin"
            )
            dependencies {
                implementation(libs.compose.ui)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.camera.core)
                implementation(libs.androidx.camera.camera2)
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                if (useZxingScanner) {
                    implementation(libs.zxing.cpp.android)
                } else {
                    implementation(libs.mlkit.barcode.scanning)
                }
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
