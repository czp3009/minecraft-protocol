# Minecraft Web Map Demo

This subproject is a local integration demo that reads the saved state of a live Minecraft world and serves a browser
map. It demonstrates the repository's data-pack loading, vanilla protocol-data projection, dimension layouts, semantic
Chunk decoding, live Anvil reads, Ktor, kRPC, and Kotlin/JS support in one application.

The map observes files already saved to disk. It does not connect to the running server, see unsaved changes, write to
the world, or provide a production server administration interface.

## Requirements

Run Gradle commands from the repository root. The JVM path requires `java` on `PATH` at the major selected by the
repository. A native executable can only run on a host supported by its Kotlin/Native target.

The selected world must match the repository-selected Minecraft release and contain a regular `level.dat`. The demo
loads the enabled data packs from that world and combines them with the matching generated vanilla protocol data; no
registry snapshot, dimension table, or height setting is supplied separately.

## World selection

Set `MINECRAFT_WORLD_DIRECTORY` to an absolute path or a path relative to the process working directory. When the
variable is absent, the demo walks upward to `.minecraft-protocol-root`, then selects the first directory in sorted
order below the launcher demo's matching `minecraft/<version>/saves` directory.

The HTTP listener defaults to `127.0.0.1:8080`. Override it with `MINECRAFT_WEB_MAP_HOST` and
`MINECRAFT_WEB_MAP_PORT`. This is a trusted local demo; do not expose it directly to an untrusted network.

## Interface

Open `http://127.0.0.1:8080` after Gradle reports that the server has started. Dragging changes the viewport. The mouse
wheel and the `+`/`-` controls use five fixed zoom levels from one to sixteen pixels per Block; wheel zoom keeps the map
center fixed. The layer menu switches among the dimensions recorded by the world.

The request debounce starts after a drag ends, after a wheel-zoom sequence settles, or after the last resize event.
While a request is pending, the browser translates and uniformly scales the committed Canvas as a local prediction; it
never rotates or independently stretches either axis. Replacement Canvas batches cover newly exposed areas first, then
replace predicted overlap, and another interaction cancels both the pending RPC generation and the replacement batches.
A dimension change immediately clears rendered and cached Canvas tiles.

Viewport requests use inclusive Chunk ranges. The controller accepts each full response atomically, successful surfaces
and resolution-specific Chunk tiles remain cached across movement, failed reads are repaired with bounded single-Chunk
retries, and Canvas work is divided into frame-budgeted batches. The status in the lower-right corner shows both the
current center and pixels-per-Block scale.

For ordinary dimensions, each surface cell is the first non-air Block found by scanning downward. The Nether instead
selects the first Block other than air, bedrock, or netherrack, and falls back to netherrack when no such Block exists.
The server runs bounded Region reads and surface projection on `Dispatchers.Default`; palette-only air Sections are
skipped, and mixed Sections retain only unresolved columns while the scan descends.

Startup has two phases. Phase 1 automatically prepares the only supported asset path before the map becomes visible:

1. read the official Mojang Piston version manifest;
2. verify the selected version metadata SHA-1;
3. validate the official client JAR size with `HEAD` and probe HTTP Range support;
4. index the ZIP central directory with zip.js;
5. concurrently fetch the distinct 64 KiB Range pages that cover blockstates, models, textures, and animation metadata.

The first phase shows the current operation, indexed-file count, cached-byte count, and overall progress. Entry offsets
come from the parsed ZIP central directory rather than fixed constants. Touching entry spans are merged, disjoint spans
remain separate, and the resulting spans are deduplicated into 64 KiB pages. Each page uses a cache-bypassing request
and retries after two seconds without an attempt limit; reloading or closing the page cancels those retries. Compressed
pages remain in memory, so adjacent ZIP entries share requests. Phase 2 lazily decompresses individual files from those
pages and caches in-flight, successful, and unavailable JSON resources, decoded images, and baked sprites. Reload or
retry replaces the entire asset session with a new session against the same official source. Cuboid models use their
official upward face. Models without an upward face, including crossed plants, use their official particle texture as a
map fallback, with the relevant built-in grass, foliage, water, or redstone tint. The server and build outputs never
contain, proxy, or persist official Minecraft asset bytes.

## Run

The simplest way to run the demo is the JVM Gradle task. It builds and stages the browser bundle, supplies that web root
to the server, and keeps the process attached until it is stopped:

```powershell
.\gradlew.bat :demo:web-map:runJvm
```

Set the world override in the same shell before running the task when automatic launcher-world discovery is not wanted:

```powershell
$env:MINECRAFT_WORLD_DIRECTORY = 'C:\path\to\world'
.\gradlew.bat :demo:web-map:runJvm
```

The declared Native targets can also run directly through Gradle on a matching host; no separate install task or copied
web directory is required. For example, Windows x64 uses:

```powershell
.\gradlew.bat :demo:web-map:runDebugExecutableMingwX64
```

```shell
./gradlew :demo:web-map:runDebugExecutableLinuxX64
```

## Development

All JVM and Native run tasks build and stage the production browser bundle automatically, then pass its location to the
server process. The narrow verification tasks are:

```powershell
.\gradlew.bat :demo:web-map:jvmTest
.\gradlew.bat :demo:web-map:jsNodeTest
.\gradlew.bat :demo:web-map:jsBrowserDistribution
```
