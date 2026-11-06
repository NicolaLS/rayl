import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Platform unit tests (qa/test-plan.md §6.1): Android host tests that need the Android framework
 * or the module's Android resources run on Robolectric. `xyz.lilsus.raylsuite.kmp.library`
 * already includes the resources in host tests; this adds Robolectric and the shared checks.
 * Apply it only in modules that need it: Robolectric is slow to start.
 */
class RobolectricConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
            extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.getByName("androidHostTest").dependencies {
                    implementation(catalog.findLibrary("robolectric").get())
                    implementation(catalog.findLibrary("androidx-test-core").get())
                    implementation(catalog.findLibrary("junit").get())
                    implementation(project(":core:testing-android"))
                }
            }
            // Robolectric's Android SDK 36 reaches into JDK internals (file descriptors).
            tasks.withType<Test>().configureEach {
                jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED"
                )
            }
        }
    }
}
