package com.hiczp.minecraft.demo.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LauncherPlatformTest {
    @Test
    fun versionProbeOutputKeepsBuildNumbersAndIgnoresLocalizedLabels() {
        assertEquals(
            "10.0.26200.9278",
            parseOperatingSystemVersion("\r\nMicrosoft Windows [Version 10.0.26200.9278]\r\n")
        )
        assertEquals("10.0.22631.1", parseOperatingSystemVersion("\r\nMicrosoft Windows [版本 10.0.22631.1]\r\n"))
        assertEquals("6.8.0", parseOperatingSystemVersion("6.8.0-52-generic\n"))
        assertEquals("15.6.1", parseOperatingSystemVersion("15.6.1\n"))
        assertFailsWith<IllegalArgumentException> { parseOperatingSystemVersion("") }
        assertFailsWith<IllegalArgumentException> { parseOperatingSystemVersion("unknown") }
    }
}
