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
            ProgramCommandBlockPacket(
                location = BlockPosition(0, 0, 0),
                command = "x",
                mode = CommandBlockMode.REDSTONE,
                flags = CommandBlockFlags(
                    trackOutput = true,
                    conditional = true,
                    automatic = true,
                ),
            ),
            ProgramCommandBlockPacket.serializer(),
            "000000000000000001780207",
        )
        assertPacketBytes(
            ProgramCommandBlockMinecartPacket(
                entityId = 300,
                command = "x",
                trackOutput = true,
            ),
            ProgramCommandBlockMinecartPacket.serializer(),
            "ac02017801",
        )

        val decoded = MinecraftProtocolFormat.decodeFromByteArray<ProgramCommandBlockPacket>(
            "00000000000000000000ff".hexToByteArray(),
        )
        assertContentEquals(
            "0000000000000000000007".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(decoded),
        )
    }

    @Test
    fun `game-rule changes are a prefixed list of identifier and string pairs`() {
        assertPacketBytes(
            SetGameRulesPacket(
                listOf(GameRuleChange(Identifier("minecraft:x"), "1")),
            ),
            SetGameRulesPacket.serializer(),
            "010b6d696e6563726166743a780131",
        )
    }

    @Test
    fun `jigsaw joint is a string enum with aligned fallback`() {
        assertPacketBytes(
            ProgramJigsawBlockPacket(
                location = BlockPosition(0, 0, 0),
                name = Identifier("minecraft:a"),
                target = Identifier("minecraft:b"),
                pool = Identifier("minecraft:c"),
                finalState = "x",
                joint = JigsawJoint.ROLLABLE,
                selectionPriority = 1,
                placementPriority = 300,
            ),
            ProgramJigsawBlockPacket.serializer(),
            "00000000000000000b6d696e6563726166743a610b6d696e6563726166743a620b6d696e6563726166743a63017808726f6c6c61626c6501ac02",
        )
        assertEquals(
            JigsawJoint.ALIGNED,
            MinecraftProtocolFormat.decodeFromByteArray<JigsawJoint>(
                "07756e6b6e6f776e".hexToByteArray(),
            ),
        )
    }

    @Test
    fun `structure block preserves field order and packs four flags`() {
        val packet = ProgramStructureBlockPacket(
            location = BlockPosition(0, 0, 0),
            action = StructureUpdateAction.SCAN_AREA,
            mode = StructureMode.DATA,
            name = "",
            offset = StructureOffset(-48, 0, 48),
            size = StructureSize(0, 1, 48),
            mirror = StructureMirror.FRONT_BACK,
            rotation = StructureRotation.COUNTERCLOCKWISE_90,
            metadata = "x",
            integrity = StructureIntegrity(0.5f),
            seed = 300,
            flags = StructureBlockFlags(
                ignoreEntities = true,
                showAir = true,
                showBoundingBox = true,
                strictPlacement = true,
            ),
        )
        assertPacketBytes(
            packet,
            ProgramStructureBlockPacket.serializer(),
            "0000000000000000030300d00030000130020301783f000000ac020f",
        )
    }

    @Test
    fun `structure decode clamps byte vectors integrity and unknown flag bits`() {
        val decoded = MinecraftProtocolFormat.decodeFromByteArray<ProgramStructureBlockPacket>(
            "0000000000000000000000807f31ff317f0000004000000000ff".hexToByteArray(),
        )
        assertEquals(StructureOffset(-48, 48, 48), decoded.offset)
        assertEquals(StructureSize(0, 48, 48), decoded.size)
        assertEquals(StructureIntegrity(1.0f), decoded.integrity)
        assertContentEquals(
            "0000000000000000000000d030300030300000003f800000000f".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(decoded),
        )
    }

    @Test
    fun `test block uses zero fallback and sign lines have no count prefix`() {
        assertPacketBytes(
            SetTestBlockPacket(
                BlockPosition(0, 0, 0),
                TestBlockMode.ACCEPT,
                "x",
            ),
            SetTestBlockPacket.serializer(),
            "0000000000000000030178",
        )
        val fallback = MinecraftProtocolFormat.decodeFromByteArray<SetTestBlockPacket>(
            "00000000000000007f00".hexToByteArray(),
        )
        assertEquals(TestBlockMode.START, fallback.mode)

        assertPacketBytes(
            UpdateSignPacket(
                location = BlockPosition(0, 0, 0),
                frontText = true,
                lines = listOf("a", "", "bc", "d"),
            ),
            UpdateSignPacket.serializer(),
            "0000000000000000010161000262630164",
        )
        assertFails {
            MinecraftProtocolFormat.encodeToByteArray(
                UpdateSignPacket(BlockPosition(0, 0, 0), true, listOf("only one")),
            )
        }
        assertFails {
            MinecraftProtocolFormat.encodeToByteArray(
                UpdateSignPacket(
                    BlockPosition(0, 0, 0),
                    true,
                    listOf("x".repeat(385), "", "", ""),
                ),
            )
        }
    }

    private fun <T> assertPacketBytes(
        packet: T,
        serializer: KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftProtocolFormat.encodeToByteArray(serializer, packet),
        )
        assertEquals(
            packet,
            MinecraftProtocolFormat.decodeFromByteArray(serializer, expected),
        )
    }
}
