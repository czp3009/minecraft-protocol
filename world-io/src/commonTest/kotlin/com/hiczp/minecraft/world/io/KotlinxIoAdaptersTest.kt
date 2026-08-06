package com.hiczp.minecraft.world.io

import kotlinx.io.buffered
import kotlinx.io.readByteArray
import okio.Sink
import okio.Source
import okio.Timeout
import kotlin.test.*
import kotlinx.io.Buffer as KotlinxBuffer
import okio.Buffer as OkioBuffer

class KotlinxIoAdaptersTest {
    @Test
    fun sourceAdapterHandlesShortReadsEofAndOwnership() {
        val bytes = ByteArray(19_000) { it.toByte() }
        val upstream = ShortSource(bytes, maximumRead = 3)
        val source = OkioToKotlinxRawSource(upstream).buffered()

        assertContentEquals(bytes, source.readByteArray())
        assertEquals(-1L, source.readAtMostTo(KotlinxBuffer(), 1L))
        source.close()
        assertTrue(upstream.closed)
    }

    @Test
    fun sourceAdapterValidatesCallsAndCanLeaveUpstreamOpen() {
        val upstream = ShortSource(byteArrayOf(1), maximumRead = 1)
        val source = OkioToKotlinxRawSource(
            upstream,
            closeUpstream = false,
        )

        assertEquals(0L, source.readAtMostTo(KotlinxBuffer(), 0L))
        assertFailsWith<IllegalArgumentException> {
            source.readAtMostTo(KotlinxBuffer(), -1)
        }
        source.close()
        source.close()
        assertTrue(!upstream.closed)
        assertFailsWith<IllegalStateException> {
            source.readAtMostTo(KotlinxBuffer(), 1)
        }
    }

    @Test
    fun sourceAdapterRejectsAnUpstreamThatMakesNoProgress() {
        val source = OkioToKotlinxRawSource(
            ShortSource(byteArrayOf(1), maximumRead = 0),
        )

        assertFailsWith<WorldIOException> {
            source.readAtMostTo(KotlinxBuffer(), 1)
        }
        source.close()
    }

    @Test
    fun sinkAdapterTransfersInFixedChunksAndFlushesAndCloses() {
        val downstream = RecordingSink()
        val sink = KotlinxToOkioRawSink(downstream).buffered()
        val bytes = ByteArray(19_000) { (it * 7).toByte() }

        sink.write(bytes)
        sink.flush()
        sink.close()

        assertContentEquals(bytes, downstream.buffer.readByteArray())
        assertEquals(1, downstream.flushes)
        assertTrue(downstream.closed)
        assertTrue(downstream.writeSizes.all { it <= 8_192L })
    }

    @Test
    fun sinkAdapterValidatesCountsIsIdempotentAndCanLeaveDownstreamOpen() {
        val downstream = RecordingSink()
        val sink = KotlinxToOkioRawSink(
            downstream,
            closeDownstream = false,
        )
        val source = KotlinxBuffer().apply { write(byteArrayOf(1, 2)) }

        sink.write(source, 0)
        assertFailsWith<IllegalArgumentException> {
            sink.write(source, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            sink.write(source, 3)
        }
        sink.write(source, 2)
        sink.flush()
        sink.close()
        sink.close()

        assertContentEquals(
            byteArrayOf(1, 2),
            downstream.buffer.readByteArray(),
        )
        assertTrue(!downstream.closed)
        assertFailsWith<IllegalStateException> {
            sink.flush()
        }
    }

    @Test
    fun closeFailuresAreSuppressedBehindTheOriginalFailure() {
        val original = IllegalStateException("write")
        val closeFailure = IllegalArgumentException("close")

        val thrown = assertFailsWith<IllegalStateException> {
            try {
                throw original
            } finally {
                closeAllPreserving(original, { throw closeFailure })
            }
        }

        assertTrue(thrown === original)
        assertTrue(thrown.suppressedExceptions.single() === closeFailure)
    }

    @Test
    fun closeAggregationUsesTheFirstCloseFailureAsPrimary() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")

        val thrown = assertFailsWith<IllegalStateException> {
            closeAllPreserving(
                failure = null,
                { throw first },
                { throw second },
            )
        }

        assertTrue(thrown === first)
        assertTrue(thrown.suppressedExceptions.single() === second)
    }
}

private class ShortSource(
    bytes: ByteArray,
    private val maximumRead: Long,
) : Source {
    private val buffer = OkioBuffer().apply { write(bytes) }
    var closed = false

    override fun read(sink: OkioBuffer, byteCount: Long): Long =
        buffer.read(sink, minOf(byteCount, maximumRead))

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
    }
}

private class RecordingSink : Sink {
    val buffer = OkioBuffer()
    var flushes = 0
    var closed = false
    val writeSizes = mutableListOf<Long>()

    override fun write(source: OkioBuffer, byteCount: Long) {
        writeSizes += byteCount
        buffer.write(source, byteCount)
    }

    override fun flush() {
        flushes++
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
    }
}
