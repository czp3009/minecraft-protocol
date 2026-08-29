package com.hiczp.minecraft.world.format.data

import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.NbtChunkPositionSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contents of `minecraft:chunk_tickets` dimension saved data. */
@Serializable
data class ChunkTicketsData(
    val tickets: List<Ticket> = emptyList(),
) {
    @Serializable
    data class Ticket(
        @SerialName("chunk_pos")
        @Serializable(with = NbtChunkPositionSerializer::class)
        val chunkPosition: ChunkPosition,
        val type: String,
        val level: Int,
        @SerialName("ticks_left")
        val ticksLeft: Long = 0,
    ) {
        init {
            require(type.isNotBlank()) { "A Chunk ticket type must not be blank" }
            require(level >= 0) { "A Chunk ticket level must not be negative" }
        }
    }
}
