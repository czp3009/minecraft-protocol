package com.hiczp.minecraft.protocol.serialization

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialInteropTest {
    @Test
    fun statusLoginAndConfigurationInteroperateWithOfficialServer() {
        val work = configuredPath(
            "minecraft.protocol.officialServerWork",
        )
        deleteTree(work)
        OfficialServerInteropRunner.main(
            arrayOf(
                configuredPath("minecraft.protocol.java").toString(),
                configuredPath("minecraft.protocol.serverJar").toString(),
                work.toString(),
                configuredPath(
                    "minecraft.protocol.officialServerReport",
                ).toString(),
            ),
        )
    }

    @Test
    fun everyPacketFixturePassesThroughOfficialCodec() {
        val fixtures = configuredPath(
            "minecraft.protocol.codecFixtures",
        )
        OfficialCodecFixtureGenerator.main(arrayOf(fixtures.toString()))

        val runtime = configuredPath(
            "minecraft.protocol.officialRuntime",
        )
        val implementationJar = runtime.resolve("server.jar")
        val libraries = Files.walk(runtime.resolve("libraries")).use { paths ->
            paths.filter {
                it.isRegularFile() && it.extension == "jar"
            }.sorted().toList()
        }
        val classpath = buildList {
            add(
                configuredPath(
                    "minecraft.protocol.codecOracleClasses",
                ).toString(),
            )
            add(implementationJar.toString())
            addAll(libraries.map(Path::toString))
        }.joinToString(File.pathSeparator)
        val report = configuredPath(
            "minecraft.protocol.codecReport",
        )
        Files.createDirectories(report.parent)
        val process = ProcessBuilder(
            configuredPath("minecraft.protocol.java").toString(),
            "-Djava.awt.headless=true",
            "-cp",
            classpath,
            "OfficialCodecOracle",
            fixtures.toString(),
            implementationJar.toString(),
            report.toString(),
        )
            .directory(runtime.toFile())
            .redirectErrorStream(true)
            .start()
        val output = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use {
                it.readText()
            }
        }
        val completed = process.waitFor(
            Duration.ofMinutes(10).toMillis(),
            TimeUnit.MILLISECONDS,
        )
        if (!completed) {
            process.destroyForcibly()
            process.waitFor()
        }
        val processOutput = output.get(30, TimeUnit.SECONDS)
        assertTrue(completed, "Official codec oracle timed out:\n$processOutput")
        assertEquals(
            0,
            process.exitValue(),
            "Official codec oracle failed:\n$processOutput",
        )
    }

    private fun configuredPath(property: String): Path =
        Path.of(
            requireNotNull(System.getProperty(property)) {
                "Gradle did not configure $property"
            },
        ).toAbsolutePath().normalize()

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder())
                .forEach(Files::delete)
        }
    }
}
