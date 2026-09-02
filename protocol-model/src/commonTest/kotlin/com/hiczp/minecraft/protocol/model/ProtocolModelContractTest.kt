package com.hiczp.minecraft.protocol.model

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.random.Random
import kotlin.test.*

class ProtocolModelContractTest {
    @Test
    fun `unknown packet subtype retains its direction invariant`() {
        val serverboundRoute = PacketRoute.TopLevel(ConnectionState.PLAY, PacketDirection.SERVERBOUND, 0)
        val clientboundRoute = PacketRoute.TopLevel(ConnectionState.PLAY, PacketDirection.CLIENTBOUND, 0)

        assertFailsWith<IllegalArgumentException> {
            UnknownPacket.Clientbound(serverboundRoute, ByteString(byteArrayOf()))
        }
        assertFailsWith<IllegalArgumentException> {
            UnknownPacket.Serverbound(clientboundRoute, ByteString(byteArrayOf()))
        }
    }

    @Test
    fun `identifier normalizes the default namespace and validates both parts`() {
        assertEquals(Identifier("minecraft:stone"), Identifier("stone"))
        assertEquals(Identifier("test:value"), Identifier("test", "value"))
        assertEquals("minecraft", Identifier("stone").namespace)
        assertEquals("stone", Identifier("stone").path)
        assertFailsWith<IllegalArgumentException> { Identifier("Minecraft:stone") }
        assertFailsWith<IllegalArgumentException> { Identifier("minecraft:") }
        assertFailsWith<IllegalArgumentException> { Identifier("minecraft:bad value") }
        assertFailsWith<IllegalArgumentException> { Identifier("Minecraft", "stone") }
        assertFailsWith<IllegalArgumentException> { Identifier("minecraft", "bad value") }
    }

    @Test
    fun `byte string has content equality and defensive copies`() {
        val source = byteArrayOf(1, 2, 3)
        val byteString = ByteString(source)
        source[0] = 9
        assertEquals(ByteString(byteArrayOf(1, 2, 3)), byteString)
        assertEquals(ByteString(byteArrayOf(1, 2, 3)).hashCode(), byteString.hashCode())

        val exported = byteString.toByteArray()
        exported[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), byteString.toByteArray())
        assertNotEquals(ByteString(exported), byteString)
    }

    @Test
    fun `block positions preserve every signed wire boundary`() {
        val positions = listOf(
            BlockPosition(0, 0, 0),
            BlockPosition(BlockPosition.MIN_XZ, BlockPosition.MIN_Y, BlockPosition.MIN_XZ),
            BlockPosition(BlockPosition.MAX_XZ, BlockPosition.MAX_Y, BlockPosition.MAX_XZ),
            BlockPosition(-1, -1, -1),
        )
        for (blockPosition in positions) {
            assertEquals(blockPosition, BlockPosition.fromPacked(blockPosition.packed()))
        }
        assertFailsWith<IllegalArgumentException> {
            BlockPosition(BlockPosition.MAX_XZ + 1, 0, 0).packed()
        }
        assertFailsWith<IllegalArgumentException> {
            BlockPosition(0, BlockPosition.MIN_Y - 1, 0).packed()
        }

        val random = Random(0x504F53)
        repeat(5_000) {
            val blockPosition = BlockPosition(
                x = random.nextInt(
                    BlockPosition.MIN_XZ,
                    BlockPosition.MAX_XZ + 1,
                ),
                y = random.nextInt(
                    BlockPosition.MIN_Y,
                    BlockPosition.MAX_Y + 1,
                ),
                z = random.nextInt(
                    BlockPosition.MIN_XZ,
                    BlockPosition.MAX_XZ + 1,
                ),
            )
            assertEquals(
                blockPosition,
                BlockPosition.fromPacked(blockPosition.packed()),
            )
        }
    }

    @Test
    fun `section positions and block changes preserve random packed values`() {
        val random = Random(0x534543)

        repeat(5_000) {
            val sectionPosition = SectionPosition(
                x = random.nextInt(
                    SectionPosition.MIN_XZ,
                    SectionPosition.MAX_XZ + 1,
                ),
                y = random.nextInt(
                    SectionPosition.MIN_Y,
                    SectionPosition.MAX_Y + 1,
                ),
                z = random.nextInt(
                    SectionPosition.MIN_XZ,
                    SectionPosition.MAX_XZ + 1,
                ),
            )
            assertEquals(
                sectionPosition,
                SectionPosition.fromPacked(sectionPosition.packed()),
            )

            val sectionBlockChange = SectionBlockChange(
                blockStateId = random.nextInt(Int.MAX_VALUE),
                localX = random.nextInt(16),
                localY = random.nextInt(16),
                localZ = random.nextInt(16),
            )
            assertEquals(
                sectionBlockChange,
                SectionBlockChange.fromPacked(sectionBlockChange.packed()),
            )
        }
    }

    @Test
    fun `packet models preserve wire values while item counts retain their representation invariant`() {
        assertEquals(
            HandshakeNextState.UNUSED,
            HandshakePacket(
                protocolVersion = 0,
                serverAddress = "localhost",
                serverPort = 25_565,
                nextState = HandshakeNextState.UNUSED,
            ).nextState,
        )
        assertEquals(0, LegacyServerListPingPacket(payload = 0).payload)
        assertFailsWith<IllegalArgumentException> {
            ItemStack.Present(count = 0, itemId = 1)
        }
    }
}
