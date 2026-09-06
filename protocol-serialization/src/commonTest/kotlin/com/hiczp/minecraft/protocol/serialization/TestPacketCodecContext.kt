package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.*

internal const val TEST_BLOCK_STATE_REGISTRY_SIZE: Int = 32_366
internal const val TEST_BIOME_REGISTRY_SIZE: Int = 66

private val baseTestPacketCodecContext: PacketCodecContext by lazy {
    val blockId = Identifier("test:block")
    PacketCodecContext(
        registries = listOf(
            RegistryIdMap(
                PacketCodecContext.BIOME_REGISTRY,
                List(TEST_BIOME_REGISTRY_SIZE) { rawId ->
                    RegistryIdMapping(
                        Identifier("test:biome_$rawId"),
                        rawId,
                    )
                },
            ),
        ),
        blockStates = List(TEST_BLOCK_STATE_REGISTRY_SIZE) { rawId ->
            BlockStateIdMapping(
                id = rawId,
                block = blockId,
                properties = emptyMap(),
                isDefault = rawId == 0,
            )
        },
    )
}

internal fun testPacketCodecContext(): PacketCodecContext = baseTestPacketCodecContext

internal fun testMinecraftPacketPayloadFormat(): MinecraftPacketPayloadFormat = MinecraftPacketPayloadFormat(
    MinecraftPacketPayloadFormatConfiguration(
        packetCodecContext = testPacketCodecContext(),
    ),
)
