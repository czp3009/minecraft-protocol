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
        configuration: OfficialMinecraftServerConfiguration =
            OfficialMinecraftServerConfiguration(),
    ): OfficialMinecraftServer {
        return withServiceClient { client ->
            client.newOfficialServer(
                ownerId = client.ownerId,
                configuration = configuration,
            )
        }
    }

    suspend fun newHeadlessClient(
        configuration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClient {
        return withServiceClient { client ->
            client.newHeadlessClient(
                ownerId = client.ownerId,
                configuration = configuration,
            )
        }
    }

    suspend fun connectHeadlessClient(
        client: HeadlessMinecraftClient,
        endpoint: MinecraftTestEndpoint,
    ): HeadlessMinecraftClientState {
        require(endpoint.host == LOOPBACK && endpoint.port in 1..0xFFFF) {
            "Headless client tests require a valid loopback endpoint"
        }
        return withServiceClient { service ->
            service.connectHeadlessClient(client, endpoint)
        }
    }

    /** Returns a correlated GUI-state snapshot without claiming protocol state. */
    suspend fun headlessClientState(
        client: HeadlessMinecraftClient,
    ): HeadlessMinecraftClientState = withServiceClient { service ->
        service.headlessClientState(client)
    }

    suspend fun disconnectHeadlessClient(client: HeadlessMinecraftClient) {
        withServiceClient { service ->
            service.disconnectHeadlessClient(client)
        }
    }

    suspend fun sendHeadlessClientCommand(
        client: HeadlessMinecraftClient,
        command: String,
        expectedNewOutput: String? = null,
        timeout: Duration = EVENT_TIMEOUT,
    ) {
        require(timeout.isPositive() && timeout.isFinite()) {
            "Command timeout must be positive and finite"
        }
        withServiceClient { service ->
            service.sendHeadlessClientCommand(
                client = client,
                command = command,
                expectedNewOutput = expectedNewOutput,
                timeout = timeout,
            )
        }
    }

    suspend fun status(
        resource: MinecraftTestResource,
    ): MinecraftTestResourceStatus = withServiceClient { service ->
        service.status(resource)
    }

    suspend fun isAlive(resource: MinecraftTestResource): Boolean =
        status(resource).alive

    suspend fun exitCode(resource: MinecraftTestResource): Int? =
        status(resource).exitCode

    suspend fun logText(resource: MinecraftTestResource): String =
        withServiceClient { service -> service.logText(resource) }

    suspend fun waitForLog(
        resource: MinecraftTestResource,
        marker: String,
        timeout: Duration = EVENT_TIMEOUT,
    ) {
        require(timeout.isPositive() && timeout.isFinite()) {
            "Log timeout must be positive and finite"
        }
        withServiceClient { service ->
            service.waitForLog(resource, marker, timeout)
        }
    }

    suspend fun sendCommand(
        server: OfficialMinecraftServer,
        command: String,
        expectedNewOutput: String? = null,
        timeout: Duration = EVENT_TIMEOUT,
    ) {
        require(timeout.isPositive() && timeout.isFinite()) {
            "Command timeout must be positive and finite"
        }
        withServiceClient { service ->
            service.sendCommand(
                server = server,
                command = command,
                expectedNewOutput = expectedNewOutput,
                timeout = timeout,
            )
        }
    }

    suspend fun restartServer(
        server: OfficialMinecraftServer,
    ): OfficialMinecraftServer = withServiceClient { service ->
        service.restartServer(server)
    }

    /**
     * Closes the process and waits until it has exited while retaining its
     * working directory and Fixture Host slot.
     */
    suspend fun closeProcess(resource: MinecraftTestResource): Int =
        withServiceClient { service -> service.closeProcess(resource) }

    /** Waits for the current process to exit without requesting shutdown. */
    suspend fun awaitExit(resource: MinecraftTestResource): Int =
        withServiceClient { service -> service.awaitExit(resource) }

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
        resource: MinecraftTestResource,
    ): String = withServiceClient { service ->
        service.hostWorkingDirectory(resource)
    }

    /**
     * Deletes a stopped resource's working directory and waits until its slot
     * has been released. The resource is invalid after this returns.
     */
    suspend fun deleteWorkingDirectory(resource: MinecraftTestResource) {
        withServiceClient(closeAfter = true) { client ->
            client.deleteWorkingDirectory(resource)
        }
    }

    /** Schedules process shutdown and directory cleanup, then returns. */
    suspend fun close(resource: MinecraftTestResource) {
        withContext(NonCancellable + Dispatchers.Default) {
            withTimeout(EVENT_TIMEOUT) {
                withServiceClient(closeAfter = true) { client ->
                    client.close(resource)
                }
            }
        }
    }

    /** Stops a resource, deletes its workspace, and releases its Host slot. */
    suspend fun closeAndAwait(resource: MinecraftTestResource): Int {
        var exitCode: Int? = null
        var failure: Throwable? = null
        try {
            exitCode = closeProcess(resource)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                try {
                    deleteWorkingDirectory(resource)
                } catch (caught: Throwable) {
                    cleanupFailure = caught
                    try {
                        close(resource)
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
        withServiceClient(closeAfter = true) { client ->
            client.verifyOfficialCodec(fixtures)
        }
    }

    suspend fun verifyOfficialNbt(fixtures: JsonElement) {
        withServiceClient(closeAfter = true) { client ->
            client.verifyOfficialNbt(fixtures)
        }
    }

    suspend fun verifyOfficialSnbt(fixtures: JsonElement) {
        withServiceClient(closeAfter = true) { client ->
            client.verifyOfficialSnbt(fixtures)
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
