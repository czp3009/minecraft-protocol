package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.Serializable

/** Immutable wire value corresponding to the official PositionAndRotation interface. */
@Serializable
data class PositionAndRotation(val position: Vector3d, val yRot: Float, val xRot: Float)
