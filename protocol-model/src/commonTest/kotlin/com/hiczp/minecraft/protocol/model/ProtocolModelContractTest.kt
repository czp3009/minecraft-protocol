package com.hiczp.minecraft.protocol.model

import com.hiczp.minecraft.protocol.model.packet.HandshakeNextState
import com.hiczp.minecraft.protocol.model.packet.HandshakePacket
import com.hiczp.minecraft.protocol.model.packet.LegacyServerListPingPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.test.*

class ProtocolModelContractTest {
    @Test
    fun `identifier normalizes the default namespace and validates both parts`() {
        assertEquals(Identifier("minecraft:stone"), Identifier("stone"))
        assertEquals("minecraft", Identifier("stone").namespace)
        assertEquals("stone", Identifier("stone").path)
        assertFailsWith<IllegalArgumentException> { Identifier("Minecraft:stone") }
        assertFailsWith<IllegalArgumentException> { Identifier("minecraft:") }
        assertFailsWith<IllegalArgumentException> { Identifier("minecraft:bad value") }
    }

    @Test
    fun `byte string has content equality and defensive copies`() {
        val source = byteArrayOf(1, 2, 3)
        val value = ByteString(source)
        source[0] = 9
        assertEquals(ByteString(byteArrayOf(1, 2, 3)), value)
        assertEquals(ByteString(byteArrayOf(1, 2, 3)).hashCode(), value.hashCode())

        val exported = value.toByteArray()
        exported[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), value.toByteArray())
        assertNotEquals(ByteString(exported), value)
    }

    @Test
    fun `block positions preserve every signed wire boundary`() {
        val positions = listOf(
            BlockPosition(0, 0, 0),
            BlockPosition(BlockPosition.MIN_XZ, BlockPosition.MIN_Y, BlockPosition.MIN_XZ),
            BlockPosition(BlockPosition.MAX_XZ, BlockPosition.MAX_Y, BlockPosition.MAX_XZ),
            BlockPosition(-1, -1, -1),
        )
        for (position in positions) {
            assertEquals(position, BlockPosition.fromPacked(position.packed()))
        }
        assertFailsWith<IllegalArgumentException> {
            BlockPosition(BlockPosition.MAX_XZ + 1, 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BlockPosition(0, BlockPosition.MIN_Y - 1, 0)
        }
    }

    @Test
    fun `NBT lists enforce their homogeneous non-END algebra`() {
        assertEquals(
            NbtList(listOf(NbtInt(1), NbtInt(2))),
            NbtList(listOf(NbtInt(1), NbtInt(2))),
        )
        assertFailsWith<IllegalArgumentException> {
            NbtList(listOf(NbtInt(1), NbtString("two")))
        }
        assertFailsWith<IllegalArgumentException> {
            NbtList(listOf(NbtEnd))
        }
    }

    @Test
    fun `packet and item constructors reject impossible wire states`() {
        assertFailsWith<IllegalArgumentException> {
            HandshakePacket(
                protocolVersion = 0,
                serverAddress = "localhost",
                serverPort = 25_565,
                nextState = HandshakeNextState.UNUSED,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LegacyServerListPingPacket(payload = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ItemStack.Present(count = 0, itemId = 1)
        }
    }
}
