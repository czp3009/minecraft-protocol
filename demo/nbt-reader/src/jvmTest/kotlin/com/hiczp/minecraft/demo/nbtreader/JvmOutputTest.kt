package com.hiczp.minecraft.demo.nbtreader

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals

class JvmOutputTest {
    @Test
    fun redirectedOutputUsesUtf8RegardlessOfThePrintStreamCharset() {
        val byteArrayOutputStream = ByteArrayOutputStream()
        val originalOutput = System.out
        val message = "accent\u00E9 emoji\uD83D\uDE00"
        PrintStream(byteArrayOutputStream, true, Charsets.US_ASCII).use { printStream ->
            try {
                System.setOut(printStream)
                printOutputLine(message)
            } finally {
                System.setOut(originalOutput)
            }
        }
        assertContentEquals("$message\n".encodeToByteArray(), byteArrayOutputStream.toByteArray())
    }
}
