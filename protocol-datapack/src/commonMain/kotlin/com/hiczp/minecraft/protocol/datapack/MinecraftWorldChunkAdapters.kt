package com.hiczp.minecraft.protocol.datapack

import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.BiomeRegistry
import com.hiczp.minecraft.world.format.BlockStateDescriptor
import com.hiczp.minecraft.world.format.BlockStateRegistry
import com.hiczp.minecraft.world.format.ChunkDataRegistries

/** Adapts one active protocol registry context to the registries used by strong world-Chunk conversion. */
fun ProtocolRegistryContext.toChunkDataRegistries(
    defaultBlock: Identifier = MinecraftBlockIds.AIR,
    defaultBiome: Identifier = MinecraftBiomeIds.PLAINS,
): ChunkDataRegistries<ProtocolBlockState, ProtocolRegistryEntry> =
    ChunkDataRegistries(
        blockStates = object : BlockStateRegistry<ProtocolBlockState> {
            override val defaultValue = requireDefaultBlockState(defaultBlock)

            override fun resolve(blockStateDescriptor: BlockStateDescriptor): ProtocolBlockState? =
                blockStateDescriptor.identifierOrNull()?.let { block ->
                    blockState(block, blockStateDescriptor.properties)
                }

            override fun describe(value: ProtocolBlockState): BlockStateDescriptor =
                BlockStateDescriptor(value.block.value, value.properties)
        },
        biomes = object : BiomeRegistry<ProtocolRegistryEntry> {
            private val protocolRegistry = requireRegistry(ProtocolRegistryContext.BIOME_REGISTRY)

            override val defaultValue = requireRegistryEntry(
                ProtocolRegistryContext.BIOME_REGISTRY,
                defaultBiome,
            )

            override fun resolve(name: String): ProtocolRegistryEntry? =
                identifierOrNull(name)?.let(protocolRegistry::entry)

            override fun name(value: ProtocolRegistryEntry): String = value.id.value
        },
    )

private fun BlockStateDescriptor.identifierOrNull(): Identifier? = identifierOrNull(name)

private fun identifierOrNull(value: String): Identifier? = try {
    Identifier(value)
} catch (_: IllegalArgumentException) {
    null
}
