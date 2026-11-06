import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.testretry.TestRetryTaskExtension

/**
 * Wallet integration tests (qa/test-plan.md §6.9): host tests named `*WalletIntegrationTest`
 * that drive Rayl's wallet code against real wallet services in test networks. They need the
 * network and funded test wallets, so they run only through `walletIntegrationTest` (nightly),
 * never in the PR gate's `testAndroidHostTest` or in PIT.
 *
 * The code under test uses the Android builds of the Breez SDK, JNA and libsodium (Nostr
 * encryption), whose native libraries only exist for Android. Their JVM builds have the same libraries for macOS and
 * Linux; only those files are added (the JVM build's classes differ internally and must not
 * mix with the Android build's).
 */
abstract class WalletIntegrationConventionPlugin @Inject constructor(
    // Not the project's zipTree: a task must not capture the Project (configuration cache).
    private val archives: ArchiveOperations
) : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.gradle.test-retry")
            // The tests share one regtest bank and its local storage, so they run one at a time
            // across all modules.
            val slot = gradle.sharedServices.registerIfAbsent(
                "walletIntegrationSlot",
                WalletIntegrationSlot::class.java
            ) { maxParallelUsages.set(1) }

            val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
            // Transitive: JNA's own desktop library comes from the jar the JVM build depends on.
            val breezJvm = configurations.create("breezHostNatives") {
                isCanBeConsumed = false
            }
            dependencies.add(breezJvm.name, catalog.findLibrary("breez-sdk-spark-jvm").get())
            // NWC's Nostr encryption: libsodium's Android build has the same gap.
            dependencies.add(breezJvm.name, catalog.findLibrary("ionspin-libsodium-jvm").get())
            val natives = tasks.register<Sync>("breezHostNatives") {
                from(breezJvm.elements.map { jars -> jars.map { archives.zipTree(it) } }) {
                    include(
                        "*/libbreez_sdk_spark_bindings.*",
                        "com/sun/jna/*/libjnidispatch.*",
                        "libdynamic-*"
                    )
                }
                into(layout.buildDirectory.dir("breez-host-natives"))
                includeEmptyDirs = false
            }

            tasks.withType<Test>().configureEach {
                if (name != TASK) filter.excludeTestsMatching(PATTERN)
            }

            tasks.register<Test>(TASK) {
                group = "verification"
                description =
                    "Runs Rayl's wallet code against real wallet services in test networks."
                val host = tasks.named("testAndroidHostTest", Test::class.java).get()
                testClassesDirs = host.testClassesDirs
                classpath = host.classpath + files(natives)
                dependsOn(host.dependsOn)
                filter.includeTestsMatching(PATTERN)
                // The Breez SDK loads its native library through JNA.
                jvmArgs("--enable-native-access=ALL-UNNAMED")
                systemProperty("rayl.root", rootDir.absolutePath)
                usesService(slot)
                outputs.upToDateWhen { false }
                // Hosted test networks fail intermittently (dropped connections, MOB-114). A test
                // that passes on its second try is reported as flaky; a regression fails twice.
                extensions.configure<TestRetryTaskExtension> {
                    maxRetries.set(1)
                    failOnPassedAfterRetry.set(false)
                }
                testLogging {
                    events("passed", "skipped", "failed")
                    exceptionFormat = TestExceptionFormat.FULL
                }
            }
        }
    }

    abstract class WalletIntegrationSlot : BuildService<BuildServiceParameters.None>

    private companion object {
        const val TASK = "walletIntegrationTest"
        const val PATTERN = "*WalletIntegrationTest"
    }
}
