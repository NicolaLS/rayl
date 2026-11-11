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
import androidx.compose.material.icons.filled.Group
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
    val glow = when (tile.kind) {
        HubWidgetKind.Sats -> BitcoinOrange
        HubWidgetKind.Ppq -> PpqEmber
        else -> null
    }
    Box(
        // Every tile is the same dark face, so a person, an amount and a price sit side by side
        // as one family. Colour comes from the portraits and from a widget's own glow.
        modifier.clip(HubTileShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF29294D), Color(0xFF101520))))
            .drawBehind {
                // A trace of the widget's own colour, so a row of them is not one slab.
                if (glow != null) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(glow.copy(alpha = 0.26f), glow.copy(alpha = 0f)),
                            center = Offset(size.width, 0f),
                            radius = 190.dp.toPx()
                        )
                    )
                }
            }
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

private val Quiet = Color.White.copy(alpha = 0.8f)

/** What every people tile starts with, in the place and styles of a figure tile's header. */
@Composable
private fun PeopleHeader(tile: HubWidgetTile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(26.dp).background(Color.White.copy(alpha = 0.14f), CircleShape)
        ) {
            Icon(
                when (tile.kind) {
                    HubWidgetKind.Favorites -> Icons.Filled.Star
                    HubWidgetKind.Recents -> Icons.Filled.AccessTime
                    HubWidgetKind.Shortcut -> Icons.Filled.Bolt
                    else -> Icons.Filled.Group
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            tile.title?.takeIf(String::isNotBlank) ?: tile.kind.label(),
            style = MaterialTheme.typography.labelLarge,
            color = Quiet,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyFace(tile: HubWidgetTile) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        PeopleHeader(tile)
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(
                when (tile.kind) {
                    HubWidgetKind.Favorites -> R.string.hub_widget_favorites_empty
                    HubWidgetKind.Recents -> R.string.hub_widget_recent_empty
                    else -> R.string.hub_widget_contacts_empty
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = Quiet,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * One person, laid out like a shortcut: the portrait where the header is, the name where the
 * figure is, and the address as its caption.
 */
@Composable
private fun SingleContactFace(tile: HubWidgetTile, onPay: (String) -> Unit, interactive: Boolean) {
    val person = tile.people.firstOrNull()
    val name = person?.title ?: stringResource(R.string.hub_widget_contact_placeholder)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().payAction(person, interactive, onPay).padding(14.dp)
    ) {
        ContactAvatar(person?.title, size = 44.dp)
        Spacer(Modifier.weight(1f))
        Text(
            name,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                lineHeight = 26.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            tile.title?.takeIf { it.isNotBlank() && it != name }
                ?: person?.address?.takeIf { it.isNotBlank() && it != name }
                ?: tile.kind.label(),
            style = MaterialTheme.typography.bodyMedium,
            color = Quiet,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis
        )
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

/**
 * Several people under the tile's header. A few are listed line by line with what they were last
 * paid; more than that stand side by side. Portraits have one size per tile height, so two
 * collections next to each other match.
 */
@Composable
private fun ContactCollectionFace(
    tile: HubWidgetTile,
    onPay: (String) -> Unit,
    preview: Boolean,
    interactive: Boolean,
    compactPreview: Boolean
) {
    val card = tile.variant.rows >= 2 && !compactPreview
    val count = when {
        compactPreview -> 2
        preview -> tile.variant.capacity
        else -> tile.people.size
    }
    val listed = compactPreview || count <= if (card) 4 else 2
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(14.dp)
    ) {
        PeopleHeader(tile)
        if (listed) {
            // A wide row ends on the same line as the figures beside it; a card reads from the top.
            if (!card) Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(if (card) 12.dp else 8.dp)) {
                repeat(count) { index ->
                    ContactLine(
                        tile.people.getOrNull(index),
                        onPay,
                        interactive,
                        index,
                        avatar = if (card) 44.dp else 36.dp
                    )
                }
            }
        } else {
            val columns = if (card) 3 else 4
            val reserveAmount = tile.people.any { it.amount != null }
            repeat(if (card) 2 else 1) { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                Modifier.weight(1f).fillMaxSize(),
                                portrait = if (card) 64.dp else 48.dp
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactLine(
    person: HubWidgetPerson?,
    onPay: (String) -> Unit,
    interactive: Boolean,
    seed: Int,
    avatar: Dp
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .payAction(person, interactive, onPay)
    ) {
        ContactAvatar(person?.title, size = avatar, seed = seed)
        Text(
            person?.title ?: stringResource(R.string.hub_widget_contact_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        person?.formattedAmount()?.let { amount ->
            Text(
                amount,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = Quiet,
                maxLines = 1
            )
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
    modifier: Modifier,
    portrait: Dp
) {
    BoxWithConstraints(
        modifier = modifier.clip(MaterialTheme.shapes.medium).payAction(person, interactive, onPay),
        contentAlignment = Alignment.BottomStart
    ) {
        val textHeight = (if (reserveAmount) 38.dp else 22.dp) * LocalDensity.current.fontScale
        // The portrait keeps its size and only gives way when large text needs the room.
        val avatar = minOf(maxWidth, maxHeight - textHeight - 6.dp).coerceIn(20.dp, portrait)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ContactAvatar(person?.title, size = avatar, seed = seed)
            Column {
                Text(
                    person?.title ?: stringResource(R.string.hub_widget_contact_placeholder),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (reserveAmount) {
                    Text(
                        person?.formattedAmount().orEmpty(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
    // Four quiet relatives of the tile's navy: enough to tell people apart, never louder than a
    // figure.
    val colors = listOf(
        Color(0xFF6BB0BC) to Color(0xFF4D8E9C),
        Color(0xFF948EDC) to Color(0xFF746DC0),
        Color(0xFF76A0DA) to Color(0xFF557CBB),
        Color(0xFFCF93A8) to Color(0xFFAE6F89)
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
