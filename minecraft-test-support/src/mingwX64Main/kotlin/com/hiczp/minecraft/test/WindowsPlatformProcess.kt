@file:OptIn(ExperimentalForeignApi::class)

package com.hiczp.minecraft.test

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.indexOf
import kotlinx.io.readByteArray
import platform.windows.*
import kotlin.concurrent.Volatile

internal actual suspend fun startPlatformProcess(
    command: List<String>,
    workingDirectory: Path,
    onOutput: (String) -> Unit,
): PlatformProcess = memScoped {
    val security = alloc<SECURITY_ATTRIBUTES>().apply {
        nLength = sizeOf<SECURITY_ATTRIBUTES>().convert()
        lpSecurityDescriptor = null
        bInheritHandle = TRUE
    }
    val stdout = createPipe(security.ptr)
    val stdin = createPipe(security.ptr)
    val processInformation = alloc<PROCESS_INFORMATION>()
    val job = checkNotNull(CreateJobObjectW(null, null)) {
        "Creating Windows process job failed with ${GetLastError()}"
    }
    try {
        checkWindows(
            SetHandleInformation(
                stdout.read,
                HANDLE_FLAG_INHERIT.convert(),
                0u,
            ),
            "marking stdout read handle non-inheritable",
        )
        checkWindows(
            SetHandleInformation(
                stdin.write,
                HANDLE_FLAG_INHERIT.convert(),
                0u,
            ),
            "marking stdin write handle non-inheritable",
        )

        val startup = alloc<STARTUPINFOW>().apply {
            cb = sizeOf<STARTUPINFOW>().convert()
            dwFlags = STARTF_USESTDHANDLES.convert()
            hStdInput = stdin.read
            hStdOutput = stdout.write
            hStdError = stdout.write
        }
        val commandLine = command.joinToString(
            separator = " ",
            transform = ::quoteWindowsArgument,
        )
        checkWindows(
            SetInformationJobObject(
                job,
                JobObjectExtendedLimitInformation,
                alloc<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>().apply {
                    BasicLimitInformation.LimitFlags =
                        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE.convert()
                }.ptr,
                sizeOf<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>().convert(),
            ),
            "configuring Windows process job",
        )
        checkWindows(
            CreateProcessW(
                lpApplicationName = null,
                lpCommandLine = commandLine.wcstr.ptr,
                lpProcessAttributes = null,
                lpThreadAttributes = null,
                bInheritHandles = TRUE,
                dwCreationFlags =
                    (CREATE_NO_WINDOW or CREATE_SUSPENDED).convert(),
                lpEnvironment = null,
                lpCurrentDirectory = workingDirectory.toString(),
                lpStartupInfo = startup.ptr,
                lpProcessInformation = processInformation.ptr,
            ),
            "starting ${command.first()}",
        )
        checkWindows(
            AssignProcessToJobObject(job, processInformation.hProcess),
            "assigning process to its Windows job",
        )
        check(ResumeThread(processInformation.hThread) != UInt.MAX_VALUE) {
            "Resuming Windows process failed with ${GetLastError()}"
        }
    } catch (failure: Throwable) {
        processInformation.hProcess?.let { process ->
            TerminateProcess(process, 1u)
            CloseHandle(process)
        }
        processInformation.hThread?.let { thread -> CloseHandle(thread) }
        CloseHandle(job)
        stdout.close()
        stdin.close()
        throw failure
    }

    CloseHandle(stdout.write)
    CloseHandle(stdin.read)
    CloseHandle(processInformation.hThread)
    WindowsPlatformProcess(
        processHandle = checkNotNull(processInformation.hProcess),
        processId = processInformation.dwProcessId.toInt(),
        job = job,
        stdin = stdin.write,
        output = stdout.read,
        onOutput = onOutput,
    )
}

private class WindowsPlatformProcess(
    private val processHandle: HANDLE,
    private val processId: Int,
    private val job: HANDLE,
    private val stdin: HANDLE,
    output: HANDLE,
    onOutput: (String) -> Unit,
) : PlatformProcess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stdinLock = Mutex()
    private val outputReader = scope.async {
        readOutput(output, processHandle, onOutput)
    }

    @Volatile
    private var completed = false

    @Volatile
    private var destroyRequested = false

    override suspend fun sendLine(line: String) {
        val content = "$line\n".encodeToByteArray()
        stdinLock.withLock {
            writeAll(stdin, content)
        }
    }

    override suspend fun awaitExit(): Int {
        while (true) {
            when (
                val wait = WaitForSingleObject(
                    processHandle,
                    PROCESS_POLL_INTERVAL_MILLIS,
                )
            ) {
                0u -> break
                WAIT_TIMEOUT.toUInt() -> yield()
                else -> error(
                    "Waiting for Windows process $processId failed with $wait (error ${GetLastError()})",
                )
            }
        }
        val exitCode = memScoped {
            val value = alloc<UIntVar>()
            checkWindows(
                GetExitCodeProcess(processHandle, value.ptr),
                "reading exit code for process $processId",
            )
            value.value.toInt()
        }
        completed = true
        outputReader.await()
        CloseHandle(stdin)
        CloseHandle(job)
        CloseHandle(processHandle)
        return exitCode
    }

    override fun destroy() {
        if (completed || destroyRequested) return
        destroyRequested = true
        if (WaitForSingleObject(processHandle, 0u) == WAIT_TIMEOUT.toUInt()) {
            checkWindows(
                TerminateJobObject(job, 1u),
                "terminating Windows process job $processId",
            )
        }
        check(WaitForSingleObject(processHandle, INFINITE) == 0u) {
            "Waiting for terminated Windows process $processId failed with ${GetLastError()}"
        }
    }
}

private suspend fun readOutput(
    handle: HANDLE,
    processHandle: HANDLE,
    onOutput: (String) -> Unit,
) {
    val pending = Buffer()
    val chunk = ByteArray(OUTPUT_BUFFER_SIZE)
    var processExited = false
    try {
        while (true) {
            val available = availableOutputBytes(handle)
            if (available < 0) break
            if (available == 0) {
                if (processExited) break
                when (
                    val wait = WaitForSingleObject(
                        processHandle,
                        OUTPUT_POLL_INTERVAL_MILLIS,
                    )
                ) {
                    0u -> processExited = true
                    WAIT_TIMEOUT.toUInt() -> yield()
                    else -> error(
                        "Waiting for Windows process output failed with $wait (error ${GetLastError()})",
                    )
                }
                continue
            }

            val count = readOutputBytes(
                handle = handle,
                destination = chunk,
                maximumBytes = minOf(available, chunk.size),
            )
            if (count <= 0) break
            pending.write(chunk, startIndex = 0, endIndex = count)
            pending.emitLines(onOutput)
        }
        if (!pending.exhausted()) {
            onOutput(pending.readByteArray().decodeToString().trimEnd('\r'))
        }
    } finally {
        CloseHandle(handle)
    }
}

private fun availableOutputBytes(handle: HANDLE): Int = memScoped {
    val available = alloc<UIntVar>()
    val succeeded = PeekNamedPipe(
        hNamedPipe = handle,
        lpBuffer = null,
        nBufferSize = 0u,
        lpBytesRead = null,
        lpTotalBytesAvail = available.ptr,
        lpBytesLeftThisMessage = null,
    )
    if (succeeded == FALSE) {
        val error = GetLastError()
        if (
            error == ERROR_BROKEN_PIPE.toUInt() ||
            error == ERROR_HANDLE_EOF.toUInt()
        ) {
            return@memScoped -1
        }
        error("Inspecting Windows process output failed with $error")
    }
    available.value.toInt()
}

private fun readOutputBytes(
    handle: HANDLE,
    destination: ByteArray,
    maximumBytes: Int,
): Int = memScoped {
    val read = alloc<UIntVar>()
    val succeeded = destination.usePinned { pinned ->
        ReadFile(
            handle,
            pinned.addressOf(0),
            maximumBytes.convert(),
            read.ptr,
            null,
        )
    }
    if (succeeded == FALSE) {
        val error = GetLastError()
        if (
            error == ERROR_BROKEN_PIPE.toUInt() ||
            error == ERROR_HANDLE_EOF.toUInt()
        ) {
            return@memScoped -1
        }
        error("Reading Windows process output failed with $error")
    }
    read.value.toInt()
}

private fun Buffer.emitLines(onOutput: (String) -> Unit) {
    while (true) {
        val newline = indexOf('\n'.code.toByte())
        if (newline < 0) return
        val line = readByteArray(newline.toInt() + 1)
            .decodeToString(endIndex = newline.toInt())
            .trimEnd('\r')
        onOutput(line)
    }
}

private fun writeAll(handle: HANDLE, content: ByteArray) {
    content.usePinned { pinned ->
        var offset = 0
        while (offset < content.size) {
            val count = memScoped {
                val written = alloc<UIntVar>()
                checkWindows(
                    WriteFile(
                        handle,
                        pinned.addressOf(offset),
                        (content.size - offset).convert(),
                        written.ptr,
                        null,
                    ),
                    "writing Windows process input",
                )
                written.value.toInt()
            }
            check(count > 0) { "Windows process input accepted no bytes" }
            offset += count
        }
    }
}

private data class WindowsPipe(
    val read: HANDLE,
    val write: HANDLE,
) {
    fun close() {
        CloseHandle(read)
        CloseHandle(write)
    }
}

private fun createPipe(
    security: CPointer<SECURITY_ATTRIBUTES>,
): WindowsPipe = memScoped {
    val read = alloc<HANDLEVar>()
    val write = alloc<HANDLEVar>()
    checkWindows(
        CreatePipe(read.ptr, write.ptr, security, 0u),
        "creating Windows process pipe",
    )
    WindowsPipe(
        read = checkNotNull(read.value),
        write = checkNotNull(write.value),
    )
}

private fun quoteWindowsArgument(argument: String): String {
    if (argument.isNotEmpty() && argument.none { it.isWhitespace() || it == '"' }) {
        return argument
    }
    return buildString {
        append('"')
        var backslashes = 0
        argument.forEach { character ->
            when (character) {
                '\\' -> backslashes++
                '"' -> {
                    repeat(backslashes * 2 + 1) { append('\\') }
                    append('"')
                    backslashes = 0
                }

                else -> {
                    repeat(backslashes) { append('\\') }
                    backslashes = 0
                    append(character)
                }
            }
        }
        repeat(backslashes * 2) { append('\\') }
        append('"')
    }
}

private fun checkWindows(result: Int, operation: String) {
    check(result != FALSE) {
        "Windows failed while $operation (error ${GetLastError()})"
    }
}

private const val OUTPUT_BUFFER_SIZE = 8 * 1024
private const val PROCESS_POLL_INTERVAL_MILLIS = 25u
private const val OUTPUT_POLL_INTERVAL_MILLIS = PROCESS_POLL_INTERVAL_MILLIS
