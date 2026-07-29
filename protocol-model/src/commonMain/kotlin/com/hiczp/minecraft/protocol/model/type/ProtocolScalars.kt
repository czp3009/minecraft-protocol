package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** One unsigned byte interpreted as 1/256 of a full turn. */
@Serializable
@JvmInline
value class Angle(val steps: Byte) {
    val degrees: Float
        get() = (steps.toInt() and 0xFF) * (360.0f / 256.0f)

    companion object {
        fun fromDegrees(degrees: Float): Angle =
            Angle((degrees * (256.0f / 360.0f)).toInt().toByte())
    }
}

/** A three-dimensional vector independent of its protocol wire representation. */
@Serializable
data class Vector3d(
    val x: Double,
    val y: Double,
    val z: Double,
)

/** Absolute position, delta movement, and rotations used by entity sync packets. */
@Serializable
data class PositionMoveRotation(
    val position: Vector3d,
    val deltaMovement: Vector3d,
    val yaw: Float,
    val pitch: Float,
)

/** A text component whose wire representation is the legacy JSON string form. */
@Serializable
@JvmInline
value class JsonTextComponent(val json: String)
