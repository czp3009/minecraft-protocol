package com.hiczp.minecraft.world.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Writer-preferring suspend coordination for one logical world file group.
 *
 * Readers hold only a shared admission count while their block runs; they do not hold [state] and
 * therefore do not serialize each other. Okio `FileHandle` owns concurrent random-access read
 * support. This coordinator adds the boundary that prevents a writer or final close from
 * overlapping those reads. It is not a fair or FIFO lock: no relative admission order is promised
 * among waiting writers or among readers that become eligible together.
 */
internal class LogicalFileAccess {
    private val state = Mutex()
    private var activeReaders = 0
    private var writerActive = false
    private var waitingWriters = 0
    private var changed = CompletableDeferred<Unit>()

    suspend fun <T> read(block: suspend () -> T): T {
        var acquired = false
        var failure: Throwable? = null
        try {
            while (!acquired) {
                val signal = state.withLock {
                    if (!writerActive && waitingWriters == 0) {
                        activeReaders++
                        acquired = true
                        null
                    } else {
                        changed
                    }
                }
                signal?.await()
            }
            throwFailureOrCancellation(null)
            return block()
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            if (acquired) {
                releaseRead(failure)
            }
        }
    }

    suspend fun <T> write(block: suspend () -> T): T {
        var registered = false
        var acquired = false
        var failure: Throwable? = null
        try {
            while (!acquired) {
                val signal = state.withLock {
                    if (!registered) {
                        waitingWriters++
                        registered = true
                    }
                    if (!writerActive && activeReaders == 0) {
                        waitingWriters--
                        registered = false
                        writerActive = true
                        acquired = true
                        null
                    } else {
                        changed
                    }
                }
                signal?.await()
            }
            throwFailureOrCancellation(null)
            return block()
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            when {
                acquired -> releaseWrite(failure)
                registered -> removeWaitingWriter(failure)
            }
        }
    }

    private suspend fun releaseRead(operationFailure: Throwable?) {
        finish(operationFailure) {
            check(activeReaders > 0) { "Logical file read access is not held" }
            activeReaders--
            if (activeReaders == 0) rotateSignal() else null
        }
    }

    private suspend fun releaseWrite(operationFailure: Throwable?) {
        finish(operationFailure) {
            check(writerActive) { "Logical file write access is not held" }
            writerActive = false
            rotateSignal()
        }
    }

    private suspend fun removeWaitingWriter(operationFailure: Throwable?) {
        finish(operationFailure) {
            check(waitingWriters > 0) { "Logical file writer is not waiting" }
            waitingWriters--
            rotateSignal()
        }
    }

    private suspend fun finish(
        operationFailure: Throwable?,
        update: () -> CompletableDeferred<Unit>?,
    ) {
        val failure = collectCleanupFailure(operationFailure) {
            state.withLock { update() }?.complete(Unit)
            null
        }
        throwFailureOrCancellation(failure)
    }

    private fun rotateSignal(): CompletableDeferred<Unit> {
        val previous = changed
        changed = CompletableDeferred()
        return previous
    }
}
