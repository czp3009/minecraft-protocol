package com.hiczp.minecraft.world.format

import kotlinx.serialization.Serializable

/** A namespaced saved-data identity independent of its filesystem placement. */
@Serializable(with = SavedDataIdSerializer::class)
data class SavedDataId(
    val path: String,
    val namespace: String = DEFAULT_MINECRAFT_NAMESPACE,
) {
    init {
        validateNamespacedValue(namespace, path, "saved-data")
    }

    override fun toString(): String = "$namespace:$path"

    companion object {
        fun parse(value: String): SavedDataId = parseNamespacedValue(value) { namespace, path ->
            SavedDataId(path, namespace)
        }
    }
}

internal object SavedDataIdSerializer : NamespacedValueSerializer<SavedDataId>(
    "com.hiczp.minecraft.world.format.SavedDataId",
) {
    override fun parse(value: String): SavedDataId = SavedDataId.parse(value)
}
