package com.hiczp.minecraft.world.io

/** A namespaced dimension directory below `dimensions`, using the selected-release layout. */
data class DimensionId(
    val path: String,
    val namespace: String = DEFAULT_STORAGE_NAMESPACE,
) {
    internal val pathSegments: List<String> = parseStoragePath(path, "dimension")

    init {
        validateStorageNamespace(namespace, "dimension")
    }

    override fun toString(): String = "$namespace:$path"

    companion object {
        val Overworld: DimensionId = DimensionId("overworld")
        val Nether: DimensionId = DimensionId("the_nether")
        val End: DimensionId = DimensionId("the_end")
    }
}
