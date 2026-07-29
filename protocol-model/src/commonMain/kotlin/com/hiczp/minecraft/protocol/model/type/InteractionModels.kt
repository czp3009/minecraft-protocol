package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.Serializable

@Serializable
data class BlockHitResult(
    val location: BlockPosition,
    val face: BlockFace,
    val cursorX: Float,
    val cursorY: Float,
    val cursorZ: Float,
    val insideBlock: Boolean,
    val worldBorderHit: Boolean,
)
