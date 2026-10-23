package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.Angle
import com.hiczp.minecraft.protocol.model.type.VecDelta
import com.hiczp.minecraft.protocol.model.type.VecDeltaSerializer
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** The official packet variants omit base-class fields fixed or absent in their own wire representation. */
object ClientboundMoveEntityPacket {
    @Serializable(with = MoveEntityPosSerializer::class)
    @PacketInfo(
        0x36,
        ConnectionState.PLAY,
        PacketDirection.CLIENTBOUND,
        officialName = "move_entity_pos",
        shapeException = "The Pos variant omits base-class rotation and discriminator fields fixed by its packet type; only transmitted members are constructor data.",
    )
    data class Pos(
        @VarInt
        val entityId: Int,
        val delta: VecDelta,
        val onGround: Boolean,
    ) : PlayStatePacket, ClientboundPacket

    @Serializable(with = MoveEntityPosRotSerializer::class)
    @PacketInfo(
        0x37,
        ConnectionState.PLAY,
        PacketDirection.CLIENTBOUND,
        officialName = "move_entity_pos_rot",
        shapeException = "The PosRot variant omits hasRot and hasPos because both are fixed by its packet type.",
    )
    data class PosRot(
        @VarInt
        val entityId: Int,
        val delta: VecDelta,
        val yRot: Angle,
        val xRot: Angle,
        val onGround: Boolean,
    ) : PlayStatePacket, ClientboundPacket

    @Serializable(with = MoveEntityRotSerializer::class)
    @PacketInfo(
        0x39,
        ConnectionState.PLAY,
        PacketDirection.CLIENTBOUND,
        officialName = "move_entity_rot",
        shapeException = "The Rot variant omits base-class position and discriminator fields fixed by its packet type; only transmitted members are constructor data.",
    )
    data class Rot(
        @VarInt
        val entityId: Int,
        val yRot: Angle,
        val xRot: Angle,
        val onGround: Boolean,
    ) : PlayStatePacket, ClientboundPacket
}

internal object MoveEntityPosSerializer : KSerializer<ClientboundMoveEntityPacket.Pos> {
    override val descriptor = buildClassSerialDescriptor("minecraft.ClientboundMoveEntityPacket.Pos") {
        element<Int>("entityId", annotations = listOf(VarInt()))
        element<Int>("properties", annotations = listOf(VarInt()))
        element("delta", VecDeltaSerializer(0).descriptor)
    }

    override fun serialize(encoder: Encoder, value: ClientboundMoveEntityPacket.Pos) {
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, 0, value.entityId)
        output.encodeIntElement(descriptor, 1, (value.delta.stepCount shl 1) or if (value.onGround) 1 else 0)
        output.encodeSerializableElement(descriptor, 2, VecDeltaSerializer(value.delta.stepCount), value.delta)
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ClientboundMoveEntityPacket.Pos {
        val input = decoder.beginStructure(descriptor)
        val entityId = input.decodeIntElement(descriptor, 0)
        val properties = input.decodeIntElement(descriptor, 1)
        val delta = input.decodeSerializableElement(descriptor, 2, VecDeltaSerializer(properties ushr 1))
        input.endStructure(descriptor)
        return ClientboundMoveEntityPacket.Pos(entityId, delta, properties and 1 != 0)
    }
}

internal object MoveEntityPosRotSerializer : KSerializer<ClientboundMoveEntityPacket.PosRot> {
    override val descriptor = buildClassSerialDescriptor("minecraft.ClientboundMoveEntityPacket.PosRot") {
        element<Int>("entityId", annotations = listOf(VarInt()))
        element<Int>("properties", annotations = listOf(VarInt()))
        element("delta", VecDeltaSerializer(0).descriptor)
        element<Angle>("yRot")
        element<Angle>("xRot")
    }

    override fun serialize(encoder: Encoder, value: ClientboundMoveEntityPacket.PosRot) {
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, 0, value.entityId)
        output.encodeIntElement(descriptor, 1, (value.delta.stepCount shl 1) or if (value.onGround) 1 else 0)
        output.encodeSerializableElement(descriptor, 2, VecDeltaSerializer(value.delta.stepCount), value.delta)
        output.encodeSerializableElement(descriptor, 3, Angle.serializer(), value.yRot)
        output.encodeSerializableElement(descriptor, 4, Angle.serializer(), value.xRot)
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ClientboundMoveEntityPacket.PosRot {
        val input = decoder.beginStructure(descriptor)
        val entityId = input.decodeIntElement(descriptor, 0)
        val properties = input.decodeIntElement(descriptor, 1)
        val delta = input.decodeSerializableElement(descriptor, 2, VecDeltaSerializer(properties ushr 1))
        val yRot = input.decodeSerializableElement(descriptor, 3, Angle.serializer())
        val xRot = input.decodeSerializableElement(descriptor, 4, Angle.serializer())
        input.endStructure(descriptor)
        return ClientboundMoveEntityPacket.PosRot(entityId, delta, yRot, xRot, properties and 1 != 0)
    }
}

@Serializable
private data class MoveEntityRotSurrogate(
    @VarInt val entityId: Int,
    val onGround: Boolean,
    val yRot: Angle,
    val xRot: Angle
)

internal object MoveEntityRotSerializer : KSerializer<ClientboundMoveEntityPacket.Rot> {
    override val descriptor = MoveEntityRotSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ClientboundMoveEntityPacket.Rot) = encoder.encodeSerializableValue(
        MoveEntityRotSurrogate.serializer(),
        MoveEntityRotSurrogate(value.entityId, value.onGround, value.yRot, value.xRot),
    )

    override fun deserialize(decoder: Decoder): ClientboundMoveEntityPacket.Rot {
        val surrogate = decoder.decodeSerializableValue(MoveEntityRotSurrogate.serializer())
        return ClientboundMoveEntityPacket.Rot(surrogate.entityId, surrogate.yRot, surrogate.xRot, surrogate.onGround)
    }
}
