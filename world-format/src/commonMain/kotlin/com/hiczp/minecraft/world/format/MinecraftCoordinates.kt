package com.hiczp.minecraft.world.format

import kotlin.math.floor

/**
 * Canonical coordinate conversions for one Minecraft dimension.
 *
 * All conversions across a boundary use floor semantics, so negative coordinates belong to the same Block, Chunk,
 * Section, and Region as they do in Minecraft. The coordinate value types delegate their convenience properties and
 * functions to this object.
 */
object MinecraftCoordinates {
    /** Returns the Block containing a continuous world position. */
    fun block(x: Double, y: Double, z: Double): BlockPosition =
        BlockPosition(blockCoordinate(x), blockCoordinate(y), blockCoordinate(z))

    /** Returns the integral Block coordinate containing [coordinate]. */
    fun blockCoordinate(coordinate: Double): Int {
        require(coordinate.isFinite()) { "A world coordinate must be finite" }
        val result = floor(coordinate)
        require(result >= Int.MIN_VALUE.toDouble() && result <= Int.MAX_VALUE.toDouble()) {
            "Block coordinate overflows Int"
        }
        return result.toInt()
    }

    /** Converts one absolute Block axis coordinate to its absolute Chunk axis coordinate. */
    fun chunkCoordinate(blockCoordinate: Int): Int = blockCoordinate.floorDiv(CHUNK_SIDE)

    /** Converts one absolute Block axis coordinate to its absolute Section axis coordinate. */
    fun sectionCoordinate(blockCoordinate: Int): Int = blockCoordinate.floorDiv(SECTION_SIDE)

    /** Converts one absolute Block axis coordinate to its absolute 4-Block biome-quart coordinate. */
    fun quartCoordinate(blockCoordinate: Int): Int = blockCoordinate.floorDiv(BIOME_CELL_SIDE)

    /** Converts one absolute Chunk axis coordinate to its absolute Region axis coordinate. */
    fun regionCoordinate(chunkCoordinate: Int): Int = chunkCoordinate.floorDiv(REGION_SIDE)

    /** Converts an absolute Block axis coordinate to its local coordinate inside a Chunk. */
    fun blockCoordinateInChunk(blockCoordinate: Int): Int = blockCoordinate.mod(CHUNK_SIDE)

    /** Converts an absolute Block axis coordinate to its local coordinate inside a Section. */
    fun blockCoordinateInSection(blockCoordinate: Int): Int = blockCoordinate.mod(SECTION_SIDE)

    /** Converts an absolute Block axis coordinate to its local coordinate inside a biome quart. */
    fun blockCoordinateInQuart(blockCoordinate: Int): Int = blockCoordinate.mod(BIOME_CELL_SIDE)

    /** Converts an absolute Block axis coordinate to its biome-quart coordinate inside a Section. */
    fun quartCoordinateInSection(blockCoordinate: Int): Int =
        quartCoordinate(blockCoordinate).mod(BIOME_SECTION_SIDE)

    /** Converts an absolute Chunk axis coordinate to its local coordinate inside a Region. */
    fun chunkCoordinateInRegion(chunkCoordinate: Int): Int = chunkCoordinate.mod(REGION_SIDE)

    /** Offsets one absolute Block axis coordinate without silently wrapping it. */
    fun offsetBlockCoordinate(blockCoordinate: Int, offset: Int): Int =
        offsetCoordinate(blockCoordinate, offset, "Block")

    /** Offsets one absolute Section axis coordinate without silently wrapping it. */
    fun offsetSectionCoordinate(sectionCoordinate: Int, offset: Int): Int =
        offsetCoordinate(sectionCoordinate, offset, "Section")

    /** Offsets one absolute Chunk axis coordinate without silently wrapping it. */
    fun offsetChunkCoordinate(chunkCoordinate: Int, offset: Int): Int =
        offsetCoordinate(chunkCoordinate, offset, "Chunk")

    /** Offsets one absolute Region axis coordinate without silently wrapping it. */
    fun offsetRegionCoordinate(regionCoordinate: Int, offset: Int): Int =
        offsetCoordinate(regionCoordinate, offset, "Region")

    /** Converts a Chunk axis coordinate and a local Block coordinate back to an absolute Block coordinate. */
    fun blockCoordinate(chunkCoordinate: Int, localBlockCoordinate: Int): Int {
        require(localBlockCoordinate in 0 until CHUNK_SIDE) { "Local Block coordinate must be in 0 until $CHUNK_SIDE" }
        return checkedCoordinate(
            chunkCoordinate.toLong() * CHUNK_SIDE + localBlockCoordinate,
            "Chunk Block",
        )
    }

    /** Converts a Section axis coordinate and a local Block coordinate back to an absolute Block coordinate. */
    fun sectionBlockCoordinate(sectionCoordinate: Int, localBlockCoordinate: Int): Int {
        require(localBlockCoordinate in 0 until SECTION_SIDE) {
            "Local Section Block coordinate must be in 0 until $SECTION_SIDE"
        }
        return checkedCoordinate(
            sectionCoordinate.toLong() * SECTION_SIDE + localBlockCoordinate,
            "Section Block",
        )
    }

    /** Converts a biome-quart coordinate and its local Block coordinate back to an absolute Block coordinate. */
    fun quartBlockCoordinate(quartCoordinate: Int, localBlockCoordinate: Int): Int {
        require(localBlockCoordinate in 0 until BIOME_CELL_SIDE) {
            "Local biome-quart Block coordinate must be in 0 until $BIOME_CELL_SIDE"
        }
        return checkedCoordinate(
            quartCoordinate.toLong() * BIOME_CELL_SIDE + localBlockCoordinate,
            "Biome-quart Block",
        )
    }

    /** Converts a Section coordinate and local biome-quart coordinate to an absolute biome-quart coordinate. */
    fun quartCoordinate(sectionCoordinate: Int, localQuartCoordinate: Int): Int {
        require(localQuartCoordinate in 0 until BIOME_SECTION_SIDE) {
            "Local Section biome-quart coordinate must be in 0 until $BIOME_SECTION_SIDE"
        }
        return checkedCoordinate(
            sectionCoordinate.toLong() * BIOME_SECTION_SIDE + localQuartCoordinate,
            "Section biome quart",
        )
    }

    /** Converts a Region axis coordinate and a local Chunk coordinate back to an absolute Chunk coordinate. */
    fun chunkCoordinate(regionCoordinate: Int, localChunkCoordinate: Int): Int {
        require(localChunkCoordinate in 0 until REGION_SIDE) {
            "Local Chunk coordinate must be in 0 until $REGION_SIDE"
        }
        return checkedCoordinate(
            regionCoordinate.toLong() * REGION_SIDE + localChunkCoordinate,
            "Region Chunk",
        )
    }

    fun chunk(blockPosition: BlockPosition): ChunkPosition =
        ChunkPosition(chunkCoordinate(blockPosition.x), chunkCoordinate(blockPosition.z))

    fun chunk(sectionPosition: SectionPosition): ChunkPosition = ChunkPosition(sectionPosition.x, sectionPosition.z)

    fun section(blockPosition: BlockPosition): SectionPosition =
        SectionPosition(
            x = sectionCoordinate(blockPosition.x),
            y = sectionCoordinate(blockPosition.y),
            z = sectionCoordinate(blockPosition.z),
        )

    fun section(chunkPosition: ChunkPosition, sectionY: Int): SectionPosition =
        SectionPosition(chunkPosition.x, sectionY, chunkPosition.z)

    fun region(blockPosition: BlockPosition): RegionPosition = region(chunk(blockPosition))

    fun region(sectionPosition: SectionPosition): RegionPosition = region(chunk(sectionPosition))

    fun region(chunkPosition: ChunkPosition): RegionPosition =
        RegionPosition(regionCoordinate(chunkPosition.x), regionCoordinate(chunkPosition.z))

    fun chunkBlock(blockPosition: BlockPosition): ChunkBlockPosition =
        ChunkBlockPosition(blockCoordinateInChunk(blockPosition.x), blockPosition.y, blockCoordinateInChunk(blockPosition.z))

    fun localBlock(blockPosition: BlockPosition): LocalBlockPosition =
        LocalBlockPosition(
            blockCoordinateInSection(blockPosition.x),
            blockCoordinateInSection(blockPosition.y),
            blockCoordinateInSection(blockPosition.z),
        )

    fun localChunk(chunkPosition: ChunkPosition): LocalChunkPosition =
        LocalChunkPosition(chunkCoordinateInRegion(chunkPosition.x), chunkCoordinateInRegion(chunkPosition.z))

    /** Converts [blockPosition] to local X/Z plus absolute Y after checking that it belongs to [chunkPosition]. */
    fun local(blockPosition: BlockPosition, chunkPosition: ChunkPosition): ChunkBlockPosition {
        require(chunk(blockPosition) == chunkPosition) { "Block $blockPosition does not belong to Chunk $chunkPosition" }
        return chunkBlock(blockPosition)
    }

    /** Converts [blockPosition] to Section-local coordinates after checking that it belongs to [sectionPosition]. */
    fun local(blockPosition: BlockPosition, sectionPosition: SectionPosition): LocalBlockPosition {
        require(this.section(blockPosition) == sectionPosition) { "Block $blockPosition does not belong to Section $sectionPosition" }
        return localBlock(blockPosition)
    }

    /** Converts [chunkPosition] to Region-local coordinates after checking that it belongs to [regionPosition]. */
    fun local(chunkPosition: ChunkPosition, regionPosition: RegionPosition): LocalChunkPosition {
        require(this.region(chunkPosition) == regionPosition) { "Chunk $chunkPosition does not belong to Region $regionPosition" }
        return localChunk(chunkPosition)
    }

    fun block(chunkPosition: ChunkPosition, chunkBlockPosition: ChunkBlockPosition): BlockPosition =
        BlockPosition(
            x = blockCoordinate(chunkPosition.x, chunkBlockPosition.x),
            y = chunkBlockPosition.y,
            z = blockCoordinate(chunkPosition.z, chunkBlockPosition.z),
        )

    fun block(sectionPosition: SectionPosition, localBlockPosition: LocalBlockPosition): BlockPosition =
        BlockPosition(
            x = sectionBlockCoordinate(sectionPosition.x, localBlockPosition.x),
            y = sectionBlockCoordinate(sectionPosition.y, localBlockPosition.y),
            z = sectionBlockCoordinate(sectionPosition.z, localBlockPosition.z),
        )

    fun chunk(regionPosition: RegionPosition, localChunkPosition: LocalChunkPosition): ChunkPosition =
        ChunkPosition(
            x = chunkCoordinate(regionPosition.x, localChunkPosition.x),
            z = chunkCoordinate(regionPosition.z, localChunkPosition.z),
        )

    /** Returns the Block height represented by [sectionCount] complete Sections. */
    fun blockCountForSections(sectionCount: Int): Int {
        require(sectionCount >= 0) { "A Section count must be non-negative" }
        return checkedCoordinate(sectionCount.toLong() * SECTION_SIDE, "Section Block count")
    }

    /** Offsets an absolute Block position without silently wrapping an axis. */
    fun offset(blockPosition: BlockPosition, x: Int, y: Int, z: Int): BlockPosition =
        BlockPosition(
            x = offsetBlockCoordinate(blockPosition.x, x),
            y = offsetBlockCoordinate(blockPosition.y, y),
            z = offsetBlockCoordinate(blockPosition.z, z),
        )

    /** Offsets an absolute Section position without silently wrapping an axis. */
    fun offset(sectionPosition: SectionPosition, x: Int, y: Int, z: Int): SectionPosition =
        SectionPosition(
            x = offsetSectionCoordinate(sectionPosition.x, x),
            y = offsetSectionCoordinate(sectionPosition.y, y),
            z = offsetSectionCoordinate(sectionPosition.z, z),
        )

    /** Offsets an absolute Chunk position without silently wrapping an axis. */
    fun offset(chunkPosition: ChunkPosition, x: Int, z: Int): ChunkPosition =
        ChunkPosition(
            x = offsetChunkCoordinate(chunkPosition.x, x),
            z = offsetChunkCoordinate(chunkPosition.z, z),
        )

    /** Offsets an absolute Region position without silently wrapping an axis. */
    fun offset(regionPosition: RegionPosition, x: Int, z: Int): RegionPosition =
        RegionPosition(
            x = offsetRegionCoordinate(regionPosition.x, x),
            z = offsetRegionCoordinate(regionPosition.z, z),
        )

    fun blockIndex(localBlockPosition: LocalBlockPosition): Int =
        (localBlockPosition.y * SECTION_SIDE + localBlockPosition.z) * SECTION_SIDE + localBlockPosition.x

    /** Converts Section-local biome-quart coordinates to their palette index. */
    fun biomeIndex(quartX: Int, quartY: Int, quartZ: Int): Int {
        require(quartX in 0 until BIOME_SECTION_SIDE) {
            "Local Section biome-quart X must be in 0 until $BIOME_SECTION_SIDE"
        }
        require(quartY in 0 until BIOME_SECTION_SIDE) {
            "Local Section biome-quart Y must be in 0 until $BIOME_SECTION_SIDE"
        }
        require(quartZ in 0 until BIOME_SECTION_SIDE) {
            "Local Section biome-quart Z must be in 0 until $BIOME_SECTION_SIDE"
        }
        return (quartY * BIOME_SECTION_SIDE + quartZ) * BIOME_SECTION_SIDE + quartX
    }

    fun localBlock(index: Int): LocalBlockPosition {
        require(index in 0 until SECTION_BLOCK_COUNT) { "Section Block index must be in 0 until $SECTION_BLOCK_COUNT" }
        return LocalBlockPosition(
            x = index % SECTION_SIDE,
            y = index / (SECTION_SIDE * SECTION_SIDE),
            z = index / SECTION_SIDE % SECTION_SIDE,
        )
    }

    fun chunkIndex(localChunkPosition: LocalChunkPosition): Int = localChunkPosition.x + localChunkPosition.z * REGION_SIDE

    fun localChunk(index: Int): LocalChunkPosition {
        require(index in 0 until REGION_CHUNK_COUNT) { "Region Chunk index must be in 0 until $REGION_CHUNK_COUNT" }
        return LocalChunkPosition(
            x = index % REGION_SIDE,
            z = index / REGION_SIDE,
        )
    }

    fun blockXRange(chunkPosition: ChunkPosition): IntRange = coordinateRange(chunkPosition.x, CHUNK_SIDE, "Chunk Block X")

    fun blockZRange(chunkPosition: ChunkPosition): IntRange = coordinateRange(chunkPosition.z, CHUNK_SIDE, "Chunk Block Z")

    fun blockXRange(sectionPosition: SectionPosition): IntRange = coordinateRange(sectionPosition.x, SECTION_SIDE, "Section Block X")

    fun blockYRange(sectionPosition: SectionPosition): IntRange = coordinateRange(sectionPosition.y, SECTION_SIDE, "Section Block Y")

    fun blockZRange(sectionPosition: SectionPosition): IntRange = coordinateRange(sectionPosition.z, SECTION_SIDE, "Section Block Z")

    fun chunkXRange(regionPosition: RegionPosition): IntRange = coordinateRange(regionPosition.x, REGION_SIDE, "Region Chunk X")

    fun chunkZRange(regionPosition: RegionPosition): IntRange = coordinateRange(regionPosition.z, REGION_SIDE, "Region Chunk Z")

    fun blockXRange(regionPosition: RegionPosition): IntRange =
        coordinateRange(regionPosition.x, REGION_SIDE * CHUNK_SIDE, "Region Block X")

    fun blockZRange(regionPosition: RegionPosition): IntRange =
        coordinateRange(regionPosition.z, REGION_SIDE * CHUNK_SIDE, "Region Block Z")

    /** Every Section-local Block coordinate, in palette-index order. */
    fun localBlockPositions(): Sequence<LocalBlockPosition> =
        (0 until SECTION_BLOCK_COUNT).asSequence().map(::localBlock)

    /** Every absolute Block position in [sectionPosition], in palette-index order. */
    fun blockPositions(sectionPosition: SectionPosition): Sequence<BlockPosition> =
        localBlockPositions().map { position -> block(sectionPosition, position) }

    /** Every Region-local Chunk coordinate, in Anvil header-index order. */
    fun localChunkPositions(): Sequence<LocalChunkPosition> =
        (0 until REGION_CHUNK_COUNT).asSequence().map(::localChunk)

    /** Every absolute Chunk position in [regionPosition], in Anvil header-index order. */
    fun chunkPositions(regionPosition: RegionPosition): Sequence<ChunkPosition> =
        localChunkPositions().map { position -> chunk(regionPosition, position) }

    /** Every Chunk in a square centered on [center], ordered by Z and then X. */
    fun chunkPositionsAround(center: ChunkPosition, horizontalRadius: Int): Sequence<ChunkPosition> {
        require(horizontalRadius >= 0) { "A Chunk radius must be non-negative" }
        val minimum = offset(center, -horizontalRadius, -horizontalRadius)
        val maximum = offset(center, horizontalRadius, horizontalRadius)
        return sequence {
            for (z in minimum.z..maximum.z) {
                for (x in minimum.x..maximum.x) {
                    yield(ChunkPosition(x, z))
                }
            }
        }
    }

    private fun coordinateRange(parentCoordinate: Int, size: Int, description: String): IntRange {
        val first = parentCoordinate.toLong() * size
        val last = first + size - 1
        return checkedCoordinate(first, description)..checkedCoordinate(last, description)
    }

    private fun offsetCoordinate(coordinate: Int, offset: Int, description: String): Int =
        checkedCoordinate(coordinate.toLong() + offset, description)

    private fun checkedCoordinate(value: Long, description: String): Int {
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$description coordinate overflows Int" }
        return value.toInt()
    }
}
