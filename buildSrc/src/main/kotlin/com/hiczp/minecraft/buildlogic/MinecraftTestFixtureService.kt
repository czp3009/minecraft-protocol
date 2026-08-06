package com.hiczp.minecraft.buildlogic

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Sync
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import java.io.BufferedWriter
import java.io.File
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject

data class MinecraftTestFixtureConnection(
    val rpcUrl: String,
    val ownerId: String,
)

abstract class MinecraftTestFixtureService :
    BuildService<MinecraftTestFixtureService.Parameters>,
    OperationCompletionListener,
    AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val hostClasspathDirectory: DirectoryProperty
        val hostWorkingDirectory: DirectoryProperty
        val minecraftVersion: Property<String>
        val serverCacheDirectory: DirectoryProperty
        val clientCacheDirectory: DirectoryProperty
        val versionMetadataFile: RegularFileProperty
        val headlessLauncherFile: RegularFileProperty
        val serverRuntimeDirectory: DirectoryProperty
        val codecClassesDirectory: DirectoryProperty
        val fixtureWorkRoot: DirectoryProperty
    }

    private val lock = Any()
    private val ownersByTask = mutableMapOf<String, String>()
    private var host: RunningFixtureHost? = null
    private var acceptingConnections = true

    fun connectionFor(taskPath: String): MinecraftTestFixtureConnection =
        synchronized(lock) {
            check(acceptingConnections) {
                "Minecraft test fixture service is shutting down"
            }
            val runningHost = host ?: startHost().also { host = it }
            val ownerId = ownersByTask.getOrPut(taskPath, ::newOwnerId)
            MinecraftTestFixtureConnection(
                rpcUrl = runningHost.rpcUrl,
                ownerId = ownerId,
            )
        }

    override fun onFinish(finishEvent: FinishEvent) {
        val task = finishEvent as? TaskFinishEvent ?: return
        val ownerIdAndHost = synchronized(lock) {
            val ownerId = ownersByTask.remove(task.descriptor.taskPath) ?: return
            ownerId to host
        }
        ownerIdAndHost.second?.closeOwner(ownerIdAndHost.first)
    }

    override fun close() {
        val runningHost = synchronized(lock) {
            acceptingConnections = false
            ownersByTask.clear()
            host.also { host = null }
        }
        runningHost?.close()
    }

    private fun startHost(): RunningFixtureHost {
        val classpath = parameters.hostClasspathDirectory.get().asFile
            .walkTopDown()
            .filter(File::isFile)
            .sortedBy(File::getAbsolutePath)
            .joinToString(File.pathSeparator, transform = File::getAbsolutePath)
        check(classpath.isNotEmpty()) {
            "Minecraft test fixture host classpath is empty"
        }
        val fixtureWorkRoot = parameters.fixtureWorkRoot.get().asFile
        val hostWorkRoot = File(
            fixtureWorkRoot,
            "hosts/${newOwnerId()}",
        ).absoluteFile
        check(!hostWorkRoot.exists()) {
            "Minecraft test fixture host work directory already exists: $hostWorkRoot"
        }
        val process = ProcessBuilder(
            "java",
            "-cp",
            classpath,
            FIXTURE_HOST_MAIN_CLASS,
            parameters.minecraftVersion.get(),
            parameters.serverCacheDirectory.get().asFile.absolutePath,
            parameters.clientCacheDirectory.get().asFile.absolutePath,
            parameters.versionMetadataFile.get().asFile.absolutePath,
            parameters.headlessLauncherFile.get().asFile.absolutePath,
            parameters.serverRuntimeDirectory.get().asFile.absolutePath,
            parameters.codecClassesDirectory.get().asFile.absolutePath,
            hostWorkRoot.absolutePath,
            MinecraftTestFixtureLimits.MAX_PARALLEL_USAGES.toString(),
        )
            .directory(parameters.hostWorkingDirectory.get().asFile)
            .redirectErrorStream(true)
            .start()
        val input = process.outputStream.bufferedWriter()

        val ready = CountDownLatch(1)
        val output = BoundedOutput(HOST_LOG_LIMIT)
        var rpcUrl: String? = null
        Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    output.append(line)
                    if (line.startsWith(READY_PREFIX)) {
                        rpcUrl = line.removePrefix(READY_PREFIX).takeIf(String::isNotBlank)
                        ready.countDown()
                    }
                }
            }
            ready.countDown()
        }, "minecraft-test-fixture-host-output").apply {
            isDaemon = true
            start()
        }

        if (!ready.await(HOST_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            failFixtureHostStart(
                process = process,
                input = input,
                output = output,
                hostWorkRoot = hostWorkRoot,
                message = "Minecraft test fixture host did not become ready within $HOST_START_TIMEOUT_SECONDS seconds",
            )
        }
        val resolvedRpcUrl = rpcUrl
        if (resolvedRpcUrl == null || !process.isAlive) {
            failFixtureHostStart(
                process = process,
                input = input,
                output = output,
                hostWorkRoot = hostWorkRoot,
                message = "Minecraft test fixture host exited before becoming ready",
            )
        }
        return RunningFixtureHost(
            process = process,
            input = input,
            output = output,
            rpcUrl = resolvedRpcUrl,
            hostWorkRoot = hostWorkRoot,
        )
    }
}

class MinecraftTestFixtureInfrastructure(
    val service: Provider<MinecraftTestFixtureService>,
    val hostClasspath: FileCollection,
)

fun Project.applyMinecraftTestFixtureServiceConvention(
    fixtureOutputs: OfficialMinecraftFixtureOutputs,
): MinecraftTestFixtureInfrastructure {
    check(this == rootProject) {
        "Minecraft test fixture service must be registered on the root project"
    }
    val hostRuntime = configurations.create("minecraftTestFixtureHostRuntime") {
        it.isCanBeConsumed = false
        it.isCanBeResolved = true
        it.isTransitive = true
        it.description = "Runtime classpath for the JVM Minecraft test fixture host"
    }
    dependencies.add(
        hostRuntime.name,
        dependencies.project(
            mapOf(
                "path" to MINECRAFT_TEST_FIXTURE_HOST_PROJECT,
                "configuration" to "runtimeElements",
            ),
        ),
    )
    val hostRuntimeDirectory = layout.buildDirectory.dir("minecraft-test-support/fixture-host-runtime")
    val prepareHostRuntime = tasks.register(
        "prepareMinecraftTestFixtureHostRuntime",
        Sync::class.java,
    ) { task ->
        task.group = "verification"
        task.description = "Prepare the JVM Minecraft test fixture host runtime."
        task.from(hostRuntime)
        task.into(hostRuntimeDirectory)
    }
    val preparedHostRuntime = files(hostRuntimeDirectory).apply {
        builtBy(prepareHostRuntime)
    }
    val service = gradle.sharedServices.registerIfAbsent(
        "minecraftTestFixtureService",
        MinecraftTestFixtureService::class.java,
    ) { registration ->
        registration.parameters.hostClasspathDirectory.set(
            hostRuntimeDirectory,
        )
        registration.parameters.hostWorkingDirectory.set(
            layout.projectDirectory,
        )
        registration.parameters.minecraftVersion.set(
            MinecraftTarget.MINECRAFT_VERSION,
        )
        registration.parameters.serverCacheDirectory.set(
            fixtureOutputs.serverCacheDirectory,
        )
        registration.parameters.clientCacheDirectory.set(
            fixtureOutputs.clientCacheDirectory,
        )
        registration.parameters.versionMetadataFile.set(
            fixtureOutputs.versionMetadataFile,
        )
        registration.parameters.headlessLauncherFile.set(
            fixtureOutputs.headlessLauncherFile,
        )
        registration.parameters.serverRuntimeDirectory.set(
            fixtureOutputs.serverRuntimeDirectory,
        )
        registration.parameters.codecClassesDirectory.set(
            fixtureOutputs.codecClassesDirectory,
        )
        registration.parameters.fixtureWorkRoot.set(
            layout.buildDirectory.dir("minecraft-test-support"),
        )
        registration.maxParallelUsages.set(
            MinecraftTestFixtureLimits.MAX_PARALLEL_USAGES,
        )
    }
    objects.newInstance(MinecraftTestFixtureEventRegistrar::class.java)
        .register(service)
    val infrastructure = MinecraftTestFixtureInfrastructure(
        service = service,
        hostClasspath = preparedHostRuntime,
    )
    extensions.add("minecraftTestFixtureInfrastructure", infrastructure)
    return infrastructure
}

private abstract class MinecraftTestFixtureEventRegistrar @Inject constructor(
    private val events: BuildEventsListenerRegistry,
) {
    fun register(service: Provider<MinecraftTestFixtureService>) {
        events.onTaskCompletion(service)
    }
}

private class RunningFixtureHost(
    private val process: Process,
    private val input: BufferedWriter,
    private val output: BoundedOutput,
    val rpcUrl: String,
    private val hostWorkRoot: File,
) : AutoCloseable {
    fun closeOwner(ownerId: String) {
        if (!process.isAlive) return
        runCatching {
            writeCommand("$CLOSE_OWNER_COMMAND_PREFIX$ownerId")
        }
    }

    override fun close() {
        val observedProcesses = linkedMapOf<Long, ProcessHandle>()
        observeProcessTree(process, observedProcesses)
        var closeFailure: Throwable? = null
        var hostExited = !process.isAlive
        try {
            if (process.isAlive) {
                runCatching {
                    synchronized(input) {
                        input.write(SHUTDOWN_COMMAND)
                        input.newLine()
                        input.close()
                    }
                }
            }
            if (!hostExited) {
                hostExited = process.waitFor(
                    HOST_GRACEFUL_SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }
        } catch (failure: Throwable) {
            closeFailure?.addSuppressed(failure)
                ?: run { closeFailure = failure }
        }
        observeProcessTree(process, observedProcesses)
        if (
            !hostExited ||
            observedProcesses.values.any(ProcessHandle::isAlive)
        ) {
            try {
                forceProcessTree(process, observedProcesses)
                check(
                    awaitProcessTreeExit(
                        observedProcesses,
                        HOST_FORCED_SHUTDOWN_TIMEOUT_SECONDS,
                    ),
                ) {
                    "Minecraft test fixture host process tree did not exit:\n${output.text()}"
                }
            } catch (failure: Throwable) {
                closeFailure?.addSuppressed(failure)
                    ?: run { closeFailure = failure }
            }
        }
        try {
            if (hostWorkRoot.exists()) {
                check(hostWorkRoot.deleteRecursively()) {
                    "Could not delete Minecraft test fixture host work directory: $hostWorkRoot"
                }
            }
        } catch (failure: Throwable) {
            closeFailure?.addSuppressed(failure)
                ?: run { closeFailure = failure }
        }
        closeFailure?.let { throw it }
    }

    private fun writeCommand(command: String) {
        synchronized(input) {
            input.write(command)
            input.newLine()
            input.flush()
        }
    }
}

private fun failFixtureHostStart(
    process: Process,
    input: BufferedWriter,
    output: BoundedOutput,
    hostWorkRoot: File,
    message: String,
): Nothing {
    val failure = IllegalStateException("$message:\n${output.text()}")
    val observedProcesses = linkedMapOf<Long, ProcessHandle>()
    runCatching { input.close() }
        .onFailure(failure::addSuppressed)
    runCatching {
        observeProcessTree(process, observedProcesses)
        forceProcessTree(process, observedProcesses)
        check(
            awaitProcessTreeExit(
                observedProcesses,
                HOST_FORCED_SHUTDOWN_TIMEOUT_SECONDS,
            ),
        ) {
            "Minecraft test fixture host process tree did not exit after startup failure"
        }
    }.onFailure(failure::addSuppressed)
    runCatching {
        if (hostWorkRoot.exists()) {
            check(hostWorkRoot.deleteRecursively()) {
                "Could not delete Minecraft test fixture host work directory: $hostWorkRoot"
            }
        }
    }.onFailure(failure::addSuppressed)
    throw failure
}

private fun observeProcessTree(
    process: Process,
    observedProcesses: MutableMap<Long, ProcessHandle>,
) {
    if (process.isAlive) {
        runCatching {
            process.descendants().use { descendants ->
                descendants.forEach { handle ->
                    observedProcesses.putIfAbsent(handle.pid(), handle)
                }
            }
        }
    }
    val hostHandle = process.toHandle()
    observedProcesses.putIfAbsent(hostHandle.pid(), hostHandle)
}

private fun forceProcessTree(
    process: Process,
    observedProcesses: Map<Long, ProcessHandle>,
) {
    val hostPid = process.pid()
    observedProcesses.values
        .filter { handle -> handle.pid() != hostPid }
        .asReversed()
        .plus(observedProcesses[hostPid])
        .filterNotNull()
        .filter(ProcessHandle::isAlive)
        .forEach { handle -> runCatching { handle.destroyForcibly() } }
}

private fun awaitProcessTreeExit(
    observedProcesses: Map<Long, ProcessHandle>,
    timeoutSeconds: Long,
): Boolean {
    val exits = observedProcesses.values
        .filter(ProcessHandle::isAlive)
        .map(ProcessHandle::onExit)
        .toTypedArray()
    if (exits.isEmpty()) return true
    return try {
        CompletableFuture.allOf(*exits)
            .get(timeoutSeconds, TimeUnit.SECONDS)
        true
    } catch (_: TimeoutException) {
        false
    }
}

private class BoundedOutput(
    private val maximumCharacters: Int,
) {
    private val content = StringBuilder()

    @Synchronized
    fun append(line: String) {
        content.append(line).append('\n')
        if (content.length > maximumCharacters) {
            content.delete(0, content.length - maximumCharacters)
        }
    }

    @Synchronized
    fun text(): String = content.toString()
}

private fun newOwnerId(): String = UUID.randomUUID().toString()

internal const val FIXTURE_RPC_URL_ENV = "MINECRAFT_TEST_FIXTURE_RPC_URL"
internal const val FIXTURE_OWNER_ID_ENV = "MINECRAFT_TEST_FIXTURE_OWNER_ID"

private const val FIXTURE_HOST_MAIN_CLASS = "com.hiczp.minecraft.test.host.MinecraftTestFixtureHostKt"
private const val MINECRAFT_TEST_FIXTURE_HOST_PROJECT = ":minecraft-test-fixture-host"
private const val READY_PREFIX = "MINECRAFT_TEST_FIXTURE_READY "
private const val HOST_LOG_LIMIT = 200_000
private const val HOST_START_TIMEOUT_SECONDS = 30L
private const val HOST_GRACEFUL_SHUTDOWN_TIMEOUT_SECONDS = 30L
private const val HOST_FORCED_SHUTDOWN_TIMEOUT_SECONDS = 5L
private const val SHUTDOWN_COMMAND = "shutdown"
private const val CLOSE_OWNER_COMMAND_PREFIX = "close-owner "
