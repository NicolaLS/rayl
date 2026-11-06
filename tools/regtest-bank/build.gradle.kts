plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
    alias(libs.plugins.breezSpark)
}

// The regtest bank for wallet integration tests (qa/test-plan.md §6.9): pays and creates
// invoices on Lightspark's hosted Regtest. Test-only: wallet modules use it in their host tests.
// It is Android code so it uses the same Breez SDK classes as the wallet code under test; the
// JVM native libraries come from WalletIntegrationConventionPlugin.
kotlin {
    android {
        namespace = "xyz.lilsus.rayl.tools.regtest.bank"
    }

    sourceSets {
        androidMain.dependencies {
            api(libs.breez.sdk.spark)
            implementation(libs.bitcoin.kmp)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
