package xyz.lilsus.raylsuite.core.ui.hero

import androidx.compose.animation.Animatable as ColorAnimatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Transparent
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun rememberHeroAnimationState(
    squares: List<RaylHeroSquareSpec>,
    arcs: List<RaylHeroArcSpec>
): RaylHeroAnimationState = remember { RaylHeroAnimationState(squares, arcs) }

internal class RaylHeroAnimationState(
    private val squares: List<RaylHeroSquareSpec>,
    private val arcs: List<RaylHeroArcSpec>
) {
    private val colorAnim = ColorAnimatable(Transparent)
    private val clusterScaleAnim = Animatable(1f)
    private val clusterShakeAnim = Animatable(0f)
    private val rotationAnim = Animatable(0f)
    private val boltScaleAnim = Animatable(0f)
    private val squareScaleAnims = List(squares.size) { Animatable(1f) }
    private val squareOffsetAnims =
        List(squares.size) { Animatable(Offset(0f, 0f), Offset.VectorConverter) }
    private val arcOffsetAnims =
        List(arcs.size) { Animatable(Offset(0f, 0f), Offset.VectorConverter) }
    private val bitOpacityAnims = List(RaylHeroGeometry.DATA_BIT_COUNT) { Animatable(1f) }

    // The rejection nudge is added on top of the phase, so neither ever interrupts the other.
    private val nudgeAnim = Animatable(0f)
    private val nudgeShakeAnim = Animatable(0f)
    private val nudgedArcOffsets = arcs.map { spec ->
        Offset(
            stepTowardCenter(spec.x, spec.length, RaylHeroGeometry.ARC_COMPRESSION_FRACTION),
            stepTowardCenter(spec.y, spec.length, RaylHeroGeometry.ARC_COMPRESSION_FRACTION)
        )
    }

    val color: Color
        get() = colorAnim.value
    val clusterScale: Float
        get() = clusterScaleAnim.value
    val clusterShakeX: Float
        get() = clusterShakeAnim.value + nudgeShakeAnim.value
    val rotation: Float
        get() = rotationAnim.value
    val boltScale: Float
        get() = boltScaleAnim.value

    fun squareScale(index: Int): Float = squareScaleAnims[index].value

    fun squareOffset(index: Int): Offset = squareOffsetAnims[index].value

    fun arcOffset(index: Int): Offset =
        arcOffsetAnims[index].value + nudgedArcOffsets[index] * nudgeAnim.value

    fun bitOpacity(index: Int): Float = bitOpacityAnims[index].value

    suspend fun animateColor(targetColor: Color) {
        colorAnim.animateTo(
            targetColor,
            animationSpec = tween(durationMillis = 500, easing = EaseInOutCubic)
        )
    }

    suspend fun animatePhase(phase: RaylHeroPhase) {
        when (phase) {
            RaylHeroPhase.Ready -> animateToActive()
            RaylHeroPhase.Acknowledged -> animateToCompressed()
            RaylHeroPhase.Processing -> animateToLoading()
            RaylHeroPhase.Succeeded -> animateToResult(isSuccess = true)
            RaylHeroPhase.Failed -> animateToResult(isSuccess = false)
        }
    }

    /** An input was turned away: the corners close in on the code, then let go with a shake. */
    suspend fun nudge() = coroutineScope {
        launch {
            nudgeAnim.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = NUDGE_DURATION_MS
                    0f at 0 using EaseInOutCubic
                    1f at 150
                    1f at 250 using EaseInOutCubic
                }
            )
        }
        launch {
            nudgeShakeAnim.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = NUDGE_DURATION_MS
                    0f at 200
                    -6f at 250
                    6f at 300
                    -4f at 350
                    4f at 400
                    0f at 500
                }
            )
        }
    }

    private suspend fun animateToActive() {
        // Idle keyframes start at the resting geometry. Finish restoring it before
        // those tracks take ownership of the same Animatables.
        reset()
        coroutineScope {
            launch { animateScanningRotation() }
            launch { animateScanningSequence() }
            launch { animateDataBits() }
        }
    }

    private suspend fun animateToCompressed() {
        coroutineScope {
            launch { rotationAnim.animateTo(0f, tween(300, easing = EaseInOutCubic)) }
            launch { clenchShrink() }
            launch { compressSquareOffsets() }
            launch { compressArcOffsets() }
            launch { stopDataBits() }
        }
    }

    private suspend fun animateToLoading() {
        coroutineScope {
            launch { rotationAnim.animateTo(0f, tween(300, easing = EaseInOutCubic)) }
            launch { clenchShrink() }
            launch { compressSquareOffsets() }
            launch { compressAndIndicateLoadingArcs() }
            launch { stopDataBits() }
        }
    }

    private suspend fun animateToResult(isSuccess: Boolean) {
        coroutineScope {
            launch { rotationAnim.animateTo(0f, tween(300, easing = EaseInOutCubic)) }
            launch { stopDataBits() }

            if (isSuccess) {
                launch {
                    delay(150)
                    boltScaleAnim.snapTo(0f)
                    // Elastic pop
                    boltScaleAnim.animateTo(1.2f, tween(250, easing = EaseInOutCubic))
                    boltScaleAnim.animateTo(1f, tween(150, easing = EaseInOutCubic))
                }
                compressArcOffsets()
                reset(pop = true)
            } else {
                // Error: Shake it!
                launch {
                    clusterShakeAnim.animateTo(
                        targetValue = 0f,
                        animationSpec = keyframes {
                            durationMillis = 500
                            0f at 0
                            -10f at 50
                            10f at 100
                            -10f at 150
                            10f at 200
                            -5f at 250
                            5f at 300
                            0f at 500
                        }
                    )
                }
                compressArcOffsets()
                reset(pop = true, isError = true)
            }
        }
    }

    private suspend fun reset(pop: Boolean = false, isError: Boolean = false) = coroutineScope {
        rotationAnim.snapTo(0f)

        if (!pop) {
            boltScaleAnim.snapTo(0f)
            clusterShakeAnim.snapTo(0f)
        }

        val popDuration = 250
        val settleDuration = 250
        val totalDuration = popDuration + settleDuration

        // Animate scale
        launch {
            clusterScaleAnim.animateTo(
                1f,
                animationSpec = tween(if (pop) totalDuration else 500, easing = EaseInOutCubic)
            )
        }

        // Animate square scales
        squareScaleAnims.forEach {
            launch {
                // If success pop, hide squares (bolt shows).
                // If error pop, keep squares visible (they shake).
                // If reset (active), show squares.
                val targetScale = if (pop && !isError) 0f else 1f
                val duration = if (pop) 200 else 500
                it.animateTo(
                    targetScale,
                    tween(duration, easing = if (pop) EaseInOutCubic else LinearOutSlowInEasing)
                )
            }
        }

        // Animate square offsets
        squareOffsetAnims.forEach {
            launch {
                it.animateTo(
                    Offset.Zero,
                    animationSpec = tween(if (pop) totalDuration else 500, easing = EaseInOutCubic)
                )
            }
        }

        // Animate arc offsets
        arcOffsetAnims.forEachIndexed { index, anim ->
            launch {
                if (pop) {
                    val spec = arcs[index]
                    val poppedOffset = Offset(
                        stepTowardCenter(
                            spec.x,
                            spec.length,
                            RaylHeroGeometry.ARC_POP_FRACTION
                        ),
                        stepTowardCenter(
                            spec.y,
                            spec.length,
                            RaylHeroGeometry.ARC_POP_FRACTION
                        )
                    )
                    anim.animateTo(
                        poppedOffset,
                        animationSpec = tween(popDuration, easing = EaseInOutCubic)
                    )
                }
                anim.animateTo(
                    Offset.Zero,
                    animationSpec = tween(if (pop) settleDuration else 250, easing = EaseInOutCubic)
                )
            }
        }
    }

    private suspend fun clenchShrink() {
        clusterScaleAnim.animateTo(
            0.9f,
            animationSpec = tween(durationMillis = 300, easing = EaseInOutCubic)
        )
    }

    private suspend fun animateScanningRotation() {
        rotationAnim.snapTo(0f)
        rotationAnim.animateTo(
            targetValue = 10f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 4000
                    0f at 0 using EaseInOutCubic
                    10f at 1000 using EaseInOutCubic
                    0f at 2000 using EaseInOutCubic
                    -10f at 3000 using EaseInOutCubic
                    0f at 4000 using EaseInOutCubic
                },
                repeatMode = RepeatMode.Restart
            )
        )
    }

    private suspend fun animateScanningSequence() = coroutineScope {
        val cycleDuration = 1000
        val pulseDuration = 300

        squareScaleAnims.forEachIndexed { index, anim ->
            launch {
                val startDelay = index * 100

                anim.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = cycleDuration
                            1f at 0
                            1f at startDelay
                            1.15f at startDelay + (pulseDuration / 2) using EaseInOutCubic
                            1f at startDelay + pulseDuration using EaseInOutCubic
                            1f at cycleDuration
                        },
                        repeatMode = RepeatMode.Restart
                    )
                )
            }
        }
    }

    private suspend fun animateDataBits() = coroutineScope {
        bitOpacityAnims.forEach { anim ->
            launch {
                // Random-looking flicker
                anim.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 500 + (Random.nextLong(500)).toInt()
                            1f at 0
                            0.3f at (durationMillis * 0.2).toInt()
                            1f at (durationMillis * 0.4).toInt()
                            0.6f at (durationMillis * 0.7).toInt()
                            1f at durationMillis
                        },
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
        }
    }

    private suspend fun stopDataBits() {
        bitOpacityAnims.forEach {
            it.snapTo(1f)
        }
    }

    private suspend fun compressSquareOffsets() = coroutineScope {
        val step = RaylHeroGeometry.SQUARE_COMPRESSION_FRACTION
        squareOffsetAnims.forEachIndexed { index, anim ->
            val spec = squares[index]
            val adjustedX = stepTowardCenter(spec.x, spec.size, step)
            val adjustedY = stepTowardCenter(spec.y, spec.size, step)
            launch {
                anim.animateTo(
                    Offset(adjustedX, adjustedY),
                    animationSpec = tween(durationMillis = 500, easing = EaseInOutCubic)
                )
            }
        }
    }

    private suspend fun compressArcOffsets() = coroutineScope {
        val arcStep = RaylHeroGeometry.ARC_COMPRESSION_FRACTION
        arcOffsetAnims.forEachIndexed { index, anim ->
            val spec = arcs[index]
            val adjustedX = stepTowardCenter(spec.x, spec.length, arcStep)
            val adjustedY = stepTowardCenter(spec.y, spec.length, arcStep)
            launch {
                anim.animateTo(
                    Offset(adjustedX, adjustedY),
                    animationSpec = tween(durationMillis = 500, easing = EaseInOutCubic)
                )
            }
        }
    }

    private suspend fun compressAndIndicateLoadingArcs() = coroutineScope {
        val arcStep = RaylHeroGeometry.ARC_COMPRESSION_FRACTION
        arcOffsetAnims.forEachIndexed { index, anim ->
            val spec = arcs[index]
            val compressedX = stepTowardCenter(spec.x, spec.length, arcStep)
            val compressedY = stepTowardCenter(spec.y, spec.length, arcStep)
            val compressedOffset = Offset(compressedX, compressedY)

            launch {
                anim.animateTo(
                    compressedOffset,
                    animationSpec = tween(durationMillis = 500, easing = EaseInOutCubic)
                )

                delay(index * 100L)

                val current = anim.value
                val targetX = current.x * RaylHeroGeometry.LOADING_ARC_INSET_FRACTION
                val targetY = current.y * RaylHeroGeometry.LOADING_ARC_INSET_FRACTION

                anim.animateTo(
                    Offset(targetX, targetY),
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 600
                            Offset(current.x, current.y) at 0 using EaseInOutCubic
                            Offset(targetX, targetY) at 300 using EaseInOutCubic
                            Offset(current.x, current.y) at 600 using EaseInOutCubic
                        },
                        repeatMode = RepeatMode.Restart
                    )
                )
            }
        }
    }
}

private const val NUDGE_DURATION_MS = 600

private fun stepTowardCenter(value: Float, size: Float, step: Float): Float {
    val center = value + size / 2f
    val delta = 0.5f - center
    return delta * step
}
