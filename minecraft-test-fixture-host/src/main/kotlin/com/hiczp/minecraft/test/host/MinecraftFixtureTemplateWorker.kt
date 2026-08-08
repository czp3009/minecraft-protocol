package com.hiczp.minecraft.test.host

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

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
                serverJar = Path(arguments[2]),
                outputRoot = Path(arguments[3]),
                workRoot = Path(arguments[4]),
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
                runtimeDirectory = Path(arguments[7]),
                templateDirectory = Path(arguments[8]),
                manifestFile = Path(arguments[9]),
                workRoot = Path(arguments[10]),
            )
        }

        else -> error("Unknown fixture template kind: ${arguments[0]}")
    }
}
