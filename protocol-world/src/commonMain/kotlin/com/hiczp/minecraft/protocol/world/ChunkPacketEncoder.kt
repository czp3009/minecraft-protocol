package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.protocol.model.packet.ClientboundLevelChunkPacketData
import com.hiczp.minecraft.protocol.model.packet.ClientboundLevelChunkWithLightPacket
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.LevelChunkSectionData
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import com.hiczp.minecraft.protocol.serialization.MinecraftChunkSectionPayloadFormat
import com.hiczp.minecraft.protocol.serialization.MinecraftChunkSectionPayloadFormatConfiguration
import com.hiczp.minecraft.world.format.BiomeId
import com.hiczp.minecraft.world.format.BlockState
import com.hiczp.minecraft.world.format.Chunk
import com.hiczp.minecraft.world.format.SECTION_SIDE
import com.hiczp.minecraft.protocol.model.type.HeightmapType as PacketHeightmapType
import com.hiczp.minecraft.protocol.model.type.PalettedContainer as PacketPalettedContainer

/** Converts current terrain, light and selected Block Entity data to packets using a fixed construction context. */
class ChunkPacketEncoder(val chunkPacketEncoderContext: ChunkPacketEncoderContext) {
    private val chunkLayout = chunkPacketEncoderContext.chunkLayout
    private val packetCodecContext = chunkPacketEncoderContext.packetCodecContext
    private val mappings = chunkPacketEncoderContext.chunkPacketWriteMappings
    private val required = chunkPacketEncoderContext.chunkPacketRequiredDataProvider
    private val sectionFormat = MinecraftChunkSectionPayloadFormat(
        MinecraftChunkSectionPayloadFormatConfiguration(packetCodecContext, chunkLayout.sectionCount),
    )

    /** Builds one full Chunk packet without mutating the Chunk or sending bytes; invokes providers only as needed. */
    fun encode(chunk: Chunk): ClientboundLevelChunkWithLightPacket {
        val sections = chunkLayout.sectionYRange.map { sectionY -> encodeSection(chunk, sectionY) }
        return ClientboundLevelChunkWithLightPacket(
            x = chunk.chunkPosition.x,
            z = chunk.chunkPosition.z,
            chunkData = ClientboundLevelChunkPacketData(
                heightmaps = encodeHeightmaps(chunk),
                buffer = sectionFormat.encode(sections),
                blockEntitiesData = chunk.blockEntities.map { (blockPosition, blockEntity) ->
                    val local = chunk.chunkPosition.local(blockPosition)
                    require(chunkLayout.containsBlockY(blockPosition.y)) { "Block entity is outside the encoded height range" }
                    ClientboundLevelChunkPacketData.BlockEntityInfo.fromLocalCoordinates(
                        local.x, blockPosition.y, local.z,
                        packetCodecContext.requireRegistryEntry(
                            BLOCK_ENTITY_TYPE_REGISTRY, Identifier(blockEntity.blockEntityTypeId.value),
                        ).rawId,
                        mappings.blockEntityUpdateTag(blockEntity),
                    )
                },
            ),
            lightData = encodePacketLight(chunk, chunkLayout, chunkPacketEncoderContext.hasSkyLight, required),
        )
    }

    private fun encodeSection(chunk: Chunk, sectionY: Int): LevelChunkSectionData {
        val terrain = chunk.sections[sectionY]?.terrain
        terrain?.requireShape()
        val statistics = terrain?.statistics
        return LevelChunkSectionData(
            nonEmptyBlockCount = statistics?.nonEmptyBlockCount
                ?: required.sectionStatistic(chunk, sectionY, SectionStatistic.NON_EMPTY_BLOCKS),
            fluidCount = statistics?.fluidCount ?: required.sectionStatistic(chunk, sectionY, SectionStatistic.FLUIDS),
            states = if (terrain == null) PacketPalettedContainer.Single(blockStateId(chunkPacketEncoderContext.defaultBlockState)) else
                encodePacketPalette(
                    terrain.blockStates,
                    packetCodecContext.blockStateRegistrySize,
                    4,
                    8,
                    ::blockStateId
                ),
            biomes = if (terrain == null) PacketPalettedContainer.Single(biomeId(chunkPacketEncoderContext.defaultBiome)) else
                encodePacketPalette(
                    terrain.biomes,
                    packetCodecContext.requireRegistry(PacketCodecContext.BIOME_REGISTRY).size,
                    1,
                    3,
                    ::biomeId,
                ),
        )
    }

    private fun blockStateId(blockState: BlockState): Int =
        packetCodecContext.blockState(Identifier(blockState.blockId.value), blockState.properties.toMap())?.id
            ?: error("Block state $blockState is absent from the packet registry")

    private fun biomeId(biomeId: BiomeId): Int =
        packetCodecContext.requireRegistryEntry(PacketCodecContext.BIOME_REGISTRY, Identifier(biomeId.value)).rawId

    private fun encodeHeightmaps(chunk: Chunk): Map<PacketHeightmapType, LongArray> = buildMap {
        chunk.heightmaps.maps.forEach { (heightmapType, heightmap) ->
            val packetHeightmapType = mappings.heightmapType(heightmapType) ?: return@forEach
            require(!containsKey(packetHeightmapType)) { "Multiple heightmaps map to $packetHeightmapType" }
            val packed =
                packPacketValues(packetValueBits(chunkLayout.height + 1), (SECTION_SIDE * SECTION_SIDE)) { index ->
                    val absolute =
                        heightmap.firstAvailable[index] ?: required.heightmapValue(chunk, heightmapType, index)
                    val relative = absolute.toLong() - chunkLayout.minBlockY
                    require(relative in 0..chunkLayout.height.toLong()) { "Heightmap value $absolute is outside the encoded height range" }
                    relative.toInt()
                }
            put(packetHeightmapType, packed.toLongArray())
        }
    }
}

internal val BLOCK_ENTITY_TYPE_REGISTRY: Identifier = Identifier("block_entity_type")
