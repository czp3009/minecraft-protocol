package com.hiczp.minecraft.protocol.client

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class OfficialServerClientInteropTest {
    @Test
    fun productionClientReachesPlayAgainstOfficialServer() {
        val work = configuredPath(
            "minecraft.protocol.clientInteropWork",
        )
        deleteTree(work)
        OfficialServerClientInteropRunner.main(
            arrayOf(
                configuredPath("minecraft.protocol.java").toString(),
                configuredPath("minecraft.protocol.serverJar").toString(),
                work.toString(),
                configuredPath(
                    "minecraft.protocol.clientInteropReport",
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
