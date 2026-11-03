plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "io.github.nicolals.nostr.nip47"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":nostr:nostr-core"))
            api(project(":nostr:nips:nip04"))
            api(project(":nostr:nips:nip44"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
