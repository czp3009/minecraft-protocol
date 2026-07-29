@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class EntityAnchor {
    FEET,
    EYES,
}

@Serializable(with = LookTargetSerializer::class)
sealed interface LookTarget {
    val fallbackPosition: Vector3d

    @Serializable
    data class Position(
        override val fallbackPosition: Vector3d,
    ) : LookTarget

    @Serializable
    data class Entity(
        override val fallbackPosition: Vector3d,
        val entityId: Int,
        val anchor: EntityAnchor,
    ) : LookTarget
}

internal object LookTargetSerializer : KSerializer<LookTarget> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.LookTarget",
    ) {
        element<Double>("x")
        element<Double>("y")
        element<Double>("z")
        element<Boolean>("isEntity")
        element<Int>("entityId", annotations = listOf(VarInt()), isOptional = true)
        element<EntityAnchor>("anchor", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: LookTarget) {
        val output = encoder.beginStructure(descriptor)
        output.encodeDoubleElement(descriptor, X, value.fallbackPosition.x)
        output.encodeDoubleElement(descriptor, Y, value.fallbackPosition.y)
        output.encodeDoubleElement(descriptor, Z, value.fallbackPosition.z)
        when (value) {
            is LookTarget.Position -> {
                output.encodeBooleanElement(descriptor, IS_ENTITY, false)
            }

            is LookTarget.Entity -> {
                output.encodeBooleanElement(descriptor, IS_ENTITY, true)
                output.encodeIntElement(descriptor, ENTITY_ID, value.entityId)
                output.encodeSerializableElement(
                    descriptor,
                    ANCHOR,
                    EntityAnchor.serializer(),
                    value.anchor,
                )
            }
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): LookTarget {
        val input = decoder.beginStructure(descriptor)
        if (input.decodeSequentially()) {
            val position = Vector3d(
                input.decodeDoubleElement(descriptor, X),
                input.decodeDoubleElement(descriptor, Y),
                input.decodeDoubleElement(descriptor, Z),
            )
            val result = if (input.decodeBooleanElement(descriptor, IS_ENTITY)) {
                LookTarget.Entity(
                    position,
                    input.decodeIntElement(descriptor, ENTITY_ID),
                    input.decodeSerializableElement(
                        descriptor,
                        ANCHOR,
                        EntityAnchor.serializer(),
                    ),
                )
            } else {
                LookTarget.Position(position)
            }
            input.endStructure(descriptor)
            return result
        }

        var x: Double? = null
        var y: Double? = null
        var z: Double? = null
        var isEntity: Boolean? = null
        var entityId: Int? = null
        var anchor: EntityAnchor? = null
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                X -> x = input.decodeDoubleElement(descriptor, X)
                Y -> y = input.decodeDoubleElement(descriptor, Y)
                Z -> z = input.decodeDoubleElement(descriptor, Z)
                IS_ENTITY -> isEntity =
                    input.decodeBooleanElement(descriptor, IS_ENTITY)

                ENTITY_ID -> entityId =
                    input.decodeIntElement(descriptor, ENTITY_ID)

                ANCHOR -> anchor = input.decodeSerializableElement(
                    descriptor,
                    ANCHOR,
                    EntityAnchor.serializer(),
                )

                -1 -> break
                else -> throw SerializationException(
                    "Unexpected LookTarget field $index",
                )
            }
        }
        input.endStructure(descriptor)
        val position = Vector3d(
            x ?: throw SerializationException("Missing look target X"),
            y ?: throw SerializationException("Missing look target Y"),
            z ?: throw SerializationException("Missing look target Z"),
        )
        return when (isEntity) {
            false -> LookTarget.Position(position)
            true -> LookTarget.Entity(
                position,
                entityId ?: throw SerializationException(
                    "Missing look target entity ID",
                ),
                anchor ?: throw SerializationException(
                    "Missing look target entity anchor",
                ),
            )

            null -> throw SerializationException("Missing isEntity discriminator")
        }
    }

    private const val X: Int = 0
    private const val Y: Int = 1
    private const val Z: Int = 2
    private const val IS_ENTITY: Int = 3
    private const val ENTITY_ID: Int = 4
    private const val ANCHOR: Int = 5
}

@Serializable
enum class RelativeMovement {
    X,
    Y,
    Z,
    YAW,
    PITCH,
    VELOCITY_X,
    VELOCITY_Y,
    VELOCITY_Z,
    ROTATE_VELOCITY,
}

@Serializable(with = RelativeMovementsSerializer::class)
data class RelativeMovements(
    val values: Set<RelativeMovement>,
)

internal object RelativeMovementsSerializer : KSerializer<RelativeMovements> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "minecraft.RelativeMovements",
        PrimitiveKind.INT,
    )

    override fun serialize(encoder: Encoder, value: RelativeMovements) {
        var flags = 0
        for (relative in value.values) {
            flags = flags or (1 shl relative.ordinal)
        }
        encoder.encodeInt(flags)
    }

    override fun deserialize(decoder: Decoder): RelativeMovements {
        val flags = decoder.decodeInt()
        return RelativeMovements(
            RelativeMovement.entries.filterTo(linkedSetOf()) {
                flags and (1 shl it.ordinal) != 0
            },
        )
    }
}

@Serializable(with = SectionPositionSerializer::class)
data class SectionPosition(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    init {
        require(x in MIN_XZ..MAX_XZ) { "section x is outside 22 bits: $x" }
        require(z in MIN_XZ..MAX_XZ) { "section z is outside 22 bits: $z" }
        require(y in MIN_Y..MAX_Y) { "section y is outside 20 bits: $y" }
    }

    fun packed(): Long =
        ((x.toLong() and XZ_MASK) shl 42) or
                ((z.toLong() and XZ_MASK) shl 20) or
                (y.toLong() and Y_MASK)

    companion object {
        const val MIN_XZ: Int = -2_097_152
        const val MAX_XZ: Int = 2_097_151
        const val MIN_Y: Int = -524_288
        const val MAX_Y: Int = 524_287

        private const val XZ_MASK: Long = 0x3F_FFFF
        private const val Y_MASK: Long = 0xF_FFFF

        fun fromPacked(packed: Long): SectionPosition = SectionPosition(
            x = (packed shr 42).toInt(),
            y = (packed shl 44 shr 44).toInt(),
            z = (packed shl 22 shr 42).toInt(),
        )
    }
}

internal object SectionPositionSerializer : KSerializer<SectionPosition> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "minecraft.SectionPosition",
        PrimitiveKind.LONG,
    )

    override fun serialize(encoder: Encoder, value: SectionPosition) {
        encoder.encodeLong(value.packed())
    }

    override fun deserialize(decoder: Decoder): SectionPosition =
        SectionPosition.fromPacked(decoder.decodeLong())
}

@Serializable(with = SectionBlockChangeSerializer::class)
data class SectionBlockChange(
    val blockStateId: Int,
    val localX: Int,
    val localY: Int,
    val localZ: Int,
) {
    init {
        require(blockStateId >= 0) { "block state ID must be non-negative" }
        require(localX in 0..15) { "local X must be in 0..15" }
        require(localY in 0..15) { "local Y must be in 0..15" }
        require(localZ in 0..15) { "local Z must be in 0..15" }
    }

    fun packed(): Long =
        (blockStateId.toLong() shl 12) or
                (localX.toLong() shl 8) or
                (localZ.toLong() shl 4) or
                localY.toLong()

    companion object {
        fun fromPacked(packed: Long): SectionBlockChange =
            SectionBlockChange(
                blockStateId = (packed ushr 12).toInt(),
                localX = ((packed ushr 8) and 0xF).toInt(),
                localY = (packed and 0xF).toInt(),
                localZ = ((packed ushr 4) and 0xF).toInt(),
            )
    }
}

internal object SectionBlockChangeSerializer : KSerializer<SectionBlockChange> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "minecraft.SectionBlockChange",
        PrimitiveKind.LONG,
    )

    override fun serialize(encoder: Encoder, value: SectionBlockChange) {
        encoder.encodeLong(value.packed())
    }

    override fun deserialize(decoder: Decoder): SectionBlockChange =
        SectionBlockChange.fromPacked(decoder.decodeLong())
}

@Serializable
data class RecipeBookTypeSettings(
    val open: Boolean,
    val filtering: Boolean,
)

@Serializable
enum class RecipeBookCategory {
    CRAFTING,
    FURNACE,
    BLAST_FURNACE,
    SMOKER,
}

@Serializable
data class RecipeBookSettings(
    val crafting: RecipeBookTypeSettings,
    val furnace: RecipeBookTypeSettings,
    val blastFurnace: RecipeBookTypeSettings,
    val smoker: RecipeBookTypeSettings,
)

@Serializable
data class GlobalPosition(
    val dimension: Identifier,
    val position: BlockPosition,
)

@Serializable
data class RespawnData(
    val globalPosition: GlobalPosition,
    val yaw: Float,
    val pitch: Float,
)
