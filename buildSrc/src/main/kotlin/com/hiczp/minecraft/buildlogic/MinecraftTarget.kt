package com.hiczp.minecraft.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * The one manually selected Minecraft release for this repository.
 *
 * Everything version-dependent is derived from the matching official JAR.
 * [BuildVersions.JAVA_VERSION] follows this release's required Java major
 * version; the remaining platform versions stay independently fixed.
 */
object MinecraftTarget {
    const val MINECRAFT_VERSION: String = "26.2"
}

abstract class PrintMinecraftVersionTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @TaskAction
    fun printVersion() {
        logger.quiet(minecraftVersion.get())
    }
}
