package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.DataPackFileContent
import com.hiczp.minecraft.world.format.datapack.DataPackFilePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorldDataPackZipTest {
    @Test
    fun readsZipAfterCallerInspectsIndividualSizes() {
        val fakeFileSystem = FakeFileSystem()
        val dataPackZipPath = "/world/datapacks/example.zip".toPath()
        val dataPackMetadataJson = buildJsonObject {
            put(
                "pack",
                buildJsonObject {
                    put("description", "zip")
                    put("pack_format", 107)
                },
            )
        }
        val recipeJson = buildJsonObject { put("value", 42) }
        fakeFileSystem.createDirectories(requireNotNull(dataPackZipPath.parent))
        fakeFileSystem.write(dataPackZipPath) {
            write(
                zipBytes(
                    mapOf(
                        "pack.mcmeta" to jsonBytes(dataPackMetadataJson),
                        "data/example/recipe/value.json" to jsonBytes(recipeJson),
                    ),
                ),
            )
        }
        val worldDataPackReader = WorldDataPackReader(fakeFileSystem, "/world/datapacks".toPath())

        val dataPackInspection = worldDataPackReader.inspectDataPack(dataPackZipPath)

        assertEquals(DataPackContainerKind.ZIP, dataPackInspection.dataPackContainerKind)
        val recipeDataPackFilePath = DataPackFilePath("data/example/recipe/value.json")
        assertEquals(
            jsonBytes(recipeJson).size.toLong(),
            dataPackInspection.dataPackFileInfo(recipeDataPackFilePath)?.sizeInBytes,
        )
        assertEquals(
            jsonBytes(recipeJson).size,
            worldDataPackReader.readDataPackFile(dataPackInspection, recipeDataPackFilePath).sizeInBytes,
        )
        assertIs<DataPackFileContent.JsonFile>(
            worldDataPackReader.readDataPack(dataPackInspection).dataPackFileContent(recipeDataPackFilePath),
        )
        fakeFileSystem.checkNoOpenFiles()
    }

    private fun zipBytes(dataPackFileBytesByPath: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { zipFileBytes ->
            ZipOutputStream(zipFileBytes).use { zipOutputStream ->
                dataPackFileBytesByPath.forEach { (dataPackFilePath, dataPackFileBytes) ->
                    zipOutputStream.putNextEntry(ZipEntry(dataPackFilePath))
                    zipOutputStream.write(dataPackFileBytes)
                    zipOutputStream.closeEntry()
                }
            }
            zipFileBytes.toByteArray()
        }

    private fun jsonBytes(jsonElement: JsonElement): ByteArray =
        Json.encodeToString(jsonElement).encodeToByteArray()
}
