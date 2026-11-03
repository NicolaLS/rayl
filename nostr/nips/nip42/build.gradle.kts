plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "io.github.nicolals.nostr.nip42"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":nostr:nostr-core"))
            api(project(":nostr:nips:nip01"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
