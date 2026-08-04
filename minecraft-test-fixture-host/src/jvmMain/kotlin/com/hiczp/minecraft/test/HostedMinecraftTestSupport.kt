@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.hiczp.minecraft.test

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.concurrent.atomics.AtomicInt

private val minecraftTestSupportLogger = DirectLoggerFactory.logger(
    "com.hiczp.minecraft.test.MinecraftTestSupport",
)

/**
 * Creates and owns isolated official-Minecraft process resources for tests.
 *
 * The fixture-host JVM has one registry and cleanup scope. RPC close requests
 * schedule cleanup; [closeAll] is the host-shutdown fallback.
 */
internal object HostedMinecraftTestSupport {
    private val cleanupScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
                CoroutineName("minecraft-test-resource-cleanup"),
    )
    private val registryMutex = Mutex()
    private val resources = linkedSetOf<ManagedMinecraftTestResource>()
    private val resourceCount = MutableStateFlow(0)

    private var configuredLayout: MinecraftTestLayout? = null

    internal val layout: MinecraftTestLayout
        get() = synchronized(this) {
            checkNotNull(configuredLayout) {
                "Minecraft test fixture host layout has not been configured"
            }
        }

    init {
        installMinecraftTestShutdownHook(
            closeResources = ::closeAll,
            awaitCleanup = ::awaitCleanup,
        )
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
    }

    /** Starts a ready HeadlessMC-backed official client resource. */
    suspend fun newOfficialClient(
        configuration: HeadlessMinecraftClientConfiguration,
    ): HostedHeadlessMinecraftClientResource {
        val workDirectory = layout.newRuntimeDirectory(
            MinecraftRuntimeKind.OFFICIAL_CLIENT,
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
    }

    /** Allocates a collision-safe report path below the host work root. */
    fun reportFile(name: String): Path = layout.reportFile(name)

    /** Asynchronously closes every resource still owned by this executable. */
    fun closeAll() {
        cleanupScope.launch {
            registryMutex.withLock { resources.toList() }
                .forEach(ManagedMinecraftTestResource::close)
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
            check(resources.add(resource)) {
                "Minecraft test resource was registered twice"
            }
            resourceCount.value = resources.size
        }
        return resource
    }

    internal fun scheduleCleanup(resource: ManagedMinecraftTestResource) {
        cleanupScope.launch {
            var cleanupFailure: Throwable? = null
            try {
                resource.cleanup()
            } catch (failure: Throwable) {
                cleanupFailure = failure
            }
            try {
                resource.workDirectory.deleteTree()
            } catch (failure: Throwable) {
                cleanupFailure?.addSuppressed(failure)
                    ?: run { cleanupFailure = failure }
            } finally {
                resource.markClosed(cleanupFailure)
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
    closeResources: () -> Unit,
    awaitCleanup: suspend () -> Unit,
) {
    Runtime.getRuntime().addShutdownHook(
        Thread(
            {
                closeResources()
                runBlocking { awaitCleanup() }
            },
            "minecraft-test-resource-shutdown",
        ),
    )
}
