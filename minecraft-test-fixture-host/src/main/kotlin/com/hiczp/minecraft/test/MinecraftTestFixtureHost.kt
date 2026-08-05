package com.hiczp.minecraft.test

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import java.nio.file.Path as NioPath

fun main(arguments: Array<String>) = runBlocking {
    require(arguments.size == 8) {
        "Expected Minecraft version, fixture inputs, and work root"
    }
    val minecraftVersion = arguments[0]
    HostedMinecraftTestSupport.configure(
        MinecraftTestLayout(
            minecraftVersion = minecraftVersion,
            serverCacheDirectory = Path(arguments[1]),
            clientCacheDirectory = Path(arguments[2]),
            versionMetadataFile = Path(arguments[3]),
            headlessLauncherFile = Path(arguments[4]),
            serverRuntimeDirectory = Path(arguments[5]),
            codecClassesDirectory = Path(arguments[6]),
            hostWorkRoot = Path(arguments[7]),
            javaExecutable = Path("java"),
        ),
    )

    val resources = HostedFixtureResources()
    val shutdown = CompletableDeferred<Unit>()
    val server = embeddedServer(CIO, host = LOOPBACK, port = 0) {
        fixtureHostModule(resources)
    }
    server.start(wait = false)
    val connector = server.engine.resolvedConnectors().single()
    // Standard output is the machine-readable startup channel consumed by the Build Service.
    println("${READY_PREFIX}ws://$LOOPBACK:${connector.port}$RPC_PATH")
    System.out.flush()

    launch(Dispatchers.IO) {
        try {
            while (true) {
                val command = readlnOrNull() ?: break
                when {
                    command == SHUTDOWN_COMMAND -> {
                        resources.stopAcceptingCreations()
                        break
                    }
                    command.startsWith(CLOSE_OWNER_COMMAND_PREFIX) ->
                        resources.closeOwner(
                            command.removePrefix(CLOSE_OWNER_COMMAND_PREFIX),
                        )
                }
            }
        } finally {
            resources.stopAcceptingCreations()
            shutdown.complete(Unit)
        }
    }
    shutdown.await()
    try {
        resources.shutdown()
    } finally {
        server.stop(
            gracePeriodMillis = HOST_STOP_GRACE_MILLIS,
            timeoutMillis = HOST_STOP_TIMEOUT_MILLIS,
        )
    }
}

private fun Application.fixtureHostModule(resources: HostedFixtureResources) {
    install(Krpc) {
        serialization {
            json()
        }
    }
    routing {
        rpc(RPC_PATH) {
            registerService<MinecraftTestFixtureRpc> {
                HostedMinecraftTestFixtureRpc(resources)
            }
        }
    }
}

private class HostedMinecraftTestFixtureRpc(
    private val resources: HostedFixtureResources,
) : MinecraftTestFixtureRpc {
    override suspend fun createServer(
        owner: String,
        request: CreateOfficialServerRequest,
    ): FixtureResourceDescriptor = resources.createServer(owner, request)

    override suspend fun createClient(
        owner: String,
        request: CreateOfficialClientRequest,
    ): FixtureResourceDescriptor = resources.createClient(owner, request)

    override suspend fun status(
        resourceId: String,
    ): FixtureResourceStatus = resources.status(resourceId)

    override suspend fun log(
        resourceId: String,
    ): String = resources.log(resourceId)

    override suspend fun waitForLog(
        resourceId: String,
        marker: String,
        timeoutMillis: Long,
    ) = resources.waitForLog(
        resourceId,
        marker,
        timeoutMillis,
    )

    override suspend fun sendCommand(
        resourceId: String,
        command: String,
    ) {
        resources.server(resourceId).sendCommand(command)
    }

    override suspend fun stopServer(resourceId: String): Int? =
        resources.server(resourceId).stop()

    override suspend fun restartServer(
        resourceId: String,
    ): FixtureResourceDescriptor =
        resources.restartServer(resourceId)

    override suspend fun awaitClientExit(
        resourceId: String,
    ): Int = resources.client(resourceId).awaitExit()

    override suspend fun closeResource(resourceId: String) {
        resources.close(resourceId)
    }

    override suspend fun verifyCodec(fixtures: JsonElement) {
        runCodecVerification(fixtures)
    }

    override suspend fun readWorldFiles(
        resourceId: String,
    ): Map<String, ByteArray> = readWorldFiles(
        resources.server(resourceId),
    )

    override suspend fun writeWorldFiles(
        resourceId: String,
        files: Map<String, ByteArray>,
    ) {
        writeWorldFiles(
            resources.server(resourceId),
            files,
        )
    }
}

private class HostedFixtureResources {
    private val mutex = Mutex()

    // kotlinx.coroutines Semaphore queues suspended acquirers fairly. A slot
    // is returned by the managed resource's post-directory-cleanup callback.
    private val processSlots = Semaphore(PROCESS_POOL_SLOTS)
    private val resources = linkedMapOf<String, HostedFixtureResource>()
    private var acceptingCreations = true

    suspend fun createServer(
        owner: String,
        request: CreateOfficialServerRequest,
    ): FixtureResourceDescriptor {
        acquireProcessSlot()
        val resource = try {
            HostedMinecraftTestSupport.newOfficialServer(
                OfficialMinecraftServerConfiguration(
                    properties = request.properties,
                    startupTimeout = request.startupTimeoutMillis.milliseconds,
                    stopTimeout = request.stopTimeoutMillis.milliseconds,
                    maximumBindAttempts = request.maximumBindAttempts,
                ),
            )
        } catch (failure: Throwable) {
            processSlots.release()
            throw failure
        }
        val id = newResourceId()
        val hosted = HostedFixtureResource.Server(owner, resource)
        hosted.invokeOnCleanupCompletion { processSlots.release() }
        return try {
            val descriptor = resource.descriptor(id)
            mutex.withLock {
                check(acceptingCreations) {
                    "Minecraft test fixture host is shutting down"
                }
                resources[id] = hosted
            }
            descriptor
        } catch (failure: Throwable) {
            hosted.close()
            throw failure
        }
    }

    suspend fun createClient(
        owner: String,
        request: CreateOfficialClientRequest,
    ): FixtureResourceDescriptor {
        acquireProcessSlot()
        val resource = try {
            HostedMinecraftTestSupport.newOfficialClient(
                HeadlessMinecraftClientConfiguration(
                    playerName = request.playerName,
                    endpoint = request.endpoint,
                ),
            )
        } catch (failure: Throwable) {
            processSlots.release()
            throw failure
        }
        val id = newResourceId()
        val hosted = HostedFixtureResource.Client(owner, resource)
        hosted.invokeOnCleanupCompletion { processSlots.release() }
        return try {
            val descriptor = resource.descriptor(id)
            mutex.withLock {
                check(acceptingCreations) {
                    "Minecraft test fixture host is shutting down"
                }
                resources[id] = hosted
            }
            descriptor
        } catch (failure: Throwable) {
            hosted.close()
            throw failure
        }
    }

    suspend fun server(
        id: String,
    ): HostedOfficialMinecraftServerResource =
        when (val resource = resource(id)) {
            is HostedFixtureResource.Server -> resource.resource
            is HostedFixtureResource.Client -> throw IllegalArgumentException(
                "Resource $id is not an official server",
            )
        }

    suspend fun client(
        id: String,
    ): HostedHeadlessMinecraftClientResource =
        when (val resource = resource(id)) {
            is HostedFixtureResource.Client -> resource.resource
            is HostedFixtureResource.Server -> throw IllegalArgumentException(
                "Resource $id is not an official client",
            )
        }

    suspend fun status(id: String): FixtureResourceStatus =
        when (val resource = resource(id)) {
            is HostedFixtureResource.Server -> resource.resource.status()
            is HostedFixtureResource.Client -> resource.resource.status()
        }

    suspend fun log(id: String): String =
        when (val resource = resource(id)) {
            is HostedFixtureResource.Server -> resource.resource.logText()
            is HostedFixtureResource.Client -> resource.resource.logText()
        }

    suspend fun waitForLog(
        id: String,
        marker: String,
        timeoutMillis: Long,
    ) {
        when (val resource = resource(id)) {
            is HostedFixtureResource.Server -> resource.resource.waitForLog(
                marker,
                timeoutMillis.milliseconds,
            )

            is HostedFixtureResource.Client -> resource.resource.waitForLog(
                marker,
                timeoutMillis.milliseconds,
            )
        }
    }

    suspend fun restartServer(
        id: String,
    ): FixtureResourceDescriptor {
        val server = server(id)
        server.restart()
        return server.descriptor(id)
    }

    suspend fun close(id: String) {
        val resource = mutex.withLock {
            resources.remove(id) ?: return
        }
        resource.close()
    }

    suspend fun closeOwner(owner: String) {
        val owned = mutex.withLock {
            val selected = resources.values.filter { it.owner == owner }
            resources.entries.removeAll { it.value.owner == owner }
            selected
        }
        owned.forEach(HostedFixtureResource::close)
    }

    suspend fun stopAcceptingCreations() {
        mutex.withLock {
            acceptingCreations = false
        }
        HostedMinecraftTestSupport.stopAcceptingResourceCreations()
    }

    suspend fun shutdown() {
        stopAcceptingCreations()
        val remaining = mutex.withLock {
            resources.values.toList().also { resources.clear() }
        }
        remaining.forEach(HostedFixtureResource::close)
        HostedMinecraftTestSupport.shutdown()
    }

    private suspend fun acquireProcessSlot() {
        mutex.withLock {
            check(acceptingCreations) {
                "Minecraft test fixture host is shutting down"
            }
        }
        processSlots.acquire()
        try {
            mutex.withLock {
                check(acceptingCreations) {
                    "Minecraft test fixture host is shutting down"
                }
            }
        } catch (failure: Throwable) {
            processSlots.release()
            throw failure
        }
    }

    private suspend fun resource(id: String): HostedFixtureResource =
        mutex.withLock {
            resources[id] ?: error("Fixture resource does not exist: $id")
        }
}

private sealed class HostedFixtureResource(
    val owner: String,
) {
    class Server(
        owner: String,
        val resource: HostedOfficialMinecraftServerResource,
    ) : HostedFixtureResource(owner)

    class Client(
        owner: String,
        val resource: HostedHeadlessMinecraftClientResource,
    ) : HostedFixtureResource(owner)

    fun close() {
        when (this) {
            is Server -> resource.close()
            is Client -> resource.close()
        }
    }

    fun invokeOnCleanupCompletion(handler: (Throwable?) -> Unit) {
        when (this) {
            is Server -> resource.invokeOnCleanupCompletion(handler)
            is Client -> resource.invokeOnCleanupCompletion(handler)
        }
    }
}

private fun HostedOfficialMinecraftServerResource.descriptor(
    id: String,
): FixtureResourceDescriptor = FixtureResourceDescriptor(
    id = id,
    endpoint = endpoint,
)

private fun HostedHeadlessMinecraftClientResource.descriptor(
    id: String,
): FixtureResourceDescriptor = FixtureResourceDescriptor(
    id = id,
    endpoint = endpoint,
)

private fun HostedOfficialMinecraftServerResource.status(): FixtureResourceStatus =
    FixtureResourceStatus(
        alive = isAlive,
        exitCode = if (isAlive) null else exitCode,
    )

private fun HostedHeadlessMinecraftClientResource.status(): FixtureResourceStatus =
    FixtureResourceStatus(
        alive = isAlive,
        exitCode = if (isAlive) null else exitCode,
    )

private suspend fun runCodecVerification(fixtures: JsonElement) {
    val scratch = HostedMinecraftTestSupport.newScratchDirectory()
    try {
        OfficialCodecOracle.verify(
            fixtures = fixtures,
            loggingConfiguration = Path(scratch, "log4j2.xml"),
        )
    } finally {
        scratch.deleteTree()
    }
}

private fun readWorldFiles(
    server: HostedOfficialMinecraftServerResource,
): Map<String, ByteArray> {
    check(!server.isAlive) {
        "Official server must be stopped before reading its world"
    }
    val root = server.worldDirectory.toNioPath().toAbsolutePath().normalize()
    check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        "Official server world does not exist"
    }
    return Files.walk(root).use { paths ->
        paths.iterator().asSequence().mapNotNull { path ->
            if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                return@mapNotNull null
            }
            val relative = root.relativize(path).normalize()
            val wirePath = relative.joinToString("/") { it.pathString }
            wirePath to Files.readAllBytes(path)
        }.sortedBy { it.first }.toMap(linkedMapOf())
    }
}

private fun writeWorldFiles(
    server: HostedOfficialMinecraftServerResource,
    files: Map<String, ByteArray>,
) {
    check(!server.isAlive) {
        "Official server must be stopped before writing its world"
    }
    files.forEach { (relativePath, content) ->
        val path = resolveExistingWorldFile(server, relativePath)
        Path(path.toString()).writeBytes(content)
    }
}

private fun resolveExistingWorldFile(
    server: HostedOfficialMinecraftServerResource,
    relativePath: String,
): NioPath {
    val root = server.worldDirectory.toNioPath().toAbsolutePath().normalize()
    val safe = Path(root.toString()).safeResolve(relativePath)
        .toNioPath().toAbsolutePath().normalize()
    require(safe.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
        "World file does not exist: $relativePath"
    }
    return safe
}

private fun newResourceId(): String = Uuid.random().toString()

private const val LOOPBACK = "127.0.0.1"
private const val RPC_PATH = "/rpc"
private const val READY_PREFIX = "MINECRAFT_TEST_FIXTURE_READY "
private const val SHUTDOWN_COMMAND = "shutdown"
private const val CLOSE_OWNER_COMMAND_PREFIX = "close-owner "
private const val HOST_STOP_GRACE_MILLIS = 1_000L
private const val HOST_STOP_TIMEOUT_MILLIS = 10_000L
private const val PROCESS_POOL_SLOTS = 8
