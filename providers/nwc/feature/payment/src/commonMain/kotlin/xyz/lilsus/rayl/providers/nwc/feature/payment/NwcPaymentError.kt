package xyz.lilsus.rayl.providers.nwc.feature.payment

import xyz.lilsus.raylsuite.core.raylapi.TelemetryPaymentError
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.localizedTextWithOptionalDetail
import xyz.lilsus.raylsuite.feature.paymentflow.ProviderPaymentError
import xyz.lilsus.raylsuite.feature.paymentui.unexpectedPaymentErrorText

internal enum class NwcPaymentTextKey(override val key: String) : LocalizedTextKey {
    ErrorMissingWalletConnection("error_missing_wallet_connection"),
    ErrorPaymentRejectedGeneric("error_payment_rejected_generic"),
    ErrorPaymentRejectedMessage("error_payment_rejected_message"),
    ErrorPaymentUnconfirmed("error_payment_unconfirmed"),
    ErrorPaymentUnconfirmedMessage("error_payment_unconfirmed_message"),
    ErrorRelayConnectionFailed("error_relay_connection_failed");

    override val table: String = "NwcPayment"
}

sealed interface NwcPaymentError : ProviderPaymentError {
    val detail: String?

    data object MissingWalletConnection : NwcPaymentError {
        override val detail: String? = null
    }

    data class Connection(override val detail: String?) : NwcPaymentError

    data class Rejected(val code: String?, override val detail: String?) : NwcPaymentError

    data class DefinitelyNotSent(override val detail: String?) : NwcPaymentError

    data class OutcomeUnknown(override val detail: String?) : NwcPaymentError

    data class Unexpected(override val detail: String?) : NwcPaymentError

    override val telemetryError: TelemetryPaymentError
        get() = when (this) {
            MissingWalletConnection -> TelemetryPaymentError.WalletNotReady
            is Connection, is DefinitelyNotSent -> TelemetryPaymentError.Network
            is Rejected -> TelemetryPaymentError.RejectedByWallet
            is OutcomeUnknown -> TelemetryPaymentError.OutcomeUnknown
            is Unexpected -> TelemetryPaymentError.Unexpected
        }

    override fun toLocalizedText(): LocalizedText = when (this) {
        MissingWalletConnection -> LocalizedText(NwcPaymentTextKey.ErrorMissingWalletConnection)

        is Connection -> LocalizedText(NwcPaymentTextKey.ErrorRelayConnectionFailed)

        is Rejected ->
            localizedTextWithOptionalDetail(
                detail ?: code,
                NwcPaymentTextKey.ErrorPaymentRejectedGeneric,
                NwcPaymentTextKey.ErrorPaymentRejectedMessage
            )

        is OutcomeUnknown ->
            localizedTextWithOptionalDetail(
                detail,
                NwcPaymentTextKey.ErrorPaymentUnconfirmed,
                NwcPaymentTextKey.ErrorPaymentUnconfirmedMessage
            )

        is DefinitelyNotSent,
        is Unexpected -> unexpectedPaymentErrorText(detail)
    }
}
