package com.hiczp.minecraft.test

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class TestFilesTest {
    @Test
    fun environmentPathsStayInsideTheOwningModuleBuildDirectory() {
        val environment = environment()
        val first = environment.freshWorkDirectory("fixtures/server")
        val second = environment.freshWorkDirectory("fixtures/server")
        val firstReport = environment.reportFile("fixtures/report.json")
        val secondReport = environment.reportFile("fixtures/report.json")

        assertNotEquals(first, second)
        assertNotEquals(firstReport, secondReport)
        assertTrue(first.isBelow(environment.moduleBuildDirectory))
        assertTrue(second.isBelow(environment.moduleBuildDirectory))
        assertTrue(firstReport.isBelow(environment.moduleBuildDirectory))
        assertTrue(secondReport.isBelow(environment.moduleBuildDirectory))
        assertFailsWith<IllegalArgumentException> {
            environment.freshWorkDirectory("../../outside")
        }
        assertFailsWith<IllegalArgumentException> {
            environment.reportFile("../../outside.json")
        }
        assertFailsWith<IllegalArgumentException> {
            environment.temporaryFile("../../outside.bin")
        }
    }

    @Test
    fun reportWriterProducesParseableStructuredJson() {
        val report = environment().temporaryFile("unit/structured.json")
        report.writeJsonReport(
            buildJsonObject {
                put("text", "quote=\" newline=\n")
                put("count", 2)
            },
        )

        assertTrue(report.isRegularFile())
        val document = report.readJsonObject()
        assertEquals("quote=\" newline=\n", document.requiredString("text"))
        assertEquals(2, document.requiredInt("count"))
    }

    private fun kotlinx.io.files.Path.isBelow(
        ancestor: kotlinx.io.files.Path,
    ): Boolean = generateSequence(parent) { it.parent }.any { it == ancestor }

    private fun environment(): MinecraftTestEnvironment =
        MinecraftTestEnvironment.forModule(
            moduleName = "minecraft-test-support",
            minecraftVersion = "test-version",
        )
}
