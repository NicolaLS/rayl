package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetTile
import xyz.lilsus.raylsuite.feature.paymenthub.R
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubState
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqAccount
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqProblem
import xyz.lilsus.raylsuite.feature.paymenthub.live.changeLabel
import xyz.lilsus.raylsuite.feature.paymenthub.live.forFace
import xyz.lilsus.raylsuite.feature.paymenthub.live.priceLabel
import xyz.lilsus.raylsuite.feature.paymenthub.live.satsLabel

/** Bitcoin's own orange marks the widgets that are about bitcoin rather than a person. */
internal val BitcoinOrange = Color(0xFFF7931A)
internal val PpqEmber = Color(0xFFFF5C33)
private val RateUp = Color(0xFF5FE08D)
private val RateDown = Color(0xFFFF8A80)
private const val PPQ_NAME = "PPQ.AI"

/** Everything a figure face shows, worked out per widget kind. */
private class FigureContent(
    val headline: String,
    val value: String?,
    val caption: String,
    val captionColor: Color,
    val chart: List<Float> = emptyList(),
    /** Names what a tap does; null when a tap does nothing. */
    val action: String? = null,
    val actionIcon: ImageVector = Icons.Filled.Add
)

/**
 * Faces of the widgets that show live information instead of people to pay. They share the
 * shortcut's frame, so tiles side by side line up: what the figure is about on top, then the
 * figure and one caption line on the same baselines.
 */
@Composable
internal fun HubInfoFace(
    tile: HubWidgetTile,
    state: WidgetHubState,
    preview: Boolean,
    interactive: Boolean,
    onOpen: (HubWidgetTile) -> Unit
) {
    val plain = Color.White.copy(alpha = 0.8f)
    val formatter = rememberAmountFormatter()
    val code = tile.currencyCode ?: state.defaultFiatCode
    val rate = state.rates[code].forFace(code, preview)
    val unavailable = if (rate == null && state.ratesUnavailable) {
        stringResource(R.string.hub_rate_unavailable)
    } else {
        null
    }
    val content = when (tile.kind) {
        HubWidgetKind.Rate -> {
            val change = rate?.changeLabel()
            FigureContent(
                headline = "BTC",
                value = rate?.priceLabel(formatter),
                // The day's move once the chart is known; until then what the price is in.
                caption = unavailable
                    ?: change?.let { it + " · " + stringResource(R.string.hub_rate_day) }
                    ?: code,
                captionColor = when {
                    unavailable != null || change == null -> plain
                    (rate.changePercent ?: 0.0) >= 0 -> RateUp
                    else -> RateDown
                },
                chart = remember(rate) { rate?.chart().orEmpty() }
            )
        }

        HubWidgetKind.Sats -> FigureContent(
            headline = "1 $code",
            value = rate?.satsLabel(),
            caption = unavailable ?: stringResource(R.string.hub_sats_unit),
            captionColor = plain,
            action = stringResource(R.string.hub_converter_title).takeIf { rate != null },
            actionIcon = Icons.Filled.SwapVert
        )

        else -> {
            val account = if (preview && state.ppq.balanceUsd == null) {
                HubPpqAccount(connected = true, balanceUsd = 12.5)
            } else {
                state.ppq
            }
            val needsKey = !account.connected || account.problem == HubPpqProblem.InvalidKey
            FigureContent(
                headline = PPQ_NAME,
                value = account.balanceAmount()?.let { formatter.format(it) },
                caption = stringResource(
                    when {
                        !account.connected -> R.string.hub_ppq_add_key
                        account.problem == HubPpqProblem.InvalidKey -> R.string.hub_ppq_key_rejected
                        account.problem == HubPpqProblem.Unreachable -> R.string.hub_ppq_not_updated
                        else -> R.string.hub_ppq_balance
                    }
                ),
                captionColor = when {
                    !account.connected -> Color.White
                    account.problem == HubPpqProblem.InvalidKey -> RateDown
                    else -> plain
                },
                action = stringResource(
                    if (needsKey) R.string.hub_ppq_add_key else R.string.hub_ppq_top_up
                )
            )
        }
    }
    FigureFace(tile, content, interactive, onOpen)
}

@Composable
private fun FigureFace(
    tile: HubWidgetTile,
    content: FigureContent,
    interactive: Boolean,
    onOpen: (HubWidgetTile) -> Unit
) {
    val description = listOfNotNull(
        tile.kind.label().takeIf { it != content.headline },
        content.headline,
        content.value,
        content.caption
    ).joinToString()
    val action = content.action
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
            .then(
                if (interactive && action != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = action) { onOpen(tile) }
                } else {
                    Modifier
                }
            )
            .clearAndSetSemantics { contentDescription = description }
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            FigureBadge(tile.kind)
            Text(
                content.headline,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (action !=
                null
            ) {
                FigureAction(action, content.actionIcon, wide = tile.variant.columns > 1)
            }
        }
        if (content.chart.size >= 2) {
            // The day's prices fill the room between the two, edge to edge.
            HubSparkline(
                content.chart,
                Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)
                    .layout { measurable, constraints ->
                        val bleed = 14.dp.roundToPx()
                        val placeable = measurable.measure(
                            constraints.copy(
                                minWidth = constraints.maxWidth + bleed * 2,
                                maxWidth = constraints.maxWidth + bleed * 2
                            )
                        )
                        layout(constraints.maxWidth, placeable.height) {
                            placeable.place(-bleed, 0)
                        }
                    }
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        // The same figure and caption styles as a shortcut's amount and name.
        Text(
            content.value ?: "—",
            color = Color.White.copy(alpha = if (content.value == null) 0.4f else 1f),
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
            content.caption,
            style = MaterialTheme.typography.bodyMedium,
            color = content.captionColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A portrait-sized mark where a shortcut shows who is paid. */
@Composable
private fun FigureBadge(kind: HubWidgetKind) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(26.dp).background(
            if (kind == HubWidgetKind.Ppq) Color.White else BitcoinOrange,
            CircleShape
        )
    ) {
        if (kind == HubWidgetKind.Ppq) {
            Image(
                painterResource(R.drawable.hub_ppq),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Icon(
                if (kind == HubWidgetKind.Sats) Icons.Filled.Bolt else Icons.Filled.CurrencyBitcoin,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/** What a tap does, in the corner where a shortcut shows its bolt. A wide tile names it. */
@Composable
private fun FigureAction(title: String, icon: ImageVector, wide: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        modifier = Modifier.height(26.dp)
            .then(if (wide) Modifier else Modifier.width(26.dp))
            .background(Color.White.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = if (wide) 10.dp else 0.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        if (wide) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1
            )
        }
    }
}

/** The day's prices as one soft line that runs off both edges of the tile, fading out below. */
@Composable
private fun HubSparkline(points: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        // Room for the stroke above and below.
        val top = stroke
        val drop = size.height - top - stroke
        val step = size.width / (points.size - 1)
        fun at(index: Int) = Offset(step * index, top + (1f - points[index]) * drop)
        val line = Path().apply {
            moveTo(at(0).x, at(0).y)
            for (index in 1 until points.size) {
                val from = at(index - 1)
                val to = at(index)
                val middle = (from.x + to.x) / 2
                cubicTo(middle, from.y, middle, to.y, to.x, to.y)
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0f))
            )
        )
        drawPath(
            line,
            Color.White.copy(alpha = 0.92f),
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
