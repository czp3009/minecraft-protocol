# World MCP demo

- This is a Native application for mingwX64, linuxX64 and macosArm64, with portable editor logic and tests in common
  source sets. Start it through Gradle.
- One editor owns the selected world across all HTTP sessions. Serialize tool execution and world switching; a failed
  selection retains the previous world. HTTP disconnects do not release the world.
- Version differences are advisory. Expose the library's generated game/DataVersion and the world's stored versions; do
  not reject selection or record writes solely for a DataVersion mismatch or automatically migrate a world.
- Every persisted terrain/entity/POI record, including raw writes and maintenance writes, receives the library's current
  DataVersion. Keep this application policy in the demo; do not change lower-level codecs or stores. Untouched records
  and level.dat retain their stored versions.
- Install Native signal handlers after Ktor starts with `wait=false`. Handlers only complete the shutdown Deferred; the
  main flow stops Ktor and closes the world. Do not call blocking cleanup from a signal handler (KTOR-9308/KTOR-9309).
- Storage metadata must use Region index/record inspection. Content summaries use selective NBT deserialization, never a
  complete Chunk decode followed by discarding fields.
- Raw SNBT preserves supplied NBT content. Expanded terrain and spatial edits use world-format domain codecs and
  maintain editing-related data in this demo.
- Complete Region writes replace the selected storage family, including removal of omitted records. Spatial writes
  preserve unrelated data and report partial persistence failures.
- Pagination is bound to the selected world, editor revision and query. Never silently omit air, missing records or
  unreturned pages.
- Keep tool descriptions, diagnostics, documentation and comments in English. Describe coordinate order, replacement
  scope and representation in the tool schema.
- Verify with the host's `:demo:world-mcp:mingwX64Test`, `linuxX64Test` or `macosArm64Test`, then
  `:demo:world-mcp:allTests`. HTTP tests use the SDK client and Ktor test host; Native filesystem tests use disposable
  worlds below this module's build directory.
