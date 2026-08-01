package com.hiczp.minecraft.test

import kotlinx.io.files.Path
import java.util.zip.ZipFile

internal data class OfficialServerRuntime(
    val directory: Path,
    val implementationJar: Path,
    val libraries: List<Path>,
)

internal suspend fun MinecraftTestEnvironment.officialServerRuntime():
        OfficialServerRuntime {
    val server = officialServer()
    val serverDirectory = requireNotNull(server.jar.parent)
    val output = Path(serverDirectory, "runtime")
    return Path(serverDirectory, ".runtime-test-support.lock")
        .withExclusiveJvmFileLock {
            if (!runtimeIsValid(server.jar, output, minecraftVersion)) {
                unpackServerRuntime(server.jar, output, minecraftVersion)
            }
            loadServerRuntime(server.jar, output, minecraftVersion)
        }
}

private fun runtimeIsValid(
    bundle: Path,
    output: Path,
    expectedVersion: String,
): Boolean = runCatching {
    loadServerRuntime(bundle, output, expectedVersion)
}.isSuccess

private fun loadServerRuntime(
    bundle: Path,
    output: Path,
    expectedVersion: String,
): OfficialServerRuntime {
    check(output.isDirectory()) { "Official runtime is absent: $output" }
    ZipFile(bundle.toNioPath().toFile()).use { archive ->
        val versionFields = archive.readText("META-INF/versions.list")
            .trim()
            .split('\t')
        check(versionFields.size == 3 && versionFields[1] == expectedVersion)
        val implementation = Path(output, "server.jar")
        check(
            implementation.isRegularFile() &&
                    implementation.sha256() == versionFields[0].lowercase(),
        ) {
            "Official runtime implementation JAR is invalid"
        }

        val libraries = archive.readText("META-INF/libraries.list")
            .lineSequence()
            .filter(String::isNotBlank)
            .map { line ->
                val fields = line.split('\t')
                check(fields.size == 3)
                Path(output, "libraries")
                    .safeResolve(fields[2])
                    .also { path ->
                        check(
                            path.isRegularFile() &&
                                    path.sha256() == fields[0].lowercase(),
                        ) {
                            "Official runtime library is invalid: ${fields[2]}"
                        }
                    }
            }
            .sortedBy(Path::toString)
            .toList()
        return OfficialServerRuntime(
            directory = output,
            implementationJar = implementation,
            libraries = libraries,
        )
    }
}

private fun unpackServerRuntime(
    bundle: Path,
    output: Path,
    expectedVersion: String,
) {
    output.deleteTree()
    output.ensureDirectory()
    ZipFile(bundle.toNioPath().toFile()).use { archive ->
        val versionFields = archive.readText("META-INF/versions.list")
            .trim()
            .split('\t')
        check(versionFields.size == 3)
        check(versionFields[1] == expectedVersion)
        val implementationDigest = versionFields[0].lowercase()
        check(implementationDigest.matches(Regex("[0-9a-f]{64}")))
        val implementation = archive.readBytes(
            "META-INF/versions/${versionFields[2]}",
        )
        check(implementation.sha256() == implementationDigest)
        Path(output, "server.jar").atomicWrite(implementation)

        archive.readText("META-INF/libraries.list")
            .lineSequence()
            .filter(String::isNotBlank)
            .forEach { line ->
                val fields = line.split('\t')
                check(fields.size == 3)
                val digest = fields[0].lowercase()
                check(digest.matches(Regex("[0-9a-f]{64}")))
                val relativePath = fields[2]
                val content = archive.readBytes(
                    "META-INF/libraries/$relativePath",
                )
                check(content.sha256() == digest)
                Path(output, "libraries")
                    .safeResolve(relativePath)
                    .atomicWrite(content)
            }
    }
    loadServerRuntime(bundle, output, expectedVersion)
}

private fun ZipFile.readBytes(name: String): ByteArray {
    val entry = getEntry(name) ?: error("Server bundle has no $name")
    return getInputStream(entry).use { it.readBytes() }
}

private fun ZipFile.readText(name: String): String =
    readBytes(name).decodeToString()
