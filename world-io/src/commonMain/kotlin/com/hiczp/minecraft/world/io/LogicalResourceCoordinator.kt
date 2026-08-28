package com.hiczp.minecraft.world.io

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ephemeral writer-preferring admissions keyed by complete logical resource identity. */
internal class LogicalResourceCoordinator<Key : Any> {
    private val state = Mutex()
    private val entries = mutableMapOf<Key, LogicalResourceEntry<Key>>()

    suspend fun <T> read(key: Key, block: suspend () -> T): T = withEntry(key) { logicalResourceEntry ->
        logicalResourceEntry.logicalFileAccess.read(block)
    }

    suspend fun <T> write(key: Key, block: suspend () -> T): T = withEntry(key) { logicalResourceEntry ->
        logicalResourceEntry.logicalFileAccess.write(block)
    }

    suspend fun <T> withEntry(
        key: Key,
        block: suspend (LogicalResourceEntry<Key>) -> T,
    ): T {
        val logicalResourceEntry = acquire(key)
        return withCleanup(cleanup = { release(logicalResourceEntry) }) {
            block(logicalResourceEntry)
        }
    }

    internal suspend fun activeEntryCount(): Int = state.withLock { entries.size }

    internal suspend fun activeUsers(): Int = state.withLock { entries.values.sumOf { it.users } }

    private suspend fun acquire(key: Key): LogicalResourceEntry<Key> = state.withLock {
        entries.getOrPut(key) { LogicalResourceEntry(key) }.also { logicalResourceEntry ->
            logicalResourceEntry.users++
        }
    }

    private suspend fun release(logicalResourceEntry: LogicalResourceEntry<Key>): Throwable? {
        state.withLock {
            check(logicalResourceEntry.users > 0) { "Logical resource is not in use: ${logicalResourceEntry.key}" }
            logicalResourceEntry.users--
            if (logicalResourceEntry.users == 0 && entries[logicalResourceEntry.key] === logicalResourceEntry) {
                entries.remove(logicalResourceEntry.key)
            }
        }
        return null
    }
}

internal class LogicalResourceEntry<Key : Any>(val key: Key) {
    val logicalFileAccess = LogicalFileAccess()
    var users = 0
}
