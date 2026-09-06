package com.hiczp.minecraft.world.format


/**
 * Read-only diagnostics for a palette-backed container.
 *
 * [bitsPerEntry] is the smallest logical width for the current palette IDs. A physical format may impose a larger
 * minimum when it packs those IDs.
 */
data class PaletteSnapshot<T : Any>(
    val values: List<T>,
    val bitsPerEntry: Int,
    val entryCount: Int,
)

/**
 * Mutable logical values backed by stable palette IDs.
 *
 * Ordinary writes reuse or append IDs. They deliberately do not remove an unused value or reorder the palette.
 */
class PalettedContainer<T : Any> private constructor(
    val size: Int,
    paletteStorage: PaletteStorage<T>,
) : Iterable<T> {
    private val palette = paletteStorage.palette
    private val ids = paletteStorage.ids

    init {
        require(size > 0) { "A paletted container must not be empty" }
        require(palette.isNotEmpty()) { "A paletted container must have at least one palette value" }
        require(ids.all { it in palette.indices }) { "A paletted container contains an invalid palette ID" }
    }

    constructor(size: Int, initialValue: T) : this(
        size,
        PaletteStorage(mutableListOf(initialValue), IntArray(size)),
    )

    constructor(values: List<T>) : this(values.size, paletteStorage(values))

    operator fun get(index: Int): T = palette[ids[index]]

    operator fun set(index: Int, value: T) {
        replace(index, value)
    }

    /** Replaces one logical value and returns the previous value without compacting the palette. */
    fun replace(index: Int, value: T): T {
        require(index in 0 until size) { "Palette index $index is outside 0 until $size" }
        val previous = palette[ids[index]]
        var id = palette.indexOf(value)
        if (id < 0) {
            palette += value
            id = palette.lastIndex
        }
        ids[index] = id
        return previous
    }

    fun paletteSnapshot(): PaletteSnapshot<T> =
        PaletteSnapshot(
            values = palette.toList(),
            bitsPerEntry = bitsForPaletteSize(palette.size),
            entryCount = size,
        )

    fun distinctValues(): Set<T> = LinkedHashSet<T>().apply {
        ids.forEach { add(palette[it]) }
    }

    fun toDenseList(): List<T> = List(size, ::get)

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private var index = 0

        override fun hasNext(): Boolean = index < size

        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            return get(index++)
        }
    }

    /** Removes unused palette values and remaps this container's IDs in place. */
    fun compact() {
        val snapshot = compactSnapshot()
        palette.clear()
        palette.addAll(snapshot.values)
        snapshot.rawIds.copyInto(ids)
    }

    /** Returns a compact palette and remapped IDs without modifying this container. */
    fun compactSnapshot(): CompactPalette<T> {
        val compactValues = mutableListOf<T>()
        val compactIds = IntArray(size)
        ids.forEachIndexed { index, oldId ->
            val value = palette[oldId]
            var compactId = compactValues.indexOf(value)
            if (compactId < 0) {
                compactValues += value
                compactId = compactValues.lastIndex
            }
            compactIds[index] = compactId
        }
        return CompactPalette(compactValues, compactIds)
    }

    companion object {
        /**
         * Creates a container from an existing palette and one palette ID per logical entry.
         *
         * Both inputs are snapshotted. This avoids constructing a temporary dense value list when adapting another
         * palette-based representation.
         */
        fun <T : Any> fromPalette(
            palette: List<T>,
            ids: IntArray,
        ): PalettedContainer<T> = PalettedContainer(
            size = ids.size,
            paletteStorage = PaletteStorage(palette.toMutableList(), ids.copyOf()),
        )
    }
}

private class PaletteStorage<T : Any>(
    val palette: MutableList<T>,
    val ids: IntArray,
)

private fun <T : Any> paletteStorage(values: List<T>): PaletteStorage<T> {
    val palette = mutableListOf<T>()
    val ids = IntArray(values.size)
    values.forEachIndexed { index, value ->
        var id = palette.indexOf(value)
        if (id < 0) {
            palette += value
            id = palette.lastIndex
        }
        ids[index] = id
    }
    return PaletteStorage(palette, ids)
}

/**
 * A read-only compact palette snapshot.
 *
 * [values] follows first-use order, while [ids] maps every logical container entry to one value.
 */
class CompactPalette<T : Any>(
    values: List<T>,
    ids: IntArray,
) {
    private val idArray = ids.copyOf()

    val values: List<T> = values.toList()
    val ids: List<Int> = idArray.asList()
    val bitsPerEntry: Int = bitsForPaletteSize(values.size)
    val entryCount: Int = idArray.size

    init {
        require(this.values.isNotEmpty()) { "A compact palette must not be empty" }
        require(idArray.isNotEmpty()) { "A compact palette must describe at least one entry" }
        require(idArray.all { it in this.values.indices }) { "A compact palette contains an invalid palette ID" }
    }

    operator fun get(index: Int): T = values[idArray[index]]

    internal val rawIds: IntArray
        get() = idArray
}
