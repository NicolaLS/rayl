plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
}

kotlin {
    android {
        namespace = "xyz.lilsus.raylsuite.core.payment"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(libs.bitcoin.kmp)
            api(libs.lightning.kmp.core)
            implementation(libs.uri.kmp)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotest.property)
        }
    }
}
