package xyz.lilsus.raylsuite.feature.paymentflow

import fr.acinq.lightning.payment.Bolt11Invoice
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.CurrencyInfo
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.payment.LightningInputParser
import xyz.lilsus.raylsuite.core.payment.LnurlInvoiceRequest
import xyz.lilsus.raylsuite.core.payment.LnurlInvoiceResolution
import xyz.lilsus.raylsuite.core.payment.LnurlInvoiceResolver
import xyz.lilsus.raylsuite.core.payment.LnurlPayClient
import xyz.lilsus.raylsuite.core.payment.LnurlPayParams
import xyz.lilsus.raylsuite.feature.paymentcurrency.CurrencyState
import xyz.lilsus.raylsuite.feature.paymenthub.HubItemId
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountConfig
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountController

internal class PaymentPreparation(lnurlPayClient: LnurlPayClient) {
    val inputParser = LightningInputParser()
    val manualAmount =
        ManualAmountController(
            ManualAmountConfig(
                info = CurrencyCatalog.infoFor(CurrencyCatalog.DEFAULT_CODE),
                exchangeRate = null
            )
        )
    private val lnurlInvoiceResolver = LnurlInvoiceResolver(lnurlPayClient)

    var manualEntryContext: ManualEntryContext? = null

    suspend fun resolveLnurlInvoice(
        session: LnurlSession,
        amountMsats: Long
    ): LnurlInvoiceResolution = lnurlInvoiceResolver.resolve(
        LnurlInvoiceRequest(
            params = session.params,
            amountMsats = amountMsats,
            comment = session.comment
        )
    )

    fun reset(currencyState: CurrencyState) {
        manualEntryContext = null
        manualAmount.reset(
            ManualAmountConfig(
                info = currencyState.info,
                exchangeRate = currencyState.exchangeRate
            ),
            clearInput = true
        )
    }
}

internal data class LnurlSession(
    val params: LnurlPayParams,
    val display: LnurlPayDisplay?,
    val targetContext: HubTargetContext?,
    val comment: String?
)

/**
 * App-owned link between a payment and the hub. A known [targetId] receives the success
 * statistic; an unknown address may be offered for saving. Never persisted.
 */
data class HubTargetContext(
    val targetId: HubItemId?,
    val address: LightningAddress,
    val isPreset: Boolean
)

internal sealed interface ManualEntryContext {
    data class Bolt(val invoice: Bolt11Invoice) : ManualEntryContext

    data class Lnurl(val session: LnurlSession, val inputInfo: CurrencyInfo) :
        ManualEntryContext
}
