package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.DataPackFileContent
import com.hiczp.minecraft.world.format.datapack.DataPackId
import com.hiczp.minecraft.world.format.datapack.DataPackPath
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorldDataPackStoreTest {
    @Test
    fun inspectionExposesSizesBeforeUnrestrictedDetachedRead() {
        val fileSystem = FakeFileSystem()
        val root = "/world/datapacks/example".toPath()
        val metadata = buildJsonObject {
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
        val value = buildJsonObject { put("value", 42) }
        fileSystem.writeJson(root / "pack.mcmeta", metadata)
        fileSystem.writeJson(root / "data/example/recipe/value.json", value)
        val binaryPath = root / "data/example/custom/payload.bin"
        fileSystem.createDirectories(requireNotNull(binaryPath.parent))
        fileSystem.write(binaryPath) { write(byteArrayOf(1, 2, 3, 4)) }
        val store = WorldDataPackStore(fileSystem, "/world/datapacks".toPath())

        val inspection = store.inspectPack(root, DataPackId("file/example"))

        assertEquals(3, inspection.files.size)
        assertEquals(
            inspection.files.sumOf { it.size.toULong() },
            inspection.totalSize,
        )
        val pack = store.readPack(inspection)
        assertEquals(DataPackId("file/example"), pack.id)
        assertEquals(
            byteArrayOf(1, 2, 3, 4).toList(),
            store.readFile(inspection, DataPackPath("data/example/custom/payload.bin")) { source ->
                source.readByteArray().toList()
            },
        )
        assertIs<DataPackFileContent.JsonFile>(
            pack.file(DataPackPath("data/example/recipe/value.json")),
        )
        assertIs<DataPackFileContent.BinaryFile>(
            pack.file(DataPackPath("data/example/custom/payload.bin")),
        )
        fileSystem.checkNoOpenFiles()
    }

    @Test
    fun enabledListRetainsNonFileReferencesForTheCaller() {
        val fileSystem = FakeFileSystem()
        val root = "/world/datapacks/example".toPath()
        fileSystem.writeJson(
            root / "pack.mcmeta",
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
        val store = WorldDataPackStore(fileSystem, "/world/datapacks".toPath())

        val loaded = store.readEnabled(listOf("vanilla", "file/example"))

        assertEquals(listOf("vanilla"), loaded.unresolvedReferences)
        assertEquals(listOf(DataPackId("file/example")), loaded.packs.map { it.id })
        fileSystem.checkNoOpenFiles()
    }
}

private fun FileSystem.writeJson(path: Path, element: JsonElement) {
    val bytes = Json.encodeToString(JsonElement.serializer(), element).encodeToByteArray()
    createDirectories(requireNotNull(path.parent))
    write(path) { write(bytes) }
}
