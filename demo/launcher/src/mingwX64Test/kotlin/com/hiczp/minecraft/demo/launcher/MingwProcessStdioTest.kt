package com.hiczp.minecraft.demo.launcher

import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MingwProcessStdioTest {
    @Test
    fun currentPlatformDetectsTheWindowsBuildNumber() {
        val launcherPlatform = LauncherPlatform.current()
        val osVersion = launcherPlatform.osVersion

        assertEquals("windows", launcherPlatform.osName)
        assertTrue(osVersion.matches(Regex("""\d+\.\d+\.\d+(?:\.\d+)?""")))
    }

    @Test
    fun crLfProcessOutputKeepsItsText() {
        val child = Command("cmd.exe")
            .args("/d", "/c", "echo native-output")
            .stdin(Stdio.Inherit)
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Null)
            .spawn()
        val bufferedReader = assertNotNull(child.bufferedStdout())
        val line = assertNotNull(bufferedReader.readLine())

        val decoded = OutputChunkDecoder().feed(normalizeProcessLineEnding(line))

        assertEquals(0, child.wait())
        assertEquals(listOf("native-output"), decoded)
    }
}
