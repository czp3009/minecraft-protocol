package com.hiczp.minecraft.test

import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories

/**
 * A Testcontainers-style process resource owned directly by a JVM test.
 *
 * Output is continuously drained, bounded in memory, and optionally mirrored
 * to a test report file. Closing the resource terminates its complete process
 * tree, so callers can use it safely with `use`.
 */
class MinecraftTestProcess private constructor(
    private val process: Process,
    private val output: BufferedWriter,
    private val log: StringBuilder,
    private val logThread: Thread,
) : AutoCloseable {
    val isAlive: Boolean
        get() = process.isAlive

    val exitCode: Int
        get() = process.exitValue()

    fun logText(): String = synchronized(log) { log.toString() }

    fun logContains(text: String): Boolean =
        synchronized(log) { text in log }

    fun requireAlive(context: String = "Test process") {
        check(process.isAlive) {
            "$context exited with ${process.exitValue()}:\n${logText()}"
        }
    }

    fun waitForLog(
        marker: String,
        timeout: Duration,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            requireAlive("Test process before log marker '$marker'")
            if (logContains(marker)) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error(
            "Test process did not emit '$marker' within $timeout:\n" +
                    logText(),
        )
    }

    fun waitForPort(
        host: String,
        port: Int,
        timeout: Duration,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            requireAlive("Test process before $host:$port became reachable")
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(host, port),
                        CONNECT_TIMEOUT_MILLIS,
                    )
                    return
                }
            } catch (_: IOException) {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
        }
        error(
            "Test process did not listen on $host:$port within $timeout:\n" +
                    logText(),
        )
    }

    fun sendLine(line: String) {
        require('\n' !in line && '\r' !in line) {
            "sendLine accepts exactly one line"
        }
        requireAlive()
        synchronized(output) {
            output.appendLine(line)
            output.flush()
        }
    }

    fun awaitExit(): Int {
        process.waitFor()
        return process.exitValue()
    }

    fun awaitExit(timeout: Duration): Int? =
        if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.exitValue()
        } else {
            null
        }

    override fun close() {
        val descendants = process.toHandle().descendants().toList()
        descendants.asReversed().forEach { handle ->
            if (handle.isAlive) handle.destroy()
        }
        if (process.isAlive) process.destroy()
        if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            descendants.asReversed().forEach { handle ->
                if (handle.isAlive) handle.destroyForcibly()
            }
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        }
        runCatching {
            synchronized(output) {
                output.close()
            }
        }
        logThread.join(LOG_JOIN_TIMEOUT)
    }

    companion object {
        fun start(
            command: List<String>,
            workingDirectory: Path,
            threadName: String,
            logFile: Path? = null,
            maximumLogCharacters: Int = DEFAULT_MAXIMUM_LOG_CHARACTERS,
            environment: Map<String, String> = emptyMap(),
        ): MinecraftTestProcess {
            require(command.isNotEmpty()) { "Process command is empty" }
            require(maximumLogCharacters > 0)
            workingDirectory.createDirectories()
            logFile?.parent?.createDirectories()
            val process = ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(environment)
                }
                .start()
            val writer = process.outputWriter()
            val content = StringBuilder()
            val thread = Thread.ofVirtual().name(threadName).start {
                val mirror = logFile?.let(Files::newBufferedWriter)
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            mirror?.apply {
                                appendLine(line)
                                flush()
                            }
                            synchronized(content) {
                                content.appendLine(line)
                                if (content.length > maximumLogCharacters) {
                                    content.delete(
                                        0,
                                        content.length -
                                                maximumLogCharacters,
                                    )
                                }
                            }
                        }
                    }
                } catch (failure: IOException) {
                    if (process.isAlive) throw failure
                } finally {
                    mirror?.close()
                }
            }
            return MinecraftTestProcess(
                process = process,
                output = writer,
                log = content,
                logThread = thread,
            )
        }
    }
}

fun MinecraftTestEnvironment.startOfficialServer(
    workDirectory: Path,
    threadName: String,
    logFile: Path? = null,
    maximumLogCharacters: Int = 300_000,
): MinecraftTestProcess {
    val server = officialServer()
    check(server.requiredJavaMajor == currentJavaMajorVersion()) {
        "Minecraft $minecraftVersion requires Java " +
                server.requiredJavaMajor
    }
    return MinecraftTestProcess.start(
        command = listOf(
            javaExecutable.toString(),
            "-Djava.awt.headless=true",
            "-jar",
            server.jar.toString(),
            "nogui",
        ),
        workingDirectory = workDirectory,
        threadName = threadName,
        logFile = logFile,
        maximumLogCharacters = maximumLogCharacters,
    )
}

private fun currentJavaMajorVersion(): Int =
    Runtime.version().feature()

private const val DEFAULT_MAXIMUM_LOG_CHARACTERS = 200_000
private const val POLL_INTERVAL_MILLIS = 100L
private const val CONNECT_TIMEOUT_MILLIS = 250
private val STOP_TIMEOUT = Duration.ofSeconds(10)
private val LOG_JOIN_TIMEOUT = Duration.ofSeconds(5)
