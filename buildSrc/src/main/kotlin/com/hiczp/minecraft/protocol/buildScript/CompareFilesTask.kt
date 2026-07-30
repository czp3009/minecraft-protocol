package com.hiczp.minecraft.protocol.buildScript

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(
    because = "This verification task has no output to cache",
)
abstract class CompareFilesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val expectedFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val actualFile: RegularFileProperty

    @get:Input
    abstract val compareJsonSemantically: Property<Boolean>

    init {
        compareJsonSemantically.convention(false)
    }

    @TaskAction
    fun compare() {
        val expected = expectedFile.get().asFile
        val actual = actualFile.get().asFile
        check(
            matchingFileContents(
                expected = expected.readBytes(),
                actual = actual.readBytes(),
                compareJsonSemantically = compareJsonSemantically.get(),
            ),
        ) {
            "$actual differs from $expected"
        }
    }
}

internal fun matchingFileContents(
    expected: ByteArray,
    actual: ByteArray,
    compareJsonSemantically: Boolean,
): Boolean =
    if (compareJsonSemantically) {
        expected.decodeJsonObject("expected JSON") ==
                actual.decodeJsonObject("actual JSON")
    } else {
        expected.contentEquals(actual)
    }
