# distribution-metadata

Mojang distribution metadata HTTP APIs for Minecraft: Java Edition. The module reads Version Manifest V2, referenced
version metadata and asset indexes, plus the current Java runtime catalog and its file manifests. Only Version Manifest
V2 and the current metadata schema are supported.

It returns serializable wire models, download descriptors, and streaming requests for arbitrary download URLs or asset
hashes. Integrity checks, filesystem operations, installation, and launching remain with the application.

## HTTP client

Create `MinecraftDistributionMetadataApiClient` with a caller-configured, caller-closed Ktor `HttpClient`. The caller
installs JSON content negotiation for typed metadata operations; streaming downloads do not require it. The module does
not create an engine, install plugins, or close the client. The client delegates the public
`MinecraftDistributionMetadataApi` contract to the module's Ktorfit-generated implementation, so consumers do not apply
the Ktorfit or KSP Gradle plugins.

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
`https://resources.download.minecraft.net/{first two hash characters}/{full hash}`. The pure function
`minecraftAssetPath(hash)` supplies that relative path with a lowercased hash; applications can reuse it beneath their
own asset storage directory. Neither operation validates the hash or performs I/O.

`MinecraftLibraryDownload.toDownload()` and `MinecraftLoggingFile.toDownload()` copy their `sha1`, `size`, and `url`
unchanged into a `MinecraftDownload`. The library's `path` and logging file's `id` remain on the original descriptors
for applications to choose their installation layout. All three conversions leave the source wire models unchanged and
perform no integrity validation.

## Metadata operations

Each operation performs one GET:

- `versionManifest()` reads the fixed V2 root;
- `versionMetadata(url)` reads a version document;
- `assetIndex(url)` reads an asset-index document;
- `javaRuntimeCatalog()` reads the current cross-platform runtime catalog, whose entries use `MinecraftDownload` for
  their `manifest` references;
- `javaRuntimeManifest(url)` reads a runtime file manifest.

Dynamic URLs and response fields are passed through without SHA-1, size, host, or cross-document identity validation. An
incomplete HTTP response fails in the transport or body-decoding layer, and malformed JSON fails during deserialization.
The returned hashes and sizes remain available to applications that choose to verify subsequently downloaded binary
artifacts. Retry, cache, offline, persistence, and installation policy remain with the caller.

## Streaming downloads

`download(url)` prepares a Ktor `HttpStatement` for any caller-supplied URL. Preparing the statement performs no
request;
each execution sends a GET using the caller's client configuration. Consume the response inside `execute { ... }` to
stream it. Ktor releases the response when the block completes, including early completion, failure, and cancellation;
the response channel must not escape the block. Calling the parameterless `execute()` instead buffers the response.

These extensions on `MinecraftDistributionMetadataApi` delegate to the same URL operation:

| Extension                        | URL source                                              |
|----------------------------------|---------------------------------------------------------|
| `downloadAsset(hash)`            | The asset resource URL derived from the lowercased hash |
| `download(minecraftDownload)`    | `MinecraftDownload.url`                                 |
| `download(minecraftAssetObject)` | The asset object's hash, via `downloadAsset(hash)`      |

Asset hashes identify individual resource objects. The asset-index JSON document itself has a full URL in version
metadata and is read through `assetIndex(url)`.

For example, the caller can copy a download into a supplied `kotlinx.io.RawSink` using Ktor's channel API:

```kotlin
suspend fun copyDownload(
    minecraftDistributionMetadataApi: MinecraftDistributionMetadataApi,
    minecraftDownload: MinecraftDownload,
    sink: RawSink,
): Long = minecraftDistributionMetadataApi.download(minecraftDownload).execute { httpResponse ->
    httpResponse.bodyAsChannel().readTo(sink)
}
```

The download APIs do not validate hash syntax, declared size, URL hosts, or response bytes. Descriptor fields remain
available for caller-owned integrity checks. Progress reporting, buffering, persistence, retries, and caching are also
caller decisions.
