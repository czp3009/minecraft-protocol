package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

fun main() {
    val logger = KotlinLogging.logger("MinecraftWebMap")
    val currentWorkingDirectory = FileSystem.SYSTEM.canonicalize(".".toPath())
    val discoveredWorldDirectory = discoverWorldDirectory(
        fileSystem = FileSystem.SYSTEM,
        currentWorkingDirectory = currentWorkingDirectory,
        explicitWorldDirectory = platformEnvironmentVariable(WORLD_DIRECTORY_ENVIRONMENT),
        minecraftVersion = MinecraftProtocol.MINECRAFT_VERSION,
    )
    logger.info {
        "World directory source=${discoveredWorldDirectory.source.name.lowercase()} path=${discoveredWorldDirectory.path}"
    }
    val webRootValue = platformEnvironmentVariable(WEB_ROOT_ENVIRONMENT)?.takeIf(String::isNotBlank) ?: "web"
    val webRoot = SystemFileSystem.resolve(Path(webRootValue))
    check(SystemFileSystem.metadataOrNull(Path(webRoot, "index.html"))?.isRegularFile == true) {
        "Web root has no regular index.html: $webRoot"
    }
    val host = platformEnvironmentVariable(HOST_ENVIRONMENT)?.takeIf(String::isNotBlank) ?: DEFAULT_HOST
    val port = platformEnvironmentVariable(PORT_ENVIRONMENT)
        ?.takeIf(String::isNotBlank)
        ?.toIntOrNull()
        ?: DEFAULT_PORT
    val webMapRuntime = WebMapRuntime.open(discoveredWorldDirectory.path, logger)
    try {
        logger.info { "Serving the Minecraft web map on $host:$port from $webRoot" }
        startWebMapServer(
            webMapService = webMapRuntime.webMapService,
            webMapServerConfiguration = WebMapServerConfiguration(host, port, webRoot),
        )
    } finally {
        webMapRuntime.close()
    }
}

expect fun platformEnvironmentVariable(name: String): String?

private const val WORLD_DIRECTORY_ENVIRONMENT: String = "MINECRAFT_WORLD_DIRECTORY"
private const val WEB_ROOT_ENVIRONMENT: String = "MINECRAFT_WEB_ROOT"
private const val HOST_ENVIRONMENT: String = "MINECRAFT_WEB_MAP_HOST"
private const val PORT_ENVIRONMENT: String = "MINECRAFT_WEB_MAP_PORT"
private const val DEFAULT_HOST: String = "127.0.0.1"
private const val DEFAULT_PORT: Int = 8080
