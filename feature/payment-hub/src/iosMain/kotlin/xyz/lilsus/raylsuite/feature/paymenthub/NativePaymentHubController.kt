package xyz.lilsus.raylsuite.feature.paymenthub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.settings.createSecureSettings
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.paymenthub.canvas.HubGridSpan
import xyz.lilsus.raylsuite.feature.paymenthub.canvas.gridRowCount
import xyz.lilsus.raylsuite.feature.paymenthub.canvas.packHubGrid
import xyz.lilsus.raylsuite.feature.paymenthub.feedback.HubFeedbackKind
import xyz.lilsus.raylsuite.feature.paymenthub.feedback.KtorHubFeedbackApi
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqAccount
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqProblem
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqTopUpError
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubRate
import xyz.lilsus.raylsuite.feature.paymenthub.live.changeLabel
import xyz.lilsus.raylsuite.feature.paymenthub.live.convertFiat
import xyz.lilsus.raylsuite.feature.paymenthub.live.convertSats
import xyz.lilsus.raylsuite.feature.paymenthub.live.forFace
import xyz.lilsus.raylsuite.feature.paymenthub.live.priceLabel
import xyz.lilsus.raylsuite.feature.paymenthub.live.satsLabel
import xyz.lilsus.raylsuite.feature.paymenthub.ppq.HubPpqService
import xyz.lilsus.raylsuite.feature.paymenthub.ppq.createHubPpqService

/** Localized values and explicit intents for the native SwiftUI widget hub. */
class NativePaymentHubController(
    repository: PaymentHubRepository,
    host: PaymentHubController,
    languageChanges: Flow<*>,
    currencyCodes: Flow<String>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currencyCode = CurrencyCatalog.DEFAULT_CODE
    private var canvasColumns = 2
    private val viewModel = WidgetHubViewModel(
        repository = repository,
        host = host,
        defaultCurrencyCode = { currencyCode },
        ppq = createHubPpqService(createSecureSettings(HubPpqService.STORAGE_NAME)),
        feedbackApi = KtorHubFeedbackApi(platform = "ios")
    )
    private val snapshot = MutableStateFlow<NativePaymentHubSnapshot?>(null)

    init {
        scope.launch { viewModel.state.collect { publish() } }
        scope.launch {
            languageChanges.collect {
                publish()
            }
        }
        scope.launch { currencyCodes.collect { currencyCode = it } }
    }

    fun observe(onChange: (NativePaymentHubSnapshot) -> Unit): () -> Unit {
        val job = scope.launch { snapshot.filterNotNull().collect(onChange) }
        return { job.cancel() }
    }

    fun openGallery() = viewModel.openGallery()

    fun back() {
        viewModel.back()
    }

    fun close() = viewModel.close()

    fun selectDefinition(id: String) = viewModel.selectDefinition(id)

    fun selectVariant(id: String) = viewModel.selectVariant(id)

    fun configureSelected() = viewModel.configureSelected()

    fun continueFromVariants() = viewModel.continueFromVariants()

    /** Called when the Hub appears and about once a minute while it stays on screen. */
    fun refreshLive() = viewModel.refreshLive()

    fun updateApiKey(value: String) = viewModel.updateApiKey(value)

    fun topUpPpq(amount: String) = viewModel.topUpPpq(amount)

    fun clearPpqTopUpError() = viewModel.clearPpqTopUpError()

    /** [kind] is one of widget, idea, problem and other. */
    fun sendFeedback(kind: String, message: String, contact: String) {
        val chosen = HubFeedbackKind.entries.firstOrNull { it.wire == kind } ?: return
        viewModel.sendFeedback(chosen, message, contact)
    }

    fun clearFeedbackError() = viewModel.clearFeedbackError()

    /** Both converter fields after the user typed [input] into the fiat one. */
    fun convertFiat(currencyCode: String, input: String): NativeHubConversion =
        viewModel.state.value.rates[currencyCode]?.convertFiat(input)
            ?.let { NativeHubConversion(it.fiat, it.sats) }
            ?: NativeHubConversion(input, "")

    /** Both converter fields after the user typed [input] into the sats one. */
    fun convertSats(currencyCode: String, input: String): NativeHubConversion =
        viewModel.state.value.rates[currencyCode]?.convertSats(input)
            ?.let { NativeHubConversion(it.fiat, it.sats) }
            ?: NativeHubConversion("", input)

    fun updateQuery(value: String) = viewModel.updateQuery(value)

    fun toggleContact(id: String) = viewModel.toggleContact(id)

    fun moveContact(id: String, offset: Int) = viewModel.moveContact(id, offset)

    fun updateTitle(value: String) = viewModel.updateTitle(value)

    fun updateAmount(value: String) = viewModel.updateAmount(value)

    fun selectCurrency(code: String) = viewModel.selectCurrency(code)

    fun updateComment(value: String) = viewModel.updateComment(value)

    fun addContact(title: String, address: String) = viewModel.addContact(title, address)

    fun deleteContact(id: String) = viewModel.deleteContact(id)

    fun saveWidget() = viewModel.saveWidget()

    fun editWidget(id: String) = viewModel.editWidget(id)

    fun removeWidget(id: String) = viewModel.removeWidget(id)

    fun moveWidget(id: String, onto: String) = viewModel.moveWidget(id, onto)

    fun moveWidgetBy(id: String, offset: Int) = viewModel.moveWidgetBy(id, offset)

    fun setArranging(value: Boolean) = viewModel.setArranging(value)

    fun pay(actionId: String) = viewModel.pay(actionId)

    fun resizeWidget(id: String, variantId: String) {
        viewModel.editWidget(id)
        if (viewModel.state.value.editor?.existingWidgetId != id) return
        viewModel.selectVariant(variantId)
        viewModel.configureSelected()
    }

    fun setCanvasColumns(value: Int) {
        val columns = if (value >= 4) 4 else 2
        if (columns == canvasColumns) return
        canvasColumns = columns
        publish()
    }

    fun clear() {
        viewModel.clear()
        scope.cancel()
    }

    private fun publish() {
        val state = viewModel.state.value
        val copy = loadCopy()
        val definitions = state.gallery.map { it.toNative(state) }
        val placements = packHubGrid(state.widgets, canvasColumns) {
            HubGridSpan(it.variant.columns, it.variant.rows)
        }
        snapshot.value = NativePaymentHubSnapshot(
            screen = state.screen.name.lowercase(),
            text = copy,
            canvas = NativeHubCanvas(
                tiles = placements.map { placement ->
                    placement.value.toNative(state).copy(
                        column = placement.column,
                        row = placement.row
                    )
                },
                columns = canvasColumns,
                rows = placements.gridRowCount(),
                arranging = state.arranging
            ),
            gallery = definitions,
            selectedDefinition = definitions.firstOrNull { it.id == state.editor?.definitionId },
            editor = state.editor?.let { editor ->
                val variant = state.selectedVariant ?: return@let null
                val query = state.query.trim()
                NativeHubEditor(
                    isEditing = editor.existingWidgetId != null,
                    kind = editor.kind.name.lowercase(),
                    variantId = editor.variantId,
                    title = editor.title,
                    amount = editor.amountInput,
                    fiatAmountHint = if (
                        editor.kind == HubWidgetKind.Shortcut && editor.currencyCode != "SAT"
                    ) {
                        localized("hub_target_amount_fiat_hint")
                    } else {
                        null
                    },
                    currencyCode = editor.currencyCode,
                    currencyCodes = CurrencyCatalog.supportedCodes,
                    comment = editor.comment,
                    selectedContacts = editor.contactIds.mapNotNull { id ->
                        state.contacts.firstOrNull { it.id == id }?.toNative()
                    },
                    availableContacts = state.contacts.filter { contact ->
                        contact.id !in editor.contactIds && (
                            query.isEmpty() || contact.title.contains(query, ignoreCase = true) ||
                                contact.address.full.contains(query, ignoreCase = true)
                            )
                    }.map { it.toNative() },
                    // Reads as an action at any capacity; "Choose up to 1 contacts" did not.
                    selectionTitle = localized(
                        if (variant.capacity == 1) {
                            "hub_widget_pick_contact"
                        } else {
                            "hub_widget_pick_contacts"
                        }
                    ),
                    selectionCount = localized(
                        "hub_widget_selected_count",
                        editor.contactIds.size,
                        variant.capacity
                    ),
                    selectionLimit = localized("hub_widget_choose_contacts", variant.capacity),
                    capacity = variant.capacity,
                    showsName = !editor.kind.showsInformation,
                    currencyChoices = if (editor.kind.quotesBitcoin) {
                        LocalHubWidgets.fiatCodes
                    } else {
                        emptyList()
                    },
                    apiKey = editor.apiKeyInput,
                    apiKeyHint = if (editor.kind == HubWidgetKind.Ppq) {
                        localized(
                            if (state.ppq.connected) {
                                "hub_ppq_api_key_saved"
                            } else {
                                "hub_ppq_api_key_hint"
                            }
                        )
                    } else {
                        null
                    },
                    preview = if (editor.kind.showsInformation) {
                        previewTile(
                            state.selectedDefinition?.id ?: editor.definitionId,
                            editor.kind,
                            variant,
                            state,
                            editor.currencyCode
                        )
                    } else {
                        null
                    },
                    canSave = state.editorValidationError() == null,
                    validationMessage = when (state.editorValidationError()) {
                        HubWidgetError.SelectContacts -> localized(
                            "hub_widget_select_contacts_error"
                        )

                        HubWidgetError.TooManyContacts -> localized(
                            "hub_widget_choose_contacts",
                            variant.capacity
                        )

                        HubWidgetError.InvalidAmount -> localized("hub_error_enter_amount")

                        HubWidgetError.ApiKeyRequired -> localized("hub_ppq_error_enter_key")

                        null -> null

                        else -> copy.unavailable
                    }
                )
            },
            query = state.query,
            contactsEmpty = state.contacts.isEmpty(),
            busy = state.busy,
            error = state.error?.let { error ->
                when (error) {
                    HubWidgetError.ContactNameRequired -> localized("hub_error_enter_title")

                    HubWidgetError.InvalidAddress -> localized("hub_error_invalid_address")

                    HubWidgetError.SelectContacts -> localized("hub_widget_select_contacts_error")

                    HubWidgetError.TooManyContacts -> localized(
                        "hub_widget_choose_contacts",
                        state.selectedVariant?.capacity ?: 1
                    )

                    HubWidgetError.InvalidAmount -> localized("hub_error_enter_amount")

                    HubWidgetError.Unavailable -> copy.unavailable

                    HubWidgetError.SaveFailed -> localized("hub_widget_error_save")

                    HubWidgetError.ApiKeyRequired -> localized("hub_ppq_error_enter_key")

                    HubWidgetError.InvalidApiKey -> localized("hub_ppq_error_key")

                    HubWidgetError.ServiceUnreachable -> localized("hub_ppq_error_unreachable")
                }
            },
            contactSavedSerial = state.contactSavedSerial,
            addsWithoutSetup = state.addsWithoutSetup,
            feedback = NativeHubFeedback(
                sending = state.feedback.sending,
                failed = state.feedback.failed,
                sentSerial = state.feedback.sentSerial
            ),
            ppq = NativeHubPpq(
                balance = state.ppq.balanceAmount()?.let {
                    localized("hub_ppq_balance") + " · " + currentAmountFormatter().format(it)
                },
                presets = HubPpqAccount.TOP_UP_PRESETS_USD.map(Int::toString),
                toppingUp = state.ppq.toppingUp,
                topUpError = when (state.ppq.topUpError) {
                    HubPpqTopUpError.Amount -> localized("hub_ppq_error_amount")
                    HubPpqTopUpError.InvalidKey -> localized("hub_ppq_error_key")
                    HubPpqTopUpError.Unreachable -> localized("hub_ppq_error_unreachable")
                    null -> null
                },
                topUpSerial = state.ppq.topUpSerial
            )
        )
    }

    /**
     * What an information widget shows, already written out for SwiftUI. A [preview] is a picture:
     * it shows the real figure once known and a sample until then.
     */
    private fun infoFor(
        kind: HubWidgetKind,
        variant: HubWidgetVariant,
        currencyCode: String?,
        state: WidgetHubState,
        preview: Boolean
    ): NativeHubInfo? {
        if (!kind.showsInformation) return null
        val name = localized(kind.titleKey())
        if (kind == HubWidgetKind.Ppq) {
            val account = if (preview && state.ppq.balanceUsd == null) {
                HubPpqAccount(connected = true, balanceUsd = 12.5)
            } else {
                state.ppq
            }
            val value = account.balanceAmount()?.let { currentAmountFormatter().format(it) }
            val needsKey = !account.connected || account.problem == HubPpqProblem.InvalidKey
            val caption = localized(
                when {
                    !account.connected -> "hub_ppq_add_key"
                    account.problem == HubPpqProblem.InvalidKey -> "hub_ppq_key_rejected"
                    account.problem == HubPpqProblem.Unreachable -> "hub_ppq_not_updated"
                    else -> "hub_ppq_balance"
                }
            )
            return NativeHubInfo(
                headline = name,
                value = value,
                caption = caption,
                captionTone = when {
                    !account.connected -> "accent"
                    account.problem == HubPpqProblem.InvalidKey -> "down"
                    else -> "plain"
                },
                action = if (needsKey) "setup" else "topup",
                actionTitle = localized(if (needsKey) "hub_ppq_add_key" else "hub_ppq_top_up"),
                hint = localized("hub_widget_needs_setup").takeIf { !account.connected },
                accessibilityLabel = listOfNotNull(name, value, caption).joinToString()
            )
        }
        val code = currencyCode ?: state.defaultFiatCode
        val rate = state.rates[code].forFace(code, preview)
        val unavailable = if (rate == null && state.ratesUnavailable) {
            localized("hub_rate_unavailable")
        } else {
            null
        }
        val day = localized("hub_rate_day")
        // Both bitcoin widgets open the same converter, in the widget's own currency.
        val converterNote = rate?.let {
            "1 BTC = " + currentAmountFormatter().format(it.priceAmount())
        }.orEmpty()
        val wholeUnits = CurrencyCatalog.infoFor(code).fractionDigits == 0
        if (kind == HubWidgetKind.Sats) {
            val value = rate?.satsLabel()
            val caption = unavailable ?: localized("hub_sats_unit")
            return NativeHubInfo(
                headline = "1 $code",
                value = value,
                caption = caption,
                currencyCode = code,
                action = if (rate == null) "none" else "convert",
                actionTitle = localized("hub_converter_title"),
                converterNote = converterNote,
                converterPlaceholder = value.orEmpty(),
                wholeUnits = wholeUnits,
                accessibilityLabel = listOfNotNull(name, "1 $code", value, caption).joinToString()
            )
        }
        val price = rate?.priceLabel(currentAmountFormatter())
        val change = rate?.changeLabel()
        return NativeHubInfo(
            headline = localized("hub_rate_headline"),
            value = price,
            // The day's move once the chart is known; until then just what the price is in.
            caption = unavailable ?: change?.let { "$it · $day" } ?: code,
            captionTone = when {
                unavailable != null || change == null -> "plain"
                (rate.changePercent ?: 0.0) >= 0 -> "up"
                else -> "down"
            },
            chart = rate?.chart().orEmpty(),
            currencyCode = code,
            action = if (rate == null) "none" else "convert",
            actionTitle = localized("hub_converter_title"),
            converterNote = converterNote,
            converterPlaceholder = rate?.satsLabel().orEmpty(),
            wholeUnits = wholeUnits,
            accessibilityLabel = listOfNotNull(name, price, change?.let { "$it $day" }, unavailable)
                .joinToString()
        )
    }

    private fun previewTile(
        definitionId: String,
        kind: HubWidgetKind,
        variant: HubWidgetVariant,
        state: WidgetHubState,
        currencyCode: String? = null,
        title: String? = null
    ) = NativeHubTile(
        id = "preview:$definitionId:${variant.id}",
        kind = kind.name.lowercase(),
        title = title ?: localized(kind.titleKey()),
        customTitle = title,
        variantId = variant.id,
        column = 0,
        row = 0,
        columns = variant.columns,
        rows = variant.rows,
        people = if (kind.showsInformation) {
            emptyList()
        } else {
            (0 until variant.capacity).map { index ->
                val contact = state.contacts.getOrNull(index)
                NativeHubPerson(
                    id = "preview:$index",
                    actionId = "",
                    title = contact?.title ?: localized("hub_widget_contact_placeholder"),
                    initials = contact?.title?.initials().orEmpty(),
                    contactTitle = contact?.title ?: localized("hub_widget_contact_placeholder"),
                    contactInitials = contact?.title?.initials().orEmpty(),
                    address = "",
                    amount = if (kind == HubWidgetKind.Shortcut) {
                        currentAmountFormatter().format(
                            DisplayAmount(500, CurrencyCatalog.infoFor("USD").currency)
                        )
                    } else {
                        null
                    }
                )
            }
        },
        sizes = emptyList(),
        emptyText = "",
        info = infoFor(kind, variant, currencyCode, state, preview = true)
    )

    private fun HubWidgetDefinition.toNative(state: WidgetHubState): NativeHubDefinition =
        NativeHubDefinition(
            id = id,
            kind = kind.name.lowercase(),
            title = title ?: localized(kind.titleKey()),
            detail = description ?: localized(kind.descriptionKey()),
            variants = variants.map { variant ->
                NativeHubVariant(
                    id = variant.id,
                    title = variant.title ?: localized(variant.titleKey(kind)),
                    detail = localized(variant.descriptionKey(kind)),
                    columns = variant.columns,
                    rows = variant.rows,
                    capacity = variant.capacity,
                    preview = previewTile(id, kind, variant, state, title = title)
                )
            }
        )

    private fun HubWidgetTile.toNative(state: WidgetHubState): NativeHubTile {
        val definitions = state.gallery
        return NativeHubTile(
            id = id,
            kind = kind.name.lowercase(),
            title = title?.takeIf { it.isNotBlank() }
                ?: definitions.firstOrNull { it.id == definitionId }?.title
                ?: localized(kind.titleKey()),
            customTitle = title?.takeIf { it.isNotBlank() },
            variantId = variant.id,
            column = 0,
            row = 0,
            columns = variant.columns,
            rows = variant.rows,
            people = people.map { person ->
                NativeHubPerson(
                    id = person.actionId,
                    actionId = person.actionId,
                    title = person.title,
                    initials = person.title.initials(),
                    contactTitle = person.contactTitle,
                    contactInitials = person.contactTitle.initials(),
                    address = person.address,
                    amount = person.amount?.let { amount ->
                        currentAmountFormatter().format(
                            DisplayAmount(
                                amount.minor,
                                CurrencyCatalog.infoFor(amount.currencyCode).currency
                            )
                        )
                    }
                )
            },
            sizes = definitions.firstOrNull { it.id == definitionId }?.variants.orEmpty().map {
                NativeHubSizeChoice(it.id, it.title ?: localized(it.titleKey(kind)))
            },
            emptyText = localized(
                when (kind) {
                    HubWidgetKind.Favorites -> "hub_widget_favorites_empty"
                    HubWidgetKind.Recents -> "hub_widget_recent_empty"
                    else -> "hub_widget_needs_setup"
                }
            ),
            info = infoFor(kind, variant, currencyCode, state, preview = false)
        )
    }
}

data class NativePaymentHubSnapshot(
    val screen: String,
    val text: NativePaymentHubCopy,
    val canvas: NativeHubCanvas,
    val gallery: List<NativeHubDefinition>,
    val selectedDefinition: NativeHubDefinition?,
    val editor: NativeHubEditor?,
    val query: String,
    val contactsEmpty: Boolean,
    val busy: Boolean,
    val error: String?,
    val contactSavedSerial: Int,
    /** Choosing a layout already adds the widget, so that screen offers "Add" not "Continue". */
    val addsWithoutSetup: Boolean,
    val ppq: NativeHubPpq,
    val feedback: NativeHubFeedback
)

/** The feedback form's progress. [sentSerial] changes when a message arrived, which closes it. */
data class NativeHubFeedback(val sending: Boolean, val failed: Boolean, val sentSerial: Int)

/** The ppq.ai account as the top-up sheet shows it. */
data class NativeHubPpq(
    val balance: String?,
    /** Whole US dollar amounts offered as shortcuts. */
    val presets: List<String>,
    val toppingUp: Boolean,
    val topUpError: String?,
    /** Changes when an invoice was handed to the wallet, which closes the sheet. */
    val topUpSerial: Int
)

data class NativeHubConversion(val fiat: String, val sats: String)

data class NativeHubCanvas(
    val tiles: List<NativeHubTile>,
    val columns: Int,
    val rows: Int,
    val arranging: Boolean
)

data class NativeHubDefinition(
    val id: String,
    val kind: String,
    val title: String,
    val detail: String,
    val variants: List<NativeHubVariant>
)

data class NativeHubVariant(
    val id: String,
    val title: String,
    val detail: String,
    val columns: Int,
    val rows: Int,
    val capacity: Int,
    val preview: NativeHubTile
)

data class NativeHubTile(
    val id: String,
    val kind: String,
    val title: String,
    val customTitle: String?,
    val variantId: String,
    val column: Int,
    val row: Int,
    val columns: Int,
    val rows: Int,
    val people: List<NativeHubPerson>,
    val sizes: List<NativeHubSizeChoice>,
    val emptyText: String,
    /** Set for the widgets that show live information instead of people. */
    val info: NativeHubInfo? = null
)

data class NativeHubInfo(
    /** What the figure is about: "BTC", "1 USD", "PPQ.AI". */
    val headline: String,
    /** The figure itself; null while it is not known. */
    val value: String?,
    val caption: String,
    /** `plain`, `accent`, `up` or `down`. */
    val captionTone: String = "plain",
    /** The day's prices scaled to 0..1, oldest first; empty without a chart. */
    val chart: List<Float> = emptyList(),
    val currencyCode: String = "",
    /** What a tap does: `none`, `convert`, `topup` or `setup`. */
    val action: String = "none",
    /** Names [action] on a wide widget, which has room to say it. */
    val actionTitle: String = "",
    /** Says how to set the widget up, in place of a figure it cannot show before that. */
    val hint: String? = null,
    val converterNote: String = "",
    val converterPlaceholder: String = "",
    /** The currency has no minor unit, so its converter field takes no decimals. */
    val wholeUnits: Boolean = false,
    val accessibilityLabel: String
)

data class NativeHubSizeChoice(val id: String, val title: String)

data class NativeHubPerson(
    val id: String,
    val actionId: String,
    val title: String,
    val initials: String,
    /** Who is paid. [title] is the shortcut's own name when it has one. */
    val contactTitle: String,
    val contactInitials: String,
    val address: String,
    val amount: String?
)

data class NativeHubContact(
    val id: String,
    val title: String,
    val address: String,
    val initials: String
)

data class NativeHubEditor(
    val isEditing: Boolean,
    val kind: String,
    val variantId: String,
    val title: String,
    val amount: String,
    val fiatAmountHint: String?,
    val currencyCode: String,
    val currencyCodes: List<String>,
    val comment: String,
    val selectedContacts: List<NativeHubContact>,
    val availableContacts: List<NativeHubContact>,
    val selectionTitle: String,
    val selectionCount: String,
    /** Explains the ceiling once a multi-contact widget is full. */
    val selectionLimit: String,
    val capacity: Int,
    /** Information widgets show no name. */
    val showsName: Boolean,
    /** The fiat currencies a Rate or Sats widget can quote; empty for the other widgets. */
    val currencyChoices: List<String>,
    val apiKey: String,
    /** Explains the ppq.ai API key field; null for every other widget. */
    val apiKeyHint: String?,
    val preview: NativeHubTile?,
    val canSave: Boolean,
    val validationMessage: String?
)

data class NativePaymentHubCopy(
    val galleryTitle: String,
    val galleryBody: String,
    val sectionPeople: String,
    val sectionCollections: String,
    val sectionInfo: String,
    val sectionAi: String,
    val feedbackPrompt: String,
    val feedbackPromptBody: String,
    val feedbackThanks: String,
    val feedbackTitle: String,
    val feedbackBody: String,
    val feedbackKindWidget: String,
    val feedbackKindIdea: String,
    val feedbackKindProblem: String,
    val feedbackKindOther: String,
    val feedbackMessage: String,
    val feedbackContact: String,
    val feedbackContactHint: String,
    val feedbackSend: String,
    val feedbackError: String,
    val currency: String,
    val apiKey: String,
    val convert: String,
    val satsUnit: String,
    val topUpTitle: String,
    val topUpBody: String,
    val topUpAction: String,
    val topUpAmount: String,
    val editHub: String,
    val addWidget: String,
    val editWidget: String,
    val widgetOptions: String,
    val chooseLayout: String,
    val continueTitle: String,
    val widgetName: String,
    val edit: String,
    val done: String,
    val back: String,
    val cancel: String,
    val save: String,
    val remove: String,
    val moveUp: String,
    val moveDown: String,
    val name: String,
    val address: String,
    val amount: String,
    val comment: String,
    val search: String,
    val addContact: String,
    val saveContact: String,
    val deleteContact: String,
    val deleteContactTitle: String,
    val deleteContactBody: String,
    val noContacts: String,
    val noMatches: String,
    val emptyTitle: String,
    val emptyBody: String,
    val unavailable: String,
    val contactsTitle: String,
    val automaticHint: String
)

private fun loadCopy() = NativePaymentHubCopy(
    galleryTitle = localized("hub_widget_gallery_title"),
    galleryBody = localized("hub_widget_gallery_body"),
    sectionPeople = localized("hub_widget_section_people"),
    sectionCollections = localized("hub_widget_section_collections"),
    sectionInfo = localized("hub_widget_section_info"),
    sectionAi = localized("hub_widget_section_ai"),
    feedbackPrompt = localized("hub_feedback_prompt"),
    feedbackPromptBody = localized("hub_feedback_prompt_body"),
    feedbackThanks = localized("hub_feedback_thanks"),
    feedbackTitle = localized("hub_feedback_title"),
    feedbackBody = localized("hub_feedback_body"),
    feedbackKindWidget = localized("hub_feedback_kind_widget"),
    feedbackKindIdea = localized("hub_feedback_kind_idea"),
    feedbackKindProblem = localized("hub_feedback_kind_problem"),
    feedbackKindOther = localized("hub_feedback_kind_other"),
    feedbackMessage = localized("hub_feedback_message"),
    feedbackContact = localized("hub_feedback_contact"),
    feedbackContactHint = localized("hub_feedback_contact_hint"),
    feedbackSend = localized("hub_feedback_send"),
    feedbackError = localized("hub_feedback_error"),
    currency = localized("hub_widget_currency"),
    apiKey = localized("hub_ppq_api_key"),
    convert = localized("hub_converter_title"),
    satsUnit = localized("hub_sats_unit"),
    topUpTitle = localized("hub_ppq_top_up_title"),
    topUpBody = localized("hub_ppq_top_up_body"),
    topUpAction = localized("hub_ppq_top_up_action"),
    topUpAmount = localized("hub_ppq_amount"),
    editHub = localized("hub_edit_hub"),
    addWidget = localized("hub_widget_add"),
    editWidget = localized("hub_widget_edit"),
    widgetOptions = localized("hub_widget_options"),
    chooseLayout = localized("hub_widget_select_variant"),
    continueTitle = localized("hub_widget_continue"),
    widgetName = localized("hub_widget_name"),
    edit = localized("hub_canvas_edit"),
    done = localized("hub_canvas_done"),
    back = localized("hub_new_back"),
    cancel = localized("hub_canvas_remove_cancel"),
    save = localized("hub_configure_save"),
    remove = localized("hub_canvas_remove_confirm"),
    moveUp = localized("hub_action_move_up"),
    moveDown = localized("hub_action_move_down"),
    name = localized("hub_target_name_label"),
    address = localized("hub_target_address_label"),
    amount = localized("hub_widget_shortcut_amount"),
    comment = localized("hub_target_comment_label"),
    search = localized("hub_new_search"),
    addContact = localized("hub_widget_add_contact"),
    saveContact = localized("hub_widget_save_contact"),
    deleteContact = localized("hub_contact_delete"),
    deleteContactTitle = localized("hub_contact_delete_title"),
    deleteContactBody = localized("hub_contact_delete_body"),
    noContacts = localized("hub_new_no_contacts"),
    noMatches = localized("hub_new_no_matches"),
    emptyTitle = localized("hub_widget_empty_title"),
    emptyBody = localized("hub_widget_empty_body"),
    unavailable = localized("hub_widget_unavailable"),
    contactsTitle = localized("hub_widget_contacts"),
    automaticHint = localized("hub_widget_recents_hint")
)

private fun HubContact.toNative() = NativeHubContact(id, title, address.full, title.initials())

private fun String.initials(): String = trim().split(Regex("\\s+"))
    .filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }

private fun HubWidgetKind.titleKey(): String = "hub_widget_${name.lowercase()}"

private fun HubWidgetKind.descriptionKey(): String = "hub_widget_${name.lowercase()}_body"

/** Layouts are named after their contacts, except where a widget shows no contacts. */
private fun HubWidgetVariant.titleKey(kind: HubWidgetKind): String = when (id) {
    "single" -> if (kind.showsInformation) "hub_widget_small" else "hub_widget_single"
    "row" -> if (kind.showsInformation) "hub_widget_wide" else "hub_widget_row"
    else -> if (kind.showsInformation) "hub_widget_large" else "hub_widget_card"
}

private fun HubWidgetVariant.descriptionKey(kind: HubWidgetKind): String = titleKey(kind) + "_body"

private fun resource(key: String) = NativeStringResource("PaymentHub", key)

private fun localized(key: String): String = nativeString(resource(key))

private fun localized(key: String, value: Int): String = nativeString(resource(key), value)

private fun localized(key: String, first: Int, second: Int): String =
    nativeString(resource(key), first, second)
