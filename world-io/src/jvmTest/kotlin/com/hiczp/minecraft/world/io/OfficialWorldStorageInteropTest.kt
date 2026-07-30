package com.hiczp.minecraft.world.io

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class OfficialWorldStorageInteropTest {
    @Test
    fun officialServerLoadsLibraryRewrittenWorld() {
        val work = configuredPath(
            "minecraft.world.officialInteropWork",
        )
        deleteTree(work)
        OfficialWorldStorageInteropRunner.main(
            arrayOf(
                configuredPath("minecraft.protocol.java").toString(),
                configuredPath("minecraft.protocol.serverJar").toString(),
                work.toString(),
                configuredPath(
                    "minecraft.world.officialInteropReport",
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
