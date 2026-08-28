package com.hiczp.minecraft.world.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** World-close barrier shared by semantic, direct-file, data-pack, and Region operations. */
internal class WorldOperationLifecycle(
    private val minecraftWorldPaths: MinecraftWorldPaths,
    private val worldDirectoryLock: WorldDirectoryLock?,
) {
    private val state = Mutex()
    private var sealed = false
    private var users = 0
    private var drain = CompletableDeferred<Unit>()
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeFailure: Throwable? = null
    private val closeBarrierFailures = mutableListOf<Throwable>()

    suspend fun acquire(): WorldOperationPin = state.withLock {
        checkOpenAndValid()
        users++
        WorldOperationPin(this)
    }

    suspend fun <T> withOperation(block: suspend () -> T): T {
        val worldOperationPin = acquire()
        return withCleanup(cleanup = { worldOperationPin.release() }, block = block)
    }

    suspend fun close() {
        val completion: CompletableDeferred<Unit>
        val owner: Boolean
        state.withLock {
            val existing = closeCompletion
            if (existing != null) {
                completion = existing
                owner = false
            } else {
                sealed = true
                completion = CompletableDeferred()
                closeCompletion = completion
                if (users == 0) drain.complete(Unit)
                owner = true
            }
        }
        if (!owner) {
            if (!completion.isCompleted) completion.await()
            closeFailure?.let { throw it }
            return
        }

        val failure = withContext(NonCancellable) {
            drain.await()
            var result = state.withLock {
                closeBarrierFailures.reduceOrNull(::combineFailures)
            }
            try {
                worldDirectoryLock?.close()
            } catch (caught: Throwable) {
                result = combineFailures(result, caught)
            }
            state.withLock {
                closeFailure = result
                closeBarrierFailures.clear()
            }
            completion.complete(Unit)
            result
        }
        throwFailureOrCancellation(failure)
    }

    internal suspend fun release(
        worldOperationPin: WorldOperationPin,
        cleanupFailure: Throwable?,
    ): Throwable? {
        state.withLock {
            check(worldOperationPin.owner === this && !worldOperationPin.released)
            worldOperationPin.released = true
            check(users > 0) { "World operation count is already zero: ${minecraftWorldPaths.root}" }
            users--
            if (sealed && cleanupFailure != null) closeBarrierFailures += cleanupFailure
            if (sealed && users == 0) drain.complete(Unit)
        }
        return cleanupFailure
    }

    internal suspend fun activeUsers(): Int = state.withLock { users }

    private fun checkOpenAndValid() {
        check(!sealed) { "World access is closed: ${minecraftWorldPaths.root}" }
        val worldDirectoryLock = worldDirectoryLock ?: return
        if (!worldDirectoryLock.isValid) {
            throw WorldLockException("World directory lock is no longer valid: ${minecraftWorldPaths.root}")
        }
    }
}

internal class WorldOperationPin internal constructor(
    internal val owner: WorldOperationLifecycle,
) {
    internal var released = false

    suspend fun release(cleanupFailure: Throwable? = null): Throwable? = owner.release(this, cleanupFailure)
}
