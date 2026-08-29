package com.hiczp.minecraft.world.format

import kotlinx.serialization.Serializable

/** A namespaced world dimension identity independent of its filesystem placement. */
@Serializable(with = DimensionIdSerializer::class)
data class DimensionId(
    val path: String,
    val namespace: String = DEFAULT_MINECRAFT_NAMESPACE,
) {
    init {
        validateNamespacedValue(namespace, path, "dimension")
    }

    override fun toString(): String = "$namespace:$path"

    companion object {
        val Overworld: DimensionId = DimensionId("overworld")
        val Nether: DimensionId = DimensionId("the_nether")
        val End: DimensionId = DimensionId("the_end")

        fun parse(value: String): DimensionId = parseNamespacedValue(value) { namespace, path ->
            DimensionId(path, namespace)
        }
    }
}

internal object DimensionIdSerializer : NamespacedValueSerializer<DimensionId>(
    "com.hiczp.minecraft.world.format.DimensionId",
) {
    override fun parse(value: String): DimensionId = DimensionId.parse(value)
}
