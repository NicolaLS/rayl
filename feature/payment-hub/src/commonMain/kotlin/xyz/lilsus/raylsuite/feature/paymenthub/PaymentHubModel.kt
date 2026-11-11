package xyz.lilsus.raylsuite.feature.paymenthub

import kotlin.jvm.JvmInline
import xyz.lilsus.raylsuite.core.model.StoredAmount

/** Identifies a payment action independently of the widgets that present it. */
@JvmInline
value class HubItemId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/** A saved contact payment action. Multiple widgets may present the same action. */
data class DirectPaymentTarget(
    val id: HubItemId,
    val title: String,
    val contactId: String,
    val amountRule: DirectTargetAmountRule,
    val comment: String? = null,
    val stats: HubItemStats = HubItemStats()
)

sealed interface DirectTargetAmountRule {
    data object AskEveryTime : DirectTargetAmountRule
    data class Preset(val amount: StoredAmount) : DirectTargetAmountRule
}

data class HubItemStats(
    val successfulPaymentCount: Long = 0,
    val lastSuccessfulPaymentAtMs: Long? = null
)

/** A placed instance, independent of its catalog definition and of payment action identity. */
data class HubWidget(
    val id: String,
    val definitionId: String,
    val kind: HubWidgetKind,
    val variant: HubWidgetVariant,
    val title: String? = null,
    val contactIds: List<String> = emptyList(),
    val targetId: HubItemId? = null,
    /** The fiat currency a Rate or Sats widget quotes bitcoin in. */
    val currencyCode: String? = null,
    /** The number a Claro widget tops up, as eight national digits; null until it is set up. */
    val phone: String? = null
)

/**
 * A Claro order the wallet was handed the invoice of. It is kept, and never shown, until the app
 * could report to the usage data whether it was paid and delivered.
 */
data class ClaroOrder(
    val transactionId: String,
    /** The checkout at Claro's payment provider. */
    val checkout: String,
    /** Eight national digits. */
    val phone: String,
    val topUp: Boolean,
    val orderedAtMs: Long,
    /** The payment was seen, and reported. */
    val paid: Boolean = false
)

/** Ordered widgets, the local contact book, and successful payment history share one atomic write. */
data class PaymentHub(
    val contacts: List<HubContact> = emptyList(),
    val targets: List<DirectPaymentTarget> = emptyList(),
    val widgets: List<HubWidget> = emptyList(),
    val claroOrders: List<ClaroOrder> = emptyList()
) {
    fun contact(id: String): HubContact? = contacts.firstOrNull { it.id == id }
    fun target(id: HubItemId): DirectPaymentTarget? = targets.firstOrNull { it.id == id }
    fun widget(id: String): HubWidget? = widgets.firstOrNull { it.id == id }
    fun contactTarget(id: String): DirectPaymentTarget? = targets.firstOrNull {
        it.contactId == id && it.amountRule == DirectTargetAmountRule.AskEveryTime
    }

    fun normalized(): PaymentHub {
        val uniqueContacts = contacts.distinctBy { it.id }
        val contactIds = uniqueContacts.mapTo(mutableSetOf()) { it.id }
        val uniqueTargets = targets.filter { it.contactId in contactIds }.distinctBy { it.id }
        val targetIds = uniqueTargets.mapTo(mutableSetOf()) { it.id }
        val normalizedWidgets = widgets.distinctBy { it.id }.mapNotNull { widget ->
            when (widget.kind) {
                // A widget that was added but not set up yet has nobody; it stays and asks to be
                // set up. One whose people are all gone leaves with them.
                HubWidgetKind.Contacts -> widget.copy(
                    contactIds = widget.contactIds.filter { it in contactIds }.distinct()
                ).takeIf { it.contactIds.isNotEmpty() || widget.contactIds.isEmpty() }

                HubWidgetKind.Shortcut -> widget.takeIf {
                    it.targetId == null || it.targetId in targetIds
                }

                HubWidgetKind.Rate, HubWidgetKind.Sats ->
                    widget.takeIf { it.currencyCode in LocalHubWidgets.fiatCodes }

                else -> widget
            }
        }
        return PaymentHub(
            uniqueContacts,
            uniqueTargets,
            normalizedWidgets,
            // Only the newest are followed; an order nobody could ask about is no loss.
            claroOrders.distinctBy { it.transactionId }.takeLast(MAX_CLARO_ORDERS)
        )
    }
}

private const val MAX_CLARO_ORDERS = 20
