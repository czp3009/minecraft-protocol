package com.hiczp.minecraft.protocol.serialization

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class OfficialServerInteropTest {
    @Test
    fun statusLoginAndConfigurationInteroperateWithOfficialServer() = runTest {
        OfficialServerInteropRunner.run()
    }
}
