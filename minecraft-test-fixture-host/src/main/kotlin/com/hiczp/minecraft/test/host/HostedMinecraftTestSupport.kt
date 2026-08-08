@file:OptIn(ExperimentalAtomicApi::class)

package com.hiczp.minecraft.test.host

import com.hiczp.minecraft.test.HeadlessMinecraftClientConfiguration
import com.hiczp.minecraft.test.OfficialMinecraftServerConfiguration
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private val minecraftTestSupportLogger = DirectLoggerFactory.logger(
    "com.hiczp.minecraft.test.host.HostedMinecraftTestSupport",
)

/**
 * Creates and owns isolated official-Minecraft process resources for tests.
 *
 * The fixture-host JVM has one registry and cleanup scope. RPC close requests
 * schedule cleanup; [shutdown] is the bounded host-shutdown fallback.
 */
internal object HostedMinecraftTestSupport {
    private val cleanupScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
                CoroutineName("minecraft-test-resource-cleanup"),
    )
    private val registryMutex = Mutex()
    private val resources = linkedSetOf<ManagedMinecraftTestResource>()
    private val resourceCount = MutableStateFlow(0)
    private val resourceCreationCount = MutableStateFlow(0)
    private val shutdownState = AtomicInt(SHUTDOWN_NOT_STARTED)
    private val shutdownCompleted = CompletableDeferred<Unit>()

    private var acceptingResourceCreations = true

    private var configuredLayout: MinecraftTestLayout? = null

    internal val layout: MinecraftTestLayout
        get() = synchronized(this) {
            checkNotNull(configuredLayout) {
                "Minecraft test fixture host layout has not been configured"
            }
        }

    init {
        installMinecraftTestShutdownHook(::shutdown)
    }

    internal fun configure(selected: MinecraftTestLayout) {
        synchronized(this) {
            val existing = configuredLayout
            check(existing == null || existing == selected) {
                "Minecraft test fixture host layout was configured twice"
            }
            configuredLayout = selected
        }
    }

    /** Starts a ready official server in a newly allocated work directory. */
    suspend fun newOfficialServer(
        configuration: OfficialMinecraftServerConfiguration =
            OfficialMinecraftServerConfiguration(),
    ): HostedOfficialMinecraftServerResource {
        beginResourceCreation()
        try {
            val workDirectory = layout.newRuntimeDirectory(
                MinecraftRuntimeKind.OFFICIAL_SERVER,
            )
            var startedResource: HostedOfficialMinecraftServerResource? = null
            return try {
                HostedOfficialMinecraftServerResource.start(
                    layout = layout,
                    workDirectory = workDirectory,
                    configuration = configuration,
                ).also { resource ->
                    startedResource = resource
                    resource.attach(
                        manage(workDirectory, resource::cleanup),
                    )
                }
            } catch (failure: Throwable) {
                startedResource?.let { resource ->
                    runCatching { resource.cleanup() }
                        .onFailure { cleanupFailure ->
                            failure.addSuppressed(cleanupFailure)
                        }
                }
                runCatching { workDirectory.deleteTree() }
                    .onFailure { cleanupFailure ->
                        failure.addSuppressed(cleanupFailure)
                    }
                throw failure
            }
        } finally {
            finishResourceCreation()
        }
    }

    /** Starts a title-ready HeadlessMC-backed client resource. */
    suspend fun newHeadlessClient(
        configuration: HeadlessMinecraftClientConfiguration,
    ): HostedHeadlessMinecraftClientResource {
        beginResourceCreation()
        try {
            val workDirectory = layout.newRuntimeDirectory(
                MinecraftRuntimeKind.HEADLESS_CLIENT,
            )
            var startedResource: HostedHeadlessMinecraftClientResource? = null
            return try {
                HostedHeadlessMinecraftClientResource.start(
                    layout = layout,
                    workDirectory = workDirectory,
                    configuration = configuration,
                ).also { resource ->
                    startedResource = resource
                    resource.attach(
                        manage(workDirectory, resource::cleanup),
                    )
                }
            } catch (failure: Throwable) {
                startedResource?.let { resource ->
                    runCatching { resource.cleanup() }
                        .onFailure { cleanupFailure ->
                            failure.addSuppressed(cleanupFailure)
                        }
                }
                runCatching { workDirectory.deleteTree() }
                    .onFailure { cleanupFailure ->
                        failure.addSuppressed(cleanupFailure)
                    }
                throw failure
            }
        } finally {
            finishResourceCreation()
        }
    }

    internal suspend fun stopAcceptingResourceCreations() {
        registryMutex.withLock {
            acceptingResourceCreations = false
        }
    }

    internal suspend fun shutdown() {
        if (!shutdownState.compareAndSet(
                SHUTDOWN_NOT_STARTED,
                SHUTDOWN_IN_PROGRESS,
            )
        ) {
            shutdownCompleted.await()
            return
        }
        try {
            stopAcceptingResourceCreations()
            closeAll()
            MinecraftTestProcesses.requestStopAll()
            val stoppedGracefully = withTimeoutOrNull(
                PROCESS_GRACEFUL_SHUTDOWN_TIMEOUT,
            ) {
                awaitQuiescence()
                true
            } ?: false
            if (!stoppedGracefully) {
                minecraftTestSupportLogger.warn {
                    "Minecraft test processes exceeded the graceful shutdown timeout; forcing termination"
                }
                MinecraftTestProcesses.forceStopAll()
                check(
                    withTimeoutOrNull(PROCESS_FORCED_SHUTDOWN_TIMEOUT) {
                        awaitQuiescence()
                        true
                    } == true,
                ) {
                    "Minecraft test processes or resource directories remained after forced termination"
                }
            }
            shutdownState.store(SHUTDOWN_COMPLETE)
            shutdownCompleted.complete(Unit)
        } catch (failure: Throwable) {
            shutdownCompleted.completeExceptionally(failure)
            throw failure
        }
    }

    internal fun newScratchDirectory(): Path = layout.newScratchDirectory()

    internal suspend fun manageTestResource(
        workDirectory: Path,
        cleanup: suspend () -> Unit,
    ): ManagedMinecraftTestResource = manage(workDirectory, cleanup)

    internal suspend fun awaitCleanup() {
        resourceCount.first { count -> count == 0 }
    }

    private suspend fun manage(
        workDirectory: Path,
        cleanup: suspend () -> Unit,
    ): ManagedMinecraftTestResource {
        val resource = ManagedMinecraftTestResource(
            workDirectory = workDirectory,
            cleanup = cleanup,
        )
        registryMutex.withLock {
            check(acceptingResourceCreations) {
                "Minecraft test fixture host is shutting down"
            }
            check(resources.add(resource)) {
                "Minecraft test resource was registered twice"
            }
            resourceCount.value = resources.size
        }
        return resource
    }

    private suspend fun beginResourceCreation() {
        registryMutex.withLock {
            check(acceptingResourceCreations) {
                "Minecraft test fixture host is shutting down"
            }
            resourceCreationCount.value += 1
        }
    }

    private suspend fun finishResourceCreation() {
        registryMutex.withLock {
            resourceCreationCount.value -= 1
        }
    }

    private suspend fun awaitQuiescence() {
        resourceCreationCount.first { count -> count == 0 }
        resourceCount.first { count -> count == 0 }
        MinecraftTestProcesses.awaitEmpty()
    }

    private suspend fun closeAll() {
        registryMutex.withLock { resources.toList() }
            .forEach(ManagedMinecraftTestResource::close)
    }

    internal fun scheduleCleanup(resource: ManagedMinecraftTestResource) {
        cleanupScope.launch {
            val cleanupFailure = runCatching {
                resource.cleanup()
                resource.workDirectory.deleteTree()
            }.exceptionOrNull()
            try {
                resource.markClosed(cleanupFailure)
            } finally {
                registryMutex.withLock {
                    resources.remove(resource)
                    resourceCount.value = resources.size
                }
            }
            cleanupFailure?.let { failure ->
                minecraftTestSupportLogger.warn(failure) {
                    "Could not completely clean Minecraft test resource ${resource.workDirectory}"
                }
            }
        }
    }
}

internal class ManagedMinecraftTestResource(
    val workDirectory: Path,
    val cleanup: suspend () -> Unit,
) : AutoCloseable {
    private val state = AtomicInt(STATE_OPEN)
    private val closed = CompletableDeferred<Unit>()

    val isOpen: Boolean
        get() = state.load() == STATE_OPEN

    override fun close() {
        if (state.compareAndSet(STATE_OPEN, STATE_CLOSING)) {
            HostedMinecraftTestSupport.scheduleCleanup(this)
        }
    }

    fun invokeOnCleanupCompletion(handler: (Throwable?) -> Unit) {
        closed.invokeOnCompletion(handler)
    }

    suspend fun awaitCleanup() {
        closed.await()
    }

    fun markClosed(failure: Throwable?) {
        state.store(STATE_CLOSED)
        if (failure == null) {
            closed.complete(Unit)
        } else {
            closed.completeExceptionally(failure)
        }
    }

    private companion object {
        const val STATE_OPEN = 0
        const val STATE_CLOSING = 1
        const val STATE_CLOSED = 2
    }
}

internal fun installMinecraftTestShutdownHook(
    shutdown: suspend () -> Unit,
) {
    Runtime.getRuntime().addShutdownHook(
        Thread(
            {
                runBlocking { shutdown() }
            },
            "minecraft-test-resource-shutdown",
        ),
    )
}

private const val SHUTDOWN_NOT_STARTED = 0
private const val SHUTDOWN_IN_PROGRESS = 1
private const val SHUTDOWN_COMPLETE = 2
