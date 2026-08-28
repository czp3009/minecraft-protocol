package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.Compression
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DirectFilesTest {
    @Test
    fun mutableAndLiveDirectFilesUseExactPathsAndOkioStreams() = runTest {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        fakeFileSystem.createDirectories(minecraftWorldPaths.root)
        val outsideRaw = "/outside/raw.bin".toPath()
        val outsideNbt = "/outside/value.nbt".toPath()
        val outsideJson = "/outside/value.json".toPath()
        val typedNbt = "/outside/typed.nbt".toPath()
        val typedJson = "/outside/typed.json".toPath()
        val nbtDocument = NbtDocument(NbtCompound(mapOf("value" to NbtInt(7))))
        val directValue = DirectValue(7)
        val minecraftWorldAccess = MinecraftWorldAccess.create(minecraftWorldPaths, fakeFileSystem)

        minecraftWorldAccess.directFiles.write(outsideRaw) { sink -> sink.writeUtf8("raw") }
        minecraftWorldAccess.directFiles.writeNbtDocument(outsideNbt, nbtDocument, Compression.NONE)
        minecraftWorldAccess.directFiles.writeJson(outsideJson) { sink -> sink.writeUtf8("7") }
        minecraftWorldAccess.directFiles.writeNbt(typedNbt, DirectValue.serializer(), directValue, Compression.NONE)
        minecraftWorldAccess.directFiles.writeNbt(typedNbt, directValue, Compression.NONE)
        minecraftWorldAccess.directFiles.writeJson(typedJson, DirectValue.serializer(), directValue)
        minecraftWorldAccess.directFiles.writeJson(typedJson, directValue)
        minecraftWorldAccess.directFiles.writeBytes(minecraftWorldPaths.sessionLock, byteArrayOf(1, 2, 3))

        assertEquals("raw", minecraftWorldAccess.directFiles.read(outsideRaw) { source -> source.readUtf8() })
        assertEquals(nbtDocument, minecraftWorldAccess.directFiles.readNbtDocument(outsideNbt, Compression.NONE))
        assertEquals(
            directValue,
            minecraftWorldAccess.directFiles.readNbt<DirectValue>(outsideNbt, Compression.NONE),
        )
        assertEquals(
            directValue,
            minecraftWorldAccess.directFiles.readNbt(typedNbt, DirectValue.serializer(), Compression.NONE),
        )
        assertEquals(directValue, minecraftWorldAccess.directFiles.readNbt<DirectValue>(typedNbt, Compression.NONE))
        assertEquals(JsonPrimitive(7), minecraftWorldAccess.directFiles.readJsonElement(outsideJson))
        assertEquals(directValue, minecraftWorldAccess.directFiles.readJson(typedJson, DirectValue.serializer()))
        assertEquals(directValue, minecraftWorldAccess.directFiles.readJson<DirectValue>(typedJson))
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            fakeFileSystem.read(minecraftWorldPaths.sessionLock) { readByteArray() })

        minecraftWorldAccess.close()
        assertFailsWith<IllegalStateException> { minecraftWorldAccess.directFiles.readBytes(outsideRaw) }

        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(minecraftWorldPaths.root, fakeFileSystem)
        assertEquals("raw", liveMinecraftWorldAccess.directFiles.read(outsideRaw) { source -> source.readUtf8() })
        assertEquals(nbtDocument, liveMinecraftWorldAccess.directFiles.readNbtDocument(outsideNbt, Compression.NONE))
        assertEquals(
            directValue,
            liveMinecraftWorldAccess.directFiles.readNbt<DirectValue>(outsideNbt, Compression.NONE),
        )
        assertEquals(
            directValue,
            liveMinecraftWorldAccess.directFiles.readNbt(typedNbt, DirectValue.serializer(), Compression.NONE),
        )
        assertEquals(directValue, liveMinecraftWorldAccess.directFiles.readNbt<DirectValue>(typedNbt, Compression.NONE))
        assertEquals("7", liveMinecraftWorldAccess.directFiles.readJson(outsideJson) { source -> source.readUtf8() })
        assertEquals(directValue, liveMinecraftWorldAccess.directFiles.readJson(typedJson, DirectValue.serializer()))
        assertEquals(directValue, liveMinecraftWorldAccess.directFiles.readJson<DirectValue>(typedJson))
        fakeFileSystem.checkNoOpenFiles()
    }
}

@Serializable
private data class DirectValue(val value: Int)
