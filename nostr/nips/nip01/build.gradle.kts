plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "io.github.nicolals.nostr.nip01"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":nostr:nostr-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
