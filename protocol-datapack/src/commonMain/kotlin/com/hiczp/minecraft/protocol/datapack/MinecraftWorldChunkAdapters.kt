package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolBlockState
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryEntry
import com.hiczp.minecraft.world.format.*

/**
 * Converts one Configuration-resolved dimension layout into the corresponding semantic world-Chunk layout.
 *
 * The receiver supplies the active dimension bounds; this conversion neither assumes a release-global default nor
 * reads a world file.
 */
fun MinecraftDimensionLayout.toChunkLayout(): ChunkLayout = ChunkLayout(
    minSectionY = MinecraftCoordinates.sectionCoordinate(minY),
    sectionCount = sectionCount,
)

/**
 * Adapts one active protocol registry context to the registries used by strong world-Chunk conversion.
 *
 * Loader-resolved block states, aliases, raw IDs, and synchronized biomes are retained through the supplied immutable
 * context. Recreate this adapter after the active connection context changes.
 */
fun ProtocolRegistryContext.toChunkDataRegistries(
    defaultBlock: Identifier = Identifier("air"),
    defaultBiome: Identifier = Identifier("plains"),
): ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry> =
    ChunkDataRegistries(
        blockStates = object : BlockStateRegistry<ProtocolBlockState> {
            override val defaultValue = requireDefaultBlockState(defaultBlock)

            override fun resolve(blockStateDescriptor: BlockStateDescriptor): ProtocolBlockState? =
                blockStateDescriptor.identifierOrNull()?.let { block ->
                    blockState(block, blockStateDescriptor.properties)
                }

            override fun describe(value: ProtocolBlockState): BlockStateDescriptor? =
                value.takeIf { protocolBlockState ->
                    blockStates.getOrNull(protocolBlockState.id) == protocolBlockState
                }
                    ?.let { protocolBlockState ->
                        BlockStateDescriptor(protocolBlockState.block.value, protocolBlockState.properties)
                    }
        },
        biomes = object : BiomeRegistry<ProtocolRegistryEntry> {
            private val protocolRegistry = requireRegistry(ProtocolRegistryContext.BIOME_REGISTRY)

            override val defaultValue = requireRegistryEntry(
                ProtocolRegistryContext.BIOME_REGISTRY,
                defaultBiome,
            )

            override fun resolve(name: String): ProtocolRegistryEntry? =
                identifierOrNull(name)?.let(protocolRegistry::entry)

            override fun name(value: ProtocolRegistryEntry): String? =
                value.takeIf { protocolRegistryEntry ->
                    protocolRegistry[protocolRegistryEntry.rawId] == protocolRegistryEntry
                }
                    ?.id
                    ?.value
        },
    )

private fun BlockStateDescriptor.identifierOrNull(): Identifier? = identifierOrNull(name)

private fun identifierOrNull(value: String): Identifier? = try {
    Identifier(value)
} catch (_: IllegalArgumentException) {
    null
}
