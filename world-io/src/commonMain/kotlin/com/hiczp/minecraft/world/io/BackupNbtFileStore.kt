package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException
import okio.Path
import kotlin.random.Random

/** The two durable candidates in a primary/previous standalone NBT file pair. */
internal enum class BackupNbtCandidate {
    PRIMARY,
    PREVIOUS,
}

/**
 * Common physical mechanism for GZIP NBT stored as a primary file plus an `_old` sibling.
 *
 * This owns candidate selection, streaming, temporary-file replacement, durable backup installation, best-effort
 * corrupt evidence, and previous-file promotion. Callers retain the file-family-specific recovery decision.
 */
internal class BackupNbtFileStore(
    val primary: Path,
    val previous: Path,
    val temporaryDirectory: Path,
    val nbtFileStore: NbtFileStore,
) {
    fun isRegularFile(backupNbtCandidate: BackupNbtCandidate): Boolean =
        nbtFileStore.fileSystem.metadataOrNull(path(backupNbtCandidate))?.isRegularFile == true

    fun readDocument(backupNbtCandidate: BackupNbtCandidate): NbtDocument =
        nbtFileStore.readCompoundDocument(path(backupNbtCandidate), nbtFileStore.nbtFormat::decodeDocumentFromOkio)

    fun <T> read(
        backupNbtCandidate: BackupNbtCandidate,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T = nbtFileStore.readCompoundDocument(path(backupNbtCandidate)) { source ->
        nbtFileStore.nbtFormat.decodeFromOkio(deserializationStrategy, source)
    }

    fun <T> read(backupNbtCandidate: BackupNbtCandidate, block: (BufferedSource) -> T): T =
        nbtFileStore.readCompoundDocument(path(backupNbtCandidate), block)

    fun writeDocument(nbtDocument: NbtDocument) {
        replaceWithTemporary(nbtFileStore.writeSyncedTemporaryDocument(temporaryDirectory, nbtDocument))
    }

    fun <T> write(serializationStrategy: SerializationStrategy<T>, value: T) {
        replaceWithTemporary(nbtFileStore.writeSyncedTemporary(temporaryDirectory, serializationStrategy, value))
    }

    fun write(block: (BufferedSink) -> Unit) {
        replaceWithTemporary(nbtFileStore.writeSyncedTemporary(temporaryDirectory, block = block))
    }

    /** Promotes a successfully parsed previous file without replacing that successful read with an I/O failure. */
    fun promotePreviousBestEffort() {
        if (nbtFileStore.liveReadOnly) return
        val displaced = temporaryDirectory / temporaryFileName(
            random = Random.nextLong().toULong(),
            prefix = "${primary.name}_corrupted_",
        )
        try {
            nbtFileStore.fileSystem.replaceWithoutRollback(
                replacement = previous,
                target = primary,
                displaced = displaced,
            )
        } catch (failure: Throwable) {
            if (failure !is IOException) throw failure
        }
    }

    /** Preserves a durable uniquely named copy of a corrupt primary file without blocking official fallback. */
    fun preservePrimaryBestEffort() {
        if (nbtFileStore.liveReadOnly) return
        try {
            copyPrimaryToUniqueCorruptFile()
        } catch (_: IOException) {
            // Official player loading logs and continues. Additional evidence protection cannot block fallback.
        }
    }

    private fun replaceWithTemporary(temporary: Path) {
        try {
            nbtFileStore.fileSystem.replaceWithBackup(
                temporary = temporary,
                target = primary,
                backup = previous,
            )
        } catch (failure: Throwable) {
            nbtFileStore.fileSystem.deleteIfExistsPreserving(temporary, failure)
            throw failure
        }
    }

    private fun copyPrimaryToUniqueCorruptFile() {
        val fileSystem = nbtFileStore.fileSystem
        var temporaryFileHandle: TemporaryFileHandle? = null
        try {
            val opened = fileSystem.openUniqueTemporaryHandle(
                directory = temporaryDirectory,
                prefix = "${primary.name}_corrupted_",
            )
            temporaryFileHandle = opened
            useResource(opened.fileHandle, { it.close() }) { fileHandle ->
                nbtFileStore.rawFileStore.read(primary) { source ->
                    nbtFileStore.rawFileStore.writeDurably(opened.path, fileHandle) { sink ->
                        source.readAll(sink)
                    }
                }
            }
            temporaryFileHandle = null
        } catch (failure: Throwable) {
            temporaryFileHandle?.let { opened ->
                try {
                    opened.fileHandle.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                fileSystem.deleteIfExistsPreserving(opened.path, failure)
            }
            throw failure
        }
    }

    private fun path(backupNbtCandidate: BackupNbtCandidate): Path = when (backupNbtCandidate) {
        BackupNbtCandidate.PRIMARY -> primary
        BackupNbtCandidate.PREVIOUS -> previous
    }
}
