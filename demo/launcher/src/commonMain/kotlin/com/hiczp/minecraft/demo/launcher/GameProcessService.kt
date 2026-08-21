package com.hiczp.minecraft.demo.launcher

import com.kgit2.kommand.io.BufferedReader
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import kotlin.random.Random

internal data class GameOutputSnapshot(
    val lines: List<GameOutputLine> = emptyList(),
    val running: Boolean = true,
    val exitCode: Int? = null,
)

internal class GameOutputBuffer(
    secrets: Collection<String>,
    private val capacity: Int = OUTPUT_CAPACITY,
    private val maximumLineLength: Int = MAXIMUM_OUTPUT_LINE_LENGTH,
) {
    private val secrets = secrets.filter(String::isNotEmpty).distinct()
    private val mutex = Mutex()
    private val lines = ArrayDeque<GameOutputLine>()
    private var nextSequence = 0L
    private val _state = MutableStateFlow(GameOutputSnapshot())
    val state: StateFlow<GameOutputSnapshot> = _state.asStateFlow()

    suspend fun append(source: OutputSource, text: String) = mutex.withLock {
        val cleaned = redactSecrets(sanitizeTerminalText(text), secrets)
        val displayLines = if (cleaned.isEmpty()) listOf("") else cleaned.chunked(maximumLineLength)
        displayLines.forEach { line ->
            lines += GameOutputLine(nextSequence++, source, line)
            while (lines.size > capacity) lines.removeFirst()
        }
        publish(running = true, exitCode = null)
    }

    suspend fun finish(exitCode: Int) = mutex.withLock {
        lines += GameOutputLine(nextSequence++, OutputSource.SYSTEM, "Game process exited (exit $exitCode)")
        while (lines.size > capacity) lines.removeFirst()
        publish(running = false, exitCode = exitCode)
    }

    private fun publish(running: Boolean, exitCode: Int?) {
        _state.value = GameOutputSnapshot(lines.toList(), running, exitCode)
    }
}

internal class OutputChunkDecoder(
    private val maximumLineLength: Int = MAXIMUM_OUTPUT_LINE_LENGTH,
) {
    private var current = StringBuilder()

    fun feed(chunk: String, endOfInput: Boolean = false): List<String> {
        val completed = mutableListOf<String>()
        chunk.forEach { character ->
            when (character) {
                '\n' -> commit(completed)
                '\r' -> current = StringBuilder()
                else -> {
                    current.append(character)
                    if (current.length >= maximumLineLength) commit(completed)
                }
            }
        }
        if (endOfInput && current.isNotEmpty()) commit(completed)
        return completed
    }

    private fun commit(lines: MutableList<String>) {
        lines += current.toString()
        current = StringBuilder()
    }
}

internal interface GameProcessRuntime {
    fun cleanupStaleArgumentFiles()

    fun outputBuffer(plan: LaunchPlan): GameOutputBuffer

    suspend fun launch(plan: LaunchPlan, output: GameOutputBuffer)
}

internal class GameProcessService(
    private val fileSystem: FileSystem,
    private val launcherRoot: Path,
) : GameProcessRuntime {
    override fun cleanupStaleArgumentFiles() {
        fileSystem.listOrNull(launcherRoot).orEmpty()
            .filter { it.name.startsWith(JAVA_ARGUMENT_FILE_PREFIX) && it.name.endsWith(JAVA_ARGUMENT_FILE_SUFFIX) }
            .forEach { fileSystem.delete(it, mustExist = false) }
    }

    suspend fun probeJavaMajor(): Int = withContext(Dispatchers.Default) {
        val output = Command("java")
            .arg("-version")
            // Kommand 2.3.0 maps Null stdin to Java's output-only DISCARD redirect on JVM.
            .stdin(Stdio.Inherit)
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Pipe)
            .output()
        require(output.status == 0) { "java -version failed (exit ${output.status})" }
        parseJavaMajor("${output.stdout.orEmpty()}\n${output.stderr.orEmpty()}")
    }

    override fun outputBuffer(plan: LaunchPlan): GameOutputBuffer = GameOutputBuffer(
        listOfNotNull(plan.sensitiveAccessToken),
    )

    override suspend fun launch(plan: LaunchPlan, output: GameOutputBuffer) {
        val actualJavaMajor = probeJavaMajor()
        plan.requiredJavaMajor?.let { required ->
            require(actualJavaMajor >= required) {
                "This version requires Java $required, but Java $actualJavaMajor was found on PATH"
            }
        }
        val argumentFile = launcherRoot /
                "$JAVA_ARGUMENT_FILE_PREFIX${Random.nextLong().toULong().toString(16)}$JAVA_ARGUMENT_FILE_SUFFIX"
        val argumentFileContent = encodeJavaArgumentFile(plan.javaArguments)
        val secret = plan.sensitiveAccessToken
        require(secret == null || secret !in argumentFileContent) {
            "Access token must not enter the Java argument file"
        }
        fileSystem.write(argumentFile, mustCreate = true) {
            writeUtf8(argumentFileContent)
            flush()
        }
        try {
            runProcess(plan, argumentFile, output)
        } finally {
            fileSystem.delete(argumentFile, mustExist = false)
        }
    }

    private suspend fun runProcess(plan: LaunchPlan, argumentFile: Path, output: GameOutputBuffer) = coroutineScope {
        val child = Command("java")
            .args(buildList {
                add("@${argumentFile}")
                add(plan.mainClass)
                addAll(plan.gameArguments)
            })
            .cwd(plan.workingDirectory)
            .stdin(Stdio.Inherit)
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Pipe)
            .spawn()
        try {
            val stdout = async(Dispatchers.Default) {
                drain(checkNotNull(child.bufferedStdout()), OutputSource.STDOUT, output)
            }
            val stderr = async(Dispatchers.Default) {
                drain(checkNotNull(child.bufferedStderr()), OutputSource.STDERR, output)
            }
            val exitCode = withContext(Dispatchers.Default) { child.wait() }
            stdout.await()
            stderr.await()
            output.finish(exitCode)
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.Default) {
                try {
                    child.kill()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    private suspend fun drain(reader: BufferedReader, source: OutputSource, output: GameOutputBuffer) {
        val decoder = OutputChunkDecoder()
        while (true) {
            val line = reader.readLine() ?: break
            decoder.feed(normalizeProcessLineEnding(line)).forEach { output.append(source, it) }
        }
        decoder.feed("", endOfInput = true).forEach { output.append(source, it) }
    }
}

// Kommand Native removes only LF from CRLF, while JVM BufferedReader removes the complete line terminator.
// Restore LF and normalize CRLF so terminal decoding receives the same input on every target.
internal fun normalizeProcessLineEnding(line: String): String = "$line\n".replace("\r\n", "\n")

internal fun parseJavaMajor(text: String): Int {
    val version = JAVA_VERSION_PATTERN.find(text)?.groupValues?.get(1)
        ?: throw IllegalArgumentException("Unable to parse java -version output")
    val first = version.substringBefore('.').toIntOrNull()
        ?: throw IllegalArgumentException("Unable to parse the Java major version")
    return if (first == 1) {
        version.substringAfter('.').substringBefore('.').toIntOrNull()
            ?: throw IllegalArgumentException("Unable to parse the legacy Java version")
    } else {
        first
    }
}

internal fun encodeJavaArgumentFile(arguments: List<String>): String = buildString {
    arguments.forEach { argument ->
        require('\n' !in argument && '\r' !in argument) { "Java argument contains a line break" }
        append('"')
        argument.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
        append('\n')
    }
}

internal fun sanitizeTerminalText(value: String): String {
    val withoutOsc = OSC_PATTERN.replace(value, "")
    val withoutAnsi = ANSI_PATTERN.replace(withoutOsc, "")
    return buildString(withoutAnsi.length) {
        withoutAnsi.forEach { character ->
            when {
                character == '\t' -> append("    ")
                character.code < 32 || character.code == 127 -> append('?')
                else -> append(character)
            }
        }
    }
}

internal fun redactSecrets(value: String, secrets: Collection<String>): String {
    var redacted = value
    secrets.filter(String::isNotEmpty).forEach { secret -> redacted = redacted.replace(secret, "<redacted>") }
    return redacted
}

private const val OUTPUT_CAPACITY = 10_000
private const val MAXIMUM_OUTPUT_LINE_LENGTH = 4_096
private const val JAVA_ARGUMENT_FILE_PREFIX = "launcher-java-"
private const val JAVA_ARGUMENT_FILE_SUFFIX = ".args"
private val JAVA_VERSION_PATTERN = Regex("""version\s+"([^"]+)""", RegexOption.IGNORE_CASE)
private val ANSI_PATTERN = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")
private val OSC_PATTERN = Regex("""\u001B][^\u0007]*(?:\u0007|\u001B\\)""")
