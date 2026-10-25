package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.world.format.DimensionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.io.encoding.Base64

/** Process-wide editor. Every tool and close is serialized, including selection, reads and complete multi-record edits. */
class WorldEditor {
    private val mutex = Mutex()
    private var selected: WorldSession? = null
    private var selections = 0L
    private var revision = 0L

    suspend fun execute(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject = mutex.withLock {
        executeLocked(name, arguments)
    }

    /** Captures failures under the same admission as execution, so a later selection cannot relabel an earlier error. */
    suspend fun call(name: String, arguments: JsonObject): ToolReply = mutex.withLock {
        try {
            ToolReply(executeLocked(name, arguments), false)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            ToolReply(envelope(arguments, (failure as? EditorException)?.details ?: buildJsonObject {
                put("message", failure.message ?: "World operation failed")
                put("type", failure::class.simpleName)
            }), true)
        }
    }

    private suspend fun executeLocked(name: String, arguments: JsonObject): JsonObject {
        val result = when (name) {
            "select_world" -> select(arguments)
            "close_world" -> {
                release(); buildJsonObject { put("closed", true) }
            }

            else -> {
                val worldSession = requireNotNull(selected) { "No world selected; call select_world first" }
                if (name.startsWith("write_") || name == "fill_blocks") revision++
                when (name) {
                    "get_world_info" -> worldSession.worldInfo()
                    "list_dimensions" -> buildJsonObject {
                        put(
                            "items",
                            JsonArray(
                                worldSession.chunkContexts.keys.sortedBy(DimensionId::toString)
                                    .map(worldSession::dimensionInfo)
                            )
                        )
                    }

                    "get_dimension_info" -> worldSession.dimensionInfo(arguments.dimensionId())
                    "list_regions" -> listRegions(worldSession, arguments)
                    "get_region_info" -> regionInfo(worldSession, arguments)
                    "list_chunks" -> listChunks(worldSession, arguments)
                    "get_chunk_info" -> chunkInfo(worldSession, arguments)
                    "list_sections" -> sections(worldSession, arguments, false)
                    "get_section_info" -> sections(worldSession, arguments, true)
                    "convert_coordinates" -> coordinates(worldSession, arguments)
                    "describe_blocks" -> describeBlocks(arguments)
                    "read_region", "read_chunk", "read_section" -> readContent(worldSession, name, arguments)
                    "write_region", "write_chunk", "write_section" -> writeContent(worldSession, name, arguments)
                    "read_blocks" -> readBlocks(worldSession, arguments)
                    "write_blocks", "fill_blocks" -> writeBlocks(worldSession, name, arguments)
                    else -> error("Unknown tool $name")
                }
            }
        }
        return envelope(arguments, result)
    }

    suspend fun close() = mutex.withLock { release() }

    private suspend fun select(arguments: JsonObject): JsonObject {
        val path = FileSystem.SYSTEM.canonicalize(arguments.text("path").toPath())
        selected?.takeIf { it.root == path }?.let { return it.worldInfo() }
        val next = WorldSession.open(path, selections + 1)
        try {
            release()
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    next.minecraftWorldAccess.close()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
        selections++
        selected = next
        revision = 0
        return next.worldInfo()
    }

    private suspend fun release() {
        val previous = selected ?: return
        selected = null
        withContext(NonCancellable) { previous.minecraftWorldAccess.close() }
    }

    private fun envelope(arguments: JsonObject, result: JsonObject): JsonObject = buildJsonObject {
        put(
            "world",
            selected?.let {
                buildJsonObject {
                    put("path", it.root.toString()); put(
                    "selection",
                    it.selection
                ); put("revision", revision)
                }
            } ?: JsonNull)
        put(
            "target",
            JsonObject(arguments.filterKeys {
                it in setOf(
                    "dimension",
                    "kind",
                    "region",
                    "chunk",
                    "sectionY",
                    "min",
                    "max"
                )
            })
        )
        put("result", result)
    }

    internal fun pageOffset(name: String, arguments: JsonObject, total: Long): Long {
        val encoded = arguments["cursor"]?.jsonPrimitive?.content ?: return 0
        val cursor = EditorJson.format.decodeFromString<Cursor>(Base64.UrlSafe.decode(encoded).decodeToString())
        require(cursor.selection == selected?.selection && cursor.revision == revision) { "Cursor expired after a world switch or edit; restart the query" }
        require(cursor.query == query(name, arguments)) { "Cursor belongs to another query" }
        require(cursor.offset in 0..total) { "Cursor offset is outside the result" }
        return cursor.offset
    }

    internal fun pageSize(arguments: JsonObject, default: Int = 32): Int = arguments.int("pageSize", default).also {
        require(it in 1..1024) { "pageSize must be between 1 and 1024" }
    }

    internal fun page(
        name: String,
        arguments: JsonObject,
        items: List<JsonElement>,
        next: Long,
        total: Long
    ): JsonObject = buildJsonObject {
        put("items", JsonArray(items))
        put("total", total)
        put(
            "nextCursor", if (next < total) JsonPrimitive(
                Base64.UrlSafe.encode(
                    EditorJson.format.encodeToString(
                        Cursor(requireNotNull(selected).selection, revision, query(name, arguments), next),
                    ).encodeToByteArray()
                )
            ) else JsonNull
        )
    }

    internal fun paginate(name: String, arguments: JsonObject, items: List<JsonElement>): JsonObject {
        val offset = pageOffset(name, arguments, items.size.toLong()).toInt()
        val end = minOf(items.size, offset + pageSize(arguments))
        return page(name, arguments, items.subList(offset, end), end.toLong(), items.size.toLong())
    }

    private fun query(name: String, arguments: JsonObject): JsonObject = buildJsonObject {
        put("tool", name)
        put("arguments", JsonObject(arguments.filterKeys { it != "cursor" && it != "pageSize" }))
    }

    @Serializable
    private data class Cursor(val selection: Long, val revision: Long, val query: JsonObject, val offset: Long)
}

data class ToolReply(val value: JsonObject, val isError: Boolean)
