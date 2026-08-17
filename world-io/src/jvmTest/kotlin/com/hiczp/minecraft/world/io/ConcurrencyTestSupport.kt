package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtInt
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.RegionChunk
import com.hiczp.minecraft.world.format.RegionChunkPayload
import kotlinx.coroutines.CompletableDeferred
import okio.*
import okio.fakefilesystem.FakeFileSystem
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class BlockingGate(
    private val expectedEntrants: Int = 1,
) {
    private val opened = AtomicBoolean(false)
    private val release = CountDownLatch(1)
    private val entered = CompletableDeferred<Unit>()
    private val entrants = AtomicInteger()

    init {
        require(expectedEntrants > 0)
    }

    fun awaitRelease() {
        if (entrants.incrementAndGet() >= expectedEntrants) {
            entered.complete(Unit)
        }
        release.await()
    }

    suspend fun awaitEntered() {
        entered.await()
    }

    fun open() {
        if (opened.compareAndSet(false, true)) {
            release.countDown()
        }
    }
}

internal class GatedFileSystem(
    val base: FakeFileSystem,
    private val target: Path,
    private val readGate: BlockingGate? = null,
    private val writeGate: BlockingGate? = null,
    private val closeGate: BlockingGate? = null,
    private val sourceGate: BlockingGate? = null,
    private val sinkGate: BlockingGate? = null,
    private val additionalWriteGates: Map<Path, BlockingGate> = emptyMap(),
    gateReadsInitially: Boolean = true,
    closeFailures: Int = 0,
) : ForwardingFileSystem(ThreadSafeFakeFileSystem(base)) {
    private val remainingCloseFailures = AtomicInteger(closeFailures)
    private val gateReads = AtomicBoolean(gateReadsInitially)
    val events = ConcurrentLinkedQueue<String>()
    val opens = AtomicInteger()
    val closes = AtomicInteger()
    val flushes = AtomicInteger()
    val activeReads = AtomicInteger()
    val maximumConcurrentReads = AtomicInteger()
    val activeWrites = AtomicInteger()
    val maximumConcurrentWrites = AtomicInteger()

    init {
        require(closeFailures >= 0)
    }

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle {
        val handle = super.openReadWrite(file, mustCreate, mustExist)
        val selectedWriteGate = if (file == target) writeGate else additionalWriteGates[file]
        if (file != target && selectedWriteGate == null) return handle
        return trackedHandle(
            file = file,
            handle = handle,
            readWrite = true,
            selectedWriteGate = selectedWriteGate,
        )
    }

    override fun openReadOnly(file: Path): FileHandle {
        val handle = super.openReadOnly(file)
        if (file != target) return handle
        return trackedHandle(file, handle, readWrite = false, selectedWriteGate = null)
    }

    private fun trackedHandle(
        file: Path,
        handle: FileHandle,
        readWrite: Boolean,
        selectedWriteGate: BlockingGate?,
    ): FileHandle {
        if (file == target) {
            opens.incrementAndGet()
            events += "open"
        }
        return object : FileHandle(readWrite = readWrite) {
            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int {
                val active = activeReads.incrementAndGet()
                maximumConcurrentReads.accumulateAndGet(active, ::maxOf)
                try {
                    if (file == target && gateReads.get()) readGate?.awaitRelease()
                    return handle.read(fileOffset, array, arrayOffset, byteCount)
                } finally {
                    activeReads.decrementAndGet()
                }
            }

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) {
                val active = activeWrites.incrementAndGet()
                maximumConcurrentWrites.accumulateAndGet(active, ::maxOf)
                try {
                    selectedWriteGate?.awaitRelease()
                    handle.write(fileOffset, array, arrayOffset, byteCount)
                } finally {
                    activeWrites.decrementAndGet()
                }
            }

            override fun protectedFlush() {
                if (file == target) flushes.incrementAndGet()
                handle.flush()
            }

            override fun protectedResize(size: Long) = handle.resize(size)

            override fun protectedSize(): Long = handle.size()

            override fun protectedClose() {
                if (file != target) {
                    handle.close()
                } else if (closeGate != null) {
                    events += "close-start"
                    closeGate.awaitRelease()
                    handle.close()
                    closes.incrementAndGet()
                    events += "close-end"
                } else {
                    handle.close()
                    closes.incrementAndGet()
                }
                if (file == target && remainingCloseFailures.getAndUpdate { maxOf(0, it - 1) } > 0) {
                    throw IOException("synthetic gated close failure")
                }
            }
        }
    }

    override fun source(file: Path): Source {
        val source = super.source(file)
        if (file != target || sourceGate == null) return source
        return object : ForwardingSource(source) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                sourceGate.awaitRelease()
                return super.read(sink, byteCount)
            }
        }
    }

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val sink = super.sink(file, mustCreate)
        if (file != target || sinkGate == null) return sink
        return object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                sinkGate.awaitRelease()
                super.write(source, byteCount)
            }
        }
    }

    fun enableReadGate() {
        gateReads.set(true)
    }
}

/**
 * [FakeFileSystem] deliberately keeps its model in ordinary mutable collections and is not safe
 * for simultaneous JVM-thread access. Concurrency is observed by [GatedFileSystem] before calls
 * reach this adapter; this adapter then protects only the fake model itself so its bookkeeping
 * cannot create probabilistic leaks or corrupt test data.
 */
private class ThreadSafeFakeFileSystem(
    private val base: FakeFileSystem,
) : ForwardingFileSystem(base) {
    private val access = ReentrantLock()

    override fun canonicalize(path: Path): Path = access.withLock {
        base.canonicalize(path)
    }

    override fun metadataOrNull(path: Path): FileMetadata? = access.withLock {
        base.metadataOrNull(path)
    }

    override fun list(dir: Path): List<Path> = access.withLock {
        base.list(dir)
    }

    override fun listOrNull(dir: Path): List<Path>? = access.withLock {
        base.listOrNull(dir)
    }

    override fun listRecursively(
        dir: Path,
        followSymlinks: Boolean,
    ): Sequence<Path> = access.withLock {
        base.listRecursively(dir, followSymlinks).toList()
    }.asSequence()

    override fun openReadOnly(file: Path): FileHandle = access.withLock {
        synchronizedHandle(base.openReadOnly(file), readWrite = false)
    }

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle = access.withLock {
        synchronizedHandle(base.openReadWrite(file, mustCreate, mustExist), readWrite = true)
    }

    override fun source(file: Path): Source = access.withLock {
        synchronizedSource(base.source(file))
    }

    override fun sink(file: Path, mustCreate: Boolean): Sink = access.withLock {
        synchronizedSink(base.sink(file, mustCreate))
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink = access.withLock {
        synchronizedSink(base.appendingSink(file, mustExist))
    }

    override fun createDirectory(dir: Path, mustCreate: Boolean) = access.withLock {
        base.createDirectory(dir, mustCreate)
    }

    override fun atomicMove(source: Path, target: Path) = access.withLock {
        base.atomicMove(source, target)
    }

    override fun delete(path: Path, mustExist: Boolean) = access.withLock {
        base.delete(path, mustExist)
    }

    override fun createSymlink(source: Path, target: Path) = access.withLock {
        base.createSymlink(source, target)
    }

    override fun close() = access.withLock {
        base.close()
    }

    private fun synchronizedHandle(
        handle: FileHandle,
        readWrite: Boolean,
    ): FileHandle = object : FileHandle(readWrite) {
        override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ): Int = access.withLock {
            handle.read(fileOffset, array, arrayOffset, byteCount)
        }

        override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ) = access.withLock {
            handle.write(fileOffset, array, arrayOffset, byteCount)
        }

        override fun protectedFlush() = access.withLock {
            handle.flush()
        }

        override fun protectedResize(size: Long) = access.withLock {
            handle.resize(size)
        }

        override fun protectedSize(): Long = access.withLock {
            handle.size()
        }

        override fun protectedClose() = access.withLock {
            handle.close()
        }
    }

    private fun synchronizedSource(source: Source): Source = object : ForwardingSource(source) {
        override fun read(sink: Buffer, byteCount: Long): Long = access.withLock {
            super.read(sink, byteCount)
        }

        override fun close() = access.withLock {
            super.close()
        }
    }

    private fun synchronizedSink(sink: Sink): Sink = object : ForwardingSink(sink) {
        override fun write(source: Buffer, byteCount: Long) = access.withLock {
            super.write(source, byteCount)
        }

        override fun flush() = access.withLock {
            super.flush()
        }

        override fun close() = access.withLock {
            super.close()
        }
    }
}

internal fun threadSafeFakeFileSystem(base: FakeFileSystem): FileSystem = ThreadSafeFakeFileSystem(base)

internal class RecordingWorldDirectoryLock : WorldDirectoryLock {
    private val valid = AtomicBoolean(true)
    val closeAttempts = AtomicInteger()

    override val isValid: Boolean
        get() = valid.get()

    override fun close() {
        closeAttempts.incrementAndGet()
        valid.set(false)
    }
}

internal fun concurrencyChunk(value: Byte): RegionChunk = RegionChunk(
    compression = Compression.NONE,
    payload = RegionChunkPayload.Inline(byteArrayOf(value)),
)

internal fun concurrencyFakeFileSystem(): FakeFileSystem = FakeFileSystem().apply {
    allowReadsWhileWriting = true
    allowWritesWhileWriting = true
}

internal fun concurrencyDocument(value: Int): NbtDocument = NbtDocument(
    NbtCompound(mapOf("value" to NbtInt(value))),
)
