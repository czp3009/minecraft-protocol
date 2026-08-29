package com.hiczp.minecraft.world.format

import kotlinx.serialization.Serializable

/** A namespaced entry in Minecraft's dimension-type registry. */
@Serializable(with = DimensionTypeIdSerializer::class)
data class DimensionTypeId(
    val path: String,
    val namespace: String = DEFAULT_MINECRAFT_NAMESPACE,
) {
    init {
        validateNamespacedValue(namespace, path, "dimension-type")
    }

    override fun toString(): String = "$namespace:$path"

    companion object {
        fun parse(value: String): DimensionTypeId = parseNamespacedValue(value) { namespace, path ->
            DimensionTypeId(path, namespace)
        }
    }
}

internal object DimensionTypeIdSerializer : NamespacedValueSerializer<DimensionTypeId>(
    "com.hiczp.minecraft.world.format.DimensionTypeId",
) {
    override fun parse(value: String): DimensionTypeId = DimensionTypeId.parse(value)
}
