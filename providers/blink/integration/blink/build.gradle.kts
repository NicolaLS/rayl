plugins {
    id("xyz.lilsus.raylsuite.kmp.library")
    id("xyz.lilsus.raylsuite.wallet.integration")
    alias(libs.plugins.apollo)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "xyz.lilsus.rayl.providers.blink.integration.blink"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:settings"))
            implementation(libs.apollo.runtime)
            implementation(libs.bitcoin.kmp)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.lightning.kmp.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

apollo {
    service("blink") {
        val blinkSchemaFile = file(
            "src/commonMain/graphql/xyz/lilsus/rayl/providers/blink/integration/blink/graphql/schema.graphqls"
        )

        packageName.set("xyz.lilsus.rayl.providers.blink.integration.blink.graphql")
        schemaFile.set(blinkSchemaFile)
        generateAsInternal.set(true)
        mapScalar("LnPaymentRequest", "kotlin.String", "com.apollographql.apollo.api.StringAdapter")
        mapScalar("WalletId", "kotlin.String", "com.apollographql.apollo.api.StringAdapter")
        mapScalar("PaymentHash", "kotlin.String", "com.apollographql.apollo.api.StringAdapter")
        mapScalar(
            "LnPaymentPreImage",
            "kotlin.String",
            "com.apollographql.apollo.api.StringAdapter"
        )
        mapScalar("Memo", "kotlin.String", "com.apollographql.apollo.api.StringAdapter")
        mapScalar("ContactAlias", "kotlin.String", "com.apollographql.apollo.api.StringAdapter")
        mapScalar("ContactHandle", "kotlin.String", "com.apollographql.apollo.api.StringAdapter")
        mapScalar("SatAmount", "kotlin.Long", "com.apollographql.apollo.api.LongAdapter")
        mapScalar("CentAmount", "kotlin.Long", "com.apollographql.apollo.api.LongAdapter")
        mapScalar("SignedAmount", "kotlin.Long", "com.apollographql.apollo.api.LongAdapter")
        introspection {
            endpointUrl.set("https://api.blink.sv/graphql")
            schemaFile.set(blinkSchemaFile)
        }
    }
}
