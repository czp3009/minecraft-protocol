package com.hiczp.minecraft.demo.launcher

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameProcessSupportTest {
    @Test
    fun parsesModernAndLegacyJavaVersions() {
        assertEquals(21, parseJavaMajor("openjdk version \"21.0.8\""))
        assertEquals(8, parseJavaMajor("java version \"1.8.0_441\""))
    }

    @Test
    fun argumentFileQuotesPathsWithoutContainingGameSecrets() {
        val content = encodeJavaArgumentFile(listOf("-Dpath=C:\\Launcher Root\\demo", "-cp", "a;b"))

        assertTrue(content.contains("Launcher Root"))
        assertFalse(content.contains("access-token"))
        assertEquals(3, content.lines().count(String::isNotEmpty))
    }

    @Test
    fun decoderHandlesCarriageReturnAndLongLines() {
        val decoder = OutputChunkDecoder(maximumLineLength = 4)

        assertEquals(listOf("abcd"), decoder.feed("abcdef"))
        assertEquals(listOf("Zx"), decoder.feed("\rxy\rZx\n"))
    }

    @Test
    fun processLineEndingNormalizesCrLfAndPreservesBareCarriageReturns() {
        assertEquals("line\n", normalizeProcessLineEnding("line\r"))
        assertEquals("line\n", normalizeProcessLineEnding("line"))
        assertEquals("progress\rupdated\n", normalizeProcessLineEnding("progress\rupdated"))
    }

    @Test
    fun outputIsSanitizedRedactedAndBounded() = runTest {
        val buffer = GameOutputBuffer(listOf("secret"), capacity = 3, maximumLineLength = 20)
        buffer.append(OutputSource.STDOUT, "\u001B[31msecret\u001B[0m")
        buffer.append(OutputSource.STDERR, "two")
        buffer.append(OutputSource.STDOUT, "three")
        buffer.append(OutputSource.STDOUT, "four")

        val lines = buffer.state.value.lines
        assertEquals(3, lines.size)
        assertFalse(lines.any { "secret" in it.text || '\u001B' in it.text })
        assertEquals(listOf("two", "three", "four"), lines.map(GameOutputLine::text))
    }

    @Test
    fun launchWithoutSensitiveTokenDoesNotRedactOrdinaryOutput() = runTest {
        val service = GameProcessService(FakeFileSystem(), "/launcher".toPath())
        val plan = LaunchPlan(
            javaArguments = emptyList(),
            mainClass = "example.Main",
            gameArguments = emptyList(),
            sensitiveAccessToken = null,
            workingDirectory = "/game",
            requiredJavaMajor = null,
        )
        val buffer = service.outputBuffer(plan)

        buffer.append(OutputSource.STDOUT, "Java 21.0")

        assertEquals("Java 21.0", buffer.state.value.lines.single().text)
    }
}
