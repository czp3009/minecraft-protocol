package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.serialization.NbtBinaryFormatException
import com.hiczp.minecraft.world.format.CompressionFormatException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException

/**
 * Stateless path, fallback/promotion, and replacement policy for `level.dat`.
 *
 * A read through a writable physical capability may promote `level.dat_old`; this store does not
 * coordinate that mutation for direct callers. The live facade supplies a read-only capability.
 */
class LevelDataStore(
    val minecraftWorldPaths: MinecraftWorldPaths,
    val nbtFileStore: NbtFileStore = NbtFileStore(),
) {
    private val backupNbtFileStore = BackupNbtFileStore(
        primary = minecraftWorldPaths.levelData,
        previous = minecraftWorldPaths.previousLevelData,
        temporaryDirectory = minecraftWorldPaths.root,
        nbtFileStore = nbtFileStore,
    )

    fun readDocument(): NbtDocument = readWithRecovery(backupNbtFileStore::readDocument)

    fun <T> read(deserializationStrategy: DeserializationStrategy<T>): T =
        readWithRecovery { backupNbtCandidate -> backupNbtFileStore.read(backupNbtCandidate, deserializationStrategy) }

    inline fun <reified T> read(): T = read(nbtFileStore.nbtFormat.serializersModule.serializer())

    fun <T> read(block: (BufferedSource) -> T): T =
        readWithRecovery { backupNbtCandidate -> backupNbtFileStore.read(backupNbtCandidate, block) }

    private fun <T> readWithRecovery(readCandidate: (BackupNbtCandidate) -> T): T {
        val primaryFailure = try {
            return readCandidate(BackupNbtCandidate.PRIMARY)
        } catch (failure: Throwable) {
            if (!failure.isRecoverableNbtReadFailure()) throw failure
            failure
        }
        val fallback = try {
            readCandidate(BackupNbtCandidate.PREVIOUS)
        } catch (fallbackFailure: Throwable) {
            if (!fallbackFailure.isRecoverableNbtReadFailure()) throw fallbackFailure
            if (fallbackFailure !== primaryFailure) fallbackFailure.addSuppressed(primaryFailure)
            throw fallbackFailure
        }
        backupNbtFileStore.promotePreviousBestEffort()
        return fallback
    }

    internal fun readDocumentForSharedAccess(): CoordinatedRead<NbtDocument> =
        probeSharedCandidate(backupNbtFileStore::readDocument)

    internal fun <T> readForSharedAccess(
        deserializationStrategy: DeserializationStrategy<T>,
    ): CoordinatedRead<T> = probeSharedCandidate { backupNbtCandidate ->
        backupNbtFileStore.read(backupNbtCandidate, deserializationStrategy)
    }

    internal inline fun <reified T> readForSharedAccess(): CoordinatedRead<T> =
        readForSharedAccess(nbtFileStore.nbtFormat.serializersModule.serializer())

    internal fun <T> readForSharedAccess(block: (BufferedSource) -> T): CoordinatedRead<T> =
        probeSharedCandidate { backupNbtCandidate -> backupNbtFileStore.read(backupNbtCandidate, block) }

    private fun <T> probeSharedCandidate(
        readCandidate: (BackupNbtCandidate) -> T,
    ): CoordinatedRead<T> = try {
        CoordinatedRead.Complete(readCandidate(BackupNbtCandidate.PRIMARY))
    } catch (failure: Throwable) {
        if (!failure.isRecoverableNbtReadFailure()) throw failure
        CoordinatedRead.RequiresExclusive
    }

    fun writeDocument(nbtDocument: NbtDocument) = backupNbtFileStore.writeDocument(nbtDocument)

    fun <T> write(value: T, serializationStrategy: SerializationStrategy<T>) =
        backupNbtFileStore.write(value, serializationStrategy)

    inline fun <reified T> write(value: T) =
        write(value, nbtFileStore.nbtFormat.serializersModule.serializer())

    fun write(block: (BufferedSink) -> Unit) = backupNbtFileStore.write(block)
}

internal fun Throwable.isRecoverableNbtReadFailure(): Boolean =
    this is IOException || this is NbtBinaryFormatException || this is CompressionFormatException
