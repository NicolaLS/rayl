package xyz.lilsus.raylsuite.core.ui.hero

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

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
    // Skip animation state and infinite coroutines completely when rendering a static QR receipt.
    if (qrContent != null) {
        BoxWithConstraints(modifier = modifier) {
            HeroQrCode(
                content = qrContent,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(minOf(maxWidth, maxHeight))
            )
        }
        return
    }

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
        val canvasSide = minOf(maxWidth * RaylHeroGeometry.CANVAS_WIDTH_FRACTION, maxHeight)
        Spacer(
            modifier = Modifier
                .align(Alignment.Center)
                .size(canvasSide)
                .drawWithCache {
                    val canvasSize = size.minDimension
                    val canvasCenter = Offset(size.width / 2f, size.height / 2f)
                    val squareStrokes = RaylHeroGeometry.squares.map { spec ->
                        Stroke(
                            width = spec.size * canvasSize *
                                RaylHeroGeometry.SQUARE_STROKE_WIDTH_FRACTION
                        )
                    }
                    val arcStroke = Stroke(
                        width = canvasSize * RaylHeroGeometry.ARC_STROKE_WIDTH_FRACTION,
                        cap = StrokeCap.Round
                    )
                    val boltSize = canvasSize * RaylHeroGeometry.BOLT_SIZE_FRACTION
                    val boltPoints = RaylHeroGeometry.bolt
                    val boltPath = Path().apply {
                        moveTo(boltSize * boltPoints[0].x, boltSize * boltPoints[0].y)
                        for (i in 1 until boltPoints.size) {
                            lineTo(boltSize * boltPoints[i].x, boltSize * boltPoints[i].y)
                        }
                        close()
                    }
                    val boltCenter = Offset(canvasSize / 2f, canvasSize / 2f)
                    val pathCenter = boltPath.getBounds().center

                    onDrawBehind {
                        val drawColor = animationState.color
                        scale(animationState.clusterScale, pivot = canvasCenter) {
                            // Apply Shake (if any)
                            translate(left = animationState.clusterShakeX) {
                                // Squares
                                RaylHeroGeometry.squares.forEachIndexed { index, spec ->
                                    val squareOffset = animationState.squareOffset(index)
                                    val px = (spec.x + squareOffset.x) * canvasSize
                                    val py = (spec.y + squareOffset.y) * canvasSize

                                    val s = spec.size * canvasSize
                                    val squareSize = Size(s, s)
                                    val squareCenter = Offset(px + s / 2f, py + s / 2f)

                                    // Sharper corners for a more digital/tech look (5% of size instead of 10%)
                                    val cornerRadius =
                                        CornerRadius(
                                            s * RaylHeroGeometry.SQUARE_CORNER_RADIUS_FRACTION
                                        )

                                    scale(animationState.squareScale(index), pivot = squareCenter) {
                                        if (spec.outlined) {
                                            // Finder Pattern (Ring)
                                            drawRoundRect(
                                                color = drawColor,
                                                size = squareSize,
                                                cornerRadius = cornerRadius,
                                                topLeft = Offset(px, py),
                                                style = squareStrokes[index]
                                            )
                                            // Inner solid square (35% size)
                                            val childSize =
                                                Size(
                                                    squareSize.width *
                                                        RaylHeroGeometry.FINDER_INNER_SIZE_FRACTION,
                                                    squareSize.height *
                                                        RaylHeroGeometry.FINDER_INNER_SIZE_FRACTION
                                                )
                                            val offsetX =
                                                px + (squareSize.width - childSize.width) / 2f
                                            val offsetY =
                                                py + (squareSize.height - childSize.height) / 2f
                                            drawRoundRect(
                                                color = drawColor,
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
                                            val miniDim = (s - gap) / 2f
                                            val miniSize = Size(miniDim, miniDim)
                                            val miniRadius =
                                                CornerRadius(
                                                    miniDim *
                                                        RaylHeroGeometry
                                                            .DATA_BIT_CORNER_RADIUS_FRACTION
                                                )
                                            val baseAlpha = drawColor.alpha

                                            drawRoundRect(
                                                color = drawColor.copy(
                                                    alpha = baseAlpha * animationState.bitOpacity(0)
                                                ),
                                                size = miniSize,
                                                cornerRadius = miniRadius,
                                                topLeft = Offset(px, py)
                                            )
                                            drawRoundRect(
                                                color = drawColor.copy(
                                                    alpha = baseAlpha * animationState.bitOpacity(1)
                                                ),
                                                size = miniSize,
                                                cornerRadius = miniRadius,
                                                topLeft = Offset(px + miniDim + gap, py)
                                            )
                                            drawRoundRect(
                                                color = drawColor.copy(
                                                    alpha = baseAlpha * animationState.bitOpacity(2)
                                                ),
                                                size = miniSize,
                                                cornerRadius = miniRadius,
                                                topLeft = Offset(px, py + miniDim + gap)
                                            )
                                            drawRoundRect(
                                                color = drawColor.copy(
                                                    alpha = baseAlpha * animationState.bitOpacity(3)
                                                ),
                                                size = miniSize,
                                                cornerRadius = miniRadius,
                                                topLeft = Offset(
                                                    px + miniDim + gap,
                                                    py + miniDim + gap
                                                )
                                            )
                                        }
                                    }
                                }

                                if (animationState.boltScale > 0f) {
                                    translate(
                                        left = boltCenter.x - pathCenter.x,
                                        top = boltCenter.y - pathCenter.y
                                    ) {
                                        scale(animationState.boltScale, pivot = pathCenter) {
                                            drawPath(boltPath, drawColor)
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
                                drawArc(
                                    color = drawColor,
                                    startAngle = spec.startAngle,
                                    sweepAngle = spec.sweepAngle,
                                    useCenter = false,
                                    style = arcStroke,
                                    size = Size(cornerLength, cornerLength),
                                    topLeft = Offset(px, py)
                                )
                            }
                        }
                    }
                }
        )
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
