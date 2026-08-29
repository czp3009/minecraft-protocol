package com.hiczp.minecraft.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Ordinary test API backed by the Gradle-managed Fixture Host. */
object MinecraftTestSupport {
    private val serviceClients = SharedServiceClient(
        createClient = MinecraftTestSupportServiceClient::fromEnvironment,
        closeClient = MinecraftTestSupportServiceClient::close,
    )

    suspend fun newOfficialServer(
        officialMinecraftServerConfiguration: OfficialMinecraftServerConfiguration =
            OfficialMinecraftServerConfiguration(),
    ): OfficialMinecraftServer {
        return withServiceClient { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.newOfficialServer(
                ownerId = minecraftTestSupportServiceClient.ownerId,
                officialMinecraftServerConfiguration = officialMinecraftServerConfiguration,
            )
        }
    }

    suspend fun newHeadlessClient(
        headlessMinecraftClientConfiguration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClient {
        return withServiceClient { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.newHeadlessClient(
                ownerId = minecraftTestSupportServiceClient.ownerId,
                headlessMinecraftClientConfiguration = headlessMinecraftClientConfiguration,
            )
        }
    }

    suspend fun connectHeadlessClient(
        headlessMinecraftClient: HeadlessMinecraftClient,
        minecraftTestEndpoint: MinecraftTestEndpoint,
    ): HeadlessMinecraftClientState {
        require(minecraftTestEndpoint.host == LOOPBACK && minecraftTestEndpoint.port in 1..0xFFFF) {
            "Headless client tests require a valid loopback endpoint"
        }
        return withServiceClient { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.connectHeadlessClient(headlessMinecraftClient, minecraftTestEndpoint)
        }
    }

    /** Returns a correlated GUI-state snapshot without claiming protocol state. */
    suspend fun headlessClientState(
        headlessMinecraftClient: HeadlessMinecraftClient,
    ): HeadlessMinecraftClientState = withServiceClient { minecraftTestSupportServiceClient ->
        minecraftTestSupportServiceClient.headlessClientState(headlessMinecraftClient)
    }

    suspend fun disconnectHeadlessClient(headlessMinecraftClient: HeadlessMinecraftClient) {
        withServiceClient { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.disconnectHeadlessClient(headlessMinecraftClient)
        }
    }

    suspend fun sendHeadlessClientCommand(
        headlessMinecraftClient: HeadlessMinecraftClient,
        command: String,
        expectedNewOutput: String? = null,
        timeout: Duration = EVENT_TIMEOUT,
    ) {
        require(timeout.isPositive() && timeout.isFinite()) {
            "Command timeout must be positive and finite"
        }
        withServiceClient { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.sendHeadlessClientCommand(
                headlessMinecraftClient = headlessMinecraftClient,
                command = command,
                expectedNewOutput = expectedNewOutput,
                timeout = timeout,
            )
        }
    }

    suspend fun status(
        minecraftTestResource: MinecraftTestResource,
    ): MinecraftTestResourceStatus = withServiceClient { minecraftTestSupportServiceClient ->
        minecraftTestSupportServiceClient.status(minecraftTestResource)
    }

    suspend fun isAlive(minecraftTestResource: MinecraftTestResource): Boolean =
        status(minecraftTestResource).alive

    suspend fun exitCode(minecraftTestResource: MinecraftTestResource): Int? =
        status(minecraftTestResource).exitCode

    suspend fun logText(minecraftTestResource: MinecraftTestResource): String =
        withServiceClient { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.logText(
                minecraftTestResource
            )
        }

    suspend fun waitForLog(
        minecraftTestResource: MinecraftTestResource,
        marker: String,
        timeout: Duration = EVENT_TIMEOUT,
    ) {
        require(timeout.isPositive() && timeout.isFinite()) {
            "Log timeout must be positive and finite"
        }
        withServiceClient { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.waitForLog(minecraftTestResource, marker, timeout)
        }
    }

    suspend fun sendCommand(
        officialMinecraftServer: OfficialMinecraftServer,
        command: String,
        expectedNewOutput: String? = null,
        timeout: Duration = EVENT_TIMEOUT,
    ) {
        require(timeout.isPositive() && timeout.isFinite()) {
            "Command timeout must be positive and finite"
        }
        withServiceClient { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.sendCommand(
                officialMinecraftServer = officialMinecraftServer,
                command = command,
                expectedNewOutput = expectedNewOutput,
                timeout = timeout,
            )
        }
    }

    suspend fun restartServer(
        officialMinecraftServer: OfficialMinecraftServer,
    ): OfficialMinecraftServer = withServiceClient { minecraftTestSupportServiceClient ->
        minecraftTestSupportServiceClient.restartServer(officialMinecraftServer)
    }

    /**
     * Closes the process and waits until it has exited while retaining its
     * working directory and Fixture Host slot.
     */
    suspend fun closeProcess(minecraftTestResource: MinecraftTestResource): Int =
        withServiceClient { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.closeProcess(
                minecraftTestResource
            )
        }

    /** Waits for the current process to exit without requesting shutdown. */
    suspend fun awaitExit(minecraftTestResource: MinecraftTestResource): Int =
        withServiceClient { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.awaitExit(
                minecraftTestResource
            )
        }

    /**
     * Returns an absolute path in the Fixture Host's filesystem namespace.
     *
     * This is an intentional backdoor for same-host filesystem integration
     * tests. It is not a portable remote-fixture API: a container, device, or
     * remote test process might not be able to open the returned path. The
     * directory is Host-owned and remains valid only until
     * [deleteWorkingDirectory], [close], task completion, or build shutdown.
     * Stop the process before inspecting files that require a consistent
     * on-disk state.
     */
    suspend fun hostWorkingDirectory(
        minecraftTestResource: MinecraftTestResource,
    ): String = withServiceClient { minecraftTestSupportServiceClient ->
        minecraftTestSupportServiceClient.hostWorkingDirectory(minecraftTestResource)
    }

    /**
     * Deletes a stopped resource's working directory and waits until its slot
     * has been released. The resource is invalid after this returns.
     */
    suspend fun deleteWorkingDirectory(minecraftTestResource: MinecraftTestResource) {
        withServiceClient(closeAfter = true) { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.deleteWorkingDirectory(minecraftTestResource)
        }
    }

    /** Schedules process shutdown and directory cleanup, then returns. */
    suspend fun close(minecraftTestResource: MinecraftTestResource) {
        withContext(NonCancellable + Dispatchers.Default) {
            withTimeout(EVENT_TIMEOUT) {
                withServiceClient(closeAfter = true) { minecraftTestSupportServiceClient ->
                    minecraftTestSupportServiceClient.close(minecraftTestResource)
                }
            }
        }
    }

    /** Stops a resource, deletes its workspace, and releases its Host slot. */
    suspend fun closeAndAwait(minecraftTestResource: MinecraftTestResource): Int {
        var exitCode: Int? = null
        var failure: Throwable? = null
        try {
            exitCode = closeProcess(minecraftTestResource)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                try {
                    deleteWorkingDirectory(minecraftTestResource)
                } catch (caught: Throwable) {
                    cleanupFailure = caught
                    try {
                        close(minecraftTestResource)
                    } catch (fallbackFailure: Throwable) {
                        caught.addSuppressed(fallbackFailure)
                    }
                }
                cleanupFailure?.let { caught ->
                    failure?.addSuppressed(caught) ?: throw caught
                }
            }
        }
        return checkNotNull(exitCode)
    }

    suspend fun verifyOfficialCodec(fixtures: JsonElement) {
        withServiceClient(closeAfter = true) { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.verifyOfficialCodec(fixtures)
        }
    }

    suspend fun verifyOfficialNbt(fixtures: JsonElement) {
        withServiceClient(closeAfter = true) { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.verifyOfficialNbt(fixtures)
        }
    }

    suspend fun verifyOfficialSnbt(fixtures: JsonElement) {
        withServiceClient(closeAfter = true) { minecraftTestSupportServiceClient ->
            minecraftTestSupportServiceClient.verifyOfficialSnbt(fixtures)
        }
    }

    private suspend fun <T> withServiceClient(
        closeAfter: Boolean = false,
        block: suspend (MinecraftTestSupportServiceClient) -> T,
    ): T = serviceClients.use(closeAfter, block)
}

suspend inline fun <T : MinecraftTestResource, R> T.use(
    block: suspend (T) -> R,
): R {
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        try {
            MinecraftTestSupport.close(this)
        } catch (closeFailure: Throwable) {
            if (failure == null) throw closeFailure
            failure.addSuppressed(closeFailure)
        }
    }
}

private val EVENT_TIMEOUT = 30.seconds
private const val LOOPBACK = "127.0.0.1"
