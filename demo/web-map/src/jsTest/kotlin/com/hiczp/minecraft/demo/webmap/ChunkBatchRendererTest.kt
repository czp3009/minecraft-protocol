package com.hiczp.minecraft.demo.webmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChunkBatchRendererTest {
    @Test
    fun localPredictionUsesOnlyTranslationAndUniformPositiveScale() {
        val canvasTransform = calculateCanvasTransform(
            referenceWidth = 320.0,
            referenceHeight = 180.0,
            transformedLeft = -48.0,
            transformedTop = 24.0,
            transformedRight = 592.0,
            transformedBottom = 384.0,
        )

        assertEquals(-48.0, canvasTransform.translationX)
        assertEquals(24.0, canvasTransform.translationY)
        assertEquals(2.0, canvasTransform.scaleX)
        assertEquals(canvasTransform.scaleX, canvasTransform.scaleY)
    }

    @Test
    fun localPredictionRejectsAnInvertedRectangle() {
        assertFailsWith<IllegalArgumentException> {
            calculateCanvasTransform(
                referenceWidth = 320.0,
                referenceHeight = 180.0,
                transformedLeft = 100.0,
                transformedTop = 50.0,
                transformedRight = 20.0,
                transformedBottom = 230.0,
            )
        }
    }
}
