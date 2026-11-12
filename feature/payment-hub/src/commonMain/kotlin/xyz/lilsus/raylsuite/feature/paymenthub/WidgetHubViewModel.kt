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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.raylapi.TelemetryCatalog
import xyz.lilsus.raylsuite.core.raylapi.TelemetryFakeDoor
import xyz.lilsus.raylsuite.core.raylapi.TelemetryFakeDoorAction
import xyz.lilsus.raylsuite.core.raylapi.TelemetryHubProduct
import xyz.lilsus.raylsuite.core.raylapi.TelemetryHubPurchaseStep
import xyz.lilsus.raylsuite.core.raylapi.TelemetryHubService
import xyz.lilsus.raylsuite.core.raylapi.telemetryEvent
import xyz.lilsus.raylsuite.core.telemetry.Telemetry
import xyz.lilsus.raylsuite.core.telemetry.TelemetryRecorder
import xyz.lilsus.raylsuite.feature.paymenthub.bills.HubBill
import xyz.lilsus.raylsuite.feature.paymenthub.canvas.HubGridSpan
import xyz.lilsus.raylsuite.feature.paymenthub.canvas.hubOrderFor
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroApi
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroDelivery
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroInvoice
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroLine
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroPayment
import xyz.lilsus.raylsuite.feature.paymenthub.claro.ClaroResult
import xyz.lilsus.raylsuite.feature.paymenthub.claro.claroPhone
import xyz.lilsus.raylsuite.feature.paymenthub.create.cleanAmountInput
import xyz.lilsus.raylsuite.feature.paymenthub.create.formatMinorAmount
import xyz.lilsus.raylsuite.feature.paymenthub.create.hasFractionForWholeCurrency
import xyz.lilsus.raylsuite.feature.paymenthub.create.parseMinorAmount
import xyz.lilsus.raylsuite.feature.paymenthub.feedback.HubFeedbackApi
import xyz.lilsus.raylsuite.feature.paymenthub.feedback.HubFeedbackKind
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubIntent
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubClaroProblem
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubClaroShop
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
    /** Null where Claro cannot be asked; its widget then opens nothing. */
    private val claro: ClaroApi? = null,
    /** Null where feedback cannot be sent; the form is then not offered. */
    private val feedbackApi: HubFeedbackApi? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    /** Where the credential store is read and written. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val telemetry: TelemetryRecorder = Telemetry,
    /** Reports a fake door, once per installation and only while the user shares usage data. */
    private val fakeDoor: (TelemetryFakeDoor, TelemetryFakeDoorAction) -> Unit =
        Telemetry::fakeDoor,
    /** Whether the user shares usage data. Without it nobody would learn what an order became. */
    private val sharesUsageData: () -> Boolean = { Telemetry.enabled.value },
    private val clock: () -> Long = ::platformCurrentTimeMillis
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState =
        MutableStateFlow(WidgetHubState(gallery = LocalHubWidgets.definitions))
    val state = mutableState.asStateFlow()
    private var ratesJob: Job? = null

    /** The order shown while a widget is dragged; null when it is the saved one. */
    private var previewOrder: List<String>? = null
    private val ppqJobs = mutableMapOf<String, Job>()
    private var claroJob: Job? = null
    private var claroOrdersJob: Job? = null

    /** The open shop's number as Claro accepted it; orders are placed for it. */
    private var claroLine: ClaroLine? = null

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
        followClaroOrders()
    }

    /**
     * Refreshes like [refreshLive], for someone who asked to: returns once every price and balance
     * on the Hub has arrived or failed. Claro orders are followed in the background as usual.
     */
    suspend fun refreshNow() {
        refreshLive()
        (listOfNotNull(ratesJob) + ppqJobs.values.toList()).joinAll()
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

    fun updatePhone(value: String) = edit { editor ->
        editor.copy(phoneInput = value.filter { it.isDigit() || it in "+ " }.take(16))
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
            editor.currencyCode.takeIf { editor.kind.quotesBitcoin },
            claroPhone(editor.phoneInput).takeIf { editor.kind == HubWidgetKind.Claro }
        )
        mutate {
            val newKey = editor.apiKeyInput.takeIf {
                editor.kind == HubWidgetKind.Ppq && it.isNotBlank()
            }
            val balance = newKey?.let { checkPpqKey(it) ?: return@mutate }
            val saved = repository.saveWidget(draft, editor.existingWidgetId)
            if (saved == null) {
                fail(HubWidgetError.SaveFailed)
            } else {
                if (newKey != null && balance != null) connectPpq(saved.id, newKey, balance)
                close()
            }
        }
    }

    fun editWidget(id: String) {
        val editor = editorFor(id) ?: return
        mutableState.update {
            it.copy(screen = HubWidgetScreen.Configure, query = "", error = null, editor = editor)
        }
    }

    /**
     * Gives a placed widget another layout right away, from the Hub, keeping what it was set up
     * with. A smaller layout shows only as many people as it holds, and a larger one brings the
     * others back, as Home Screen widgets keep their setup.
     */
    fun resizeWidget(id: String, variantId: String) {
        if (mutableState.value.busy) return
        val widget = repository.hub.value.widget(id) ?: return
        if (widget.variant.id == variantId) return
        val variant = mutableState.value.gallery.firstOrNull { it.id == widget.definitionId }
            ?.variants?.firstOrNull { it.id == variantId }
            ?: return fail(HubWidgetError.Unavailable)
        mutate {
            if (repository.resizeWidget(id, variant) == null) fail(HubWidgetError.SaveFailed)
        }
    }

    /** The editor for a placed widget, filled with what it is set up with. */
    private fun editorFor(id: String): HubWidgetEditor? {
        val hub = repository.hub.value
        val widget = hub.widget(id) ?: return null
        val definition = mutableState.value.gallery.firstOrNull { it.id == widget.definitionId }
        if (definition == null) {
            fail(HubWidgetError.Unavailable)
            return null
        }
        val variant = definition.variants.firstOrNull { it.id == widget.variant.id }
            ?: definition.variants.first()
        val target = widget.targetId?.let(hub::target)
        val amount = (target?.amountRule as? DirectTargetAmountRule.Preset)?.amount
        return HubWidgetEditor(
            widget.definitionId,
            widget.kind,
            variant.id,
            widget.id,
            // The settings show the people the widget shows; saving them keeps only those.
            contactIds = (target?.let { listOf(it.contactId) } ?: widget.contactIds)
                .take(variant.capacity),
            title = widget.title.orEmpty(),
            amountInput = amount?.minor?.formatMinorAmount(
                CurrencyCatalog.infoFor(amount.normalizedCurrencyCode).fractionDigits
            ).orEmpty(),
            currencyCode = amount?.normalizedCurrencyCode
                ?: widget.currencyCode
                ?: defaultCurrencyCode(),
            comment = target?.comment.orEmpty(),
            phoneInput = widget.phone.orEmpty()
        )
    }

    fun removeWidget(id: String) = mutate {
        val wasPpq = repository.hub.value.widget(id)?.kind == HubWidgetKind.Ppq
        repository.deleteWidget(id)
        if (mutableState.value.editor?.existingWidgetId == id) close()
        // A key belongs to its widget alone and goes with it.
        if (ppq != null && wasPpq) {
            ppqJobs.remove(id)?.cancel()
            withContext(background) { ppq.forgetApiKey(id) }
            mutableState.update { it.copy(ppq = it.ppq - id) }
        }
    }

    /**
     * Shows [id] where it is held while it is dragged, without saving: the other widgets make room
     * for it. [x] and [y] are the middle of the carried widget in cells of a grid [columns] wide,
     * counting a cell and the gap after it as one. [finishMove] keeps the place it was let go in.
     */
    fun previewMove(id: String, x: Float, y: Float, columns: Int) {
        val widgets = repository.hub.value.widgets
        val spans = widgets.associate { it.id to HubGridSpan(it.variant.columns, it.variant.rows) }
        val shown = previewOrder.orEmpty().filter { it in spans }
        val order = shown + spans.keys.filter { it !in shown }
        previewOrder = hubOrderFor(order, id, x, y, columns) { spans.getValue(it) } ?: return
        rebuildTiles()
    }

    /** Keeps the order shown where a dragged widget was let go. */
    fun finishMove() {
        val order = previewOrder ?: return
        val unchanged = order == repository.hub.value.widgets.map { it.id }
        if (unchanged || mutableState.value.busy) return cancelMove()
        mutate {
            try {
                repository.arrangeWidgets(order)
            } finally {
                previewOrder = null
                rebuildTiles()
            }
        }
    }

    /** Puts a dragged widget back where it was. */
    fun cancelMove() {
        if (previewOrder == null) return
        previewOrder = null
        rebuildTiles()
    }
    fun moveWidgetBy(id: String, delta: Int) {
        val index = repository.hub.value.widgets.indexOfFirst { it.id == id }
        if (index >= 0) mutate { repository.moveWidget(id, index + delta) }
    }
    fun setArranging(value: Boolean) {
        if (!value) cancelMove()
        // Arranging can start with a widget already in hand. An error going away above the Hub
        // then would move the Hub, and the widget with it, away from the finger, so it stays.
        mutableState.update { it.copy(arranging = value, error = if (value) it.error else null) }
    }
    fun pay(actionId: String) {
        if (!mutableState.value.arranging && actionId.isNotBlank()) {
            host.dispatch(PaymentHubIntent.SelectItem(HubItemId(actionId)))
        }
    }

    /**
     * Asks ppq.ai for a Lightning invoice over [amountInput] US dollars and hands it to the
     * wallet, which always asks the user to confirm it.
     */
    fun topUpPpq(widgetId: String, amountInput: String) {
        val service = ppq ?: return
        if (mutableState.value.ppqAccount(widgetId).toppingUp || mutableState.value.arranging) {
            return
        }
        val cents = amountInput.parseMinorAmount(2)
        if (cents == null || cents !in HubPpqAccount.TOP_UP_CENTS) {
            return updatePpq(widgetId) { it.copy(topUpError = HubPpqTopUpError.Amount) }
        }
        updatePpq(widgetId) { it.copy(toppingUp = true, topUpError = null) }
        scope.launch {
            var result: PpqResult<String> = PpqResult.Unreachable
            try {
                val key = withContext(background) { service.apiKey(widgetId) }
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
                updatePpq(widgetId) { account ->
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

    fun clearPpqTopUpError(widgetId: String) = updatePpq(widgetId) { it.copy(topUpError = null) }

    /** Opens the shop for a Claro widget and reads what Claro sells to its number. */
    fun openClaro(widgetId: String) {
        val api = claro ?: return
        val phone = repository.hub.value.widget(widgetId)
            ?.takeIf { it.kind == HubWidgetKind.Claro }?.phone
        if (phone == null || mutableState.value.arranging) return
        claroJob?.cancel()
        claroLine = null
        updateClaro { HubClaroShop(widgetId, phone, loading = true) }
        claroJob = scope.launch {
            val catalogue = api.catalogue(phone)
            claroLine = (catalogue as? ClaroResult.Ok)?.value?.line
            updateClaro { shop ->
                when (catalogue) {
                    is ClaroResult.Ok -> shop.copy(
                        loading = false,
                        products = catalogue.value.products,
                        // A shop with nothing in it gives the user nothing to do but try again.
                        problem = HubClaroProblem.Unreachable.takeIf {
                            catalogue.value.products.isEmpty()
                        }
                    )

                    ClaroResult.NotEligible ->
                        shop.copy(loading = false, problem = HubClaroProblem.NotEligible)

                    ClaroResult.Unavailable ->
                        shop.copy(loading = false, problem = HubClaroProblem.Unreachable)
                }
            }
        }
    }

    /**
     * Orders a product of the open shop and hands Claro's invoice to the wallet, which always asks
     * the user to confirm it. The shop closes to make room for it.
     */
    fun buyClaro(productId: String) {
        val api = claro ?: return
        val line = claroLine ?: return
        val shop = mutableState.value.claro
        val product = shop.products.firstOrNull { it.id == productId }
        if (product == null || shop.ordering) return
        updateClaro { it.copy(ordering = true, problem = null) }
        claroJob = scope.launch {
            val invoice = (api.invoice(line, product) as? ClaroResult.Ok)?.value
                ?.takeUnless { overcharges(it, product.priceCents) }
            if (invoice == null) {
                reportClaro(product.topUp, TelemetryHubPurchaseStep.OrderFailed)
                updateClaro { it.copy(ordering = false, problem = HubClaroProblem.OrderFailed) }
            } else {
                // Kept before the hand-over: the Hub may leave the screen for the payment.
                if (sharesUsageData()) {
                    keepClaroOrder(
                        ClaroOrder(
                            invoice.transactionId,
                            invoice.checkout,
                            line.phone,
                            product.topUp,
                            orderedAtMs = clock()
                        )
                    )
                }
                reportClaro(product.topUp, TelemetryHubPurchaseStep.Ordered)
                claroLine = null
                updateClaro { HubClaroShop() }
                host.payInvoice(invoice.bolt11)
            }
        }
    }

    /** Leaves the shop. An order still on its way to an invoice is dropped, unpaid. */
    fun closeClaro() {
        claroJob?.cancel()
        claroLine = null
        updateClaro { HubClaroShop() }
    }

    /** Sends what a tester wrote. The form stays open, and keeps their words, until it worked. */
    fun sendFeedback(kind: HubFeedbackKind, message: String, contact: String) {
        val api = feedbackApi ?: return
        if (mutableState.value.feedback.sending || message.isBlank()) return
        mutableState.update { it.copy(feedback = it.feedback.copy(sending = true, failed = false)) }
        scope.launch {
            val sent = try {
                api.send(kind, message, contact)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            mutableState.update {
                it.copy(
                    feedback = it.feedback.copy(
                        sending = false,
                        failed = !sent,
                        sentSerial = it.feedback.sentSerial + if (sent) 1 else 0
                    )
                )
            }
        }
    }

    fun clearFeedbackError() = mutableState.update {
        it.copy(feedback = it.feedback.copy(failed = false))
    }

    /** [bill] came into view in the gallery. */
    fun billSeen(bill: HubBill) = fakeDoor(bill.door, TelemetryFakeDoorAction.Shown)

    /** Someone wants to pay [bill]. The Hub cannot yet; the platform says that it is on its way. */
    fun billTapped(bill: HubBill) = fakeDoor(bill.door, TelemetryFakeDoorAction.Tapped)

    fun clear() {
        scope.cancel()
        ppq?.close()
        claro?.close()
        feedbackApi?.close()
    }

    /** Changes the account of [widgetId], unless its widget is gone by now. */
    private fun updatePpq(widgetId: String, transform: (HubPpqAccount) -> HubPpqAccount) {
        if (repository.hub.value.widget(widgetId) == null) return
        mutableState.update {
            it.copy(
                ppq =
                    it.ppq + (widgetId to transform(it.ppqAccount(widgetId)))
            )
        }
    }

    /**
     * Asks, for every order whose outcome is not reported yet, whether it was paid and whether
     * Claro delivered it, and reports what it learns to the usage data. Nothing of it is shown:
     * the user's journey ends with the wallet's payment. It runs with [refreshLive], so an order
     * is followed whenever the Hub is on screen, also after the app was closed in between. For
     * someone who does not share usage data nothing is kept and nobody is asked.
     */
    private fun followClaroOrders() {
        val api = claro ?: return
        if (claroOrdersJob?.isActive == true) return
        val orders = repository.hub.value.claroOrders
        if (orders.isEmpty()) return
        // Someone who stopped sharing usage data is not asked about, and no order is kept.
        val follows = sharesUsageData()
        claroOrdersJob = scope.launch {
            try {
                orders.forEach { order ->
                    if (follows) {
                        followClaroOrder(api, order)
                    } else {
                        repository.removeClaroOrder(order.transactionId)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The orders are still kept; the next refresh asks again.
            }
        }
    }

    /** An order that cannot be kept is paid for all the same; only its report is lost. */
    private suspend fun keepClaroOrder(order: ClaroOrder) {
        try {
            repository.saveClaroOrder(order)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
    }

    private suspend fun followClaroOrder(api: ClaroApi, order: ClaroOrder) {
        val age = clock() - order.orderedAtMs
        if (!order.paid) {
            when (api.payment(order.checkout)) {
                ClaroPayment.Paid -> {
                    repository.saveClaroOrder(order.copy(paid = true))
                    reportClaro(order.topUp, TelemetryHubPurchaseStep.Paid)
                }

                // The user did not pay after all: there is nothing to deliver.
                ClaroPayment.Expired -> return repository.removeClaroOrder(order.transactionId)

                ClaroPayment.Unknown -> {
                    if (age >
                        CLARO_FORGET_ORDER_MS
                    ) {
                        repository.removeClaroOrder(order.transactionId)
                    }
                    return
                }
            }
        }
        val step = when (api.delivery(order.phone, order.transactionId)) {
            ClaroDelivery.Delivered -> TelemetryHubPurchaseStep.Delivered

            // Claro needs a moment after the payment; only later is its silence a failure.
            ClaroDelivery.NotConfirmed ->
                if (age > CLARO_DELIVERY_WINDOW_MS) {
                    TelemetryHubPurchaseStep.DeliveryFailed
                } else {
                    return
                }

            ClaroDelivery.NoAnswer -> {
                if (age > CLARO_FORGET_ORDER_MS) repository.removeClaroOrder(order.transactionId)
                return
            }
        }
        repository.removeClaroOrder(order.transactionId)
        reportClaro(order.topUp, step)
    }

    /** One step of a purchase, as a count: never the number, the product or the amount. */
    private fun reportClaro(topUp: Boolean, step: TelemetryHubPurchaseStep) {
        telemetry.record(
            telemetryEvent(
                TelemetryCatalog.hubPurchase,
                mapOf(
                    "service" to TelemetryHubService.Claro,
                    "product" to
                        if (topUp) TelemetryHubProduct.TopUp else TelemetryHubProduct.Package,
                    "step" to step
                )
            )
        )
    }

    private fun updateClaro(transform: (HubClaroShop) -> HubClaroShop) {
        mutableState.update { it.copy(claro = transform(it.claro)) }
    }

    /**
     * Whether [invoice] asks for more bitcoin than the dollar price the user chose is worth at
     * the wallet's own rate, beyond the room two rates need to differ. Claro's payment provider
     * sets the rate. Without a rate of its own the wallet's confirmation is the check.
     */
    private suspend fun overcharges(invoice: ClaroInvoice, priceCents: Long): Boolean {
        val usdPerBitcoin = host.prices?.pricePerBitcoin("USD")?.takeIf { it > 0 } ?: return false
        val worthSats = priceCents / 100.0 / usdPerBitcoin * SATS_PER_BITCOIN
        return invoice.amountSats > worthSats * CLARO_RATE_TOLERANCE
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
        val widgets = repository.hub.value.widgets.filter { it.kind == HubWidgetKind.Ppq }
        // A widget deleted elsewhere takes its account with it.
        mutableState.update { state ->
            state.copy(
                ppq = state.ppq.filterKeys { id ->
                    widgets.any {
                        it.id ==
                            id
                    }
                }
            )
        }
        widgets.forEach { widget ->
            val id = widget.id
            ppqJobs[id]?.cancel()
            ppqJobs[id] = scope.launch {
                val key = try {
                    withContext(background) { service.apiKey(id) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // The credential store is closed, as the iOS Keychain is while the device
                    // is locked. What is on screen stays, and the next refresh reads it again.
                    return@launch
                }
                if (key == null) {
                    updatePpq(id) { HubPpqAccount(topUpSerial = it.topUpSerial) }
                    return@launch
                }
                updatePpq(id) { it.copy(connected = true) }
                when (val balance = service.api.balanceUsd(key)) {
                    is PpqResult.Ok ->
                        updatePpq(id) { it.copy(balanceUsd = balance.value, problem = null) }

                    PpqResult.InvalidKey ->
                        updatePpq(id) { it.copy(problem = HubPpqProblem.InvalidKey) }

                    PpqResult.Unreachable ->
                        updatePpq(id) { it.copy(problem = HubPpqProblem.Unreachable) }
                }
            }
        }
    }

    /** Asks ppq.ai for the balance of [apiKey]. Reports the reason and returns null on failure. */
    private suspend fun checkPpqKey(apiKey: String): Double? {
        val service = ppq
        if (service == null) {
            fail(HubWidgetError.Unavailable)
            return null
        }
        return when (val balance = service.api.balanceUsd(apiKey)) {
            is PpqResult.Ok -> balance.value

            PpqResult.InvalidKey -> {
                fail(HubWidgetError.InvalidApiKey)
                null
            }

            PpqResult.Unreachable -> {
                fail(HubWidgetError.ServiceUnreachable)
                null
            }
        }
    }

    /** Keeps [apiKey], which ppq.ai accepted, as the key of [widgetId] alone. */
    private suspend fun connectPpq(widgetId: String, apiKey: String, balance: Double) {
        val service = ppq ?: return
        ppqJobs.remove(widgetId)?.cancel()
        withContext(background) { service.saveApiKey(widgetId, apiKey) }
        updatePpq(widgetId) { it.copy(connected = true, balanceUsd = balance, problem = null) }
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
        // While a widget is dragged, the others are shown making room for it.
        val widgets = previewOrder?.let { order ->
            hub.widgets.sortedBy { widget ->
                order.indexOf(widget.id).takeIf { it >= 0 }
                    ?: Int.MAX_VALUE
            }
        } ?: hub.widgets
        val tiles = widgets.map { widget ->
            val targets = when (widget.kind) {
                // A widget made smaller from the Hub keeps everyone, and shows who fits.
                HubWidgetKind.Contacts ->
                    widget.contactIds.take(widget.variant.capacity).mapNotNull(hub::contactTarget)

                HubWidgetKind.Shortcut -> listOfNotNull(widget.targetId?.let(hub::target))

                HubWidgetKind.Favorites -> favorites.take(widget.variant.capacity)

                HubWidgetKind.Recents -> recents.take(widget.variant.capacity)

                HubWidgetKind.Rate, HubWidgetKind.Sats, HubWidgetKind.Ppq, HubWidgetKind.Claro ->
                    emptyList()
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
                phone = widget.phone,
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

private const val SATS_PER_BITCOIN = 100_000_000.0

/** How much above the wallet's own rate a Claro invoice may be before it is not paid. */
private const val CLARO_RATE_TOLERANCE = 1.05

/**
 * How long after ordering a paid order may stay unconfirmed before that counts as a failed
 * delivery. The invoice can be paid for about a quarter of an hour, and Claro then needs a
 * moment of its own.
 */
private const val CLARO_DELIVERY_WINDOW_MS = 30 * 60_000L

/** An order nobody could be asked about for this long is dropped, unreported. */
private const val CLARO_FORGET_ORDER_MS = 3 * 24 * 60 * 60_000L
