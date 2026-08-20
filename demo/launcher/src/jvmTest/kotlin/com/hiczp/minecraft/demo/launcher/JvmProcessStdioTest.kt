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
    fun javaProbeStartsWithoutAnInvalidInputRedirect() = runTest {
        val service = GameProcessService(FakeFileSystem(), "/launcher".toPath())

        assertTrue(service.probeJavaMajor() > 0)
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
