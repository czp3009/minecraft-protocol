package com.hiczp.minecraft.protocol.model.type

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
@Serializable(with = JsonTextComponentSerializer::class)
@JvmInline
value class JsonTextComponent(val json: String) {
    companion object {
        fun literal(text: String): JsonTextComponent = JsonTextComponent(
            buildJsonObject { put("text", text) }.toString(),
        )
    }
}

internal object JsonTextComponentSerializer : KSerializer<JsonTextComponent> {
    override val descriptor = PrimitiveSerialDescriptor(
        "minecraft.JsonTextComponent",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: JsonTextComponent) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(Json.parseToJsonElement(value.json))
        } else {
            encoder.encodeString(value.json)
        }
    }

    override fun deserialize(decoder: Decoder): JsonTextComponent =
        if (decoder is JsonDecoder) {
            JsonTextComponent(decoder.decodeJsonElement().toString())
        } else {
            JsonTextComponent(decoder.decodeString())
        }
}
