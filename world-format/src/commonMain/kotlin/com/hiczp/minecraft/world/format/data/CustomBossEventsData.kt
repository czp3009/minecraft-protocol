package com.hiczp.minecraft.world.format.data

import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.world.format.NbtUuidSetSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/** Contents of root `minecraft:custom_boss_events` saved data, keyed by boss-event identifier. */
@JvmInline
@Serializable
value class CustomBossEventsData(
    val events: Map<String, Event>,
) {
    @Serializable
    data class Event(
        @SerialName("Name")
        val name: NbtTag,
        @SerialName("Visible")
        val visible: Boolean = false,
        @SerialName("Value")
        val value: Int = 0,
        @SerialName("Max")
        val max: Int = 100,
        @SerialName("Color")
        val color: Color = Color.WHITE,
        @SerialName("Overlay")
        val overlay: Overlay = Overlay.PROGRESS,
        @SerialName("DarkenScreen")
        val darkenScreen: Boolean = false,
        @SerialName("PlayBossMusic")
        val playBossMusic: Boolean = false,
        @SerialName("CreateWorldFog")
        val createWorldFog: Boolean = false,
        @SerialName("Players")
        @Serializable(with = NbtUuidSetSerializer::class)
        val players: Set<Uuid> = emptySet(),
    )

    @Serializable
    enum class Color {
        @SerialName("pink")
        PINK,

        @SerialName("blue")
        BLUE,

        @SerialName("red")
        RED,

        @SerialName("green")
        GREEN,

        @SerialName("yellow")
        YELLOW,

        @SerialName("purple")
        PURPLE,

        @SerialName("white")
        WHITE,
    }

    @Serializable
    enum class Overlay {
        @SerialName("progress")
        PROGRESS,

        @SerialName("notched_6")
        NOTCHED_6,

        @SerialName("notched_10")
        NOTCHED_10,

        @SerialName("notched_12")
        NOTCHED_12,

        @SerialName("notched_20")
        NOTCHED_20,
    }
}
