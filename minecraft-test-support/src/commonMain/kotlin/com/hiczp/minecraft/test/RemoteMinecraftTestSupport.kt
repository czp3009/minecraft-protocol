package com.hiczp.minecraft.test

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Client facade for the Gradle-managed JVM fixture host.
 *
 * Test code owns remote handles only. Official processes, logs, and their work
 * directories remain private to the fixture host.
 */
object MinecraftTestSupport {
    suspend fun newOfficialServer(
        configuration: OfficialMinecraftServerConfiguration =
            OfficialMinecraftServerConfiguration(),
    ): OfficialMinecraftServerResource {
        val client = MinecraftTestFixtureRpcClient.connect()
        return try {
            OfficialMinecraftServerResource(
                client = client,
                descriptor = client.createServer(configuration),
            )
        } catch (failure: Throwable) {
            client.close()
            throw failure
        }
    }

    suspend fun newOfficialClient(
        configuration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClientResource {
        val client = MinecraftTestFixtureRpcClient.connect()
        return try {
            HeadlessMinecraftClientResource(
                client = client,
                descriptor = client.createClient(configuration),
            )
        } catch (failure: Throwable) {
            client.close()
            throw failure
        }
    }

    suspend fun verifyOfficialCodec(fixtures: JsonElement) {
        withFixtureClient { client ->
            client.verifyCodec(fixtures)
        }
    }
}

private suspend inline fun <T> withFixtureClient(
    block: suspend (MinecraftTestFixtureRpcClient) -> T,
): T {
    val client = MinecraftTestFixtureRpcClient.connect()
    return try {
        block(client)
    } finally {
        client.close()
    }
}

interface RemoteMinecraftTestResource {
    suspend fun close()
}

suspend inline fun <T : RemoteMinecraftTestResource, R> T.useRemote(
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
            close()
        } catch (closeFailure: Throwable) {
            if (failure == null) throw closeFailure
            failure.addSuppressed(closeFailure)
        }
    }
}

class OfficialMinecraftServerResource internal constructor(
    private val client: MinecraftTestFixtureRpcClient,
    descriptor: FixtureResourceDescriptor,
) : RemoteMinecraftTestResource {
    private val closeMutex = Mutex()
    private var closed = false
    private var currentDescriptor = descriptor

    val endpoint: MinecraftTestEndpoint
        get() = currentDescriptor.endpoint

    internal suspend fun status(): FixtureResourceStatus =
        client.status(currentDescriptor.id)

    suspend fun isAlive(): Boolean = status().alive

    suspend fun exitCode(): Int? = status().exitCode

    suspend fun logText(): String = client.log(currentDescriptor.id)

    suspend fun waitForLog(
        marker: String,
        timeout: Duration = SERVER_EVENT_TIMEOUT,
    ) {
        require(timeout.isPositive() && timeout.isFinite()) {
            "Log timeout must be positive and finite"
        }
        client.waitForLog(
            resourceId = currentDescriptor.id,
            marker = marker,
            timeoutMillis = timeout.inWholeMilliseconds,
        )
    }

    suspend fun sendCommand(command: String) {
        client.sendCommand(currentDescriptor.id, command)
    }

    suspend fun stop(): Int? = client.stopServer(currentDescriptor.id)

    suspend fun restart() {
        currentDescriptor = client.restartServer(currentDescriptor.id)
    }

    suspend fun <T> withWorldSnapshot(
        writeBack: Boolean,
        block: suspend (Path) -> T,
    ): T {
        val scratch = createTestTemporaryDirectory()
        val world = Path(scratch, "world")
        world.ensureDirectory()
        var failure: Throwable? = null
        try {
            val files = client.readWorldFiles(currentDescriptor.id)
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
                    client.writeWorldFiles(
                        currentDescriptor.id,
                        changedFiles,
                    )
                }
            }
            return result
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCatching {
                scratch.deleteTree()
            }
                .onFailure { cleanupFailure ->
                    failure?.addSuppressed(cleanupFailure)
                        ?: throw cleanupFailure
                }
        }
    }

    override suspend fun close() {
        closeMutex.withLock {
            if (closed) return
            closed = true
            try {
                client.closeResource(currentDescriptor.id)
            } finally {
                client.close()
            }
        }
    }
}

class HeadlessMinecraftClientResource internal constructor(
    private val client: MinecraftTestFixtureRpcClient,
    private val descriptor: FixtureResourceDescriptor,
) : RemoteMinecraftTestResource {
    private val closeMutex = Mutex()
    private var closed = false

    val endpoint: MinecraftTestEndpoint
        get() = descriptor.endpoint

    internal suspend fun status(): FixtureResourceStatus =
        client.status(descriptor.id)

    suspend fun isAlive(): Boolean = status().alive

    suspend fun exitCode(): Int? = status().exitCode

    suspend fun logText(): String = client.log(descriptor.id)

    suspend fun waitForLog(
        marker: String,
        timeout: Duration = CLIENT_EVENT_TIMEOUT,
    ) {
        require(timeout.isPositive() && timeout.isFinite()) {
            "Log timeout must be positive and finite"
        }
        client.waitForLog(
            resourceId = descriptor.id,
            marker = marker,
            timeoutMillis = timeout.inWholeMilliseconds,
        )
    }

    suspend fun awaitExit(): Int = client.awaitClientExit(descriptor.id)

    override suspend fun close() {
        closeMutex.withLock {
            if (closed) return
            closed = true
            try {
                client.closeResource(descriptor.id)
            } finally {
                client.close()
            }
        }
    }
}

private val SERVER_EVENT_TIMEOUT = 30.seconds
private val CLIENT_EVENT_TIMEOUT = 30.seconds
