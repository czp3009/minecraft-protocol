package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MinecraftProtocolToolSupportTest {
    @Test
    fun jsonAccessorsRequireTheDeclaredJsonTypes() {
        val value = jsonObjectOf(
            "object" to jsonObjectOf("value" to jsonNumber(1)),
            "array" to JsonArray(listOf(jsonString("entry"))),
            "string" to jsonString("text"),
            "integer" to jsonNumber(42),
            "long" to jsonNumber(Long.MAX_VALUE),
            "boolean" to jsonBoolean(true),
            "null" to JsonNull,
        )

        assertEquals(1, value.requiredObject("object").requiredInt("value"))
        assertEquals("entry", value.requiredArray("array").single().toString().trim('"'))
        assertEquals("text", value.requiredString("string"))
        assertEquals(42, value.requiredInt("integer"))
        assertEquals(Long.MAX_VALUE, value.requiredLong("long"))
        assertEquals(true, value.optionalBoolean("boolean"))
        assertEquals("text", value.optionalString("string"))
        assertEquals(null, value.optionalString("absent"))

        assertFailsWith<IllegalStateException> {
            value.requiredString("integer")
        }
        assertFailsWith<IllegalStateException> {
            jsonObjectOf("value" to jsonString("42")).requiredInt("value")
        }
        assertFailsWith<IllegalStateException> {
            jsonObjectOf("value" to jsonString("true"))
                .optionalBoolean("value")
        }
        assertFailsWith<IllegalStateException> {
            value.optionalString("null")
        }
        assertFailsWith<NoSuchElementException> {
            value.requiredString("absent")
        }
    }

    @Test
    fun jsonRenderingIsDeterministicAndEscapesNonAscii() {
        val value = jsonObjectOf(
            "z" to jsonString("quote\"\\\n\u0001😀"),
            "a" to JsonArray(
                listOf(
                    jsonNumber(1),
                    jsonBoolean(false),
                    JsonNull,
                ),
            ),
        )

        assertEquals(
            """{"a":[1,false,null],"z":"quote\"\\\n\u0001\ud83d\ude00"}""",
            renderCanonicalJson(value),
        )
        assertTrue(renderJson(value).startsWith("{\n  \"z\""))
        assertTrue(renderJson(value, sortKeys = true).startsWith("{\n  \"a\""))
        assertEquals(
            value,
            renderJson(value).encodeToByteArray()
                .decodeJsonObject("rendered"),
        )
    }

    @Test
    fun atomicWritesHashesAndChangeDetectionAreStable() = withTemporaryDirectory {
        val target = resolve("nested/state.txt")

        assertTrue(target.writeIfChanged("first"))
        assertFalse(target.writeIfChanged("first"))
        assertTrue(target.writeIfChanged("second"))
        assertEquals("second", target.readText())
        assertEquals(
            "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d",
            "hello".encodeToByteArray().sha1(),
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c" +
                    "1fa7425e73043362938b9824",
            "hello".encodeToByteArray().sha256(),
        )
        assertNotEquals(target.sha1(), target.sha256())
        assertEquals("second".encodeToByteArray().sha1(), target.sha1())

        target.atomicWriteText("replacement")
        assertEquals("replacement", target.readText())
        assertTrue(
            Files.list(target.parent).use { files ->
                files.noneMatch { it.fileName.toString().endsWith(".tmp") }
            },
        )
    }

    @Test
    fun safeResolveAcceptsOnlyPortableRelativePaths() = withTemporaryDirectory {
        assertEquals(
            resolve("one/two/three").normalize(),
            safeResolve("one\\two/three"),
        )
        for (
        path in listOf(
            "",
            "/absolute",
            "\\absolute",
            ".",
            "..",
            "one/./two",
            "one/../two",
            "one//two",
            "C:/escape",
            "name:stream",
        )
        ) {
            assertFailsWith<IllegalArgumentException>(path) {
                safeResolve(path)
            }
        }
    }

    @Test
    fun readsZipEntriesAndRejectsMissingEntries() = withTemporaryDirectory {
        val archive = resolve("source.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("nested/value.txt"))
            zip.write("payload".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }

        assertContentEquals(
            "payload".encodeToByteArray(),
            archive.readZipEntry("nested/value.txt"),
        )
        assertFailsWith<IllegalStateException> {
            archive.readZipEntry("missing.txt")
        }
    }

    @Test
    fun readsAndValidatesCheckedInProtocolTarget() = withTemporaryDirectory {
        val specification = resolve("protocol-specification")
        specification.createDirectories()
        val snapshot = specification.resolve("wiki-protocol-snapshot.json")
        snapshot.writeText(
            """
            {
              "minecraft_version": "26.1",
              "protocol_version": 777,
              "cross_checks": {"java_major_version": 25}
            }
            """.trimIndent(),
        )

        assertEquals(
            MinecraftProtocolTarget("26.1", 777, 25),
            readMinecraftProtocolTarget(),
        )

        snapshot.writeText(
            """
            {
              "minecraft_version": "../unsafe",
              "protocol_version": 777,
              "cross_checks": {"java_major_version": 25}
            }
            """.trimIndent(),
        )
        assertFailsWith<IllegalArgumentException> {
            readMinecraftProtocolTarget()
        }
    }

    @Test
    fun offlineArtifactVerificationAcceptsOnlyExactExistingBytes() =
        withTemporaryDirectory {
            val artifact = resolve("artifact.bin")
            val bytes = "artifact".encodeToByteArray()
            Files.write(artifact, bytes)

            assertFalse(
                ProtocolHttp.ensureDownload(
                    url = "https://invalid.example/artifact",
                    destination = artifact,
                    expectedSize = bytes.size.toLong(),
                    expectedSha1 = bytes.sha1(),
                    offline = true,
                ),
            )
            assertFailsWith<IllegalStateException> {
                ProtocolHttp.ensureDownload(
                    url = "https://invalid.example/artifact",
                    destination = artifact,
                    expectedSize = bytes.size.toLong() + 1,
                    expectedSha1 = bytes.sha1(),
                    offline = true,
                )
            }
        }

    @Test
    fun processRunnerCapturesOutputExitStatusAndTimeout() =
        withTemporaryDirectory {
            val java = Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) {
                    "java.exe"
                } else {
                    "java"
                },
            ).toString()

            val success = runProcess(listOf(java, "-version"))
            assertEquals(0, success.exitCode)
            assertTrue(success.output.contains("version"))

            val failure = runProcess(listOf(java, "-not-a-real-option"))
            assertNotEquals(0, failure.exitCode)
            assertTrue(failure.output.isNotBlank())

            val sleeper = resolve("Sleeper.java")
            sleeper.writeText(
                """
                class Sleeper {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(10_000L);
                    }
                }
                """.trimIndent(),
            )
            assertFailsWith<IllegalStateException> {
                runProcess(
                    listOf(java, sleeper.toString()),
                    timeout = Duration.ofMillis(100),
                )
            }
        }

    @Test
    fun deleteTreeIsIdempotent() = withTemporaryDirectory(deleteAfter = false) {
        resolve("a/b").createDirectories()
        resolve("a/b/value.txt").writeText("value")

        deleteTree()
        assertFalse(exists())
        deleteTree()
    }

    private fun withTemporaryDirectory(
        deleteAfter: Boolean = true,
        block: Path.() -> Unit,
    ) {
        val directory = Files.createTempDirectory("protocol-build-tools-")
        try {
            directory.block()
        } finally {
            if (deleteAfter && directory.exists()) {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder())
                        .forEach(Files::delete)
                }
            }
        }
    }
}
