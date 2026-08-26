package com.hiczp.minecraft.world.io

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LogicalFileAccessTest {
    @Test
    fun readersShareAccess() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val release = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                firstEntered.complete(Unit)
                release.await()
                1
            }
        }
        firstEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                secondEntered.complete(Unit)
                release.await()
                2
            }
        }

        secondEntered.await()
        release.complete(Unit)
        assertEquals(1, first.await())
        assertEquals(2, second.await())
    }

    @Test
    fun writerWaitsForExistingReader() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val releaseReader = CompletableDeferred<Unit>()
        val readerEntered = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()
        val reader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                readerEntered.complete(Unit)
                releaseReader.await()
            }
        }
        readerEntered.await()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                writerEntered.complete(Unit)
            }
        }

        assertFalse(writerEntered.isCompleted)
        releaseReader.complete(Unit)
        writerEntered.await()
        reader.await()
        writer.await()
    }

    @Test
    fun writerWaitsForEveryExistingReader() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                secondEntered.complete(Unit)
                releaseSecond.await()
            }
        }
        firstEntered.await()
        secondEntered.await()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                writerEntered.complete(Unit)
            }
        }

        releaseFirst.complete(Unit)
        first.await()
        assertFalse(writerEntered.isCompleted)
        releaseSecond.complete(Unit)
        second.await()
        writerEntered.await()
        writer.await()
    }

    @Test
    fun readersResumeTogetherAfterWriter() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val releaseWriter = CompletableDeferred<Unit>()
        val releaseReaders = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()
        val firstReaderEntered = CompletableDeferred<Unit>()
        val secondReaderEntered = CompletableDeferred<Unit>()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                writerEntered.complete(Unit)
                releaseWriter.await()
            }
        }
        writerEntered.await()
        val firstReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                firstReaderEntered.complete(Unit)
                releaseReaders.await()
            }
        }
        val secondReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                secondReaderEntered.complete(Unit)
                releaseReaders.await()
            }
        }

        assertFalse(firstReaderEntered.isCompleted)
        assertFalse(secondReaderEntered.isCompleted)
        releaseWriter.complete(Unit)
        firstReaderEntered.await()
        secondReaderEntered.await()
        releaseReaders.complete(Unit)
        writer.await()
        firstReader.await()
        secondReader.await()
    }

    @Test
    fun writersAreExclusive() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                secondEntered.complete(Unit)
            }
        }

        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        secondEntered.await()
        first.await()
        second.await()
    }

    @Test
    fun waitingWriterPrecedesLaterReader() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val releaseFirstReader = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val firstReaderEntered = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()
        val laterReaderEntered = CompletableDeferred<Unit>()
        val firstReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                firstReaderEntered.complete(Unit)
                releaseFirstReader.await()
            }
        }
        firstReaderEntered.await()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                writerEntered.complete(Unit)
                releaseWriter.await()
            }
        }
        val laterReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                laterReaderEntered.complete(Unit)
            }
        }

        assertFalse(writerEntered.isCompleted)
        assertFalse(laterReaderEntered.isCompleted)
        releaseFirstReader.complete(Unit)
        writerEntered.await()
        assertFalse(laterReaderEntered.isCompleted)
        releaseWriter.complete(Unit)
        laterReaderEntered.await()
        firstReader.await()
        writer.await()
        laterReader.await()
    }

    @Test
    fun everyQueuedWriterPrecedesLaterReaders() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val releaseInitialReader = CompletableDeferred<Unit>()
        val releaseWriters = CompletableDeferred<Unit>()
        val initialReaderEntered = CompletableDeferred<Unit>()
        val anyWriterEntered = CompletableDeferred<Unit>()
        val firstWriterEntered = CompletableDeferred<Unit>()
        val secondWriterEntered = CompletableDeferred<Unit>()
        val laterReaderEntered = CompletableDeferred<Unit>()
        val initialReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                initialReaderEntered.complete(Unit)
                releaseInitialReader.await()
            }
        }
        initialReaderEntered.await()
        val firstWriter = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                anyWriterEntered.complete(Unit)
                firstWriterEntered.complete(Unit)
                releaseWriters.await()
            }
        }
        val secondWriter = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                anyWriterEntered.complete(Unit)
                secondWriterEntered.complete(Unit)
                releaseWriters.await()
            }
        }
        val laterReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                laterReaderEntered.complete(Unit)
            }
        }

        releaseInitialReader.complete(Unit)
        anyWriterEntered.await()
        assertFalse(laterReaderEntered.isCompleted)
        releaseWriters.complete(Unit)
        firstWriterEntered.await()
        secondWriterEntered.await()
        laterReaderEntered.await()
        initialReader.await()
        firstWriter.await()
        secondWriter.await()
        laterReader.await()
    }

    @Test
    fun cancellingWaitingWriterUnblocksReaders() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val releaseReaders = CompletableDeferred<Unit>()
        val firstReaderEntered = CompletableDeferred<Unit>()
        val laterReaderEntered = CompletableDeferred<Unit>()
        val firstReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                firstReaderEntered.complete(Unit)
                releaseReaders.await()
            }
        }
        firstReaderEntered.await()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write { Unit }
        }
        val laterReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                laterReaderEntered.complete(Unit)
                releaseReaders.await()
            }
        }

        assertFalse(laterReaderEntered.isCompleted)
        writer.cancelAndJoin()
        laterReaderEntered.await()
        releaseReaders.complete(Unit)
        firstReader.await()
        laterReader.await()
    }

    @Test
    fun cancellingOneWaitingWriterDoesNotBypassAnotherWriter() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val releaseInitialReader = CompletableDeferred<Unit>()
        val releaseRemainingWriter = CompletableDeferred<Unit>()
        val initialReaderEntered = CompletableDeferred<Unit>()
        val remainingWriterEntered = CompletableDeferred<Unit>()
        val laterReaderEntered = CompletableDeferred<Unit>()
        val initialReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                initialReaderEntered.complete(Unit)
                releaseInitialReader.await()
            }
        }
        initialReaderEntered.await()
        val remainingWriter = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                remainingWriterEntered.complete(Unit)
                releaseRemainingWriter.await()
            }
        }
        val cancelledWriter = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write { Unit }
        }
        val laterReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                laterReaderEntered.complete(Unit)
            }
        }

        cancelledWriter.cancelAndJoin()
        assertFalse(laterReaderEntered.isCompleted)
        releaseInitialReader.complete(Unit)
        remainingWriterEntered.await()
        assertFalse(laterReaderEntered.isCompleted)
        releaseRemainingWriter.complete(Unit)
        laterReaderEntered.await()
        initialReader.await()
        remainingWriter.await()
        laterReader.await()
    }

    @Test
    fun cancellingAWaitingReaderDoesNotDelayLaterReaders() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val releaseWriter = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()
        val cancelledReaderEntered = CompletableDeferred<Unit>()
        val remainingReaderEntered = CompletableDeferred<Unit>()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                writerEntered.complete(Unit)
                releaseWriter.await()
            }
        }
        writerEntered.await()
        val cancelledReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                cancelledReaderEntered.complete(Unit)
            }
        }
        val remainingReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                remainingReaderEntered.complete(Unit)
            }
        }

        assertFalse(cancelledReaderEntered.isCompleted)
        assertFalse(remainingReaderEntered.isCompleted)
        cancelledReader.cancelAndJoin()
        releaseWriter.complete(Unit)
        remainingReaderEntered.await()
        writer.await()
        remainingReader.await()
        assertFalse(cancelledReaderEntered.isCompleted)
    }

    @Test
    fun readerFailureReleasesWaitingWriter() = runTest {
        supervisorScope {
            val logicalFileAccess = LogicalFileAccess()
            val releaseReader = CompletableDeferred<Unit>()
            val readerEntered = CompletableDeferred<Unit>()
            val writerEntered = CompletableDeferred<Unit>()
            val reader = async(start = CoroutineStart.UNDISPATCHED) {
                logicalFileAccess.read {
                    readerEntered.complete(Unit)
                    releaseReader.await()
                    error("synthetic read failure")
                }
            }
            readerEntered.await()
            val writer = async(start = CoroutineStart.UNDISPATCHED) {
                logicalFileAccess.write {
                    writerEntered.complete(Unit)
                }
            }

            releaseReader.complete(Unit)
            writerEntered.await()
            assertFailsWith<IllegalStateException> { reader.await() }
            writer.await()
        }
    }

    @Test
    fun writerFailureReleasesQueuedReadersTogether() = runTest {
        supervisorScope {
            val logicalFileAccess = LogicalFileAccess()
            val releaseWriter = CompletableDeferred<Unit>()
            val writerEntered = CompletableDeferred<Unit>()
            val firstReaderEntered = CompletableDeferred<Unit>()
            val secondReaderEntered = CompletableDeferred<Unit>()
            val writer = async(start = CoroutineStart.UNDISPATCHED) {
                logicalFileAccess.write {
                    writerEntered.complete(Unit)
                    releaseWriter.await()
                    error("synthetic write failure")
                }
            }
            writerEntered.await()
            val firstReader = async(start = CoroutineStart.UNDISPATCHED) {
                logicalFileAccess.read {
                    firstReaderEntered.complete(Unit)
                }
            }
            val secondReader = async(start = CoroutineStart.UNDISPATCHED) {
                logicalFileAccess.read {
                    secondReaderEntered.complete(Unit)
                }
            }

            releaseWriter.complete(Unit)
            firstReaderEntered.await()
            secondReaderEntered.await()
            assertFailsWith<IllegalStateException> { writer.await() }
            firstReader.await()
            secondReader.await()
        }
    }

    @Test
    fun cancellingActiveReaderUnblocksWriter() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val readerEntered = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()
        val reader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                readerEntered.complete(Unit)
                awaitCancellation()
            }
        }
        readerEntered.await()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                writerEntered.complete(Unit)
            }
        }

        assertFalse(writerEntered.isCompleted)
        reader.cancelAndJoin()
        writerEntered.await()
        writer.await()
    }

    @Test
    fun cancellingActiveWriterReleasesQueuedReadersTogether() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val writerEntered = CompletableDeferred<Unit>()
        val firstReaderEntered = CompletableDeferred<Unit>()
        val secondReaderEntered = CompletableDeferred<Unit>()
        val releaseReaders = CompletableDeferred<Unit>()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                writerEntered.complete(Unit)
                awaitCancellation()
            }
        }
        writerEntered.await()
        val firstReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                firstReaderEntered.complete(Unit)
                releaseReaders.await()
            }
        }
        val secondReader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                secondReaderEntered.complete(Unit)
                releaseReaders.await()
            }
        }

        assertFalse(firstReaderEntered.isCompleted)
        assertFalse(secondReaderEntered.isCompleted)
        writer.cancelAndJoin()
        firstReaderEntered.await()
        secondReaderEntered.await()
        releaseReaders.complete(Unit)
        firstReader.await()
        secondReader.await()
    }

    @Test
    fun cancellationRequestedInsideReaderIsObservedAfterReadStateIsReleased() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val continued = CompletableDeferred<Unit>()
        val cancellationException = CancellationException("reader cancelled after synchronous work")

        val reader = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.read {
                currentCoroutineContext().cancel(cancellationException)
                1
            }
            continued.complete(Unit)
        }
        val failure = assertFailsWith<CancellationException> { reader.await() }

        assertEquals(cancellationException.message, failure.message)
        assertFalse(continued.isCompleted)
        assertEquals(2, logicalFileAccess.write { 2 })
    }

    @Test
    fun cancellationRequestedInsideWriterIsObservedAfterWriteStateIsReleased() = runTest {
        val logicalFileAccess = LogicalFileAccess()
        val continued = CompletableDeferred<Unit>()
        val cancellationException = CancellationException("writer cancelled after synchronous work")

        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            logicalFileAccess.write {
                currentCoroutineContext().cancel(cancellationException)
                1
            }
            continued.complete(Unit)
        }
        val failure = assertFailsWith<CancellationException> { writer.await() }

        assertEquals(cancellationException.message, failure.message)
        assertFalse(continued.isCompleted)
        assertEquals(2, logicalFileAccess.read { 2 })
    }
}
