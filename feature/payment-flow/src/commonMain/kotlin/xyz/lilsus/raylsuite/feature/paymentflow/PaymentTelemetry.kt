package xyz.lilsus.raylsuite.feature.paymentflow

import xyz.lilsus.raylsuite.core.raylapi.TelemetryCatalog
import xyz.lilsus.raylsuite.core.raylapi.TelemetryOnboardingStep
import xyz.lilsus.raylsuite.core.raylapi.TelemetryPaymentConfirmation
import xyz.lilsus.raylsuite.core.raylapi.TelemetryPaymentError
import xyz.lilsus.raylsuite.core.raylapi.TelemetryPaymentOutcome
import xyz.lilsus.raylsuite.core.raylapi.TelemetryPaymentSource
import xyz.lilsus.raylsuite.core.raylapi.TelemetryWallet
import xyz.lilsus.raylsuite.core.raylapi.TelemetryYesNo
import xyz.lilsus.raylsuite.core.raylapi.telemetryEvent
import xyz.lilsus.raylsuite.core.telemetry.RaylTrace
import xyz.lilsus.raylsuite.core.telemetry.Telemetry
import xyz.lilsus.raylsuite.core.telemetry.TelemetryRecorder
import xyz.lilsus.raylsuite.core.telemetry.TracePoint

/**
 * Follows one payment attempt through the shared flow and reports it as one `payment` event:
 * where it came from, how it was confirmed, how it ended, and how long the machine stages took.
 * It is handed stages and categories only, never an amount, an invoice or a recipient.
 *
 * The defaults read the process-wide clock and state; tests replace them.
 */
class PaymentTelemetry(
    private val recorder: TelemetryRecorder = Telemetry,
    private val nowMs: () -> Long = RaylTrace::nowMs,
    private val latestQrMs: () -> Long? = { RaylTrace.latestMs(TracePoint.QrDetected) },
    private val latestCameraFrameMs: () -> Long? = {
        RaylTrace.latestMs(TracePoint.CameraFirstFrame)
    },
    private val wallet: () -> TelemetryWallet = { Telemetry.wallet },
    private val takeFirstPayment: () -> Boolean = Telemetry::takeFirstPayment,
    private val countsFromLaunch: Boolean = RaylTrace.countsFromLaunch,
    private val onPaid: () -> Unit = { Telemetry.onboarding(TelemetryOnboardingStep.FirstPayment) },
    private val afterResult: () -> Unit = Telemetry::flushSoon
) {
    private var attempt: Attempt? = null

    internal fun started(source: TelemetryPaymentSource) {
        val scanned = source == TelemetryPaymentSource.Scan
        attempt = Attempt(
            source = source,
            qrMs = if (scanned) latestQrMs() else null,
            cameraMs = if (scanned) latestCameraFrameMs() else null
        )
    }

    /** What the flow showed instead of going on to pay. */
    internal fun presented(state: PaymentUiState) {
        when (state) {
            is PaymentUiState.Error ->
                finished(TelemetryPaymentOutcome.Rejected, state.error.telemetryError())

            // From here on the durations include the user's time.
            is PaymentUiState.EnterAmount ->
                attempt?.confirmation = TelemetryPaymentConfirmation.AmountEntered

            // A scan that is ignored while the confirmation is open arrives here again.
            is PaymentUiState.Confirm -> if (attempt?.preparedMs == null) {
                prepared()
                attempt?.confirmation = TelemetryPaymentConfirmation.Confirmed
            }

            // The input was not a payment (an unsupported code, or one already in the history).
            else -> attempt = null
        }
    }

    internal fun admitted() {
        RaylTrace.mark(TracePoint.PaymentAdmitted)
        attempt?.admittedMs = nowMs()
    }

    /** The wallet bound the invoice to its funding (for Rayl's wallet, the quote). */
    internal fun prepared() {
        RaylTrace.mark(TracePoint.PaymentPrepared)
        attempt?.preparedMs = nowMs()
    }

    internal fun submitted() {
        RaylTrace.mark(TracePoint.PaymentSubmitted)
        attempt?.submittedMs = nowMs()
    }

    /** An unexpected failure: rejected if the wallet was never asked to pay, unknown after. */
    internal fun failedUnexpectedly(error: PaymentUiError) {
        val outcome =
            if (attempt?.submittedMs != null) {
                TelemetryPaymentOutcome.Unknown
            } else {
                TelemetryPaymentOutcome.Rejected
            }
        finished(outcome, error.telemetryError())
    }

    internal fun abandoned() {
        attempt = null
    }

    internal fun finished(outcome: TelemetryPaymentOutcome, error: TelemetryPaymentError) {
        val attempt = attempt ?: return
        this.attempt = null
        RaylTrace.mark(TracePoint.PaymentOutcome)
        val now = nowMs()
        val reachedWallet = attempt.submittedMs != null
        val first = reachedWallet && takeFirstPayment()
        val untouched = attempt.confirmation == TelemetryPaymentConfirmation.Auto
        // Entering an amount comes before admission; a confirmation comes after preparation.
        val admittedByMachine =
            attempt.confirmation != TelemetryPaymentConfirmation.AmountEntered
        recorder.record(
            telemetryEvent(
                TelemetryCatalog.payment,
                dimensions = mapOf(
                    TelemetryCatalog.WALLET to wallet(),
                    "source" to attempt.source,
                    "confirmation" to attempt.confirmation,
                    "outcome" to outcome,
                    "error" to error,
                    "first_in_launch" to if (first) TelemetryYesNo.Yes else TelemetryYesNo.No
                ),
                measures = mapOf(
                    "qr_to_admitted_ms" to
                        attempt.admittedMs.takeIf { admittedByMachine }.since(attempt.qrMs),
                    "admitted_to_prepared_ms" to attempt.preparedMs.since(attempt.admittedMs),
                    "submit_to_outcome_ms" to attempt.submittedMs?.let { now - it },
                    "camera_to_qr_ms" to attempt.qrMs.since(attempt.cameraMs),
                    "qr_to_outcome_ms" to now.takeIf {
                        untouched && outcome == TelemetryPaymentOutcome.Paid
                    }.since(attempt.qrMs),
                    "process_to_outcome_ms" to now.takeIf {
                        first &&
                            untouched &&
                            countsFromLaunch &&
                            attempt.qrMs != null &&
                            outcome == TelemetryPaymentOutcome.Paid
                    }
                )
            )
        )
        if (outcome == TelemetryPaymentOutcome.Paid) onPaid()
        afterResult()
    }

    private class Attempt(
        val source: TelemetryPaymentSource,
        val qrMs: Long?,
        /** When the scanner that read the code got its first frame. */
        val cameraMs: Long?
    ) {
        var confirmation = TelemetryPaymentConfirmation.Auto
        var admittedMs: Long? = null
        var preparedMs: Long? = null
        var submittedMs: Long? = null
    }
}

/** Null when either end was not timed, or the clock says the stage ended before it began. */
private fun Long?.since(start: Long?): Long? =
    if (this == null || start == null) null else (this - start).takeIf { it >= 0 }

internal fun PaymentUiError.telemetryError(): TelemetryPaymentError = when (this) {
    is PaymentUiError.Provider -> error.telemetryError
    is PaymentUiError.InvalidInvoice -> TelemetryPaymentError.InvalidInvoice
    is PaymentUiError.Lnurl -> TelemetryPaymentError.Lnurl
    PaymentUiError.NetworkUnavailable -> TelemetryPaymentError.Network
    is PaymentUiError.ExchangeRateUnavailable -> TelemetryPaymentError.ExchangeRate
    is PaymentUiError.Unexpected -> TelemetryPaymentError.Unexpected
}
