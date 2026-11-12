package xyz.lilsus.raylsuite.feature.paymenthub

import kotlinx.coroutines.flow.StateFlow
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.model.StoredAmount

interface PaymentHubRepository {
    val hub: StateFlow<PaymentHub>
    suspend fun saveContact(address: LightningAddress, title: String?): HubContact
    suspend fun deleteContact(id: String)
    suspend fun saveWidget(draft: HubWidgetDraft, id: String? = null): HubWidget?
    suspend fun deleteWidget(id: String)

    /**
     * Gives widget [id] another of its layouts and keeps everything else, including people beyond
     * what the new layout shows. Returns null when the widget or the layout isn't there.
     */
    suspend fun resizeWidget(id: String, variant: HubWidgetVariant): HubWidget?
    suspend fun moveWidget(id: String, index: Int)

    /** Puts the widgets in the order of [ids]; any it doesn't name follow in their own order. */
    suspend fun arrangeWidgets(ids: List<String>)
    suspend fun saveContactAndWidget(address: LightningAddress, title: String): HubContact
    suspend fun recordSuccessfulPayment(id: HubItemId, paidAtMs: Long)

    /** Keeps [order], replacing what was kept under its transaction. */
    suspend fun saveClaroOrder(order: ClaroOrder)
    suspend fun removeClaroOrder(transactionId: String)
}

data class HubWidgetDraft(
    val definitionId: String,
    val kind: HubWidgetKind,
    val variant: HubWidgetVariant,
    val title: String? = null,
    val contactIds: List<String> = emptyList(),
    val amount: StoredAmount? = null,
    val comment: String? = null,
    val currencyCode: String? = null,
    val phone: String? = null
)
