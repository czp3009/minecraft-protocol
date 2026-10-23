package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.Serializable

/** The face of a sign targeted by editing and text update packets. */
@Serializable
enum class SignTextSlot { BACK, FRONT }
