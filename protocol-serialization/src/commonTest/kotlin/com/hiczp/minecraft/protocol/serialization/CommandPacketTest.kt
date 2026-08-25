package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.CommandsPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommandPacketTest {
    @Test
    fun `command graph flags and parser properties match Wiki and vanilla`() {
        val packet = CommandsPacket(
            nodes = listOf(
                CommandNode.Root(children = listOf(1)),
                CommandNode.Argument(
                    name = "target",
                    parser = CommandParser.Entity(
                        single = true,
                        playersOnly = false,
                    ),
                    suggestionsType = Identifier("minecraft:ask_server"),
                    children = emptyList(),
                    executable = true,
                    restricted = true,
                ),
            ),
            rootIndex = 0,
        )
        val expected = "020001013600067461726765740601146d696e6563726166743a61736b5f73657276657200".hexToByteArray()

        assertContentEquals(
            expected,
            MinecraftProtocolFormat.encodeToByteArray(packet),
        )
        assertEquals(
            expected = packet,
            actual = MinecraftProtocolFormat.decodeFromByteArray<CommandsPacket>(expected),
        )

    }

    @Test
    fun `all property-bearing command parsers use their exact physical shapes`() {
        val cases = listOf(
            CommandParser.FloatRange(-1.0f, 2.0f) to
                    "0103bf80000040000000",
            CommandParser.DoubleRange(maximum = 2.0) to
                    "02024000000000000000",
            CommandParser.IntegerRange(minimum = -2) to
                    "0301fffffffe",
            CommandParser.LongRange() to
                    "0400",
            CommandParser.StringValue(CommandStringBehavior.GREEDY_PHRASE) to
                    "0502",
            CommandParser.Entity(single = true, playersOnly = true) to
                    "0603",
            CommandParser.ScoreHolder(allowsMultiple = true) to
                    "1f01",
            CommandParser.Time(minimumTicks = 20) to
                    "2b00000014",
            CommandParser.Registry(
                RegistryCommandParser.RESOURCE_OR_TAG,
                Identifier("minecraft:block"),
            ) to "2c0f6d696e6563726166743a626c6f636b",
        )

        for ((parser, hex) in cases) {
            val value = ParserValue(parser)
            val bytes = hex.hexToByteArray()
            assertContentEquals(
                bytes,
                MinecraftProtocolFormat.encodeToByteArray(value),
                parser.toString(),
            )
            assertEquals(
                value,
                MinecraftProtocolFormat.decodeFromByteArray<ParserValue>(bytes),
                parser.toString(),
            )
        }
    }

    @Test
    fun `all no-property parser IDs round trip without assuming enum ordinals`() {
        for (type in SimpleCommandParser.entries) {
            val value = ParserValue(CommandParser.Simple(type))
            val encoded = MinecraftProtocolFormat.encodeToByteArray(value)
            assertContentEquals(byteArrayOf(type.protocolId.toByte()), encoded, type.name)
            assertEquals(
                value,
                MinecraftProtocolFormat.decodeFromByteArray<ParserValue>(encoded),
                type.name,
            )
        }
    }

    @Test
    fun `numeric parser sentinel bounds canonicalize like vanilla`() {
        val value = ParserValue(
            CommandParser.IntegerRange(
                minimum = Int.MIN_VALUE,
                maximum = Int.MAX_VALUE,
            ),
        )
        assertContentEquals(
            "0300".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(value),
        )

        val decoded = MinecraftProtocolFormat.decodeFromByteArray<ParserValue>(
            "0303800000007fffffff".hexToByteArray(),
        )
        assertEquals(
            ParserValue(CommandParser.IntegerRange()),
            decoded,
        )
        assertContentEquals(
            "0300".hexToByteArray(),
            MinecraftProtocolFormat.encodeToByteArray(decoded),
        )
    }

    @Test
    fun `unknown parser and impossible graph cycles are rejected`() {
        assertFailsWith<SerializationException> {
            MinecraftProtocolFormat.decodeFromByteArray<ParserValue>(
                "39".hexToByteArray(),
            )
        }

        val childCycle = CommandsPacket(
            nodes = listOf(CommandNode.Root(children = listOf(0))),
            rootIndex = 0,
        )
        assertFailsWith<SerializationException> {
            MinecraftProtocolFormat.encodeToByteArray(childCycle)
        }

        val redirectCycle = CommandsPacket(
            nodes = listOf(CommandNode.Root(emptyList(), redirect = 0)),
            rootIndex = 0,
        )
        assertFailsWith<SerializationException> {
            MinecraftProtocolFormat.encodeToByteArray(redirectCycle)
        }
    }
}

@Serializable
private data class ParserValue(
    val parser: CommandParser,
)
