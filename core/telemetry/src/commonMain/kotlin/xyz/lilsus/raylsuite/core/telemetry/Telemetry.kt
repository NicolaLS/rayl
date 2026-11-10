package xyz.lilsus.raylsuite.core.telemetry

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import xyz.lilsus.raylsuite.core.raylapi.TelemetryCameraVariant
import xyz.lilsus.raylsuite.core.raylapi.TelemetryCatalog
import xyz.lilsus.raylsuite.core.raylapi.TelemetryEvent
import xyz.lilsus.raylsuite.core.raylapi.TelemetryFakeDoor
import xyz.lilsus.raylsuite.core.raylapi.TelemetryFakeDoorAction
import xyz.lilsus.raylsuite.core.raylapi.TelemetryOnboardingStep
import xyz.lilsus.raylsuite.core.raylapi.TelemetrySetting
import xyz.lilsus.raylsuite.core.raylapi.TelemetryUsageDataChoice
import xyz.lilsus.raylsuite.core.raylapi.TelemetryWallet
import xyz.lilsus.raylsuite.core.raylapi.TelemetryWalletAction
import xyz.lilsus.raylsuite.core.raylapi.telemetryEvent

/** Where a feature reports an event; replaceable so a test can read what was reported. */
fun interface TelemetryRecorder {
    fun record(event: TelemetryEvent)
}

/**
 * Rayl's anonymous usage data, as the app's code sees it. Everything that can be recorded is
 * listed in `TelemetryCatalog.kt`. Until the app installs an engine at launch, and in builds that
 * install none, every call does nothing.
 */
@OptIn(ExperimentalAtomicApi::class)
object Telemetry : TelemetryRecorder {
    @Volatile
    private var engine: TelemetryEngine? = null
    private val notInstalled = MutableStateFlow(false)
    private val launchRecorded = AtomicBoolean(false)
    private val firstPaymentTaken = AtomicBoolean(false)
    private val changedSettings = mutableSetOf<TelemetrySetting>()

    /** The wallet in use, set by the app when it mounts one. */
    @Volatile
    var wallet: TelemetryWallet = TelemetryWallet.None

    /** The scanner configuration this process drew; see `CameraVariant` in `core/camera`. */
    @Volatile
    var cameraVariant: TelemetryCameraVariant = TelemetryCameraVariant.Control

    fun install(engine: TelemetryEngine) {
        this.engine = engine
    }

    /** Whether the user shares usage data; on unless they turned it off in Settings. */
    val enabled: StateFlow<Boolean> get() = engine?.enabled ?: notInstalled

    fun setEnabled(enabled: Boolean) {
        val engine = engine ?: return
        val turnedOnAgain = enabled && !engine.enabled.value
        engine.setEnabled(enabled)
        if (turnedOnAgain) record(usageDataChoice(TelemetryUsageDataChoice.TurnedOnAgain))
    }

    /**
     * The "turn off usage data?" question was shown. This goes out at once, while the user still
     * shares usage data: whoever then turns it off is never heard from again, and is counted as
     * someone who was asked and did not keep it on.
     */
    fun usageDataAsked() {
        engine?.sendNow(usageDataChoice(TelemetryUsageDataChoice.Asked))
    }

    /** The user saw the question and kept usage data on. */
    fun usageDataKeptOn() {
        record(usageDataChoice(TelemetryUsageDataChoice.KeptOn))
    }

    private fun usageDataChoice(choice: TelemetryUsageDataChoice) =
        telemetryEvent(TelemetryCatalog.usageDataChoice, mapOf("choice" to choice))

    override fun record(event: TelemetryEvent) {
        engine?.record(event)
    }

    /** Reported once per installation, by installations that started with usage data. */
    fun onboarding(step: TelemetryOnboardingStep) {
        engine?.recordOnboarding(
            key = "onboarding.${step.wire}",
            event = telemetryEvent(
                TelemetryCatalog.onboarding,
                mapOf(TelemetryCatalog.WALLET to wallet, "step" to step)
            )
        )
    }

    /** Reported once per installation for each door and action. */
    fun fakeDoor(door: TelemetryFakeDoor, action: TelemetryFakeDoorAction) {
        engine?.recordOnce(
            key = "fake_door.${door.wire}.${action.wire}",
            event = telemetryEvent(
                TelemetryCatalog.fakeDoor,
                mapOf("door" to door, "action" to action)
            )
        )
    }

    /**
     * Reports that [setting] was changed, once per process: a slider reports every step, and how
     * often someone toggles a switch back and forth says nothing. Main thread only.
     */
    fun settingChanged(setting: TelemetrySetting) {
        if (!changedSettings.add(setting)) return
        record(telemetryEvent(TelemetryCatalog.settingChanged, mapOf("setting" to setting)))
    }

    fun walletChanged(wallet: TelemetryWallet, action: TelemetryWalletAction) {
        record(
            telemetryEvent(
                TelemetryCatalog.walletChanged,
                mapOf(TelemetryCatalog.WALLET to wallet, "action" to action)
            )
        )
    }

    /** True for the first payment of this process, which carries the cold-start timings. */
    fun takeFirstPayment(): Boolean = firstPaymentTaken.compareAndSet(false, true)

    /** Sends what is buffered; for when the app leaves the foreground. */
    fun flush() {
        recordLaunch()
        engine?.flush()
    }

    suspend fun flushNow() {
        recordLaunch()
        engine?.flushNow()
    }

    /** Sends a few seconds from now, once the result the user waited for is on screen. */
    fun flushSoon() {
        recordLaunch()
        engine?.flushAfter(FLUSH_SOON_DELAY_MS)
    }

    /** One per process, written when the first batch leaves so its timings are complete. */
    private fun recordLaunch() {
        if (!launchRecorded.compareAndSet(false, true)) return
        val fromLaunch = RaylTrace.countsFromLaunch
        record(
            telemetryEvent(
                TelemetryCatalog.launch,
                dimensions = mapOf(
                    TelemetryCatalog.WALLET to wallet,
                    TelemetryCatalog.CAMERA_VARIANT to cameraVariant
                ),
                measures = mapOf(
                    "process_to_first_frame_ms" to
                        RaylTrace.firstMs(TracePoint.CameraFirstFrame).takeIf { fromLaunch },
                    "wallet_ready_ms" to
                        RaylTrace.firstMs(TracePoint.WalletReady).takeIf { fromLaunch }
                )
            )
        )
    }

    private const val FLUSH_SOON_DELAY_MS = 3_000L
}
