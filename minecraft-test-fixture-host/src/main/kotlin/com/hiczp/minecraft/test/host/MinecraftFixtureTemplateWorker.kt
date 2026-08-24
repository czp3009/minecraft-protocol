package com.hiczp.minecraft.test.host

import kotlinx.coroutines.runBlocking
import java.nio.file.Path

fun main(arguments: Array<String>) = runBlocking {
    require(arguments.isNotEmpty()) {
        "Expected a fixture template kind"
    }
    when (arguments[0]) {
        "server" -> {
            require(arguments.size == 5) {
                "Expected server version, JAR, output root, and work root"
            }
            generateOfficialMinecraftServerTemplate(
                minecraftVersion = arguments[1],
                serverJar = Path.of(arguments[2]),
                outputRoot = Path.of(arguments[3]),
                workRoot = Path.of(arguments[4]),
            )
        }

        "client" -> {
            require(arguments.size == 11) {
                "Expected client versions, HMC coordinates, runtime, template, manifest, and work root"
            }
            generateHeadlessClientTemplate(
                minecraftVersion = arguments[1],
                headlessMcVersion = arguments[2],
                fabricLoaderVersion = arguments[3],
                hmcSpecificsReleaseTag = arguments[4],
                hmcSpecificsAssetName = arguments[5],
                hmcSpecificsAssetUrl = arguments[6],
                runtimeDirectory = Path.of(arguments[7]),
                templateDirectory = Path.of(arguments[8]),
                manifestFile = Path.of(arguments[9]),
                workRoot = Path.of(arguments[10]),
            )
        }

        else -> error("Unknown fixture template kind: ${arguments[0]}")
    }
}
