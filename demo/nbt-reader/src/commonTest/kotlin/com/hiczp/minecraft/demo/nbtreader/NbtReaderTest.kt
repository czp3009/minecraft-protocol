package com.hiczp.minecraft.demo.nbtreader

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtBinaryFormatException
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import okio.Buffer
import okio.GzipSink
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import okio.use
import kotlin.test.*

class NbtReaderTest {
    private val path = "/input.nbt".toPath()
    private val namedNbtTag = NamedNbtTag(
        "named root",
        NbtCompound(mapOf("mixed" to NbtList(listOf(NbtInt(42), NbtString("hello"))))),
    )

    @Test
    fun detectsRawAndGzipRegardlessOfFileExtensionAndPreservesTheNamedTree() {
        for (inputCompression in listOf(InputCompression.NONE, InputCompression.GZIP)) {
            FakeFileSystem().use { fakeFileSystem ->
                val bytes = NbtFormat.encodeNamedTagToByteArray(namedNbtTag)
                val storedBytes = if (inputCompression == InputCompression.GZIP) bytes.gzip() else bytes
                fakeFileSystem.write(path) { write(storedBytes) }
                val nbtReader = NbtReader(fakeFileSystem)
                assertEquals(NbtReadResult(namedNbtTag, inputCompression), nbtReader.read(path))
                assertEquals(NbtReadResult(namedNbtTag, inputCompression), nbtReader.read(path, inputCompression))
                assertContentEquals(storedBytes, fakeFileSystem.read(path) { readByteArray() })
                fakeFileSystem.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun acceptsScalarRootsWithEmptyNames() {
        FakeFileSystem().use { fakeFileSystem ->
            val scalarRoot = NamedNbtTag("", NbtInt(7))
            fakeFileSystem.write(path) { write(NbtFormat.encodeNamedTagToByteArray(scalarRoot)) }
            assertEquals(scalarRoot, NbtReader(fakeFileSystem).read(path).namedNbtTag)
        }
    }

    @Test
    fun rejectsTrailingAndTruncatedNbtInBothCompressionModes() {
        val bytes = NbtFormat.encodeNamedTagToByteArray(namedNbtTag)
        for (malformedBytes in listOf(bytes + byteArrayOf(1), bytes.copyOf(bytes.size - 1))) {
            for (gzip in listOf(false, true)) {
                FakeFileSystem().use { fakeFileSystem ->
                    fakeFileSystem.write(path) { write(if (gzip) malformedBytes.gzip() else malformedBytes) }
                    assertFailsWith<NbtBinaryFormatException> { NbtReader(fakeFileSystem).read(path) }
                    fakeFileSystem.checkNoOpenFiles()
                }
            }
        }
    }

    @Test
    fun checksTheGzipTrailerEvenWhenTheNbtRootDecodedSuccessfully() {
        val compressedBytes = NbtFormat.encodeNamedTagToByteArray(namedNbtTag).gzip()
        compressedBytes[compressedBytes.size - 8] = (compressedBytes[compressedBytes.size - 8].toInt() xor 1).toByte()
        FakeFileSystem().use { fakeFileSystem ->
            fakeFileSystem.write(path) { write(compressedBytes) }
            assertFails { NbtReader(fakeFileSystem).read(path) }
            fakeFileSystem.checkNoOpenFiles()
        }
    }

    @Test
    fun rejectsEmptyFilesAndIncorrectExplicitCompression() {
        FakeFileSystem().use { fakeFileSystem ->
            fakeFileSystem.write(path) {}
            assertFailsWith<NbtBinaryFormatException> { NbtReader(fakeFileSystem).read(path) }
            fakeFileSystem.write(path) { write(NbtFormat.encodeNamedTagToByteArray(namedNbtTag)) }
            assertFails { NbtReader(fakeFileSystem).read(path, InputCompression.GZIP) }
            fakeFileSystem.write(path) { write(NbtFormat.encodeNamedTagToByteArray(namedNbtTag).gzip()) }
            assertFailsWith<NbtBinaryFormatException> { NbtReader(fakeFileSystem).read(path, InputCompression.NONE) }
            fakeFileSystem.checkNoOpenFiles()
        }
    }

    @Test
    fun rejectsIncompleteGzipHeadersAndTrailersWithoutFallingBackToRawInput() {
        val compressedBytes = NbtFormat.encodeNamedTagToByteArray(namedNbtTag).gzip()
        for (length in listOf(2, 9, compressedBytes.size - 8, compressedBytes.size - 1)) {
            FakeFileSystem().use { fakeFileSystem ->
                fakeFileSystem.write(path) { write(compressedBytes, 0, length) }
                assertFails { NbtReader(fakeFileSystem).read(path) }
                fakeFileSystem.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun rejectsTrailingCompressedBytesAndConcatenatedGzipMembers() {
        val compressedBytes = NbtFormat.encodeNamedTagToByteArray(namedNbtTag).gzip()
        for (suffix in listOf(byteArrayOf(1), compressedBytes)) {
            FakeFileSystem().use { fakeFileSystem ->
                fakeFileSystem.write(path) { write(compressedBytes + suffix) }
                assertFails { NbtReader(fakeFileSystem).read(path) }
                fakeFileSystem.checkNoOpenFiles()
            }
        }
    }

    @Test
    fun preservesModifiedUtfInRootNamesKeysAndValues() {
        val text = "nul\u0000 pair\uD83D\uDE00 accent\u00E9"
        val unicodeRoot = NamedNbtTag(text, NbtCompound(mapOf(text to NbtString(text))))
        FakeFileSystem().use { fakeFileSystem ->
            fakeFileSystem.write(path) { write(NbtFormat.encodeNamedTagToByteArray(unicodeRoot).gzip()) }
            assertEquals(unicodeRoot, NbtReader(fakeFileSystem).read(path).namedNbtTag)
            fakeFileSystem.checkNoOpenFiles()
        }
    }

    @Test
    fun missingFilesAndDirectoriesFailWithoutCreatingOrChangingFiles() {
        FakeFileSystem().use { fakeFileSystem ->
            fakeFileSystem.createDirectories(path.parent!!)
            val nbtReader = NbtReader(fakeFileSystem)
            assertFails { nbtReader.read(path) }
            assertEquals(emptyList(), fakeFileSystem.list(path.parent!!))
            fakeFileSystem.createDirectory(path)
            assertFails { nbtReader.read(path) }
            assertEquals(emptyList(), fakeFileSystem.list(path))
            fakeFileSystem.checkNoOpenFiles()
        }
    }

    private fun ByteArray.gzip(): ByteArray {
        val buffer = Buffer()
        GzipSink(buffer).buffer().use { it.write(this) }
        return buffer.readByteArray()
    }
}
