# distribution-metadata

Mojang distribution metadata HTTP APIs for Minecraft: Java Edition. The module reads Version Manifest V2, referenced
version metadata and asset indexes, plus the current Java runtime catalog and its file manifests. Only Version Manifest
V2 and the current metadata schema are supported.

It returns serializable wire models and download descriptors. It does not download client/server JARs, libraries, asset
objects, logging files, or Java runtimes, and it does not install or launch the game.

## HTTP client

Create `MinecraftDistributionMetadataApiClient` with a caller-configured, caller-closed Ktor `HttpClient`. The caller
installs JSON content negotiation; the module does not create an engine, install plugins, or close the client. The
client delegates the public `MinecraftDistributionMetadataApi` contract to the module's Ktorfit-generated
implementation, so consumers do not apply the Ktorfit or KSP Gradle plugins.

The optional `pistonMetaBaseUrl` constructor parameter changes the base URL used by the two fixed-root operations. It
defaults to Mojang's Piston Meta service. Absolute URLs passed to the other operations are unaffected.

```kotlin
val distributionMetadataJson = Json {
    ignoreUnknownKeys = true
}
val applicationHttpClient = HttpClient {
    install(ContentNegotiation) {
        json(distributionMetadataJson)
    }
}
val minecraftDistributionMetadataApi = MinecraftDistributionMetadataApiClient(applicationHttpClient)
```

## Resolve the current release and its assets

This example follows the release selected by Version Manifest V2 and turns every asset-index entry into a download
descriptor without downloading the asset bytes. `applicationHttpClient` is the configured client from above.

```kotlin
suspend fun currentReleaseAssetDownloads(
    applicationHttpClient: HttpClient,
): List<MinecraftDownload> {
    val minecraftDistributionMetadataApi = MinecraftDistributionMetadataApiClient(applicationHttpClient)
    val minecraftVersionManifest = minecraftDistributionMetadataApi.versionManifest()
    val minecraftVersionReference = minecraftVersionManifest.versions.single {
        it.id == minecraftVersionManifest.latest.release
    }
    val minecraftVersionMetadata = minecraftDistributionMetadataApi.versionMetadata(minecraftVersionReference.url)
    val minecraftAssetIndex = minecraftDistributionMetadataApi.assetIndex(minecraftVersionMetadata.assetIndex.url)
    return minecraftAssetIndex.objects.values
        .distinctBy { minecraftAssetObject -> minecraftAssetObject.hash.lowercase() }
        .map(MinecraftAssetObject::toDownload)
}
```

`MinecraftAssetObject.toDownload()` lowercases the returned hash and derives
`https://resources.download.minecraft.net/{first two hash characters}/{full hash}`. It performs no network request or
integrity validation.

## Operations

Each operation performs one GET:

- `versionManifest()` reads the fixed V2 root;
- `versionMetadata(url)` reads a version document;
- `assetIndex(url)` reads an asset-index document;
- `javaRuntimeCatalog()` reads the current cross-platform runtime catalog;
- `javaRuntimeManifest(url)` reads a runtime file manifest.

Dynamic URLs and response fields are passed through without SHA-1, size, host, or cross-document identity validation. An
incomplete HTTP response fails in the transport or body-decoding layer, and malformed JSON fails during deserialization.
The returned hashes and sizes remain available to applications that choose to verify subsequently downloaded binary
artifacts. Retry, cache, offline, persistence, and installation policy remain with the caller.
