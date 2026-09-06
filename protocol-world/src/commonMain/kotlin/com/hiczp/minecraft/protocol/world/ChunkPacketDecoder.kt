package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.protocol.model.packet.ClientboundLevelChunkWithLightPacket
import com.hiczp.minecraft.protocol.model.type.PackedLongArray
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import com.hiczp.minecraft.protocol.serialization.MinecraftChunkSectionPayloadFormat
import com.hiczp.minecraft.protocol.serialization.MinecraftChunkSectionPayloadFormatConfiguration
import com.hiczp.minecraft.world.format.*

/** Decodes full Chunk packets; reuse only while its dimension, registries and application mappings remain valid. */
class ChunkPacketDecoder(val chunkPacketDecoderContext: ChunkPacketDecoderContext) {
    private val chunkContext = chunkPacketDecoderContext.chunkContext
    private val chunkLayout = chunkContext.dimensionTypeLayout.chunkLayout
    private val packetCodecContext = chunkPacketDecoderContext.packetCodecContext
    private val mappings = chunkPacketDecoderContext.chunkPacketReadMappings
    private val sectionFormat = MinecraftChunkSectionPayloadFormat(
        MinecraftChunkSectionPayloadFormatConfiguration(packetCodecContext, chunkLayout.sectionCount),
    )

    /**
     * Constructs a mutable Chunk from transmitted data and one call to the configured missing-data provider.
     * Retains the provider's mutable containers and domain-context reference. Block Entity update tags contain only
     * the server-selected projection, so an inventory absent from the packet remains absent unless supplied locally.
     */
    fun decode(clientboundLevelChunkWithLightPacket: ClientboundLevelChunkWithLightPacket): Chunk {
        val packet = clientboundLevelChunkWithLightPacket
        val chunkPosition = ChunkPosition(packet.x, packet.z)
        val packetSections = sectionFormat.decode(packet.chunkData.buffer)
        val missing = chunkPacketDecoderContext.chunkPacketMissingDataProvider.provide(chunkPosition)
        val biomeRegistry = packetCodecContext.requireRegistry(PacketCodecContext.BIOME_REGISTRY)
        val sections = linkedMapOf<Int, ChunkSection>()
        packetSections.forEachIndexed { index, section ->
            val sectionY = MinecraftCoordinates.offsetSectionCoordinate(chunkLayout.minSectionY, index)
            val missingSection = missing.sections[sectionY]
            sections[sectionY] = ChunkSection(
                SectionTerrain(
                    decodePacketPalette(
                        section.states,
                        SECTION_BLOCK_COUNT,
                        packetCodecContext.blockStateRegistrySize
                    ) { id ->
                        val state = packetCodecContext.blockState(id)
                            ?: error("Block-state registry ID $id has no installed entry")
                        BlockState(BlockId(state.block.value), StateProperties(state.properties))
                    },
                    decodePacketPalette(section.biomes, SECTION_BIOME_COUNT, biomeRegistry.size) { id ->
                        BiomeId((biomeRegistry[id] ?: error("Biome registry ID $id has no installed entry")).id.value)
                    },
                    SectionStatistics(
                        section.nonEmptyBlockCount,
                        section.fluidCount,
                        missingSection?.tickingBlockCount,
                        missingSection?.tickingFluidCount
                    ),
                ),
                SectionLighting(), missingSection?.properties ?: DataProperties(),
            )
        }
        missing.sections.forEach { (sectionY, missingSection) ->
            if (sectionY !in sections) sections[sectionY] =
                ChunkSection(null, SectionLighting(), missingSection.properties)
        }
        val light = packet.lightData
        val block = decodePacketLight(light.blockYMask, light.emptyBlockYMask, light.blockUpdates, chunkLayout)
        val sky = decodePacketLight(light.skyYMask, light.emptySkyYMask, light.skyUpdates, chunkLayout)
        (block.keys + sky.keys).forEach { sectionY ->
            val section = sections.getOrPut(sectionY) { ChunkSection(null, SectionLighting(), DataProperties()) }
            section.lighting = SectionLighting(block[sectionY], sky[sectionY])
        }
        // The provider supplies missing fields and retains its references; received heightmaps replace the same keys.
        packet.chunkData.heightmaps.forEach { (type, packed) ->
            val values = unpackPacketValues(
                PackedLongArray(packed),
                packetValueBits(chunkLayout.height + 1),
                (SECTION_SIDE * SECTION_SIDE)
            )
            missing.heightmaps.maps[mappings.heightmapType(type)] =
                Heightmap(ColumnData(List((SECTION_SIDE * SECTION_SIDE)) { index ->
                    require(values[index] <= chunkLayout.height) { "Packet heightmap exceeds the encoded height range" }
                    MinecraftCoordinates.offsetBlockCoordinate(chunkLayout.minBlockY, values[index])
                }))
        }
        val blockEntities = linkedMapOf<BlockPosition, BlockEntity>()
        packet.chunkData.blockEntitiesData.forEach { info ->
            val type = packetCodecContext.requireRegistry(BLOCK_ENTITY_TYPE_REGISTRY)[info.type]
                ?: error("Block-entity type registry ID ${info.type} has no installed entry")
            val blockPosition = chunkPosition.block(ChunkBlockPosition(info.localX, info.y.toInt(), info.localZ))
            require(chunkLayout.containsBlockY(blockPosition.y)) { "Block entity is outside the decoded height range" }
            val blockEntityTypeId = BlockEntityTypeId(type.id.value)
            val contents = mappings.blockEntity(blockEntityTypeId, info.tag)
            blockEntities[blockPosition] = BlockEntity(blockEntityTypeId, contents.components, contents.properties)
        }
        return Chunk(
            chunkPosition, chunkContext, sections, blockEntities, missing.heightmaps, missing.lighting,
            missing.blockTicks, missing.fluidTicks, missing.structures, missing.postProcessing, missing.status,
            missing.inhabitedTime, missing.upgradeData, missing.blendingData, missing.properties,
        )
    }
}
