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
    fun chunkCoordinate(blockCoordinate: Int): Int = floorDivide(blockCoordinate, CHUNK_SIDE)

    /** Converts one absolute Block axis coordinate to its absolute Section axis coordinate. */
    fun sectionCoordinate(blockCoordinate: Int): Int = floorDivide(blockCoordinate, SECTION_SIDE)

    /** Converts one absolute Block axis coordinate to its absolute 4-Block biome-quart coordinate. */
    fun quartCoordinate(blockCoordinate: Int): Int = floorDivide(blockCoordinate, BIOME_CELL_SIDE)

    /** Converts one absolute Chunk axis coordinate to its absolute Region axis coordinate. */
    fun regionCoordinate(chunkCoordinate: Int): Int = floorDivide(chunkCoordinate, REGION_SIDE)

    /** Converts an absolute Block axis coordinate to its local coordinate inside a Chunk. */
    fun blockCoordinateInChunk(blockCoordinate: Int): Int = floorModulo(blockCoordinate, CHUNK_SIDE)

    /** Converts an absolute Block axis coordinate to its local coordinate inside a Section. */
    fun blockCoordinateInSection(blockCoordinate: Int): Int = floorModulo(blockCoordinate, SECTION_SIDE)

    /** Converts an absolute Block axis coordinate to its local coordinate inside a biome quart. */
    fun blockCoordinateInQuart(blockCoordinate: Int): Int = floorModulo(blockCoordinate, BIOME_CELL_SIDE)

    /** Converts an absolute Block axis coordinate to its biome-quart coordinate inside a Section. */
    fun quartCoordinateInSection(blockCoordinate: Int): Int =
        floorModulo(quartCoordinate(blockCoordinate), BIOME_SECTION_SIDE)

    /** Converts an absolute Chunk axis coordinate to its local coordinate inside a Region. */
    fun chunkCoordinateInRegion(chunkCoordinate: Int): Int = floorModulo(chunkCoordinate, REGION_SIDE)

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

    fun chunk(position: BlockPosition): ChunkPosition =
        ChunkPosition(chunkCoordinate(position.x), chunkCoordinate(position.z))

    fun chunk(position: SectionPosition): ChunkPosition = ChunkPosition(position.x, position.z)

    fun section(position: BlockPosition): SectionPosition =
        SectionPosition(
            x = sectionCoordinate(position.x),
            y = sectionCoordinate(position.y),
            z = sectionCoordinate(position.z),
        )

    fun section(position: ChunkPosition, sectionY: Int): SectionPosition =
        SectionPosition(position.x, sectionY, position.z)

    fun region(position: BlockPosition): RegionPosition = region(chunk(position))

    fun region(position: SectionPosition): RegionPosition = region(chunk(position))

    fun region(position: ChunkPosition): RegionPosition =
        RegionPosition(regionCoordinate(position.x), regionCoordinate(position.z))

    fun chunkBlock(position: BlockPosition): ChunkBlockPosition =
        ChunkBlockPosition(blockCoordinateInChunk(position.x), position.y, blockCoordinateInChunk(position.z))

    fun localBlock(position: BlockPosition): LocalBlockPosition =
        LocalBlockPosition(
            blockCoordinateInSection(position.x),
            blockCoordinateInSection(position.y),
            blockCoordinateInSection(position.z),
        )

    fun localChunk(position: ChunkPosition): LocalChunkPosition =
        LocalChunkPosition(chunkCoordinateInRegion(position.x), chunkCoordinateInRegion(position.z))

    /** Converts [position] to local X/Z plus absolute Y after checking that it belongs to [chunk]. */
    fun local(position: BlockPosition, chunk: ChunkPosition): ChunkBlockPosition {
        require(chunk(position) == chunk) { "Block $position does not belong to Chunk $chunk" }
        return chunkBlock(position)
    }

    /** Converts [position] to Section-local coordinates after checking that it belongs to [section]. */
    fun local(position: BlockPosition, section: SectionPosition): LocalBlockPosition {
        require(this.section(position) == section) { "Block $position does not belong to Section $section" }
        return localBlock(position)
    }

    /** Converts [position] to Region-local coordinates after checking that it belongs to [region]. */
    fun local(position: ChunkPosition, region: RegionPosition): LocalChunkPosition {
        require(this.region(position) == region) { "Chunk $position does not belong to Region $region" }
        return localChunk(position)
    }

    fun block(chunk: ChunkPosition, position: ChunkBlockPosition): BlockPosition =
        BlockPosition(
            x = blockCoordinate(chunk.x, position.x),
            y = position.y,
            z = blockCoordinate(chunk.z, position.z),
        )

    fun block(section: SectionPosition, position: LocalBlockPosition): BlockPosition =
        BlockPosition(
            x = sectionBlockCoordinate(section.x, position.x),
            y = sectionBlockCoordinate(section.y, position.y),
            z = sectionBlockCoordinate(section.z, position.z),
        )

    fun chunk(region: RegionPosition, position: LocalChunkPosition): ChunkPosition =
        ChunkPosition(
            x = chunkCoordinate(region.x, position.x),
            z = chunkCoordinate(region.z, position.z),
        )

    /** Returns the Block height represented by [sectionCount] complete Sections. */
    fun blockCountForSections(sectionCount: Int): Int {
        require(sectionCount >= 0) { "A Section count must be non-negative" }
        return checkedCoordinate(sectionCount.toLong() * SECTION_SIDE, "Section Block count")
    }

    /** Offsets an absolute Block position without silently wrapping an axis. */
    fun offset(position: BlockPosition, x: Int, y: Int, z: Int): BlockPosition =
        BlockPosition(
            x = offsetBlockCoordinate(position.x, x),
            y = offsetBlockCoordinate(position.y, y),
            z = offsetBlockCoordinate(position.z, z),
        )

    /** Offsets an absolute Section position without silently wrapping an axis. */
    fun offset(position: SectionPosition, x: Int, y: Int, z: Int): SectionPosition =
        SectionPosition(
            x = offsetSectionCoordinate(position.x, x),
            y = offsetSectionCoordinate(position.y, y),
            z = offsetSectionCoordinate(position.z, z),
        )

    /** Offsets an absolute Chunk position without silently wrapping an axis. */
    fun offset(position: ChunkPosition, x: Int, z: Int): ChunkPosition =
        ChunkPosition(
            x = offsetChunkCoordinate(position.x, x),
            z = offsetChunkCoordinate(position.z, z),
        )

    /** Offsets an absolute Region position without silently wrapping an axis. */
    fun offset(position: RegionPosition, x: Int, z: Int): RegionPosition =
        RegionPosition(
            x = offsetRegionCoordinate(position.x, x),
            z = offsetRegionCoordinate(position.z, z),
        )

    fun blockIndex(position: LocalBlockPosition): Int =
        (position.y * SECTION_SIDE + position.z) * SECTION_SIDE + position.x

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

    fun chunkIndex(position: LocalChunkPosition): Int = position.x + position.z * REGION_SIDE

    fun localChunk(index: Int): LocalChunkPosition {
        require(index in 0 until REGION_CHUNK_COUNT) { "Region Chunk index must be in 0 until $REGION_CHUNK_COUNT" }
        return LocalChunkPosition(
            x = index % REGION_SIDE,
            z = index / REGION_SIDE,
        )
    }

    fun blockXRange(position: ChunkPosition): IntRange = coordinateRange(position.x, CHUNK_SIDE, "Chunk Block X")

    fun blockZRange(position: ChunkPosition): IntRange = coordinateRange(position.z, CHUNK_SIDE, "Chunk Block Z")

    fun blockXRange(position: SectionPosition): IntRange = coordinateRange(position.x, SECTION_SIDE, "Section Block X")

    fun blockYRange(position: SectionPosition): IntRange = coordinateRange(position.y, SECTION_SIDE, "Section Block Y")

    fun blockZRange(position: SectionPosition): IntRange = coordinateRange(position.z, SECTION_SIDE, "Section Block Z")

    fun chunkXRange(position: RegionPosition): IntRange = coordinateRange(position.x, REGION_SIDE, "Region Chunk X")

    fun chunkZRange(position: RegionPosition): IntRange = coordinateRange(position.z, REGION_SIDE, "Region Chunk Z")

    fun blockXRange(position: RegionPosition): IntRange =
        coordinateRange(position.x, REGION_SIDE * CHUNK_SIDE, "Region Block X")

    fun blockZRange(position: RegionPosition): IntRange =
        coordinateRange(position.z, REGION_SIDE * CHUNK_SIDE, "Region Block Z")

    /** Every Section-local Block coordinate, in palette-index order. */
    fun localBlockPositions(): Sequence<LocalBlockPosition> =
        (0 until SECTION_BLOCK_COUNT).asSequence().map(::localBlock)

    /** Every absolute Block position in [section], in palette-index order. */
    fun blockPositions(section: SectionPosition): Sequence<BlockPosition> =
        localBlockPositions().map { position -> block(section, position) }

    /** Every Region-local Chunk coordinate, in Anvil header-index order. */
    fun localChunkPositions(): Sequence<LocalChunkPosition> =
        (0 until REGION_CHUNK_COUNT).asSequence().map(::localChunk)

    /** Every absolute Chunk position in [region], in Anvil header-index order. */
    fun chunkPositions(region: RegionPosition): Sequence<ChunkPosition> =
        localChunkPositions().map { position -> chunk(region, position) }

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

    private fun floorDivide(value: Int, divisor: Int): Int {
        val quotient = value / divisor
        val remainder = value % divisor
        return if (remainder != 0 && value < 0) quotient - 1 else quotient
    }

    private fun floorModulo(value: Int, divisor: Int): Int = value - floorDivide(value, divisor) * divisor

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
