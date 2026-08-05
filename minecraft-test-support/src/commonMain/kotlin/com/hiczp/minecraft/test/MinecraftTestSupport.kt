package com.hiczp.minecraft.test

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Ordinary test API backed by the Gradle-managed Fixture Host. */
object MinecraftTestSupport {
    private val serviceClientMutex = Mutex()
    private var currentServiceClient: MinecraftTestSupportServiceClient? = null

    suspend fun newOfficialServer(
        configuration: OfficialMinecraftServerConfiguration =
            OfficialMinecraftServerConfiguration(),
    ): OfficialMinecraftServer {
        val client = serviceClient()
        return try {
            client.newOfficialServer(
                ownerId = client.ownerId,
                configuration = configuration,
            )
        } catch (failure: Throwable) {
            closeServiceClient(client, failure)
            throw failure
        }
    }

    suspend fun newOfficialClient(
        configuration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClient {
        val client = serviceClient()
        return try {
            client.newOfficialClient(
                ownerId = client.ownerId,
                configuration = configuration,
            )
        } catch (failure: Throwable) {
            closeServiceClient(client, failure)
            throw failure
        }
    }

    suspend fun status(
        resource: MinecraftTestResource,
    ): MinecraftTestResourceStatus = serviceClient().status(resource)

    suspend fun isAlive(resource: MinecraftTestResource): Boolean =
        status(resource).alive

    suspend fun exitCode(resource: MinecraftTestResource): Int? =
        status(resource).exitCode

    suspend fun logText(resource: MinecraftTestResource): String =
        serviceClient().logText(resource)

    suspend fun waitForLog(
        resource: MinecraftTestResource,
        marker: String,
        timeout: Duration = EVENT_TIMEOUT,
    ) {
        require(timeout.isPositive() && timeout.isFinite()) {
            "Log timeout must be positive and finite"
        }
        serviceClient().waitForLog(resource, marker, timeout)
    }

    suspend fun sendCommand(
        server: OfficialMinecraftServer,
        command: String,
    ) {
        serviceClient().sendCommand(server, command)
    }

    suspend fun stopServer(server: OfficialMinecraftServer): Int? =
        serviceClient().stopServer(server)

    suspend fun restartServer(
        server: OfficialMinecraftServer,
    ): OfficialMinecraftServer = serviceClient().restartServer(server)

    suspend fun awaitClientExit(client: HeadlessMinecraftClient): Int =
        serviceClient().awaitClientExit(client)

    suspend fun close(resource: MinecraftTestResource) {
        withClosingServiceClient { client ->
            client.close(resource)
        }
    }

    suspend fun verifyOfficialCodec(fixtures: JsonElement) {
        withClosingServiceClient { client ->
            client.verifyOfficialCodec(fixtures)
        }
    }

    suspend fun <T> withWorldSnapshot(
        server: OfficialMinecraftServer,
        writeBack: Boolean,
        block: suspend (Path) -> T,
    ): T {
        val scratch = createTestTemporaryDirectory()
        val world = Path(scratch, "world")
        world.ensureDirectory()
        var failure: Throwable? = null
        try {
            val files = serviceClient().readWorldFiles(server)
            files.forEach { (relativePath, content) ->
                world.safeResolve(relativePath).writeBytes(content)
            }

            val result = block(world)
            if (writeBack) {
                val changedFiles = buildMap {
                    files.forEach { (relativePath, original) ->
                        val path = world.safeResolve(relativePath)
                        check(path.isRegularFile()) {
                            "World rewrite removed $relativePath"
                        }
                        val content = path.readBytes()
                        if (!content.contentEquals(original)) {
                            put(relativePath, content)
                        }
                    }
                }
                if (changedFiles.isNotEmpty()) {
                    serviceClient().writeWorldFiles(server, changedFiles)
                }
            }
            return result
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCatching { scratch.deleteTree() }
                .onFailure { cleanupFailure ->
                    failure?.addSuppressed(cleanupFailure)
                        ?: throw cleanupFailure
                }
        }
    }

    private suspend fun serviceClient(): MinecraftTestSupportServiceClient =
        serviceClientMutex.withLock {
            currentServiceClient
                ?: MinecraftTestSupportServiceClient.fromEnvironment()
                    .also { currentServiceClient = it }
        }

    private suspend fun <T> withClosingServiceClient(
        block: suspend (MinecraftTestSupportServiceClient) -> T,
    ): T {
        val client = serviceClient()
        var failure: Throwable? = null
        try {
            return block(client)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            closeServiceClient(client, failure)
        }
    }

    private suspend fun closeServiceClient(
        client: MinecraftTestSupportServiceClient,
        failure: Throwable?,
    ) = withContext(NonCancellable) {
        val shouldClose = serviceClientMutex.withLock {
            if (currentServiceClient !== client) {
                false
            } else {
                currentServiceClient = null
                true
            }
        }
        if (shouldClose) {
            runCatching { client.close() }
                .onFailure { closeFailure ->
                    failure?.addSuppressed(closeFailure)
                        ?: throw closeFailure
                }
        }
    }
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
