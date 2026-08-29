package com.hiczp.minecraft.world.io

/** A namespaced saved-data file below a world or dimension `data` directory. */
data class SavedDataId(
    val path: String,
    val namespace: String = DEFAULT_STORAGE_NAMESPACE,
) {
    internal val pathSegments: List<String> = parseStoragePath(path, "saved-data")

    init {
        validateStorageNamespace(namespace, "saved-data")
    }

    override fun toString(): String = "$namespace:$path"
}
