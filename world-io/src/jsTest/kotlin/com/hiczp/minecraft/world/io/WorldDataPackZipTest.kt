package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.DataPackFileContent
import com.hiczp.minecraft.world.format.datapack.DataPackFilePath
import com.hiczp.minecraft.world.io.internal.admzip.createAdmZip
import com.hiczp.minecraft.world.io.internal.admzip.toExactByteArray
import com.hiczp.minecraft.world.io.internal.admzip.toExactUint8Array
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorldDataPackZipTest {
    @Test
    fun portableZipAdapterInspectsStreamsAndParses() {
        val fakeFileSystem = FakeFileSystem()
        val dataPackZipPath = "/world/datapacks/example.zip".toPath()
        val dataPackMetadataJson = buildJsonObject {
            put("pack", buildJsonObject {
                put("description", "zip")
                put("pack_format", 107)
            })
        }
        val recipeJson = buildJsonObject { put("value", 42) }
        val dataPackFileBytesByPath = mapOf(
            "pack.mcmeta" to jsonBytes(dataPackMetadataJson),
            "data/example/recipe/value.json" to jsonBytes(recipeJson),
        )
        fakeFileSystem.createDirectories(requireNotNull(dataPackZipPath.parent))
        fakeFileSystem.write(dataPackZipPath) { write(zipBytes(dataPackFileBytesByPath)) }
        val worldDataPackReader = WorldDataPackReader(fakeFileSystem, "/world/datapacks".toPath())

        val dataPackInspection = worldDataPackReader.inspectDataPack(dataPackZipPath)
        val dataPack = worldDataPackReader.readDataPack(dataPackZipPath)

        assertEquals(DataPackContainerKind.ZIP, dataPackInspection.dataPackContainerKind)
        assertEquals(
            dataPackFileBytesByPath.values.sumOf(ByteArray::size).toULong(),
            dataPackInspection.totalSizeInBytes,
        )
        assertEquals(
            recipeJson,
            assertIs<DataPackFileContent.JsonFile>(
                dataPack.dataPackFileContent(DataPackFilePath("data/example/recipe/value.json")),
            ).jsonElement,
        )
        assertEquals(
            jsonBytes(recipeJson).toList(),
            worldDataPackReader.readDataPackFile(
                dataPackInspection,
                DataPackFilePath("data/example/recipe/value.json"),
            ).toByteArray().toList(),
        )
        fakeFileSystem.checkNoOpenFiles()
    }

    private fun zipBytes(dataPackFileBytesByPath: Map<String, ByteArray>): ByteArray {
        val dataPackArchive = createAdmZip()
        dataPackFileBytesByPath.forEach { (dataPackFilePath, dataPackFileBytes) ->
            dataPackArchive.addFile(dataPackFilePath, dataPackFileBytes.toExactUint8Array())
        }
        return dataPackArchive.toBuffer().toExactByteArray()
    }

    private fun jsonBytes(jsonElement: JsonElement): ByteArray =
        Json.encodeToString(jsonElement).encodeToByteArray()
}
