package com.hiczp.minecraft.buildlogic

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class MinecraftTargetGenerationTest {
    @Test
    fun officialServerTargetRetainsWorldVersionInTheAnalysisReport() {
        val root = Files.createTempDirectory("minecraft-target-generation-")
        try {
            val serverJar = root.resolve("server.jar")
            writeServerJar(
                serverJar,
                minecraftVersion = "test-release",
                protocolVersion = 123,
                worldVersion = 456,
                javaMajorVersion = 25,
            )

            val minecraftProtocolTarget = serverJar.readMinecraftProtocolTarget()
            assertEquals(456, minecraftProtocolTarget.worldVersion)

            val targetFile = root.resolve("target.json")
            val officialMinecraftTargetReportJson = minecraftProtocolTarget.toOfficialMinecraftTargetReportJson()
            assertEquals(1, officialMinecraftTargetReportJson.getValue("schema_version").jsonPrimitive.int)
            targetFile.writeJson(officialMinecraftTargetReportJson, sortKeys = true)

            val officialMinecraftTargetReport = targetFile.readOfficialMinecraftTargetReport()
            assertEquals(456, officialMinecraftTargetReport.minecraftProtocolTarget.worldVersion)
        } finally {
            root.deleteTree()
        }
    }

    @Test
    fun worldFormatSourceUsesOnlyTheWorldVersionName() {
        val source = renderMinecraftWorldFormatSource(456)

        assertContains(source, "public object MinecraftWorldFormat")
        assertContains(source, "public const val WORLD_VERSION: Int = 456")
        assertFalse("DATA_VERSION" in source)
        assertFailsWith<IllegalArgumentException> {
            renderMinecraftWorldFormatSource(-1)
        }
    }

    private fun writeServerJar(
        path: Path,
        minecraftVersion: String,
        protocolVersion: Int,
        worldVersion: Int,
        javaMajorVersion: Int,
    ) {
        val version = buildJsonObject {
            put("id", minecraftVersion)
            put("protocol_version", protocolVersion)
            put("world_version", worldVersion)
            put("java_version", javaMajorVersion)
        }
        ZipOutputStream(Files.newOutputStream(path)).use { zipOutputStream ->
            zipOutputStream.putNextEntry(ZipEntry("version.json"))
            zipOutputStream.write(version.toString().encodeToByteArray())
            zipOutputStream.closeEntry()
        }
    }
}
