# Minecraft World MCP Demo

A local, stateful Minecraft world editor using
the [official MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
and Streamable HTTP. Agents select an existing world, inspect its Minecraft hierarchy, and edit saved terrain using
SNBT, expanded Sections or absolute block coordinates. This demo targets the repository-selected Minecraft release.

## Run

The demo supports Windows x64, Linux x64 and macOS ARM64 through Kotlin/Native. Run the command for your host from the
repository root, with the repository's matching Java major on `PATH` for Gradle.

Windows x64:

```powershell
.\gradlew.bat :demo:world-mcp:runDebugExecutableMingwX64
```

Linux x64:

```shell
./gradlew :demo:world-mcp:runDebugExecutableLinuxX64
```

macOS ARM64:

```shell
./gradlew :demo:world-mcp:runDebugExecutableMacosArm64
```

Connect an MCP client to `http://127.0.0.1:8081/mcp`. Set `MINECRAFT_MCP_PORT` before launching to select another port.
The server binds to loopback. There is no installation step, initial world argument, GUI, or stdio transport.

The process has **one selected world shared by all HTTP sessions**. It starts without a selection. Select a directory
using `select_world`; all subsequent tools use it. The world remains locked when an HTTP client disconnects.
`close_world`, a successful switch, or process shutdown releases its vanilla `session.lock`.

The following JSON snippets are tool arguments, not JSON-RPC envelopes. For `select_world`, substitute an existing
world directory on your machine:

```json
{
  "path": "C:/Users/you/AppData/Roaming/.minecraft/saves/My World"
}
```

Relative paths resolve from the repository root. Selection reads `level.dat`, enabled data packs and dimension
definitions. It never creates a world or upgrades a version. A failed validation or lock acquisition retains the
previous world. `get_world_info` returns its normalized path, name, versions, spawn and dimensions.

Both selection and world information report the world's `version` (game name), `dataVersion` (disk schema revision),
and `storageVersion` (the `level.dat` storage-format field), plus `library.minecraftVersion` and `library.dataVersion`
from this build's generated release data. `dataVersionMatchesLibrary` reports equality, not compatibility.
Different versions may work correctly or may fail; the agent decides whether to proceed with reads and writes.
A DataVersion difference alone never rejects selection or record writes and never triggers automatic migration.
Actual schema or data incompatibilities still produce tool errors. Every written terrain/entity/POI record receives
the library's current DataVersion, including raw replacements and neighbouring light/POI maintenance writes.
Untouched records and `level.dat` keep their stored versions, so a world can contain mixed DataVersions. Stamping a
version does not migrate incompatible raw content; callers supply a valid payload for the current format.

## Navigation and metadata

| Tool                                            | Parameters and result                                                                                                |
|-------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| `select_world`, `close_world`, `get_world_info` | Selection lifecycle and world identity                                                                               |
| `list_dimensions`, `get_dimension_info`         | Declared dimension identifiers and inclusive vertical bounds                                                         |
| `list_regions`                                  | `dimension`, optional `kind`; paginated Region coordinates, unioning families when kind is omitted                   |
| `get_region_info`                               | `dimension`, `region`; MCA existence, size, modification time and indexed Chunk count per family                     |
| `list_chunks`                                   | `dimension`, `region`, optional `kind`; paginated indexed Chunk coordinates                                          |
| `get_chunk_info`                                | `dimension`, `chunk`, optional `kind`; compression, payload size, placement and record timestamp                     |
| `list_sections`, `get_section_info`             | `dimension`, `chunk`, optional `kind`; saved Section Ys and palette/POI metadata; the latter takes `sectionY`        |
| `convert_coordinates`                           | `dimension`, `from`, and its `position`, `chunk` or `region`; use `from: "box"` with `min`/`max` for covering ranges |
| `describe_blocks`                               | Optional identifier substring `query`; defaults and legal property values from official data                         |

`kind` is `terrain` (default), `entities` or `poi`. Entity storage has no Section level. A Region/Chunk coordinate is
`{"x":0,"z":0}`. Block and Section positions are `[x,y,z]`. Section Y is absolute, including negative values; it is
not an array index. All conversions delegate to the library's floor-based coordinate operations.

`get_region_info(includeRecords=true)` and `list_chunks(includeRecords=true)` include record metadata.
These operations read indexes and small record headers, plus metadata for external `.mcc` payloads; they do not
decompress Chunks. MCA modification times and Anvil record timestamps are reported separately.

`get_chunk_info(includeContent=true)` additionally inspects terrain/POI NBT. Section inspection also needs the owning
Chunk's NBT stream. Selective deserialization skips packed arrays and unrelated subtrees instead of constructing a
complete Chunk or NBT document. Stored palette entries can be unused; this metadata does not claim actual block counts.
There is no persistent decoded-world cache. Region handles and decoded values are reused within each batch where useful.

For example, obtain a dimension from `list_dimensions`, then list its first page of terrain Chunks in Region `(0,0)`:

```json
{
  "dimension": "minecraft:overworld",
  "region": {
    "x": 0,
    "z": 0
  },
  "pageSize": 32
}
```

All results contain `world` (path, selection and editor revision), `target`, and `result`. Paginated results have
`items`, `total`, and `nextCursor`. Echo the cursor with the same query to continue. Changes or world switches expire
old cursors. `pageSize` is a demo response policy of 1–1024 items; defaults are 32, one Chunk for Region content, and
256 blocks for spatial reads. Missing records, omitted pages and known air are distinct.

## Complete content reads and writes

`read_region`, `read_chunk`, and `read_section` accept `representation: "raw" | "expanded"`, defaulting to `raw`.
Their `write_*` counterparts accept the returned `data` representation.

**Raw data is a complete SNBT compound string.** It preserves NBT numeric widths, long-array values and unknown fields,
except that the owning record's DataVersion is set to the library's current version when saved.
Raw writes preserve supplied logical content, including lighting, heightmaps and scheduled ticks. They validate the
record envelope and target coordinates but do not repair its gameplay semantics. Saving can change compression,
palette layout on semantic paths, allocation and storage timestamps; byte-for-byte file identity is not a contract.

**Expanded terrain data** has this structure:

```text
Chunk:   { nbt: "<root compound excluding sections>", sections: [Section, ...] }
Section: { y: 4, nbt: "<compound excluding Y/block_states/biomes>",
           blocks: [4096 {name, properties} values], biomes: [64 biome identifiers] }
```

Arrays use X fastest, then Z, then Y. Biomes use the Section's 4×4×4 quart-cell grid. A light-only Section has null
`blocks` and `biomes`. Empty/air terrain still expands to its full cell count when explicitly requested. Remaining
fields use SNBT so unknown data and numeric types survive; do not duplicate the excluded fields in `nbt`.
Entity/POI expanded content is `{nbt: "<complete compound>"}` because those records have no block/biome palette to
expand.
Expanded terrain editing requires completed Chunks; raw access preserves unfinished generation data.

For `read_section`, specify a Chunk and absolute Section Y:

```json
{
  "dimension": "minecraft:overworld",
  "chunk": {
    "x": 0,
    "z": 0
  },
  "sectionY": 4,
  "representation": "expanded"
}
```

Pass the returned `result.data`, after modification, as `data` to `write_section` with the same coordinates and
representation.
Section writes require an existing owning Chunk. Complete Chunk/Region writes can create their targets.

**Writes replace the complete addressed object in the selected family.** `write_chunk(kind="terrain")` leaves the
independent entity record intact. `write_region` takes `data: [{chunk: {x,z}, data: ...}, ...]` containing **all desired
records**; omitted old Chunks are removed. It is not an append operation, and one `read_region` page is not necessarily
a complete replacement. The Region write delegates to the library's staged Region replacement.

## Spatial construction

`read_blocks` always returns expanded states and explicit per-position availability. Bounds are absolute and inclusive;
results are paginated in X/Z/Y order. `write_blocks` accepts either
`blocks: [{position:[x,y,z], state:{name,properties}}, ...]`
or an inclusive `min`/`max` box with a complete `values` array in the same order.

For a uniform structure use `fill_blocks`. The following places a three-block-wide road across multiple Chunks:

```json
{
  "dimension":"minecraft:overworld",
  "min":[100,64,-20],
  "max":[500,64,-18],
  "state":{"name":"minecraft:stone_bricks"}
}
```

Add `match: {name: "minecraft:air"}` to replace only air; `match.properties` matches a subset of state properties.
Omitted write-state properties are resolved using the official default, and invalid property combinations fail.

Missing Chunks fail before any spatial writes. To deliberately create empty Chunks, include
`missingChunks: "create_empty"` and `biome: "minecraft:plains"` (or the intended biome identifier). This creates saved
empty terrain with that biome, not generated terrain. Existing incomplete-generation Chunks require raw access.

Expanded terrain writes invalidate saved heightmaps, lighting, and affected POI data. Spatial edits also remove
scheduled block/fluid ticks at changed positions and remove Block Entities when block identity changes. Section edits
apply the same cleanup to their changed cells. Complete expanded Chunk payloads own their Block Entity/tick contents.
Existing neighbouring Chunks have lighting invalidated because border lighting can change. The matching official
loader rebuilds missing heightmaps, untrusted lighting and invalid POI Sections.

Newly placed blocks that need a Block Entity receive vanilla defaults when normal game access creates it. To provide
custom contents, include their complete Block Entity NBT in a Chunk write. Editing does not execute neighbour updates,
redstone, fluid flow, gravity, multi-block placement or other gameplay logic; callers supply desired block states.

Every write saves immediately. There is no separate save command or undo history. Validation runs before persistent
mutation where possible; I/O failures may still leave partial writes. Error results identify the phase, completed
Chunks and records whose completion is not confirmed. Multi-file writes, neighbour maintenance and sidecars do not
promise transaction atomicity. Cancellation propagates and can also leave completed writes.

## Verification

```powershell
.\gradlew.bat :demo:world-mcp:mingwX64Test
.\gradlew.bat :demo:world-mcp:allTests
```

Use `./gradlew :demo:world-mcp:linuxX64Test` on Linux x64 or `./gradlew :demo:world-mcp:macosArm64Test` on macOS ARM64.
Portable tests cover the SDK HTTP handshake, schema/tool discovery, negative coordinates, selective inspection,
round trips and editing scenarios. Native entries supply disposable system-filesystem worlds below `build/` to exercise
real file locks and persistence. A corrupt-payload test verifies metadata remains readable without decompression.
