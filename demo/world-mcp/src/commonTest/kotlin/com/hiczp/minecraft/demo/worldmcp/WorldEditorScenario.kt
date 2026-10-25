package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.world.format.BlockPosition
import com.hiczp.minecraft.world.format.ChunkPosition
import com.hiczp.minecraft.world.format.MinecraftWorldFormat
import com.hiczp.minecraft.world.format.RegionPosition
import kotlinx.serialization.json.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Portable assertions; Native entries supply disposable filesystem worlds and real locking. */
object WorldEditorScenario {
    suspend fun editDifferentVersion(worldEditor: WorldEditor, path: String, dataVersion: Int) {
        suspend fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject =
            worldEditor.execute(name, arguments).obj("result")

        val selected = call("select_world", buildJsonObject { put("path", path) })
        assertEquals(dataVersion, selected.int("dataVersion"))
        assertEquals(MinecraftProtocol.MINECRAFT_VERSION, selected.obj("library").text("minecraftVersion"))
        assertEquals(MinecraftWorldFormat.WORLD_VERSION, selected.obj("library").int("dataVersion"))
        assertFalse(selected.flag("dataVersionMatchesLibrary"))
        assertEquals(selected, call("get_world_info"))
        val chunk =
            buildJsonObject { put("dimension", "minecraft:overworld"); put("chunk", ChunkPosition(0, 0).json()) }

        suspend fun storedVersion(x: Int, kind: String = "terrain"): Int = call(
            "read_chunk", JsonObject(
                chunk + mapOf(
                    "chunk" to ChunkPosition(x, 0).json(), "kind" to JsonPrimitive(kind),
                )
            )
        ).text("data").compoundSnbt().integer("DataVersion")
        assertEquals(dataVersion, storedVersion(0))
        val fill = buildJsonObject {
            put("dimension", "minecraft:overworld")
            put("min", BlockPosition(0, 64, 0).json()); put("max", BlockPosition(0, 64, 0).json())
            put("state", buildJsonObject { put("name", "minecraft:gold_block") })
        }
        call("fill_blocks", fill)
        assertEquals(MinecraftWorldFormat.WORLD_VERSION, storedVersion(0))
        assertEquals(MinecraftWorldFormat.WORLD_VERSION, storedVersion(1)) // Neighbour lighting was written.
        assertEquals(MinecraftWorldFormat.WORLD_VERSION, storedVersion(0, "poi"))
        assertEquals(dataVersion, storedVersion(3))
        assertEquals(dataVersion, storedVersion(0, "entities"))
        val original = call("read_chunk", chunk).text("data").compoundSnbt()
        val raw = original.withFields("DataVersion" to NbtInt(dataVersion))
        call("write_chunk", JsonObject(chunk + ("data" to JsonPrimitive(raw.snbt()))))
        assertEquals(original, call("read_chunk", chunk).text("data").compoundSnbt())
        val entities = JsonObject(chunk + ("kind" to JsonPrimitive("entities")))
        call("write_chunk", JsonObject(entities + ("data" to call("read_chunk", entities)["data"]!!)))
        assertEquals(MinecraftWorldFormat.WORLD_VERSION, storedVersion(0, "entities"))
        val region = buildJsonObject {
            put("dimension", "minecraft:overworld"); put(
            "region",
            RegionPosition(0, 0).json()
        ); put("pageSize", 32)
        }
        val records = call("read_region", region).array("items")
        call("write_region", JsonObject(region + ("data" to records)))
        assertEquals(
            MinecraftWorldFormat.WORLD_VERSION,
            storedVersion(3)
        ) // Complete Region replacement writes every supplied record.
        val read = call("read_blocks", JsonObject(fill.filterKeys { it in setOf("dimension", "min", "max") }))
        assertEquals("minecraft:gold_block", read.array("items").single().jsonObject.obj("state").text("name"))
        call("close_world")
    }

    suspend fun edit(worldEditor: WorldEditor, first: String, second: String, invalid: String) {
        suspend fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject =
            worldEditor.execute(name, arguments).obj("result")
        call("select_world", buildJsonObject { put("path", first) })
        assertFailsWith<IllegalArgumentException> { call("select_world", buildJsonObject { put("path", invalid) }) }
        assertEquals("first", call("get_world_info").text("name"))
        val dimension = "minecraft:overworld"
        val fill = buildJsonObject {
            put("dimension", dimension)
            put("min", BlockPosition(-1, 64, 0).json())
            put("max", BlockPosition(16, 64, 0).json())
            put("state", buildJsonObject { put("name", "minecraft:stone_bricks") })
        }
        assertFailsWith<IllegalArgumentException> { call("fill_blocks", fill) }
        val created = call(
            "fill_blocks",
            JsonObject(
                fill + mapOf(
                    "missingChunks" to JsonPrimitive("create_empty"),
                    "biome" to JsonPrimitive("minecraft:plains")
                )
            )
        )
        assertEquals(18, created["changedBlocks"]!!.jsonPrimitive.int)
        assertEquals(3, created.array("writtenChunks").size)

        val readArgs =
            JsonObject(fill.filterKeys { it in setOf("dimension", "min", "max") } + ("pageSize" to JsonPrimitive(7)))
        var page = call("read_blocks", readArgs)
        var total = 0
        val staleCursor = page.text("nextCursor")
        while (true) {
            page.array("items")
                .forEach { item -> assertEquals("minecraft:stone_bricks", item.jsonObject.obj("state").text("name")) }
            total += page.array("items").size
            if (page["nextCursor"] is JsonNull) break
            page = call("read_blocks", JsonObject(readArgs + ("cursor" to page["nextCursor"]!!)))
        }
        assertEquals(18, total)
        val chunkArgs = buildJsonObject { put("dimension", dimension); put("chunk", ChunkPosition(0, 0).json()) }
        val raw = call("read_chunk", chunkArgs).text("data").compoundSnbt()
        val marked =
            raw.withFields("custom" to NbtCompound(mapOf("exact" to NbtLong(Long.MAX_VALUE), "small" to NbtByte(7))))
        call("write_chunk", JsonObject(chunkArgs + ("data" to JsonPrimitive(marked.snbt()))))
        assertEquals(marked, call("read_chunk", chunkArgs).text("data").compoundSnbt())
        assertFailsWith<IllegalArgumentException> {
            call(
                "read_blocks",
                JsonObject(readArgs + ("cursor" to JsonPrimitive(staleCursor)))
            )
        }

        val sectionArgs =
            JsonObject(chunkArgs + mapOf("sectionY" to JsonPrimitive(4), "representation" to JsonPrimitive("expanded")))
        val expanded = call("read_section", sectionArgs).obj("data")
        assertEquals(4096, expanded.array("blocks").size)
        assertEquals(64, expanded.array("biomes").size)
        val blocks = expanded.array("blocks").toMutableList()
        blocks[1] = buildJsonObject { put("name", "minecraft:gold_block") }
        call(
            "write_section",
            JsonObject(sectionArgs + ("data" to JsonObject(expanded + ("blocks" to JsonArray(blocks)))))
        )
        assertEquals(
            NbtLong(Long.MAX_VALUE),
            call("read_chunk", chunkArgs).text("data").compoundSnbt().compound("custom")["exact"]
        )
        val changed = call(
            "read_blocks",
            buildJsonObject {
                put("dimension", dimension); put("min", BlockPosition(1, 64, 0).json()); put(
                "max",
                BlockPosition(1, 64, 0).json()
            )
            })
        assertEquals("minecraft:gold_block", changed.array("items").single().jsonObject.obj("state").text("name"))

        val expandedChunkArgs = JsonObject(chunkArgs + ("representation" to JsonPrimitive("expanded")))
        val whole = call("read_chunk", expandedChunkArgs).obj("data")
        call("write_chunk", JsonObject(expandedChunkArgs + ("data" to whole)))
        assertEquals(
            NbtLong(Long.MAX_VALUE),
            call("read_chunk", chunkArgs).text("data").compoundSnbt().compound("custom")["exact"]
        )
        val regionArgs = buildJsonObject {
            put("dimension", dimension); put("region", RegionPosition(0, 0).json()); put(
            "pageSize",
            1
        )
        }
        val regionPage = call("read_region", regionArgs)
        assertEquals(2, regionPage["total"]!!.jsonPrimitive.int)
        assertFalse(regionPage["nextCursor"] is JsonNull)
        assertEquals(
            1,
            call("read_region", JsonObject(regionArgs + ("cursor" to regionPage["nextCursor"]!!))).array("items").size
        )

        val entityRoot = NbtCompound(
            mapOf(
                "DataVersion" to NbtInt(MinecraftWorldFormat.WORLD_VERSION),
                "Position" to NbtIntArray(intArrayOf(0, 0)),
                "Entities" to NbtList(emptyList())
            )
        )
        val entityArgs = JsonObject(chunkArgs + ("kind" to JsonPrimitive("entities")))
        call("write_chunk", JsonObject(entityArgs + ("data" to JsonPrimitive(entityRoot.snbt()))))
        assertEquals(entityRoot, call("read_chunk", entityArgs).text("data").compoundSnbt())
        val poiRoot = NbtCompound(
            mapOf(
                "DataVersion" to NbtInt(MinecraftWorldFormat.WORLD_VERSION),
                "Sections" to NbtCompound(
                    mapOf(
                        "4" to NbtCompound(
                            mapOf(
                                "Valid" to NbtByte(1),
                                "Records" to NbtList(emptyList())
                            )
                        )
                    )
                )
            )
        )
        val poiArgs = JsonObject(chunkArgs + ("kind" to JsonPrimitive("poi")))
        call("write_chunk", JsonObject(poiArgs + ("data" to JsonPrimitive(poiRoot.snbt()))))
        assertTrue(
            call("get_section_info", JsonObject(poiArgs + ("sectionY" to JsonPrimitive(4)))).obj("section")
                .flag("valid")
        )

        val position = BlockPosition(2, 64, 0)
        val chestFill = buildJsonObject {
            put("dimension", dimension); put("min", position.json()); put("max", position.json())
            put("state", buildJsonObject { put("name", "minecraft:chest") })
        }
        call("fill_blocks", chestFill)
        val chestRoot = call("read_chunk", chunkArgs).text("data").compoundSnbt().withFields(
            "block_entities" to NbtList(
                listOf(
                    NbtCompound(
                        mapOf(
                            "id" to NbtString("minecraft:chest"),
                            "x" to NbtInt(2),
                            "y" to NbtInt(64),
                            "z" to NbtInt(0),
                            "Items" to NbtList(emptyList())
                        )
                    )
                )
            ),
            "block_ticks" to NbtList(
                listOf(
                    NbtCompound(
                        mapOf(
                            "i" to NbtString("minecraft:chest"),
                            "x" to NbtInt(2),
                            "y" to NbtInt(64),
                            "z" to NbtInt(0),
                            "t" to NbtInt(10),
                            "p" to NbtInt(0)
                        )
                    )
                )
            ),
        )
        call("write_chunk", JsonObject(chunkArgs + ("data" to JsonPrimitive(chestRoot.snbt()))))
        call("fill_blocks", JsonObject(chestFill + ("state" to buildJsonObject { put("name", "minecraft:air") })))
        val cleaned = call("read_chunk", chunkArgs).text("data").compoundSnbt()
        assertTrue(cleaned.list("block_entities").isEmpty())
        assertTrue(cleaned.list("block_ticks").isEmpty())
        assertTrue(cleaned.compound("Heightmaps").toMap().isEmpty())
        assertFalse(
            call("get_section_info", JsonObject(poiArgs + ("sectionY" to JsonPrimitive(4)))).obj("section")
                .flag("valid")
        )

        // A complete Region replacement removes a previously present sibling Chunk.
        val updatedRaw = call("read_chunk", chunkArgs).text("data")
        call("write_region", buildJsonObject {
            put("dimension", dimension); put("region", RegionPosition(0, 0).json())
            put(
                "data",
                JsonArray(listOf(buildJsonObject { put("chunk", ChunkPosition(0, 0).json()); put("data", updatedRaw) }))
            )
        })
        val chunks = call(
            "list_chunks",
            buildJsonObject { put("dimension", dimension); put("region", RegionPosition(0, 0).json()) })
        assertEquals(1, chunks.array("items").size)
        assertFalse(call("read_chunk", JsonObject(chunkArgs + ("chunk" to ChunkPosition(1, 0).json()))).flag("exists"))
        assertEquals(entityRoot, call("read_chunk", entityArgs).text("data").compoundSnbt())
        val info = call("get_chunk_info", JsonObject(chunkArgs + ("includeContent" to JsonPrimitive(true))))
        assertEquals("minecraft:full", info.obj("content").text("status"))
        assertTrue(call("list_sections", chunkArgs).array("items").any { it.jsonObject.int("y") == 4 })
        val coordinates = call(
            "convert_coordinates",
            buildJsonObject {
                put("dimension", dimension); put("from", "block"); put(
                "position",
                BlockPosition(-1, 64, -1).json()
            )
            })
        assertEquals(RegionPosition(-1, -1).json(), coordinates["region"])

        call("select_world", buildJsonObject { put("path", second) })
        assertEquals("second", call("get_world_info").text("name"))
        call("close_world")
        assertFailsWith<IllegalArgumentException> { call("get_world_info") }
    }
}
