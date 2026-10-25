package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.data.SavedDataFile
import com.hiczp.minecraft.world.format.data.WorldGenDimension
import com.hiczp.minecraft.world.format.data.WorldGenDimensionType
import com.hiczp.minecraft.world.format.data.WorldGenSettingsData
import com.hiczp.minecraft.world.io.MinecraftWorldAccess
import com.hiczp.minecraft.world.io.WorldLockException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WorldEditorFilesystemTest {
    @Test
    fun versionDifferencesAreReportedAndOnlyWrittenRecordsReceiveCurrentVersion() = runTest {
        val directory = "build/test-worlds/${Uuid.random()}".toPath()
        val worldEditor = WorldEditor()
        try {
            for (dataVersion in listOf(
                MinecraftWorldFormat.WORLD_VERSION - 1,
                MinecraftWorldFormat.WORLD_VERSION + 1
            )) {
                val path = createWorld(directory / dataVersion.toString(), "different-version", dataVersion)
                createVersionedRecords(path, dataVersion)
                WorldEditorScenario.editDifferentVersion(worldEditor, path.toString(), dataVersion)
                MinecraftWorldAccess.open(path).use { assertEquals(dataVersion, it.readLevelData().data.dataVersion) }
            }
        } finally {
            worldEditor.close()
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    private suspend fun createVersionedRecords(path: Path, dataVersion: Int) {
        val worldSession = WorldSession.open(path, 0)
        try {
            val dimension = worldSession.minecraftWorldAccess.dimensions.overworld
            dimension.openRegion(RegionPosition(0, 0)).use { regionHandle ->
                for (x in listOf(0, 1, 3)) {
                    val chunk = Chunk(ChunkPosition(x, 0), worldSession.context(DimensionId.Overworld))
                    chunk.setSection(
                        4,
                        ChunkSection(chunk.chunkContext.defaultBlockState, chunk.chunkContext.defaultBiome)
                    )
                    val nbtDocument = worldSession.encoder(DimensionId.Overworld, ChunkNbtMetadata(dataVersion, 0))
                        .encodeDocument(chunk)
                    regionHandle.writeChunkNbtDocument(chunk.chunkPosition, nbtDocument)
                }
            }
            dimension.openPoiRegion(RegionPosition(0, 0)).use { regionHandle ->
                regionHandle.writeChunkNbtDocument(
                    ChunkPosition(0, 0), NbtDocument(
                        NbtCompound(
                            mapOf(
                                "DataVersion" to NbtInt(dataVersion),
                                "Sections" to NbtCompound(
                                    mapOf(
                                        "4" to NbtCompound(
                                            mapOf(
                                                "Valid" to NbtByte(1),
                                                "Records" to NbtList(emptyList())
                                            )
                                        )
                                    )
                                ),
                            )
                        )
                    )
                )
            }
            dimension.openEntityRegion(RegionPosition(0, 0)).use { regionHandle ->
                regionHandle.writeChunkNbtDocument(
                    ChunkPosition(0, 0), NbtDocument(
                        NbtCompound(
                            mapOf(
                                "DataVersion" to NbtInt(dataVersion),
                                "Position" to NbtIntArray(intArrayOf(0, 0)),
                                "Entities" to NbtList(emptyList()),
                            )
                        )
                    )
                )
            }
        } finally {
            worldSession.minecraftWorldAccess.close()
        }
    }

    @Test
    fun laterPersistenceFailureReportsAlreadyCompletedRegions() = runTest {
        val directory = "build/test-worlds/${Uuid.random()}".toPath()
        val path = createWorld(directory / "partial", "partial")
        val editor = WorldEditor()
        try {
            editor.execute("select_world", buildJsonObject { put("path", path.toString()) })
            val fill = buildJsonObject {
                put("dimension", "minecraft:overworld")
                put("min", BlockPosition(-1, 64, 0).json()); put("max", BlockPosition(0, 64, 0).json())
                put("state", buildJsonObject { put("name", "minecraft:stone") })
                put("missingChunks", "create_empty"); put("biome", "minecraft:desert")
            }
            editor.execute("fill_blocks", fill)
            val malformedPoi = NbtCompound(
                mapOf(
                    "DataVersion" to NbtInt(MinecraftWorldFormat.WORLD_VERSION),
                    "Sections" to NbtCompound(
                        mapOf(
                            "4" to NbtCompound(
                                mapOf(
                                    "Valid" to NbtByte(1),
                                    "Records" to NbtList(listOf(NbtCompound(mapOf("pos" to NbtString("invalid saved position"))))),
                                )
                            )
                        )
                    ),
                )
            )
            editor.execute("write_chunk", buildJsonObject {
                put("dimension", "minecraft:overworld"); put("kind", "poi")
                put("chunk", ChunkPosition(0, 0).json()); put("data", malformedPoi.snbt())
            })
            val failure = editor.call(
                "fill_blocks",
                JsonObject(fill + ("state" to buildJsonObject { put("name", "minecraft:gold_block") }))
            )
            assertTrue(failure.isError)
            val details = failure.value.obj("result")
            assertEquals("poi", details.text("phase"))
            assertEquals(listOf(ChunkPosition(-1, 0).json()), details.array("completed").toList())
            val read =
                editor.execute("read_blocks", JsonObject(fill.filterKeys { it in setOf("dimension", "min", "max") }))
                    .obj("result")
            assertEquals(
                listOf("minecraft:gold_block", "minecraft:stone"),
                read.array("items").map { it.jsonObject.obj("state").text("name") })
            val section = editor.execute("read_section", buildJsonObject {
                put("dimension", "minecraft:overworld"); put("chunk", ChunkPosition(-1, 0).json()); put(
                "sectionY",
                0
            ); put("representation", "expanded")
            }).obj("result").obj("data")
            assertTrue(section.array("biomes").all { it.jsonPrimitive.content == "minecraft:desert" })
        } finally {
            editor.close()
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun storageMetadataDoesNotDecodeCorruptChunkPayloads() = runTest {
        val directory = "build/test-worlds/${Uuid.random()}".toPath()
        val path = createWorld(directory / "metadata", "metadata")
        MinecraftWorldAccess.open(path).use { world ->
            world.dimensions.overworld.openRegion(RegionPosition(0, 0)).use {
                it.writeCompressedChunk(ChunkPosition(0, 0), CompressedChunk(Compression.ZLIB, byteArrayOf(1, 2, 3)))
            }
        }
        val editor = WorldEditor()
        try {
            editor.execute("select_world", buildJsonObject { put("path", path.toString()) })
            val arguments =
                buildJsonObject { put("dimension", "minecraft:overworld"); put("chunk", ChunkPosition(0, 0).json()) }
            assertEquals(
                3,
                editor.execute("get_chunk_info", arguments).obj("result")["compressedBytes"]!!.jsonPrimitive.int
            )
            val region = buildJsonObject {
                put("dimension", "minecraft:overworld"); put(
                "region",
                RegionPosition(0, 0).json()
            ); put("includeRecords", true)
            }
            assertEquals(1, editor.execute("list_chunks", region).obj("result").array("items").size)
            assertEquals(
                1,
                editor.execute("get_region_info", region).obj("result").obj("storage").obj("terrain").int("chunkCount")
            )
            assertTrue(editor.call("read_chunk", arguments).isError)
        } finally {
            editor.close()
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun editsAndSwitchesRealWorldDirectories() = runTest {
        val directory = "build/test-worlds/${Uuid.random()}".toPath()
        val first = createWorld(directory / "first", "first")
        val second = createWorld(directory / "second", "second")
        val invalid = directory / "invalid"
        FileSystem.SYSTEM.createDirectories(invalid)
        val editor = WorldEditor()
        try {
            WorldEditorScenario.edit(editor, first.toString(), second.toString(), invalid.toString())
            MinecraftWorldAccess.open(first).use { assertEquals("first", it.readLevelData().data.levelName) }
            MinecraftWorldAccess.open(second).use { assertEquals("second", it.readLevelData().data.levelName) }
        } finally {
            editor.close()
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun failedLockSwitchRetainsOldWorldAndCloseReleasesIt() = runTest {
        val directory = "build/test-worlds/${Uuid.random()}".toPath()
        val first = createWorld(directory / "first", "first")
        val second = createWorld(directory / "second", "second")
        val editor = WorldEditor()
        try {
            editor.execute("select_world", buildJsonObject { put("path", first.toString()) })
            assertFailsWith<WorldLockException> { MinecraftWorldAccess.open(first) }
            MinecraftWorldAccess.open(second).use {
                assertFailsWith<WorldLockException> {
                    editor.execute(
                        "select_world",
                        buildJsonObject { put("path", second.toString()) })
                }
                assertEquals("first", editor.execute("get_world_info").obj("result").text("name"))
            }
            editor.execute("close_world")
            MinecraftWorldAccess.open(first).use { assertEquals("first", it.readLevelData().data.levelName) }
        } finally {
            editor.close()
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    private suspend fun createWorld(
        path: Path,
        name: String,
        dataVersion: Int = MinecraftWorldFormat.WORLD_VERSION
    ): Path {
        MinecraftWorldAccess.open(path).use { world ->
            world.writeLevelData(
                LevelDat(
                    LevelDat.Data(
                        dataVersion = dataVersion, lastPlayed = 0L, levelName = name,
                        gameType = 1, time = 100L, version = 19133,
                        versionInfo = LevelDat.Data.Version(dataVersion, "test", "main", false),
                        serverBrands = listOf("vanilla"), wasModded = false, allowCommands = true, initialized = true,
                        difficultySettings = LevelDat.Data.DifficultySettings("peaceful", false, false),
                        spawn = LevelDat.Data.Spawn("minecraft:overworld", NbtIntArray(intArrayOf(0, 64, 0)), 0f, 0f),
                    )
                )
            )
            world.data.write(
                SavedDataId("world_gen_settings"), SavedDataFile(
                    dataVersion,
                    WorldGenSettingsData(
                        0L, false, false, mapOf(
                            DimensionId.Overworld to WorldGenDimension(
                                WorldGenDimensionType.Reference(DimensionTypeId.parse("minecraft:overworld")),
                                NbtCompound(
                                    mapOf(
                                        "type" to NbtString("minecraft:noise"),
                                        "settings" to NbtString("minecraft:overworld")
                                    )
                                ),
                            ),
                        )
                    ),
                )
            )
        }
        return FileSystem.SYSTEM.canonicalize(path)
    }
}
