@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.hiczp.minecraft.test

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * Every test executable has its own singleton, registry, and cleanup scope.
 * Callers normally close a resource with `use`; [closeAll] is a best-effort
 * fallback for test-framework lifecycle hooks.
 */
object MinecraftTestSupport {
    private val cleanupScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
                CoroutineName("minecraft-test-resource-cleanup"),
    )
    private val registryMutex = Mutex()
    private val resources = linkedSetOf<ManagedMinecraftTestResource>()
    private val resourceCount = MutableStateFlow(0)

    internal val layout: MinecraftTestLayout by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        MinecraftTestLayout.discover()
    }

    init {
        installMinecraftTestShutdownHook(
            closeResources = ::closeAll,
            awaitCleanup = ::awaitCleanup,
        )
    }

    /** The repository-wide Minecraft version selected by the build. */
    val minecraftVersion: String
        get() = layout.minecraftVersion

    /** Starts a ready official server in a newly allocated UUID directory. */
    suspend fun newOfficialServer(
        configuration: OfficialMinecraftServerConfiguration =
            OfficialMinecraftServerConfiguration(),
    ): OfficialMinecraftServerResource {
        val workDirectory = layout.newRuntimeDirectory(
            MinecraftRuntimeKind.OFFICIAL_SERVER,
        )
        return try {
            OfficialMinecraftServerResource.start(
                layout = layout,
                workDirectory = workDirectory,
                configuration = configuration,
            ).also { resource ->
                resource.attach(
                    manage(workDirectory, resource::cleanup),
                )
            }
        } catch (failure: Throwable) {
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
    ): HeadlessMinecraftClientResource {
        val workDirectory = layout.newRuntimeDirectory(
            MinecraftRuntimeKind.OFFICIAL_CLIENT,
        )
        return try {
            HeadlessMinecraftClientResource.start(
                layout = layout,
                workDirectory = workDirectory,
                configuration = configuration,
            ).also { resource ->
                resource.attach(
                    manage(workDirectory, resource::cleanup),
                )
            }
        } catch (failure: Throwable) {
            runCatching { workDirectory.deleteTree() }
                .onFailure { cleanupFailure ->
                    failure.addSuppressed(cleanupFailure)
                }
            throw failure
        }
    }

    /** Allocates a collision-safe report path under the owning module build. */
    fun reportFile(name: String): Path = layout.reportFile(name)

    /** Allocates a collision-safe temporary file path for the current test. */
    fun temporaryFile(name: String): Path = layout.temporaryFile(name)

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
                resource.markClosed()
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

    val isOpen: Boolean
        get() = state.load() == STATE_OPEN

    override fun close() {
        if (state.compareAndSet(STATE_OPEN, STATE_CLOSING)) {
            MinecraftTestSupport.scheduleCleanup(this)
        }
    }

    fun markClosed() {
        state.store(STATE_CLOSED)
    }

    private companion object {
        const val STATE_OPEN = 0
        const val STATE_CLOSING = 1
        const val STATE_CLOSED = 2
    }
}

internal expect fun installMinecraftTestShutdownHook(
    closeResources: () -> Unit,
    awaitCleanup: suspend () -> Unit,
)
