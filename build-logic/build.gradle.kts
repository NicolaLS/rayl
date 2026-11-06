import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

group = "xyz.lilsus.raylsuite.buildlogic"

extensions.configure<KtlintExtension> {
    version.set(libs.versions.ktlintCli)
    android.set(true)
    outputToConsole.set(true)
    outputColorName.set("RED")
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

dependencies {
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    implementation(
        "org.jetbrains.compose:compose-gradle-plugin:${libs.versions.composeMultiplatform.get()}"
    )
    implementation(
        "org.jetbrains.kotlin:compose-compiler-gradle-plugin:${libs.versions.kotlin.get()}"
    )
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation(
        "org.jetbrains.kotlinx:kover-gradle-plugin:${libs.versions.kover.get()}"
    )
    implementation("org.jlleitschuh.gradle:ktlint-gradle:${libs.versions.ktlint.get()}")
    implementation("org.gradle:test-retry-gradle-plugin:${libs.versions.testRetry.get()}")
    implementation(
        "pl.droidsonroids.gradle:gradle-pitest-plugin:${libs.versions.pitestGradle.get()}"
    )
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "xyz.lilsus.raylsuite.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kover") {
            id = "xyz.lilsus.raylsuite.kover"
            implementationClass = "KoverConventionPlugin"
        }
        register("walletIntegration") {
            id = "xyz.lilsus.raylsuite.wallet.integration"
            implementationClass = "WalletIntegrationConventionPlugin"
        }
        register("robolectric") {
            id = "xyz.lilsus.raylsuite.robolectric"
            implementationClass = "RobolectricConventionPlugin"
        }
        register("pitest") {
            id = "xyz.lilsus.raylsuite.pitest"
            implementationClass = "PitestConventionPlugin"
        }
        register("kmpComposeLibrary") {
            id = "xyz.lilsus.raylsuite.kmp.compose"
            implementationClass = "KmpComposeConventionPlugin"
        }
        register("appShared") {
            id = "xyz.lilsus.raylsuite.app.shared"
            implementationClass = "AppSharedConventionPlugin"
        }
        register("androidApplication") {
            id = "xyz.lilsus.raylsuite.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidAppRelease") {
            id = "xyz.lilsus.raylsuite.android.release"
            implementationClass = "AndroidAppReleaseConventionPlugin"
        }
    }
}
