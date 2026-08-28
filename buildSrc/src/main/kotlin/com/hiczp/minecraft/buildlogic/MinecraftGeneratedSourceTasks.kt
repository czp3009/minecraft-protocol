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
        val source = renderMinecraftProtocolSource(minecraftProtocolTarget)
        val output = outputFile.asFile.get().toPath()
        output.atomicWriteText(source)
        logger.lifecycle(
            "Generated Minecraft ${minecraftProtocolTarget.minecraftVersion} protocol ${minecraftProtocolTarget.protocolVersion}: $output",
        )
    }
}

@CacheableTask
abstract class GenerateMinecraftWorldFormatSourceTask :
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
        val source = renderMinecraftWorldFormatSource(minecraftProtocolTarget.worldVersion)
        val output = outputFile.asFile.get().toPath()
        output.atomicWriteText(source)
        logger.lifecycle(
            "Generated Minecraft world format ${minecraftProtocolTarget.worldVersion}: $output",
        )
    }
}

internal fun renderMinecraftProtocolSource(minecraftProtocolTarget: MinecraftProtocolTarget): String =
    FileSpec.builder(
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

internal fun renderMinecraftWorldFormatSource(worldVersion: Int): String {
    require(worldVersion >= 0) { "A Minecraft world version must be non-negative" }
    return FileSpec.builder(
        "com.hiczp.minecraft.world.format",
        "MinecraftWorldFormat",
    ).addType(
        TypeSpec.objectBuilder("MinecraftWorldFormat")
            .addKdoc(
                """
                The world format version implemented by this build.

                Generated from world_version in version.json in the matching official server JAR.
                Serialized NBT continues to name this value DataVersion.
                """.trimIndent(),
            )
            .addProperty(
                PropertySpec.builder("WORLD_VERSION", Int::class, CONST)
                    .initializer("%L", worldVersion)
                    .build(),
            )
            .build(),
    ).build().toString()
}
