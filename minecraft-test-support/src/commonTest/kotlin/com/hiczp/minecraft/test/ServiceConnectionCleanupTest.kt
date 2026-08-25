package com.hiczp.minecraft.test

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServiceConnectionCleanupTest {
    @Test
    fun everyCloseIsAttemptedAndLaterFailuresAreSuppressed() {
        val closed = mutableListOf<Int>()
        val firstFailure = IllegalStateException("first")
        val secondFailure = IllegalArgumentException("second")

        val actual = assertFailsWith<IllegalStateException> {
            closeServiceConnection(
                closeActions = arrayOf(
                    {
                        closed += 1
                        throw firstFailure
                    },
                    {
                        closed += 2
                        throw secondFailure
                    },
                ),
            )
        }

        assertEquals(firstFailure, actual)
        assertEquals(listOf(1, 2), closed)
        assertEquals(listOf(secondFailure), actual.suppressedExceptions)
    }

    @Test
    fun closeFailureIsAttachedToAnExistingFailure() {
        val primaryFailure = IllegalStateException("operation")
        val closeFailure = IllegalArgumentException("close")

        closeServiceConnection(
            primaryFailure,
            { throw closeFailure },
        )

        assertEquals(listOf(closeFailure), primaryFailure.suppressedExceptions)
    }
}
