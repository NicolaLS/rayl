package xyz.lilsus.raylsuite.feature.paymentflow

import fr.acinq.lightning.payment.Bolt11Invoice
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.payment.LightningInputParser
import xyz.lilsus.raylsuite.core.payment.LnurlInvoiceResolution
import xyz.lilsus.raylsuite.core.payment.LnurlPayClient
import xyz.lilsus.raylsuite.core.payment.LnurlPayParams
import xyz.lilsus.raylsuite.core.payment.LnurlResult
import xyz.lilsus.raylsuite.feature.paymentcurrency.CurrencyState
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager
import xyz.lilsus.raylsuite.feature.paymenthub.DirectTargetAmountRule
import xyz.lilsus.raylsuite.feature.paymenthub.host.DirectTargetPaymentIntent
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentToastMessage
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountConfig
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountKey

internal class PaymentAdmissionPhase(
    private val lnurlPayClient: LnurlPayClient,
    private val currencyManager: PaymentCurrencyManager,
    private val history: PaymentHistory,
    private val presentation: PaymentPresentationPhase,
    private val notifyScanSuccess: () -> Unit,
    private val nowSeconds: () -> Long
) {
    private val preparation = PaymentPreparation(lnurlPayClient)
    private val inputParser = preparation.inputParser
    private val manualAmount = preparation.manualAmount

    /** [isFromHubWidget]: a Hub widget's service issued [rawInput], so it is always confirmed. */
    suspend fun handlePaymentInput(
        rawInput: String,
        isFromHubWidget: Boolean = false
    ): AdmissionResult {
        if (presentation.uiState.value != PaymentUiState.Active) return AdmissionResult.Presented
        preparation.manualEntryContext = null
        return when (val result = inputParser.parse(rawInput)) {
            is LightningInputParser.ParseResult.Failure -> {
                handleParseFailure(result.reason)
                AdmissionResult.Presented
            }

            is LightningInputParser.ParseResult.Success ->
                when (val target = result.target) {
                    is LightningInputParser.Target.Bolt11 -> {
                        history.findPaidByPaymentHash(target.invoice.paymentHash.toHex())
                            ?.let { existing ->
                                requestTransactionDetailNavigation(existing.id)
                                return AdmissionResult.Presented
                            }
                        if (rejectExpiredInvoice(target.invoice)) return AdmissionResult.Presented
                        notifyScanSuccess()
                        processBoltInvoice(target.invoice, isFromHubWidget)
                    }

                    is LightningInputParser.Target.Lnurl -> {
                        notifyScanSuccess()
                        fetchLnurl(target.endpoint)
                    }

                    is LightningInputParser.Target.LightningAddressTarget -> {
                        notifyScanSuccess()
                        resolveLightningAddress(
                            address = target.address,
                            targetContext = HubTargetContext(null, target.address, isPreset = false)
                        )
                    }

                    else -> AdmissionResult.Presented
                }
        }
    }

    suspend fun payTarget(intent: DirectTargetPaymentIntent): AdmissionResult {
        if (presentation.uiState.value != PaymentUiState.Active) return AdmissionResult.Presented
        presentation.showLoading(LoadingKind.Resolving)
        val preset = (intent.amountRule as? DirectTargetAmountRule.Preset)?.amount
        val context =
            HubTargetContext(
                targetId = intent.targetId,
                address = intent.address,
                isPreset = preset != null
            )
        return if (preset == null) {
            resolveTargetPayment(
                address = intent.address,
                context = context,
                paymentQuote = null,
                comment = intent.comment
            )
        } else {
            val paymentQuote = currencyManager.quoteStoredAmount(preset)
            currentCoroutineContext().ensureActive()
            if (paymentQuote == null) {
                val info = CurrencyCatalog.infoFor(preset.normalizedCurrencyCode)
                presentation.presentError(
                    if (info.currency is DisplayCurrency.Fiat) {
                        PaymentUiError.ExchangeRateUnavailable(info.code)
                    } else {
                        PaymentUiError.InvalidInvoice(
                            "Preset amount could not be converted"
                        )
                    }
                )
                AdmissionResult.Presented
            } else {
                resolveTargetPayment(
                    address = intent.address,
                    context = context,
                    paymentQuote = paymentQuote,
                    comment = intent.comment
                )
            }
        }
    }

    suspend fun submitManualAmount(): AdmissionResult {
        val entryState = presentation.uiState.value as? PaymentUiState.EnterAmount
            ?: return AdmissionResult.Presented
        val context = preparation.manualEntryContext ?: return AdmissionResult.Presented
        val enteredAmount = manualAmount.enteredAmount() ?: return AdmissionResult.Presented
        presentation.showLoading(LoadingKind.Resolving)
        val paymentQuote = currencyManager.quote(enteredAmount)
        currentCoroutineContext().ensureActive()
        if (paymentQuote == null) {
            presentation.showManualAmount(entryState.entry, entryState.lnurlPayDisplay)
            val error =
                when (val currency = enteredAmount.currency) {
                    is DisplayCurrency.Fiat ->
                        PaymentUiError.ExchangeRateUnavailable(currency.iso4217)

                    else -> PaymentUiError.InvalidInvoice("Amount could not be converted")
                }
            presentation.emitErrorEvent(error)
            return AdmissionResult.Presented
        }

        return when (context) {
            is ManualEntryContext.Bolt ->
                AdmissionResult.Payment(
                    PreparedPayment(
                        invoice = context.invoice,
                        amountOverrideMsats = paymentQuote.amountMsats,
                        isManualEntry = true,
                        paymentQuote = paymentQuote
                    )
                )

            is ManualEntryContext.Lnurl -> {
                val roundedAmount = paymentQuote.amountMsats
                if (
                    roundedAmount < context.session.params.minSendable ||
                    roundedAmount > context.session.params.maxSendable
                ) {
                    presentation.showManualAmount(entryState.entry, entryState.lnurlPayDisplay)
                    presentation.emitErrorEvent(
                        PaymentUiError.InvalidInvoice(
                            "Amount is outside the allowed range"
                        )
                    )
                    AdmissionResult.Presented
                } else {
                    resolveLnurlInvoice(
                        session = context.session,
                        amountMsats = roundedAmount,
                        isManualEntry = true,
                        paymentQuote = paymentQuote
                    )
                }
            }
        }
    }

    fun updateManualAmount(key: ManualAmountKey) {
        val state = presentation.uiState.value as? PaymentUiState.EnterAmount ?: return
        preparation.manualEntryContext ?: return
        presentation.showManualAmount(
            manualAmount.handleKeyPress(key),
            state.lnurlPayDisplay
        )
    }

    fun dismissManualAmount() {
        if (presentation.uiState.value !is PaymentUiState.EnterAmount) return
        manualAmount.reset()
        preparation.manualEntryContext = null
        presentation.showActive()
    }

    fun refreshManualAmountState() {
        val preserveInput = preparation.manualEntryContext != null
        val currencyState = currencyManager.state.value
        val manualInfo =
            when (val context = preparation.manualEntryContext) {
                is ManualEntryContext.Lnurl -> context.inputInfo
                else -> currencyState.info
            }
        val manualCurrencyState =
            CurrencyState(
                info = manualInfo,
                exchangeRate =
                    currencyState.exchangeRate.takeIf {
                        manualInfo.code.equals(currencyState.info.code, ignoreCase = true)
                    }
            )
        val config =
            when (val context = preparation.manualEntryContext) {
                is ManualEntryContext.Lnurl ->
                    ManualAmountConfig(
                        info = manualCurrencyState.info,
                        exchangeRate = manualCurrencyState.exchangeRate,
                        minMsats = context.session.params.minSendable,
                        maxMsats = context.session.params.maxSendable
                    )

                else ->
                    ManualAmountConfig(
                        info = manualCurrencyState.info,
                        exchangeRate = manualCurrencyState.exchangeRate
                    )
            }
        val entry = manualAmount.reset(config, clearInput = !preserveInput)
        if (presentation.uiState.value is PaymentUiState.EnterAmount) {
            val display =
                (preparation.manualEntryContext as? ManualEntryContext.Lnurl)
                    ?.session
                    ?.display
            presentation.showManualAmount(entry, display)
        }
    }

    fun reset(currencyState: CurrencyState) {
        preparation.reset(currencyState)
    }

    private fun handleParseFailure(reason: LightningInputParser.FailureReason) {
        when (reason) {
            LightningInputParser.FailureReason.BitcoinAddress ->
                presentation.showToast(PaymentToastMessage.BitcoinAddressNotSupported)

            LightningInputParser.FailureReason.Bolt12 ->
                presentation.showToast(PaymentToastMessage.Bolt12NotSupported)

            LightningInputParser.FailureReason.UnsupportedLnurl ->
                presentation.showToast(PaymentToastMessage.LnurlRequestNotSupported)

            LightningInputParser.FailureReason.InvalidLnurl ->
                presentation.presentError(
                    PaymentUiError.InvalidInvoice("Invalid LNURL request")
                )

            is LightningInputParser.FailureReason.InvalidInvoice ->
                presentation.presentError(PaymentUiError.InvalidInvoice(reason.reason))

            LightningInputParser.FailureReason.Empty,
            LightningInputParser.FailureReason.Unrecognized -> Unit
        }
    }

    private fun processBoltInvoice(
        invoice: Bolt11Invoice,
        isFromHubWidget: Boolean
    ): AdmissionResult {
        val entry =
            manualAmount.reset(
                ManualAmountConfig(
                    info = currencyManager.state.value.info,
                    exchangeRate = currencyManager.state.value.exchangeRate
                ),
                clearInput = true
            )
        return if (invoice.amount == null) {
            preparation.manualEntryContext = ManualEntryContext.Bolt(invoice)
            presentation.showManualAmount(entry)
            AdmissionResult.Presented
        } else {
            AdmissionResult.Payment(
                PreparedPayment(
                    invoice = invoice,
                    amountOverrideMsats = null,
                    isFromHubWidget = isFromHubWidget
                )
            )
        }
    }

    private suspend fun fetchLnurl(endpoint: String): AdmissionResult {
        presentation.showLoading(LoadingKind.Resolving)
        val result = lnurlPayClient.fetchPayParams(endpoint)
        currentCoroutineContext().ensureActive()
        return when (result) {
            is LnurlResult.Success ->
                handleLnurlParams(params = result.data)

            is LnurlResult.Error -> {
                presentation.presentError(result.error.toPaymentUiError())
                AdmissionResult.Presented
            }
        }
    }

    private suspend fun resolveLightningAddress(
        address: LightningAddress,
        targetContext: HubTargetContext? = null,
        presetQuote: PaymentAmountQuote? = null,
        targetComment: String? = null
    ): AdmissionResult {
        presentation.showLoading(LoadingKind.Resolving)
        val result = lnurlPayClient.fetchPayParams(address)
        currentCoroutineContext().ensureActive()
        return when (result) {
            is LnurlResult.Success ->
                handleLnurlParams(
                    params = result.data,
                    targetContext = targetContext,
                    presetQuote = presetQuote,
                    targetComment = targetComment
                )

            is LnurlResult.Error -> {
                presentation.presentError(result.error.toPaymentUiError())
                AdmissionResult.Presented
            }
        }
    }

    private suspend fun resolveTargetPayment(
        address: LightningAddress,
        context: HubTargetContext,
        paymentQuote: PaymentAmountQuote?,
        comment: String?
    ): AdmissionResult {
        notifyScanSuccess()
        return resolveLightningAddress(
            address = address,
            targetContext = context,
            presetQuote = paymentQuote,
            targetComment = comment
        )
    }

    private suspend fun handleLnurlParams(
        params: LnurlPayParams,
        targetContext: HubTargetContext? = null,
        presetQuote: PaymentAmountQuote? = null,
        targetComment: String? = null
    ): AdmissionResult {
        if (params.minSendable <= 0 || params.maxSendable < params.minSendable) {
            presentation.presentError(
                PaymentUiError.InvalidInvoice("LNURL amount range is invalid")
            )
            return AdmissionResult.Presented
        }
        val lnurlPayDisplay =
            LnurlPayDisplay.fromUntrusted(
                domain = params.domain,
                description = params.metadata.plainText,
                imagePngBase64 = params.metadata.imagePng,
                imageJpegBase64 = params.metadata.imageJpeg,
                lightningAddress = targetContext?.address
            ) ?: run {
                presentation.presentError(
                    PaymentUiError.InvalidInvoice("LNURL payment details are invalid")
                )
                return AdmissionResult.Presented
            }
        val session =
            LnurlSession(
                params = params,
                display = lnurlPayDisplay,
                targetContext = targetContext,
                comment = targetComment
            )
        val currencyState = currencyManager.state.value
        val inputInfo = currencyState.info
        val manualCurrencyState =
            CurrencyState(
                info = inputInfo,
                exchangeRate =
                    currencyState.exchangeRate.takeIf {
                        inputInfo.code.equals(currencyState.info.code, ignoreCase = true)
                    }
            )

        if (
            inputInfo.currency is DisplayCurrency.Fiat &&
            inputInfo.code.equals(currencyState.info.code, ignoreCase = true)
        ) {
            currencyManager.ensureExchangeRateIfNeeded(inputInfo)
            currentCoroutineContext().ensureActive()
        }

        presetQuote?.let { paymentQuote ->
            val roundedAmount = paymentQuote.amountMsats
            if (
                roundedAmount < params.minSendable ||
                roundedAmount > params.maxSendable
            ) {
                presentation.presentError(
                    PaymentUiError.InvalidInvoice(
                        "Preset amount is outside the allowed range"
                    )
                )
                return AdmissionResult.Presented
            }
            return resolveLnurlInvoice(
                session = session,
                amountMsats = roundedAmount,
                isManualEntry = false,
                paymentQuote = paymentQuote
            )
        }

        if (params.minSendable == params.maxSendable) {
            return resolveLnurlInvoice(
                session = session,
                amountMsats = params.minSendable,
                isManualEntry = false,
                paymentQuote = null
            )
        }

        preparation.manualEntryContext = ManualEntryContext.Lnurl(session, inputInfo)
        val config =
            ManualAmountConfig(
                info = manualCurrencyState.info,
                exchangeRate = manualCurrencyState.exchangeRate,
                minMsats = params.minSendable,
                maxMsats = params.maxSendable
            )
        val entry = manualAmount.reset(config, clearInput = true)
        presentation.showManualAmount(entry, session.display)
        return AdmissionResult.Presented
    }

    private suspend fun resolveLnurlInvoice(
        session: LnurlSession,
        amountMsats: Long,
        isManualEntry: Boolean,
        paymentQuote: PaymentAmountQuote?
    ): AdmissionResult {
        presentation.showLoading()
        val result = preparation.resolveLnurlInvoice(session, amountMsats)
        currentCoroutineContext().ensureActive()
        return when (result) {
            is LnurlInvoiceResolution.Success ->
                handleLnurlInvoice(
                    session = session,
                    invoice = result.invoice,
                    isManualEntry = isManualEntry,
                    paymentQuote = paymentQuote
                )

            is LnurlInvoiceResolution.Failure -> {
                preparation.manualEntryContext = null
                presentation.presentError(result.error.toPaymentUiError())
                AdmissionResult.Presented
            }
        }
    }

    private fun handleLnurlInvoice(
        session: LnurlSession,
        invoice: Bolt11Invoice,
        isManualEntry: Boolean,
        paymentQuote: PaymentAmountQuote?
    ): AdmissionResult {
        history.findPaidByPaymentHash(invoice.paymentHash.toHex())?.let { existing ->
            preparation.manualEntryContext = null
            presentation.showActive()
            requestTransactionDetailNavigation(existing.id)
            return AdmissionResult.Presented
        }

        return AdmissionResult.Payment(
            PreparedPayment(
                invoice = invoice,
                amountOverrideMsats = null,
                isManualEntry = isManualEntry,
                targetContext = session.targetContext,
                lnurlPayDisplay = session.display,
                paymentQuote = paymentQuote
            )
        )
    }

    private fun requestTransactionDetailNavigation(id: String) {
        presentation.requestTransactionDetailNavigation(id)
    }

    private fun rejectExpiredInvoice(invoice: Bolt11Invoice): Boolean {
        if (!invoice.isExpired(nowSeconds())) return false
        preparation.manualEntryContext = null
        presentation.presentError(PaymentUiError.InvalidInvoice("Invoice has expired"))
        return true
    }
}
