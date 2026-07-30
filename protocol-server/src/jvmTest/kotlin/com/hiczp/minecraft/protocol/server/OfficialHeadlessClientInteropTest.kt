package com.hiczp.minecraft.protocol.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class OfficialHeadlessClientInteropTest {
    @Test
    fun officialHeadlessClientReachesPlayAgainstLibraryServer() {
        val work = configuredPath(
            "minecraft.protocol.headlessClientWork",
        )
        deleteTree(work)
        OfficialClientEndToEndRunner.main(
            arrayOf(
                configuredPath("minecraft.protocol.java").toString(),
                configuredPath(
                    "minecraft.protocol.clientDirectory",
                ).toString(),
                requireNotNull(
                    System.getProperty("minecraft.protocol.version"),
                ),
                work.toString(),
                configuredPath(
                    "minecraft.protocol.headlessClientReport",
                ).toString(),
                configuredPath(
                    "minecraft.protocol.headlessLauncher",
                ).toString(),
            ),
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
