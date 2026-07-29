@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class MinecartStep(
    val position: Vector3d,
    val velocity: Vector3d,
    val yaw: Angle,
    val pitch: Angle,
    val weight: Float,
)

/**
 * Logical expansion of the four low bits in the player-abilities flags byte.
 * Unknown high bits accepted from the network are intentionally discarded,
 * matching vanilla's decode/re-encode behavior.
 */
@Serializable(with = PlayerAbilitiesSerializer::class)
data class PlayerAbilities(
    val invulnerable: Boolean,
    val flying: Boolean,
    val canFly: Boolean,
    val instantBuild: Boolean,
    val flyingSpeed: Float,
    val walkingSpeed: Float,
)

internal object PlayerAbilitiesSerializer : KSerializer<PlayerAbilities> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.PlayerAbilities",
    ) {
        element<Byte>("flags")
        element<Float>("flyingSpeed")
        element<Float>("walkingSpeed")
    }

    override fun serialize(encoder: Encoder, value: PlayerAbilities) {
        var flags = 0
        if (value.invulnerable) flags = flags or INVULNERABLE
        if (value.flying) flags = flags or FLYING
        if (value.canFly) flags = flags or CAN_FLY
        if (value.instantBuild) flags = flags or INSTANT_BUILD

        val output = encoder.beginStructure(descriptor)
        output.encodeByteElement(descriptor, FLAGS, flags.toByte())
        output.encodeFloatElement(descriptor, FLYING_SPEED, value.flyingSpeed)
        output.encodeFloatElement(descriptor, WALKING_SPEED, value.walkingSpeed)
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): PlayerAbilities {
        val input = decoder.beginStructure(descriptor)
        var flags: Byte = 0
        var flyingSpeed = 0.0f
        var walkingSpeed = 0.0f
        if (input.decodeSequentially()) {
            flags = input.decodeByteElement(descriptor, FLAGS)
            flyingSpeed = input.decodeFloatElement(descriptor, FLYING_SPEED)
            walkingSpeed = input.decodeFloatElement(descriptor, WALKING_SPEED)
        } else {
            while (true) {
                when (val index = input.decodeElementIndex(descriptor)) {
                    FLAGS -> flags = input.decodeByteElement(descriptor, FLAGS)
                    FLYING_SPEED -> flyingSpeed =
                        input.decodeFloatElement(descriptor, FLYING_SPEED)

                    WALKING_SPEED -> walkingSpeed =
                        input.decodeFloatElement(descriptor, WALKING_SPEED)

                    -1 -> break
                    else -> error("Unexpected PlayerAbilities field $index")
                }
            }
        }
        input.endStructure(descriptor)
        val bits = flags.toInt()
        return PlayerAbilities(
            invulnerable = bits and INVULNERABLE != 0,
            flying = bits and FLYING != 0,
            canFly = bits and CAN_FLY != 0,
            instantBuild = bits and INSTANT_BUILD != 0,
            flyingSpeed = flyingSpeed,
            walkingSpeed = walkingSpeed,
        )
    }

    private const val FLAGS: Int = 0
    private const val FLYING_SPEED: Int = 1
    private const val WALKING_SPEED: Int = 2
    private const val INVULNERABLE: Int = 0x01
    private const val FLYING: Int = 0x02
    private const val CAN_FLY: Int = 0x04
    private const val INSTANT_BUILD: Int = 0x08
}

/**
 * Logical view of the two low bits shared by all serverbound movement packets.
 * Unknown bits are discarded by vanilla while decoding.
 */
@Serializable(with = PlayerMovementFlagsSerializer::class)
data class PlayerMovementFlags(
    val onGround: Boolean,
    val horizontalCollision: Boolean,
)

internal object PlayerMovementFlagsSerializer : KSerializer<PlayerMovementFlags> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.PlayerMovementFlags",
    ) {
        element<Byte>("flags")
    }

    override fun serialize(encoder: Encoder, value: PlayerMovementFlags) {
        var flags = 0
        if (value.onGround) flags = flags or ON_GROUND
        if (value.horizontalCollision) flags = flags or HORIZONTAL_COLLISION
        val output = encoder.beginStructure(descriptor)
        output.encodeByteElement(descriptor, FLAGS, flags.toByte())
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): PlayerMovementFlags {
        val input = decoder.beginStructure(descriptor)
        var flags: Byte = 0
        if (input.decodeSequentially()) {
            flags = input.decodeByteElement(descriptor, FLAGS)
        } else {
            while (true) {
                when (val index = input.decodeElementIndex(descriptor)) {
                    FLAGS -> flags = input.decodeByteElement(descriptor, FLAGS)
                    -1 -> break
                    else -> error("Unexpected PlayerMovementFlags field $index")
                }
            }
        }
        input.endStructure(descriptor)
        val bits = flags.toInt()
        return PlayerMovementFlags(
            onGround = bits and ON_GROUND != 0,
            horizontalCollision = bits and HORIZONTAL_COLLISION != 0,
        )
    }

    private const val FLAGS: Int = 0
    private const val ON_GROUND: Int = 0x01
    private const val HORIZONTAL_COLLISION: Int = 0x02
}

/**
 * The serverbound abilities packet carries only the flying bit (mask 0x02).
 */
@Serializable(with = ServerboundAbilitiesSerializer::class)
data class ServerboundAbilities(
    val flying: Boolean,
)

internal object ServerboundAbilitiesSerializer : KSerializer<ServerboundAbilities> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.ServerboundAbilities",
    ) {
        element<Byte>("flags")
    }

    override fun serialize(encoder: Encoder, value: ServerboundAbilities) {
        val output = encoder.beginStructure(descriptor)
        output.encodeByteElement(
            descriptor,
            FLAGS,
            if (value.flying) FLYING.toByte() else 0,
        )
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ServerboundAbilities {
        val input = decoder.beginStructure(descriptor)
        var flags: Byte = 0
        if (input.decodeSequentially()) {
            flags = input.decodeByteElement(descriptor, FLAGS)
        } else {
            while (true) {
                when (val index = input.decodeElementIndex(descriptor)) {
                    FLAGS -> flags = input.decodeByteElement(descriptor, FLAGS)
                    -1 -> break
                    else -> error("Unexpected ServerboundAbilities field $index")
                }
            }
        }
        input.endStructure(descriptor)
        return ServerboundAbilities(flags.toInt() and FLYING != 0)
    }

    private const val FLAGS: Int = 0
    private const val FLYING: Int = 0x02
}

/**
 * Logical expansion of the seven input bits sent by modern clients.
 */
@Serializable(with = PlayerInputSerializer::class)
data class PlayerInput(
    val forward: Boolean,
    val backward: Boolean,
    val left: Boolean,
    val right: Boolean,
    val jump: Boolean,
    val shift: Boolean,
    val sprint: Boolean,
)

internal object PlayerInputSerializer : KSerializer<PlayerInput> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.PlayerInput",
    ) {
        element<Byte>("flags")
    }

    override fun serialize(encoder: Encoder, value: PlayerInput) {
        var flags = 0
        if (value.forward) flags = flags or FORWARD
        if (value.backward) flags = flags or BACKWARD
        if (value.left) flags = flags or LEFT
        if (value.right) flags = flags or RIGHT
        if (value.jump) flags = flags or JUMP
        if (value.shift) flags = flags or SHIFT
        if (value.sprint) flags = flags or SPRINT
        val output = encoder.beginStructure(descriptor)
        output.encodeByteElement(descriptor, FLAGS, flags.toByte())
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): PlayerInput {
        val input = decoder.beginStructure(descriptor)
        var flags: Byte = 0
        if (input.decodeSequentially()) {
            flags = input.decodeByteElement(descriptor, FLAGS)
        } else {
            while (true) {
                when (val index = input.decodeElementIndex(descriptor)) {
                    FLAGS -> flags = input.decodeByteElement(descriptor, FLAGS)
                    -1 -> break
                    else -> error("Unexpected PlayerInput field $index")
                }
            }
        }
        input.endStructure(descriptor)
        val bits = flags.toInt()
        return PlayerInput(
            forward = bits and FORWARD != 0,
            backward = bits and BACKWARD != 0,
            left = bits and LEFT != 0,
            right = bits and RIGHT != 0,
            jump = bits and JUMP != 0,
            shift = bits and SHIFT != 0,
            sprint = bits and SPRINT != 0,
        )
    }

    private const val FLAGS: Int = 0
    private const val FORWARD: Int = 0x01
    private const val BACKWARD: Int = 0x02
    private const val LEFT: Int = 0x04
    private const val RIGHT: Int = 0x08
    private const val JUMP: Int = 0x10
    private const val SHIFT: Int = 0x20
    private const val SPRINT: Int = 0x40
}
