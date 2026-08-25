package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.DataPackFileContent
import com.hiczp.minecraft.world.format.datapack.DataPackFilePath
import com.hiczp.minecraft.world.format.datapack.DataPackId
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorldDataPackReaderTest {
    @Test
    fun inspectionExposesSizesBeforeUnrestrictedDetachedRead() {
        val fileSystem = FakeFileSystem()
        val dataPackDirectory = "/world/datapacks/example".toPath()
        val dataPackMetadataJson = buildJsonObject {
            put(
                "pack",
                buildJsonObject {
                    put("description", "example")
                    put("min_format", buildJsonArray {
                        add(107)
                        add(1)
                    })
                    put("max_format", 107)
                },
            )
        }
        val recipeJson = buildJsonObject { put("value", 42) }
        fileSystem.writeJson(dataPackDirectory / "pack.mcmeta", dataPackMetadataJson)
        fileSystem.writeJson(dataPackDirectory / "data/example/recipe/value.json", recipeJson)
        val binaryDataPackFilePath = dataPackDirectory / "data/example/custom/payload.bin"
        fileSystem.createDirectories(requireNotNull(binaryDataPackFilePath.parent))
        fileSystem.write(binaryDataPackFilePath) { write(byteArrayOf(1, 2, 3, 4)) }
        val worldDataPackReader = WorldDataPackReader(fileSystem, "/world/datapacks".toPath())

        val dataPackInspection = worldDataPackReader.inspectDataPack(
            dataPackDirectory,
            DataPackId("file/example"),
        )

        assertEquals(3, dataPackInspection.dataPackFileInfos.size)
        assertEquals(
            dataPackInspection.dataPackFileInfos.sumOf { it.sizeInBytes.toULong() },
            dataPackInspection.totalSizeInBytes,
        )
        val dataPack = worldDataPackReader.readDataPack(dataPackInspection)
        assertEquals(DataPackId("file/example"), dataPack.dataPackId)
        assertEquals(
            byteArrayOf(1, 2, 3, 4).toList(),
            worldDataPackReader.readDataPackFile(
                dataPackInspection,
                DataPackFilePath("data/example/custom/payload.bin"),
            ) { dataPackFileSource -> dataPackFileSource.readByteArray().toList() },
        )
        assertIs<DataPackFileContent.JsonFile>(
            dataPack.dataPackFileContent(DataPackFilePath("data/example/recipe/value.json")),
        )
        assertIs<DataPackFileContent.BinaryFile>(
            dataPack.dataPackFileContent(DataPackFilePath("data/example/custom/payload.bin")),
        )
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun enabledListRetainsNonFileReferencesForTheCaller() {
        val fileSystem = FakeFileSystem()
        val dataPackDirectory = "/world/datapacks/example".toPath()
        fileSystem.writeJson(
            dataPackDirectory / "pack.mcmeta",
            buildJsonObject {
                put(
                    "pack",
                    buildJsonObject {
                        put("description", "example")
                        put("pack_format", 107)
                    },
                )
            },
        )
        val worldDataPackReader = WorldDataPackReader(fileSystem, "/world/datapacks".toPath())

        val worldDataPackLoadResult = worldDataPackReader.readEnabledDataPacks(
            listOf("vanilla", "file/example"),
        )

        assertEquals(listOf("vanilla"), worldDataPackLoadResult.unresolvedDataPackReferences)
        assertEquals(
            listOf(DataPackId("file/example")),
            worldDataPackLoadResult.dataPackStack.dataPacks.map { it.dataPackId },
        )
        fileSystem.checkNoOpenFiles()
    }
}

private fun FileSystem.writeJson(path: Path, element: JsonElement) {
    val bytes = Json.encodeToString(element).encodeToByteArray()
    createDirectories(requireNotNull(path.parent))
    write(path) { write(bytes) }
}
