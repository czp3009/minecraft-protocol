package com.hiczp.minecraft.protocol.buildScript

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertTrue

class BuildInputBoundaryTest {
    @Test
    fun gradleBuildInputsDoNotDependOnAgentOnlyFiles() {
        val repository = findRepositoryRoot()
        val violations = gradleInputFiles(repository).flatMap { file ->
            val content = Files.readString(file)
            forbiddenAgentInputPatterns.mapNotNull { pattern ->
                if (pattern.regex.containsMatchIn(content)) {
                    "${repository.relativize(file)} references ${pattern.label}"
                } else {
                    null
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Gradle build inputs must not depend on optional agent files:\n" +
                    violations.joinToString("\n"),
        )
    }

    private fun findRepositoryRoot(): Path {
        var candidate =
            Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (true) {
            if (
                Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
                candidate.resolve("protocol-model").isDirectory()
            ) {
                return candidate
            }
            candidate = candidate.parent
                ?: error("Could not locate the minecraft-protocol repository")
        }
    }

    private fun gradleInputFiles(repository: Path): List<Path> =
        Files.walk(repository).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { file ->
                    val relative = repository.relativize(file)
                    !relative.any { component ->
                        component.name in excludedDirectories
                    } && isGradleInput(relative)
                }
                .sorted()
                .toList()
        }

    private fun isGradleInput(relative: Path): Boolean {
        val filename = relative.fileName.name
        return filename.endsWith(".gradle.kts") ||
                filename.endsWith(".gradle") ||
                filename == "gradle.properties" ||
                filename == "libs.versions.toml" ||
                (
                        relative.startsWith("buildSrc/src/main") &&
                                filename.endsWith(".kt")
                        )
    }

    private data class ForbiddenPattern(
        val label: String,
        val regex: Regex,
    )

    private companion object {
        val excludedDirectories = setOf(
            ".agents",
            ".git",
            ".gradle",
            ".idea",
            "build",
            "temp",
        )

        val forbiddenAgentInputPatterns = listOf(
            ForbiddenPattern(
                label = ".agents/skills",
                regex = Regex("""\.agents[/\\]skills"""),
            ),
            ForbiddenPattern(
                label = "the agent-only temp directory",
                regex = Regex(
                    """(?i)(?:projectDirectory\.dir\(\s*)?["']temp(?:["'/\\])""",
                ),
            ),
        )
    }
}
