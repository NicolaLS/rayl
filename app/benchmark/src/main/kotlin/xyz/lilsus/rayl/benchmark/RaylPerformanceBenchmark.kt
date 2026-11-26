package xyz.lilsus.rayl.benchmark

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class RaylPerformanceBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private fun grantCameraPermission() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(TARGET_PACKAGE, Manifest.permission.CAMERA)
    }

    private fun MacrobenchmarkScope.seedState(mode: String) {
        startActivityAndWait(
            Intent()
                .setComponent(ComponentName(TARGET_PACKAGE, SEED_ACTIVITY))
                .putExtra(EXTRA_SEED_MODE, mode)
        )
        device.waitForIdle()
        killProcess()
        pressHome()
    }

    private fun findBoundsInWindows(tag: String): Rect? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val roots = automation.windows.mapNotNull { it.root }
        for (root in roots) {
            findNodeBounds(root, tag)?.let { return it }
        }
        return automation.rootInActiveWindow?.let { findNodeBounds(it, tag) }
    }

    private fun findNodeBounds(node: AccessibilityNodeInfo, tag: String): Rect? {
        val resId = node.viewIdResourceName
        if (resId == tag || resId?.endsWith(":id/$tag") == true) {
            return Rect().also(node::getBoundsInScreen).takeUnless(Rect::isEmpty)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findNodeBounds(child, tag)?.let { return it }
        }
        return null
    }

    private fun MacrobenchmarkScope.clickTag(tag: String, timeoutMs: Long = UI_WAIT_MS) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val bounds = findBoundsInWindows(tag)
            if (bounds != null) {
                device.click(bounds.centerX(), bounds.centerY())
                device.waitForIdle()
                return
            }
            Thread.sleep(50L)
        }
        error("Expected UI element with tag '$tag' within ${timeoutMs}ms")
    }

    private fun MacrobenchmarkScope.waitForTag(tag: String, timeoutMs: Long = UI_WAIT_MS) {
        check(device.wait(Until.hasObject(By.res(tag)), timeoutMs)) {
            "Timed out waiting for tag '$tag' after ${timeoutMs}ms"
        }
    }

    private fun MacrobenchmarkScope.scrollDownAndUp() {
        val centerX = device.displayWidth / 2
        val bottomY = (device.displayHeight * 0.75f).toInt()
        val topY = (device.displayHeight * 0.38f).toInt()
        device.swipe(centerX, bottomY, centerX, topY, SCROLL_STEPS)
        device.waitForIdle()
        device.swipe(centerX, topY, centerX, bottomY, SCROLL_STEPS)
        device.waitForIdle()
    }

    @Test
    fun coldStartup() {
        var seeded = false
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = ITERATIONS,
            setupBlock = {
                if (!seeded || iteration == 0) {
                    seedState(MODE_FRESH)
                    seeded = true
                }
                pressHome()
            }
        ) {
            startActivityAndWait()
        }
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun returningColdStartup() {
        var seeded = false
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                StartupTimingMetric(),
                TraceSectionMetric(
                    sectionName = "camera.start_to_ready",
                    mode = TraceSectionMetric.Mode.First
                ),
                TraceSectionMetric(
                    sectionName = "camera.start_to_first_frame",
                    mode = TraceSectionMetric.Mode.First
                )
            ),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = ITERATIONS,
            setupBlock = {
                grantCameraPermission()
                if (!seeded || iteration == 0) {
                    seedState(MODE_RETURNING)
                    seeded = true
                }
                pressHome()
            }
        ) {
            startActivityAndWait()
            waitForTag(TAG_APP_SCAFFOLD)
            Thread.sleep(CAMERA_SETTLE_MILLIS)
        }
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun cameraStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            TraceSectionMetric(
                sectionName = "camera.start_to_ready",
                mode = TraceSectionMetric.Mode.First
            ),
            TraceSectionMetric(
                sectionName = "camera.start_to_first_frame",
                mode = TraceSectionMetric.Mode.First
            )
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            // Compilation setup can reinstall the app and revoke an earlier permission grant.
            grantCameraPermission()
            pressHome()
        }
    ) {
        startActivityAndWait(
            Intent().setComponent(
                ComponentName(TARGET_PACKAGE, CAMERA_BENCHMARK_ACTIVITY)
            )
        )
        Thread.sleep(CAMERA_SETTLE_MILLIS)
    }

    @Test
    fun tabNavigationAndScrolling() {
        var seeded = false
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = null,
            iterations = ITERATIONS,
            setupBlock = {
                grantCameraPermission()
                if (!seeded || iteration == 0) {
                    seedState(MODE_RETURNING)
                    seeded = true
                }
                if (!device.hasObject(By.res(TAG_APP_SCAFFOLD))) {
                    startActivityAndWait()
                    waitForTag(TAG_APP_SCAFFOLD)
                } else {
                    clickTag(TAG_TAB_SCAN)
                    waitForTag(TAG_PAYMENT_SCREEN)
                }
            }
        ) {
            clickTag(TAG_TAB_HUB)
            Thread.sleep(UI_SETTLE_MS)
            scrollDownAndUp()

            clickTag(TAG_TAB_SETTINGS)
            waitForTag(TAG_SETTINGS_SCREEN)
            clickTag(TAG_SETTINGS_MODE_ALWAYS)
            clickTag(TAG_SETTINGS_MODE_ABOVE)
            scrollDownAndUp()

            clickTag(TAG_TAB_SCAN)
            waitForTag(TAG_PAYMENT_SCREEN)
        }
    }

    @Test
    fun onboardingJourney() {
        var seeded = false
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = null,
            iterations = ITERATIONS,
            setupBlock = {
                if (!seeded || iteration == 0) {
                    seedState(MODE_FRESH)
                    seeded = true
                }
                if (!device.hasObject(By.res(TAG_ONBOARDING_WELCOME))) {
                    startActivityAndWait()
                    waitForTag(TAG_ONBOARDING_WELCOME)
                }
            }
        ) {
            scrollDownAndUp()
            clickTag(TAG_ONBOARDING_WELCOME_CONTINUE)
            waitForTag(TAG_ONBOARDING_AUTO_PAY)
            Thread.sleep(UI_SETTLE_MS)
            device.pressBack()
            waitForTag(TAG_ONBOARDING_WELCOME)
        }
    }

    @Test
    fun manualAmountKeypadJourney() {
        var seeded = false
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = ITERATIONS,
            setupBlock = {
                grantCameraPermission()
                if (!seeded || iteration == 0) {
                    seedState(MODE_RETURNING)
                    seeded = true
                }
                pressHome()
            }
        ) {
            startActivityAndWait(
                Intent()
                    .setComponent(ComponentName(TARGET_PACKAGE, SCAN_BENCHMARK_ACTIVITY))
                    .putExtra(EXTRA_ZERO_AMOUNT_BOLT11, true)
            )
            waitForTag(TAG_MANUAL_AMOUNT_SCREEN, timeoutMs = 10_000L)

            clickTag(TAG_KEY_1)
            clickTag(TAG_KEY_2)
            clickTag(TAG_KEY_5)
            clickTag(TAG_KEY_0)
            clickTag(TAG_KEY_BACKSPACE)
            clickTag(TAG_KEY_5)

            clickTag(TAG_MANUAL_AMOUNT_CONTINUE)
            waitForTag(TAG_MANUAL_AMOUNT_REVIEW)

            clickTag(TAG_MANUAL_AMOUNT_BACK)
            waitForTag(TAG_KEY_1)
            Thread.sleep(UI_SETTLE_MS)

            clickTag(TAG_MANUAL_AMOUNT_CANCEL)
            if (device.wait(Until.hasObject(By.res(TAG_MANUAL_AMOUNT_SCREEN)), 500L)) {
                device.pressBack()
            }
            waitForTag(TAG_PAYMENT_SCREEN)
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.nicolasusca.rayl.benchmark"
        const val CAMERA_BENCHMARK_ACTIVITY = "xyz.lilsus.rayl.CameraBenchmarkActivity"
        const val SEED_ACTIVITY = "xyz.lilsus.rayl.BenchmarkSeedActivity"
        const val SCAN_BENCHMARK_ACTIVITY = "xyz.lilsus.rayl.BenchmarkScanActivity"
        const val EXTRA_SEED_MODE = "mode"
        const val MODE_FRESH = "fresh"
        const val MODE_RETURNING = "returning"
        const val EXTRA_ZERO_AMOUNT_BOLT11 = "scanZeroAmountInvoice"

        const val TAG_APP_SCAFFOLD = "app_tab_scaffold"
        const val TAG_TAB_SCAN = "app_tab_scan"
        const val TAG_TAB_HUB = "app_tab_hub"
        const val TAG_TAB_SETTINGS = "app_tab_settings"
        const val TAG_PAYMENT_SCREEN = "payment_screen"
        const val TAG_SETTINGS_SCREEN = "settings_screen"
        const val TAG_SETTINGS_MODE_ALWAYS = "settings_confirmation_always"
        const val TAG_SETTINGS_MODE_ABOVE = "settings_confirmation_above"

        const val TAG_ONBOARDING_WELCOME = "onboarding_welcome_screen"
        const val TAG_ONBOARDING_WELCOME_CONTINUE = "onboarding_welcome_continue"
        const val TAG_ONBOARDING_AUTO_PAY = "onboarding_auto_pay_screen"

        const val TAG_MANUAL_AMOUNT_SCREEN = "payment_manual_amount_screen"
        const val TAG_MANUAL_AMOUNT_CONTINUE = "payment_manual_amount_continue_button"
        const val TAG_MANUAL_AMOUNT_REVIEW = "payment_manual_amount_review"
        const val TAG_MANUAL_AMOUNT_BACK = "payment_manual_amount_back_button"
        const val TAG_MANUAL_AMOUNT_CANCEL = "payment_manual_amount_cancel_button"
        const val TAG_KEY_1 = "payment_manual_amount_key_1"
        const val TAG_KEY_2 = "payment_manual_amount_key_2"
        const val TAG_KEY_5 = "payment_manual_amount_key_5"
        const val TAG_KEY_0 = "payment_manual_amount_key_0"
        const val TAG_KEY_BACKSPACE = "payment_manual_amount_key_backspace"

        const val ITERATIONS = 5
        const val CAMERA_SETTLE_MILLIS = 3_000L
        const val UI_WAIT_MS = 5_000L
        const val UI_SETTLE_MS = 350L
        const val SCROLL_STEPS = 20
    }
}
