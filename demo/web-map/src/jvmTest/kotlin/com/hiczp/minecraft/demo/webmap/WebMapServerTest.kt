package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.world.format.DimensionId
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class WebMapServerTest {
    @Test
    fun servesBoundedStaticFilesAndKeepsRpcPrefixReserved() {
        val temporaryDirectory = Files.createTempDirectory("minecraft-web-map-test")
        try {
            Files.writeString(temporaryDirectory.resolve("index.html"), "<main>map</main>")
            Files.writeString(temporaryDirectory.resolve("styles.css"), "body { color: green; }")
            testApplication {
                application {
                    webMapModule(
                        webMapService = TestWebMapService,
                        webRoot = Path(temporaryDirectory.toString()),
                    )
                }

                val indexResponse = client.get("/")
                assertEquals(HttpStatusCode.OK, indexResponse.status)
                assertEquals(ContentType.Text.Html.contentType, indexResponse.contentType()?.contentType)
                assertEquals(ContentType.Text.Html.contentSubtype, indexResponse.contentType()?.contentSubtype)
                assertEquals("<main>map</main>", indexResponse.bodyAsText())

                val styleResponse = client.get("/styles.css")
                assertEquals(HttpStatusCode.OK, styleResponse.status)
                assertEquals(ContentType.Text.CSS.contentType, styleResponse.contentType()?.contentType)
                assertEquals(ContentType.Text.CSS.contentSubtype, styleResponse.contentType()?.contentSubtype)

                assertEquals(HttpStatusCode.NotFound, client.get("/missing.js").status)
                assertEquals(HttpStatusCode.NotFound, client.get("/rpc/not-a-static-file").status)
                assertEquals(HttpStatusCode.NotFound, client.get("/assets/not-a-static-file").status)
                assertEquals(HttpStatusCode.NotFound, client.get("/%2e%2e/index.html").status)

                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get("/assets/revision/minecraft/textures/block/stone.png").status,
                )
            }
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }
}

private object TestWebMapService : WebMapService {
    override suspend fun worldMetadata(): WorldMetadata = WorldMetadata("test", listOf(DimensionId.Overworld))

    override fun assetLoading(): Flow<AssetLoadStatus> = flowOf(AssetLoadStatus.Ready("revision", 1, 3))

    override fun blockRenderResources(
        blockRenderResourceRequest: BlockRenderResourceRequest,
    ): Flow<BlockRenderResourceResult> = flowOf()

    override suspend fun textureResource(textureResourceRequest: TextureResourceRequest): TextureResource? = null

    override fun querySurface(surfaceRequest: SurfaceRequest): Flow<SurfaceQueryUpdate> =
        flowOf(SurfaceQueryUpdate.Rejected(SurfaceQueryRejection.UNKNOWN_DIMENSION))
}
