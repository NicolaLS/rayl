plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "io.github.nicolals.nostr.runtime.coroutines"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":nostr:nostr-core"))
            api(project(":nostr:nips:nip01"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":nostr:nips:nip42"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
