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
import kotlin.time.Duration
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
            registerService<MinecraftTestSupportService> {
                MinecraftTestSupportServiceServer(resources)
            }
        }
    }
}

private class MinecraftTestSupportServiceServer(
    private val resources: HostedFixtureResources,
) : MinecraftTestSupportService {
    override suspend fun newOfficialServer(
        ownerId: String,
        configuration: OfficialMinecraftServerConfiguration,
    ): OfficialMinecraftServer = resources.newOfficialServer(ownerId, configuration)

    override suspend fun newOfficialClient(
        ownerId: String,
        configuration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClient = resources.newOfficialClient(ownerId, configuration)

    override suspend fun status(
        resource: MinecraftTestResource,
    ): MinecraftTestResourceStatus = resources.status(resource)

    override suspend fun logText(
        resource: MinecraftTestResource,
    ): String = resources.logText(resource)

    override suspend fun waitForLog(
        resource: MinecraftTestResource,
        marker: String,
        timeout: Duration,
    ) = resources.waitForLog(
        resource,
        marker,
        timeout,
    )

    override suspend fun sendCommand(
        server: OfficialMinecraftServer,
        command: String,
    ) {
        resources.hostedServer(server).sendCommand(command)
    }

    override suspend fun stopServer(server: OfficialMinecraftServer): Int? =
        resources.hostedServer(server).stop()

    override suspend fun restartServer(
        server: OfficialMinecraftServer,
    ): OfficialMinecraftServer = resources.restartServer(server)

    override suspend fun awaitClientExit(
        client: HeadlessMinecraftClient,
    ): Int = resources.hostedClient(client).awaitExit()

    override suspend fun close(resource: MinecraftTestResource) {
        resources.close(resource)
    }

    override suspend fun verifyOfficialCodec(fixtures: JsonElement) {
        verifyFixturesWithOfficialCodec(fixtures)
    }

    override suspend fun readWorldFiles(
        server: OfficialMinecraftServer,
    ): Map<String, ByteArray> = readWorldFiles(
        resources.hostedServer(server),
    )

    override suspend fun writeWorldFiles(
        server: OfficialMinecraftServer,
        files: Map<String, ByteArray>,
    ) {
        writeWorldFiles(
            resources.hostedServer(server),
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

    suspend fun newOfficialServer(
        ownerId: String,
        configuration: OfficialMinecraftServerConfiguration,
    ): OfficialMinecraftServer {
        acquireProcessSlot()
        val resource = try {
            HostedMinecraftTestSupport.newOfficialServer(configuration)
        } catch (failure: Throwable) {
            processSlots.release()
            throw failure
        }
        val id = newResourceId()
        val hosted = HostedFixtureResource.Server(ownerId, resource)
        hosted.invokeOnCleanupCompletion { processSlots.release() }
        return try {
            val server = resource.toOfficialMinecraftServer(id)
            mutex.withLock {
                check(acceptingCreations) {
                    "Minecraft test fixture host is shutting down"
                }
                resources[id] = hosted
            }
            server
        } catch (failure: Throwable) {
            hosted.close()
            throw failure
        }
    }

    suspend fun newOfficialClient(
        ownerId: String,
        configuration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClient {
        acquireProcessSlot()
        val resource = try {
            HostedMinecraftTestSupport.newOfficialClient(configuration)
        } catch (failure: Throwable) {
            processSlots.release()
            throw failure
        }
        val id = newResourceId()
        val hosted = HostedFixtureResource.Client(ownerId, resource)
        hosted.invokeOnCleanupCompletion { processSlots.release() }
        return try {
            val client = resource.toHeadlessMinecraftClient(id)
            mutex.withLock {
                check(acceptingCreations) {
                    "Minecraft test fixture host is shutting down"
                }
                resources[id] = hosted
            }
            client
        } catch (failure: Throwable) {
            hosted.close()
            throw failure
        }
    }

    suspend fun hostedServer(
        server: OfficialMinecraftServer,
    ): HostedOfficialMinecraftServerResource =
        when (val resource = hostedResource(server.id)) {
            is HostedFixtureResource.Server -> resource.resource
            is HostedFixtureResource.Client -> throw IllegalArgumentException(
                "Resource ${server.id} is not an official server",
            )
        }

    suspend fun hostedClient(
        client: HeadlessMinecraftClient,
    ): HostedHeadlessMinecraftClientResource =
        when (val resource = hostedResource(client.id)) {
            is HostedFixtureResource.Client -> resource.resource
            is HostedFixtureResource.Server -> throw IllegalArgumentException(
                "Resource ${client.id} is not an official client",
            )
        }

    suspend fun status(
        resource: MinecraftTestResource,
    ): MinecraftTestResourceStatus =
        when (val hosted = hostedResource(resource.id)) {
            is HostedFixtureResource.Server -> hosted.resource.status()
            is HostedFixtureResource.Client -> hosted.resource.status()
        }

    suspend fun logText(resource: MinecraftTestResource): String =
        when (val hosted = hostedResource(resource.id)) {
            is HostedFixtureResource.Server -> hosted.resource.logText()
            is HostedFixtureResource.Client -> hosted.resource.logText()
        }

    suspend fun waitForLog(
        resource: MinecraftTestResource,
        marker: String,
        timeout: Duration,
    ) {
        when (val hosted = hostedResource(resource.id)) {
            is HostedFixtureResource.Server -> hosted.resource.waitForLog(
                marker,
                timeout,
            )

            is HostedFixtureResource.Client -> hosted.resource.waitForLog(
                marker,
                timeout,
            )
        }
    }

    suspend fun restartServer(
        server: OfficialMinecraftServer,
    ): OfficialMinecraftServer {
        val hostedServer = hostedServer(server)
        hostedServer.restart()
        return hostedServer.toOfficialMinecraftServer(server.id)
    }

    suspend fun close(resource: MinecraftTestResource) {
        val hosted = mutex.withLock {
            resources.remove(resource.id) ?: return
        }
        hosted.close()
    }

    suspend fun closeOwner(ownerId: String) {
        val owned = mutex.withLock {
            val selected = resources.values.filter { it.ownerId == ownerId }
            resources.entries.removeAll { it.value.ownerId == ownerId }
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

    private suspend fun hostedResource(id: String): HostedFixtureResource =
        mutex.withLock {
            resources[id] ?: error("Fixture resource does not exist: $id")
        }
}

private sealed class HostedFixtureResource(
    val ownerId: String,
) {
    class Server(
        ownerId: String,
        val resource: HostedOfficialMinecraftServerResource,
    ) : HostedFixtureResource(ownerId)

    class Client(
        ownerId: String,
        val resource: HostedHeadlessMinecraftClientResource,
    ) : HostedFixtureResource(ownerId)

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

private fun HostedOfficialMinecraftServerResource.toOfficialMinecraftServer(
    id: String,
): OfficialMinecraftServer = OfficialMinecraftServer(
    id = id,
    endpoint = endpoint,
)

private fun HostedHeadlessMinecraftClientResource.toHeadlessMinecraftClient(
    id: String,
): HeadlessMinecraftClient = HeadlessMinecraftClient(
    id = id,
    endpoint = endpoint,
)

private fun HostedOfficialMinecraftServerResource.status(): MinecraftTestResourceStatus =
    MinecraftTestResourceStatus(
        alive = isAlive,
        exitCode = if (isAlive) null else exitCode,
    )

private fun HostedHeadlessMinecraftClientResource.status(): MinecraftTestResourceStatus =
    MinecraftTestResourceStatus(
        alive = isAlive,
        exitCode = if (isAlive) null else exitCode,
    )

private suspend fun verifyFixturesWithOfficialCodec(fixtures: JsonElement) {
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
