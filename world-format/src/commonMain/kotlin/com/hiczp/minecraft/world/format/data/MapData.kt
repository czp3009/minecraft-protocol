package com.hiczp.minecraft.world.format.data

import com.hiczp.minecraft.nbt.NbtByteArray
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.world.format.BlockPosition
import com.hiczp.minecraft.world.format.NbtBlockPositionSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contents of one root `minecraft:maps/<id>` saved-data file. */
@Serializable
data class MapData(
    val dimension: String,
    @SerialName("xCenter")
    val centerX: Int,
    @SerialName("zCenter")
    val centerZ: Int,
    val scale: Byte = 0,
    val colors: NbtByteArray,
    @SerialName("trackingPosition")
    val trackingPosition: Boolean = true,
    @SerialName("unlimitedTracking")
    val unlimitedTracking: Boolean = false,
    val locked: Boolean = false,
    val banners: List<Banner> = emptyList(),
    val frames: List<Frame> = emptyList(),
) {
    @Serializable
    data class Banner(
        @Serializable(with = NbtBlockPositionSerializer::class)
        val pos: BlockPosition,
        val color: Color,
        val name: NbtTag? = null,
    )

    @Serializable
    data class Frame(
        @Serializable(with = NbtBlockPositionSerializer::class)
        val pos: BlockPosition,
        val rotation: Int,
        @SerialName("entity_id")
        val entityId: Int,
    )

    @Serializable
    enum class Color {
        @SerialName("white")
        WHITE,

        @SerialName("orange")
        ORANGE,

        @SerialName("magenta")
        MAGENTA,

        @SerialName("light_blue")
        LIGHT_BLUE,

        @SerialName("yellow")
        YELLOW,

        @SerialName("lime")
        LIME,

        @SerialName("pink")
        PINK,

        @SerialName("gray")
        GRAY,

        @SerialName("light_gray")
        LIGHT_GRAY,

        @SerialName("cyan")
        CYAN,

        @SerialName("purple")
        PURPLE,

        @SerialName("blue")
        BLUE,

        @SerialName("brown")
        BROWN,

        @SerialName("green")
        GREEN,

        @SerialName("red")
        RED,

        @SerialName("black")
        BLACK,
    }
}
