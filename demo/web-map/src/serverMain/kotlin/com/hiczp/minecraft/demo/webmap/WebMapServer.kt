package com.hiczp.minecraft.demo.webmap

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json

data class WebMapServerConfiguration(
    val host: String,
    val port: Int,
    val webRoot: Path,
) {
    init {
        require(host.isNotBlank()) { "Web-map listen host must not be blank" }
        require(port in 1..65_535) { "Web-map listen port must be between 1 and 65535" }
    }
}

fun Application.webMapModule(
    webMapService: WebMapService,
    webRoot: Path,
) {
    install(Krpc) {
        serialization {
            json(WebMapJson)
        }
    }
    routing {
        rpc("/rpc") {
            registerService<WebMapService> { webMapService }
        }
        get("/") {
            call.respondWebFile(webRoot, listOf("index.html"))
        }
        get("/{path...}") {
            val pathSegments = call.parameters.getAll("path")
                .orEmpty()
                .flatMap { value -> value.split('/') }
            if (pathSegments.firstOrNull() in RESERVED_WEB_PATHS || !pathSegments.isSafeWebPath()) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respondWebFile(webRoot, pathSegments)
            }
        }
    }
}

fun startWebMapServer(
    webMapService: WebMapService,
    webMapServerConfiguration: WebMapServerConfiguration,
) {
    embeddedServer(
        factory = CIO,
        host = webMapServerConfiguration.host,
        port = webMapServerConfiguration.port,
    ) {
        webMapModule(webMapService, webMapServerConfiguration.webRoot)
    }.start(wait = true)
}

private val RESERVED_WEB_PATHS: Set<String> = setOf("assets", "rpc")

private suspend fun ApplicationCall.respondWebFile(
    webRoot: Path,
    pathSegments: List<String>,
) {
    if (!pathSegments.isSafeWebPath()) {
        respond(HttpStatusCode.NotFound)
        return
    }
    val path = Path(webRoot, *pathSegments.toTypedArray())
    val metadata = SystemFileSystem.metadataOrNull(path)
    if (metadata?.isRegularFile != true) {
        respond(HttpStatusCode.NotFound)
        return
    }
    respondSource(
        source = SystemFileSystem.source(path),
        contentType = contentTypeFor(path.name),
        contentLength = metadata.size,
    )
}

private fun List<String>.isSafeWebPath(): Boolean =
    isNotEmpty() && all { segment ->
        segment.isNotEmpty() &&
                segment != "." &&
                segment != ".." &&
                '/' !in segment &&
                '\\' !in segment &&
                segment.none(Char::isISOControl)
    }

private fun contentTypeFor(fileName: String): ContentType = when (fileName.substringAfterLast('.', "")) {
    "html" -> ContentType.Text.Html
    "css" -> ContentType.Text.CSS
    "js", "mjs" -> ContentType.Application.JavaScript
    "json", "map" -> ContentType.Application.Json
    "png" -> ContentType.Image.PNG
    "svg" -> ContentType.Image.SVG
    "ico" -> ContentType.Image.XIcon
    "woff2" -> ContentType.parse("font/woff2")
    else -> ContentType.Application.OctetStream
}
