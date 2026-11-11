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
    suspend fun moveWidget(id: String, index: Int)
    suspend fun saveContactAndWidget(address: LightningAddress, title: String): HubContact
    suspend fun recordSuccessfulPayment(id: HubItemId, paidAtMs: Long)
}

data class HubWidgetDraft(
    val definitionId: String,
    val kind: HubWidgetKind,
    val variant: HubWidgetVariant,
    val title: String? = null,
    val contactIds: List<String> = emptyList(),
    val amount: StoredAmount? = null,
    val comment: String? = null,
    val currencyCode: String? = null
)
