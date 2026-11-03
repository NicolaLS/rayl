plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "io.github.nicolals.nostr.nip04"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":nostr:nostr-core"))
        }
        commonTest.dependencies {
            implementation(project(":nostr:nostr-crypto"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
