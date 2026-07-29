package com.hiczp.minecraft.protocol.buildScript

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.time.Duration

abstract class DownloadHeadlessMinecraftLauncherTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val downloadUrl: Property<String>

    @get:Input
    abstract val expectedSize: Property<Long>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:Input
    abstract val offline: Property<Boolean>

    @get:OutputFile
    abstract val launcherJar: RegularFileProperty

    @TaskAction
    fun download() {
        val output = launcherJar.get().asFile.toPath()
        val changed = ProtocolHttp.ensureDownloadSha256(
            url = downloadUrl.get(),
            destination = output,
            expectedSize = expectedSize.get(),
            expectedSha256 = expectedSha256.get(),
            offline = offline.get(),
            timeout = Duration.ofMinutes(5),
        )
        val action = if (changed) "Downloaded and verified" else "Verified"
        logger.lifecycle(
            "$action HeadlessMC ${version.get()}: $output",
        )
    }
}
