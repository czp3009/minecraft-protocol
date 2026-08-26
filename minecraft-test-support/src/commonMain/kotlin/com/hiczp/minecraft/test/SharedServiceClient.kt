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
        val clientState = acquire()
        var failure: Throwable? = null
        try {
            return block(clientState.client)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            release(
                clientState = clientState,
                requestClose = closeAfter || failure != null,
                failure = failure,
            )
        }
    }

    private suspend fun acquire(): ClientState<C> = mutex.withLock {
        val clientState = current ?: ClientState(createClient()).also { current = it }
        check(!clientState.closeWhenIdle) { "Closing service client remained current" }
        clientState.activeUses += 1
        clientState
    }

    private suspend fun release(
        clientState: ClientState<C>,
        requestClose: Boolean,
        failure: Throwable?,
    ) = withContext(NonCancellable) {
        val closeClient = mutex.withLock {
            check(clientState.activeUses > 0) { "Service client lease was released twice" }
            if (requestClose) {
                clientState.closeWhenIdle = true
                if (clientState.failure == null) clientState.failure = failure
                if (current === clientState) current = null
            }
            clientState.activeUses -= 1
            if (
                clientState.activeUses == 0 &&
                clientState.closeWhenIdle &&
                !clientState.closed
            ) {
                clientState.closed = true
                CloseClient(clientState.client, clientState.failure)
            } else {
                null
            }
        }
        if (closeClient != null) {
            try {
                closeClient(closeClient.client)
            } catch (closeFailure: Throwable) {
                closeClient.failure?.addSuppressed(closeFailure) ?: throw closeFailure
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
