package com.hiczp.minecraft.test.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OfficialCodecOracleTest {
    @Test
    fun codecEnvironmentsAreExclusiveAndRestoreGlobalProperties() = runTest {
        val directory = Files.createTempDirectory("official-codec-environment-")
        val firstConfiguration = directory.resolve("first.xml")
        val secondConfiguration = directory.resolve("second.xml")
        val originalLoggingConfiguration = System.getProperty(LOGGING_CONFIGURATION_PROPERTY)
        val originalJomlNoUnsafe = System.getProperty(JOML_NO_UNSAFE_PROPERTY)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                withOfficialCodecEnvironment(firstConfiguration) {
                    assertEquals(
                        firstConfiguration.toUri().toString(),
                        System.getProperty(LOGGING_CONFIGURATION_PROPERTY),
                    )
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    assertEquals(
                        firstConfiguration.toUri().toString(),
                        System.getProperty(LOGGING_CONFIGURATION_PROPERTY),
                    )
                }
            }
            firstEntered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                withOfficialCodecEnvironment(secondConfiguration) {
                    secondEntered.complete(Unit)
                    assertEquals(
                        secondConfiguration.toUri().toString(),
                        System.getProperty(LOGGING_CONFIGURATION_PROPERTY),
                    )
                }
            }

            assertFalse(secondEntered.isCompleted)
            releaseFirst.complete(Unit)
            first.await()
            second.await()

            assertEquals(
                originalLoggingConfiguration,
                System.getProperty(LOGGING_CONFIGURATION_PROPERTY),
            )
            assertEquals(
                originalJomlNoUnsafe,
                System.getProperty(JOML_NO_UNSAFE_PROPERTY),
            )
        } finally {
            directory.deleteTree()
        }
    }

    private companion object {
        const val LOGGING_CONFIGURATION_PROPERTY = "log4j2.configurationFile"
        const val JOML_NO_UNSAFE_PROPERTY = "joml.nounsafe"
    }
}
