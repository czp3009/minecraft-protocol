package com.hiczp.minecraft.test.host

import com.hiczp.minecraft.test.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.time.Duration
import kotlin.uuid.Uuid

fun main(arguments: Array<String>) = runBlocking {
    require(arguments.size == 7) {
        "Expected Minecraft version, fixture inputs, work root, and maximum parallel usages"
    }

    // 0: The official Minecraft release shared by every prepared fixture input.
    val minecraftVersion = arguments[0]
    // 1: The Gradle-prepared, stopped official-server template and runtime.
    val officialServerRootDirectory = Path.of(arguments[1])
    // 2: The Gradle-prepared, stopped HeadlessMC template and runtime.
    val headlessClientRootDirectory = Path.of(arguments[2])
    // 3: The extracted official server implementation and runtime libraries.
    val serverRuntimeDirectory = Path.of(arguments[3])
    // 4: The compiled bridge used to verify values with the official codecs.
    val codecClassesDirectory = Path.of(arguments[4])
    // 5: This Host process's isolated root for runtime directories and scratch files.
    val hostWorkRoot = Path.of(arguments[5])
    // 6: The resource capacity shared with the Gradle Build Service task limit.
    val maximumParallelUsages = arguments[6].toInt()
    require(maximumParallelUsages > 0) {
        "Maximum parallel usages must be positive"
    }
    HostedMinecraftTestSupport.configure(
        MinecraftTestLayout(
            minecraftVersion = minecraftVersion,
            officialServerRootDirectory = officialServerRootDirectory,
            headlessClientRootDirectory = headlessClientRootDirectory,
            serverRuntimeDirectory = serverRuntimeDirectory,
            codecClassesDirectory = codecClassesDirectory,
            hostWorkRoot = hostWorkRoot,
        ),
    )

    val resources = HostedFixtureResources(maximumParallelUsages)
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

    override suspend fun newHeadlessClient(
        ownerId: String,
        configuration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClient = resources.newHeadlessClient(ownerId, configuration)

    override suspend fun connectHeadlessClient(
        client: HeadlessMinecraftClient,
        endpoint: MinecraftTestEndpoint,
    ): HeadlessMinecraftClientState = resources.hostedClient(client).connect(endpoint)

    override suspend fun headlessClientState(
        client: HeadlessMinecraftClient,
    ): HeadlessMinecraftClientState = resources.hostedClient(client).state()

    override suspend fun disconnectHeadlessClient(
        client: HeadlessMinecraftClient,
    ) {
        resources.hostedClient(client).disconnect()
    }

    override suspend fun sendHeadlessClientCommand(
        client: HeadlessMinecraftClient,
        command: String,
        expectedNewOutput: String?,
        timeout: Duration,
    ) {
        resources.hostedClient(client).sendCommand(
            command = command,
            expectedNewOutput = expectedNewOutput,
            timeout = timeout,
        )
    }

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

    override suspend fun restartServer(
        server: OfficialMinecraftServer,
    ): OfficialMinecraftServer = resources.restartServer(server)

    override suspend fun closeProcess(
        resource: MinecraftTestResource,
    ): Int = resources.closeProcess(resource)

    override suspend fun awaitExit(
        resource: MinecraftTestResource,
    ): Int = resources.awaitExit(resource)

    override suspend fun hostWorkingDirectory(
        resource: MinecraftTestResource,
    ): String = resources.hostWorkingDirectory(resource)

    override suspend fun deleteWorkingDirectory(
        resource: MinecraftTestResource,
    ) {
        resources.deleteWorkingDirectory(resource)
    }

    override suspend fun close(resource: MinecraftTestResource) {
        resources.close(resource)
    }

    override suspend fun verifyOfficialCodec(fixtures: JsonElement) {
        verifyFixturesWithOfficialCodec(fixtures, "run")
    }

    override suspend fun verifyOfficialNbt(fixtures: JsonElement) {
        verifyFixturesWithOfficialCodec(fixtures, "runNbt")
    }

    override suspend fun verifyOfficialSnbt(fixtures: JsonElement) {
        verifyFixturesWithOfficialCodec(fixtures, "runSnbt")
    }
}

internal class HostedFixtureResources(maximumParallelUsages: Int) {
    private val mutex = Mutex()

    // kotlinx.coroutines Semaphore queues suspended acquirers fairly. A slot
    // is returned by the managed resource's post-directory-cleanup callback.
    private val resourceSlots = Semaphore(maximumParallelUsages)
    private val resources = linkedMapOf<String, HostedFixtureResource>()
    private val ownerCreationJobs = mutableMapOf<String, MutableSet<Job>>()
    private val closedOwnerIds = mutableSetOf<String>()
    private var acceptingCreations = true

    suspend fun newOfficialServer(
        ownerId: String,
        configuration: OfficialMinecraftServerConfiguration,
    ): OfficialMinecraftServer = withOwnerCreation(ownerId) {
        acquireResourceSlot(ownerId)
        val resource = try {
            HostedMinecraftTestSupport.newOfficialServer(configuration)
        } catch (failure: Throwable) {
            resourceSlots.release()
            throw failure
        }
        val id = Uuid.random().toString()
        val hosted = HostedFixtureResource.Server(ownerId, resource)
        hosted.invokeOnCleanupCompletion { resourceSlots.release() }
        try {
            val server = resource.toOfficialMinecraftServer(id)
            mutex.withLock {
                checkCreationAllowed(ownerId)
                resources[id] = hosted
            }
            server
        } catch (failure: Throwable) {
            hosted.close()
            throw failure
        }
    }

    suspend fun newHeadlessClient(
        ownerId: String,
        configuration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClient = withOwnerCreation(ownerId) {
        acquireResourceSlot(ownerId)
        val resource = try {
            HostedMinecraftTestSupport.newHeadlessClient(configuration)
        } catch (failure: Throwable) {
            resourceSlots.release()
            throw failure
        }
        val id = Uuid.random().toString()
        val hosted = HostedFixtureResource.Client(ownerId, resource)
        hosted.invokeOnCleanupCompletion { resourceSlots.release() }
        try {
            val client = resource.toHeadlessMinecraftClient(id)
            mutex.withLock {
                checkCreationAllowed(ownerId)
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
                "Resource ${client.id} is not a HeadlessMC client",
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

    suspend fun closeProcess(resource: MinecraftTestResource): Int =
        when (val hosted = hostedResource(resource.id)) {
            is HostedFixtureResource.Server -> hosted.resource.closeProcess()
            is HostedFixtureResource.Client -> hosted.resource.closeProcess()
        }

    suspend fun awaitExit(resource: MinecraftTestResource): Int =
        when (val hosted = hostedResource(resource.id)) {
            is HostedFixtureResource.Server -> hosted.resource.awaitExit()
            is HostedFixtureResource.Client -> hosted.resource.awaitExit()
        }

    suspend fun hostWorkingDirectory(
        resource: MinecraftTestResource,
    ): String = hostedResource(resource.id).workDirectory
        .toAbsolutePath()
        .normalize()
        .pathString

    suspend fun deleteWorkingDirectory(resource: MinecraftTestResource) {
        val hosted = hostedResource(resource.id)
        hosted.deleteWorkingDirectory()
        mutex.withLock {
            if (resources[resource.id] === hosted) {
                resources.remove(resource.id)
            }
        }
    }

    suspend fun close(resource: MinecraftTestResource) {
        val hosted = mutex.withLock {
            resources.remove(resource.id) ?: return
        }
        hosted.close()
    }

    suspend fun closeOwner(ownerId: String) {
        val closure = mutex.withLock {
            closedOwnerIds.add(ownerId)
            val creationJobs = ownerCreationJobs.remove(ownerId)?.toList().orEmpty()
            val selected = resources.values.filter { it.ownerId == ownerId }
            resources.entries.removeAll { it.value.ownerId == ownerId }
            OwnerClosure(creationJobs, selected)
        }
        closure.creationJobs.forEach { creationJob ->
            creationJob.cancel(CancellationException("Fixture owner $ownerId was closed"))
        }
        closure.resources.forEach(HostedFixtureResource::close)
    }

    suspend fun stopAcceptingCreations() {
        val creationJobs = mutex.withLock {
            acceptingCreations = false
            ownerCreationJobs.values.flatMap { it.toList() }.also {
                ownerCreationJobs.clear()
            }
        }
        creationJobs.forEach { creationJob ->
            creationJob.cancel(CancellationException("Minecraft test fixture host is shutting down"))
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

    internal suspend fun <T> withOwnerCreation(
        ownerId: String,
        action: suspend () -> T,
    ): T {
        val creationJob = currentCoroutineContext().job
        mutex.withLock {
            checkCreationAllowed(ownerId)
            ownerCreationJobs.getOrPut(ownerId, ::linkedSetOf).add(creationJob)
        }
        try {
            return action()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val jobs = ownerCreationJobs[ownerId]
                    if (jobs != null && jobs.remove(creationJob) && jobs.isEmpty()) {
                        ownerCreationJobs.remove(ownerId)
                    }
                }
            }
        }
    }

    private suspend fun acquireResourceSlot(ownerId: String) {
        mutex.withLock {
            checkCreationAllowed(ownerId)
        }
        resourceSlots.acquire()
        try {
            mutex.withLock {
                checkCreationAllowed(ownerId)
            }
        } catch (failure: Throwable) {
            resourceSlots.release()
            throw failure
        }
    }

    private suspend fun hostedResource(id: String): HostedFixtureResource =
        mutex.withLock {
            resources[id] ?: error("Fixture resource does not exist: $id")
        }

    private fun checkCreationAllowed(ownerId: String) {
        require(ownerId.isNotBlank()) { "Fixture owner ID is blank" }
        check(acceptingCreations) {
            "Minecraft test fixture host is shutting down"
        }
        check(ownerId !in closedOwnerIds) {
            "Fixture owner $ownerId is already closed"
        }
    }
}

private data class OwnerClosure(
    val creationJobs: List<Job>,
    val resources: List<HostedFixtureResource>,
)

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

    val workDirectory: Path
        get() = when (this) {
            is Server -> resource.workDirectory
            is Client -> resource.workDirectory
        }

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

    suspend fun deleteWorkingDirectory() {
        when (this) {
            is Server -> resource.deleteWorkingDirectory()
            is Client -> resource.deleteWorkingDirectory()
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

private suspend fun verifyFixturesWithOfficialCodec(
    fixtures: JsonElement,
    methodName: String,
) {
    val scratch = HostedMinecraftTestSupport.newScratchDirectory()
    try {
        OfficialCodecOracle.verify(
            fixtures = fixtures,
            loggingConfiguration = scratch.resolve("log4j2.xml"),
            methodName = methodName,
        )
    } finally {
        scratch.deleteTree()
    }
}

private const val LOOPBACK = "127.0.0.1"
private const val RPC_PATH = "/rpc"
private const val READY_PREFIX = "MINECRAFT_TEST_FIXTURE_READY "
private const val SHUTDOWN_COMMAND = "shutdown"
private const val CLOSE_OWNER_COMMAND_PREFIX = "close-owner "
private const val HOST_STOP_GRACE_MILLIS = 1_000L
private const val HOST_STOP_TIMEOUT_MILLIS = 10_000L
