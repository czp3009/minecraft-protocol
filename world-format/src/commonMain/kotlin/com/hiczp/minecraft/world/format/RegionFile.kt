package com.hiczp.minecraft.world.format

/** Absolute chunk coordinates in a Minecraft dimension. */
data class ChunkPosition(
    val x: Int,
    val z: Int,
) {
    val region: RegionPosition
        get() = RegionPosition(floorDiv(x, REGION_SIDE), floorDiv(z, REGION_SIDE))

    val local: LocalChunkPosition
        get() = LocalChunkPosition(floorMod(x, REGION_SIDE), floorMod(z, REGION_SIDE))
}

/** Absolute region coordinates in a Minecraft dimension. */
data class RegionPosition(
    val x: Int,
    val z: Int,
) {
    /** Returns whether [position] belongs to this 32 by 32 Region. */
    operator fun contains(position: ChunkPosition): Boolean = position.region == this

    /** Converts an absolute [position] in this Region to its Region-local coordinates. */
    fun local(position: ChunkPosition): LocalChunkPosition {
        require(position in this) { "Chunk $position does not belong to Region $this" }
        return position.local
    }

    /** Converts Region-local coordinates to an absolute Chunk position. */
    fun chunk(local: LocalChunkPosition): ChunkPosition =
        ChunkPosition(
            x = x * REGION_SIDE + local.x,
            z = z * REGION_SIDE + local.z,
        )

    /**
     * All absolute chunk positions covered by this region, in Anvil header-index order.
     *
     * This describes coordinate coverage, not the chunks actually present in an `.mca` file.
     */
    fun chunkPositions(): Sequence<ChunkPosition> =
        (0 until REGION_CHUNK_COUNT).asSequence().map { index ->
            chunk(LocalChunkPosition.fromIndex(index))
        }
}

/** A chunk coordinate inside one 32 by 32 Anvil region. */
data class LocalChunkPosition(
    val x: Int,
    val z: Int,
) {
    init {
        require(x in 0 until REGION_SIDE)
        require(z in 0 until REGION_SIDE)
    }

    val index: Int
        get() = x + z * REGION_SIDE

    companion object {
        fun fromIndex(index: Int): LocalChunkPosition {
            require(index in 0 until REGION_CHUNK_COUNT)
            return LocalChunkPosition(
                x = index % REGION_SIDE,
                z = index / REGION_SIDE,
            )
        }
    }
}

sealed interface RegionChunkPayload {
    val compressedBytes: ByteArray?
    val isExternal: Boolean

    class Inline(
        override val compressedBytes: ByteArray,
    ) : RegionChunkPayload {
        override val isExternal: Boolean = false

        override fun equals(other: Any?): Boolean =
            other is Inline &&
                    compressedBytes.contentEquals(other.compressedBytes)

        override fun hashCode(): Int = compressedBytes.contentHashCode()
    }

    /**
     * Payload stored in `c.<x>.<z>.mcc`.
     *
     * [compressedBytes] is null only directly after parsing an `.mca` stream.
     * A filesystem or caller-provided resolver supplies it before encoding or
     * decompression.
     */
    class External(
        override val compressedBytes: ByteArray? = null,
    ) : RegionChunkPayload {
        override val isExternal: Boolean = true

        override fun equals(other: Any?): Boolean =
            other is External &&
                    when {
                        compressedBytes == null -> other.compressedBytes == null
                        other.compressedBytes == null -> false
                        else -> compressedBytes.contentEquals(other.compressedBytes)
                    }

        override fun hashCode(): Int = compressedBytes?.contentHashCode() ?: 0
    }
}

data class RegionChunk(
    val compression: Compression,
    val payload: RegionChunkPayload,
    /** Raw signed 32-bit seconds-since-epoch value stored in the header. */
    val timestamp: Int = 0,
)

/** A detached Region snapshot whose chunks are keyed independently of any source Region position. */
data class RegionFile(
    val chunks: Map<LocalChunkPosition, RegionChunk> = emptyMap(),
) {
    init {
        require(chunks.size <= REGION_CHUNK_COUNT)
    }

    operator fun get(position: LocalChunkPosition): RegionChunk? =
        chunks[position]
}

data class EncodedRegionFile(
    val bytes: ByteArray,
    /**
     * External payloads keyed by local position. Values contain compressed
     * bytes exactly as stored in `.mcc`.
     */
    val externalChunks: Map<LocalChunkPosition, ByteArray>,
) {
    override fun equals(other: Any?): Boolean =
        other is EncodedRegionFile &&
                bytes.contentEquals(other.bytes) &&
                externalChunks.keys == other.externalChunks.keys &&
                externalChunks.all { (position, value) ->
                    value.contentEquals(other.externalChunks.getValue(position))
                }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        externalChunks.entries
            .sortedBy { it.key.index }
            .forEach { (position, value) ->
                result = 31 * result + position.hashCode()
                result = 31 * result + value.contentHashCode()
            }
        return result
    }
}

internal fun floorDiv(value: Int, divisor: Int): Int {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0 && value < 0) quotient - 1 else quotient
}

internal fun floorMod(value: Int, divisor: Int): Int =
    value - floorDiv(value, divisor) * divisor

const val REGION_SIDE: Int = 32
const val REGION_CHUNK_COUNT: Int = REGION_SIDE * REGION_SIDE
const val REGION_SECTOR_BYTES: Int = 4_096
const val REGION_HEADER_BYTES: Int = REGION_SECTOR_BYTES * 2
