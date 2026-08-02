package com.hiczp.minecraft.test

import io.matthewnelson.kmp.file.toFile
import io.matthewnelson.kmp.process.Process
import io.matthewnelson.kmp.process.Stdio
import io.matthewnelson.kmp.process.changeDir
import kotlinx.io.files.Path

internal actual suspend fun startPlatformProcess(
    command: List<String>,
    workingDirectory: Path,
    onOutput: (String) -> Unit,
): PlatformProcess {
    val process = Process.Builder(command.first())
        .args(command.drop(1))
        .changeDir(workingDirectory.toString().toFile())
        .stdin(Stdio.Pipe)
        .stdout(Stdio.Pipe)
        .stderr(Stdio.Pipe)
        .createProcessAsync()
    process.stdoutFeed { line -> line?.let(onOutput) }
    process.stderrFeed { line -> line?.let(onOutput) }
    return KmpPlatformProcess(process)
}

private class KmpPlatformProcess(
    private val process: Process,
) : PlatformProcess {
    override suspend fun sendLine(line: String) {
        val input = checkNotNull(process.input) {
            "Test process stdin is not piped"
        }
        input.writeAsync("$line\n".encodeToByteArray())
        input.flushAsync()
    }

    override suspend fun awaitExit(): Int {
        val code = process.waitForAsync()
        process.destroy()
        process.stdoutWaiter().awaitStopAsync()
        process.stderrWaiter().awaitStopAsync()
        return code
    }

    override fun destroy() {
        process.destroy()
    }
}
