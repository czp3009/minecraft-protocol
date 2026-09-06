package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PlayServerboundCommandAndStructurePacketTest {
    @Test
    fun `command block flags are one byte while minecart uses a boolean`() {
        assertPacketBytes(
            ServerboundSetCommandBlockPacket(
                pos = BlockPosition(0, 0, 0),
                command = "x",
                mode = CommandBlockMode.REDSTONE,
                trackOutput = true,
                conditional = true,
                automatic = true,
            ),
            ServerboundSetCommandBlockPacket.serializer(),
            "000000000000000001780207",
        )
        assertPacketBytes(
            ServerboundSetCommandMinecartPacket(
                entity = 300,
                command = "x",
                trackOutput = true,
            ),
            ServerboundSetCommandMinecartPacket.serializer(),
            "ac02017801",
        )

        val decoded = MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundSetCommandBlockPacket>(
            "00000000000000000000ff".hexToByteArray(),
        )
        assertContentEquals(
            "0000000000000000000007".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(decoded),
        )
    }

    @Test
    fun `game-rule changes are a prefixed list of identifier and string pairs`() {
        assertPacketBytes(
            ServerboundSetGameRulePacket(
                listOf(ServerboundSetGameRulePacket.Entry(Identifier("minecraft:x"), "1")),
            ),
            ServerboundSetGameRulePacket.serializer(),
            "010b6d696e6563726166743a780131",
        )
    }

    @Test
    fun `jigsaw joint is a string enum with aligned fallback`() {
        assertPacketBytes(
            ServerboundSetJigsawBlockPacket(
                pos = BlockPosition(0, 0, 0),
                name = Identifier("minecraft:a"),
                target = Identifier("minecraft:b"),
                pool = Identifier("minecraft:c"),
                finalState = "x",
                joint = JigsawJoint.ROLLABLE,
                selectionPriority = 1,
                placementPriority = 300,
            ),
            ServerboundSetJigsawBlockPacket.serializer(),
            "00000000000000000b6d696e6563726166743a610b6d696e6563726166743a620b6d696e6563726166743a63017808726f6c6c61626c6501ac02",
        )
        assertEquals(
            JigsawJoint.ALIGNED,
            MinecraftPacketPayloadFormat.decodeFromByteArray<JigsawJoint>(
                "07756e6b6e6f776e".hexToByteArray(),
            ),
        )
    }

    @Test
    fun `structure block preserves field order and packs four flags`() {
        val serverboundSetStructureBlockPacket = ServerboundSetStructureBlockPacket(
            pos = BlockPosition(0, 0, 0),
            updateType = StructureUpdateAction.SCAN_AREA,
            mode = StructureMode.DATA,
            name = "",
            offset = StructureOffset(-48, 0, 48),
            size = StructureSize(0, 1, 48),
            mirror = StructureMirror.FRONT_BACK,
            rotation = StructureRotation.COUNTERCLOCKWISE_90,
            data = "x",
            integrity = StructureIntegrity(0.5f),
            seed = 300,
            ignoreEntities = true,
            showAir = true,
            showBoundingBox = true,
            strict = true,
        )
        assertPacketBytes(
            serverboundSetStructureBlockPacket,
            ServerboundSetStructureBlockPacket.serializer(),
            "0000000000000000030300d00030000130020301783f000000ac020f",
        )
    }

    @Test
    fun `structure decode clamps byte vectors integrity and unknown flag bits`() {
        val decoded = MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundSetStructureBlockPacket>(
            "0000000000000000000000807f31ff317f0000004000000000ff".hexToByteArray(),
        )
        assertEquals(StructureOffset(-48, 48, 48), decoded.offset)
        assertEquals(StructureSize(0, 48, 48), decoded.size)
        assertEquals(StructureIntegrity(1.0f), decoded.integrity)
        assertContentEquals(
            "0000000000000000000000d030300030300000003f800000000f".hexToByteArray(),
            MinecraftPacketPayloadFormat.encodeToByteArray(decoded),
        )
    }

    @Test
    fun `test block uses zero fallback and sign lines have no count prefix`() {
        assertPacketBytes(
            ServerboundSetTestBlockPacket(
                BlockPosition(0, 0, 0),
                TestBlockMode.ACCEPT,
                "x",
            ),
            ServerboundSetTestBlockPacket.serializer(),
            "0000000000000000030178",
        )
        val fallback = MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundSetTestBlockPacket>(
            "00000000000000007f00".hexToByteArray(),
        )
        assertEquals(TestBlockMode.START, fallback.mode)

        assertPacketBytes(
            ServerboundSignUpdatePacket(
                pos = BlockPosition(0, 0, 0),
                isFrontText = true,
                lines = listOf("a", "", "bc", "d"),
            ),
            ServerboundSignUpdatePacket.serializer(),
            "0000000000000000010161000262630164",
        )
        assertFails {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ServerboundSignUpdatePacket(
                    pos = BlockPosition(0, 0, 0),
                    isFrontText = true,
                    lines = listOf("only one")
                ),
            )
        }
        assertFails {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ServerboundSignUpdatePacket(
                    pos = BlockPosition(0, 0, 0),
                    isFrontText = true,
                    lines = listOf("x".repeat(385), "", "", ""),
                ),
            )
        }
    }

    private fun <T> assertPacketBytes(
        packet: T,
        kSerializer: KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftPacketPayloadFormat.encodeToByteArray(kSerializer, packet),
        )
        assertEquals(
            packet,
            MinecraftPacketPayloadFormat.decodeFromByteArray(kSerializer, expected),
        )
    }
}
