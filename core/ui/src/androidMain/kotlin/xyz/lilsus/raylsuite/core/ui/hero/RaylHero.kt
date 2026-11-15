package xyz.lilsus.raylsuite.core.ui.hero

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import xyz.lilsus.raylsuite.core.ui.R

/**
 * [rejections] counts inputs turned away while the hero shows; each new one plays a short nudge,
 * because the scanner has no viewfinder to show that it saw the code.
 */
@Composable
fun RaylHero(
    phase: RaylHeroPhase,
    modifier: Modifier = Modifier,
    qrContent: RaylHeroQrContent? = null,
    rejections: Int = 0
) {
    val color = when (phase) {
        RaylHeroPhase.Ready -> MaterialTheme.colorScheme.onSurfaceVariant

        RaylHeroPhase.Acknowledged, RaylHeroPhase.Processing ->
            MaterialTheme.colorScheme.primary

        RaylHeroPhase.Succeeded -> MaterialTheme.colorScheme.tertiary

        RaylHeroPhase.Failed -> MaterialTheme.colorScheme.error
    }

    val animationState = rememberHeroAnimationState(
        RaylHeroGeometry.squares,
        RaylHeroGeometry.arcs
    )

    LaunchedEffect(phase) {
        animationState.animatePhase(phase)
    }

    LaunchedEffect(color) {
        animationState.animateColor(color)
    }

    // Only rejections that arrive while the hero is on screen; not the count it was shown with.
    val shownRejections = remember { rejections }
    LaunchedEffect(rejections) {
        if (rejections != shownRejections) animationState.nudge()
    }

    BoxWithConstraints(modifier = modifier) {
        if (qrContent != null) {
            HeroQrCode(
                content = qrContent,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(minOf(maxWidth, maxHeight))
            )
            return@BoxWithConstraints
        }

        val canvasSide = minOf(maxWidth * RaylHeroGeometry.CANVAS_WIDTH_FRACTION, maxHeight)
        Canvas(
            modifier = Modifier
                .align(Alignment.Center)
                .size(canvasSide)
        ) {
            val canvasSize = size.minDimension
            val canvasCenter = Offset(size.width / 2f, size.height / 2f)

            scale(animationState.clusterScale, pivot = canvasCenter) {
                // Apply Shake (if any)
                translate(left = animationState.clusterShakeX) {
                    // Squares
                    RaylHeroGeometry.squares.forEachIndexed { index, spec ->
                        val squareOffset = animationState.squareOffset(index)
                        val px = (spec.x + squareOffset.x) * canvasSize
                        val py = (spec.y + squareOffset.y) * canvasSize

                        val s = spec.size * canvasSize
                        val size = Size(s, s)
                        val squareCenter = Offset(px + s / 2, py + s / 2)

                        // Sharper corners for a more digital/tech look (5% of size instead of 10%)
                        val cornerRadius =
                            CornerRadius(s * RaylHeroGeometry.SQUARE_CORNER_RADIUS_FRACTION)
                        val stroke =
                            Stroke(width = s * RaylHeroGeometry.SQUARE_STROKE_WIDTH_FRACTION)

                        scale(animationState.squareScale(index), pivot = squareCenter) {
                            if (spec.outlined) {
                                // Finder Pattern (Ring)
                                drawRoundRect(
                                    color = animationState.color,
                                    size = size,
                                    cornerRadius = cornerRadius,
                                    topLeft = Offset(px, py),
                                    style = stroke
                                )
                                // Inner solid square (35% size)
                                val childSize =
                                    Size(
                                        size.width * RaylHeroGeometry.FINDER_INNER_SIZE_FRACTION,
                                        size.height * RaylHeroGeometry.FINDER_INNER_SIZE_FRACTION
                                    )
                                val offsetX = px + (size.width - childSize.width) / 2f
                                val offsetY = py + (size.height - childSize.height) / 2f
                                drawRoundRect(
                                    color = animationState.color,
                                    size = childSize,
                                    cornerRadius =
                                        CornerRadius(
                                            childSize.width *
                                                RaylHeroGeometry
                                                    .FINDER_INNER_CORNER_RADIUS_FRACTION
                                        ),
                                    topLeft = Offset(offsetX, offsetY)
                                )
                            } else {
                                // 4th Corner: "Data" Cluster with Flickering Bits
                                val gap = s * RaylHeroGeometry.DATA_BIT_GAP_FRACTION
                                val miniSize = (s - gap) / 2f
                                val miniRadius =
                                    CornerRadius(
                                        miniSize * RaylHeroGeometry.DATA_BIT_CORNER_RADIUS_FRACTION
                                    )

                                fun drawBit(x: Float, y: Float, opacity: Float) {
                                    drawRoundRect(
                                        color = animationState.color.copy(
                                            alpha = animationState.color.alpha * opacity
                                        ),
                                        size = Size(miniSize, miniSize),
                                        cornerRadius = miniRadius,
                                        topLeft = Offset(x, y)
                                    )
                                }

                                drawBit(px, py, animationState.bitOpacity(0))
                                drawBit(
                                    px + miniSize + gap,
                                    py,
                                    animationState.bitOpacity(1)
                                )
                                drawBit(
                                    px,
                                    py + miniSize + gap,
                                    animationState.bitOpacity(2)
                                )
                                drawBit(
                                    px + miniSize + gap,
                                    py + miniSize + gap,
                                    animationState.bitOpacity(3)
                                )
                            }
                        }
                    }

                    if (animationState.boltScale > 0f) {
                        val boltSize = canvasSize * RaylHeroGeometry.BOLT_SIZE_FRACTION
                        val boltPath = Path().apply {
                            val points = RaylHeroGeometry.bolt
                            moveTo(boltSize * points.first().x, boltSize * points.first().y)
                            points.drop(1).forEach { point ->
                                lineTo(boltSize * point.x, boltSize * point.y)
                            }
                            close()
                        }

                        val boltCenter = Offset(canvasSize / 2f, canvasSize / 2f)
                        val pathBounds = boltPath.getBounds()
                        val pathCenter = pathBounds.center

                        translate(
                            left = boltCenter.x - pathCenter.x,
                            top = boltCenter.y - pathCenter.y
                        ) {
                            scale(animationState.boltScale, pivot = pathCenter) {
                                drawPath(boltPath, animationState.color)
                            }
                        }
                    }
                }
            }

            rotate(animationState.rotation, pivot = canvasCenter) {
                RaylHeroGeometry.arcs.forEachIndexed { index, spec ->
                    val arcOffset = animationState.arcOffset(index)
                    val px = (spec.x + arcOffset.x) * canvasSize
                    val py = (spec.y + arcOffset.y) * canvasSize
                    val cornerLength = canvasSize * spec.length
                    val cornerStroke =
                        Stroke(
                            width = canvasSize * RaylHeroGeometry.ARC_STROKE_WIDTH_FRACTION,
                            cap = StrokeCap.Round
                        )
                    drawArc(
                        color = animationState.color,
                        startAngle = spec.startAngle,
                        sweepAngle = spec.sweepAngle,
                        useCenter = false,
                        style = cornerStroke,
                        size = Size(cornerLength, cornerLength),
                        topLeft = Offset(px, py)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroQrCode(content: RaylHeroQrContent, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = Color.White,
        contentColor = Color.Black
    ) {
        Image(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            painter = rememberQrCodePainter(
                data = content.data,
                darkBrush = QrBrush.solid(Color.Black),
                lightBrush = QrBrush.solid(Color.White),
                ballBrush = QrBrush.solid(Color.Black),
                frameBrush = QrBrush.solid(Color.Black)
            ),
            contentDescription = content.contentDescription
        )
    }
}

data class RaylHeroQrContent(val data: String, val contentDescription: String)
