import groovy.json.JsonSlurper
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.ProjectDependency
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.apollo) apply false
    alias(libs.plugins.mokkery) apply false
    alias(libs.plugins.breezSpark) apply false
    id("xyz.lilsus.raylsuite.kover")
}

val ktlintCliVersion = libs.versions.ktlintCli
val nativeLocalizationLocales = listOf("en", "de", "es")
val androidOnlyLocalizationModules = setOf("feature/theme-settings")
val appleOnlyLocalizationModules = emptySet<String>()

data class NativeLocalizationEntry(val kind: String, val variants: Map<String, String>)

fun parseAndroidLocalization(file: File): Map<String, NativeLocalizationEntry> {
    val documentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
            isXIncludeAware = false
        }
    val root = documentBuilderFactory.newDocumentBuilder().parse(file).documentElement
    val entries = mutableMapOf<String, NativeLocalizationEntry>()

    for (index in 0 until root.childNodes.length) {
        val element = root.childNodes.item(index) as? Element ?: continue
        val name = element.getAttribute("name")
        val entry =
            when (element.tagName) {
                "string" ->
                    NativeLocalizationEntry(
                        kind = "string",
                        variants = mapOf("value" to element.textContent)
                    )

                "plurals" -> {
                    val variants = mutableMapOf<String, String>()
                    for (itemIndex in 0 until element.childNodes.length) {
                        val item = element.childNodes.item(itemIndex) as? Element ?: continue
                        if (item.tagName != "item") continue
                        val quantity = item.getAttribute("quantity")
                        check(variants.put(quantity, item.textContent) == null) {
                            "Duplicate plural quantity '$quantity' for '$name' in $file"
                        }
                    }
                    NativeLocalizationEntry(kind = "plural", variants = variants)
                }

                else -> continue
            }

        check(name.isNotBlank()) { "Unnamed ${element.tagName} in $file" }
        check(entries.put(name, entry) == null) { "Duplicate localization key '$name' in $file" }
    }
    return entries
}

fun requiredMap(value: Any?, context: String): Map<*, *> =
    value as? Map<*, *> ?: error("Expected an object for $context")

fun parseAppleLocalization(file: File): Map<String, Map<String, NativeLocalizationEntry>> {
    val catalog = requiredMap(JsonSlurper().parse(file), file.path)
    check(catalog["sourceLanguage"] == "en") { "$file must use English as its source language" }
    val strings = requiredMap(catalog["strings"], "strings in $file")
    val entriesByLocale = nativeLocalizationLocales.associateWith {
        mutableMapOf<String, NativeLocalizationEntry>()
    }

    strings.forEach { (rawName, rawEntry) ->
        val name = rawName as? String ?: error("Non-string localization key in $file")
        val entry = requiredMap(rawEntry, "'$name' in $file")
        val localizations =
            requiredMap(entry["localizations"], "localizations for '$name' in $file")
        check(localizations.keys.map(Any?::toString).toSet() == nativeLocalizationLocales.toSet()) {
            "'$name' in $file must contain exactly ${nativeLocalizationLocales.joinToString()}"
        }

        nativeLocalizationLocales.forEach { locale ->
            val localization =
                requiredMap(localizations[locale], "$locale localization for '$name' in $file")
            val stringUnit = localization["stringUnit"] as? Map<*, *>
            val parsedEntry =
                if (stringUnit != null) {
                    check(stringUnit["state"] == "translated") {
                        "$locale localization for '$name' in $file is not translated"
                    }
                    NativeLocalizationEntry(
                        kind = "string",
                        variants = mapOf("value" to stringUnit["value"].toString())
                    )
                } else {
                    val variations =
                        requiredMap(localization["variations"], "variations for '$name' in $file")
                    val plural =
                        requiredMap(variations["plural"], "plural variations for '$name' in $file")
                    val variants =
                        plural.map { (rawQuantity, rawVariation) ->
                            val quantity =
                                rawQuantity as? String
                                    ?: error("Invalid plural quantity for '$name' in $file")
                            val variation =
                                requiredMap(
                                    rawVariation,
                                    "$quantity variation for '$name' in $file"
                                )
                            val unit =
                                requiredMap(
                                    variation["stringUnit"],
                                    "$quantity string unit for '$name' in $file"
                                )
                            check(unit["state"] == "translated") {
                                "$locale/$quantity localization for '$name' in $file is not translated"
                            }
                            quantity to unit["value"].toString()
                        }.toMap()
                    NativeLocalizationEntry(kind = "plural", variants = variants)
                }

            check(entriesByLocale.getValue(locale).put(name, parsedEntry) == null) {
                "Duplicate localization key '$name' in $file"
            }
        }
    }

    return entriesByLocale
}

fun String.androidValueAsAppleFormat(): String = replace("\\'", "'")
    .replace("\\\"", "\"")
    .replace("\\n", "\n")
    .replace(Regex("""%(\d+)\${'$'}s""")) { match -> "%${match.groupValues[1]}${'$'}@" }
    .replace(Regex("""%(\d+)\${'$'}d""")) { match -> "%${match.groupValues[1]}${'$'}lld" }

fun validateLocaleCompleteness(
    owner: String,
    platform: String,
    entriesByLocale: Map<String, Map<String, NativeLocalizationEntry>>,
    violations: MutableList<String>
) {
    val englishEntries = entriesByLocale.getValue("en")
    entriesByLocale.forEach { (locale, entries) ->
        val missing = englishEntries.keys - entries.keys
        val unexpected = entries.keys - englishEntries.keys
        if (missing.isNotEmpty()) {
            violations +=
                "$owner/$platform/$locale missing keys: ${missing.sorted().joinToString()}"
        }
        if (unexpected.isNotEmpty()) {
            violations +=
                "$owner/$platform/$locale has unexpected keys: ${unexpected.sorted().joinToString()}"
        }

        (englishEntries.keys intersect entries.keys).sorted().forEach { key ->
            val englishEntry = englishEntries.getValue(key)
            val localizedEntry = entries.getValue(key)
            if (englishEntry.kind != localizedEntry.kind) {
                violations +=
                    "$owner/$platform/$locale/$key differs in kind " +
                    "(${englishEntry.kind} vs ${localizedEntry.kind})"
            }
        }

        entries.filterValues { it.kind == "plural" }.forEach { (key, entry) ->
            if ("other" !in entry.variants) {
                violations += "$owner/$platform/$locale/$key is missing the 'other' plural"
            }
        }
    }
}

fun Project.configureKtlint() {
    extensions.configure<KtlintExtension> {
        version.set(ktlintCliVersion)
        android.set(true)
        outputToConsole.set(true)
        outputColorName.set("RED")
        ignoreFailures.set(false)
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }
}

allprojects {
    pluginManager.withPlugin("org.jlleitschuh.gradle.ktlint") {
        configureKtlint()
    }
}

fun String.isAppModule(): Boolean = startsWith(":app:")

fun String.providerOwner(): String? =
    takeIf { it.startsWith(":providers:") }?.removePrefix(":providers:")?.substringBefore(":")

val backendWireContracts = setOf(":core:rayl-api")

val verifyModuleDependencies = tasks.register("verifyModuleDependencies") {
    group = "verification"
    notCompatibleWithConfigurationCache(
        "Calls helper functions defined in this build script; move them into the task to fix."
    )
    description = "Enforces app, provider, provider-neutral, and Nostr SDK project ownership."

    doLast {
        val violations =
            subprojects.flatMap { source ->
                val sourceIsApp = source.path.isAppModule()
                source.configurations.flatMap { configuration ->
                    configuration.dependencies
                        .withType(ProjectDependency::class.java)
                        .mapNotNull { dependency ->
                            val targetPath = dependency.path
                            val targetIsApp = targetPath.isAppModule()
                            val sourceProvider = source.path.providerOwner()
                            val targetProvider = targetPath.providerOwner()
                            when {
                                source.path.startsWith(":nostr:") &&
                                    !targetPath.startsWith(":nostr:") ->
                                    "${source.path} -> $targetPath (Nostr SDK depends on Rayl code)"

                                targetPath.startsWith(":backend:") &&
                                    !source.path.startsWith(":backend:") ->
                                    "${source.path} -> $targetPath (client module depends on backend implementation)"

                                source.path.startsWith(":backend:") &&
                                    !targetPath.startsWith(":backend:") &&
                                    targetPath !in backendWireContracts ->
                                    "${source.path} -> $targetPath (backend may share only wire contracts)"

                                targetProvider != null && !sourceIsApp &&
                                    sourceProvider == null ->
                                    "${source.path} -> $targetPath (neutral module depends on a provider)"

                                targetProvider != null && sourceProvider != null &&
                                    sourceProvider != targetProvider ->
                                    "${source.path} -> $targetPath (cross-provider dependency)"

                                !sourceIsApp && targetIsApp ->
                                    "${source.path} -> $targetPath (root module depends on an app)"

                                else -> null
                            }
                        }
                }
            }.distinct()
                .sorted()

        check(violations.isEmpty()) {
            buildString {
                appendLine("Invalid project dependencies:")
                violations.forEach { appendLine("  - $it") }
            }
        }
    }
}

val verifyNativeLocalizations = tasks.register("verifyNativeLocalizations") {
    group = "verification"
    notCompatibleWithConfigurationCache(
        "Calls helper functions defined in this build script; move them into the task to fix."
    )
    description =
        "Validates native locale completeness, intentional parity, and Apple target membership."

    doLast {
        val staleComposeCatalogs =
            fileTree(rootDir) {
                include("**/src/commonMain/composeResources/values*/strings.xml")
                exclude("**/build/**")
            }.files.sorted()
        check(staleComposeCatalogs.isEmpty()) {
            buildString {
                appendLine("Localization catalogs must not remain in commonMain/composeResources:")
                staleComposeCatalogs.forEach { appendLine("  - ${it.relativeTo(rootDir)}") }
            }
        }

        val baseAndroidCatalogs =
            fileTree(rootDir) {
                include("**/src/androidMain/res/values/strings.xml")
                exclude("**/build/**")
            }.files.sorted()
        check(baseAndroidCatalogs.isNotEmpty()) { "No native Android localization catalogs found" }

        val appleCatalogs =
            fileTree(rootDir) {
                include("**/src/iosMain/resources/*.xcstrings")
                exclude("**/build/**")
            }.files.sorted()
        check(appleCatalogs.isNotEmpty()) { "No Apple String Catalogs found" }

        val violations = mutableListOf<String>()
        val androidCatalogsByModule =
            baseAndroidCatalogs.associateBy { catalog ->
                catalog.parentFile.parentFile.parentFile.parentFile.parentFile
                    .relativeTo(rootDir)
                    .invariantSeparatorsPath
            }
        val appleCatalogsByModule =
            appleCatalogs.groupBy { catalog ->
                catalog.parentFile.parentFile.parentFile.parentFile
                    .relativeTo(rootDir)
                    .invariantSeparatorsPath
            }

        val duplicatedAppleNames = appleCatalogs.groupBy { it.name }.filterValues { it.size > 1 }
        duplicatedAppleNames.forEach { (name, catalogs) ->
            violations +=
                "Apple String Catalog name '$name' is ambiguous: " +
                catalogs.joinToString { it.relativeTo(rootDir).invariantSeparatorsPath }
        }

        val xcodeProjects =
            fileTree(rootDir) {
                include("app/iosApp/*.xcodeproj/project.pbxproj")
                exclude("**/build/**")
            }.files.associateWith { project ->
                project.readText()
            }
        check(xcodeProjects.isNotEmpty()) {
            "No Xcode projects found for Apple resource validation"
        }
        appleCatalogs.forEach { catalog ->
            val escapedName = Regex.escape(catalog.name)
            val resourcesPhaseEntry =
                Regex(
                    """(?m)^\s*[A-Za-z0-9]+ /\* $escapedName in Resources \*/,\s*${'$'}"""
                )
            if (xcodeProjects.values.none(resourcesPhaseEntry::containsMatchIn)) {
                val catalogPath = catalog.relativeTo(rootDir).invariantSeparatorsPath
                violations += "$catalogPath is not included in an Xcode Resources phase"
            }
        }

        val androidByModuleAndLocale =
            androidCatalogsByModule.mapValues { (moduleName, baseCatalog) ->
                val moduleDir = baseCatalog.parentFile.parentFile.parentFile.parentFile.parentFile
                var hasEveryLocale = true
                val entriesByLocale =
                    nativeLocalizationLocales.associateWith { locale ->
                        val valuesDirectory = if (locale == "en") "values" else "values-$locale"
                        val catalog =
                            moduleDir.resolve("src/androidMain/res/$valuesDirectory/strings.xml")
                        if (!catalog.isFile) {
                            hasEveryLocale = false
                            violations += "$moduleName/Android is missing locale $locale"
                            emptyMap()
                        } else {
                            parseAndroidLocalization(catalog)
                        }
                    }
                if (hasEveryLocale) {
                    validateLocaleCompleteness(
                        owner = moduleName,
                        platform = "Android",
                        entriesByLocale = entriesByLocale,
                        violations = violations
                    )
                }
                entriesByLocale
            }

        val appleByModuleAndLocale =
            appleCatalogsByModule.mapNotNull { (moduleName, catalogs) ->
                if (catalogs.size != 1) {
                    violations +=
                        "$moduleName must contain at most one feature-owned Apple String Catalog"
                    null
                } else {
                    val entriesByLocale = parseAppleLocalization(catalogs.single())
                    validateLocaleCompleteness(
                        owner = moduleName,
                        platform = "Apple",
                        entriesByLocale = entriesByLocale,
                        violations = violations
                    )
                    moduleName to entriesByLocale
                }
            }.toMap()

        check((androidOnlyLocalizationModules intersect appleOnlyLocalizationModules).isEmpty()) {
            "A localization module cannot be both Android-only and Apple-only"
        }
        androidOnlyLocalizationModules.forEach { moduleName ->
            if (moduleName !in androidCatalogsByModule) {
                violations += "$moduleName is declared Android-only but has no Android catalog"
            }
            if (moduleName in appleCatalogsByModule) {
                violations += "$moduleName is declared Android-only but has an Apple catalog"
            }
        }
        appleOnlyLocalizationModules.forEach { moduleName ->
            if (moduleName !in appleCatalogsByModule) {
                violations += "$moduleName is declared Apple-only but has no Apple catalog"
            }
            if (moduleName in androidCatalogsByModule) {
                violations += "$moduleName is declared Apple-only but has an Android catalog"
            }
        }

        (androidCatalogsByModule.keys - appleCatalogsByModule.keys)
            .filterNot(androidOnlyLocalizationModules::contains)
            .forEach { moduleName ->
                violations +=
                    "$moduleName has only Android localizations; declare it platform-only or add " +
                    "an Apple catalog"
            }
        (appleCatalogsByModule.keys - androidCatalogsByModule.keys)
            .filterNot(appleOnlyLocalizationModules::contains)
            .forEach { moduleName ->
                violations +=
                    "$moduleName has only Apple localizations; declare it platform-only or add " +
                    "an Android catalog"
            }

        val pairedModules =
            (androidCatalogsByModule.keys intersect appleCatalogsByModule.keys) -
                androidOnlyLocalizationModules - appleOnlyLocalizationModules
        pairedModules.sorted().forEach { moduleName ->
            val androidByLocale = androidByModuleAndLocale.getValue(moduleName)
            val appleByLocale = appleByModuleAndLocale[moduleName] ?: return@forEach
            nativeLocalizationLocales.forEach { locale ->
                val androidEntries = androidByLocale.getValue(locale)
                val appleEntries = appleByLocale.getValue(locale)
                if (androidEntries.keys != appleEntries.keys) {
                    val missingFromApple = androidEntries.keys - appleEntries.keys
                    val missingFromAndroid = appleEntries.keys - androidEntries.keys
                    if (missingFromApple.isNotEmpty()) {
                        violations +=
                            "$moduleName/$locale missing from Apple: " +
                            missingFromApple.sorted().joinToString()
                    }
                    if (missingFromAndroid.isNotEmpty()) {
                        violations +=
                            "$moduleName/$locale missing from Android: " +
                            missingFromAndroid.sorted().joinToString()
                    }
                }

                (androidEntries.keys intersect appleEntries.keys).sorted().forEach { key ->
                    val androidEntry = androidEntries.getValue(key)
                    val appleEntry = appleEntries.getValue(key)
                    if (androidEntry.kind != appleEntry.kind) {
                        violations +=
                            "$moduleName/$locale/$key differs in kind " +
                            "(${androidEntry.kind} vs ${appleEntry.kind})"
                    } else if (androidEntry.variants.keys != appleEntry.variants.keys) {
                        violations += "$moduleName/$locale/$key differs in plural quantities"
                    } else {
                        androidEntry.variants.forEach { (variant, androidValue) ->
                            val expectedAppleValue = androidValue.androidValueAsAppleFormat()
                            if (expectedAppleValue != appleEntry.variants.getValue(variant)) {
                                violations +=
                                    "$moduleName/$locale/$key/$variant differs between Android and Apple"
                            }
                        }
                    }
                }
            }
        }

        check(violations.isEmpty()) {
            buildString {
                appendLine("Native localization violations:")
                violations.sorted().forEach { appendLine("  - $it") }
            }
        }
    }
}

val xcodeSpmLinkedModules =
    setOf(
        ":app:shared",
        ":providers:rayl:experience",
        ":providers:rayl:feature:payment",
        ":providers:rayl:integration:pocket",
        ":providers:spark:experience",
        ":providers:spark:integration:wallet"
    )

subprojects {
    afterEvaluate {
        if (path in xcodeSpmLinkedModules) {
            // breez_sdk_sparkFFI is linked by Xcode via Swift Package Manager; commonTest suites in
            // these modules run on the JVM via testAndroidHostTest.
            tasks.matching {
                it.name == "linkDebugTestIosSimulatorArm64" || it.name == "iosSimulatorArm64Test"
            }.configureEach {
                enabled = false
            }
        }
    }
}

val verifyBreezSdkPins = tasks.register("verifyBreezSdkPins") {
    group = "verification"
    description =
        "Checks that Android and iOS use the same Breez SDK version (Gradle and Xcode pins)."
    // Locals only: the configuration cache can't store references to the build script.
    val breezSparkVersion = libs.versions.breezSpark
    val project = file("app/iosApp/iosApp.xcodeproj/project.pbxproj")
    val resolved =
        file(
            "app/iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
        )
    inputs.files(project, resolved)
    inputs.property("gradleVersion", breezSparkVersion)

    doLast {
        val gradleVersion = breezSparkVersion.get()
        val xcodePin =
            Regex(
                """breez-sdk-spark-swift\.git";\s*requirement = \{\s*kind = exactVersion;\s*version = ([^;]+);"""
            ).find(project.readText())?.groupValues?.get(1)?.trim()
        val resolvedPins =
            (JsonSlurper().parse(resolved) as Map<*, *>)["pins"] as List<*>
        val resolvedVersion =
            resolvedPins.map { it as Map<*, *> }
                .firstOrNull { it["identity"] == "breez-sdk-spark-swift" }
                ?.let { (it["state"] as Map<*, *>)["version"] as String? }
        check(xcodePin == gradleVersion && resolvedVersion == gradleVersion) {
            "Breez SDK pins differ: Gradle $gradleVersion, Xcode exactVersion $xcodePin, " +
                "Package.resolved $resolvedVersion. Bump them together (gradle/libs.versions.toml)."
        }
    }
}

tasks.register("check") {
    group = "verification"
    description = "Runs the root architecture checks."
    dependsOn(verifyModuleDependencies)
    dependsOn(verifyNativeLocalizations)
    dependsOn(verifyBreezSdkPins)
}

tasks.register("perfCheck") {
    group = "verification"
    description =
        "Runs Rayl startup and camera macrobenchmarks on a connected Android 10+ device. " +
        "Use a stable physical device for comparable numbers."
    dependsOn(":app:benchmark:connectedBenchmarkAndroidTest")
}
