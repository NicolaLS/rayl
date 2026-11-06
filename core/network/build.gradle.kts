plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
    id("xyz.lilsus.raylsuite.robolectric")
}

kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.core.network"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.contentNegotiation)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
