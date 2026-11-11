package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
        HubWidgetKind.Claro -> ClaroRed
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
            tile.people.isEmpty() && !preview -> EmptyFace(tile, interactive, onOpen)
            shortcut -> ShortcutFace(tile, onPay, interactive, preview)
            tile.variant.capacity == 1 -> SingleContactFace(tile, onPay, interactive)
            else -> ContactCollectionFace(tile, onPay, preview, interactive, compactPreview)
        }
    }
}

internal val Quiet = Color.White.copy(alpha = 0.8f)

/** A small tile centres what it shows; a wide one keeps it on the leading edge. */
internal val HubWidgetTile.small: Boolean get() = variant.columns == 1

/**
 * What every tile starts with: what it is about, and what a tap does in the far corner. Only a
 * service brings its own mark.
 */
@Composable
internal fun TileHeader(
    title: String,
    mark: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp)
    ) {
        mark?.invoke()
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = Quiet,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** The one line under a tile's figure or portrait. */
@Composable
internal fun TileCaption(text: String, small: Boolean, color: Color = Quiet) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        textAlign = if (small) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Quiet words in place of content a tile does not have yet. */
@Composable
internal fun ColumnScope.TileHint(text: String, small: Boolean) {
    Box(
        contentAlignment = if (small) Alignment.Center else Alignment.CenterStart,
        modifier = Modifier.weight(1f).fillMaxWidth()
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = if (small) TextAlign.Center else TextAlign.Start,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun TileFigure(text: String, small: Boolean) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.headlineLarge.copy(
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
            letterSpacing = (-1).sp
        ),
        autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 36.sp),
        maxLines = 1,
        textAlign = if (small) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Stands where a figure will be while it is not known, like a system widget's placeholder. */
@Composable
internal fun TileFigurePlaceholder(small: Boolean) {
    Box(
        contentAlignment = if (small) Alignment.Center else Alignment.CenterStart,
        modifier = Modifier.fillMaxWidth().height(44.dp)
    ) {
        Box(
            Modifier.size(width = 104.dp, height = 30.dp)
                .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(9.dp))
        )
    }
}

@Composable
private fun EmptyFace(tile: HubWidgetTile, interactive: Boolean, onOpen: (HubWidgetTile) -> Unit) {
    // A widget that was added but not set up yet opens its settings.
    val needsSetup = tile.kind == HubWidgetKind.Contacts || tile.kind == HubWidgetKind.Shortcut
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
            .then(
                if (needsSetup && interactive) {
                    Modifier.clickable(role = Role.Button) { onOpen(tile) }
                } else {
                    Modifier
                }
            )
            .padding(14.dp)
    ) {
        TileHeader(tile.title?.takeIf(String::isNotBlank) ?: tile.kind.label())
        TileHint(
            stringResource(
                when (tile.kind) {
                    HubWidgetKind.Favorites -> R.string.hub_widget_favorites_empty
                    HubWidgetKind.Recents -> R.string.hub_widget_recent_empty
                    else -> R.string.hub_widget_needs_setup
                }
            ),
            tile.small
        )
    }
}

/**
 * One person, framed like a shortcut: the name as the tile's title, the portrait where the figure
 * is, and the address on the bottom line.
 */
@Composable
private fun SingleContactFace(tile: HubWidgetTile, onPay: (String) -> Unit, interactive: Boolean) {
    val person = tile.people.firstOrNull()
    val name = person?.title ?: stringResource(R.string.hub_widget_contact_placeholder)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().payAction(person, interactive, onPay).padding(14.dp)
    ) {
        TileHeader(tile.title?.takeIf(String::isNotBlank) ?: name)
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            ContactAvatar(person?.title, size = minOf(maxWidth, maxHeight).coerceIn(24.dp, 72.dp))
        }
        TileCaption(
            person?.address?.takeIf { it.isNotBlank() && it != name } ?: tile.kind.label(),
            small = true
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
        TileHeader(tile.title?.takeIf(String::isNotBlank) ?: tile.kind.label()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(26.dp)
                    .background(Color.White.copy(alpha = 0.14f), CircleShape)
            ) {
                Icon(Icons.Filled.Bolt, null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        TileFigure(amount, tile.small)
        Spacer(Modifier.weight(1f))
        // Where the money goes, on the line where a contact shows its address.
        TileCaption(
            person?.address?.takeIf(String::isNotBlank)
                ?: person?.contactTitle
                ?: stringResource(R.string.hub_widget_contact_placeholder),
            tile.small
        )
    }
}

/**
 * Several people under the tile's title, always as lines of portrait and name so every collection
 * reads the same. A few get the tile's width and show what they were last paid at the far end;
 * more share it in two columns.
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
        else -> tile.people.size.coerceAtMost(if (card) 6 else 4)
    }
    val paired = count > if (card) 3 else 2
    val avatar = if (card) 44.dp else 34.dp
    val gap = if (card) 12.dp else 6.dp
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(14.dp)
    ) {
        TileHeader(tile.title?.takeIf(String::isNotBlank) ?: tile.kind.label())
        Column(
            verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            if (paired) {
                repeat((count + 1) / 2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(2) { column ->
                            val index = row * 2 + column
                            if (index < count) {
                                ContactLine(
                                    tile.people.getOrNull(index),
                                    onPay,
                                    interactive,
                                    index,
                                    avatar,
                                    stacked = true,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                repeat(count) { index ->
                    ContactLine(
                        tile.people.getOrNull(index),
                        onPay,
                        interactive,
                        index,
                        avatar,
                        stacked = false,
                        modifier = Modifier.fillMaxWidth()
                    )
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
    avatar: Dp,
    stacked: Boolean,
    modifier: Modifier
) {
    val amount = person?.formattedAmount()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.clip(MaterialTheme.shapes.medium).payAction(person, interactive, onPay)
    ) {
        ContactAvatar(person?.title, size = avatar, seed = seed)
        Column(Modifier.weight(1f)) {
            Text(
                person?.title ?: stringResource(R.string.hub_widget_contact_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (stacked && amount != null) {
                Text(
                    amount,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!stacked && amount != null) {
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
