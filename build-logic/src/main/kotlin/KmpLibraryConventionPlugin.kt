import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.kotlin.multiplatform.library")
            pluginManager.apply("xyz.lilsus.raylsuite.kover")
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            if (file("src/commonTest").isDirectory) {
                pluginManager.apply("xyz.lilsus.raylsuite.pitest")
            }

            extensions.configure<KotlinMultiplatformExtension> {
                jvmToolchain(21)

                targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
                    compileSdk = 37
                    minSdk = 24

                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_11)
                    }

                    // Resources for the modules whose host tests run on Robolectric
                    // (xyz.lilsus.raylsuite.robolectric). Host tests can be configured only once.
                    withHostTest { isIncludeAndroidResources = true }
                }

                iosArm64()
                iosSimulatorArm64()

                applyDefaultHierarchyTemplate()
            }
        }
    }
}
