package com.hiczp.minecraft.world.format

/**
 * A rectangular range of absolute Chunk positions.
 *
 * [xRange] and [zRange] are both inclusive. An empty range has both axes empty. Positions are
 * visited lazily in Z-major order: Z is the outer axis and X is the inner axis.
 */
data class ChunkRange(
    val xRange: IntRange,
    val zRange: IntRange,
) : Iterable<ChunkPosition> {
    init {
        require(xRange.isEmpty() == zRange.isEmpty()) {
            "A Chunk range must have either two non-empty axes or two empty axes"
        }
    }

    /** Creates an inclusive range or an empty range if either axis is reversed. */
    constructor(start: ChunkPosition, endInclusive: ChunkPosition) : this(
        xRange = orderedRange(start.x, endInclusive.x, start.z <= endInclusive.z),
        zRange = orderedRange(start.z, endInclusive.z, start.x <= endInclusive.x),
    )

    fun isEmpty(): Boolean = xRange.isEmpty()

    operator fun contains(chunkPosition: ChunkPosition): Boolean =
        chunkPosition.x in xRange && chunkPosition.z in zRange

    /** Returns whether this range contains every position in [chunkRange]. */
    operator fun contains(chunkRange: ChunkRange): Boolean {
        return chunkRange.isEmpty() || !isEmpty() &&
                chunkRange.xRange.first >= xRange.first &&
                chunkRange.xRange.last <= xRange.last &&
                chunkRange.zRange.first >= zRange.first &&
                chunkRange.zRange.last <= zRange.last
    }

    /** Returns whether this range and [chunkRange] share at least one Chunk. */
    infix fun intersects(chunkRange: ChunkRange): Boolean =
        !isEmpty() &&
                !chunkRange.isEmpty() &&
                xRange.first <= chunkRange.xRange.last &&
                chunkRange.xRange.first <= xRange.last &&
                zRange.first <= chunkRange.zRange.last &&
                chunkRange.zRange.first <= zRange.last

    /** Returns the Chunks shared by this range and [chunkRange]. */
    infix fun intersect(chunkRange: ChunkRange): ChunkRange {
        if (!intersects(chunkRange)) return EMPTY
        return ChunkRange(
            xRange = maxOf(xRange.first, chunkRange.xRange.first)..minOf(xRange.last, chunkRange.xRange.last),
            zRange = maxOf(zRange.first, chunkRange.zRange.first)..minOf(zRange.last, chunkRange.zRange.last),
        )
    }

    /** The smallest rectangular Region range that covers every Chunk in this range. */
    val coveringRegionRange: RegionRange
        get() = MinecraftCoordinates.regionRange(this)

    /** Every Chunk position in this range, ordered by Z and then X. */
    fun chunkPositions(): Sequence<ChunkPosition> = if (isEmpty()) {
        emptySequence()
    } else {
        sequence {
            for (z in zRange) {
                for (x in xRange) {
                    yield(ChunkPosition(x, z))
                }
            }
        }
    }

    /** Every Region touched by this range, ordered by Z and then X. */
    fun regionPositions(): Sequence<RegionPosition> = coveringRegionRange.regionPositions()

    override operator fun iterator(): Iterator<ChunkPosition> = chunkPositions().iterator()

    companion object {
        val EMPTY: ChunkRange = ChunkRange(IntRange.EMPTY, IntRange.EMPTY)

        /** Creates the inclusive range bounded by two corners in either order. */
        fun enclosing(first: ChunkPosition, second: ChunkPosition): ChunkRange = ChunkRange(
            xRange = minOf(first.x, second.x)..maxOf(first.x, second.x),
            zRange = minOf(first.z, second.z)..maxOf(first.z, second.z),
        )

        internal fun until(start: ChunkPosition, endExclusive: ChunkPosition): ChunkRange {
            if (start.x >= endExclusive.x || start.z >= endExclusive.z) return EMPTY
            return ChunkRange(
                xRange = start.x..<endExclusive.x,
                zRange = start.z..<endExclusive.z,
            )
        }
    }
}

/**
 * A rectangular range of absolute Anvil Region positions.
 *
 * [xRange] and [zRange] are both inclusive. An empty range has both axes empty. Positions are
 * visited lazily in Z-major order: Z is the outer axis and X is the inner axis.
 */
data class RegionRange(
    val xRange: IntRange,
    val zRange: IntRange,
) : Iterable<RegionPosition> {
    init {
        require(xRange.isEmpty() == zRange.isEmpty()) {
            "A Region range must have either two non-empty axes or two empty axes"
        }
    }

    /** Creates an inclusive range or an empty range if either axis is reversed. */
    constructor(start: RegionPosition, endInclusive: RegionPosition) : this(
        xRange = orderedRange(start.x, endInclusive.x, start.z <= endInclusive.z),
        zRange = orderedRange(start.z, endInclusive.z, start.x <= endInclusive.x),
    )

    fun isEmpty(): Boolean = xRange.isEmpty()

    operator fun contains(regionPosition: RegionPosition): Boolean =
        regionPosition.x in xRange && regionPosition.z in zRange

    /** Returns whether this range contains every position in [regionRange]. */
    operator fun contains(regionRange: RegionRange): Boolean {
        return regionRange.isEmpty() || !isEmpty() &&
                regionRange.xRange.first >= xRange.first &&
                regionRange.xRange.last <= xRange.last &&
                regionRange.zRange.first >= zRange.first &&
                regionRange.zRange.last <= zRange.last
    }

    /** Returns whether this range and [regionRange] share at least one Region. */
    infix fun intersects(regionRange: RegionRange): Boolean =
        !isEmpty() &&
                !regionRange.isEmpty() &&
                xRange.first <= regionRange.xRange.last &&
                regionRange.xRange.first <= xRange.last &&
                zRange.first <= regionRange.zRange.last &&
                regionRange.zRange.first <= zRange.last

    /** Returns the Regions shared by this range and [regionRange]. */
    infix fun intersect(regionRange: RegionRange): RegionRange {
        if (!intersects(regionRange)) return EMPTY
        return RegionRange(
            xRange = maxOf(xRange.first, regionRange.xRange.first)..minOf(xRange.last, regionRange.xRange.last),
            zRange = maxOf(zRange.first, regionRange.zRange.first)..minOf(zRange.last, regionRange.zRange.last),
        )
    }

    /** Every Chunk covered by these complete Regions. */
    val chunkRange: ChunkRange
        get() = MinecraftCoordinates.chunkRange(this)

    /** Every Region position in this range, ordered by Z and then X. */
    fun regionPositions(): Sequence<RegionPosition> = if (isEmpty()) {
        emptySequence()
    } else {
        sequence {
            for (z in zRange) {
                for (x in xRange) {
                    yield(RegionPosition(x, z))
                }
            }
        }
    }

    /** Every Chunk covered by these complete Regions, ordered by Z and then X. */
    fun chunkPositions(): Sequence<ChunkPosition> = chunkRange.chunkPositions()

    override operator fun iterator(): Iterator<RegionPosition> = regionPositions().iterator()

    companion object {
        val EMPTY: RegionRange = RegionRange(IntRange.EMPTY, IntRange.EMPTY)

        /** Creates the inclusive range bounded by two corners in either order. */
        fun enclosing(first: RegionPosition, second: RegionPosition): RegionRange = RegionRange(
            xRange = minOf(first.x, second.x)..maxOf(first.x, second.x),
            zRange = minOf(first.z, second.z)..maxOf(first.z, second.z),
        )

        internal fun until(start: RegionPosition, endExclusive: RegionPosition): RegionRange {
            if (start.x >= endExclusive.x || start.z >= endExclusive.z) return EMPTY
            return RegionRange(
                xRange = start.x..<endExclusive.x,
                zRange = start.z..<endExclusive.z,
            )
        }
    }
}

private fun orderedRange(start: Int, endInclusive: Int, otherAxisOrdered: Boolean): IntRange =
    if (start <= endInclusive && otherAxisOrdered) start..endInclusive else IntRange.EMPTY
