package com.hiczp.minecraft.protocol.buildScript

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProtocolInventorySupportTest {
    @Test
    fun packetKeysIntegersAndLineNumbersUseProtocolNotation() {
        assertEquals(
            "play/clientbound/0x0A",
            PacketKey("PLAY", "CLIENTBOUND", 10).display(),
        )
        assertEquals(255, parseInteger("255"))
        assertEquals(255, parseInteger("0xff"))
        assertEquals(255, parseInteger("0XFF"))
        assertEquals(-1, parseInteger("-1"))
        assertFailsWith<NumberFormatException> { parseInteger("0x") }
        assertFailsWith<NumberFormatException> { parseInteger("1.0") }
        assertEquals(1, lineNumber("a\nb\nc", 0))
        assertEquals(2, lineNumber("a\nb\nc", 2))
        assertEquals(3, lineNumber("a\nb\nc", 5))
    }

    @Test
    fun readsQuotedCsvWithASelectedDelimiter() = withTemporaryDirectory {
        val csv = resolve("values.csv")
        csv.writeText(
            """
            id;name
            1;"comma, value"
            """.trimIndent(),
        )

        val rows = readCsv(csv, ';').use { it.toList() }

        assertEquals(1, rows.size)
        assertEquals("1", rows.single()["id"])
        assertEquals("comma, value", rows.single()["name"])
    }

    @Test
    fun packetManifestAppliesOfficialNameCorrections() =
        withTemporaryDirectory {
            writeTextFile(
                "packet-inventory.csv",
                """
                state,direction,id,wiki_name,official_name,framing
                play,clientbound,0x01,Wiki Name,old_name,normal
                play,serverbound,2,Other,unchanged,normal
                """,
            )
            writeTextFile(
                "official-packet-audit.json",
                """
                {
                  "normalized_name_differences": [
                    {
                      "state": "play",
                      "direction": "clientbound",
                      "id": 1,
                      "vanilla": "minecraft:corrected_name"
                    }
                  ]
                }
                """,
            )

            val packets = loadPacketManifest(this)

            assertEquals(2, packets.size)
            assertEquals("corrected_name", packets[0].officialName)
            assertEquals("unchanged", packets[1].officialName)
            assertEquals("normal", packets[0].framing)
        }

    @Test
    fun packetManifestRequiresBothCheckedInInputs() = withTemporaryDirectory {
        val missingInventory = assertFailsWith<IllegalStateException> {
            loadPacketManifest(this)
        }
        assertTrue(missingInventory.message.orEmpty().contains("packet-inventory"))

        writeTextFile(
            "packet-inventory.csv",
            """
            state,direction,id,wiki_name,official_name,framing
            play,clientbound,1,Name,name,normal
            """,
        )
        val missingAudit = assertFailsWith<IllegalStateException> {
            loadPacketManifest(this)
        }
        assertTrue(
            missingAudit.message.orEmpty().contains("official-packet-audit"),
        )
    }

    @Test
    fun discoversLocalPacketsAndReportsStructuralAnnotationErrors() =
        withTemporaryDirectory {
            val sources = resolve("protocol-model/src/commonMain/kotlin/test")
            sources.createDirectories()
            sources.resolve("Packets.kt").writeText(
                """
                package test

                @Serializable
                @PacketInfo(
                    0x01,
                    ConnectionState.PLAY,
                    PacketDirection.CLIENTBOUND,
                    "first"
                )
                data class FirstPacket(val value: Int)

                @PacketInfo(
                    id = 2,
                    state = ConnectionState.PLAY,
                    direction = PacketDirection.SERVERBOUND,
                    officialName = "second",
                )
                class SecondPacket

                @PacketInfo(
                    3,
                    ConnectionState.STATUS,
                    PacketDirection.CLIENTBOUND,
                    "duplicate"
                )
                @PacketInfo(
                    4,
                    ConnectionState.STATUS,
                    PacketDirection.CLIENTBOUND,
                    "duplicate"
                )
                @Serializable
                data object DuplicatePacket
                """.trimIndent(),
            )
            sources.resolve("Orphan.kt").writeText(
                """
                @PacketInfo(
                    5,
                    ConnectionState.LOGIN,
                    PacketDirection.CLIENTBOUND,
                    "orphan"
                )
                """.trimIndent(),
            )
            sources.resolve("Ignored.txt").writeText("@PacketInfo")

            val (packets, errors) = loadLocalPackets(this)

            assertEquals(
                listOf("FirstPacket", "SecondPacket", "DuplicatePacket"),
                packets.map(LocalPacket::className),
            )
            assertEquals(
                listOf(1, 2, 3),
                packets.map { it.key.packetId },
            )
            assertEquals(
                listOf("first", "second", "duplicate"),
                packets.map(LocalPacket::officialName),
            )
            assertEquals(3, errors.size)
            assertTrue(errors.any { it.contains("not @Serializable") })
            assertTrue(errors.any { it.contains("multiple @PacketInfo") })
            assertTrue(errors.any { it.contains("not followed") })
        }

    @Test
    fun kotlinSourceDiscoveryIsRecursiveSortedAndMissingSafe() =
        withTemporaryDirectory {
            val sources = resolve("sources")
            sources.resolve("b").createDirectories()
            sources.resolve("a").createDirectories()
            sources.resolve("b/B.kt").writeText("")
            sources.resolve("a/A.kt").writeText("")
            sources.resolve("a/ignored.java").writeText("")

            assertEquals(
                listOf("A.kt", "B.kt"),
                kotlinSources(sources).map { it.fileName.toString() },
            )
            assertTrue(kotlinSources(resolve("missing")).isEmpty())
        }

    private fun Path.writeTextFile(relative: String, content: String) {
        resolve(relative).writeText(content.trimIndent() + "\n")
    }

    private fun withTemporaryDirectory(block: Path.() -> Unit) {
        val directory = Files.createTempDirectory("protocol-inventory-")
        try {
            directory.block()
        } finally {
            if (directory.exists()) {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder())
                        .forEach(Files::delete)
                }
            }
        }
    }
}
