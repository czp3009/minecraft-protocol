package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.ZeroFallbackEnum
import kotlinx.serialization.Serializable

/** The animation shared by item interaction components and entity swing packets. */
@Serializable
data class SwingAnimation(
    @ZeroFallbackEnum val type: SwingAnimationType,
    @VarInt val duration: Int,
)
