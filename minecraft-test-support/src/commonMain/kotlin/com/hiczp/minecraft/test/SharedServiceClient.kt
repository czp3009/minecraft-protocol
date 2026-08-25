package com.hiczp.minecraft.test

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps a shared client alive until every operation that leased it finishes. */
internal class SharedServiceClient<C>(
    private val createClient: () -> C,
    private val closeClient: (C) -> Unit,
) {
    private val mutex = Mutex()
    private var current: ClientState<C>? = null

    suspend fun <T> use(
        closeAfter: Boolean = false,
        block: suspend (C) -> T,
    ): T {
        val state = acquire()
        var failure: Throwable? = null
        try {
            return block(state.client)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            release(
                state = state,
                requestClose = closeAfter || failure != null,
                failure = failure,
            )
        }
    }

    private suspend fun acquire(): ClientState<C> = mutex.withLock {
        val state = current ?: ClientState(createClient()).also { current = it }
        check(!state.closeWhenIdle) { "Closing service client remained current" }
        state.activeUses += 1
        state
    }

    private suspend fun release(
        state: ClientState<C>,
        requestClose: Boolean,
        failure: Throwable?,
    ) = withContext(NonCancellable) {
        val close = mutex.withLock {
            check(state.activeUses > 0) { "Service client lease was released twice" }
            if (requestClose) {
                state.closeWhenIdle = true
                if (state.failure == null) state.failure = failure
                if (current === state) current = null
            }
            state.activeUses -= 1
            if (
                state.activeUses == 0 &&
                state.closeWhenIdle &&
                !state.closed
            ) {
                state.closed = true
                CloseClient(state.client, state.failure)
            } else {
                null
            }
        }
        if (close != null) {
            try {
                closeClient(close.client)
            } catch (closeFailure: Throwable) {
                close.failure?.addSuppressed(closeFailure) ?: throw closeFailure
            }
        }
    }
}

private class ClientState<C>(
    val client: C,
    var activeUses: Int = 0,
    var closeWhenIdle: Boolean = false,
    var closed: Boolean = false,
    var failure: Throwable? = null,
)

private data class CloseClient<C>(
    val client: C,
    val failure: Throwable?,
)
