package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.*

internal const val TEST_BLOCK_STATE_REGISTRY_SIZE: Int = 32_366
internal const val TEST_BIOME_REGISTRY_SIZE: Int = 66

private val baseTestProtocolRegistryContext: ProtocolRegistryContext by lazy {
    val blockId = Identifier("test:block")
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
                block = blockId,
                properties = emptyMap(),
                isDefault = rawId == 0,
            )
        },
    )
}

internal fun testProtocolRegistryContext(
    chunkSectionCount: Int? = null,
): ProtocolRegistryContext = chunkSectionCount?.let(
    baseTestProtocolRegistryContext::withChunkSectionCount,
) ?: baseTestProtocolRegistryContext

internal fun testMinecraftProtocolFormat(
    chunkSectionCount: Int? = null,
): MinecraftProtocolFormat = MinecraftProtocolFormat(
    MinecraftProtocolFormatConfiguration(
        protocolRegistryContext = testProtocolRegistryContext(chunkSectionCount),
    ),
)
