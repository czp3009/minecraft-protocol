package com.hiczp.minecraft.buildlogic

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OfficialPacketAnalysisTest {
    @Test
    fun registrationsResolveThroughGenericSignaturesAndMembersRetainTheirOrderAndOwners() {
        withFixtureJar { implementationJar ->
            val report = analyzeOfficialPacketClasses(implementationJar, report("fixture"))
            val packet = report.getValue("packets").jsonArray.single().jsonObject
            assertEquals(
                "net.minecraft.network.protocol.ClientboundFixturePacket",
                packet.getValue("class_name").jsonPrimitive.content
            )
            assertEquals(19, packet.getValue("protocol_id").jsonPrimitive.int)
            val classes = report.getValue("classes").jsonObject
            val fields = classes.getValue("net.minecraft.network.protocol.ClientboundFixturePacket").jsonObject
            assertEquals(
                "net.minecraft.network.protocol.BasePacket",
                fields.getValue("superclass").jsonPrimitive.content
            )
            assertEquals(
                listOf("message", "entries"),
                fields.getValue("fields").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
            assertEquals(
                "Ljava/util/List<Lnet/minecraft/network/protocol/ClientboundFixturePacket\$Entry;>;",
                fields.getValue("fields").jsonArray[1].jsonObject.getValue("signature").jsonPrimitive.content
            )
            val record = classes.getValue("net.minecraft.network.protocol.ClientboundFixturePacket\$Entry").jsonObject
            assertEquals(
                listOf("name", "count"),
                record.getValue("record_components").jsonArray.map { it.jsonPrimitive.content })
            val references =
                fields.getValue("methods").jsonArray.single { it.jsonObject.getValue("name").jsonPrimitive.content == "readMessage" }
                    .jsonObject.getValue("references").jsonArray
            assertEquals("GETFIELD", references.single().jsonObject.getValue("opcode").jsonPrimitive.content)
            assertEquals("message", references.single().jsonObject.getValue("name").jsonPrimitive.content)
            assertFailsWith<IllegalStateException> {
                analyzeOfficialPacketClasses(
                    implementationJar,
                    report("missing")
                )
            }
        }
    }

    private fun report(name: String): JsonObject = buildJsonObject {
        putJsonObject("play") {
            putJsonObject("clientbound") {
                putJsonObject("minecraft:$name") { put("protocol_id", 19) }
            }
        }
    }

    private fun withFixtureJar(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory(Path.of("build/test-packet-analysis").createDirectories(), "fixture-")
        try {
            val source = directory.resolve("FixturePacketTypes.java")
            javaClass.getResourceAsStream("/packet-analysis/FixturePacketTypes.java")!!.use { inputStream ->
                source.outputStream().use(inputStream::copyTo)
            }
            val classes = directory.resolve("classes").createDirectories()
            assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString())
            )
            val archive = directory.resolve("fixture.jar")
            ZipOutputStream(archive.outputStream()).use { zipOutputStream ->
                Files.walk(classes).use { files ->
                    files.filter(Files::isRegularFile).sorted().forEach { path ->
                        zipOutputStream.putNextEntry(ZipEntry(classes.relativize(path).toString().replace('\\', '/')))
                        Files.copy(path, zipOutputStream)
                        zipOutputStream.closeEntry()
                    }
                }
            }
            test(archive)
        } finally {
            directory.deleteTree()
        }
    }
}
