package xyz.lilsus.raylsuite.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class QrDetectionSelectorTest {
    private fun code(value: String, left: Float, top: Float, size: Float) =
        QrDetectionCandidate(value, left, top, right = left + size, bottom = top + size)

    @Test
    fun theLargerCodeComesFirstAndTheSmallerOneIsNotDropped() {
        val ranked = rankQrValues(
            candidates = listOf(
                code("small_centered", left = 40f, top = 40f, size = 15f),
                code("large_edge", left = 0f, top = 0f, size = 60f)
            ),
            frameWidth = 100f,
            frameHeight = 100f
        )

        assertEquals(listOf("large_edge", "small_centered"), ranked)
    }

    @Test
    fun ofTwoCodesTheSameSizeTheOneNearerTheCentreComesFirst() {
        val ranked = rankQrValues(
            candidates = listOf(
                code("corner", left = 0f, top = 0f, size = 20f),
                code("centre", left = 40f, top = 40f, size = 20f)
            ),
            frameWidth = 100f,
            frameHeight = 100f
        )

        assertEquals(listOf("centre", "corner"), ranked)
    }

    @Test
    fun aCodeReadTwiceInOneFrameIsListedOnce() {
        val ranked = rankQrValues(
            candidates = listOf(
                code("invoice", left = 0f, top = 0f, size = 20f),
                code("invoice", left = 50f, top = 50f, size = 30f)
            ),
            frameWidth = 100f,
            frameHeight = 100f
        )

        assertEquals(listOf("invoice"), ranked)
    }
}
