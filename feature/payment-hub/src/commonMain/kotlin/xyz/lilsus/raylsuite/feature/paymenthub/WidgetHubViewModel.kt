package xyz.lilsus.raylsuite.feature.paymenthub

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.feature.paymenthub.create.cleanAmountInput
import xyz.lilsus.raylsuite.feature.paymenthub.create.formatMinorAmount
import xyz.lilsus.raylsuite.feature.paymenthub.create.hasFractionForWholeCurrency
import xyz.lilsus.raylsuite.feature.paymenthub.create.parseMinorAmount
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubIntent
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqAccount
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqProblem
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqTopUpError
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubRate
import xyz.lilsus.raylsuite.feature.paymenthub.ppq.HubPpqService
import xyz.lilsus.raylsuite.feature.paymenthub.ppq.PpqResult

/** Shared widget presentation state. Native platforms own sheets, controls, gestures and copy. */
class WidgetHubViewModel(
    private val repository: PaymentHubRepository,
    private val host: PaymentHubController,
    private val defaultCurrencyCode: () -> String,
    /** Null where no ppq.ai account can be kept; its widget then stays unconnected. */
    private val ppq: HubPpqService? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    /** Where the credential store is read and written. */
    private val background: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState =
        MutableStateFlow(WidgetHubState(gallery = LocalHubWidgets.definitions))
    val state = mutableState.asStateFlow()
    private var ratesJob: Job? = null
    private var ppqJob: Job? = null

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
                // A widget added for another currency needs its price now, not in a minute.
                refreshRates()
            }
        }
    }

    /**
     * Fetches what the information widgets show. The platform calls this when the Hub appears
     * and about once a minute while it stays on screen.
     */
    fun refreshLive() {
        refreshRates()
        refreshPpq()
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
                    currencyCode = if (definition.kind.quotesBitcoin) {
                        defaultFiatCode()
                    } else {
                        defaultCurrencyCode()
                    }
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

    /** Leaves the layout choice: adds a new widget as it is, and goes on editing a placed one. */
    fun continueFromVariants() {
        if (mutableState.value.addsWithoutSetup) saveWidget(setUp = false) else configureSelected()
    }

    fun updateApiKey(value: String) = edit { it.copy(apiKeyInput = value.trim().take(200)) }

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
        if (mutableState.value.editor?.kind?.quotesBitcoin == true) {
            if (code !in LocalHubWidgets.fiatCodes) return
            edit { it.copy(currencyCode = code) }
            // The preview on the configuration screen shows this currency right away.
            refreshRates()
        } else {
            edit { it.copy(currencyCode = code, amountInput = "") }
        }
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

    fun saveWidget() = saveWidget(setUp = true)

    /** A widget that is not [setUp] yet is saved without the choices its settings ask for. */
    private fun saveWidget(setUp: Boolean) {
        val snapshot = mutableState.value
        val editor = snapshot.editor ?: return
        val variant = snapshot.selectedVariant ?: return fail(HubWidgetError.Unavailable)
        if (setUp) snapshot.editorValidationError()?.let { return fail(it) }
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
            editor.comment,
            editor.currencyCode.takeIf { editor.kind.quotesBitcoin }
        )
        mutate {
            if (editor.kind == HubWidgetKind.Ppq && editor.apiKeyInput.isNotBlank() &&
                !connectPpq(editor.apiKeyInput)
            ) {
                return@mutate
            }
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
                    currencyCode = amount?.normalizedCurrencyCode
                        ?: widget.currencyCode
                        ?: defaultCurrencyCode(),
                    comment = target?.comment.orEmpty()
                )
            )
        }
    }

    fun removeWidget(id: String) = mutate {
        repository.deleteWidget(id)
        if (mutableState.value.editor?.existingWidgetId == id) close()
        // The key is only there for the widgets; a Hub without one keeps no credential.
        if (ppq != null && repository.hub.value.widgets.none { it.kind == HubWidgetKind.Ppq }) {
            ppqJob?.cancel()
            withContext(background) { ppq.forgetApiKey() }
            mutableState.update { it.copy(ppq = HubPpqAccount()) }
        }
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

    /**
     * Asks ppq.ai for a Lightning invoice over [amountInput] US dollars and hands it to the
     * wallet, which presents and confirms it like any other payment.
     */
    fun topUpPpq(amountInput: String) {
        val service = ppq ?: return
        if (mutableState.value.ppq.toppingUp || mutableState.value.arranging) return
        val cents = amountInput.parseMinorAmount(2)
        if (cents == null || cents !in HubPpqAccount.TOP_UP_CENTS) {
            return updatePpq { it.copy(topUpError = HubPpqTopUpError.Amount) }
        }
        updatePpq { it.copy(toppingUp = true, topUpError = null) }
        scope.launch {
            var result: PpqResult<String> = PpqResult.Unreachable
            try {
                val key = withContext(background) { service.apiKey() }
                result = if (key == null) {
                    PpqResult.InvalidKey
                } else {
                    service.api.createLightningTopUp(key, cents / 100.0)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // An unreadable credential store is reported like an unreachable service.
            } finally {
                val outcome = result
                updatePpq { account ->
                    when (outcome) {
                        is PpqResult.Ok -> account.copy(
                            toppingUp = false,
                            topUpSerial = account.topUpSerial + 1
                        )

                        PpqResult.InvalidKey -> account.copy(
                            toppingUp = false,
                            problem = HubPpqProblem.InvalidKey,
                            topUpError = HubPpqTopUpError.InvalidKey
                        )

                        PpqResult.Unreachable -> account.copy(
                            toppingUp = false,
                            topUpError = HubPpqTopUpError.Unreachable
                        )
                    }
                }
            }
            (result as? PpqResult.Ok)?.let { host.payInvoice(it.value) }
        }
    }

    fun clearPpqTopUpError() = updatePpq { it.copy(topUpError = null) }

    fun clear() {
        scope.cancel()
        ppq?.close()
    }

    private fun updatePpq(transform: (HubPpqAccount) -> HubPpqAccount) {
        mutableState.update { it.copy(ppq = transform(it.ppq)) }
    }

    private fun defaultFiatCode(): String =
        defaultCurrencyCode().uppercase().takeIf { it in LocalHubWidgets.fiatCodes } ?: "USD"

    /** Prices first, so a figure shows before its chart arrives. */
    private fun refreshRates() {
        val prices = host.prices ?: return
        val default = defaultFiatCode()
        val editing = mutableState.value.editor?.takeIf { it.kind.quotesBitcoin }?.currencyCode
        val codes = (
            repository.hub.value.widgets.mapNotNull { it.currencyCode } + listOfNotNull(editing) +
                default
            ).filter { it in LocalHubWidgets.fiatCodes }.distinct()
        mutableState.update { it.copy(defaultFiatCode = default) }
        ratesJob?.cancel()
        ratesJob = scope.launch {
            var missing = false
            codes.forEach { code ->
                val price = prices.pricePerBitcoin(code)
                if (price == null) {
                    missing = true
                } else {
                    publishRate(code) { HubRate(code, price, it?.history.orEmpty()) }
                }
            }
            mutableState.update { it.copy(ratesUnavailable = missing) }
            val charts = host.priceHistory ?: return@launch
            codes.forEach { code ->
                val history = charts.priceHistory(code) ?: return@forEach
                publishRate(code) { it?.copy(history = history) }
            }
        }
    }

    private fun publishRate(code: String, transform: (HubRate?) -> HubRate?) {
        mutableState.update { current ->
            val next = transform(current.rates[code]) ?: return@update current
            current.copy(rates = current.rates + (code to next))
        }
    }

    private fun refreshPpq() {
        val service = ppq ?: return
        ppqJob?.cancel()
        ppqJob = scope.launch {
            val key = try {
                withContext(background) { service.apiKey() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The credential store is closed, as the iOS Keychain is while the device is
                // locked. What is on screen stays, and the next refresh reads it again.
                return@launch
            }
            if (key == null) {
                updatePpq { HubPpqAccount(topUpSerial = it.topUpSerial) }
                return@launch
            }
            updatePpq { it.copy(connected = true) }
            when (val balance = service.api.balanceUsd(key)) {
                is PpqResult.Ok ->
                    updatePpq { it.copy(balanceUsd = balance.value, problem = null) }

                PpqResult.InvalidKey ->
                    updatePpq { it.copy(problem = HubPpqProblem.InvalidKey) }

                PpqResult.Unreachable ->
                    updatePpq { it.copy(problem = HubPpqProblem.Unreachable) }
            }
        }
    }

    /** Stores [apiKey] once ppq.ai accepted it. Reports the reason and returns false otherwise. */
    private suspend fun connectPpq(apiKey: String): Boolean {
        val service = ppq
        if (service == null) {
            fail(HubWidgetError.Unavailable)
            return false
        }
        return when (val balance = service.api.balanceUsd(apiKey)) {
            is PpqResult.Ok -> {
                ppqJob?.cancel()
                withContext(background) { service.saveApiKey(apiKey) }
                updatePpq {
                    it.copy(connected = true, balanceUsd = balance.value, problem = null)
                }
                true
            }

            PpqResult.InvalidKey -> {
                fail(HubWidgetError.InvalidApiKey)
                false
            }

            PpqResult.Unreachable -> {
                fail(HubWidgetError.ServiceUnreachable)
                false
            }
        }
    }

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
                HubWidgetKind.Rate, HubWidgetKind.Sats, HubWidgetKind.Ppq -> emptyList()
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
                currencyCode = widget.currencyCode,
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
