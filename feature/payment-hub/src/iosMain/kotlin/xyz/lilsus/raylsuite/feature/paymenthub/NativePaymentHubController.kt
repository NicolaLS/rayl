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
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.paymenthub.canvas.HubGridSpan
import xyz.lilsus.raylsuite.feature.paymenthub.canvas.gridRowCount
import xyz.lilsus.raylsuite.feature.paymenthub.canvas.packHubGrid
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController

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
        defaultCurrencyCode = { currencyCode }
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
        val definitions = state.gallery.map { it.toNative(state.contacts) }
        val placements = packHubGrid(state.widgets, canvasColumns) {
            HubGridSpan(it.variant.columns, it.variant.rows)
        }
        snapshot.value = NativePaymentHubSnapshot(
            screen = state.screen.name.lowercase(),
            text = copy,
            canvas = NativeHubCanvas(
                tiles = placements.map { placement ->
                    placement.value.toNative(state.gallery).copy(
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
                }
            },
            contactSavedSerial = state.contactSavedSerial
        )
    }

    private fun HubWidgetDefinition.toNative(contacts: List<HubContact>): NativeHubDefinition =
        NativeHubDefinition(
            id = id,
            kind = kind.name.lowercase(),
            title = title ?: localized(kind.titleKey()),
            detail = description ?: localized(kind.descriptionKey()),
            variants = variants.map { variant ->
                NativeHubVariant(
                    id = variant.id,
                    title = variant.title ?: localized(variant.titleKey()),
                    detail = localized(variant.descriptionKey()),
                    columns = variant.columns,
                    rows = variant.rows,
                    capacity = variant.capacity,
                    preview = NativeHubTile(
                        id = "preview:$id:${variant.id}",
                        kind = kind.name.lowercase(),
                        title = title ?: localized(kind.titleKey()),
                        customTitle = title,
                        variantId = variant.id,
                        column = 0,
                        row = 0,
                        columns = variant.columns,
                        rows = variant.rows,
                        people = (0 until variant.capacity).map { index ->
                            val contact = contacts.getOrNull(index)
                            NativeHubPerson(
                                id = "preview:$index",
                                actionId = "",
                                title =
                                    contact?.title
                                        ?: localized("hub_widget_contact_placeholder"),
                                initials = contact?.title?.initials().orEmpty(),
                                contactTitle =
                                    contact?.title
                                        ?: localized("hub_widget_contact_placeholder"),
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
                        },
                        sizes = emptyList(),
                        emptyText = ""
                    )
                )
            }
        )

    private fun HubWidgetTile.toNative(definitions: List<HubWidgetDefinition>): NativeHubTile =
        NativeHubTile(
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
                NativeHubSizeChoice(it.id, it.title ?: localized(it.titleKey()))
            },
            emptyText = localized(
                when (kind) {
                    HubWidgetKind.Favorites -> "hub_widget_favorites_empty"
                    HubWidgetKind.Recents -> "hub_widget_recent_empty"
                    else -> "hub_widget_contacts_empty"
                }
            )
        )
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
    val contactSavedSerial: Int
)

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
    val emptyText: String
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
    val canSave: Boolean,
    val validationMessage: String?
)

data class NativePaymentHubCopy(
    val galleryTitle: String,
    val galleryBody: String,
    val sectionPeople: String,
    val sectionCollections: String,
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

private fun HubWidgetVariant.titleKey(): String = when (id) {
    "single" -> "hub_widget_single"
    "row" -> "hub_widget_row"
    else -> "hub_widget_card"
}

private fun HubWidgetVariant.descriptionKey(): String = when (id) {
    "single" -> "hub_widget_single_body"
    "row" -> "hub_widget_row_body"
    else -> "hub_widget_card_body"
}

private fun resource(key: String) = NativeStringResource("PaymentHub", key)

private fun localized(key: String): String = nativeString(resource(key))

private fun localized(key: String, value: Int): String = nativeString(resource(key), value)

private fun localized(key: String, first: Int, second: Int): String =
    nativeString(resource(key), first, second)
