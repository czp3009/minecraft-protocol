@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.VarInt
import com.hiczp.minecraft.protocol.model.wire.WrappedEnum
import com.hiczp.minecraft.protocol.model.wire.ZeroFallbackEnum
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

@Serializable
enum class CommandBlockMode {
    SEQUENCE,
    AUTO,
    REDSTONE,
}

@Serializable(with = CommandBlockFlagsSerializer::class)
data class CommandBlockFlags(
    val trackOutput: Boolean,
    val conditional: Boolean,
    val automatic: Boolean,
)

internal object CommandBlockFlagsSerializer : KSerializer<CommandBlockFlags> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.CommandBlockFlags",
    ) {
        element<Byte>("flags")
    }

    override fun serialize(encoder: Encoder, value: CommandBlockFlags) {
        var flags = 0
        if (value.trackOutput) flags = flags or TRACK_OUTPUT
        if (value.conditional) flags = flags or CONDITIONAL
        if (value.automatic) flags = flags or AUTOMATIC
        val output = encoder.beginStructure(descriptor)
        output.encodeByteElement(descriptor, FLAGS, flags.toByte())
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): CommandBlockFlags {
        val input = decoder.beginStructure(descriptor)
        var flags: Byte = 0
        if (input.decodeSequentially()) {
            flags = input.decodeByteElement(descriptor, FLAGS)
        } else {
            while (true) {
                when (val index = input.decodeElementIndex(descriptor)) {
                    FLAGS -> flags = input.decodeByteElement(descriptor, FLAGS)
                    -1 -> break
                    else -> error("Unexpected CommandBlockFlags field $index")
                }
            }
        }
        input.endStructure(descriptor)
        val bits = flags.toInt()
        return CommandBlockFlags(
            trackOutput = bits and TRACK_OUTPUT != 0,
            conditional = bits and CONDITIONAL != 0,
            automatic = bits and AUTOMATIC != 0,
        )
    }

    private const val FLAGS: Int = 0
    private const val TRACK_OUTPUT: Int = 0x01
    private const val CONDITIONAL: Int = 0x02
    private const val AUTOMATIC: Int = 0x04
}

@Serializable(with = JigsawJointSerializer::class)
enum class JigsawJoint(val wireName: String) {
    ROLLABLE("rollable"),
    ALIGNED("aligned"),
}

internal object JigsawJointSerializer : KSerializer<JigsawJoint> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "minecraft.JigsawJoint",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: JigsawJoint) {
        encoder.encodeString(value.wireName)
    }

    override fun deserialize(decoder: Decoder): JigsawJoint {
        val value = decoder.decodeString()
        return JigsawJoint.entries.firstOrNull { it.wireName == value }
            ?: JigsawJoint.ALIGNED
    }
}

@Serializable
enum class StructureUpdateAction {
    UPDATE_DATA,
    SAVE_AREA,
    LOAD_AREA,
    SCAN_AREA,
}

@Serializable
enum class StructureMode {
    SAVE,
    LOAD,
    CORNER,
    DATA,
}

@Serializable
enum class StructureMirror {
    NONE,
    LEFT_RIGHT,
    FRONT_BACK,
}

@Serializable
enum class StructureRotation {
    NONE,
    CLOCKWISE_90,
    CLOCKWISE_180,
    COUNTERCLOCKWISE_90,
}

@Serializable(with = StructureOffsetSerializer::class)
data class StructureOffset(
    val x: Int,
    val y: Int,
    val z: Int,
)

internal object StructureOffsetSerializer : KSerializer<StructureOffset> {
    override val descriptor: SerialDescriptor = byteVectorDescriptor(
        "minecraft.StructureOffset",
    )

    override fun serialize(encoder: Encoder, value: StructureOffset) {
        val output = encoder.beginStructure(descriptor)
        output.encodeByteElement(descriptor, X, value.x.toByte())
        output.encodeByteElement(descriptor, Y, value.y.toByte())
        output.encodeByteElement(descriptor, Z, value.z.toByte())
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): StructureOffset {
        val byteVector = decodeByteVector(decoder, descriptor)
        return StructureOffset(
            byteVector.x.coerceIn(-MAXIMUM, MAXIMUM),
            byteVector.y.coerceIn(-MAXIMUM, MAXIMUM),
            byteVector.z.coerceIn(-MAXIMUM, MAXIMUM),
        )
    }

    private const val MAXIMUM: Int = 48
}

@Serializable(with = StructureSizeSerializer::class)
data class StructureSize(
    val x: Int,
    val y: Int,
    val z: Int,
)

internal object StructureSizeSerializer : KSerializer<StructureSize> {
    override val descriptor: SerialDescriptor = byteVectorDescriptor(
        "minecraft.StructureSize",
    )

    override fun serialize(encoder: Encoder, value: StructureSize) {
        val output = encoder.beginStructure(descriptor)
        output.encodeByteElement(descriptor, X, value.x.toByte())
        output.encodeByteElement(descriptor, Y, value.y.toByte())
        output.encodeByteElement(descriptor, Z, value.z.toByte())
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): StructureSize {
        val byteVector = decodeByteVector(decoder, descriptor)
        return StructureSize(
            byteVector.x.coerceIn(0, MAXIMUM),
            byteVector.y.coerceIn(0, MAXIMUM),
            byteVector.z.coerceIn(0, MAXIMUM),
        )
    }

    private const val MAXIMUM: Int = 48
}

@Serializable(with = StructureIntegritySerializer::class)
@JvmInline
value class StructureIntegrity(val value: Float)

internal object StructureIntegritySerializer : KSerializer<StructureIntegrity> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "minecraft.StructureIntegrity",
        PrimitiveKind.FLOAT,
    )

    override fun serialize(encoder: Encoder, value: StructureIntegrity) {
        encoder.encodeFloat(value.value)
    }

    override fun deserialize(decoder: Decoder): StructureIntegrity =
        StructureIntegrity(decoder.decodeFloat().coerceIn(0.0f, 1.0f))
}

@Serializable(with = StructureBlockFlagsSerializer::class)
data class StructureBlockFlags(
    val ignoreEntities: Boolean,
    val showAir: Boolean,
    val showBoundingBox: Boolean,
    val strictPlacement: Boolean,
)

internal object StructureBlockFlagsSerializer : KSerializer<StructureBlockFlags> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.StructureBlockFlags",
    ) {
        element<Byte>("flags")
    }

    override fun serialize(encoder: Encoder, value: StructureBlockFlags) {
        var flags = 0
        if (value.ignoreEntities) flags = flags or IGNORE_ENTITIES
        if (value.showAir) flags = flags or SHOW_AIR
        if (value.showBoundingBox) flags = flags or SHOW_BOUNDING_BOX
        if (value.strictPlacement) flags = flags or STRICT_PLACEMENT
        val output = encoder.beginStructure(descriptor)
        output.encodeByteElement(descriptor, FLAGS, flags.toByte())
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): StructureBlockFlags {
        val input = decoder.beginStructure(descriptor)
        var flags: Byte = 0
        if (input.decodeSequentially()) {
            flags = input.decodeByteElement(descriptor, FLAGS)
        } else {
            while (true) {
                when (val index = input.decodeElementIndex(descriptor)) {
                    FLAGS -> flags = input.decodeByteElement(descriptor, FLAGS)
                    -1 -> break
                    else -> error("Unexpected StructureBlockFlags field $index")
                }
            }
        }
        input.endStructure(descriptor)
        val bits = flags.toInt()
        return StructureBlockFlags(
            ignoreEntities = bits and IGNORE_ENTITIES != 0,
            showAir = bits and SHOW_AIR != 0,
            showBoundingBox = bits and SHOW_BOUNDING_BOX != 0,
            strictPlacement = bits and STRICT_PLACEMENT != 0,
        )
    }

    private const val FLAGS: Int = 0
    private const val IGNORE_ENTITIES: Int = 0x01
    private const val SHOW_AIR: Int = 0x02
    private const val SHOW_BOUNDING_BOX: Int = 0x04
    private const val STRICT_PLACEMENT: Int = 0x08
}

@Serializable
enum class TestBlockMode {
    START,
    LOG,
    FAIL,
    ACCEPT,
}

@Serializable
enum class TestInstanceAction {
    INIT,
    QUERY,
    SET,
    RESET,
    SAVE,
    EXPORT,
    RUN,
}

@Serializable
enum class TestInstanceStatus {
    CLEARED,
    RUNNING,
    FINISHED,
}

@Serializable
data class TestInstanceSize(
    @VarInt
    val x: Int,
    @VarInt
    val y: Int,
    @VarInt
    val z: Int,
)

@Serializable
data class TestInstanceData(
    val test: Identifier?,
    val size: TestInstanceSize,
    @WrappedEnum
    val rotation: StructureRotation,
    val ignoreEntities: Boolean,
    @ZeroFallbackEnum
    val status: TestInstanceStatus,
    val errorMessage: TextComponent?,
)

private data class ByteVector(
    val x: Int,
    val y: Int,
    val z: Int,
)

private fun byteVectorDescriptor(name: String): SerialDescriptor =
    buildClassSerialDescriptor(name) {
        element<Byte>("x")
        element<Byte>("y")
        element<Byte>("z")
    }

private fun decodeByteVector(
    decoder: Decoder,
    serialDescriptor: SerialDescriptor,
): ByteVector {
    val input = decoder.beginStructure(serialDescriptor)
    var x: Byte = 0
    var y: Byte = 0
    var z: Byte = 0
    if (input.decodeSequentially()) {
        x = input.decodeByteElement(serialDescriptor, X)
        y = input.decodeByteElement(serialDescriptor, Y)
        z = input.decodeByteElement(serialDescriptor, Z)
    } else {
        while (true) {
            when (val index = input.decodeElementIndex(serialDescriptor)) {
                X -> x = input.decodeByteElement(serialDescriptor, X)
                Y -> y = input.decodeByteElement(serialDescriptor, Y)
                Z -> z = input.decodeByteElement(serialDescriptor, Z)
                -1 -> break
                else -> error("Unexpected byte-vector field $index")
            }
        }
    }
    input.endStructure(serialDescriptor)
    return ByteVector(x.toInt(), y.toInt(), z.toInt())
}

private const val X: Int = 0
private const val Y: Int = 1
private const val Z: Int = 2
