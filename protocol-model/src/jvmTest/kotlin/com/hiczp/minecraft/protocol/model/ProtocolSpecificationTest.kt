package com.hiczp.minecraft.protocol.model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolSpecificationTest {
    @Test
    fun checkedInSpecificationMatchesOfficialGeneration() {
        val expected = configuredPath(
            "minecraft.protocol.expectedSpecification",
        )
        val actual = configuredPath(
            "minecraft.protocol.checkedInSpecification",
        )
        assertTrue(
            Files.isDirectory(actual),
            "Checked-in protocol specification is absent; run " +
                    "./gradlew refreshProtocolSpecification",
        )

        val expectedFiles = relativeFiles(expected)
        val actualFiles = relativeFiles(actual)
        assertEquals(
            expectedFiles,
            actualFiles,
            "Checked-in protocol specification file set is stale; run " +
                    "./gradlew refreshProtocolSpecification",
        )
        expectedFiles.forEach { relative ->
            val expectedBytes = Files.readAllBytes(expected.resolve(relative))
            val actualBytes = Files.readAllBytes(actual.resolve(relative))
            assertTrue(
                expectedBytes.contentEquals(actualBytes),
                "$relative is stale; run " +
                        "./gradlew refreshProtocolSpecification",
            )
        }
    }

    private fun configuredPath(property: String): Path =
        Path.of(
            requireNotNull(System.getProperty(property)) {
                "Gradle did not configure $property"
            },
        ).toAbsolutePath().normalize()

    private fun relativeFiles(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths.filter(Path::isRegularFile)
                .map(root::relativize)
                .sorted()
                .toList()
        }
}
