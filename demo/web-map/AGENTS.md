# Web map demo

This private application demonstrates library composition. Prefer existing repository values and conversions such as
`Identifier`, `MinecraftBlockIds`, `DimensionId`, `MinecraftCoordinates`, `ChunkRange`, `RegionPosition`,
`MinecraftChunkContext`, and live world APIs over parallel demo-specific Minecraft models or constants.

## Local design

- `commonMain` owns only the kRPC contract, serializable map DTOs, viewport state, and the demo-specific surface
  projection policy. Keep filesystem and browser APIs out of it.
- `serverMain` composes `world-io`, `protocol-datapack`, and generated vanilla data. Every request owns its Region
  handles and read scopes. Do not add cross-request Region, Chunk, or surface caches to the server.
- Region absence and absent Chunk headers are omissions. Region-level failures mark every requested Chunk in that
  Region, while payload, decompression, decoding, and incomplete-generation failures mark only the affected Chunk.
  Cancellation and unexpected programming failures must still propagate.
- `jsMain` owns Leaflet, Canvas, the official Piston client-JAR Range session, asset parsing, and browser memory caches.
  Do not add another asset host, a runtime source selector, automatic source fallback, persistent asset storage, or a
  server-side asset proxy.
- Keep official asset loading as the global first UI phase. Index the client JAR, preload bounded compressed Range pages
  for the relevant asset entries, report file and byte progress, then lazily decompress and decode from memory in the
  map phase. Derive entry spans from parsed ZIP metadata, preserve disjoint spans before splitting them into 64 KiB
  pages, and retry each page every two seconds without an attempt limit while its asset session remains active.
- A pending viewport request never clears the committed render state. Apply one full response atomically, retain old
  values for `read_failed`, remove omitted Chunks, and render replacements in bounded animation-frame batches.
- Keep the five integer zoom levels aligned with one, two, four, eight, and sixteen pixels per Block. Wheel zoom remains
  centered on the current map center.
- User-facing text and source comments in this subproject are written in English.

## Verification

Run `:demo:web-map:jvmTest` for contracts, projection, discovery, Region failure isolation, and static serving. Run
`:demo:web-map:jsNodeTest` for browser-independent request, cache, and asset-model logic. Browser packaging changes also
require `:demo:web-map:jsBrowserDistribution` and the affected JVM/native run task. Do not add custom executable install
tasks; this non-interactive server runs directly through Gradle. Verify configuration-cache store and reuse after
changing task inputs, outputs, or distribution wiring.
