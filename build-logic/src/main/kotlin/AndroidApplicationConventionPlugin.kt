import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension

/** Platform defaults for the Rayl Android application. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            pluginManager.apply("xyz.lilsus.raylsuite.android.release")

            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            }

            extensions.configure<ApplicationExtension> {
                compileSdk = 37

                defaultConfig {
                    minSdk = 24
                    targetSdk = 36
                    // Native symbols in App Bundles, so Play Console can symbolicate native
                    // crashes and ANRs (Breez SDK, JNA, libsodium; MOB-105). Needs the NDK on the
                    // machine that builds the bundle; without it AGP silently skips this.
                    ndk { debugSymbolLevel = "SYMBOL_TABLE" }
                }

                buildTypes {
                    getByName("debug") {
                        applicationIdSuffix = ".dev"
                    }
                    getByName("release") {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            target.file("proguard-rules.pro")
                        )
                    }
                }

                buildFeatures {
                    compose = true
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_11
                    targetCompatibility = JavaVersion.VERSION_11
                }
                androidResources {
                    generateLocaleConfig = true
                }
                // Android Lint (qa/test-plan.md, item 4a): API levels down to minSdk 24, manifest,
                // resources, security. Library modules run their own (`lintAndroidMain`).
                lint {
                    lintConfig = target.rootProject.file("lint.xml")
                    abortOnError = true
                    warningsAsErrors = false
                }
            }

            extensions.configure<KotlinAndroidExtension> {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }

            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            dependencies.add("implementation", libs.findLibrary("androidx-activity-compose").get())
            dependencies.add("implementation", libs.findLibrary("androidx-appcompat").get())
            dependencies.add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
            dependencies.add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
        }
    }
}
