package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.ClientboundUpdateAdvancementsPacket
import com.hiczp.minecraft.protocol.model.packet.Packet
import com.hiczp.minecraft.protocol.model.packet.ServerboundClientInformationPacket
import com.hiczp.minecraft.protocol.model.packet.ServerboundUseItemOnPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Raw non-canonical inputs and the values required by the official nested codecs. */
internal data class PacketNormalizationSample(
    val name: String,
    val packet: Packet,
    val payloadHex: String,
    val expectedHex: String,
)

internal fun packetNormalizationSamples(): List<PacketNormalizationSample> = listOf(
    PacketNormalizationSample(
        "client-information-enum-policies",
        ServerboundClientInformationPacket(
            ClientInformation(
                "",
                8,
                ChatMode.HIDDEN,
                true,
                127,
                MainHand.LEFT,
                false,
                true,
                ParticleStatus.DECREASED
            )
        ),
        "0008ffffffff0f017fffffffff0f000104",
        "000802017f00000101",
    ),
    PacketNormalizationSample(
        "block-hit-direction-wrap",
        ServerboundUseItemOnPacket(
            InteractionHand.MAIN_HAND,
            BlockHitResult(BlockPosition(0, 0, 0), BlockFace.EAST, 0.0f, 0.5f, 1.0f, true, false),
            300,
        ),
        "7f0000000000000000ffffffff0f000000003f0000003f8000000100ac02",
        "00000000000000000005000000003f0000003f8000000100ac02",
    ),
    PacketNormalizationSample(
        "advancement-type-zero-fallback",
        ClientboundUpdateAdvancementsPacket(
            false,
            listOf(
                ClientboundUpdateAdvancementsPacket.PositionedAdvancement(
                    AdvancementHolder(
                        Identifier("test:a"),
                        Advancement(
                            null,
                            AdvancementDisplayInfo(
                                TextComponent.literal("title"),
                                TextComponent.literal("desc"),
                                ItemStackTemplate(1, 1),
                                AdvancementType.TASK
                            ),
                            emptyList(),
                            false,
                        ),
                    ),
                    1.0f,
                    2.0f,
                ),
            ),
            emptySet(),
            emptyMap(),
            true,
        ),
        "000106746573743a6100010800057469746c650800046465736301010000ffffffff0f0000000000003f80000040000000000001",
        "000106746573743a6100010800057469746c650800046465736301010000000000000000003f80000040000000000001",
    ),
)

class PacketNormalizationTest {
    @Test
    fun `nested enums match official normalization in every registered state`() {
        for (packetNormalizationSample in packetNormalizationSamples()) {
            val entries =
                PacketRegistry.vanilla.entries.filter { it.packetClass == packetNormalizationSample.packet::class }
            for (packetCodec in entries) {
                val packet = PacketRegistry.vanilla.decodePayload(
                    packetCodec.packetKey.connectionState,
                    packetCodec.packetKey.packetDirection,
                    packetCodec.packetKey.id,
                    packetNormalizationSample.payloadHex.hexToByteArray(),
                )
                assertEquals(packetNormalizationSample.packet, packet, packetNormalizationSample.name)
                val encoded = PacketRegistry.vanilla.encodePayload(
                    packet, packetCodec.packetKey.connectionState, packetCodec.packetKey.packetDirection,
                )
                assertContentEquals(
                    packetNormalizationSample.expectedHex.hexToByteArray(),
                    encoded.payload,
                    packetNormalizationSample.name
                )
            }
        }
    }
}
