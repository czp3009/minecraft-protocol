package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.CompressedNbtFormat
import com.hiczp.minecraft.world.format.RegionPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import kotlin.coroutines.cancellation.CancellationException

/** Ephemeral mutable Region-directory owners used only by the leased world facade. */
internal class CoordinatedRegionRegistry(
    private val minecraftWorldPaths: MinecraftWorldPaths,
    private val worldFileAccess: WorldFileAccess,
    private val chunkNbtFormat: CompressedNbtFormat,
    private val regionStorageConfiguration: RegionStorageConfiguration,
) {
    private val bookkeeping = Mutex()
    private val entries = mutableMapOf<RegionDirectoryKey, RegionDirectoryEntry>()

    suspend fun openRegion(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
        regionPosition: RegionPosition,
        afterRelease: suspend (Throwable?) -> Throwable?,
    ): RegionHandle {
        val regionDirectoryEntry = acquire(regionStorageDirectory, dimensionDirectory)
        return try {
            regionDirectoryEntry.coordinatedRegionStore.openRegion(regionPosition) { regionFailure ->
                var failure = regionFailure
                val directoryFailure = release(regionDirectoryEntry)
                if (directoryFailure != null) failure = combineFailures(failure, directoryFailure)
                try {
                    afterRelease(failure)
                } catch (caught: Throwable) {
                    combineFailures(failure, caught)
                }
            }
        } catch (failure: Throwable) {
            val cleanupFailure = release(regionDirectoryEntry)
            throw cleanupFailure?.let { caught -> combineFailures(failure, caught) } ?: failure
        }
    }

    suspend fun flush() {
        val pinned = bookkeeping.withLock {
            entries.values.filterNot { it.closing }.onEach { regionDirectoryEntry ->
                regionDirectoryEntry.users++
            }
        }
        var failure: Throwable? = null
        for (regionDirectoryEntry in pinned) {
            val entryFailure = try {
                regionDirectoryEntry.coordinatedRegionStore.flush()
                null
            } catch (caught: Throwable) {
                caught
            }
            if (entryFailure != null) {
                failure = combineFailures(failure, entryFailure)
                if (failure is CancellationException) break
            }
        }
        withContext(NonCancellable) {
            pinned.forEach { regionDirectoryEntry ->
                release(regionDirectoryEntry)?.let { caught -> failure = combineFailures(failure, caught) }
            }
        }
        throwFailureOrCancellation(failure)
    }

    internal suspend fun activeDirectoryCount(): Int = bookkeeping.withLock { entries.size }

    internal suspend fun activeDirectoryUsers(): Int = bookkeeping.withLock {
        entries.values.sumOf { regionDirectoryEntry -> regionDirectoryEntry.users }
    }

    private suspend fun acquire(
        regionStorageDirectory: RegionStorageDirectory,
        dimensionDirectory: DimensionDirectory,
    ): RegionDirectoryEntry {
        val regionDirectoryKey = RegionDirectoryKey(
            minecraftWorldPaths.regionDirectory(regionStorageDirectory, dimensionDirectory),
        )
        while (true) {
            val closing = bookkeeping.withLock {
                val existing = entries[regionDirectoryKey]
                if (existing == null) {
                    val created = RegionDirectoryEntry(
                        regionDirectoryKey,
                        CoordinatedRegionStore(
                            directory = regionDirectoryKey.directory,
                            worldFileAccess = worldFileAccess,
                            chunkNbtFormat = chunkNbtFormat,
                            regionStorageConfiguration = regionStorageConfiguration,
                        ),
                    )
                    entries[regionDirectoryKey] = created
                    return created
                }
                if (!existing.closing) {
                    existing.users++
                    return existing
                }
                existing.closed
            }
            closing.await()
        }
    }

    private suspend fun release(regionDirectoryEntry: RegionDirectoryEntry): Throwable? {
        val shouldClose = bookkeeping.withLock {
            check(regionDirectoryEntry.users > 0) {
                "Region directory is not in use: ${regionDirectoryEntry.regionDirectoryKey}"
            }
            regionDirectoryEntry.users--
            if (regionDirectoryEntry.users > 0) return@withLock false
            regionDirectoryEntry.closing = true
            true
        }
        if (!shouldClose) return null

        val closeFailure = try {
            regionDirectoryEntry.coordinatedRegionStore.close()
            null
        } catch (failure: Throwable) {
            failure
        }
        bookkeeping.withLock {
            if (entries[regionDirectoryEntry.regionDirectoryKey] === regionDirectoryEntry) {
                entries.remove(regionDirectoryEntry.regionDirectoryKey)
            }
            regionDirectoryEntry.closed.complete(Unit)
        }
        return closeFailure
    }
}

private data class RegionDirectoryKey(
    val directory: Path,
)

private class RegionDirectoryEntry(
    val regionDirectoryKey: RegionDirectoryKey,
    val coordinatedRegionStore: CoordinatedRegionStore,
) {
    var users = 1
    var closing = false
    val closed = CompletableDeferred<Unit>()
}
