package com.hiczp.minecraft.protocol.buildScript

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
        val target = targetFile.asFile.get().toPath()
            .readOfficialMinecraftTargetReport()
            .target
        val source = FileSpec.builder(
            "com.hiczp.minecraft.protocol.model",
            "MinecraftProtocol",
        ).addType(
            TypeSpec.objectBuilder("MinecraftProtocol")
                .addKdoc(
                    "The single protocol revision implemented by this build.\n\n" +
                            "Generated from version.json in the matching official server JAR.\n",
                )
                .addProperty(
                    PropertySpec.builder("MINECRAFT_VERSION", String::class, CONST)
                        .initializer("%S", target.minecraftVersion)
                        .build(),
                )
                .addProperty(
                    PropertySpec.builder("PROTOCOL_VERSION", Int::class, CONST)
                        .initializer("%L", target.protocolVersion)
                        .build(),
                )
                .build(),
        ).build().toString()
        val output = outputFile.asFile.get().toPath()
        output.atomicWriteText(source)
        logger.lifecycle(
            "Generated Minecraft ${target.minecraftVersion} protocol " +
                    "${target.protocolVersion}: $output",
        )
    }

}
