package com.hiczp.minecraft.world.io

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps cancellation primary without losing an earlier operation or cleanup failure. Repeated
 * cancellation signals are the same outcome and are not accumulated as suppressed noise.
 */
internal fun combineFailures(
    current: Throwable?,
    caught: Throwable,
): Throwable {
    if (current == null) return caught
    if (current === caught) return current
    if (caught is CancellationException && current !is CancellationException) {
        caught.addSuppressed(current)
        return caught
    }
    if (current is CancellationException && caught is CancellationException) return current
    current.addSuppressed(caught)
    return current
}

internal fun closeAllPreserving(
    failure: Throwable?,
    vararg closes: () -> Unit,
) {
    var result = failure
    closes.forEach { close ->
        try {
            close()
        } catch (caught: Throwable) {
            result = combineFailures(result, caught)
        }
    }
    if (result != null && (failure == null || result !== failure)) throw result
}

/** Kotlin's standard `use` keeps an ordinary block failure primary when close signals cancellation. */
internal inline fun <T, R> useResource(
    resource: T,
    close: (T) -> Unit,
    block: (T) -> R,
): R {
    var failure: Throwable? = null
    try {
        return block(resource)
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        try {
            close(resource)
        } catch (caught: Throwable) {
            val result = combineFailures(failure, caught)
            if (result !== failure) throw result
        }
    }
}

/** Suspend counterpart of [useResource] for resources whose close waits for coroutine-owned work. */
internal suspend fun <T, R> useSuspendingResource(
    resource: T,
    close: suspend (T) -> Unit,
    block: suspend (T) -> R,
): R = withCleanup(
    cleanup = {
        close(resource)
        null
    },
) {
    block(resource)
}

/**
 * Runs state/resource cleanup to completion and combines its reported failure with [failure].
 * Returning the failure keeps coroutine stack recovery from copying it across [NonCancellable].
 */
internal suspend inline fun collectCleanupFailure(
    failure: Throwable?,
    crossinline cleanup: suspend () -> Throwable?,
): Throwable? {
    val cleanupFailure = withContext(NonCancellable) {
        try {
            cleanup()
        } catch (caught: Throwable) {
            caught
        }
    }
    return cleanupFailure?.let { combineFailures(failure, it) } ?: failure
}

/** Executes [cleanup] non-cancellably after [block], then restores the caller's cancellation. */
internal suspend inline fun <T> withCleanup(
    crossinline cleanup: suspend () -> Throwable?,
    crossinline block: suspend () -> T,
): T {
    var failure: Throwable? = null
    try {
        return block()
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        failure = collectCleanupFailure(failure, cleanup)
        throwFailureOrCancellation(failure)
    }
}

/** Rechecks the caller's Job only after its non-cancellable state and resource cleanup is done. */
internal suspend fun throwFailureOrCancellation(failure: Throwable?) {
    var result = failure
    try {
        currentCoroutineContext().ensureActive()
    } catch (cancellation: CancellationException) {
        result = combineFailures(result, cancellation)
    }
    result?.let { throw it }
}

internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
