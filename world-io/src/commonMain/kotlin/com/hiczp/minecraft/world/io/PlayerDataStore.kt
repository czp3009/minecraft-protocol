package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import okio.BufferedSink
import okio.BufferedSource

/**
 * Stateless path, fallback, corrupt-copy, and replacement policy for one player's data.
 *
 * A read through a writable physical capability may preserve corrupt evidence; this store does not
 * coordinate that mutation for direct callers. The live facade supplies a read-only capability.
 */
class PlayerDataStore(
    val minecraftWorldPaths: MinecraftWorldPaths,
    val nbtFileStore: NbtFileStore = NbtFileStore(),
) {
    fun readDocument(playerUuid: String): NbtDocument? = readWithRecovery(playerUuid) { backupNbtFileStore, candidate ->
        backupNbtFileStore.readDocument(candidate)
    }

    fun <T> read(playerUuid: String, deserializationStrategy: DeserializationStrategy<T>): T? =
        readWithRecovery(playerUuid) { backupNbtFileStore, candidate ->
            backupNbtFileStore.read(candidate, deserializationStrategy)
        }

    /**
     * Returns the same final result as the official player loader: no usable candidate is `null`.
     * Mutable access makes a best-effort durable copy of a corrupt current file before falling back,
     * while the previous file remains only the official read fallback.
     */
    fun <T> read(playerUuid: String, block: (BufferedSource) -> T): T? =
        readWithRecovery(playerUuid) { backupNbtFileStore, candidate -> backupNbtFileStore.read(candidate, block) }

    private fun <T> readWithRecovery(
        playerUuid: String,
        readCandidate: (BackupNbtFileStore, BackupNbtCandidate) -> T,
    ): T? {
        val backupNbtFileStore = backupNbtFileStore(playerUuid)
        try {
            return readCandidate(backupNbtFileStore, BackupNbtCandidate.PRIMARY)
        } catch (failure: Throwable) {
            if (!failure.isRecoverableNbtReadFailure()) throw failure
            if (backupNbtFileStore.isRegularFile(BackupNbtCandidate.PRIMARY)) {
                backupNbtFileStore.preservePrimaryBestEffort()
            }
        }

        return try {
            readCandidate(backupNbtFileStore, BackupNbtCandidate.PREVIOUS)
        } catch (failure: Throwable) {
            if (!failure.isRecoverableNbtReadFailure()) throw failure
            null
        }
    }

    internal fun readDocumentForSharedAccess(playerUuid: String): CoordinatedRead<NbtDocument?> =
        probeSharedCandidate(playerUuid) { backupNbtFileStore, candidate ->
            backupNbtFileStore.readDocument(candidate)
        }

    internal fun <T> readForSharedAccess(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): CoordinatedRead<T?> = probeSharedCandidate(playerUuid) { backupNbtFileStore, candidate ->
        backupNbtFileStore.read(candidate, deserializationStrategy)
    }

    internal fun <T> readForSharedAccess(
        playerUuid: String,
        block: (BufferedSource) -> T,
    ): CoordinatedRead<T?> = probeSharedCandidate(playerUuid) { backupNbtFileStore, candidate ->
        backupNbtFileStore.read(candidate, block)
    }

    private fun <T> probeSharedCandidate(
        playerUuid: String,
        readCandidate: (BackupNbtFileStore, BackupNbtCandidate) -> T,
    ): CoordinatedRead<T?> {
        val backupNbtFileStore = backupNbtFileStore(playerUuid)
        try {
            return CoordinatedRead.Complete(readCandidate(backupNbtFileStore, BackupNbtCandidate.PRIMARY))
        } catch (failure: Throwable) {
            if (!failure.isRecoverableNbtReadFailure()) throw failure
            if (backupNbtFileStore.isRegularFile(BackupNbtCandidate.PRIMARY)) {
                return CoordinatedRead.RequiresExclusive
            }
        }
        return try {
            CoordinatedRead.Complete(readCandidate(backupNbtFileStore, BackupNbtCandidate.PREVIOUS))
        } catch (failure: Throwable) {
            if (!failure.isRecoverableNbtReadFailure()) throw failure
            CoordinatedRead.Complete(null)
        }
    }

    fun writeDocument(playerUuid: String, nbtDocument: NbtDocument) =
        backupNbtFileStore(playerUuid).writeDocument(nbtDocument)

    fun <T> write(
        playerUuid: String,
        serializationStrategy: SerializationStrategy<T>,
        value: T,
    ) = backupNbtFileStore(playerUuid).write(serializationStrategy, value)

    fun write(playerUuid: String, block: (BufferedSink) -> Unit) = backupNbtFileStore(playerUuid).write(block)

    private fun backupNbtFileStore(playerUuid: String): BackupNbtFileStore {
        val target = minecraftWorldPaths.playerData(playerUuid)
        return BackupNbtFileStore(
            primary = target,
            previous = minecraftWorldPaths.previousPlayerData(playerUuid),
            temporaryDirectory = checkNotNull(target.parent),
            nbtFileStore = nbtFileStore,
        )
    }
}
