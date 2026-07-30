package com.hiczp.minecraft.protocol.buildScript

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompareFilesTaskTest {
    @Test
    fun semanticJsonComparisonIgnoresFormattingButNotValues() {
        val compact = """{"first":1,"nested":{"value":true}}"""
            .encodeToByteArray()
        val formatted =
            """
            {
              "nested": {
                "value": true
              },
              "first": 1
            }
            """.trimIndent().encodeToByteArray()
        val changed = """{"first":2,"nested":{"value":true}}"""
            .encodeToByteArray()

        assertFalse(
            matchingFileContents(
                compact,
                formatted,
                compareJsonSemantically = false,
            ),
        )
        assertTrue(
            matchingFileContents(
                compact,
                formatted,
                compareJsonSemantically = true,
            ),
        )
        assertFalse(
            matchingFileContents(
                compact,
                changed,
                compareJsonSemantically = true,
            ),
        )
    }
}
