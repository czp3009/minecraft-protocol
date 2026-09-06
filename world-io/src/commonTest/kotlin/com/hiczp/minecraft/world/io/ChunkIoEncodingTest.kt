package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.world.format.*
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import okio.Buffer
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*
import kotlinx.io.Buffer as KotlinxBuffer
import kotlinx.io.IOException as KotlinxIOException
import kotlinx.io.Sink as KotlinxSink
import kotlinx.io.Source as KotlinxSource

class ChunkIoEncodingTest {
    private val chunkContext = ChunkContext(
        DimensionId.Overworld, DimensionTypeLayout(0, 16, 16, false, false),
        BlockState(BlockId("example:empty")), BiomeId("example:biome"),
    )
    private val chunkPosition = ChunkPosition(-1, 2)
    private val chunkNbtEncoder = ChunkNbtEncoder(
        ChunkNbtEncoderContext(
            chunkContext.dimensionTypeLayout.chunkLayout, NbtFormat, NbtPropertyWriteMappings(), 300,
            ChunkNbtMetadata(12345, 400),
        )
    )

    @Test
    fun semanticDocumentAndCallbackWritesFinishTheSelectedCompressionExactlyOnce() {
        val fakeFileSystem = FakeFileSystem()
        val compressionCodec = FinishingCompressionCodec()
        val regionFileStore = store(fakeFileSystem, compressionCodec)
        val chunk = Chunk(chunkPosition, chunkContext)
        chunk.properties["example:value"] = PropertyValue(PropertyTypes.Int, 17)
        val nbtDocument = chunkNbtEncoder.encodeDocument(chunk)

        regionFileStore.writeChunk(chunk, chunkNbtEncoder, Compression.NONE)
        assertEquals(1, compressionCodec.finishCount)
        assertEquals(nbtDocument, regionFileStore.readChunkNbtDocument(chunkPosition))

        regionFileStore.writeChunkNbtDocument(chunkPosition, nbtDocument, Compression.NONE)
        assertEquals(2, compressionCodec.finishCount)
        assertEquals(nbtDocument, regionFileStore.readChunkNbtDocument(chunkPosition))

        val buffer = Buffer()
        regionFileStore.readChunkNbtTo(chunkPosition, buffer)
        regionFileStore.writeChunkNbt(chunkPosition, Compression.NONE) { sink -> sink.writeAll(buffer) }
        assertEquals(3, compressionCodec.finishCount)
        assertEquals(nbtDocument, regionFileStore.readChunkNbtDocument(chunkPosition))
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun compressionFailureKeepsTheOldRecordAndDoesNotReplaceCancellation() {
        val fakeFileSystem = FakeFileSystem()
        val compressionCodec = FinishingCompressionCodec()
        val regionFileStore = store(fakeFileSystem, compressionCodec)
        val chunk = Chunk(chunkPosition, chunkContext)
        chunk.properties["example:value"] = PropertyValue(PropertyTypes.Int, 17)
        regionFileStore.writeChunk(chunk, chunkNbtEncoder, Compression.NONE)

        val closeFailure = KotlinxIOException("synthetic compression finish failure")
        compressionCodec.closeFailure = closeFailure
        chunk.properties["example:value"] = PropertyValue(PropertyTypes.Int, 18)
        val exposed = assertFailsWith<IOException> {
            regionFileStore.writeChunk(chunk, chunkNbtEncoder, Compression.NONE)
        }
        val exposedFailure: Throwable = exposed
        assertTrue(exposedFailure === closeFailure || exposed.cause === closeFailure)
        assertEquals(2, compressionCodec.finishCount)
        assertEquals(NbtInt(17), regionFileStore.readChunkNbtDocument(chunkPosition)?.root?.get("example:value"))

        val cancellationException = CancellationException("encoding cancelled")
        val cancelled = assertFailsWith<CancellationException> {
            regionFileStore.writeChunkNbt(chunkPosition, Compression.NONE) { throw cancellationException }
        }
        assertSame(cancellationException, cancelled)
        assertTrue(cancelled.suppressedExceptions.any { it === closeFailure || it.cause === closeFailure })
        assertEquals(3, compressionCodec.finishCount)
        assertEquals(NbtInt(17), regionFileStore.readChunkNbtDocument(chunkPosition)?.root?.get("example:value"))
        fakeFileSystem.checkNoOpenFiles()
    }

    private fun store(fakeFileSystem: FakeFileSystem, compressionCodec: CompressionCodec): RegionFileStore =
        RegionFileStore(
            "/world/region".toPath(), fakeFileSystem,
            CompressedNbtFormat(compressionRegistry = CompressionRegistry(mapOf(Compression.NONE to compressionCodec))),
            RegionStorageConfiguration(syncWrites = false),
        )

    private class FinishingCompressionCodec : CompressionCodec {
        var finishCount = 0
        var closeFailure: Throwable? = null

        override fun compressingSink(sink: KotlinxSink): RawSink = object : RawSink {
            override fun write(source: KotlinxBuffer, byteCount: Long) = sink.write(source, byteCount)
            override fun flush() = sink.flush()
            override fun close() {
                finishCount++
                closeFailure?.let { throw it }
            }
        }

        override fun decompressingSource(source: KotlinxSource): RawSource = object : RawSource {
            override fun readAtMostTo(sink: KotlinxBuffer, byteCount: Long): Long = source.readAtMostTo(sink, byteCount)
            override fun close() = Unit
        }
    }
}
