package xyz.lilsus.raylsuite.core.raylapi

import kotlinx.serialization.Serializable

/*
 * Everything Rayl's anonymous usage data can contain is in this file. The app builds its events
 * from it and the backend drops whatever it does not describe, so nothing else can be collected.
 *
 * Rules:
 * - No identifier of any kind and no client timestamps. The backend stores neither the sender's
 *   address nor the exact arrival time.
 * - A dimension is one value out of a fixed list; a measure is a whole number. There is no free
 *   text, so amounts, invoices, payment hashes, addresses, balances, keys, Lightning addresses,
 *   contact names and error messages cannot be sent.
 * - Additive only: released apps keep sending what they shipped with, and the backend drops what
 *   it no longer knows.
 */

/**
 * What one app process buffered, sent to [RaylApi.TELEMETRY_PATH]. [deviceModel] is the hardware
 * model (for example `Pixel 8` or `iPhone15,2`); camera and payment timings depend on it.
 */
@Serializable
data class TelemetryBatch(
    val platform: String,
    val appVersion: String,
    val osMajor: Int,
    val deviceModel: String,
    val events: List<TelemetryEvent>
)

@Serializable
data class TelemetryEvent(
    val name: String,
    val dimensions: Map<String, String> = emptyMap(),
    val measures: Map<String, Long> = emptyMap()
)

interface TelemetryValue {
    val wire: String
}

/** The wallet in use; [None] before one is mounted. */
enum class TelemetryWallet(override val wire: String) : TelemetryValue {
    Rayl("rayl"),
    Blink("blink"),
    Nwc("nwc"),
    Spark("spark"),
    None("none")
}

enum class TelemetryPaymentSource(override val wire: String) : TelemetryValue {
    Scan("scan"),

    /** Pasted or typed payment input. */
    Input("input"),
    Hub("hub")
}

enum class TelemetryPaymentConfirmation(override val wire: String) : TelemetryValue {
    /** Paid without asking (Auto Pay, or a Hub preset). */
    Auto("auto"),
    Confirmed("confirmed"),

    /** The user entered the amount, which is the confirmation. */
    AmountEntered("amount_entered")
}

enum class TelemetryPaymentOutcome(override val wire: String) : TelemetryValue {
    Paid("paid"),
    AlreadyPaid("already_paid"),
    Failed("failed"),
    Unknown("unknown"),

    /** Stopped before the wallet was asked to pay. */
    Rejected("rejected"),

    /** The user closed the confirmation. */
    Dismissed("dismissed")
}

enum class TelemetryPaymentError(override val wire: String) : TelemetryValue {
    None("none"),
    InvalidInvoice("invalid_invoice"),
    Lnurl("lnurl"),
    Network("network"),
    ExchangeRate("exchange_rate"),
    InsufficientFunds("insufficient_funds"),
    WalletNotReady("wallet_not_ready"),
    Expired("expired"),
    RejectedByWallet("rejected_by_wallet"),
    OutcomeUnknown("outcome_unknown"),

    /** A wallet-specific failure without a category of its own. */
    Wallet("wallet"),
    Unexpected("unexpected")
}

enum class TelemetryYesNo(override val wire: String) : TelemetryValue {
    Yes("yes"),
    No("no")
}

/** Scanner configuration under comparison; see `CameraVariant` in `core/camera`. */
enum class TelemetryCameraVariant(override val wire: String) : TelemetryValue {
    Control("control")
}

enum class TelemetryScanSession(override val wire: String) : TelemetryValue {
    /** The scanner's first start in this process. */
    First("first"),
    Restart("restart")
}

/** Which code reader decoded the code first. */
enum class TelemetryScanDecoder(override val wire: String) : TelemetryValue {
    /** Android: Google ML Kit. */
    MlKit("mlkit"),

    /** iOS: AVFoundation's metadata output. */
    AvFoundation("avfoundation"),

    /** iOS: Apple's Vision framework on the camera's frames. */
    Vision("vision")
}

/** Where two code readers race: what the slower one did before the scanner stopped. */
enum class TelemetryScanRunnerUp(override val wire: String) : TelemetryValue {
    Decoded("decoded"),
    Missed("missed")
}

/** Sent at most once per installation, and only by installations that started after telemetry. */
enum class TelemetryOnboardingStep(override val wire: String) : TelemetryValue {
    Welcome("welcome"),
    AppOnboarded("app_onboarded"),
    WalletReady("wallet_ready"),
    Funded("funded"),
    FirstPayment("first_payment")
}

enum class TelemetryWalletAction(override val wire: String) : TelemetryValue {
    Connected("connected"),
    Removed("removed")
}

enum class TelemetrySetting(override val wire: String) : TelemetryValue {
    ConfirmationAlways("confirmation_always"),
    ConfirmationThreshold("confirmation_threshold"),
    ConfirmationAmount("confirmation_amount"),
    Currency("currency"),
    Language("language"),
    Theme("theme"),
    Haptics("haptics"),
    OfferToSaveTargets("offer_to_save_targets")
}

/**
 * A feature that is offered before it exists, to learn whether it is wanted. Add a value here
 * together with the door itself.
 */
enum class TelemetryFakeDoor(override val wire: String) : TelemetryValue {
    /** Never shown; keeps the list non-empty until the first door exists. */
    Placeholder("placeholder")
}

enum class TelemetryFakeDoorAction(override val wire: String) : TelemetryValue {
    Shown("shown"),
    Tapped("tapped")
}

/** One kind of event: the only dimension values and measures it may carry. */
class TelemetryEventSpec(
    val name: String,
    val dimensions: Map<String, Set<String>>,
    val measures: Set<String>
)

object TelemetryCatalog {
    const val MAX_EVENTS_PER_BATCH = 200
    const val MAX_DEVICE_MODEL_LENGTH = 64

    /** Durations are milliseconds; one hour bounds them, and counts and pixel sizes fit below it. */
    val MEASURE_RANGE = 0L..3_600_000L
    val OS_MAJOR_RANGE = 1..99
    val PLATFORMS = setOf("android", "ios")

    private val appVersionPattern = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}(-[a-z0-9.]{1,16})?""")
    private val deviceModelPattern = Regex("""[A-Za-z0-9 ._,()+/-]+""")

    /** One cold process start that reached the foreground. */
    val launch = spec(
        name = "launch",
        dimensions = mapOf(
            WALLET to TelemetryWallet.entries,
            CAMERA_VARIANT to TelemetryCameraVariant.entries
        ),
        // From process start (on iOS: from app initialisation) to the scanner's first camera
        // frame, and to Rayl's wallet being ready to pay.
        measures = setOf("process_to_first_frame_ms", "wallet_ready_ms")
    )

    /** The first QR code a scanner session decoded. */
    val scan = spec(
        name = "scan",
        dimensions = mapOf(
            CAMERA_VARIANT to TelemetryCameraVariant.entries,
            "session" to TelemetryScanSession.entries,
            "decoder" to TelemetryScanDecoder.entries,
            "runner_up" to TelemetryScanRunnerUp.entries
        ),
        measures = setOf(
            "camera_start_to_ready_ms",
            "camera_start_to_first_frame_ms",
            // Includes the time the user needs to aim.
            "first_frame_to_qr_ms",
            "frames_analyzed",
            "frame_analysis_avg_ms",
            "frame_width",
            "frame_height",
            "process_to_qr_ms",
            // How much later the slower reader decoded the same code, or how long the scanner
            // still ran without it doing so.
            "runner_up_lag_ms",
            "runner_up_wait_ms"
        )
    )

    /** One payment attempt that reached a result. */
    val payment = spec(
        name = "payment",
        dimensions = mapOf(
            WALLET to TelemetryWallet.entries,
            "source" to TelemetryPaymentSource.entries,
            "confirmation" to TelemetryPaymentConfirmation.entries,
            "outcome" to TelemetryPaymentOutcome.entries,
            "error" to TelemetryPaymentError.entries,
            "first_in_launch" to TelemetryYesNo.entries
        ),
        measures = setOf(
            "qr_to_admitted_ms",
            "admitted_to_prepared_ms",
            "submit_to_outcome_ms",
            // Cold start to paid without a tap: only for the first, scanned, unconfirmed payment.
            // It includes however long the app was open before a code came into view.
            "process_to_outcome_ms",
            // How long the scanner had been running when it read the code. Short: the user
            // pointed at the code and then opened the app. Long: the app was open and waiting.
            "camera_to_qr_ms",
            // Code read to paid without a tap: what the user waits for in either case.
            "qr_to_outcome_ms"
        )
    )

    val onboarding = spec(
        name = "onboarding",
        dimensions = mapOf(
            WALLET to TelemetryWallet.entries,
            "step" to TelemetryOnboardingStep.entries
        )
    )

    /** An external wallet was connected, or removed to go back to Rayl's wallet. */
    val walletChanged = spec(
        name = "wallet_changed",
        dimensions = mapOf(
            WALLET to TelemetryWallet.entries,
            "action" to TelemetryWalletAction.entries
        )
    )

    /** Which setting was changed, never its value. */
    val settingChanged = spec(
        name = "setting_changed",
        dimensions = mapOf("setting" to TelemetrySetting.entries)
    )

    /** Sent at most once per installation for each door and action. */
    val fakeDoor = spec(
        name = "fake_door",
        dimensions = mapOf(
            "door" to TelemetryFakeDoor.entries,
            "action" to TelemetryFakeDoorAction.entries
        )
    )

    val events: List<TelemetryEventSpec> =
        listOf(launch, scan, payment, onboarding, walletChanged, settingChanged, fakeDoor)

    private val eventsByName = events.associateBy(TelemetryEventSpec::name)

    /**
     * [batch] reduced to what the catalogue describes, or null when its header is invalid or
     * nothing is left. Unknown events, dimensions, values and measures are dropped one by one, so
     * an app that is newer or older than the backend still reports what both understand.
     */
    fun sanitize(batch: TelemetryBatch): TelemetryBatch? {
        if (batch.platform !in PLATFORMS) return null
        if (!appVersionPattern.matches(batch.appVersion)) return null
        if (batch.osMajor !in OS_MAJOR_RANGE) return null
        if (batch.deviceModel.length > MAX_DEVICE_MODEL_LENGTH) return null
        if (!deviceModelPattern.matches(batch.deviceModel)) return null
        if (batch.events.size > MAX_EVENTS_PER_BATCH) return null
        val events = batch.events.mapNotNull(::sanitize)
        return if (events.isEmpty()) null else batch.copy(events = events)
    }

    fun sanitize(event: TelemetryEvent): TelemetryEvent? {
        val spec = eventsByName[event.name] ?: return null
        return TelemetryEvent(
            name = spec.name,
            dimensions = event.dimensions.filter { (key, value) ->
                spec.dimensions[key]?.contains(value) == true
            },
            measures = event.measures.filter { (key, value) ->
                key in spec.measures && value in MEASURE_RANGE
            }
        )
    }

    const val WALLET = "wallet"
    const val CAMERA_VARIANT = "camera_variant"

    private fun spec(
        name: String,
        dimensions: Map<String, List<TelemetryValue>>,
        measures: Set<String> = emptySet()
    ) = TelemetryEventSpec(
        name = name,
        dimensions = dimensions.mapValues { (_, values) ->
            values.mapTo(mutableSetOf()) { it.wire }
        },
        measures = measures
    )
}

/** Builds [spec]'s event; measures that were not taken are passed as null and left out. */
fun telemetryEvent(
    spec: TelemetryEventSpec,
    dimensions: Map<String, TelemetryValue> = emptyMap(),
    measures: Map<String, Long?> = emptyMap()
): TelemetryEvent {
    val event = TelemetryEvent(
        name = spec.name,
        dimensions = dimensions.mapValues { (_, value) -> value.wire },
        measures = buildMap {
            measures.forEach { (key, value) ->
                if (value != null) put(key, value.coerceIn(TelemetryCatalog.MEASURE_RANGE))
            }
        }
    )
    check(TelemetryCatalog.sanitize(event) == event) {
        "${spec.name} carries a dimension or measure the catalogue does not describe"
    }
    return event
}
