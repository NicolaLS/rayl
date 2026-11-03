rootProject.name = "rayl"

pluginManagement {
    includeBuild("build-logic")

    repositories {
        // Only Breez's own artifacts: an error from this repository must not block resolving
        // anything else (it broke CI's iOS run on 2026-11-03).
        maven("https://mvn.breez.technology/releases") {
            mavenContent { includeGroupAndSubgroups("technology.breez") }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        // Only Breez's own artifacts: an error from this repository must not block resolving
        // anything else (it broke CI's iOS run on 2026-11-03).
        maven("https://mvn.breez.technology/releases") {
            mavenContent { includeGroupAndSubgroups("technology.breez") }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // Used for maintainer-owned io.github.nicolals:nwc-kmp snapshot builds
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

include(":app:shared", ":app:androidApp", ":app:benchmark")

fun includeProvider(name: String, modules: List<String>) {
    modules.forEach { module ->
        val path = ":providers:$name:$module"
        include(path)
        project(path).projectDir = file("providers/$name/${module.replace(':', '/')}")
    }
}

includeProvider(
    "blink",
    listOf(
        "experience",
        "feature:blink-contacts",
        "feature:onboarding",
        "feature:payment",
        "feature:wallet-connection",
        "feature:wallet-settings",
        "integration:blink",
        "ui"
    )
)
includeProvider(
    "nwc",
    listOf(
        "experience",
        "feature:onboarding",
        "feature:payment",
        "feature:wallet-connection",
        "feature:wallet-details",
        "integration:nwc"
    )
)
includeProvider(
    "rayl",
    listOf(
        "experience",
        "feature:payment",
        "integration:pocket"
    )
)
includeProvider(
    "spark",
    listOf(
        "experience",
        "application",
        "feature:onboarding",
        "feature:payment",
        "feature:wallet-connection",
        "integration:wallet"
    )
)

include(":core:model")
include(":core:rayl-api")
include(":core:camera")
include(":core:network")
include(":core:payment")
include(":core:settings")
include(":core:ui")
include(":feature:theme-settings")
include(":feature:currency-settings")
include(":feature:language-settings")
include(":feature:onboarding")
include(":feature:payment-currency")
include(":feature:payment-ui")
include(":feature:payment-flow")
include(":feature:settings")
include(":feature:payment-settings")
include(":feature:payment-hub")
include(":feature:app-shell")
include(":feature:wallet-management")
include(":integration:exchange-rate")
include(":integration:lnurl")
include(":backend:rayl")
include(":tools:regtest")
