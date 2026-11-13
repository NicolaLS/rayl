package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetTile
import xyz.lilsus.raylsuite.feature.paymenthub.R
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubState
import xyz.lilsus.raylsuite.feature.paymenthub.claro.claroPhoneLabel
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqAccount
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubPpqProblem
import xyz.lilsus.raylsuite.feature.paymenthub.live.changeLabel
import xyz.lilsus.raylsuite.feature.paymenthub.live.forFace
import xyz.lilsus.raylsuite.feature.paymenthub.live.priceLabel
import xyz.lilsus.raylsuite.feature.paymenthub.live.satsLabel

/** Bitcoin's own orange marks the widgets that are about bitcoin rather than a person. */
internal val BitcoinOrange = Color(0xFFF7931A)
internal val PpqEmber = Color(0xFFFF5C33)
internal val ClaroRed = Color(0xFFDA291C)
private const val PPQ_NAME = "PPQ.AI"
private const val CLARO_NAME = "Claro"

/** The number a picture of the Claro widget shows. */
private const val CLARO_SAMPLE_PHONE = "71234567"

/** Everything a figure face shows, worked out per widget kind. */
private class FigureContent(
    val headline: String,
    val value: String?,
    val caption: String,
    val captionColor: Color,
    val chart: List<Float> = emptyList(),
    /** Names what a tap does; null when a tap does nothing. */
    val action: String? = null,
    /** Says how to set the widget up, in place of a figure it cannot show before that. */
    val hint: String? = null
)

/**
 * Faces of the widgets that show live information instead of people to pay. They share the
 * shortcut's frame, so tiles side by side line up: what the figure is about on top, the figure in
 * the middle, and one caption on the bottom line.
 */
@Composable
internal fun HubInfoFace(
    tile: HubWidgetTile,
    state: WidgetHubState,
    preview: Boolean,
    interactive: Boolean,
    onOpen: (HubWidgetTile) -> Unit
) {
    val face = hubFaceColors()
    val plain = face.quiet
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
                headline = stringResource(R.string.hub_rate_headline),
                value = rate?.priceLabel(formatter),
                // The day's move once the chart is known; until then what the price is in.
                caption = unavailable
                    ?: change?.let { it + " · " + stringResource(R.string.hub_rate_day) }
                    ?: code,
                captionColor = when {
                    unavailable != null || change == null -> plain
                    (rate.changePercent ?: 0.0) >= 0 -> face.up
                    else -> face.down
                },
                chart = remember(rate) { rate?.chart().orEmpty() },
                action = stringResource(R.string.hub_converter_title).takeIf { rate != null }
            )
        }

        HubWidgetKind.Sats -> FigureContent(
            headline = "1 $code",
            value = rate?.satsLabel(),
            caption = unavailable ?: stringResource(R.string.hub_sats_unit),
            captionColor = plain,
            action = stringResource(R.string.hub_converter_title).takeIf { rate != null }
        )

        HubWidgetKind.Claro -> {
            val phone = tile.phone ?: CLARO_SAMPLE_PHONE.takeIf { preview }
            FigureContent(
                headline = CLARO_NAME,
                value = phone?.let(::claroPhoneLabel),
                caption = stringResource(R.string.hub_claro_caption),
                captionColor = plain,
                action = stringResource(R.string.hub_claro_top_up),
                hint = stringResource(R.string.hub_widget_needs_setup).takeIf { phone == null }
            )
        }

        else -> {
            val account = if (preview && state.ppqAccount(tile.id).balanceUsd == null) {
                HubPpqAccount(connected = true, balanceUsd = 12.5)
            } else {
                state.ppqAccount(tile.id)
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
                    !account.connected -> face.ink
                    account.problem == HubPpqProblem.InvalidKey -> face.down
                    else -> plain
                },
                action = stringResource(
                    if (needsKey) R.string.hub_ppq_add_key else R.string.hub_ppq_top_up
                ),
                hint = stringResource(R.string.hub_widget_needs_setup).takeIf { !account.connected }
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
                // A widget that asks to be touched and held to be set up does nothing on a tap.
                if (interactive && action != null && content.hint == null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = action) { onOpen(tile) }
                } else {
                    Modifier
                }
            )
            .clearAndSetSemantics { contentDescription = description }
            .padding(14.dp)
    ) {
        val small = tile.small
        TileHeader(
            content.headline,
            mark = when (tile.kind) {
                HubWidgetKind.Ppq -> {
                    { ServiceMark(R.drawable.hub_ppq) }
                }

                HubWidgetKind.Claro -> {
                    { ServiceMark(R.drawable.hub_claro) }
                }

                else -> null
            }
        )
        if (content.hint != null) {
            TileHint(content.hint, small)
            return@Column
        }
        // A small tile has no room for a chart that shows anything, so it keeps just the figure.
        val charted = content.chart.size >= 2 && !small
        when {
            !charted -> {
                Spacer(Modifier.weight(1f))
                FigureValue(content.value, small)
                Spacer(Modifier.weight(1f))
                TileCaption(content.caption, small, content.captionColor)
            }

            // A row is too short to stack a chart under the figure: it would come out flat, so the
            // chart stands beside the figure and runs off the trailing edge.
            tile.variant.rows == 1 -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    Spacer(Modifier.weight(1f))
                    FigureValue(content.value, small)
                    Spacer(Modifier.weight(1f))
                    TileCaption(content.caption, small, content.captionColor)
                }
                HubSparkline(
                    content.chart,
                    Modifier.weight(1f).fillMaxHeight().bleed(leading = false),
                    fadeIn = true
                )
            }

            // The day's prices fill the room below the price, edge to edge.
            else -> {
                FigureValue(content.value, small)
                HubSparkline(
                    content.chart,
                    Modifier.weight(1f).fillMaxWidth().bleed(leading = true)
                )
                TileCaption(content.caption, small, content.captionColor)
            }
        }
    }
}

@Composable
private fun FigureValue(value: String?, small: Boolean) {
    if (value != null) TileFigure(value, small) else TileFigurePlaceholder(small)
}

/** Runs past the tile's 14dp padding to its trailing edge, and to its leading edge too if asked. */
private fun Modifier.bleed(leading: Boolean): Modifier = layout { measurable, constraints ->
    val bleed = 14.dp.roundToPx()
    val width = constraints.maxWidth + bleed * (if (leading) 2 else 1)
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(if (leading) -bleed else 0, 0)
    }
}

/** The service's own mark, the only one a tile's header carries. */
@Composable
internal fun ServiceMark(@DrawableRes mark: Int, size: Dp = 26.dp) {
    val face = hubFaceColors()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).background(Color.White, CircleShape)
            // By day the white disc would melt into a white face without a hairline.
            .then(
                if (face.dark) {
                    Modifier
                } else {
                    Modifier.border(1.dp, face.ink.copy(alpha = 0.08f), CircleShape)
                }
            )
    ) {
        Image(
            painterResource(mark),
            contentDescription = null,
            modifier = Modifier.size(size * 0.7f)
        )
    }
}

/**
 * The day's prices as one soft line that runs off both edges of the tile, fading out below. A chart
 * that starts inside the tile can fade its fill in, so it has no hard edge beside the figure.
 */
@Composable
private fun HubSparkline(points: List<Float>, modifier: Modifier, fadeIn: Boolean = false) {
    val ink = hubFaceColors().ink
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
        val fill = Brush.verticalGradient(listOf(ink.copy(alpha = 0.14f), ink.copy(alpha = 0f)))
        if (fadeIn) {
            // The fill alone goes into a layer, where its leading edge is masked away.
            drawContext.canvas.saveLayer(size.toRect(), Paint())
            drawPath(area, fill)
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.Black),
                    endX = 32.dp.toPx()
                ),
                blendMode = BlendMode.DstIn
            )
            drawContext.canvas.restore()
        } else {
            drawPath(area, fill)
        }
        drawPath(
            line,
            ink.copy(alpha = 0.92f),
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
