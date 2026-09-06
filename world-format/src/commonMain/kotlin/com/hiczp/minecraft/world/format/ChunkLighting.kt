package com.hiczp.minecraft.world.format


/** A fixed 16 by 16 column grid, indexed in Z-major order. */
class ColumnData<T>(values: List<T>) : Iterable<T> {
    private val values = values.toMutableList()

    init {
        require(this.values.size == MinecraftCoordinates.SECTION_SIDE * MinecraftCoordinates.SECTION_SIDE) { "Column data needs 256 entries" }
    }

    constructor(initialValue: T) : this(List(MinecraftCoordinates.SECTION_SIDE * MinecraftCoordinates.SECTION_SIDE) { initialValue })

    operator fun get(index: Int): T = values[index]

    operator fun set(index: Int, value: T) {
        values[index] = value
    }

    operator fun get(x: Int, z: Int): T = values[index(x, z)]

    operator fun set(x: Int, z: Int, value: T) {
        values[index(x, z)] = value
    }

    fun toList(): List<T> = values.toList()

    override fun iterator(): Iterator<T> = values.iterator()

    private fun index(x: Int, z: Int): Int {
        require(x in 0 until MinecraftCoordinates.SECTION_SIDE && z in 0 until MinecraftCoordinates.SECTION_SIDE) { "Column coordinates must be in 0..15" }
        return z * MinecraftCoordinates.SECTION_SIDE + x
    }
}

/** Missing layers are represented by null in SectionLighting; a present all-zero layer is known darkness. */
class LightLayer(values: List<Int>) : Iterable<Int> {
    private val values = values.toMutableList()

    init {
        require(this.values.size == MinecraftCoordinates.SECTION_BLOCK_COUNT) {
            "A light layer needs ${MinecraftCoordinates.SECTION_BLOCK_COUNT} values"
        }
        require(this.values.all { it in 0..15 }) { "Light values must be in 0..15" }
    }

    constructor(initialValue: Int) : this(List(MinecraftCoordinates.SECTION_BLOCK_COUNT) { initialValue })

    operator fun get(index: Int): Int = values[index]

    operator fun set(index: Int, value: Int) {
        require(value in 0..15) { "Light values must be in 0..15" }
        values[index] = value
    }

    operator fun get(localBlockPosition: LocalBlockPosition): Int = get(localBlockPosition.index)

    operator fun set(localBlockPosition: LocalBlockPosition, value: Int) {
        set(localBlockPosition.index, value)
    }

    override fun iterator(): Iterator<Int> = values.iterator()
}

data class SectionLighting(var blockLight: LightLayer? = null, var skyLight: LightLayer? = null)

data class ChunkLighting(var isLightCorrect: Boolean, var skyLightSources: ChunkSkyLightSources?)

data class ChunkSkyLightSources(var lowestSource: ColumnData<SkyLightSourceBoundary?>)

sealed interface SkyLightSourceBoundary {
    data class AtY(val y: Int) : SkyLightSourceBoundary
    data object BelowWorld : SkyLightSourceBoundary
}

data class HeightmapType(val serializationKey: String) {
    init {
        require(serializationKey.isNotBlank()) { "A heightmap type must not be blank" }
    }

    companion object {
        val WorldSurfaceWg: HeightmapType = HeightmapType("WORLD_SURFACE_WG")
        val WorldSurface: HeightmapType = HeightmapType("WORLD_SURFACE")
        val OceanFloorWg: HeightmapType = HeightmapType("OCEAN_FLOOR_WG")
        val OceanFloor: HeightmapType = HeightmapType("OCEAN_FLOOR")
        val MotionBlocking: HeightmapType = HeightmapType("MOTION_BLOCKING")
        val MotionBlockingNoLeaves: HeightmapType = HeightmapType("MOTION_BLOCKING_NO_LEAVES")
    }
}

/** Absolute first available Y. Null is unknown; the dimension's minY is a known empty column. */
data class Heightmap(var firstAvailable: ColumnData<Int?>)

data class ChunkHeightmaps(
    var maps: MutableMap<HeightmapType, Heightmap> = linkedMapOf(),
    var properties: DataProperties = DataProperties(),
)
