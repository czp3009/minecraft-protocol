package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.*

internal const val TEST_BLOCK_STATE_REGISTRY_SIZE: Int = 32_366
internal const val TEST_BIOME_REGISTRY_SIZE: Int = 66

private val baseTestRegistryContext: ProtocolRegistryContext by lazy {
    val block = Identifier("test:block")
    ProtocolRegistryContext(
        registries = listOf(
            ProtocolRegistry(
                ProtocolRegistryContext.BIOME_REGISTRY,
                List(TEST_BIOME_REGISTRY_SIZE) { rawId ->
                    ProtocolRegistryEntry(
                        Identifier("test:biome_$rawId"),
                        rawId,
                    )
                },
            ),
        ),
        blockStates = List(TEST_BLOCK_STATE_REGISTRY_SIZE) { rawId ->
            ProtocolBlockState(
                id = rawId,
                block = block,
                properties = emptyMap(),
                isDefault = rawId == 0,
            )
        },
    )
}

internal fun testRegistryContext(
    chunkSectionCount: Int? = null,
): ProtocolRegistryContext = chunkSectionCount?.let(
    baseTestRegistryContext::withChunkSectionCount,
) ?: baseTestRegistryContext

internal fun testMinecraftProtocolFormat(
    chunkSectionCount: Int? = null,
): MinecraftProtocolFormat = MinecraftProtocolFormat(
    MinecraftProtocolFormatConfiguration(
        registries = testRegistryContext(chunkSectionCount),
    ),
)
