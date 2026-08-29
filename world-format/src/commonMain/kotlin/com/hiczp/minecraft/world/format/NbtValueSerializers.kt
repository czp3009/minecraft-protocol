package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtDouble
import com.hiczp.minecraft.nbt.NbtFloat
import com.hiczp.minecraft.nbt.NbtIntArray
import com.hiczp.minecraft.nbt.NbtList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.Uuid

/** Encodes an absolute Block position with the three-int NBT representation used by Mojang codecs. */
object NbtBlockPositionSerializer : KSerializer<BlockPosition> {
    override val descriptor: SerialDescriptor = NbtIntArray.serializer().descriptor

    override fun serialize(encoder: Encoder, value: BlockPosition) {
        encoder.encodeSerializableValue(
            NbtIntArray.serializer(),
            NbtIntArray(intArrayOf(value.x, value.y, value.z)),
        )
    }

    override fun deserialize(decoder: Decoder): BlockPosition {
        val values = decoder.decodeSerializableValue(NbtIntArray.serializer())
        if (values.size != BLOCK_POSITION_COMPONENT_COUNT) {
            throw SerializationException("A Block position must contain three integers")
        }
        return BlockPosition(values[0], values[1], values[2])
    }
}

/** Encodes an absolute Chunk position with the two-int NBT representation used by Mojang codecs. */
object NbtChunkPositionSerializer : KSerializer<ChunkPosition> {
    override val descriptor: SerialDescriptor = NbtIntArray.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ChunkPosition) {
        encoder.encodeSerializableValue(
            NbtIntArray.serializer(),
            NbtIntArray(intArrayOf(value.x, value.z)),
        )
    }

    override fun deserialize(decoder: Decoder): ChunkPosition {
        val values = decoder.decodeSerializableValue(NbtIntArray.serializer())
        if (values.size != CHUNK_POSITION_COMPONENT_COUNT) {
            throw SerializationException("A Chunk position must contain two integers")
        }
        return ChunkPosition(values[0], values[1])
    }
}

/** Encodes a UUID with Mojang's four-int NBT representation. */
object NbtUuidSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor = NbtIntArray.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Uuid) {
        encoder.encodeSerializableValue(NbtIntArray.serializer(), value.toNbtIntArray())
    }

    override fun deserialize(decoder: Decoder): Uuid =
        decoder.decodeSerializableValue(NbtIntArray.serializer()).toUuid()
}

/** Encodes a UUID set as the ordered NBT list used by the official set codec. */
object NbtUuidSetSerializer : KSerializer<Set<Uuid>> {
    private val delegate = ListSerializer(NbtUuidSerializer)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Set<Uuid>) {
        encoder.encodeSerializableValue(delegate, value.toList())
    }

    override fun deserialize(decoder: Decoder): Set<Uuid> {
        val values = decoder.decodeSerializableValue(delegate)
        if (values.distinct().size != values.size) {
            throw SerializationException("A UUID set cannot contain duplicates")
        }
        return values.toCollection(linkedSetOf())
    }
}

/** Encodes an Entity position or velocity as the official three-double NBT list. */
object NbtEntityVector3dSerializer : KSerializer<EntityVector3d> {
    override val descriptor: SerialDescriptor = NbtList.serializer().descriptor

    override fun serialize(encoder: Encoder, value: EntityVector3d) {
        encoder.encodeSerializableValue(
            NbtList.serializer(),
            NbtList(listOf(NbtDouble(value.x), NbtDouble(value.y), NbtDouble(value.z))),
        )
    }

    override fun deserialize(decoder: Decoder): EntityVector3d {
        val values = decoder.decodeSerializableValue(NbtList.serializer())
        if (values.size != VECTOR_COMPONENT_COUNT) {
            throw SerializationException("An Entity vector must contain three doubles")
        }
        return try {
            EntityVector3d(
                x = (values[0] as? NbtDouble)?.value ?: wrongVectorComponent(0),
                y = (values[1] as? NbtDouble)?.value ?: wrongVectorComponent(1),
                z = (values[2] as? NbtDouble)?.value ?: wrongVectorComponent(2),
            )
        } catch (failure: IllegalArgumentException) {
            throw SerializationException("An Entity vector must contain finite coordinates", failure)
        }
    }
}

/** Encodes Entity yaw and pitch as the official two-float NBT list. */
object NbtEntityRotationSerializer : KSerializer<EntityRotation> {
    override val descriptor: SerialDescriptor = NbtList.serializer().descriptor

    override fun serialize(encoder: Encoder, value: EntityRotation) {
        encoder.encodeSerializableValue(
            NbtList.serializer(),
            NbtList(listOf(NbtFloat(value.yaw), NbtFloat(value.pitch))),
        )
    }

    override fun deserialize(decoder: Decoder): EntityRotation {
        val values = decoder.decodeSerializableValue(NbtList.serializer())
        if (values.size != ROTATION_COMPONENT_COUNT) {
            throw SerializationException("An Entity rotation must contain yaw and pitch")
        }
        return try {
            EntityRotation(
                yaw = (values[0] as? NbtFloat)?.value ?: wrongRotationComponent("yaw"),
                pitch = (values[1] as? NbtFloat)?.value ?: wrongRotationComponent("pitch"),
            )
        } catch (failure: IllegalArgumentException) {
            throw SerializationException("An Entity rotation must contain finite angles", failure)
        }
    }
}

internal fun Uuid.toNbtIntArray(): NbtIntArray {
    val bytes = toByteArray()
    return NbtIntArray(IntArray(UUID_INT_COUNT) { index ->
        val offset = index * Int.SIZE_BYTES
        ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    })
}

internal fun NbtIntArray.toUuid(): Uuid {
    if (size != UUID_INT_COUNT) throw SerializationException("A UUID must contain four integers")
    val bytes = ByteArray(Uuid.SIZE_BYTES)
    repeat(UUID_INT_COUNT) { index ->
        val value = this[index]
        val offset = index * Int.SIZE_BYTES
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
    return Uuid.fromByteArray(bytes)
}

private fun wrongVectorComponent(index: Int): Nothing =
    throw SerializationException("Entity vector component $index must be TAG_Double")

private fun wrongRotationComponent(name: String): Nothing =
    throw SerializationException("Entity rotation $name must be TAG_Float")

private const val BLOCK_POSITION_COMPONENT_COUNT = 3
private const val CHUNK_POSITION_COMPONENT_COUNT = 2
private const val VECTOR_COMPONENT_COUNT = 3
private const val ROTATION_COMPONENT_COUNT = 2
private const val UUID_INT_COUNT = Uuid.SIZE_BYTES / Int.SIZE_BYTES
