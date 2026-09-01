# Web map demo

This private application demonstrates library composition. Prefer existing repository values and conversions such as
`Identifier`, `MinecraftBlockIds`, `DimensionId`, `MinecraftCoordinates`, `ChunkRange`, `RegionPosition`,
`MinecraftChunkContext`, and live world APIs over parallel demo-specific Minecraft models or constants.

## Local design

- `commonMain` owns the kRPC contract, serializable map and resource DTOs, viewport state, official asset JSON models,
  and the demo-specific surface projection policy. Keep filesystem and browser APIs out of it.
- This is application/demo code. Leave composition and data types at Kotlin's default visibility instead of adding
  blanket `internal` modifiers or private constructors; reserve `private` for actual file or implementation details.
- Every surface RPC returns a `Flow` of individual Chunk updates; Region grouping and handles are backend-only details.
  The backend groups target Chunks before opening files, processes different Regions concurrently, and uses bounded
  parallel workers over one caller-owned live Region handle per read attempt. A successful Chunk carries the signed
  Anvil header timestamp in epoch seconds and a 16 by 16 paletted surface whose cells contain top-to-bottom Block-state
  layers. Keep this payload limited to dynamic data derived from the world; reusable model geometry, texture
  identifiers, animation metadata, and image bytes belong to resource RPCs.
- Apply one surface rule to every dimension: scan downward once, select the first non-air Block after air, retain
  transparent layers through the first opaque Block, and fall back to the first non-air Block from that same pass when
  no Block follows air.
- `serverMain` owns official client-asset download, verification, ZIP extraction, model interpretation, PNG transparency
  analysis, immutable kRPC resource serving, live Region reads, and surface projection. Asset progress begins at runtime
  startup and is exposed as a separate `Flow`. HTTP serves only the browser bundle.
- Organize the server surface cache by dimension and Region, but version and lock each entry at Chunk granularity. The
  sole cache version is `RegionChunkInfo.timestampEpochSeconds`; the Region header remains authoritative for inline and
  external Chunks, so never compare `.mca` or `.mcc` filesystem modification times. Serialize rebuilds of the same Chunk
  without preventing unrelated Chunks from running on backend worker threads.
- Region absence, absent Chunk headers, and incomplete-generation Chunks produce no update. Region-level failures mark
  every requested Chunk in that Region, while payload, decompression, and decoding failures mark only the affected
  Chunk. Keep retrying only explicitly failed Chunks inside the active surface Flow; cancellation and unexpected
  programming failures must still propagate.
- `jsMain` owns Leaflet, Canvas composition, RPC reconnection, deterministic selection from backend-provided render
  resources, and browser memory caches. It must not download or inspect the official client ZIP, parse Block-state or
  model JSON, or request `.mcmeta`; every application payload and image crosses kRPC.
- Keep the last published Canvas while requests, resource reads, or reconnection are in progress. Pan and zoom operate
  by local translation and uniform scaling. Build replacements offscreen and publish them atomically; never clear and
  restore an older snapshot during ordinary viewport updates.
- Starting a viewport interaction cancels the active surface Flow and viewport render Job. Do only local Canvas
  transforms while the viewport moves. Resource requests and resource-derived cache work are a separate, monotonic
  pipeline and must continue across interactions. When the viewport settles, render available Chunk caches immediately;
  debounce only the next surface request. Cancellable CPU loops must check or yield before viewport cache and Canvas
  side effects.
- Cache one maximum-zoom composited browser tile per dimension and Chunk coordinate, versioned by Anvil timestamp and
  asset revision. Scale that Canvas only in the display layer; never cache or recompose separate zoom variants. Treat
  surface responses as updates rather than an authoritative snapshot: only a later timestamp replaces cached Chunk data,
  while equal or older timestamps, read failures, and unreturned Chunks retain it. Keep the old Canvas tile until its
  replacement finishes offscreen, then commit the replacement atomically.
- Keep the five integer zoom levels aligned with one, two, four, eight, and sixteen pixels per Block. Wheel zoom remains
  centered on the current map center. Size map controls from their contents and internal padding rather than fixed
  container dimensions.
- User-facing text and source comments in this subproject are written in English.

## Verification

Run `:demo:web-map:jvmTest` for contracts, projection, cache concurrency, transparency analysis, discovery, Region
failure isolation, and HTTP serving. Run `:demo:web-map:jsNodeTest` for portable controller, Canvas-transform, and
asset-model logic. Browser packaging changes also require `:demo:web-map:jsBrowserDistribution` and the affected
JVM/native compile or run task. Do not add custom executable install tasks; this non-interactive server runs directly
through Gradle. Verify configuration-cache store and reuse after changing task inputs, outputs, or distribution wiring.
