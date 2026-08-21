package com.hiczp.minecraft.world.format

/**
 * Compression streams used by Minecraft world storage.
 *
 * The same streams back Anvil region-chunk payloads and standalone NBT files
 * such as `level.dat`. Numeric wire IDs exist only inside the region container
 * and are owned by [RegionChunkRecordHeader]; standalone files select their
 * compression explicitly and store no ID.
 */
enum class Compression {
    GZIP,
    ZLIB,
    NONE,
    LZ4,
    CUSTOM,
}

/** Invalid compression framing or a missing registered custom codec. */
class CompressionFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
