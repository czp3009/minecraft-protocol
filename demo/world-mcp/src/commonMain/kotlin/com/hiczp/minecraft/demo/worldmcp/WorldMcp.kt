package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.world.format.MinecraftWorldFormat
import io.ktor.server.application.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Registers the shared process editor on the SDK's Streamable HTTP endpoint. HTTP sessions do not own the world lease. */
object WorldMcp {
    fun install(application: Application, worldEditor: WorldEditor) {
        application.mcpStreamableHttp(enableDnsRebindingProtection = true) { server(worldEditor) }
    }

    fun server(worldEditor: WorldEditor): Server = Server(
        Implementation("minecraft-world-editor", "1.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
        instructions = "This library targets Minecraft ${MinecraftProtocol.MINECRAFT_VERSION}, disk DataVersion ${MinecraftWorldFormat.WORLD_VERSION}. Call select_world before any world operation and compare the returned world dataVersion with library.dataVersion. Different versions may work correctly or may fail; a mismatch does not block access or trigger migration. Decide whether to proceed with reads and writes using this information. Every saved terrain/entity/POI record, including raw replacements and maintenance writes, gets the library DataVersion; untouched records and level.dat keep their versions. This stamp does not migrate incompatible raw content. The process has one selected world shared by all connections. Dimensions are explicit. Coordinates are absolute integers; boxes include both ends; flat arrays use X fastest, then Z, then Y. Raw means typed SNBT. Expanded terrain uses blocks and biomes arrays plus nbt for remaining fields. write_region replaces every record in the selected family, including removal of omitted Chunks. Spatial writes save immediately, preserve unrelated content, and never silently create missing Chunks. Use metadata tools before large reads and follow nextCursor until null. Edits or world switches expire cursors. Newly placed Block Entities receive vanilla defaults when the game accesses them; custom contents require a complete raw or expanded Chunk payload. Offline edits do not execute gameplay updates.",
    ).apply {
        ToolDefinitions.all.forEach { definition ->
            addTool(
                name = definition.name,
                description = definition.description,
                inputSchema = ToolSchema(
                    properties = JsonObject(definition.properties),
                    required = definition.required
                ),
                toolAnnotations = ToolAnnotations(
                    readOnlyHint = definition.readOnly,
                    destructiveHint = !definition.readOnly,
                    openWorldHint = false
                ),
            ) { request ->
                val arguments = request.arguments ?: JsonObject(emptyMap())
                val reply = withContext(Dispatchers.Default) { worldEditor.call(definition.name, arguments) }
                CallToolResult(
                    content = listOf(TextContent(EditorJson.format.encodeToString(reply.value))),
                    structuredContent = reply.value,
                    isError = reply.isError
                )
            }
        }
    }
}

/** Tool catalog kept separate from execution so the same contracts can be inspected and exercised by tests. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val properties: Map<String, JsonObject> = emptyMap(),
    val required: List<String> = emptyList(),
    val readOnly: Boolean = true,
)

object ToolDefinitions {
    private fun scalar(type: String, description: String): JsonObject =
        buildJsonObject { put("type", type); put("description", description) }

    private fun choice(description: String, vararg values: String): JsonObject = buildJsonObject {
        put("type", "string"); put("description", description); put("enum", JsonArray(values.map(::JsonPrimitive)))
    }

    private fun objectSchema(
        properties: Map<String, JsonObject>,
        required: List<String> = properties.keys.toList()
    ): JsonObject = buildJsonObject {
        put("type", "object"); put("properties", JsonObject(properties)); put(
        "required",
        JsonArray(required.map(::JsonPrimitive))
    ); put("additionalProperties", false)
    }

    private fun arraySchema(items: JsonObject): JsonObject =
        buildJsonObject { put("type", "array"); put("items", items) }

    private val integer = scalar("integer", "Signed integer coordinate")
    private val position = buildJsonObject {
        put("type", "array"); put("items", integer); put("minItems", 3); put("maxItems", 3); put(
        "description",
        "Absolute [x,y,z] block coordinates"
    )
    }
    private val horizontal = objectSchema(mapOf("x" to integer, "z" to integer))
    private val blockProperties = buildJsonObject {
        put("type", "object"); put(
        "additionalProperties",
        buildJsonObject { put("type", "string") })
    }
    private val state = objectSchema(
        mapOf("name" to scalar("string", "Namespaced block identifier"), "properties" to blockProperties),
        listOf("name")
    )
    private val dimension = mapOf("dimension" to scalar("string", "Dimension identifier returned by list_dimensions"))
    private val kind =
        mapOf("kind" to choice("Independent storage family; defaults to terrain", "terrain", "entities", "poi"))
    private val region = dimension + kind + ("region" to horizontal)
    private val chunk = dimension + kind + ("chunk" to horizontal)
    private val section = chunk + ("sectionY" to scalar("integer", "Absolute Section Y, not block Y or array index"))
    private val paging = mapOf(
        "cursor" to scalar("string", "Opaque nextCursor from the same query"),
        "pageSize" to scalar(
            "integer",
            "Items per page, 1..1024; defaults to 32, 1 for Region contents, 256 for blocks"
        )
    )
    private val representation = mapOf(
        "representation" to choice(
            "raw: SNBT; expanded: palette-free arrays and typed SNBT for other fields. Defaults to raw",
            "raw",
            "expanded"
        )
    )
    private val nullableBlocks =
        buildJsonObject { put("anyOf", JsonArray(listOf(arraySchema(state), buildJsonObject { put("type", "null") }))) }
    private val nullableBiomes = buildJsonObject {
        put(
            "anyOf",
            JsonArray(
                listOf(
                    arraySchema(scalar("string", "Biome identifier")),
                    buildJsonObject { put("type", "null") })
            )
        )
    }
    private val expandedSection = objectSchema(
        mapOf(
            "y" to integer,
            "nbt" to scalar(
                "string",
                "SNBT compound of remaining Section fields, excluding Y, block_states and biomes"
            ),
            "blocks" to nullableBlocks,
            "biomes" to nullableBiomes,
        )
    )
    private val expandedChunk = objectSchema(
        mapOf(
            "nbt" to scalar(
                "string",
                "SNBT compound of remaining Chunk fields, excluding sections; includes positions, DataVersion, Status, Block Entities and ticks"
            ),
            "sections" to arraySchema(expandedSection),
        )
    )
    private val content = buildJsonObject {
        put("description", "Raw SNBT string, expanded terrain object, or {nbt: SNBT} for entities/POI")
        put(
            "anyOf",
            JsonArray(
                listOf(
                    scalar("string", "Complete raw SNBT compound"),
                    expandedChunk,
                    expandedSection,
                    objectSchema(mapOf("nbt" to scalar("string", "Complete entity/POI SNBT compound")))
                )
            )
        )
    }
    private val box = dimension + mapOf("min" to position, "max" to position)
    private val creation = mapOf(
        "missingChunks" to choice(
            "Default error; create_empty explicitly creates empty terrain without generation",
            "error",
            "create_empty"
        ), "biome" to scalar("string", "Required initial biome when missingChunks=create_empty")
    )

    val all: List<ToolDefinition> = buildList {
        add(
            ToolDefinition(
                "select_world",
                "Validate and exclusively lock an existing world directory. Returns the world's game version and disk DataVersion alongside the library's supported versions. Different versions may work correctly or may fail; decide whether to read or write after comparing them. Version differences alone are allowed and do not trigger migration. Failed selection retains the previous world. All HTTP clients share this selection.",
                mapOf("path" to scalar("string", "World directory; relative paths resolve from the repository root")),
                listOf("path"),
                false
            )
        )
        add(
            ToolDefinition(
                "close_world",
                "Release the selected world and its file lock without stopping HTTP.",
                readOnly = false
            )
        )
        add(
            ToolDefinition(
                "get_world_info",
                "Inspect the selected path, name, world game version and disk DataVersion, library-supported game version and DataVersion, spawn and dimension identifiers without scanning terrain. Different versions may work correctly or may fail; the agent decides whether to proceed. dataVersionMatchesLibrary reports equality, not compatibility."
            )
        )
        add(
            ToolDefinition(
                "list_dimensions",
                "List declared dimensions and vertical bounds without reading Region contents."
            )
        )
        add(
            ToolDefinition(
                "get_dimension_info",
                "Read one dimension's bounds and location without enumerating its Chunks.",
                dimension,
                listOf("dimension")
            )
        )
        add(
            ToolDefinition(
                "list_regions",
                "List existing Region coordinates. Omit kind to union terrain, entities and POI. Does not load Chunk payloads.",
                dimension + kind + paging,
                listOf("dimension")
            )
        )
        add(
            ToolDefinition(
                "get_region_info",
                "Summarize terrain/entities/POI MCA files and occupied Chunk counts using indexes. includeRecords also reads small record headers and external-file metadata.",
                dimension + ("region" to horizontal) + ("includeRecords" to scalar(
                    "boolean",
                    "Include compression, size, timestamp and placement for indexed records"
                )),
                listOf("dimension", "region")
            )
        )
        add(
            ToolDefinition(
                "list_chunks",
                "List occupied Chunk coordinates in one Region from its index. Optional record metadata uses random access, without payload decompression.",
                region + paging + ("includeRecords" to scalar("boolean", "Include stored record metadata")),
                listOf("dimension", "region")
            )
        )
        add(
            ToolDefinition(
                "get_chunk_info",
                "Read stored metadata using small random reads. includeContent additionally scans selected NBT fields for terrain/POI, skipping packed arrays.",
                chunk + ("includeContent" to scalar("boolean", "Include selective terrain/POI NBT summary")),
                listOf("dimension", "chunk")
            )
        )
        add(
            ToolDefinition(
                "list_sections",
                "List saved terrain/POI Sections. Selective NBT projection skips packed arrays. Missing Chunk is distinct from an empty Section list.",
                chunk + paging,
                listOf("dimension", "chunk")
            )
        )
        add(
            ToolDefinition(
                "get_section_info",
                "Inspect one Section's stored palette or POI validity without expanding cells. Palette entries may be unused; they are not block counts.",
                section,
                listOf("dimension", "chunk", "sectionY")
            )
        )
        add(
            ToolDefinition(
                "convert_coordinates",
                "Convert block/section/chunk/region coordinates and optional local offsets. from=box converts inclusive min/max bounds to covering Chunk/Region ranges. Parent positions default to their minimum corner. Negative coordinates use floor semantics.",
                dimension + mapOf(
                    "from" to choice("Source coordinate level", "block", "section", "chunk", "region", "box"),
                    "position" to position,
                    "min" to position,
                    "max" to position,
                    "chunk" to horizontal,
                    "region" to horizontal,
                    "local" to buildJsonObject { put("anyOf", JsonArray(listOf(position, horizontal))) }),
                listOf("dimension", "from")
            )
        )
        add(
            ToolDefinition(
                "describe_blocks",
                "Search matching vanilla block identifiers and inspect default states and allowed property values.",
                paging + ("query" to scalar("string", "Identifier substring; empty lists all blocks"))
            )
        )
        for ((level, target) in listOf("region" to region, "chunk" to chunk, "section" to section)) {
            val required = listOf(
                "dimension",
                if (level == "region") "region" else "chunk"
            ) + if (level == "section") listOf("sectionY") else emptyList()
            add(
                ToolDefinition(
                    "read_$level",
                    "Read complete $level content as raw SNBT or expanded data. Region reads paginate whole Chunk records. Expanded terrain arrays use X/Z/Y order (4096 blocks, 64 biomes per Section); other fields retain SNBT types.",
                    target + representation + if (level == "region") paging else emptyMap(),
                    required
                )
            )
            val data = if (level == "region") arraySchema(
                objectSchema(
                    mapOf(
                        "chunk" to horizontal,
                        "data" to content
                    )
                )
            ) else content
            add(
                ToolDefinition(
                    "write_$level",
                    "Completely replace this $level in the selected storage family. Omitted children are removed; Region data must contain ALL desired records, not one read page. Every saved record gets the library DataVersion; other raw NBT is preserved. Untouched records keep their versions. Expanded terrain invalidates heightmaps, lighting and POI. Section writes require an existing Chunk. Saves immediately.",
                    target + representation + ("data" to data),
                    required + "data",
                    false
                )
            )
        }
        add(
            ToolDefinition(
                "read_blocks",
                "Read an inclusive absolute box as expanded block states, paginated in X/Z/Y order. Each position explicitly distinguishes present, missing and incomplete Chunks.",
                box + paging,
                listOf("dimension", "min", "max")
            )
        )
        add(
            ToolDefinition(
                "write_blocks",
                "Write explicit {position,state} entries in blocks, OR provide min/max and a complete values array in X/Z/Y order. Changes only addressed blocks and maintains derived data. Saves immediately and reports partial failures.",
                box + creation + mapOf(
                    "blocks" to arraySchema(
                        objectSchema(
                            mapOf(
                                "position" to position,
                                "state" to state
                            )
                        )
                    ), "values" to arraySchema(state)
                ),
                listOf("dimension"),
                false
            )
        )
        add(
            ToolDefinition(
                "fill_blocks",
                "Fill an inclusive absolute box with one state, optionally replacing only matching block IDs/properties. Crosses Section/Chunk/Region boundaries automatically. Saves immediately.",
                box + creation + mapOf(
                    "state" to state,
                    "match" to objectSchema(
                        mapOf(
                            "name" to scalar("string", "Only replace this block identifier"),
                            "properties" to blockProperties
                        ), emptyList()
                    )
                ),
                listOf("dimension", "min", "max", "state"),
                false
            )
        )
    }
}
