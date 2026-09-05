package com.hiczp.minecraft.demo.launcher

import com.kgit2.kommand.Platform
import com.kgit2.kommand.platform
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio

internal data class LauncherPlatform(
    val osName: String,
    val architecture: String,
    val classpathSeparator: String,
    val platformKey: String,
    val osVersion: String,
) {
    companion object {
        fun current(): LauncherPlatform {
            val command = when (platform) {
                // Windows rules include the build number, which Java's os.version does not expose.
                Platform.MINGW_X64 -> Command("cmd.exe").args("/d", "/c", "ver")
                Platform.LINUX_X64, Platform.LINUX_ARM64 -> Command("uname").arg("-r")
                Platform.MACOS_X64, Platform.MACOS_ARM64 -> Command("sw_vers").arg("-productVersion")
            }
            val output = command
                .stdin(Stdio.Inherit)
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Pipe)
                .output()
            check(output.status == 0) { "Unable to detect OS version using ${command.command} (exit ${output.status})" }
            val osVersion = parseOperatingSystemVersion(output.stdout.orEmpty())
            return when (platform) {
                Platform.MINGW_X64 -> LauncherPlatform("windows", "x86_64", ";", "windows-x86_64", osVersion)
                Platform.LINUX_X64 -> LauncherPlatform("linux", "x86_64", ":", "linux-x86_64", osVersion)
                Platform.LINUX_ARM64 -> LauncherPlatform("linux", "aarch64", ":", "linux-aarch64", osVersion)
                Platform.MACOS_X64 -> LauncherPlatform("osx", "x86_64", ":", "osx-x86_64", osVersion)
                Platform.MACOS_ARM64 -> LauncherPlatform("osx", "aarch64", ":", "osx-aarch64", osVersion)
            }
        }
    }
}

internal fun parseOperatingSystemVersion(text: String): String =
    requireNotNull(OS_VERSION_PATTERN.find(text)?.value) { "Unable to parse the operating system version" }

private val OS_VERSION_PATTERN = Regex("""\d+(?:\.\d+)+""")
