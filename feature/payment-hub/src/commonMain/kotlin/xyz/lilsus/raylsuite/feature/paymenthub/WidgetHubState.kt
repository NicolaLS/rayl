package xyz.lilsus.raylsuite.feature.paymenthub

import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.StoredAmount
import xyz.lilsus.raylsuite.feature.paymenthub.claro.claroPhone
import xyz.lilsus.raylsuite.feature.paymenthub.create.hasFractionForWholeCurrency
import xyz.lilsus.raylsuite.feature.paymenthub.create.parseMinorAmount
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubClaroShop
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubFeedbackState
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqAccount
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubRate

enum class HubWidgetKind {
    Contacts,
    Shortcut,
    Favorites,
    Recents,

    /** Bitcoin's price in a fiat currency, with the last day's chart. */
    Rate,

    /** How many sats one unit of a fiat currency buys; opens a converter. */
    Sats,

    /** The balance of the user's ppq.ai account, with Lightning top-ups. */
    Ppq,

    /** Top-ups and packages for one Claro El Salvador number. */
    Claro;

    /** Quotes bitcoin in the widget's own fiat currency. */
    val quotesBitcoin: Boolean get() = this == Rate || this == Sats

    /** Shows a figure and opens something of its own, instead of people to pay. */
    val showsInformation: Boolean get() = quotesBitcoin || this == Ppq || this == Claro
}

enum class HubWidgetScreen { Hub, Gallery, Variants, Configure }

data class HubWidgetVariant(
    val id: String,
    val columns: Int,
    val rows: Int,
    val capacity: Int,
    val title: String? = null
)

data class HubWidgetDefinition(
    val id: String,
    val kind: HubWidgetKind,
    val variants: List<HubWidgetVariant>,
    val title: String? = null,
    val description: String? = null
)

data class HubWidgetPerson(
    val actionId: String,
    val contactId: String,
    val title: String,
    val address: String,
    val amount: StoredAmount? = null,
    /** Who is paid. [title] is the shortcut's own name when it has one. */
    val contactTitle: String = title
)

data class HubWidgetTile(
    val id: String,
    val definitionId: String,
    val kind: HubWidgetKind,
    val variant: HubWidgetVariant,
    val title: String?,
    val people: List<HubWidgetPerson> = emptyList(),
    val currencyCode: String? = null,
    val phone: String? = null
)

data class HubWidgetEditor(
    val definitionId: String,
    val kind: HubWidgetKind,
    val variantId: String,
    val existingWidgetId: String? = null,
    val contactIds: List<String> = emptyList(),
    val title: String = "",
    val amountInput: String = "",
    val currencyCode: String = "USD",
    val comment: String = "",
    /** A new API key for this widget. It is checked and stored on save, and never shown again. */
    val apiKeyInput: String = "",
    /** The number a Claro widget tops up, as the user typed it. */
    val phoneInput: String = ""
)

enum class HubWidgetError {
    ContactNameRequired,
    InvalidAddress,
    SelectContacts,
    TooManyContacts,
    InvalidAmount,
    Unavailable,
    SaveFailed,
    ApiKeyRequired,
    InvalidApiKey,
    ServiceUnreachable,
    PhoneRequired
}

data class WidgetHubState(
    val screen: HubWidgetScreen = HubWidgetScreen.Hub,
    val widgets: List<HubWidgetTile> = emptyList(),
    val gallery: List<HubWidgetDefinition> = emptyList(),
    val contacts: List<HubContact> = emptyList(),
    val editor: HubWidgetEditor? = null,
    val query: String = "",
    val arranging: Boolean = false,
    val busy: Boolean = false,
    val contactSavedSerial: Int = 0,
    val error: HubWidgetError? = null,
    /** Bitcoin prices by fiat currency code, for the widgets on the canvas and the previews. */
    val rates: Map<String, HubRate> = emptyMap(),
    /** The last refresh found no price, so a widget without one says so instead of loading. */
    val ratesUnavailable: Boolean = false,
    /** The fiat currency a new Rate or Sats widget starts with. */
    val defaultFiatCode: String = "USD",
    /** The ppq.ai account of each PPQ.AI widget, by widget id. */
    val ppq: Map<String, HubPpqAccount> = emptyMap(),
    val claro: HubClaroShop = HubClaroShop(),
    val feedback: HubFeedbackState = HubFeedbackState()
) {
    /**
     * Whether choosing a layout already adds the widget. Every new widget is: it starts with its
     * defaults, and one that cannot work without a choice asks on its face to be set up.
     */
    val addsWithoutSetup: Boolean get() = editor != null && editor.existingWidgetId == null

    val selectedDefinition: HubWidgetDefinition?
        get() = gallery.firstOrNull { it.id == editor?.definitionId }

    /** The account of [widgetId]; a widget without a key, or one not placed yet, has none. */
    fun ppqAccount(widgetId: String?): HubPpqAccount = widgetId?.let(ppq::get) ?: HubPpqAccount()

    val selectedVariant: HubWidgetVariant?
        get() = selectedDefinition?.variants?.firstOrNull { it.id == editor?.variantId }
}

internal fun HubWidgetEditor.shortcutAmount(): StoredAmount? {
    val digits = CurrencyCatalog.infoFor(currencyCode).fractionDigits
    if (amountInput.hasFractionForWholeCurrency(digits)) return null
    val minor = amountInput.parseMinorAmount(digits)?.takeIf { it > 0 } ?: return null
    return StoredAmount(minor, currencyCode)
}

internal fun WidgetHubState.editorValidationError(): HubWidgetError? {
    val editor = editor ?: return HubWidgetError.Unavailable
    val variant = selectedVariant ?: return HubWidgetError.Unavailable
    if (editor.kind == HubWidgetKind.Contacts || editor.kind == HubWidgetKind.Shortcut) {
        if (editor.contactIds.isEmpty()) return HubWidgetError.SelectContacts
        if (editor.contactIds.size > variant.capacity) return HubWidgetError.TooManyContacts
    }
    if (editor.kind == HubWidgetKind.Shortcut && editor.shortcutAmount() == null) {
        return HubWidgetError.InvalidAmount
    }
    if (editor.kind == HubWidgetKind.Ppq && editor.apiKeyInput.isBlank() &&
        !ppqAccount(editor.existingWidgetId).connected
    ) {
        return HubWidgetError.ApiKeyRequired
    }
    if (editor.kind == HubWidgetKind.Claro && claroPhone(editor.phoneInput) == null) {
        return HubWidgetError.PhoneRequired
    }
    return null
}

object LocalHubWidgets {
    val Single = HubWidgetVariant("single", 1, 1, 1)
    val Row = HubWidgetVariant("row", 2, 1, 4)
    val Card = HubWidgetVariant("card", 2, 2, 6)
    val definitions = listOf(
        HubWidgetDefinition("local.contacts", HubWidgetKind.Contacts, listOf(Single, Row, Card)),
        HubWidgetDefinition("local.shortcut", HubWidgetKind.Shortcut, listOf(Single)),
        HubWidgetDefinition("local.favorites", HubWidgetKind.Favorites, listOf(Row, Card)),
        HubWidgetDefinition("local.recents", HubWidgetKind.Recents, listOf(Row, Card)),
        HubWidgetDefinition("local.rate", HubWidgetKind.Rate, listOf(Single, Row, Card)),
        HubWidgetDefinition("local.sats", HubWidgetKind.Sats, listOf(Single, Row)),
        HubWidgetDefinition("local.ppq", HubWidgetKind.Ppq, listOf(Single, Row)),
        HubWidgetDefinition("local.claro", HubWidgetKind.Claro, listOf(Single, Row))
    )

    /** The currencies a Rate or Sats widget can quote bitcoin in. */
    val fiatCodes: List<String> = CurrencyCatalog.supported
        .filter { it.currency is DisplayCurrency.Fiat }
        .map { it.code }
}
