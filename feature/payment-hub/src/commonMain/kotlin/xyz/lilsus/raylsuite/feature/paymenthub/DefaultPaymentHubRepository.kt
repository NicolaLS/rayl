package xyz.lilsus.raylsuite.feature.paymenthub

import com.russhwolf.settings.Settings
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.feature.paymenthub.claro.claroPhone

/** One app-scoped document keeps contacts, widget instances and action history consistent. */
class DefaultPaymentHubRepository(
    private val settings: Settings,
    private val idGenerator: () -> String = { Random.nextLong().toULong().toString(16) }
) : PaymentHubRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val mutableHub = MutableStateFlow(load())
    override val hub = mutableHub.asStateFlow()

    override suspend fun saveContact(address: LightningAddress, title: String?): HubContact {
        lateinit var result: HubContact
        mutate { current ->
            val existing = current.contacts.firstOrNull { it.address.isSameAddressAs(address) }
            result = HubContact(
                existing?.id ?: "contact:${idGenerator()}",
                title?.trim()?.takeIf(String::isNotEmpty) ?: existing?.title ?: address.username,
                address
            )
            current.withContact(result)
        }
        return result
    }

    override suspend fun deleteContact(id: String) {
        mutate { it.copy(contacts = it.contacts.filterNot { contact -> contact.id == id }) }
    }

    override suspend fun saveWidget(draft: HubWidgetDraft, id: String?): HubWidget? {
        var result: HubWidget? = null
        mutate { current ->
            if (id != null && current.widget(id) == null) return@mutate current
            val contacts = draft.contactIds.distinct()
            val local = LocalHubWidgets.definitions.firstOrNull { it.id == draft.definitionId }
            if (local == null || local.kind != draft.kind || draft.variant !in local.variants) {
                return@mutate current
            }
            if (draft.kind == HubWidgetKind.Contacts || draft.kind == HubWidgetKind.Shortcut) {
                // No contacts at all is a widget that is added now and set up later.
                if (contacts.size > draft.variant.capacity ||
                    contacts.any { current.contact(it) == null }
                ) {
                    return@mutate current
                }
            }
            val quotesBitcoin = draft.kind == HubWidgetKind.Rate || draft.kind == HubWidgetKind.Sats
            val currencyCode = draft.currencyCode?.trim()?.uppercase()
            if (quotesBitcoin && currencyCode !in LocalHubWidgets.fiatCodes) return@mutate current
            if (draft.phone != null && claroPhone(draft.phone) != draft.phone) return@mutate current
            var updated = current
            var targetId: HubItemId? = null
            when (draft.kind) {
                HubWidgetKind.Contacts -> contacts.forEach {
                    updated = updated.withContactAction(it)
                }

                HubWidgetKind.Shortcut -> if (contacts.isNotEmpty() || draft.amount != null) {
                    val amount = draft.amount ?: return@mutate current
                    if (amount.minor <= 0 ||
                        amount.normalizedCurrencyCode !in CurrencyCatalog.supportedCodes ||
                        contacts.size != 1 || draft.variant != LocalHubWidgets.Single
                    ) {
                        return@mutate current
                    }
                    val normalizedAmount = StoredAmount(amount.minor, amount.normalizedCurrencyCode)
                    val comment = draft.comment?.trim()?.takeIf(String::isNotEmpty)
                    val existing = current.targets.firstOrNull {
                        it.contactId == contacts.single() &&
                            it.amountRule == DirectTargetAmountRule.Preset(normalizedAmount) &&
                            it.comment == comment
                    }
                    val target = existing ?: DirectPaymentTarget(
                        id = HubItemId("preset:${idGenerator()}"),
                        title = draft.title?.trim()?.takeIf(String::isNotEmpty)
                            ?: current.contact(contacts.single())!!.title,
                        contactId = contacts.single(),
                        amountRule = DirectTargetAmountRule.Preset(normalizedAmount),
                        comment = comment
                    )
                    targetId = target.id
                    if (existing == null) updated = updated.copy(targets = updated.targets + target)
                }

                else -> Unit
            }
            val saved = HubWidget(
                id = id ?: "widget:${idGenerator()}",
                definitionId = draft.definitionId,
                kind = draft.kind,
                variant = draft.variant,
                title = draft.title?.trim()?.takeIf(String::isNotEmpty),
                contactIds = if (draft.kind == HubWidgetKind.Contacts) contacts else emptyList(),
                targetId = targetId,
                currencyCode = currencyCode.takeIf { quotesBitcoin },
                phone = draft.phone?.takeIf { draft.kind == HubWidgetKind.Claro }
            )
            result = saved
            updated.copy(
                widgets = if (id == null) {
                    updated.widgets + saved
                } else {
                    updated.widgets.map { if (it.id == id) saved else it }
                }
            )
        }
        return result
    }

    override suspend fun deleteWidget(id: String) {
        // Removing presentation never removes a contact or its successful payment history.
        mutate { it.copy(widgets = it.widgets.filterNot { widget -> widget.id == id }) }
    }

    override suspend fun moveWidget(id: String, index: Int) {
        mutate { current ->
            val from = current.widgets.indexOfFirst { it.id == id }
            if (from < 0) return@mutate current
            val ordered = current.widgets.toMutableList()
            val item = ordered.removeAt(from)
            ordered.add(index.coerceIn(0, ordered.size), item)
            current.copy(widgets = ordered)
        }
    }

    override suspend fun saveContactAndWidget(
        address: LightningAddress,
        title: String
    ): HubContact {
        lateinit var saved: HubContact
        mutate { current ->
            saved = current.contacts.firstOrNull { it.address.isSameAddressAs(address) }
                ?: HubContact(
                    "contact:${idGenerator()}",
                    title.trim().ifEmpty {
                        address.username
                    },
                    address
                )
            val updated = current.withContact(saved).withContactAction(saved.id)
            if (updated.widgets.any { saved.id in it.contactIds }) return@mutate updated
            updated.copy(
                widgets = updated.widgets + HubWidget(
                    id = "widget:${idGenerator()}",
                    definitionId = "local.contacts",
                    kind = HubWidgetKind.Contacts,
                    variant = LocalHubWidgets.Single,
                    contactIds = listOf(saved.id)
                )
            )
        }
        return saved
    }

    override suspend fun recordSuccessfulPayment(id: HubItemId, paidAtMs: Long) {
        mutate { current ->
            current.copy(
                targets = current.targets.map { target ->
                    if (target.id != id) {
                        target
                    } else {
                        target.copy(
                            stats = HubItemStats(
                                successfulPaymentCount =
                                    (target.stats.successfulPaymentCount + 1)
                                        .coerceAtLeast(target.stats.successfulPaymentCount),
                                lastSuccessfulPaymentAtMs = paidAtMs
                            )
                        )
                    }
                }
            )
        }
    }

    override suspend fun saveClaroOrder(order: ClaroOrder) {
        mutate { current ->
            val others = current.claroOrders.filterNot { it.transactionId == order.transactionId }
            // A replaced order keeps its place, so the oldest is still the first to be dropped.
            val at = current.claroOrders.indexOfFirst { it.transactionId == order.transactionId }
            current.copy(
                claroOrders = if (at < 0) {
                    others + order
                } else {
                    others.take(at) + order + others.drop(at)
                }
            )
        }
    }

    override suspend fun removeClaroOrder(transactionId: String) {
        mutate { current ->
            current.copy(
                claroOrders = current.claroOrders.filterNot { it.transactionId == transactionId }
            )
        }
    }

    private fun PaymentHub.withContact(contact: HubContact): PaymentHub = copy(
        contacts = if (contacts.any { it.id == contact.id }) {
            contacts.map { if (it.id == contact.id) contact else it }
        } else {
            contacts + contact
        }
    )

    private fun PaymentHub.withContactAction(id: String): PaymentHub {
        if (contactTarget(id) != null) return this
        val contact = contact(id) ?: return this
        return copy(
            targets = targets + DirectPaymentTarget(
                id = HubItemId("contact-payment:$id"),
                title = contact.title,
                contactId = id,
                amountRule = DirectTargetAmountRule.AskEveryTime
            )
        )
    }

    private suspend fun mutate(transform: (PaymentHub) -> PaymentHub) {
        mutex.withLock {
            val current = mutableHub.value
            val next = transform(current).normalized()
            if (next == current) return
            settings.putString(DOCUMENT_KEY, json.encodeToString(next.toRecord()))
            mutableHub.value = next
        }
    }

    private fun load(): PaymentHub = settings.getStringOrNull(DOCUMENT_KEY)?.let { encoded ->
        runCatching {
            val record = json.decodeFromString<HubRecord>(encoded)
            require(record.schemaVersion == SCHEMA_VERSION)
            record.toHub().normalized()
        }.getOrNull()
    } ?: PaymentHub()

    private companion object {
        const val DOCUMENT_KEY = "paymentHub.widgets.document"
        const val SCHEMA_VERSION = 1
    }
}

internal expect fun platformCurrentTimeMillis(): Long

@Serializable
private data class HubRecord(
    val schemaVersion: Int = 1,
    val contacts: List<ContactRecord>,
    val actions: List<ActionRecord>,
    val widgets: List<WidgetRecord>,
    val claroOrders: List<ClaroOrderRecord> = emptyList()
)

@Serializable
private data class ClaroOrderRecord(
    val transactionId: String,
    val checkout: String,
    val phone: String,
    val topUp: Boolean,
    val orderedAt: Long,
    val paid: Boolean = false
)

@Serializable
private data class ContactRecord(val id: String, val title: String, val address: String)

@Serializable
private data class ActionRecord(
    val id: String,
    val title: String,
    val contactId: String,
    val minor: Long? = null,
    val currency: String? = null,
    val comment: String? = null,
    val count: Long = 0,
    val lastPaid: Long? = null
)

@Serializable
private data class WidgetRecord(
    val id: String,
    val definitionId: String,
    val kind: String,
    val variantId: String,
    val columns: Int,
    val rows: Int,
    val capacity: Int,
    val title: String? = null,
    val contactIds: List<String> = emptyList(),
    val actionId: String? = null,
    val currency: String? = null,
    val phone: String? = null
)

private fun PaymentHub.toRecord() = HubRecord(
    contacts = contacts.map { ContactRecord(it.id, it.title, it.address.full) },
    actions = targets.map { target ->
        val amount = (target.amountRule as? DirectTargetAmountRule.Preset)?.amount
        ActionRecord(
            target.id.value,
            target.title,
            target.contactId,
            amount?.minor,
            amount?.normalizedCurrencyCode,
            target.comment,
            target.stats.successfulPaymentCount,
            target.stats.lastSuccessfulPaymentAtMs
        )
    },
    widgets = widgets.map { widget ->
        WidgetRecord(
            widget.id,
            widget.definitionId,
            widget.kind.name,
            widget.variant.id,
            widget.variant.columns,
            widget.variant.rows,
            widget.variant.capacity,
            widget.title,
            widget.contactIds,
            widget.targetId?.value,
            widget.currencyCode,
            widget.phone
        )
    },
    claroOrders = claroOrders.map {
        ClaroOrderRecord(it.transactionId, it.checkout, it.phone, it.topUp, it.orderedAtMs, it.paid)
    }
)

private fun HubRecord.toHub() = PaymentHub(
    contacts = contacts.mapNotNull { record ->
        LightningAddress.parse(record.address)?.let { HubContact(record.id, record.title, it) }
    },
    targets = actions.mapNotNull { record ->
        val amount = if (record.minor != null && record.currency != null) {
            if (record.minor <= 0 ||
                record.currency !in CurrencyCatalog.supportedCodes
            ) {
                return@mapNotNull null
            }
            DirectTargetAmountRule.Preset(StoredAmount(record.minor, record.currency))
        } else if (record.minor == null && record.currency == null) {
            DirectTargetAmountRule.AskEveryTime
        } else {
            return@mapNotNull null
        }
        DirectPaymentTarget(
            HubItemId(record.id),
            record.title,
            record.contactId,
            amount,
            record.comment,
            HubItemStats(record.count.coerceAtLeast(0), record.lastPaid)
        )
    },
    widgets = widgets.mapNotNull { record ->
        val kind =
            HubWidgetKind.entries.firstOrNull { it.name == record.kind } ?: return@mapNotNull null
        if (record.columns !in 1..2 || record.rows !in 1..2) return@mapNotNull null
        HubWidget(
            record.id,
            record.definitionId,
            kind,
            HubWidgetVariant(record.variantId, record.columns, record.rows, record.capacity),
            record.title,
            record.contactIds,
            record.actionId?.let(::HubItemId),
            record.currency,
            record.phone?.let(::claroPhone)
        )
    },
    claroOrders = claroOrders.map {
        ClaroOrder(it.transactionId, it.checkout, it.phone, it.topUp, it.orderedAt, it.paid)
    }
)
