package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetDefinition
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetError
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetVariant
import xyz.lilsus.raylsuite.feature.paymenthub.R

@Composable
internal fun HubWidgetKind.label(): String = stringResource(
    when (this) {
        HubWidgetKind.Contacts -> R.string.hub_widget_contacts
        HubWidgetKind.Shortcut -> R.string.hub_widget_shortcut
        HubWidgetKind.Favorites -> R.string.hub_widget_favorites
        HubWidgetKind.Recents -> R.string.hub_widget_recents
        HubWidgetKind.Rate -> R.string.hub_widget_rate
        HubWidgetKind.Sats -> R.string.hub_widget_sats
        HubWidgetKind.Ppq -> R.string.hub_widget_ppq
        HubWidgetKind.Claro -> R.string.hub_widget_claro
    }
)

@Composable
internal fun HubWidgetDefinition.label(): String = title ?: kind.label()

@Composable
internal fun HubWidgetDefinition.body(): String = description ?: when (kind) {
    HubWidgetKind.Contacts -> stringResource(R.string.hub_widget_contacts_body)
    HubWidgetKind.Shortcut -> stringResource(R.string.hub_widget_shortcut_body)
    HubWidgetKind.Favorites -> stringResource(R.string.hub_widget_favorites_body)
    HubWidgetKind.Recents -> stringResource(R.string.hub_widget_recents_body)
    HubWidgetKind.Rate -> stringResource(R.string.hub_widget_rate_body)
    HubWidgetKind.Sats -> stringResource(R.string.hub_widget_sats_body)
    HubWidgetKind.Ppq -> stringResource(R.string.hub_widget_ppq_body)
    HubWidgetKind.Claro -> stringResource(R.string.hub_widget_claro_body)
}

/** Layouts are named after their contacts, except where a widget shows no contacts. */
@Composable
internal fun HubWidgetVariant.label(kind: HubWidgetKind): String = title ?: stringResource(
    when {
        columns == 1 && rows == 1 ->
            if (kind.showsInformation) R.string.hub_widget_small else R.string.hub_widget_single

        rows == 1 ->
            if (kind.showsInformation) R.string.hub_widget_wide else R.string.hub_widget_row

        else -> if (kind.showsInformation) R.string.hub_widget_large else R.string.hub_widget_card
    }
)

@Composable
internal fun HubWidgetVariant.body(kind: HubWidgetKind): String = stringResource(
    when {
        columns == 1 && rows == 1 -> if (kind.showsInformation) {
            R.string.hub_widget_small_body
        } else {
            R.string.hub_widget_single_body
        }

        rows == 1 -> if (kind.showsInformation) {
            R.string.hub_widget_wide_body
        } else {
            R.string.hub_widget_row_body
        }

        else -> if (kind.showsInformation) {
            R.string.hub_widget_large_body
        } else {
            R.string.hub_widget_card_body
        }
    }
)

@Composable
internal fun HubWidgetError.label(capacity: Int): String = when (this) {
    HubWidgetError.ContactNameRequired -> stringResource(R.string.hub_error_enter_title)
    HubWidgetError.InvalidAddress -> stringResource(R.string.hub_error_invalid_address)
    HubWidgetError.SelectContacts -> stringResource(R.string.hub_widget_select_contacts_error)
    HubWidgetError.TooManyContacts -> stringResource(R.string.hub_widget_choose_contacts, capacity)
    HubWidgetError.InvalidAmount -> stringResource(R.string.hub_error_enter_amount)
    HubWidgetError.Unavailable -> stringResource(R.string.hub_widget_unavailable)
    HubWidgetError.SaveFailed -> stringResource(R.string.hub_widget_error_save)
    HubWidgetError.ApiKeyRequired -> stringResource(R.string.hub_ppq_error_enter_key)
    HubWidgetError.InvalidApiKey -> stringResource(R.string.hub_ppq_error_key)
    HubWidgetError.ServiceUnreachable -> stringResource(R.string.hub_ppq_error_unreachable)
    HubWidgetError.PhoneRequired -> stringResource(R.string.hub_claro_error_phone)
}
