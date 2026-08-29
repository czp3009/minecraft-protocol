package com.hiczp.minecraft.world.format

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

/** Absolute block coordinates in one Minecraft dimension. */
data class BlockPosition(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    val chunkPosition: ChunkPosition
        get() = MinecraftCoordinates.chunk(this)

    val regionPosition: RegionPosition
        get() = MinecraftCoordinates.region(this)

    val sectionPosition: SectionPosition
        get() = MinecraftCoordinates.section(this)

    val localInChunk: ChunkBlockPosition
        get() = MinecraftCoordinates.chunkBlock(this)

    val localInSection: LocalBlockPosition
        get() = MinecraftCoordinates.localBlock(this)

    fun offset(x: Int, y: Int, z: Int): BlockPosition = MinecraftCoordinates.offset(this, x, y, z)
}

/** A block coordinate inside one Chunk: local X/Z and dimension-absolute Y. */
data class ChunkBlockPosition(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    init {
        require(x in 0 until CHUNK_SIDE)
        require(z in 0 until CHUNK_SIDE)
    }

    val sectionY: Int
        get() = MinecraftCoordinates.sectionCoordinate(y)

    val localInSection: LocalBlockPosition
        get() = LocalBlockPosition(x, MinecraftCoordinates.blockCoordinateInSection(y), z)
}

/** A block coordinate inside one 16 by 16 by 16 Section. */
data class LocalBlockPosition(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    init {
        require(x in 0 until SECTION_SIDE)
        require(y in 0 until SECTION_SIDE)
        require(z in 0 until SECTION_SIDE)
    }

    val index: Int
        get() = MinecraftCoordinates.blockIndex(this)

    companion object {
        fun fromIndex(index: Int): LocalBlockPosition = MinecraftCoordinates.localBlock(index)
    }
}

/** Absolute Section coordinates in one Minecraft dimension. */
data class SectionPosition(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    val chunkPosition: ChunkPosition
        get() = MinecraftCoordinates.chunk(this)

    val regionPosition: RegionPosition
        get() = MinecraftCoordinates.region(this)

    val blockXRange: IntRange
        get() = MinecraftCoordinates.blockXRange(this)

    val blockYRange: IntRange
        get() = MinecraftCoordinates.blockYRange(this)

    val blockZRange: IntRange
        get() = MinecraftCoordinates.blockZRange(this)

    operator fun contains(blockPosition: BlockPosition): Boolean = blockPosition.sectionPosition == this

    /** Converts an absolute block position in this Section to Section-local coordinates. */
    fun local(blockPosition: BlockPosition): LocalBlockPosition = MinecraftCoordinates.local(blockPosition, this)

    fun block(localBlockPosition: LocalBlockPosition): BlockPosition =
        MinecraftCoordinates.block(this, localBlockPosition)

    /** Every absolute Block position in this Section, in palette-index order. */
    fun blockPositions(): Sequence<BlockPosition> = MinecraftCoordinates.blockPositions(this)

    fun offset(x: Int, y: Int, z: Int): SectionPosition = MinecraftCoordinates.offset(this, x, y, z)
}

/** Absolute chunk coordinates in a Minecraft dimension. */
data class ChunkPosition(
    val x: Int,
    val z: Int,
) {
    val regionPosition: RegionPosition
        get() = MinecraftCoordinates.region(this)

    val localChunkPosition: LocalChunkPosition
        get() = MinecraftCoordinates.localChunk(this)

    val blockXRange: IntRange
        get() = MinecraftCoordinates.blockXRange(this)

    val blockZRange: IntRange
        get() = MinecraftCoordinates.blockZRange(this)

    /** Creates a rectangular range whose two corners are inclusive. */
    operator fun rangeTo(endInclusive: ChunkPosition): ChunkRange = ChunkRange(this, endInclusive)

    /** Creates a rectangular range that includes this corner and excludes [endExclusive] on both axes. */
    operator fun rangeUntil(endExclusive: ChunkPosition): ChunkRange = ChunkRange.until(this, endExclusive)

    operator fun contains(blockPosition: BlockPosition): Boolean = blockPosition.chunkPosition == this

    operator fun contains(sectionPosition: SectionPosition): Boolean = sectionPosition.chunkPosition == this

    /** Converts an absolute block position in this Chunk to local X/Z plus absolute Y. */
    fun local(blockPosition: BlockPosition): ChunkBlockPosition = MinecraftCoordinates.local(blockPosition, this)

    fun section(sectionY: Int): SectionPosition = MinecraftCoordinates.section(this, sectionY)

    fun section(chunkBlockPosition: ChunkBlockPosition): SectionPosition = section(chunkBlockPosition.sectionY)

    fun block(chunkBlockPosition: ChunkBlockPosition): BlockPosition =
        MinecraftCoordinates.block(this, chunkBlockPosition)

    fun block(localX: Int, y: Int, localZ: Int): BlockPosition =
        block(ChunkBlockPosition(localX, y, localZ))

    fun offset(x: Int, z: Int): ChunkPosition = MinecraftCoordinates.offset(this, x, z)

    /** Every Chunk in a square centered on this position, ordered by Z and then X. */
    fun positionsAround(horizontalRadius: Int): Sequence<ChunkPosition> =
        MinecraftCoordinates.chunkPositionsAround(this, horizontalRadius)
}

/** Absolute region coordinates in a Minecraft dimension. */
data class RegionPosition(
    val x: Int,
    val z: Int,
) {
    val chunkXRange: IntRange
        get() = MinecraftCoordinates.chunkXRange(this)

    val chunkZRange: IntRange
        get() = MinecraftCoordinates.chunkZRange(this)

    val blockXRange: IntRange
        get() = MinecraftCoordinates.blockXRange(this)

    val blockZRange: IntRange
        get() = MinecraftCoordinates.blockZRange(this)

    /** All Chunks covered by this complete Region. */
    val chunkRange: ChunkRange
        get() = MinecraftCoordinates.chunkRange(this)

    /** Creates a rectangular range whose two corners are inclusive. */
    operator fun rangeTo(endInclusive: RegionPosition): RegionRange = RegionRange(this, endInclusive)

    /** Creates a rectangular range that includes this corner and excludes [endExclusive] on both axes. */
    operator fun rangeUntil(endExclusive: RegionPosition): RegionRange = RegionRange.until(this, endExclusive)

    /** Returns whether [chunkPosition] belongs to this 32 by 32 Region. */
    operator fun contains(chunkPosition: ChunkPosition): Boolean = chunkPosition.regionPosition == this

    /** Converts an absolute [chunkPosition] in this Region to its Region-local coordinates. */
    fun local(chunkPosition: ChunkPosition): LocalChunkPosition = MinecraftCoordinates.local(chunkPosition, this)

    /** Converts Region-local coordinates to an absolute Chunk position. */
    fun chunk(localChunkPosition: LocalChunkPosition): ChunkPosition =
        MinecraftCoordinates.chunk(this, localChunkPosition)

    /**
     * All absolute chunk positions covered by this region, in Anvil header-index order.
     *
     * This describes coordinate coverage, not the chunks actually present in an `.mca` file.
     */
    fun chunkPositions(): Sequence<ChunkPosition> = MinecraftCoordinates.chunkPositions(this)

    /** Every Region-local Chunk coordinate, in Anvil header-index order. */
    fun localChunkPositions(): Sequence<LocalChunkPosition> = MinecraftCoordinates.localChunkPositions()

    fun offset(x: Int, z: Int): RegionPosition = MinecraftCoordinates.offset(this, x, z)
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
        get() = MinecraftCoordinates.chunkIndex(this)

    companion object {
        fun fromIndex(index: Int): LocalChunkPosition = MinecraftCoordinates.localChunk(index)
    }
}

/** A complete compressed Chunk payload that can be written without first materializing another byte array. */
interface CompressedChunkInput {
    val compression: Compression
    val compressedByteCount: Long

    fun writeTo(sink: Sink)
}

/** Positionless, detached compressed Chunk content. */
class CompressedChunk private constructor(
    override val compression: Compression,
    compressedBytes: ByteArray,
    takeOwnership: Boolean,
) : CompressedChunkInput {
    constructor(
        compression: Compression,
        compressedBytes: ByteArray,
    ) : this(compression, compressedBytes, takeOwnership = false)

    private val bytes = if (takeOwnership) compressedBytes else compressedBytes.copyOf()

    override val compressedByteCount: Long
        get() = bytes.size.toLong()

    override fun writeTo(sink: Sink) {
        sink.write(bytes)
    }

    /** Returns a defensive copy of the compressed payload. */
    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CompressedChunk && compression == other.compression && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * compression.hashCode() + bytes.contentHashCode()

    override fun toString(): String =
        "CompressedChunk(compression=$compression, compressedByteCount=$compressedByteCount)"

    companion object {
        /** Reads all remaining bytes without closing [source] and owns the resulting payload directly. */
        fun readFromSource(source: Source, compression: Compression): CompressedChunk =
            takeOwnership(compression, source.readByteArray())

        internal fun takeOwnership(compression: Compression, bytes: ByteArray): CompressedChunk =
            CompressedChunk(compression, bytes, takeOwnership = true)
    }
}

enum class AnvilChunkPlacement {
    INLINE,
    EXTERNAL,
}

/** One record in a detached `.mca` image. */
data class AnvilChunkRecord(
    val compression: Compression,
    val content: CompressedChunk?,
    val anvilChunkPlacement: AnvilChunkPlacement,
    /** Raw signed 32-bit seconds-since-epoch value stored in the header. */
    val timestampEpochSeconds: Int = 0,
) {
    init {
        require(anvilChunkPlacement == AnvilChunkPlacement.EXTERNAL || content != null) {
            "An inline Anvil Chunk record must contain its compressed payload"
        }
        require(content == null || content.compression == compression) {
            "Anvil record compression does not match its compressed content"
        }
    }
}

/** A local position paired with positionless compressed content for a Region write. */
data class RegionChunkInput(
    val localChunkPosition: LocalChunkPosition,
    val content: CompressedChunkInput,
)

/** A detached `.mca` snapshot whose external payloads may still need sidecar resolution. */
class AnvilRegion(
    chunks: Map<LocalChunkPosition, AnvilChunkRecord> = emptyMap(),
) {
    val chunks: Map<LocalChunkPosition, AnvilChunkRecord> = chunks.toMap()

    init {
        require(this.chunks.size <= REGION_CHUNK_COUNT)
    }

    /** Whether this detached Region contains a Chunk record at [localChunkPosition], without inspecting its payload. */
    fun hasChunk(localChunkPosition: LocalChunkPosition): Boolean = chunks.containsKey(localChunkPosition)

    operator fun get(localChunkPosition: LocalChunkPosition): AnvilChunkRecord? = chunks[localChunkPosition]

    override fun equals(other: Any?): Boolean = other is AnvilRegion && chunks == other.chunks

    override fun hashCode(): Int = chunks.hashCode()

    override fun toString(): String = "AnvilRegion(chunks=$chunks)"
}

class EncodedAnvilRegion private constructor(
    bytes: ByteArray,
    /**
     * External payloads keyed by local position. Values contain compressed
     * bytes exactly as stored in `.mcc`.
     */
    externalChunks: Map<LocalChunkPosition, ByteArray>,
    takeOwnership: Boolean,
) {
    constructor(
        bytes: ByteArray,
        externalChunks: Map<LocalChunkPosition, ByteArray>,
    ) : this(bytes, externalChunks, takeOwnership = false)

    private val encodedBytes = if (takeOwnership) bytes else bytes.copyOf()
    private val encodedExternalChunks: Map<LocalChunkPosition, ByteArray> = if (takeOwnership) {
        externalChunks
    } else {
        externalChunks.mapValues { (_, payload) -> payload.copyOf() }
    }

    /** Returns a defensive copy of the encoded `.mca` image. */
    val bytes: ByteArray
        get() = encodedBytes.copyOf()

    /** Returns a deep defensive copy of every encoded `.mcc` payload. */
    val externalChunks: Map<LocalChunkPosition, ByteArray>
        get() = encodedExternalChunks.mapValues { (_, payload) -> payload.copyOf() }

    val externalChunkPositions: Set<LocalChunkPosition>
        get() = encodedExternalChunks.keys.toSet()

    val byteCount: Long
        get() = encodedBytes.size.toLong()

    /** Writes the encoded `.mca` image without first creating another complete copy. */
    fun writeTo(sink: Sink) {
        sink.write(encodedBytes)
    }

    /** Writes one encoded `.mcc` payload, returning false when [localChunkPosition] is not external. */
    fun writeExternalChunkTo(localChunkPosition: LocalChunkPosition, sink: Sink): Boolean {
        val payload = encodedExternalChunks[localChunkPosition] ?: return false
        sink.write(payload)
        return true
    }

    override fun equals(other: Any?): Boolean =
        other is EncodedAnvilRegion &&
                encodedBytes.contentEquals(other.encodedBytes) &&
                encodedExternalChunks.keys == other.encodedExternalChunks.keys &&
                encodedExternalChunks.all { (position, value) ->
                    value.contentEquals(other.encodedExternalChunks.getValue(position))
                }

    override fun hashCode(): Int {
        var result = encodedBytes.contentHashCode()
        encodedExternalChunks.entries
            .sortedBy { it.key.index }
            .forEach { (localChunkPosition, value) ->
                result = 31 * result + localChunkPosition.hashCode()
                result = 31 * result + value.contentHashCode()
            }
        return result
    }

    override fun toString(): String =
        "EncodedAnvilRegion(byteCount=$byteCount, externalChunkCount=${encodedExternalChunks.size})"

    internal companion object {
        fun takeOwnership(
            bytes: ByteArray,
            externalChunks: Map<LocalChunkPosition, ByteArray>,
        ): EncodedAnvilRegion = EncodedAnvilRegion(bytes, externalChunks, takeOwnership = true)
    }
}

const val REGION_SIDE: Int = 32
const val CHUNK_SIDE: Int = 16
const val SECTION_SIDE: Int = 16
const val SECTION_BLOCK_COUNT: Int = SECTION_SIDE * SECTION_SIDE * SECTION_SIDE
const val REGION_CHUNK_COUNT: Int = REGION_SIDE * REGION_SIDE
const val REGION_SECTOR_BYTES: Int = 4_096
const val REGION_HEADER_BYTES: Int = REGION_SECTOR_BYTES * 2
