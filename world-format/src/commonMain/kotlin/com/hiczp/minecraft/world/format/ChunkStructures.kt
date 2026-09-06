package com.hiczp.minecraft.world.format


enum class Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }

enum class Direction8 { NORTH, NORTH_EAST, EAST, SOUTH_EAST, SOUTH, SOUTH_WEST, WEST, NORTH_WEST }

/** Inclusive block bounds, independent of a piece's world owner or derived transforms. */
data class BoundingBox(val min: BlockPosition, val max: BlockPosition) {
    init {
        require(min.x <= max.x && min.y <= max.y && min.z <= max.z) { "Bounding box bounds must be ordered" }
    }
}

data class ChunkStructures(
    var starts: MutableMap<StructureId, StructureStart> = linkedMapOf(),
    var references: MutableMap<StructureId, MutableSet<ChunkPosition>> = linkedMapOf(),
    var properties: DataProperties = DataProperties(),
)

sealed interface StructureStart {
    data object Invalid : StructureStart

    data class Valid(
        var chunkPosition: ChunkPosition,
        var references: Int,
        var pieces: MutableList<StructurePiece>,
        var properties: DataProperties = DataProperties(),
    ) : StructureStart
}

data class StructurePiece(
    var pieceTypeId: StructurePieceTypeId,
    var boundingBox: BoundingBox,
    var orientation: Direction?,
    var genDepth: Int,
    var properties: DataProperties = DataProperties(),
)

data class ChunkPostProcessing(var positions: MutableMap<Int, MutableList<LocalBlockPosition>> = linkedMapOf())

enum class TickPriority(val value: Int) {
    EXTREMELY_HIGH(-3), VERY_HIGH(-2), HIGH(-1), NORMAL(0), LOW(1), VERY_LOW(2), EXTREMELY_LOW(3),
}

data class ScheduledTick<T : Any>(
    var type: T,
    var blockPosition: BlockPosition,
    var triggerTick: Long,
    var priority: TickPriority,
    var subTickOrder: Long,
    var properties: DataProperties = DataProperties(),
)

data class SavedTick<T : Any>(
    var type: T,
    var blockPosition: BlockPosition,
    var delay: Int,
    var priority: TickPriority,
    var properties: DataProperties = DataProperties(),
)

data class UpgradeData(
    var sides: MutableSet<Direction8>,
    var indices: MutableMap<Int, MutableList<LocalBlockPosition>>,
    var neighborBlockTicks: MutableList<SavedTick<BlockId>>,
    var neighborFluidTicks: MutableList<SavedTick<FluidId>>,
    var properties: DataProperties = DataProperties(),
)

/**
 * The old-generation vertical interval is [minSection, maxSection). Null height samples mean NO_VALUE.
 * Biome and density columns are runtime data and remain null until supplied by the caller.
 *
 * Official BlendingData uses 16 perimeter columns in quart coordinates. For indices 0..6, x = max(3 - i, 0)
 * and z = max(i - 3, 0). For indices 7..15, j = i - 7, x = 4 - max(4 - j, 0), z = 4 - max(j - 4, 0).
 * The inverse is 3 - x + z on the inner edges and 7 + x + 4 - z on the outer edges. Coordinates include the
 * neighboring boundary at quart 4; interior columns are not represented.
 *
 * A biome column has (maxSection - minSection) * 4 entries indexed by absolute quart Y - minSection * 4.
 * A density column has (maxSection - minSection) * 2 entries, spaced eight blocks apart; its index is absolute
 * density-cell Y - (minSection * 2 + 1). Null columns mean unavailable samples, independently of null heights.
 */
data class BlendingData(
    var minSection: Int,
    var maxSection: Int,
    var heights: MutableList<Double?>,
    var biomes: MutableList<MutableList<BiomeId>?>?,
    var densities: MutableList<MutableList<Double>?>?,
    var properties: DataProperties = DataProperties(),
)
