package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import xyz.lilsus.raylsuite.feature.paymenthub.live.HubRate
import xyz.lilsus.raylsuite.feature.paymenthub.live.changeLabel
import xyz.lilsus.raylsuite.feature.paymenthub.live.forFace
import xyz.lilsus.raylsuite.feature.paymenthub.live.satsLabel

/** Bitcoin's own orange marks the widgets that are about bitcoin rather than a person. */
internal val BitcoinOrange = Color(0xFFF7931A)
private val RateUp = Color(0xFF5FE08D)
private val RateDown = Color(0xFFFF8A80)
private const val PPQ_NAME = "PPQ.AI"

/** Faces of the widgets that show live information instead of people to pay. */
@Composable
internal fun HubInfoFace(
    tile: HubWidgetTile,
    state: WidgetHubState,
    preview: Boolean,
    interactive: Boolean,
    onOpen: (HubWidgetTile) -> Unit
) {
    val code = tile.currencyCode ?: state.defaultFiatCode
    val rate = state.rates[code].forFace(code, preview)
    val unavailable = rate == null && state.ratesUnavailable
    when (tile.kind) {
        HubWidgetKind.Rate -> RateFace(tile, code, rate, unavailable)

        HubWidgetKind.Sats ->
            SatsFace(tile, code, rate, unavailable, interactive && rate != null, onOpen)

        else -> PpqFace(
            tile = tile,
            account = if (preview && state.ppq.balanceUsd == null) {
                HubPpqAccount(connected = true, balanceUsd = 12.5)
            } else {
                state.ppq
            },
            interactive = interactive,
            onOpen = onOpen
        )
    }
}

@Composable
private fun RateFace(tile: HubWidgetTile, code: String, rate: HubRate?, unavailable: Boolean) {
    val large = tile.variant.rows >= 2
    val price = rate?.let { rememberAmountFormatter().format(it.priceAmount()) }
    val change = rate?.changeLabel()
    val rising = (rate?.changePercent ?: 0.0) >= 0
    val chart = remember(rate) { rate?.chart().orEmpty() }
    val day = stringResource(R.string.hub_rate_day)
    val status = if (unavailable) stringResource(R.string.hub_rate_unavailable) else null
    val description = listOfNotNull(
        tile.kind.label(),
        price,
        change?.let { "$it $day" },
        status
    ).joinToString()
    Box(Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = description }) {
        if (chart.size >= 2) {
            HubSparkline(
                chart,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .fillMaxHeight(if (large) 0.52f else 0.38f)
            )
        }
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(26.dp)
                        .background(BitcoinOrange, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        Icons.Filled.CurrencyBitcoin,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    "BTC",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (change != null) {
                    Text(
                        change,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (rising) RateUp else RateDown,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(if (large) 16.dp else 8.dp))
            Text(
                price ?: "—",
                color = Color.White.copy(alpha = if (price == null) 0.5f else 1f),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                    letterSpacing = (-0.5).sp
                ),
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 14.sp,
                    maxFontSize = if (large) 44.sp else 30.sp
                ),
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            if (status != null || large) {
                Text(
                    status ?: "$code · $day",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The day's prices as one soft line that runs off both edges of the tile. */
@Composable
private fun HubSparkline(points: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        // Room for the stroke above, and for the tile's rounded corners below.
        val top = stroke
        val drop = size.height - top - 12.dp.toPx()
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
                listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.02f))
            )
        )
        drawPath(
            line,
            Color.White.copy(alpha = 0.92f),
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun SatsFace(
    tile: HubWidgetTile,
    code: String,
    rate: HubRate?,
    unavailable: Boolean,
    interactive: Boolean,
    onOpen: (HubWidgetTile) -> Unit
) {
    val sats = rate?.satsLabel()
    val unit = stringResource(R.string.hub_sats_unit)
    val caption = if (unavailable) stringResource(R.string.hub_rate_unavailable) else unit
    InfoColumn(
        description = listOfNotNull(tile.kind.label(), "1 $code", sats, caption).joinToString(),
        actionLabel = stringResource(R.string.hub_converter_title),
        onClick = if (interactive) ({ onOpen(tile) }) else null
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(26.dp)
                    .background(BitcoinOrange.copy(alpha = 0.16f), CircleShape)
            ) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = BitcoinOrange,
                    modifier = Modifier.size(17.dp)
                )
            }
            Text(
                "1 $code",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.SwapVert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        InfoFigure(sats)
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PpqFace(
    tile: HubWidgetTile,
    account: HubPpqAccount,
    interactive: Boolean,
    onOpen: (HubWidgetTile) -> Unit
) {
    val balance = account.balanceAmount()?.let { rememberAmountFormatter().format(it) }
    val needsKey = !account.connected || account.problem == HubPpqProblem.InvalidKey
    val caption = stringResource(
        when {
            !account.connected -> R.string.hub_ppq_add_key
            account.problem == HubPpqProblem.InvalidKey -> R.string.hub_ppq_key_rejected
            account.problem == HubPpqProblem.Unreachable -> R.string.hub_ppq_not_updated
            else -> R.string.hub_ppq_balance
        }
    )
    InfoColumn(
        description = listOfNotNull(PPQ_NAME, balance, caption).joinToString(),
        actionLabel = stringResource(
            if (needsKey) R.string.hub_ppq_add_key else R.string.hub_ppq_top_up
        ),
        onClick = if (interactive) ({ onOpen(tile) }) else null
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Image(
                painterResource(R.drawable.hub_ppq),
                contentDescription = null,
                modifier = Modifier.size(26.dp)
            )
            Text(
                PPQ_NAME,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (!needsKey) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(26.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        InfoFigure(balance)
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                !account.connected -> MaterialTheme.colorScheme.primary
                account.problem == HubPpqProblem.InvalidKey -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The one figure an information widget is about; a dash stands in until it is known. */
@Composable
private fun InfoFigure(value: String?) {
    Text(
        value ?: "—",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (value == null) 0.4f else 1f),
        style = MaterialTheme.typography.headlineLarge.copy(
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
            letterSpacing = (-1).sp
        ),
        autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 36.sp),
        maxLines = 1,
        modifier = Modifier.fillMaxWidth()
    )
}

/** The same frame as a shortcut, read out as one element and tappable as a whole. */
@Composable
private fun InfoColumn(
    description: String,
    actionLabel: String,
    onClick: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = actionLabel,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .clearAndSetSemantics { contentDescription = description }
            .padding(14.dp),
        content = content
    )
}
