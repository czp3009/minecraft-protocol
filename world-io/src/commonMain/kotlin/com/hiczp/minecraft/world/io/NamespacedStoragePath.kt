package com.hiczp.minecraft.world.io

internal const val DEFAULT_STORAGE_NAMESPACE = "minecraft"

internal fun validateStorageNamespace(namespace: String, description: String) {
    require(
        namespace.matches(STORAGE_NAMESPACE_PATTERN) &&
                namespace != "." && namespace != "..",
    ) {
        "Invalid $description namespace: $namespace"
    }
}

internal fun parseStoragePath(path: String, description: String): List<String> {
    val pathSegments = path.split('/')
    require(
        pathSegments.isNotEmpty() &&
                pathSegments.all {
                    it.matches(STORAGE_PATH_SEGMENT_PATTERN) &&
                            it != "." && it != ".."
                },
    ) {
        "Invalid $description path: $path"
    }
    return pathSegments
}

private val STORAGE_NAMESPACE_PATTERN = Regex("[a-z0-9._-]+")
private val STORAGE_PATH_SEGMENT_PATTERN = Regex("[a-z0-9._-]+")
