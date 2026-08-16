package com.hiczp.minecraft.world.format

/** One raw entry in an Anvil region location table. */
data class RegionLocation(
    val sectorOffset: Int,
    val sectorCount: Int,
) {
    init {
        require(sectorOffset in 0..REGION_MAX_SECTOR_OFFSET)
        require(sectorCount in 0..REGION_MAX_SECTOR_COUNT)
    }

    val packed: Int
        get() = (sectorOffset shl Byte.SIZE_BITS) or sectorCount

    val byteOffset: Long
        get() = sectorOffset.toLong() * REGION_SECTOR_BYTES

    val allocatedBytes: Int
        get() = sectorCount * REGION_SECTOR_BYTES

    /** Vanilla's compatibility checks when a region handle is opened. */
    fun isUsableAtOpen(fileSize: Long): Boolean =
        sectorOffset >= REGION_HEADER_SECTORS &&
                sectorCount > 0 &&
                byteOffset <= fileSize

    companion object {
        fun fromPacked(packed: Int): RegionLocation? =
            if (packed == 0) {
                null
            } else {
                RegionLocation(
                    sectorOffset = packed ushr Byte.SIZE_BITS,
                    sectorCount = packed and REGION_MAX_SECTOR_COUNT,
                )
            }
    }
}

/**
 * The two fixed Anvil header sectors.
 *
 * Decoding accepts a short header and treats its missing suffix as zero. This
 * is the behavior used when vanilla opens an existing region file. A strict
 * complete-file codec may still reject the same truncation before calling it.
 */
class RegionHeader private constructor(
    private val locations: IntArray,
    private val timestamps: IntArray,
) {
    constructor() : this(
        locations = IntArray(REGION_CHUNK_COUNT),
        timestamps = IntArray(REGION_CHUNK_COUNT),
    )

    fun location(position: LocalChunkPosition): RegionLocation? =
        RegionLocation.fromPacked(locations[position.index])

    fun timestamp(position: LocalChunkPosition): Int =
        timestamps[position.index]

    fun set(
        position: LocalChunkPosition,
        location: RegionLocation?,
        timestamp: Int,
    ) {
        locations[position.index] = location?.packed ?: 0
        timestamps[position.index] = timestamp
    }

    fun clearLocation(position: LocalChunkPosition) {
        locations[position.index] = 0
    }

    fun copy(): RegionHeader = RegionHeader(
        locations = locations.copyOf(),
        timestamps = timestamps.copyOf(),
    )

    fun encode(): ByteArray = ByteArray(REGION_HEADER_BYTES).also { bytes ->
        for (index in 0 until REGION_CHUNK_COUNT) {
            writeRegionInt(
                bytes,
                index * Int.SIZE_BYTES,
                locations[index],
            )
            writeRegionInt(
                bytes,
                REGION_SECTOR_BYTES + index * Int.SIZE_BYTES,
                timestamps[index],
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RegionHeader &&
                locations.contentEquals(other.locations) &&
                timestamps.contentEquals(other.timestamps)

    override fun hashCode(): Int =
        31 * locations.contentHashCode() + timestamps.contentHashCode()

    companion object {
        fun decode(bytes: ByteArray): RegionHeader {
            require(bytes.size <= REGION_HEADER_BYTES)
            val complete = if (bytes.size == REGION_HEADER_BYTES) {
                bytes
            } else {
                ByteArray(REGION_HEADER_BYTES).also {
                    bytes.copyInto(it)
                }
            }
            val locations = IntArray(REGION_CHUNK_COUNT)
            val timestamps = IntArray(REGION_CHUNK_COUNT)
            for (index in 0 until REGION_CHUNK_COUNT) {
                locations[index] = readRegionInt(
                    complete,
                    index * Int.SIZE_BYTES,
                )
                timestamps[index] = readRegionInt(
                    complete,
                    REGION_SECTOR_BYTES + index * Int.SIZE_BYTES,
                )
            }
            return RegionHeader(locations, timestamps)
        }
    }
}

/** First-fit sector allocation matching vanilla's region bitmap. */
class RegionSectorAllocator {
    private var used = booleanArrayOf(true, true)

    fun mark(location: RegionLocation) {
        require(location.sectorCount > 0)
        ensureCapacity(location.sectorOffset + location.sectorCount)
        for (
        sector in location.sectorOffset until
                location.sectorOffset + location.sectorCount
        ) {
            used[sector] = true
        }
    }

    fun allocate(sectorCount: Int): RegionLocation {
        require(sectorCount in 1..REGION_MAX_SECTOR_COUNT)
        var start = REGION_HEADER_SECTORS
        while (true) {
            ensureCapacity(start + sectorCount)
            var available = true
            for (sector in start until start + sectorCount) {
                if (used[sector]) {
                    start = sector + 1
                    available = false
                    break
                }
            }
            if (!available) continue
            if (start > REGION_MAX_SECTOR_OFFSET) {
                throw RegionFormatException(
                    "Region allocation exceeds location-table range",
                )
            }
            val location = RegionLocation(start, sectorCount)
            mark(location)
            return location
        }
    }

    fun free(location: RegionLocation?) {
        if (location == null || location.sectorCount == 0) return
        ensureCapacity(location.sectorOffset + location.sectorCount)
        for (
        sector in location.sectorOffset until
                location.sectorOffset + location.sectorCount
        ) {
            used[sector] = false
        }
        used[0] = true
        used[1] = true
    }

    fun isUsed(sector: Int): Boolean =
        sector in used.indices && used[sector]

    private fun ensureCapacity(size: Int) {
        if (used.size >= size) return
        var newSize = used.size
        while (newSize < size) {
            newSize = maxOf(size, newSize * 2)
        }
        used = used.copyOf(newSize)
    }
}

/** The five-byte record prefix stored at the beginning of a chunk allocation. */
data class RegionChunkRecordHeader(
    val length: Int,
    val compression: Compression,
    val external: Boolean,
) {
    val compressedLength: Int
        get() = length - 1

    companion object {
        /** Compression ID stored in one record header's version byte. */
        fun compressionId(compression: Compression): Int =
            when (compression) {
                Compression.GZIP -> 1
                Compression.ZLIB -> 2
                Compression.NONE -> 3
                Compression.LZ4 -> 4
                Compression.CUSTOM -> 127
            }

        /** Compression for one record-header ID, or null when it is unknown. */
        fun compressionFromId(compressionId: Int): Compression? =
            when (compressionId) {
                1 -> Compression.GZIP
                2 -> Compression.ZLIB
                3 -> Compression.NONE
                4 -> Compression.LZ4
                127 -> Compression.CUSTOM
                else -> null
            }

        fun decode(bytes: ByteArray): RegionChunkRecordHeader {
            if (bytes.size < REGION_CHUNK_RECORD_HEADER_BYTES) {
                throw RegionFormatException("Truncated region chunk record header")
            }
            val version = bytes[Int.SIZE_BYTES].toInt() and 0xFF
            val compressionId = version and REGION_EXTERNAL_STREAM_FLAG.inv()
            val compression = compressionFromId(compressionId)
                ?: throw RegionFormatException(
                    "Unknown region compression ID $compressionId",
                )
            return RegionChunkRecordHeader(
                length = readRegionInt(bytes, 0),
                compression = compression,
                external = version and REGION_EXTERNAL_STREAM_FLAG != 0,
            )
        }
    }
}

/** A complete inline record or a one-sector external-stream stub. */
class EncodedRegionChunkRecord private constructor(
    val bytes: ByteArray,
    val externalPayload: ByteArray?,
    val allocatedSectors: Int,
) {
    val external: Boolean
        get() = externalPayload != null

    companion object {
        fun encode(
            compression: Compression,
            compressedPayload: ByteArray,
            forceExternal: Boolean = false,
        ): EncodedRegionChunkRecord {
            val inlineBytes =
                REGION_CHUNK_RECORD_HEADER_BYTES.toLong() +
                        compressedPayload.size
            val inlineSectors = sectorsForBytes(inlineBytes)
            val external = forceExternal ||
                    inlineSectors >= REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD
            if (external) {
                val stub = ByteArray(REGION_CHUNK_RECORD_HEADER_BYTES)
                writeRegionInt(stub, 0, 1)
                stub[Int.SIZE_BYTES] = (
                        RegionChunkRecordHeader.compressionId(compression) or
                                REGION_EXTERNAL_STREAM_FLAG
                        ).toByte()
                return EncodedRegionChunkRecord(
                    bytes = stub,
                    externalPayload = compressedPayload,
                    allocatedSectors = 1,
                )
            }

            val record = ByteArray(inlineBytes.toInt())
            writeRegionInt(record, 0, compressedPayload.size + 1)
            record[Int.SIZE_BYTES] = RegionChunkRecordHeader.compressionId(compression).toByte()
            compressedPayload.copyInto(
                destination = record,
                destinationOffset = REGION_CHUNK_RECORD_HEADER_BYTES,
            )
            return EncodedRegionChunkRecord(
                bytes = record,
                externalPayload = null,
                allocatedSectors = inlineSectors,
            )
        }
    }
}

internal fun readRegionInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

internal fun writeRegionInt(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

internal fun sectorsForBytes(byteCount: Long): Int {
    require(byteCount >= 0)
    val sectors = if (byteCount == 0L) {
        0L
    } else {
        (byteCount - 1L) / REGION_SECTOR_BYTES + 1L
    }
    if (sectors > Int.MAX_VALUE) {
        throw RegionFormatException("Region record is too large")
    }
    return sectors.toInt()
}

const val REGION_HEADER_SECTORS: Int = 2
const val REGION_CHUNK_RECORD_HEADER_BYTES: Int = Int.SIZE_BYTES + 1
const val REGION_EXTERNAL_STREAM_FLAG: Int = 0x80
const val REGION_EXTERNAL_CHUNK_SECTOR_THRESHOLD: Int = 256
const val REGION_MAX_SECTOR_COUNT: Int = 0xFF
const val REGION_MAX_SECTOR_OFFSET: Int = 0xFF_FFFF
