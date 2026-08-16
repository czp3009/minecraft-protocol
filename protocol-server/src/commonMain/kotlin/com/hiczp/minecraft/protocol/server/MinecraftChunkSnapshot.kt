package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.data.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.model.packet.ChunkDataAndUpdateLightPacket
import com.hiczp.minecraft.protocol.model.type.*

/**
 * A complete client-facing chunk column. It is a protocol projection, not an
 * authoritative world or a mutable chunk implementation.
 */
data class MinecraftChunkSnapshot(
    val chunkX: Int,
    val chunkZ: Int,
    val chunkData: ChunkData,
    val lightData: LightUpdateData,
) {
    fun packet(): ChunkDataAndUpdateLightPacket =
        ChunkDataAndUpdateLightPacket(
            chunkX = chunkX,
            chunkZ = chunkZ,
            chunkData = chunkData,
            lightData = lightData,
        )

    companion object {
        /**
         * Creates a full-height chunk with one solid surface layer and
         * uniform biome data. Sections are emitted bottom-to-top.
         */
        fun flat(
            dimension: MinecraftDimensionLayout,
            chunkX: Int,
            chunkZ: Int,
            groundY: Int,
            surfaceBlockStateId: Int,
            biomeId: Int,
            airBlockStateId: Int,
            fullBrightSky: Boolean = dimension.hasSkyLight,
        ): MinecraftChunkSnapshot {
            require(groundY in dimension.minY until dimension.minY + dimension.height) {
                "Ground Y $groundY is outside ${dimension.id}"
            }
            require(
                airBlockStateId >= 0,
            ) {
                "Air block-state ID must be non-negative"
            }
            require(
                surfaceBlockStateId >= 0,
            ) {
                "Surface block-state ID must be non-negative"
            }
            require(surfaceBlockStateId != airBlockStateId) {
                "The surface block state must differ from air"
            }
            require(biomeId >= 0) {
                "A biome registry ID must be non-negative"
            }
            require(!fullBrightSky || dimension.hasSkyLight) {
                "${dimension.id} has no sky light"
            }

            val groundOffset = groundY - dimension.minY
            val groundSection = groundOffset / SECTION_SIZE
            val localGroundY = groundOffset % SECTION_SIZE
            val sections = List(dimension.sectionCount) { sectionIndex ->
                ChunkSection(
                    nonAirBlockCount =
                        if (sectionIndex == groundSection) {
                            SURFACE_BLOCK_COUNT
                        } else {
                            0
                        },
                    fluidCount = 0,
                    blockStates =
                        if (sectionIndex == groundSection) {
                            surfacePalette(
                                airBlockStateId = airBlockStateId,
                                surfaceBlockStateId = surfaceBlockStateId,
                                localGroundY = localGroundY,
                            )
                        } else {
                            PalettedContainer.Single(airBlockStateId)
                        },
                    biomes = PalettedContainer.Single(biomeId),
                )
            }
            val height = groundY - dimension.minY + 1
            val heightmap = packValues(
                bitsPerEntry = bitsToRepresent(dimension.height),
                entryCount = HEIGHTMAP_ENTRY_COUNT,
            ) { height }.toLongArray()
            val fullSkyLayer = LightDataLayer(
                ByteString(ByteArray(LightDataLayer.DATA_LAYER_BYTES) {
                    FULL_LIGHT_BYTE
                }),
            )

            return MinecraftChunkSnapshot(
                chunkX = chunkX,
                chunkZ = chunkZ,
                chunkData = ChunkData(
                    heightmaps = mapOf(
                        HeightmapType.WORLD_SURFACE to heightmap,
                        HeightmapType.MOTION_BLOCKING to heightmap.copyOf(),
                    ),
                    sections = sections,
                    blockEntities = emptyList(),
                ),
                lightData = LightUpdateData(
                    skyYMask =
                        if (fullBrightSky) {
                            sectionMask(dimension.sectionCount)
                        } else {
                            emptyBitSet()
                        },
                    blockYMask = emptyBitSet(),
                    emptySkyYMask =
                        if (fullBrightSky) {
                            emptyBitSet()
                        } else {
                            sectionMask(dimension.sectionCount)
                        },
                    emptyBlockYMask = sectionMask(dimension.sectionCount),
                    skyUpdates =
                        if (fullBrightSky) {
                            List(dimension.sectionCount) { fullSkyLayer }
                        } else {
                            emptyList()
                        },
                    blockUpdates = emptyList(),
                ),
            )
        }

        /** Resolves all palette IDs from the caller's active registry context. */
        fun flat(
            registries: ProtocolRegistryContext,
            dimension: MinecraftDimensionLayout,
            chunkX: Int,
            chunkZ: Int,
            groundY: Int,
            surfaceBlock: Identifier,
            biome: Identifier,
            airBlock: Identifier = Identifier("air"),
            fullBrightSky: Boolean = dimension.hasSkyLight,
        ): MinecraftChunkSnapshot = flat(
            dimension = dimension,
            chunkX = chunkX,
            chunkZ = chunkZ,
            groundY = groundY,
            surfaceBlockStateId = registries
                .requireDefaultBlockState(surfaceBlock)
                .id,
            biomeId = registries.requireRegistryEntry(
                ProtocolRegistryContext.BIOME_REGISTRY,
                biome,
            ).rawId,
            airBlockStateId = registries
                .requireDefaultBlockState(airBlock)
                .id,
            fullBrightSky = fullBrightSky,
        )

        private fun surfacePalette(
            airBlockStateId: Int,
            surfaceBlockStateId: Int,
            localGroundY: Int,
        ): PalettedContainer.Indirect =
            PalettedContainer.Indirect(
                bitsPerEntry = BLOCK_INDIRECT_BITS,
                palette = listOf(
                    airBlockStateId,
                    surfaceBlockStateId,
                ),
                data = packValues(
                    bitsPerEntry = BLOCK_INDIRECT_BITS,
                    entryCount = ChunkSection.BLOCK_COUNT,
                ) { index ->
                    if (index / SURFACE_BLOCK_COUNT == localGroundY) 1 else 0
                },
            )

        private fun sectionMask(sectionCount: Int): BitSet {
            val words = LongArray(
                (sectionCount + LIGHT_BOUNDARY_SECTION_COUNT +
                        Long.SIZE_BITS - 1) / Long.SIZE_BITS,
            )
            for (bit in 1..sectionCount) {
                val word = bit / Long.SIZE_BITS
                words[word] = words[word] or
                        (1L shl (bit % Long.SIZE_BITS))
            }
            return BitSet(words)
        }

        private fun emptyBitSet(): BitSet = BitSet(LongArray(0))

        private fun packValues(
            bitsPerEntry: Int,
            entryCount: Int,
            valueAt: (Int) -> Int,
        ): PackedLongArray {
            require(bitsPerEntry in 1..<Int.SIZE_BITS)
            require(entryCount >= 0)
            val entriesPerLong = Long.SIZE_BITS / bitsPerEntry
            val values = LongArray(
                (entryCount + entriesPerLong - 1) / entriesPerLong,
            )
            val maximumValue = (1L shl bitsPerEntry) - 1
            repeat(entryCount) { index ->
                val value = valueAt(index)
                require(value >= 0 && value.toLong() <= maximumValue) {
                    "Packed value $value does not fit in $bitsPerEntry bits"
                }
                val longIndex = index / entriesPerLong
                val bitIndex = index % entriesPerLong * bitsPerEntry
                values[longIndex] = values[longIndex] or
                        (value.toLong() shl bitIndex)
            }
            return PackedLongArray(values)
        }

        private fun bitsToRepresent(maximumValue: Int): Int {
            require(maximumValue > 0)
            return Int.SIZE_BITS - maximumValue.countLeadingZeroBits()
        }

        private const val SECTION_SIZE: Int = 16
        private const val SURFACE_BLOCK_COUNT: Int = SECTION_SIZE * SECTION_SIZE
        private const val HEIGHTMAP_ENTRY_COUNT: Int = SECTION_SIZE * SECTION_SIZE
        private const val BLOCK_INDIRECT_BITS: Int = 4
        private const val LIGHT_BOUNDARY_SECTION_COUNT: Int = 2
        private const val FULL_LIGHT_BYTE: Byte = -1
    }
}
