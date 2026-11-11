package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetPerson
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetTile
import xyz.lilsus.raylsuite.feature.paymenthub.R
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubState

internal val HubTileShape = RoundedCornerShape(28.dp)

/** Gallery and canvas share the same faces. Preview values never become payment actions. */
@Composable
internal fun HubWidgetFace(
    tile: HubWidgetTile,
    state: WidgetHubState,
    onPay: (String) -> Unit,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    interactive: Boolean = true,
    compactPreview: Boolean = false,
    /** A tap on a widget that opens something of its own instead of paying a person. */
    onOpen: (HubWidgetTile) -> Unit = {}
) {
    val shortcut = tile.kind == HubWidgetKind.Shortcut
    Box(
        modifier.clip(HubTileShape).then(
            // The dark face is for the one figure a widget is about: an amount or a price.
            if (shortcut || tile.kind == HubWidgetKind.Rate) {
                Modifier.background(
                    Brush.verticalGradient(listOf(Color(0xFF29294D), Color(0xFF101520)))
                )
            } else {
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest)
            }
        )
    ) {
        when {
            tile.kind.showsInformation -> HubInfoFace(tile, state, preview, interactive, onOpen)
            tile.people.isEmpty() && !preview -> EmptyFace(tile)
            shortcut -> ShortcutFace(tile, onPay, interactive, preview)
            tile.variant.capacity == 1 -> SingleContactFace(tile, onPay, interactive)
            else -> ContactCollectionFace(tile, onPay, preview, interactive, compactPreview)
        }
    }
}

@Composable
private fun EmptyFace(tile: HubWidgetTile) {
    val contentColor = if (tile.kind == HubWidgetKind.Shortcut) {
        Color.White.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(20.dp)
    ) {
        Icon(
            when (tile.kind) {
                HubWidgetKind.Favorites -> Icons.Filled.Star
                HubWidgetKind.Recents -> Icons.Filled.AccessTime
                else -> Icons.Filled.Person
            },
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(28.dp)
        )
        Text(
            tile.title?.takeIf(String::isNotBlank) ?: tile.kind.label(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
        Text(
            stringResource(
                when (tile.kind) {
                    HubWidgetKind.Favorites -> R.string.hub_widget_favorites_empty
                    HubWidgetKind.Recents -> R.string.hub_widget_recent_empty
                    else -> R.string.hub_widget_contacts_empty
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SingleContactFace(tile: HubWidgetTile, onPay: (String) -> Unit, interactive: Boolean) {
    val person = tile.people.firstOrNull()
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().payAction(person, interactive, onPay).padding(16.dp)
    ) {
        val textBudget = 42.dp * LocalDensity.current.fontScale
        val avatar = minOf(maxWidth * 0.66f, maxHeight - textBudget).coerceIn(24.dp, 112.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ContactAvatar(person?.title, size = avatar)
            Text(
                person?.title ?: stringResource(R.string.hub_widget_contact_placeholder),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ShortcutFace(
    tile: HubWidgetTile,
    onPay: (String) -> Unit,
    interactive: Boolean,
    preview: Boolean
) {
    val person = tile.people.firstOrNull()
    val amount = person?.formattedAmount() ?: if (preview) {
        rememberAmountFormatter().format(
            DisplayAmount(500, CurrencyCatalog.infoFor("USD").currency)
        )
    } else {
        ""
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().payAction(person, interactive, onPay).padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            ContactAvatar(person?.contactTitle, size = 26.dp)
            Text(
                person?.contactTitle ?: stringResource(R.string.hub_widget_contact_placeholder),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(
                    26.dp
                ).background(Color.White.copy(alpha = 0.14f), CircleShape)
            ) {
                Icon(Icons.Filled.Bolt, null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            amount,
            color = Color.White,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
                letterSpacing = (-1).sp
            ),
            autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 36.sp),
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            tile.title?.takeIf { it.isNotBlank() && it != person?.contactTitle }
                ?: tile.kind.label(),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ContactCollectionFace(
    tile: HubWidgetTile,
    onPay: (String) -> Unit,
    preview: Boolean,
    interactive: Boolean,
    compactPreview: Boolean
) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        if (tile.kind != HubWidgetKind.Contacts || !tile.title.isNullOrBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                if (tile.kind == HubWidgetKind.Favorites || tile.kind == HubWidgetKind.Recents) {
                    Icon(
                        if (tile.kind ==
                            HubWidgetKind.Favorites
                        ) {
                            Icons.Filled.Star
                        } else {
                            Icons.Filled.AccessTime
                        },
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    tile.title?.takeIf(String::isNotBlank) ?: tile.kind.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        val columns = when {
            compactPreview -> 2
            tile.variant.rows >= 2 -> 3
            else -> 4
        }
        val rows = if (compactPreview || tile.variant.rows >= 2) 2 else 1
        val reserveAmount = tile.people.any { it.amount != null }
        repeat(rows) { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                repeat(columns) { column ->
                    val index = row * columns + column
                    val person = tile.people.getOrNull(index)
                    if (person != null || preview) {
                        ContactCell(
                            person,
                            onPay,
                            interactive,
                            reserveAmount,
                            index,
                            Modifier.weight(1f).fillMaxSize()
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactCell(
    person: HubWidgetPerson?,
    onPay: (String) -> Unit,
    interactive: Boolean,
    reserveAmount: Boolean,
    seed: Int,
    modifier: Modifier
) {
    BoxWithConstraints(
        modifier = modifier.clip(MaterialTheme.shapes.medium).payAction(person, interactive, onPay),
        contentAlignment = Alignment.Center
    ) {
        val textHeight = (if (reserveAmount) 44.dp else 26.dp) * LocalDensity.current.fontScale
        val avatar = minOf(maxWidth - 4.dp, maxHeight - textHeight - 8.dp).coerceIn(20.dp, 88.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            ContactAvatar(person?.title, size = avatar, seed = seed)
            Text(
                person?.title ?: stringResource(R.string.hub_widget_contact_placeholder),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (reserveAmount) {
                Text(
                    person?.formattedAmount().orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HubWidgetPerson.formattedAmount(): String? = amount?.let {
    rememberAmountFormatter().format(
        DisplayAmount(it.minor, CurrencyCatalog.infoFor(it.normalizedCurrencyCode).currency)
    )
}

@Composable
private fun Modifier.payAction(
    person: HubWidgetPerson?,
    interactive: Boolean,
    onPay: (String) -> Unit
): Modifier {
    if (person == null || !interactive) return this
    val payLabel = stringResource(R.string.hub_canvas_pay, person.title)
    return clickable(role = Role.Button, onClickLabel = payLabel) { onPay(person.actionId) }
}

@Composable
internal fun ContactAvatar(
    title: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    seed: Int = 0
) {
    val initials = title?.trim()?.split(Regex("\\s+"))?.filter(String::isNotEmpty)
        ?.take(2)?.joinToString("") {
            it.substring(0, it.offsetByCodePoints(0, 1)).uppercase()
        }.orEmpty()
    val colors = listOf(
        Color(0xFF65D5DF) to Color(0xFF43B5C5),
        Color(0xFF9381FF) to Color(0xFF7160DF),
        Color(0xFF73A7F3) to Color(0xFF4D7AD1),
        Color(0xFFF09BAC) to Color(0xFFD46E91)
    )
    val colorIndex = if (initials.isEmpty()) seed else initials.sumOf { it.code }
    val color = colors[colorIndex.mod(colors.size)]
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size)
            .background(Brush.verticalGradient(listOf(color.first, color.second)), CircleShape)
            .clearAndSetSemantics {}
    ) {
        if (initials.isBlank()) {
            Icon(
                Icons.Filled.Person,
                null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.45f)
            )
        } else {
            Text(
                initials,
                fontSize = with(LocalDensity.current) { (size * 0.36f).toSp() },
                // The size ignores the font scale, so the inherited line height must too, or a
                // large text setting pushes the initials out of a small portrait.
                style = LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified),
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}
