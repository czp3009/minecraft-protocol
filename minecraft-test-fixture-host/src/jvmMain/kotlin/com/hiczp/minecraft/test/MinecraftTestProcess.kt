package com.hiczp.minecraft.test

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.time.Duration

internal class MinecraftTestProcess private constructor(
    private val process: RunningProcess,
    private val log: ProcessLog,
    private val exit: CompletableDeferred<Int>,
) : AutoCloseable {
    val isAlive: Boolean
        get() = !exit.isCompleted

    val exitCode: Int
        get() = log.snapshot.value.exitCode
            ?: error("Test process has not exited")

    fun logText(): String = log.snapshot.value.text

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
        require(marker.isNotEmpty()) { "Log marker is empty" }
        require(timeout.isPositive() && timeout.isFinite()) {
            "Log timeout must be positive and finite"
        }
        val observed = withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeout) {
                log.snapshot.first { snapshot ->
                    marker in snapshot.text ||
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
        check(marker in observed.text) {
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
        requireAlive()
        process.sendLine(line)
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

    override fun close() {
        process.destroy()
    }

    companion object {
        suspend fun start(
            command: List<String>,
            workingDirectory: Path,
            threadName: String,
        ): MinecraftTestProcess {
            require(command.isNotEmpty()) { "Process command is empty" }
            require(threadName.isNotBlank()) { "Process name is blank" }
            workingDirectory.ensureDirectory()

            val log = ProcessLog()
            val process = RunningProcess(
                process = ProcessBuilder(command)
                    .directory(java.io.File(workingDirectory.toString()))
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
            scope.launch {
                try {
                    val exitCode = process.awaitExit()
                    log.exit(exitCode)
                    exit.complete(exitCode)
                } catch (failure: Throwable) {
                    log.fail(failure)
                    exit.completeExceptionally(failure)
                }
            }
            return MinecraftTestProcess(process, log, exit)
        }
    }
}

private class RunningProcess(
    private val process: Process,
    onOutput: (String) -> Unit,
) {
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
        runCatching { process.inputStream.close() }
        if (process.isAlive) process.destroy()
    }
}

private class ProcessLog {
    val snapshot = MutableStateFlow(ProcessSnapshot())

    fun append(line: String) {
        snapshot.update { current ->
            val combined = "${current.text}$line\n"
            current.copy(
                text = if (combined.length <= MAXIMUM_LOG_CHARACTERS) {
                    combined
                } else {
                    combined.takeLast(MAXIMUM_LOG_CHARACTERS)
                },
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
    val text: String = "",
    val exitCode: Int? = null,
    val failure: Throwable? = null,
)

private const val MAXIMUM_LOG_CHARACTERS = 200_000
