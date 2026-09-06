package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** The official ChunkPos value, packed with X in the low 32 bits and Z in the high 32 bits. */
@Serializable(with = ChunkPosSerializer::class)
data class ChunkPos(val x: Int, val z: Int)

internal object ChunkPosSerializer : KSerializer<ChunkPos> {
    override val descriptor = PrimitiveSerialDescriptor("minecraft.ChunkPos", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: ChunkPos) =
        encoder.encodeLong((value.x.toLong() and 0xFFFFFFFFL) or (value.z.toLong() shl 32))

    override fun deserialize(decoder: Decoder): ChunkPos = decoder.decodeLong().let {
        ChunkPos(it.toInt(), (it ushr 32).toInt())
    }
}
