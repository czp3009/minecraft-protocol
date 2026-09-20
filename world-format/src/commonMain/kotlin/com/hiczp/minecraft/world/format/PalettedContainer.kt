package com.hiczp.minecraft.world.format


/**
 * Detached palette diagnostics, not a copy of the cells. Element references are shared.
 *
 * [bitsPerEntry] is the smallest logical width for the current palette IDs. A physical format may impose a larger
 * minimum when it packs those IDs.
 */
data class PaletteInfo<T : Any>(
    val values: List<T>,
    val bitsPerEntry: Int,
    val entryCount: Int,
)

/**
 * Mutable palette-backed values. Elements must retain stable equality and hash codes while stored.
 * Single-valued storage needs no per-cell array; other IDs are packed in primitive words.
 * Writes preserve palette IDs until [compact]; [fill] keeps the palette but replaces all cells.
 * Growth deliberately trades retained history for inexpensive writes. The caller chooses when to compact, typically
 * before copying data for background persistence. Encoding alone never compacts this mutable container in place.
 * This container has no synchronization and does not update any owning Section statistics.
 */
class PalettedContainer<T : Any> private constructor(
    val size: Int,
    private var palette: MutableList<T>,
) : Iterable<T> {
    private var reverse = HashMap<T, Int>()
    private var words: LongArray? = null
    private var bits = 0
    private var uniformId = 0

    init {
        require(size > 0) { "A paletted container must not be empty" }
        require(palette.isNotEmpty()) { "A paletted container must have at least one palette value" }
        palette.forEachIndexed { index, value -> if (value !in reverse) reverse[value] = index }
    }

    constructor(size: Int, initialValue: T) : this(size, mutableListOf(initialValue))

    constructor(values: List<T>) : this(values.size, mutableListOf(values.first())) {
        values.forEachIndexed { index, value -> replace(index, value) }
    }

    operator fun get(index: Int): T {
        requireIndex(index)
        return palette[idAt(index)]
    }

    operator fun set(index: Int, value: T) {
        replace(index, value)
    }

    /** Returns the old value. No palette search proportional to the number of distinct values is needed. */
    fun replace(index: Int, value: T): T {
        requireIndex(index)
        val oldId = idAt(index)
        val id = paletteId(value)
        if (oldId != id) {
            ensureWidth(maxOf(id, uniformId))
            putId(index, id)
        }
        return palette[oldId]
    }

    /** Replaces all cells without a per-cell loop or materializing an ID array. */
    fun fill(value: T) {
        uniformId = paletteId(value)
        words = null
        bits = 0
    }

    /** Copies the palette table for inspection; entries can be unused until [compact]. No cell indexes are included. */
    fun paletteInfo(): PaletteInfo<T> = PaletteInfo(palette.toList(), bitsForPaletteSize(palette.size), size)

    fun distinctValues(): Set<T> {
        if (words == null) return setOf(palette[uniformId])
        val seen = BooleanArray(palette.size)
        val values = LinkedHashSet<T>()
        repeat(size) {
            val id = idAt(it)
            if (!seen[id]) {
                seen[id] = true; values.add(palette[id])
            }
        }
        return values
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

    /**
     * Copies current palette history, cell indexes and packed width without compaction. Mutable storage is independent;
     * element references are shared. Callers coordinate copying with concurrent access, as with other operations.
     */
    fun copy(): PalettedContainer<T> = PalettedContainer(size, palette.toMutableList()).also {
        it.words = words?.copyOf()
        it.bits = bits
        it.uniformId = uniformId
    }

    /**
     * Removes unused entries, deduplicates equal values and replaces historical palette/index capacity without changing
     * logical cell values. This is an explicit maintenance operation; callers coordinate it with other access.
     */
    fun compact() {
        val compacted = compactCopy()
        palette = compacted.palette
        reverse = compacted.reverse
        words = compacted.words
        bits = compacted.bits
        uniformId = compacted.uniformId
    }

    /**
     * Constructs an independently editable compact container without modifying or copying the historical storage first.
     * Elements are shared; the new palette follows first-use order. Work is linear in cell count plus palette size.
     * Uniform data requires no per-cell array. Other cells are packed directly, without a dense temporary ID array.
     */
    fun compactCopy(): PalettedContainer<T> {
        if (words == null) return PalettedContainer(size, palette[uniformId])
        val result = PalettedContainer(size, palette[idAt(0)])
        val remap = IntArray(palette.size) { -1 }
        repeat(size) { index ->
            val oldId = idAt(index)
            if (remap[oldId] < 0) remap[oldId] = result.paletteId(palette[oldId])
        }
        if (result.palette.size > 1) {
            result.bits = bitsForPaletteSize(result.palette.size)
            val perWord = Long.SIZE_BITS / result.bits
            result.words = LongArray((size + perWord - 1) / perWord)
            repeat(size) { index -> result.putId(index, remap[idAt(index)]) }
        }
        return result
    }

    /**
     * Current local palette ID of a cell, suitable for indexing [paletteInfo]'s table. This is not a registry ID.
     * Writes retain existing IDs; [compact] can renumber them. A retained diagnostics table predates later additions.
     */
    fun paletteIndex(index: Int): Int {
        requireIndex(index)
        return idAt(index)
    }

    private fun paletteId(value: T): Int = reverse.getOrPut(value) {
        palette.add(value)
        palette.lastIndex
    }

    private fun idAt(index: Int): Int {
        val data = words ?: return uniformId
        val perWord = Long.SIZE_BITS / bits
        return (data[index / perWord] ushr (index % perWord * bits) and ((1L shl bits) - 1)).toInt()
    }

    private fun putId(index: Int, id: Int) {
        val perWord = Long.SIZE_BITS / bits
        val shift = index % perWord * bits
        val mask = ((1L shl bits) - 1) shl shift
        val data = words!!
        data[index / perWord] = (data[index / perWord] and mask.inv()) or (id.toLong() shl shift)
    }

    private fun ensureWidth(maxId: Int) {
        val requiredBits = maxOf(1, bitsForPaletteSize(maxId + 1))
        if (words != null && requiredBits <= bits) return
        val oldWords = words
        val oldBits = bits
        val oldPerWord = if (oldBits == 0) 0 else Long.SIZE_BITS / oldBits
        bits = requiredBits
        val perWord = Long.SIZE_BITS / bits
        words = LongArray((size + perWord - 1) / perWord)
        repeat(size) { index ->
            val id = if (oldWords == null) uniformId else
                (oldWords[index / oldPerWord] ushr (index % oldPerWord * oldBits) and ((1L shl oldBits) - 1)).toInt()
            putId(index, id)
        }
    }

    private fun install(ids: IntArray) {
        require(ids.size == size && ids.all { it in palette.indices }) { "A paletted container contains invalid IDs" }
        uniformId = ids[0]
        words = null
        bits = 0
        if (ids.any { it != uniformId }) {
            ensureWidth(ids.max())
            ids.forEachIndexed { index, id -> putId(index, id) }
        }
    }

    private fun requireIndex(index: Int) {
        require(index in 0 until size) { "Palette index $index is outside 0 until $size" }
    }

    companion object {
        /** Snapshots both inputs; equal duplicate palette entries remain legal until explicit compaction. */
        fun <T : Any> fromPalette(palette: List<T>, ids: IntArray): PalettedContainer<T> =
            PalettedContainer(ids.size, palette.toMutableList()).also { it.install(ids) }
    }
}
