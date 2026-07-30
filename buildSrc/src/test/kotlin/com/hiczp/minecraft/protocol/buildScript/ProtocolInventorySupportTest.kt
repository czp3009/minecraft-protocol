package com.hiczp.minecraft.protocol.buildScript

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolInventorySupportTest {
    @Test
    fun protocolAuditReadsSourcesButNeverConcurrentBuildOutputs() {
        val repository = createTempDirectory("protocol-audit-sources")
        try {
            val modelSource = repository.resolve(
                "protocol-model/src/commonMain/kotlin/Model.kt",
            )
            val serializationSource = repository.resolve(
                "protocol-serialization/src/commonMain/kotlin/Format.kt",
            )
            val concurrentBuildOutput = repository.resolve(
                "protocol-model/build/generated/Generated.kt",
            )
            listOf(
                modelSource,
                serializationSource,
                concurrentBuildOutput,
            ).forEach { file ->
                file.parent.createDirectories()
                file.writeText("// ${file.fileName}\n")
            }

            assertEquals(
                listOf(modelSource, serializationSource).sorted(),
                protocolAuditSources(repository),
            )
        } finally {
            Files.walk(repository).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }
}
