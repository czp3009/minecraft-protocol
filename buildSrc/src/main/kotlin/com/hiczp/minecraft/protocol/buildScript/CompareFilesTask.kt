package com.hiczp.minecraft.protocol.buildScript

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
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

    @TaskAction
    fun compare() {
        val expected = expectedFile.get().asFile
        val actual = actualFile.get().asFile
        check(expected.readBytes().contentEquals(actual.readBytes())) {
            "$actual differs from $expected"
        }
    }
}
