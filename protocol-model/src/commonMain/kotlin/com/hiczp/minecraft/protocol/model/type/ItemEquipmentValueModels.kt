package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.Serializable

@Serializable
enum class EquipmentSlot {
    MAINHAND,
    OFFHAND,
    FEET,
    LEGS,
    CHEST,
    HEAD,
    BODY,
    SADDLE,
}

@Serializable
enum class SwingAnimationType {
    NONE,
    WHACK,
    STAB,
}

@Serializable
enum class DyeColor {
    WHITE,
    ORANGE,
    MAGENTA,
    LIGHT_BLUE,
    YELLOW,
    LIME,
    PINK,
    GRAY,
    LIGHT_GRAY,
    CYAN,
    PURPLE,
    BLUE,
    BROWN,
    GREEN,
    RED,
    BLACK,
}

@Serializable
data class ToolRule(
    val blocks: RegistryHolderSet,
    val speed: Float? = null,
    val correctForDrops: Boolean? = null,
)

@Serializable
data class BlocksAttackDamageReduction(
    val horizontalBlockingAngle: Float,
    val type: RegistryHolderSet? = null,
    val base: Float,
    val factor: Float,
)

@Serializable
data class ItemDamageFunction(
    val threshold: Float,
    val base: Float,
    val factor: Float,
)

@Serializable
data class KineticWeaponCondition(
    @VarInt
    val maximumDurationTicks: Int,
    val minimumSpeed: Float,
    val minimumRelativeSpeed: Float,
)
