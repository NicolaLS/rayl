package xyz.lilsus.raylsuite.feature.paymenthub.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceHistoryProvider
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.feature.paymenthub.DirectTargetAmountRule
import xyz.lilsus.raylsuite.feature.paymenthub.HubItemId
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.platformCurrentTimeMillis

/**
 * Shared hub host logic: owns the post-payment save prompt and emits [DirectTargetPaymentIntent]
 * when a target is chosen. Each app maps that intent into its own provider-native payment flow.
 * Widget placement belongs to the Hub presentation, not here.
 *
 * It is also how widgets reach what the wallet experience already owns: its bitcoin [prices] and
 * [priceHistory], and its payment flow for an invoice a widget obtained ([payInvoice]).
 */
class PaymentHubController(
    private val repository: PaymentHubRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::platformCurrentTimeMillis,
    val prices: BitcoinPriceProvider? = null,
    val priceHistory: BitcoinPriceHistoryProvider? = null
) {
    private val mutableState = MutableStateFlow(PaymentHubHostState())
    val state: StateFlow<PaymentHubHostState> = mutableState.asStateFlow()

    private val mutablePaymentRequests =
        MutableSharedFlow<DirectTargetPaymentIntent>(extraBufferCapacity = 4)
    val paymentRequests: SharedFlow<DirectTargetPaymentIntent> =
        mutablePaymentRequests.asSharedFlow()

    private val mutableInvoiceRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** BOLT11 invoices to pay like any scanned invoice, with the wallet's usual confirmation. */
    val invoiceRequests: SharedFlow<String> = mutableInvoiceRequests.asSharedFlow()

    private val hub get() = repository.hub.value
    private var saving = false

    fun dispatch(intent: PaymentHubIntent) {
        when (intent) {
            is PaymentHubIntent.SelectItem -> selectItem(intent.id)

            PaymentHubIntent.OpenScanner -> mutableState.update { it.copy(scannerRequested = true) }

            PaymentHubIntent.DismissScanner ->
                mutableState.update { it.copy(scannerRequested = false) }

            is PaymentHubIntent.SavePromptTitleChanged ->
                if (!saving) {
                    mutableState.update { current ->
                        current.copy(savePrompt = current.savePrompt?.copy(title = intent.title))
                    }
                }

            PaymentHubIntent.SavePromptSave -> savePrompt()

            PaymentHubIntent.SavePromptDismiss -> mutableState.update { it.copy(savePrompt = null) }
        }
    }

    /** Hands an invoice a widget obtained from its service to the wallet's payment flow. */
    fun payInvoice(invoice: String) {
        mutableState.update { it.copy(scannerRequested = false) }
        mutableInvoiceRequests.tryEmit(invoice)
    }

    /** Reports a terminal successful wallet payment that was started from [targetId]. */
    fun recordSuccessfulPayment(targetId: HubItemId) {
        val paidAtMs = clock()
        scope.launch { repository.recordSuccessfulPayment(targetId, paidAtMs) }
    }

    /** Offers to save a new contact together with a single Contacts widget. */
    fun offerSave(address: LightningAddress) {
        if (hub.contacts.any { it.address.isSameAddressAs(address) }) return
        mutableState.update { current ->
            current.copy(
                savePrompt = HubSavePrompt(address = address, title = address.username)
            )
        }
    }

    fun resetSession() {
        mutableState.update { it.copy(savePrompt = null, scannerRequested = false) }
    }

    private fun selectItem(id: HubItemId) {
        val snapshot = hub
        val target = snapshot.target(id) ?: return
        val contact = snapshot.contact(target.contactId) ?: return
        mutableState.update { it.copy(scannerRequested = false) }
        mutablePaymentRequests.tryEmit(
            DirectTargetPaymentIntent(
                targetId = target.id,
                address = contact.address,
                amountRule = target.amountRule,
                comment = target.comment
            )
        )
    }

    private fun savePrompt() {
        if (saving) return
        val prompt = mutableState.value.savePrompt ?: return
        saving = true
        scope.launch {
            try {
                repository.saveContactAndWidget(
                    prompt.address,
                    prompt.title.trim().ifEmpty { prompt.address.username }
                )
                mutableState.update {
                    if (it.savePrompt ==
                        prompt
                    ) {
                        it.copy(savePrompt = null)
                    } else {
                        it
                    }
                }
            } finally {
                saving = false
            }
        }
    }
}

data class PaymentHubHostState(
    val savePrompt: HubSavePrompt? = null,
    /** A lens asked for the host scanner surface to be shown prominently. */
    val scannerRequested: Boolean = false
) {
    val hasModalContent: Boolean
        get() = savePrompt != null
}

data class HubSavePrompt(val address: LightningAddress, val title: String)

sealed interface PaymentHubIntent {
    data class SelectItem(val id: HubItemId) : PaymentHubIntent

    data object OpenScanner : PaymentHubIntent

    data object DismissScanner : PaymentHubIntent

    data class SavePromptTitleChanged(val title: String) : PaymentHubIntent

    data object SavePromptSave : PaymentHubIntent

    data object SavePromptDismiss : PaymentHubIntent
}

/** What the hub hands to an app when a direct target is chosen. */
data class DirectTargetPaymentIntent(
    val targetId: HubItemId,
    val address: LightningAddress,
    val amountRule: DirectTargetAmountRule,
    val comment: String?
)
