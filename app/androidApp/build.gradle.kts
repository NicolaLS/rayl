import java.util.Properties

plugins {
    id("xyz.lilsus.raylsuite.android.application")
}

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.isFile) file.inputStream().use(::load)
    }
val productionBreezApiKey =
    providers.gradleProperty("RAYL_BREEZ_API_KEY")
        .orElse(providers.environmentVariable("RAYL_BREEZ_API_KEY"))
        .orElse(localProperties.getProperty("RAYL_BREEZ_API_KEY") ?: "")

val validateProductionConfiguration = tasks.register("validateProductionConfiguration") {
    description = "Checks that the production Breez API key is configured."
    notCompatibleWithConfigurationCache("Reads the production API key at execution time.")
    doLast {
        check(productionBreezApiKey.get().isNotBlank()) {
            "RAYL_BREEZ_API_KEY is required for Mainnet builds."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" || it.name == "preDebugMainnetBuild" }.configureEach {
    dependsOn(validateProductionConfiguration)
}

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "xyz.lilsus.rayl"

    defaultConfig {
        applicationId = "com.nicolasusca.rayl"
        versionCode = 3
        versionName = "1.1.0"
        manifestPlaceholders["mainActivityName"] = "xyz.lilsus.rayl.MainActivity"
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "BREEZ_API_KEY", "".asBuildConfigString())
            manifestPlaceholders["appLabel"] = "Rayl Dev"
        }
        // A debug app on Bitcoin Mainnet, installed beside the Regtest one.
        create("debugMainnet") {
            initWith(getByName("debug"))
            buildConfigField(
                "String",
                "BREEZ_API_KEY",
                productionBreezApiKey.get().asBuildConfigString()
            )
            applicationIdSuffix = ".mainnet"
            matchingFallbacks += listOf("debug")
            manifestPlaceholders["appLabel"] = "Rayl Dev Mainnet"
        }
        getByName("release") {
            buildConfigField(
                "String",
                "BREEZ_API_KEY",
                productionBreezApiKey.get().asBuildConfigString()
            )
            manifestPlaceholders["appLabel"] = "Rayl"
        }
        create("e2e") {
            initWith(getByName("release"))
            buildConfigField("String", "BREEZ_API_KEY", "".asBuildConfigString())
            applicationIdSuffix = ".e2e"
            matchingFallbacks += listOf("release")
            manifestPlaceholders["appLabel"] = "Rayl E2E"
            manifestPlaceholders["mainActivityName"] = "xyz.lilsus.rayl.E2eMainActivity"
            signingConfig = signingConfigs.getByName("debug")
        }
        create("benchmark") {
            initWith(getByName("release"))
            buildConfigField("String", "BREEZ_API_KEY", "".asBuildConfigString())
            applicationIdSuffix = ".benchmark"
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
            manifestPlaceholders["appLabel"] = "Rayl Benchmark"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            excludes += setOf(
                "/lib/armeabi/libjnidispatch.so",
                "/lib/mips/libjnidispatch.so",
                "/lib/mips64/libjnidispatch.so"
            )
        }
        resources {
            excludes += "/fr/acinq/secp256k1/jni/native/**"
        }
    }
}

dependencies {
    implementation(project(":app:shared"))
    implementation(project(":core:network"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.core.ktx)
    "benchmarkImplementation"(project(":core:camera"))
}
