plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "io.github.nicolals.nwc"
    }

    sourceSets {
        commonMain.dependencies {
            // Coroutine scopes and flows are exposed in the client API.
            api(libs.kotlinx.coroutines.core)
            // nostr-core types (PublicKey) are exposed in the public API.
            api(project(":nostr:nostr-core"))
            implementation(project(":nostr:nostr-codec-kotlinx"))
            implementation(project(":nostr:nostr-runtime-coroutines"))
            implementation(project(":nostr:nostr-transport-ktor"))
            implementation(project(":nostr:nostr-crypto"))
            implementation(project(":nostr:nips:nip44"))
            implementation(project(":nostr:nips:nip04"))
            // NIP-47 types (NwcEncryption) are exposed in the public API.
            api(project(":nostr:nips:nip47"))
            // Client factories accept an optional HttpClient.
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
