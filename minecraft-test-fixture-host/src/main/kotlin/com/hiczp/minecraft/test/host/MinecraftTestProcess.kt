package com.hiczp.minecraft.test.host

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class MinecraftTestProcess private constructor(
    private val process: RunningProcess,
    private val log: ProcessLog,
    private val exit: CompletableDeferred<Int>,
    private val shutdownCommand: String?,
) {
    private val commandMutex = Mutex()
    private val shutdownMutex = Mutex()
    private var shutdownRequested = false

    val isAlive: Boolean
        get() = !exit.isCompleted

    val exitCode: Int
        get() = log.snapshot.value.exitCode
            ?: error("Test process has not exited")

    fun logText(): String = log.snapshot.value.text

    val outputSequence: Long
        get() = log.snapshot.value.outputSequence

    fun containsLogAfter(marker: String, afterSequence: Long): Boolean =
        log.snapshot.value.containsAfter(afterSequence, marker)

    fun requireAlive(context: String = "Test process") {
        val current = log.snapshot.value
        current.failure?.let { failure ->
            throw IllegalStateException(
                "$context output reader failed:\n${current.text}",
                failure,
            )
        }
        check(current.exitCode == null) {
            "$context exited with ${current.exitCode}:\n${current.text}"
        }
    }

    suspend fun waitForLog(
        marker: String,
        timeout: Duration,
    ) {
        waitForLogAfter(marker, 0L, timeout)
    }

    suspend fun waitForLogAfter(
        marker: String,
        afterSequence: Long,
        timeout: Duration,
    ) {
        require(marker.isNotEmpty()) { "Log marker is empty" }
        require(afterSequence >= 0L) {
            "Output sequence must not be negative"
        }
        require(timeout.isPositive() && timeout.isFinite()) {
            "Log timeout must be positive and finite"
        }
        val observed = withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeout) {
                log.snapshot.first { snapshot ->
                    snapshot.containsAfter(afterSequence, marker) ||
                            snapshot.exitCode != null ||
                            snapshot.failure != null
                }
            }
        } ?: error(
            """
            |Test process did not emit '$marker' within $timeout:
            |${logText()}
            """.trimMargin(),
        )
        observed.failure?.let { failure ->
            throw IllegalStateException(
                """
                |Test process output failed before log marker '$marker':
                |${observed.text}
                """.trimMargin(),
                failure,
            )
        }
        check(observed.containsAfter(afterSequence, marker)) {
            """
            |Test process exited with ${observed.exitCode} before log marker '$marker':
            |${observed.text}
            """.trimMargin()
        }
    }

    suspend fun sendLine(line: String) {
        require('\n' !in line && '\r' !in line) {
            "sendLine accepts exactly one line"
        }
        commandMutex.withLock {
            requireAlive()
            process.sendLine(line)
        }
    }

    suspend fun sendLineAndWait(
        line: String,
        marker: String,
        timeout: Duration,
    ): Long {
        require('\n' !in line && '\r' !in line) {
            "sendLineAndWait accepts exactly one line"
        }
        commandMutex.withLock {
            requireAlive()
            val sequence = outputSequence
            process.sendLine(line)
            waitForLogAfter(marker, sequence, timeout)
            return sequence
        }
    }

    suspend fun awaitExit(): Int = exit.await()

    suspend fun awaitExitWithin(timeout: Duration): Int? {
        require(timeout.isPositive() && timeout.isFinite()) {
            "Exit timeout must be positive and finite"
        }
        return withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeout) { exit.await() }
        }
    }

    suspend fun terminate(
        gracefulTimeout: Duration = PROCESS_GRACEFUL_SHUTDOWN_TIMEOUT,
        forcedTimeout: Duration = PROCESS_FORCED_SHUTDOWN_TIMEOUT,
    ): Int {
        requestStop()
        awaitExitWithin(gracefulTimeout)?.let { return it }
        forceStop()
        return checkNotNull(awaitExitWithin(forcedTimeout)) {
            "Test process did not exit after forced termination:\n${logText()}"
        }
    }

    internal suspend fun requestStop() {
        shutdownMutex.withLock {
            if (shutdownRequested || !process.isAlive) return
            shutdownRequested = true
            val command = shutdownCommand
            if (command == null) {
                process.destroy()
            } else {
                runCatching {
                    commandMutex.withLock {
                        process.sendLine(command)
                    }
                }
                    .onFailure { process.destroy() }
            }
        }
    }

    internal fun forceStop() {
        process.destroyForcibly()
    }

    companion object {
        suspend fun start(
            command: List<String>,
            workingDirectory: Path,
            threadName: String,
            shutdownCommand: String? = null,
        ): MinecraftTestProcess {
            require(command.isNotEmpty()) { "Process command is empty" }
            require(threadName.isNotBlank()) { "Process name is blank" }
            workingDirectory.ensureDirectory()

            val log = ProcessLog()
            val process = RunningProcess(
                process = ProcessBuilder(command)
                    .directory(File(workingDirectory.toString()))
                    .redirectErrorStream(true)
                    .start(),
                onOutput = { line ->
                    log.append(line)
                },
            )
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineName(threadName),
            )
            val exit = CompletableDeferred<Int>()
            val testProcess = MinecraftTestProcess(
                process = process,
                log = log,
                exit = exit,
                shutdownCommand = shutdownCommand,
            )
            val shutdownState = MinecraftTestProcesses.register(testProcess)
            scope.launch {
                try {
                    val exitCode = process.awaitExit()
                    log.exit(exitCode)
                    exit.complete(exitCode)
                } catch (failure: Throwable) {
                    log.fail(failure)
                    exit.completeExceptionally(failure)
                } finally {
                    MinecraftTestProcesses.unregister(testProcess)
                }
            }
            when (shutdownState) {
                PROCESS_SHUTDOWN_REQUESTED -> testProcess.requestStop()
                PROCESS_FORCE_REQUESTED -> testProcess.forceStop()
            }
            return testProcess
        }
    }
}

internal object MinecraftTestProcesses {
    private val lock = Any()
    private val processes = linkedSetOf<MinecraftTestProcess>()
    private val processCount = MutableStateFlow(0)
    private var shutdownState = PROCESS_RUNNING

    fun register(process: MinecraftTestProcess): Int = synchronized(lock) {
        check(processes.add(process)) {
            "Minecraft test process was registered twice"
        }
        processCount.value = processes.size
        shutdownState
    }

    fun unregister(process: MinecraftTestProcess) {
        synchronized(lock) {
            processes.remove(process)
            processCount.value = processes.size
        }
    }

    suspend fun requestStopAll() {
        val snapshot = synchronized(lock) {
            if (shutdownState < PROCESS_SHUTDOWN_REQUESTED) {
                shutdownState = PROCESS_SHUTDOWN_REQUESTED
            }
            processes.toList()
        }
        coroutineScope {
            snapshot.forEach { process ->
                launch { process.requestStop() }
            }
        }
    }

    fun forceStopAll() {
        val snapshot = synchronized(lock) {
            shutdownState = PROCESS_FORCE_REQUESTED
            processes.toList()
        }
        snapshot.forEach(MinecraftTestProcess::forceStop)
    }

    suspend fun awaitEmpty() {
        processCount.first { count -> count == 0 }
    }
}

private class RunningProcess(
    private val process: Process,
    onOutput: (String) -> Unit,
) {
    val isAlive: Boolean
        get() = process.isAlive

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
                CoroutineName("minecraft-test-process-output"),
    )
    private val inputMutex = Mutex()
    private val input = process.outputStream.bufferedWriter()
    private val outputReader = scope.launch {
        runCatching {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach(onOutput)
            }
        }
    }

    suspend fun sendLine(line: String) {
        inputMutex.withLock {
            input.write(line)
            input.newLine()
            input.flush()
        }
    }

    suspend fun awaitExit(): Int {
        val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
        // A child spawned by the observed process may inherit both pipes. Close
        // stdin first so that such a child can observe EOF instead of keeping
        // the output reader alive indefinitely.
        runCatching { input.close() }
        runCatching { process.inputStream.close() }
        outputReader.join()
        scope.cancel()
        return exitCode
    }

    fun destroy() {
        runCatching { input.close() }
        terminateTree { handle -> handle.destroy() }
    }

    fun destroyForcibly() {
        runCatching { input.close() }
        terminateTree { handle -> handle.destroyForcibly() }
    }

    private fun terminateTree(terminate: (ProcessHandle) -> Boolean) {
        val descendants = runCatching {
            process.descendants().use { handles -> handles.toList() }
        }.getOrDefault(emptyList())
        (descendants.asReversed() + process.toHandle()).forEach { handle ->
            if (handle.isAlive) runCatching { terminate(handle) }
        }
    }
}

private class ProcessLog {
    val snapshot = MutableStateFlow(ProcessSnapshot())

    fun append(line: String) {
        snapshot.update { current ->
            val nextLine = SequencedOutputLine(
                sequence = current.outputSequence + 1L,
                text = "$line\n",
            )
            val retained = current.lines.toMutableList().apply {
                add(nextLine)
                var characters = sumOf { it.text.length }
                while (
                    size > 1 &&
                    characters > MAXIMUM_LOG_CHARACTERS
                ) {
                    characters -= removeFirst().text.length
                }
            }
            current.copy(
                lines = retained,
                outputSequence = nextLine.sequence,
            )
        }
    }

    fun exit(code: Int) {
        snapshot.update { current -> current.copy(exitCode = code) }
    }

    fun fail(cause: Throwable) {
        snapshot.update { current -> current.copy(failure = cause) }
    }
}

private data class ProcessSnapshot(
    val lines: List<SequencedOutputLine> = emptyList(),
    val outputSequence: Long = 0L,
    val exitCode: Int? = null,
    val failure: Throwable? = null,
) {
    val text: String
        get() = buildString {
            lines.forEach { append(it.text) }
        }

    fun containsAfter(sequence: Long, marker: String): Boolean =
        lines.any { it.sequence > sequence && marker in it.text }
}

private data class SequencedOutputLine(
    val sequence: Long,
    val text: String,
)

private const val MAXIMUM_LOG_CHARACTERS = 200_000
private const val PROCESS_RUNNING = 0
private const val PROCESS_SHUTDOWN_REQUESTED = 1
private const val PROCESS_FORCE_REQUESTED = 2
internal val PROCESS_GRACEFUL_SHUTDOWN_TIMEOUT =
    10.seconds
internal val PROCESS_FORCED_SHUTDOWN_TIMEOUT =
    5.seconds
