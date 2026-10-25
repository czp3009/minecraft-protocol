package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import com.hiczp.minecraft.world.format.BlockPosition
import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.MinecraftWorldFormat
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Buffer as OkioBuffer

class EditorContractTest {
    @Test
    fun httpSdkListsToolsAndReportsUnselectedWorld() = testApplication {
        val worldEditor = WorldEditor()
        application { WorldMcp.install(this, worldEditor) }
        val httpClient = createClient { install(SSE); defaultRequest { header(HttpHeaders.Host, "localhost") } }
        val mcpClient = Client(Implementation("world-editor-test", "1"))
        try {
            mcpClient.connect(StreamableHttpClientTransport(httpClient, "http://localhost/mcp"))
            val names = mcpClient.listTools().tools.map { it.name }
            assertTrue("write_region" in names)
            assertTrue("select_world" in names)
            assertFalse("list_files" in names)
            val result = mcpClient.callTool(CallToolRequest(CallToolRequestParams("get_world_info")))
            assertEquals(true, result.isError)
            assertTrue(result.structuredContent!!.obj("result").text("message").contains("select_world"))
        } finally {
            mcpClient.close()
            httpClient.close()
            worldEditor.close()
        }
    }

    @Test
    fun selectiveInspectionSkipsInvalidPalettePayloadAndUnknownTrees() {
        val document = NbtDocument(
            NbtCompound(
                mapOf(
                    "DataVersion" to NbtInt(MinecraftWorldFormat.WORLD_VERSION),
                    "Status" to NbtString("minecraft:full"),
                    "sections" to NbtList(
                        listOf(
                            NbtCompound(
                                mapOf(
                                    "Y" to NbtByte(-1),
                                    "block_states" to NbtCompound(
                                        mapOf(
                                            "palette" to NbtList(
                                                listOf(
                                                    NbtString("minecraft:air"),
                                                    NbtString("minecraft:stone")
                                                )
                                            ),
                                            // This cannot decode as a valid palette array. Inspection must skip it.
                                            "data" to NbtString("not a packed array"),
                                        )
                                    ),
                                )
                            )
                        )
                    ),
                    "unrelated" to NbtCompound(mapOf("large" to NbtByteArray(ByteArray(100_000)))),
                )
            )
        )
        val buffer = Buffer()
        NbtFormat(NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED)).encodeDocumentToSink(
            document,
            buffer
        )
        val inspection = ChunkInspection.terrain(OkioBuffer().write(buffer.readByteArray()))
        assertEquals(-1, inspection.sections.single().y.toInt())
        assertEquals(2, inspection.sections.single().blocks!!.palette.size)
    }

    @Test
    fun boxesUseInclusiveNegativeCoordinatesAndXzyOrder() {
        val box = BlockBox(BlockPosition(-17, -1, -2), BlockPosition(-16, 0, -1))
        assertEquals(8L, box.volume)
        assertEquals(BlockPosition(-16, -1, -2), box.position(1))
        assertEquals(BlockPosition(-17, -1, -1), box.position(2))
        assertEquals(BlockPosition(-17, 0, -2), box.position(4))
        assertEquals(listOf(ChunkPosition(-2, -1), ChunkPosition(-1, -1)), box.chunkRange.toList())
        for (index in 0 until box.volume) assertEquals(index, box.index(box.position(index)))
    }
}
