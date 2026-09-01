package com.hiczp.minecraft.demo.webmap

internal data class CanvasTransform(
    val translationX: Double,
    val translationY: Double,
    val scaleX: Double,
    val scaleY: Double,
)

internal fun calculateCanvasTransform(
    referenceWidth: Double,
    referenceHeight: Double,
    transformedLeft: Double,
    transformedTop: Double,
    transformedRight: Double,
    transformedBottom: Double,
): CanvasTransform {
    require(referenceWidth > 0.0 && referenceHeight > 0.0) { "Canvas reference dimensions must be positive" }
    require(
        listOf(transformedLeft, transformedTop, transformedRight, transformedBottom).all(Double::isFinite),
    ) { "Transformed Canvas bounds must be finite" }
    require(transformedRight > transformedLeft && transformedBottom > transformedTop) {
        "Transformed Canvas bounds must describe a positive axis-aligned rectangle"
    }
    val horizontalScale = (transformedRight - transformedLeft) / referenceWidth
    val verticalScale = (transformedBottom - transformedTop) / referenceHeight
    val uniformScale = (horizontalScale + verticalScale) / 2.0
    return CanvasTransform(
        translationX = transformedLeft,
        translationY = transformedTop,
        scaleX = uniformScale,
        scaleY = uniformScale,
    )
}
