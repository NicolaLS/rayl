plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

// Shared checks for Android host tests on Robolectric (xyz.lilsus.raylsuite.robolectric).
kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.core.testing"
    }

    sourceSets {
        androidMain.dependencies {
            api(project(":core:ui"))
            implementation(libs.androidx.test.core)
        }
    }
}
