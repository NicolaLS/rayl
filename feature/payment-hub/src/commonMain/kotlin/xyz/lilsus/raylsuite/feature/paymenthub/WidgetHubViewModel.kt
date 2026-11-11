package xyz.lilsus.raylsuite.feature.paymenthub

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.feature.paymenthub.create.cleanAmountInput
import xyz.lilsus.raylsuite.feature.paymenthub.create.formatMinorAmount
import xyz.lilsus.raylsuite.feature.paymenthub.create.hasFractionForWholeCurrency
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubIntent

/** Shared widget presentation state. Native platforms own sheets, controls, gestures and copy. */
class WidgetHubViewModel(
    private val repository: PaymentHubRepository,
    private val host: PaymentHubController,
    private val defaultCurrencyCode: () -> String,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState =
        MutableStateFlow(WidgetHubState(gallery = LocalHubWidgets.definitions))
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            repository.hub.collect { hub ->
                val editor = mutableState.value.editor
                val editedWidgetRemoved = editor?.existingWidgetId?.let { hub.widget(it) == null }
                    ?: false
                mutableState.update { current ->
                    current.copy(
                        contacts = hub.contacts,
                        screen = if (editedWidgetRemoved) HubWidgetScreen.Hub else current.screen,
                        editor = if (editedWidgetRemoved) {
                            null
                        } else {
                            editor?.copy(
                                contactIds = editor.contactIds.filter {
                                    hub.contact(it) !=
                                        null
                                }
                            )
                        }
                    )
                }
                rebuildTiles()
            }
        }
    }

    fun openGallery() {
        mutableState.update {
            it.copy(
                screen = HubWidgetScreen.Gallery,
                editor = null,
                query = "",
                error = null,
                arranging = false
            )
        }
    }

    fun close() {
        mutableState.update {
            it.copy(screen = HubWidgetScreen.Hub, editor = null, query = "", error = null)
        }
    }

    fun back(): Boolean {
        when (mutableState.value.screen) {
            HubWidgetScreen.Hub -> {
                if (!mutableState.value.arranging) return false
                setArranging(false)
            }

            HubWidgetScreen.Gallery -> close()

            HubWidgetScreen.Variants -> {
                if (mutableState.value.editor?.existingWidgetId != null) close() else openGallery()
            }

            HubWidgetScreen.Configure -> mutableState.update {
                it.copy(screen = HubWidgetScreen.Variants, error = null)
            }
        }
        return true
    }

    fun selectDefinition(id: String) {
        val definition = mutableState.value.gallery.firstOrNull { it.id == id } ?: return
        mutableState.update {
            it.copy(
                screen = HubWidgetScreen.Variants,
                query = "",
                error = null,
                editor = HubWidgetEditor(
                    id,
                    definition.kind,
                    definition.variants.first().id,
                    currencyCode = defaultCurrencyCode()
                )
            )
        }
    }

    fun selectVariant(id: String) {
        val definition = mutableState.value.selectedDefinition ?: return
        val variant = definition.variants.firstOrNull { it.id == id } ?: return
        edit { it.copy(variantId = id, contactIds = it.contactIds.take(variant.capacity)) }
    }

    fun configureSelected() {
        if (mutableState.value.editor == null) return
        mutableState.update {
            it.copy(screen = HubWidgetScreen.Configure, query = "", error = null)
        }
    }

    fun updateQuery(value: String) {
        mutableState.update { it.copy(query = value) }
    }
    fun updateTitle(value: String) = edit { it.copy(title = value.take(120)) }
    fun updateComment(value: String) = edit { it.copy(comment = value.take(500)) }

    fun toggleContact(id: String) {
        if (repository.hub.value.contact(id) == null) return
        val capacity = mutableState.value.selectedVariant?.capacity ?: return
        val current = mutableState.value.editor ?: return
        if (capacity > 1 && id !in current.contactIds && current.contactIds.size >= capacity) {
            return fail(HubWidgetError.TooManyContacts)
        }
        edit { editor ->
            if (capacity == 1) {
                editor.copy(contactIds = listOf(id))
            } else {
                editor.copy(
                    contactIds = if (id in
                        editor.contactIds
                    ) {
                        editor.contactIds - id
                    } else {
                        editor.contactIds + id
                    }
                )
            }
        }
    }

    fun moveContact(id: String, delta: Int) {
        edit { editor ->
            val from = editor.contactIds.indexOf(id)
            if (from < 0) {
                editor
            } else {
                val reordered = editor.contactIds.toMutableList()
                reordered.removeAt(from)
                reordered.add((from + delta).coerceIn(0, reordered.size), id)
                editor.copy(contactIds = reordered)
            }
        }
    }

    fun updateAmount(value: String) = edit {
        val digits = CurrencyCatalog.infoFor(it.currencyCode).fractionDigits
        it.copy(
            amountInput = if (value.hasFractionForWholeCurrency(
                    digits
                )
            ) {
                value
            } else {
                value.cleanAmountInput(digits)
            }
        )
    }

    fun selectCurrency(code: String) {
        if (code !in CurrencyCatalog.supportedCodes) return
        edit { it.copy(currencyCode = code, amountInput = "") }
    }

    fun addContact(title: String, address: String) {
        if (mutableState.value.busy) return
        val name = title.trim()
        if (name.isEmpty()) return fail(HubWidgetError.ContactNameRequired)
        val parsed = LightningAddress.parse(address) ?: return fail(HubWidgetError.InvalidAddress)
        val existing = repository.hub.value.contacts.firstOrNull {
            it.address.isSameAddressAs(parsed)
        }
        val editor = mutableState.value.editor
        val capacity = mutableState.value.selectedVariant?.capacity ?: 1
        if (capacity > 1 && editor?.kind == HubWidgetKind.Contacts &&
            existing?.id !in editor.contactIds &&
            editor.contactIds.size >= capacity
        ) {
            return fail(HubWidgetError.TooManyContacts)
        }
        mutate {
            val contact = existing ?: repository.saveContact(parsed, name)
            mutableState.update { current ->
                val currentEditor = current.editor
                current.copy(
                    contactSavedSerial = current.contactSavedSerial + 1,
                    editor = currentEditor?.copy(
                        contactIds = if (capacity == 1) {
                            listOf(contact.id)
                        } else {
                            (currentEditor.contactIds + contact.id).distinct()
                        }
                    )
                )
            }
        }
    }

    fun deleteContact(id: String) = mutate { repository.deleteContact(id) }

    fun saveWidget() {
        val snapshot = mutableState.value
        val editor = snapshot.editor ?: return
        val variant = snapshot.selectedVariant ?: return fail(HubWidgetError.Unavailable)
        snapshot.editorValidationError()?.let { return fail(it) }
        val amount = if (editor.kind == HubWidgetKind.Shortcut) {
            editor.shortcutAmount()
        } else {
            null
        }
        val draft = HubWidgetDraft(
            editor.definitionId,
            editor.kind,
            variant,
            editor.title,
            editor.contactIds,
            amount,
            editor.comment
        )
        mutate {
            if (repository.saveWidget(draft, editor.existingWidgetId) == null) {
                fail(HubWidgetError.SaveFailed)
            } else {
                close()
            }
        }
    }

    fun editWidget(id: String) {
        val hub = repository.hub.value
        val widget = hub.widget(id) ?: return
        val definition = mutableState.value.gallery.firstOrNull { it.id == widget.definitionId }
        if (definition == null) {
            fail(HubWidgetError.Unavailable)
            return
        }
        val variant = definition.variants.firstOrNull { it.id == widget.variant.id }
            ?: definition.variants.first()
        val target = widget.targetId?.let(hub::target)
        val amount = (target?.amountRule as? DirectTargetAmountRule.Preset)?.amount
        mutableState.update {
            it.copy(
                screen = HubWidgetScreen.Configure,
                query = "",
                error = null,
                editor = HubWidgetEditor(
                    widget.definitionId,
                    widget.kind,
                    variant.id,
                    widget.id,
                    contactIds = target?.let { listOf(it.contactId) } ?: widget.contactIds,
                    title = widget.title.orEmpty(),
                    amountInput = amount?.minor?.formatMinorAmount(
                        CurrencyCatalog.infoFor(amount.normalizedCurrencyCode).fractionDigits
                    ).orEmpty(),
                    currencyCode = amount?.normalizedCurrencyCode ?: defaultCurrencyCode(),
                    comment = target?.comment.orEmpty()
                )
            )
        }
    }

    fun removeWidget(id: String) = mutate {
        repository.deleteWidget(id)
        if (mutableState.value.editor?.existingWidgetId == id) close()
    }
    fun moveWidget(id: String, targetId: String) {
        val index = repository.hub.value.widgets.indexOfFirst { it.id == targetId }
        if (index >= 0) mutate { repository.moveWidget(id, index) }
    }
    fun moveWidgetBy(id: String, delta: Int) {
        val index = repository.hub.value.widgets.indexOfFirst { it.id == id }
        if (index >= 0) mutate { repository.moveWidget(id, index + delta) }
    }
    fun setArranging(value: Boolean) {
        mutableState.update { it.copy(arranging = value, error = null) }
    }
    fun pay(actionId: String) {
        if (!mutableState.value.arranging && actionId.isNotBlank()) {
            host.dispatch(PaymentHubIntent.SelectItem(HubItemId(actionId)))
        }
    }

    fun clear() = scope.cancel()

    private fun edit(transform: (HubWidgetEditor) -> HubWidgetEditor) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(editor = it.editor?.let(transform), error = null) }
    }

    private fun fail(error: HubWidgetError) {
        mutableState.update { it.copy(error = error) }
    }

    private fun mutate(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                fail(HubWidgetError.SaveFailed)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun rebuildTiles() {
        val hub = repository.hub.value
        val paid = hub.targets.filter { it.stats.successfulPaymentCount > 0 }
        val favorites = paid.sortedWith(
            compareByDescending<DirectPaymentTarget> {
                it.stats.successfulPaymentCount
            }
                .thenByDescending { it.stats.lastSuccessfulPaymentAtMs ?: 0 }.thenBy { it.id.value }
        )
        val recents = paid.sortedWith(
            compareByDescending<DirectPaymentTarget> {
                it.stats.lastSuccessfulPaymentAtMs
                    ?: 0
            }
                .thenBy { it.id.value }
        )
        val tiles = hub.widgets.map { widget ->
            val targets = when (widget.kind) {
                HubWidgetKind.Contacts -> widget.contactIds.mapNotNull(hub::contactTarget)
                HubWidgetKind.Shortcut -> listOfNotNull(widget.targetId?.let(hub::target))
                HubWidgetKind.Favorites -> favorites.take(widget.variant.capacity)
                HubWidgetKind.Recents -> recents.take(widget.variant.capacity)
            }
            HubWidgetTile(
                widget.id,
                widget.definitionId,
                widget.kind,
                widget.variant,
                widget.title
                    ?: mutableState.value.gallery.firstOrNull {
                        it.id == widget.definitionId
                    }?.title,
                people = targets.mapNotNull { target ->
                    hub.contact(target.contactId)?.let { contact ->
                        HubWidgetPerson(
                            target.id.value,
                            contact.id,
                            if (target.amountRule is DirectTargetAmountRule.Preset) {
                                target.title
                            } else {
                                contact.title
                            },
                            contact.address.full,
                            (target.amountRule as? DirectTargetAmountRule.Preset)?.amount,
                            contact.title
                        )
                    }
                }
            )
        }
        // An empty canvas has no Done button, so removing the last widget ends arranging.
        mutableState.update {
            it.copy(widgets = tiles, arranging = it.arranging && tiles.isNotEmpty())
        }
    }
}
