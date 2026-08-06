package com.hiczp.minecraft.world.io

import kotlinx.coroutines.test.runTest
import okio.Path
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import java.nio.file.Path as NioPath

class OfficialWorldStorageInteropTest {
    @Test
    fun officialServerLoadsLibraryRewrittenWorld() = runTest(
        timeout = 2.minutes,
    ) {
        OfficialWorldStorageInteropRunner.run(
            fileIdentity = ::fileIdentity,
        )
    }
}

private fun fileIdentity(path: Path): String? = Files.readAttributes(
    NioPath.of(path.toString()),
    BasicFileAttributes::class.java,
).fileKey()?.toString()
