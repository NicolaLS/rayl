package xyz.lilsus.raylsuite.core.telemetry

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.raylapi.TelemetryCatalog
import xyz.lilsus.raylsuite.core.raylapi.TelemetryEvent
import xyz.lilsus.raylsuite.core.raylapi.TelemetrySetting
import xyz.lilsus.raylsuite.core.raylapi.telemetryEvent

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryEngineTest {
    private val settings = MapSettings()
    private val sent = mutableListOf<List<TelemetryEvent>>()
    private val logged = mutableListOf<String>()
    private var backendUp = true

    private fun TestScope.engine(mode: TelemetryMode = TelemetryMode.Upload, newInstallation: Boolean = true) = TelemetryEngine(
        settings = settings,
        mode = mode,
        scope = backgroundScope,
        newInstallation = { newInstallation },
        log = { logged += it },
        send = { events ->
            if (backendUp) sent += events
            backendUp
        }
    )

    private fun changed(setting: TelemetrySetting) = telemetryEvent(TelemetryCatalog.settingChanged, mapOf("setting" to setting))

    private val currency = changed(TelemetrySetting.Currency)
    private val language = changed(TelemetrySetting.Language)
    private val theme = changed(TelemetrySetting.Theme)

    @Test
    fun sendsWhatWasRecordedAsOneBatch() = runTest {
        val engine = engine()
        engine.record(currency)
        engine.record(language)

        engine.flush()
        runCurrent()

        assertEquals(listOf(listOf(currency, language)), sent)
        // Nothing is sent twice.
        engine.flush()
        runCurrent()
        assertEquals(1, sent.size)
    }

    @Test
    fun keepsABatchTheBackendDidNotTakeAndSendsItWithWhatCameLater() = runTest {
        val engine = engine()
        engine.record(currency)
        backendUp = false
        engine.flush()
        runCurrent()
        engine.record(language)

        backendUp = true
        engine.flush()
        runCurrent()

        assertEquals(listOf(listOf(currency, language)), sent)
    }

    @Test
    fun turningUsageDataOffDiscardsWhatWasNotSentAndRecordsNothingMore() = runTest {
        val engine = engine()
        engine.record(currency)

        engine.setEnabled(false)
        engine.record(language)
        engine.flush()
        runCurrent()

        assertEquals(emptyList(), sent)
        // The choice survives a restart.
        val restarted = engine()
        assertFalse(restarted.enabled.value)
        restarted.record(theme)
        restarted.flush()
        runCurrent()
        assertEquals(emptyList(), sent)
    }

    @Test
    fun turningUsageDataOffWhileTheBackendIsAwayDropsTheUnsentBatch() = runTest {
        // The batch is out of the buffer during the request; a failed request must not put it
        // back after the user said no.
        lateinit var engine: TelemetryEngine
        engine = TelemetryEngine(
            settings = settings,
            mode = TelemetryMode.Upload,
            scope = backgroundScope,
            newInstallation = { true },
            send = { events ->
                if (events == listOf(currency)) {
                    engine.setEnabled(false)
                    false
                } else {
                    sent += events
                    true
                }
            }
        )
        engine.record(currency)
        engine.flush()
        runCurrent()

        engine.setEnabled(true)
        engine.record(language)
        engine.flush()
        runCurrent()

        assertEquals(listOf(listOf(language)), sent)
    }

    @Test
    fun recordsAOncePerInstallationEventOnlyOnceAcrossRestarts() = runTest {
        val engine = engine()
        engine.recordOnce("door", currency)
        engine.recordOnce("door", currency)
        engine.recordOnce("other", language)
        val restarted = engine()
        restarted.recordOnce("door", currency)

        engine.flush()
        restarted.flush()
        runCurrent()

        assertEquals(listOf(listOf(currency, language)), sent)
    }

    @Test
    fun anEventThatWasNotRecordedWhileOffIsStillRecordedOnceLater() = runTest {
        val engine = engine()
        engine.setEnabled(false)
        engine.recordOnce("door", currency)

        engine.setEnabled(true)
        engine.recordOnce("door", currency)
        engine.flush()
        runCurrent()

        assertEquals(listOf(listOf(currency)), sent)
    }

    @Test
    fun onlyInstallationsThatStartedWithUsageDataReportOnboarding() = runTest {
        // Someone who updated is past the first steps; their later steps would look like a funnel
        // that starts in the middle.
        val updated = engine(newInstallation = false)
        updated.recordOnboarding("first_payment", currency)
        // Decided on the first start, not again once the app is onboarded.
        val later = engine(newInstallation = true)
        later.recordOnboarding("first_payment", currency)

        later.flush()
        updated.flush()
        runCurrent()

        assertEquals(emptyList(), sent)
    }

    @Test
    fun aNewInstallationReportsEachOnboardingStepOnce() = runTest {
        val engine = engine(newInstallation = true)
        engine.recordOnboarding("welcome", currency)
        // After onboarding the app would no longer look new.
        val restarted = engine(newInstallation = false)
        restarted.recordOnboarding("welcome", currency)
        restarted.recordOnboarding("first_payment", language)

        engine.flush()
        restarted.flush()
        runCurrent()

        assertEquals(listOf(listOf(currency), listOf(language)), sent)
    }

    @Test
    fun keepsOnlyTheNewestEventsWhenTheBufferIsFull() = runTest {
        val engine = engine()
        repeat(TelemetryEngine.MAX_BUFFERED_EVENTS) { engine.record(currency) }
        engine.record(language)

        engine.flush()
        runCurrent()

        assertEquals(TelemetryEngine.MAX_BUFFERED_EVENTS, sent.single().size)
        assertEquals(language, sent.single().last())
    }

    @Test
    fun aDelayedFlushWaitsAndALaterOneReplacesIt() = runTest {
        val engine = engine()
        engine.record(currency)

        engine.flushAfter(3_000)
        advanceTimeBy(2_000)
        engine.flushAfter(3_000)
        advanceTimeBy(2_000)
        assertEquals(emptyList(), sent)

        advanceTimeBy(1_001)
        assertEquals(listOf(listOf(currency)), sent)
    }

    @Test
    fun aDebugBuildLogsEventsAndSendsNothing() = runTest {
        val engine = engine(mode = TelemetryMode.Log)
        engine.record(currency)

        engine.flush()
        runCurrent()

        assertEquals(listOf("""{"name":"setting_changed","dimensions":{"setting":"currency"}}"""), logged)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun anAutomatedBuildKeepsNothing() = runTest {
        val engine = engine(mode = TelemetryMode.Off)
        engine.record(currency)
        engine.recordOnce("door", language)

        engine.flush()
        runCurrent()

        assertEquals(emptyList(), sent)
        assertEquals(emptyList(), logged)
    }
}
