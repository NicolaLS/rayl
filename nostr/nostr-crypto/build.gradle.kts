plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "io.github.nicolals.nostr.crypto"
    }

    compilerOptions {
        // Aes256Cbc is an expect object with platform actuals.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":nostr:nostr-core"))
            implementation(libs.ionspin.libsodium)
            implementation(libs.secp256k1.kmp)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.kmp.jni.android)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
