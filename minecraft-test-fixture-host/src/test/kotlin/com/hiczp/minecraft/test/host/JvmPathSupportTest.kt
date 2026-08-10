package com.hiczp.minecraft.test.host

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmPathSupportTest {
    @Test
    fun fixtureJavaCommandsEnableNativeAccessBeforeApplicationArguments() {
        assertEquals(
            listOf(
                "java",
                ENABLE_NATIVE_ACCESS_ALL_UNNAMED,
                "-jar",
                "fixture.jar",
            ),
            fixtureJavaCommand("-jar", "fixture.jar"),
        )
    }
}
