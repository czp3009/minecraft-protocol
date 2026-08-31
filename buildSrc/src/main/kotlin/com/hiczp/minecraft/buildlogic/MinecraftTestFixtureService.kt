package com.hiczp.minecraft.buildlogic

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
        val officialServerRootDirectory: DirectoryProperty
        val headlessClientRootDirectory: DirectoryProperty
        val serverRuntimeDirectory: DirectoryProperty
        val codecClassesDirectory: DirectoryProperty
        val fixtureWorkRoot: DirectoryProperty
    }

    private val lock = Any()
    private val ownersByTask = mutableMapOf<String, String>()
    private var runningFixtureHost: RunningFixtureHost? = null
    private var acceptingConnections = true

    fun connectionFor(taskPath: String): MinecraftTestFixtureConnection =
        synchronized(lock) {
            check(acceptingConnections) {
                "Minecraft test fixture service is shutting down"
            }
            val runningFixtureHost = runningFixtureHost ?: startHost().also { runningFixtureHost = it }
            runningFixtureHost.requireAvailable()
            val ownerId = ownersByTask.getOrPut(taskPath) { UUID.randomUUID().toString() }
            MinecraftTestFixtureConnection(
                rpcUrl = runningFixtureHost.rpcUrl,
                ownerId = ownerId,
            )
        }

    override fun onFinish(finishEvent: FinishEvent) {
        val taskFinishEvent = finishEvent as? TaskFinishEvent ?: return
        val ownerIdAndHost = synchronized(lock) {
            val ownerId = ownersByTask.remove(taskFinishEvent.descriptor.taskPath) ?: return
            ownerId to runningFixtureHost
        }
        ownerIdAndHost.second?.closeOwner(ownerIdAndHost.first)
    }

    override fun close() {
        val runningFixtureHost = synchronized(lock) {
            acceptingConnections = false
            ownersByTask.clear()
            runningFixtureHost.also { runningFixtureHost = null }
        }
        runningFixtureHost?.close()
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
            "hosts/${UUID.randomUUID()}",
        ).absoluteFile
        check(!hostWorkRoot.exists()) {
            "Minecraft test fixture host work directory already exists: $hostWorkRoot"
        }
        val process = ProcessBuilder(
            "java",
            JvmProcessArguments.ENABLE_NATIVE_ACCESS_ALL_UNNAMED,
            "-cp",
            classpath,
            FIXTURE_HOST_MAIN_CLASS,
            parameters.minecraftVersion.get(),
            parameters.officialServerRootDirectory.get().asFile.absolutePath,
            parameters.headlessClientRootDirectory.get().asFile.absolutePath,
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
        val boundedOutput = BoundedOutput(HOST_LOG_LIMIT)
        val outputReaderFailure = AtomicReference<Throwable?>()
        val outputEnded = AtomicBoolean()
        val rpcUrl = AtomicReference<String?>()
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        boundedOutput.append(line)
                        if (line.startsWith(READY_PREFIX)) {
                            rpcUrl.compareAndSet(
                                null,
                                line.removePrefix(READY_PREFIX).takeIf(String::isNotBlank),
                            )
                            ready.countDown()
                        }
                    }
                }
            } catch (failure: Throwable) {
                outputReaderFailure.set(failure)
            } finally {
                outputEnded.set(true)
                ready.countDown()
            }
        }, "minecraft-test-fixture-host-output").apply {
            isDaemon = true
            start()
        }

        if (!ready.await(HOST_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            failFixtureHostStart(
                process = process,
                input = input,
                boundedOutput = boundedOutput,
                hostWorkRoot = hostWorkRoot,
                message = "Minecraft test fixture host did not become ready within $HOST_START_TIMEOUT_SECONDS seconds",
                cause = outputReaderFailure.get(),
            )
        }
        val resolvedRpcUrl = rpcUrl.get()
        val readerFailure = outputReaderFailure.get()
        if (
            resolvedRpcUrl == null ||
            !process.isAlive ||
            outputEnded.get() ||
            readerFailure != null
        ) {
            failFixtureHostStart(
                process = process,
                input = input,
                boundedOutput = boundedOutput,
                hostWorkRoot = hostWorkRoot,
                message = if (readerFailure != null) {
                    "Minecraft test fixture host output reader failed before readiness"
                } else {
                    "Minecraft test fixture host exited before becoming ready"
                },
                cause = readerFailure,
            )
        }
        return RunningFixtureHost(
            process = process,
            input = input,
            boundedOutput = boundedOutput,
            outputEnded = outputEnded,
            outputReaderFailure = outputReaderFailure,
            rpcUrl = resolvedRpcUrl,
            hostWorkRoot = hostWorkRoot,
        )
    }
}

data class MinecraftTestFixtureInfrastructure(
    val service: Provider<MinecraftTestFixtureService>,
    val hostClasspath: FileCollection,
)

fun Project.applyMinecraftTestFixtureServiceConvention(
    minecraftTestFixtureOutputs: MinecraftTestFixtureOutputs,
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
    val assembleHostRuntime = tasks.register(
        "assembleMinecraftTestFixtureHostRuntime",
        Sync::class.java,
    ) { task ->
        task.group = "minecraft fixtures"
        task.description = "Assemble the JVM Minecraft test fixture host runtime."
        task.from(hostRuntime)
        task.into(hostRuntimeDirectory)
    }
    val prepareHostRuntime = tasks.register(
        "prepareMinecraftTestFixtureHostRuntime",
    ) { task ->
        task.group = "minecraft fixtures"
        task.description = "Prepare the JVM Minecraft test fixture host runtime."
        task.dependsOn(assembleHostRuntime)
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
        registration.parameters.officialServerRootDirectory.set(
            minecraftTestFixtureOutputs.officialServerRootDirectory,
        )
        registration.parameters.headlessClientRootDirectory.set(
            minecraftTestFixtureOutputs.headlessClientRootDirectory,
        )
        registration.parameters.serverRuntimeDirectory.set(
            minecraftTestFixtureOutputs.serverRuntimeDirectory,
        )
        registration.parameters.codecClassesDirectory.set(
            minecraftTestFixtureOutputs.codecClassesDirectory,
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
    val minecraftTestFixtureInfrastructure = MinecraftTestFixtureInfrastructure(
        service = service,
        hostClasspath = preparedHostRuntime,
    )
    extensions.add("minecraftTestFixtureInfrastructure", minecraftTestFixtureInfrastructure)
    return minecraftTestFixtureInfrastructure
}

private abstract class MinecraftTestFixtureEventRegistrar @Inject constructor(
    private val buildEventsListenerRegistry: BuildEventsListenerRegistry,
) {
    fun register(service: Provider<MinecraftTestFixtureService>) {
        buildEventsListenerRegistry.onTaskCompletion(service)
    }
}

private class RunningFixtureHost(
    private val process: Process,
    private val input: BufferedWriter,
    private val boundedOutput: BoundedOutput,
    private val outputEnded: AtomicBoolean,
    private val outputReaderFailure: AtomicReference<Throwable?>,
    val rpcUrl: String,
    private val hostWorkRoot: File,
) : AutoCloseable {
    private var closing = false

    fun requireAvailable() {
        val readerFailure = outputReaderFailure.get()
        if (!process.isAlive || outputEnded.get() || readerFailure != null) {
            throw IllegalStateException(
                "Minecraft test fixture host is unavailable:\n${boundedOutput.text()}",
                readerFailure,
            )
        }
    }

    fun closeOwner(ownerId: String) {
        synchronized(input) {
            if (closing || !process.isAlive) return
            try {
                input.write("$CLOSE_OWNER_COMMAND_PREFIX$ownerId")
                input.newLine()
                input.flush()
            } catch (failure: Throwable) {
                if (process.isAlive) throw failure
            }
        }
    }

    override fun close() {
        val observedProcesses = linkedMapOf<Long, ProcessHandle>()
        observeProcessTree(process, observedProcesses)
        var closeFailure: Throwable? = null
        val startedClosing = synchronized(input) {
            if (closing) {
                false
            } else {
                closing = true
                if (process.isAlive) {
                    try {
                        input.write(SHUTDOWN_COMMAND)
                        input.newLine()
                    } catch (failure: Throwable) {
                        closeFailure = failure
                    }
                }
                try {
                    input.close()
                } catch (failure: Throwable) {
                    closeFailure?.addSuppressed(failure)
                        ?: run { closeFailure = failure }
                }
                true
            }
        }
        if (!startedClosing) return
        var hostExited = !process.isAlive
        try {
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
                    "Minecraft test fixture host process tree did not exit:\n${boundedOutput.text()}"
                }
            } catch (failure: Throwable) {
                closeFailure?.addSuppressed(failure)
                    ?: run { closeFailure = failure }
            }
        }
        try {
            hostWorkRoot.toPath().deleteTree()
        } catch (failure: Throwable) {
            closeFailure?.addSuppressed(failure)
                ?: run { closeFailure = failure }
        }
        closeFailure?.let { throw it }
    }
}

private fun failFixtureHostStart(
    process: Process,
    input: BufferedWriter,
    boundedOutput: BoundedOutput,
    hostWorkRoot: File,
    message: String,
    cause: Throwable?,
): Nothing {
    val failure = IllegalStateException("$message:\n${boundedOutput.text()}", cause)
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
        hostWorkRoot.toPath().deleteTree()
    }.onFailure(failure::addSuppressed)
    throw failure
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

internal const val FIXTURE_RPC_URL_ENV = "MINECRAFT_TEST_FIXTURE_RPC_URL"
internal const val FIXTURE_OWNER_ID_ENV = "MINECRAFT_TEST_FIXTURE_OWNER_ID"

private const val FIXTURE_HOST_MAIN_CLASS = "com.hiczp.minecraft.test.host.MinecraftTestFixtureHostKt"
internal const val MINECRAFT_TEST_FIXTURE_HOST_PROJECT = ":minecraft-test-fixture-host"
private const val READY_PREFIX = "MINECRAFT_TEST_FIXTURE_READY "
private const val HOST_LOG_LIMIT = 200_000
private const val HOST_START_TIMEOUT_SECONDS = 30L
private const val HOST_GRACEFUL_SHUTDOWN_TIMEOUT_SECONDS = 30L
private const val HOST_FORCED_SHUTDOWN_TIMEOUT_SECONDS = 5L
private const val SHUTDOWN_COMMAND = "shutdown"
private const val CLOSE_OWNER_COMMAND_PREFIX = "close-owner "
