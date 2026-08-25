package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.DataPackFileContent
import com.hiczp.minecraft.world.format.datapack.DataPackPath
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
        val fileSystem = FakeFileSystem()
        val zipPath = "/world/datapacks/example.zip".toPath()
        val metadata = buildJsonObject {
            put("pack", buildJsonObject {
                put("description", "zip")
                put("pack_format", 107)
            })
        }
        val recipe = buildJsonObject { put("value", 42) }
        val files = mapOf(
            "pack.mcmeta" to jsonBytes(metadata),
            "data/example/recipe/value.json" to jsonBytes(recipe),
        )
        fileSystem.createDirectories(requireNotNull(zipPath.parent))
        fileSystem.write(zipPath) { write(zipBytes(files)) }
        val store = WorldDataPackStore(fileSystem, "/world/datapacks".toPath())

        val inspection = store.inspectPack(zipPath)
        val pack = store.readPack(zipPath)

        assertEquals(DataPackContainerKind.ZIP, inspection.containerKind)
        assertEquals(files.values.sumOf(ByteArray::size).toULong(), inspection.totalSize)
        assertEquals(
            recipe,
            assertIs<DataPackFileContent.JsonFile>(
                pack.file(DataPackPath("data/example/recipe/value.json")),
            ).element,
        )
        assertEquals(
            jsonBytes(recipe).toList(),
            store.readFile(inspection, DataPackPath("data/example/recipe/value.json")).toByteArray().toList(),
        )
        fileSystem.checkNoOpenFiles()
    }

    private fun zipBytes(files: Map<String, ByteArray>): ByteArray {
        val archive = createAdmZip()
        files.forEach { (path, bytes) ->
            archive.addFile(path, bytes.toExactUint8Array())
        }
        return archive.toBuffer().toExactByteArray()
    }

    private fun jsonBytes(element: JsonElement): ByteArray =
        Json.encodeToString(element).encodeToByteArray()
}
