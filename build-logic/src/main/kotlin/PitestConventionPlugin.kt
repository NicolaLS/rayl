import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Attribute
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import pl.droidsonroids.gradle.pitest.PitestPluginExtension

/**
 * Mutation testing with PIT for a module's host tests: `./gradlew :module:pitestAndroidMain`,
 * report in `build/reports/pitest/androidMain/index.html`. See `qa/test-plan.md` §6.7.
 */
class PitestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("pl.droidsonroids.pitest")
            val versions = extensions.getByType<VersionCatalogsExtension>().named("libs")
            fun version(name: String) = versions.findVersion(name).get().requiredVersion

            extensions.configure<PitestPluginExtension> {
                pitestVersion.set(version("pitest"))
                targetClasses.set(listOf("xyz.lilsus.*"))
                // Compose UI can't run in host tests, so its mutants would only be noise.
                excludedClasses.set(listOf("*ScreenKt", "*ComposableSingletons*"))
                // Calls the Kotlin compiler inserts (null-check intrinsics, coroutine result
                // checks) are not behaviour; mutating them only produces equivalent mutants.
                avoidCallsTo.set(listOf("kotlin.jvm.internal", "kotlin.ResultKt"))
                outputFormats.set(listOf("HTML", "XML"))
                timestampedReports.set(false)
                threads.set(Runtime.getRuntime().availableProcessors())
            }

            // The plugin puts kotlin-test on PIT's classpath without its JUnit 4 binding, and
            // PIT then finds no runnable tests.
            dependencies.add("pitestTestCompile", "junit:junit:${version("junit")}")
            dependencies.add(
                "pitestTestCompile",
                "org.jetbrains.kotlin:kotlin-test-junit:${version("kotlin")}"
            )
            // The plugin doesn't find the KMP host tests' runtime classpath, so libraries the tests
            // only need at runtime (e.g. through a test-only module dependency) are missing.
            val hostTestRuntime = provider {
                configurations.getByName("androidHostTestRuntimeClasspath").incoming.artifactView {
                    attributes.attribute(ARTIFACT_TYPE, "android-classes-jar")
                }.files
            }
            dependencies.add("pitestTestCompile", files(hostTestRuntime))
        }
    }

    private companion object {
        val ARTIFACT_TYPE: Attribute<String> = Attribute.of("artifactType", String::class.java)
    }
}
