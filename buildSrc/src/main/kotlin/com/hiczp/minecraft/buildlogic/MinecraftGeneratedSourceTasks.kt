package com.hiczp.minecraft.buildlogic

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier.CONST
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateMinecraftProtocolSourceTask :
    DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val targetFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val minecraftProtocolTarget = targetFile.asFile.get().toPath()
            .readOfficialMinecraftTargetReport()
            .minecraftProtocolTarget
        val source = FileSpec.builder(
            "com.hiczp.minecraft.protocol.model",
            "MinecraftProtocol",
        ).addType(
            TypeSpec.objectBuilder("MinecraftProtocol")
                .addKdoc(
                    "The single protocol revision implemented by this build.\n\nGenerated from version.json in the matching official server JAR.\n",
                )
                .addProperty(
                    PropertySpec.builder("MINECRAFT_VERSION", String::class, CONST)
                        .initializer("%S", minecraftProtocolTarget.minecraftVersion)
                        .build(),
                )
                .addProperty(
                    PropertySpec.builder("PROTOCOL_VERSION", Int::class, CONST)
                        .initializer("%L", minecraftProtocolTarget.protocolVersion)
                        .build(),
                )
                .build(),
        ).build().toString()
        val output = outputFile.asFile.get().toPath()
        output.atomicWriteText(source)
        logger.lifecycle(
            "Generated Minecraft ${minecraftProtocolTarget.minecraftVersion} protocol ${minecraftProtocolTarget.protocolVersion}: $output",
        )
    }

}
