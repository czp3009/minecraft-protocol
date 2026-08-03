package com.hiczp.minecraft.test

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration

class MinecraftTestProcess private constructor(
    private val platformProcess: PlatformProcess,
    private val log: ProcessLog,
    private val exit: CompletableDeferred<Int>,
) : AutoCloseable {
    val isAlive: Boolean
        get() = !exit.isCompleted

    val exitCode: Int
        get() = log.snapshot.value.exitCode
            ?: error("Test process has not exited")

    fun logText(): String = log.snapshot.value.text

    fun logContains(text: String): Boolean = text in logText()

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
        platformProcess.sendLine(line)
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
        platformProcess.destroy()
    }

    companion object {
        suspend fun start(
            command: List<String>,
            workingDirectory: Path,
            threadName: String,
            logFile: Path? = null,
            maximumLogCharacters: Int = DEFAULT_MAXIMUM_LOG_CHARACTERS,
        ): MinecraftTestProcess {
            require(command.isNotEmpty()) { "Process command is empty" }
            require(threadName.isNotBlank()) { "Process name is blank" }
            require(maximumLogCharacters > 0) {
                "Maximum log characters must be positive"
            }
            workingDirectory.ensureDirectory()
            logFile?.parent?.ensureDirectory()

            val log = ProcessLog(maximumLogCharacters)
            val lines = logFile?.let { Channel<String>(Channel.UNLIMITED) }
            val platformProcess = startPlatformProcess(
                command = command,
                workingDirectory = workingDirectory,
                onOutput = { line ->
                    log.append(line)
                    lines?.trySend(line)
                },
            )
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineName(threadName),
            )
            val writer = logFile?.let { output ->
                scope.launch {
                    runCatching {
                        SystemFileSystem.sink(output).buffered().use { sink ->
                            for (line in checkNotNull(lines)) {
                                sink.write("$line\n".encodeToByteArray())
                                sink.flush()
                            }
                        }
                    }.onFailure(log::fail)
                }
            }
            val exit = CompletableDeferred<Int>()
            scope.launch {
                try {
                    val exitCode = platformProcess.awaitExit()
                    lines?.close()
                    writer?.join()
                    log.exit(exitCode)
                    exit.complete(exitCode)
                } catch (failure: Throwable) {
                    lines?.close(failure)
                    writer?.join()
                    log.fail(failure)
                    exit.completeExceptionally(failure)
                }
            }
            return MinecraftTestProcess(platformProcess, log, exit)
        }
    }
}

internal interface PlatformProcess {
    suspend fun sendLine(line: String)

    suspend fun awaitExit(): Int

    fun destroy()
}

internal expect suspend fun startPlatformProcess(
    command: List<String>,
    workingDirectory: Path,
    onOutput: (String) -> Unit,
): PlatformProcess

private class ProcessLog(
    private val maximumCharacters: Int,
) {
    val snapshot = MutableStateFlow(ProcessSnapshot())

    fun append(line: String) {
        snapshot.update { current ->
            val combined = "${current.text}$line\n"
            current.copy(
                text = if (combined.length <= maximumCharacters) {
                    combined
                } else {
                    combined.takeLast(maximumCharacters)
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

private const val DEFAULT_MAXIMUM_LOG_CHARACTERS = 200_000
