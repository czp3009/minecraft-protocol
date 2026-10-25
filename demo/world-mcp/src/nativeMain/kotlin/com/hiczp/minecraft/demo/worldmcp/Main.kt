package com.hiczp.minecraft.demo.worldmcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.getenv
import platform.posix.signal

private val shutdownRequested = CompletableDeferred<Unit>()

/** Gradle-launched HTTP application; the process owns the world lease until close_world, switching or shutdown. */
@OptIn(ExperimentalForeignApi::class)
fun main() {
    val logger = KotlinLogging.logger {}
    val port = getenv("MINECRAFT_MCP_PORT")?.toKString()?.toInt() ?: 8081
    require(port in 1..65535) { "MINECRAFT_MCP_PORT must be in 1..65535" }
    val worldEditor = WorldEditor()
    val server = embeddedServer(CIO, host = "127.0.0.1", port = port) { WorldMcp.install(this, worldEditor) }
    try {
        server.start(wait = false)
        // Replace Ktor's handler after start: KTOR-9308 blocks in stop(), and KTOR-9309 overwrites earlier hooks.
        val shutdownHandler = staticCFunction<Int, Unit> { shutdownRequested.complete(Unit) }
        signal(SIGINT, shutdownHandler)
        signal(SIGTERM, shutdownHandler)
        logger.info { "Minecraft world MCP is listening at http://127.0.0.1:$port/mcp; select a world with select_world" }
        runBlocking { shutdownRequested.await() }
    } finally {
        try {
            server.stop(500, 5000)
        } finally {
            runBlocking { worldEditor.close() }
        }
    }
}
