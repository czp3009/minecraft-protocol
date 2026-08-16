package com.hiczp.minecraft.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import java.time.Duration

@CacheableTask
abstract class GenerateOfficialMinecraftServerTemplateTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverJar: RegularFileProperty

    @get:Classpath
    abstract val workerClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val workDirectory = createIsolatedTemporaryDirectory("server-template")
        try {
            runTemplateWorker(
                workerClasspath = workerClasspath,
                arguments = listOf(
                    "server",
                    minecraftVersion.get(),
                    serverJar.asFile.get().absolutePath,
                    outputDirectory.asFile.get().absolutePath,
                    workDirectory.toString(),
                ),
                workDirectory = workDirectory,
            )
        } finally {
            workDirectory.deleteTree()
        }
        logger.lifecycle(
            "Generated official Minecraft server template: ${outputDirectory.asFile.get()}",
        )
    }
}

@CacheableTask
abstract class GenerateHeadlessClientTemplateTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:Input
    abstract val headlessMcVersion: Property<String>

    @get:Input
    abstract val fabricLoaderVersion: Property<String>

    @get:Input
    abstract val hmcSpecificsReleaseTag: Property<String>

    @get:Input
    abstract val hmcSpecificsAssetName: Property<String>

    @get:Input
    abstract val hmcSpecificsAssetUrl: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeDirectory: DirectoryProperty

    @get:Classpath
    abstract val workerClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val templateDirectory: DirectoryProperty

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val workDirectory = createIsolatedTemporaryDirectory("client-template")
        try {
            runTemplateWorker(
                workerClasspath = workerClasspath,
                arguments = listOf(
                    "client",
                    minecraftVersion.get(),
                    headlessMcVersion.get(),
                    fabricLoaderVersion.get(),
                    hmcSpecificsReleaseTag.get(),
                    hmcSpecificsAssetName.get(),
                    hmcSpecificsAssetUrl.get(),
                    runtimeDirectory.asFile.get().absolutePath,
                    templateDirectory.asFile.get().absolutePath,
                    manifestFile.asFile.get().absolutePath,
                    workDirectory.toString(),
                ),
                workDirectory = workDirectory,
            )
        } finally {
            workDirectory.deleteTree()
        }
        logger.lifecycle(
            "Generated HeadlessMC client template: ${templateDirectory.asFile.get()}",
        )
    }
}

private fun DefaultTask.runTemplateWorker(
    workerClasspath: ConfigurableFileCollection,
    arguments: List<String>,
    workDirectory: java.nio.file.Path,
) {
    val classpath = workerClasspath.files
        .sortedBy(File::getAbsolutePath)
        .joinToString(File.pathSeparator, transform = File::getAbsolutePath)
    check(classpath.isNotEmpty()) {
        "Minecraft fixture template worker classpath is empty"
    }
    val result = runProcess(
        command = buildList {
            add("java")
            add(JvmProcessArguments.ENABLE_NATIVE_ACCESS_ALL_UNNAMED)
            add("-cp")
            add(classpath)
            add(TEMPLATE_WORKER_MAIN_CLASS)
            addAll(arguments)
        },
        workingDirectory = workDirectory,
        timeout = TEMPLATE_WORKER_TIMEOUT,
    )
    check(result.exitCode == 0) {
        "Minecraft fixture template worker exited with ${result.exitCode}:\n${result.output}"
    }
}

private const val TEMPLATE_WORKER_MAIN_CLASS = "com.hiczp.minecraft.test.host.MinecraftFixtureTemplateWorkerKt"
private val TEMPLATE_WORKER_TIMEOUT = Duration.ofMinutes(10)
