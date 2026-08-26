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

    val hostedFixtureResources = HostedFixtureResources(maximumParallelUsages)
    val embeddedServer = embeddedServer(CIO, host = LOOPBACK, port = 0) {
        fixtureHostModule(hostedFixtureResources)
    }
    embeddedServer.start(wait = false)
    var failure: Throwable? = null
    try {
        val engineConnectorConfig = embeddedServer.engine.resolvedConnectors().single()
        // Standard output is the machine-readable startup channel consumed by the Build Service.
        println("${READY_PREFIX}ws://$LOOPBACK:${engineConnectorConfig.port}$RPC_PATH")
        System.out.flush()
        withContext(Dispatchers.IO) {
            while (true) {
                val command = readlnOrNull() ?: break
                when {
                    command == SHUTDOWN_COMMAND -> break
                    command.startsWith(CLOSE_OWNER_COMMAND_PREFIX) ->
                        hostedFixtureResources.closeOwner(
                            command.removePrefix(CLOSE_OWNER_COMMAND_PREFIX),
                        )
                }
            }
        }
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        withContext(NonCancellable) {
            var cleanupFailure: Throwable? = null
            try {
                hostedFixtureResources.shutdown()
            } catch (caught: Throwable) {
                cleanupFailure = caught
            }
            try {
                embeddedServer.stop(
                    gracePeriodMillis = HOST_STOP_GRACE_MILLIS,
                    timeoutMillis = HOST_STOP_TIMEOUT_MILLIS,
                )
            } catch (caught: Throwable) {
                cleanupFailure?.addSuppressed(caught)
                    ?: run { cleanupFailure = caught }
            }
            cleanupFailure?.let { caught ->
                failure?.addSuppressed(caught) ?: throw caught
            }
        }
    }
}

private fun Application.fixtureHostModule(hostedFixtureResources: HostedFixtureResources) {
    install(Krpc) {
        serialization {
            json()
        }
    }
    routing {
        rpc(RPC_PATH) {
            registerService<MinecraftTestSupportService> {
                MinecraftTestSupportServiceServer(hostedFixtureResources)
            }
        }
    }
}

private class MinecraftTestSupportServiceServer(
    private val hostedFixtureResources: HostedFixtureResources,
) : MinecraftTestSupportService {
    override suspend fun newOfficialServer(
        ownerId: String,
        officialMinecraftServerConfiguration: OfficialMinecraftServerConfiguration,
    ): OfficialMinecraftServer = hostedFixtureResources.newOfficialServer(ownerId, officialMinecraftServerConfiguration)

    override suspend fun newHeadlessClient(
        ownerId: String,
        headlessMinecraftClientConfiguration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClient = hostedFixtureResources.newHeadlessClient(ownerId, headlessMinecraftClientConfiguration)

    override suspend fun connectHeadlessClient(
        headlessMinecraftClient: HeadlessMinecraftClient,
        minecraftTestEndpoint: MinecraftTestEndpoint,
    ): HeadlessMinecraftClientState =
        hostedFixtureResources.hostedClient(headlessMinecraftClient).connect(minecraftTestEndpoint)

    override suspend fun headlessClientState(
        headlessMinecraftClient: HeadlessMinecraftClient,
    ): HeadlessMinecraftClientState = hostedFixtureResources.hostedClient(headlessMinecraftClient).state()

    override suspend fun disconnectHeadlessClient(
        headlessMinecraftClient: HeadlessMinecraftClient,
    ) {
        hostedFixtureResources.hostedClient(headlessMinecraftClient).disconnect()
    }

    override suspend fun sendHeadlessClientCommand(
        headlessMinecraftClient: HeadlessMinecraftClient,
        command: String,
        expectedNewOutput: String?,
        timeout: Duration,
    ) {
        hostedFixtureResources.hostedClient(headlessMinecraftClient).sendCommand(
            command = command,
            expectedNewOutput = expectedNewOutput,
            timeout = timeout,
        )
    }

    override suspend fun status(
        minecraftTestResource: MinecraftTestResource,
    ): MinecraftTestResourceStatus = hostedFixtureResources.status(minecraftTestResource)

    override suspend fun logText(
        minecraftTestResource: MinecraftTestResource,
    ): String = hostedFixtureResources.logText(minecraftTestResource)

    override suspend fun waitForLog(
        minecraftTestResource: MinecraftTestResource,
        marker: String,
        timeout: Duration,
    ) = hostedFixtureResources.waitForLog(
        minecraftTestResource,
        marker,
        timeout,
    )

    override suspend fun sendCommand(
        officialMinecraftServer: OfficialMinecraftServer,
        command: String,
        expectedNewOutput: String?,
        timeout: Duration,
    ) {
        hostedFixtureResources.hostedServer(officialMinecraftServer).sendCommand(
            command = command,
            expectedNewOutput = expectedNewOutput,
            timeout = timeout,
        )
    }

    override suspend fun restartServer(
        officialMinecraftServer: OfficialMinecraftServer,
    ): OfficialMinecraftServer = hostedFixtureResources.restartServer(officialMinecraftServer)

    override suspend fun closeProcess(
        minecraftTestResource: MinecraftTestResource,
    ): Int = hostedFixtureResources.closeProcess(minecraftTestResource)

    override suspend fun awaitExit(
        minecraftTestResource: MinecraftTestResource,
    ): Int = hostedFixtureResources.awaitExit(minecraftTestResource)

    override suspend fun hostWorkingDirectory(
        minecraftTestResource: MinecraftTestResource,
    ): String = hostedFixtureResources.hostWorkingDirectory(minecraftTestResource)

    override suspend fun deleteWorkingDirectory(
        minecraftTestResource: MinecraftTestResource,
    ) {
        hostedFixtureResources.deleteWorkingDirectory(minecraftTestResource)
    }

    override suspend fun close(minecraftTestResource: MinecraftTestResource) {
        hostedFixtureResources.close(minecraftTestResource)
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
        officialMinecraftServerConfiguration: OfficialMinecraftServerConfiguration,
    ): OfficialMinecraftServer = withOwnerCreation(ownerId) {
        acquireResourceSlot(ownerId)
        val hostedOfficialMinecraftServerResource = try {
            HostedMinecraftTestSupport.newOfficialServer(officialMinecraftServerConfiguration)
        } catch (failure: Throwable) {
            resourceSlots.release()
            throw failure
        }
        val hosted = HostedFixtureResource.Server(ownerId, hostedOfficialMinecraftServerResource)
        hosted.invokeOnCleanupCompletion { resourceSlots.release() }
        try {
            val id = Uuid.random().toString()
            val officialMinecraftServer = hostedOfficialMinecraftServerResource.toOfficialMinecraftServer(id)
            mutex.withLock {
                checkCreationAllowed(ownerId)
                check(id !in resources) { "Fixture resource ID collision: $id" }
                resources[id] = hosted
            }
            officialMinecraftServer
        } catch (failure: Throwable) {
            hosted.close()
            throw failure
        }
    }

    suspend fun newHeadlessClient(
        ownerId: String,
        headlessMinecraftClientConfiguration: HeadlessMinecraftClientConfiguration,
    ): HeadlessMinecraftClient = withOwnerCreation(ownerId) {
        acquireResourceSlot(ownerId)
        val hostedHeadlessMinecraftClientResource = try {
            HostedMinecraftTestSupport.newHeadlessClient(headlessMinecraftClientConfiguration)
        } catch (failure: Throwable) {
            resourceSlots.release()
            throw failure
        }
        val hosted = HostedFixtureResource.Client(ownerId, hostedHeadlessMinecraftClientResource)
        hosted.invokeOnCleanupCompletion { resourceSlots.release() }
        try {
            val id = Uuid.random().toString()
            val headlessMinecraftClient = HeadlessMinecraftClient(id = id)
            mutex.withLock {
                checkCreationAllowed(ownerId)
                check(id !in resources) { "Fixture resource ID collision: $id" }
                resources[id] = hosted
            }
            headlessMinecraftClient
        } catch (failure: Throwable) {
            hosted.close()
            throw failure
        }
    }

    suspend fun hostedServer(
        officialMinecraftServer: OfficialMinecraftServer,
    ): HostedOfficialMinecraftServerResource =
        when (val hostedFixtureResource = hostedResource(officialMinecraftServer.id)) {
            is HostedFixtureResource.Server -> hostedFixtureResource.hostedOfficialMinecraftServerResource
            is HostedFixtureResource.Client -> throw IllegalArgumentException(
                "Resource ${officialMinecraftServer.id} is not an official server",
            )
        }

    suspend fun hostedClient(
        headlessMinecraftClient: HeadlessMinecraftClient,
    ): HostedHeadlessMinecraftClientResource =
        when (val hostedFixtureResource = hostedResource(headlessMinecraftClient.id)) {
            is HostedFixtureResource.Client -> hostedFixtureResource.hostedHeadlessMinecraftClientResource
            is HostedFixtureResource.Server -> throw IllegalArgumentException(
                "Resource ${headlessMinecraftClient.id} is not a HeadlessMC client",
            )
        }

    suspend fun status(
        minecraftTestResource: MinecraftTestResource,
    ): MinecraftTestResourceStatus =
        when (val hostedFixtureResource = hostedResource(minecraftTestResource.id)) {
            is HostedFixtureResource.Server -> hostedFixtureResource.hostedOfficialMinecraftServerResource.status()
            is HostedFixtureResource.Client -> hostedFixtureResource.hostedHeadlessMinecraftClientResource.status()
        }

    suspend fun logText(minecraftTestResource: MinecraftTestResource): String =
        when (val hostedFixtureResource = hostedResource(minecraftTestResource.id)) {
            is HostedFixtureResource.Server -> hostedFixtureResource.hostedOfficialMinecraftServerResource.logText()
            is HostedFixtureResource.Client -> hostedFixtureResource.hostedHeadlessMinecraftClientResource.logText()
        }

    suspend fun waitForLog(
        minecraftTestResource: MinecraftTestResource,
        marker: String,
        timeout: Duration,
    ) {
        when (val hostedFixtureResource = hostedResource(minecraftTestResource.id)) {
            is HostedFixtureResource.Server -> hostedFixtureResource.hostedOfficialMinecraftServerResource.waitForLog(
                marker,
                timeout,
            )

            is HostedFixtureResource.Client -> hostedFixtureResource.hostedHeadlessMinecraftClientResource.waitForLog(
                marker,
                timeout,
            )
        }
    }

    suspend fun restartServer(
        officialMinecraftServer: OfficialMinecraftServer,
    ): OfficialMinecraftServer {
        val hostedOfficialMinecraftServerResource = hostedServer(officialMinecraftServer)
        hostedOfficialMinecraftServerResource.restart()
        return hostedOfficialMinecraftServerResource.toOfficialMinecraftServer(officialMinecraftServer.id)
    }

    suspend fun closeProcess(minecraftTestResource: MinecraftTestResource): Int =
        when (val hostedFixtureResource = hostedResource(minecraftTestResource.id)) {
            is HostedFixtureResource.Server -> hostedFixtureResource.hostedOfficialMinecraftServerResource.closeProcess()
            is HostedFixtureResource.Client -> hostedFixtureResource.hostedHeadlessMinecraftClientResource.closeProcess()
        }

    suspend fun awaitExit(minecraftTestResource: MinecraftTestResource): Int =
        when (val hostedFixtureResource = hostedResource(minecraftTestResource.id)) {
            is HostedFixtureResource.Server -> hostedFixtureResource.hostedOfficialMinecraftServerResource.awaitExit()
            is HostedFixtureResource.Client -> hostedFixtureResource.hostedHeadlessMinecraftClientResource.awaitExit()
        }

    suspend fun hostWorkingDirectory(
        minecraftTestResource: MinecraftTestResource,
    ): String = hostedResource(minecraftTestResource.id).workDirectory
        .toAbsolutePath()
        .normalize()
        .pathString

    suspend fun deleteWorkingDirectory(minecraftTestResource: MinecraftTestResource) {
        val hostedFixtureResource = hostedResource(minecraftTestResource.id)
        hostedFixtureResource.beginWorkingDirectoryDeletion()
        withContext(NonCancellable) {
            mutex.withLock {
                if (resources[minecraftTestResource.id] === hostedFixtureResource) {
                    resources.remove(minecraftTestResource.id)
                }
            }
        }
        hostedFixtureResource.awaitCleanup()
    }

    suspend fun close(minecraftTestResource: MinecraftTestResource) {
        val hostedFixtureResource = mutex.withLock {
            resources.remove(minecraftTestResource.id) ?: return
        }
        hostedFixtureResource.close()
    }

    suspend fun closeOwner(ownerId: String) {
        val ownerClosure = mutex.withLock {
            closedOwnerIds.add(ownerId)
            val creationJobs = ownerCreationJobs.remove(ownerId)?.toList().orEmpty()
            val selected = resources.values.filter { it.ownerId == ownerId }
            resources.entries.removeAll { it.value.ownerId == ownerId }
            OwnerClosure(creationJobs, selected)
        }
        ownerClosure.creationJobs.forEach { creationJob ->
            creationJob.cancel(CancellationException("Fixture owner $ownerId was closed"))
        }
        ownerClosure.resources.forEach(HostedFixtureResource::close)
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
        val hostedOfficialMinecraftServerResource: HostedOfficialMinecraftServerResource,
    ) : HostedFixtureResource(ownerId)

    class Client(
        ownerId: String,
        val hostedHeadlessMinecraftClientResource: HostedHeadlessMinecraftClientResource,
    ) : HostedFixtureResource(ownerId)

    val workDirectory: Path
        get() = when (this) {
            is Server -> hostedOfficialMinecraftServerResource.workDirectory
            is Client -> hostedHeadlessMinecraftClientResource.workDirectory
        }

    fun close() {
        when (this) {
            is Server -> hostedOfficialMinecraftServerResource.close()
            is Client -> hostedHeadlessMinecraftClientResource.close()
        }
    }

    fun invokeOnCleanupCompletion(handler: (Throwable?) -> Unit) {
        when (this) {
            is Server -> hostedOfficialMinecraftServerResource.invokeOnCleanupCompletion(handler)
            is Client -> hostedHeadlessMinecraftClientResource.invokeOnCleanupCompletion(handler)
        }
    }

    suspend fun beginWorkingDirectoryDeletion() {
        when (this) {
            is Server -> hostedOfficialMinecraftServerResource.beginWorkingDirectoryDeletion()
            is Client -> hostedHeadlessMinecraftClientResource.beginWorkingDirectoryDeletion()
        }
    }

    suspend fun awaitCleanup() {
        when (this) {
            is Server -> hostedOfficialMinecraftServerResource.awaitCleanup()
            is Client -> hostedHeadlessMinecraftClientResource.awaitCleanup()
        }
    }
}

private fun HostedOfficialMinecraftServerResource.toOfficialMinecraftServer(
    id: String,
): OfficialMinecraftServer = OfficialMinecraftServer(
    id = id,
    minecraftTestEndpoint = minecraftTestEndpoint,
)

private suspend fun verifyFixturesWithOfficialCodec(
    fixtures: JsonElement,
    methodName: String,
) {
    val scratch = HostedMinecraftTestSupport.newScratchDirectory()
    var failure: Throwable? = null
    try {
        OfficialCodecOracle.verify(
            fixtures = fixtures,
            loggingConfiguration = scratch.resolve("log4j2.xml"),
            methodName = methodName,
        )
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        deleteTreesPreserving(failure, scratch)
    }
}

private const val LOOPBACK = "127.0.0.1"
private const val RPC_PATH = "/rpc"
private const val READY_PREFIX = "MINECRAFT_TEST_FIXTURE_READY "
private const val SHUTDOWN_COMMAND = "shutdown"
private const val CLOSE_OWNER_COMMAND_PREFIX = "close-owner "
private const val HOST_STOP_GRACE_MILLIS = 1_000L
private const val HOST_STOP_TIMEOUT_MILLIS = 10_000L
