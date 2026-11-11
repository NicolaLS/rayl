package xyz.lilsus.raylsuite.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Rayl's mark as on the app icon: four rails of light meeting at one point over deep navy. It
 * keeps the icon's colours in both themes.
 */
@Composable
fun RaylMark(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    Canvas(modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.23f))) {
        val w = this.size.width
        val h = this.size.height
        val origin = Offset(w * ORIGIN_X, h * ORIGIN_Y)
        drawRect(
            Brush.radialGradient(
                colors = listOf(NavyLit, Navy),
                center = origin,
                radius = w * 0.9f
            )
        )
        val light = Brush.linearGradient(
            colors = listOf(RailNear, RailFar),
            start = origin,
            end = Offset(0f, h)
        )
        Rails.forEach { rail ->
            val path = Path().apply {
                moveTo(origin.x, origin.y)
                rail.forEach { (x, y) -> lineTo(w * x, h * y) }
                close()
            }
            drawPath(path, light)
        }
        drawCircle(
            Brush.radialGradient(
                colors = listOf(RailNear.copy(alpha = 0.55f), Color.Transparent),
                center = origin,
                radius = w * 0.16f
            ),
            radius = w * 0.16f,
            center = origin
        )
    }
}

private const val ORIGIN_X = 0.672f
private const val ORIGIN_Y = 0.33f

// Where each rail leaves the tile, as fractions of its size.
private val Rails = listOf(
    listOf(0f to 0.585f, 0f to 0.76f),
    listOf(0f to 0.87f, 0f to 1f, 0.075f to 1f),
    listOf(0.245f to 1f, 0.44f to 1f),
    listOf(0.515f to 1f, 0.66f to 1f)
)

private val Navy = Color(0xFF0A1422)
private val NavyLit = Color(0xFF12284A)
private val RailNear = Color(0xFFD6E6FF)
private val RailFar = Color(0xFF3B7DDB)
