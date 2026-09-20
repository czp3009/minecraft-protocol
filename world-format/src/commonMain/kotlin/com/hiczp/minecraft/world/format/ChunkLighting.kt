package com.hiczp.minecraft.world.format


/** Mutable absolute column heights. Arrays are retained and replaceable; [known] distinguishes missing samples. */
class Heightmap(
    var values: IntArray,
    var known: BooleanArray,
) {
    constructor(initialValue: Int? = null) : this(
        IntArray(256) { initialValue ?: 0 },
        BooleanArray(256) { initialValue != null })

    constructor(values: IntArray) : this(values, BooleanArray(values.size) { true })

    operator fun get(index: Int): Int? = if (known[index]) values[index] else null
    operator fun set(index: Int, value: Int?) {
        known[index] = value != null
        if (value != null) values[index] = value
    }

    operator fun get(x: Int, z: Int): Int? = get(z * 16 + x)
    operator fun set(x: Int, z: Int, value: Int?) {
        set(z * 16 + x, value)
    }
}

/**
 * Mutable 4-bit light samples in X/Z/Y order. Null [data] represents [uniformValue] without an array.
 * The packed array is caller-owned and replaceable; editing it directly changes subsequent reads and encodings.
 * Missing light is a null LightLayer, independently of the uniform zero state.
 */
class LightLayer(var data: ByteArray?, var uniformValue: Int = 0) : Iterable<Int> {
    constructor(initialValue: Int) : this(null, initialValue) {
        require(initialValue in 0..15) { "Light values must be in 0..15" }
    }

    operator fun get(index: Int): Int =
        data?.let { it[index ushr 1].toInt() ushr ((index and 1) * 4) and 15 } ?: uniformValue

    operator fun set(index: Int, value: Int) {
        if (data == null && value == uniformValue) return
        val bytes = data ?: ByteArray(2048) { (uniformValue or (uniformValue shl 4)).toByte() }.also { data = it }
        val offset = index ushr 1
        val shift = (index and 1) * 4
        bytes[offset] = ((bytes[offset].toInt() and (15 shl shift).inv()) or ((value and 15) shl shift)).toByte()
    }

    operator fun get(localBlockPosition: LocalBlockPosition): Int = get(localBlockPosition.index)
    operator fun set(localBlockPosition: LocalBlockPosition, value: Int) {
        set(localBlockPosition.index, value)
    }

    fun fill(value: Int) {
        uniformValue = value; data = null
    }

    /** Reads current storage, including edits through retained array aliases. */
    fun isEmpty(): Boolean = data?.all { it == 0.toByte() } ?: (uniformValue == 0)

    /** Detached packed representation, without expanding 4096 boxed values. */
    fun toByteArray(): ByteArray = data?.copyOf() ?: ByteArray(2048) { (uniformValue or (uniformValue shl 4)).toByte() }

    override fun iterator(): Iterator<Int> = object : Iterator<Int> {
        private var index = 0
        override fun hasNext(): Boolean = index < 4096
        override fun next(): Int {
            if (!hasNext()) throw NoSuchElementException()
            return get(index++)
        }
    }
}

data class SectionLighting(var blockLight: LightLayer? = null, var skyLight: LightLayer? = null)

data class ChunkLighting(var isLightCorrect: Boolean, var skyLightSources: ChunkSkyLightSources?)

/** Primitive column samples; missing and below-world boundaries are independent from ordinary Y coordinates. */
class ChunkSkyLightSources(
    var values: IntArray = IntArray(256),
    var known: BooleanArray = BooleanArray(256),
    var belowWorld: BooleanArray = BooleanArray(256),
) {
    operator fun get(index: Int): SkyLightSourceBoundary? = when {
        !known[index] -> null
        belowWorld[index] -> SkyLightSourceBoundary.BelowWorld
        else -> SkyLightSourceBoundary.AtY(values[index])
    }

    operator fun set(index: Int, value: SkyLightSourceBoundary?) {
        known[index] = value != null
        belowWorld[index] = value === SkyLightSourceBoundary.BelowWorld
        if (value is SkyLightSourceBoundary.AtY) values[index] = value.y
    }
}

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

data class ChunkHeightmaps(
    var maps: MutableMap<HeightmapType, Heightmap> = linkedMapOf(),
    var properties: DataProperties = DataProperties(),
)
