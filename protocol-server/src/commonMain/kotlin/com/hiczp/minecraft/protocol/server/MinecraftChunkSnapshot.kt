package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.model.packet.ChunkDataAndUpdateLightPacket
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.MinecraftCoordinates
import com.hiczp.minecraft.world.format.SECTION_SIDE

/**
 * A complete client-facing chunk column. It is a protocol projection, not an
 * authoritative world or a mutable chunk implementation.
 */
data class MinecraftChunkSnapshot(
    val chunkX: Int,
    val chunkZ: Int,
    val chunkData: ChunkData,
    val lightUpdateData: LightUpdateData,
) {
    fun packet(): ChunkDataAndUpdateLightPacket =
        ChunkDataAndUpdateLightPacket(
            chunkX = chunkX,
            chunkZ = chunkZ,
            chunkData = chunkData,
            lightData = lightUpdateData,
        )

    companion object {
        /**
         * Creates a full-height chunk with one solid surface layer and
         * uniform biome data. Sections are emitted bottom-to-top.
         */
        fun flat(
            minecraftDimensionLayout: MinecraftDimensionLayout,
            chunkX: Int,
            chunkZ: Int,
            groundY: Int,
            surfaceBlockStateRawId: Int,
            biomeRawId: Int,
            airBlockStateRawId: Int,
            fullBrightSky: Boolean = minecraftDimensionLayout.hasSkyLight,
        ): MinecraftChunkSnapshot {
            require(
                groundY in minecraftDimensionLayout.minY until
                        minecraftDimensionLayout.minY + minecraftDimensionLayout.height,
            ) {
                "Ground Y $groundY is outside ${minecraftDimensionLayout.dimensionTypeId}"
            }
            require(
                airBlockStateRawId >= 0,
            ) {
                "Air block-state ID must be non-negative"
            }
            require(
                surfaceBlockStateRawId >= 0,
            ) {
                "Surface block-state ID must be non-negative"
            }
            require(surfaceBlockStateRawId != airBlockStateRawId) {
                "The surface block state must differ from air"
            }
            require(biomeRawId >= 0) {
                "A biome registry ID must be non-negative"
            }
            require(!fullBrightSky || minecraftDimensionLayout.hasSkyLight) {
                "${minecraftDimensionLayout.dimensionTypeId} has no sky light"
            }

            val minimumSectionY = MinecraftCoordinates.sectionCoordinate(minecraftDimensionLayout.minY)
            val groundSection = MinecraftCoordinates.sectionCoordinate(groundY) - minimumSectionY
            val localGroundY = MinecraftCoordinates.blockCoordinateInSection(groundY)
            val sections = List(minecraftDimensionLayout.sectionCount) { sectionIndex ->
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
                                airBlockStateRawId = airBlockStateRawId,
                                surfaceBlockStateRawId = surfaceBlockStateRawId,
                                localGroundY = localGroundY,
                            )
                        } else {
                            PalettedContainer.Single(airBlockStateRawId)
                        },
                    biomes = PalettedContainer.Single(biomeRawId),
                )
            }
            val height = groundY - minecraftDimensionLayout.minY + 1
            val heightmap = packValues(
                bitsPerEntry = bitsToRepresent(minecraftDimensionLayout.height),
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
                lightUpdateData = LightUpdateData(
                    skyYMask =
                        if (fullBrightSky) {
                            sectionMask(minecraftDimensionLayout.sectionCount)
                        } else {
                            emptyBitSet()
                        },
                    blockYMask = emptyBitSet(),
                    emptySkyYMask =
                        if (fullBrightSky) {
                            emptyBitSet()
                        } else {
                            sectionMask(minecraftDimensionLayout.sectionCount)
                        },
                    emptyBlockYMask = sectionMask(minecraftDimensionLayout.sectionCount),
                    skyUpdates =
                        if (fullBrightSky) {
                            List(minecraftDimensionLayout.sectionCount) { fullSkyLayer }
                        } else {
                            emptyList()
                        },
                    blockUpdates = emptyList(),
                ),
            )
        }

        /** Resolves all palette IDs from the caller's active registry context. */
        fun flat(
            protocolRegistryContext: ProtocolRegistryContext,
            minecraftDimensionLayout: MinecraftDimensionLayout,
            chunkX: Int,
            chunkZ: Int,
            groundY: Int,
            surfaceBlockId: Identifier,
            biomeId: Identifier,
            airBlockId: Identifier = Identifier("air"),
            fullBrightSky: Boolean = minecraftDimensionLayout.hasSkyLight,
        ): MinecraftChunkSnapshot = flat(
            minecraftDimensionLayout = minecraftDimensionLayout,
            chunkX = chunkX,
            chunkZ = chunkZ,
            groundY = groundY,
            surfaceBlockStateRawId = protocolRegistryContext
                .requireDefaultBlockState(surfaceBlockId)
                .id,
            biomeRawId = protocolRegistryContext.requireRegistryEntry(
                ProtocolRegistryContext.BIOME_REGISTRY,
                biomeId,
            ).rawId,
            airBlockStateRawId = protocolRegistryContext
                .requireDefaultBlockState(airBlockId)
                .id,
            fullBrightSky = fullBrightSky,
        )

        private fun surfacePalette(
            airBlockStateRawId: Int,
            surfaceBlockStateRawId: Int,
            localGroundY: Int,
        ): PalettedContainer.Indirect =
            PalettedContainer.Indirect(
                bitsPerEntry = BLOCK_INDIRECT_BITS,
                palette = listOf(
                    airBlockStateRawId,
                    surfaceBlockStateRawId,
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

        private const val SURFACE_BLOCK_COUNT: Int = SECTION_SIDE * SECTION_SIDE
        private const val HEIGHTMAP_ENTRY_COUNT: Int = SECTION_SIDE * SECTION_SIDE
        private const val BLOCK_INDIRECT_BITS: Int = 4
        private const val LIGHT_BOUNDARY_SECTION_COUNT: Int = 2
        private const val FULL_LIGHT_BYTE: Byte = -1
    }
}
