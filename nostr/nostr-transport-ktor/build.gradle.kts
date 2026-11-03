plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "io.github.nicolals.nostr.transport.ktor"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":nostr:nostr-core"))
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
