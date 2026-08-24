package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.DataPackFileContent
import com.hiczp.minecraft.world.format.datapack.DataPackPath
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
        val fileSystem = FakeFileSystem()
        val zipPath = "/world/datapacks/example.zip".toPath()
        val metadata = buildJsonObject {
            put(
                "pack",
                buildJsonObject {
                    put("description", "zip")
                    put("pack_format", 107)
                },
            )
        }
        val recipe = buildJsonObject { put("value", 42) }
        fileSystem.createDirectories(requireNotNull(zipPath.parent))
        fileSystem.write(zipPath) {
            write(
                zipBytes(
                    mapOf(
                        "pack.mcmeta" to jsonBytes(metadata),
                        "data/example/recipe/value.json" to jsonBytes(recipe),
                    ),
                ),
            )
        }
        val store = WorldDataPackStore(fileSystem, "/world/datapacks".toPath())

        val inspection = store.inspectPack(zipPath)

        assertEquals(DataPackContainerKind.ZIP, inspection.containerKind)
        val recipePath = DataPackPath("data/example/recipe/value.json")
        assertEquals(jsonBytes(recipe).size.toLong(), inspection.file(recipePath)?.size)
        assertEquals(jsonBytes(recipe).size, store.readFile(inspection, recipePath).size)
        assertIs<DataPackFileContent.JsonFile>(store.readPack(inspection).file(recipePath))
        fileSystem.checkNoOpenFiles()
    }

    private fun zipBytes(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private fun jsonBytes(element: JsonElement): ByteArray =
        Json.encodeToString(JsonElement.serializer(), element).encodeToByteArray()
}
