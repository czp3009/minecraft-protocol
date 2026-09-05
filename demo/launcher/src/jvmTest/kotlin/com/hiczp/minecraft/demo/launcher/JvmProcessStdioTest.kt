package com.hiczp.minecraft.demo.launcher

import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmProcessStdioTest {
    @Test
    fun currentPlatformDetectsAnOsVersionIncludingTheWindowsBuild() {
        val launcherPlatform = LauncherPlatform.current()
        val osVersion = launcherPlatform.osVersion

        assertTrue(osVersion.matches(Regex("""\d+(?:\.\d+)+""")))
        if (launcherPlatform.osName == "windows") assertTrue(osVersion.split('.').size >= 3)
    }

    @Test
    fun javaProbeStartsWithoutAnInvalidInputRedirect() = runTest {
        val gameProcessService = GameProcessService(FakeFileSystem(), "/launcher".toPath())

        assertTrue(gameProcessService.probeJavaMajor() > 0)
    }

    @Test
    fun browserStyleCommandDiscardsOutputWithoutRedirectingInput() = runTest {
        val status = Command("java")
            .arg("-version")
            .stdout(Stdio.Null)
            .stderr(Stdio.Null)
            .status()

        assertEquals(0, status)
    }
}
